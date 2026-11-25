package dev.vitals.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vitals.app.AppViewModel
import dev.vitals.app.core.AlertRule
import dev.vitals.app.core.Disk
import dev.vitals.app.core.Power
import dev.vitals.app.ui.AccessTab
import dev.vitals.app.ui.Banner
import dev.vitals.app.ui.Bar
import dev.vitals.app.ui.CardHeader
import dev.vitals.app.ui.Chip
import dev.vitals.app.ui.ChipTone
import dev.vitals.app.ui.DividerLine
import dev.vitals.app.ui.Dot
import dev.vitals.app.ui.EmptyState
import dev.vitals.app.ui.Fmt
import dev.vitals.app.ui.Glyph
import dev.vitals.app.ui.GlyphIcon
import dev.vitals.app.ui.Palette
import dev.vitals.app.ui.ResourceTab
import dev.vitals.app.ui.Screen
import dev.vitals.app.ui.TextAction
import dev.vitals.app.ui.Type
import dev.vitals.app.ui.VitalsCard
import dev.vitals.app.ui.agoLabel
import dev.vitals.app.ui.batteryColor
import dev.vitals.app.ui.batteryPrimaryLine
import dev.vitals.app.ui.batteryStatusLabel
import dev.vitals.app.ui.batteryTone
import dev.vitals.app.ui.diskColor
import dev.vitals.app.ui.diskFraction
import dev.vitals.app.ui.memoryColor
import dev.vitals.app.ui.plural
import dev.vitals.app.ui.ruleDetail
import dev.vitals.app.ui.ruleTitle
import dev.vitals.app.ui.sessionCountLabel
import dev.vitals.app.ui.worstDisk
import dev.vitals.app.watchdog.WatchStatus

/**
 * Resumen answers one question first: does anything need me right now?
 * Detail graphs and full lists live in their own topic, never here.
 */
