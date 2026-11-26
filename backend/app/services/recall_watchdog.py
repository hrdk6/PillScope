import asyncio
import json
import re
import zipfile
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import quote
from uuid import UUID

import httpx
import structlog
from sqlalchemy import or_, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.core.config import Settings
from backend.app.db.models import Medication, Profile, RecallAlert
from backend.app.schemas.recalls import (
    MedicationRecallFlag,
    ProfileRecallAlertItem,
    ProfileRecallsResponse,
    RecallClassification,
    RecallMatchBasis,
)
from backend.app.services.drug_normalizer import (
    normalize_brand_name,
    normalize_canonical_salt,
    strip_dosage_form_suffix,
)

logger = structlog.get_logger(__name__)

ACTIVE_RECALL_STATUSES: frozenset[str] = frozenset({"Ongoing", "Pending"})
HIGH_PRIORITY_CLASSIFICATIONS: frozenset[str] = frozenset({"Class I", "Class II"})
_VALID_CLASSIFICATIONS: frozenset[str] = frozenset({"Class I", "Class II", "Class III"})

_SALT_SPLIT_RE = re.compile(r"\s*(?:,|;|/|\+|\band\b)\s*", re.IGNORECASE)
_PAREN_GENERIC_RE = re.compile(r"\(([^()]{3,120})\)")
_LEADING_PRODUCT_RE = re.compile(
    r"^\s*([A-Za-z][A-Za-z0-9\-\s]{2,80}?)(?:\s*\(|\s*,|\s+\d|\s+(?:tablets?|capsules?|usp|"
    r"oral|solution|suspension|injection|cream|ointment|gel|syrup|drops|extended|delayed)\b)",
    re.IGNORECASE,
)
_STRENGTH_TOKEN_RE = re.compile(
    r"\b\d+(?:\.\d+)?\s*(?:mg|mcg|ug|g|gm|ml|iu|%)\b|\b\d+\b",
    re.IGNORECASE,
)
_GENERIC_BRAND_STOPWORDS: frozenset[str] = frozenset(
    {
        "tablet",
        "tablets",
        "capsule",
        "capsules",
        "oral",
        "solution",
        "suspension",
        "injection",
        "syrup",
        "cream",
        "ointment",
        "usp",
        "rx",
        "only",
        "bottle",
        "bottles",
        "pack",
        "strip",
        "ndc",
        "extended",
        "release",
        "delayed",
        "sodium",
        "potassium",
        "hydrochloride",
    }
)


@dataclass(slots=True)
class PreparedRecallRecord:
    recall_number: str
    event_id: str | None
    status: str
    classification: str
    recalling_firm: str | None
    product_description: str
    reason_for_recall: str
    distribution_pattern: str | None
    code_info: str | None
    voluntary_mandated: str | None
    report_date: str | None
    recall_initiation_date: str | None
    termination_date: str | None
    brand_names: list[str]
    generic_names: list[str]
    canonical_salt_keys: list[str]
    raw_payload: dict[str, Any]
    source_url: str


@dataclass(slots=True)
class RecallIngestionResult:
    pages_fetched: int = 0
    records_read: int = 0
    records_upserted: int = 0
    active_class_1_or_2_upserted: int = 0
    rate_limited: bool = False
    warnings: list[str] = field(default_factory=list)


def _brand_variants(raw_brand: str) -> list[str]:
    norm = normalize_brand_name(raw_brand)
    if not norm:
        return []
    stripped_form = strip_dosage_form_suffix(norm)
    stripped_strength = " ".join(_STRENGTH_TOKEN_RE.sub(" ", stripped_form).split())
    stripped_strength = strip_dosage_form_suffix(stripped_strength)

    variants: list[str] = []
    for candidate in (norm, stripped_form, stripped_strength):
        cleaned = candidate.strip()
        if (
            cleaned
            and len(cleaned) >= 3
            and cleaned not in _GENERIC_BRAND_STOPWORDS
            and cleaned not in variants
        ):
            variants.append(cleaned)
    return variants


def _extract_salts_from_text(raw_text: str) -> list[str]:
    salts: list[str] = []
    for piece in _SALT_SPLIT_RE.split(raw_text):
        canonical = normalize_canonical_salt(piece)[:160]
        if (
            canonical
            and len(canonical) >= 3
            and canonical not in _GENERIC_BRAND_STOPWORDS
            and canonical not in salts
        ):
            salts.append(canonical)
    return salts


