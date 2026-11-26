from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field

DoseSlotName = Literal[
    "Morning (Empty Stomach)",
    "Morning (With Food)",
    "Afternoon",
    "Evening",
    "Bedtime",
]

ScheduleClashType = Literal[
    "MAJOR_DDI",
    "DUPLICATE_ACTIVE_INGREDIENT",
    "TIMING_UNSATISFIABLE",
]


class ScheduledDoseItem(BaseModel):
    medication_id: UUID
    display_name: str
    canonical_salt_keys: list[str]
    dosage_schedule: str | None = None
    slot: DoseSlotName
    recommended_time: str
    dose_number: int = 1
    total_daily_doses: int = 1
    rationale: list[str] = Field(default_factory=list)
    separation_notes: list[str] = Field(default_factory=list)


class ScheduleSlotGroup(BaseModel):
    slot: DoseSlotName
    recommended_time: str
    medications: list[ScheduledDoseItem] = Field(default_factory=list)


class AppliedSeparationConstraint(BaseModel):
    medication_a_id: UUID
    medication_a_name: str
    salt_a: str
    medication_b_id: UUID
    medication_b_name: str
    salt_b: str
    min_separation_minutes: int
    actual_separation_minutes: int
    satisfied: bool
    reason: str


class UnresolvableScheduleClash(BaseModel):
    medication_a_id: UUID
    medication_a_name: str
    salt_a: str
    medication_b_id: UUID
    medication_b_name: str
    salt_b: str
    severity: Literal["MAJOR"] = "MAJOR"
    clash_type: ScheduleClashType
    mechanism: str
    management: str
    reason: str


class ProfileScheduleResponse(BaseModel):
    profile_id: UUID
    profile_name: str
    safety_banner_locked: bool
    slots: list[ScheduleSlotGroup]
    applied_separations: list[AppliedSeparationConstraint] = Field(default_factory=list)
    unresolvable_clashes: list[UnresolvableScheduleClash] = Field(default_factory=list)
    disclaimer: str = (
        "Automated daily dose schedule for educational support only; confirm medication "
        "timing and interactions with a licensed pharmacist or clinician."
    )
