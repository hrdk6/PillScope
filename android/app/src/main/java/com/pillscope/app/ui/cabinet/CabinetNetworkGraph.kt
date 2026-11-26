package com.pillscope.app.ui.cabinet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
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
import com.pillscope.app.ui.theme.LocalPillScopePalette
import com.pillscope.app.ui.theme.PillScopeColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CabinetNetworkGraph(
    graphState: CabinetGraphState,
    onSelectNode: (String?) -> Unit,
    onSelectEdge: (String?) -> Unit,
    onMoveNode: (medicationId: String, normX: Float, normY: Float) -> Unit,
    onInspectMedication: ((medicationId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPillScopePalette.current
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingNodeId by remember { mutableStateOf<String?>(null) }

    val pulseTransition = rememberInfiniteTransition(label = "majorEdgePulse")
    val pulseFraction by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = PillScopeColors.CardSurface),
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
                        text = "Add active medications to visualize pairwise interactions and FDA recalls.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PillScopeColors.InkSecondary,
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(graphState.nodes, graphState.edges, canvasSize) {
                            detectTapGestures { offset ->
                                val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                val nodeHit = CabinetGraphState.hitTestNode(
                                    nodes = graphState.nodes,
                                    tapX = offset.x,
                                    tapY = offset.y,
                                    canvasWidth = w,
                                    canvasHeight = h,
                                    hitRadiusPx = 52.dp.toPx(),
                                )
                                if (nodeHit != null) {
                                    onSelectNode(nodeHit.medicationId)
                                } else {
                                    val edgeHit = CabinetGraphState.hitTestInteractiveEdge(
                                        nodes = graphState.nodes,
                                        edges = graphState.edges,
                                        tapX = offset.x,
                                        tapY = offset.y,
                                        canvasWidth = w,
                                        canvasHeight = h,
                                        hitThresholdPx = 28.dp.toPx(),
                                    )
                                    if (edgeHit != null) {
                                        onSelectEdge(edgeHit.id)
                                    } else {
                                        onSelectNode(null)
                                    }
                                }
                            }
                        }
                        .pointerInput(graphState.nodes, canvasSize) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                    draggingNodeId = CabinetGraphState.hitTestNode(
                                        nodes = graphState.nodes,
                                        tapX = startOffset.x,
                                        tapY = startOffset.y,
                                        canvasWidth = w,
                                        canvasHeight = h,
                                        hitRadiusPx = 54.dp.toPx(),
                                    )?.medicationId
                                },
                                onDragEnd = { draggingNodeId = null },
                                onDragCancel = { draggingNodeId = null },
                                onDrag = { change, dragAmount ->
                                    val activeId = draggingNodeId ?: return@detectDragGestures
                                    val node = graphState.nodes.firstOrNull { it.medicationId == activeId }
                                        ?: return@detectDragGestures
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                    change.consume()
                                    val nextX = node.normalizedX + (dragAmount.x / w)
                                    val nextY = node.normalizedY + (dragAmount.y / h)
                                    onMoveNode(activeId, nextX, nextY)
                                },
                            )
                        },
                ) {
                    val nodeMap = graphState.nodes.associateBy { it.medicationId }

                    for (edge in graphState.edges) {
                        val nA = nodeMap[edge.sourceId] ?: continue
                        val nB = nodeMap[edge.targetId] ?: continue
                        val ax = nA.normalizedX * size.width
                        val ay = nA.normalizedY * size.height
                        val bx = nB.normalizedX * size.width
                        val by = nB.normalizedY * size.height
                        val (cx, cy) = CabinetGraphState.controlPoint(ax, ay, bx, by)

                        val path = Path().apply {
                            moveTo(ax, ay)
                            quadraticTo(cx, cy, bx, by)
                        }
                        val highlighted = graphState.isEdgeHighlighted(edge)
                        val alphaScale = if (highlighted) 1f else 0.18f

                        when (edge.severity) {
                            GraphEdgeSeverity.MAJOR -> {
                                drawPath(
                                    path = path,
                                    color = palette.criticalCrimson.copy(
                                        alpha = 0.25f * pulseFraction * alphaScale,
                                    ),
                                    style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round),
                                )
                                drawPath(
                                    path = path,
                                    color = palette.criticalCrimson.copy(
                                        alpha = pulseFraction * alphaScale,
                                    ),
                                    style = Stroke(width = 3.8.dp.toPx(), cap = StrokeCap.Round),
                                )
                            }

                            GraphEdgeSeverity.MODERATE -> {
                                drawPath(
                                    path = path,
                                    color = palette.moderateAmber.copy(alpha = 0.9f * alphaScale),
                                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                                )
                            }

                            GraphEdgeSeverity.MINOR -> {
                                drawPath(
                                    path = path,
                                    color = palette.minorBlue.copy(alpha = 0.82f * alphaScale),
                                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
                                )
                            }

                            GraphEdgeSeverity.SAFE -> {
                                drawPath(
                                    path = path,
                                    color = palette.inkSecondary.copy(alpha = 0.35f * alphaScale),
                                    style = Stroke(
                                        width = 1.6.dp.toPx(),
                                        cap = StrokeCap.Round,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
                                    ),
                                )
                            }
                        }
                    }

                    val pillWidth = if (graphState.nodes.size >= 7) 96.dp.toPx() else 112.dp.toPx()
                    val pillHeight = 44.dp.toPx()
                    val cornerRadius = CornerRadius(pillHeight / 2f, pillHeight / 2f)

                    for (node in graphState.nodes) {
                        val cx = node.normalizedX * size.width
                        val cy = node.normalizedY * size.height
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
                            color = Color.Black.copy(alpha = 0.08f),
                            topLeft = topLeft + Offset(0f, 3.dp.toPx()),
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
                            style = Stroke(width = if (isSelected) 2.5.dp.toPx() else 1.4.dp.toPx()),
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
                                color = palette.criticalCrimson.copy(alpha = 0.30f * pulseFraction),
                                radius = 9.dp.toPx(),
                                center = dotCenter,
                            )
                            drawCircle(
                                color = palette.criticalCrimson,
                                radius = 5.dp.toPx(),
                                center = dotCenter,
                            )
                        }
                    }
                }
            }
        }

        val selectedNode = graphState.selectedNodeId?.let { id ->
            graphState.nodes.firstOrNull { it.medicationId == id }
        }
        if (selectedNode != null && onInspectMedication != null) {
            OutlinedButton(
                onClick = { onInspectMedication(selectedNode.medicationId) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    text = "  View details for ${selectedNode.displayName}",
                    style = MaterialTheme.typography.labelLarge,
                )
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
            GraphLegendBadge(color = PillScopeColors.InkSecondary.copy(alpha = 0.5f), label = "Safe")
            Text(
                text = "Tap colored curve for details",
                style = MaterialTheme.typography.labelSmall,
                color = PillScopeColors.InkSecondary,
            )
        }
    }

    graphState.selectedEdge?.let { edge ->
        ModalBottomSheet(
            onDismissRequest = { onSelectEdge(null) },
            containerColor = PillScopeColors.CardSurface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            EdgeInteractionDetailSheet(
                edge = edge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            )
        }
    }
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
            modifier = Modifier.size(9.dp),
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
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val accent = when (edge.severity) {
        GraphEdgeSeverity.MAJOR -> PillScopeColors.CriticalCrimson
        GraphEdgeSeverity.MODERATE -> PillScopeColors.ModerateAmber
        GraphEdgeSeverity.MINOR -> PillScopeColors.MinorBlue
        GraphEdgeSeverity.SAFE -> PillScopeColors.SafeEmerald
    }
    val surfaceColor = when (edge.severity) {
        GraphEdgeSeverity.MAJOR -> PillScopeColors.CriticalCrimsonSurface
        GraphEdgeSeverity.MODERATE -> PillScopeColors.ModerateAmberSurface
        GraphEdgeSeverity.MINOR -> PillScopeColors.MinorBlueSurface
        GraphEdgeSeverity.SAFE -> PillScopeColors.SafeEmeraldSurface
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${edge.sourceName} + ${edge.targetName}",
                    style = MaterialTheme.typography.titleLarge,
                    color = PillScopeColors.InkPrimary,
                )
                Text(
                    text = "${edge.saltA} + ${edge.saltB}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PillScopeColors.ClinicalTeal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Surface(shape = CircleShape, color = surfaceColor) {
                Text(
                    text = edge.severity.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = PillScopeColors.SlateSurface,
            border = BorderStroke(1.dp, PillScopeColors.SlateBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val probText = edge.mlMajorProbability?.let { prob ->
                    "RDKit ML major probability: ${(prob * 100).roundToInt()}%"
                } ?: "Curated deterministic rule (${edge.source})"
                Text(
                    text = probText,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
                edge.mlMajorProbability?.let { prob ->
                    LinearProgressIndicator(
                        progress = { prob.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        color = accent,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Interaction mechanism",
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
                    text = "Verbatim openFDA monograph (${citation.sectionTitle})",
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = if (isWebUrl) {
                        Modifier.clickable { runCatching { uriHandler.openUri(citation.sourceUrl) } }
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        text = citation.sourceUrl,
                        style = MaterialTheme.typography.labelSmall.copy(
                            textDecoration = if (isWebUrl) TextDecoration.Underline else TextDecoration.None,
                        ),
                        color = PillScopeColors.MinorBlue,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isWebUrl) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Open source link",
                            tint = PillScopeColors.MinorBlue,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}
