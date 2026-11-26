from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field

MatchStrategy = Literal["exact_brand", "trigram_brand", "exact_salt", "ocr_salt_parse"]


class SaltComponent(BaseModel):
    canonical_salt: str = Field(min_length=1, max_length=160)
    strength_mg: float | None = Field(default=None, ge=0)
    strength_text: str | None = Field(default=None, max_length=64)
    rxcui: str | None = Field(default=None, max_length=32)


class DrugResolveRequest(BaseModel):
    query: str = Field(min_length=1, max_length=255)
    raw_ocr_text: str | None = Field(default=None, max_length=2000)
    country_code: str | None = Field(default=None, min_length=2, max_length=2)
    limit: int = Field(default=5, ge=1, le=20)


class BrandCandidate(BaseModel):
    brand_mapping_id: UUID | None = None
    brand_name: str
    manufacturer: str | None = None
    country_code: str = "IN"
    dosage_form: str | None = None
    generic_salts: list[SaltComponent]
    canonical_salt_keys: list[str]
    match_strategy: MatchStrategy
    confidence: float = Field(ge=0.0, le=1.0)


class DrugResolveResponse(BaseModel):
    query: str
    normalized_query: str
    candidates: list[BrandCandidate]
    extracted_salts: list[SaltComponent]
