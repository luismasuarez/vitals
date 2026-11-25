package dev.vitals.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/* ------------------------------- wire types ------------------------------- */

data class Cpu(
    val total: Float,
    val cores: List<Float>,
    val load1: Double,
    val load5: Double,
    val load15: Double,
)

data class Mem(
    val total: Long,
    val used: Long,
    val available: Long,
    val swapTotal: Long,
    val swapUsed: Long,
)

data class Iface(val name: String, val rxBps: Long, val txBps: Long)

data class Net(val rxBps: Long, val txBps: Long, val ifaces: List<Iface>)

data class Disk(
    val mount: String,
    val fs: String,
    val total: Long,
    val used: Long,
    val rBps: Long,
    val wBps: Long,
)

data class Temp(val label: String, val celsius: Float)

data class Battery(
    val name: String,
    val present: Boolean,
    val status: String,
    val percent: Float?,
    val powerW: Float?,
    val energyNowWh: Float?,
    val energyFullWh: Float?,
    val energyFullDesignWh: Float?,
    val voltageV: Float?,
    val secondsLeft: Long?,
    val secondsToFull: Long?,
    val healthPct: Float?,
    val cycles: Int?,
    val technology: String?,
    val model: String?,
)

data class Power(
    val acOnline: Boolean,
    val onBattery: Boolean,
    val percent: Float?,
    val status: String,
    val powerW: Float?,
    val secondsLeft: Long?,
    val secondsToFull: Long?,
    val healthPct: Float?,
    val cycles: Int?,
    val batteries: List<Battery>,
)

data class SshSession(
    val user: String,
    val tty: String,
    val service: String,
    val remote: Boolean,
    val host: String?,
    val since: Long,
)

data class AuthEvent(
    val t: Long,
    val ok: Boolean,
    val user: String,
    val host: String,
    val method: String?,
    val port: Int?,
    val invalidUser: Boolean,
)

data class AccessSource(
    val host: String,
    val public: Boolean,
    val ok: Int,
    val failed: Int,
    val firstT: Long,
    val lastT: Long,
    val users: List<String>,
    val new: Boolean,
)

data class SshConfigInfo(
    val passwordAuth: Boolean,
    val rootLogin: String,
    val files: List<String>,
)

data class AccessSummary(
    val sessions: List<SshSession>,
    val logins24h: Int,
    val failures24h: Int,
    val lastLogin: AuthEvent?,
    val lastFailure: AuthEvent?,
    val passwordAuth: Boolean,
    val configRead: Boolean,
    val rootLogin: String,
    val source: String,
)

data class AccessReport(
    val updated: Long,
    val range: String,
    val source: String,
    val coveredS: Long,
    val sessions: List<SshSession>,
    val logins: List<AuthEvent>,
    val failures: List<AuthEvent>,
    val sources: List<AccessSource>,
    val ssh: SshConfigInfo,
    val totalLogins: Int,
    val totalFailures: Int,
)

data class Sample(
    val t: Long,
    val cpu: Cpu,
    val mem: Mem,
    val net: Net,
    val disks: List<Disk>,
    val temps: List<Temp>,
    val uptime: Long,
    val power: Power? = null,
    val access: AccessSummary? = null,
)

data class Point(
    val t: Long,
    val cpu: Double,
    val mem: Long,
    val netRx: Long,
    val netTx: Long,
    val batteryPct: Float? = null,
    val powerW: Float? = null,
)

data class Proc(val pid: Int, val name: String, val cpu: Float, val mem: Long)

data class HostInfo(
    val hostname: String,
    val os: String,
    val kernel: String,
    val arch: String,
    val cpuModel: String,
    val cores: Int,
    val memTotal: Long,
    val formFactor: String = "unknown",
    val virtualized: Boolean = false,
)

data class DeviceInfo(
    val id: String,
    val name: String,
    val scope: String,
    val lastSeenAt: Long?,
)

