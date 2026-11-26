package com.pillscope.app.ui.cabinet

import com.pillscope.app.data.remote.dto.InteractionItemDto
import com.pillscope.app.data.remote.dto.MonographCitationDto
import com.pillscope.app.data.remote.dto.UnresolvableScheduleClashDto
import com.pillscope.app.data.repository.CabinetMedication
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class GraphEdgeSeverity(val rank: Int) {
    UNANALYZED(-1),
    SAFE(0),
    MINOR(1),
    MODERATE(2),
    MAJOR(3),
}

data class GraphNode(
    val medicationId: String,
    val displayName: String,
    val canonicalSaltKeys: List<String>,
    val hasActiveRecall: Boolean,
    val recallCount: Int,
    val normalizedX: Float,
    val normalizedY: Float,
)

data class GraphEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val sourceName: String,
    val targetName: String,
    val saltA: String,
    val saltB: String,
    val severity: GraphEdgeSeverity,
    val mlMajorProbability: Double?,
    val source: String,
    val mechanism: String,
    val management: String,
    val citation: MonographCitationDto?,
) {
    val isInteractive: Boolean
        get() = severity != GraphEdgeSeverity.SAFE && severity != GraphEdgeSeverity.UNANALYZED
}

data class CabinetGraphState(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    val pausedMedicationCount: Int = 0,
    val selectedNodeId: String? = null,
    val selectedEdgeId: String? = null,
) {
    val selectedEdge: GraphEdge?
        get() = selectedEdgeId?.let { id -> edges.firstOrNull { it.id == id } }

    val unanalyzedEdgeCount: Int
        get() = edges.count { it.severity == GraphEdgeSeverity.UNANALYZED }

    fun isEdgeHighlighted(edge: GraphEdge): Boolean {
        val focusNode = selectedNodeId ?: return true
        return edge.sourceId == focusNode || edge.targetId == focusNode
    }

    fun selectNode(nodeId: String?): CabinetGraphState {
        if (nodeId == null) {
            return copy(selectedNodeId = null)
        }
        val next = if (selectedNodeId == nodeId) null else nodeId
        return copy(selectedNodeId = next, selectedEdgeId = null)
    }

    fun selectEdge(edgeId: String?): CabinetGraphState {
        if (edgeId == null) return copy(selectedEdgeId = null)
        val target = edges.firstOrNull { it.id == edgeId } ?: return this
        if (!target.isInteractive) return this
        return copy(selectedEdgeId = target.id)
    }

    fun moveNode(medicationId: String, normalizedX: Float, normalizedY: Float): CabinetGraphState {
        val clampedX = normalizedX.coerceIn(0.14f, 0.86f)
        val clampedY = normalizedY.coerceIn(0.14f, 0.86f)
        return copy(
            nodes = nodes.map { node ->
                if (node.medicationId == medicationId) {
                    node.copy(normalizedX = clampedX, normalizedY = clampedY)
                } else {
                    node
                }
            },
        )
    }

    companion object {
        fun build(
            medications: List<CabinetMedication>,
            interactions: List<InteractionItemDto>,
            citations: List<MonographCitationDto>,
            unresolvableClashes: List<UnresolvableScheduleClashDto> = emptyList(),
            existingPositions: Map<String, Pair<Float, Float>> = emptyMap(),
            analyzedSalts: Set<String> = emptySet(),
        ): CabinetGraphState {
            val activeMeds = medications.filter { it.isActive }
            val pausedCount = medications.count { !it.isActive }
            if (activeMeds.isEmpty()) {
                return CabinetGraphState(pausedMedicationCount = pausedCount)
            }

            val outerCount = if (activeMeds.size >= 6) {
                ceil(activeMeds.size * 0.65).toInt().coerceAtLeast(4)
            } else {
                activeMeds.size
            }
            val innerCount = (activeMeds.size - outerCount).coerceAtLeast(0)

            val nodes = activeMeds.mapIndexed { index, med ->
                val savedPos = existingPositions[med.id]
                val (nx, ny) = when {
                    savedPos != null -> savedPos
                    activeMeds.size == 1 -> 0.5f to 0.5f
                    activeMeds.size < 6 -> {
                        val angle = (-PI / 2.0) + (2.0 * PI * index.toDouble() / activeMeds.size.toDouble())
                        (0.5f + (cos(angle) * 0.34f).toFloat()) to (0.5f + (sin(angle) * 0.33f).toFloat())
                    }
                    index < outerCount -> {
                        val angle = (-PI / 2.0) + (2.0 * PI * index.toDouble() / outerCount.toDouble())
                        (0.5f + (cos(angle) * 0.37f).toFloat()) to (0.5f + (sin(angle) * 0.36f).toFloat())
                    }
                    innerCount == 1 -> 0.5f to 0.5f
                    else -> {
                        val innerIndex = index - outerCount
                        val phaseOffset = PI / innerCount.toDouble()
                        val angle = (-PI / 2.0) + phaseOffset +
                            (2.0 * PI * innerIndex.toDouble() / innerCount.toDouble())
                        (0.5f + (cos(angle) * 0.17f).toFloat()) to (0.5f + (sin(angle) * 0.17f).toFloat())
                    }
                }
                GraphNode(
                    medicationId = med.id,
                    displayName = med.displayName,
                    canonicalSaltKeys = med.canonicalSaltKeys,
                    hasActiveRecall = med.hasActiveRecall,
                    recallCount = med.activeRecalls.size,
                    normalizedX = nx.coerceIn(0.12f, 0.88f),
                    normalizedY = ny.coerceIn(0.12f, 0.88f),
                )
            }

            val interactionByPair = linkedMapOf<Pair<String, String>, InteractionItemDto>()
            for (item in interactions) {
                val key = canonicalSaltPair(item.saltA, item.saltB)
                val existing = interactionByPair[key]
                if (existing == null || severityRank(item.severity) > severityRank(existing.severity)) {
                    interactionByPair[key] = item
                }
            }

            val clashByPair = linkedMapOf<Pair<String, String>, UnresolvableScheduleClashDto>()
            for (clash in unresolvableClashes) {
                val key = canonicalSaltPair(clash.saltA, clash.saltB)
                clashByPair[key] = clash
            }

            val normalizedAnalyzedSalts = analyzedSalts
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()

            val edges = mutableListOf<GraphEdge>()
            for (i in activeMeds.indices) {
                for (j in i + 1 until activeMeds.size) {
                    val medA = activeMeds[i]
                    val medB = activeMeds[j]
                    val edge = buildPairwiseEdge(
                        medA = medA,
                        medB = medB,
                        interactionByPair = interactionByPair,
                        clashByPair = clashByPair,
                        citations = citations,
                        analyzedSalts = normalizedAnalyzedSalts,
                    )
                    edges.add(edge)
                }
            }

            return CabinetGraphState(
                nodes = nodes,
                edges = edges,
                pausedMedicationCount = pausedCount,
            )
        }

        private fun buildPairwiseEdge(
            medA: CabinetMedication,
            medB: CabinetMedication,
            interactionByPair: Map<Pair<String, String>, InteractionItemDto>,
            clashByPair: Map<Pair<String, String>, UnresolvableScheduleClashDto>,
            citations: List<MonographCitationDto>,
            analyzedSalts: Set<String>,
        ): GraphEdge {
            val pairWasAnalyzed = medA.canonicalSaltKeys.any { it.trim().lowercase() in analyzedSalts } ||
                medB.canonicalSaltKeys.any { it.trim().lowercase() in analyzedSalts }

            var bestSeverity = if (pairWasAnalyzed) GraphEdgeSeverity.SAFE else GraphEdgeSeverity.UNANALYZED
            var bestSaltA = medA.canonicalSaltKeys.firstOrNull().orEmpty()
            var bestSaltB = medB.canonicalSaltKeys.firstOrNull().orEmpty()
            var bestProb: Double? = null
            var bestSource = if (pairWasAnalyzed) "safe_check" else "not_analyzed"
            var bestMechanism = if (pairWasAnalyzed) {
                "No known clinically significant pairwise interaction between active ingredients."
            } else {
                "This pair has not been checked by a full safety analysis yet."
            }
            var bestManagement = if (pairWasAnalyzed) {
                "Continue routine adherence monitoring."
            } else {
                "Run a cabinet interaction check to verify safety."
            }

            val sharedSalts = medA.canonicalSaltKeys.intersect(medB.canonicalSaltKeys.toSet()).sorted()
            if (sharedSalts.isNotEmpty()) {
                val dupSalt = sharedSalts.first()
                bestSeverity = GraphEdgeSeverity.MAJOR
                bestSaltA = dupSalt
                bestSaltB = dupSalt
                bestSource = "deterministic_db"
                bestMechanism = "Duplicate active pharmaceutical ingredient ($dupSalt) across two active cabinet entries."
                bestManagement = "Do not combine duplicate active salts; verify regimen with a pharmacist."
            }

            for (sa in medA.canonicalSaltKeys) {
                for (sb in medB.canonicalSaltKeys) {
                    if (sa == sb) continue
                    val key = canonicalSaltPair(sa, sb)
                    val hit = interactionByPair[key]
                    if (hit != null) {
                        val sev = toEdgeSeverity(hit.severity)
                        if (sev.rank > bestSeverity.rank) {
                            bestSeverity = sev
                            bestSaltA = key.first
                            bestSaltB = key.second
                            bestProb = hit.mlMajorProbability
                            bestSource = hit.source
                            bestMechanism = hit.mechanism
                            bestManagement = hit.management
                        }
                    }
                    val clash = clashByPair[key]
                    if (clash != null && GraphEdgeSeverity.MAJOR.rank > bestSeverity.rank) {
                        bestSeverity = GraphEdgeSeverity.MAJOR
                        bestSaltA = key.first
                        bestSaltB = key.second
                        bestSource = "deterministic_db"
                        bestMechanism = clash.mechanism
                        bestManagement = clash.management
                    }
                }
            }

            val matchedCitation = citations.firstOrNull { citation ->
                val cSalt = citation.canonicalSalt.lowercase()
                cSalt == bestSaltA.lowercase() || cSalt == bestSaltB.lowercase()
            }

            return GraphEdge(
                id = "${medA.id}__${medB.id}",
                sourceId = medA.id,
                targetId = medB.id,
                sourceName = medA.displayName,
                targetName = medB.displayName,
                saltA = bestSaltA,
                saltB = bestSaltB,
                severity = bestSeverity,
                mlMajorProbability = bestProb,
                source = bestSource,
                mechanism = bestMechanism,
                management = bestManagement,
                citation = matchedCitation,
            )
        }

        fun controlPoint(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
            curvature: Float = 0.16f,
        ): Pair<Float, Float> {
            val midX = (ax + bx) / 2f
            val midY = (ay + by) / 2f
            val dx = bx - ax
            val dy = by - ay
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
            val perpX = -dy / dist
            val perpY = dx / dist
            val offset = dist * curvature
            return (midX + perpX * offset) to (midY + perpY * offset)
        }

        fun hitTestNode(
            nodes: List<GraphNode>,
            tapX: Float,
            tapY: Float,
            canvasWidth: Float,
            canvasHeight: Float,
            hitRadiusPx: Float,
        ): GraphNode? {
            return nodes.minByOrNull { node ->
                val cx = node.normalizedX * canvasWidth
                val cy = node.normalizedY * canvasHeight
                hypot((tapX - cx).toDouble(), (tapY - cy).toDouble()).toFloat()
            }?.takeIf { node ->
                val cx = node.normalizedX * canvasWidth
                val cy = node.normalizedY * canvasHeight
                hypot((tapX - cx).toDouble(), (tapY - cy).toDouble()).toFloat() <= hitRadiusPx
            }
        }

        fun hitTestInteractiveEdge(
            nodes: List<GraphNode>,
            edges: List<GraphEdge>,
            tapX: Float,
            tapY: Float,
            canvasWidth: Float,
            canvasHeight: Float,
            hitThresholdPx: Float,
        ): GraphEdge? {
            val nodeMap = nodes.associateBy { it.medicationId }
            var bestEdge: GraphEdge? = null
            var bestDist = Float.MAX_VALUE

            for (edge in edges) {
                if (!edge.isInteractive) continue
                val nA = nodeMap[edge.sourceId] ?: continue
                val nB = nodeMap[edge.targetId] ?: continue
                val ax = nA.normalizedX * canvasWidth
                val ay = nA.normalizedY * canvasHeight
                val bx = nB.normalizedX * canvasWidth
                val by = nB.normalizedY * canvasHeight
                val (cx, cy) = controlPoint(ax, ay, bx, by)

                for (step in 2..18) {
                    val t = step / 20f
                    val inv = 1f - t
                    val px = (inv * inv * ax) + (2f * inv * t * cx) + (t * t * bx)
                    val py = (inv * inv * ay) + (2f * inv * t * cy) + (t * t * by)
                    val dist = hypot((tapX - px).toDouble(), (tapY - py).toDouble()).toFloat()
                    if (dist < bestDist) {
                        bestDist = dist
                        bestEdge = edge
                    }
                }
            }

            return if (bestDist <= hitThresholdPx) bestEdge else null
        }

        private fun canonicalSaltPair(a: String, b: String): Pair<String, String> {
            val na = a.trim().lowercase()
            val nb = b.trim().lowercase()
            return if (na <= nb) na to nb else nb to na
        }

        private fun severityRank(raw: String): Int = when (raw.trim().uppercase()) {
            "MAJOR" -> 3
            "MODERATE" -> 2
            "MINOR" -> 1
            else -> 0
        }

        private fun toEdgeSeverity(raw: String): GraphEdgeSeverity = when (raw.trim().uppercase()) {
            "MAJOR" -> GraphEdgeSeverity.MAJOR
            "MODERATE" -> GraphEdgeSeverity.MODERATE
            "MINOR" -> GraphEdgeSeverity.MINOR
            else -> GraphEdgeSeverity.SAFE
        }
    }
}
