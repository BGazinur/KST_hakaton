package com.arengineering.inspection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun InspectionGraphics(
    hud: State<HudState>?,
    overlay: InspectionOverlay,
    qrGuide: Boolean,
    manualPreview: ArPreview?,
) {
    val label = stringResource(R.string.marker_label)
    val textMeasurer = rememberTextMeasurer()
    val touch = if (manualPreview == null) Modifier else Modifier.pointerInput(manualPreview) {
        detectTapGestures { position ->
            if (size.width > 0 && size.height > 0) {
                manualPreview.placeMarker(ScreenPoint(position.x / size.width, position.y / size.height))
            }
        }
    }
    Canvas(Modifier.fillMaxSize().then(touch)) {
        drawScanner()
        if (qrGuide) drawQrGuide()

        val tracking = hud?.value ?: return@Canvas
        if (!tracking.status.canProject) return@Canvas
        if (manualPreview != null) {
            tracking.point?.let {
                drawMarker(Offset(it.x * size.width, it.y * size.height), label, textMeasurer, Amber)
            }
            return@Canvas
        }

        if (overlay.pumpContour.size >= 3) {
            val contour = Path()
            overlay.pumpContour.forEachIndexed { index, point ->
                val x = point.x * size.width
                val y = point.y * size.height
                if (index == 0) contour.moveTo(x, y) else contour.lineTo(x, y)
            }
            contour.close()
            drawPath(contour, SkyBlue.copy(alpha = 0.12f))
            drawPath(contour, SkyBlue, style = Stroke(2.dp.toPx()))
        }
        overlay.defectRegions.forEach { points ->
            if (points.size < 3) return@forEach
            val border = Path()
            points.forEachIndexed { index, point ->
                val x = point.x * size.width
                val y = point.y * size.height
                if (index == 0) border.moveTo(x, y) else border.lineTo(x, y)
            }
            border.close()
            drawPath(border, Amber.copy(alpha = 0.16f))
            drawPath(border, Amber, style = Stroke(2.dp.toPx()))
        }
        overlay.marks.forEach { mark ->
            drawMarker(
                Offset(mark.point.x * size.width, mark.point.y * size.height),
                mark.label, textMeasurer, if (mark.warning) Amber else SkyBlue,
            )
        }
    }
}

internal val HudStatus.canProject: Boolean
    get() = this == HudStatus.READY || this == HudStatus.PLACED || this == HudStatus.MISSED ||
        this == HudStatus.LOST

private fun DrawScope.drawQrGuide() {
    val half = minOf(size.minDimension * 0.23f, 105.dp.toPx())
    val arm = half * 0.30f
    for (x in listOf(-1f, 1f)) {
        for (y in listOf(-1f, 1f)) {
            val corner = Offset(center.x + x * half, size.height * 0.42f + y * half)
            drawLine(SkyBlue, corner, corner - Offset(x * arm, 0f), 2.dp.toPx())
            drawLine(SkyBlue, corner, corner - Offset(0f, y * arm), 2.dp.toPx())
        }
    }
}

private fun DrawScope.drawScanner() {
    val radius = size.minDimension * 0.43f
    val origin = Offset(center.x - radius, size.height * 0.56f - radius)
    repeat(11) { segment ->
        drawArc(
            color = SkyBlue.copy(alpha = if (segment == 5) 0.70f else 0.34f),
            startAngle = 35f + segment * 10f,
            sweepAngle = 6f,
            useCenter = false,
            topLeft = origin,
            size = Size(radius * 2, radius * 2),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawMarker(point: Offset, label: String, measurer: TextMeasurer, color: Color) {
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

    drawCircle(color.copy(alpha = 0.22f), 15.dp.toPx(), point)
    drawCircle(color, 13.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
    drawLine(color, lead, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
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
        color,
    )
    val corner = CornerRadius(8.dp.toPx())
    drawRoundRect(Glass, Offset(x, y), box, corner)
    drawRoundRect(color, Offset(x, y), box, corner, style = Stroke(1.dp.toPx()))
    drawText(text, color = Color.White, topLeft = Offset(x + padding, y + padding))
}
