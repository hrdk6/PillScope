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

SpeechTone = Literal["reassuring", "caution", "urgent"]


@dataclass(frozen=True, slots=True)
class SpokenLexicon:
    mg: str
    mcg: str
    ml: str
    gram: str
    bid: str
    tid: str
    qid: str
    od: str
    hs: str
    prn: str
    per: str
    ratio_to: str
    and_word: str
    or_word: str
    sentence_end: str


@dataclass(frozen=True, slots=True)
class LanguageSpec:
    code: SupportedLanguageCode
    bcp47: str
    english_name: str
    native_name: str
    script_name: str
    edge_tts_voice: str
    disclaimer: str
    safety_override_prefix: str
    verbal_major_warning_prefix: str
    followup_prompts: tuple[str, str, str]
    spoken_lexicon: SpokenLexicon


SUPPORTED_LANGUAGES: dict[SupportedLanguageCode, LanguageSpec] = {
    "en": LanguageSpec(
        code="en",
        bcp47="en-IN",
        english_name="English (India)",
        native_name="English",
        script_name="Latin",
        edge_tts_voice="en-IN-NeerjaNeural",
        disclaimer=(
            "Informational safety check only; consult a licensed clinician or pharmacist "
            "before starting, stopping, or changing any medication."
        ),
        safety_override_prefix="CRITICAL SAFETY ALERT:",
        verbal_major_warning_prefix="Please pause for an important safety alert.",
        followup_prompts=(
            "Can I take this with my current medicines?",
            "What side effects or food warnings should I know?",
            "When is the safest time of day to take this?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="milligrams",
            mcg="micrograms",
            ml="milliliters",
            gram="grams",
            bid="twice a day",
            tid="three times a day",
            qid="four times a day",
            od="once a day",
            hs="at bedtime",
            prn="as needed",
            per="per",
            ratio_to="to",
            and_word="and",
            or_word="or",
            sentence_end=".",
        ),
    ),
    "hi": LanguageSpec(
        code="hi",
        bcp47="hi-IN",
        english_name="Hindi",
        native_name="हिन्दी",
        script_name="Devanagari",
        edge_tts_voice="hi-IN-SwaraNeural",
        disclaimer=(
            "यह केवल जानकारी के लिए सुरक्षा जांच है; कोई भी दवा शुरू करने, बंद करने या बदलने से "
            "पहले डॉक्टर या फार्मासिस्ट से सलाह लें।"
        ),
        safety_override_prefix="गंभीर सुरक्षा चेतावनी:",
        verbal_major_warning_prefix="कृपया थोड़ा रुकें, यह एक महत्वपूर्ण सुरक्षा चेतावनी है।",
        followup_prompts=(
            "क्या मैं यह दवा अपनी मौजूदा दवाओं के साथ ले सकता हूँ?",
            "इस दवा के साथ खान-पान या दुष्प्रभाव की क्या सावधानियाँ हैं?",
            "यह दवा दिन में किस समय लेना सबसे सुरक्षित है?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="मिलीग्राम",
            mcg="माइक्रोग्राम",
            ml="मिलीलीटर",
            gram="ग्राम",
            bid="दिन में दो बार",
            tid="दिन में तीन बार",
            qid="दिन में चार बार",
            od="दिन में एक बार",
            hs="सोने से पहले",
            prn="जरूरत पड़ने पर",
            per="प्रति",
            ratio_to="अनुपात",
            and_word="और",
            or_word="या",
            sentence_end="।",
        ),
    ),
    "bn": LanguageSpec(
        code="bn",
        bcp47="bn-IN",
        english_name="Bengali",
        native_name="বাংলা",
        script_name="Bengali",
        edge_tts_voice="bn-IN-TanishaaNeural",
        disclaimer=(
            "এটি শুধুমাত্র তথ্যমূলক নিরাপত্তা পরীক্ষা; কোনো ওষুধ শুরু, বন্ধ বা পরিবর্তন করার আগে "
            "চিকিৎসক বা ফার্মাসিস্টের পরামর্শ নিন।"
        ),
        safety_override_prefix="গুরুতর নিরাপত্তা সতর্কতা:",
        verbal_major_warning_prefix="অনুগ্রহ করে একটু থামুন, এটি একটি গুরুত্বপূর্ণ নিরাপত্তা সতর্কতা।",
        followup_prompts=(
            "আমি কি আমার বর্তমান ওষুধগুলোর সাথে এটি খেতে পারি?",
            "এই ওষুধের খাবার বা পার্শ্বপ্রতিক্রিয়া সংক্রান্ত সতর্কতা কী?",
            "দিনের কোন সময়ে এই ওষুধ খাওয়া সবচেয়ে নিরাপদ?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="মিলিগ্রাম",
            mcg="মাইক্রোগ্রাম",
            ml="মিলিলিটার",
            gram="গ্রাম",
            bid="দিনে দুবার",
            tid="দিনে তিনবার",
            qid="দিনে চারবার",
            od="দিনে একবার",
            hs="ঘুমানোর আগে",
            prn="প্রয়োজন অনুযায়ী",
            per="প্রতি",
            ratio_to="অনুপাত",
            and_word="এবং",
            or_word="বা",
            sentence_end="।",
        ),
    ),
    "te": LanguageSpec(
        code="te",
        bcp47="te-IN",
        english_name="Telugu",
        native_name="తెలుగు",
        script_name="Telugu",
        edge_tts_voice="te-IN-ShrutiNeural",
        disclaimer=(
            "ఇది సమాచారం కోసం మాత్రమే భద్రతా తనిఖీ; ఏ మందునైనా ప్రారంభించే, ఆపే లేదా మార్చే ముందు "
            "వైద్యుడు లేదా ఫార్మసిస్ట్‌ను సంప్రదించండి."
        ),
        safety_override_prefix="ముఖ్యమైన భద్రతా హెచ్చరిక:",
        verbal_major_warning_prefix="దయచేసి కాసేపు ఆగండి, ఇది ముఖ్యమైన భద్రతా హెచ్చరిక.",
        followup_prompts=(
            "నా ప్రస్తుత మందులతో దీనిని తీసుకోవచ్చా?",
            "ఈ మందుతో ఆహారం లేదా దుష్ప్రభావాల జాగ్రత్తలు ఏమిటి?",
            "రోజులో ఏ సమయంలో ఈ మందు తీసుకోవడం సురక్షితం?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="మిల్లీగ్రాములు",
            mcg="మైక్రోగ్రాములు",
            ml="మిల్లీలీటర్లు",
            gram="గ్రాములు",
            bid="రోజుకు రెండుసార్లు",
            tid="రోజుకు మూడుసార్లు",
            qid="రోజుకు నాలుగుసార్లు",
            od="రోజుకు ఒకసారి",
            hs="నిద్రపోయే ముందు",
            prn="అవసరమైనప్పుడు",
            per="ప్రతి",
            ratio_to="నిష్పత్తి",
            and_word="మరియు",
            or_word="లేదా",
            sentence_end=".",
        ),
    ),
    "mr": LanguageSpec(
        code="mr",
        bcp47="mr-IN",
        english_name="Marathi",
        native_name="मराठी",
        script_name="Devanagari",
        edge_tts_voice="mr-IN-AarohiNeural",
        disclaimer=(
            "ही केवळ माहितीसाठी सुरक्षा तपासणी आहे; कोणतेही औषध सुरू करण्यापूर्वी, बंद करण्यापूर्वी "
            "किंवा बदलण्यापूर्वी डॉक्टर किंवा फार्मासिस्टचा सल्ला घ्या."
        ),
        safety_override_prefix="गंभीर सुरक्षा इशारा:",
        verbal_major_warning_prefix="कृपया थोडे थांबा, हा एक महत्त्वाचा सुरक्षा इशारा आहे.",
        followup_prompts=(
            "मी हे औषध माझ्या सध्याच्या औषधांसोबत घेऊ शकतो का?",
            "या औषधासोबत आहार किंवा दुष्परिणामांची कोणती काळजी घ्यावी?",
            "हे औषध दिवसातील कोणत्या वेळी घेणे सर्वात सुरक्षित आहे?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="मिलीग्रॅम",
            mcg="मायक्रोग्रॅम",
            ml="मिलीलीटर",
            gram="ग्रॅम",
            bid="दिवसातून दोनदा",
            tid="दिवसातून तीनदा",
            qid="दिवसातून चार वेळा",
            od="दिवसातून एकदा",
            hs="झोपण्यापूर्वी",
            prn="गरजेनुसार",
            per="प्रति",
            ratio_to="प्रमाणात",
            and_word="आणि",
            or_word="किंवा",
            sentence_end=".",
        ),
    ),
    "ta": LanguageSpec(
        code="ta",
        bcp47="ta-IN",
        english_name="Tamil",
        native_name="தமிழ்",
        script_name="Tamil",
        edge_tts_voice="ta-IN-PallaviNeural",
        disclaimer=(
            "இது தகவல் பாதுகாப்பு சரிபார்ப்பு மட்டுமே; எந்த மருந்தையும் தொடங்க, நிறுத்த அல்லது மாற்ற "
            "மருத்துவர் அல்லது மருந்தாளரை அணுகவும்."
        ),
        safety_override_prefix="முக்கிய பாதுகாப்பு எச்சரிக்கை:",
        verbal_major_warning_prefix="தயவுசெய்து சற்று நிறுத்துங்கள், இது முக்கியமான பாதுகாப்பு எச்சரிக்கை.",
        followup_prompts=(
            "எனது தற்போதைய மருந்துகளுடன் இதை எடுக்கலாமா?",
            "இந்த மருந்துக்கான உணவு அல்லது பக்கவிளைவு எச்சரிக்கைகள் என்ன?",
            "இந்த மருந்தை நாளின் எந்த நேரத்தில் எடுப்பது பாதுகாப்பானது?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="மில்லிகிராம்",
            mcg="மைக்ரோகிராம்",
            ml="மில்லிலிட்டர்",
            gram="கிராம்",
            bid="நாளொன்றுக்கு இருமுறை",
            tid="நாளொன்றுக்கு மூன்று முறை",
            qid="நாளொன்றுக்கு நான்கு முறை",
            od="நாளொன்றுக்கு ஒருமுறை",
            hs="தூங்கும் முன்",
            prn="தேவைப்படும்போது",
            per="ஒவ்வொரு",
            ratio_to="விகிதம்",
            and_word="மற்றும்",
            or_word="அல்லது",
            sentence_end=".",
        ),
    ),
    "gu": LanguageSpec(
        code="gu",
        bcp47="gu-IN",
        english_name="Gujarati",
        native_name="ગુજરાતી",
        script_name="Gujarati",
        edge_tts_voice="gu-IN-DhwaniNeural",
        disclaimer=(
            "આ માત્ર માહિતી માટેની સુરક્ષા તપાસ છે; કોઈપણ દવા શરૂ કરતા, બંધ કરતા કે બદલતા પહેલા "
            "ડૉક્ટર અથવા ફાર્માસિસ્ટની સલાહ લો."
        ),
        safety_override_prefix="ગંભીર સુરક્ષા ચેતવણી:",
        verbal_major_warning_prefix="કૃપા કરીને થોડી વાર થોભો, આ એક મહત્વપૂર્ણ સુરક્ષા ચેતવણી છે.",
        followup_prompts=(
            "શું હું આ દવા મારી હાલની દવાઓ સાથે લઈ શકું?",
            "આ દવા સાથે ખોરાક કે આડઅસરની કઈ સાવચેતી રાખવી?",
            "દિવસના કયા સમયે આ દવા લેવી સૌથી સુરક્ષિત છે?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="મિલિગ્રામ",
            mcg="માઇક્રોગ્રામ",
            ml="મિલિલિટર",
            gram="ગ્રામ",
            bid="દિવસમાં બે વાર",
            tid="દિવસમાં ત્રણ વાર",
            qid="દિવસમાં ચાર વાર",
            od="દિવસમાં એક વાર",
            hs="સૂતા પહેલા",
            prn="જરૂર મુજબ",
            per="દીઠ",
            ratio_to="પ્રમાણ",
            and_word="અને",
            or_word="અથવા",
            sentence_end=".",
        ),
    ),
    "kn": LanguageSpec(
        code="kn",
        bcp47="kn-IN",
        english_name="Kannada",
        native_name="ಕನ್ನಡ",
        script_name="Kannada",
        edge_tts_voice="kn-IN-SapnaNeural",
        disclaimer=(
            "ಇದು ಮಾಹಿತಿಗಾಗಿ ಮಾತ್ರ ಸುರಕ್ಷತಾ ಪರಿಶೀಲನೆ; ಯಾವುದೇ ಔಷಧಿಯನ್ನು ಪ್ರಾರಂಭಿಸುವ, ನಿಲ್ಲಿಸುವ ಅಥವಾ "
            "ಬದಲಾಯಿಸುವ ಮೊದಲು ವೈದ್ಯರು ಅಥವಾ ಔಷಧಜ್ಞರನ್ನು ಸಂಪರ್ಕಿಸಿ."
        ),
        safety_override_prefix="ಗಂಭೀರ ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ:",
        verbal_major_warning_prefix="ದಯವಿಟ್ಟು ಸ್ವಲ್ಪ ನಿಲ್ಲಿ, ಇದು ಪ್ರಮುಖ ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ.",
        followup_prompts=(
            "ನನ್ನ ಪ್ರಸ್ತುತ ಔಷಧಿಗಳೊಂದಿಗೆ ಇದನ್ನು ತೆಗೆದುಕೊಳ್ಳಬಹುದೇ?",
            "ಈ ಔಷಧಿಯ ಆಹಾರ ಅಥವಾ ಅಡ್ಡಪರಿಣಾಮಗಳ ಎಚ್ಚರಿಕೆಗಳು ಯಾವುವು?",
            "ದಿನದ ಯಾವ ಸಮಯದಲ್ಲಿ ಈ ಔಷಧಿ ತೆಗೆದುಕೊಳ್ಳುವುದು ಸುರಕ್ಷಿತ?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="ಮಿಲಿಗ್ರಾಂ",
            mcg="ಮೈಕ್ರೋಗ್ರಾಂ",
            ml="ಮಿಲಿಲೀಟರ್",
            gram="ಗ್ರಾಂ",
            bid="ದಿನಕ್ಕೆ ಎರಡು ಬಾರಿ",
            tid="ದಿನಕ್ಕೆ ಮೂರು ಬಾರಿ",
            qid="ದಿನಕ್ಕೆ ನಾಲ್ಕು ಬಾರಿ",
            od="ದಿನಕ್ಕೆ ಒಂದು ಬಾರಿ",
            hs="ಮಲಗುವ ಮುನ್ನ",
            prn="ಅಗತ್ಯವಿದ್ದಾಗ",
            per="ಪ್ರತಿ",
            ratio_to="ಅನುಪಾತ",
            and_word="ಮತ್ತು",
            or_word="ಅಥವಾ",
            sentence_end=".",
        ),
    ),
    "ml": LanguageSpec(
        code="ml",
        bcp47="ml-IN",
        english_name="Malayalam",
        native_name="മലയാളം",
        script_name="Malayalam",
        edge_tts_voice="ml-IN-SobhanaNeural",
        disclaimer=(
            "ഇത് വിവരങ്ങൾക്കായുള്ള സുരക്ഷാ പരിശോധന മാത്രമാണ്; ഏതെങ്കിലും മരുന്ന് തുടങ്ങുന്നതിനോ "
            "നിർത്തുന്നതിനോ മാറ്റുന്നതിനോ മുമ്പ് ഡോക്ടറെയോ ഫാർമസിസ്റ്റിനെയോ സമീപിക്കുക."
        ),
        safety_override_prefix="ഗുരുതര സുരക്ഷാ മുന്നറിയിപ്പ്:",
        verbal_major_warning_prefix="ദയവായി ഒന്ന് നിൽക്കൂ, ഇതൊരു പ്രധാന സുരക്ഷാ മുന്നറിയിപ്പാണ്.",
        followup_prompts=(
            "എന്റെ നിലവിലുള്ള മരുന്നുകൾക്കൊപ്പം ഇത് കഴിക്കാമോ?",
            "ഈ മരുന്നിനുള്ള ഭക്ഷണ മുന്നറിയിപ്പുകളും പാർശ്വഫലങ്ങളും എന്തെല്ലാമാണ്?",
            "ദിവസത്തിലെ ഏത് സമയത്താണ് ഈ മരുന്ന് കഴിക്കാൻ ഏറ്റവും സുരക്ഷിതം?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="മില്ലിഗ്രാം",
            mcg="മൈക്രോഗ്രാം",
            ml="മില്ലിലിറ്റർ",
            gram="ഗ്രാം",
            bid="ദിവസത്തിൽ രണ്ടുതവണ",
            tid="ദിവസത്തിൽ മൂന്നുതവണ",
            qid="ദിവസത്തിൽ നാലുതവണ",
            od="ദിവസത്തിൽ ഒരിക്കൽ",
            hs="ഉറങ്ങുന്നതിന് മുമ്പ്",
            prn="ആവശ്യാനുസരണം",
            per="ഓരോ",
            ratio_to="അനുപാതം",
            and_word="കൂടാതെ",
            or_word="അല്ലെങ്കിൽ",
            sentence_end=".",
        ),
    ),
    "pa": LanguageSpec(
        code="pa",
        bcp47="pa-IN",
        english_name="Punjabi",
        native_name="ਪੰਜਾਬੀ",
        script_name="Gurmukhi",
        edge_tts_voice="pa-IN-VaaniNeural",
        disclaimer=(
            "ਇਹ ਸਿਰਫ਼ ਜਾਣਕਾਰੀ ਲਈ ਸੁਰੱਖਿਆ ਜਾਂਚ ਹੈ; ਕੋਈ ਵੀ ਦਵਾਈ ਸ਼ੁਰੂ ਕਰਨ, ਬੰਦ ਕਰਨ ਜਾਂ ਬਦਲਣ ਤੋਂ "
            "ਪਹਿਲਾਂ ਡਾਕਟਰ ਜਾਂ ਫਾਰਮਾਸਿਸਟ ਦੀ ਸਲਾਹ ਲਓ।"
        ),
        safety_override_prefix="ਗੰਭੀਰ ਸੁਰੱਖਿਆ ਚੇਤਾਵਨੀ:",
        verbal_major_warning_prefix="ਕਿਰਪਾ ਕਰਕੇ ਥੋੜ੍ਹਾ ਰੁਕੋ, ਇਹ ਇੱਕ ਮਹੱਤਵਪੂਰਨ ਸੁਰੱਖਿਆ ਚੇਤਾਵਨੀ ਹੈ।",
        followup_prompts=(
            "ਕੀ ਮੈਂ ਇਹ ਦਵਾਈ ਆਪਣੀਆਂ ਮੌਜੂਦਾ ਦਵਾਈਆਂ ਨਾਲ ਲੈ ਸਕਦਾ ਹਾਂ?",
            "ਇਸ ਦਵਾਈ ਨਾਲ ਖਾਣ-ਪੀਣ ਜਾਂ ਮਾੜੇ ਪ੍ਰਭਾਵਾਂ ਦੀਆਂ ਕਿਹੜੀਆਂ ਸਾਵਧਾਨੀਆਂ ਹਨ?",
            "ਦਿਨ ਦੇ ਕਿਸ ਸਮੇਂ ਇਹ ਦਵਾਈ ਲੈਣਾ ਸਭ ਤੋਂ ਸੁਰੱਖਿਅਤ ਹੈ?",
        ),
        spoken_lexicon=SpokenLexicon(
            mg="ਮਿਲੀਗ੍ਰਾਮ",
            mcg="ਮਾਈਕ੍ਰੋਗ੍ਰਾਮ",
            ml="ਮਿਲੀਲੀਟਰ",
            gram="ਗ੍ਰਾਮ",
            bid="ਦਿਨ ਵਿੱਚ ਦੋ ਵਾਰ",
            tid="ਦਿਨ ਵਿੱਚ ਤਿੰਨ ਵਾਰ",
            qid="ਦਿਨ ਵਿੱਚ ਚਾਰ ਵਾਰ",
            od="ਦਿਨ ਵਿੱਚ ਇੱਕ ਵਾਰ",
            hs="ਸੌਣ ਤੋਂ ਪਹਿਲਾਂ",
            prn="ਲੋੜ ਅਨੁਸਾਰ",
            per="ਪ੍ਰਤੀ",
            ratio_to="ਅਨੁਪਾਤ",
            and_word="ਅਤੇ",
            or_word="ਜਾਂ",
            sentence_end="।",
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

MULTILINGUAL_DOWNGRADE_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\bno\s+(?:major|serious|significant|clinically\s+relevant)\s+interaction", re.I),
    re.compile(r"\bsafe\s+to\s+(?:take|use|combine)\s+together", re.I),
    re.compile(r"\bno\s+interactions?\s+(?:were\s+)?(?:found|detected|identified)", re.I),
    re.compile(r"\bno\s+safety\s+concerns?\b", re.I),
    re.compile(r"\blow\s+risk\b", re.I),
    re.compile(r"एक\s+साथ\s+लेना\s+सुरक्षित", re.I),
    re.compile(r"कोई\s+(?:गंभीर|बड़ा|खतरनाक)\s+(?:इंटरैक्शन|खतरा)\s+नहीं", re.I),
    re.compile(r"एकत्र\s+घेणे\s+सुरक्षित", re.I),
    re.compile(r"\bsaath\s+lena\s+safe\b", re.I),
    re.compile(r"একসাথে\s+(?:খাওয়া|নেওয়া)\s+নিরাপদ", re.I),
    re.compile(r"కలిపి\s+తీసుకోవడం\s+సురక్షితం", re.I),
    re.compile(r"ஒன்றாக\s+எடுப்பது\s+பாதுகாப்பானது", re.I),
    re.compile(r"સાથે\s+લેવી\s+સુરક્ષિત", re.I),
    re.compile(r"ಒಟ್ಟಿಗೆ\s+ತೆಗೆದುಕೊಳ್ಳುವುದು\s+ಸುರಕ್ಷಿತ", re.I),
    re.compile(r"ഒരുമിച്ച്\s+കഴിക്കുന്നത്\s+സുരക്ഷിതമാണ്", re.I),
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
        return "Respond in warm, clear, patient-friendly Indian English."
    return (
        f"Respond ENTIRELY in {spec.english_name} ({spec.native_name}) using {spec.script_name} "
        "script. Keep medicine brand names and canonical generic salt names clear and "
        "recognizable without adding parenthetical English blocks in spoken text. "
        "Do NOT switch back to English paragraphs."
    )


_SPEECH_UUID_RE = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b",
    re.IGNORECASE,
)
_URL_RE = re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)
_MD_LINK_RE = re.compile(r"\[([^\]]+)\]\((?:https?://|www\.)[^)]+\)", re.IGNORECASE)
_CITATION_BRACKET_RE = re.compile(r"\[[^\]]*\]")
_PAREN_GROUP_RE = re.compile(r"\(\s*([^()]*)\s*\)")
_LEADING_BULLET_RE = re.compile(r"(?:^|\n)\s*(?:[-•▪◦*]+|\d+[.)])\s+")
_MARKDOWN_SYMBOLS_RE = re.compile(r"[*_`#>~|\\]+")
_RATIO_RE = re.compile(r"\b(\d+(?:\.\d+)?)\s*:\s*(\d+(?:\.\d+)?)\b")
_UNIT_SLASH_UNIT_RE = re.compile(
    r"\b(\d+(?:\.\d+)?)\s*(mcg|µg|μg|mg|ml|gm|g)\s*/\s*"
    r"(?:(\d+(?:\.\d+)?)\s*)?(mcg|µg|μg|mg|ml|gm|g)\b",
    re.IGNORECASE,
)
_UNIT_SLASH_WORD_RE = re.compile(
    r"\b(\d+(?:\.\d+)?)\s*(mcg|µg|μg|mg|ml|gm|g)\s*/\s*(day|hr|hour|kg|dose|tablet|cap|capsule)\b",
    re.IGNORECASE,
)
_NUM_SLASH_NUM_RE = re.compile(r"\b(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\b")
_AND_OR_RE = re.compile(r"\band\s*/\s*or\b", re.IGNORECASE)
_FDA_DAILYMED_RE = re.compile(r"\bFDA\s*/\s*DailyMed\b", re.IGNORECASE)
_WORD_SLASH_WORD_RE = re.compile(r"\b([A-Za-z]{2,})\s*/\s*([A-Za-z]{2,})\b")
_NUM_UNIT_RE = re.compile(r"\b(\d+(?:\.\d+)?)\s*(mcg|µg|μg|mg|ml|gm|g)\b", re.IGNORECASE)
_BARE_UNIT_RE = re.compile(r"\b(mcg|µg|μg|mg|mL)\b")
_BID_RE = re.compile(r"(?:\b(?:BID|BD)\b|\bb\.i\.d\.)", re.ASCII)
_TID_RE = re.compile(r"(?:\b(?:TID|TDS)\b|\bt\.i\.d\.)", re.ASCII)
_QID_RE = re.compile(r"(?:\bQID\b|\bq\.i\.d\.)", re.ASCII)
_OD_RE = re.compile(r"(?:\b(?:OD|QD)\b|\bo\.d\.|\bq\.d\.)", re.ASCII)
_HS_RE = re.compile(r"(?:\bHS\b|\bh\.s\.)", re.ASCII)
_PRN_RE = re.compile(r"(?:\bPRN\b|\bp\.r\.n\.)", re.ASCII)
_PLUS_BETWEEN_RE = re.compile(r"\s*\+\s*")
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?।॥])\s+|\n+")
_CLAUSE_SPLIT_RE = re.compile(
    r"(?<=[;:])\s+|,\s+(?=(?:and|or|but|so|because|before|after|while|unless|however|"
    r"और|या|लेकिन|क्योंकि|এবং|কিন্তু|మరియు|आणि|மற்றும்|અને|ಮತ್ತು|കൂടാതെ|ਅਤੇ)\b)",
    re.IGNORECASE,
)
_DEDUP_KEY_RE = re.compile(r"[^\w\u0900-\u0D7F]+", re.UNICODE)
_MULTI_SPACE_RE = re.compile(r"\s+")
_MULTI_COMMA_RE = re.compile(r"(?:,\s*){2,}")
_SPACE_BEFORE_PUNCT_RE = re.compile(r"\s+([,.;:!?।॥])")


