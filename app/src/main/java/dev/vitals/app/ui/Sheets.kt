package dev.vitals.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.vitals.app.AppViewModel
import dev.vitals.app.watchdog.WatchStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsSheets(vm: AppViewModel, sheet: Sheet, onClose: () -> Unit) {
    if (sheet == Sheet.None) return

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Palette.Surface,
        contentColor = Palette.Text,
    ) {
        // ModalBottomSheet already sits next to the navigation bar but does not
        // reserve its height for scrolling content, so add the raw inset.
        val insetBottom = with(LocalDensity.current) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp + insetBottom),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (sheet) {
                Sheet.Endpoints -> EndpointsSheet(vm, onClose)
                Sheet.Settings -> SettingsSheet(vm, onClose)
                Sheet.None -> Unit
            }
        }
    }
}

/* -------------------------------- endpoints -------------------------------- */

@Composable
private fun EndpointsSheet(vm: AppViewModel, onClose: () -> Unit) {
    Text("Conexión", color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "La app prueba los endpoints en orden y cae al siguiente si no hay respuesta.",
        color = Palette.Muted,
        fontSize = 12.sp,
    )

    vm.endpoints.forEachIndexed { index, url ->
        val active = index == vm.endpointIndex
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) Palette.AccentDim else Palette.Surface2)
                .border(
                    1.dp,
                    if (active) Palette.Accent.copy(alpha = 0.4f) else Palette.Line,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                url.removePrefix("https://"),
                color = Palette.Text,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                if (active) "activo" else AppViewModel.kindOf(url),
                color = if (active) Palette.Accent else Palette.Dim,
                fontSize = 10.sp,
            )
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Transporte", color = Palette.Muted, fontSize = 12.5.sp)
        Text(
            when (vm.transport) {
                Transport.Sse -> "SSE"
                Transport.Polling -> "polling"
                Transport.Idle -> "—"
            },
            color = Palette.Text,
            fontSize = 12.5.sp,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Latencia", color = Palette.Muted, fontSize = 12.5.sp)
        Text(vm.latency?.let { "$it ms" } ?: "—", color = Palette.Text, fontSize = 12.5.sp)
    }

    PrimaryButton("Reconectar ahora") { vm.reconnect() }
    GhostButton("Cerrar", onClick = onClose)
}

/* -------------------------------- settings --------------------------------- */

