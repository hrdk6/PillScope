from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Literal

SupportedLanguageCode = Literal[
    "en",
    "hi",
    "bn",
    "te",
    "mr",
    "ta",
    "gu",
    "kn",
    "ml",
    "pa",
]


@dataclass(frozen=True, slots=True)
class LanguageSpec:
    code: SupportedLanguageCode
    bcp47: str
    english_name: str
    native_name: str
    script_name: str
    disclaimer: str
    safety_override_prefix: str
    followup_prompts: tuple[str, str, str]


SUPPORTED_LANGUAGES: dict[SupportedLanguageCode, LanguageSpec] = {
    "en": LanguageSpec(
        code="en",
        bcp47="en-IN",
        english_name="English (India)",
        native_name="English",
        script_name="Latin",
        disclaimer=(
            "Informational safety check only; consult a licensed clinician or pharmacist "
            "before starting, stopping, or changing any medication."
        ),
        safety_override_prefix="CRITICAL SAFETY ALERT:",
        followup_prompts=(
            "Can I take this with my current medicines?",
            "What side effects or food warnings should I know?",
            "When is the safest time of day to take this?",
        ),
    ),
    "hi": LanguageSpec(
        code="hi",
        bcp47="hi-IN",
        english_name="Hindi",
        native_name="हिन्दी",
        script_name="Devanagari",
        disclaimer=(
            "यह केवल जानकारी के लिए सुरक्षा जांच है; कोई भी दवा शुरू करने, बंद करने या बदलने से "
            "पहले डॉक्टर या फार्मासिस्ट से सलाह लें।"
        ),
        safety_override_prefix="गंभीर सुरक्षा चेतावनी:",
        followup_prompts=(
            "क्या मैं यह दवा अपनी मौजूदा दवाओं के साथ ले सकता हूँ?",
            "इस दवा के साथ खान-पान या दुष्प्रभाव की क्या सावधानियाँ हैं?",
            "यह दवा दिन में किस समय लेना सबसे सुरक्षित है?",
        ),
    ),
    "bn": LanguageSpec(
        code="bn",
        bcp47="bn-IN",
        english_name="Bengali",
        native_name="বাংলা",
        script_name="Bengali",
        disclaimer=(
            "এটি শুধুমাত্র তথ্যমূলক নিরাপত্তা পরীক্ষা; কোনো ওষুধ শুরু, বন্ধ বা পরিবর্তন করার আগে "
            "চিকিৎসক বা ফার্মাসিস্টের পরামর্শ নিন।"
        ),
        safety_override_prefix="গুরুতর নিরাপত্তা সতর্কতা:",
        followup_prompts=(
            "আমি কি আমার বর্তমান ওষুধগুলোর সাথে এটি খেতে পারি?",
            "এই ওষুধের খাবার বা পার্শ্বপ্রতিক্রিয়া সংক্রান্ত সতর্কতা কী?",
            "দিনের কোন সময়ে এই ওষুধ খাওয়া সবচেয়ে নিরাপদ?",
        ),
    ),
    "te": LanguageSpec(
        code="te",
        bcp47="te-IN",
        english_name="Telugu",
        native_name="తెలుగు",
        script_name="Telugu",
        disclaimer=(
            "ఇది సమాచారం కోసం మాత్రమే భద్రతా తనిఖీ; ఏ మందునైనా ప్రారంభించే, ఆపే లేదా మార్చే ముందు "
            "వైద్యుడు లేదా ఫార్మసిస్ట్‌ను సంప్రదించండి."
        ),
        safety_override_prefix="ముఖ్యమైన భద్రతా హెచ్చరిక:",
        followup_prompts=(
            "నా ప్రస్తుత మందులతో దీనిని తీసుకోవచ్చా?",
            "ఈ మందుతో ఆహారం లేదా దుష్ప్రభావాల జాగ్రత్తలు ఏమిటి?",
            "రోజులో ఏ సమయంలో ఈ మందు తీసుకోవడం సురక్షితం?",
        ),
    ),
    "mr": LanguageSpec(
        code="mr",
        bcp47="mr-IN",
        english_name="Marathi",
        native_name="मराठी",
        script_name="Devanagari",
        disclaimer=(
            "ही केवळ माहितीसाठी सुरक्षा तपासणी आहे; कोणतेही औषध सुरू करण्यापूर्वी, बंद करण्यापूर्वी "
            "किंवा बदलण्यापूर्वी डॉक्टर किंवा फार्मासिस्टचा सल्ला घ्या."
        ),
        safety_override_prefix="गंभीर सुरक्षा इशारा:",
        followup_prompts=(
            "मी हे औषध माझ्या सध्याच्या औषधांसोबत घेऊ शकतो का?",
            "या औषधासोबत आहार किंवा दुष्परिणामांची कोणती काळजी घ्यावी?",
            "हे औषध दिवसातील कोणत्या वेळी घेणे सर्वात सुरक्षित आहे?",
        ),
    ),
    "ta": LanguageSpec(
        code="ta",
        bcp47="ta-IN",
        english_name="Tamil",
        native_name="தமிழ்",
        script_name="Tamil",
        disclaimer=(
            "இது தகவல் பாதுகாப்பு சரிபார்ப்பு மட்டுமே; எந்த மருந்தையும் தொடங்க, நிறுத்த அல்லது மாற்ற "
            "மருத்துவர் அல்லது மருந்தாளரை அணுகவும்."
        ),
        safety_override_prefix="முக்கிய பாதுகாப்பு எச்சரிக்கை:",
        followup_prompts=(
            "எனது தற்போதைய மருந்துகளுடன் இதை எடுக்கலாமா?",
            "இந்த மருந்துக்கான உணவு அல்லது பக்கவிளைவு எச்சரிக்கைகள் என்ன?",
            "இந்த மருந்தை நாளின் எந்த நேரத்தில் எடுப்பது பாதுகாப்பானது?",
        ),
    ),
    "gu": LanguageSpec(
        code="gu",
        bcp47="gu-IN",
        english_name="Gujarati",
        native_name="ગુજરાતી",
        script_name="Gujarati",
        disclaimer=(
            "આ માત્ર માહિતી માટેની સુરક્ષા તપાસ છે; કોઈપણ દવા શરૂ કરતા, બંધ કરતા કે બદલતા પહેલા "
            "ડૉક્ટર અથવા ફાર્માસિસ્ટની સલાહ લો."
        ),
        safety_override_prefix="ગંભીર સુરક્ષા ચેતવણી:",
        followup_prompts=(
            "શું હું આ દવા મારી હાલની દવાઓ સાથે લઈ શકું?",
            "આ દવા સાથે ખોરાક કે આડઅસરની કઈ સાવચેતી રાખવી?",
            "દિવસના કયા સમયે આ દવા લેવી સૌથી સુરક્ષિત છે?",
        ),
    ),
    "kn": LanguageSpec(
        code="kn",
        bcp47="kn-IN",
        english_name="Kannada",
        native_name="ಕನ್ನಡ",
        script_name="Kannada",
        disclaimer=(
            "ಇದು ಮಾಹಿತಿಗಾಗಿ ಮಾತ್ರ ಸುರಕ್ಷತಾ ಪರಿಶೀಲನೆ; ಯಾವುದೇ ಔಷಧಿಯನ್ನು ಪ್ರಾರಂಭಿಸುವ, ನಿಲ್ಲಿಸುವ ಅಥವಾ "
            "ಬದಲಾಯಿಸುವ ಮೊದಲು ವೈದ್ಯರು ಅಥವಾ ಔಷಧಜ್ಞರನ್ನು ಸಂಪರ್ಕಿಸಿ."
        ),
        safety_override_prefix="ಗಂಭೀರ ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ:",
        followup_prompts=(
            "ನನ್ನ ಪ್ರಸ್ತುತ ಔಷಧಿಗಳೊಂದಿಗೆ ಇದನ್ನು ತೆಗೆದುಕೊಳ್ಳಬಹುದೇ?",
            "ಈ ಔಷಧಿಯ ಆಹಾರ ಅಥವಾ ಅಡ್ಡಪರಿಣಾಮಗಳ ಎಚ್ಚರಿಕೆಗಳು ಯಾವುವು?",
            "ದಿನದ ಯಾವ ಸಮಯದಲ್ಲಿ ಈ ಔಷಧಿ ತೆಗೆದುಕೊಳ್ಳುವುದು ಸುರಕ್ಷಿತ?",
        ),
    ),
    "ml": LanguageSpec(
        code="ml",
        bcp47="ml-IN",
        english_name="Malayalam",
        native_name="മലയാളം",
        script_name="Malayalam",
        disclaimer=(
            "ഇത് വിവരങ്ങൾക്കായുള്ള സുരക്ഷാ പരിശോധന മാത്രമാണ്; ഏതെങ്കിലും മരുന്ന് തുടങ്ങുന്നതിനോ "
            "നിർത്തുന്നതിനോ മാറ്റുന്നതിനോ മുമ്പ് ഡോക്ടറെയോ ഫാർമസിസ്റ്റിനെയോ സമീപിക്കുക."
        ),
        safety_override_prefix="ഗുരുതര സുരക്ഷാ മുന്നറിയിപ്പ്:",
        followup_prompts=(
            "എന്റെ നിലവിലുള്ള മരുന്നുകൾക്കൊപ്പം ഇത് കഴിക്കാമോ?",
            "ഈ മരുന്നിനുള്ള ഭക്ഷണ മുന്നറിയിപ്പുകളും പാർശ്വഫലങ്ങളും എന്തെല്ലാമാണ്?",
            "ദിവസത്തിലെ ഏത് സമയത്താണ് ഈ മരുന്ന് കഴിക്കാൻ ഏറ്റവും സുരക്ഷിതം?",
        ),
    ),
    "pa": LanguageSpec(
        code="pa",
        bcp47="pa-IN",
        english_name="Punjabi",
        native_name="ਪੰਜਾਬੀ",
        script_name="Gurmukhi",
        disclaimer=(
            "ਇਹ ਸਿਰਫ਼ ਜਾਣਕਾਰੀ ਲਈ ਸੁਰੱਖਿਆ ਜਾਂਚ ਹੈ; ਕੋਈ ਵੀ ਦਵਾਈ ਸ਼ੁਰੂ ਕਰਨ, ਬੰਦ ਕਰਨ ਜਾਂ ਬਦਲਣ ਤੋਂ "
            "ਪਹਿਲਾਂ ਡਾਕਟਰ ਜਾਂ ਫਾਰਮਾਸਿਸਟ ਦੀ ਸਲਾਹ ਲਓ।"
        ),
        safety_override_prefix="ਗੰਭੀਰ ਸੁਰੱਖਿਆ ਚੇਤਾਵਨੀ:",
        followup_prompts=(
            "ਕੀ ਮੈਂ ਇਹ ਦਵਾਈ ਆਪਣੀਆਂ ਮੌਜੂਦਾ ਦਵਾਈਆਂ ਨਾਲ ਲੈ ਸਕਦਾ ਹਾਂ?",
            "ਇਸ ਦਵਾਈ ਨਾਲ ਖਾਣ-ਪੀਣ ਜਾਂ ਮਾੜੇ ਪ੍ਰਭਾਵਾਂ ਦੀਆਂ ਕਿਹੜੀਆਂ ਸਾਵਧਾਨੀਆਂ ਹਨ?",
            "ਦਿਨ ਦੇ ਕਿਸ ਸਮੇਂ ਇਹ ਦਵਾਈ ਲੈਣਾ ਸਭ ਤੋਂ ਸੁਰੱਖਿਅਤ ਹੈ?",
        ),
    ),
}

