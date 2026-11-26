import json
import re
from dataclasses import dataclass, field
from typing import Any, Protocol
from uuid import UUID

import httpx
import structlog

from backend.app.core.config import Settings, get_settings
from backend.app.core.languages import (
    MULTILINGUAL_DOWNGRADE_PATTERNS,
    SupportedLanguageCode,
    get_language_spec,
    language_prompt_instruction,
    normalize_language_code,
)
from backend.app.schemas.cabinet import DuplicateSaltAlert, RiskLevel
from backend.app.schemas.profiles import AllergyAlert
from backend.app.schemas.safety import (
    ClinicalSummary,
    DeterministicDdiAlert,
    InteractionItem,
    MonographCitation,
)

logger = structlog.get_logger(__name__)

_UUID_RE = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b",
    re.IGNORECASE,
)
_DOWNGRADE_PATTERNS: tuple[re.Pattern[str], ...] = MULTILINGUAL_DOWNGRADE_PATTERNS


_BRIEFING_TEMPLATES: dict[SupportedLanguageCode, dict[str, str]] = {
    "hi": {
        "unrecognized_headline": "'{query}' के सक्रिय तत्वों की पहचान नहीं हो सकी।",
        "unrecognized_body": (
            "PillScope '{query}' को किसी ज्ञात दवा से मिला नहीं सका। इसे अपनी मौजूदा दवाओं के साथ "
            "सुरक्षित न मानें — स्ट्रिप पर लिखा जेनेरिक सॉल्ट देखें और फिर से खोजें या फार्मासिस्ट से पूछें।"
        ),
        "unrecognized_step_1": "दवा की पट्टी या बोतल पर लिखा जेनेरिक सॉल्ट नाम दर्ज करें।",
        "unrecognized_step_2": "अनजान दवा लेने से पहले डॉक्टर या फार्मासिस्ट से सलाह लें।",
        "major_locked_headline": "गंभीर सुरक्षा चेतावनी लॉक है: {detail}।",
        "major_locked_body": (
            "{query} के लिए सुरक्षा नियमों ने {detail} पाया है। इन सॉल्ट्स के लिए आधिकारिक "
            "openFDA/DailyMed मोनोग्राफ उपलब्ध नहीं हैं, लेकिन लाल सुरक्षा चेतावनी लॉक रहेगी।"
        ),
        "major_step_1": "डॉक्टर या फार्मासिस्ट की स्पष्ट मंजूरी के बिना इन दवाओं को एक साथ न लें।",
        "major_step_2": "अपनी सभी सक्रिय दवाओं की सूची अपने डॉक्टर को दिखाएं।",
        "major_extractive_headline": "{query} के लिए गंभीर सुरक्षा चेतावनी — डॉक्टर की जांच आवश्यक।",
        "interaction_abstain_headline": "मोनोग्राफ उपलब्ध नहीं; {risk} इंटरैक्शन जोखिम मिला।",
        "interaction_abstain_body": (
            "{salts} के लिए आधिकारिक मोनोग्राफ उपलब्ध नहीं हैं। स्वचालित जांच में "
            "{salt_a} + {salt_b} ({severity}) इंटरैक्शन जोखिम मिला है।"
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b} के लिए {risk} इंटरैक्शन जोखिम।",
        "interaction_step_1": "एक साथ लेने से पहले डॉक्टर या फार्मासिस्ट से पुष्टि करें।",
        "interaction_step_2": "दवा के पैकेट में दी गई जानकारी और खुराक निर्देश पढ़ें।",
        "no_monograph_headline": "विस्तृत मोनोग्राफ जानकारी उपलब्ध नहीं है।",
        "no_monograph_body": (
            "{query} के लिए कोई डुप्लिकेट सॉल्ट या ज्ञात इंटरैक्शन नहीं मिला, लेकिन आधिकारिक "
            "मोनोग्राफ सेक्शन अभी उपलब्ध नहीं हैं।"
        ),
        "no_interaction_headline": "{query} के लिए कोई ज्ञात इंटरैक्शन नहीं मिला।",
        "no_interaction_body": (
            "आपके कैबिनेट की दवाओं के साथ कोई डुप्लिकेट सॉल्ट या सक्रिय इंटरैक्शन नहीं मिला। "
            "मोनोग्राफ संदर्भ: {snippets}"
        ),
        "safe_step": "निर्धारित खुराक के अनुसार लें और किसी भी असामान्य लक्षण पर ध्यान दें।",
        "allergy_conflict": "दर्ज एलर्जी टकराव ({items})",
        "duplicate_salt": "डुप्लिकेट सक्रिय तत्व ({items})",
        "major_ddi": "गंभीर दवा इंटरैक्शन ({items})",
        "evidence_prefix": "आधिकारिक मोनोग्राफ अंश:",
    },
    "bn": {
        "unrecognized_headline": "'{query}'-এর সক্রিয় উপাদান শনাক্ত করা যায়নি।",
        "unrecognized_body": (
            "PillScope '{query}' ওষুধটি শনাক্ত করতে পারেনি। এটি আপনার বর্তমান ওষুধের সাথে নিরাপদ "
            "ধরে নেবেন না — প্যাকেটের জেনেরিক নাম দেখে আবার খুঁজুন বা ফার্মাসিস্টকে জিজ্ঞাসা করুন।"
        ),
        "unrecognized_step_1": "প্যাকেট বা বোতলে লেখা জেনেরিক সল্টের নাম লিখুন।",
        "unrecognized_step_2": "অপরিচিত ওষুধ খাওয়ার আগে চিকিৎসক বা ফার্মাসিস্টের পরামর্শ নিন।",
        "major_locked_headline": "গুরুতর নিরাপত্তা সতর্কতা লক করা হয়েছে: {detail}।",
        "major_locked_body": (
            "{query}-এর জন্য নিরাপত্তা নিয়মে {detail} শনাক্ত হয়েছে। সরকারি মনোগ্রাফ পাওয়া যায়নি, "
            "তবে লাল নিরাপত্তা সতর্কতা সক্রিয় থাকবে।"
        ),
        "major_step_1": "চিকিৎসক বা ফার্মাসিস্টের অনুমোদন ছাড়া এই ওষুধগুলো একসাথে খাবেন না।",
        "major_step_2": "আপনার সব সক্রিয় ওষুধের তালিকা চিকিৎসককে দেখান।",
        "major_extractive_headline": "{query}-এর জন্য গুরুতর নিরাপত্তা সতর্কতা — চিকিৎসকের পরামর্শ প্রয়োজন।",
        "interaction_abstain_headline": "মনোগ্রাফ অনুপলব্ধ; {risk} ইন্টারঅ্যাকশন ঝুঁকি শনাক্ত।",
        "interaction_abstain_body": (
            "{salts}-এর জন্য সরকারি মনোগ্রাফ পাওয়া যায়নি। স্বয়ংক্রিয় পরীক্ষায় "
            "{salt_a} + {salt_b} ({severity}) ঝুঁকি শনাক্ত হয়েছে।"
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b}-এর জন্য {risk} ইন্টারঅ্যাকশন ঝুঁকি।",
        "interaction_step_1": "একসাথে খাওয়ার আগে চিকিৎসক বা ফার্মাসিস্টের সাথে যাচাই করুন।",
        "interaction_step_2": "ওষুধের প্যাকেটের নির্দেশিকা মনোযোগ দিয়ে পড়ুন।",
        "no_monograph_headline": "মনোগ্রাফ তথ্য পর্যাপ্ত নয়।",
        "no_monograph_body": (
            "{query}-এর জন্য কোনো ডুপ্লিকেট সল্ট বা পরিচিত ইন্টারঅ্যাকশন পাওয়া যায়নি, তবে "
            "সরকারি মনোগ্রাফ এখনও যুক্ত নেই।"
        ),
        "no_interaction_headline": "{query}-এর জন্য কোনো পরিচিত ইন্টারঅ্যাকশন পাওয়া যায়নি।",
        "no_interaction_body": (
            "আপনার ক্যাবিনেটের ওষুধের সাথে কোনো ডুপ্লিকেট সল্ট বা ইন্টারঅ্যাকশন মেলেনি। "
            "মনোগ্রাফ তথ্য: {snippets}"
        ),
        "safe_step": "নির্ধারিত মাত্রা মেনে চলুন এবং কোনো পার্শ্বপ্রতিক্রিয়া হলে চিকিৎসককে জানান।",
        "allergy_conflict": "অ্যালার্জি সংঘাত ({items})",
        "duplicate_salt": "ডুপ্লিকেট সক্রিয় উপাদান ({items})",
        "major_ddi": "গুরুতর ওষুধ ইন্টারঅ্যাকশন ({items})",
        "evidence_prefix": "সরকারি মনোগ্রাফ উদ্ধৃতি:",
    },
    "te": {
        "unrecognized_headline": "'{query}' లోని క్రియాశీల పదార్థాలను గుర్తించలేకపోయాము.",
        "unrecognized_body": (
            "PillScope '{query}' ను తెలిసిన మందుగా గుర్తించలేదు. దీనిని సురక్షితమని భావించకండి — "
            "ప్యాక్ పై ఉన్న జెనెరిక్ పేరుతో మళ్ళీ శోధించండి లేదా ఫార్మసిస్ట్‌ను అడగండి."
        ),
        "unrecognized_step_1": "స్ట్రిప్ లేదా బాటిల్ పై ఉన్న జెనెరిక్ సాల్ట్ పేరును నమోదు చేయండి.",
        "unrecognized_step_2": "గుర్తించని మందు తీసుకునే ముందు వైద్యుడిని సంప్రదించండి.",
        "major_locked_headline": "ముఖ్యమైన భద్రతా హెచ్చరిక లాక్ చేయబడింది: {detail}.",
        "major_locked_body": (
            "{query} కోసం భద్రతా నియమాలు {detail} గుర్తించాయి. అధికారిక మోనోగ్రాఫ్‌లు అందుబాటులో "
            "లేవు, కానీ ఎరుపు భద్రతా హెచ్చరిక లాక్‌లో ఉంటుంది."
        ),
        "major_step_1": "డాక్టర్ లేదా ఫార్మసిస్ట్ అనుమతి లేకుండా ఈ మందులను కలిపి తీసుకోకండి.",
        "major_step_2": "మీరు వాడుతున్న అన్ని మందుల జాబితాను వైద్యుడికి చూపించండి.",
        "major_extractive_headline": "{query} కు తీవ్ర భద్రతా హెచ్చరిక — వైద్యుల సమీక్ష అవసరం.",
        "interaction_abstain_headline": "మోనోగ్రాఫ్ అందుబాటులో లేదు; {risk} పరస్పర చర్య ప్రమాదం ఉంది.",
        "interaction_abstain_body": (
            "{salts} కు అధికారిక మోనోగ్రాఫ్‌లు లేవు. ఆటోమేటిక్ తనిఖీలో "
            "{salt_a} + {salt_b} ({severity}) ప్రమాదం గుర్తించబడింది."
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b} కు {risk} పరస్పర చర్య ప్రమాదం.",
        "interaction_step_1": "కలిపి వాడే ముందు డాక్టర్ లేదా ఫార్మసిస్ట్‌తో నిర్ధారించుకోండి.",
        "interaction_step_2": "మందు ప్యాకెట్ లోని సూచనలను చదవండి.",
        "no_monograph_headline": "అధికారిక మోనోగ్రాఫ్ వివరాలు అందుబాటులో లేవు.",
        "no_monograph_body": (
            "{query} కు డూప్లికేట్ సాల్ట్ లేదా తెలిసిన పరస్పర చర్యలు కనిపించలేదు, కానీ అధికారిక "
            "మోనోగ్రాఫ్ విభాగాలు ఇంకా అందుబాటులో లేవు."
        ),
        "no_interaction_headline": "{query} కు తెలిసిన పరస్పర చర్యలు కనుగొనబడలేదు.",
        "no_interaction_body": (
            "మీ క్యాబినెట్ మందులతో డూప్లికేట్ సాల్ట్ లేదా పరస్పర చర్యలు కనిపించలేదు. "
            "మోనోగ్రాఫ్ వివరాలు: {snippets}"
        ),
        "safe_step": "సూచించిన మోతాదును పాటించండి మరియు ఏవైనా అసాధారణ లక్షణాలను గమనించండి.",
        "allergy_conflict": "నమోదైన అలెర్జీ హెచ్చరిక ({items})",
        "duplicate_salt": "డూప్లికేట్ క్రియాశీల పదార్థం ({items})",
        "major_ddi": "తీవ్ర మందుల పరస్పర చర్య ({items})",
        "evidence_prefix": "అధికారిక మోనోగ్రాఫ్ ఆధారాలు:",
    },
    "mr": {
        "unrecognized_headline": "'{query}' मधील सक्रिय घटक ओळखता आले नाहीत.",
        "unrecognized_body": (
            "PillScope ला '{query}' हे औषध ओळखता आले नाही. ते सुरक्षित मानू नका — पॅकवरील "
            "जेनेरिक सॉल्टचे नाव पाहून पुन्हा शोधा किंवा फार्मासिस्टला विचारा."
        ),
        "unrecognized_step_1": "स्ट्रिप किंवा बाटलीवर लिहिलेले जेनेरिक सॉल्टचे नाव टाइप करा.",
        "unrecognized_step_2": "अनोळखी औषध घेण्यापूर्वी डॉक्टर किंवा फार्मासिस्टचा सल्ला घ्या.",
        "major_locked_headline": "गंभीर सुरक्षा इशारा लॉक आहे: {detail}.",
        "major_locked_body": (
            "{query} साठी सुरक्षा नियमांनी {detail} शोधले आहे. अधिकृत मोनोग्राफ उपलब्ध नाहीत, "
            "परंतु लाल सुरक्षा इशारा लॉक राहील."
        ),
        "major_step_1": "डॉक्टर किंवा फार्मासिस्टच्या परवानगीशिवाय ही औषधे एकत्र घेऊ नका.",
        "major_step_2": "तुमच्या सर्व सक्रिय औषधांची यादी डॉक्टरांना दाखवा.",
        "major_extractive_headline": "{query} साठी गंभीर सुरक्षा इशारा — डॉक्टरांचा सल्ला आवश्यक.",
        "interaction_abstain_headline": "मोनोग्राफ उपलब्ध नाही; {risk} परस्परक्रिया धोका आढळला.",
        "interaction_abstain_body": (
            "{salts} साठी अधिकृत मोनोग्राफ नाहीत. स्वयंचलित तपासणीत "
            "{salt_a} + {salt_b} ({severity}) धोका आढळला आहे."
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b} साठी {risk} परस्परक्रिया धोका.",
        "interaction_step_1": "एकत्र घेण्यापूर्वी डॉक्टर किंवा फार्मासिस्टशी खात्री करा.",
        "interaction_step_2": "औषधाच्या पॅकेटवरील सूचना वाचा.",
        "no_monograph_headline": "अधिकृत मोनोग्राफ माहिती उपलब्ध नाही.",
        "no_monograph_body": (
            "{query} साठी डुप्लिकेट सॉल्ट किंवा ज्ञात परस्परक्रिया आढळली नाही, परंतु अधिकृत "
            "मोनोग्राफ सध्या उपलब्ध नाहीत."
        ),
        "no_interaction_headline": "{query} साठी कोणतीही ज्ञात परस्परक्रिया आढळली नाही.",
        "no_interaction_body": (
            "तुमच्या कॅबिनेटमधील औषधांसोबत कोणताही डुप्लिकेट सॉल्ट किंवा धोका आढळला नाही. "
            "मोनोग्राफ माहिती: {snippets}"
        ),
        "safe_step": "सांगितलेल्या मात्रेनुसार औषध घ्या आणि दुष्परिणामांवर लक्ष ठेवा.",
        "allergy_conflict": "नोंदवलेली ॲलर्जी ({items})",
        "duplicate_salt": "डुप्लिकेट सक्रिय घटक ({items})",
        "major_ddi": "गंभीर औषध परस्परक्रिया ({items})",
        "evidence_prefix": "अधिकृत मोनोग्राफ संदर्भ:",
    },
    "ta": {
        "unrecognized_headline": "'{query}' மருந்தின் மூலப்பொருட்களை அடையாளம் காண முடியவில்லை.",
        "unrecognized_body": (
            "PillScope '{query}' மருந்தை அடையாளம் காணவில்லை. இது பாதுகாப்பானது என்று கருத வேண்டாம் — "
            "அட்டையில் உள்ள ஜெனரிக் மூலப்பொருள் பெயரை தேடுங்கள் அல்லது மருந்தாளரைக் கேளுங்கள்."
        ),
        "unrecognized_step_1": "மருந்து அட்டை அல்லது பாட்டிலில் உள்ள ஜெனரிக் பெயரை உள்ளிடவும்.",
        "unrecognized_step_2": "அடையாளம் தெரியாத மருந்தை எடுக்கும் முன் மருத்துவரை அணுகவும்.",
        "major_locked_headline": "முக்கிய பாதுகாப்பு எச்சரிக்கை பூட்டப்பட்டது: {detail}.",
        "major_locked_body": (
            "{query} மருந்திற்கு பாதுகாப்பு விதிகள் {detail} கண்டறிந்துள்ளன. அதிகாரப்பூர்வ "
            "மருந்து ஆவணங்கள் இல்லை என்றாலும் சிவப்பு எச்சரிக்கை பூட்டப்பட்டிருக்கும்."
        ),
        "major_step_1": "மருத்துவர் அல்லது மருந்தாளரின் அனுமதியின்றி இவற்றை ஒன்றாக எடுக்க வேண்டாம்.",
        "major_step_2": "நீங்கள் எடுக்கும் அனைத்து மருந்துகளையும் மருத்துவரிடம் காட்டவும்.",
        "major_extractive_headline": (
            "{query} மருந்திற்கு தீவிர பாதுகாப்பு எச்சரிக்கை — மருத்துவர் ஆலோசனை தேவை."
        ),
        "interaction_abstain_headline": "மருந்து ஆவணம் இல்லை; {risk} இடைவினை ஆபத்து கண்டறியப்பட்டது.",
        "interaction_abstain_body": (
            "{salts} மருந்திற்கு அதிகாரப்பூர்வ ஆவணங்கள் இல்லை. தானியங்கி சோதனையில் "
            "{salt_a} + {salt_b} ({severity}) ஆபத்து கண்டறியப்பட்டது."
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b} இடையே {risk} இடைவினை ஆபத்து.",
        "interaction_step_1": "ஒன்றாக எடுக்கும் முன் மருத்துவர் அல்லது மருந்தாளரிடம் உறுதி செய்யவும்.",
        "interaction_step_2": "மருந்து அட்டையில் உள்ள வழிமுறைகளைப் படிக்கவும்.",
        "no_monograph_headline": "அதிகாரப்பூர்வ மருந்து ஆவண சான்று இல்லை.",
        "no_monograph_body": (
            "{query} மருந்திற்கு இரட்டை மூலப்பொருள் அல்லது தெரிந்த இடைவினை எதுவும் இல்லை, "
            "ஆனால் அதிகாரப்பூர்வ ஆவணங்கள் இன்னும் சேர்க்கப்படவில்லை."
        ),
        "no_interaction_headline": "{query} மருந்திற்கு தெரிந்த இடைவினைகள் இல்லை.",
        "no_interaction_body": (
            "உங்கள் மருந்துப் பெட்டியுடன் இரட்டை மூலப்பொருள் அல்லது இடைவினை கண்டறியப்படவில்லை. "
            "ஆவண வழிகாட்டுதல்: {snippets}"
        ),
        "safe_step": "பரிந்துரைக்கப்பட்ட அளவைப் பின்பற்றி ஏதேனும் பக்கவிளைவுகளைக் கவனிக்கவும்.",
        "allergy_conflict": "பதிவான ஒவ்வாமை முரண்பாடு ({items})",
        "duplicate_salt": "இரட்டை மூலப்பொருள் ({items})",
        "major_ddi": "தீவிர மருந்து இடைவினை ({items})",
        "evidence_prefix": "அதிகாரப்பூர்வ ஆவணப் பகுதிகள்:",
    },
    "gu": {
        "unrecognized_headline": "'{query}' ના સક્રિય ઘટકો ઓળખી શકાયા નથી.",
        "unrecognized_body": (
            "PillScope '{query}' દવાને ઓળખી શક્યું નથી. તેને સુરક્ષિત માની લેશો નહીં — પટ્ટી પર "
            "લખેલું જેનેરિક સોલ્ટ નામ શોધો અથવા ફાર્માસિસ્ટને પૂછો."
        ),
        "unrecognized_step_1": "દવાના પૅક પર લખેલું જેનેરિક સોલ્ટ નામ દાખલ કરો.",
        "unrecognized_step_2": "અજાણી દવા લેતા પહેલા ડૉક્ટર અથવા ફાર્માસિસ્ટની સલાહ લો.",
        "major_locked_headline": "ગંભીર સુરક્ષા ચેતવણી લૉક છે: {detail}.",
        "major_locked_body": (
            "{query} માટે સુરક્ષા નિયમોએ {detail} શોધી કાઢ્યું છે. સત્તાવાર મોનોગ્રાફ ઉપલબ્ધ નથી, "
            "પરંતુ લાલ સુરક્ષા ચેતવણી લૉક રહેશે."
        ),
        "major_step_1": "ડૉક્ટર કે ફાર્માસિસ્ટની મંજૂરી વગર આ દવાઓ સાથે લેશો નહીં.",
        "major_step_2": "તમારી બધી ચાલુ દવાઓની યાદી ડૉક્ટરને બતાવો.",
        "major_extractive_headline": "{query} માટે ગંભીર સુરક્ષા ચેતવણી — ડૉક્ટરની તપાસ જરૂરી.",
        "interaction_abstain_headline": "મોનોગ્રાફ ઉપલબ્ધ નથી; {risk} ઇન્ટરેક્શન જોખમ મળ્યું.",
        "interaction_abstain_body": (
            "{salts} માટે સત્તાવાર મોનોગ્રાફ ઉપલબ્ધ નથી. સ્વચાલિત તપાસમાં "
            "{salt_a} + {salt_b} ({severity}) જોખમ મળ્યું છે."
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b} માટે {risk} ઇન્ટરેક્શન જોખમ.",
        "interaction_step_1": "સાથે લેતા પહેલા ડૉક્ટર અથવા ફાર્માસિસ્ટ સાથે ખાતરી કરો.",
        "interaction_step_2": "દવાના પૅકેટમાં આપેલી સૂચનાઓ વાંચો.",
        "no_monograph_headline": "સત્તાવાર મોનોગ્રાફ માહિતી ઉપલબ્ધ નથી.",
        "no_monograph_body": (
            "{query} માટે કોઈ ડુપ્લિકેટ સોલ્ટ કે જાણીતું ઇન્ટરેક્શન મળ્યું નથી, પરંતુ સત્તાવાર "
            "મોનોગ્રાફ વિભાગો હાલમાં ઉપલબ્ધ નથી."
        ),
        "no_interaction_headline": "{query} માટે કોઈ જાણીતું ઇન્ટરેક્શન મળ્યું નથી.",
        "no_interaction_body": (
            "તમારા કેબિનેટની દવાઓ સાથે કોઈ ડુપ્લિકેટ સોલ્ટ કે ઇન્ટરેક્શન મળ્યું નથી. "
            "મોનોગ્રાફ માર્ગદર્શન: {snippets}"
        ),
        "safe_step": "સૂચવેલી માત્રા મુજબ દવા લો અને કોઈ આડઅસર જણાય તો ડૉક્ટરને જણાવો.",
        "allergy_conflict": "નોંધાયેલી એલર્જી ({items})",
        "duplicate_salt": "ડુપ્લિકેટ સક્રિય ઘટક ({items})",
        "major_ddi": "ગંભીર દવા ઇન્ટરેક્શન ({items})",
        "evidence_prefix": "સત્તાવાર મોનોગ્રાફ અંશ:",
    },
    "kn": {
        "unrecognized_headline": "'{query}' ನ ಸಕ್ರಿಯ ಘಟಕಗಳನ್ನು ಗುರುತಿಸಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ.",
        "unrecognized_body": (
            "PillScope '{query}' ಔಷಧಿಯನ್ನು ಗುರುತಿಸಲಾಗಲಿಲ್ಲ. ಇದನ್ನು ಸುರಕ್ಷಿತ ಎಂದು ಭಾವಿಸಬೇಡಿ — "
            "ಪ್ಯಾಕ್ ಮೇಲಿರುವ ಜೆನೆರಿಕ್ ಹೆಸರನ್ನು ಹುಡುಕಿ ಅಥವಾ ಔಷಧಜ್ಞರನ್ನು ಕೇಳಿ."
        ),
        "unrecognized_step_1": "ಸ್ಟ್ರಿಪ್ ಅಥವಾ ಬಾಟಲಿಯ ಮೇಲಿರುವ ಜೆನೆರಿಕ್ ಸಾಲ್ಟ್ ಹೆಸರನ್ನು ನಮೂದಿಸಿ.",
        "unrecognized_step_2": "ಗುರುತಿಸಲಾಗದ ಔಷಧಿ ತೆಗೆದುಕೊಳ್ಳುವ ಮೊದಲು ವೈದ್ಯರನ್ನು ಸಂಪರ್ಕಿಸಿ.",
        "major_locked_headline": "ಗಂಭೀರ ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ ಲಾಕ್ ಆಗಿದೆ: {detail}.",
        "major_locked_body": (
            "{query} ಗಾಗಿ ಸುರಕ್ಷತಾ ನಿಯಮಗಳು {detail} ಪತ್ತೆಹಚ್ಚಿವೆ. ಅಧಿಕೃತ ಮೊನೊಗ್ರಾಫ್ ಲಭ್ಯವಿಲ್ಲ, "
            "ಆದರೆ ಕೆಂಪು ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ ಲಾಕ್ ಆಗಿರುತ್ತದೆ."
        ),
        "major_step_1": "ವೈದ್ಯರು ಅಥವಾ ಔಷಧಜ್ಞರ ಅನುಮತಿಯಿಲ್ಲದೆ ಇವುಗಳನ್ನು ಒಟ್ಟಿಗೆ ತೆಗೆದುಕೊಳ್ಳಬೇಡಿ.",
        "major_step_2": "ನಿಮ್ಮ ಎಲ್ಲಾ ಸಕ್ರಿಯ ಔಷಧಿಗಳ ಪಟ್ಟಿಯನ್ನು ವೈದ್ಯರಿಗೆ ತೋರಿಸಿ.",
        "major_extractive_headline": "{query} ಗಾಗಿ ಗಂಭೀರ ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ — ವೈದ್ಯರ ಪರಿಶೀಲನೆ ಅಗತ್ಯ.",
        "interaction_abstain_headline": "ಮೊನೊಗ್ರಾಫ್ ಲಭ್ಯವಿಲ್ಲ; {risk} ಪರಸ್ಪರ ಕ್ರಿಯೆಯ ಅಪಾಯ ಪತ್ತೆಯಾಗಿದೆ.",
        "interaction_abstain_body": (
            "{salts} ಗಾಗಿ ಅಧಿಕೃತ ಮೊನೊಗ್ರಾಫ್ ಲಭ್ಯವಿಲ್ಲ. ಸ್ವಯಂಚಾಲಿತ ತಪಾಸಣೆಯಲ್ಲಿ "
            "{salt_a} + {salt_b} ({severity}) ಅಪಾಯ ಕಂಡುಬಂದಿದೆ."
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b} ಗಾಗಿ {risk} ಪರಸ್ಪರ ಕ್ರಿಯೆಯ ಅಪಾಯ.",
        "interaction_step_1": "ಒಟ್ಟಿಗೆ ತೆಗೆದುಕೊಳ್ಳುವ ಮೊದಲು ವೈದ್ಯರು ಅಥವಾ ಔಷಧಜ್ಞರೊಂದಿಗೆ ಖಚಿತಪಡಿಸಿಕೊಳ್ಳಿ.",
        "interaction_step_2": "ಔಷಧಿಯ ಪ್ಯಾಕೆಟ್‌ನಲ್ಲಿರುವ ಸೂಚನೆಗಳನ್ನು ಓದಿ.",
        "no_monograph_headline": "ಅಧಿಕೃತ ಮೊನೊಗ್ರಾಫ್ ಪುರಾವೆ ಲಭ್ಯವಿಲ್ಲ.",
        "no_monograph_body": (
            "{query} ಗಾಗಿ ಯಾವುದೇ ನಕಲಿ ಘಟಕ ಅಥವಾ ತಿಳಿದಿರುವ ಪರಸ್ಪರ ಕ್ರಿಯೆ ಕಂಡುಬಂದಿಲ್ಲ, ಆದರೆ "
            "ಅಧಿಕೃತ ಮೊನೊಗ್ರಾಫ್ ವಿಭಾಗಗಳು ಇನ್ನೂ ಲಭ್ಯವಿಲ್ಲ."
        ),
        "no_interaction_headline": "{query} ಗಾಗಿ ಯಾವುದೇ ತಿಳಿದಿರುವ ಪರಸ್ಪರ ಕ್ರಿಯೆ ಕಂಡುಬಂದಿಲ್ಲ.",
        "no_interaction_body": (
            "ನಿಮ್ಮ ಕ್ಯಾಬಿನೆಟ್ ಔಷಧಿಗಳೊಂದಿಗೆ ಯಾವುದೇ ನಕಲಿ ಘಟಕ ಅಥವಾ ಪರಸ್ಪರ ಕ್ರಿಯೆ ಪತ್ತೆಯಾಗಿಲ್ಲ. "
            "ಮೊನೊಗ್ರಾಫ್ ಮಾರ್ಗದರ್ಶನ: {snippets}"
        ),
        "safe_step": "ಸೂಚಿಸಿದ ಪ್ರಮಾಣವನ್ನು ಪಾಲಿಸಿ ಮತ್ತು ಅನಿರೀಕ್ಷಿತ ಅಡ್ಡಪರಿಣಾಮಗಳನ್ನು ಗಮನಿಸಿ.",
        "allergy_conflict": "ದಾಖಲಾದ ಅಲರ್ಜಿ ಸಂಘರ್ಷ ({items})",
        "duplicate_salt": "ನಕಲಿ ಸಕ್ರಿಯ ಘಟಕ ({items})",
        "major_ddi": "ಗಂಭೀರ ಔಷಧ ಪರಸ್ಪರ ಕ್ರಿಯೆ ({items})",
        "evidence_prefix": "ಅಧಿಕೃತ ಮೊನೊಗ್ರಾಫ್ ಉಲ್ಲೇಖಗಳು:",
    },
    "ml": {
        "unrecognized_headline": "'{query}' എന്നതിലെ സജീവ ഘടകങ്ങൾ തിരിച്ചറിയാനായില്ല.",
        "unrecognized_body": (
            "PillScope-ന് '{query}' തിരിച്ചറിയാനായില്ല. ഇത് സുരക്ഷിതമാണെന്ന് കരുതരുത് — "
            "പാക്കറ്റിലെ ജനറിക് പേര് തിരയുക അല്ലെങ്കിൽ ഫാർമസിസ്റ്റിനോട് ചോദിക്കുക."
        ),
        "unrecognized_step_1": "സ്ട്രിപ്പിലോ കുപ്പിയിലോ ഉള്ള ജനറിക് സാൾട്ട് പേര് നൽകുക.",
        "unrecognized_step_2": "തിരിച്ചറിയാത്ത മരുന്ന് കഴിക്കുന്നതിന് മുമ്പ് ഡോക്ടറെ സമീപിക്കുക.",
        "major_locked_headline": "ഗുരുതര സുരക്ഷാ മുന്നറിയിപ്പ് ലോക്ക് ചെയ്തിരിക്കുന്നു: {detail}.",
        "major_locked_body": (
            "{query} എന്നതിനായി സുരക്ഷാ നിയമങ്ങൾ {detail} കണ്ടെത്തി. ഔദ്യോഗിക മോണോഗ്രാഫ് "
            "ലഭ്യമല്ലെങ്കിലും ചുവപ്പ് സുരക്ഷാ മുന്നറിയിപ്പ് ലോക്ക് ചെയ്തിരിക്കും."
        ),
        "major_step_1": "ഡോക്ടറുടെയോ ഫാർമസിസ്റ്റിന്റെയോ അനുമതിയില്ലാതെ ഇവ ഒരുമിച്ച് കഴിക്കരുത്.",
        "major_step_2": "നിങ്ങൾ കഴിക്കുന്ന എല്ലാ മരുന്നുകളുടെയും പട്ടിക ഡോക്ടറെ കാണിക്കുക.",
        "major_extractive_headline": "{query} എന്നതിന് ഗുരുതര സുരക്ഷാ മുന്നറിയിപ്പ് — ഡോക്ടറുടെ പരിശോധന ആവശ്യമാണ്.",
        "interaction_abstain_headline": "മോണോഗ്രാഫ് ലഭ്യമല്ല; {risk} ഇടപെടൽ സാധ്യത കണ്ടെത്തി.",
        "interaction_abstain_body": (
            "{salts} എന്നതിന് ഔദ്യോഗിക മോണോഗ്രാഫ് ലഭ്യമല്ല. പരിശോധനയിൽ "
            "{salt_a} + {salt_b} ({severity}) സാധ്യത കണ്ടെത്തിയിട്ടുണ്ട്."
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b} എന്നിവയ്ക്ക് {risk} ഇടപെടൽ സാധ്യത.",
        "interaction_step_1": "ഒരുമിച്ച് കഴിക്കുന്നതിന് മുമ്പ് ഡോക്ടറുമായോ ഫാർമസിസ്റ്റുമായോ ഉറപ്പുവരുത്തുക.",
        "interaction_step_2": "മരുന്ന് പാക്കറ്റിലെ നിർദ്ദേശങ്ങൾ വായിക്കുക.",
        "no_monograph_headline": "ഔദ്യോഗിക മോണോഗ്രാഫ് വിവരങ്ങൾ ലഭ്യമല്ല.",
        "no_monograph_body": (
            "{query} എന്നതിന് ഡ്യൂപ്ലിക്കേറ്റ് ഘടകങ്ങളോ അറിയപ്പെടുന്ന ഇടപെടലുകളോ കണ്ടെത്തിയില്ല, "
            "പക്ഷേ ഔദ്യോഗിക മോണോഗ്രാഫ് ഇതുവരെ ചേർത്തിട്ടില്ല."
        ),
        "no_interaction_headline": "{query} എന്നതിന് അറിയപ്പെടുന്ന ഇടപെടലുകൾ കണ്ടെത്തിയില്ല.",
        "no_interaction_body": (
            "നിങ്ങളുടെ ക്യാബിനറ്റിലെ മരുന്നുകളുമായി ഡ്യൂപ്ലിക്കേറ്റ് ഘടകങ്ങളോ ഇടപെടലുകളോ ഇല്ല. "
            "മോണോഗ്രാഫ് വിവരങ്ങൾ: {snippets}"
        ),
        "safe_step": "നിർദ്ദേശിച്ച അളവ് പാലിക്കുകയും പാർശ്വഫലങ്ങൾ ശ്രദ്ധിക്കുകയും ചെയ്യുക.",
        "allergy_conflict": "രേഖപ്പെടുത്തിയ അലർജി ({items})",
        "duplicate_salt": "ഡ്യൂപ്ലിക്കേറ്റ് സജീവ ഘടകം ({items})",
        "major_ddi": "ഗുരുതര മരുന്ന് ഇടപെടൽ ({items})",
        "evidence_prefix": "ഔദ്യോഗിക മോണോഗ്രാഫ് ഭാഗങ്ങൾ:",
    },
    "pa": {
        "unrecognized_headline": "'{query}' ਦੇ ਸਰਗਰਮ ਤੱਤਾਂ ਦੀ ਪਛਾਣ ਨਹੀਂ ਹੋ ਸਕੀ।",
        "unrecognized_body": (
            "PillScope '{query}' ਨੂੰ ਪਛਾਣ ਨਹੀਂ ਸਕਿਆ। ਇਸ ਨੂੰ ਸੁਰੱਖਿਅਤ ਨਾ ਮੰਨੋ — ਪੱਤੇ ਉੱਤੇ ਲਿਖਿਆ "
            "ਜੈਨਰਿਕ ਸਾਲਟ ਨਾਮ ਖੋਜੋ ਜਾਂ ਫਾਰਮਾਸਿਸਟ ਨੂੰ ਪੁੱਛੋ।"
        ),
        "unrecognized_step_1": "ਦਵਾਈ ਦੇ ਪੱਤੇ ਜਾਂ ਬੋਤਲ 'ਤੇ ਲਿਖਿਆ ਜੈਨਰਿਕ ਸਾਲਟ ਨਾਮ ਦਰਜ ਕਰੋ।",
        "unrecognized_step_2": "ਅਣਪਛਾਤੀ ਦਵਾਈ ਲੈਣ ਤੋਂ ਪਹਿਲਾਂ ਡਾਕਟਰ ਜਾਂ ਫਾਰਮਾਸਿਸਟ ਦੀ ਸਲਾਹ ਲਓ।",
        "major_locked_headline": "ਗੰਭੀਰ ਸੁਰੱਖਿਆ ਚੇਤਾਵਨੀ ਲੌਕ ਹੈ: {detail}।",
        "major_locked_body": (
            "{query} ਲਈ ਸੁਰੱਖਿਆ ਨਿਯਮਾਂ ਨੇ {detail} ਪਾਇਆ ਹੈ। ਅਧਿਕਾਰਤ ਮੋਨੋਗ੍ਰਾਫ ਉਪਲਬਧ ਨਹੀਂ ਹਨ, "
            "ਪਰ ਲਾਲ ਸੁਰੱਖਿਆ ਚੇਤਾਵਨੀ ਲੌਕ ਰਹੇਗੀ।"
        ),
        "major_step_1": "ਡਾਕਟਰ ਜਾਂ ਫਾਰਮਾਸਿਸਟ ਦੀ ਮਨਜ਼ੂਰੀ ਤੋਂ ਬਿਨਾਂ ਇਹ ਦਵਾਈਆਂ ਇਕੱਠੇ ਨਾ ਲਓ।",
        "major_step_2": "ਆਪਣੀਆਂ ਸਾਰੀਆਂ ਚੱਲ ਰਹੀਆਂ ਦਵਾਈਆਂ ਦੀ ਸੂਚੀ ਡਾਕਟਰ ਨੂੰ ਦਿਖਾਓ।",
        "major_extractive_headline": "{query} ਲਈ ਗੰਭੀਰ ਸੁਰੱਖਿਆ ਚੇਤਾਵਨੀ — ਡਾਕਟਰ ਦੀ ਸਲਾਹ ਜ਼ਰੂਰੀ।",
        "interaction_abstain_headline": "ਮੋਨੋਗ੍ਰਾਫ ਉਪਲਬਧ ਨਹੀਂ; {risk} ਇੰਟਰਐਕਸ਼ਨ ਖਤਰਾ ਮਿਲਿਆ।",
        "interaction_abstain_body": (
            "{salts} ਲਈ ਅਧਿਕਾਰਤ ਮੋਨੋਗ੍ਰਾਫ ਉਪਲਬਧ ਨਹੀਂ ਹਨ। ਜਾਂਚ ਵਿੱਚ "
            "{salt_a} + {salt_b} ({severity}) ਖਤਰਾ ਮਿਲਿਆ ਹੈ।"
        ),
        "interaction_extractive_headline": "{salt_a} + {salt_b} ਲਈ {risk} ਇੰਟਰਐਕਸ਼ਨ ਖਤਰਾ।",
        "interaction_step_1": "ਇਕੱਠੇ ਲੈਣ ਤੋਂ ਪਹਿਲਾਂ ਡਾਕਟਰ ਜਾਂ ਫਾਰਮਾਸਿਸਟ ਨਾਲ ਪੁਸ਼ਟੀ ਕਰੋ।",
        "interaction_step_2": "ਦਵਾਈ ਦੇ ਪੈਕਟ ਵਿੱਚ ਦਿੱਤੀਆਂ ਹਦਾਇਤਾਂ ਪੜ੍ਹੋ।",
        "no_monograph_headline": "ਅਧਿਕਾਰਤ ਮੋਨੋਗ੍ਰਾਫ ਸਬੂਤ ਉਪਲਬਧ ਨਹੀਂ ਹਨ।",
        "no_monograph_body": (
            "{query} ਲਈ ਕੋਈ ਡੁਪਲੀਕੇਟ ਸਾਲਟ ਜਾਂ ਜਾਣਿਆ ਇੰਟਰਐਕਸ਼ਨ ਨਹੀਂ ਮਿਲਿਆ, ਪਰ ਅਧਿਕਾਰਤ "
            "ਮੋਨੋਗ੍ਰਾਫ ਹਾਲੇ ਉਪਲਬਧ ਨਹੀਂ ਹਨ।"
        ),
        "no_interaction_headline": "{query} ਲਈ ਕੋਈ ਜਾਣਿਆ ਇੰਟਰਐਕਸ਼ਨ ਨਹੀਂ ਮਿਲਿਆ।",
        "no_interaction_body": (
            "ਤੁਹਾਡੇ ਕੈਬਨਿਟ ਦੀਆਂ ਦਵਾਈਆਂ ਨਾਲ ਕੋਈ ਡੁਪਲੀਕੇਟ ਸਾਲਟ ਜਾਂ ਇੰਟਰਐਕਸ਼ਨ ਨਹੀਂ ਮਿਲਿਆ। "
            "ਮੋਨੋਗ੍ਰਾਫ ਜਾਣਕਾਰੀ: {snippets}"
        ),
        "safe_step": "ਦੱਸੀ ਗਈ ਖੁਰਾਕ ਅਨੁਸਾਰ ਲਓ ਅਤੇ ਕਿਸੇ ਵੀ ਅਸਧਾਰਨ ਲੱਛਣ 'ਤੇ ਧਿਆਨ ਦਿਓ।",
        "allergy_conflict": "ਦਰਜ ਐਲਰਜੀ ਟਕਰਾਅ ({items})",
        "duplicate_salt": "ਡੁਪਲੀਕੇਟ ਸਰਗਰਮ ਤੱਤ ({items})",
        "major_ddi": "ਗੰਭੀਰ ਦਵਾਈ ਇੰਟਰਐਕਸ਼ਨ ({items})",
        "evidence_prefix": "ਅਧਿਕਾਰਤ ਮੋਨੋਗ੍ਰਾਫ ਅੰਸ਼:",
    },
}


