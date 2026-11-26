from __future__ import annotations

import base64
import io
import json
import re
import wave
from uuid import uuid4

import httpx
import pytest

from backend.app.api.assistant import get_supported_languages
from backend.app.core.config import Settings
from backend.app.core.languages import (
    SUPPORTED_LANGUAGES,
    get_language_spec,
    list_supported_languages,
    normalize_language_code,
    prepare_text_for_speech,
)
from backend.app.schemas.assistant import AssistantAskRequest, AssistantTtsRequest
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
    build_spoken_clinical_summary,
    verify_clinical_briefing,
)
from backend.app.services.voice_assistant import (
    AssistantContext,
    SpeechSynthesisUnavailableError,
    _build_deterministic_answer,
    _extract_indic_alias_salts,
    _RawAssistantAnswer,
    _verify_assistant_answer,
    derive_speech_tone,
    is_cloud_tts_available,
    synthesize_speech_audio,
)


class _InMemoryAsyncRedis:
    def __init__(self) -> None:
        self.store: dict[str, bytes] = {}
        self.get_calls: int = 0
        self.set_calls: int = 0

    async def get(self, key: str) -> bytes | None:
        self.get_calls += 1
        return self.store.get(key)

    async def set(self, key: str, value: bytes, ex: int | None = None) -> bool:
        self.set_calls += 1
        self.store[key] = value
        return True


def _make_sample_wav(duration_ms: int = 40, sample_rate: int = 24000) -> bytes:
    buf = io.BytesIO()
    frame_count = int(sample_rate * (duration_ms / 1000.0))
    with wave.open(buf, "wb") as wav_out:
        wav_out.setnchannels(1)
        wav_out.setsampwidth(2)
        wav_out.setframerate(sample_rate)
        wav_out.writeframes(b"\x00\x01" * frame_count)
    return buf.getvalue()


def test_supported_indian_languages_registry_and_edge_voice_mappings() -> None:
    specs = list_supported_languages()
    codes = {s.code for s in specs}
    assert codes == {"en", "hi", "bn", "te", "mr", "ta", "gu", "kn", "ml", "pa"}
    assert len(SUPPORTED_LANGUAGES) == 10

    expected_voices = {
        "en": "en-IN-NeerjaNeural",
        "hi": "hi-IN-SwaraNeural",
        "bn": "bn-IN-TanishaaNeural",
        "te": "te-IN-ShrutiNeural",
        "mr": "mr-IN-AarohiNeural",
        "ta": "ta-IN-PallaviNeural",
        "gu": "gu-IN-DhwaniNeural",
        "kn": "kn-IN-SapnaNeural",
        "ml": "ml-IN-SobhanaNeural",
        "pa": "pa-IN-VaaniNeural",
    }
    for code, expected_voice in expected_voices.items():
        spec = get_language_spec(code)
        assert spec.edge_tts_voice == expected_voice
        assert spec.verbal_major_warning_prefix.strip()

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

    tts_req = AssistantTtsRequest(text="Hello", language="hi-IN", tone="urgent")
    assert tts_req.language == "hi"
    assert tts_req.tone == "urgent"
    assert tts_req.speech_rate == pytest.approx(0.94)


@pytest.mark.asyncio
async def test_languages_endpoint_reports_cloud_tts_availability() -> None:
    edge_only = Settings(app_env="test", gemini_api_key="", tts_edge_enabled=True)
    assert is_cloud_tts_available(edge_only) is True
    resp_edge = await get_supported_languages(settings=edge_only)
    assert resp_edge.cloud_tts_available is True
    assert len(resp_edge.languages) == 10
    voice_by_code = {item.code: item.edge_tts_voice for item in resp_edge.languages}
    assert voice_by_code["hi"] == "hi-IN-SwaraNeural"
    assert voice_by_code["pa"] == "pa-IN-VaaniNeural"

    gemini_only = Settings(app_env="test", gemini_api_key="test-key", tts_edge_enabled=False)
    assert is_cloud_tts_available(gemini_only) is True
    resp_gemini = await get_supported_languages(settings=gemini_only)
    assert resp_gemini.cloud_tts_available is True

    none_enabled = Settings(app_env="test", gemini_api_key="", tts_edge_enabled=False)
    assert is_cloud_tts_available(none_enabled) is False
    resp_none = await get_supported_languages(settings=none_enabled)
    assert resp_none.cloud_tts_available is False


