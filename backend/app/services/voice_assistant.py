from __future__ import annotations

import base64
import hashlib
import importlib.util
import io
import json
import re
import wave
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Protocol
from uuid import UUID

import httpx
import redis.asyncio as aioredis
import structlog
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.app.core.config import Settings, get_settings
from backend.app.core.languages import (
    MULTILINGUAL_DOWNGRADE_PATTERNS,
    LanguageSpec,
    SpeechTone,
    SupportedLanguageCode,
    deduplicate_sentences,
    get_language_spec,
    language_prompt_instruction,
    normalize_language_code,
    prepare_text_for_speech,
)
from backend.app.db.models import Medication, Profile, ScanLog
from backend.app.schemas.assistant import (
    AssistantAskRequest,
    AssistantAskResponse,
)
from backend.app.schemas.cabinet import DuplicateSaltAlert, RiskLevel
from backend.app.schemas.drugs import DrugResolveRequest
from backend.app.schemas.lifestyle import LifestyleTrigger
from backend.app.schemas.profiles import AllergyAlert
from backend.app.schemas.recalls import MedicationRecallFlag
from backend.app.schemas.safety import DeterministicDdiAlert, MonographCitation
from backend.app.services.allergy_checker import detect_allergy_conflicts
from backend.app.services.brand_resolver import BrandSaltResolver
from backend.app.services.deterministic_safety import (
    detect_deterministic_ddi_alerts,
    detect_duplicate_salts,
    max_risk,
)
from backend.app.services.dose_scheduler import build_profile_schedule
from backend.app.services.drug_normalizer import canonical_salt_keys, parse_ocr_salts
from backend.app.services.hybrid_rag import HybridRagRetriever
from backend.app.services.lifestyle_triggers import extract_lifestyle_triggers_for_salts
from backend.app.services.profiles import resolve_user_profile
from backend.app.services.recall_watchdog import match_active_recalls_for_salts

logger = structlog.get_logger(__name__)

_UUID_RE = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b",
    re.IGNORECASE,
)
_TTS_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?।॥])\s+")
_TTS_CACHE_KEY_PREFIX = "pillscope:tts:v1:"
_DEFAULT_PCM_SAMPLE_RATE = 24000
_DEFAULT_PCM_SAMPLE_WIDTH = 2
_DEFAULT_PCM_CHANNELS = 1
_CHUNK_PAUSE_MS = 160

_INDIC_MEDICINE_ALIASES: dict[str, tuple[str, ...]] = {
    "पैरासिटामोल": ("acetaminophen",),
    "पॅरासिटामोल": ("acetaminophen",),
    "पेरासिटामोल": ("acetaminophen",),
    "डोलो": ("acetaminophen",),
    "क्रोसिन": ("acetaminophen",),
    "कैलपोल": ("acetaminophen",),
    "कॉम्बिफ्लेम": ("acetaminophen", "ibuprofen"),
    "एस्प्रिन": ("aspirin",),
    "इकोस्प्रिन": ("aspirin",),
    "वॉर्फ": ("warfarin",),
    "वारफारिन": ("warfarin",),
    "वॉरफेरिन": ("warfarin",),
    "ऑगमेंटिन": ("amoxicillin", "clavulanic acid"),
    "क्लैवम": ("amoxicillin", "clavulanic acid"),
    "अमोक्सिसिलिन": ("amoxicillin",),
    "एमोक्सिसिलिन": ("amoxicillin",),
    "मॉक्स": ("amoxicillin",),
    "इबुप्रोफेन": ("ibuprofen",),
    "ब्रूफेन": ("ibuprofen",),
    "मेटफॉर्मिन": ("metformin",),
    "ग्लाइकोमेट": ("metformin",),
    "एज़िथ्रोमाइसिन": ("azithromycin",),
    "पैंटॉप": ("pantoprazole",),
    "पैंटोप्राजोल": ("pantoprazole",),
    "थायरोनॉर्म": ("levothyroxine",),
    "लेवोथायरोक्सिन": ("levothyroxine",),
    "टेलमा": ("telmisartan",),
    "एटोरवास्टेटिन": ("atorvastatin",),
    "সালবুটামল": ("albuterol",),
    "প্যারাসিটামল": ("acetaminophen",),
    "ডোলো": ("acetaminophen",),
    "অ্যাসপিরিন": ("aspirin",),
    "ওয়ারফারিন": ("warfarin",),
    "অ্যামোক্সিসিলিন": ("amoxicillin",),
    "আইবুপ্রোফেন": ("ibuprofen",),
    "మెట్‌ఫార్మిన్": ("metformin",),
    "పారాసిటమాల్": ("acetaminophen",),
    "డోలో": ("acetaminophen",),
    "ఆస్పిరిన్": ("aspirin",),
    "వార్ఫరిన్": ("warfarin",),
    "அமோக்ஸிசிலின்": ("amoxicillin",),
    "பாராசிட்டமால்": ("acetaminophen",),
    "டோலோ": ("acetaminophen",),
    "ஆஸ்பிரின்": ("aspirin",),
    "வார்ஃபரின்": ("warfarin",),
    "પેરાસીટામોલ": ("acetaminophen",),
    "ડોલો": ("acetaminophen",),
    "એસ્પિરિન": ("aspirin",),
    "ಪ್ಯಾರಸಿಟಮಾಲ್": ("acetaminophen",),
    "ಡೋಲೋ": ("acetaminophen",),
    "ಆಸ್ಪಿರಿನ್": ("aspirin",),
    "പാരസെറ്റമോൾ": ("acetaminophen",),
    "ഡോളോ": ("acetaminophen",),
    "ആസ്പിരിൻ": ("aspirin",),
    "ਪੈਰਾਸੀਟਾਮੋਲ": ("acetaminophen",),
    "ਡੋਲੋ": ("acetaminophen",),
    "ਐਸਪਰੀਨ": ("aspirin",),
}

_SCHEDULE_KEYWORDS: tuple[str, ...] = (
    "schedule",
    "timing",
    "when",
    "morning",
    "night",
    "bedtime",
    "empty stomach",
    "with food",
    "before food",
    "after food",
    "कब",
    "समय",
    "सुबह",
    "रात",
    "खाली पेट",
    "কখন",
    "সময়",
    "ఎప్పుడు",
    "केव्हा",
    "எப்போது",
    "ક્યારે",
    "ಯಾವಾಗ",
    "എപ്പോൾ",
    "ਕਦੋਂ",
)

