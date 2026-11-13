package com.arengineering.inspection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val Cyan = Color(0xFF65F3EB)
private val Amber = Color(0xFFFFC063)
private val Night = Color(0xFF071317)
private val Glass = Color(0xE6112428)
private val Muted = Color(0xFFC1D5D6)
private val PanelShape = RoundedCornerShape(18.dp)

@Composable
internal fun InspectionScreen(
    preview: ArPreview,
    issue: StartupIssue?,
    onStartupAction: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val hud = preview.hud.collectAsState()
    val status by remember(hud) { derivedStateOf { hud.value.status } }
    val hasAnchor by remember(hud) { derivedStateOf { hud.value.hasAnchor } }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, onPrimary = Night, surface = Night)) {
        Box(Modifier.fillMaxSize().background(Night)) {
            AndroidView(factory = { preview.view }, modifier = Modifier.fillMaxSize())

            if (issue == null) {
                Box(
                    Modifier.fillMaxWidth().height(140.dp).background(
                        Brush.verticalGradient(listOf(Night.copy(alpha = 0.86f), Color.Transparent)),
                    ),
                )
                TrackingGraphics(preview, hud)
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                    InspectionHeader(status, onOpenTerminal, Modifier.align(Alignment.TopCenter))
                    TrackingPanel(status, hasAnchor, preview::clearMarker, Modifier.align(Alignment.BottomCenter))
                }
            } else {
                Box(Modifier.fillMaxSize().background(Night).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TerminalButton(onOpenTerminal, Modifier.align(Alignment.TopEnd).padding(16.dp))
                    Column(
                        Modifier.align(Alignment.Center).widthIn(max = 420.dp).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(issue.title), style = MaterialTheme.typography.headlineSmall, color = Cyan)
                        Text(stringResource(issue.hint), color = Muted)
                        issue.action?.let { action ->
                            Button(onClick = onStartupAction) { Text(stringResource(action)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionHeader(status: HudStatus, onOpenTerminal: () -> Unit, modifier: Modifier) {
    val active = status == HudStatus.READY || status == HudStatus.PLACED || status == HudStatus.MISSED
    Row(
        modifier.widthIn(max = 520.dp).fillMaxWidth().background(Glass, CircleShape)
            .border(1.dp, Cyan.copy(alpha = 0.32f), CircleShape).padding(start = 18.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.inspection), color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (active) Cyan else Amber, CircleShape))
            Text(stringResource(R.string.tracking_status), color = Muted, style = MaterialTheme.typography.labelLarge)
            TerminalButton(onOpenTerminal)
        }
    }
}

@Composable
private fun TerminalButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.telemetry_terminal_title)
    IconButton(onClick = onClick, modifier = modifier.semantics { contentDescription = description }) {
        Text("?", color = Cyan, fontSize = 22.sp)
    }
}

@Composable
private fun TrackingPanel(status: HudStatus, hasAnchor: Boolean, clear: () -> Unit, modifier: Modifier) {
    val (title, hint) = when (status) {
        HudStatus.SEARCHING -> R.string.tracking_wait to R.string.tracking_hint_wait
        HudStatus.READY -> R.string.tracking_ready to R.string.tracking_hint_ready
        HudStatus.PLACED -> R.string.tracking_anchored to R.string.tracking_hint_anchored
        HudStatus.MISSED -> R.string.tracking_missed to R.string.tracking_hint_missed
        HudStatus.PAUSED -> R.string.tracking_paused to R.string.tracking_hint_paused
        HudStatus.LOST -> R.string.tracking_lost to R.string.tracking_hint_lost
        HudStatus.TOO_DARK -> R.string.tracking_dark to R.string.tracking_hint_dark
        HudStatus.TOO_FAST -> R.string.tracking_motion to R.string.tracking_hint_motion
        HudStatus.LOW_DETAIL -> R.string.tracking_texture to R.string.tracking_hint_texture
        HudStatus.CAMERA_BUSY -> R.string.camera_busy to R.string.camera_busy_hint
    }
    Column(
        modifier.widthIn(max = 520.dp).fillMaxWidth().background(Glass, PanelShape)
            .border(1.dp, Cyan.copy(alpha = 0.40f), PanelShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(title), color = if (hasAnchor) Amber else Cyan, fontWeight = FontWeight.SemiBold)
        Text(stringResource(hint), color = Muted, style = MaterialTheme.typography.bodyMedium)
        if (hasAnchor) {
            OutlinedButton(onClick = clear, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.clear_marker))
            }
        }
    }
}

