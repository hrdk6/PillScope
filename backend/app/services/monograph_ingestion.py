import hashlib
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Any

import httpx
import structlog
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.core.config import Settings
from backend.app.db.models import MonographChunk
from backend.app.services.drug_normalizer import normalize_canonical_salt
from backend.app.services.embeddings import EmbeddingProvider

logger = structlog.get_logger(__name__)

CLINICAL_SECTIONS: tuple[tuple[str, str, str, tuple[str, ...]], ...] = (
    (
        "drug_interactions",
        "34073-7",
        "DRUG INTERACTIONS",
        ("drug_interactions",),
    ),
    (
        "warnings",
        "43685-7",
        "WARNINGS AND PRECAUTIONS",
        ("boxed_warning", "warnings_and_cautions", "warnings_and_precautions", "warnings"),
    ),
    (
        "dosage_and_administration",
        "34068-7",
        "DOSAGE AND ADMINISTRATION",
        ("dosage_and_administration",),
    ),
    (
        "contraindications",
        "34070-3",
        "CONTRAINDICATIONS",
        ("contraindications",),
    ),
    (
        "precautions",
        "42232-9",
        "PRECAUTIONS",
        ("precautions",),
    ),
)

LOINC_TO_SECTION: dict[str, tuple[str, str]] = {
    "34073-7": ("drug_interactions", "DRUG INTERACTIONS"),
    "43685-7": ("warnings", "WARNINGS AND PRECAUTIONS"),
    "34071-1": ("warnings", "WARNINGS AND PRECAUTIONS"),
    "34066-1": ("warnings", "BOXED WARNING"),
    "34068-7": ("dosage_and_administration", "DOSAGE AND ADMINISTRATION"),
    "34070-3": ("contraindications", "CONTRAINDICATIONS"),
    "42232-9": ("precautions", "PRECAUTIONS"),
}

_WHITESPACE_RE = re.compile(r"\s+")
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+")
_OPENFDA_SALT_SPLIT_RE = re.compile(r"\s*(?:,|;|/|\band\b)\s*", re.IGNORECASE)


@dataclass(slots=True)
class PreparedMonographChunk:
    set_id: str
    document_version: str | None
    canonical_salt: str
    section_key: str
    loinc_section_code: str
    section_title: str
    chunk_index: int
    content: str
    content_sha256: str
    source_url: str
    chunk_metadata: dict[str, Any]


@dataclass(slots=True)
class MonographIngestionResult:
    drugs_requested: int = 0
    labels_processed: int = 0
    chunks_upserted: int = 0
    drugs_not_found: list[str] = field(default_factory=list)


def _clean_text(raw: str) -> str:
    return _WHITESPACE_RE.sub(" ", raw).strip()


def chunk_section_text(
    text: str,
    *,
    max_chars: int = 1200,
    overlap_chars: int = 180,
) -> list[str]:
    cleaned = _clean_text(text)
    if not cleaned:
        return []
    if len(cleaned) <= max_chars:
        return [cleaned]

    sentences = _SENTENCE_SPLIT_RE.split(cleaned)
    chunks: list[str] = []
    current_parts: list[str] = []
    current_len = 0

    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue

        if len(sentence) > max_chars:
            if current_parts:
                chunks.append(" ".join(current_parts))
                current_parts = []
                current_len = 0
            step = max(max_chars - overlap_chars, 200)
            for start in range(0, len(sentence), step):
                piece = sentence[start : start + max_chars].strip()
                if piece:
                    chunks.append(piece)
            continue

        added_len = len(sentence) + (1 if current_parts else 0)
        if current_len + added_len > max_chars and current_parts:
            finished = " ".join(current_parts)
            chunks.append(finished)

            overlap_parts: list[str] = []
            overlap_len = 0
            for prev in reversed(current_parts):
                if overlap_len + len(prev) > overlap_chars and overlap_parts:
                    break
                overlap_parts.insert(0, prev)
                overlap_len += len(prev) + 1
            current_parts = [*overlap_parts, sentence]
            current_len = sum(len(p) for p in current_parts) + max(len(current_parts) - 1, 0)
        else:
            current_parts.append(sentence)
            current_len += added_len

    if current_parts:
        chunks.append(" ".join(current_parts))

    return chunks