def test_spoken_text_unit_expansion_sanitization_deduplication_and_major_prefix() -> None:
    raw_en = (
        "- **Take** 500mg BID (or 5 mg/5 mL) with 50 mcg OD and ratio 1:1000 [1]. "
        "Check https://dailymed.nlm.nih.gov/info for details.\n"
        "Take 500mg BID (or 5 mg/5 mL) with 50 mcg OD and ratio 1:1000 [2].\n"
        "Take 250/125 mg TID with food because taking this medicine after a light meal "
        "helps reduce stomach irritation and supports comfortable absorption every day."
    )
    spoken_en = prepare_text_for_speech(raw_en, language="en", is_major_alert=True)
    assert spoken_en.startswith(get_language_spec("en").verbal_major_warning_prefix)
    assert "milligrams" in spoken_en
    assert "micrograms" in spoken_en
    assert "milliliters" in spoken_en
    assert "twice a day" in spoken_en
    assert "three times a day" in spoken_en
    assert "once a day" in spoken_en
    assert "1 to 1000" in spoken_en
    assert "5 milligrams per 5 milliliters" in spoken_en
    assert "250 and 125 milligrams" in spoken_en
    for forbidden in ("**", "[", "]", "(", ")", "http", "•", "/"):
        assert forbidden not in spoken_en

    sentences = [s.strip() for s in re.split(r"(?<=[.!?।॥])\s+", spoken_en) if s.strip()]
    assert len(sentences) == len({s.lower() for s in sentences})
    for sentence in sentences:
        assert len(sentence.split()) < 18

    spoken_hi = prepare_text_for_speech(
        "डोलो 500mg BID और 5 mL TID लें [1]। डोलो 500mg BID और 5 mL TID लें।",
        language="hi",
        is_major_alert=True,
    )
    assert spoken_hi.startswith(get_language_spec("hi").verbal_major_warning_prefix)
    assert "मिलीग्राम" in spoken_hi
    assert "मिलीलीटर" in spoken_hi
    assert "दिन में दो बार" in spoken_hi
    assert "दिन में तीन बार" in spoken_hi
    assert spoken_hi.count("दिन में दो बार") == 1


def test_multilingual_clinical_briefings_and_indic_downgrade_guard() -> None:
    chunk_id = uuid4()
    citation = MonographCitation(
        chunk_id=chunk_id,
        set_id="spl-warf-1",
        canonical_salt="warfarin",
        section_key="drug_interactions",
        section_title="DRUG INTERACTIONS",
        loinc_section_code="34073-7",
        excerpt="Concomitant aspirin 75 mg OD increases risk of severe gastrointestinal bleeding.",
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
        assert "[" not in extractive.patient_explanation
        assert get_language_spec(code).verbal_major_warning_prefix in extractive.patient_explanation

        spoken_briefing = build_spoken_clinical_summary(extractive, is_major_alert=True)
        assert spoken_briefing.startswith(get_language_spec(code).verbal_major_warning_prefix)
        assert "[" not in spoken_briefing

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
        assert get_language_spec(code).verbal_major_warning_prefix in abstain.patient_explanation

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
    assert "18 words" in prompt

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

    assert derive_speech_tone("MAJOR", False) == "urgent"
    assert derive_speech_tone("NONE", True) == "urgent"
    assert derive_speech_tone("MODERATE", False) == "caution"
    assert derive_speech_tone("NONE", False) == "reassuring"

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
        assert ans.spoken_text.startswith(spec.verbal_major_warning_prefix)
        assert len(ans.suggested_followups) == 3

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
async def test_tts_redis_cache_hit_and_gemini_style_chunking() -> None:
    redis_cache = _InMemoryAsyncRedis()
    sample_wav = _make_sample_wav(duration_ms=40)
    request_payloads: list[dict[str, object]] = []

    def _mock_gemini_tts(request: httpx.Request) -> httpx.Response:
        request_payloads.append(json.loads(request.read().decode("utf-8")))
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
                                "data": base64.b64encode(sample_wav).decode("ascii"),
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
        tts_gemini_max_chunk_chars=140,
        tts_edge_enabled=True,
    )
    long_text = (
        "Take paracetamol 500 mg twice a day after meals. "
        "Drink plenty of clean water throughout the afternoon. "
        "Keep a gap of six hours between every dose. "
        "Keep a gap of six hours between every dose. "
        "Call your doctor if fever lasts beyond three days."
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(_mock_gemini_tts)) as mock_client:
        audio_1, mime_1 = await synthesize_speech_audio(
            text=long_text,
            language="en-IN",
            tone="urgent",
            speech_rate=0.94,
            settings=tts_settings,
            http_client=mock_client,
            redis_client=redis_cache,
        )
        first_call_count = len(request_payloads)
        assert first_call_count >= 2
        assert audio_1.startswith(b"RIFF")
        assert mime_1 == "audio/wav"
        assert redis_cache.set_calls == 1

        first_input = request_payloads[0]["input"]
        assert isinstance(first_input, list)
        content_item = first_input[0]["content"][0]
        style_text = content_item["annotations"][0]["style"]
        transcript_text = content_item["text"]
        assert "gently firm" in style_text
        assert "gently firm" not in transcript_text
        assert "milligrams" in transcript_text

        audio_2, mime_2 = await synthesize_speech_audio(
            text=long_text,
            language="en-IN",
            tone="urgent",
            speech_rate=0.94,
            settings=tts_settings,
            http_client=mock_client,
            redis_client=redis_cache,
        )
        assert audio_2 == audio_1
        assert mime_2 == "audio/wav"
        assert len(request_payloads) == first_call_count
        assert redis_cache.get_calls == 2


