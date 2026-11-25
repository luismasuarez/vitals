package dev.vitals.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vitals.app.AppViewModel
import dev.vitals.app.core.Disk
import dev.vitals.app.core.Point
import dev.vitals.app.core.Power
import dev.vitals.app.ui.Bar
import dev.vitals.app.ui.CardHeader
import dev.vitals.app.ui.Chart
import dev.vitals.app.ui.Chip
import dev.vitals.app.ui.ChipTone
import dev.vitals.app.ui.DividerLine
import dev.vitals.app.ui.EmptyState
import dev.vitals.app.ui.Fmt
import dev.vitals.app.ui.Glyph
import dev.vitals.app.ui.GlyphIcon
import dev.vitals.app.ui.InfoRow
import dev.vitals.app.ui.Label
import dev.vitals.app.ui.LegendItem
import dev.vitals.app.ui.Palette
import dev.vitals.app.ui.ResourceTab
import dev.vitals.app.ui.Ring
import dev.vitals.app.ui.RowSplit
import dev.vitals.app.ui.Segmented
import dev.vitals.app.ui.SeriesData
import dev.vitals.app.ui.StackBar
import dev.vitals.app.ui.TextAction
import dev.vitals.app.ui.TopicTabs
import dev.vitals.app.ui.Type
import dev.vitals.app.ui.VitalsCard
import dev.vitals.app.ui.agoLabel
import dev.vitals.app.ui.batteryColor
import dev.vitals.app.ui.batteryPrimaryLine
import dev.vitals.app.ui.batteryStatusLabel
import dev.vitals.app.ui.batteryTone
import dev.vitals.app.ui.diskColor
import dev.vitals.app.ui.diskFraction
import dev.vitals.app.ui.heatColor
import dev.vitals.app.ui.plural
import dev.vitals.app.ui.worstDisk

/**
 * Recursos owns the host's resources, one topic at a time. The tab row avoids
 * a dashboard of unrelated cards while keeping CPU, memory, disks, network and
 * battery one tap apart.
 */
@Composable
fun ResourcesScreen(vm: AppViewModel) {
    val tabs = buildList {
        add(ResourceTab.Cpu)
        add(ResourceTab.Memory)
        add(ResourceTab.Storage)
        add(ResourceTab.Network)
        if (vm.sample?.power != null) add(ResourceTab.Energy)
    }
    LaunchedEffect(tabs) {
        if (vm.resourceTab !in tabs) vm.selectResourceTab(ResourceTab.Cpu)
    }
    Column(Modifier.fillMaxSize()) {
        TopicTabs(
            items = tabs,
            selected = vm.resourceTab,
            label = { it.label },
            onSelect = vm::selectResourceTab,
        )
        if (vm.sample == null) {
            ResourceUnavailable(vm)
        } else {
            when (vm.resourceTab) {
                ResourceTab.Cpu -> CpuTab(vm)
                ResourceTab.Memory -> MemoryTab(vm)
                ResourceTab.Storage -> StorageTab(vm)
                ResourceTab.Network -> NetworkTab(vm)
                ResourceTab.Energy -> EnergyTab(vm)
            }
        }
    }
}

@Composable
private fun ResourceUnavailable(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        VitalsCard {
            EmptyState(
                title = if (vm.bootError != null) "Sin datos del host" else "Conectando con el host",
                body = vm.bootError ?: "Esperando el primer dato de métricas.",
            )
            TextAction("Reintentar", modifier = Modifier.align(Alignment.CenterHorizontally)) {
                vm.reconnect()
            }
        }
    }
}

/* ----------------------------------- cpu ----------------------------------- */

