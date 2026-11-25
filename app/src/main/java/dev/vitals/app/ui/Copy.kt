package dev.vitals.app.ui

import dev.vitals.app.core.AlertRule
import dev.vitals.app.core.Disk
import dev.vitals.app.core.Power
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Shared interface copy. Keep the vocabulary in one place: a rule is a rule,
 * an access is an access, and a permission is a permission across the app. */

internal fun agoLabel(ts: Long): String {
    if (ts <= 0) return "—"
    val secs = (System.currentTimeMillis() / 1000 - ts).coerceAtLeast(0)
    return when {
        secs < 10 -> "ahora"
        secs < 60 -> "hace ${secs} s"
        secs < 3600 -> "hace ${secs / 60} min"
        secs < 86_400 -> "hace ${secs / 3600} h"
        else -> "hace ${secs / 86_400} d"
    }
}

internal fun clockLabel(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts * 1000))

internal fun plural(count: Int, one: String, many: String): String =
    if (count == 1) "1 $one" else "$count $many"

internal fun ruleTitle(rule: AlertRule): String = when (rule.kind) {
    "disk" -> "Disco ${rule.mount ?: "/"}"
    "memory" -> "Memoria"
    "temperature" -> "Temperatura"
    else -> rule.id
}

internal fun ruleDetail(rule: AlertRule): String = when (rule.kind) {
    "disk" -> buildString {
        append("Avisar si el disco supera el ")
        append(rule.percent?.let { fmtThreshold(it) } ?: "85")
        append(" %")
        rule.days?.let { append(" o le quedan menos de ${fmtThreshold(it)} días") }
        append(".")
    }
    "memory" -> "Avisar si la RAM usada supera el ${fmtThreshold(rule.percent ?: 90.0)} %."
    "temperature" -> "Avisar si un sensor supera los ${fmtThreshold(rule.celsius ?: 85.0)} °C."
    else -> rule.condition()
}

private fun fmtThreshold(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

internal fun diskFraction(disk: Disk): Float =
    if (disk.total > 0) (disk.used.toFloat() / disk.total).coerceIn(0f, 1f) else 0f

internal fun worstDisk(disks: List<Disk>): Disk? =
    disks.maxByOrNull { diskFraction(it) }

internal fun sessionCountLabel(count: Int): String = when (count) {
    0 -> "nadie conectado"
    1 -> "1 sesión abierta"
    else -> "$count sesiones abiertas"
}

/** The host calls this "scope"; in the interface it is the token's reach. */
internal fun scopeLabel(scope: String?): String = when (scope) {
    "operator" -> "control total"
    "reader" -> "sólo lectura"
    else -> scope?.takeIf { it.isNotBlank() } ?: "sin datos"
}

/** Plain-language purpose for the closed capability catalog shown in Más › Permisos. */
internal fun capabilityPurpose(id: String): String? = when (id) {
    "read.metrics" -> "Leer métricas del host."
    "read.processes" -> "Ver procesos y su consumo."
    "read.history" -> "Consultar el histórico de métricas."
    "read.access" -> "Ver sesiones y accesos SSH."
    "read.audit" -> "Consultar el registro de auditoría."
    "write.alerts" -> "Crear, quitar y silenciar reglas."
    "write.channels" -> "Enviar avisos a un canal externo."
    "exec.unit_restart" -> "Reiniciar un servicio del host."
    "exec.journal_vacuum" -> "Recortar los registros del sistema."
    "exec.pkg_cache_clean" -> "Limpiar la caché de paquetes."
    else -> null
}

internal fun batteryStatusLabel(status: String): String = when (status) {
    "charging" -> "cargando"
    "discharging" -> "descargando"
    "full" -> "completa"
    "not_charging" -> "sin cargar"
    else -> "sin datos"
}

internal fun batteryPrimaryLine(power: Power): String = when {
    power.status == "charging" && (power.percent ?: 0f) >= 99.5f -> "batería completa"
    power.status == "charging" && power.secondsToFull != null && power.secondsToFull > 0 ->
        "${Fmt.duration(power.secondsToFull)} para el 100 %"
    power.status == "charging" -> "cargando"
    power.status == "discharging" && power.secondsLeft != null ->
        "${Fmt.duration(power.secondsLeft)} restantes"
    power.status == "full" -> "batería completa"
    power.status == "not_charging" -> "conectada, sin cargar"
    else -> "—"
}

internal fun batteryColor(power: Power) = when {
    power.status == "charging" -> Palette.Accent
    (power.percent ?: 100f) <= 10f -> Palette.Danger
    (power.percent ?: 100f) <= 25f -> Palette.Warn
    else -> Palette.Rx
}

internal fun batteryTone(power: Power): ChipTone = when {
    power.status == "charging" -> ChipTone.Accent
    (power.percent ?: 100f) <= 10f -> ChipTone.Danger
    (power.percent ?: 100f) <= 25f -> ChipTone.Warm
    else -> ChipTone.Neutral
}

internal fun powerStatus(status: String): String = batteryStatusLabel(status)
