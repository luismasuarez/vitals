package dev.vitals.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.vitals.app.AppViewModel
import dev.vitals.app.core.AlertEvent
import dev.vitals.app.core.AlertRule
import dev.vitals.app.ui.Banner
import dev.vitals.app.ui.CardHeader
import dev.vitals.app.ui.Chip
import dev.vitals.app.ui.ChipTone
import dev.vitals.app.ui.Dot
import dev.vitals.app.ui.EmptyState
import dev.vitals.app.ui.Fmt
import dev.vitals.app.ui.Label
import dev.vitals.app.ui.Palette
import dev.vitals.app.ui.TextAction
import dev.vitals.app.ui.Type
import dev.vitals.app.ui.VitalsCard
import dev.vitals.app.ui.agoLabel
import dev.vitals.app.ui.clockLabel
import dev.vitals.app.ui.diskFraction
import dev.vitals.app.ui.plural
import dev.vitals.app.ui.ruleDetail
import dev.vitals.app.ui.ruleTitle
import dev.vitals.app.watchdog.WatchStatus

@Composable
fun AlertsScreen(vm: AppViewModel) {
    var pendingDelete by remember { mutableStateOf<AlertRule?>(null) }
    var silenceMinutes by remember { mutableStateOf<Int?>(null) }
    var showAllHistory by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (vm.alertReport == null) vm.loadAlerts() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vm.isOperator) {
            vm.alertsError?.let { error ->
                item { Banner(error, Palette.Danger) }
            }
        }

        if (!vm.isOperator) {
            item { ReaderGate(vm) }
        } else if (vm.alertReport == null) {
            item {
                VitalsCard {
                    if (vm.alertsBusy || vm.alertsError == null) {
                        EmptyState("Leyendo alertas", "Reglas, avisos e historial del host…")
                    } else {
                        EmptyState("Sin datos de alertas", "El agente no ha respondido todavía. Vuelve a intentarlo.")
                        TextAction("Reintentar", modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            vm.loadAlerts()
                        }
                    }
                }
            }
        } else {
            item { AlertsSummary(vm) }
            item { LocalRiskCard(vm) }
            item { RulesSection(vm, onDelete = { pendingDelete = it }) }
            item { SilenceSection(vm, enabled = !vm.alertReport?.rules.isNullOrEmpty()) { silenceMinutes = it } }
        }

        item { WatchdogCard(vm) }

        if (vm.isOperator && vm.alertReport != null) {
            item { HistorySection(vm, showAllHistory) { showAllHistory = !showAllHistory } }
        }

        if (vm.alertsBusy && vm.alertReport != null) {
            item { Text("Actualizando alertas…", style = Type.caption) }
        }
        item {
            Text(
                if (vm.isOperator) {
                    "Las reglas se evalúan en el host cada 5 segundos. No dependen de que la app esté abierta."
                } else {
                    "La vigilancia de caída funciona con cualquier token; las reglas necesitan un token de control total."
                },
                style = Type.micro,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Quitar ${ruleTitle(rule).lowercase()}") },
            text = { Text("Se eliminará esta regla y dejarás de recibir sus avisos en este host.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAlert(rule.id)
                    pendingDelete = null
                }) { Text("Quitar regla", color = Palette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            },
        )
    }

    silenceMinutes?.let { minutes ->
        AlertDialog(
            onDismissRequest = { silenceMinutes = null },
            title = { Text("Silenciar todos los avisos") },
            text = {
                Text(
                    "No se generarán avisos durante ${if (minutes < 60) "$minutes min" else "${minutes / 60} h"}. " +
                        "Después se reevalúa y, si el problema sigue, avisa una vez.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.silenceAlerts(minutes)
                    silenceMinutes = null
                }) { Text("Silenciar") }
            },
            dismissButton = {
                TextButton(onClick = { silenceMinutes = null }) { Text("Cancelar") }
            },
        )
    }
}

/* ---------------------------------- gate ----------------------------------- */