@Composable
private fun TrackingGraphics(preview: ArPreview, hud: State<HudState>) {
    val label = stringResource(R.string.marker_label)
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        Modifier.fillMaxSize().pointerInput(preview) {
            detectTapGestures { position ->
                if (size.width > 0 && size.height > 0) {
                    preview.placeMarker(ScreenPoint(position.x / size.width, position.y / size.height))
                }
            }
        },
    ) {
        drawScanner()
        val point = hud.value.point
        if (point != null) {
            drawMarker(Offset(point.x * size.width, point.y * size.height), label, textMeasurer)
        } else if (!hud.value.hasAnchor) {
            drawLine(Cyan.copy(alpha = 0.65f), center - Offset(12.dp.toPx(), 0f), center + Offset(12.dp.toPx(), 0f))
            drawLine(Cyan.copy(alpha = 0.65f), center - Offset(0f, 12.dp.toPx()), center + Offset(0f, 12.dp.toPx()))
        }
    }
}

private fun DrawScope.drawScanner() {
    val radius = size.minDimension * 0.43f
    val origin = Offset(center.x - radius, size.height * 0.56f - radius)
    repeat(11) { segment ->
        drawArc(
            color = Cyan.copy(alpha = if (segment == 5) 0.70f else 0.34f),
            startAngle = 35f + segment * 10f,
            sweepAngle = 6f,
            useCenter = false,
            topLeft = origin,
            size = Size(radius * 2, radius * 2),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawMarker(point: Offset, label: String, measurer: TextMeasurer) {
    val margin = 12.dp.toPx()
    val padding = 9.dp.toPx()
    val text = measurer.measure(
        text = label,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        constraints = Constraints(maxWidth = (size.width - 2 * (margin + padding)).toInt().coerceAtLeast(1)),
    )
    val box = Size(text.size.width + 2 * padding, text.size.height + 2 * padding)
    val gap = 34.dp.toPx()
    val toRight = point.x < center.x
    val above = point.y > center.y
    val x = (if (toRight) point.x + gap else point.x - gap - box.width)
        .coerceIn(margin, (size.width - box.width - margin).coerceAtLeast(margin))
    val y = (if (above) point.y - gap - box.height else point.y + gap)
        .coerceIn(margin, (size.height - box.height - margin).coerceAtLeast(margin))
    val lead = Offset(if (toRight) x else x + box.width, y + box.height / 2)

    drawCircle(Amber.copy(alpha = 0.22f), 15.dp.toPx(), point)
    drawCircle(Amber, 13.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
    drawLine(Amber, lead, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    val direction = (point - lead) / (point - lead).getDistance().coerceAtLeast(1f)
    val arrowBase = point - direction * 10.dp.toPx()
    val side = Offset(-direction.y, direction.x) * 4.dp.toPx()
    drawPath(
        Path().apply {
            moveTo(point.x, point.y)
            lineTo(arrowBase.x + side.x, arrowBase.y + side.y)
            lineTo(arrowBase.x - side.x, arrowBase.y - side.y)
            close()
        },
        Amber,
    )
    val corner = CornerRadius(8.dp.toPx())
    drawRoundRect(Glass, Offset(x, y), box, corner)
    drawRoundRect(Amber, Offset(x, y), box, corner, style = Stroke(1.dp.toPx()))
    drawText(text, color = Color.White, topLeft = Offset(x + padding, y + padding))
}
