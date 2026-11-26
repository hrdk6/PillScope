from typing import Literal

from pydantic import BaseModel, Field

LifestyleTriggerType = Literal[
    "ALCOHOL",
    "GRAPEFRUIT",
    "DAIRY",
    "CAFFEINE",
    "EMPTY_STOMACH",
    "WITH_FOOD",
    "DROWSINESS",
    "SUN_SENSITIVITY",
]

LifestyleTriggerSeverity = Literal["HIGH", "MODERATE", "INFO"]


class LifestyleTrigger(BaseModel):
    type: LifestyleTriggerType
    severity: LifestyleTriggerSeverity
    short_note: str = Field(min_length=1, max_length=280)
    source_section: str = Field(min_length=1, max_length=160)