@Composable
private fun CpuTab(vm: AppViewModel) {
    val sample = vm.sample
    val values = vm.series.cpu
    val peak = values.maxOrNull() ?: 0f
    val scale = ((peak * 1.25f).coerceAtLeast(25f)).coerceAtMost(100f)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            VitalsCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cpu = sample?.cpu?.total ?: 0f
                    Ring(
                        percent = cpu,
                        size = 84.dp,
                        color = heatColor(cpu),
                        label = "CPU al ${Fmt.whole(cpu)} por ciento",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Label("Ahora mismo")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("carga ${Fmt.load(sample?.cpu?.load1)}")
                            Chip(sample?.temps?.firstOrNull()?.let { "${Fmt.whole(it.celsius)} °C" } ?: "sin temperatura")
                        }
                        Text(
                            vm.host?.cpuModel?.ifBlank { "—" } ?: "—",
                            color = Palette.Muted,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (values.isEmpty()) {
                    Text("Recopilando las primeras muestras…", style = Type.caption)
                } else {
                    Chart(
                        listOf(SeriesData(values, Palette.Accent)),
                        modifier = Modifier.fillMaxWidth().height(132.dp),
                        capacity = dev.vitals.app.Series.MAX,
                        maxValue = scale,
                        grid = true,
                        description = "CPU, últimos 60 datos. Escala 0 a ${Fmt.whole(scale)} por ciento.",
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("mín ${Fmt.whole(values.min())} %", style = Type.caption)
                        Text("med ${Fmt.whole(values.average().toFloat())} %", style = Type.caption)
                        Text("máx ${Fmt.whole(values.max())} %", style = Type.caption)
                        Spacer(Modifier.weight(1f))
                        Text("escala 0–${Fmt.whole(scale)} %", style = Type.micro)
                    }
                }
            }
        }

        item {
            VitalsCard {
                CardHeader("Carga media", trailing = if (vm.host != null) "${vm.host?.cores ?: 0} núcleos" else null)
                Text(
                    "Procesos que esperan CPU, media de 1, 5 y 15 minutos.",
                    style = Type.caption,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LoadValue("1 min", sample?.cpu?.load1, Modifier.weight(1f))
                    LoadValue("5 min", sample?.cpu?.load5, Modifier.weight(1f))
                    LoadValue("15 min", sample?.cpu?.load15, Modifier.weight(1f))
                }
            }
        }

        item {
            VitalsCard {
                CardHeader("Núcleos", trailing = "${sample?.cpu?.cores?.size ?: 0}")
                val cores = sample?.cpu?.cores.orEmpty()
                if (cores.isEmpty()) {
                    EmptyState("Sin datos por núcleo", "El host todavía no ha enviado el desglose.")
                } else {
                    cores.chunked(2).forEachIndexed { row, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            pair.forEachIndexed { column, value ->
                                CoreRow(row * 2 + column, value, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            VitalsCard {
                CardHeader("Temperaturas")
                val temps = sample?.temps.orEmpty()
                if (temps.isEmpty()) {
                    Text("Este host no expone sensores de temperatura.", color = Palette.Muted, fontSize = 12.sp)
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

        item {
            ProcessesShortcut(vm, sort = "cpu", title = "Procesos por CPU", detail = "Consulta qué consume el procesador ahora mismo.")
        }
    }
}

@Composable
private fun LoadValue(label: String, value: Double?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = Type.micro)
        Text(Fmt.load(value), color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CoreRow(index: Int, value: Float, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("#$index", style = Type.micro)
            Text("${Fmt.whole(value)} %", style = Type.micro)
        }
        Bar(value / 100f, heatColor(value), height = 5.dp)
    }
}

/* --------------------------------- memory ---------------------------------- */

@Composable
private fun MemoryTab(vm: AppViewModel) {
    val sample = vm.sample
    val mem = sample?.mem
    val fraction = if ((mem?.total ?: 0L) > 0) (mem!!.used.toFloat() / mem.total) else 0f
    val available = mem?.available ?: 0L

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            VitalsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Label("RAM usada")
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${Fmt.whole(fraction * 100)}", style = Type.metric)
                            Text(" %", color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                        }
                    }
                    Text(
                        "${Fmt.bytes(mem?.used ?: 0L)} de ${Fmt.bytes(mem?.total ?: 0L)}",
                        style = Type.secondary,
                    )
                }
                StackBar(fraction, dev.vitals.app.ui.memoryColor(fraction))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LegendItem(dev.vitals.app.ui.memoryColor(fraction), Fmt.bytes(mem?.used ?: 0L), "usada")
                    LegendItem(Color(0xFF2B3A4A), Fmt.bytes((mem?.total ?: 0L) - (mem?.used ?: 0L)), "libre")
                }
                if (vm.series.mem.isEmpty()) {
                    Text("Recopilando las primeras muestras…", style = Type.caption)
                } else {
                    Chart(
                        listOf(SeriesData(vm.series.mem, Palette.Accent)),
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        capacity = dev.vitals.app.Series.MAX,
                        maxValue = 100f,
                        minValue = 10f,
                        description = "Memoria usada en los últimos 60 datos.",
                    )
                }
                RowSplit(
                    "disponible ${Fmt.bytes(available)}",
                    if ((mem?.swapTotal ?: 0L) > 0) {
                        "swap ${Fmt.bytes(mem?.swapUsed ?: 0L)} de ${Fmt.bytes(mem?.swapTotal ?: 0L)}"
                    } else {
                        "sin swap"
                    },
                )
                Text(
                    "«Libre» es RAM sin usar; «disponible» incluye la caché que el sistema puede recuperar.",
                    style = Type.micro,
                    lineHeight = 15.sp,
                )
            }
        }

        item {
            VitalsCard {
                CardHeader("Swap", trailing = if ((mem?.swapTotal ?: 0L) > 0) "activa" else "no configurada")
                if ((mem?.swapTotal ?: 0L) > 0) {
                    Text(
                        "Espacio en disco que el sistema usa como respaldo cuando la RAM se llena.",
                        style = Type.caption,
                    )
                    val swapFraction = mem!!.swapUsed.toFloat() / mem.swapTotal
                    Bar(swapFraction, if (swapFraction > 0.8f) Palette.Warn else Palette.Rx)
                    RowSplit(Fmt.bytes(mem.swapUsed), "de ${Fmt.bytes(mem.swapTotal)}")
                } else {
                    Text(
                        "Este host no usa swap, así que la memoria disponible depende sólo de la RAM.",
                        color = Palette.Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }

        item {
            ProcessesShortcut(vm, sort = "mem", title = "Procesos por memoria", detail = "Los que más RAM están usando en este momento.")
        }
    }
}

/* -------------------------------- storage ---------------------------------- */

@Composable
private fun StorageTab(vm: AppViewModel) {
    val sample = vm.sample
    val disks = sample?.disks.orEmpty()
    LaunchedEffect(Unit) { vm.loadAlertsQuiet() }
    val total = disks.sumOf { it.total }
    val used = disks.sumOf { it.used }
    val worst = worstDisk(disks)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (disks.isEmpty()) {
            item {
                VitalsCard {
                    EmptyState(
                        "Sin discos legibles",
                        "El agente no ha encontrado sistemas de archivos que pueda leer.",
                    )
                }
            }
        } else {
            item {
                VitalsCard {
                    CardHeader("Capacidad", trailing = plural(disks.size, "disco", "discos"))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Label("Usado")
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    if (total > 0) "${Fmt.whole(used.toFloat() / total * 100)}" else "—",
                                    style = Type.metric,
                                )
                                Text(" %", color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                            }
                        }
                        Text("${Fmt.bytes(used)} de ${Fmt.bytes(total)}", style = Type.secondary)
                    }
                    if (total > 0) Bar(used.toFloat() / total, diskColor(used.toFloat() / total))
                    worst?.let { disk ->
                        val free = disk.total - disk.used
                        BannerOrHint(disk, free)
                    }
                }
            }

            items(disks.size) { index ->
                DiskCard(disks[index], vm)
            }

            item {
                Text(
                    "El uso corresponde a cada sistema de archivos; la lectura y la escritura son su actividad instantánea.",
                    style = Type.micro,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BannerOrHint(disk: Disk, free: Long) {
    val fraction = diskFraction(disk)
    if (fraction >= 0.90f) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.Danger.copy(alpha = 0.10f))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${disk.mount} está al ${Fmt.whole(fraction * 100)} %. Quedan ${Fmt.bytes(free)}.",
                color = Palette.Danger,
                fontSize = 12.sp,
            )
        }
    } else {
        Text("Quedan ${Fmt.bytes(free)} libres en el disco más lleno (${disk.mount}).", style = Type.caption)
    }
}

