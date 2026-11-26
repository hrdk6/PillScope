package com.pillscope.app.ui.cabinet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pillscope.app.ui.common.formatInteractionSourceLabel
import com.pillscope.app.ui.common.formatMonographLinkLabel
import com.pillscope.app.ui.common.rememberSafeUriOpener
import com.pillscope.app.ui.theme.LocalPillScopePalette
import com.pillscope.app.ui.theme.PillScopeColors
import com.pillscope.app.ui.theme.PillScopeMotion
import com.pillscope.app.ui.theme.rememberReducedMotionEnabled
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CabinetNetworkGraph(
    graphState: CabinetGraphState,
    onSelectNode: (String?) -> Unit,
    onSelectEdge: (String?) -> Unit,
    onMoveNode: (medicationId: String, normX: Float, normY: Float) -> Unit,
    onResetLayout: (() -> Unit)? = null,
    onInspectMedication: ((medicationId: String) -> Unit)? = null,
    onCheckCabinetInteractions: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPillScopePalette.current
    val reducedMotion = rememberReducedMotionEnabled()
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingNodeId by remember { mutableStateOf<String?>(null) }
    var targetZoomScale by remember { mutableFloatStateOf(1f) }
    val zoomScale by animateFloatAsState(
        targetValue = targetZoomScale,
        animationSpec = if (reducedMotion) tween(0) else PillScopeMotion.settleSpring(),
        label = "graphZoom",
    )

    val hasMajorOrRecall = remember(graphState.edges, graphState.nodes) {
        graphState.edges.any { it.severity == GraphEdgeSeverity.MAJOR } ||
            graphState.nodes.any { it.hasActiveRecall }
    }
    val pulseFraction = if (hasMajorOrRecall && !reducedMotion) {
        val pulseTransition = rememberInfiniteTransition(label = "majorEdgePulse")
        pulseTransition.animateFloat(
            initialValue = 0.62f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        ).value
    } else {
        1f
    }

    val interactiveEdges = remember(graphState.edges) {
        graphState.edges.filter { it.isInteractive }
            .sortedByDescending { it.severity.rank }
    }

    val graphSemanticSummary = remember(graphState.nodes, graphState.edges, graphState.pausedMedicationCount) {
        val majorCount = graphState.edges.count { it.severity == GraphEdgeSeverity.MAJOR }
        val moderateCount = graphState.edges.count { it.severity == GraphEdgeSeverity.MODERATE }
        val minorCount = graphState.edges.count { it.severity == GraphEdgeSeverity.MINOR }
        val unanalyzedCount = graphState.unanalyzedEdgeCount
        buildString {
            append("Medication network with ${graphState.nodes.size} active medications. ")
            if (majorCount > 0) append("$majorCount major interactions. ")
            if (moderateCount > 0) append("$moderateCount moderate interactions. ")
            if (minorCount > 0) append("$minorCount minor interactions. ")
            if (unanalyzedCount > 0) append("$unanalyzedCount pairs not analyzed yet. ")
            if (graphState.pausedMedicationCount > 0) {
                append("${graphState.pausedMedicationCount} paused medications hidden.")
            }
        }.trim()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Scientific dot-grid molecular interaction diagram surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp),
            shape = RoundedCornerShape(20.dp),
            color = PillScopeColors.CardSurface,
            border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
        ) {
            if (graphState.nodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (graphState.pausedMedicationCount > 0) {
                            "All ${graphState.pausedMedicationCount} saved medications are paused. Activate medications in List view to inspect pairwise interactions."
                        } else {
                            "Add active medications to visualize pairwise interactions and FDA recalls."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = graphSemanticSummary }
                            .onSizeChanged { canvasSize = it }
                            .pointerInput(graphState.nodes, graphState.edges, canvasSize, zoomScale) {
                                detectTapGestures { offset ->
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                    val unscaledX = ((offset.x - w / 2f) / zoomScale) + w / 2f
                                    val unscaledY = ((offset.y - h / 2f) / zoomScale) + h / 2f
                                    val nodeHit = CabinetGraphState.hitTestNode(
                                        nodes = graphState.nodes,
                                        tapX = unscaledX,
                                        tapY = unscaledY,
                                        canvasWidth = w,
                                        canvasHeight = h,
                                        hitRadiusPx = 52.dp.toPx() / zoomScale,
                                    )
                                    if (nodeHit != null) {
                                        onSelectNode(nodeHit.medicationId)
                                    } else {
                                        val edgeHit = CabinetGraphState.hitTestInteractiveEdge(
                                            nodes = graphState.nodes,
                                            edges = graphState.edges,
                                            tapX = unscaledX,
                                            tapY = unscaledY,
                                            canvasWidth = w,
                                            canvasHeight = h,
                                            hitThresholdPx = 28.dp.toPx() / zoomScale,
                                        )
                                        if (edgeHit != null) {
                                            onSelectEdge(edgeHit.id)
                                        } else {
                                            onSelectNode(null)
                                        }
                                    }
                                }
                            }
                            .pointerInput(graphState.nodes, canvasSize, zoomScale) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                    val unscaledX = ((down.position.x - w / 2f) / zoomScale) + w / 2f
                                    val unscaledY = ((down.position.y - h / 2f) / zoomScale) + h / 2f
                                    val activeId = CabinetGraphState.hitTestNode(
                                        nodes = graphState.nodes,
                                        tapX = unscaledX,
                                        tapY = unscaledY,
                                        canvasWidth = w,
                                        canvasHeight = h,
                                        hitRadiusPx = 54.dp.toPx() / zoomScale,
                                    )?.medicationId ?: return@awaitEachGesture

                                    draggingNodeId = activeId
                                    try {
                                        var currentX = graphState.nodes
                                            .firstOrNull { it.medicationId == activeId }?.normalizedX ?: return@awaitEachGesture
                                        var currentY = graphState.nodes
                                            .firstOrNull { it.medicationId == activeId }?.normalizedY ?: return@awaitEachGesture
                                        drag(down.id) { change ->
                                            val delta = change.positionChange()
                                            if (delta != Offset.Zero) {
                                                change.consume()
                                                currentX = (currentX + (delta.x / (w * zoomScale))).coerceIn(0.12f, 0.88f)
                                                currentY = (currentY + (delta.y / (h * zoomScale))).coerceIn(0.12f, 0.88f)
                                                onMoveNode(activeId, currentX, currentY)
                                            }
                                        }
                                    } finally {
                                        draggingNodeId = null
                                    }
                                }
                            },
                    ) {
                        // Subtle archival dot-grid background
                        val gridStep = 22.dp.toPx()
                        val dotRadius = 1.dp.toPx()
                        val dotColor = palette.slateBorder.copy(alpha = 0.45f)
                        var gx = gridStep / 2f
                        while (gx < size.width) {
                            var gy = gridStep / 2f
                            while (gy < size.height) {
                                drawCircle(color = dotColor, radius = dotRadius, center = Offset(gx, gy))
                                gy += gridStep
                            }
                            gx += gridStep
                        }

                        val nodeMap = graphState.nodes.associateBy { it.medicationId }
                        fun project(normX: Float, normY: Float): Offset {
                            val rawX = normX * size.width
                            val rawY = normY * size.height
                            return Offset(
                                x = (rawX - size.width / 2f) * zoomScale + size.width / 2f,
                                y = (rawY - size.height / 2f) * zoomScale + size.height / 2f,
                            )
                        }

                        for (edge in graphState.edges) {
                            val nA = nodeMap[edge.sourceId] ?: continue
                            val nB = nodeMap[edge.targetId] ?: continue
                            val pA = project(nA.normalizedX, nA.normalizedY)
                            val pB = project(nB.normalizedX, nB.normalizedY)
                            val (cx, cy) = CabinetGraphState.controlPoint(pA.x, pA.y, pB.x, pB.y)

                            val path = Path().apply {
                                moveTo(pA.x, pA.y)
                                quadraticTo(cx, cy, pB.x, pB.y)
                            }
                            val highlighted = graphState.isEdgeHighlighted(edge)
                            val alphaScale = if (highlighted) 1f else 0.16f

                            when (edge.severity) {
                                GraphEdgeSeverity.MAJOR -> {
                                    drawPath(
                                        path = path,
                                        color = palette.criticalCrimson.copy(
                                            alpha = 0.16f * pulseFraction * alphaScale,
                                        ),
                                        style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
                                    )
                                    drawPath(
                                        path = path,
                                        color = palette.criticalCrimson.copy(
                                            alpha = pulseFraction * alphaScale,
                                        ),
                                        style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round),
                                    )
                                }

                                GraphEdgeSeverity.MODERATE -> {
                                    drawPath(
                                        path = path,
                                        color = palette.moderateAmber.copy(alpha = 0.88f * alphaScale),
                                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
                                    )
                                }

                                GraphEdgeSeverity.MINOR -> {
                                    drawPath(
                                        path = path,
                                        color = palette.minorBlue.copy(alpha = 0.82f * alphaScale),
                                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
                                    )
                                }

                                GraphEdgeSeverity.SAFE -> {
                                    drawPath(
                                        path = path,
                                        color = palette.safeEmerald.copy(alpha = 0.55f * alphaScale),
                                        style = Stroke(
                                            width = 1.8.dp.toPx(),
                                            cap = StrokeCap.Round,
                                        ),
                                    )
                                }

                                GraphEdgeSeverity.UNANALYZED -> {
                                    drawPath(
                                        path = path,
                                        color = palette.inkSecondary.copy(alpha = 0.36f * alphaScale),
                                        style = Stroke(
                                            width = 1.5.dp.toPx(),
                                            cap = StrokeCap.Round,
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 9f), 0f),
                                        ),
                                    )
                                }
                            }
                        }

                        val pillWidth = if (graphState.nodes.size >= 7) 96.dp.toPx() else 112.dp.toPx()
                        val pillHeight = 44.dp.toPx()
                        val cornerRadius = CornerRadius(pillHeight / 2f, pillHeight / 2f)

                        for (node in graphState.nodes) {
                            val center = project(node.normalizedX, node.normalizedY)
                            val cx = center.x
                            val cy = center.y
                            val topLeft = Offset(cx - pillWidth / 2f, cy - pillHeight / 2f)
                            val isSelected = graphState.selectedNodeId == node.medicationId
                            val fillColor = if (isSelected) {
                                palette.clinicalTealContainer
                            } else {
                                palette.cardSurface
                            }
                            val borderColor = when {
                                isSelected -> palette.clinicalTeal
                                node.hasActiveRecall -> palette.criticalCrimson
                                else -> palette.slateBorder
                            }

                            drawRoundRect(
                                color = Color.Black.copy(alpha = if (palette.isDark) 0.24f else 0.06f),
                                topLeft = topLeft + Offset(0f, 2.dp.toPx()),
                                size = Size(pillWidth, pillHeight),
                                cornerRadius = cornerRadius,
                            )
                            drawRoundRect(
                                color = fillColor,
                                topLeft = topLeft,
                                size = Size(pillWidth, pillHeight),
                                cornerRadius = cornerRadius,
                            )
                            drawRoundRect(
                                color = borderColor,
                                topLeft = topLeft,
                                size = Size(pillWidth, pillHeight),
                                cornerRadius = cornerRadius,
                                style = Stroke(width = if (isSelected) 2.2.dp.toPx() else 1.2.dp.toPx()),
                            )

                            val maxTextW = (pillWidth - 18.dp.toPx()).toInt().coerceAtLeast(24)
                            val titleLayout = textMeasurer.measure(
                                text = node.displayName,
                                style = TextStyle(
                                    color = palette.inkPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                constraints = Constraints(maxWidth = maxTextW),
                            )
                            val saltText = node.canonicalSaltKeys.firstOrNull().orEmpty()
                            val saltLayout = textMeasurer.measure(
                                text = saltText,
                                style = TextStyle(
                                    color = palette.clinicalTeal,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                constraints = Constraints(maxWidth = maxTextW),
                            )

                            val totalTextH = titleLayout.size.height + saltLayout.size.height
                            val textStartY = cy - totalTextH / 2f
                            drawText(
                                textLayoutResult = titleLayout,
                                topLeft = Offset(cx - titleLayout.size.width / 2f, textStartY),
                            )
                            drawText(
                                textLayoutResult = saltLayout,
                                topLeft = Offset(
                                    cx - saltLayout.size.width / 2f,
                                    textStartY + titleLayout.size.height,
                                ),
                            )

                            if (node.hasActiveRecall) {
                                val dotCenter = Offset(topLeft.x + pillWidth - 8.dp.toPx(), topLeft.y + 6.dp.toPx())
                                drawCircle(
                                    color = palette.criticalCrimson.copy(alpha = 0.24f * pulseFraction),
                                    radius = 8.dp.toPx(),
                                    center = dotCenter,
                                )
                                drawCircle(
                                    color = palette.criticalCrimson,
                                    radius = 4.5.dp.toPx(),
                                    center = dotCenter,
                                )
                            }
                        }
                    }

                    // Compact zoom & reset instrument bar
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = PillScopeColors.SlateSurface.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { targetZoomScale = (targetZoomScale - 0.25f).coerceAtLeast(0.75f) },
                                enabled = targetZoomScale > 0.76f,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Zoom out graph",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { targetZoomScale = (targetZoomScale + 0.25f).coerceAtMost(2.0f) },
                                enabled = targetZoomScale < 1.99f,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Zoom in graph",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    targetZoomScale = 1f
                                    onResetLayout?.invoke()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = "Reset graph layout",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (graphState.pausedMedicationCount > 0) {
            Surface(
                shape = CircleShape,
                color = PillScopeColors.SurfaceTint,
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                val count = graphState.pausedMedicationCount
                Text(
                    text = "$count paused ${if (count == 1) "medication" else "medications"} hidden from network",
                    style = MaterialTheme.typography.labelSmall,
                    color = PillScopeColors.InkSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }

        if (graphState.unanalyzedEdgeCount > 0) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = PillScopeColors.SurfaceTint,
                border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val count = graphState.unanalyzedEdgeCount
                    Text(
                        text = "$count medication ${if (count == 1) "pair has" else "pairs have"} not been checked yet (dashed lines).",
                        style = MaterialTheme.typography.bodySmall,
                        color = PillScopeColors.InkPrimary,
                    )
                    if (onCheckCabinetInteractions != null) {
                        Button(
                            onClick = onCheckCabinetInteractions,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PillScopeColors.ClinicalTeal,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.HealthAndSafety,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Check cabinet interactions")
                        }
                    }
                }
            }
        }

        val selectedNode = graphState.selectedNodeId?.let { id ->
            graphState.nodes.firstOrNull { it.medicationId == id }
        }
        AnimatedVisibility(
            visible = selectedNode != null && onInspectMedication != null,
            enter = PillScopeMotion.panelEnter(reducedMotion),
            exit = PillScopeMotion.panelExit(reducedMotion),
        ) {
            if (selectedNode != null && onInspectMedication != null) {
                OutlinedButton(
                    onClick = { onInspectMedication(selectedNode.medicationId) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PillScopeColors.ClinicalTeal.copy(alpha = 0.45f)),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "View details for ${selectedNode.displayName}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GraphLegendBadge(color = PillScopeColors.CriticalCrimson, label = "Major")
            GraphLegendBadge(color = PillScopeColors.ModerateAmber, label = "Moderate")
            GraphLegendBadge(color = PillScopeColors.MinorBlue, label = "Minor")
            GraphLegendBadge(color = PillScopeColors.SafeEmerald, label = "Checked safe")
            GraphLegendBadge(color = PillScopeColors.InkSecondary.copy(alpha = 0.55f), label = "Not analyzed")
        }

        if (graphState.nodes.isNotEmpty()) {
            Text(
                text = "Tap a medication or flagged pair to inspect",
                style = MaterialTheme.typography.labelMedium,
                color = PillScopeColors.InkSecondary,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                graphState.nodes.forEach { node ->
                    val selected = node.medicationId == graphState.selectedNodeId
                    Surface(
                        onClick = { onSelectNode(node.medicationId) },
                        modifier = Modifier.heightIn(min = 38.dp),
                        shape = CircleShape,
                        color = if (selected) PillScopeColors.ClinicalTealContainer else PillScopeColors.CardSurface,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = when {
                                selected -> PillScopeColors.ClinicalTeal
                                node.hasActiveRecall -> PillScopeColors.CriticalCrimson
                                else -> PillScopeColors.SlateBorder
                            },
                        ),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = if (node.hasActiveRecall) "${node.displayName} (Recall)" else node.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) PillScopeColors.ClinicalTealDeep else PillScopeColors.InkPrimary,
                            )
                        }
                    }
                }
            }
        }

        if (interactiveEdges.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Flagged interactions (${interactiveEdges.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = PillScopeColors.InkPrimary,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = PillScopeColors.CardSurface,
                    border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        interactiveEdges.forEachIndexed { idx, edge ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    color = PillScopeColors.SlateBorder.copy(alpha = 0.65f),
                                )
                            }
                            val (bg, fg) = edgeSeverityColors(edge.severity)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { onSelectEdge(edge.id) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                ) {
                                    Text(
                                        text = "${edge.sourceName} + ${edge.targetName}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = PillScopeColors.InkPrimary,
                                    )
                                    Text(
                                        text = "${edge.saltA} + ${edge.saltB}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PillScopeColors.ClinicalTeal,
                                    )
                                }
                                Surface(shape = CircleShape, color = bg) {
                                    Text(
                                        text = edge.severity.name.lowercase().replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = fg,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    graphState.selectedEdge?.let { edge ->
        ModalBottomSheet(
            onDismissRequest = { onSelectEdge(null) },
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            EdgeInteractionDetailSheet(
                edge = edge,
                reducedMotion = reducedMotion,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun edgeSeverityColors(severity: GraphEdgeSeverity): Pair<Color, Color> = when (severity) {
    GraphEdgeSeverity.MAJOR -> PillScopeColors.CriticalCrimsonSurface to PillScopeColors.CriticalCrimsonDark
    GraphEdgeSeverity.MODERATE -> PillScopeColors.ModerateAmberSurface to PillScopeColors.ModerateAmberDark
    GraphEdgeSeverity.MINOR -> PillScopeColors.MinorBlueSurface to PillScopeColors.MinorBlueDark
    GraphEdgeSeverity.SAFE -> PillScopeColors.SafeEmeraldSurface to PillScopeColors.SafeEmeraldDark
    GraphEdgeSeverity.UNANALYZED -> PillScopeColors.SurfaceTint to PillScopeColors.InkSecondary
}

@Composable
private fun GraphLegendBadge(
    color: Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = PillScopeColors.InkSecondary,
        )
    }
}

@Composable
private fun EdgeInteractionDetailSheet(
    edge: GraphEdge,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val openUri = rememberSafeUriOpener()
    val accent = when (edge.severity) {
        GraphEdgeSeverity.MAJOR -> PillScopeColors.CriticalCrimson
        GraphEdgeSeverity.MODERATE -> PillScopeColors.ModerateAmber
        GraphEdgeSeverity.MINOR -> PillScopeColors.MinorBlue
        GraphEdgeSeverity.SAFE -> PillScopeColors.SafeEmerald
        GraphEdgeSeverity.UNANALYZED -> PillScopeColors.InkSecondary
    }
    val (surfaceColor, badgeTextColor) = edgeSeverityColors(edge.severity)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = "${edge.sourceName} + ${edge.targetName}",
                    style = MaterialTheme.typography.titleLarge,
                    color = PillScopeColors.InkPrimary,
                )
                Text(
                    text = "${edge.saltA} + ${edge.saltB}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                    fontWeight = FontWeight.Medium,
                )
            }
            Surface(shape = CircleShape, color = surfaceColor) {
                Text(
                    text = edge.severity.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeTextColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = PillScopeColors.SlateSurface,
            border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val probText = edge.mlMajorProbability?.let { prob ->
                    "Estimated major interaction likelihood: ${(prob * 100).roundToInt()}%"
                } ?: formatInteractionSourceLabel(edge.source)
                Text(
                    text = probText,
                    style = MaterialTheme.typography.labelMedium,
                    color = PillScopeColors.InkPrimary,
                )
                edge.mlMajorProbability?.let { prob ->
                    var shown by remember(edge.id) { mutableStateOf(reducedMotion) }
                    LaunchedEffect(edge.id) { shown = true }
                    val fill by animateFloatAsState(
                        targetValue = if (shown) prob.toFloat().coerceIn(0f, 1f) else 0f,
                        animationSpec = if (reducedMotion) {
                            tween(0)
                        } else {
                            tween(durationMillis = 480, easing = FastOutSlowInEasing)
                        },
                        label = "edgeProbBar",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(PillScopeColors.SurfaceTint),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fill)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(accent),
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "How they interact",
                style = MaterialTheme.typography.titleSmall,
                color = PillScopeColors.InkPrimary,
            )
            Text(
                text = edge.mechanism,
                style = MaterialTheme.typography.bodyMedium,
                color = PillScopeColors.InkPrimary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Recommended action",
                style = MaterialTheme.typography.titleSmall,
                color = PillScopeColors.InkPrimary,
            )
            Text(
                text = edge.management,
                style = MaterialTheme.typography.bodyMedium,
                color = PillScopeColors.InkPrimary,
            )
        }

        edge.citation?.let { citation ->
            HorizontalDivider(color = PillScopeColors.SlateBorder)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Official FDA drug label (${citation.sectionTitle.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }})",
                    style = MaterialTheme.typography.titleSmall,
                    color = PillScopeColors.ClinicalTeal,
                )
                Text(
                    text = citation.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.InkPrimary,
                )
                val isWebUrl = citation.sourceUrl.startsWith("https://") ||
                    citation.sourceUrl.startsWith("http://")
                if (isWebUrl) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { openUri(citation.sourceUrl) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = formatMonographLinkLabel(citation.sourceUrl),
                            style = MaterialTheme.typography.labelLarge.copy(
                                textDecoration = TextDecoration.Underline,
                            ),
                            color = PillScopeColors.MinorBlue,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open official source",
                            tint = PillScopeColors.MinorBlue,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
