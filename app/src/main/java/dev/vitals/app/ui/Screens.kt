package dev.vitals.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vitals.app.AppViewModel
import dev.vitals.app.Series
import dev.vitals.app.core.Point
import dev.vitals.app.core.Power
import dev.vitals.app.core.Proc
import dev.vitals.app.core.Sample
import dev.vitals.app.watchdog.WatchStatus
import kotlin.math.abs

/* ---------------------------------- top bar -------------------------------- */

@Composable
fun TopBar(vm: AppViewModel, onSettings: () -> Unit, onEndpoints: () -> Unit) {
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSettings)) {
            Text(vm.host?.hostname ?: "—", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(vm.host?.os ?: "conectando…", color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.watchState.status != WatchStatus.Off) WatchPill(vm.watchState.status)
            StatusPill(vm, onEndpoints)
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Surface)
                    .border(1.dp, Palette.LineStrong, RoundedCornerShape(12.dp))
                    .clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Sliders, Palette.Muted, size = 18.dp)
            }
        }
    }
}

@Composable
private fun StatusPill(vm: AppViewModel, onClick: () -> Unit) {
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
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Dot(dotColor)
        Text(kind, color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WatchPill(status: WatchStatus) {
    val label = when (status) {
        WatchStatus.Watching -> "vigilando"
        WatchStatus.Down -> "caído"
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
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color)
        Text(label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ----------------------------------- home ---------------------------------- */

@Composable
fun HomeScreen(vm: AppViewModel) {
    val sample = vm.sample
    val series = vm.series

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vm.bootError != null && sample == null) {
            Banner(vm.bootError ?: "")
        }
        if (vm.watchState.status == WatchStatus.Down) {
            Banner("El host no responde. Te envié una notificación local.")
        }

        VitalsCard(padding = 18.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cpu = sample?.cpu?.total ?: 0f
                Ring(percent = cpu, color = heatColor(cpu))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("CPU")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("load ${formatLoad(sample?.cpu?.load1)}")
                        Chip(tempLabel(sample))
                    }
                    Text(
                        sample?.let { vm.host?.cpuModel }.orEmpty().ifBlank { "—" },
                        color = Palette.Muted,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Chart(
                listOf(SeriesData(series.cpu, Palette.Accent)),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                capacity = Series.MAX,
                minValue = 15f,
            )
        }

        VitalsCard {
            CardHeader("Memoria", trailing = sample?.let { Fmt.whole(it.mem.used.toFloat() / it.mem.total * 100) + "%" } ?: "—")
            StackBar(usedFraction = sample?.let { it.mem.used.toFloat() / it.mem.total }.orZero())
            RowSplit(
                start = sample?.let { "${Fmt.bytes(it.mem.used)} de ${Fmt.bytes(it.mem.total)}" } ?: "—",
                end = sample?.let { if (it.mem.swapTotal > 0) "swap ${Fmt.bytes(it.mem.swapUsed)}" else "sin swap" } ?: "—",
            )
        }

        sample?.power?.let { power ->
            EnergyCard(power = power, history = vm.powerHistory)
        }

        VitalsCard {
            CardHeader(
                "Red",
                trailing = sample?.let { Fmt.rate(it.net.rxBps + it.net.txBps) } ?: "—",
            )
            Chart(
                listOf(
                    SeriesData(series.rx, Palette.Rx),
                    SeriesData(series.tx, Palette.Tx, fill = false),
                ),
                modifier = Modifier.fillMaxWidth().height(72.dp),
                capacity = Series.MAX,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(Palette.Rx, sample?.let { Fmt.rate(it.net.rxBps) } ?: "—", "bajada")
                LegendItem(Palette.Tx, sample?.let { Fmt.rate(it.net.txBps) } ?: "—", "subida")
            }
        }

        VitalsCard {
            CardHeader("Discos", trailing = sample?.let { d ->
                val io = d.disks.sumOf { it.rBps + it.wBps }
                if (io > 0) Fmt.rate(io) else "sin I/O"
            } ?: "—")
            val disks = sample?.disks.orEmpty()
            if (disks.isEmpty()) {
                Text("Sin discos legibles.", color = Palette.Dim, fontSize = 12.sp)
            } else {
                disks.forEach { disk ->
                    val fraction = if (disk.total > 0) disk.used.toFloat() / disk.total else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                disk.mount,
                                color = Palette.Muted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text(
                                "${Fmt.whole(fraction * 100)}% · ${Fmt.bytes(disk.used)}/${Fmt.bytes(disk.total)}",
                                color = Palette.Text,
                                fontSize = 12.sp,
                            )
                        }
                        Bar(fraction, Palette.Rx)
                        val io = disk.rBps + disk.wBps
                        Text(
                            if (io > 0) "R ${Fmt.rate(disk.rBps)} · W ${Fmt.rate(disk.wBps)}" else "inactivo",
                            color = Palette.Dim,
                            fontSize = 10.5.sp,
                        )
                    }
                }
            }
        }

        Text(
            sample?.let { "encendido hace ${Fmt.uptime(it.uptime)}" } ?: "—",
            color = Palette.Dim,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
    }
}

/* ---------------------------------- energy --------------------------------- */

@Composable
private fun EnergyCard(power: Power, history: List<Point>) {
    val color = batteryColor(power)
    val battery = batterySeries(history)
    val showChart = battery.size >= 3 && battery.distinct().size > 1
    val details = buildList {
        power.powerW?.let { add(Fmt.watts(it)) }
        power.healthPct?.let { add("salud ${Fmt.whole(it)}%") }
    }.joinToString(" · ")

    VitalsCard {
        CardHeader(
            "Energía",
            trailing = if (power.acOnline) "AC conectada" else "en batería",
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ring(percent = power.percent ?: 0f, size = 96.dp, color = color)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(powerStatus(power.status), batteryTone(power))
                    if (power.acOnline) Chip("AC", ChipTone.Accent)
                }
                Text(
                    powerPrimaryLine(power),
                    color = Palette.Text,
                    fontSize = 12.5.sp,
                )
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        color = Palette.Muted,
                        fontSize = 11.5.sp,
                    )
                }
                power.cycles?.let {
                    Text("$it ciclos", color = Palette.Dim, fontSize = 10.5.sp)
                }
            }
        }

        if (showChart) {
            Chart(
                listOf(SeriesData(battery, color)),
                modifier = Modifier.fillMaxWidth().height(58.dp),
                capacity = Series.MAX,
                maxValue = 100f,
                minValue = 20f,
            )
            RowSplit(
                start = "batería · ${historyLabel(history)}",
                end = power.batteries.firstOrNull()?.model ?: "—",
            )
        } else {
            val batteryName = power.batteries.firstOrNull()?.name ?: "batería"
            val model = power.batteries.firstOrNull()?.model
            if (!model.isNullOrBlank()) {
                RowSplit(start = batteryName, end = model)
            }
        }

        if (power.batteries.size > 1) {
            power.batteries.forEach { item ->
                RowSplit(
                    start = item.name,
                    end = "${item.percent?.let { Fmt.whole(it) + "%" } ?: "—"} · ${powerStatus(item.status)}",
                )
            }
        }
    }
}

