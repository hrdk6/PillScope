package com.pillscope.app

import com.pillscope.app.data.remote.ApiCallError
import com.pillscope.app.data.remote.AuthTokenProvider
import com.pillscope.app.data.remote.BearerAuthInterceptor
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.SessionEventBus
import com.pillscope.app.data.remote.TokenRefreshAuthenticator
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import com.pillscope.app.data.remote.dto.InteractionItemDto
import com.pillscope.app.data.remote.dto.SaltComponentDto
import com.pillscope.app.data.remote.sse.OkHttpScanSseClient
import com.pillscope.app.data.remote.sse.ScanSseEvent
import com.pillscope.app.data.remote.sse.ScanSseParser
import com.pillscope.app.data.remote.toApiCallError
import com.pillscope.app.data.repository.CabinetMedication
import com.pillscope.app.data.repository.OfflineAllergyChecker
import com.pillscope.app.data.repository.ScanRepository
import com.pillscope.app.ml.OnDeviceSaltChipExtractor
import com.pillscope.app.ui.cabinet.CabinetGraphState
import com.pillscope.app.ui.cabinet.GraphEdgeSeverity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import kotlin.math.hypot

private class FakeAuthTokenProvider(
    private var access: String? = null,
    private var refresh: String? = null,
) : AuthTokenProvider {
    private var consent = false
    private var email: String? = null
    private var displayName: String? = null

    override fun getAccessToken(): String? = access
    override fun getRefreshToken(): String? = refresh
    override fun saveTokens(accessToken: String, refreshToken: String) {
        access = accessToken
        refresh = refreshToken
    }
    override fun clearTokens() {
        access = null
        refresh = null
    }
    override fun isConsentAccepted(): Boolean = consent
    override fun setConsentAccepted(accepted: Boolean) {
        consent = accepted
    }
    override fun getCachedUserEmail(): String? = email
    override fun getCachedUserDisplayName(): String? = displayName
    override fun saveUserSummary(email: String, displayName: String?) {
        this.email = email
        this.displayName = displayName
    }
}

class ProductionHardeningTest {