@pytest.mark.asyncio
async def test_tts_falls_back_from_gemini_429_and_error_to_edge_tts_all_languages() -> None:
    redis_cache = _InMemoryAsyncRedis()
    edge_calls: list[tuple[str, str, str]] = []
    fake_mp3 = b"ID3\x03\x00\x00\x00\x00\x00\x00fake-mp3-audio"

    async def _fake_edge_synth(text: str, voice: str, rate: str) -> tuple[bytes, str]:
        edge_calls.append((text, voice, rate))
        return fake_mp3, "audio/mpeg"

    def _mock_gemini_429(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": {"message": "Rate limit exceeded"}})

    settings_429 = Settings(
        app_env="test",
        gemini_api_key="test-gemini-key",
        tts_edge_enabled=True,
        tts_edge_rate="-6%",
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(_mock_gemini_429)) as mock_client:
        audio_bytes, media_type = await synthesize_speech_audio(
            text="नमस्ते, यह सुरक्षा जांच है।",
            language="hi-IN",
            tone="reassuring",
            speech_rate=0.94,
            settings=settings_429,
            http_client=mock_client,
            redis_client=redis_cache,
            edge_synthesizer=_fake_edge_synth,
        )

    assert audio_bytes == fake_mp3
    assert media_type == "audio/mpeg"
    assert len(edge_calls) == 1
    assert edge_calls[0][1] == "hi-IN-SwaraNeural"
    assert edge_calls[0][2] == "-6%"

    def _mock_gemini_500(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, json={"error": {"message": "Internal error"}})

    edge_calls.clear()
    async with httpx.AsyncClient(transport=httpx.MockTransport(_mock_gemini_500)) as mock_client:
        for spec in list_supported_languages():
            audio_out, mime_out = await synthesize_speech_audio(
                text=f"Sample medication safety check for {spec.code}.",
                language=spec.code,
                tone="caution",
                speech_rate=0.94,
                settings=settings_429,
                http_client=mock_client,
                redis_client=_InMemoryAsyncRedis(),
                edge_synthesizer=_fake_edge_synth,
            )
            assert audio_out == fake_mp3
            assert mime_out == "audio/mpeg"

    assert [call[1] for call in edge_calls] == [
        spec.edge_tts_voice for spec in list_supported_languages()
    ]
    assert all(call[2] == "-6%" for call in edge_calls)

    disabled_settings = Settings(app_env="test", gemini_api_key="", tts_edge_enabled=False)
    with pytest.raises(SpeechSynthesisUnavailableError):
        await synthesize_speech_audio(
            text="नमस्ते, यह सुरक्षा जांच है।",
            language="hi",
            settings=disabled_settings,
        )
