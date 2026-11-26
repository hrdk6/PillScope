package com.pillscope.app

import com.pillscope.app.data.local.entity.SyncStatus
import com.pillscope.app.data.remote.dto.InteractionItemDto
import com.pillscope.app.data.remote.dto.MedicationRecallFlagDto
import com.pillscope.app.data.remote.dto.MonographCitationDto
import com.pillscope.app.data.remote.dto.SaltComponentDto
import com.pillscope.app.data.remote.dto.UnresolvableScheduleClashDto
import com.pillscope.app.data.repository.CabinetMedication
import com.pillscope.app.ui.cabinet.CabinetGraphState
import com.pillscope.app.ui.cabinet.GraphEdgeSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinetGraphStateTest {

    private fun sampleMedication(
        id: String,
        name: String,
        salts: List<String>,
        isActive: Boolean = true,
        hasRecall: Boolean = false,
    ): CabinetMedication = CabinetMedication(
        id = id,
        profileId = "prof-1",
        brandMappingId = null,
        displayName = name,
        genericSalts = salts.map { SaltComponentDto(canonicalSalt = it) },
        canonicalSaltKeys = salts,
        dosageSchedule = "1 tab OD",
        isActive = isActive,
        syncStatus = SyncStatus.SYNCED,
        hasActiveRecall = hasRecall,
        activeRecalls = if (hasRecall) {
            listOf(
                MedicationRecallFlagDto(
                    recallId = "rec-1",
                    recallNumber = "D-001-2026",
                    classification = "Class II",
                    status = "Ongoing",
                    reasonForRecall = "Subpotent lot.",
                    matchBasis = "generic_salt",
                    matchedTerms = salts,
                    sourceUrl = "https://api.fda.gov/drug/enforcement.json",
                ),
            )
        } else {
            emptyList()
        },
        updatedAtEpochMs = 1L,
    )

    @Test
    fun buildsPairwiseSeverityEdgesWithRecallDotsCitationsAndMlScores() {
        val meds = listOf(
            sampleMedication("m-warf", "Warf 5", listOf("warfarin"), hasRecall = true),
            sampleMedication("m-asp", "Ecosprin 75", listOf("aspirin")),
            sampleMedication("m-met", "Glycomet 500", listOf("metformin")),
            sampleMedication("m-inactive", "Old Med", listOf("atorvastatin"), isActive = false),
        )
        val interactions = listOf(
            InteractionItemDto(
                saltA = "aspirin",
                saltB = "warfarin",
                severity = "MAJOR",
                confidence = 0.96,
                source = "hybrid",
                mechanism = "Additive antiplatelet and anticoagulant effect.",
                management = "Avoid concurrent use unless supervised.",
                mlMajorProbability = 0.91,
            ),
            InteractionItemDto(
                saltA = "aspirin",
                saltB = "metformin",
                severity = "MODERATE",
                confidence = 0.72,
                source = "ml_rdkit",
                mechanism = "Salicylates may enhance hypoglycemic effect.",
                management = "Monitor blood glucose closely.",
                mlMajorProbability = 0.44,
            ),
        )
        val citations = listOf(
            MonographCitationDto(
                chunkId = "c-1",
                setId = "spl-1",
                canonicalSalt = "warfarin",
                sectionKey = "drug_interactions",
                sectionTitle = "DRUG INTERACTIONS",
                loincSectionCode = "34073-7",
                excerpt = "Concomitant use of aspirin with warfarin increases risk of bleeding.",
                sourceUrl = "https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=spl-1",
                rerankScore = 0.94,
            ),
        )

        val graph = CabinetGraphState.build(
            medications = meds,
            interactions = interactions,
            citations = citations,
        )

        assertEquals(3, graph.nodes.size)
        assertTrue(graph.nodes.first { it.medicationId == "m-warf" }.hasActiveRecall)
        assertEquals(1, graph.nodes.first { it.medicationId == "m-warf" }.recallCount)
        assertEquals(3, graph.edges.size)

        val majorEdge = graph.edges.first { it.id == "m-warf__m-asp" }
        assertEquals(GraphEdgeSeverity.MAJOR, majorEdge.severity)
        assertEquals(0.91, majorEdge.mlMajorProbability!!, 0.001)
        assertNotNull(majorEdge.citation)
        assertTrue(majorEdge.isInteractive)

        val moderateEdge = graph.edges.first { it.id == "m-asp__m-met" }
        assertEquals(GraphEdgeSeverity.MODERATE, moderateEdge.severity)
        assertTrue(moderateEdge.isInteractive)

        val safeEdge = graph.edges.first { it.id == "m-warf__m-met" }
        assertEquals(GraphEdgeSeverity.SAFE, safeEdge.severity)
        assertFalse(safeEdge.isInteractive)
    }

    @Test
    fun nodeSelectionHighlightsIncidentEdgesAndEdgeSelectionRejectsSafeEdges() {
        val meds = listOf(
            sampleMedication("m-1", "Warf 5", listOf("warfarin")),
            sampleMedication("m-2", "Ecosprin 75", listOf("aspirin")),
            sampleMedication("m-3", "Thyronorm", listOf("levothyroxine")),
        )
        val clashes = listOf(
            UnresolvableScheduleClashDto(
                medicationAId = "m-1",
                medicationAName = "Warf 5",
                saltA = "warfarin",
                medicationBId = "m-2",
                medicationBName = "Ecosprin 75",
                saltB = "aspirin",
                severity = "MAJOR",
                clashType = "MAJOR_DDI",
                mechanism = "Synergistic bleeding risk.",
                management = "Consult physician before co-administering.",
                reason = "Cannot be separated by timing alone.",
            ),
        )

        var graph = CabinetGraphState.build(
            medications = meds,
            interactions = emptyList(),
            citations = emptyList(),
            unresolvableClashes = clashes,
        )

        val majorEdge = graph.edges.first { it.id == "m-1__m-2" }
        val safeEdge = graph.edges.first { it.id == "m-2__m-3" }
        assertEquals(GraphEdgeSeverity.MAJOR, majorEdge.severity)
        assertEquals(GraphEdgeSeverity.SAFE, safeEdge.severity)

        graph = graph.selectEdge(safeEdge.id)
        assertNull(graph.selectedEdge)

        graph = graph.selectEdge(majorEdge.id)
        assertEquals(majorEdge.id, graph.selectedEdge?.id)

        graph = graph.selectNode("m-1")
        assertEquals("m-1", graph.selectedNodeId)
        assertNull(graph.selectedEdgeId)
        assertTrue(graph.isEdgeHighlighted(majorEdge))
        assertFalse(graph.isEdgeHighlighted(safeEdge))

        graph = graph.selectNode("m-1")
        assertNull(graph.selectedNodeId)
        assertTrue(graph.isEdgeHighlighted(safeEdge))
    }

    @Test
    fun moveNodeClampsWithinBoundsAndHitTestingDetectsNodesAndCurvedEdges() {
        val meds = listOf(
            sampleMedication("m-1", "Mox 500", listOf("amoxicillin")),
            sampleMedication("m-2", "Augmentin 625", listOf("amoxicillin", "clavulanic acid")),
        )
        var graph = CabinetGraphState.build(
            medications = meds,
            interactions = emptyList(),
            citations = emptyList(),
            existingPositions = mapOf(
                "m-1" to (0.25f to 0.50f),
                "m-2" to (0.75f to 0.50f),
            ),
        )

        assertEquals(1, graph.edges.size)
        assertEquals(GraphEdgeSeverity.MAJOR, graph.edges.first().severity)

        val width = 1000f
        val height = 1000f
        val hitNode = CabinetGraphState.hitTestNode(
            nodes = graph.nodes,
            tapX = 255f,
            tapY = 498f,
            canvasWidth = width,
            canvasHeight = height,
            hitRadiusPx = 60f,
        )
        assertEquals("m-1", hitNode?.medicationId)

        val (cx, cy) = CabinetGraphState.controlPoint(250f, 500f, 750f, 500f)
        val midCurveX = (0.25f * 250f) + (0.5f * cx) + (0.25f * 750f)
        val midCurveY = (0.25f * 500f) + (0.5f * cy) + (0.25f * 500f)
        val hitEdge = CabinetGraphState.hitTestInteractiveEdge(
            nodes = graph.nodes,
            edges = graph.edges,
            tapX = midCurveX,
            tapY = midCurveY,
            canvasWidth = width,
            canvasHeight = height,
            hitThresholdPx = 36f,
        )
        assertEquals("m-1__m-2", hitEdge?.id)

        graph = graph.moveNode("m-1", normalizedX = 0.01f, normalizedY = 0.99f)
        val moved = graph.nodes.first { it.medicationId == "m-1" }
        assertEquals(0.14f, moved.normalizedX, 0.0001f)
        assertEquals(0.86f, moved.normalizedY, 0.0001f)
    }
}
