package com.pillscope.app.voice

import com.pillscope.app.i18n.AppLanguage

enum class VoiceNavTarget {
    SCAN,
    SAFETY,
    ASK_AI,
    CABINET,
    SCHEDULE,
}

sealed interface VoiceCommand {
    data class Navigate(val target: VoiceNavTarget) : VoiceCommand
    data class SwitchLanguage(val language: AppLanguage) : VoiceCommand
    data object ReadSafetyReport : VoiceCommand
    data object ReadSchedule : VoiceCommand
    data class SearchMedicine(val query: String) : VoiceCommand
    data class AskAiQuestion(val question: String) : VoiceCommand
}

/**
 * Parses spoken utterances across English and 9 Indian languages into hands-free app commands
 * (navigation, language switching, readout triggers, medicine search) or AI assistant questions.
 */
object VoiceCommandParser {
    private val navTriggers = listOf(
        "open ", "go to ", "show ", "switch to ",
        "खोलो", "खोलें", "दिखाओ", "दिखाएं", "जाओ",
        "খুলুন", "দেখান",
        "తెరువు", "చూపించు", "చూపు",
        "उघडा", "दाखवा",
        "திற", "காட்டு",
        "ખોલો", "બતાવો",
        "ತೆರೆಯಿರಿ", "ತೋರಿಸಿ",
        "തുറക്കുക", "കാണിക്കുക",
        "ਖੋਲ੍ਹੋ", "ਦਿਖਾਓ",
    )

    private val cabinetWords = listOf(
        "cabinet", "my medicines", "medicines list", "pill box",
        "कैबिनेट", "मेरी दवा", "कॅबिनेट", "ক্যাবিনেট", "క్యాబినెట్",
        "மருந்துப் பெட்டி", "કેબિનેટ", "ಕ್ಯಾಬಿನೆಟ್", "ക്യാബിനറ്റ്", "ਕੈਬਨਿਟ",
    )

    private val scheduleWords = listOf(
        "schedule", "timetable", "daily doses", "reminders",
        "समय सारणी", "वेळापत्रक", "সময়সূচী", "సమయ పట్టిక",
        "அட்டவணை", "સમયપત્રક", "ವೇಳಾಪಟ್ಟಿ", "സമയക്രമം", "ਸਮਾਂ ਸਾਰਣੀ",
    )

    private val scanWords = listOf(
        "scan", "camera", "scanner",
        "स्कैन", "स्कॅन", "ক্যামেরা", "স্ক্যান", "స్కాన్",
        "ஸ்கேன்", "સ્કેન", "ಸ್ಕ್ಯಾನ್", "സ്കാൻ", "ਸਕੈਨ",
    )

    private val safetyWords = listOf(
        "safety", "report", "alerts", "warnings",
        "सुरक्षा", "रिपोर्ट", "चेतावनी", "নিরাপত্তা", "భద్రత",
        "பாதுகாப்பு", "સુરક્ષા", "ಸುರಕ್ಷತೆ", "സുരക്ഷ", "ਸੁਰੱਖਿਆ",
    )

    private val readTriggers = listOf(
        "read ", "speak ", "listen ", "read aloud",
        "पढ़ो", "पढ़ें", "सुनाओ", "सुनाएं", "बोलकर",
        "পড়ে শোনান", "শোনান", "చదవండి", "వినండి",
        "वाचा", "ऐकवा", "படி", "சொல்லு", "વાંચો", "સંભળાવો",
        "ಓದಿ", "ಹೇಳಿ", "വായിക്കുക", "കേൾപ്പിക്കുക", "ਪੜ੍ਹੋ", "ਸੁਣਾਓ",
    )

    private val languageSwitchTriggers = listOf(
        "language", "speak in", "switch to", "change to", "talk in",
        "भाषा", "में बोलो", "में बात करो", "ভাষা", "భాష", "மொழி", "ભાષા", "ಭಾಷೆ", "ഭാഷ", "ਭਾਸ਼ਾ",
    )

