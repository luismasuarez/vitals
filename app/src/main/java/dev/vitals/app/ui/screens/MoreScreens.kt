package dev.vitals.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vitals.app.AppViewModel
import dev.vitals.app.core.Capability
import dev.vitals.app.ui.Banner
import dev.vitals.app.ui.CardHeader
import dev.vitals.app.ui.Chip
import dev.vitals.app.ui.ChipTone
import dev.vitals.app.ui.DangerButton
import dev.vitals.app.ui.DividerLine
import dev.vitals.app.ui.EmptyState
import dev.vitals.app.ui.Fmt
import dev.vitals.app.ui.GhostButton
import dev.vitals.app.ui.Glyph
import dev.vitals.app.ui.GlyphIcon
import dev.vitals.app.ui.InfoRow
import dev.vitals.app.ui.Label
import dev.vitals.app.ui.Palette
import dev.vitals.app.ui.PrimaryButton
import dev.vitals.app.ui.Screen
import dev.vitals.app.ui.SectionLabel
import dev.vitals.app.ui.TextAction
import dev.vitals.app.ui.Type
import dev.vitals.app.ui.VitalsCard
import dev.vitals.app.ui.agoLabel
import dev.vitals.app.ui.capabilityPurpose
import dev.vitals.app.ui.clockLabel
import dev.vitals.app.ui.plural
import dev.vitals.app.ui.scopeLabel
import dev.vitals.app.watchdog.WatchStatus
import java.util.Locale

/* ----------------------------------- more ---------------------------------- */

