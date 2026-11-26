import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Protocol

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.db.models import MonographChunk
from backend.app.schemas.lifestyle import (
    LifestyleTrigger,
    LifestyleTriggerSeverity,
    LifestyleTriggerType,
)
from backend.app.services.drug_normalizer import canonical_salt_keys

_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+|\n+")
_WHITESPACE_RE = re.compile(r"\s+")

_TARGET_SECTIONS: dict[str, str] = {
    "warnings": "warnings",
    "warnings_and_precautions": "warnings",
    "warnings_and_cautions": "warnings",
    "boxed_warning": "warnings",
    "drug_interactions": "drug_interactions",
    "dosage_and_administration": "dosage_and_administration",
    "precautions": "precautions",
}

_TARGET_LOINC_SECTIONS: dict[str, str] = {
    "43685-7": "warnings",
    "34071-1": "warnings",
    "34066-1": "warnings",
    "34073-7": "drug_interactions",
    "34068-7": "dosage_and_administration",
    "42232-9": "precautions",
}

_SEVERITY_RANK: dict[LifestyleTriggerSeverity, int] = {
    "INFO": 1,
    "MODERATE": 2,
    "HIGH": 3,
}

_SECTION_PRIORITY: dict[str, int] = {
    "warnings": 4,
    "drug_interactions": 3,
    "dosage_and_administration": 2,
    "precautions": 1,
}


class MonographChunkLike(Protocol):
    canonical_salt: str
    loinc_section_code: str
    section_title: str
    content: str
    chunk_metadata: dict[str, object]


@dataclass(frozen=True, slots=True)
class _TriggerRule:
    trigger_type: LifestyleTriggerType
    include_patterns: tuple[re.Pattern[str], ...]
    exclude_patterns: tuple[re.Pattern[str], ...]
    high_severity_patterns: tuple[re.Pattern[str], ...]
    info_severity_patterns: tuple[re.Pattern[str], ...]
    allowed_sections: frozenset[str]
    fallback_note: str


_ALL_TARGET_SECTIONS = frozenset(
    {"warnings", "drug_interactions", "dosage_and_administration", "precautions"}
)