@Composable
private fun SettingsSheet(vm: AppViewModel, onClose: () -> Unit) {
    val session = vm.session

    Text("Ajustes", color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)

    SettingsSection("Host") {
        SettingsRow("Nombre", vm.host?.hostname ?: session?.name ?: "—")
        SettingsRow("Pin", session?.pin ?: "—", mono = true)
        SettingsRow(
            "Token",
            session?.token?.let { "${it.take(12)}…${it.takeLast(4)}" } ?: "—",
            mono = true,
        )
        SettingsRow("Almacenamiento", vm.tokenStorage)
        SettingsRow("Scope", session?.scope ?: "reader")
        SettingsRow("Versión", vm.agent?.version ?: "—")
        SettingsRow("Kernel", vm.host?.kernel ?: "—")
    }

    SettingsSection("Gestión") {
        Text(
            "Las reglas y los permisos necesitan un token operator. Si esta sesión es " +
                "reader, reempareja con: vitals pair --operator",
            color = Palette.Dim,
            fontSize = 11.sp,
        )
        GhostButton("Reglas de alerta") {
            onClose()
            vm.selectScreen(Screen.Rules)
        }
        GhostButton("Permisos por dispositivo") {
            onClose()
            vm.selectScreen(Screen.Caps)
        }
    }

    SettingsSection("Vigilancia") {
        val context = LocalContext.current
        val permission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                vm.setWatchdog(true)
            } else {
                vm.toast = "Sin permiso de notificaciones no puedo avisarte"
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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
            "Un servicio en primer plano mantiene la conexión y avisa si el host deja de " +
                "responder en unos 30 s. Si el sistema lo mata, WorkManager lo revive cada " +
                "15 min. Para máxima fiabilidad, excluye vitals de la optimización de batería.",
            color = Palette.Dim,
            fontSize = 11.sp,
        )
    }

    SettingsSection("Diagnóstico") {
        LaunchedEffect(Unit) { vm.loadDiagnostics() }
        val report = vm.diagnostics
        val error = vm.diagnosticsError
        when {
            error != null -> Text(error, color = Palette.Danger, fontSize = 11.5.sp)
            report == null -> Text("Analizando el host…", color = Palette.Muted, fontSize = 11.5.sp)
            report.findings.isEmpty() ->
                Text("Sin hallazgos: el host está dentro de lo esperado.", color = Palette.Muted, fontSize = 11.5.sp)
            else -> report.findings.forEach { finding ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(severityColor(finding.severity))
                        Text(finding.title, color = Palette.Text, fontSize = 12.5.sp)
                    }
                    Text(finding.detail, color = Palette.Muted, fontSize = 11.sp)
                    report.suggestions.firstOrNull { it.finding == finding.id }?.let { suggestion ->
                        Text(
                            suggestion.command,
                            color = Palette.Accent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        Text(
            "Los comandos son sugerencias: ni la app ni el agente los ejecutan.",
            color = Palette.Dim,
            fontSize = 10.5.sp,
        )
    }

    SettingsSection("Dispositivos emparejados") {
        val devices = vm.agent?.devices.orEmpty()
        if (devices.isEmpty()) {
            Text("Sin datos del host todavía.", color = Palette.Dim, fontSize = 12.sp)
        } else {
            devices.forEach { device ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(device.name, color = Palette.Text, fontSize = 12.5.sp)
                        Text("id ${device.id}", color = Palette.Muted, fontSize = 10.5.sp)
                    }
                    Text(formatTime(device.lastSeenAt), color = Palette.Muted, fontSize = 10.5.sp)
                }
            }
        }
        Text(
            "La lectura es libre para el token del dispositivo; revocar o cambiar permisos " +
                "se hace en el host con  vitals devices  y  vitals caps.",
            color = Palette.Dim,
            fontSize = 11.sp,
        )
    }

    SettingsSection("Zona peligrosa") {
        DangerButton("Olvidar host y reiniciar") { vm.forget() }
        Text(
            "El token de este dispositivo sigue válido en el host hasta que lo revoques allí.",
            color = Palette.Dim,
            fontSize = 11.sp,
        )
    }

    GhostButton("Cerrar", onClick = onClose)
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        Text(title.uppercase(), color = Palette.Dim, fontSize = 10.5.sp, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun SettingsRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Palette.Muted, fontSize = 12.5.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = Palette.Text,
            fontSize = if (mono) 10.5.sp else 12.5.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
        )
    }
}

/* --------------------------------- helpers --------------------------------- */

private fun watchStatusText(vm: AppViewModel): String {
    if (!vm.watching) return "Apagada · sólo consulta al abrir la app"
    val state = vm.watchState
    return when (state.status) {
        WatchStatus.Off -> "Activando…"
        WatchStatus.Connecting -> "Conectando con el host…"
        WatchStatus.Watching -> state.lastEventAt?.let { "Vigilando · último dato hace ${ago(it)}" }
            ?: "Vigilando · esperando el primer dato"
        WatchStatus.Down -> state.since?.let { "Sin respuesta desde hace ${ago(it)}" }
            ?: "Sin respuesta"
    }
}

private fun ago(wallClockMs: Long): String {
    val seconds = ((System.currentTimeMillis() - wallClockMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60} min"
        else -> "${seconds / 3_600} h"
    }
}

private fun severityColor(severity: String) = when (severity) {
    "critical" -> Palette.Danger
    "warning" -> Palette.Warn
    else -> Palette.Accent
}

@Composable
internal fun SheetHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(42.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Palette.Surface3)
        )
    }
}