_FALLBACK_TEXTS: dict[SupportedLanguageCode, dict[str, str]] = {
    "en": {
        "major_header": "Critical safety alert for {profile}:",
        "allergy_line": "Allergy warning — {salt} conflicts with documented allergy '{allergen}'.",
        "duplicate_line": "Duplicate active ingredient — {salt} is already active in {med}.",
        "major_ddi_line": "Major interaction between {salt_a} and {salt_b}: {effect} {management}",
        "recall_line": "Active US FDA {classification} recall alert for {terms}: {reason}",
        "moderate_line": "{severity} interaction noted between {salt_a} and {salt_b}: {management}",
        "safe_checked": (
            "No major interactions, duplicate ingredients, or documented allergy conflicts were "
            "detected against {profile}'s active cabinet ({cabinet})."
        ),
        "empty_cabinet": (
            "{profile}'s medication cabinet has no active medicines saved yet. Add your active "
            "medicines or scan a medicine strip to check interactions."
        ),
        "cabinet_summary": "Active medicines for {profile}: {cabinet}.",
        "allergy_summary": "Recorded allergies: {allergies}.",
        "lifestyle_line": "Food & lifestyle note ({trigger}): {note}",
        "schedule_header": "Recommended daily timing for {profile}: {slots}.",
        "monograph_note": "From official FDA/DailyMed label ({salt}): {excerpt}",
        "major_action": (
            "Do not take or combine these medications without speaking to your doctor "
            "or pharmacist first."
        ),
    },
    "hi": {
        "major_header": "{profile} के लिए गंभीर सुरक्षा चेतावनी:",
        "allergy_line": "एलर्जी चेतावनी — {salt} आपकी दर्ज एलर्जी '{allergen}' से टकराता है।",
        "duplicate_line": "डुप्लिकेट सक्रिय तत्व — {salt} पहले से {med} में मौजूद है।",
        "major_ddi_line": "{salt_a} और {salt_b} के बीच गंभीर इंटरैक्शन: {effect} {management}",
        "recall_line": "{terms} के लिए सक्रिय US FDA {classification} रिकॉल चेतावनी: {reason}",
        "moderate_line": "{salt_a} और {salt_b} के बीच {severity} इंटरैक्शन मिला: {management}",
        "safe_checked": (
            "{profile} के सक्रिय कैबिनेट ({cabinet}) के साथ कोई गंभीर इंटरैक्शन, डुप्लिकेट सॉल्ट "
            "या एलर्जी टकराव नहीं मिला।"
        ),
        "empty_cabinet": (
            "{profile} के कैबिनेट में अभी कोई सक्रिय दवा सेव नहीं है। जांच के लिए दवा स्कैन करें "
            "या कैबिनेट में जोड़ें।"
        ),
        "cabinet_summary": "{profile} की सक्रिय दवाएं: {cabinet}।",
        "allergy_summary": "दर्ज एलर्जी: {allergies}।",
        "lifestyle_line": "खान-पान और जीवनशैली सलाह ({trigger}): {note}",
        "schedule_header": "{profile} का दैनिक दवा समय: {slots}।",
        "monograph_note": "आधिकारिक FDA/DailyMed लेबल ({salt}): {excerpt}",
        "major_action": "डॉक्टर या फार्मासिस्ट से पूछे बिना इन दवाओं को एक साथ न लें।",
    },
    "bn": {
        "major_header": "{profile}-এর জন্য গুরুতর নিরাপত্তা সতর্কতা:",
        "allergy_line": (
            "অ্যালার্জি সতর্কতা — {salt} আপনার নথিভুক্ত '{allergen}' অ্যালার্জির সাথে সাংঘর্ষিক।"
        ),
        "duplicate_line": "ডুপ্লিকেট উপাদান — {salt} ইতিমধ্যেই {med}-এ সক্রিয় আছে।",
        "major_ddi_line": "{salt_a} ও {salt_b}-এর মধ্যে গুরুতর ইন্টারঅ্যাকশন: {effect} {management}",
        "recall_line": "{terms}-এর জন্য সক্রিয় US FDA {classification} রিকল সতর্কতা: {reason}",
        "moderate_line": "{salt_a} ও {salt_b}-এর মধ্যে {severity} ইন্টারঅ্যাকশন পাওয়া গেছে: {management}",
        "safe_checked": (
            "{profile}-এর সক্রিয় ক্যাবিনেটের ({cabinet}) সাথে কোনো গুরুতর ইন্টারঅ্যাকশন, "
            "ডুপ্লিকেট সল্ট বা অ্যালার্জি সংঘাত মেলেনি।"
        ),
        "empty_cabinet": (
            "{profile}-এর ক্যাবিনেটে এখনো কোনো সক্রিয় ওষুধ সংরক্ষিত নেই। ওষুধ স্ক্যান করুন "
            "বা ক্যাবিনেটে যোগ করুন।"
        ),
        "cabinet_summary": "{profile}-এর সক্রিয় ওষুধসমূহ: {cabinet}।",
        "allergy_summary": "নথিভুক্ত অ্যালার্জি: {allergies}।",
        "lifestyle_line": "খাবার ও জীবনযাপন পরামর্শ ({trigger}): {note}",
        "schedule_header": "{profile}-এর দৈনিক ওষুধের সময়সূচী: {slots}।",
        "monograph_note": "অফিশিয়াল FDA/DailyMed লেবেল ({salt}): {excerpt}",
        "major_action": "চিকিৎসক বা ফার্মাসিস্টের পরামর্শ ছাড়া এই ওষুধগুলো একসাথে খাবেন না।",
    },
    "te": {
        "major_header": "{profile} కోసం ముఖ్యమైన భద్రతా హెచ్చరిక:",
        "allergy_line": "అలెర్జీ హెచ్చరిక — {salt} నమోదైన '{allergen}' అలెర్జీతో విభేదిస్తుంది.",
        "duplicate_line": "డూప్లికేట్ పదార్థం — {salt} ఇప్పటికే {med} లో ఉంది.",
        "major_ddi_line": "{salt_a} మరియు {salt_b} మధ్య తీవ్ర పరస్పర చర్య: {effect} {management}",
        "recall_line": "{terms} కోసం సక్రియ US FDA {classification} రీకాల్ హెచ్చరిక: {reason}",
        "moderate_line": "{salt_a} మరియు {salt_b} మధ్య {severity} పరస్పర చర్య: {management}",
        "safe_checked": (
            "{profile} సక్రియ క్యాబినెట్ ({cabinet}) తో తీవ్ర పరస్పర చర్యలు, డూప్లికేట్ సాల్ట్‌లు "
            "లేదా అలెర్జీ సమస్యలు కనుగొనబడలేదు."
        ),
        "empty_cabinet": (
            "{profile} క్యాబినెట్‌లో ఇంకా సక్రియ మందులు సేవ్ చేయబడలేదు. మందును స్కాన్ చేయండి "
            "లేదా క్యాబినెట్‌లో చేర్చండి."
        ),
        "cabinet_summary": "{profile} సక్రియ మందులు: {cabinet}.",
        "allergy_summary": "నమోదైన అలెర్జీలు: {allergies}.",
        "lifestyle_line": "ఆహారం మరియు జీవనశైలి సూచన ({trigger}): {note}",
        "schedule_header": "{profile} రోజువారీ మందుల సమయం: {slots}.",
        "monograph_note": "అధికారిక FDA/DailyMed లేబుల్ ({salt}): {excerpt}",
        "major_action": "వైద్యుడు లేదా ఫార్మసిస్ట్ సలహా లేకుండా ఈ మందులను కలిపి తీసుకోకండి.",
    },
    "mr": {
        "major_header": "{profile} साठी गंभीर सुरक्षा इशारा:",
        "allergy_line": "ॲलर्जी इशारा — {salt} तुमच्या '{allergen}' ॲलर्जीशी विसंगत आहे.",
        "duplicate_line": "डुप्लिकेट घटक — {salt} आधीच {med} मध्ये सक्रिय आहे.",
        "major_ddi_line": "{salt_a} आणि {salt_b} मध्ये गंभीर परस्परक्रिया: {effect} {management}",
        "recall_line": "{terms} साठी सक्रिय US FDA {classification} रिकॉल इशारा: {reason}",
        "moderate_line": "{salt_a} आणि {salt_b} मध्ये {severity} परस्परक्रिया: {management}",
        "safe_checked": (
            "{profile} च्या सक्रिय कॅबिनेट ({cabinet}) सोबत कोणतीही गंभीर परस्परक्रिया, "
            "डुप्लिकेट सॉल्ट किंवा ॲलर्जी आढळली नाही."
        ),
        "empty_cabinet": (
            "{profile} च्या कॅबिनेटमध्ये अद्याप कोणतीही सक्रिय औषधे जतन केलेली नाहीत. "
            "औषध स्कॅन करा किंवा कॅबिनेटमध्ये जोडा."
        ),
        "cabinet_summary": "{profile} ची सक्रिय औषधे: {cabinet}.",
        "allergy_summary": "नोंदवलेल्या ॲलर्जी: {allergies}.",
        "lifestyle_line": "आहार व जीवनशैली सूचना ({trigger}): {note}",
        "schedule_header": "{profile} चे दैनिक औषध वेळापत्रक: {slots}.",
        "monograph_note": "अधिकृत FDA/DailyMed लेबल ({salt}): {excerpt}",
        "major_action": "डॉक्टर किंवा फार्मासिस्टच्या सल्ल्याशिवाय ही औषधे एकत्र घेऊ नका.",
    },
    "ta": {
        "major_header": "{profile} என்பவருக்கான முக்கிய பாதுகாப்பு எச்சரிக்கை:",
        "allergy_line": (
            "ஒவ்வாமை எச்சரிக்கை — {salt} பதிவுசெய்யப்பட்ட '{allergen}' ஒவ்வாமையுடன் முரண்படுகிறது."
        ),
        "duplicate_line": "இரட்டை மூலப்பொருள் — {salt} ஏற்கனவே {med} மருந்தில் உள்ளது.",
        "major_ddi_line": "{salt_a} மற்றும் {salt_b} இடையே தீவிர இடைவினை: {effect} {management}",
        "recall_line": (
            "{terms} மருந்திற்கு US FDA {classification} திரும்பப்பெறுதல் எச்சரிக்கை: {reason}"
        ),
        "moderate_line": "{salt_a} மற்றும் {salt_b} இடையே {severity} இடைவினை: {management}",
        "safe_checked": (
            "{profile} மருந்துப் பெட்டியுடன் ({cabinet}) தீவிர இடைவினை, இரட்டை மூலப்பொருள் "
            "அல்லது ஒவ்வாமை எதுவும் கண்டறியப்படவில்லை."
        ),
        "empty_cabinet": (
            "{profile} மருந்துப் பெட்டியில் இன்னும் செயலில் உள்ள மருந்துகள் சேமிக்கப்படவில்லை. "
            "மருந்தை ஸ்கேன் செய்யவும் அல்லது சேர்க்கவும்."
        ),
        "cabinet_summary": "{profile} எடுக்கும் மருந்துகள்: {cabinet}.",
        "allergy_summary": "பதிவான ஒவ்வாமைகள்: {allergies}.",
        "lifestyle_line": "உணவு மற்றும் வாழ்க்கைமுறை குறிப்பு ({trigger}): {note}",
        "schedule_header": "{profile} தினசரி மருந்து அட்டவணை: {slots}.",
        "monograph_note": "அதிகாரப்பூர்வ FDA/DailyMed தகவல் ({salt}): {excerpt}",
        "major_action": "மருத்துவர் அல்லது மருந்தாளரிடம் கேட்காமல் இவற்றை ஒன்றாக எடுக்க வேண்டாம்.",
    },
    "gu": {
        "major_header": "{profile} માટે ગંભીર સુરક્ષા ચેતવણી:",
        "allergy_line": "એલર્જી ચેતવણી — {salt} તમારી '{allergen}' એલર્જી સાથે ટકરાય છે.",
        "duplicate_line": "ડુપ્લિકેટ ઘટક — {salt} પહેલેથી {med} માં સક્રિય છે.",
        "major_ddi_line": "{salt_a} અને {salt_b} વચ્ચે ગંભીર ઇન્ટરેક્શન: {effect} {management}",
        "recall_line": "{terms} માટે સક્રિય US FDA {classification} રિકોલ ચેતવણી: {reason}",
        "moderate_line": "{salt_a} અને {salt_b} વચ્ચે {severity} ઇન્ટરેક્શન: {management}",
        "safe_checked": (
            "{profile} ના સક્રિય કેબિનેટ ({cabinet}) સાથે કોઈ ગંભીર ઇન્ટરેક્શન, "
            "ડુપ્લિકેટ સોલ્ટ કે એલર્જી મળી નથી."
        ),
        "empty_cabinet": (
            "{profile} ના કેબિનેટમાં હજી કોઈ સક્રિય દવા ઉમેરેલી નથી. "
            "દવા સ્કેન કરો અથવા કેબિનેટમાં ઉમેરો."
        ),
        "cabinet_summary": "{profile} ની સક્રિય દવાઓ: {cabinet}.",
        "allergy_summary": "નોંધાયેલી એલર્જી: {allergies}.",
        "lifestyle_line": "ખોરાક અને જીવનશૈલી સૂચના ({trigger}): {note}",
        "schedule_header": "{profile} નું દૈનિક દવા સમયપત્રક: {slots}.",
        "monograph_note": "સત્તાવાર FDA/DailyMed લેબલ ({salt}): {excerpt}",
        "major_action": "ડૉક્ટર અથવા ફાર્માસિસ્ટની સલાહ લીધા વિના આ દવાઓ સાથે લેશો નહીં.",
    },
    "kn": {
        "major_header": "{profile} ಗಾಗಿ ಗಂಭೀರ ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ:",
        "allergy_line": "ಅಲರ್ಜಿ ಎಚ್ಚರಿಕೆ — {salt} ದಾಖಲಾದ '{allergen}' ಅಲರ್ಜಿಯೊಂದಿಗೆ ಸಂಘರ್ಷಿಸುತ್ತದೆ.",
        "duplicate_line": "ನಕಲಿ ಘಟಕ — {salt} ಈಗಾಗಲೇ {med} ನಲ್ಲಿ ಸಕ್ರಿಯವಾಗಿದೆ.",
        "major_ddi_line": "{salt_a} ಮತ್ತು {salt_b} ನಡುವೆ ಗಂಭೀರ ಪರಸ್ಪರ ಕ್ರಿಯೆ: {effect} {management}",
        "recall_line": "{terms} ಗಾಗಿ ಸಕ್ರಿಯ US FDA {classification} ಹಿಂಪಡೆಯುವಿಕೆ: {reason}",
        "moderate_line": "{salt_a} ಮತ್ತು {salt_b} ನಡುವೆ {severity} ಪರಸ್ಪರ ಕ್ರಿಯೆ: {management}",
        "safe_checked": (
            "{profile} ಸಕ್ರಿಯ ಕ್ಯಾಬಿನೆಟ್ ({cabinet}) ನೊಂದಿಗೆ ಯಾವುದೇ ಗಂಭೀರ ಪರಸ್ಪರ ಕ್ರಿಯೆ, "
            "ನಕಲಿ ಘಟಕ ಅಥವಾ ಅಲರ್ಜಿ ಪತ್ತೆಯಾಗಿಲ್ಲ."
        ),
        "empty_cabinet": (
            "{profile} ಕ್ಯಾಬಿನೆಟ್‌ನಲ್ಲಿ ಇನ್ನೂ ಯಾವುದೇ ಸಕ್ರಿಯ ಔಷಧಿಗಳನ್ನು ಉಳಿಸಲಾಗಿಲ್ಲ. "
            "ಔಷಧಿಯನ್ನು ಸ್ಕ್ಯಾನ್ ಮಾಡಿ ಅಥವಾ ಸೇರಿಸಿ."
        ),
        "cabinet_summary": "{profile} ಸಕ್ರಿಯ ಔಷಧಿಗಳು: {cabinet}.",
        "allergy_summary": "ದಾಖಲಾದ ಅಲರ್ಜಿಗಳು: {allergies}.",
        "lifestyle_line": "ಆಹಾರ ಮತ್ತು ಜೀವನಶೈಲಿ ಸೂಚನೆ ({trigger}): {note}",
        "schedule_header": "{profile} ದೈನಂದಿನ ಔಷಧ ವೇಳಾಪಟ್ಟಿ: {slots}.",
        "monograph_note": "ಅಧಿಕೃತ FDA/DailyMed ಲೇಬಲ್ ({salt}): {excerpt}",
        "major_action": "ವೈದ್ಯರು ಅಥವಾ ಔಷಧಜ್ಞರ ಸಲಹೆಯಿಲ್ಲದೆ ಈ ಔಷಧಿಗಳನ್ನು ಒಟ್ಟಿಗೆ ತೆಗೆದುಕೊಳ್ಳಬೇಡಿ.",
    },
    "ml": {
        "major_header": "{profile} എന്നയാൾക്കുള്ള ഗുരുതര സുരക്ഷാ മുന്നറിയിപ്പ്:",
        "allergy_line": (
            "അലർജി മുന്നറിയിപ്പ് — {salt} രേഖപ്പെടുത്തിയ '{allergen}' അലർജിയുമായി പൊരുത്തപ്പെടുന്നില്ല."
        ),
        "duplicate_line": "ഡ്യൂപ്ലിക്കേറ്റ് ഘടകം — {salt} ഇതിനകം {med} എന്നതിൽ സജീവമാണ്.",
        "major_ddi_line": "{salt_a}, {salt_b} എന്നിവയ്ക്കിടയിൽ ഗുരുതര ഇടപെടൽ: {effect} {management}",
        "recall_line": "{terms} എന്നതിന് സജീവ US FDA {classification} റീകോൾ: {reason}",
        "moderate_line": "{salt_a}, {salt_b} എന്നിവയ്ക്കിടയിൽ {severity} ഇടപെടൽ: {management}",
        "safe_checked": (
            "{profile} എന്നയാളുടെ ക്യാബിനറ്റുമായി ({cabinet}) ഗുരുതര ഇടപെടലുകളോ "
            "ഡ്യൂപ്ലിക്കേറ്റ് ഘടകങ്ങളോ അലർജി പ്രശ്നങ്ങളോ കണ്ടെത്തിയില്ല."
        ),
        "empty_cabinet": (
            "{profile} എന്നയാളുടെ ക്യാബിനറ്റിൽ സജീവ മരുന്നുകളൊന്നും ചേർത്തിട്ടില്ല. "
            "മരുന്ന് സ്കാൻ ചെയ്യുക അല്ലെങ്കിൽ ചേർക്കുക."
        ),
        "cabinet_summary": "{profile} എന്നയാളുടെ സജീവ മരുന്നുകൾ: {cabinet}.",
        "allergy_summary": "രേഖപ്പെടുത്തിയ അലർജികൾ: {allergies}.",
        "lifestyle_line": "ഭക്ഷണവും ജീവിതശൈലിയും ({trigger}): {note}",
        "schedule_header": "{profile} എന്നയാളുടെ പ്രതിദിന മരുന്ന് സമയം: {slots}.",
        "monograph_note": "ഔദ്യോഗിക FDA/DailyMed ലേബൽ ({salt}): {excerpt}",
        "major_action": "ഡോക്ടറുമായോ ഫാർമസിസ്റ്റുമായോ സംസാരിക്കാതെ ഇവ ഒരുമിച്ച് കഴിക്കരുത്.",
    },
    "pa": {
        "major_header": "{profile} ਲਈ ਗੰਭੀਰ ਸੁਰੱਖਿਆ ਚੇਤਾਵਨੀ:",
        "allergy_line": "ਐਲਰਜੀ ਚੇਤਾਵਨੀ — {salt} ਤੁਹਾਡੀ '{allergen}' ਐਲਰਜੀ ਨਾਲ ਟਕਰਾਉਂਦਾ ਹੈ।",
        "duplicate_line": "ਡੁਪਲੀਕੇਟ ਤੱਤ — {salt} ਪਹਿਲਾਂ ਹੀ {med} ਵਿੱਚ ਸਰਗਰਮ ਹੈ।",
        "major_ddi_line": "{salt_a} ਅਤੇ {salt_b} ਵਿਚਕਾਰ ਗੰਭੀਰ ਇੰਟਰਐਕਸ਼ਨ: {effect} {management}",
        "recall_line": "{terms} ਲਈ ਸਰਗਰਮ US FDA {classification} ਰੀਕਾਲ ਚੇਤਾਵਨੀ: {reason}",
        "moderate_line": "{salt_a} ਅਤੇ {salt_b} ਵਿਚਕਾਰ {severity} ਇੰਟਰਐਕਸ਼ਨ: {management}",
        "safe_checked": (
            "{profile} ਦੇ ਸਰਗਰਮ ਕੈਬਨਿਟ ({cabinet}) ਨਾਲ ਕੋਈ ਗੰਭੀਰ ਇੰਟਰਐਕਸ਼ਨ, "
            "ਡੁਪਲੀਕੇਟ ਸਾਲਟ ਜਾਂ ਐਲਰਜੀ ਟਕਰਾਅ ਨਹੀਂ ਮਿਲਿਆ।"
        ),
        "empty_cabinet": (
            "{profile} ਦੇ ਕੈਬਨਿਟ ਵਿੱਚ ਹਾਲੇ ਕੋਈ ਸਰਗਰਮ ਦਵਾਈ ਸੇਵ ਨਹੀਂ ਹੈ। "
            "ਦਵਾਈ ਸਕੈਨ ਕਰੋ ਜਾਂ ਕੈਬਨਿਟ ਵਿੱਚ ਜੋੜੋ।"
        ),
        "cabinet_summary": "{profile} ਦੀਆਂ ਸਰਗਰਮ ਦਵਾਈਆਂ: {cabinet}।",
        "allergy_summary": "ਦਰਜ ਐਲਰਜੀਆਂ: {allergies}।",
        "lifestyle_line": "ਖਾਣ-ਪੀਣ ਅਤੇ ਜੀਵਨਸ਼ੈਲੀ ਸਲਾਹ ({trigger}): {note}",
        "schedule_header": "{profile} ਦਾ ਰੋਜ਼ਾਨਾ ਦਵਾਈ ਸਮਾਂ: {slots}।",
        "monograph_note": "ਅਧਿਕਾਰਤ FDA/DailyMed ਲੇਬਲ ({salt}): {excerpt}",
        "major_action": "ਡਾਕਟਰ ਜਾਂ ਫਾਰਮਾਸਿਸਟ ਨਾਲ ਗੱਲ ਕੀਤੇ ਬਿਨਾਂ ਇਹ ਦਵਾਈਆਂ ਇਕੱਠੇ ਨਾ ਲਓ।",
    },
}


