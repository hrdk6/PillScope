import json
import re
from dataclasses import dataclass, field
from typing import Any, Protocol
from uuid import UUID

import httpx
import structlog

from backend.app.core.config import Settings, get_settings
from backend.app.schemas.cabinet import DuplicateSaltAlert, RiskLevel
from backend.app.schemas.profiles import AllergyAlert
from backend.app.schemas.safety import (
    ClinicalSummary,
    DeterministicDdiAlert,
    InteractionItem,
    MonographCitation,
)

logger = structlog.get_logger(__name__)

_UUID_RE = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b",
    re.IGNORECASE,
)
_DOWNGRADE_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\bno\s+(?:major|serious|significant|clinically\s+relevant)\s+interaction", re.I),
    re.compile(r"\bsafe\s+to\s+(?:take|use|combine)\s+together", re.I),
    re.compile(r"\bno\s+interactions?\s+(?:were\s+)?(?:found|detected|identified)", re.I),
    re.compile(r"\bno\s+safety\s+concerns?\b", re.I),
    re.compile(r"\blow\s+risk\b", re.I),
)


@dataclass(slots=True)
class BriefingContext:
    submitted_query: str
    resolved_salts: list[str]
    overall_risk: RiskLevel
    safety_banner_locked: bool
    duplicate_salts: list[DuplicateSaltAlert]
    deterministic_alerts: list[DeterministicDdiAlert]
    interactions: list[InteractionItem]
    citations: list[MonographCitation]
    allergy_alerts: list[AllergyAlert] = field(default_factory=list)


@dataclass(slots=True)
class LlmTokenUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0


@dataclass(slots=True)
class VerifiedBriefingResult:
    summary: ClinicalSummary
    verified_citations: list[MonographCitation]
    citation_verified: bool
    blocked_citation_ids: list[str]


class LlmBriefingGenerator(Protocol):
    async def generate(self, context: BriefingContext) -> ClinicalSummary: ...


def build_abstention_summary(context: BriefingContext) -> ClinicalSummary:
    if context.safety_banner_locked:
        reasons: list[str] = []
        if context.allergy_alerts:
            allergy_labels = ", ".join(
                sorted({f"{a.canonical_salt} ({a.allergen})" for a in context.allergy_alerts})
            )
            reasons.append(f"documented allergy conflict ({allergy_labels})")
        if context.duplicate_salts:
            dup_names = ", ".join(sorted({d.canonical_salt for d in context.duplicate_salts}))
            reasons.append(f"duplicate active ingredient ({dup_names})")
        major_pairs = [a for a in context.deterministic_alerts if a.severity == "MAJOR"]
        if major_pairs:
            pair_labels = ", ".join(f"{a.salt_a} + {a.salt_b}" for a in major_pairs)
            reasons.append(f"major drug interaction ({pair_labels})")
        detail = " and ".join(reasons) if reasons else "deterministic safety trigger"
        return ClinicalSummary(
            headline=f"MAJOR safety alert locked: {detail}.",
            patient_explanation=(
                f"Deterministic safety rules detected a {detail} for {context.submitted_query}. "
                "No official openFDA/DailyMed monograph sections are currently indexed for these "
                "salts, so monograph-based clinical synthesis is abstained; the red safety alert "
                "remains locked."
            ),
            actionable_next_steps=[
                "Do not combine these medications without explicit pharmacist or doctor approval.",
                "Share your full active medication cabinet with your clinician.",
            ],
            cited_chunk_ids=[],
            abstained=True,
            abstention_reason="NO_INDEXED_MONOGRAPHS",
        )

    if context.interactions:
        top = context.interactions[0]
        return ClinicalSummary(
            headline=(
                f"Monograph evidence unavailable; {context.overall_risk} interaction risk flagged."
            ),
            patient_explanation=(
                f"No official openFDA/DailyMed drug monograph sections are indexed for "
                f"{', '.join(context.resolved_salts) or context.submitted_query}. "
                f"Automated screening still flagged {top.salt_a} + {top.salt_b} ({top.severity}, "
                f"source: {top.source}). Clinical monograph synthesis is abstained."
            ),
            actionable_next_steps=[
                "Verify concurrent use with a pharmacist or prescribing doctor.",
                "Review the physical package insert for drug interactions and dosing guidance.",
            ],
            cited_chunk_ids=[],
            abstained=True,
            abstention_reason="NO_INDEXED_MONOGRAPHS",
        )

    return ClinicalSummary(
        headline="Insufficient indexed monograph evidence for clinical synthesis.",
        patient_explanation=(
            f"No duplicate active salts or curated interactions were triggered for "
            f"{context.submitted_query}, but no official openFDA/DailyMed monograph sections are "
            "currently indexed for these salts. PillScope is abstaining from monograph commentary."
        ),
        actionable_next_steps=[
            "Consult a pharmacist or check the official package insert before starting a new drug.",
        ],
        cited_chunk_ids=[],
        abstained=True,
        abstention_reason="NO_INDEXED_MONOGRAPHS",
    )