def _extract_canonical_salt_from_openfda(
    record: dict[str, Any],
    fallback_drug_name: str | None = None,
) -> str:
    openfda = record.get("openfda") or {}
    generic_names = openfda.get("generic_name") or openfda.get("substance_name") or []
    normalized_fallback = normalize_canonical_salt(fallback_drug_name) if fallback_drug_name else ""

    extracted: list[str] = []
    for raw_name in generic_names:
        if not isinstance(raw_name, str) or not raw_name.strip():
            continue
        for piece in _OPENFDA_SALT_SPLIT_RE.split(raw_name.strip()):
            normalized = normalize_canonical_salt(piece)
            if normalized and normalized not in extracted:
                extracted.append(normalized)

    if normalized_fallback and normalized_fallback in extracted:
        return normalized_fallback[:160]
    if extracted:
        return extracted[0][:160]
    if normalized_fallback:
        return normalized_fallback[:160]
    return ""


def parse_openfda_label_record(
    record: dict[str, Any],
    *,
    fallback_drug_name: str | None = None,
    max_chars: int = 1200,
    overlap_chars: int = 180,
) -> list[PreparedMonographChunk]:
    set_id = str(record.get("set_id") or record.get("id") or "").strip()[:128]
    canonical_salt = _extract_canonical_salt_from_openfda(record, fallback_drug_name)[:160]
    if not set_id or not canonical_salt:
        return []

    version = str(record.get("version") or "1")[:32]
    effective_time = str(record.get("effective_time") or "")
    openfda = record.get("openfda") or {}
    brand_names = [str(b) for b in (openfda.get("brand_name") or [])[:5]]
    source_url = (
        str(record.get("source_url"))
        if record.get("source_url")
        else f"https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid={set_id}"
    )

    prepared: list[PreparedMonographChunk] = []
    for section_key, loinc_code, section_title, field_names in CLINICAL_SECTIONS:
        parts: list[str] = []
        for field_name in field_names:
            raw_val = record.get(field_name)
            if isinstance(raw_val, list):
                parts.extend(str(item) for item in raw_val if item)
            elif isinstance(raw_val, str) and raw_val.strip():
                parts.append(raw_val)

        merged_text = "\n".join(parts).strip()
        if not merged_text:
            continue

        for chunk_idx, chunk_text in enumerate(
            chunk_section_text(merged_text, max_chars=max_chars, overlap_chars=overlap_chars)
        ):
            sha = hashlib.sha256(chunk_text.encode("utf-8")).hexdigest()
            prepared.append(
                PreparedMonographChunk(
                    set_id=set_id,
                    document_version=version,
                    canonical_salt=canonical_salt,
                    section_key=section_key,
                    loinc_section_code=loinc_code,
                    section_title=section_title,
                    chunk_index=chunk_idx,
                    content=chunk_text,
                    content_sha256=sha,
                    source_url=source_url,
                    chunk_metadata={
                        "section_key": section_key,
                        "source": "openfda",
                        "brand_names": brand_names,
                        "effective_time": effective_time,
                    },
                )
            )

    return prepared


def _collect_spl_section_texts(section: ET.Element, ns: dict[str, str]) -> list[str]:
    texts: list[str] = []
    direct_text = section.find("hl7:text", ns)
    if direct_text is not None:
        raw = _clean_text(" ".join(direct_text.itertext()))
        if raw:
            texts.append(raw)

    for comp in section.findall("hl7:component", ns):
        child_section = comp.find("hl7:section", ns)
        if child_section is None:
            continue
        child_code_el = child_section.find("hl7:code", ns)
        child_code = (
            (child_code_el.attrib.get("code") or "").strip() if child_code_el is not None else ""
        )
        if child_code in LOINC_TO_SECTION:
            continue
        title_el = child_section.find("hl7:title", ns)
        title_text = _clean_text(" ".join(title_el.itertext())) if title_el is not None else ""
        sub_texts = _collect_spl_section_texts(child_section, ns)
        if sub_texts:
            if title_text:
                texts.append(f"{title_text}: {' '.join(sub_texts)}")
            else:
                texts.extend(sub_texts)
    return texts