@dataclass(slots=True)
class BriefingContext:
    submitted_query: str
    resolved_salts: list[str]
    overall_risk: RiskLevel
    safety_banner_locked: bool
    duplicate_salts: list[DuplicateSaltAlert]
    deterministic_alerts: list[DeterministicDdiAlert]
    interactions: list[InteractionItem]
    citations: list[MonographCitation]
    allergy_alerts: list[AllergyAlert] = field(default_factory=list)
    language: str = "en"


@dataclass(slots=True)
class LlmTokenUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0


@dataclass(slots=True)
class VerifiedBriefingResult:
    summary: ClinicalSummary
    verified_citations: list[MonographCitation]
    citation_verified: bool
    blocked_citation_ids: list[str]


class LlmBriefingGenerator(Protocol):
    async def generate(self, context: BriefingContext) -> ClinicalSummary: ...


def build_abstention_summary(context: BriefingContext) -> ClinicalSummary:
    lang = normalize_language_code(context.language)
    spec = get_language_spec(lang)
    tpl = _BRIEFING_TEMPLATES.get(lang)

    if not context.resolved_salts:
        if tpl is not None:
            return ClinicalSummary(
                headline=tpl["unrecognized_headline"].format(query=context.submitted_query)[:300],
                patient_explanation=tpl["unrecognized_body"].format(query=context.submitted_query)[
                    :3000
                ],
                actionable_next_steps=[tpl["unrecognized_step_1"], tpl["unrecognized_step_2"]],
                cited_chunk_ids=[],
                abstained=True,
                abstention_reason="UNRECOGNIZED_MEDICATION",
                language=lang,
                disclaimer=spec.disclaimer,
            )
        return ClinicalSummary(
            headline=f"Could not identify active ingredients for '{context.submitted_query}'.",
            patient_explanation=(
                f"PillScope could not match '{context.submitted_query}' to a known medicine or "
                "extract its active pharmaceutical ingredients. Do not assume this medication is "
                "safe to combine with your cabinet — check the generic salt printed on the pack "
                "and search again or ask a pharmacist."
            ),
            actionable_next_steps=[
                "Enter the generic salt name printed on the blister strip or bottle.",
                "Consult a pharmacist or physician before taking an unidentified medicine.",
            ],
            cited_chunk_ids=[],
            abstained=True,
            abstention_reason="UNRECOGNIZED_MEDICATION",
            language=lang,
            disclaimer=spec.disclaimer,
        )

    if context.safety_banner_locked:
        reasons: list[str] = []
        if context.allergy_alerts:
            allergy_labels = ", ".join(
                sorted({f"{a.canonical_salt} ({a.allergen})" for a in context.allergy_alerts})
            )
            if tpl is not None:
                reasons.append(tpl["allergy_conflict"].format(items=allergy_labels))
            else:
                reasons.append(f"documented allergy conflict ({allergy_labels})")
        if context.duplicate_salts:
            dup_names = ", ".join(sorted({d.canonical_salt for d in context.duplicate_salts}))
            if tpl is not None:
                reasons.append(tpl["duplicate_salt"].format(items=dup_names))
            else:
                reasons.append(f"duplicate active ingredient ({dup_names})")
        major_pairs = [a for a in context.deterministic_alerts if a.severity == "MAJOR"]
        if major_pairs:
            pair_labels = ", ".join(f"{a.salt_a} + {a.salt_b}" for a in major_pairs)
            if tpl is not None:
                reasons.append(tpl["major_ddi"].format(items=pair_labels))
            else:
                reasons.append(f"major drug interaction ({pair_labels})")
        detail = ", ".join(reasons) if tpl is not None and reasons else (
            " and ".join(reasons) if reasons else "deterministic safety trigger"
        )
        if tpl is not None:
            return ClinicalSummary(
                headline=tpl["major_locked_headline"].format(detail=detail)[:300],
                patient_explanation=tpl["major_locked_body"].format(
                    query=context.submitted_query, detail=detail
                )[:3000],
                actionable_next_steps=[tpl["major_step_1"], tpl["major_step_2"]],
                cited_chunk_ids=[],
                abstained=True,
                abstention_reason="NO_INDEXED_MONOGRAPHS",
                language=lang,
                disclaimer=spec.disclaimer,
            )
        return ClinicalSummary(
            headline=f"MAJOR safety alert locked: {detail}.",
            patient_explanation=(
                f"Deterministic safety rules detected a {detail} for {context.submitted_query}. "
                "No official openFDA/DailyMed monograph sections are currently indexed for these "
                "salts, so monograph-based clinical synthesis is abstained; the red safety alert "
                "remains locked."
            ),
            actionable_next_steps=[
                "Do not combine these medications without explicit pharmacist or doctor approval.",
                "Share your full active medication cabinet with your clinician.",
            ],
            cited_chunk_ids=[],
            abstained=True,
            abstention_reason="NO_INDEXED_MONOGRAPHS",
            language=lang,
            disclaimer=spec.disclaimer,
        )

    if context.interactions:
        top = context.interactions[0]
        if tpl is not None:
            salts_str = ", ".join(context.resolved_salts) or context.submitted_query
            return ClinicalSummary(
                headline=tpl["interaction_abstain_headline"].format(risk=context.overall_risk)[
                    :300
                ],
                patient_explanation=tpl["interaction_abstain_body"].format(
                    salts=salts_str,
                    salt_a=top.salt_a,
                    salt_b=top.salt_b,
                    severity=top.severity,
                )[:3000],
                actionable_next_steps=[tpl["interaction_step_1"], tpl["interaction_step_2"]],
                cited_chunk_ids=[],
                abstained=True,
                abstention_reason="NO_INDEXED_MONOGRAPHS",
                language=lang,
                disclaimer=spec.disclaimer,
            )
        return ClinicalSummary(
            headline=(
                f"Monograph evidence unavailable; {context.overall_risk} interaction risk flagged."
            ),
            patient_explanation=(
                f"No official openFDA/DailyMed drug monograph sections are indexed for "
                f"{', '.join(context.resolved_salts) or context.submitted_query}. "
                f"Automated screening still flagged {top.salt_a} + {top.salt_b} ({top.severity}, "
                f"source: {top.source}). Clinical monograph synthesis is abstained."
            ),
            actionable_next_steps=[
                "Verify concurrent use with a pharmacist or prescribing doctor.",
                "Review the physical package insert for drug interactions and dosing guidance.",
            ],
            cited_chunk_ids=[],
            abstained=True,
            abstention_reason="NO_INDEXED_MONOGRAPHS",
            language=lang,
            disclaimer=spec.disclaimer,
        )

    if tpl is not None:
        return ClinicalSummary(
            headline=tpl["no_monograph_headline"][:300],
            patient_explanation=tpl["no_monograph_body"].format(query=context.submitted_query)[
                :3000
            ],
            actionable_next_steps=[tpl["interaction_step_1"]],
            cited_chunk_ids=[],
            abstained=True,
            abstention_reason="NO_INDEXED_MONOGRAPHS",
            language=lang,
            disclaimer=spec.disclaimer,
        )

    return ClinicalSummary(
        headline="Insufficient indexed monograph evidence for clinical synthesis.",
        patient_explanation=(
            f"No duplicate active salts or curated interactions were triggered for "
            f"{context.submitted_query}, but no official openFDA/DailyMed monograph sections are "
            "currently indexed for these salts. PillScope is abstaining from monograph commentary."
        ),
        actionable_next_steps=[
            "Consult a pharmacist or check the official package insert before starting a new drug.",
        ],
        cited_chunk_ids=[],
        abstained=True,
        abstention_reason="NO_INDEXED_MONOGRAPHS",
        language=lang,
        disclaimer=spec.disclaimer,
    )


