package dev.vitals.app.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * The QR code carries a truncated SHA-256 of the agent certificate. That hash
 * is the only thing we trust: no CA chain, no hostname check, no date window.
 */
class PinnedTrustManager(private val pin: String) : X509TrustManager {

    val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<X509TrustManager>(this@PinnedTrustManager), SecureRandom())
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val actual = Base64.encodeToString(
            digest.copyOf(16),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )
        if (!MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) {
            throw CertificateException("certificate pin mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Thin client over the read-only API. Failover is part of the contract: on any
 * transport error the next endpoint in the QR list is tried, and the working
 * one is remembered for subsequent calls.
 */
class VitalsApi(val session: Session) {

    private val trust = PinnedTrustManager(session.pin)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(trust.sslContext.socketFactory, trust)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val sseClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var index = 0

    var latencyMs: Long = 0
        private set

    val endpointIndex: Int get() = index
    val activeEndpoint: String get() = session.endpoints[index]

    fun selectEndpoint(i: Int) {
        index = i.coerceIn(0, session.endpoints.lastIndex)
    }

    fun rotateEndpoint(): Int {
        index = (index + 1) % session.endpoints.size
        return index
    }

    suspend fun <T> get(path: String, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        parse(execute("GET", path, null))
    }

    suspend fun history(range: String): List<Point> =
        get("/api/v1/history?range=$range") { Wire.history(it) }

    suspend fun processes(sort: String, limit: Int = 20): List<Proc> =
        get("/api/v1/processes?sort=$sort&limit=$limit") { Wire.processes(it) }

    suspend fun host(): HostInfo = get("/api/v1/host") { Wire.host(it) }

    suspend fun info(): AgentInfo = get("/api/v1/info") { Wire.info(it) }

    suspend fun metrics(): Sample = get("/api/v1/metrics") { Wire.sample(it) }

    suspend fun alerts(): AlertReport = get("/api/v1/alerts") { Wire.alerts(it) }

    suspend fun caps(): CapsReport = get("/api/v1/caps") { Wire.caps(it) }

    suspend fun diagnostics(): DiagnosticsReport = get("/api/v1/diagnostics") { Wire.diagnostics(it) }

    suspend fun suggestions(): List<Suggestion> =
        get("/api/v1/suggest") { Wire.suggestions(it) }

    /** One mutable endpoint, one JSON envelope. */
    suspend fun command(op: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val envelope = JSONObject()
            .put("v", 1)
            .put("id", UUID.randomUUID().toString())
            .put("op", op)
            .put("args", args)
        val body = envelope.toString()
            .toRequestBody("application/json".toMediaType())
        JSONObject(execute("POST", "/api/v1/command", body))
    }

    fun openStream(listener: EventSourceListener): EventSource =
        openStreamAt(index, listener)

    /**
     * Opens the stream against a specific endpoint. The watchdog rotates this
     * index itself so a failure on LAN immediately falls through to Tailscale.
     */
    fun openStreamAt(at: Int, listener: EventSourceListener): EventSource {
        val endpoint = session.endpoints[at.coerceIn(0, session.endpoints.lastIndex)]
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + "/api/v1/stream")
            .header("Authorization", "Bearer ${session.token}")
            .build()
        return EventSources.createFactory(sseClient).newEventSource(request, listener)
    }

    private fun execute(method: String, path: String, body: RequestBody?): String {
        var last: Throwable? = null
        for (attempt in session.endpoints.indices) {
            val candidate = (index + attempt) % session.endpoints.size
            val url = session.endpoints[candidate].trimEnd('/') + path
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${session.token}")
                    .method(method, body)
                    .build()
                val started = System.nanoTime()
                client.newCall(request).execute().use { response ->
                    latencyMs = (System.nanoTime() - started) / 1_000_000
                    if (!response.isSuccessful) {
                        val text = response.body?.string().orEmpty()
                        throw ApiException(response.code, Wire.errorMessage(text) ?: "HTTP ${response.code}")
                    }
                    index = candidate
                    return response.body?.string().orEmpty()
                }
            } catch (e: ApiException) {
                if (e.status == 401) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("ningún endpoint respondió")
    }
}

/** Pairing runs before a session exists, so it needs its own pinned client. */
object Pairing {

    suspend fun exchange(payload: PairingPayload, deviceName: String): Pair<PairResult, Int> =
        withContext(Dispatchers.IO) {
            val trust = PinnedTrustManager(payload.pin)
            val client = OkHttpClient.Builder()
                .sslSocketFactory(trust.sslContext.socketFactory, trust)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()

            val body = JSONObject()
                .put("code", payload.code)
                .put("device_name", deviceName)
                .toString()
                .toRequestBody("application/json".toMediaType())

            var last: Throwable? = null
            for ((i, endpoint) in payload.endpoints.withIndex()) {
                try {
                    val request = Request.Builder()
                        .url(endpoint.trimEnd('/') + "/api/v1/pair")
                        .post(body)
                        .build()
                    client.newCall(request).execute().use { response: Response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw ApiException(response.code, Wire.errorMessage(text) ?: "HTTP ${response.code}")
                        }
                        return@withContext Wire.pairResult(text) to i
                    }
                } catch (e: ApiException) {
                    if (e.status == 401 || e.status == 403) throw e
                    last = e
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("ningún endpoint aceptó la conexión")
        }
}
