from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class ScanLogResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    user_id: UUID
    profile_id: UUID
    input_mode: str
    raw_ocr_text: str | None
    edge_quality_metrics: dict[str, Any] | None
    submitted_query: str
    resolved_salts: list[dict[str, Any]]
    cabinet_snapshot: list[dict[str, Any]]
    overall_risk: str
    safety_banner_locked: bool
    deterministic_alerts: list[dict[str, Any]]
    duplicate_salts: list[dict[str, Any]]
    allergy_alerts: list[dict[str, Any]] = Field(default_factory=list)
    lifestyle_triggers: list[dict[str, Any]] = Field(default_factory=list)
    active_recalls: list[dict[str, Any]] = Field(default_factory=list)
    interactions: list[dict[str, Any]]
    clinical_summary: dict[str, Any] | None
    citations: list[dict[str, Any]]
    citation_verified: bool
    trace_id: str | None
    latency_ms: int | None
    created_at: datetime


class ScanLogListResponse(BaseModel):
    items: list[ScanLogResponse]
    total: int
    limit: int
    offset: int