def _expand_unit_token(unit_raw: str, lexicon: SpokenLexicon) -> str:
    lowered = unit_raw.lower()
    if lowered in {"mcg", "µg", "μg"}:
        return lexicon.mcg
    if lowered == "mg":
        return lexicon.mg
    if lowered == "ml":
        return lexicon.ml
    return lexicon.gram


def strip_non_speech_artifacts(text: str) -> str:
    cleaned = _MD_LINK_RE.sub(r"\1", text)
    cleaned = _URL_RE.sub(" ", cleaned)
    cleaned = _SPEECH_UUID_RE.sub("", cleaned)
    cleaned = _CITATION_BRACKET_RE.sub(" ", cleaned)
    cleaned = _LEADING_BULLET_RE.sub(". ", cleaned)
    for _ in range(3):
        updated = _PAREN_GROUP_RE.sub(
            lambda m: f", {m.group(1).strip()}, " if m.group(1).strip() else " ",
            cleaned,
        )
        if updated == cleaned:
            break
        cleaned = updated
    cleaned = cleaned.replace("(", " ").replace(")", " ")
    cleaned = cleaned.replace("[", " ").replace("]", " ")
    cleaned = _MARKDOWN_SYMBOLS_RE.sub(" ", cleaned)
    cleaned = cleaned.replace("\u2022", ". ").replace("\u2014", ", ").replace("\u2013", ", ")
    cleaned = _MULTI_COMMA_RE.sub(", ", cleaned)
    cleaned = _SPACE_BEFORE_PUNCT_RE.sub(r"\1", cleaned)
    return _MULTI_SPACE_RE.sub(" ", cleaned).strip()


