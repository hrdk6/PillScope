package com.pillscope.app

import com.pillscope.app.data.local.entity.MedicationEntity
import com.pillscope.app.data.local.entity.SyncStatus
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.remote.sse.ScanSseParser
import com.pillscope.app.data.repository.AssistantRepository
import com.pillscope.app.data.repository.UserProfile
import com.pillscope.app.i18n.AppLanguage
import com.pillscope.app.i18n.AppStringsCatalog
import com.pillscope.app.voice.VoiceCommand
import com.pillscope.app.voice.VoiceCommandParser
import com.pillscope.app.voice.VoiceNavTarget
import com.pillscope.app.voice.splitTextForSpeech
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class MultilingualVoiceAssistantTest {

    @Test
    fun allTenIndianLanguagesHaveCompleteStringsAndValidLocales() {
        assertEquals(10, AppLanguage.entries.size)
        for (lang in AppLanguage.entries) {
            assertTrue("Missing native name for $lang", lang.nativeName.isNotBlank())
            assertTrue("Invalid BCP-47 tag for $lang", lang.bcp47Tag.endsWith("-IN"))
            assertEquals(lang, AppLanguage.fromCode(lang.code))
            assertEquals(lang, AppLanguage.fromCode(lang.bcp47Tag))

            val strings = AppStringsCatalog.forLanguage(lang)
            assertTrue(strings.navScan.isNotBlank())
            assertTrue(strings.navSafety.isNotBlank())
            assertTrue(strings.navAskAi.isNotBlank())
            assertTrue(strings.navCabinet.isNotBlank())
            assertTrue(strings.navSchedule.isNotBlank())
            assertTrue(strings.aiWelcomeMessage.isNotBlank())
            assertTrue(strings.starterQ1.isNotBlank())
            assertTrue(strings.criticalSafetyAlert.isNotBlank())
            assertTrue(strings.listen.isNotBlank())
            assertTrue(strings.readFullReport.isNotBlank())
        }
    }

    @Test
    fun voiceCommandParserRoutesMultilingualNavigationLanguageSwitchAndQuestions() {
        assertEquals(
            VoiceCommand.Navigate(VoiceNavTarget.CABINET),
            VoiceCommandParser.parse("Open cabinet"),
        )
        assertEquals(
            VoiceCommand.Navigate(VoiceNavTarget.CABINET),
            VoiceCommandParser.parse("कैबिनेट खोलो"),
        )
        assertEquals(
            VoiceCommand.Navigate(VoiceNavTarget.SCHEDULE),
            VoiceCommandParser.parse("समय सारणी दिखाओ"),
        )
        assertEquals(
            VoiceCommand.SwitchLanguage(AppLanguage.HINDI),
            VoiceCommandParser.parse("Switch to Hindi language"),
        )
        assertEquals(
            VoiceCommand.SwitchLanguage(AppLanguage.TAMIL),
            VoiceCommandParser.parse("தமிழ் மொழி"),
        )
        assertEquals(
            VoiceCommand.ReadSafetyReport,
            VoiceCommandParser.parse("Read safety report"),
        )
        assertEquals(
            VoiceCommand.ReadSchedule,
            VoiceCommandParser.parse("समय सारणी सुनाओ"),
        )
        assertEquals(
            VoiceCommand.SearchMedicine("Dolo 650"),
            VoiceCommandParser.parse("search Dolo 650"),
        )

        val questionCmd = VoiceCommandParser.parse("क्या मैं डोलो 650 और वॉर्फ 5 एक साथ ले सकता हूँ?")
        assertTrue(questionCmd is VoiceCommand.AskAiQuestion)
        assertEquals(
            "क्या मैं डोलो 650 और वॉर्फ 5 एक साथ ले सकता हूँ?",
            (questionCmd as VoiceCommand.AskAiQuestion).question,
        )
    }

    @Test
    fun speechSplitterRespectsDevanagariDandaAndLengthLimits() {
        val hindiParagraph =
            "यह एक गंभीर सुरक्षा चेतावनी है। एस्प्रिन और वॉरफेरिन को एक साथ लेने से रक्तस्राव का खतरा बढ़ जाता है। " +
                "कृपया इस दवा को लेने से पहले अपने डॉक्टर या फार्मासिस्ट से तुरंत संपर्क करें। " +
                "अपनी सभी सक्रिय दवाओं की सूची डॉक्टर को दिखाएं।"
        val chunks = splitTextForSpeech(hindiParagraph, maxChunkChars = 90)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 95 })
    }

    @Test
    fun scanAnalyzeRequestSerializesSelectedIndianLanguage() {
        val json = ScanSseParser.defaultSseJson
        val encoded = json.encodeToString(
            ScanAnalyzeRequestDto(
                inputMode = "MANUAL_TEXT",
                submittedQuery = "Ecosprin 75",
                language = AppLanguage.HINDI.code,
            ),
        )
        assertTrue(encoded.contains("\"language\":\"hi\""))
    }

    @Test
    fun assistantRepositoryCallsBackendAndFallsBackOfflineInChosenLanguage() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "answer_text": "गंभीर सुरक्षा चेतावनी: एस्प्रिन और वॉरफेरिन साथ न लें।",
                      "spoken_text": "गंभीर सुरक्षा चेतावनी। एस्प्रिन और वॉरफेरिन एक साथ न लें।",
                      "language": "hi",
                      "bcp47_locale": "hi-IN",
                      "overall_risk": "MAJOR",
                      "safety_banner_locked": true,
                      "referenced_salts": ["aspirin", "warfarin"],
                      "suggested_followups": ["कौन सा विकल्प सुरक्षित है?"],
                      "citations": [],
                      "disclaimer": "केवल जानकारी के लिए।",
                      "provider": "gemini"
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        try {
            val json = ScanSseParser.defaultSseJson
            val api = Retrofit.Builder()
                .baseUrl(server.url("/").toString())
                .client(OkHttpClient())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(PillScopeApi::class.java)

            val medDao = InMemoryMedicationDao()
            medDao.upsert(
                MedicationEntity(
                    id = "med-1",
                    profileId = "prof-1",
                    brandMappingId = null,
                    displayName = "Mox 500",
                    genericSaltsJson = """[{"canonical_salt":"amoxicillin"}]""",
                    canonicalSaltKeysCsv = "amoxicillin",
                    dosageSchedule = "Twice daily",
                    isActive = true,
                    syncStatus = SyncStatus.SYNCED,
                    updatedAtEpochMs = 1L,
                ),
            )
            val repo = AssistantRepository(api = api, medicationDao = medDao)
            val profile = UserProfile(
                id = "prof-1",
                userId = "user-1",
                name = "Mom",
                relationship = "parent",
                allergies = listOf("Penicillin"),
                allergyEntries = listOf(AllergyEntryDto(allergen = "Penicillin")),
                notes = null,
                isDefault = true,
                updatedAtEpochMs = 1L,
            )

            // 1. Online response in Hindi
            val online = repo.askQuestion(
                question = "क्या मैं एस्प्रिन और वॉरफेरिन साथ ले सकता हूँ?",
                language = AppLanguage.HINDI,
                profile = profile,
            )
            assertFalse(online.usedOfflineFallback)
            assertEquals("hi", online.response.language)
            assertTrue(online.response.safetyBannerLocked)
            assertEquals("MAJOR", online.response.overallRisk)
            assertTrue(online.response.spokenText.contains("गंभीर"))

            val reqBody = server.takeRequest().body.readUtf8()
            assertTrue(reqBody.contains("\"language\":\"hi\""))

            // 2. Offline fallback still catches Penicillin allergy conflict & responds in Tamil
            val offline = repo.askQuestion(
                question = "Can I take amoxicillin?",
                language = AppLanguage.TAMIL,
                profile = profile,
                contextSalts = listOf("amoxicillin"),
            )
            assertTrue(offline.usedOfflineFallback)
            assertEquals("ta", offline.response.language)
            assertEquals("ta-IN", offline.response.bcp47Locale)
            assertTrue(offline.response.safetyBannerLocked)
            assertEquals("MAJOR", offline.response.overallRisk)
            assertTrue(offline.response.answerText.contains(AppStringsCatalog.forLanguage(AppLanguage.TAMIL).criticalSafetyAlert))
        } finally {
            server.shutdown()
        }
    }
}