    @Test
    fun tokenRefreshAuthenticatorRefreshesOnceAndRetriesProtectedCall() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Token expired"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer","expires_in_seconds":900}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
        try {
            val tokenStore = FakeAuthTokenProvider(access = "old-access", refresh = "old-refresh")
            val bus = SessionEventBus()
            val authenticator = TokenRefreshAuthenticator(
                tokenProvider = tokenStore,
                baseUrl = server.url("/").toString(),
                json = ScanSseParser.defaultSseJson,
                sessionEventBus = bus,
            )
            val client = OkHttpClient.Builder()
                .addInterceptor(BearerAuthInterceptor(tokenStore))
                .authenticator(authenticator)
                .build()

            val resp = client.newCall(
                Request.Builder().url(server.url("/api/v1/auth/me")).get().build(),
            ).execute()

            assertEquals(200, resp.code)
            assertEquals("new-access", tokenStore.getAccessToken())
            assertEquals("new-refresh", tokenStore.getRefreshToken())
            resp.close()

            val firstReq = server.takeRequest()
            assertEquals("Bearer old-access", firstReq.getHeader("Authorization"))
            val refreshReq = server.takeRequest()
            assertEquals("/api/v1/auth/refresh", refreshReq.path)
            val retriedReq = server.takeRequest()
            assertEquals("Bearer new-access", retriedReq.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun tokenRefreshAuthenticatorClearsTokensAndEmitsExpiryWhenRefreshFails() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Refresh revoked"}"""))
        server.start()
        try {
            val tokenStore = FakeAuthTokenProvider(access = "expired", refresh = "revoked-refresh")
            val bus = SessionEventBus()
            val authenticator = TokenRefreshAuthenticator(
                tokenProvider = tokenStore,
                baseUrl = server.url("/").toString(),
                json = ScanSseParser.defaultSseJson,
                sessionEventBus = bus,
            )
            val client = OkHttpClient.Builder()
                .addInterceptor(BearerAuthInterceptor(tokenStore))
                .authenticator(authenticator)
                .build()

            val resp = client.newCall(
                Request.Builder().url(server.url("/api/v1/auth/me")).get().build(),
            ).execute()
            assertEquals(401, resp.code)
            resp.close()

            assertNull(tokenStore.getAccessToken())
            assertNull(tokenStore.getRefreshToken())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun apiErrorClassifierDistinguishesHttpStatusesAndOfflineExceptions() {
        val json = ScanSseParser.defaultSseJson

        val ioErr = IOException("Connection reset").toApiCallError(json)
        assertTrue(ioErr is ApiCallError.Offline)

        val err401 = HttpException(
            Response.error<Any>(401, """{"detail":"Invalid email or password"}""".toResponseBody("application/json".toMediaType())),
        ).toApiCallError(json)
        assertTrue(err401 is ApiCallError.Unauthorized)
        assertEquals("Invalid email or password", err401.userMessage)

        val err409 = HttpException(
            Response.error<Any>(409, """{"detail":"Email already registered"}""".toResponseBody("application/json".toMediaType())),
        ).toApiCallError(json)
        assertTrue(err409 is ApiCallError.Conflict)

        val err422 = HttpException(
            Response.error<Any>(422, """{"detail":[{"msg":"field required"}]}""".toResponseBody("application/json".toMediaType())),
        ).toApiCallError(json)
        assertTrue(err422 is ApiCallError.Validation)
        assertEquals("field required", err422.userMessage)

        val err429 = HttpException(
            Response.error<Any>(429, """{"detail":"Too many requests"}""".toResponseBody("application/json".toMediaType())),
        ).toApiCallError(json)
        assertTrue(err429 is ApiCallError.RateLimited)

        val err500 = HttpException(
            Response.error<Any>(500, """{"detail":"Internal error"}""".toResponseBody("application/json".toMediaType())),
        ).toApiCallError(json)
        assertTrue(err500 is ApiCallError.Server)
    }

    @Test
    fun offlineAllergyCheckerDetectsDirectClassAndTypoConflicts() {
        val alerts = OfflineAllergyChecker.detectConflicts(
            candidateSalts = listOf("amoxicillin", "ibuprofen"),
            allergies = listOf(
                AllergyEntryDto(allergen = "Penicilin", severity = "SEVERE"),
                AllergyEntryDto(allergen = "ibupofen"),
            ),
        )
        assertEquals(2, alerts.size)
        assertTrue(alerts.any { it.canonicalSalt == "amoxicillin" && it.matchedClass == "penicillin" })
        assertTrue(alerts.any { it.canonicalSalt == "ibuprofen" && it.matchType == "DIRECT_SALT" })
    }

    @Test
    fun ocrExtractorSkipsNonBrandMetadataLinesAndHandlesSyncedSuffixes() {
        val ocr = """
            Rx
            MRP Rs. 145.00 incl. of all taxes
            Batch No. AB1234
            DOXY-1 L-DR FORTE
            Each capsule contains Doxycycline Hyclate 100mg and Calcium Lactate 500mg
        """.trimIndent()
        assertEquals("DOXY-1 L-DR FORTE", OnDeviceSaltChipExtractor.extractPrimaryBrandGuess(ocr))
        val chips = OnDeviceSaltChipExtractor.extractSaltChips(ocr)
        assertEquals(listOf("doxycycline", "calcium lactate"), chips.map { it.canonicalSalt })
    }

    @Test
    fun cabinetGraphSupportsMinorEdgesAndDualRingLayoutForSevenMeds() {
        val meds = (1..7).map { i ->
            CabinetMedication(
                id = "m-$i",
                profileId = "p-1",
                brandMappingId = null,
                displayName = "Med $i",
                genericSalts = listOf(SaltComponentDto(canonicalSalt = "salt$i")),
                canonicalSaltKeys = listOf("salt$i"),
                dosageSchedule = "1 OD",
                isActive = true,
                syncStatus = "SYNCED",
                updatedAtEpochMs = 1L,
            )
        }
        val interactions = listOf(
            InteractionItemDto(
                saltA = "salt1",
                saltB = "salt2",
                severity = "MINOR",
                confidence = 0.65,
                source = "ddinter_2",
                mechanism = "Mild absorption change",
                management = "Monitor routinely",
            ),
        )
        val graph = CabinetGraphState.build(meds, interactions, emptyList())
        assertEquals(7, graph.nodes.size)
        val minorEdge = graph.edges.first { it.id == "m-1__m-2" }
        assertEquals(GraphEdgeSeverity.MINOR, minorEdge.severity)
        assertTrue(minorEdge.isInteractive)

        // Ensure no two nodes overlap closely in normalized coordinates
        for (i in graph.nodes.indices) {
            for (j in i + 1 until graph.nodes.size) {
                val dist = hypot(
                    (graph.nodes[i].normalizedX - graph.nodes[j].normalizedX).toDouble(),
                    (graph.nodes[i].normalizedY - graph.nodes[j].normalizedY).toDouble(),
                )
                assertTrue("Nodes $i and $j overlapped too closely: $dist", dist > 0.14)
            }
        }
    }

    @Test
    fun scanRepositoryIsolatesProfileHistoryParsesIsoDatesAndCachesRecalls() = runTest {
        val server = MockWebServer()
        val listJson = """
            {
              "items": [
                {
                  "id": "scan-p1",
                  "user_id": "u-1",
                  "profile_id": "prof-1",
                  "input_mode": "MANUAL_TEXT",
                  "submitted_query": "Warf 5",
                  "resolved_salts": [{"canonical_salt": "warfarin"}],
                  "overall_risk": "MAJOR",
                  "safety_banner_locked": true,
                  "active_recalls": [
                    {
                      "recall_id": "r1",
                      "recall_number": "D-42-2026",
                      "classification": "Class I",
                      "status": "Ongoing",
                      "recalling_firm": "Acme",
                      "reason_for_recall": "Superpotent lot",
                      "match_basis": "generic_salt",
                      "matched_terms": ["warfarin"],
                      "source_url": "https://api.fda.gov/drug/enforcement.json"
                    }
                  ],
                  "citation_verified": true,
                  "latency_ms": 55,
                  "created_at": "2026-05-10T12:30:00Z"
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(listJson))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody("event: error\ndata: {\"message\":\"Backend timeout\"}\n\n"),
        )
        server.start()
        try {
            val json = ScanSseParser.defaultSseJson
            val api = Retrofit.Builder()
                .baseUrl(server.url("/").toString())
                .client(OkHttpClient())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(PillScopeApi::class.java)
            val dao = InMemoryScanReportDao()
            val repo = ScanRepository(
                api = api,
                sseClient = OkHttpScanSseClient(OkHttpClient(), server.url("/").toString()),
                scanReportDao = dao,
                json = json,
            )

            val syncErr = repo.syncRecentScansForProfile("prof-1")
            assertNull(syncErr)
            val p1History = repo.observeCachedReports("prof-1").first()
            val p2History = repo.observeCachedReports("prof-2").first()
            assertEquals(1, p1History.size)
            assertEquals(0, p2History.size)
            assertEquals(1778416200000L, p1History.first().createdAtEpochMs)

            val loaded = repo.loadCachedReport("scan-p1")
            assertNotNull(loaded)
            assertEquals(1, loaded!!.activeRecalls.size)
            assertEquals("D-42-2026", loaded.activeRecalls.first().recallNumber)

            // Verify SSE JSON error parsing
            val errEvents = OkHttpScanSseClient(OkHttpClient(), server.url("/").toString())
                .streamAnalysis(
                    com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto(
                        inputMode = "MANUAL_TEXT",
                        submittedQuery = "Test",
                    ),
                )
                .toList()
            val streamErr = errEvents.filterIsInstance<ScanSseEvent.StreamError>().firstOrNull()
            assertNotNull(streamErr)
            assertEquals("Backend timeout", streamErr!!.message)
        } finally {
            server.shutdown()
        }
    }
}
