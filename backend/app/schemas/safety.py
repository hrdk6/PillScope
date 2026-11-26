from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

from backend.app.schemas.cabinet import DuplicateSaltAlert, RiskLevel
from backend.app.schemas.lifestyle import LifestyleTrigger
from backend.app.schemas.profiles import AllergyAlert
from backend.app.schemas.recalls import MedicationRecallFlag

InputMode = Literal["CAMERA_OCR", "MANUAL_TEXT", "BARCODE"]
InteractionSource = Literal["deterministic_db", "ml_model", "hybrid"]
ClinicalSectionKey = Literal[
    "drug_interactions",
    "warnings",
    "dosage_and_administration",
    "contraindications",
    "precautions",
]


class ScanAnalyzeRequest(BaseModel):
    profile_id: UUID | None = None
    persist: bool = True
    input_mode: InputMode = "MANUAL_TEXT"
    submitted_query: str = Field(min_length=1, max_length=255)
    confirmed_salts: list[str] | None = Field(default=None, max_length=20)
    raw_ocr_text: str | None = Field(default=None, max_length=2000)
    country_code: str | None = Field(default=None, min_length=2, max_length=2)
    edge_quality_metrics: dict[str, Any] | None = None

    @model_validator(mode="after")
    def validate_query(self) -> "ScanAnalyzeRequest":
        if not self.submitted_query.strip():
            raise ValueError("submitted_query cannot be blank.")
        if self.confirmed_salts is not None:
            for item in self.confirmed_salts:
                if len(item) > 160:
                    raise ValueError("confirmed_salts entries must be <= 160 characters.")
        if self.edge_quality_metrics is not None and len(self.edge_quality_metrics) > 16:
            raise ValueError("edge_quality_metrics cannot contain more than 16 keys.")
        return self


class DeterministicDdiAlert(BaseModel):
    rule_id: str
    salt_a: str
    salt_b: str
    severity: RiskLevel
    mechanism: str
    clinical_effect: str
    management: str
    sources: list[str]


class InteractionItem(BaseModel):
    salt_a: str
    salt_b: str
    severity: RiskLevel
    confidence: float = Field(ge=0.0, le=1.0)
    source: InteractionSource
    mechanism: str
    management: str
    ml_major_probability: float | None = Field(default=None, ge=0.0, le=1.0)


class MonographCitation(BaseModel):
    chunk_id: UUID
    set_id: str
    canonical_salt: str
    section_key: str
    section_title: str
    loinc_section_code: str
    excerpt: str
    source_url: str
    lexical_score: float | None = None
    dense_score: float | None = None
    rrf_score: float | None = None
    rerank_score: float = 0.0


class ClinicalSummary(BaseModel):
    headline: str = Field(min_length=1, max_length=300)
    patient_explanation: str = Field(min_length=1, max_length=3000)
    actionable_next_steps: list[str] = Field(default_factory=list)
    cited_chunk_ids: list[UUID] = Field(default_factory=list)
    abstained: bool = False
    abstention_reason: str | None = None
    disclaimer: str = (
        "Informational safety check only; consult a licensed clinician or pharmacist "
        "before starting, stopping, or changing any medication."
    )


class DeterministicSafetyEvent(BaseModel):
    scan_id: UUID
    profile_id: UUID | None = None
    persisted: bool = True
    submitted_query: str
    resolved_salts: list[str]
    overall_risk: RiskLevel
    safety_banner_locked: bool
    duplicate_salts: list[DuplicateSaltAlert]
    allergy_alerts: list[AllergyAlert] = Field(default_factory=list)
    deterministic_alerts: list[DeterministicDdiAlert]
    active_recalls: list[MedicationRecallFlag] = Field(default_factory=list)


class InteractionsEvent(BaseModel):
    scan_id: UUID
    overall_risk: RiskLevel
    safety_banner_locked: bool
    interactions: list[InteractionItem]
    ml_scored_pair_count: int = 0


class CitationsEvent(BaseModel):
    scan_id: UUID
    citations: list[MonographCitation]
    lifestyle_triggers: list[LifestyleTrigger] = Field(default_factory=list)


class ClinicalSummaryEvent(BaseModel):
    scan_id: UUID
    overall_risk: RiskLevel
    safety_banner_locked: bool
    citation_verified: bool
    blocked_citation_ids: list[str] = Field(default_factory=list)
    clinical_summary: ClinicalSummary
    citations: list[MonographCitation]


class ScanCompleteEvent(BaseModel):
    scan_id: UUID
    profile_id: UUID | None = None
    persisted: bool = True
    submitted_query: str
    resolved_salts: list[str]
    overall_risk: RiskLevel
    safety_banner_locked: bool
    deterministic_alerts: list[DeterministicDdiAlert]
    duplicate_salts: list[DuplicateSaltAlert]
    allergy_alerts: list[AllergyAlert] = Field(default_factory=list)
    lifestyle_triggers: list[LifestyleTrigger] = Field(default_factory=list)
    active_recalls: list[MedicationRecallFlag] = Field(default_factory=list)
    interactions: list[InteractionItem]
    clinical_summary: ClinicalSummary
    citations: list[MonographCitation]
    citation_verified: bool
    blocked_citation_ids: list[str] = Field(default_factory=list)
    latency_ms: int


class MonographIngestJobRequest(BaseModel):
    drug_names: list[str] = Field(min_length=1, max_length=50)
    source: Literal["openfda", "dailymed", "auto"] = "auto"


class MonographIngestJobResponse(BaseModel):
    job_id: str
    status: Literal["queued"] = "queued"
    drug_names: list[str]