class _RawAssistantAnswer(BaseModel):
    answer_text: str = Field(min_length=1, max_length=2500)
    spoken_text: str = Field(min_length=1, max_length=1800)
    suggested_followups: list[str] = Field(default_factory=list, max_length=4)
    cited_chunk_ids: list[UUID] = Field(default_factory=list)


@dataclass(slots=True)
class AssistantContext:
    question: str
    language: SupportedLanguageCode
    profile_name: str
    allergies: list[Any]
    active_medications: list[dict[str, Any]]
    mentioned_salts: list[str]
    overall_risk: RiskLevel
    safety_banner_locked: bool
    duplicate_salts: list[DuplicateSaltAlert] = field(default_factory=list)
    allergy_alerts: list[AllergyAlert] = field(default_factory=list)
    deterministic_alerts: list[DeterministicDdiAlert] = field(default_factory=list)
    active_recalls: list[MedicationRecallFlag] = field(default_factory=list)
    lifestyle_triggers: list[LifestyleTrigger] = field(default_factory=list)
    citations: list[MonographCitation] = field(default_factory=list)
    schedule_summary: list[dict[str, Any]] = field(default_factory=list)
    conversation_history: list[dict[str, str]] = field(default_factory=list)
    context_medicine_name: str | None = None


class AssistantAnswerGenerator(Protocol):
    provider_name: str

    async def generate(self, context: AssistantContext) -> _RawAssistantAnswer: ...