def build_extractive_grounded_summary(context: BriefingContext) -> ClinicalSummary:
    if not context.citations:
        return build_abstention_summary(context)

    lang = normalize_language_code(context.language)
    spec = get_language_spec(lang)
    tpl = _BRIEFING_TEMPLATES.get(lang)

    top_citations = context.citations[:3]
    cited_ids = [c.chunk_id for c in top_citations]
    evidence_snippets = " ".join(
        f"[{c.canonical_salt} - {c.section_title}]: {c.excerpt[:220].strip()}"
        for c in top_citations
    )

    if context.safety_banner_locked:
        alert_parts: list[str] = []
        if context.allergy_alerts:
            allergies = ", ".join(
                f"{a.canonical_salt} ({a.allergen})"
                if tpl is not None
                else f"{a.canonical_salt} matches documented allergy {a.allergen}"
                for a in context.allergy_alerts
            )
            if tpl is not None:
                alert_parts.append(tpl["allergy_conflict"].format(items=allergies) + ".")
            else:
                alert_parts.append(f"Documented allergy conflict: {allergies}.")
        if context.duplicate_salts:
            dups = ", ".join(
                f"{d.canonical_salt} ({d.existing_display_name})"
                if tpl is not None
                else f"{d.canonical_salt} (already in {d.existing_display_name})"
                for d in context.duplicate_salts
            )
            if tpl is not None:
                alert_parts.append(tpl["duplicate_salt"].format(items=dups) + ".")
            else:
                alert_parts.append(f"Duplicate active salt: {dups}.")
        for alert in context.deterministic_alerts:
            if alert.severity == "MAJOR":
                if tpl is not None:
                    alert_parts.append(
                        f"{tpl['major_ddi'].format(items=f'{alert.salt_a} + {alert.salt_b}')}: "
                        f"{alert.clinical_effect} {alert.management}"
                    )
                else:
                    alert_parts.append(
                        f"Major interaction between {alert.salt_a} and {alert.salt_b}: "
                        f"{alert.clinical_effect} {alert.management}"
                    )
        if tpl is not None:
            headline = tpl["major_extractive_headline"].format(query=context.submitted_query)
            explanation = f"{' '.join(alert_parts)} {tpl['evidence_prefix']} {evidence_snippets}"
            steps = [tpl["major_step_1"], tpl["major_step_2"]]
        else:
            headline = (
                f"MAJOR safety warning for {context.submitted_query} — clinician review required."
            )
            explanation = (
                f"{' '.join(alert_parts)} Grounded monograph excerpts: {evidence_snippets}"
            )
            steps = [
                "Pause before taking this medication alongside your active cabinet.",
                (
                    "Contact your prescribing clinician or pharmacist immediately "
                    "to review alternatives."
                ),
            ]
    elif context.interactions:
        top = context.interactions[0]
        if tpl is not None:
            headline = tpl["interaction_extractive_headline"].format(
                risk=context.overall_risk, salt_a=top.salt_a, salt_b=top.salt_b
            )
            explanation = (
                f"{top.salt_a} + {top.salt_b} ({top.severity}): {top.mechanism} "
                f"{tpl['evidence_prefix']} {evidence_snippets}"
            )
            steps = [tpl["interaction_step_1"], tpl["interaction_step_2"]]
        else:
            headline = (
                f"{context.overall_risk} interaction risk noted for {top.salt_a} + {top.salt_b}."
            )
            explanation = (
                f"Screening identified a {top.severity} interaction between {top.salt_a} and "
                f"{top.salt_b} ({top.mechanism}). Grounded monograph excerpts: {evidence_snippets}"
            )
            steps = [
                top.management,
                "Discuss monitoring or dose spacing with your pharmacist.",
            ]
    else:
        if tpl is not None:
            headline = tpl["no_interaction_headline"].format(query=context.submitted_query)
            explanation = tpl["no_interaction_body"].format(snippets=evidence_snippets)
            steps = [tpl["safe_step"]]
        else:
            headline = f"No curated interactions detected for {context.submitted_query}."
            explanation = (
                f"No duplicate salts or active interactions were detected against your cabinet. "
                f"Relevant monograph guidance: {evidence_snippets}"
            )
            steps = [
                "Follow prescribed dosing instructions and monitor for unexpected side effects.",
            ]

    return ClinicalSummary(
        headline=headline[:300],
        patient_explanation=explanation[:3000],
        actionable_next_steps=steps,
        cited_chunk_ids=cited_ids,
        abstained=False,
        abstention_reason=None,
        language=lang,
        disclaimer=spec.disclaimer,
    )