@Composable
private fun ReaderGate(vm: AppViewModel) {
    VitalsCard {
        CardHeader("Sólo lectura")
        Text(
            "Este dispositivo tiene un token de sólo lectura (reader): puede ver métricas, accesos y ajustes, " +
                "pero no crear reglas.",
            color = Palette.Text,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Text(
            "Para configurar alertas, genera un token de control total (operator) en el host, " +
                "olvida este host en Ajustes y escanea el QR nuevo.",
            style = Type.secondary,
            lineHeight = 18.sp,
        )
        Text(
            "vitals pair --operator",
            color = Palette.Accent,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(CircleShape)
                .background(Palette.Surface3)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        TextAction("Abrir ajustes", color = Palette.Warn) { vm.navigate(dev.vitals.app.ui.Screen.Settings) }
    }
}

/* --------------------------------- summary --------------------------------- */

@Composable
private fun AlertsSummary(vm: AppViewModel) {
    val rules = vm.alertReport?.rules.orEmpty()
    val firing = rules.filter { it.firing }
    val silenced = rules.any { it.silenceUntil * 1000 > System.currentTimeMillis() }

    val sample = vm.sample
    val uncovered = sample?.disks.orEmpty().count { disk ->
        diskFraction(disk) >= 0.90f && rules.none { it.kind == "disk" && it.mount == disk.mount }
    } + sample?.temps.orEmpty().count { temp ->
        temp.celsius >= 85f && rules.none { it.kind == "temperature" }
    }

    VitalsCard {
        CardHeader(
            "Reglas",
            trailing = when {
                firing.size == 1 -> "1 disparada"
                firing.isNotEmpty() -> "${firing.size} disparadas"
                silenced -> "avisos silenciados"
                else -> "ninguna disparada"
            },
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (firing.isEmpty()) Palette.Accent else Palette.Danger)
            Text(
                when {
                    firing.isNotEmpty() && firing.size == 1 -> "Hay una regla disparada. Revisa la lista de abajo."
                    firing.isNotEmpty() -> "Hay ${firing.size} reglas disparadas. Revisa la lista de abajo."
                    uncovered > 0 ->
                        "Ninguna regla está disparada, pero hay $uncovered " +
                            (if (uncovered == 1) "situación de riesgo" else "situaciones de riesgo") +
                            " sin regla de aviso."
                    else -> "Ninguna regla está disparada ahora mismo."
                },
                color = if (firing.isEmpty()) Palette.Muted else Palette.Text,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
        }
        if (firing.isNotEmpty()) {
            firing.take(3).forEach { rule ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(Palette.Danger, 6.dp)
                    Column(Modifier.weight(1f)) {
                        Text(ruleTitle(rule), color = Palette.Text, fontSize = 12.5.sp)
                        Text(ruleDetail(rule), style = Type.micro)
                    }
                    rule.since?.let { Text("desde ${agoLabel(it)}", style = Type.micro) }
                }
            }
        }
        if (silenced) {
            val until = rules.maxOfOrNull { it.silenceUntil } ?: 0L
            Text("Avisos silenciados hasta las ${clockLabel(until)}.", style = Type.caption)
        }
    }
}

/* -------------------------------- local risk ------------------------------- */

@Composable
private fun LocalRiskCard(vm: AppViewModel) {
    val sample = vm.sample ?: return
    val rules = vm.alertReport?.rules.orEmpty()
    val uncovered = sample.disks.filter { disk ->
        diskFraction(disk) >= 0.90f && rules.none { it.kind == "disk" && it.mount == disk.mount }
    }
    val hot = sample.temps.filter { temp ->
        temp.celsius >= 85f && rules.none { it.kind == "temperature" }
    }
    if (uncovered.isEmpty() && hot.isEmpty()) return

    VitalsCard(padding = 14.dp) {
        CardHeader("Riesgos sin aviso", trailing = "ahora")
        uncovered.forEach { disk ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(Palette.Danger, 6.dp)
                Column(Modifier.weight(1f)) {
                    Text("Disco ${disk.mount}", color = Palette.Text, fontSize = 12.5.sp)
                    Text(
                        "${Fmt.whole(diskFraction(disk) * 100)} % usado · ${Fmt.bytes(disk.total - disk.used)} libres",
                        style = Type.micro,
                    )
                }
                TextAction("Añadir regla") { vm.applyDiskAlert(disk.mount) }
            }
        }
        hot.forEach { temp ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(Palette.Warn, 6.dp)
                Text(
                    "${temp.label} a ${Fmt.whole(temp.celsius)} °C",
                    color = Palette.Text,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f),
                )
                TextAction("Añadir regla") { vm.applyAlertTemplate("temperature") }
            }
        }
        Text(
            "Estas situaciones se ven en Resumen, pero ninguna regla avisará si cierras la app.",
            style = Type.micro,
            lineHeight = 15.sp,
        )
    }
}