_TRIGGER_RULES: tuple[_TriggerRule, ...] = (
    _TriggerRule(
        trigger_type="ALCOHOL",
        include_patterns=(
            re.compile(
                r"\b(?:alcohol|alcoholic\s+beverages?|ethanol)\b",
                re.IGNORECASE,
            ),
            re.compile(r"\bdisulfiram[- ]like\s+reaction\b", re.IGNORECASE),
        ),
        exclude_patterns=(
            re.compile(
                r"\b(?:benzyl|cetyl|stearyl|polyvinyl|isopropyl|sugar|fatty|wood)\s+alcohol\b",
                re.IGNORECASE,
            ),
            re.compile(r"\balcohol[- ]free\b", re.IGNORECASE),
            re.compile(
                r"\bno\s+(?:clinically\s+)?(?:significant\s+)?(?:interaction|effect)s?\s+(?:with|of)\s+(?:alcohol|ethanol)\b",
                re.IGNORECASE,
            ),
            re.compile(r"\bdoes\s+not\s+interact\s+with\s+(?:alcohol|ethanol)\b", re.IGNORECASE),
        ),
        high_severity_patterns=(
            re.compile(
                r"\b(?:avoid|contraindicated|do\s+not\s+(?:consume|drink|use)|fatal|severe|serious|"
                r"bleeding|hemorrhage|hepatotox|liver\s+(?:damage|injury|failure)|"
                r"respiratory\s+depression|disulfiram|lactic\s+acidosis|cns\s+depression|sedation)\b",
                re.IGNORECASE,
            ),
        ),
        info_severity_patterns=(),
        allowed_sections=_ALL_TARGET_SECTIONS,
        fallback_note="Avoid or limit alcohol due to clinically significant interaction risk.",
    ),
    _TriggerRule(
        trigger_type="GRAPEFRUIT",
        include_patterns=(
            re.compile(r"\bgrapefruit(?:\s+juice|\s+products?)?\b", re.IGNORECASE),
        ),
        exclude_patterns=(
            re.compile(
                r"\bno\s+(?:clinically\s+)?(?:significant\s+)?(?:interaction|effect)s?\s+(?:with|of)\s+grapefruit\b",
                re.IGNORECASE,
            ),
        ),
        high_severity_patterns=(
            re.compile(
                r"\b(?:avoid|contraindicated|do\s+not|toxic|toxicity|myopathy|rhabdomyolysis|"
                r"severe|serious|markedly\s+increase|substantially\s+increase)\b",
                re.IGNORECASE,
            ),
        ),
        info_severity_patterns=(),
        allowed_sections=_ALL_TARGET_SECTIONS,
        fallback_note="Avoid grapefruit and grapefruit juice while taking this medication.",
    ),
    _TriggerRule(
        trigger_type="DAIRY",
        include_patterns=(
            re.compile(
                r"\b(?:dairy\s+products?|milk|yogurt|cheese|calcium[- ]fortified\s+juices?)\b"
                r".{0,120}\b(?:avoid|separate|hour|decrease|reduce|interfere|impair|chelat|"
                r"absorption|bioavailability|do\s+not\s+take|alone)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\b(?:avoid|separate|do\s+not\s+take|decrease|reduce|interfere|impair|chelat|"
                r"absorption)\b.{0,120}\b(?:dairy\s+products?|milk|yogurt|cheese|"
                r"calcium[- ]fortified\s+juices?)\b",
                re.IGNORECASE,
            ),
        ),
        exclude_patterns=(
            re.compile(r"\b(?:breast\s*milk|human\s+milk|lactation|nursing\s+mothers?)\b", re.I),
            re.compile(r"\bwith\s+food\s+or\s+milk\b", re.IGNORECASE),
            re.compile(
                r"\bno\s+(?:clinically\s+)?(?:significant\s+)?(?:interaction|effect)s?\s+(?:with|of)\s+(?:milk|dairy)\b",
                re.IGNORECASE,
            ),
        ),
        high_severity_patterns=(
            re.compile(
                r"\b(?:avoid|do\s+not\s+take|chelat|treatment\s+failure|markedly\s+reduce|"
                r"substantially\s+decrease)\b",
                re.IGNORECASE,
            ),
        ),
        info_severity_patterns=(),
        allowed_sections=_ALL_TARGET_SECTIONS,
        fallback_note="Separate this medication from milk, yogurt, and dairy products.",
    ),
    _TriggerRule(
        trigger_type="CAFFEINE",
        include_patterns=(
            re.compile(
                r"\b(?:caffeine|caffeinated\s+(?:beverages?|drinks?)|coffee|cola)\b"
                r".{0,120}\b(?:avoid|limit|reduce|inhibit|increase|elevat|clearance|metabol|"
                r"insomnia|tachycardia|palpitation|jitter|nervousness|interaction|toxicity|"
                r"absorption|excessive)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\b(?:avoid|limit|reduce|inhibit|increase|elevat|clearance|metabol|interaction|"
                r"excessive)\b.{0,120}\b(?:caffeine|caffeinated\s+(?:beverages?|drinks?)|coffee)\b",
                re.IGNORECASE,
            ),
        ),
        exclude_patterns=(
            re.compile(
                r"\bno\s+(?:clinically\s+)?(?:significant\s+)?(?:interaction|effect)s?\s+"
                r"(?:with|of)\b.{0,50}\bcaffeine\b",
                re.IGNORECASE,
            ),
        ),
        high_severity_patterns=(
            re.compile(
                r"\b(?:avoid|contraindicated|toxicity|arrhythmia|severe|seizure)\b",
                re.IGNORECASE,
            ),
        ),
        info_severity_patterns=(),
        allowed_sections=_ALL_TARGET_SECTIONS,
        fallback_note="Limit or avoid caffeine while taking this medication.",
    ),
    _TriggerRule(
        trigger_type="EMPTY_STOMACH",
        include_patterns=(
            re.compile(r"\bon\s+an\s+empty\s+stomach\b", re.IGNORECASE),
            re.compile(r"\bempty\s+stomach\b", re.IGNORECASE),
            re.compile(
                r"\b(?:at\s+least\s+)?\d+\s*(?:to\s*\d+\s*)?(?:minutes?|hours?)\s+before\s+"
                r"(?:breakfast|food|eating|a\s+meal|meals|the\s+first\s+food)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\b1\s+hour\s+before\s+or\s+2\s+hours\s+after\s+(?:a\s+)?(?:food|meal|meals)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\b(?:take|administer(?:ed)?)\s+(?:in\s+a\s+)?fasting\s+(?:state|condition)\b",
                re.IGNORECASE,
            ),
            re.compile(r"\bwithout\s+food\b", re.IGNORECASE),
        ),
        exclude_patterns=(
            re.compile(r"\bwith\s+or\s+without\s+(?:food|meals?)\b", re.IGNORECASE),
            re.compile(r"\bwithout\s+regard\s+to\s+(?:food|meals?)\b", re.IGNORECASE),
            re.compile(r"\bregardless\s+of\s+(?:food|meals?)\b", re.IGNORECASE),
            re.compile(r"\bfood\s+does\s+not\s+(?:significantly\s+)?affect\b", re.IGNORECASE),
        ),
        high_severity_patterns=(
            re.compile(
                r"\b(?:must|strictly|at\s+least\s+\d+(?:\s*(?:to|-)\s*\d+)?\s+minutes\s+before|"
                r"plain\s+water|esophageal|absorption\s+is\s+(?:markedly|significantly)\s+reduced)\b",
                re.IGNORECASE,
            ),
        ),
        info_severity_patterns=(),
        allowed_sections=_ALL_TARGET_SECTIONS,
        fallback_note="Take on an empty stomach with water before meals as directed.",
    ),
    _TriggerRule(
        trigger_type="WITH_FOOD",
        include_patterns=(
            re.compile(
                r"\b(?:take|taken|administer(?:ed)?|give|given)\s+(?:immediately\s+)?"
                r"(?:with|after)\s+(?:a\s+)?(?:food|meal|meals)\b",
                re.IGNORECASE,
            ),
            re.compile(r"\bwith\s+food\s+or\s+milk\b", re.IGNORECASE),
            re.compile(
                r"\b(?:food|meals?)\s+(?:enhances?|increases?|improves?)\s+"
                r"(?:absorption|bioavailability)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\bwith\s+(?:a\s+)?(?:food|meal|meals)\s+to\s+(?:reduce|minimize|decrease)\s+"
                r"(?:gastrointestinal|gi|stomach)\b",
                re.IGNORECASE,
            ),
        ),
        exclude_patterns=(
            re.compile(r"\bwith\s+or\s+without\s+(?:food|meals?)\b", re.IGNORECASE),
            re.compile(r"\bwithout\s+regard\s+to\s+(?:food|meals?)\b", re.IGNORECASE),
            re.compile(r"\bregardless\s+of\s+(?:food|meals?)\b", re.IGNORECASE),
            re.compile(r"\bon\s+an\s+empty\s+stomach\b", re.IGNORECASE),
            re.compile(r"\bdo\s+not\s+take\s+with\s+food\b", re.IGNORECASE),
        ),
        high_severity_patterns=(
            re.compile(
                r"\b(?:must\s+be\s+taken\s+with\s+(?:a\s+)?(?:food|meal)|"
                r"significantly\s+(?:enhances|increases)\s+(?:absorption|bioavailability))\b",
                re.IGNORECASE,
            ),
        ),
        info_severity_patterns=(
            re.compile(r"\b(?:may\s+be\s+taken\s+with\s+food|if\s+stomach\s+upset)\b", re.I),
        ),
        allowed_sections=_ALL_TARGET_SECTIONS,
        fallback_note="Take with food or meals to improve absorption or reduce stomach upset.",
    ),
    _TriggerRule(
        trigger_type="DROWSINESS",
        include_patterns=(
            re.compile(
                r"\b(?:drowsiness|somnolence|sedation|sedating|marked\s+drowsiness)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\b(?:driving|drive\s+a\s+(?:car|vehicle)|operat(?:e|ing)\s+"
                r"(?:heavy\s+|hazardous\s+)?machinery)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\b(?:cns\s+depression|impair(?:s|ed|ment\s+of)?\s+(?:mental|physical)\s+"
                r"(?:alertness|abilities))\b",
                re.IGNORECASE,
            ),
        ),
        exclude_patterns=(
            re.compile(r"\bdoes\s+not\s+(?:usually\s+)?cause\s+(?:drowsiness|sedation)\b", re.I),
            re.compile(r"\bnon[- ]drowsy\b", re.IGNORECASE),
        ),
        high_severity_patterns=(
            re.compile(
                r"\b(?:do\s+not\s+drive|hazardous\s+machinery|heavy\s+machinery|"
                r"cns\s+depression|severe\s+sedation|respiratory\s+depression|"
                r"somnolence\s+and\s+sedation|driving\s+impairment)\b",
                re.IGNORECASE,
            ),
        ),
        info_severity_patterns=(),
        allowed_sections=_ALL_TARGET_SECTIONS,
        fallback_note=(
            "May cause drowsiness or sedation; use caution when driving or operating machinery."
        ),
    ),
    _TriggerRule(
        trigger_type="SUN_SENSITIVITY",
        include_patterns=(
            re.compile(
                r"\b(?:photosensitiv(?:e|ity)|phototox(?:ic|icity)|photoallergic|"
                r"sun\s+sensitivity|sensitivity\s+to\s+sunlight)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\b(?:avoid|minimize|limit)\s+(?:prolonged|excessive|direct|unnecessary)?\s*"
                r"(?:sunlight|sun\s+exposure|ultraviolet|uv\s+(?:light|radiation)|tanning)\b",
                re.IGNORECASE,
            ),
            re.compile(
                r"\b(?:exaggerated\s+sunburn|severe\s+sunburn|wear\s+sunscreen|"
                r"use\s+sunscreen|protective\s+clothing)\b",
                re.IGNORECASE,
            ),
        ),
        exclude_patterns=(
            re.compile(r"\bno\s+evidence\s+of\s+photosensitivity\b", re.IGNORECASE),
        ),
        high_severity_patterns=(
            re.compile(
                r"\b(?:phototox(?:ic|icity)|severe\s+sunburn|exaggerated\s+sunburn|"
                r"avoid\s+(?:direct|prolonged|excessive)\s+sunlight|discontinue\s+at\s+the\s+first)\b",
                re.IGNORECASE,
            ),
        ),
        info_severity_patterns=(),
        allowed_sections=_ALL_TARGET_SECTIONS,
        fallback_note=(
            "May increase sun sensitivity; use sunscreen and limit prolonged UV exposure."
        ),
    ),
)


