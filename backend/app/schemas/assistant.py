from __future__ import annotations

from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

from backend.app.core.languages import SpeechTone, normalize_language_code
from backend.app.schemas.cabinet import RiskLevel
from backend.app.schemas.safety import MonographCitation


class SupportedLanguageInfo(BaseModel):
    code: str
    bcp47: str
    english_name: str
    native_name: str
    script_name: str
    edge_tts_voice: str = ""


class SupportedLanguagesResponse(BaseModel):
    default_language: str = "en"
    cloud_tts_available: bool = True
    languages: list[SupportedLanguageInfo]


class AssistantTurn(BaseModel):
    role: Literal["user", "assistant"]
    text: str = Field(min_length=1, max_length=1500)


class AssistantAskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    language: str = Field(default="en", min_length=2, max_length=16)
    profile_id: UUID | None = None
    scan_id: UUID | None = None
    context_medicine_name: str | None = Field(default=None, max_length=255)
    context_salts: list[str] = Field(default_factory=list, max_length=20)
    conversation_history: list[AssistantTurn] = Field(default_factory=list, max_length=8)

    @model_validator(mode="after")
    def normalize_fields(self) -> AssistantAskRequest:
        cleaned = self.question.strip()
        if not cleaned:
            raise ValueError("question cannot be blank.")
        self.question = cleaned
        self.language = normalize_language_code(self.language)
        if self.context_medicine_name is not None:
            self.context_medicine_name = self.context_medicine_name.strip() or None
        return self


class AssistantAskResponse(BaseModel):
    answer_text: str
    spoken_text: str
    language: str
    bcp47_locale: str
    overall_risk: RiskLevel
    safety_banner_locked: bool
    tts_tone: SpeechTone = "reassuring"
    referenced_salts: list[str] = Field(default_factory=list)
    suggested_followups: list[str] = Field(default_factory=list)
    citations: list[MonographCitation] = Field(default_factory=list)
    disclaimer: str
    provider: str = "deterministic_grounded"


class AssistantTtsRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    language: str = Field(default="en", min_length=2, max_length=16)
    voice: str | None = Field(default=None, max_length=64)
    tone: SpeechTone = "reassuring"
    speech_rate: float = Field(default=0.94, ge=0.70, le=1.35)

    @model_validator(mode="after")
    def normalize_fields(self) -> AssistantTtsRequest:
        cleaned = self.text.strip()
        if not cleaned:
            raise ValueError("text cannot be blank.")
        self.text = cleaned
        self.language = normalize_language_code(self.language)
        return self