@Composable
fun MoreScreen(vm: AppViewModel) {
    val sample = vm.sample
    val findings = vm.diagnostics?.findings?.size
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            VitalsCard(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button) { vm.navigate(Screen.Settings) },
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            vm.host?.hostname ?: vm.session?.name ?: "host",
                            color = Palette.Text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            vm.host?.let { "${it.os} · ${it.arch}" }
                                ?: if (vm.bootError != null) "Sin conexión con el host" else "Conectando…",
                            style = Type.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GlyphIcon(Glyph.Chevron, Palette.Dim, 18.dp)
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip(scopeLabel(vm.session?.scope), if (vm.isOperator) ChipTone.Accent else ChipTone.Neutral)
                    if (vm.watching) Chip("vigilando", ChipTone.Accent)
                    vm.agent?.version?.let { Chip("agente $it") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        sample?.let { "Encendido hace ${Fmt.uptime(it.uptime)}" } ?: "Sin métricas",
                        style = Type.micro,
                    )
                    sample?.let { Text("actualizado ${agoLabel(it.t)}", style = Type.micro) }
                }
            }
        }

        item {
            Label("Gestionar")
            VitalsCard(padding = 0.dp) {
                Column {
                    NavRow(
                        title = "Procesos",
                        detail = "Qué consume CPU y memoria en vivo.",
                        onClick = { vm.openProcesses("cpu") },
                    )
                    DividerLine()
                    NavRow(
                        title = "Diagnóstico",
                        detail = when {
                            findings == null -> "Comprobar el host."
                            findings == 0 -> "Sin hallazgos."
                            findings == 1 -> "1 hallazgo."
                            else -> "$findings hallazgos."
                        },
                        onClick = { vm.navigate(Screen.Diagnostics) },
                    )
                    DividerLine()
                    NavRow(
                        title = "Permisos",
                        detail = if (vm.isOperator) "Qué puede pedir este dispositivo al host." else "Requiere un token de control total.",
                        onClick = { vm.navigate(Screen.Permissions) },
                    )
                    DividerLine()
                    NavRow(
                        title = "Conexión",
                        detail = connectionDetail(vm),
                        onClick = { vm.navigate(Screen.Connection) },
                    )
                    DividerLine()
                    NavRow(
                        title = "Ajustes",
                        detail = "Host, acceso, dispositivos y zona peligrosa.",
                        onClick = { vm.navigate(Screen.Settings) },
                    )
                }
            }
        }

        item {
            Text(
                "vitals · la app sólo escribe en el host cuando activas una regla, la vigilancia de caída " +
                    "o solicitas un permiso con un token de control total.",
                style = Type.micro,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun connectionDetail(vm: AppViewModel): String {
    if (vm.activeEndpoint.isBlank()) return "Sin conexión."
    val transport = when (vm.transport) {
        dev.vitals.app.ui.Transport.Sse -> "en vivo"
        dev.vitals.app.ui.Transport.Polling -> "sondeo"
        dev.vitals.app.ui.Transport.Idle -> "sin conexión"
    }
    val latency = vm.latency?.let { " · latencia $it ms" } ?: ""
    return "${AppViewModel.kindOf(vm.activeEndpoint)} · $transport$latency"
}

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Palette.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(detail, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        GlyphIcon(Glyph.Chevron, Palette.Dim, 17.dp)
    }
}

/* --------------------------------- settings -------------------------------- */

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var confirmForget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            InfoSection("Host") {
                InfoRow("Nombre", vm.host?.hostname ?: vm.session?.name ?: "—")
                InfoRow("Sistema", vm.host?.os ?: "—")
                InfoRow("Kernel", vm.host?.kernel ?: "—", mono = true)
                InfoRow("Arquitectura", vm.host?.arch ?: "—")
                vm.host?.cpuModel?.takeIf { it.isNotBlank() }?.let { InfoRow("CPU", it) }
                vm.host?.cores?.takeIf { it > 0 }?.let { InfoRow("Núcleos", "$it") }
                vm.host?.memTotal?.takeIf { it > 0 }?.let { InfoRow("RAM", Fmt.bytes(it)) }
                vm.host?.formFactor?.takeIf { it.isNotBlank() && it != "unknown" }?.let {
                    InfoRow("Formato", if (it == "laptop") "portátil" else it)
                }
                if (vm.host?.virtualized == true) InfoRow("Virtualización", "sí")
                vm.sample?.let { InfoRow("Encendido", Fmt.uptime(it.uptime)) }
            }
        }

        item {
            InfoSection("Acceso y token") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Alcance del token", style = Type.secondary)
                    Chip(scopeLabel(vm.session?.scope), if (vm.isOperator) ChipTone.Accent else ChipTone.Neutral)
                }
                Text(
                    "El token es la credencial que este teléfono usa para leer el host.",
                    style = Type.caption,
                )
                InfoRow("Guardado en", if (vm.tokenStorage.startsWith("Android Keystore")) "Cifrado en el teléfono" else vm.tokenStorage)
                InfoRow("Pin del certificado", vm.session?.pin ?: "—", mono = true)
                InfoRow("ID del dispositivo", vm.session?.deviceId ?: "—", mono = true)
                InfoRow("Versión de la app", appVersion)
                InfoRow("Versión del agente", vm.agent?.version ?: "—")
            }
        }

        item {
            InfoSection("Vigilancia") {
                InfoRow(
                    "Estado",
                    when {
                        !vm.watching -> "Apagada"
                        vm.watchState.status == WatchStatus.Down -> "Host caído"
                        vm.watchState.status == WatchStatus.Watching -> "Vigilando"
                        else -> "Conectando"
                    },
                    valueColor = when (vm.watchState.status) {
                        WatchStatus.Down -> Palette.Warn
                        WatchStatus.Watching -> Palette.Accent
                        else -> Palette.Text
                    },
                )
                Text(
                    "Configura las reglas y la vigilancia de caída en Alertas.",
                    style = Type.caption,
                )
                TextAction("Abrir alertas") { vm.navigate(Screen.Alerts) }
            }
        }

        item {
            InfoSection("Conexión") {
                InfoRow("Dirección activa", vm.activeEndpoint.removePrefix("https://").ifBlank { "—" }, mono = true)
                InfoRow(
                    "Modo",
                    when (vm.transport) {
                        dev.vitals.app.ui.Transport.Sse -> "en vivo (SSE)"
                        dev.vitals.app.ui.Transport.Polling -> "sondeo cada 2,5 s"
                        dev.vitals.app.ui.Transport.Idle -> "sin conexión"
                    },
                )
                vm.latency?.let { InfoRow("Latencia de red", "$it ms") }
                TextAction("Gestionar conexión") { vm.navigate(Screen.Connection) }
            }
        }

        item {
            InfoSection("Diagnóstico") {
                Text(
                    vm.diagnostics?.findings?.let {
                        if (it.isEmpty()) "Sin hallazgos en la última revisión." else "${it.size} hallazgos en la última revisión."
                    } ?: "Todavía no se ha ejecutado una revisión.",
                    style = Type.caption,
                )
                TextAction("Abrir diagnóstico") { vm.navigate(Screen.Diagnostics) }
            }
        }

        item {
            InfoSection("Dispositivos emparejados") {
                val devices = vm.agent?.devices.orEmpty()
                if (devices.isEmpty()) {
                    Text(
                        if (vm.agent == null) "Conectando con el host…" else "El host no reporta dispositivos emparejados.",
                        style = Type.caption,
                    )
                } else {
                    devices.forEach { device ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        device.name,
                                        color = Palette.Text,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Chip(
                                        scopeLabel(device.scope),
                                        if (device.scope == "operator") ChipTone.Accent else ChipTone.Neutral,
                                    )
                                }
                                Text("ID ${device.id}", style = Type.micro, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                device.lastSeenAt?.let { "visto ${agoLabel(it)}" } ?: "—",
                                style = Type.micro,
                            )
                        }
                    }
                }
                Text(
                    "Revocar un dispositivo se hace en el host con vitals devices.",
                    style = Type.micro,
                    lineHeight = 15.sp,
                )
            }
        }

        item {
            InfoSection("Zona peligrosa") {
                DangerButton("Olvidar este host") { confirmForget = true }
                Text(
                    "Borra el pin del certificado, las direcciones y el token cifrado de este teléfono. " +
                        "El host seguirá considerando válido este dispositivo hasta que lo revoques allí. " +
                        "Para cambiar de token o de alcance, olvida el host y escanea un QR nuevo.",
                    style = Type.micro,
                    lineHeight = 15.sp,
                )
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Olvidar este host") },
            text = {
                Text(
                    "Se borrarán el pin del certificado, las direcciones y el token de este teléfono. " +
                        "Para volver a verlo tendrás que emparejarlo otra vez con un QR nuevo.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    vm.forget()
                }) { Text("Olvidar host", color = Palette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel(title, Modifier.padding(horizontal = 4.dp))
        VitalsCard {
            content()
        }
    }
}

/* -------------------------------- connection ------------------------------- */

@Composable
fun ConnectionScreen(vm: AppViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.bootError?.let { error ->
            item { Banner(error, Palette.Danger) }
        }
        item {
            VitalsCard {
                CardHeader("Conexión", trailing = if (vm.transport == dev.vitals.app.ui.Transport.Sse) "en vivo" else "por sondeo")
                InfoRow("Dirección activa", vm.activeEndpoint.removePrefix("https://").ifBlank { "—" }, mono = true)
                InfoRow(
                    "Modo",
                    when (vm.transport) {
                        dev.vitals.app.ui.Transport.Sse -> "en vivo (SSE)"
                        dev.vitals.app.ui.Transport.Polling -> "sondeo cada 2,5 s"
                        dev.vitals.app.ui.Transport.Idle -> "sin conexión"
                    },
                )
                InfoRow("Latencia de red", vm.latency?.let { "$it ms" } ?: "—")
                Text(
                    "En vivo mantiene la conexión abierta; el sondeo pide datos cada 2,5 s.",
                    style = Type.micro,
                    lineHeight = 15.sp,
                )
            }
        }

        item {
            VitalsCard {
                CardHeader("Direcciones", trailing = "${vm.endpoints.size}")
                Text(
                    "La app prueba las direcciones en orden y pasa a la siguiente si no hay respuesta. " +
                        "Al tocar una, se prueba y se recuerda la que funcione.",
                    style = Type.caption,
                    lineHeight = 17.sp,
                )
                vm.endpoints.forEachIndexed { index, url ->
                    val active = index == vm.endpointIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) Palette.AccentDim else Palette.Surface2)
                            .border(
                                1.dp,
                                if (active) Palette.Accent.copy(alpha = 0.4f) else Palette.Line,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(role = Role.Button) { vm.selectEndpoint(index) }
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            if (active) "activa" else AppViewModel.kindOf(url),
                            color = if (active) Palette.Accent else Palette.Dim,
                            fontSize = 10.5.sp,
                        )
                    }
                }
                if (vm.endpoints.isEmpty()) {
                    EmptyState("Sin direcciones", "Vuelve a emparejar el host para recibir sus direcciones.")
                }
            }
        }

        item {
            PrimaryButton("Reconectar ahora") { vm.reconnect() }
            Text(
                "Si el host cambió de red, el agente reporta sus direcciones nuevas al reconectar.",
                style = Type.micro,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/* ------------------------------- diagnostics ------------------------------- */

@Composable
fun DiagnosticsScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadDiagnostics() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.diagnosticsError?.let { error ->
            item { Banner(error, Palette.Danger) }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Revisión del host", style = Type.secondary)
                Spacer(Modifier.weight(1f))
                TextAction("Volver a revisar") { vm.loadDiagnostics() }
            }
        }
        val report = vm.diagnostics
        if (report == null) {
            item {
                VitalsCard {
                    EmptyState(
                        if (vm.diagnosticsError != null) "No se pudo revisar" else "Revisando el host",
                        if (vm.diagnosticsError != null) "Comprueba la conexión e inténtalo otra vez." else "Recogiendo hallazgos y comandos sugeridos…",
                    )
                }
            }
        } else if (report.findings.isEmpty()) {
            item {
                VitalsCard {
                    EmptyState(
                        "Sin hallazgos",
                        "El host está dentro de lo esperado en esta revisión.",
                    )
                }
            }
        } else {
            item {
                VitalsCard {
                    CardHeader("Hallazgos", trailing = "${report.findings.size}")
                    report.findings.forEach { finding ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                dev.vitals.app.ui.Dot(severityColor(finding.severity))
                                Text(
                                    finding.title,
                                    color = Palette.Text,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(severityLabel(finding.severity), style = Type.micro)
                            }
                            Text(finding.detail, color = Palette.Muted, fontSize = 11.5.sp, lineHeight = 17.sp)
                            report.suggestions.firstOrNull { it.finding == finding.id }?.let { suggestion ->
                                Text(
                                    suggestion.command,
                                    color = Palette.Accent,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Palette.Surface3)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                                Text(suggestion.explanation, style = Type.micro, lineHeight = 15.sp)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Los comandos son sugerencias: ni la app ni el agente los ejecutan.",
                    style = Type.micro,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

private fun severityColor(severity: String): Color = when (severity) {
    "critical" -> Palette.Danger
    "warning" -> Palette.Warn
    else -> Palette.Accent
}

private fun severityLabel(severity: String): String = when (severity) {
    "critical" -> "crítico"
    "warning" -> "aviso"
    "info" -> "info"
    else -> "sin clasificar"
}

/* -------------------------------- permissions ------------------------------ */

@Composable
fun PermissionsScreen(vm: AppViewModel) {
    var request by remember { mutableStateOf<Capability?>(null) }
    LaunchedEffect(Unit) { if (vm.capsReport == null) vm.loadCaps() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!vm.isOperator) {
            item {
                VitalsCard {
                    CardHeader("Sólo lectura")
                    Text(
                        "Este dispositivo no puede solicitar permisos. La gestión de permisos requiere un token " +
                            "de control total.",
                        color = Palette.Text,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Text(
                        "En el host: vitals pair --operator. Después, olvida este host en Ajustes y escanea el QR nuevo.",
                        style = Type.caption,
                        lineHeight = 17.sp,
                    )
                    TextAction("Abrir ajustes") { vm.navigate(Screen.Settings) }
                }
            }
            return@LazyColumn
        }

        vm.capsError?.let { error ->
            item { Banner(error, Palette.Danger) }
        }

        item {
            VitalsCard {
                CardHeader("Cómo funcionan")
                Text(
                    "El alcance lo define el token. Los permisos de ejecución (exec.*) se conceden uno a uno " +
                        "en el host y sólo valen para este dispositivo. Conceder uno no ejecuta la operación: " +
                        "sólo autoriza. La ejecución llegará en una versión posterior.",
                    style = Type.secondary,
                    lineHeight = 18.sp,
                )
            }
        }

        val report = vm.capsReport
        if (report == null) {
            item {
                VitalsCard {
                    if (vm.capsBusy || vm.capsError == null) {
                        EmptyState("Leyendo permisos", "Permisos de este dispositivo en el host…")
                    } else {
                        EmptyState("Sin datos de permisos", "El agente no ha respondido todavía. Vuelve a intentarlo.")
                        TextAction("Reintentar", modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            vm.loadCaps()
                        }
                    }
                }
            }
        } else {
            val included = report.capabilities.filter { !it.approvable }
            val approvable = report.capabilities.filter { it.approvable }

            item {
                Label("Incluidos con el token")
                VitalsCard(padding = 14.dp) {
                    if (included.isEmpty()) {
                        Text("Este token no incluye permisos base.", style = Type.caption)
                    } else {
                        included.forEachIndexed { index, capability ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        capability.id,
                                        color = Palette.Text,
                                        fontSize = 12.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    capabilityPurpose(capability.id)?.let {
                                        Text(it, style = Type.caption, lineHeight = 16.sp)
                                    }
                                    if (capability.state != "granted") {
                                        Text(
                                            if (capability.id == "write.channels") {
                                                "Todavía no disponible en esta versión."
                                            } else {
                                                stateText(capability.state)
                                            },
                                            style = Type.micro,
                                        )
                                    }
                                }
                                Chip(riskLabel(capability.risk), riskTone(capability.risk))
                            }
                            if (index != included.lastIndex) DividerLine()
                        }
                        if (included.any { it.state != "granted" && it.id != "write.channels" }) {
                            Text(
                                "Las que aparecen como «sin conceder» necesitan configuración en el host; " +
                                    "no se solicitan desde aquí.",
                                style = Type.micro,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }

            item {
                Label("Requieren aprobación en el host")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (approvable.isEmpty()) {
                        Text("Este host no ofrece permisos de ejecución.", style = Type.caption)
                    } else {
                        approvable.forEach { capability ->
                            CapabilityCard(capability) { request = capability }
                        }
                    }
                }
            }
        }

        if (vm.capsBusy && vm.capsReport != null) {
            item { Text("Actualizando permisos…", style = Type.caption) }
        }
        item {
            Text(
                "Aprobar o revocar se hace en el host con vitals caps. La app sólo solicita y muestra el estado.",
                style = Type.micro,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
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
    VitalsCard(padding = 13.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    capability.id,
                    color = Palette.Text,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
                val purpose = capabilityPurpose(capability.id)
                Text(
                    purpose ?: "Requiere aprobación en el host.",
                    style = Type.micro,
                    lineHeight = 15.sp,
                )
                if (purpose != null) {
                    capability.params?.let { Text("Parámetros: $it", style = Type.micro, lineHeight = 15.sp) }
                    capability.grantedBy?.let { Text("Concedido por $it", style = Type.micro, lineHeight = 15.sp) }
                }
                capability.request?.code?.let { code ->
                    Text(
                        "código $code · caduca ${clockLabel(capability.request.expiresAt)}",
                        color = Palette.Warn,
                        fontSize = 11.sp,
                    )
                }
            }
            Chip(riskLabel(capability.risk), riskTone(capability.risk))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(stateText(capability.state), stateTone(capability.state))
            Spacer(Modifier.weight(1f))
            if (onRequest != null && capability.state != "granted" && capability.state != "pending") {
                TextAction("Solicitar") { onRequest() }
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
    var unit by remember(capability.id) { mutableStateOf("") }
    var megabytes by remember(capability.id) { mutableStateOf("") }
    val valid = if (isUnit) unit.isNotBlank() else megabytes.toLongOrNull()?.let { it in 1..2048 } == true

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Solicitar permiso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Riesgo", style = Type.secondary)
                    Chip(riskLabel(capability.risk).removePrefix("riesgo "), riskTone(capability.risk))
                }
                Text(
                    when (capability.id) {
                        "exec.unit_restart" -> "Se pedirá permiso para reiniciar sólo la unidad indicada."
                        "exec.journal_vacuum" ->
                            "Se pedirá permiso para recortar los registros del sistema hasta el tamaño indicado."
                        "exec.pkg_cache_clean" ->
                            "Se pedirá permiso para limpiar la caché de paquetes hasta el tamaño indicado."
                        else -> "Se pedirá permiso para esta operación."
                    },
                    style = Type.caption,
                )
                Text(
                    "Solicitar no ejecuta nada: sólo pide autorización. La aprobación se hace en el host.",
                    style = Type.caption,
                )
                if (isUnit) {
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Servicio (unidad systemd)") },
                        placeholder = { Text("ej. jellyfin.service") },
                        singleLine = true,
                        enabled = !busy,
                    )
                } else {
                    OutlinedTextField(
                        value = megabytes,
                        onValueChange = { megabytes = it.filter(Char::isDigit).take(4) },
                        label = { Text("Máximo (MB)") },
                        placeholder = { Text("ej. 500") },
                        singleLine = true,
                        enabled = !busy,
                    )
                }
                Text(
                    "Cuando lo solicites, el host debe aprobarlo con: sudo vitals caps approve <código>",
                    style = Type.micro,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    if (isUnit) onSend(unit, null) else onSend(null, megabytes.toLongOrNull())
                },
            ) { Text("Solicitar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        },
    )
}

private fun riskLabel(risk: String) = when (risk) {
    "high" -> "riesgo alto"
    "medium" -> "riesgo medio"
    "low" -> "riesgo bajo"
    else -> "riesgo sin clasificar"
}

private fun riskTone(risk: String) = when (risk) {
    "high" -> ChipTone.Hot
    "medium" -> ChipTone.Warm
    "low" -> ChipTone.Accent
    else -> ChipTone.Neutral
}

private fun stateText(state: String) = when (state) {
    "granted" -> "concedido"
    "pending" -> "pendiente"
    "not_granted" -> "sin conceder"
    else -> "estado desconocido"
}

private fun stateTone(state: String) = when (state) {
    "granted" -> ChipTone.Accent
    "pending" -> ChipTone.Warm
    else -> ChipTone.Neutral
}