class SpeechSynthesisUnavailableError(RuntimeError):
    """Raised when cloud TTS is unavailable, signaling on-device TTS fallback."""


class TtsAudioCache(Protocol):
    async def get(self, key: str) -> bytes | None: ...

    async def set(self, key: str, value: bytes, ex: int | None = None) -> Any: ...


EdgeSynthesizer = Callable[[str, str, str], Awaitable[tuple[bytes, str]]]

_shared_tts_redis: aioredis.Redis | None = None
_shared_tts_redis_url: str = ""


def derive_speech_tone(
    overall_risk: RiskLevel,
    safety_banner_locked: bool = False,
) -> SpeechTone:
    if safety_banner_locked or overall_risk == "MAJOR":
        return "urgent"
    if overall_risk in {"MODERATE", "MINOR"}:
        return "caution"
    return "reassuring"


def _clean_for_speech(
    text: str,
    language: str = "en",
    *,
    is_major_alert: bool = False,
) -> str:
    return prepare_text_for_speech(
        text,
        language=language,
        is_major_alert=is_major_alert,
    )


def _extract_indic_alias_salts(text: str) -> list[str]:
    found: list[str] = []
    lowered = text.lower()
    for alias, salts in _INDIC_MEDICINE_ALIASES.items():
        if alias in text or alias in lowered:
            found.extend(salts)
    return canonical_salt_keys(found)


def _wants_schedule_context(question: str) -> bool:
    lowered = question.lower()
    return any(keyword in lowered or keyword in question for keyword in _SCHEDULE_KEYWORDS)


