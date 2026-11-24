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

data class Sample(
    val t: Long,
    val cpu: Cpu,
    val mem: Mem,
    val net: Net,
    val disks: List<Disk>,
    val temps: List<Temp>,
    val uptime: Long,
)

data class Point(
    val t: Long,
    val cpu: Double,
    val mem: Long,
    val netRx: Long,
    val netTx: Long,
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
)

data class DeviceInfo(val id: String, val name: String, val lastSeenAt: Long?)

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
    val expiresAt: Long,
)

data class PairResult(val token: String, val deviceId: String)

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
                    lastSeenAt = if (d.has("last_seen_at") && !d.isNull("last_seen_at")) d.getLong("last_seen_at") else null,
                )
            },
        )
    }

    fun pairResult(text: String): PairResult {
        val o = JSONObject(text)
        return PairResult(token = o.getString("token"), deviceId = o.getString("device_id"))
    }

    fun errorMessage(text: String): String? =
        runCatching { JSONObject(text).optString("error").ifBlank { null } }.getOrNull()

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
            device = o.optString("dev").ifBlank { null },
            endpoints = endpoints,
            pin = pin,
            code = code,
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