def build_extractive_grounded_summary(context: BriefingContext) -> ClinicalSummary:
    if not context.citations:
        return build_abstention_summary(context)

    top_citations = context.citations[:3]
    cited_ids = [c.chunk_id for c in top_citations]
    evidence_snippets = " ".join(
        f"[{c.canonical_salt} - {c.section_title}]: {c.excerpt[:220].strip()}"
        for c in top_citations
    )

    if context.safety_banner_locked:
        alert_parts: list[str] = []
        if context.allergy_alerts:
            allergies = ", ".join(
                f"{a.canonical_salt} matches documented allergy {a.allergen}"
                for a in context.allergy_alerts
            )
            alert_parts.append(f"Documented allergy conflict: {allergies}.")
        if context.duplicate_salts:
            dups = ", ".join(
                f"{d.canonical_salt} (already in {d.existing_display_name})"
                for d in context.duplicate_salts
            )
            alert_parts.append(f"Duplicate active salt: {dups}.")
        for alert in context.deterministic_alerts:
            if alert.severity == "MAJOR":
                alert_parts.append(
                    f"Major interaction between {alert.salt_a} and {alert.salt_b}: "
                    f"{alert.clinical_effect} {alert.management}"
                )
        headline = (
            f"MAJOR safety warning for {context.submitted_query} — clinician review required."
        )
        explanation = f"{' '.join(alert_parts)} Grounded monograph excerpts: {evidence_snippets}"
        steps = [
            "Pause before taking this medication alongside your active cabinet.",
            "Contact your prescribing clinician or pharmacist immediately to review alternatives.",
        ]
    elif context.interactions:
        top = context.interactions[0]
        headline = f"{context.overall_risk} interaction risk noted for {top.salt_a} + {top.salt_b}."
        explanation = (
            f"Screening identified a {top.severity} interaction between {top.salt_a} and "
            f"{top.salt_b} ({top.mechanism}). Grounded monograph excerpts: {evidence_snippets}"
        )
        steps = [
            top.management,
            "Discuss monitoring or dose spacing with your pharmacist.",
        ]
    else:
        headline = f"No curated interactions detected for {context.submitted_query}."
        explanation = (
            f"No duplicate salts or active interactions were detected against your cabinet. "
            f"Relevant monograph guidance: {evidence_snippets}"
        )
        steps = [
            "Follow prescribed dosing instructions and monitor for unexpected side effects.",
        ]

    return ClinicalSummary(
        headline=headline[:300],
        patient_explanation=explanation[:3000],
        actionable_next_steps=steps,
        cited_chunk_ids=cited_ids,
        abstained=False,
        abstention_reason=None,
    )


def _extract_embedded_uuids(summary: ClinicalSummary) -> set[str]:
    blob = " ".join([summary.headline, summary.patient_explanation, *summary.actionable_next_steps])
    return {match.lower() for match in _UUID_RE.findall(blob)}


def _contains_downgrade_language(summary: ClinicalSummary) -> bool:
    blob = f"{summary.headline} {summary.patient_explanation}"
    return any(pattern.search(blob) is not None for pattern in _DOWNGRADE_PATTERNS)