def _build_deterministic_answer(context: AssistantContext) -> _RawAssistantAnswer:
    spec = get_language_spec(context.language)
    tpl = _FALLBACK_TEXTS[spec.code]
    cabinet_names = ", ".join(m["display_name"] for m in context.active_medications[:8])
    parts: list[str] = []

    if context.safety_banner_locked:
        parts.append(tpl["major_header"].format(profile=context.profile_name))
        for allergy in context.allergy_alerts:
            parts.append(
                tpl["allergy_line"].format(
                    salt=allergy.canonical_salt,
                    allergen=allergy.allergen,
                )
            )
        for dup in context.duplicate_salts:
            parts.append(
                tpl["duplicate_line"].format(
                    salt=dup.canonical_salt,
                    med=dup.existing_display_name,
                )
            )
        for alert in context.deterministic_alerts:
            if alert.severity == "MAJOR":
                parts.append(
                    tpl["major_ddi_line"].format(
                        salt_a=alert.salt_a,
                        salt_b=alert.salt_b,
                        effect=alert.clinical_effect,
                        management=alert.management,
                    )
                )
        for recall in context.active_recalls:
            parts.append(
                tpl["recall_line"].format(
                    classification=recall.classification,
                    terms=", ".join(recall.matched_terms) or recall.recall_number,
                    reason=recall.reason_for_recall,
                )
            )
        parts.append(tpl["major_action"])
    else:
        for alert in context.deterministic_alerts:
            parts.append(
                tpl["moderate_line"].format(
                    severity=alert.severity,
                    salt_a=alert.salt_a,
                    salt_b=alert.salt_b,
                    management=alert.management,
                )
            )
        for recall in context.active_recalls:
            parts.append(
                tpl["recall_line"].format(
                    classification=recall.classification,
                    terms=", ".join(recall.matched_terms) or recall.recall_number,
                    reason=recall.reason_for_recall,
                )
            )
        if not parts:
            if cabinet_names:
                if context.mentioned_salts or context.context_medicine_name:
                    parts.append(
                        tpl["safe_checked"].format(
                            profile=context.profile_name,
                            cabinet=cabinet_names,
                        )
                    )
                else:
                    parts.append(
                        tpl["cabinet_summary"].format(
                            profile=context.profile_name,
                            cabinet=cabinet_names,
                        )
                    )
            else:
                parts.append(tpl["empty_cabinet"].format(profile=context.profile_name))

    if context.schedule_summary:
        slot_desc = "; ".join(
            f"{slot['slot']} ({slot['recommended_time']}): {', '.join(slot['medications'])}"
            for slot in context.schedule_summary
            if slot.get("medications")
        )
        if slot_desc:
            parts.append(
                tpl["schedule_header"].format(
                    profile=context.profile_name,
                    slots=slot_desc,
                )
            )

    if context.allergies and not context.allergy_alerts:
        allergy_labels = [
            a if isinstance(a, str) else str(a.get("allergen", "")).strip()
            for a in context.allergies
        ]
        cleaned_allergies = [label for label in allergy_labels if label]
        if cleaned_allergies:
            parts.append(tpl["allergy_summary"].format(allergies=", ".join(cleaned_allergies)))

    for trigger in context.lifestyle_triggers[:2]:
        parts.append(
            tpl["lifestyle_line"].format(
                trigger=trigger.type.replace("_", " ").title(),
                note=trigger.short_note,
            )
        )

    cited_ids: list[UUID] = []
    if context.citations:
        top = context.citations[0]
        cited_ids.append(top.chunk_id)
        parts.append(
            tpl["monograph_note"].format(
                salt=top.canonical_salt,
                excerpt=top.excerpt[:200].strip(),
            )
        )

    answer_text = "\n\n".join(parts)
    is_major = context.safety_banner_locked or context.overall_risk == "MAJOR"
    spoken_source = " ".join(parts[1:4] if context.safety_banner_locked else parts[:4])
    spoken_text = _clean_for_speech(
        spoken_source,
        language=spec.code,
        is_major_alert=is_major,
    )
    return _RawAssistantAnswer(
        answer_text=answer_text[:2500],
        spoken_text=spoken_text[:1800] or answer_text[:500],
        suggested_followups=list(spec.followup_prompts),
        cited_chunk_ids=cited_ids,
    )


def _build_assistant_prompt(context: AssistantContext) -> str:
    spec = get_language_spec(context.language)
    payload = {
        "user_question": context.question,
        "response_language_code": spec.code,
        "response_language_name": f"{spec.english_name} ({spec.native_name})",
        "profile_name": context.profile_name,
        "context_medicine_name": context.context_medicine_name,
        "mentioned_or_context_salts": context.mentioned_salts,
        "overall_risk": context.overall_risk,
        "safety_banner_locked": context.safety_banner_locked,
        "tts_tone": derive_speech_tone(context.overall_risk, context.safety_banner_locked),
        "verbal_major_warning_prefix": spec.verbal_major_warning_prefix,
        "active_cabinet": context.active_medications,
        "documented_allergies": context.allergies,
        "allergy_alerts": [a.model_dump(mode="json") for a in context.allergy_alerts],
        "duplicate_salts": [d.model_dump(mode="json") for d in context.duplicate_salts],
        "deterministic_ddi_alerts": [
            a.model_dump(mode="json") for a in context.deterministic_alerts
        ],
        "active_fda_recalls": [r.model_dump(mode="json") for r in context.active_recalls],
        "lifestyle_triggers": [t.model_dump(mode="json") for t in context.lifestyle_triggers],
        "daily_schedule": context.schedule_summary,
        "monograph_chunks": [
            {
                "chunk_id": str(c.chunk_id),
                "canonical_salt": c.canonical_salt,
                "section_title": c.section_title,
                "excerpt": c.excerpt,
            }
            for c in context.citations
        ],
        "recent_conversation": context.conversation_history[-6:],
    }
    return (
        "You are PillScope's warm, conversational Indian healthcare medication safety companion.\n"
        f"Language requirement: {language_prompt_instruction(spec.code)}\n"
        "Strict clinical and voice rules:\n"
        "1. NEVER contradict, soften, or downgrade safety_banner_locked or overall_risk.\n"
        "2. If safety_banner_locked is true or overall_risk is MAJOR, open spoken_text with "
        f"the calm localized warning prefix '{spec.verbal_major_warning_prefix}' before "
        "explaining the risk and advising the user to check with a doctor or pharmacist.\n"
        "3. Ground every drug-label claim ONLY in the provided deterministic alerts, cabinet, "
        "schedule, and monograph_chunks. ONLY cite chunk_id UUIDs present in monograph_chunks.\n"
        "4. Produce BOTH:\n"
        "   - answer_text: clear, structured patient-friendly answer in the required language "
        "(no markdown asterisks or raw UUIDs).\n"
        "   - spoken_text: written strictly for ears, not screens, in the EXACT same language. "
        "Use short sentences under 18 words with natural comma pauses. Expand medical units and "
        "abbreviations into natural spoken words (milligrams, micrograms, milliliters, "
        "twice a day, three times a day, once a day, per). Strip all markdown, bullets, "
        "parentheses, URLs, slashes, and citation brackets. Never repeat sentences.\n"
        "   - suggested_followups: 3 short follow-up questions in the EXACT same language.\n"
        "5. Return strict JSON matching the schema.\n\n"
        f"CONTEXT_JSON:\n{json.dumps(payload, indent=2, ensure_ascii=False)}"
    )


class DeterministicAssistantGenerator:
    provider_name = "deterministic_grounded"

    async def generate(self, context: AssistantContext) -> _RawAssistantAnswer:
        return _build_deterministic_answer(context)


class GeminiAssistantGenerator:
    provider_name = "gemini"

    def __init__(
        self,
        *,
        api_key: str,
        model_name: str = "gemini-2.5-flash",
        timeout_seconds: float = 20.0,
    ) -> None:
        self._api_key = api_key
        self._model_name = model_name
        self._timeout = timeout_seconds

    async def generate(self, context: AssistantContext) -> _RawAssistantAnswer:
        url = (
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"{self._model_name}:generateContent"
        )
        schema: dict[str, Any] = {
            "type": "object",
            "properties": {
                "answer_text": {"type": "string"},
                "spoken_text": {"type": "string"},
                "suggested_followups": {"type": "array", "items": {"type": "string"}},
                "cited_chunk_ids": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["answer_text", "spoken_text", "suggested_followups", "cited_chunk_ids"],
        }
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                url,
                headers={"x-goog-api-key": self._api_key},
                json={
                    "contents": [{"parts": [{"text": _build_assistant_prompt(context)}]}],
                    "generationConfig": {
                        "temperature": 0.2,
                        "responseMimeType": "application/json",
                        "responseSchema": schema,
                    },
                },
            )
            response.raise_for_status()
            data = response.json()
            text_out = data["candidates"][0]["content"]["parts"][0]["text"]
            return _RawAssistantAnswer.model_validate_json(text_out)