data class AgentInfo(
    val name: String,
    val version: String,
    val pin: String,
    val endpoints: List<String>,
    val devices: List<DeviceInfo>,
)

data class PairingPayload(
    val version: Int,
    val name: String,
    val device: String?,
    val endpoints: List<String>,
    val pin: String,
    val code: String,
    val scope: String,
    val expiresAt: Long,
)

data class PairResult(val token: String, val deviceId: String, val scope: String)

/* ------------------------------- alerts/caps ------------------------------ */

data class AlertRule(
    val id: String,
    val kind: String,
    val mount: String?,
    val percent: Double?,
    val days: Double?,
    val celsius: Double?,
    val silenceUntil: Long,
    val firing: Boolean,
    val since: Long?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        // A null from the wire used to become the literal "null" string; only
        // absolute paths are ever valid, so drop anything else.
        mount?.takeIf { it.startsWith("/") }?.let { put("mount", it) }
        percent?.let { put("percent", it) }
        days?.let { put("days", it) }
        celsius?.let { put("celsius", it) }
    }

    fun condition(): String = when (kind) {
        "disk" -> listOfNotNull(
            percent?.let { ">${fmt(it)}%" },
            days?.let { "<${fmt(it)} días" },
        ).joinToString(" o ").ifBlank { "sin umbral" }
        "memory" -> ">${fmt(percent ?: 0.0)}%"
        "temperature" -> ">${fmt(celsius ?: 0.0)} °C"
        else -> kind
    }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
}

data class AlertEvent(
    val id: Long,
    val ruleId: String,
    val kind: String,
    val at: Long,
    val state: String,
    val detail: String?,
)

data class Prediction(val mount: String, val daysLeft: Double)

data class AlertReport(
    val rules: List<AlertRule>,
    val events: List<AlertEvent>,
    val predictions: List<Prediction>,
    val retentionDays: Int,
)

data class CapRequestInfo(val id: Long, val code: String?, val expiresAt: Long)

data class Capability(
    val id: String,
    val risk: String,
    val approvable: Boolean,
    val state: String,
    val grantedBy: String?,
    val params: JSONObject?,
    val request: CapRequestInfo?,
)

data class CapsReport(
    val deviceId: String,
    val scope: String,
    val capabilities: List<Capability>,
)

data class Finding(
    val id: String,
    val severity: String,
    val title: String,
    val detail: String,
    val suggestion: String?,
)

data class Suggestion(val id: String, val finding: String, val risk: String, val command: String, val explanation: String)

data class DiagnosticsReport(
    val findings: List<Finding>,
    val suggestions: List<Suggestion>,
)

/* --------------------------------- parsing -------------------------------- */

object Wire {

