package com.pillscope.app

import com.pillscope.app.data.remote.dto.ClinicalSummaryDto
import com.pillscope.app.data.remote.dto.ClinicalSummaryEventDto
import com.pillscope.app.data.remote.dto.DeterministicDdiAlertDto
import com.pillscope.app.data.remote.dto.DeterministicSafetyEventDto
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.InteractionsEventDto
import com.pillscope.app.data.remote.dto.ScanAnalyzeRequestDto
import com.pillscope.app.data.remote.sse.OkHttpScanSseClient
import com.pillscope.app.data.remote.sse.ScanSseEvent
import com.pillscope.app.data.remote.sse.ScanSseParser
import com.pillscope.app.data.repository.AnalysisStage
import com.pillscope.app.data.repository.RiskLevel
import com.pillscope.app.data.repository.ScanAnalysisReducer
import com.pillscope.app.data.repository.ScanAnalysisState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSseParserAndReducerTest {
    private val parser = ScanSseParser()

    private val sampleSseWire = """
        : keep-alive comment
        id: 1
        event: deterministic_safety
        data: {"scan_id":"scan-001","submitted_query":"Ecosprin 75","resolved_salts":["aspirin"],"overall_risk":"MAJOR","safety_banner_locked":true,"duplicate_salts":[],"deterministic_alerts":[{"rule_id":"ddi:1","salt_a":"aspirin","salt_b":"warfarin","severity":"MAJOR","mechanism":"Additive bleeding risk","clinical_effect":"Severe GI bleeding","management":"Avoid concurrent therapy","sources":["ddinter_2"]}]}

        id: 2
        event: interactions
        data: {"scan_id":"scan-001","overall_risk":"MAJOR","safety_banner_locked":true,"ml_scored_pair_count":1,"interactions":[{"salt_a":"aspirin","salt_b":"warfarin","severity":"MAJOR","confidence":1.0,"source":"hybrid","mechanism":"Additive bleeding risk","management":"Avoid concurrent therapy","ml_major_probability":0.89}]}

        id: 3
        event: citations
        data: {"scan_id":"scan-001","citations":[{"chunk_id":"11111111-1111-4111-8111-111111111111","set_id":"spl-warf-1","canonical_salt":"warfarin","section_key":"drug_interactions","section_title":"DRUG INTERACTIONS","loinc_section_code":"34073-7","excerpt":"Concomitant aspirin increases bleeding risk.","source_url":"https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=spl-warf-1","rerank_score":0.95}]}

        id: 4
        event: clinical_summary
        data: {"scan_id":"scan-001","overall_risk":"MAJOR","safety_banner_locked":true,"citation_verified":true,"blocked_citation_ids":[],"clinical_summary":{"headline":"MAJOR bleeding risk","patient_explanation":"Combining aspirin and warfarin raises bleeding risk.","actionable_next_steps":["Contact clinician immediately."],"cited_chunk_ids":["11111111-1111-4111-8111-111111111111"],"abstained":false},"citations":[{"chunk_id":"11111111-1111-4111-8111-111111111111","set_id":"spl-warf-1","canonical_salt":"warfarin","section_key":"drug_interactions","section_title":"DRUG INTERACTIONS","loinc_section_code":"34073-7","excerpt":"Concomitant aspirin increases bleeding risk.","source_url":"https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=spl-warf-1","rerank_score":0.95}]}

        id: 5
        event: complete
        data: {"scan_id":"scan-001","submitted_query":"Ecosprin 75","resolved_salts":["aspirin"],"overall_risk":"MAJOR","safety_banner_locked":true,"deterministic_alerts":[],"duplicate_salts":[],"interactions":[{"salt_a":"aspirin","salt_b":"warfarin","severity":"MAJOR","confidence":1.0,"source":"hybrid","mechanism":"Additive bleeding risk","management":"Avoid concurrent therapy","ml_major_probability":0.89}],"clinical_summary":{"headline":"MAJOR bleeding risk","patient_explanation":"Combining aspirin and warfarin raises bleeding risk.","actionable_next_steps":["Contact clinician immediately."],"cited_chunk_ids":["11111111-1111-4111-8111-111111111111"],"abstained":false},"citations":[{"chunk_id":"11111111-1111-4111-8111-111111111111","set_id":"spl-warf-1","canonical_salt":"warfarin","section_key":"drug_interactions","section_title":"DRUG INTERACTIONS","loinc_section_code":"34073-7","excerpt":"Concomitant aspirin increases bleeding risk.","source_url":"https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=spl-warf-1","rerank_score":0.95}],"citation_verified":true,"blocked_citation_ids":[],"latency_ms":142}

    """.trimIndent() + "\n\n"

    @Test
    fun parsesFullFiveEventSseWireStreamInOrder() {
        val events = parser.parseWireStream(sampleSseWire)
        assertEquals(5, events.size)
        assertTrue(events[0] is ScanSseEvent.DeterministicSafety)
        assertTrue(events[1] is ScanSseEvent.Interactions)
        assertTrue(events[2] is ScanSseEvent.Citations)
        assertTrue(events[3] is ScanSseEvent.ClinicalSummary)
        assertTrue(events[4] is ScanSseEvent.Complete)

        val det = events[0] as ScanSseEvent.DeterministicSafety
        assertEquals("1", det.eventId)
        assertTrue(det.payload.safetyBannerLocked)
        assertEquals("MAJOR", det.payload.overallRisk)

        val done = events[4] as ScanSseEvent.Complete
        assertEquals(142, done.payload.latencyMs)
        assertTrue(done.payload.citationVerified)
    }

    @Test
    fun malformedJsonProducesRecoverableStreamError() {
        val ev = parser.parseEvent("deterministic_safety", "1", "{bad-json")
        assertTrue(ev is ScanSseEvent.StreamError)
        assertTrue((ev as ScanSseEvent.StreamError).recoverable)
    }

    @Test
    fun reducerLocksRedBannerMonotonicallyAndNeverAllowsDowngrade() {
        val initial = ScanAnalysisState(stage = AnalysisStage.CONNECTING)

        val lockedState = ScanAnalysisReducer.reduce(
            initial,
            ScanSseEvent.DeterministicSafety(
                eventId = "1",
                payload = DeterministicSafetyEventDto(
                    scanId = "scan-lock",
                    submittedQuery = "Augmentin 625 Duo",
                    resolvedSalts = listOf("amoxicillin", "clavulanic acid"),
                    overallRisk = "MAJOR",
                    safetyBannerLocked = true,
                    duplicateSalts = listOf(
                        DuplicateSaltAlertDto(
                            canonicalSalt = "amoxicillin",
                            existingMedicationId = "med-1",
                            existingDisplayName = "Mox 500mg Capsule",
                        ),
                    ),
                    deterministicAlerts = listOf(
                        DeterministicDdiAlertDto(
                            ruleId = "ddi:major",
                            saltA = "amoxicillin",
                            saltB = "methotrexate",
                            severity = "MAJOR",
                            mechanism = "Reduced renal clearance",
                            management = "Avoid combination",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(lockedState.safetyBannerLocked)
        assertEquals(RiskLevel.MAJOR, lockedState.overallRisk)
        assertEquals(AnalysisStage.DETERMINISTIC_READY, lockedState.stage)

        val afterDowngradeAttempt = ScanAnalysisReducer.reduce(
            lockedState,
            ScanSseEvent.Interactions(
                eventId = "2",
                payload = InteractionsEventDto(
                    scanId = "scan-lock",
                    overallRisk = "NONE",
                    safetyBannerLocked = false,
                    interactions = emptyList(),
                ),
            ),
        )
        assertTrue(afterDowngradeAttempt.safetyBannerLocked)
        assertEquals(RiskLevel.MAJOR, afterDowngradeAttempt.overallRisk)

        val afterSummaryAttempt = ScanAnalysisReducer.reduce(
            afterDowngradeAttempt,
            ScanSseEvent.ClinicalSummary(
                eventId = "4",
                payload = ClinicalSummaryEventDto(
                    scanId = "scan-lock",
                    overallRisk = "MINOR",
                    safetyBannerLocked = false,
                    citationVerified = true,
                    clinicalSummary = ClinicalSummaryDto(
                        headline = "Summary",
                        patientExplanation = "Explanation",
                    ),
                ),
            ),
        )
        assertTrue(afterSummaryAttempt.safetyBannerLocked)
        assertEquals(RiskLevel.MAJOR, afterSummaryAttempt.overallRisk)

        val afterStreamError = ScanAnalysisReducer.reduce(
            afterSummaryAttempt,
            ScanSseEvent.StreamError("Connection reset", recoverable = false),
        )
        assertTrue(afterStreamError.safetyBannerLocked)
        assertEquals(RiskLevel.MAJOR, afterStreamError.overallRisk)
        assertEquals(AnalysisStage.ERROR, afterStreamError.stage)
        assertFalse(afterStreamError.duplicateSalts.isEmpty())

        val completeWithEmptyAlerts = ScanAnalysisReducer.reduce(
            lockedState,
            ScanSseEvent.Complete(
                eventId = "5",
                payload = com.pillscope.app.data.remote.dto.ScanCompleteEventDto(
                    scanId = "scan-lock",
                    submittedQuery = "Augmentin 625 Duo",
                    resolvedSalts = listOf("amoxicillin", "clavulanic acid"),
                    overallRisk = "NONE",
                    safetyBannerLocked = false,
                    deterministicAlerts = emptyList(),
                    duplicateSalts = emptyList(),
                    interactions = emptyList(),
                    clinicalSummary = ClinicalSummaryDto(
                        headline = "Summary",
                        patientExplanation = "Explanation",
                    ),
                    citations = emptyList(),
                    citationVerified = true,
                    latencyMs = 95,
                ),
            ),
        )
        assertEquals(AnalysisStage.COMPLETE, completeWithEmptyAlerts.stage)
        assertTrue(completeWithEmptyAlerts.safetyBannerLocked)
        assertEquals(RiskLevel.MAJOR, completeWithEmptyAlerts.overallRisk)
        assertEquals(1, completeWithEmptyAlerts.duplicateSalts.size)
        assertEquals(1, completeWithEmptyAlerts.deterministicAlerts.size)

        val afterPostCompleteCancel = ScanAnalysisReducer.reduce(
            completeWithEmptyAlerts,
            ScanSseEvent.StreamError("Canceled", recoverable = false),
        )
        assertEquals(AnalysisStage.COMPLETE, afterPostCompleteCancel.stage)
        assertEquals(null, afterPostCompleteCancel.errorMessage)
    }

    @Test
    fun okHttpSseClientStreamsRealHttpEventStreamFromMockWebServer() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(sampleSseWire),
        )
        server.start()
        try {
            val sseClient = OkHttpScanSseClient(
                okHttpClient = OkHttpClient(),
                baseUrl = server.url("/").toString(),
            )
            val emitted = sseClient.streamAnalysis(
                ScanAnalyzeRequestDto(
                    inputMode = "MANUAL_TEXT",
                    submittedQuery = "Ecosprin 75",
                    confirmedSalts = listOf("aspirin"),
                ),
            ).toList()

            assertEquals(5, emitted.size)
            assertTrue(emitted.first() is ScanSseEvent.DeterministicSafety)
            assertTrue(emitted.last() is ScanSseEvent.Complete)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/v1/scans/analyze", recorded.path)
            assertEquals("text/event-stream", recorded.getHeader("Accept"))
        } finally {
            server.shutdown()
        }
    }
}