class OpenAiAssistantGenerator:
    provider_name = "openai"

    def __init__(
        self,
        *,
        api_key: str,
        model_name: str = "gpt-4o-mini",
        timeout_seconds: float = 20.0,
    ) -> None:
        self._api_key = api_key
        self._model_name = model_name
        self._timeout = timeout_seconds

    async def generate(self, context: AssistantContext) -> _RawAssistantAnswer:
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                "https://api.openai.com/v1/chat/completions",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={
                    "model": self._model_name,
                    "temperature": 0.2,
                    "response_format": {"type": "json_object"},
                    "messages": [
                        {
                            "role": "system",
                            "content": (
                                "Return strict JSON with keys: answer_text, spoken_text, "
                                "suggested_followups, cited_chunk_ids."
                            ),
                        },
                        {"role": "user", "content": _build_assistant_prompt(context)},
                    ],
                },
            )
            response.raise_for_status()
            payload = response.json()
            content = payload["choices"][0]["message"]["content"]
            return _RawAssistantAnswer.model_validate_json(content)


def get_assistant_generator(settings: Settings | None = None) -> AssistantAnswerGenerator:
    resolved = settings or get_settings()
    if resolved.llm_provider == "gemini" and resolved.gemini_api_key:
        return GeminiAssistantGenerator(
            api_key=resolved.gemini_api_key,
            model_name=resolved.llm_model,
            timeout_seconds=resolved.llm_timeout_seconds,
        )
    if resolved.llm_provider == "openai" and resolved.openai_api_key:
        return OpenAiAssistantGenerator(
            api_key=resolved.openai_api_key,
            model_name=resolved.llm_model,
            timeout_seconds=resolved.llm_timeout_seconds,
        )
    return DeterministicAssistantGenerator()


def _verify_assistant_answer(
    raw: _RawAssistantAnswer,
    context: AssistantContext,
) -> tuple[_RawAssistantAnswer, list[MonographCitation]]:
    valid_by_id = {c.chunk_id: c for c in context.citations}
    valid_id_strs = {str(cid).lower() for cid in valid_by_id}

    blob = f"{raw.answer_text} {raw.spoken_text}"
    embedded_uuids = {m.lower() for m in _UUID_RE.findall(blob)}
    has_fake_uuid = any(u not in valid_id_strs for u in embedded_uuids) or any(
        cid not in valid_by_id for cid in raw.cited_chunk_ids
    )
    has_downgrade = context.safety_banner_locked and any(
        pattern.search(blob) is not None for pattern in MULTILINGUAL_DOWNGRADE_PATTERNS
    )

    if has_fake_uuid or has_downgrade:
        logger.warning(
            "voice_assistant_verifier_override",
            has_fake_uuid=has_fake_uuid,
            has_downgrade=has_downgrade,
            language=context.language,
        )
        fallback = _build_deterministic_answer(context)
        fallback_cites = [
            valid_by_id[cid] for cid in fallback.cited_chunk_ids if cid in valid_by_id
        ]
        return fallback, fallback_cites

    valid_cites = [
        valid_by_id[cid]
        for cid in dict.fromkeys(raw.cited_chunk_ids)
        if cid in valid_by_id
    ]
    if not valid_cites and context.citations:
        valid_cites = context.citations[:2]

    spec = get_language_spec(context.language)
    cleaned_followups = [f.strip() for f in raw.suggested_followups if f.strip()][:3]
    if not cleaned_followups:
        cleaned_followups = list(spec.followup_prompts)

    is_major = context.safety_banner_locked or context.overall_risk == "MAJOR"
    spoken_clean = _clean_for_speech(
        raw.spoken_text,
        language=spec.code,
        is_major_alert=is_major,
    ) or _clean_for_speech(
        raw.answer_text,
        language=spec.code,
        is_major_alert=is_major,
    )
    cleaned = _RawAssistantAnswer(
        answer_text=raw.answer_text.strip(),
        spoken_text=spoken_clean[:1800],
        suggested_followups=cleaned_followups,
        cited_chunk_ids=[c.chunk_id for c in valid_cites],
    )
    return cleaned, valid_cites


async def answer_voice_question(
    *,
    session: AsyncSession,
    user_id: UUID,
    request: AssistantAskRequest,
    settings: Settings,
    rag_retriever: HybridRagRetriever | None,
    generator: AssistantAnswerGenerator,
    profile: Profile | None = None,
) -> AssistantAskResponse:
    lang = normalize_language_code(request.language)
    spec = get_language_spec(lang)

    active_profile = profile or await resolve_user_profile(
        session=session,
        user_id=user_id,
        profile_id=request.profile_id,
    )
    active_meds = list(
        (
            await session.execute(
                select(Medication).where(
                    Medication.user_id == user_id,
                    Medication.profile_id == active_profile.id,
                    Medication.is_active.is_(True),
                )
            )
        )
        .scalars()
        .all()
    )
    cabinet_dicts: list[dict[str, Any]] = [
        {
            "display_name": med.display_name,
            "canonical_salt_keys": list(med.canonical_salt_keys),
            "dosage_schedule": med.dosage_schedule,
        }
        for med in active_meds
    ]
    cabinet_salts = canonical_salt_keys(
        salt for med in active_meds for salt in med.canonical_salt_keys
    )

    candidate_salts: list[str] = list(canonical_salt_keys(request.context_salts))
    context_medicine_name = request.context_medicine_name

    if request.scan_id is not None:
        scan_log = await session.scalar(
            select(ScanLog).where(
                ScanLog.id == request.scan_id,
                ScanLog.user_id == user_id,
            )
        )
        if scan_log is not None:
            if not context_medicine_name:
                context_medicine_name = scan_log.submitted_query
            scan_salts = [
                str(item.get("canonical_salt", "")).strip()
                for item in (scan_log.resolved_salts or [])
                if isinstance(item, dict)
            ]
            candidate_salts.extend(scan_salts)

    candidate_salts.extend(_extract_indic_alias_salts(request.question))
    candidate_salts.extend(comp.canonical_salt for comp in parse_ocr_salts(request.question))

    q_lower = request.question.lower()
    for med in active_meds:
        if med.display_name.lower() in q_lower:
            candidate_salts.extend(med.canonical_salt_keys)

    resolver = BrandSaltResolver(trigram_threshold=max(settings.trigram_similarity_threshold, 0.35))
    try:
        resolved_brand = await resolver.resolve(
            session=session,
            request=DrugResolveRequest(query=request.question[:120], limit=2),
        )
        if resolved_brand.candidates and resolved_brand.candidates[0].confidence >= 0.45:
            candidate_salts.extend(resolved_brand.candidates[0].canonical_salt_keys)
            if not context_medicine_name:
                context_medicine_name = resolved_brand.candidates[0].brand_name
    except Exception as exc:
        logger.debug("assistant_brand_resolve_skipped", error=str(exc))

    mentioned_salts = canonical_salt_keys(candidate_salts)
    all_involved_salts = canonical_salt_keys([*mentioned_salts, *cabinet_salts])

    duplicate_salts = (
        await detect_duplicate_salts(
            session=session,
            user_id=user_id,
            profile_id=active_profile.id,
            candidate_salts=mentioned_salts,
        )
        if mentioned_salts
        else []
    )
    allergy_alerts = (
        detect_allergy_conflicts(
            candidate_salts=all_involved_salts,
            allergies_json=list(active_profile.allergies_json or []),
        )
        if all_involved_salts
        else []
    )

    if mentioned_salts and cabinet_salts:
        deterministic_alerts = await detect_deterministic_ddi_alerts(
            session=session,
            candidate_salts=mentioned_salts,
            cabinet_salts=cabinet_salts,
        )
    elif len(all_involved_salts) >= 2:
        deterministic_alerts = await detect_deterministic_ddi_alerts(
            session=session,
            candidate_salts=all_involved_salts,
            cabinet_salts=all_involved_salts,
        )
    else:
        deterministic_alerts = []

    active_recalls = (
        await match_active_recalls_for_salts(
            session=session,
            canonical_salts=all_involved_salts,
            submitted_query=context_medicine_name or request.question[:120],
        )
        if all_involved_salts
        else []
    )

    lifestyle_triggers: list[LifestyleTrigger] = []
    if all_involved_salts:
        try:
            lifestyle_triggers = await extract_lifestyle_triggers_for_salts(
                session=session,
                canonical_salts=mentioned_salts or all_involved_salts[:4],
            )
        except Exception as exc:
            logger.warning("assistant_lifestyle_trigger_failed", error=str(exc))

    schedule_summary: list[dict[str, Any]] = []
    if _wants_schedule_context(request.question) and active_meds:
        try:
            schedule = await build_profile_schedule(session=session, profile=active_profile)
            schedule_summary = [
                {
                    "slot": slot.slot,
                    "recommended_time": slot.recommended_time,
                    "medications": [item.display_name for item in slot.medications],
                }
                for slot in schedule.slots
                if slot.medications
            ]
        except Exception as exc:
            logger.warning("assistant_schedule_context_failed", error=str(exc))

    citations: list[MonographCitation] = []
    if all_involved_salts and rag_retriever is not None:
        rag_query = " ".join(dict.fromkeys([*all_involved_salts, request.question[:160]]))
        try:
            citations = await rag_retriever.retrieve(
                session=session,
                query=rag_query,
                involved_salts=all_involved_salts,
                top_k=3,
            )
        except Exception as exc:
            logger.warning("assistant_rag_retrieval_failed", error=str(exc))
            await session.rollback()

    has_major_ddi = any(a.severity == "MAJOR" for a in deterministic_alerts)
    safety_banner_locked = (
        bool(duplicate_salts) or bool(allergy_alerts) or has_major_ddi or bool(active_recalls)
    )
    overall_risk: RiskLevel = "MAJOR" if safety_banner_locked else "NONE"
    for alert in deterministic_alerts:
        overall_risk = max_risk(overall_risk, alert.severity)

    context = AssistantContext(
        question=request.question,
        language=lang,
        profile_name=active_profile.name,
        allergies=list(active_profile.allergies_json or []),
        active_medications=cabinet_dicts,
        mentioned_salts=mentioned_salts,
        overall_risk=overall_risk,
        safety_banner_locked=safety_banner_locked,
        duplicate_salts=duplicate_salts,
        allergy_alerts=allergy_alerts,
        deterministic_alerts=deterministic_alerts,
        active_recalls=active_recalls,
        lifestyle_triggers=lifestyle_triggers,
        citations=citations,
        schedule_summary=schedule_summary,
        conversation_history=[
            {"role": turn.role, "text": turn.text} for turn in request.conversation_history
        ],
        context_medicine_name=context_medicine_name,
    )

    provider_used = getattr(generator, "provider_name", "deterministic_grounded")
    try:
        raw_answer = await generator.generate(context)
    except Exception as exc:
        logger.warning("voice_assistant_llm_failed_fallback", error=str(exc), language=lang)
        raw_answer = _build_deterministic_answer(context)
        provider_used = "deterministic_grounded"

    verified_answer, verified_citations = _verify_assistant_answer(raw_answer, context)
    return AssistantAskResponse(
        answer_text=verified_answer.answer_text,
        spoken_text=verified_answer.spoken_text,
        language=spec.code,
        bcp47_locale=spec.bcp47,
        overall_risk=overall_risk,
        safety_banner_locked=safety_banner_locked,
        tts_tone=derive_speech_tone(overall_risk, safety_banner_locked),
        referenced_salts=all_involved_salts,
        suggested_followups=verified_answer.suggested_followups,
        citations=verified_citations,
        disclaimer=spec.disclaimer,
        provider=provider_used,
    )