def expand_spoken_medical_terms(text: str, language: str = "en") -> str:
    lex = get_language_spec(language).spoken_lexicon
    out = _RATIO_RE.sub(rf"\1 {lex.ratio_to} \2", text)
    out = _UNIT_SLASH_UNIT_RE.sub(
        lambda m: (
            f"{m.group(1)} {_expand_unit_token(m.group(2), lex)} {lex.per} "
            f"{f'{m.group(3)} ' if m.group(3) else ''}{_expand_unit_token(m.group(4), lex)}"
        ),
        out,
    )
    out = _UNIT_SLASH_WORD_RE.sub(
        lambda m: f"{m.group(1)} {_expand_unit_token(m.group(2), lex)} {lex.per} {m.group(3)}",
        out,
    )
    out = _NUM_SLASH_NUM_RE.sub(rf"\1 {lex.and_word} \2", out)
    out = _FDA_DAILYMED_RE.sub(f"FDA {lex.and_word} DailyMed", out)
    out = _AND_OR_RE.sub(lex.or_word, out)
    out = _WORD_SLASH_WORD_RE.sub(rf"\1 {lex.or_word} \2", out)
    out = _NUM_UNIT_RE.sub(
        lambda m: f"{m.group(1)} {_expand_unit_token(m.group(2), lex)}",
        out,
    )
    out = _BARE_UNIT_RE.sub(lambda m: _expand_unit_token(m.group(1), lex), out)
    out = _BID_RE.sub(lex.bid, out)
    out = _TID_RE.sub(lex.tid, out)
    out = _QID_RE.sub(lex.qid, out)
    out = _OD_RE.sub(lex.od, out)
    out = _HS_RE.sub(lex.hs, out)
    out = _PRN_RE.sub(lex.prn, out)
    out = _PLUS_BETWEEN_RE.sub(f" {lex.and_word} ", out)
    out = out.replace("/", f" {lex.or_word} ")
    out = _SPACE_BEFORE_PUNCT_RE.sub(r"\1", out)
    return _MULTI_SPACE_RE.sub(" ", out).strip()