def parse_openfda_enforcement_record(
    record: dict[str, Any],
    *,
    enforcement_base_url: str = "https://api.fda.gov/drug/enforcement.json",
) -> PreparedRecallRecord | None:
    recall_number = str(record.get("recall_number") or "").strip()[:64]
    classification = str(record.get("classification") or "").strip()[:16]
    status = str(record.get("status") or "").strip()[:32]
    product_description = str(record.get("product_description") or "").strip()
    reason_for_recall = str(record.get("reason_for_recall") or "").strip()

    if not recall_number or classification not in _VALID_CLASSIFICATIONS or not status:
        return None
    if not product_description or not reason_for_recall:
        return None

    raw_openfda = record.get("openfda")
    openfda: dict[str, Any] = raw_openfda if isinstance(raw_openfda, dict) else {}
    raw_generics: list[str] = []
    for field_name in ("generic_name", "substance_name"):
        vals = openfda.get(field_name) or []
        if isinstance(vals, list):
            for val in vals:
                if isinstance(val, str) and val.strip():
                    raw_generics.append(val.strip())

    raw_brands: list[str] = []
    brand_vals = openfda.get("brand_name") or []
    if isinstance(brand_vals, list):
        for val in brand_vals:
            if isinstance(val, str) and val.strip():
                raw_brands.append(val.strip())

    canonical_salts: list[str] = []
    for generic in raw_generics:
        for salt in _extract_salts_from_text(generic):
            if salt not in canonical_salts:
                canonical_salts.append(salt)

    brand_keys: list[str] = []
    for brand in raw_brands:
        for variant in _brand_variants(brand):
            if variant not in brand_keys:
                brand_keys.append(variant)

    if not canonical_salts:
        for paren_match in _PAREN_GENERIC_RE.findall(product_description):
            for salt in _extract_salts_from_text(paren_match):
                if salt not in canonical_salts:
                    canonical_salts.append(salt)

    if not canonical_salts or not brand_keys:
        leading = _LEADING_PRODUCT_RE.match(product_description)
        if leading:
            lead_phrase = leading.group(1).strip()
            if not brand_keys:
                for variant in _brand_variants(lead_phrase):
                    if variant not in brand_keys:
                        brand_keys.append(variant)
            if not canonical_salts:
                for salt in _extract_salts_from_text(lead_phrase):
                    if salt not in canonical_salts:
                        canonical_salts.append(salt)

    canonical_salts.sort()
    brand_keys.sort()
    dedup_generics = sorted({g.strip() for g in raw_generics if g.strip()})

    encoded_num = quote(f'recall_number:"{recall_number}"', safe="")
    source_url = f"{enforcement_base_url}?search={encoded_num}"

    return PreparedRecallRecord(
        recall_number=recall_number,
        event_id=str(record.get("event_id") or "").strip()[:64] or None,
        status=status,
        classification=classification,
        recalling_firm=str(record.get("recalling_firm") or "").strip()[:255] or None,
        product_description=product_description,
        reason_for_recall=reason_for_recall,
        distribution_pattern=str(record.get("distribution_pattern") or "").strip() or None,
        code_info=str(record.get("code_info") or "").strip() or None,
        voluntary_mandated=str(record.get("voluntary_mandated") or "").strip()[:128] or None,
        report_date=str(record.get("report_date") or "").strip()[:16] or None,
        recall_initiation_date=str(record.get("recall_initiation_date") or "").strip()[:16] or None,
        termination_date=str(record.get("termination_date") or "").strip()[:16] or None,
        brand_names=brand_keys,
        generic_names=dedup_generics,
        canonical_salt_keys=canonical_salts,
        raw_payload=dict(record),
        source_url=source_url,
    )