/* ---------------------------------- rules ---------------------------------- */

@Composable
private fun RulesSection(vm: AppViewModel, onDelete: (AlertRule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Reglas activas")
        val rules = vm.alertReport?.rules.orEmpty()
        if (rules.isEmpty()) {
            VitalsCard {
                EmptyState(
                    "Sin reglas",
                    "Añade una plantilla para que el host avise aunque la app esté cerrada.",
                )
            }
        } else {
            rules.forEach { rule ->
                RuleCard(rule, vm, onDelete)
            }
        }

        Label("Añadir regla")
        val disks = vm.sample?.disks.orEmpty()
        if (disks.isEmpty()) {
            TemplateRow(
                title = "Disco /",
                detail = "Usado por encima del 85 % o menos de 7 días restantes.",
                active = rules.any { it.kind == "disk" && it.mount == "/" },
            ) { vm.applyDiskAlert("/") }
        } else {
            disks.forEach { disk ->
                val active = rules.any { it.kind == "disk" && it.mount == disk.mount }
                TemplateRow(
                    title = "Disco ${disk.mount}",
                    detail = "Usado por encima del 85 % o menos de 7 días restantes.",
                    active = active,
                ) { vm.applyDiskAlert(disk.mount) }
            }
        }
        TemplateRow(
            title = "Memoria",
            detail = "RAM usada por encima del 90 %.",
            active = rules.any { it.id == "memory" },
        ) { vm.applyAlertTemplate("memory") }
        TemplateRow(
            title = "Temperatura",
            detail = "Cualquier sensor por encima de 85 °C.",
            active = rules.any { it.id == "temperature" },
        ) { vm.applyAlertTemplate("temperature") }
    }
}

@Composable
private fun RuleCard(rule: AlertRule, vm: AppViewModel, onDelete: (AlertRule) -> Unit) {
    val prediction = vm.alertReport?.predictions?.firstOrNull { it.mount == rule.mount && rule.kind == "disk" }
    VitalsCard(padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ruleTitle(rule), color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(ruleDetail(rule), style = Type.caption, lineHeight = 16.sp)
                if (prediction != null) {
                    Text(
                        "Con el ritmo actual, quedan ≈ ${"%.1f".format(prediction.daysLeft)} días.",
                        color = if (prediction.daysLeft <= 7) Palette.Warn else Palette.Muted,
                        fontSize = 11.sp,
                    )
                }
                if (rule.firing) {
                    rule.since?.let { Text("Disparada desde ${agoLabel(it)}.", color = Palette.Warn, fontSize = 11.sp) }
                }
            }
            Chip(
                if (rule.firing) "disparada" else "en orden",
                if (rule.firing) ChipTone.Danger else ChipTone.Accent,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rule.silenceUntil * 1000 > System.currentTimeMillis()) {
                Chip("silenciada hasta ${clockLabel(rule.silenceUntil)}", ChipTone.Neutral)
            }
            Spacer(Modifier.weight(1f))
            TextAction("Quitar regla", color = Palette.Danger) { onDelete(rule) }
        }
    }
}

@Composable
private fun TemplateRow(title: String, detail: String, active: Boolean, onAdd: () -> Unit) {
    VitalsCard(padding = 12.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(detail, style = Type.caption, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            if (active) {
                Chip("activa", ChipTone.Accent)
            } else {
                TextAction("Añadir") { onAdd() }
            }
        }
    }
}

