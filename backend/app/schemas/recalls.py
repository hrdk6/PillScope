from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field

RecallClassification = Literal["Class I", "Class II"]
RecallMatchBasis = Literal["GENERIC_SALT", "BRAND_NAME", "BRAND_AND_SALT"]
RecallAlertLabel = Literal["US FDA Recall Alert"]


class MedicationRecallFlag(BaseModel):
    alert_label: RecallAlertLabel = "US FDA Recall Alert"
    recall_id: UUID
    recall_number: str
    classification: RecallClassification
    status: str
    recalling_firm: str | None = None
    reason_for_recall: str
    report_date: str | None = None
    match_basis: RecallMatchBasis
    matched_terms: list[str] = Field(default_factory=list)
    source_url: str


class ProfileRecallAlertItem(BaseModel):
    alert_label: RecallAlertLabel = "US FDA Recall Alert"
    jurisdiction: Literal["US_FDA"] = "US_FDA"
    recall_id: UUID
    recall_number: str
    event_id: str | None = None
    classification: RecallClassification
    status: str
    recalling_firm: str | None = None
    product_description: str
    reason_for_recall: str
    distribution_pattern: str | None = None
    code_info: str | None = None
    report_date: str | None = None
    recall_initiation_date: str | None = None
    source_url: str
    matched_medication_id: UUID
    matched_medication_name: str
    match_basis: RecallMatchBasis
    matched_terms: list[str] = Field(default_factory=list)


class ProfileRecallsResponse(BaseModel):
    profile_id: UUID
    profile_name: str
    alert_label: RecallAlertLabel = "US FDA Recall Alert"
    has_active_recalls: bool
    total_matches: int
    recalls: list[ProfileRecallAlertItem] = Field(default_factory=list)


class RecallSyncJobRequest(BaseModel):
    max_pages: int = Field(default=5, ge=1, le=50)
    page_size: int = Field(default=100, ge=1, le=1000)
    status_filter: str = Field(default="Ongoing", min_length=1, max_length=32)


class RecallSyncJobResponse(BaseModel):
    job_id: str
    status: Literal["queued"] = "queued"
