package dev.vitals.app.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything needed to talk to one host. The token is a device credential
 * issued during pairing and lives encrypted with Android Keystore; the rest of
 * the session (pin, endpoints) is not secret.
 */
data class Session(
    val name: String,
    val pin: String,
    val endpoints: List<String>,
    val token: String,
    val deviceId: String,
    val scope: String,
    val pairedAt: Long,
)

class SessionStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val vault = TokenVault(app)

    fun load(): Session? {
        val raw = prefs.getString(KEY, null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val legacy = o.optString("token").takeIf { it.isNotBlank() }
        val encrypted = vault.load()
        val token = when {
            encrypted != null -> {
                // A crash between both writes can leave the old plaintext copy.
                if (legacy != null) stripLegacyToken()
                encrypted
            }
            legacy != null -> {
                migrateLegacy(legacy)
                legacy
            }
            else -> return null
        }

        val session = runCatching {
            Session(
                name = o.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "host",
                pin = o.optString("pin"),
                endpoints = o.optJSONArray("endpoints").toStringList(),
                token = token,
                deviceId = o.optString("device_id"),
                scope = o.optString("scope").takeIf { it.isNotBlank() && it != "null" } ?: "reader",
                pairedAt = o.optLong("paired_at"),
            )
        }.getOrNull()
        return session?.takeIf { it.token.isNotBlank() && it.pin.isNotBlank() && it.endpoints.isNotEmpty() }
    }

    /** Returns false when the token could not be encrypted; nothing is written. */
    fun save(session: Session): Boolean {
        val encrypted = runCatching { vault.save(session.token) }
        if (encrypted.isFailure) {
            Log.e(TAG, "cannot encrypt the token; session not persisted", encrypted.exceptionOrNull())
            return false
        }
        val o = JSONObject()
            .put("name", session.name)
            .put("pin", session.pin)
            .put("endpoints", JSONArray(session.endpoints))
            .put("device_id", session.deviceId)
            .put("scope", session.scope)
            .put("paired_at", session.pairedAt)
        return prefs.edit().putString(KEY, o.toString()).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY).commit()
        vault.clear()
    }

    /** Human-readable state for Settings: Keystore, plain text or empty. */
    fun tokenStorage(): String = when {
        vault.hasEncryptedToken() -> "Android Keystore"
        legacyToken() != null -> "texto plano (migrando)"
        else -> "—"
    }

    private fun migrateLegacy(token: String) {
        val saved = runCatching { vault.save(token) }
        if (saved.isFailure) {
            // Keep the old value so the app still works; retry on next launch.
            Log.e(TAG, "token migration failed", saved.exceptionOrNull())
            return
        }
        stripLegacyToken()
        Log.i(TAG, "device token migrated to Android Keystore")
    }

    private fun stripLegacyToken() {
        val raw = prefs.getString(KEY, null) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (!o.has("token")) return
        o.remove("token")
        if (!prefs.edit().putString(KEY, o.toString()).commit()) {
            Log.w(TAG, "cannot remove the legacy plaintext token")
        }
    }

    private fun legacyToken(): String? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { JSONObject(raw).optString("token") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
    }

    private companion object {
        const val PREFS = "vitals.session"
        const val KEY = "session"
        const val TAG = "VitalsSession"
    }
}
