package com.sam.twintracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10
import kotlin.math.pow

data class Series(val name: String, val color: Color, val values: List<Double>)

@Composable
fun Legend(series: List<Series>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(s.color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(6.dp))
                Text(s.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Rounds an axis maximum up to a 1/2/5 × 10ⁿ step so gridlines land on readable numbers. */
private fun niceMax(v: Double): Double {
    if (v <= 0) return 1.0
    val exp = 10.0.pow(kotlin.math.floor(log10(v)))
    val f = v / exp
    val nice = when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
    return nice * exp
}

/**
 * Grouped bar chart: one group per day, one bar per baby. Tap a group to read its exact values
 * (shown in the read-out line above the plot, which also shows the latest day by default).
 */
@Composable
fun GroupedBarChart(
    labels: List<String>,
    tooltipLabels: List<String>,
    series: List<Series>,
    valueFormat: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val measurer = rememberTextMeasurer()
    var selected by remember(labels.size) { mutableStateOf(labels.lastIndex) }
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val maxV = niceMax(series.flatMap { it.values }.maxOrNull() ?: 0.0)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = ink, fontSize = 10.sp)

    Column(modifier) {
        val sel = selected.coerceIn(0, labels.lastIndex)
        Text(
            tooltipLabels[sel] + "  ·  " + series.joinToString("  ·  ") { "${it.name} ${valueFormat(it.values[sel])}" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(170.dp).pointerInput(labels.size) {
                detectTapGestures { pos ->
                    val left = 32.dp.toPx()
                    val w = (size.width - left) / labels.size
                    selected = ((pos.x - left) / w).toInt().coerceIn(0, labels.lastIndex)
                }
            }
        ) {
            val left = 32.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val top = 4.dp.toPx()
            val plotH = bottom - top
            // gridlines + y labels (3 steps)
            for (i in 0..3) {
                val y = bottom - plotH * i / 3f
                drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f,
                    pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                drawLabel(measurer, compact(maxV * i / 3), Offset(0f, y - 7.dp.toPx()), labelStyle)
            }
            val groupW = (size.width - left) / labels.size
            val gap = 2.dp.toPx()
            val barW = ((groupW * 0.72f) - gap * (series.size - 1)) / series.size
            labels.forEachIndexed { i, label ->
                val gx = left + groupW * i
                if (i == sel) drawRect(highlight, Offset(gx, top), Size(groupW, plotH))
                var x = gx + groupW * 0.14f
                series.forEach { s ->
                    val v = s.values[i]
                    val h = (plotH * (v / maxV)).toFloat()
                    if (h > 0f) drawTopRoundedBar(s.color, x, bottom, barW, h)
                    x += barW + gap
                }
                // x labels: thin out when crowded
                val every = when { labels.size > 20 -> 5; labels.size > 10 -> 2; else -> 1 }
                if ((labels.lastIndex - i) % every == 0) {
                    drawLabel(measurer, label, Offset(gx + groupW / 2 - 10.dp.toPx(), bottom + 3.dp.toPx()), labelStyle)
                }
            }
        }
    }
}

private fun DrawScope.drawTopRoundedBar(color: Color, x: Float, bottom: Float, w: Float, h: Float) {
    val r = minOf(4.dp.toPx(), w / 2, h)
    val path = Path().apply {
        moveTo(x, bottom)
        lineTo(x, bottom - h + r)
        quadraticTo(x, bottom - h, x + r, bottom - h)
        lineTo(x + w - r, bottom - h)
        quadraticTo(x + w, bottom - h, x + w, bottom - h + r)
        lineTo(x + w, bottom)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawLabel(m: TextMeasurer, text: String, at: Offset, style: androidx.compose.ui.text.TextStyle) {
    drawText(m, text, topLeft = at, style = style)
}

/** Small horizontal meter used on the baby cards ("today vs daily average"). */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(3.dp.toPx()))
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
    }
}

private fun compact(v: Double): String = when {
    v >= 1000 -> "%.1fk".format(v / 1000).replace(".0k", "k")
    v % 1.0 == 0.0 -> v.toInt().toString()
    else -> "%.1f".format(v)
}

/**
 * Single-series line chart over time (one baby's weight, height or temperature). Tap to read a
 * point; the latest point is shown by default. An optional [threshold] draws a dashed reference
 * line (e.g. fever).
 */
@Composable
fun TimeLineChart(
    points: List<Pair<Long, Double>>,
    color: Color,
    valueFormat: (Double) -> String,
    dateFormat: (Long) -> String,
    modifier: Modifier = Modifier,
    threshold: Double? = null,
    thresholdColor: Color = color,
) {
    if (points.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = ink, fontSize = 10.sp)
    var selected by remember(points.size) { mutableStateOf(points.lastIndex) }
    val values = points.map { it.second } + listOfNotNull(threshold)
    val lo0 = values.min()
    val hi0 = values.max()
    val pad = ((hi0 - lo0) * 0.15).coerceAtLeast(if (hi0 == 0.0) 1.0 else hi0 * 0.02)
    val lo = lo0 - pad
    val hi = hi0 + pad
    val t0 = points.first().first
    val t1 = points.last().first.coerceAtLeast(t0 + 1)

    Column(modifier) {
        val sel = selected.coerceIn(0, points.lastIndex)
        Text(
            "${dateFormat(points[sel].first)}  ·  ${valueFormat(points[sel].second)}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).pointerInput(points) {
                detectTapGestures { pos ->
                    val left = 36.dp.toPx(); val right = 8.dp.toPx()
                    val w = size.width - left - right
                    selected = points.indices.minByOrNull { i ->
                        val x = if (points.size == 1) left + w / 2 else left + w * ((points[i].first - t0).toFloat() / (t1 - t0))
                        kotlin.math.abs(x - pos.x)
                    } ?: 0
                }
            }
        ) {
            val left = 36.dp.toPx(); val right = 8.dp.toPx()
            val top = 6.dp.toPx(); val bottom = size.height - 6.dp.toPx()
            val w = size.width - left - right
            fun x(t: Long) = if (points.size == 1) left + w / 2 else left + w * ((t - t0).toFloat() / (t1 - t0))
            fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()
            for (i in 0..2) {
                val v = lo + (hi - lo) * i / 2
                val yy = y(v)
                drawLine(grid, Offset(left, yy), Offset(size.width - right, yy), 1f, pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                drawText(measurer, compact(v), topLeft = Offset(0f, yy - 7.dp.toPx()), style = labelStyle)
            }
            threshold?.let {
                val yy = y(it)
                drawLine(thresholdColor, Offset(left, yy), Offset(size.width - right, yy), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
            if (points.size > 1) {
                val path = Path()
                points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            }
            points.forEachIndexed { i, (t, v) ->
                val r = if (i == sel) 5.dp.toPx() else 3.5.dp.toPx()
                drawCircle(surface, r + 2.dp.toPx(), Offset(x(t), y(v)))
                drawCircle(color, r, Offset(x(t), y(v)))
            }
        }
    }
}
