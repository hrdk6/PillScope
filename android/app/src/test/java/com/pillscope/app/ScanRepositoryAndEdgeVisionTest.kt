package com.pillscope.app

import com.pillscope.app.data.local.dao.ScanReportDao
import com.pillscope.app.data.local.entity.CachedScanReportEntity
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.remote.sse.OkHttpScanSseClient
import com.pillscope.app.data.remote.sse.ScanSseParser
import com.pillscope.app.data.repository.AnalysisStage
import com.pillscope.app.data.repository.RiskLevel
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.ml.LiteRtLabelPreprocessor
import com.pillscope.app.ml.OnDeviceSaltChipExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class InMemoryScanReportDao : ScanReportDao {
    private val store = MutableStateFlow<Map<String, CachedScanReportEntity>>(emptyMap())

    override fun observeRecentReports(limit: Int): Flow<List<CachedScanReportEntity>> =
        store.map { map ->
            map.values.sortedByDescending { it.createdAtEpochMs }.take(limit)
        }

    override suspend fun getByScanId(scanId: String): CachedScanReportEntity? = store.value[scanId]

    override suspend fun upsert(entity: CachedScanReportEntity) {
        store.value = store.value + (entity.scanId to entity)
    }
}

class ScanRepositoryAndEdgeVisionTest {
    private fun createApi(baseUrl: String): PillScopeApi {
        val json = ScanSseParser.defaultSseJson
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PillScopeApi::class.java)
    }

    @Test
    fun analyzeMedicineStreamCachesCompletedReportInRoomDao() = runTest {
        val server = MockWebServer()
        val sseBody = """
            id: 1
            event: deterministic_safety
            data: {"scan_id":"scan-cache-99","submitted_query":"Ecosprin 75","resolved_salts":["aspirin"],"overall_risk":"MAJOR","safety_banner_locked":true,"duplicate_salts":[],"deterministic_alerts":[{"rule_id":"ddi:1","salt_a":"aspirin","salt_b":"warfarin","severity":"MAJOR","mechanism":"Additive bleeding","management":"Avoid concurrent therapy","sources":["ddinter_2"]}]}

            id: 2
            event: complete
            data: {"scan_id":"scan-cache-99","submitted_query":"Ecosprin 75","resolved_salts":["aspirin"],"overall_risk":"MAJOR","safety_banner_locked":true,"deterministic_alerts":[{"rule_id":"ddi:1","salt_a":"aspirin","salt_b":"warfarin","severity":"MAJOR","mechanism":"Additive bleeding","management":"Avoid concurrent therapy","sources":["ddinter_2"]}],"duplicate_salts":[],"interactions":[],"clinical_summary":{"headline":"MAJOR bleeding risk","patient_explanation":"Avoid combining aspirin and warfarin.","actionable_next_steps":["Call clinician."],"cited_chunk_ids":[],"abstained":false},"citations":[],"citation_verified":true,"blocked_citation_ids":[],"latency_ms":88}

        """.trimIndent() + "\n\n"

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )
        server.start()
        try {
            val reportDao = InMemoryScanReportDao()
            val client = OkHttpClient()
            val repo = ScanRepository(
                api = createApi(server.url("/").toString()),
                sseClient = OkHttpScanSseClient(client, server.url("/").toString()),
                scanReportDao = reportDao,
                json = ScanSseParser.defaultSseJson,
            )

            val states = repo.analyzeMedicineStream(
                ScanAnalyzeRequestDto(
                    inputMode = "CAMERA_OCR",
                    submittedQuery = "Ecosprin 75",
                    confirmedSalts = listOf("aspirin"),
                ),
            ).toList()

            assertEquals(AnalysisStage.CONNECTING, states[0].stage)
            assertEquals(AnalysisStage.DETERMINISTIC_READY, states[1].stage)
            assertTrue(states[1].safetyBannerLocked)
            assertEquals(AnalysisStage.COMPLETE, states.last().stage)

            val cached = repo.loadCachedReport("scan-cache-99")
            assertNotNull(cached)
            assertTrue(cached!!.fromOfflineCache)
            assertTrue(cached.safetyBannerLocked)
            assertEquals(RiskLevel.MAJOR, cached.overallRisk)
            assertEquals(88, cached.latencyMs)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun offlineResolveFallsBackToOnDeviceSaltChipExtractor() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        try {
            val repo = ScanRepository(
                api = createApi(server.url("/").toString()),
                sseClient = OkHttpScanSseClient(OkHttpClient(), server.url("/").toString()),
                scanReportDao = InMemoryScanReportDao(),
                json = ScanSseParser.defaultSseJson,
            )
            val candidates = repo.resolveDrugCandidates(
                query = "Augmentin 625 Duo",
                rawOcrText = "AUGMENTIN 625 DUO\nAmoxycillin 500mg + Clavulanic Acid 125mg",
            )
            assertEquals(1, candidates.size)
            assertEquals("ocr_salt_parse", candidates[0].matchStrategy)
            assertEquals(listOf("amoxicillin", "clavulanic acid"), candidates[0].canonicalSaltKeys)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun onDeviceSaltChipExtractorNormalizesIndianStripOcrAndGenericNonHardcodedSalts() {
        val ocr = "AUGMENTIN 625 DUO TABLET\nEach tablet contains Amoxycillin Trihydrate 500mg and Potassium Clavulanate 125mg"
        val chips = OnDeviceSaltChipExtractor.extractSaltChips(ocr)
        assertEquals(listOf("amoxicillin", "clavulanic acid"), chips.map { it.canonicalSalt })
        assertEquals("500mg", chips[0].strengthText)
        assertEquals("125mg", chips[1].strengthText)
        assertEquals("AUGMENTIN 625 DUO TABLET", OnDeviceSaltChipExtractor.extractPrimaryBrandGuess(ocr))

        val genericOcr = "RABLET-L CAPSULE\nRabeprazole Sodium IP (20mg) + Levosulpiride SR (75mg)"
        val genericChips = OnDeviceSaltChipExtractor.extractSaltChips(genericOcr)
        assertEquals(listOf("rabeprazole", "levosulpiride"), genericChips.map { it.canonicalSalt })
        assertEquals("20mg", genericChips[0].strengthText)
        assertEquals("75mg", genericChips[1].strengthText)
    }

    @Test
    fun glareAndBlurSignalChecksRejectBlurryAndOverexposedFrames() {
        val size = 64
        val flatGray = IntArray(size * size) { 0xFF808080.toInt() }
        val blurry = LiteRtLabelPreprocessor.evaluateSignalQualityAndCrop(flatGray, size, size)
        assertFalse(blurry.isAcceptable)
        assertTrue(blurry.blurScore < 0.1f)

        val blownWhite = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        val glaring = LiteRtLabelPreprocessor.evaluateSignalQualityAndCrop(blownWhite, size, size)
        assertFalse(glaring.isAcceptable)
        assertTrue(glaring.glareRatio > 0.9f)

        val sharpStripes = IntArray(size * size) { idx ->
            val x = idx % size
            val y = idx / size
            val insideBox = x in 10..54 && y in 14..50
            if (insideBox && ((x + y) % 2 == 0)) 0xFF101010.toInt() else 0xFFD8D8D8.toInt()
        }
        val sharp = LiteRtLabelPreprocessor.evaluateSignalQualityAndCrop(sharpStripes, size, size)
        assertTrue(sharp.isAcceptable)
        assertTrue(sharp.blurScore > 0.6f)
        assertTrue(sharp.glareRatio < 0.05f)
    }
}