def _extract_embedded_uuids(summary: ClinicalSummary) -> set[str]:
    blob = " ".join([summary.headline, summary.patient_explanation, *summary.actionable_next_steps])
    return {match.lower() for match in _UUID_RE.findall(blob)}


def _contains_downgrade_language(summary: ClinicalSummary) -> bool:
    blob = f"{summary.headline} {summary.patient_explanation}"
    return any(pattern.search(blob) is not None for pattern in _DOWNGRADE_PATTERNS)


def verify_clinical_briefing(
    raw_summary: ClinicalSummary,
    context: BriefingContext,
) -> VerifiedBriefingResult:
    valid_by_id: dict[UUID, MonographCitation] = {c.chunk_id: c for c in context.citations}
    valid_id_strs: set[str] = {str(cid).lower() for cid in valid_by_id}

    blocked: list[str] = []
    for cid in raw_summary.cited_chunk_ids:
        if cid not in valid_by_id:
            blocked.append(str(cid))

    for embedded in sorted(_extract_embedded_uuids(raw_summary)):
        if embedded not in valid_id_strs and embedded not in blocked:
            blocked.append(embedded)

    if not context.citations:
        safe_abstention = build_abstention_summary(context)
        return VerifiedBriefingResult(
            summary=safe_abstention,
            verified_citations=[],
            citation_verified=True,
            blocked_citation_ids=blocked,
        )

    valid_cited_ids: list[UUID] = list(
        dict.fromkeys(cid for cid in raw_summary.cited_chunk_ids if cid in valid_by_id)
    )

    fabricated_detected = len(blocked) > 0
    missing_citations = len(valid_cited_ids) == 0
    downgraded_major = context.safety_banner_locked and _contains_downgrade_language(raw_summary)

    if fabricated_detected or missing_citations or downgraded_major:
        logger.warning(
            "clinical_briefing_verifier_override",
            fabricated_detected=fabricated_detected,
            blocked_citation_ids=blocked,
            missing_citations=missing_citations,
            downgraded_major=downgraded_major,
        )
        fallback = build_extractive_grounded_summary(context)
        verified_cites = [
            valid_by_id[cid] for cid in fallback.cited_chunk_ids if cid in valid_by_id
        ]
        return VerifiedBriefingResult(
            summary=fallback,
            verified_citations=verified_cites,
            citation_verified=True,
            blocked_citation_ids=blocked,
        )

    lang = normalize_language_code(context.language)
    spec = get_language_spec(lang)
    cleaned = raw_summary.model_copy(
        update={
            "cited_chunk_ids": valid_cited_ids,
            "abstained": False,
            "abstention_reason": None,
            "language": lang,
            "disclaimer": spec.disclaimer,
        }
    )
    verified_cites = [valid_by_id[cid] for cid in valid_cited_ids]
    return VerifiedBriefingResult(
        summary=cleaned,
        verified_citations=verified_cites,
        citation_verified=True,
        blocked_citation_ids=[],
    )


