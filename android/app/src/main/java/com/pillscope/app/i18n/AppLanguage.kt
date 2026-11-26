package com.pillscope.app.i18n

import java.util.Locale

/**
 * Supported languages in PillScope (English + 9 major Indian languages).
 * Each entry carries its ISO-639-1 code (matching backend assistant and scan endpoints),
 * BCP-47 Indian locale tag for Android SpeechRecognizer & TextToSpeech, native display name,
 * and English display name.
 */
enum class AppLanguage(
    val code: String,
    val bcp47Tag: String,
    val nativeName: String,
    val englishName: String,
    val shortBadge: String,
) {
    ENGLISH("en", "en-IN", "English", "English (India)", "EN"),
    HINDI("hi", "hi-IN", "हिन्दी", "Hindi", "हिं"),
    BENGALI("bn", "bn-IN", "বাংলা", "Bengali", "বাং"),
    TELUGU("te", "te-IN", "తెలుగు", "Telugu", "తె"),
    MARATHI("mr", "mr-IN", "मराठी", "Marathi", "मरा"),
    TAMIL("ta", "ta-IN", "தமிழ்", "Tamil", "தமி"),
    GUJARATI("gu", "gu-IN", "ગુજરાતી", "Gujarati", "ગુજ"),
    KANNADA("kn", "kn-IN", "ಕನ್ನಡ", "Kannada", "ಕನ್ನ"),
    MALAYALAM("ml", "ml-IN", "മലയാളം", "Malayalam", "മല"),
    PUNJABI("pa", "pa-IN", "ਪੰਜਾਬੀ", "Punjabi", "ਪੰ"),
    ;

    val locale: Locale
        get() = Locale.forLanguageTag(bcp47Tag)

    companion object {
        fun fromCode(raw: String?): AppLanguage {
            if (raw.isNullOrBlank()) return ENGLISH
            val normalized = raw.trim().lowercase().replace("_", "-")
            val primary = normalized.substringBefore("-")
            return entries.firstOrNull {
                it.code == primary || it.bcp47Tag.lowercase() == normalized
            } ?: ENGLISH
        }

        fun fromSystemLocale(locale: Locale = Locale.getDefault()): AppLanguage =
            fromCode(locale.language)
    }
}
