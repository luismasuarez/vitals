package dev.vitals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vitals.app.AppViewModel
import dev.vitals.app.core.AlertRule
import dev.vitals.app.core.Capability

/* ------------------------------- shared header ------------------------------ */

@Composable
private fun ManagementHeader(title: String, vm: AppViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Volver",
            color = Palette.Accent,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { vm.selectScreen(Screen.Home) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun OperatorOnly() {
    Text(
        "Esta pantalla necesita un token operator. En el host: vitals pair --operator, " +
            "y escanea el QR nuevo desde Ajustes → Olvidar host.",
        color = Palette.Warn,
        fontSize = 12.sp,
    )
}

/* ----------------------------------- rules ---------------------------------- */

@Composable
fun RulesScreen(vm: AppViewModel) {
    var dialogMinutes by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        if (vm.alertReport == null) vm.loadAlerts()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ManagementHeader("Reglas de alerta", vm)
        if (!vm.isOperator) {
            OperatorOnly()
            return@Column
        }

        vm.alertsError?.let { Banner(it) }

        Label("Plantillas")
        TemplateCard("Disco", "Usado > 85 % o menos de 7 días restantes") {
            vm.applyAlertTemplate("disk")
        }
        TemplateCard("Memoria", "Usado > 90 % de la RAM") {
            vm.applyAlertTemplate("memory")
        }
        TemplateCard("Temperatura", "Cualquier sensor > 85 °C") {
            vm.applyAlertTemplate("temperature")
        }

        Label("Activas")
        val rules = vm.alertReport?.rules.orEmpty()
        if (rules.isEmpty()) {
            Text("Ninguna regla. Añade una plantilla para que el host avise.", color = Palette.Muted, fontSize = 12.sp)
        }
        rules.forEach { rule ->
            RuleCard(rule, predictions = vm.alertReport?.predictions.orEmpty()) {
                vm.deleteAlert(rule.id)
            }
        }

        Label("Silencio")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60, 480, 1440).forEach { minutes ->
                ActionChip(if (minutes < 60) "${minutes} min" else "${minutes / 60} h") {
                    dialogMinutes = minutes
                }
            }
        }
        Text(
            "Silenciar resetea los avisos activos y deja de notificar durante la ventana elegida.",
            color = Palette.Dim,
            fontSize = 10.5.sp,
        )

        Label("Historial · 30 días")
        val events = vm.alertReport?.events.orEmpty()
        if (events.isEmpty()) {
            Text("Sin eventos todavía.", color = Palette.Muted, fontSize = 12.sp)
        }
        events.take(12).forEach { event ->
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
                    Text(event.ruleId, color = Palette.Text, fontSize = 12.sp)
                }
                Text(
                    "${stateLabel(event.state)} · ${ago(event.at)}",
                    color = Palette.Muted,
                    fontSize = 10.5.sp,
                )
            }
        }
        if (vm.alertsBusy) {
            Text("Guardando…", color = Palette.Muted, fontSize = 11.sp)
        }
    }

    dialogMinutes?.let { minutes ->
        AlertDialog(
            onDismissRequest = { dialogMinutes = null },
            title = { Text("Silenciar alertas") },
            text = {
                Text(
                    "No se generarán avisos durante ${if (minutes < 60) "$minutes min" else "${minutes / 60} h"}. " +
                        "Después se reevalúa y, si sigue mal, avisa una vez.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.silenceAlerts(minutes)
                    dialogMinutes = null
                }) { Text("Silenciar") }
            },
            dismissButton = {
                TextButton(onClick = { dialogMinutes = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun TemplateCard(title: String, description: String, onAdd: () -> Unit) {
    VitalsCard(padding = 12.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Palette.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text(description, color = Palette.Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Añadir",
                color = Palette.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Palette.AccentDim)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun RuleCard(rule: AlertRule, predictions: List<dev.vitals.app.core.Prediction>, onDelete: () -> Unit) {
    VitalsCard(padding = 12.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(rule.id, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${rule.kind} · ${rule.condition()}",
                    color = Palette.Muted,
                    fontSize = 11.sp,
                )
                if (rule.kind == "disk") {
                    predictions.firstOrNull { it.mount == rule.mount }?.let {
                        Text("≈ ${"%.1f".format(it.daysLeft)} días restantes", color = Palette.Muted, fontSize = 10.5.sp)
                    }
                }
            }
            Chip(
                if (rule.firing) "disparada" else "ok",
                if (rule.firing) ChipTone.Warm else ChipTone.Accent,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rule.silenceUntil * 1000 > System.currentTimeMillis()) {
                Chip("silenciada hasta ${clock(rule.silenceUntil)}", ChipTone.Neutral)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Quitar",
                color = Palette.Danger,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/* --------------------------------- permissions ------------------------------ */

@Composable
fun CapsScreen(vm: AppViewModel) {
    var request by remember { mutableStateOf<Capability?>(null) }
    LaunchedEffect(Unit) {
        if (vm.capsReport == null) vm.loadCaps()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ManagementHeader("Permisos", vm)
        if (!vm.isOperator) {
            OperatorOnly()
            return@Column
        }

        vm.capsError?.let { Banner(it) }
        Text(
            "El scope viene con el token. Las capabilities exec.* se conceden una a una " +
                "en el host y sólo valen para este dispositivo.",
            color = Palette.Muted,
            fontSize = 11.5.sp,
        )

        val capabilities = vm.capsReport?.capabilities.orEmpty()
        val visible = capabilities.filter { it.approvable || it.state == "granted" }
        Label("Incluidos con el token")
        visible.filter { !it.approvable }.forEach { CapabilityCard(it) }
        Label("Requieren aprobación en el host")
        visible.filter { it.approvable }.forEach { capability ->
            CapabilityCard(capability) { request = capability }
        }
        if (vm.capsBusy) {
            Text("Consultando…", color = Palette.Muted, fontSize = 11.sp)
        }
    }

    request?.let { capability ->
        RequestCapabilityDialog(
            capability = capability,
            busy = vm.capsBusy,
            onDismiss = { request = null },
            onSend = { unit, maxMb ->
                vm.requestCapability(capability.id, unit, maxMb)
                request = null
            },
        )
    }
}

@Composable
private fun CapabilityCard(capability: Capability, onRequest: (() -> Unit)? = null) {
    VitalsCard(padding = 12.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    capability.id,
                    color = Palette.Text,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    capability.params?.let { "parámetros: $it" } ?: stateText(capability.state),
                    color = Palette.Muted,
                    fontSize = 10.5.sp,
                )
                capability.request?.code?.let { code ->
                    Text("código $code · caduca ${clock(capability.request.expiresAt)}", color = Palette.Warn, fontSize = 11.sp)
                }
            }
            Chip(riskLabel(capability.risk), riskTone(capability.risk))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(stateText(capability.state), stateTone(capability.state))
            Spacer(Modifier.weight(1f))
            if (onRequest != null && capability.state != "granted" && capability.state != "pending") {
                Text(
                    "Solicitar",
                    color = Palette.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Palette.AccentDim)
                        .clickable(onClick = onRequest)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun RequestCapabilityDialog(
    capability: Capability,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSend: (String?, Long?) -> Unit,
) {
    val isUnit = capability.id == "exec.unit_restart"
    var unit by remember(capability.id) { mutableStateOf("jellyfin.service") }
    var megabytes by remember(capability.id) { mutableStateOf("500") }
    val valid = if (isUnit) unit.isNotBlank() else megabytes.toLongOrNull()?.let { it in 1..2048 } == true

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Solicitar ${capability.id}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Riesgo", color = Palette.Muted, fontSize = 12.sp)
                    Chip(riskLabel(capability.risk), riskTone(capability.risk))
                }
                Text(
                    if (isUnit) {
                        "Se pedirá permiso para reiniciar sólo la unidad indicada. En 0.2 el host no ejecuta nada."
                    } else {
                        "Se pedirá permiso hasta el tamaño indicado. En 0.2 el host no ejecuta nada."
                    },
                    color = Palette.Muted,
                    fontSize = 11.5.sp,
                )
                if (isUnit) {
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unidad systemd") },
                        singleLine = true,
                        enabled = !busy,
                    )
                } else {
                    OutlinedTextField(
                        value = megabytes,
                        onValueChange = { megabytes = it.filter(Char::isDigit).take(4) },
                        label = { Text("Máximo (MB)") },
                        singleLine = true,
                        enabled = !busy,
                    )
                }
                Text(
                    "El host debe aprobarlo con: sudo vitals caps approve <código>",
                    color = Palette.Dim,
                    fontSize = 10.5.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    if (isUnit) onSend(unit, null)
                    else onSend(null, megabytes.toLongOrNull())
                },
            ) { Text("Solicitar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        },
    )
}

/* ---------------------------------- helpers --------------------------------- */

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Palette.Text,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(Palette.Surface2)
            .border(1.dp, Palette.LineStrong, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private fun riskLabel(risk: String) = when (risk) {
    "high" -> "riesgo alto"
    "medium" -> "riesgo medio"
    else -> "riesgo bajo"
}

private fun riskTone(risk: String) = when (risk) {
    "high" -> ChipTone.Hot
    "medium" -> ChipTone.Warm
    else -> ChipTone.Accent
}

private fun stateText(state: String) = when (state) {
    "granted" -> "concedido"
    "pending" -> "pendiente"
    else -> "sin conceder"
}

private fun stateTone(state: String) = when (state) {
    "granted" -> ChipTone.Accent
    "pending" -> ChipTone.Warm
    else -> ChipTone.Neutral
}

private fun stateLabel(state: String) = when (state) {
    "fired" -> "disparada"
    "recovered" -> "recuperada"
    "silenced" -> "silenciada"
    "delivered" -> "entregada"
    else -> state
}

private fun stateColor(state: String) = when (state) {
    "fired" -> Palette.Warn
    "recovered" -> Palette.Accent
    "silenced" -> Palette.Muted
    else -> Palette.Dim
}

private fun ago(wallClockSeconds: Long): String {
    val seconds = (System.currentTimeMillis() / 1000 - wallClockSeconds).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60} min"
        seconds < 86_400 -> "${seconds / 3_600} h"
        else -> "${seconds / 86_400} d"
    }
}

private fun clock(seconds: Long): String {
    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return format.format(java.util.Date(seconds * 1000))
}