def _build_llm_prompt(context: BriefingContext) -> str:
    spec = get_language_spec(context.language)
    payload = {
        "submitted_query": context.submitted_query,
        "resolved_salts": context.resolved_salts,
        "overall_risk": context.overall_risk,
        "safety_banner_locked": context.safety_banner_locked,
        "response_language_code": spec.code,
        "response_language_name": f"{spec.english_name} ({spec.native_name})",
        "duplicate_salts": [d.model_dump(mode="json") for d in context.duplicate_salts],
        "allergy_alerts": [a.model_dump(mode="json") for a in context.allergy_alerts],
        "deterministic_alerts": [a.model_dump(mode="json") for a in context.deterministic_alerts],
        "interactions": [i.model_dump(mode="json") for i in context.interactions],
        "monograph_chunks": [
            {
                "chunk_id": str(c.chunk_id),
                "canonical_salt": c.canonical_salt,
                "section_title": c.section_title,
                "loinc_section_code": c.loinc_section_code,
                "excerpt": c.excerpt,
            }
            for c in context.citations
        ],
    }
    return (
        "You are PillScope's multilingual clinical medication safety briefing synthesizer.\n"
        f"Language requirement: {language_prompt_instruction(spec.code)}\n"
        "Strict rules:\n"
        "1. NEVER contradict or downgrade overall_risk or safety_banner_locked.\n"
        "2. ONLY cite chunk_id UUIDs present in monograph_chunks.\n"
        "3. Never invent monograph claims, studies, or UUIDs.\n"
        "4. Write headline, patient_explanation, and actionable_next_steps in the target language "
        "so they read naturally and sound clear when read aloud by Text-to-Speech.\n"
        "5. Return valid JSON matching the ClinicalSummary schema.\n\n"
        f"INPUT_JSON:\n{json.dumps(payload, indent=2, ensure_ascii=False)}"
    )


