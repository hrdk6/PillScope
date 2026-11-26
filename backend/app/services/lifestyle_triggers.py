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
        include_patterns=(re.compile(r"\bgrapefruit(?:\s+juice|\s+products?)?\b", re.IGNORECASE),),
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
        exclude_patterns=(re.compile(r"\bno\s+evidence\s+of\s+photosensitivity\b", re.IGNORECASE),),
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


_BASELINE_SALT_TRIGGERS: dict[str, tuple[LifestyleTrigger, ...]] = {
    "warfarin": (
        LifestyleTrigger(
            type="ALCOHOL",
            severity="HIGH",
            short_note=(
                "Avoid or strictly limit alcohol; alcohol alters INR and raises bleeding risk."
            ),
            source_section="warnings",
        ),
    ),
    "metronidazole": (
        LifestyleTrigger(
            type="ALCOHOL",
            severity="HIGH",
            short_note=(
                "Avoid alcohol during therapy and for 3 days afterward (disulfiram-like reaction)."
            ),
            source_section="warnings",
        ),
    ),
    "tinidazole": (
        LifestyleTrigger(
            type="ALCOHOL",
            severity="HIGH",
            short_note=(
                "Avoid alcohol during therapy and for 3 days afterward (disulfiram-like reaction)."
            ),
            source_section="warnings",
        ),
    ),
    "acetaminophen": (
        LifestyleTrigger(
            type="ALCOHOL",
            severity="HIGH",
            short_note=(
                "Avoid heavy alcohol consumption; combining with acetaminophen raises "
                "liver injury risk."
            ),
            source_section="warnings",
        ),
    ),
    "simvastatin": (
        LifestyleTrigger(
            type="GRAPEFRUIT",
            severity="HIGH",
            short_note=(
                "Avoid grapefruit juice; CYP3A4 inhibition increases myopathy and "
                "rhabdomyolysis risk."
            ),
            source_section="drug_interactions",
        ),
    ),
    "atorvastatin": (
        LifestyleTrigger(
            type="GRAPEFRUIT",
            severity="MODERATE",
            short_note="Avoid large quantities of grapefruit juice while taking atorvastatin.",
            source_section="drug_interactions",
        ),
    ),
    "lovastatin": (
        LifestyleTrigger(
            type="GRAPEFRUIT",
            severity="HIGH",
            short_note="Avoid grapefruit juice due to increased risk of statin myopathy.",
            source_section="drug_interactions",
        ),
    ),
    "amlodipine": (
        LifestyleTrigger(
            type="GRAPEFRUIT",
            severity="MODERATE",
            short_note=(
                "Large amounts of grapefruit juice may increase blood-pressure-lowering effects."
            ),
            source_section="drug_interactions",
        ),
    ),
    "ciprofloxacin": (
        LifestyleTrigger(
            type="DAIRY",
            severity="HIGH",
            short_note="Separate from milk, yogurt, and calcium-fortified drinks by 2-4 hours.",
            source_section="dosage_and_administration",
        ),
        LifestyleTrigger(
            type="SUN_SENSITIVITY",
            severity="MODERATE",
            short_note="May cause photosensitivity; use sunscreen and limit prolonged UV exposure.",
            source_section="warnings",
        ),
    ),
    "levofloxacin": (
        LifestyleTrigger(
            type="DAIRY",
            severity="MODERATE",
            short_note=(
                "Separate from divalent cations and calcium/iron supplements by at least 2 hours."
            ),
            source_section="dosage_and_administration",
        ),
        LifestyleTrigger(
            type="SUN_SENSITIVITY",
            severity="MODERATE",
            short_note="Use sun protection and avoid excessive sunlight exposure.",
            source_section="warnings",
        ),
    ),
    "doxycycline": (
        LifestyleTrigger(
            type="DAIRY",
            severity="MODERATE",
            short_note=(
                "Separate from milk, dairy, calcium, and iron supplements by at least 2 hours."
            ),
            source_section="drug_interactions",
        ),
        LifestyleTrigger(
            type="SUN_SENSITIVITY",
            severity="HIGH",
            short_note=(
                "May cause exaggerated sunburn reactions; wear sunscreen and protective clothing."
            ),
            source_section="warnings",
        ),
    ),
    "levothyroxine": (
        LifestyleTrigger(
            type="EMPTY_STOMACH",
            severity="HIGH",
            short_note="Take on an empty stomach with water 30-60 minutes before breakfast.",
            source_section="dosage_and_administration",
        ),
    ),
    "alendronate": (
        LifestyleTrigger(
            type="EMPTY_STOMACH",
            severity="HIGH",
            short_note="Take with plain water at least 30 minutes before food and stay upright.",
            source_section="dosage_and_administration",
        ),
    ),
    "omeprazole": (
        LifestyleTrigger(
            type="EMPTY_STOMACH",
            severity="MODERATE",
            short_note="Take 30-60 minutes before a meal for best acid suppression.",
            source_section="dosage_and_administration",
        ),
    ),
    "pantoprazole": (
        LifestyleTrigger(
            type="EMPTY_STOMACH",
            severity="MODERATE",
            short_note="Take 30-60 minutes before breakfast for optimal effect.",
            source_section="dosage_and_administration",
        ),
    ),
    "metformin": (
        LifestyleTrigger(
            type="WITH_FOOD",
            severity="MODERATE",
            short_note="Take with meals to minimize gastrointestinal upset.",
            source_section="dosage_and_administration",
        ),
        LifestyleTrigger(
            type="ALCOHOL",
            severity="HIGH",
            short_note="Avoid excessive alcohol intake due to lactic acidosis risk.",
            source_section="warnings",
        ),
    ),
    "ibuprofen": (
        LifestyleTrigger(
            type="WITH_FOOD",
            severity="MODERATE",
            short_note="Take with food or meals to minimize stomach irritation.",
            source_section="dosage_and_administration",
        ),
        LifestyleTrigger(
            type="ALCOHOL",
            severity="MODERATE",
            short_note="Alcohol increases the risk of NSAID-related stomach bleeding.",
            source_section="warnings",
        ),
    ),
    "diclofenac": (
        LifestyleTrigger(
            type="WITH_FOOD",
            severity="MODERATE",
            short_note="Take with food to reduce gastrointestinal discomfort.",
            source_section="dosage_and_administration",
        ),
    ),
    "naproxen": (
        LifestyleTrigger(
            type="WITH_FOOD",
            severity="MODERATE",
            short_note="Take with food or meals to minimize stomach upset.",
            source_section="dosage_and_administration",
        ),
    ),
    "diphenhydramine": (
        LifestyleTrigger(
            type="DROWSINESS",
            severity="HIGH",
            short_note="Causes marked drowsiness; avoid driving or operating heavy machinery.",
            source_section="warnings",
        ),
        LifestyleTrigger(
            type="ALCOHOL",
            severity="HIGH",
            short_note="Avoid alcohol due to additive CNS depression and sedation.",
            source_section="drug_interactions",
        ),
    ),
    "cetirizine": (
        LifestyleTrigger(
            type="DROWSINESS",
            severity="MODERATE",
            short_note="May cause drowsiness in some patients; use caution when driving.",
            source_section="warnings",
        ),
    ),
    "alprazolam": (
        LifestyleTrigger(
            type="DROWSINESS",
            severity="HIGH",
            short_note="May cause sedation and impair alertness; do not drive if affected.",
            source_section="warnings",
        ),
        LifestyleTrigger(
            type="ALCOHOL",
            severity="HIGH",
            short_note="Avoid alcohol due to life-threatening CNS and respiratory depression risk.",
            source_section="warnings",
        ),
    ),
}


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

    results: dict[str, list[LifestyleTrigger]] = {}
    for salt in normalized:
        salt_chunks = chunks_by_salt.get(salt, [])
        if salt_chunks:
            results[salt] = extract_lifestyle_triggers_from_chunks(salt_chunks)
        else:
            results[salt] = list(_BASELINE_SALT_TRIGGERS.get(salt, ()))
    return results


async def extract_lifestyle_triggers_for_salts(
    session: AsyncSession,
    canonical_salts: Iterable[str],
) -> list[LifestyleTrigger]:
    by_salt = await extract_lifestyle_triggers_by_salt(session, canonical_salts)
    best_by_type: dict[LifestyleTriggerType, LifestyleTrigger] = {}
    for triggers in by_salt.values():
        for trigger in triggers:
            existing = best_by_type.get(trigger.type)
            if (
                existing is None
                or _SEVERITY_RANK[trigger.severity] > _SEVERITY_RANK[existing.severity]
            ):
                best_by_type[trigger.type] = trigger
    return sorted(
        best_by_type.values(),
        key=lambda item: (-_SEVERITY_RANK[item.severity], item.type),
    )