    fun sample(text: String): Sample {
        val o = JSONObject(text)
        val cpu = o.getJSONObject("cpu")
        val mem = o.getJSONObject("mem")
        val net = o.getJSONObject("net")
        return Sample(
            t = o.optLong("t"),
            cpu = Cpu(
                total = cpu.getDouble("total").toFloat(),
                cores = cpu.optJSONArray("cores").asFloatList(),
                load1 = cpu.optDouble("load1"),
                load5 = cpu.optDouble("load5"),
                load15 = cpu.optDouble("load15"),
            ),
            mem = Mem(
                total = mem.optLong("total"),
                used = mem.optLong("used"),
                available = mem.optLong("available"),
                swapTotal = mem.optLong("swap_total"),
                swapUsed = mem.optLong("swap_used"),
            ),
            net = Net(
                rxBps = net.optLong("rx_bps"),
                txBps = net.optLong("tx_bps"),
                ifaces = net.optJSONArray("ifaces").objects { i ->
                    Iface(i.getString("name"), i.optLong("rx_bps"), i.optLong("tx_bps"))
                },
            ),
            disks = o.optJSONArray("disks").objects { d ->
                Disk(
                    mount = d.optString("mount"),
                    fs = d.optString("fs"),
                    total = d.optLong("total"),
                    used = d.optLong("used"),
                    rBps = d.optLong("r_bps"),
                    wBps = d.optLong("w_bps"),
                )
            },
            temps = o.optJSONArray("temps").objects { t ->
                Temp(t.optString("label"), t.optDouble("celsius").toFloat())
            },
            uptime = o.optLong("uptime"),
            access = o.optJSONObject("access")?.let { a ->
                AccessSummary(
                    sessions = a.optJSONArray("sessions").sessions(),
                    logins24h = a.optInt("logins_24h"),
                    failures24h = a.optInt("failures_24h"),
                    lastLogin = a.optJSONObject("last_login")?.authEvent(),
                    lastFailure = a.optJSONObject("last_failure")?.authEvent(),
                    passwordAuth = a.optBoolean("password_auth", true),
                    configRead = a.optBoolean("config_read"),
                    rootLogin = a.optString("root_login", "prohibit-password"),
                    source = a.optString("source", "none"),
                )
            },
            power = o.optJSONObject("power")?.let { p ->
                Power(
                    acOnline = p.optBoolean("ac_online"),
                    onBattery = p.optBoolean("on_battery"),
                    percent = p.floatOrNull("percent"),
                    status = p.optString("status", "unknown"),
                    powerW = p.floatOrNull("power_w"),
                    secondsLeft = p.longOrNull("seconds_left"),
                    secondsToFull = p.longOrNull("seconds_to_full"),
                    healthPct = p.floatOrNull("health_pct"),
                    cycles = p.intOrNull("cycles"),
                    batteries = p.optJSONArray("batteries").objects { b ->
                        Battery(
                            name = b.optString("name"),
                            present = b.optBoolean("present", true),
                            status = b.optString("status", "unknown"),
                            percent = b.floatOrNull("percent"),
                            powerW = b.floatOrNull("power_w"),
                            energyNowWh = b.floatOrNull("energy_now_wh"),
                            energyFullWh = b.floatOrNull("energy_full_wh"),
                            energyFullDesignWh = b.floatOrNull("energy_full_design_wh"),
                            voltageV = b.floatOrNull("voltage_v"),
                            secondsLeft = b.longOrNull("seconds_left"),
                            secondsToFull = b.longOrNull("seconds_to_full"),
                            healthPct = b.floatOrNull("health_pct"),
                            cycles = b.intOrNull("cycles"),
                            technology = b.stringOrNull("technology"),
                            model = b.stringOrNull("model"),
                        )
                    },
                )
            },
        )
    }

    fun history(text: String): List<Point> =
        JSONObject(text).optJSONArray("points").objects { p ->
            Point(
                t = p.optLong("t"),
                cpu = p.optDouble("cpu"),
                mem = p.optLong("mem"),
                netRx = p.optLong("net_rx"),
                netTx = p.optLong("net_tx"),
                batteryPct = p.floatOrNull("battery_pct"),
                powerW = p.floatOrNull("power_w"),
            )
        }

    fun processes(text: String): List<Proc> =
        JSONObject(text).optJSONArray("processes").objects { p ->
            Proc(
                pid = p.optInt("pid"),
                name = p.optString("name"),
                cpu = p.optDouble("cpu").toFloat(),
                mem = p.optLong("mem"),
            )
        }

    fun host(text: String): HostInfo {
        val o = JSONObject(text)
        return HostInfo(
            hostname = o.optString("hostname"),
            os = o.optString("os"),
            kernel = o.optString("kernel"),
            arch = o.optString("arch"),
            cpuModel = o.optString("cpu_model"),
            cores = o.optInt("cores"),
            memTotal = o.optLong("mem_total"),
            formFactor = o.optString("form_factor", "unknown"),
            virtualized = o.optBoolean("virtualized"),
        )
    }