class DeterministicGroundedBriefingGenerator:
    provider_name = "deterministic_extractive"
    model_name = "extractive-v1"

    def __init__(self) -> None:
        self.last_usage = LlmTokenUsage()

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        self.last_usage = LlmTokenUsage()
        return build_extractive_grounded_summary(context)


class GeminiBriefingGenerator:
    provider_name = "gemini"

    def __init__(
        self,
        *,
        api_key: str,
        model_name: str = "gemini-2.5-flash",
        timeout_seconds: float = 20.0,
    ) -> None:
        self._api_key = api_key
        self.model_name = model_name
        self._timeout = timeout_seconds
        self.last_usage = LlmTokenUsage()

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        self.last_usage = LlmTokenUsage()
        if not context.citations:
            return build_abstention_summary(context)

        url = (
            f"https://generativelanguage.googleapis.com/v1beta/models/"
            f"{self.model_name}:generateContent"
        )
        schema: dict[str, Any] = {
            "type": "object",
            "properties": {
                "headline": {"type": "string"},
                "patient_explanation": {"type": "string"},
                "actionable_next_steps": {"type": "array", "items": {"type": "string"}},
                "cited_chunk_ids": {"type": "array", "items": {"type": "string"}},
            },
            "required": [
                "headline",
                "patient_explanation",
                "actionable_next_steps",
                "cited_chunk_ids",
            ],
        }
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                url,
                headers={"x-goog-api-key": self._api_key},
                json={
                    "contents": [{"parts": [{"text": _build_llm_prompt(context)}]}],
                    "generationConfig": {
                        "temperature": 0.1,
                        "responseMimeType": "application/json",
                        "responseSchema": schema,
                    },
                },
            )
            response.raise_for_status()
            data = response.json()
            usage_meta = data.get("usageMetadata") or {}
            self.last_usage = LlmTokenUsage(
                input_tokens=int(usage_meta.get("promptTokenCount") or 0),
                output_tokens=int(usage_meta.get("candidatesTokenCount") or 0),
                total_tokens=int(usage_meta.get("totalTokenCount") or 0),
            )
            text_out = data["candidates"][0]["content"]["parts"][0]["text"]
            return ClinicalSummary.model_validate_json(text_out)


