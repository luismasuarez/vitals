package dev.vitals.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything needed to talk to one host. The token is a device credential
 * issued during pairing; a production build should keep it in the Android
 * Keystore instead of plain SharedPreferences.
 */
data class Session(
    val name: String,
    val pin: String,
    val endpoints: List<String>,
    val token: String,
    val deviceId: String,
    val pairedAt: Long,
)

class SessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("vitals.session", Context.MODE_PRIVATE)

    fun load(): Session? {
        val raw = prefs.getString(KEY, null) ?: return null
        val session = runCatching {
            val o = JSONObject(raw)
            Session(
                name = o.optString("name").ifBlank { "host" },
                pin = o.optString("pin"),
                endpoints = o.optJSONArray("endpoints").toStringList(),
                token = o.optString("token"),
                deviceId = o.optString("device_id"),
                pairedAt = o.optLong("paired_at"),
            )
        }.getOrNull()
        return session?.takeIf { it.token.isNotBlank() && it.pin.isNotBlank() && it.endpoints.isNotEmpty() }
    }

    fun save(session: Session) {
        val o = JSONObject()
            .put("name", session.name)
            .put("pin", session.pin)
            .put("endpoints", JSONArray(session.endpoints))
            .put("token", session.token)
            .put("device_id", session.deviceId)
            .put("paired_at", session.pairedAt)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private companion object {
        const val KEY = "session"
    }
}
