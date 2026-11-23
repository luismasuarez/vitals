package dev.vitals.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

/* --------------------------------- navigation ------------------------------ */

enum class Screen { Home, Cpu, Net, Procs, Rules, Caps }

/* --------------------------------- formaters ------------------------------- */

object Fmt {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (v >= 100) "${v.toInt()} ${units[i]}" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun rate(value: Long): String = "${bytes(value)}/s"

    fun uptime(secs: Long): String {
        val d = secs / 86400
        val h = (secs % 86400) / 3600
        val m = (secs % 3600) / 60
        return buildString {
            if (d > 0) append("${d}d ")
            append("${h}h ${m}m")
        }
    }

    fun one(value: Float): String = String.format(Locale.US, "%.1f", value)

    fun whole(value: Float): String = String.format(Locale.US, "%.0f", value)
}

/* ---------------------------------- surfaces ------------------------------- */

@Composable
fun VitalsCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Palette.Surface, Palette.SurfaceLow)))
            .border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun CardHeader(title: String, trailing: String? = null, trailingContent: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            color = Palette.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        if (trailingContent != null) trailingContent()
        else if (trailing != null) Text(trailing, color = Palette.Muted, fontSize = 12.sp)
    }
}

@Composable
fun RowSplit(start: String, end: String, startColor: Color = Palette.Text, endColor: Color = Palette.Muted) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, color = startColor, fontSize = 12.5.sp)
        Text(end, color = endColor, fontSize = 12.5.sp)
    }
}

@Composable
fun Chip(text: String, tone: ChipTone = ChipTone.Neutral, modifier: Modifier = Modifier) {
    val color = when (tone) {
        ChipTone.Neutral -> Palette.Muted
        ChipTone.Warm -> Palette.Warn
        ChipTone.Hot -> Palette.Tx
        ChipTone.Accent -> Palette.Accent
    }
    Text(
        text,
        modifier
            .clip(CircleShape)
            .background(Palette.Surface3)
            .border(1.dp, color.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        color = color,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

enum class ChipTone { Neutral, Warm, Hot, Accent }

@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.Line)
    )
}

/* ----------------------------------- ring ---------------------------------- */

@Composable
fun Ring(percent: Float, modifier: Modifier = Modifier, size: Dp = 104.dp, color: Color = Palette.Accent) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = Palette.Surface3,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * (percent / 100f).coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium)) {
                    append(Fmt.whole(percent))
                }
                withStyle(SpanStyle(color = Palette.Muted, fontSize = 12.sp)) {
                    append("%")
                }
            }
        )
    }
}

/* ---------------------------------- charts --------------------------------- */

data class SeriesData(val values: List<Float>, val color: Color, val fill: Boolean = true)

