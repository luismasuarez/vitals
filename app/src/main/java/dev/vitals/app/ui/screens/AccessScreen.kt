package dev.vitals.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vitals.app.AppViewModel
import dev.vitals.app.core.AccessReport
import dev.vitals.app.core.AccessSource
import dev.vitals.app.core.AuthEvent
import dev.vitals.app.core.SshSession
import dev.vitals.app.ui.AccessTab
import dev.vitals.app.ui.Banner
import dev.vitals.app.ui.CardHeader
import dev.vitals.app.ui.Chip
import dev.vitals.app.ui.ChipTone
import dev.vitals.app.ui.Dot
import dev.vitals.app.ui.EmptyState
import dev.vitals.app.ui.Fmt
import dev.vitals.app.ui.Label
import dev.vitals.app.ui.Palette
import dev.vitals.app.ui.Segmented
import dev.vitals.app.ui.TextAction
import dev.vitals.app.ui.TopicTabs
import dev.vitals.app.ui.Type
import dev.vitals.app.ui.VitalsCard
import dev.vitals.app.ui.agoLabel
import dev.vitals.app.ui.plural
import dev.vitals.app.ui.sessionCountLabel
import dev.vitals.app.ui.worstDisk

@Composable
fun AccessScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) {
        if (vm.accessReport == null) vm.loadAccess(quiet = false) else vm.loadAccess(quiet = true)
    }
    Column(Modifier.fillMaxSize()) {
        TopicTabs(
            items = AccessTab.entries,
            selected = vm.accessTab,
            label = { it.label },
            onSelect = vm::selectAccessTab,
        )
        when (vm.accessTab) {
            AccessTab.Now -> NowTab(vm)
            AccessTab.Activity -> ActivityTab(vm)
            AccessTab.Ssh -> SshTab(vm)
        }
    }
}

/* ----------------------------------- now ----------------------------------- */

@Composable
private fun NowTab(vm: AppViewModel) {
    val report = vm.accessReport
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.accessError?.let { error ->
            item { Banner(error, Palette.Danger) }
        }
        if (report == null) {
            item {
                VitalsCard {
                    if (vm.accessBusy || vm.accessError == null) {
                        EmptyState("Leyendo accesos", "Sesiones abiertas y registros del host…")
                    } else {
                        EmptyState("Sin datos de acceso", "El agente no ha respondido todavía. Vuelve a intentarlo.")
                        TextAction("Reintentar", modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            vm.loadAccess()
                        }
                    }
                }
            }
            return@LazyColumn
        }

        item {
            val logsReadable = report.source != "none"
            val external = report.sources.filter { it.public }.map { it.host }.distinct()
            VitalsCard {
                CardHeader("Resumen de acceso", trailing = "actualizado ${agoLabel(report.updated)}")
                StatusLine(
                    tone = if (report.sessions.isEmpty()) Palette.Accent else Palette.Rx,
                    label = "Conectado ahora",
                    value = sessionSummary(report.sessions),
                )
                StatusLine(
                    tone = when {
                        !logsReadable -> Palette.Dim
                        external.isEmpty() -> Palette.Accent
                        else -> Palette.Rx
                    },
                    label = "Direcciones de internet",
                    value = when {
                        !logsReadable -> "No disponible"
                        external.isEmpty() -> "Ninguna"
                        external.size == 1 -> "1 dirección"
                        else -> "${external.size} direcciones"
                    },
                )
                StatusLine(
                    tone = when {
                        !logsReadable -> Palette.Dim
                        report.totalFailures == 0 -> Palette.Accent
                        else -> Palette.Warn
                    },
                    label = "Intentos fallidos",
                    value = when {
                        !logsReadable -> "No disponible"
                        report.totalFailures == 0 -> "Ninguno"
                        else -> "${report.totalFailures} en ${coverageShort(report.coveredS, report.range)}"
                    },
                )
                StatusLine(
                    tone = sshSummaryTone(report.ssh.passwordAuth, report.ssh.rootLogin, report.ssh.files.isNotEmpty()),
                    label = "Configuración SSH",
                    value = sshSummaryLabel(report.ssh.passwordAuth, report.ssh.rootLogin, report.ssh.files.isNotEmpty()),
                )
                if (!logsReadable) {
                    Banner(
                        "No se pudieron leer los registros del sistema. Sólo se muestran las sesiones abiertas y la configuración de SSH.",
                        Palette.Warn,
                    )
                }
            }
        }

        item {
            VitalsCard {
                CardHeader("Sesiones abiertas", trailing = sessionCountLabel(report.sessions.size))
                if (report.sessions.isEmpty()) {
                    EmptyState("Nadie está conectado", "No hay sesiones abiertas en este momento.")
                } else {
                    report.sessions.forEach { session -> SessionLine(session) }
                }
            }
        }

        item {
            val requested = rangeShort(report.range)
            val coverage = coverageShort(report.coveredS, report.range)
            Text(
                when {
                    report.coveredS <= 0 ->
                        "Sin actividad de acceso registrada en este periodo · actualizado ${agoLabel(report.updated)}"
                    coverage == requested ->
                        "Registros de acceso de los últimos $requested · actualizado ${agoLabel(report.updated)}"
                    else ->
                        "Los registros cubren $coverage de $requested · actualizado ${agoLabel(report.updated)}"
                },
                style = Type.micro,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(tone: Color, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(tone, 6.dp)
        Text(label, style = Type.secondary, modifier = Modifier.weight(1f))
        Text(value, color = if (tone == Palette.Dim) Palette.Muted else Palette.Text, fontSize = 12.5.sp)
    }
}

@Composable
private fun SessionLine(session: SshSession) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.user,
                    color = Palette.Text,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Chip(if (session.remote) "Remota" else "En el host")
            }
            Text(sessionOrigin(session), style = Type.micro, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (session.since > 0) "abierta ${agoLabel(session.since)}" else "—",
            style = Type.caption,
        )
    }
}