class OpenAiBriefingGenerator:
    provider_name = "openai"

    def __init__(
        self,
        *,
        api_key: str,
        model_name: str = "gpt-4o-mini",
        timeout_seconds: float = 20.0,
    ) -> None:
        self._api_key = api_key
        self.model_name = model_name
        self._timeout = timeout_seconds
        self.last_usage = LlmTokenUsage()

    async def generate(self, context: BriefingContext) -> ClinicalSummary:
        self.last_usage = LlmTokenUsage()
        if not context.citations:
            return build_abstention_summary(context)

        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                "https://api.openai.com/v1/chat/completions",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={
                    "model": self.model_name,
                    "temperature": 0.1,
                    "response_format": {"type": "json_object"},
                    "messages": [
                        {
                            "role": "system",
                            "content": "Return strict JSON matching ClinicalSummary.",
                        },
                        {"role": "user", "content": _build_llm_prompt(context)},
                    ],
                },
            )
            response.raise_for_status()
            payload = response.json()
            usage_meta = payload.get("usage") or {}
            self.last_usage = LlmTokenUsage(
                input_tokens=int(usage_meta.get("prompt_tokens") or 0),
                output_tokens=int(usage_meta.get("completion_tokens") or 0),
                total_tokens=int(usage_meta.get("total_tokens") or 0),
            )
            content = payload["choices"][0]["message"]["content"]
            return ClinicalSummary.model_validate_json(content)


def get_briefing_generator(settings: Settings | None = None) -> LlmBriefingGenerator:
    resolved = settings or get_settings()
    if resolved.llm_provider == "gemini" and resolved.gemini_api_key:
        return GeminiBriefingGenerator(
            api_key=resolved.gemini_api_key,
            model_name=resolved.llm_model,
            timeout_seconds=resolved.llm_timeout_seconds,
        )
    if resolved.llm_provider == "openai" and resolved.openai_api_key:
        return OpenAiBriefingGenerator(
            api_key=resolved.openai_api_key,
            model_name=resolved.llm_model,
            timeout_seconds=resolved.llm_timeout_seconds,
        )
    if resolved.app_env != "test":
        logger.warning(
            "llm_api_key_missing_using_deterministic_fallback",
            llm_provider=resolved.llm_provider,
            app_env=resolved.app_env,
        )
    return DeterministicGroundedBriefingGenerator()