@Composable
private fun DiskCard(disk: Disk, vm: AppViewModel) {
    val fraction = diskFraction(disk)
    val prediction = vm.alertReport?.predictions?.firstOrNull { it.mount == disk.mount }
    val io = disk.rBps + disk.wBps
    VitalsCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    disk.mount,
                    color = Palette.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(disk.fs, style = Type.micro, maxLines = 1)
            }
            Text("${Fmt.whole(fraction * 100)} %", style = Type.value)
        }
        Bar(fraction, diskColor(fraction), height = 6.dp)
        RowSplit(
            "${Fmt.bytes(disk.used)} usados",
            "${Fmt.bytes(disk.total - disk.used)} libres",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (io > 0) "lee ${Fmt.rate(disk.rBps)} · escribe ${Fmt.rate(disk.wBps)}" else "sin actividad",
                style = Type.micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (fraction >= 0.80f) {
                Text(
                    if (fraction >= 0.90f) "crítico" else "alto",
                    color = diskColor(fraction),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        prediction?.let {
            Text(
                "Quedan ≈ ${"%.1f".format(it.daysLeft)} días con el ritmo actual.",
                color = when {
                    it.daysLeft <= 3 -> Palette.Danger
                    it.daysLeft <= 7 -> Palette.Warn
                    else -> Palette.Muted
                },
                fontSize = 11.5.sp,
            )
        }
    }
}

/* --------------------------------- network --------------------------------- */

@Composable
private fun NetworkTab(vm: AppViewModel) {
    val sample = vm.sample
    val history = vm.history

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            VitalsCard {
                CardHeader(
                    "Tráfico",
                    trailingContent = {
                        Segmented(
                            options = listOf("live" to "En vivo", "1h" to "1 h", "6h" to "6 h"),
                            selected = vm.netRange,
                            onSelect = { vm.loadHistory(it) },
                        )
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BigRate(Palette.Rx, "Descarga", sample?.net?.rxBps ?: 0L)
                    BigRate(Palette.Tx, "Subida", sample?.net?.txBps ?: 0L)
                }
                val liveValues = if (vm.netRange == "live") vm.series.rx else emptyList()
                val hasData = if (vm.netRange == "live") liveValues.isNotEmpty() else history.isNotEmpty()
                if (!hasData) {
                    Text(
                        if (vm.netRange == "live") "Recopilando las primeras muestras…" else "Sin datos para este rango todavía.",
                        style = Type.caption,
                    )
                } else {
                    val values = if (vm.netRange == "live") {
                        listOf(
                            SeriesData(vm.series.rx, Palette.Rx),
                            SeriesData(vm.series.tx, Palette.Tx, fill = false),
                        )
                    } else {
                        listOf(
                            SeriesData(history.map { it.netRx.toFloat() }, Palette.Rx),
                            SeriesData(history.map { it.netTx.toFloat() }, Palette.Tx, fill = false),
                        )
                    }
                    Chart(
                        values,
                        modifier = Modifier.fillMaxWidth().height(176.dp),
                        capacity = if (vm.netRange == "live") dev.vitals.app.Series.MAX else history.size.coerceAtLeast(2),
                        grid = true,
                        description = "Tráfico de red, ${if (vm.netRange == "live") "en vivo" else "últimas ${if (vm.netRange == "1h") "1 h" else "6 h"}"}.",
                    )
                }
            }
        }

        item {
            VitalsCard {
                CardHeader("Interfaces", trailing = "${sample?.net?.ifaces?.size ?: 0}")
                val ifaces = sample?.net?.ifaces.orEmpty()
                if (ifaces.isEmpty()) {
                    EmptyState("Sin interfaces activas", "El host no reporta interfaces de red con tráfico.")
                } else {
                    ifaces.forEach { iface ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Palette.Surface2)
                                .border(1.dp, Palette.Line, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
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
                                "↓ ${Fmt.rate(iface.rxBps)}  ↑ ${Fmt.rate(iface.txBps)}",
                                color = Palette.Muted,
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                }
            }
        }

        item {
            VitalsCard(padding = 14.dp) {
                Text("Conexión y direcciones", color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "El modo de conexión, las direcciones del host y la latencia se gestionan en Más › Conexión.",
                    style = Type.caption,
                )
                TextAction("Abrir conexión") { vm.navigate(dev.vitals.app.ui.Screen.Connection) }
            }
        }
    }
}

@Composable
private fun BigRate(color: Color, label: String, bytesPerSec: Long) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column {
            Text(label, style = Type.micro)
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier
                        .padding(bottom = 4.dp)
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(color),
                )
                Spacer(Modifier.width(6.dp))
                Text(Fmt.bytes(bytesPerSec), color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("/s", color = Palette.Dim, fontSize = 11.sp)
            }
        }
    }
}

