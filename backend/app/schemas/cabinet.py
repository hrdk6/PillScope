from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

from backend.app.schemas.drugs import SaltComponent

RiskLevel = Literal["MAJOR", "MODERATE", "MINOR", "NONE"]


class DuplicateSaltAlert(BaseModel):
    canonical_salt: str
    existing_medication_id: UUID
    existing_display_name: str
    reason: Literal["DUPLICATE_ACTIVE_INGREDIENT"] = "DUPLICATE_ACTIVE_INGREDIENT"


class MedicationCreateRequest(BaseModel):
    brand_mapping_id: UUID | None = None
    display_name: str | None = Field(default=None, min_length=1, max_length=255)
    generic_salts: list[SaltComponent] | None = None
    dosage_schedule: str | None = Field(default=None, max_length=120)
    is_active: bool = True
    started_at: datetime | None = None
    ended_at: datetime | None = None

    @model_validator(mode="after")
    def validate_source_fields(self) -> "MedicationCreateRequest":
        if self.brand_mapping_id is None and (not self.display_name or not self.generic_salts):
            raise ValueError(
                "Provide either brand_mapping_id or both display_name and generic_salts."
            )
        return self


class MedicationUpdateRequest(BaseModel):
    display_name: str | None = Field(default=None, min_length=1, max_length=255)
    dosage_schedule: str | None = Field(default=None, max_length=120)
    is_active: bool | None = None
    started_at: datetime | None = None
    ended_at: datetime | None = None


class MedicationResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    user_id: UUID
    brand_mapping_id: UUID | None
    display_name: str
    generic_salts: list[SaltComponent]
    canonical_salt_keys: list[str]
    dosage_schedule: str | None
    is_active: bool
    started_at: datetime | None
    ended_at: datetime | None
    created_at: datetime
    updated_at: datetime


class MedicationCreateResponse(BaseModel):
    medication: MedicationResponse
    overall_risk: RiskLevel
    safety_banner_locked: bool
    duplicate_salts: list[DuplicateSaltAlert]


class DuplicateCheckRequest(BaseModel):
    brand_name: str | None = Field(default=None, min_length=1, max_length=255)
    confirmed_salts: list[str] | None = None
    raw_ocr_text: str | None = Field(default=None, max_length=2000)

    @model_validator(mode="after")
    def validate_candidate_input(self) -> "DuplicateCheckRequest":
        if not self.brand_name and not self.confirmed_salts and not self.raw_ocr_text:
            raise ValueError("Provide brand_name, confirmed_salts, or raw_ocr_text.")
        return self


class DuplicateCheckResponse(BaseModel):
    resolved_salts: list[str]
    overall_risk: RiskLevel
    safety_banner_locked: bool
    duplicate_salts: list[DuplicateSaltAlert]