@Composable
fun Chart(
    series: List<SeriesData>,
    modifier: Modifier = Modifier,
    capacity: Int = 60,
    maxValue: Float? = null,
    minValue: Float = 1f,
    grid: Boolean = false,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val top = 4.dp.toPx()
        val bottom = if (grid) 4.dp.toPx() else 0f
        val usable = (h - top - bottom).coerceAtLeast(1f)

        if (grid) {
            for (i in 1..3) {
                val y = top + usable * i / 4f
                drawLine(Color(0x0DFFFFFF), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
        }

        val ceiling = maxValue ?: niceCeiling(series.flatMap { it.values }, minValue)
        val step = w / (capacity - 1).coerceAtLeast(1)

        series.forEachIndexed { index, item ->
            val values = item.values
            if (values.size < 1) return@forEachIndexed
            val offset = w - step * (values.size - 1)
            val path = Path()
            val fillPath = Path()
            var lastX = 0f
            values.forEachIndexed { i, raw ->
                val x = offset + i * step
                val y = top + usable * (1f - (raw / ceiling).coerceIn(0f, 1f))
                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                lastX = x
            }
            if (item.fill) {
                fillPath.lineTo(lastX, h)
                fillPath.close()
                drawPath(
                    fillPath,
                    brush = Brush.verticalGradient(
                        listOf(item.color.copy(alpha = 0.26f), Color.Transparent),
                        startY = top,
                        endY = h,
                    ),
                )
            }
            drawPath(
                path,
                color = item.color,
                style = Stroke(
                    width = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

private fun niceCeiling(values: List<Float>, floor: Float): Float {
    var m = floor
    for (v in values) if (v > m) m = v
    val padded = m * 1.18f
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(padded.toDouble()))).toFloat()
    return (Math.ceil((padded / magnitude).toDouble()) * magnitude).toFloat().coerceAtLeast(1f)
}

/* ---------------------------------- icons ---------------------------------- */

enum class Glyph { Home, Cpu, Net, List, Sliders }

@Composable
fun GlyphIcon(kind: Glyph, color: Color, size: Dp = 21.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = 1.7.dp.toPx()
        val path = Path()

        when (kind) {
            Glyph.Home -> {
                path.moveTo(3 * u, 11.5f * u); path.lineTo(12 * u, 4 * u); path.lineTo(21 * u, 11.5f * u)
                path.moveTo(5.5f * u, 10 * u); path.lineTo(5.5f * u, 20 * u)
                path.lineTo(18.5f * u, 20 * u); path.lineTo(18.5f * u, 10 * u)
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Glyph.Cpu -> {
                drawRoundRect(
                    color, Offset(6 * u, 6 * u), Size(12 * u, 12 * u),
                    CornerRadius(2 * u), style = Stroke(stroke),
                )
                listOf(9f, 15f).forEach { x ->
                    drawLine(color, Offset(x * u, 3 * u), Offset(x * u, 6 * u), stroke, StrokeCap.Round)
                    drawLine(color, Offset(x * u, 18 * u), Offset(x * u, 21 * u), stroke, StrokeCap.Round)
                    drawLine(color, Offset(3 * u, x * u), Offset(6 * u, x * u), stroke, StrokeCap.Round)
                    drawLine(color, Offset(18 * u, x * u), Offset(21 * u, x * u), stroke, StrokeCap.Round)
                }
            }
            Glyph.Net -> {
                path.moveTo(12 * u, 19 * u); path.lineTo(12 * u, 7 * u)
                path.moveTo(7 * u, 12 * u); path.lineTo(12 * u, 7 * u); path.lineTo(17 * u, 12 * u)
                path.moveTo(4 * u, 20.5f * u); path.lineTo(20 * u, 20.5f * u)
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Glyph.List -> {
                drawLine(color, Offset(4 * u, 6 * u), Offset(20 * u, 6 * u), stroke, StrokeCap.Round)
                drawLine(color, Offset(4 * u, 12 * u), Offset(20 * u, 12 * u), stroke, StrokeCap.Round)
                drawLine(color, Offset(4 * u, 18 * u), Offset(14 * u, 18 * u), stroke, StrokeCap.Round)
            }
            Glyph.Sliders -> {
                val rows = listOf(7f to 9f, 12f to 15f, 17f to 11f)
                rows.forEach { (y, knob) ->
                    drawLine(color, Offset(4 * u, y * u), Offset(20 * u, y * u), stroke, StrokeCap.Round)
                    drawCircle(Palette.Bg, radius = 3 * u, center = Offset(knob * u, y * u))
                    drawCircle(color, radius = 3 * u, center = Offset(knob * u, y * u), style = Stroke(stroke))
                }
            }
        }
    }
}

/* -------------------------------- bottom bar ------------------------------- */

@Composable
fun TabBar(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xF206080B))
            .drawBehind {
                drawLine(
                    Palette.Line,
                    Offset(0f, 0f),
                    Offset(size.width, 0f),
                    1.dp.toPx(),
                )
            }
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TabItem(Glyph.Home, "Inicio", current == Screen.Home) { onSelect(Screen.Home) }
        TabItem(Glyph.Cpu, "CPU", current == Screen.Cpu) { onSelect(Screen.Cpu) }
        TabItem(Glyph.Net, "Red", current == Screen.Net) { onSelect(Screen.Net) }
        TabItem(Glyph.List, "Procesos", current == Screen.Procs) { onSelect(Screen.Procs) }
    }
}

@Composable
private fun TabItem(glyph: Glyph, label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) Palette.Accent else Palette.Muted
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        GlyphIcon(glyph, color)
        Text(label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ---------------------------------- toast ---------------------------------- */

@Composable
fun ToastHost(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(
                message.orEmpty(),
                modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF182028))
                    .border(1.dp, Palette.LineStrong, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = Palette.Text,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/* -------------------------------- primitives ------------------------------- */

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Palette.Accent else Palette.Surface3)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color(0xFF06120C) else Palette.Dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Palette.LineStrong, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x1AFF6B6B))
            .border(1.dp, Palette.Danger.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.Danger, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Label(text: String, color: Color = Palette.Dim) {
    Text(text.uppercase(), color = color, fontSize = 10.5.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun VSpacer(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpacer(width: Dp) = Spacer(Modifier.width(width))

/* --------------------------------- drawing --------------------------------- */

internal fun DrawScope.dashedLine(color: Color, start: Offset, end: Offset, width: Float) {
    val total = (end - start).getDistance()
    val dash = 6f
    var drawn = 0f
    while (drawn < total) {
        val from = start + (end - start) * (drawn / total)
        val to = start + (end - start) * ((drawn + dash) / total).coerceAtMost(1f)
        drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)
        drawn += dash * 2
    }
}

internal fun DrawScope.progressBar(fraction: Float, color: Color, height: Float) {
    val w = size.width
    val y = size.height / 2
    val radius = height / 2
    drawRoundRect(
        Palette.Surface3,
        topLeft = Offset(0f, y - radius),
        size = Size(w, height),
        cornerRadius = CornerRadius(radius),
    )
    val filled = (w * fraction.coerceIn(0f, 1f)).coerceAtLeast(if (fraction > 0f) height else 0f)
    drawRoundRect(
        color,
        topLeft = Offset(0f, y - radius),
        size = Size(filled, height),
        cornerRadius = CornerRadius(radius),
    )
}

internal fun lerpColor(fraction: Float, from: Color, to: Color): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = 1f,
    )
}

internal fun heatColor(percent: Float): Color = when {
    percent > 85f -> Palette.Tx
    percent > 60f -> Palette.Warn
    else -> Palette.Accent
}

internal fun maxOf(values: List<Float>, floor: Float): Float = values.fold(floor) { acc, v -> max(acc, v) }