/* --------------------------------- silence --------------------------------- */

@Composable
private fun SilenceSection(vm: AppViewModel, enabled: Boolean, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Silenciar")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 480, 1440).forEach { minutes ->
                val label = if (minutes < 60) "$minutes min" else "${minutes / 60} h"
                Text(
                    label,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .background(Palette.Surface2)
                        .border(1.dp, Palette.LineStrong, CircleShape)
                        .clickable(enabled = enabled, role = Role.Button) { onPick(minutes) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (enabled) Palette.Text else Palette.Dim,
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            "Silenciar detiene los avisos durante el tiempo elegido; después se reevalúa el estado.",
            style = Type.micro,
            lineHeight = 15.sp,
        )
        if (!enabled) {
            Text("Necesitas al menos una regla activa para silenciar avisos.", style = Type.micro, lineHeight = 15.sp)
        }
    }
}

/* -------------------------------- watchdog --------------------------------- */

@Composable
private fun WatchdogCard(vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.setWatchdog(true) else vm.toast = "Sin permiso de notificaciones no puedo avisarte"
    }

    VitalsCard {
        CardHeader("Vigilancia de caída", trailing = if (vm.watching) "activa" else "apagada")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Vigilar este host", color = Palette.Text, fontSize = 13.sp)
                Text(
                    watchStatusText(vm),
                    color = if (vm.watchState.status == WatchStatus.Down) Palette.Warn else Palette.Muted,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = vm.watching,
                onCheckedChange = { enabled ->
                    when {
                        !enabled -> vm.setWatchdog(false)
                        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED -> permission.launch(
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                        else -> vm.setWatchdog(true)
                    }
                },
            )
        }
        Text(
            "Mantiene la conexión abierta y avisa si el host deja de responder en unos 30 segundos. " +
                "Para que el sistema no la detenga, excluye vitals de la optimización de batería.",
            style = Type.secondary,
            lineHeight = 18.sp,
        )
    }
}

private fun watchStatusText(vm: AppViewModel): String {
    if (!vm.watching) return "Apagada · sólo se consulta al abrir la app"
    val state = vm.watchState
    return when (state.status) {
        WatchStatus.Off -> "Activando…"
        WatchStatus.Connecting -> "Conectando con el host…"
        WatchStatus.Watching -> state.lastEventAt?.let { "Vigilando · último dato ${agoLabel(it / 1000)}" }
            ?: "Vigilando · esperando el primer dato"
        WatchStatus.Down -> state.since?.let { "Sin respuesta desde ${agoLabel(it / 1000)}" } ?: "Sin respuesta"
    }
}

/* --------------------------------- history --------------------------------- */

@Composable
private fun HistorySection(vm: AppViewModel, showAll: Boolean, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Historial de avisos · 30 días")
        VitalsCard {
            val events = vm.alertReport?.events.orEmpty()
            if (events.isEmpty()) {
                EmptyState("Sin eventos todavía", "Cuando una regla se dispare o se recupere, aparecerá aquí.")
            } else {
                val titles = vm.alertReport?.rules.orEmpty().associate { it.id to ruleTitle(it) }
                events.take(if (showAll) events.size else 12).forEach { event ->
                    EventRow(event, titles[event.ruleId] ?: event.ruleId)
                }
                if (events.size > 12) {
                    TextAction(if (showAll) "Mostrar menos" else "Ver todos (${events.size})") { onToggle() }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: AlertEvent, title: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(stateColor(event.state))
            Text(title, color = Palette.Text, fontSize = 12.sp)
        }
        Text("${stateLabel(event.state)} · ${agoLabel(event.at)}", style = Type.micro)
    }
}

private fun stateLabel(state: String) = when (state) {
    "fired" -> "disparada"
    "recovered" -> "recuperada"
    "silenced" -> "silenciada"
    "delivered" -> "aviso enviado"
    else -> state
}

private fun stateColor(state: String) = when (state) {
    "fired" -> Palette.Warn
    "recovered" -> Palette.Accent
    "silenced" -> Palette.Muted
    else -> Palette.Dim
}