def _ensure_terminal_punctuation(sentence: str, sentence_end: str) -> str:
    trimmed = sentence.strip().rstrip(",;:")
    if not trimmed:
        return ""
    if trimmed[-1] in ".!?।॥":
        return trimmed
    return f"{trimmed}{sentence_end}"


def _split_long_spoken_sentence(
    sentence: str,
    *,
    max_words: int,
    sentence_end: str,
) -> list[str]:
    words = sentence.split()
    if len(words) <= max_words:
        closed = _ensure_terminal_punctuation(sentence, sentence_end)
        return [closed] if closed else []

    clauses = [part.strip() for part in _CLAUSE_SPLIT_RE.split(sentence) if part.strip()]
    if len(clauses) > 1:
        merged: list[str] = []
        for clause in clauses:
            if merged and len(merged[-1].split()) + len(clause.split()) <= max_words:
                merged[-1] = f"{merged[-1].rstrip(',.;:!?।॥')}, {clause}"
            else:
                merged.append(clause)
        result: list[str] = []
        for item in merged:
            if len(item.split()) <= max_words:
                closed = _ensure_terminal_punctuation(item, sentence_end)
                if closed:
                    result.append(closed)
            else:
                result.extend(
                    _split_by_word_budget(item, max_words=max_words, sentence_end=sentence_end)
                )
        return result

    return _split_by_word_budget(sentence, max_words=max_words, sentence_end=sentence_end)


