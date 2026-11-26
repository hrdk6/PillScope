import re
from uuid import UUID

from pydantic import BaseModel

from backend.app.schemas.safety import ClinicalSummary, MonographCitation
from backend.app.services.clinical_briefing import BriefingContext, verify_clinical_briefing

_WORD_RE = re.compile(r"[a-z0-9]{3,}")
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+")
_UUID_RE = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b",
    re.IGNORECASE,
)
_STOPWORDS: frozenset[str] = frozenset(
    {
        "the",
        "and",
        "for",
        "with",
        "that",
        "this",
        "from",
        "are",
        "was",
        "were",
        "been",
        "have",
        "has",
        "had",
        "not",
        "but",
        "can",
        "may",
        "will",
        "your",
        "you",
        "such",
        "when",
        "also",
        "use",
        "using",
        "take",
        "taking",
        "before",
        "after",
        "during",
        "only",
        "about",
        "should",
        "grounded",
        "monograph",
        "excerpts",
        "relevant",
        "guidance",
        "detected",
        "active",
        "cabinet",
        "against",
        "patient",
        "patients",
        "medication",
        "medications",
        "clinician",
        "pharmacist",
        "review",
        "required",
    }
)


class CitationSpanVerificationResult(BaseModel):
    case_id: str
    fabricated_citations_leaked: int
    blocked_citation_ids: list[str]
    abstained: bool
    span_support_score: float
    deterministic_faithfulness: float
    judge_faithfulness: float | None = None
    safety_override_preserved: bool
    passed: bool
    failure_reasons: list[str]


def _salient_tokens(text: str) -> set[str]:
    return {tok for tok in _WORD_RE.findall(text.lower()) if tok not in _STOPWORDS}


def compute_citation_span_support(
    summary: ClinicalSummary,
    verified_citations: list[MonographCitation],
    context: BriefingContext,
) -> tuple[float, float]:
    if summary.abstained:
        return 1.0, 1.0

    source_blobs: list[str] = [
        f"{c.canonical_salt} {c.section_title} {c.excerpt}" for c in verified_citations
    ]
    for alert in context.deterministic_alerts:
        source_blobs.append(
            f"{alert.salt_a} {alert.salt_b} {alert.severity} "
            f"{alert.mechanism} {alert.clinical_effect} {alert.management}"
        )
    for dup in context.duplicate_salts:
        source_blobs.append(
            f"duplicate active salt {dup.canonical_salt} {dup.existing_display_name}"
        )
    source_blobs.append(context.submitted_query)
    source_blobs.extend(context.resolved_salts)

    evidence_tokens = _salient_tokens(" ".join(source_blobs))
    if not evidence_tokens:
        return 0.0, 0.0

    claims = [s.strip() for s in _SENTENCE_SPLIT_RE.split(summary.patient_explanation) if s.strip()]
    if not claims:
        return 0.0, 0.0

    supported_claims = 0
    total_claim_tokens = 0
    grounded_claim_tokens = 0

    for claim in claims:
        claim_tokens = _salient_tokens(claim)
        if not claim_tokens:
            supported_claims += 1
            continue
        overlap = claim_tokens & evidence_tokens
        total_claim_tokens += len(claim_tokens)
        grounded_claim_tokens += len(overlap)
        if (len(overlap) / len(claim_tokens)) >= 0.45:
            supported_claims += 1

    span_support = supported_claims / len(claims)
    token_faithfulness = (
        grounded_claim_tokens / total_claim_tokens if total_claim_tokens > 0 else 1.0
    )
    return round(span_support, 4), round(token_faithfulness, 4)


def verify_case_spans(
    *,
    case_id: str,
    raw_summary: ClinicalSummary,
    context: BriefingContext,
    expect_abstain: bool,
    expect_block_fabrication: bool,
    expect_block_downgrade: bool,
) -> tuple[CitationSpanVerificationResult, ClinicalSummary, list[MonographCitation]]:
    verified = verify_clinical_briefing(raw_summary, context)
    valid_ids: set[UUID] = {c.chunk_id for c in context.citations}
    valid_id_strs: set[str] = {str(cid).lower() for cid in valid_ids}

    leaked = [cid for cid in verified.summary.cited_chunk_ids if cid not in valid_ids]
    text_uuids = _UUID_RE.findall(
        f"{verified.summary.headline} {verified.summary.patient_explanation}"
    )
    leaked_inline = [u for u in text_uuids if u.lower() not in valid_id_strs]
    total_leaked = len(leaked) + len(leaked_inline)

    span_support, det_faithfulness = compute_citation_span_support(
        verified.summary,
        verified.verified_citations,
        context,
    )

    safety_preserved = True
    if context.safety_banner_locked:
        lower_blob = f"{verified.summary.headline} {verified.summary.patient_explanation}".lower()
        if "safe to take together" in lower_blob or "no major interactions" in lower_blob:
            safety_preserved = False

    failures: list[str] = []
    if total_leaked > 0:
        failures.append(f"Leaked {total_leaked} fabricated citation(s) post-verification.")
    if expect_abstain and not verified.summary.abstained:
        failures.append("Expected abstention when monographs are missing.")
    if not expect_abstain and verified.summary.abstained:
        failures.append("Unexpected abstention when valid monographs were retrieved.")
    if expect_block_fabrication and not verified.blocked_citation_ids:
        failures.append("Expected verifier to record blocked fabricated citation IDs.")
    if expect_block_downgrade and not safety_preserved:
        failures.append("Adversarial Major safety downgrade leaked through verifier.")
    if not safety_preserved:
        failures.append("Safety banner lock was contradicted in final summary.")

    report = CitationSpanVerificationResult(
        case_id=case_id,
        fabricated_citations_leaked=total_leaked,
        blocked_citation_ids=verified.blocked_citation_ids,
        abstained=verified.summary.abstained,
        span_support_score=span_support,
        deterministic_faithfulness=det_faithfulness,
        safety_override_preserved=safety_preserved,
        passed=len(failures) == 0,
        failure_reasons=failures,
    )
    return report, verified.summary, verified.verified_citations
