from __future__ import annotations

import base64
from uuid import uuid4

import httpx
import pytest

from backend.app.core.config import Settings
from backend.app.core.languages import (
    SUPPORTED_LANGUAGES,
    get_language_spec,
    list_supported_languages,
    normalize_language_code,
)
from backend.app.schemas.assistant import AssistantAskRequest
from backend.app.schemas.cabinet import DuplicateSaltAlert
from backend.app.schemas.profiles import AllergyAlert
from backend.app.schemas.safety import (
    ClinicalSummary,
    DeterministicDdiAlert,
    MonographCitation,
    ScanAnalyzeRequest,
)
from backend.app.services.clinical_briefing import (
    BriefingContext,
    _build_llm_prompt,
    build_abstention_summary,
    build_extractive_grounded_summary,
    verify_clinical_briefing,
)
from backend.app.services.voice_assistant import (
    AssistantContext,
    SpeechSynthesisUnavailableError,
    _build_deterministic_answer,
    _extract_indic_alias_salts,
    _RawAssistantAnswer,
    _verify_assistant_answer,
    synthesize_speech_audio,
)


def test_supported_indian_languages_registry_and_normalization() -> None:
    specs = list_supported_languages()
    codes = {s.code for s in specs}
    assert codes == {"en", "hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa"}
    assert len(SUPPORTED_LANGUAGES) == 10

    assert normalize_language_code("hi-IN") == "hi"
    assert normalize_language_code("ta_IN") == "ta"
    assert normalize_language_code("தமிழ்") == "ta"
    assert normalize_language_code("हिन्दी") == "hi"
    assert normalize_language_code("bn-IN") == "bn"
    assert normalize_language_code("unknown-xx") == "en"
    assert normalize_language_code(None) == "en"

    req = ScanAnalyzeRequest(submitted_query="Dolo 650", language="hi-IN")
    assert req.language == "hi"

    ask_req = AssistantAskRequest(question="क्या मैं डोलो 650 ले सकता हूँ?", language="te-IN")
    assert ask_req.language == "te"


def test_multilingual_clinical_briefings_and_indic_downgrade_guard() -> None:
    chunk_id = uuid4()
    citation = MonographCitation(
        chunk_id=chunk_id,
        set_id="spl-warf-1",
        canonical_salt="warfarin",
        section_key="drug_interactions",
        section_title="DRUG INTERACTIONS",
        loinc_section_code="34073-7",
        excerpt="Concomitant aspirin increases risk of severe gastrointestinal bleeding.",
        source_url="https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=spl-warf-1",
        rerank_score=0.95,
    )

    for code in ("hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa"):
        ctx = BriefingContext(
            submitted_query="Ecosprin 75",
            resolved_salts=["aspirin"],
            overall_risk="MAJOR",
            safety_banner_locked=True,
            duplicate_salts=[],
            deterministic_alerts=[
                DeterministicDdiAlert(
                    rule_id="ddi:major",
                    salt_a="aspirin",
                    salt_b="warfarin",
                    severity="MAJOR",
                    mechanism="Additive bleeding risk.",
                    clinical_effect="High risk of severe bleeding.",
                    management="Avoid combining without INR monitoring.",
                    sources=["ddinter_2"],
                )
            ],
            interactions=[],
            citations=[citation],
            language=code,
        )
        extractive = build_extractive_grounded_summary(ctx)
        assert extractive.language == code
        assert extractive.disclaimer == get_language_spec(code).disclaimer
        assert extractive.cited_chunk_ids == [chunk_id]

        abstain = build_abstention_summary(
            BriefingContext(
                submitted_query="Ecosprin 75",
                resolved_salts=["aspirin"],
                overall_risk="MAJOR",
                safety_banner_locked=True,
                duplicate_salts=[],
                deterministic_alerts=[],
                interactions=[],
                citations=[],
                language=code,
            )
        )
        assert abstain.abstained is True
        assert abstain.language == code
        assert abstain.disclaimer == get_language_spec(code).disclaimer

    # Hindi LLM prompt explicitly mandates Hindi Devanagari output
    hi_ctx = BriefingContext(
        submitted_query="Ecosprin 75",
        resolved_salts=["aspirin"],
        overall_risk="MAJOR",
        safety_banner_locked=True,
        duplicate_salts=[],
        deterministic_alerts=[],
        interactions=[],
        citations=[citation],
        language="hi",
    )
    prompt = _build_llm_prompt(hi_ctx)
    assert "Hindi (हिन्दी)" in prompt
    assert "Devanagari" in prompt

    # Hindi downgrade attempt must be caught and overridden by the deterministic verifier
    bad_hindi = ClinicalSummary(
        headline="एक साथ लेना सुरक्षित है।",
        patient_explanation="कोई गंभीर इंटरैक्शन नहीं पाया गया।",
        actionable_next_steps=["लेते रहें।"],
        cited_chunk_ids=[chunk_id],
        language="hi",
    )
    verified = verify_clinical_briefing(bad_hindi, hi_ctx)
    assert verified.citation_verified is True
    assert "एक साथ लेना सुरक्षित" not in verified.summary.headline
    assert verified.summary.language == "hi"