def _split_by_word_budget(sentence: str, *, max_words: int, sentence_end: str) -> list[str]:
    words = sentence.split()
    chunks: list[str] = []
    idx = 0
    while idx < len(words):
        end = min(idx + max_words, len(words))
        if end < len(words):
            comma_cut = -1
            for probe in range(end - 1, idx + max(4, max_words // 2) - 1, -1):
                if words[probe].endswith((",", ";", ":")):
                    comma_cut = probe + 1
                    break
            if comma_cut > idx:
                end = comma_cut
        piece = " ".join(words[idx:end]).strip()
        closed = _ensure_terminal_punctuation(piece, sentence_end)
        if closed:
            chunks.append(closed)
        idx = end
    return chunks


def deduplicate_sentences(sentences: list[str]) -> list[str]:
    seen: set[str] = set()
    unique: list[str] = []
    for sentence in sentences:
        key = _DEDUP_KEY_RE.sub("", sentence.lower())
        if not key or key in seen:
            continue
        seen.add(key)
        unique.append(sentence)
    return unique


def prepare_text_for_speech(
    text: str,
    language: str = "en",
    *,
    is_major_alert: bool = False,
    max_words_per_sentence: int = 17,
) -> str:
    spec = get_language_spec(language)
    stripped = strip_non_speech_artifacts(text)
    if not stripped:
        return spec.verbal_major_warning_prefix if is_major_alert else ""

    expanded = expand_spoken_medical_terms(stripped, spec.code)
    if is_major_alert:
        prefix_clean = spec.safety_override_prefix.rstrip(":").strip()
        if prefix_clean and expanded.lower().startswith(prefix_clean.lower()):
            expanded = expanded[len(prefix_clean) :].lstrip(" :.,-—")

    raw_sentences = [
        segment.strip() for segment in _SENTENCE_SPLIT_RE.split(expanded) if segment.strip()
    ]
    short_sentences: list[str] = []
    for raw_sentence in raw_sentences:
        short_sentences.extend(
            _split_long_spoken_sentence(
                raw_sentence,
                max_words=max_words_per_sentence,
                sentence_end=spec.spoken_lexicon.sentence_end,
            )
        )

    if is_major_alert:
        short_sentences.insert(0, spec.verbal_major_warning_prefix)

    deduped = deduplicate_sentences(short_sentences)
    return " ".join(deduped).strip()