private fun powerStatus(status: String): String = when (status) {
    "charging" -> "cargando"
    "discharging" -> "descargando"
    "full" -> "completa"
    "not_charging" -> "en pausa"
    else -> "sin datos"
}

private fun powerPrimaryLine(power: Power): String = when {
    power.status == "charging" && (power.percent ?: 0f) >= 99.5f -> "batería completa"
    power.status == "charging" && power.secondsToFull != null && power.secondsToFull > 0 ->
        "${Fmt.duration(power.secondsToFull)} para 100 %"
    power.status == "charging" -> "cargando"
    power.status == "discharging" && power.secondsLeft != null ->
        "${Fmt.duration(power.secondsLeft)} restantes"
    power.status == "full" -> "batería completa"
    power.status == "not_charging" -> "conectada, sin cargar"
    else -> "—"
}

private fun batteryColor(power: Power): Color {
    val percent = power.percent ?: 100f
    return when {
        power.status == "charging" -> Palette.Accent
        percent <= 10f -> Palette.Danger
        percent <= 25f -> Palette.Warn
        else -> Palette.Rx
    }
}

private fun batteryTone(power: Power): ChipTone {
    val percent = power.percent ?: 100f
    return when {
        power.status == "charging" -> ChipTone.Accent
        percent <= 10f -> ChipTone.Hot
        percent <= 25f -> ChipTone.Warm
        else -> ChipTone.Neutral
    }
}