async def upsert_recall_records(
    session: AsyncSession,
    records: Sequence[PreparedRecallRecord],
    *,
    batch_size: int = 100,
) -> tuple[int, int]:
    if not records:
        return 0, 0

    deduped: dict[str, PreparedRecallRecord] = {rec.recall_number: rec for rec in records}
    ordered = list(deduped.values())
    total_upserted = 0
    active_priority = 0

    for start in range(0, len(ordered), batch_size):
        batch = ordered[start : start + batch_size]
        values: list[dict[str, Any]] = []
        for item in batch:
            if (
                item.status in ACTIVE_RECALL_STATUSES
                and item.classification in HIGH_PRIORITY_CLASSIFICATIONS
                and not item.termination_date
            ):
                active_priority += 1
            values.append(
                {
                    "recall_number": item.recall_number,
                    "event_id": item.event_id,
                    "status": item.status,
                    "classification": item.classification,
                    "recalling_firm": item.recalling_firm,
                    "product_description": item.product_description,
                    "reason_for_recall": item.reason_for_recall,
                    "distribution_pattern": item.distribution_pattern,
                    "code_info": item.code_info,
                    "voluntary_mandated": item.voluntary_mandated,
                    "report_date": item.report_date,
                    "recall_initiation_date": item.recall_initiation_date,
                    "termination_date": item.termination_date,
                    "brand_names": item.brand_names,
                    "generic_names": item.generic_names,
                    "canonical_salt_keys": item.canonical_salt_keys,
                    "raw_payload": item.raw_payload,
                    "source_url": item.source_url,
                }
            )

        stmt = pg_insert(RecallAlert).values(values)
        stmt = stmt.on_conflict_do_update(
            constraint="uq_recall_alerts_recall_number",
            set_={
                "event_id": stmt.excluded.event_id,
                "status": stmt.excluded.status,
                "classification": stmt.excluded.classification,
                "recalling_firm": stmt.excluded.recalling_firm,
                "product_description": stmt.excluded.product_description,
                "reason_for_recall": stmt.excluded.reason_for_recall,
                "distribution_pattern": stmt.excluded.distribution_pattern,
                "code_info": stmt.excluded.code_info,
                "voluntary_mandated": stmt.excluded.voluntary_mandated,
                "report_date": stmt.excluded.report_date,
                "recall_initiation_date": stmt.excluded.recall_initiation_date,
                "termination_date": stmt.excluded.termination_date,
                "brand_names": stmt.excluded.brand_names,
                "generic_names": stmt.excluded.generic_names,
                "canonical_salt_keys": stmt.excluded.canonical_salt_keys,
                "raw_payload": stmt.excluded.raw_payload,
                "source_url": stmt.excluded.source_url,
            },
        )
        await session.execute(stmt)
        await session.commit()
        total_upserted += len(values)

    return total_upserted, active_priority


def extract_records_from_bulk_payload(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, dict) and isinstance(payload.get("results"), list):
        return [dict(item) for item in payload["results"] if isinstance(item, dict)]
    if isinstance(payload, list):
        return [dict(item) for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict) and "recall_number" in payload:
        return [dict(payload)]
    raise ValueError("Unsupported openFDA enforcement bulk JSON structure.")


def _load_bulk_json_file(json_path: Path) -> list[dict[str, Any]]:
    if json_path.suffix.lower() == ".zip":
        all_records: list[dict[str, Any]] = []
        with zipfile.ZipFile(json_path, "r") as archive:
            for member in sorted(archive.namelist()):
                if not member.lower().endswith(".json"):
                    continue
                with archive.open(member, "r") as handle:
                    payload = json.loads(handle.read().decode("utf-8"))
                    all_records.extend(extract_records_from_bulk_payload(payload))
        return all_records
    payload = json.loads(json_path.read_text(encoding="utf-8"))
    return extract_records_from_bulk_payload(payload)


async def ingest_recalls_from_payload(
    session: AsyncSession,
    payload: Any,
    *,
    enforcement_base_url: str = "https://api.fda.gov/drug/enforcement.json",
) -> RecallIngestionResult:
    raw_records = extract_records_from_bulk_payload(payload)
    prepared: list[PreparedRecallRecord] = []
    for raw in raw_records:
        parsed = parse_openfda_enforcement_record(raw, enforcement_base_url=enforcement_base_url)
        if parsed is not None:
            prepared.append(parsed)

    upserted, active_priority = await upsert_recall_records(session, prepared)
    return RecallIngestionResult(
        pages_fetched=1,
        records_read=len(raw_records),
        records_upserted=upserted,
        active_class_1_or_2_upserted=active_priority,
    )


async def ingest_recalls_from_file(
    session: AsyncSession,
    json_path: Path,
    *,
    enforcement_base_url: str = "https://api.fda.gov/drug/enforcement.json",
) -> RecallIngestionResult:
    raw_records = await asyncio.to_thread(_load_bulk_json_file, json_path)
    prepared: list[PreparedRecallRecord] = []
    for raw in raw_records:
        parsed = parse_openfda_enforcement_record(raw, enforcement_base_url=enforcement_base_url)
        if parsed is not None:
            prepared.append(parsed)

    upserted, active_priority = await upsert_recall_records(session, prepared)
    logger.info(
        "fda_recall_bulk_ingestion_complete",
        path=str(json_path),
        records_read=len(raw_records),
        records_upserted=upserted,
        active_class_1_or_2_upserted=active_priority,
    )
    return RecallIngestionResult(
        pages_fetched=1,
        records_read=len(raw_records),
        records_upserted=upserted,
        active_class_1_or_2_upserted=active_priority,
    )


