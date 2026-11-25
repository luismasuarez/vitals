package dev.vitals.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vitals.app.AppViewModel
import dev.vitals.app.watchdog.WatchStatus
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

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

    /** Coarse duration for the battery card: hours and minutes, never days. */
    fun duration(secs: Long?): String {
        if (secs == null || secs <= 0) return "—"
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h} h ${m} min" else "${m} min"
    }

    fun watts(value: Float?): String =
        if (value == null) "—" else String.format(Locale.US, "%.1f W", value)

    fun one(value: Float): String = String.format(Locale.US, "%.1f", value)

    fun whole(value: Float): String = String.format(Locale.US, "%.0f", value)

    fun load(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
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
fun CardHeader(
    title: String,
    trailing: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = Type.section,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingContent != null) trailingContent()
        else if (trailing != null) {
            Text(trailing, style = Type.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        modifier = modifier,
        style = Type.section,
    )
}

@Composable
fun RowSplit(
    start: String,
    end: String,
    startColor: Color = Palette.Text,
    endColor: Color = Palette.Muted,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            start,
            color = startColor,
            style = Type.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        Text(end, color = endColor, style = Type.secondary, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, tone: ChipTone = ChipTone.Neutral, modifier: Modifier = Modifier) {
    val color = when (tone) {
        ChipTone.Neutral -> Palette.Muted
        ChipTone.Warm -> Palette.Warn
        ChipTone.Hot -> Palette.Tx
        ChipTone.Accent -> Palette.Accent
        ChipTone.Danger -> Palette.Danger
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

enum class ChipTone { Neutral, Warm, Hot, Accent, Danger }

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
fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
}

/* ----------------------------------- ring ---------------------------------- */

@Composable
fun Ring(
    percent: Float,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    color: Color = Palette.Accent,
    label: String? = null,
) {
    val sweep by animateFloatAsState(
        targetValue = (percent / 100f).coerceIn(0f, 1f),
        label = "ring",
    )
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = label ?: "${Fmt.whole(percent)} por ciento" },
        contentAlignment = Alignment.Center,
    ) {
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
                sweepAngle = 360f * sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Palette.Text, fontSize = 25.sp, fontWeight = FontWeight.Medium)) {
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
    description: String? = null,
) {
    Canvas(modifier.semantics { if (description != null) contentDescription = description }) {
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

        series.forEach { item ->
            val values = item.values
            if (values.isEmpty()) return@forEach
            val offset = w - step * (values.size - 1)
            val path = Path()
            val fillPath = Path()
            var lastX = 0f
            var lastY = 0f
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
                lastY = y
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
            drawCircle(item.color, radius = 2.6.dp.toPx(), center = Offset(lastX, lastY))
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

enum class Glyph {
    Home, Gauge, Shield, Bell, Grid, Cpu, Net, List, Sliders,
    ArrowLeft, Chevron, Plus, Refresh, Search, Battery,
}

@Composable
fun GlyphIcon(
    kind: Glyph,
    color: Color,
    size: Dp = 22.dp,
    contentDescription: String? = null,
) {
    Canvas(
        Modifier
            .size(size)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
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
            Glyph.Gauge -> {
                drawArc(
                    color = color,
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = Offset(3 * u, 4 * u),
                    size = Size(18 * u, 18 * u),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(color, Offset(12 * u, 13 * u), Offset(16.5f * u, 7.5f * u), stroke, StrokeCap.Round)
                drawCircle(color, radius = 1.6f * u, center = Offset(12 * u, 13 * u))
            }
            Glyph.Shield -> {
                path.moveTo(12 * u, 3.5f * u)
                path.lineTo(19.5f * u, 6.5f * u)
                path.lineTo(19.5f * u, 12 * u)
                path.cubicTo(19.5f * u, 16.5f * u, 16.3f * u, 19.4f * u, 12 * u, 21 * u)
                path.cubicTo(7.7f * u, 19.4f * u, 4.5f * u, 16.5f * u, 4.5f * u, 12 * u)
                path.lineTo(4.5f * u, 6.5f * u)
                path.close()
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(color, Offset(8.6f * u, 12 * u), Offset(11.2f * u, 14.6f * u), stroke, StrokeCap.Round)
                drawLine(color, Offset(11.2f * u, 14.6f * u), Offset(15.6f * u, 9.4f * u), stroke, StrokeCap.Round)
            }
            Glyph.Bell -> {
                path.moveTo(6 * u, 17 * u)
                path.cubicTo(7 * u, 15.8f * u, 7.4f * u, 14.3f * u, 7.4f * u, 12.4f * u)
                path.lineTo(7.4f * u, 10.5f * u)
                path.cubicTo(7.4f * u, 7.6f * u, 9.4f * u, 5.5f * u, 12 * u, 5.5f * u)
                path.cubicTo(14.6f * u, 5.5f * u, 16.6f * u, 7.6f * u, 16.6f * u, 10.5f * u)
                path.lineTo(16.6f * u, 12.4f * u)
                path.cubicTo(16.6f * u, 14.3f * u, 17 * u, 15.8f * u, 18 * u, 17 * u)
                path.close()
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(color, Offset(10.4f * u, 20 * u), Offset(13.6f * u, 20 * u), stroke, StrokeCap.Round)
            }
            Glyph.Grid -> {
                listOf(4f to 4f, 14f to 4f, 4f to 14f, 14f to 14f).forEach { (x, y) ->
                    drawRoundRect(
                        color,
                        topLeft = Offset(x * u, y * u),
                        size = Size(6 * u, 6 * u),
                        cornerRadius = CornerRadius(1.6f * u),
                        style = Stroke(stroke),
                    )
                }
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
            Glyph.ArrowLeft -> {
                drawLine(color, Offset(19 * u, 12 * u), Offset(5 * u, 12 * u), stroke, StrokeCap.Round)
                path.moveTo(11 * u, 6 * u); path.lineTo(5 * u, 12 * u); path.lineTo(11 * u, 18 * u)
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Glyph.Chevron -> {
                path.moveTo(9 * u, 5 * u); path.lineTo(16 * u, 12 * u); path.lineTo(9 * u, 19 * u)
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Glyph.Plus -> {
                drawLine(color, Offset(12 * u, 5 * u), Offset(12 * u, 19 * u), stroke, StrokeCap.Round)
                drawLine(color, Offset(5 * u, 12 * u), Offset(19 * u, 12 * u), stroke, StrokeCap.Round)
            }
            Glyph.Refresh -> {
                drawArc(
                    color, startAngle = -60f, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(4 * u, 4 * u), size = Size(16 * u, 16 * u),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                path.moveTo(18.5f * u, 4 * u); path.lineTo(18.5f * u, 9 * u); path.lineTo(13.5f * u, 9 * u)
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Glyph.Search -> {
                drawCircle(color, radius = 6 * u, center = Offset(10.5f * u, 10.5f * u), style = Stroke(stroke))
                drawLine(color, Offset(15 * u, 15 * u), Offset(20 * u, 20 * u), stroke, StrokeCap.Round)
            }
            Glyph.Battery -> {
                drawRoundRect(
                    color, Offset(3 * u, 7 * u), Size(16 * u, 10 * u),
                    CornerRadius(2 * u), style = Stroke(stroke),
                )
                drawLine(color, Offset(21 * u, 10 * u), Offset(21 * u, 14 * u), stroke, StrokeCap.Round)
                drawRoundRect(color, Offset(5.5f * u, 9.5f * u), Size(7 * u, 5 * u), CornerRadius(1 * u))
            }
        }
    }
}

/* --------------------------------- top bar --------------------------------- */

@Composable
fun TopBar(vm: AppViewModel) {
    val title = vm.screen.title
    val subtitle = vm.host?.let { "${it.hostname} · ${it.os}" }
        ?: vm.session?.name?.let { "$it · ${if (vm.bootError != null) "sin conexión" else "conectando…"}" }
        ?: "conectando…"

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF206080B))
            .drawBehind {
                drawLine(
                    Palette.Line,
                    Offset(0f, size.height),
                    Offset(size.width, size.height),
                    1.dp.toPx(),
                )
            }
            .heightIn(min = 60.dp)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.canGoBack) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { vm.back() },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.ArrowLeft, Palette.Text, 22.dp, contentDescription = "Volver")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(title, style = Type.screenTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Type.screenSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.watchState.status != WatchStatus.Off) WatchPill(vm.watchState.status)
            StatusPill(vm)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { vm.navigate(Screen.Settings) }
                    .semantics { contentDescription = "Ajustes" },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Sliders, Palette.Muted, 19.dp)
            }
        }
    }
}

@Composable
private fun StatusPill(vm: AppViewModel) {
    val kind = if (vm.activeEndpoint.isBlank()) "—" else AppViewModel.kindOf(vm.activeEndpoint)
    val dotColor = when {
        vm.transport == Transport.Sse -> Palette.Accent
        vm.transport == Transport.Polling -> Palette.Warn
        else -> Palette.Dim
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(Palette.Surface)
            .border(1.dp, Palette.LineStrong, CircleShape)
            .clickable(role = Role.Button) { vm.navigate(Screen.Connection) }
            .heightIn(min = 40.dp)
            .padding(horizontal = 11.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Conexión: $kind" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Dot(dotColor, 6.dp)
        Text(kind, color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
    // The transport detail lives one tap away; it stays out of the bar to save width.
}

@Composable
private fun WatchPill(status: WatchStatus) {
    val label = when (status) {
        WatchStatus.Watching -> "vigilando"
        WatchStatus.Down -> "host caído"
        WatchStatus.Connecting -> "conectando"
        WatchStatus.Off -> return
    }
    val color = when (status) {
        WatchStatus.Watching -> Palette.Accent
        WatchStatus.Down -> Palette.Warn
        WatchStatus.Connecting -> Palette.Dim
        WatchStatus.Off -> Palette.Dim
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (status == WatchStatus.Down) Color(0x1AF5D76E) else Palette.Surface)
            .border(1.dp, Palette.LineStrong, CircleShape)
            .heightIn(min = 40.dp)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color, 6.dp)
        Text(label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
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
                drawLine(Palette.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Screen.topLevelScreens.forEach { target ->
            TabItem(target, current == target, Modifier.weight(1f)) { onSelect(target) }
        }
    }
}

@Composable
private fun TabItem(target: Screen, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (active) Palette.Accent else Palette.Muted
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab) { onClick() }
            .semantics {
                role = Role.Tab
                selected = active
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(target.glyph ?: Glyph.Home, color, size = 21.dp)
        Spacer(Modifier.height(3.dp))
        Text(
            target.title,
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun NavRail(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(88.dp)
            .fillMaxHeight()
            .background(Color(0xF206080B))
            .drawBehind {
                drawLine(Palette.Line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
            }
            .navigationBarsPadding()
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Screen.topLevelScreens.forEach { target ->
            val active = current == target
            val color = if (active) Palette.Accent else Palette.Muted
            Column(
                Modifier
                    .widthIn(min = 72.dp)
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) Palette.AccentDim else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(target) }
                    .semantics {
                        role = Role.Tab
                        selected = active
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GlyphIcon(target.glyph ?: Glyph.Home, color, size = 22.dp)
                Spacer(Modifier.height(4.dp))
                Text(target.title, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/* -------------------------------- topic tabs -------------------------------- */

@Composable
fun <T> TopicTabs(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            val active = item == selected
            Text(
                label(item),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (active) Palette.AccentDim else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) Palette.Accent.copy(alpha = 0.45f) else Palette.Line,
                        CircleShape,
                    )
                    .clickable(role = Role.Tab) { onSelect(item) }
                    .semantics {
                        role = Role.Tab
                        this.selected = active
                    }
                    .padding(horizontal = 15.dp, vertical = 10.dp),
                color = if (active) Palette.Accent else Palette.Muted,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/* ---------------------------------- toast ---------------------------------- */

@Composable
fun ToastHost(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2600)
            onDismiss()
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
        exit = androidx.compose.animation.fadeOut(),
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
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Palette.Accent else Palette.Surface3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFF06120C) else Palette.Dim,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Palette.LineStrong, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.Text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x1AFF6B6B))
            .border(1.dp, Palette.Danger.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.Danger, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TextAction(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Accent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text,
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        color = if (enabled) color else Palette.Dim,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
fun Label(text: String, color: Color = Palette.Dim) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = 10.5.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false, valueColor: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Type.secondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = valueColor,
            fontSize = if (mono) 11.sp else 12.5.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.2f, fill = false),
        )
    }
}

/* -------------------------------- feedback -------------------------------- */

@Composable
fun Banner(
    text: String,
    tone: Color = Palette.Warn,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tone.copy(alpha = 0.10f))
            .border(1.dp, tone.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(tone)
        Text(text, color = tone, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Palette.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(body, color = Palette.Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/* --------------------------------- controls -------------------------------- */

@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Palette.Surface2)
            .border(1.dp, Palette.Line, CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                label,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (active) Palette.AccentDim else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .semantics {
                        role = Role.Tab
                        this.selected = active
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                color = if (active) Palette.Accent else Palette.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String = "Filtrar") {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface2)
            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Search, Palette.Dim, 16.dp)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 13.5.sp),
            cursorBrush = SolidColor(Palette.Accent),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = Palette.Dim, fontSize = 13.5.sp)
                inner()
            },
        )
    }
}

/* --------------------------------- drawing --------------------------------- */

@Composable
internal fun Bar(fraction: Float, color: Color, height: Dp = 6.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "bar")
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Palette.Surface3)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
internal fun StackBar(usedFraction: Float, usedColor: Color = Palette.Accent) {
    val used = usedFraction.coerceIn(0.001f, 0.999f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(Palette.Surface3)
    ) {
        Box(Modifier.weight(used).fillMaxHeight().background(usedColor))
        Box(Modifier.weight(1f - used).fillMaxHeight().background(Color(0xFF2B3A4A)))
    }
}

@Composable
internal fun LegendItem(color: Color, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Text(value, color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text(label, color = Palette.Muted, fontSize = 12.sp)
    }
}

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

internal fun heatColor(percent: Float): Color = when {
    percent > 85f -> Palette.Tx
    percent > 60f -> Palette.Warn
    else -> Palette.Accent
}

internal fun diskColor(fraction: Float): Color = when {
    fraction >= 0.90f -> Palette.Danger
    fraction >= 0.80f -> Palette.Warn
    else -> Palette.Rx
}

internal fun memoryColor(fraction: Float): Color = when {
    fraction >= 0.90f -> Palette.Danger
    fraction >= 0.80f -> Palette.Warn
    else -> Palette.Accent
}

internal fun maxOf(values: List<Float>, floor: Float): Float = values.fold(floor) { acc, v -> max(acc, v) }

internal fun minOf(values: List<Float>, ceiling: Float): Float = values.fold(ceiling) { acc, v -> min(acc, v) }