    private val languageKeywords: List<Pair<AppLanguage, List<String>>> = listOf(
        AppLanguage.HINDI to listOf("hindi", "हिन्दी", "हिंदी"),
        AppLanguage.BENGALI to listOf("bengali", "bangla", "বাংলা"),
        AppLanguage.TELUGU to listOf("telugu", "తెలుగు"),
        AppLanguage.MARATHI to listOf("marathi", "मराठी"),
        AppLanguage.TAMIL to listOf("tamil", "தமிழ்"),
        AppLanguage.GUJARATI to listOf("gujarati", "ગુજરાતી"),
        AppLanguage.KANNADA to listOf("kannada", "ಕನ್ನಡ"),
        AppLanguage.MALAYALAM to listOf("malayalam", "മലയാളം"),
        AppLanguage.PUNJABI to listOf("punjabi", "ਪੰਜਾਬੀ"),
        AppLanguage.ENGLISH to listOf("english", "अंग्रेजी", "अंग्रेज़ी", "ইংরেজি", "ఇంగ్లీష్", "ஆங்கிலம்"),
    )

    private val searchPrefixes = listOf(
        "search for ", "search ", "find medicine ", "find ", "look up ",
        "check medicine ", "scan medicine ",
    )

    fun parse(rawTranscript: String): VoiceCommand {
        val cleaned = rawTranscript.trim()
        if (cleaned.isEmpty()) return VoiceCommand.AskAiQuestion("")
        val lower = cleaned.lowercase()

        // 1. Explicit language switch command (short command mentioning language name)
        val hasLangTrigger = languageSwitchTriggers.any { lower.contains(it) || cleaned.contains(it) }
        if (hasLangTrigger && cleaned.length <= 60) {
            for ((lang, keywords) in languageKeywords) {
                if (keywords.any { lower.contains(it) || cleaned.contains(it) }) {
                    return VoiceCommand.SwitchLanguage(lang)
                }
            }
        }

        // 2. Read-aloud commands
        val hasReadTrigger = readTriggers.any { lower.contains(it) || cleaned.contains(it) }
        if (hasReadTrigger && cleaned.length <= 65) {
            if (scheduleWords.any { lower.contains(it) || cleaned.contains(it) }) {
                return VoiceCommand.ReadSchedule
            }
            if (safetyWords.any { lower.contains(it) || cleaned.contains(it) }) {
                return VoiceCommand.ReadSafetyReport
            }
        }

        // 3. Direct tab navigation commands (kept short so full clinical questions still go to AI)
        val hasNavTrigger = navTriggers.any { lower.contains(it) || cleaned.contains(it) }
        if (hasNavTrigger && cleaned.length <= 50) {
            when {
                cabinetWords.any { lower.contains(it) || cleaned.contains(it) } ->
                    return VoiceCommand.Navigate(VoiceNavTarget.CABINET)
                scheduleWords.any { lower.contains(it) || cleaned.contains(it) } ->
                    return VoiceCommand.Navigate(VoiceNavTarget.SCHEDULE)
                scanWords.any { lower.contains(it) || cleaned.contains(it) } ->
                    return VoiceCommand.Navigate(VoiceNavTarget.SCAN)
                safetyWords.any { lower.contains(it) || cleaned.contains(it) } ->
                    return VoiceCommand.Navigate(VoiceNavTarget.SAFETY)
            }
        }

        // 4. Explicit medicine search command
        for (prefix in searchPrefixes) {
            if (lower.startsWith(prefix)) {
                val remainder = cleaned.substring(prefix.length).trim()
                if (remainder.length >= 2) {
                    return VoiceCommand.SearchMedicine(remainder)
                }
            }
        }

        // 5. Default: ask the grounded multilingual AI assistant
        return VoiceCommand.AskAiQuestion(cleaned)
    }
}