def parse_dailymed_spl_xml(
    xml_bytes: bytes,
    *,
    canonical_salt: str,
    set_id: str | None = None,
    max_chars: int = 1200,
    overlap_chars: int = 180,
) -> list[PreparedMonographChunk]:
    normalized_salt = normalize_canonical_salt(canonical_salt)[:160]
    if not normalized_salt:
        return []

    root = ET.fromstring(xml_bytes)
    ns = {"hl7": "urn:hl7-org:v3"}

    set_id_el = root.find("hl7:setId", ns)
    resolved_set_id = (
        set_id or (set_id_el.attrib.get("root") if set_id_el is not None else None) or ""
    ).strip()[:128]
    if not resolved_set_id:
        return []

    version_el = root.find("hl7:versionNumber", ns)
    version = (version_el.attrib.get("value") if version_el is not None else "1") or "1"
    version = version[:32]
    source_url = f"https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid={resolved_set_id}"

    section_buckets: dict[str, list[str]] = {}
    section_meta: dict[str, tuple[str, str]] = {}

    for section in root.findall(".//hl7:section", ns):
        code_el = section.find("hl7:code", ns)
        if code_el is None:
            continue
        code = (code_el.attrib.get("code") or "").strip()
        if code not in LOINC_TO_SECTION:
            continue

        section_key, canonical_title = LOINC_TO_SECTION[code]
        normalized_loinc = "43685-7" if section_key == "warnings" else code
        extracted_texts = _collect_spl_section_texts(section, ns)
        if not extracted_texts:
            continue
        section_buckets.setdefault(normalized_loinc, []).extend(extracted_texts)
        section_meta[normalized_loinc] = (section_key, canonical_title)

    prepared: list[PreparedMonographChunk] = []
    for loinc_code, text_parts in section_buckets.items():
        section_key, section_title = section_meta[loinc_code]
        merged = "\n".join(text_parts)
        for chunk_idx, chunk_text in enumerate(
            chunk_section_text(merged, max_chars=max_chars, overlap_chars=overlap_chars)
        ):
            sha = hashlib.sha256(chunk_text.encode("utf-8")).hexdigest()
            prepared.append(
                PreparedMonographChunk(
                    set_id=resolved_set_id,
                    document_version=version,
                    canonical_salt=normalized_salt,
                    section_key=section_key,
                    loinc_section_code=loinc_code,
                    section_title=section_title,
                    chunk_index=chunk_idx,
                    content=chunk_text,
                    content_sha256=sha,
                    source_url=source_url,
                    chunk_metadata={
                        "section_key": section_key,
                        "source": "dailymed_spl",
                    },
                )
            )
    return prepared


def _score_openfda_candidate(record: dict[str, Any], canonical: str) -> tuple[int, int, int]:
    has_ddi = 1 if record.get("drug_interactions") else 0
    section_count = sum(
        1 for _, _, _, field_names in CLINICAL_SECTIONS if any(record.get(fn) for fn in field_names)
    )
    openfda = record.get("openfda") or {}
    generics = [normalize_canonical_salt(str(g)) for g in (openfda.get("generic_name") or []) if g]
    exact_single = 1 if canonical in generics else 0
    return (has_ddi, exact_single, section_count)


async def fetch_openfda_label(
    client: httpx.AsyncClient,
    drug_name: str,
    settings: Settings,
) -> dict[str, Any] | None:
    canonical = normalize_canonical_salt(drug_name)
    if not canonical:
        return None

    queries = (
        f'openfda.generic_name:"{canonical}"',
        f'openfda.substance_name:"{canonical}"',
    )
    for query in queries:
        params: dict[str, str | int] = {"search": query, "limit": 5}
        if settings.openfda_api_key:
            params["api_key"] = settings.openfda_api_key

        response = await client.get(settings.openfda_base_url, params=params)
        if response.status_code == 404:
            continue
        response.raise_for_status()
        results = [dict(r) for r in (response.json().get("results") or [])]
        if results:
            results.sort(
                key=lambda rec: _score_openfda_candidate(rec, canonical),
                reverse=True,
            )
            return results[0]
    return None