def verify_clinical_briefing(
    raw_summary: ClinicalSummary,
    context: BriefingContext,
) -> VerifiedBriefingResult:
    valid_by_id: dict[UUID, MonographCitation] = {c.chunk_id: c for c in context.citations}
    valid_id_strs: set[str] = {str(cid).lower() for cid in valid_by_id}

    blocked: list[str] = []
    for cid in raw_summary.cited_chunk_ids:
        if cid not in valid_by_id:
            blocked.append(str(cid))

    for embedded in sorted(_extract_embedded_uuids(raw_summary)):
        if embedded not in valid_id_strs and embedded not in blocked:
            blocked.append(embedded)

    if not context.citations:
        safe_abstention = build_abstention_summary(context)
        return VerifiedBriefingResult(
            summary=safe_abstention,
            verified_citations=[],
            citation_verified=True,
            blocked_citation_ids=blocked,
        )

    valid_cited_ids: list[UUID] = list(
        dict.fromkeys(cid for cid in raw_summary.cited_chunk_ids if cid in valid_by_id)
    )

    fabricated_detected = len(blocked) > 0
    missing_citations = len(valid_cited_ids) == 0
    downgraded_major = context.safety_banner_locked and _contains_downgrade_language(raw_summary)

    if fabricated_detected or missing_citations or downgraded_major:
        logger.warning(
            "clinical_briefing_verifier_override",
            fabricated_detected=fabricated_detected,
            blocked_citation_ids=blocked,
            missing_citations=missing_citations,
            downgraded_major=downgraded_major,
        )
        fallback = build_extractive_grounded_summary(context)
        verified_cites = [
            valid_by_id[cid] for cid in fallback.cited_chunk_ids if cid in valid_by_id
        ]
        return VerifiedBriefingResult(
            summary=fallback,
            verified_citations=verified_cites,
            citation_verified=True,
            blocked_citation_ids=blocked,
        )

    cleaned = raw_summary.model_copy(
        update={
            "cited_chunk_ids": valid_cited_ids,
            "abstained": False,
            "abstention_reason": None,
        }
    )
    verified_cites = [valid_by_id[cid] for cid in valid_cited_ids]
    return VerifiedBriefingResult(
        summary=cleaned,
        verified_citations=verified_cites,
        citation_verified=True,
        blocked_citation_ids=[],
    )


def _build_llm_prompt(context: BriefingContext) -> str:
    payload = {
        "submitted_query": context.submitted_query,
        "resolved_salts": context.resolved_salts,
        "overall_risk": context.overall_risk,
        "safety_banner_locked": context.safety_banner_locked,
        "duplicate_salts": [d.model_dump(mode="json") for d in context.duplicate_salts],
        "allergy_alerts": [a.model_dump(mode="json") for a in context.allergy_alerts],
        "deterministic_alerts": [a.model_dump(mode="json") for a in context.deterministic_alerts],
        "interactions": [i.model_dump(mode="json") for i in context.interactions],
        "monograph_chunks": [
            {
                "chunk_id": str(c.chunk_id),
                "canonical_salt": c.canonical_salt,
                "section_title": c.section_title,
                "loinc_section_code": c.loinc_section_code,
                "excerpt": c.excerpt,
            }
            for c in context.citations
        ],
    }
    return (
        "You are PillScope's clinical medication safety briefing synthesizer.\n"
        "Strict rules:\n"
        "1. NEVER contradict or downgrade overall_risk or safety_banner_locked.\n"
        "2. ONLY cite chunk_id UUIDs present in monograph_chunks.\n"
        "3. Never invent monograph claims, studies, or UUIDs.\n"
        "4. Return valid JSON matching the ClinicalSummary schema.\n\n"
        f"INPUT_JSON:\n{json.dumps(payload, indent=2)}"
    )


class DeterministicGroundedBriefingGenerator:
    provider_name = "deterministic_extractive"
    model_name = "extractive-v1"

    def __init__(self) -> None:
        self.last_usage = LlmTokenUsage()

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        self.last_usage = LlmTokenUsage()
        return build_extractive_grounded_summary(context)