def resolve_chunk_section(chunk: MonographChunkLike) -> str | None:
    meta = chunk.chunk_metadata if isinstance(chunk.chunk_metadata, dict) else {}
    raw_key = str(meta.get("section_key") or "").strip().lower()
    if raw_key in _TARGET_SECTIONS:
        return _TARGET_SECTIONS[raw_key]

    loinc = (chunk.loinc_section_code or "").strip()
    if loinc in _TARGET_LOINC_SECTIONS:
        return _TARGET_LOINC_SECTIONS[loinc]

    title_lower = (chunk.section_title or "").strip().lower()
    if "drug interaction" in title_lower:
        return "drug_interactions"
    if "dosage" in title_lower or "administration" in title_lower:
        return "dosage_and_administration"
    if "warning" in title_lower:
        return "warnings"
    if "precaution" in title_lower:
        return "precautions"
    return None


def _truncate_sentence(sentence: str, max_chars: int = 240) -> str:
    cleaned = _WHITESPACE_RE.sub(" ", sentence).strip()
    if len(cleaned) <= max_chars:
        return cleaned
    clipped = cleaned[: max_chars - 3].rsplit(" ", 1)[0].rstrip(" ,;:-")
    return f"{clipped}..."


def _classify_severity(
    rule: _TriggerRule,
    sentence: str,
    section_title: str,
) -> LifestyleTriggerSeverity:
    if "boxed warning" in section_title.lower():
        return "HIGH"
    if any(pat.search(sentence) is not None for pat in rule.high_severity_patterns):
        return "HIGH"
    if any(pat.search(sentence) is not None for pat in rule.info_severity_patterns):
        return "INFO"
    return "MODERATE"