@Composable
fun OverviewScreen(vm: AppViewModel) {
    val sample = vm.sample
    LaunchedEffect(Unit) { vm.loadAlertsQuiet() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vm.watchState.status == WatchStatus.Down) {
            item {
                Banner(
                    text = "El host no responde desde ${vm.watchState.since?.let(::agoLabel) ?: "hace unos segundos"}.",
                    tone = Palette.Danger,
                    action = { TextAction("Reconectar") { vm.reconnect() } },
                )
            }
        }

        if (sample == null) {
            item {
                VitalsCard {
                    if (vm.bootError != null) {
                        EmptyState(
                            title = "Sin conexión con el host",
                            body = vm.bootError.orEmpty(),
                        )
                        TextAction("Reintentar", modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            vm.reconnect()
                        }
                    } else {
                        EmptyState(
                            title = "Conectando con el host",
                            body = "Esperando el primer dato de métricas.",
                        )
                    }
                }
            }
        } else {
            val critical = overviewCritical(vm)
            if (critical.isNotEmpty()) {
                item {
                    AttentionCard(vm, critical)
                }
            }
            item { StatusCard(vm) }
            sample.power?.let { power ->
                item { EnergyRow(power) { vm.openResource(ResourceTab.Energy) } }
            }
            item { AccessRow(vm) }
            item {
                Text(
                    "Encendido hace ${Fmt.uptime(sample.uptime)} · actualizado ${agoLabel(sample.t)}",
                    style = Type.micro,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
}

/* ------------------------------- attention -------------------------------- */

private data class Attention(
    val title: String,
    val detail: String,
    val tone: Color,
    val target: Screen,
)

private fun overviewCritical(vm: AppViewModel): List<Attention> {
    val sample = vm.sample ?: return emptyList()
    val rules = vm.alertReport?.rules?.filter { it.firing }.orEmpty()
    val coveredMounts = rules.filter { it.kind == "disk" }.mapNotNull { it.mount }.toSet()
    val result = buildList {
        rules.forEach { rule ->
            val prediction = vm.alertReport?.predictions
                ?.firstOrNull { it.mount == rule.mount && rule.kind == "disk" }
            add(
                Attention(
                    title = ruleTitle(rule),
                    detail = buildString {
                        append(firingDetail(vm, rule))
                        prediction?.let { append(" Quedan ≈ ${"%.1f".format(it.daysLeft)} días.") }
                    },
                    tone = Palette.Danger,
                    target = Screen.Alerts,
                ),
            )
        }
        sample.disks
            .filter { diskFraction(it) >= 0.90f && it.mount !in coveredMounts }
            .forEach { disk ->
                add(
                    Attention(
                        title = "Disco ${disk.mount}",
                        detail = "${Fmt.whole(diskFraction(disk) * 100)} % usado · ${Fmt.bytes(disk.total - disk.used)} libres",
                        tone = Palette.Danger,
                        target = Screen.Resources,
                    ),
                )
            }
        sample.temps.filter { it.celsius >= 85f }.forEach { temp ->
            add(
                Attention(
                    title = temp.label,
                    detail = "${Fmt.whole(temp.celsius)} °C · por encima de 85 °C",
                    tone = Palette.Warn,
                    target = Screen.Alerts,
                ),
            )
        }
    }
    return result
}

/** A firing rule is interesting for its current value, not for its condition. */
private fun firingDetail(vm: AppViewModel, rule: AlertRule): String {
    val sample = vm.sample
    return when (rule.kind) {
        "disk" -> sample?.disks?.firstOrNull { it.mount == rule.mount }?.let { disk ->
            "${Fmt.whole(diskFraction(disk) * 100)} % usado · ${Fmt.bytes(disk.total - disk.used)} libres"
        } ?: ruleDetail(rule)
        "memory" -> sample?.mem?.takeIf { it.total > 0 }?.let { mem ->
            "${Fmt.whole(mem.used.toFloat() / mem.total * 100)} % de RAM usada"
        } ?: ruleDetail(rule)
        "temperature" -> sample?.temps?.maxByOrNull { it.celsius }?.let { temp ->
            "${temp.label} a ${Fmt.whole(temp.celsius)} °C"
        } ?: ruleDetail(rule)
        else -> ruleDetail(rule)
    }
}

@Composable
private fun AttentionCard(vm: AppViewModel, items: List<Attention>) {
    VitalsCard(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) {
                if (items.any { it.target == Screen.Alerts }) {
                    vm.navigate(Screen.Alerts)
                } else {
                    vm.openResource(ResourceTab.Storage)
                }
            },
        padding = 14.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(Palette.Danger)
                Text(
                    if (items.size == 1) "Requiere atención" else "${items.size} requieren atención",
                    color = Palette.Text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text("Ver", color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        items.take(4).forEach { item ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Dot(item.tone, 6.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, color = Palette.Text, fontSize = 13.sp)
                    Text(item.detail, color = Palette.Muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                }
            }
        }
        if (items.size > 4) {
            Text("+${items.size - 4} más", style = Type.micro)
        }
    }
}

/* --------------------------------- status --------------------------------- */

@Composable
private fun StatusCard(vm: AppViewModel) {
    val sample = vm.sample ?: return
    val cpu = sample.cpu.total
    val memFraction = if (sample.mem.total > 0) sample.mem.used.toFloat() / sample.mem.total else 0f
    val disk = worstDisk(sample.disks)
    val temp = sample.temps.firstOrNull()?.celsius
    val firing = vm.alertReport?.rules?.count { it.firing } ?: 0

    VitalsCard {
        CardHeader(
            "Estado del host",
            trailing = when {
                firing > 0 -> plural(firing, "alerta activa", "alertas activas")
                sample.disks.any { diskFraction(it) >= 0.90f } -> "disco casi lleno"
                else -> "sin alertas"
            },
        )
        StatusRow(
            label = "CPU",
            value = "${Fmt.whole(cpu)} %",
            detail = "carga ${Fmt.load(sample.cpu.load1)}" + (temp?.let { " · ${Fmt.whole(it)} °C" } ?: ""),
            fraction = cpu / 100f,
            color = heatColorFor(cpu),
            onClick = { vm.openResource(ResourceTab.Cpu) },
        )
        DividerLine()
        StatusRow(
            label = "Memoria",
            value = "${Fmt.whole(memFraction * 100)} %",
            detail = "${Fmt.bytes(sample.mem.used)} de ${Fmt.bytes(sample.mem.total)}",
            fraction = memFraction,
            color = memoryColor(memFraction),
            onClick = { vm.openResource(ResourceTab.Memory) },
        )
        DividerLine()
        StatusRow(
            label = "Disco",
            value = disk?.let { "${Fmt.whole(diskFraction(it) * 100)} %" } ?: "—",
            detail = disk?.let {
                "${it.mount} · ${Fmt.bytes(it.total - it.used)} libres"
            } ?: "sin discos legibles",
            fraction = disk?.let(::diskFraction) ?: 0f,
            color = disk?.let { diskColor(diskFraction(it)) } ?: Palette.Dim,
            onClick = { vm.openResource(ResourceTab.Storage) },
        )
        DividerLine()
        StatusRow(
            label = "Red",
            value = Fmt.rate(sample.net.rxBps + sample.net.txBps),
            detail = "↓ ${Fmt.rate(sample.net.rxBps)} · ↑ ${Fmt.rate(sample.net.txBps)}",
            fraction = null,
            color = Palette.Rx,
            onClick = { vm.openResource(ResourceTab.Network) },
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    detail: String,
    fraction: Float?,
    color: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                detail,
                style = Type.caption,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction != null && fraction >= 0.80f) {
                Text(
                    if (fraction >= 0.90f) "crítico" else "alto",
                    color = color,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (fraction != null) Bar(fraction, color, height = 4.dp)
    }
}

private fun heatColorFor(percent: Float): Color = when {
    percent >= 85f -> Palette.Tx
    percent >= 60f -> Palette.Warn
    else -> Palette.Accent
}

/* --------------------------------- energy --------------------------------- */

@Composable
private fun EnergyRow(power: Power, onClick: () -> Unit) {
    VitalsCard(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        CardHeader(
            "Energía",
            trailing = when {
                power.acOnline -> "con cable"
                power.onBattery -> "en batería"
                else -> "sin datos"
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Battery, batteryColor(power), 24.dp, contentDescription = "Batería")
            Spacer(Modifier.width(10.dp))
            Text(power.percent?.let { "${Fmt.whole(it)} %" } ?: "—", style = Type.value)
            Spacer(Modifier.width(10.dp))
            Text(
                batteryPrimaryLine(power),
                color = Palette.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        power.percent?.let { Bar(it / 100f, batteryColor(power)) }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(batteryStatusLabel(power.status), batteryTone(power))
            power.powerW?.let { Chip(Fmt.watts(it)) }
            power.healthPct?.let { Chip("salud ${Fmt.whole(it)} %") }
            power.cycles?.let { Chip("$it ciclos") }
        }
    }
}

/* --------------------------------- access --------------------------------- */

@Composable
private fun AccessRow(vm: AppViewModel) {
    val access = vm.sample?.access ?: return
    val hasLogs = access.source != "none"
    val failures = access.failures24h
    val sshTone = sshTone(access.passwordAuth, access.rootLogin, access.configRead)

    VitalsCard(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) { vm.openAccess(AccessTab.Now) },
    ) {
        CardHeader("Accesos", trailing = sessionCountLabel(access.sessions.size))
        AccessDotLine(
            color = if (!hasLogs) Palette.Dim else if (failures == 0) Palette.Accent else Palette.Warn,
            text = when {
                !hasLogs -> "Registros de acceso no disponibles"
                failures == 0 -> "Ningún intento fallido en 24 h"
                else -> plural(failures, "intento fallido", "intentos fallidos") + " en 24 h"
            },
        )
        AccessDotLine(
            color = sshTone,
            text = when {
                !access.configRead -> "Configuración de SSH no legible"
                access.rootLogin.equals("yes", ignoreCase = true) -> "SSH permite entrar como root"
                access.passwordAuth -> "SSH acepta contraseña"
                else -> "SSH: sólo con clave"
            },
        )
        access.sessions.firstOrNull()?.let { session ->
            Text(
                "Conectado ahora: ${session.user}" + (session.host?.takeIf { session.remote }?.let { " desde $it" } ?: ""),
                color = Palette.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        access.lastLogin?.let { last ->
            Text(
                "Último acceso: ${last.user} desde ${last.host} · ${agoLabel(last.t)}",
                style = Type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextAction("Ver accesos") { vm.openAccess(AccessTab.Now) }
    }
}

@Composable
private fun AccessDotLine(color: Color, text: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color, 6.dp)
        Text(text, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun sshTone(passwordAuth: Boolean, rootLogin: String, readable: Boolean): Color = when {
    !readable -> Palette.Dim
    rootLogin.equals("yes", ignoreCase = true) -> Palette.Tx
    passwordAuth -> Palette.Warn
    else -> Palette.Accent
}