_LANGUAGE_ALIASES: dict[str, SupportedLanguageCode] = {
    "en": "en",
    "eng": "en",
    "english": "en",
    "hi": "hi",
    "hin": "hi",
    "hindi": "hi",
    "हिन्दी": "hi",
    "हिंदी": "hi",
    "bn": "bn",
    "ben": "bn",
    "bengali": "bn",
    "bangla": "bn",
    "বাংলা": "bn",
    "te": "te",
    "tel": "te",
    "telugu": "te",
    "తెలుగు": "te",
    "mr": "mr",
    "mar": "mr",
    "marathi": "mr",
    "मराठी": "mr",
    "ta": "ta",
    "tam": "ta",
    "tamil": "ta",
    "தமிழ்": "ta",
    "gu": "gu",
    "guj": "gu",
    "gujarati": "gu",
    "ગુજરાતી": "gu",
    "kn": "kn",
    "kan": "kn",
    "kannada": "kn",
    "ಕನ್ನಡ": "kn",
    "ml": "ml",
    "mal": "ml",
    "malayalam": "ml",
    "മലയാളം": "ml",
    "pa": "pa",
    "pan": "pa",
    "punjabi": "pa",
    "ਪੰਜਾਬੀ": "pa",
}

# Anti-downgrade patterns in English + major Indian languages so LLM outputs can never
# contradict a locked deterministic MAJOR safety alert in any supported language.
MULTILINGUAL_DOWNGRADE_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\bno\s+(?:major|serious|significant|clinically\s+relevant)\s+interaction", re.I),
    re.compile(r"\bsafe\s+to\s+(?:take|use|combine)\s+together", re.I),
    re.compile(r"\bno\s+interactions?\s+(?:were\s+)?(?:found|detected|identified)", re.I),
    re.compile(r"\bno\s+safety\s+concerns?\b", re.I),
    re.compile(r"\blow\s+risk\b", re.I),
    # Hindi / Marathi (Devanagari & Hinglish)
    re.compile(r"एक\s+साथ\s+लेना\s+सुरक्षित", re.I),
    re.compile(r"कोई\s+(?:गंभीर|बड़ा|खतरनाक)\s+(?:इंटरैक्शन|खतरा)\s+नहीं", re.I),
    re.compile(r"एकत्र\s+घेणे\s+सुरक्षित", re.I),
    re.compile(r"\bsaath\s+lena\s+safe\b", re.I),
    # Bengali
    re.compile(r"একসাথে\s+(?:খাওয়া|নেওয়া)\s+নিরাপদ", re.I),
    # Telugu
    re.compile(r"కలిపి\s+తీసుకోవడం\s+సురక్షితం", re.I),
    # Tamil
    re.compile(r"ஒன்றாக\s+எடுப்பது\s+பாதுகாப்பானது", re.I),
    # Gujarati
    re.compile(r"સાથે\s+લેવી\s+સુરક્ષિત", re.I),
    # Kannada
    re.compile(r"ಒಟ್ಟಿಗೆ\s+ತೆಗೆದುಕೊಳ್ಳುವುದು\s+ಸುರಕ್ಷಿತ", re.I),
    # Malayalam
    re.compile(r"ഒരുമിച്ച്\s+കഴിക്കുന്നത്\s+സുരക്ഷിതമാണ്", re.I),
    # Punjabi
    re.compile(r"ਇਕੱਠੇ\s+ਲੈਣਾ\s+ਸੁਰੱਖਿਅਤ", re.I),
)


def normalize_language_code(raw: str | None) -> SupportedLanguageCode:
    if not raw:
        return "en"
    cleaned = raw.strip().lower().replace("_", "-")
    if not cleaned:
        return "en"
    if cleaned in _LANGUAGE_ALIASES:
        return _LANGUAGE_ALIASES[cleaned]
    primary = cleaned.split("-", 1)[0]
    if primary in _LANGUAGE_ALIASES:
        return _LANGUAGE_ALIASES[primary]
    return "en"


def get_language_spec(language: str | None) -> LanguageSpec:
    return SUPPORTED_LANGUAGES[normalize_language_code(language)]


def list_supported_languages() -> list[LanguageSpec]:
    return list(SUPPORTED_LANGUAGES.values())


def language_prompt_instruction(language: str | None) -> str:
    spec = get_language_spec(language)
    if spec.code == "en":
        return "Respond in clear, patient-friendly Indian English."
    return (
        f"Respond ENTIRELY in {spec.english_name} ({spec.native_name}) using {spec.script_name} "
        "script. Keep medicine brand names and canonical generic salt names clear and "
        "recognizable (you may include the English salt name in parentheses once for clinical "
        "clarity). Do NOT switch back to English paragraphs."
    )