def extract_lifestyle_triggers_from_chunks(
    chunks: Sequence[MonographChunkLike],
) -> list[LifestyleTrigger]:
    best_by_type: dict[LifestyleTriggerType, tuple[int, int, LifestyleTrigger]] = {}

    for chunk in chunks:
        section_name = resolve_chunk_section(chunk)
        if section_name is None:
            continue
        sentences = [
            _WHITESPACE_RE.sub(" ", s).strip()
            for s in _SENTENCE_SPLIT_RE.split(chunk.content or "")
            if s.strip()
        ]
        if not sentences:
            continue

        section_prio = _SECTION_PRIORITY.get(section_name, 0)
        for sentence in sentences:
            for rule in _TRIGGER_RULES:
                if section_name not in rule.allowed_sections:
                    continue
                if not any(pat.search(sentence) is not None for pat in rule.include_patterns):
                    continue
                if any(pat.search(sentence) is not None for pat in rule.exclude_patterns):
                    continue

                severity = _classify_severity(rule, sentence, chunk.section_title)
                sev_rank = _SEVERITY_RANK[severity]
                note = _truncate_sentence(sentence) or rule.fallback_note
                candidate = LifestyleTrigger(
                    type=rule.trigger_type,
                    severity=severity,
                    short_note=note,
                    source_section=section_name,
                )
                existing = best_by_type.get(rule.trigger_type)
                if existing is None or (sev_rank, section_prio) > (existing[0], existing[1]):
                    best_by_type[rule.trigger_type] = (sev_rank, section_prio, candidate)

    ordered = sorted(
        best_by_type.values(),
        key=lambda item: (-item[0], item[2].type),
    )
    return [item[2] for item in ordered]