    fun info(text: String): AgentInfo {
        val o = JSONObject(text)
        return AgentInfo(
            name = o.optString("name"),
            version = o.optString("version"),
            pin = o.optString("pin"),
            endpoints = o.optJSONArray("endpoints").strings(),
            devices = o.optJSONArray("devices").objects { d ->
                DeviceInfo(
                    id = d.optString("id"),
                    name = d.optString("name"),
                    scope = d.optString("scope", "reader"),
                    lastSeenAt = if (d.has("last_seen_at") && !d.isNull("last_seen_at")) d.getLong("last_seen_at") else null,
                )
            },
        )
    }

    fun pairResult(text: String): PairResult {
        val o = JSONObject(text)
        return PairResult(
            token = o.getString("token"),
            deviceId = o.getString("device_id"),
            scope = o.stringOrNull("scope") ?: "reader",
        )
    }

    fun alerts(text: String): AlertReport {
        val o = JSONObject(text)
        return AlertReport(
            rules = o.optJSONArray("rules").objects { r ->
                AlertRule(
                    id = r.getString("id"),
                    kind = r.optString("kind"),
                    mount = r.stringOrNull("mount"),
                    percent = r.doubleOrNull("percent"),
                    days = r.doubleOrNull("days"),
                    celsius = r.doubleOrNull("celsius"),
                    silenceUntil = r.optLong("silence_until"),
                    firing = r.optBoolean("firing"),
                    since = r.longOrNull("since"),
                )
            },
            events = o.optJSONArray("events").objects { e ->
                AlertEvent(
                    id = e.optLong("id"),
                    ruleId = e.optString("rule_id"),
                    kind = e.optString("kind"),
                    at = e.optLong("at"),
                    state = e.optString("state"),
                    detail = e.stringOrNull("detail"),
                )
            },
            predictions = o.optJSONArray("predictions").objects { p ->
                Prediction(p.optString("mount"), p.optDouble("days_left"))
            },
            retentionDays = o.optInt("retention_days", 30),
        )
    }

    fun caps(text: String): CapsReport {
        val o = JSONObject(text)
        return CapsReport(
            deviceId = o.optString("device_id"),
            scope = o.optString("scope"),
            capabilities = o.optJSONArray("capabilities").objects { c ->
                val request = c.optJSONObject("request")?.let {
                    CapRequestInfo(
                        id = it.optLong("id"),
                        code = it.stringOrNull("code"),
                        expiresAt = it.optLong("expires_at"),
                    )
                }
                Capability(
                    id = c.optString("id"),
                    risk = c.optString("risk"),
                    approvable = c.optBoolean("approvable"),
                    state = c.optString("state"),
                    grantedBy = c.stringOrNull("granted_by"),
                    params = c.optJSONObject("params"),
                    request = request,
                )
            },
        )
    }

    fun diagnostics(text: String): DiagnosticsReport {
        val o = JSONObject(text)
        return DiagnosticsReport(
            findings = o.optJSONArray("findings").objects { f ->
                Finding(
                    id = f.optString("id"),
                    severity = f.optString("severity"),
                    title = f.optString("title"),
                    detail = f.optString("detail"),
                    suggestion = f.stringOrNull("suggestion"),
                )
            },
            suggestions = o.optJSONArray("suggestions").objects { s ->
                Suggestion(
                    id = s.optString("id"),
                    finding = s.optString("finding"),
                    risk = s.optString("risk"),
                    command = s.optString("command"),
                    explanation = s.optString("explanation"),
                )
            },
        )
    }

    fun suggestions(text: String): List<Suggestion> =
        JSONObject(text).optJSONArray("suggestions").objects { s ->
            Suggestion(
                id = s.optString("id"),
                finding = s.optString("finding"),
                risk = s.optString("risk"),
                command = s.optString("command"),
                explanation = s.optString("explanation"),
            )
        }