def test_indic_alias_extraction_and_deterministic_assistant_answers_all_languages() -> None:
    assert "acetaminophen" in _extract_indic_alias_salts("क्या मैं डोलो 650 ले सकता हूँ?")
    assert "warfarin" in _extract_indic_alias_salts("वॉर्फ 5 और एस्प्रिन साथ में")
    assert "aspirin" in _extract_indic_alias_salts("वॉर्फ 5 और एस्प्रिन साथ में")
    assert "acetaminophen" in _extract_indic_alias_salts("பாராசிட்டமால் எடுக்கலாமா?")
    assert "acetaminophen" in _extract_indic_alias_salts("పారాసిటమాల్ తీసుకోవచ్చా?")

    for spec in list_supported_languages():
        ctx = AssistantContext(
            question="Can I take Augmentin?",
            language=spec.code,
            profile_name="Mom",
            allergies=["Penicillin"],
            active_medications=[
                {
                    "display_name": "Mox 500",
                    "canonical_salt_keys": ["amoxicillin"],
                    "dosage_schedule": "1 cap TID",
                }
            ],
            mentioned_salts=["amoxicillin"],
            overall_risk="MAJOR",
            safety_banner_locked=True,
            duplicate_salts=[
                DuplicateSaltAlert(
                    canonical_salt="amoxicillin",
                    existing_medication_id=uuid4(),
                    existing_display_name="Mox 500",
                )
            ],
            allergy_alerts=[
                AllergyAlert(
                    allergen="Penicillin",
                    canonical_salt="amoxicillin",
                    match_type="DRUG_CLASS",
                    matched_class="penicillin",
                    reason="Penicillin allergy conflict.",
                )
            ],
        )
        ans = _build_deterministic_answer(ctx)
        assert ans.answer_text.strip()
        assert ans.spoken_text.strip()
        assert len(ans.suggested_followups) == 3

        # Verify assistant safety guard rejects Hindi downgrade when locked
        fake_raw = _RawAssistantAnswer(
            answer_text="एक साथ लेना सुरक्षित है, low risk.",
            spoken_text="एक साथ लेना सुरक्षित है।",
            suggested_followups=[],
            cited_chunk_ids=[],
        )
        checked, _ = _verify_assistant_answer(fake_raw, ctx)
        assert "low risk" not in checked.answer_text.lower()
        assert "एक साथ लेना सुरक्षित" not in checked.answer_text


@pytest.mark.asyncio
async def test_gemini_tts_synthesis_and_unavailable_fallback() -> None:
    # Without API key, synthesize_speech_audio signals device TTS fallback cleanly
    empty_settings = Settings(app_env="test", llm_provider="gemini", gemini_api_key="")
    with pytest.raises(SpeechSynthesisUnavailableError):
        await synthesize_speech_audio(
            text="नमस्ते, यह सुरक्षा जांच है।",
            language="hi",
            settings=empty_settings,
        )

    # With Gemini API key, calls /v1beta/interactions and decodes base64 WAV audio
    fake_wav = b"RIFF\x24\x00\x00\x00WAVEfmt "
    captured: dict[str, object] = {}

    def _mock_gemini_tts(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["api_key"] = request.headers.get("x-goog-api-key")
        captured["body"] = request.read().decode("utf-8")
        return httpx.Response(
            200,
            json={
                "steps": [
                    {
                        "type": "model_output",
                        "content": [
                            {
                                "type": "audio",
                                "mime_type": "audio/wav",
                                "data": base64.b64encode(fake_wav).decode("ascii"),
                            }
                        ],
                    }
                ]
            },
        )

    tts_settings = Settings(
        app_env="test",
        llm_provider="gemini",
        gemini_api_key="test-gemini-key",
        gemini_tts_model="gemini-3.8-flash-tts",
        gemini_tts_voice="Kore",
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(_mock_gemini_tts)) as mock_client:
        audio_bytes, media_type = await synthesize_speech_audio(
            text="नमस्ते, यह सुरक्षा जांच है।",
            language="hi-IN",
            settings=tts_settings,
            http_client=mock_client,
        )

    assert audio_bytes == fake_wav
    assert media_type == "audio/wav"
    assert captured["url"] == "https://generativelanguage.googleapis.com/v1beta/interactions"
    assert captured["api_key"] == "test-gemini-key"
    assert "hi-IN" in str(captured["body"])
    assert "gemini-3.8-flash-tts" in str(captured["body"])