async def extract_lifestyle_triggers_by_salt(
    session: AsyncSession,
    canonical_salts: Iterable[str],
) -> dict[str, list[LifestyleTrigger]]:
    normalized = canonical_salt_keys(canonical_salts)
    if not normalized:
        return {}

    stmt = (
        select(MonographChunk)
        .where(MonographChunk.canonical_salt.in_(normalized))
        .order_by(MonographChunk.canonical_salt.asc(), MonographChunk.chunk_index.asc())
    )
    rows = list((await session.execute(stmt)).scalars().all())

    chunks_by_salt: dict[str, list[MonographChunk]] = {}
    for row in rows:
        chunks_by_salt.setdefault(row.canonical_salt, []).append(row)

    return {
        salt: extract_lifestyle_triggers_from_chunks(chunks_by_salt.get(salt, []))
        for salt in normalized
    }


async def extract_lifestyle_triggers_for_salts(
    session: AsyncSession,
    canonical_salts: Iterable[str],
) -> list[LifestyleTrigger]:
    normalized = canonical_salt_keys(canonical_salts)
    if not normalized:
        return []

    stmt = (
        select(MonographChunk)
        .where(MonographChunk.canonical_salt.in_(normalized))
        .order_by(MonographChunk.canonical_salt.asc(), MonographChunk.chunk_index.asc())
    )
    rows = list((await session.execute(stmt)).scalars().all())
    return extract_lifestyle_triggers_from_chunks(rows)