async def fetch_dailymed_spl(
    client: httpx.AsyncClient,
    drug_name: str,
    settings: Settings,
) -> tuple[str, bytes] | None:
    canonical = normalize_canonical_salt(drug_name)
    if not canonical:
        return None
    list_url = f"{settings.dailymed_base_url.rstrip('/')}/spls.json"
    list_res = await client.get(list_url, params={"drug_name": canonical, "pagesize": 1})
    if list_res.status_code == 404:
        return None
    list_res.raise_for_status()
    items = list_res.json().get("data") or []
    if not items:
        return None
    set_id = str(items[0].get("setid") or "").strip()
    if not set_id:
        return None

    xml_url = f"{settings.dailymed_base_url.rstrip('/')}/spls/{set_id}.xml"
    xml_res = await client.get(xml_url)
    if xml_res.status_code == 404:
        return None
    xml_res.raise_for_status()
    return set_id, xml_res.content


async def upsert_monograph_chunks(
    session: AsyncSession,
    chunks: list[PreparedMonographChunk],
    embedding_provider: EmbeddingProvider,
    *,
    batch_size: int = 32,
) -> int:
    if not chunks:
        return 0

    deduped: dict[tuple[str, str, int], PreparedMonographChunk] = {
        (c.set_id, c.loinc_section_code, c.chunk_index): c for c in chunks
    }
    ordered = list(deduped.values())
    total_upserted = 0

    for start in range(0, len(ordered), batch_size):
        batch = ordered[start : start + batch_size]
        texts = [f"{item.canonical_salt} | {item.section_title} | {item.content}" for item in batch]
        embeddings = await embedding_provider.embed_texts(texts)

        values: list[dict[str, Any]] = []
        for item, vector in zip(batch, embeddings, strict=True):
            values.append(
                {
                    "set_id": item.set_id,
                    "document_version": item.document_version,
                    "canonical_salt": item.canonical_salt,
                    "loinc_section_code": item.loinc_section_code,
                    "section_title": item.section_title,
                    "chunk_index": item.chunk_index,
                    "content": item.content,
                    "content_sha256": item.content_sha256,
                    "source_url": item.source_url,
                    "embedding": vector,
                    "metadata": item.chunk_metadata,
                }
            )

        stmt = pg_insert(MonographChunk).values(values)
        stmt = stmt.on_conflict_do_update(
            constraint="uq_monograph_chunks_section_chunk",
            set_={
                "document_version": stmt.excluded.document_version,
                "canonical_salt": stmt.excluded.canonical_salt,
                "section_title": stmt.excluded.section_title,
                "content": stmt.excluded.content,
                "content_sha256": stmt.excluded.content_sha256,
                "source_url": stmt.excluded.source_url,
                "embedding": stmt.excluded.embedding,
                "metadata": stmt.excluded.metadata,
            },
        )
        await session.execute(stmt)
        await session.commit()
        total_upserted += len(values)

    return total_upserted


async def ingest_drug_monographs(
    session: AsyncSession,
    drug_names: list[str],
    *,
    settings: Settings,
    embedding_provider: EmbeddingProvider,
    source: str = "auto",
    http_client: httpx.AsyncClient | None = None,
) -> MonographIngestionResult:
    result = MonographIngestionResult(drugs_requested=len(drug_names))

    async def _process_with_client(client: httpx.AsyncClient) -> None:
        for drug_name in drug_names:
            chunks: list[PreparedMonographChunk] = []
            if source in {"openfda", "auto"}:
                record = await fetch_openfda_label(client, drug_name, settings)
                if record is not None:
                    chunks = parse_openfda_label_record(record, fallback_drug_name=drug_name)

            if not chunks and source in {"dailymed", "auto"}:
                spl = await fetch_dailymed_spl(client, drug_name, settings)
                if spl is not None:
                    set_id, xml_bytes = spl
                    chunks = parse_dailymed_spl_xml(
                        xml_bytes,
                        canonical_salt=drug_name,
                        set_id=set_id,
                    )

            if not chunks:
                result.drugs_not_found.append(drug_name)
                continue

            result.labels_processed += 1
            result.chunks_upserted += await upsert_monograph_chunks(
                session=session,
                chunks=chunks,
                embedding_provider=embedding_provider,
            )

    if http_client is not None:
        await _process_with_client(http_client)
    else:
        async with httpx.AsyncClient(timeout=30.0, follow_redirects=True) as client:
            await _process_with_client(client)

    logger.info(
        "monograph_ingestion_complete",
        drugs_requested=result.drugs_requested,
        labels_processed=result.labels_processed,
        chunks_upserted=result.chunks_upserted,
        not_found=len(result.drugs_not_found),
    )
    return result