class GeminiBriefingGenerator:
    provider_name = "gemini"

    def __init__(
        self,
        *,
        api_key: str,
        model_name: str = "gemini-2.5-flash",
        timeout_seconds: float = 20.0,
    ) -> None:
        self._api_key = api_key
        self.model_name = model_name
        self._timeout = timeout_seconds
        self.last_usage = LlmTokenUsage()

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        self.last_usage = LlmTokenUsage()
        if not context.citations:
            return build_abstention_summary(context)

        url = (
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"{self.model_name}:generateContent"
        )
        schema: dict[str, Any] = {
            "type": "object",
            "properties": {
                "headline": {"type": "string"},
                "patient_explanation": {"type": "string"},
                "actionable_next_steps": {"type": "array", "items": {"type": "string"}},
                "cited_chunk_ids": {"type": "array", "items": {"type": "string"}},
            },
            "required": [
                "headline",
                "patient_explanation",
                "actionable_next_steps",
                "cited_chunk_ids",
            ],
        }
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                url,
                headers={"x-goog-api-key": self._api_key},
                json={
                    "contents": [{"parts": [{"text": _build_llm_prompt(context)}]}],
                    "generationConfig": {
                        "temperature": 0.1,
                        "responseMimeType": "application/json",
                        "responseSchema": schema,
                    },
                },
            )
            response.raise_for_status()
            data = response.json()
            usage_meta = data.get("usageMetadata") or {}
            self.last_usage = LlmTokenUsage(
                input_tokens=int(usage_meta.get("promptTokenCount") or 0),
                output_tokens=int(usage_meta.get("candidatesTokenCount") or 0),
                total_tokens=int(usage_meta.get("totalTokenCount") or 0),
            )
            text_out = data["candidates"][0]["content"]["parts"][0]["text"]
            return ClinicalSummary.model_validate_json(text_out)


class OpenAiBriefingGenerator:
    provider_name = "openai"

    def __init__(
        self,
        *,
        api_key: str,
        model_name: str = "gpt-4o-mini",
        timeout_seconds: float = 20.0,
    ) -> None:
        self._api_key = api_key
        self.model_name = model_name
        self._timeout = timeout_seconds
        self.last_usage = LlmTokenUsage()

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        self.last_usage = LlmTokenUsage()
        if not context.citations:
            return build_abstention_summary(context)

        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                "https://api.openai.com/v1/chat/completions",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={
                    "model": self.model_name,
                    "temperature": 0.1,
                    "response_format": {"type": "json_object"},
                    "messages": [
                        {
                            "role": "system",
                            "content": "Return strict JSON matching ClinicalSummary.",
                        },
                        {"role": "user", "content": _build_llm_prompt(context)},
                    ],
                },
            )
            response.raise_for_status()
            payload = response.json()
            usage_meta = payload.get("usage") or {}
            self.last_usage = LlmTokenUsage(
                input_tokens=int(usage_meta.get("prompt_tokens") or 0),
                output_tokens=int(usage_meta.get("completion_tokens") or 0),
                total_tokens=int(usage_meta.get("total_tokens") or 0),
            )
            content = payload["choices"][0]["message"]["content"]
            return ClinicalSummary.model_validate_json(content)


def get_briefing_generator(settings: Settings | None = None) -> LlmBriefingGenerator:
    resolved = settings or get_settings()
    if resolved.llm_provider == "gemini" and resolved.gemini_api_key:
        return GeminiBriefingGenerator(
            api_key=resolved.gemini_api_key,
            model_name=resolved.llm_model,
            timeout_seconds=resolved.llm_timeout_seconds,
        )
    if resolved.llm_provider == "openai" and resolved.openai_api_key:
        return OpenAiBriefingGenerator(
            api_key=resolved.openai_api_key,
            model_name=resolved.llm_model,
            timeout_seconds=resolved.llm_timeout_seconds,
        )
    return DeterministicGroundedBriefingGenerator()
