from datetime import datetime
from typing import Any, Literal
from uuid import UUID

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, model_validator

AllergyMatchType = Literal["DIRECT_SALT", "DRUG_CLASS"]


class AllergyEntry(BaseModel):
    allergen: str = Field(min_length=1, max_length=160)
    reaction: str | None = Field(default=None, max_length=255)
    severity: Literal["SEVERE", "MODERATE", "MILD"] | None = None

    @model_validator(mode="after")
    def validate_allergen(self) -> "AllergyEntry":
        cleaned = self.allergen.strip()
        if not cleaned:
            raise ValueError("allergen cannot be blank.")
        self.allergen = cleaned
        if self.reaction is not None:
            self.reaction = self.reaction.strip() or None
        return self


class AllergyAlert(BaseModel):
    allergen: str
    canonical_salt: str
    match_type: AllergyMatchType
    matched_class: str | None = None
    severity: Literal["MAJOR"] = "MAJOR"
    reason: str


class ProfileCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    relationship: str = Field(default="other", min_length=1, max_length=64)
    allergies_json: list[str | AllergyEntry] = Field(
        default_factory=list,
        validation_alias=AliasChoices("allergies_json", "allergies"),
    )
    notes: str | None = Field(default=None, max_length=2000)
    is_default: bool = False

    @model_validator(mode="after")
    def validate_fields(self) -> "ProfileCreateRequest":
        cleaned_name = self.name.strip()
        cleaned_rel = self.relationship.strip()
        if not cleaned_name:
            raise ValueError("name cannot be blank.")
        if not cleaned_rel:
            raise ValueError("relationship cannot be blank.")
        self.name = cleaned_name
        self.relationship = cleaned_rel
        if self.notes is not None:
            self.notes = self.notes.strip() or None
        return self


class ProfileUpdateRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)
    relationship: str | None = Field(default=None, min_length=1, max_length=64)
    allergies_json: list[str | AllergyEntry] | None = Field(
        default=None,
        validation_alias=AliasChoices("allergies_json", "allergies"),
    )
    notes: str | None = Field(default=None, max_length=2000)
    is_default: bool | None = None

    @model_validator(mode="after")
    def validate_optional_fields(self) -> "ProfileUpdateRequest":
        if self.name is not None:
            cleaned_name = self.name.strip()
            if not cleaned_name:
                raise ValueError("name cannot be blank.")
            self.name = cleaned_name
        if self.relationship is not None:
            cleaned_rel = self.relationship.strip()
            if not cleaned_rel:
                raise ValueError("relationship cannot be blank.")
            self.relationship = cleaned_rel
        if self.notes is not None:
            self.notes = self.notes.strip() or None
        return self


class ProfileResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    user_id: UUID
    name: str
    relationship: str
    allergies_json: list[str | dict[str, Any]]
    notes: str | None
    is_default: bool
    created_at: datetime
    updated_at: datetime