def _compute_retry_delay(
    response: httpx.Response,
    attempt: int,
    base_backoff_seconds: float,
    max_backoff_seconds: float,
) -> float:
    retry_after = response.headers.get("Retry-After")
    if retry_after:
        try:
            parsed = float(retry_after.strip())
            if parsed >= 0:
                return min(parsed, max_backoff_seconds)
        except ValueError:
            pass
    computed_backoff: float = base_backoff_seconds * float(2**attempt)
    return min(computed_backoff, max_backoff_seconds)


async def sync_openfda_recalls(
    session: AsyncSession,
    *,
    settings: Settings,
    max_pages: int = 5,
    page_size: int = 100,
    status_filter: str = "Ongoing",
    http_client: httpx.AsyncClient | None = None,
    max_retries: int = 3,
    base_backoff_seconds: float = 1.0,
    max_backoff_seconds: float = 30.0,
    sleep_fn: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> RecallIngestionResult:
    result = RecallIngestionResult()
    search_query = (
        f'status:"{status_filter}" AND (classification:"Class I" OR classification:"Class II")'
    )

    async def _run(client: httpx.AsyncClient) -> None:
        skip = 0
        for _ in range(max_pages):
            params: dict[str, str | int] = {
                "search": search_query,
                "limit": page_size,
                "skip": skip,
            }
            if settings.openfda_api_key:
                params["api_key"] = settings.openfda_api_key

            response: httpx.Response | None = None
            for attempt in range(max_retries + 1):
                response = await client.get(settings.openfda_enforcement_url, params=params)
                if response.status_code in {429, 502, 503, 504}:
                    if attempt < max_retries:
                        delay = _compute_retry_delay(
                            response,
                            attempt,
                            base_backoff_seconds,
                            max_backoff_seconds,
                        )
                        logger.warning(
                            "openfda_recall_retry",
                            status_code=response.status_code,
                            attempt=attempt + 1,
                            delay_seconds=delay,
                        )
                        await sleep_fn(delay)
                        continue
                    result.rate_limited = response.status_code == 429
                    result.warnings.append(
                        f"openFDA enforcement endpoint returned HTTP {response.status_code} "
                        f"after {max_retries + 1} attempts at skip={skip}."
                    )
                    logger.warning(
                        "openfda_recall_rate_limited",
                        status_code=response.status_code,
                        skip=skip,
                    )
                    return
                break

            if response is None:
                return
            if response.status_code == 404:
                return
            response.raise_for_status()

            payload = response.json()
            raw_results = [dict(r) for r in (payload.get("results") or []) if isinstance(r, dict)]
            result.pages_fetched += 1
            if not raw_results:
                return

            result.records_read += len(raw_results)
            prepared: list[PreparedRecallRecord] = []
            for raw in raw_results:
                parsed = parse_openfda_enforcement_record(
                    raw, enforcement_base_url=settings.openfda_enforcement_url
                )
                if parsed is not None:
                    prepared.append(parsed)

            upserted, active_priority = await upsert_recall_records(session, prepared)
            result.records_upserted += upserted
            result.active_class_1_or_2_upserted += active_priority

            total_available = int(
                ((payload.get("meta") or {}).get("results") or {}).get("total") or 0
            )
            skip += len(raw_results)
            if len(raw_results) < page_size or (total_available > 0 and skip >= total_available):
                return

    if http_client is not None:
        await _run(http_client)
    else:
        async with httpx.AsyncClient(timeout=30.0, follow_redirects=True) as client:
            await _run(client)

    logger.info(
        "fda_recall_sync_complete",
        pages_fetched=result.pages_fetched,
        records_read=result.records_read,
        records_upserted=result.records_upserted,
        rate_limited=result.rate_limited,
    )
    return result


def _match_medication_to_recall(
    medication: Medication,
    recall: RecallAlert,
) -> tuple[RecallMatchBasis, list[str]] | None:
    med_salts = set(medication.canonical_salt_keys)
    recall_salts = set(recall.canonical_salt_keys)
    salt_overlap = sorted(med_salts & recall_salts)

    med_brand_variants = set(_brand_variants(medication.display_name))
    recall_brands = set(recall.brand_names)
    brand_overlap = sorted(med_brand_variants & recall_brands)

    if salt_overlap and brand_overlap:
        return "BRAND_AND_SALT", sorted(set(salt_overlap) | set(brand_overlap))
    if salt_overlap:
        return "GENERIC_SALT", salt_overlap
    if brand_overlap:
        return "BRAND_NAME", brand_overlap
    return None


def _as_priority_classification(raw: str) -> RecallClassification:
    return "Class I" if raw == "Class I" else "Class II"


async def match_active_recalls_for_medications(
    session: AsyncSession,
    medications: Sequence[Medication],
) -> tuple[dict[UUID, list[MedicationRecallFlag]], list[ProfileRecallAlertItem]]:
    if not medications:
        return {}, []

    all_salts = sorted({salt for med in medications for salt in med.canonical_salt_keys})
    all_brands = sorted(
        {variant for med in medications for variant in _brand_variants(med.display_name)}
    )

    conditions = []
    if all_salts:
        conditions.append(RecallAlert.canonical_salt_keys.overlap(all_salts))
    if all_brands:
        conditions.append(RecallAlert.brand_names.overlap(all_brands))
    if not conditions:
        return {med.id: [] for med in medications}, []

    stmt = (
        select(RecallAlert)
        .where(
            RecallAlert.status.in_(sorted(ACTIVE_RECALL_STATUSES)),
            RecallAlert.classification.in_(sorted(HIGH_PRIORITY_CLASSIFICATIONS)),
            or_(RecallAlert.termination_date.is_(None), RecallAlert.termination_date == ""),
            or_(*conditions),
        )
        .order_by(RecallAlert.classification.asc(), RecallAlert.report_date.desc())
    )
    recalls = list((await session.execute(stmt)).scalars().all())

    flags_by_med: dict[UUID, list[MedicationRecallFlag]] = {med.id: [] for med in medications}
    profile_items: list[ProfileRecallAlertItem] = []

    for med in medications:
        for recall in recalls:
            matched = _match_medication_to_recall(med, recall)
            if matched is None:
                continue
            basis, terms = matched
            classification = _as_priority_classification(recall.classification)
            flags_by_med[med.id].append(
                MedicationRecallFlag(
                    alert_label="US FDA Recall Alert",
                    recall_id=recall.id,
                    recall_number=recall.recall_number,
                    classification=classification,
                    status=recall.status,
                    recalling_firm=recall.recalling_firm,
                    reason_for_recall=recall.reason_for_recall,
                    report_date=recall.report_date,
                    match_basis=basis,
                    matched_terms=terms,
                    source_url=recall.source_url,
                )
            )
            profile_items.append(
                ProfileRecallAlertItem(
                    alert_label="US FDA Recall Alert",
                    jurisdiction="US_FDA",
                    recall_id=recall.id,
                    recall_number=recall.recall_number,
                    event_id=recall.event_id,
                    classification=classification,
                    status=recall.status,
                    recalling_firm=recall.recalling_firm,
                    product_description=recall.product_description,
                    reason_for_recall=recall.reason_for_recall,
                    distribution_pattern=recall.distribution_pattern,
                    code_info=recall.code_info,
                    report_date=recall.report_date,
                    recall_initiation_date=recall.recall_initiation_date,
                    source_url=recall.source_url,
                    matched_medication_id=med.id,
                    matched_medication_name=med.display_name,
                    match_basis=basis,
                    matched_terms=terms,
                )
            )

    profile_items.sort(
        key=lambda item: (
            0 if item.classification == "Class I" else 1,
            item.matched_medication_name.lower(),
            item.recall_number,
        )
    )
    return flags_by_med, profile_items


async def get_profile_recalls(
    session: AsyncSession,
    profile: Profile,
    *,
    active_only: bool = True,
) -> ProfileRecallsResponse:
    stmt = select(Medication).where(Medication.profile_id == profile.id)
    if active_only:
        stmt = stmt.where(Medication.is_active.is_(True))
    stmt = stmt.order_by(Medication.created_at.desc())

    meds = list((await session.execute(stmt)).scalars().all())
    _, items = await match_active_recalls_for_medications(session, meds)
    return ProfileRecallsResponse(
        profile_id=profile.id,
        profile_name=profile.name,
        alert_label="US FDA Recall Alert",
        has_active_recalls=len(items) > 0,
        total_matches=len(items),
        recalls=items,
    )