/* --------------------------------- energy ---------------------------------- */

@Composable
private fun EnergyTab(vm: AppViewModel) {
    val power = vm.sample?.power
    if (power == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            VitalsCard {
                EmptyState(
                    "Este host no tiene batería",
                    "La pestaña Energía sólo aparece en portátiles con batería reportada por el agente.",
                )
            }
        }
        return
    }
    val history = vm.powerHistory
    val battery = remember(history) { batterySeries(history) }
    val showChart = battery.size >= 3 && battery.distinct().size > 1

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            VitalsCard {
                CardHeader(
                    "Batería",
                    trailing = when {
                        power.acOnline -> "con cable"
                        power.onBattery -> "sin cable"
                        else -> "sin datos"
                    },
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    power.percent?.let { percent ->
                        Ring(
                            percent = percent,
                            size = 96.dp,
                            color = batteryColor(power),
                            label = "Batería al ${Fmt.whole(percent)} por ciento",
                        )
                    } ?: Box(
                        Modifier.size(96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Sin dato", style = Type.caption, textAlign = TextAlign.Center)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(batteryStatusLabel(power.status), batteryTone(power))
                        }
                        Text(batteryPrimaryLine(power), color = Palette.Text, fontSize = 12.5.sp)
                        buildString {
                            power.powerW?.let { append(Fmt.watts(it)) }
                            power.healthPct?.let {
                                if (isNotEmpty()) append(" · ")
                                append("salud ${Fmt.whole(it)} %")
                            }
                            power.cycles?.let {
                                if (isNotEmpty()) append(" · ")
                                append("$it ciclos")
                            }
                        }.takeIf { it.isNotEmpty() }?.let {
                            Text(it, color = Palette.Muted, fontSize = 11.5.sp)
                        }
                    }
                }
                if (showChart) {
                    Chart(
                        listOf(SeriesData(battery, batteryColor(power))),
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        capacity = dev.vitals.app.Series.MAX,
                        maxValue = 100f,
                        minValue = 20f,
                        description = "Batería del host, últimas 24 horas.",
                    )
                    RowSplit("batería · ${historyLabel(history)}", power.batteries.firstOrNull()?.model ?: "—")
                } else {
                    Text("Sin historial de batería todavía.", style = Type.caption)
                }
            }
        }

        item {
            VitalsCard {
                CardHeader(
                    "Estado del portátil",
                    trailing = when {
                        power.acOnline -> "con cable"
                        power.onBattery -> "sin cable"
                        else -> "sin datos"
                    },
                )
                InfoRow(
                    "Alimentación",
                    when {
                        power.acOnline -> "Cable"
                        power.onBattery -> "Batería"
                        else -> "Sin datos"
                    },
                )
                InfoRow("Carga", batteryStatusLabel(power.status))
                power.secondsLeft?.let { InfoRow("Autonomía estimada", Fmt.duration(it)) }
                power.secondsToFull?.let { InfoRow("Hasta el 100 %", Fmt.duration(it)) }
                power.powerW?.let { InfoRow("Consumo", Fmt.watts(it)) }
                power.healthPct?.let { InfoRow("Salud de la batería", "${Fmt.whole(it)} %") }
                power.cycles?.let { InfoRow("Ciclos de carga", "$it") }
                if (power.healthPct != null || power.cycles != null) {
                    Text(
                        "La salud es la capacidad que conserva la batería frente a una nueva; " +
                            "los ciclos son cargas completas acumuladas.",
                        style = Type.micro,
                        lineHeight = 15.sp,
                    )
                }
            }
        }

        if (power.batteries.size > 1) {
            item {
                VitalsCard {
                    CardHeader("Baterías", trailing = "${power.batteries.size}")
                    power.batteries.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RowSplit(
                                item.name,
                                "${item.percent?.let { "${Fmt.whole(it)} %" } ?: "—"} · ${batteryStatusLabel(item.status)}",
                            )
                            if (item.percent != null) Bar(item.percent!! / 100f, batteryColor(power), height = 4.dp)
                            buildList {
                                item.model?.let(::add)
                                item.technology?.let(::add)
                                item.cycles?.let { add("$it ciclos") }
                            }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = Type.micro)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Datos de la batería del host. La app no lee la batería del teléfono.",
                style = Type.micro,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun batterySeries(points: List<Point>): List<Float> {
    val values = points.mapNotNull { it.batteryPct }
    if (values.size <= dev.vitals.app.Series.MAX) return values
    val step = values.size / dev.vitals.app.Series.MAX.toFloat()
    return (0 until dev.vitals.app.Series.MAX).map { index ->
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

/* --------------------------------- shared ---------------------------------- */

@Composable
private fun ProcessesShortcut(vm: AppViewModel, sort: String, title: String, detail: String) {
    VitalsCard(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) { vm.openProcesses(sort) },
        padding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(detail, style = Type.caption, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
            GlyphIcon(Glyph.Chevron, Palette.Dim, 18.dp)
        }
    }
}