def is_edge_tts_available() -> bool:
    return importlib.util.find_spec("edge_tts") is not None


def is_cloud_tts_available(settings: Settings) -> bool:
    return bool(settings.gemini_api_key) or (
        settings.tts_edge_enabled and is_edge_tts_available()
    )


def build_tts_cache_key(
    normalized_spoken_text: str,
    language: SupportedLanguageCode,
    tone: SpeechTone,
    speech_rate: float,
) -> str:
    payload = f"{normalized_spoken_text}|{language}|{tone}|{speech_rate:.2f}"
    digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    return f"{_TTS_CACHE_KEY_PREFIX}{digest}"


def _encode_cached_audio(audio_bytes: bytes, media_type: str) -> bytes:
    return f"{media_type}\n".encode("ascii") + audio_bytes


def _decode_cached_audio(raw: bytes | None) -> tuple[bytes, str] | None:
    if not raw or b"\n" not in raw:
        return None
    header, audio = raw.split(b"\n", 1)
    if not audio:
        return None
    try:
        media_type = header.decode("ascii").strip()
    except UnicodeDecodeError:
        return None
    if not media_type:
        return None
    return audio, media_type


def _get_tts_redis_client(settings: Settings) -> aioredis.Redis | None:
    global _shared_tts_redis, _shared_tts_redis_url
    if not settings.redis_url:
        return None
    if _shared_tts_redis is None or _shared_tts_redis_url != settings.redis_url:
        _shared_tts_redis = aioredis.Redis.from_url(
            settings.redis_url,
            socket_connect_timeout=1,
            socket_timeout=1,
        )
        _shared_tts_redis_url = settings.redis_url
    return _shared_tts_redis


async def _read_cached_tts_audio(
    cache: TtsAudioCache | aioredis.Redis | None,
    cache_key: str,
) -> tuple[bytes, str] | None:
    if cache is None:
        return None
    try:
        raw = await cache.get(cache_key)
    except Exception as exc:
        logger.debug("tts_redis_cache_read_skipped", error=str(exc))
        return None
    if not isinstance(raw, bytes):
        return None
    return _decode_cached_audio(raw)


async def _write_cached_tts_audio(
    cache: TtsAudioCache | aioredis.Redis | None,
    cache_key: str,
    audio_bytes: bytes,
    media_type: str,
    ttl_seconds: int,
) -> None:
    if cache is None or ttl_seconds <= 0:
        return
    try:
        await cache.set(cache_key, _encode_cached_audio(audio_bytes, media_type), ex=ttl_seconds)
    except Exception as exc:
        logger.debug("tts_redis_cache_write_skipped", error=str(exc))


def format_edge_tts_rate(speech_rate: float, default_rate: str = "-6%") -> str:
    if abs(speech_rate - 0.94) < 0.01:
        return default_rate
    pct = max(-30, min(35, round((speech_rate - 1.0) * 100)))
    return f"{pct:+d}%"


def _gemini_style_direction(
    spec: LanguageSpec,
    tone: SpeechTone,
    speech_rate: float,
) -> str:
    pace_note = (
        "unhurried and gentle pace"
        if speech_rate <= 0.90
        else "slightly brisk yet clear pace"
        if speech_rate >= 1.08
        else "natural, unhurried pace"
    )
    if tone == "urgent":
        return (
            f"clear, calm, gently firm Indian healthcare companion speaking in "
            f"{spec.english_name} ({spec.bcp47}), {pace_note} with brief pauses at commas "
            "and periods so critical medication safety guidance is unmistakable"
        )
    if tone == "caution":
        return (
            f"calm, attentive, caring Indian healthcare companion speaking in "
            f"{spec.english_name} ({spec.bcp47}), {pace_note} with brief pauses at commas "
            "and periods"
        )
    return (
        f"warm, unhurried, conversational Indian healthcare companion speaking in "
        f"{spec.english_name} ({spec.bcp47}), {pace_note} with brief pauses at commas "
        "and periods"
    )


def _chunk_text_for_gemini_tts(text: str, max_chars: int = 420) -> list[str]:
    raw_sentences = [
        part.strip() for part in _TTS_SENTENCE_SPLIT_RE.split(text.strip()) if part.strip()
    ]
    sentences = deduplicate_sentences(raw_sentences)
    if not sentences:
        return []
    chunks: list[str] = []
    current: list[str] = []
    current_len = 0
    for sentence in sentences:
        if len(sentence) > max_chars:
            if current:
                chunks.append(" ".join(current))
                current = []
                current_len = 0
            words = sentence.split()
            segment: list[str] = []
            seg_len = 0
            for word in words:
                added = len(word) + (1 if segment else 0)
                if segment and seg_len + added > max_chars:
                    chunks.append(" ".join(segment))
                    segment = [word]
                    seg_len = len(word)
                else:
                    segment.append(word)
                    seg_len += added
            if segment:
                chunks.append(" ".join(segment))
            continue

        added = len(sentence) + (1 if current else 0)
        if current and current_len + added > max_chars:
            chunks.append(" ".join(current))
            current = [sentence]
            current_len = len(sentence)
        else:
            current.append(sentence)
            current_len += added
    if current:
        chunks.append(" ".join(current))
    return chunks