    fun access(text: String): AccessReport {
        val o = JSONObject(text)
        val totals = o.optJSONObject("totals")
        val ssh = o.optJSONObject("ssh")
        return AccessReport(
            updated = o.optLong("updated"),
            range = o.optString("range", "24h"),
            source = o.optString("source", "none"),
            coveredS = o.optLong("covered_s"),
            sessions = o.optJSONArray("sessions").sessions(),
            logins = o.optJSONArray("logins").authEvents(),
            failures = o.optJSONArray("failures").authEvents(),
            sources = o.optJSONArray("sources").accessSources(),
            ssh = SshConfigInfo(
                passwordAuth = ssh?.optBoolean("password_auth", true) ?: true,
                rootLogin = ssh?.optString("root_login", "prohibit-password") ?: "prohibit-password",
                files = ssh?.optJSONArray("files").strings().orEmpty(),
            ),
            totalLogins = totals?.optInt("logins") ?: 0,
            totalFailures = totals?.optInt("failures") ?: 0,
        )
    }

    fun errorMessage(text: String): String? = runCatching {
        val o = JSONObject(text)
        when (val error = o.opt("error")) {
            is String -> error.ifBlank { null }
            is JSONObject -> error.stringOrNull("detail") ?: error.stringOrNull("code")
            else -> null
        }
    }.getOrNull()

    /**
     * Accepts `vitals1:<base64url>` or the bare base64url body, with or
     * without padding.
     */
    fun pairingPayload(raw: String): PairingPayload {
        val body = raw.trim().removePrefix("vitals1:")
        val normalized = body.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val json = String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        val o = JSONObject(json)
        val version = o.optInt("v")
        require(version == 1) { "unsupported payload version $version" }
        val endpoints = o.optJSONArray("ep").strings()
        require(endpoints.isNotEmpty()) { "payload has no endpoints" }
        val pin = o.optString("pin")
        val code = o.optString("code")
        require(pin.isNotBlank() && code.isNotBlank()) { "payload is incomplete" }
        return PairingPayload(
            version = version,
            name = o.optString("name"),
            device = o.stringOrNull("dev"),
            endpoints = endpoints,
            pin = pin,
            code = code,
            scope = o.stringOrNull("scope") ?: "reader",
            expiresAt = o.optLong("exp"),
        )
    }
}

/* ------------------------------- json helpers ----------------------------- */

@Suppress("UNCHECKED_CAST")
private fun <T> JSONArray?.objects(block: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(block) }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}

private fun JSONArray?.asFloatList(): List<Float> {
    if (this == null) return emptyList()
    return (0 until length()).map { optDouble(it).toFloat() }
}

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null

private fun JSONObject.floatOrNull(key: String): Float? = doubleOrNull(key)?.toFloat()

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONArray?.sessions(): List<SshSession> = objects { s ->
    SshSession(
        user = s.optString("user"),
        tty = s.optString("tty"),
        service = s.optString("service", "?"),
        remote = s.optBoolean("remote"),
        host = s.stringOrNull("host"),
        since = s.optLong("since"),
    )
}

private fun JSONObject.authEvent(): AuthEvent = AuthEvent(
    t = optLong("t"),
    ok = optBoolean("ok"),
    user = optString("user"),
    host = optString("host"),
    method = stringOrNull("method"),
    port = intOrNull("port"),
    invalidUser = optBoolean("invalid_user"),
)

private fun JSONArray?.authEvents(): List<AuthEvent> = objects { it.authEvent() }

private fun JSONArray?.accessSources(): List<AccessSource> = objects { s ->
    AccessSource(
        host = s.optString("host"),
        public = s.optBoolean("public"),
        ok = s.optInt("ok"),
        failed = s.optInt("failed"),
        firstT = s.optLong("first_t"),
        lastT = s.optLong("last_t"),
        users = s.optJSONArray("users").strings(),
        new = s.optBoolean("new"),
    )
}

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