/** A 24 h series has one point per minute; the chart only needs 60. */
private fun batterySeries(points: List<Point>): List<Float> {
    val values = points.mapNotNull { it.batteryPct }
    if (values.size <= Series.MAX) return values
    val step = values.size / Series.MAX.toFloat()
    return (0 until Series.MAX).map { index ->
        val from = (index * step).toInt().coerceAtMost(values.lastIndex)
        val to = ((index + 1) * step).toInt().coerceIn(from + 1, values.size)
        values.subList(from, to).average().toFloat()
    }
}

private fun historyLabel(points: List<Point>): String {
    val powered = points.filter { it.batteryPct != null }
    if (powered.size < 2) return "en vivo"
    val span = powered.last().t - powered.first().t
    return when {
        span >= 20 * 3600 -> "24 h"
        span >= 50 * 60 -> "1 h"
        span >= 10 * 60 -> "15 min"
        else -> "en vivo"
    }
}

/* ----------------------------------- cpu ----------------------------------- */

@Composable
fun CpuScreen(vm: AppViewModel) {
    val sample = vm.sample
    val values = vm.series.cpu

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VitalsCard {
            CardHeader("Total", trailing = sample?.let { "${Fmt.one(it.cpu.total)}% ahora" } ?: "—")
            Chart(
                listOf(SeriesData(values, Palette.Accent)),
                modifier = Modifier.fillMaxWidth().height(160.dp),
                capacity = Series.MAX,
                maxValue = 100f,
                grid = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (values.isNotEmpty()) {
                    Text("mín ${Fmt.whole(values.min())}%", color = Palette.Muted, fontSize = 11.sp)
                    Text("med ${Fmt.whole(values.average().toFloat())}%", color = Palette.Muted, fontSize = 11.sp)
                    Text("máx ${Fmt.whole(values.max())}%", color = Palette.Muted, fontSize = 11.sp)
                }
            }
        }

        VitalsCard {
            CardHeader("Núcleos", trailing = "${sample?.cpu?.cores?.size ?: 0} núcleos")
            val cores = sample?.cpu?.cores.orEmpty()
            cores.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEachIndexed { column, value ->
                        CoreRow(
                            index = row * 2 + column,
                            value = value,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (cores.isEmpty()) Text("—", color = Palette.Dim, fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LoadCard("load 1m", sample?.cpu?.load1, Modifier.weight(1f))
            LoadCard("load 5m", sample?.cpu?.load5, Modifier.weight(1f))
            LoadCard("load 15m", sample?.cpu?.load15, Modifier.weight(1f))
        }

        VitalsCard {
            CardHeader("Temperaturas")
            val temps = sample?.temps.orEmpty()
            if (temps.isEmpty()) {
                Text("Este host no expone sensores legibles.", color = Palette.Dim, fontSize = 12.sp)
            } else {
                temps.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { temp ->
                            Chip(
                                "${temp.label} ${Fmt.whole(temp.celsius)} °C",
                                tone = when {
                                    temp.celsius > 80f -> ChipTone.Hot
                                    temp.celsius > 65f -> ChipTone.Warm
                                    else -> ChipTone.Neutral
                                },
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreRow(index: Int, value: Float, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("#$index", color = Palette.Dim, fontSize = 10.5.sp)
            Text("${Fmt.whole(value)}%", color = Palette.Dim, fontSize = 10.5.sp)
        }
        Bar(value / 100f, heatColor(value), height = 5.dp)
    }
}

@Composable
private fun LoadCard(label: String, value: Double?, modifier: Modifier = Modifier) {
    VitalsCard(modifier = modifier, padding = 12.dp) {
        Label(label)
        Text(value?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—", color = Palette.Text, fontSize = 20.sp)
    }
}

/* ----------------------------------- net ----------------------------------- */

@Composable
fun NetScreen(vm: AppViewModel) {
    val sample = vm.sample
    val history = vm.history

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VitalsCard {
            CardHeader(
                "Tráfico",
                trailingContent = {
                    Segmented(
                        options = listOf("live" to "Ahora", "1h" to "1 h", "6h" to "6 h"),
                        selected = vm.netRange,
                        onSelect = { vm.loadHistory(it) },
                    )
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BigRate(Palette.Rx, sample?.net?.rxBps ?: 0L)
                BigRate(Palette.Tx, sample?.net?.txBps ?: 0L)
            }
            if (vm.netRange == "live") {
                Chart(
                    listOf(
                        SeriesData(vm.series.rx, Palette.Rx),
                        SeriesData(vm.series.tx, Palette.Tx, fill = false),
                    ),
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    capacity = Series.MAX,
                    grid = true,
                )
            } else {
                val points = history
                Chart(
                    listOf(
                        SeriesData(points.map { it.netRx.toFloat() }, Palette.Rx),
                        SeriesData(points.map { it.netTx.toFloat() }, Palette.Tx, fill = false),
                    ),
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    capacity = points.size.coerceAtLeast(2),
                    grid = true,
                )
                if (points.isEmpty()) {
                    Text("Sin datos para este rango todavía.", color = Palette.Dim, fontSize = 11.5.sp)
                }
            }
        }

        VitalsCard {
            CardHeader("Interfaces")
            val ifaces = sample?.net?.ifaces.orEmpty()
            if (ifaces.isEmpty()) {
                Text("—", color = Palette.Dim, fontSize = 12.sp)
            } else {
                ifaces.forEach { iface ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.Surface2)
                            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            iface.name,
                            color = Palette.Text,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            "↓${Fmt.rate(iface.rxBps)} ↑${Fmt.rate(iface.txBps)}",
                            color = Palette.Muted,
                            fontSize = 11.5.sp,
                        )
                    }
                }
            }
        }

        VitalsCard {
            CardHeader("Conexión")
            vm.endpoints.forEachIndexed { index, url ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (index == vm.endpointIndex) Palette.AccentDim else Palette.Surface2)
                        .border(
                            1.dp,
                            if (index == vm.endpointIndex) Palette.Accent.copy(alpha = 0.4f) else Palette.Line,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { vm.selectEndpoint(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        url.removePrefix("https://"),
                        color = Palette.Text,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        if (index == vm.endpointIndex) "activo" else AppViewModel.kindOf(url),
                        color = if (index == vm.endpointIndex) Palette.Accent else Palette.Dim,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/* --------------------------------- procesos -------------------------------- */

@Composable
fun ProcsScreen(vm: AppViewModel) {
    val filtered = remember(vm.procs, vm.procFilter) {
        val needle = vm.procFilter.trim().lowercase()
        if (needle.isEmpty()) vm.procs else vm.procs.filter { it.name.lowercase().contains(needle) }
    }

    val maxValue = remember(filtered, vm.procSort) {
        val raw = when (vm.procSort) {
            "mem" -> filtered.maxOfOrNull { it.mem.toFloat() } ?: 1f
            else -> filtered.maxOfOrNull { it.cpu } ?: 1f
        }
        raw.coerceAtLeast(1f)
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchField(vm.procFilter, onValueChange = vm::filterProcs)
            Segmented(
                options = listOf("cpu" to "CPU", "mem" to "Memoria"),
                selected = vm.procSort,
                onSelect = vm::sortProcs,
                modifier = Modifier.align(Alignment.Start),
            )
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Palette.Surface, Palette.SurfaceLow)))
                .border(1.dp, Palette.Line, RoundedCornerShape(18.dp)),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(filtered, key = { _, proc -> proc.pid }) { index, proc ->
                ProcRow(proc, vm.procSort, maxValue, last = index == filtered.lastIndex)
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        "Sin procesos para este filtro.",
                        color = Palette.Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcRow(proc: Proc, sort: String, max: Float, last: Boolean) {
    val value = if (sort == "cpu") proc.cpu else proc.mem.toFloat()
    val fraction = if (sort == "cpu") {
        (proc.cpu / 100f).coerceIn(0f, 1f)
    } else {
        (value / max).coerceIn(0f, 1f)
    }
    val hot = if (sort == "cpu") proc.cpu > 70f else fraction > 0.7f
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    proc.name,
                    color = Palette.Text,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                    Text("pid ${proc.pid}", color = Palette.Muted, fontSize = 10.5.sp)
            }
            Text(
                if (sort == "cpu") "${Fmt.one(proc.cpu)}%" else Fmt.bytes(proc.mem),
                color = Palette.Text,
                fontSize = 12.sp,
            )
        }
        Bar(fraction, if (hot) Palette.Tx else Palette.Accent, height = 3.dp)
        }
        if (!last) DividerLine()
    }
}

/* ---------------------------------- helpers -------------------------------- */

@Composable
internal fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
}

@Composable
private fun BigRate(color: Color, bytesPerSec: Long) {
    Row(verticalAlignment = Alignment.Bottom) {
        Dot(color)
        Text(Fmt.bytes(bytesPerSec), color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("/s", color = Palette.Dim, fontSize = 11.sp)
    }
}

@Composable
internal fun Bar(fraction: Float, color: Color, height: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Palette.Surface3)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
internal fun StackBar(usedFraction: Float) {
    val used = usedFraction.coerceIn(0.001f, 0.999f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(Palette.Surface3)
    ) {
        Box(Modifier.weight(used).fillMaxHeight().background(Palette.Accent))
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

@Composable
internal fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
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
                    .clip(CircleShape)
                    .background(if (active) Palette.AccentDim else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                color = if (active) Palette.Accent else Palette.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface2)
            .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val radius = size.minDimension * 0.32f
            val center = Offset(size.width * 0.42f, size.height * 0.42f)
            drawCircle(Palette.Dim, radius, center, style = Stroke(1.6.dp.toPx()))
            drawLine(
                Palette.Dim,
                Offset(center.x + radius * 0.75f, center.y + radius * 0.75f),
                Offset(size.width * 0.9f, size.height * 0.9f),
                1.6.dp.toPx(),
                StrokeCap.Round,
            )
        }
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 13.5.sp),
            cursorBrush = SolidColor(Palette.Accent),
            decorationBox = { inner ->
                if (value.isEmpty()) Text("Filtrar procesos", color = Palette.Dim, fontSize = 13.5.sp)
                inner()
            },
        )
    }
}

@Composable
internal fun Banner(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x1AF5D76E))
            .border(1.dp, Palette.Warn.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(Palette.Warn)
        Text(text, color = Palette.Warn, fontSize = 12.sp)
    }
}

private fun sampleTemp(sample: Sample?): Float? = sample?.temps?.firstOrNull()?.celsius

private fun tempLabel(sample: Sample?): String =
    sampleTemp(sample)?.let { "${Fmt.whole(it)} °C" } ?: "sin sensor"

private fun formatLoad(value: Double?): String =
    value?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"

private fun Float?.orZero(): Float = this ?: 0f