def _wrap_pcm_as_wav(
    pcm_bytes: bytes,
    sample_rate: int = _DEFAULT_PCM_SAMPLE_RATE,
    channels: int = _DEFAULT_PCM_CHANNELS,
    sample_width: int = _DEFAULT_PCM_SAMPLE_WIDTH,
) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav_out:
        wav_out.setnchannels(channels)
        wav_out.setsampwidth(sample_width)
        wav_out.setframerate(sample_rate)
        wav_out.writeframes(pcm_bytes)
    return buf.getvalue()


def _combine_wav_chunks(chunks: list[tuple[bytes, str]]) -> tuple[bytes, str]:
    if len(chunks) == 1:
        audio_bytes, mime_type = chunks[0]
        lower_mime = mime_type.lower()
        if ("l16" in lower_mime or "pcm" in lower_mime) and not audio_bytes.startswith(b"RIFF"):
            return _wrap_pcm_as_wav(audio_bytes), "audio/wav"
        return audio_bytes, mime_type

    channels = _DEFAULT_PCM_CHANNELS
    sample_width = _DEFAULT_PCM_SAMPLE_WIDTH
    sample_rate = _DEFAULT_PCM_SAMPLE_RATE
    pcm_segments: list[bytes] = []

    for audio_bytes, _mime in chunks:
        if audio_bytes.startswith(b"RIFF"):
            try:
                with wave.open(io.BytesIO(audio_bytes), "rb") as wav_in:
                    channels = wav_in.getnchannels()
                    sample_width = wav_in.getsampwidth()
                    sample_rate = wav_in.getframerate()
                    pcm_segments.append(wav_in.readframes(wav_in.getnframes()))
                    continue
            except wave.Error:
                pass
        pcm_segments.append(audio_bytes)

    pause_frames = int(sample_rate * (_CHUNK_PAUSE_MS / 1000.0))
    silence = b"\x00" * (pause_frames * channels * sample_width)
    merged_pcm = silence.join(seg for seg in pcm_segments if seg)
    return _wrap_pcm_as_wav(merged_pcm, sample_rate, channels, sample_width), "audio/wav"


async def _synthesize_with_gemini_tts(
    *,
    spoken_text: str,
    spec: LanguageSpec,
    tone: SpeechTone,
    speech_rate: float,
    voice: str,
    settings: Settings,
    http_client: httpx.AsyncClient | None = None,
) -> tuple[bytes, str]:
    chunks = _chunk_text_for_gemini_tts(
        spoken_text,
        max_chars=max(120, settings.tts_gemini_max_chunk_chars),
    )
    if not chunks:
        raise SpeechSynthesisUnavailableError("No chunks available for Gemini TTS.")

    style = _gemini_style_direction(spec, tone, speech_rate)
    client = http_client or httpx.AsyncClient(timeout=settings.llm_timeout_seconds)
    should_close = http_client is None
    rendered_chunks: list[tuple[bytes, str]] = []
    try:
        for chunk in chunks:
            payload: dict[str, Any] = {
                "model": settings.gemini_tts_model,
                "input": [
                    {
                        "type": "user_input",
                        "content": [
                            {
                                "type": "text",
                                "text": chunk,
                                "annotations": [
                                    {
                                        "type": "speech_metadata",
                                        "style": style,
                                    }
                                ],
                            }
                        ],
                    }
                ],
                "response_format": {"type": "audio"},
                "generation_config": {
                    "speech_config": [{"voice": voice}],
                },
            }
            resp = await client.post(
                "https://generativelanguage.googleapis.com/v1beta/interactions",
                headers={
                    "x-goog-api-key": settings.gemini_api_key,
                    "Content-Type": "application/json",
                },
                json=payload,
            )
            resp.raise_for_status()
            body = resp.json()
            audio_b64: str | None = None
            mime_type = "audio/wav"
            for step in body.get("steps", []):
                if step.get("type") != "model_output":
                    continue
                for part in step.get("content", []):
                    if part.get("type") == "audio" and part.get("data"):
                        audio_b64 = str(part["data"])
                        if part.get("mime_type"):
                            mime_type = str(part["mime_type"])
            if not audio_b64:
                raise SpeechSynthesisUnavailableError("Gemini TTS response contained no audio.")
            rendered_chunks.append((base64.b64decode(audio_b64), mime_type))
    finally:
        if should_close:
            await client.aclose()

    return _combine_wav_chunks(rendered_chunks)


async def _synthesize_with_edge_tts(
    text: str,
    voice: str,
    rate: str,
) -> tuple[bytes, str]:
    import edge_tts

    communicator = edge_tts.Communicate(text=text, voice=voice, rate=rate)
    audio_buffer = io.BytesIO()
    async for chunk in communicator.stream():
        if chunk.get("type") == "audio":
            data = chunk.get("data")
            if isinstance(data, (bytes, bytearray)):
                audio_buffer.write(data)
    audio_bytes = audio_buffer.getvalue()
    if not audio_bytes:
        raise SpeechSynthesisUnavailableError("Edge TTS returned empty audio.")
    return audio_bytes, "audio/mpeg"


async def synthesize_speech_audio(
    *,
    text: str,
    language: str,
    settings: Settings,
    voice: str | None = None,
    tone: SpeechTone = "reassuring",
    speech_rate: float = 0.94,
    http_client: httpx.AsyncClient | None = None,
    redis_client: TtsAudioCache | None = None,
    edge_synthesizer: EdgeSynthesizer | None = None,
) -> tuple[bytes, str]:
    spec = get_language_spec(language)
    spoken_text = _clean_for_speech(
        text,
        language=spec.code,
        is_major_alert=(tone == "urgent"),
    )[:1800]
    if not spoken_text:
        raise ValueError("Nothing to synthesize after cleaning input text.")

    cache_backend: TtsAudioCache | aioredis.Redis | None = redis_client
    if cache_backend is None and settings.app_env != "test":
        cache_backend = _get_tts_redis_client(settings)

    cache_key = build_tts_cache_key(spoken_text, spec.code, tone, speech_rate)
    cached = await _read_cached_tts_audio(cache_backend, cache_key)
    if cached is not None:
        return cached

    if settings.gemini_api_key:
        selected_gemini_voice = voice or settings.gemini_tts_voice
        try:
            audio_bytes, media_type = await _synthesize_with_gemini_tts(
                spoken_text=spoken_text,
                spec=spec,
                tone=tone,
                speech_rate=speech_rate,
                voice=selected_gemini_voice,
                settings=settings,
                http_client=http_client,
            )
            await _write_cached_tts_audio(
                cache_backend,
                cache_key,
                audio_bytes,
                media_type,
                settings.tts_cache_ttl_seconds,
            )
            return audio_bytes, media_type
        except Exception as exc:
            logger.warning(
                "gemini_tts_failed_falling_back_to_edge",
                error=str(exc),
                language=spec.code,
                tone=tone,
            )

    if settings.tts_edge_enabled and (edge_synthesizer is not None or is_edge_tts_available()):
        selected_edge_voice = (
            voice if voice and "Neural" in voice else spec.edge_tts_voice
        )
        edge_rate = format_edge_tts_rate(speech_rate, settings.tts_edge_rate)
        synth_fn = edge_synthesizer or _synthesize_with_edge_tts
        try:
            audio_bytes, media_type = await synth_fn(spoken_text, selected_edge_voice, edge_rate)
            if not audio_bytes:
                raise SpeechSynthesisUnavailableError("Edge TTS returned empty audio.")
            await _write_cached_tts_audio(
                cache_backend,
                cache_key,
                audio_bytes,
                media_type,
                settings.tts_cache_ttl_seconds,
            )
            return audio_bytes, media_type
        except Exception as exc:
            logger.warning(
                "edge_tts_failed",
                error=str(exc),
                language=spec.code,
                voice=selected_edge_voice,
            )

    raise SpeechSynthesisUnavailableError(
        "Cloud neural TTS is unavailable; use on-device TextToSpeech."
    )