/* -------------------------------- activity --------------------------------- */

@Composable
private fun ActivityTab(vm: AppViewModel) {
    val report = vm.accessReport
    var allFailures by remember { mutableStateOf(false) }
    var allLogins by remember { mutableStateOf(false) }
    var allSources by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.accessError?.let { error ->
            item { Banner(error, Palette.Danger) }
        }
        if (report == null) {
            item {
                VitalsCard {
                    if (vm.accessBusy || vm.accessError == null) {
                        EmptyState("Leyendo la actividad", "Sesiones y registros de acceso del host…")
                    } else {
                        EmptyState("Sin datos de acceso", "El agente no ha respondido todavía. Vuelve a intentarlo.")
                        TextAction("Reintentar", modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            vm.loadAccess()
                        }
                    }
                }
            }
            return@LazyColumn
        }
        if (report.source == "none") {
            item {
                Banner("No se pudieron leer los registros del sistema. No hay actividad de acceso que mostrar.", Palette.Warn)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Segmented(
                    options = listOf("24h" to "24 h", "7d" to "7 días"),
                    selected = vm.accessRange,
                    onSelect = { if (it != vm.accessRange) vm.loadAccess(it) },
                )
                Spacer(Modifier.weight(1f))
                TextAction(if (vm.accessBusy) "Leyendo…" else "Actualizar", enabled = !vm.accessBusy) {
                    vm.loadAccess(vm.accessRange)
                }
            }
        }

        if (report.source != "none") {
            item {
                VitalsCard {
                    CardHeader(
                        "Intentos fallidos",
                        trailing = if (report.totalFailures == 0) "ninguno" else "${report.totalFailures} en ${coverageShort(report.coveredS, report.range)}",
                    )
                    if (report.failures.isEmpty()) {
                        EmptyState("Ningún intento fallido", "Nadie ha intentado entrar sin éxito en este periodo.")
                    } else {
                        report.failures.take(if (allFailures) report.failures.size else 12).forEach { event ->
                            EventLine(event, failure = true)
                        }
                        if (report.failures.size > 12) {
                            TextAction(if (allFailures) "Mostrar menos" else "Ver todos (${report.failures.size})") {
                                allFailures = !allFailures
                            }
                        }
                    }
                }
            }

            item {
                VitalsCard {
                    CardHeader(
                        "Últimos accesos",
                        trailing = if (report.totalLogins == 0) "ninguno" else "${report.totalLogins} en ${coverageShort(report.coveredS, report.range)}",
                    )
                    if (report.logins.isEmpty()) {
                        EmptyState("Sin accesos aceptados", "Nadie ha entrado en este periodo.")
                    } else {
                        report.logins.take(if (allLogins) report.logins.size else 12).forEach { event ->
                            EventLine(event)
                        }
                        if (report.logins.size > 12) {
                            TextAction(if (allLogins) "Mostrar menos" else "Ver todos (${report.logins.size})") {
                                allLogins = !allLogins
                            }
                        }
                    }
                }
            }

            item {
                VitalsCard {
                    CardHeader("Direcciones de origen", trailing = plural(report.sources.size, "dirección", "direcciones"))
                    if (report.sources.isEmpty()) {
                        EmptyState("Sin direcciones de origen", "No hay actividad desde ninguna dirección en este periodo.")
                    } else {
                        val loginsByHost = report.logins.groupingBy { it.host }.eachCount()
                        val failuresByHost = report.failures.groupingBy { it.host }.eachCount()
                        report.sources.take(if (allSources) report.sources.size else 12).forEach { source ->
                            SourceLine(
                                source,
                                logins = loginsByHost[source.host] ?: 0,
                                failures = failuresByHost[source.host] ?: 0,
                            )
                        }
                        if (report.sources.size > 12) {
                            TextAction(if (allSources) "Mostrar menos" else "Ver todas (${report.sources.size})") {
                                allSources = !allSources
                            }
                        }
                        Text(
                            "«Internet» indica una dirección pública; «Red local» incluye tu LAN y Tailscale. " +
                                "«Dirección nueva» es la primera vez vista en las últimas 24 h.",
                            style = Type.micro,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }

        item {
            val requested = rangeShort(report.range)
            val coverage = coverageShort(report.coveredS, report.range)
            Text(
                when {
                    report.coveredS <= 0 ->
                        "Sin actividad de acceso registrada en este periodo · actualizado ${agoLabel(report.updated)}"
                    coverage == requested ->
                        "Registros de acceso de los últimos $requested · actualizado ${agoLabel(report.updated)}"
                    else ->
                        "Los registros cubren $coverage de $requested · actualizado ${agoLabel(report.updated)}"
                },
                style = Type.micro,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun EventLine(event: AuthEvent, failure: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${event.user} desde ${event.host}",
                color = if (failure) Palette.Warn else Palette.Text,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                eventDetail(event, failure),
                style = Type.micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(agoLabel(event.t), style = Type.caption)
    }
}

@Composable
private fun SourceLine(source: AccessSource, logins: Int, failures: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    source.host,
                    color = Palette.Text,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (source.new) Chip("Dirección nueva", ChipTone.Warm)
                Chip(if (source.public) "Internet" else "Red local")
            }
            Text(
                buildString {
                    append(if (logins == 0) "Sin accesos aceptados" else plural(logins, "acceso aceptado", "accesos aceptados"))
                    if (failures > 0) append(" · ${plural(failures, "intento fallido", "intentos fallidos")}")
                    if (source.users.isNotEmpty()) append(" · ${source.users.joinToString(", ")}")
                },
                style = Type.micro,
                maxLines = 2,
            )
        }
        Text(agoLabel(source.lastT), style = Type.caption)
    }
}

/* ----------------------------------- ssh ----------------------------------- */

@Composable
private fun SshTab(vm: AppViewModel) {
    val report = vm.accessReport
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.accessError?.let { error ->
            item { Banner(error, Palette.Danger) }
        }
        if (report == null) {
            item {
                VitalsCard {
                    if (vm.accessBusy || vm.accessError == null) {
                        EmptyState("Leyendo la configuración", "El agente está consultando sshd…")
                    } else {
                        EmptyState("Sin datos de SSH", "El agente no ha respondido todavía. Vuelve a intentarlo.")
                        TextAction("Reintentar", modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            vm.loadAccess()
                        }
                    }
                }
            }
            return@LazyColumn
        }
        val ssh = report.ssh
        val readable = ssh.files.isNotEmpty()
        item {
            VitalsCard {
                CardHeader("Configuración SSH", trailing = if (readable) "disponible" else "no disponible")
                if (!readable) {
                    EmptyState(
                        "No se pudo leer la configuración",
                        "Puede que sshd no esté instalado o que el archivo no tenga permisos de lectura.",
                    )
                } else {
                    Text("Así se puede entrar al host por SSH.", style = Type.caption)
                    ConfigRow(
                        label = "Contraseña por SSH",
                        description = if (ssh.passwordAuth) {
                            "Cualquiera que sepa la contraseña puede entrar."
                        } else {
                            "Sólo se puede entrar con una clave."
                        },
                        value = if (ssh.passwordAuth) "Permitida" else "No permitida",
                        tone = if (ssh.passwordAuth) ChipTone.Warm else ChipTone.Accent,
                    )
                    ConfigRow(
                        label = "Entrada de root",
                        description = rootHint(ssh.rootLogin),
                        value = rootValue(ssh.rootLogin),
                        tone = rootTone(ssh.rootLogin),
                    )
                }
            }
        }
        if (readable) {
            item {
                VitalsCard {
                    CardHeader("Qué significa")
                    Text(
                        "Con contraseña puede entrar quien la conozca; con clave hace falta el archivo privado, " +
                            "que es más difícil de adivinar.",
                        style = Type.secondary,
                        lineHeight = 18.sp,
                    )
                    Text(
                        "La app sólo lee la configuración. Los cambios se hacen en el host, en sshd_config.",
                        style = Type.micro,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, description: String, value: String, tone: ChipTone) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Palette.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Palette.Dim, fontSize = 10.5.sp, lineHeight = 15.sp)
        }
        Chip(value, tone)
    }
}

/* --------------------------------- helpers --------------------------------- */

private fun rangeShort(range: String): String = if (range == "7d") "7 días" else "24 h"

/** Actual history behind the totals, capped to the selected range. */
private fun coverageShort(coveredS: Long, range: String): String {
    val window = if (range == "7d") 7 * 86_400L else 86_400L
    val covered = coveredS.coerceIn(0L, window)
    return when {
        covered >= window -> rangeShort(range)
        covered >= 86_400L -> plural((covered / 86_400L).toInt(), "día", "días")
        covered >= 3_600L -> "${covered / 3_600L} h"
        covered >= 60L -> "${covered / 60L} min"
        covered > 0L -> "menos de 1 min"
        else -> "sin historial"
    }
}

private fun sshSummaryLabel(passwordAuth: Boolean, rootLogin: String, readable: Boolean): String = when {
    !readable -> "No legible"
    rootLogin.equals("yes", ignoreCase = true) -> "Root puede entrar"
    passwordAuth -> "Acepta contraseña"
    else -> "Sólo con clave"
}

private fun sshSummaryTone(passwordAuth: Boolean, rootLogin: String, readable: Boolean): Color = when {
    !readable -> Palette.Dim
    rootLogin.equals("yes", ignoreCase = true) -> Palette.Tx
    passwordAuth -> Palette.Warn
    else -> Palette.Accent
}

private fun sessionSummary(sessions: List<SshSession>): String {
    if (sessions.isEmpty()) return "nadie"
    val users = sessions.map { it.user }.distinct()
    return when {
        users.size == 1 && sessions.size == 1 -> users.first()
        users.size == 1 -> "${users.first()} · ${sessions.size} sesiones"
        else -> "${users.size} usuarios"
    }
}

private fun sessionOrigin(session: SshSession): String {
    val host = session.host
    return when {
        session.remote && !host.isNullOrBlank() -> "Desde $host"
        session.remote -> "Sesión SSH remota"
        isDesktopSession(session.service) -> "Sesión de escritorio"
        else -> "Sesión en el host"
    }
}

private fun isDesktopSession(service: String): Boolean =
    listOf("gdm", "sddm", "lightdm", "lxdm", "greetd").any { service.startsWith(it) }

private fun eventDetail(event: AuthEvent, failure: Boolean): String {
    val parts = buildList {
        if (event.invalidUser) add("usuario inexistente")
        methodLabel(event.method)?.let(::add)
    }
    return parts.joinToString(" · ").ifBlank { if (failure) "intento rechazado" else "acceso aceptado" }
}

private fun methodLabel(method: String?): String? {
    val value = method?.trim()?.lowercase().orEmpty()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("password") -> "con contraseña"
        value.startsWith("publickey") -> "con clave pública"
        value.startsWith("keyboard-interactive") -> "con contraseña interactiva"
        value.startsWith("hostbased") -> "con clave del host"
        value == "none" -> "sin autenticación"
        else -> value
    }
}

private fun rootValue(rootLogin: String): String = when (rootLogin.lowercase()) {
    "yes" -> "Puede entrar"
    "no" -> "No puede entrar"
    "prohibit-password" -> "Sólo con clave"
    "forced-commands-only" -> "Sólo comandos"
    else -> rootLogin.ifBlank { "Sin datos" }
}

private fun rootHint(rootLogin: String): String = when (rootLogin.lowercase()) {
    "yes" -> "El superusuario puede entrar por SSH con contraseña o clave."
    "no" -> "El superusuario no puede entrar por SSH."
    "prohibit-password" -> "El superusuario sólo puede entrar con una clave."
    "forced-commands-only" -> "El superusuario sólo puede ejecutar comandos autorizados."
    else -> if (rootLogin.isBlank()) "No se pudo leer el ajuste de root." else "Valor en la configuración: $rootLogin."
}

private fun rootTone(rootLogin: String): ChipTone = when (rootLogin.lowercase()) {
    "yes" -> ChipTone.Hot
    "no", "prohibit-password" -> ChipTone.Accent
    else -> ChipTone.Neutral
}
