package dev.vitals.app

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.vitals.app.core.AgentInfo
import dev.vitals.app.core.ApiException
import dev.vitals.app.core.HostInfo
import dev.vitals.app.core.Pairing
import dev.vitals.app.core.PairingPayload
import dev.vitals.app.core.Point
import dev.vitals.app.core.Proc
import dev.vitals.app.core.Sample
import dev.vitals.app.core.Session
import dev.vitals.app.core.SessionStore
import dev.vitals.app.core.VitalsApi
import dev.vitals.app.core.Wire
import dev.vitals.app.ui.OnboardStep
import dev.vitals.app.ui.Screen
import dev.vitals.app.ui.Transport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

data class Series(
    val cpu: List<Float> = emptyList(),
    val rx: List<Float> = emptyList(),
    val tx: List<Float> = emptyList(),
) {
    fun push(sample: Sample) = Series(
        cpu = (cpu + sample.cpu.total).takeLast(MAX),
        rx = (rx + sample.net.rxBps.toFloat()).takeLast(MAX),
        tx = (tx + sample.net.txBps.toFloat()).takeLast(MAX),
    )

    companion object {
        const val MAX = 60
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SessionStore(app)
    private val main = Handler(Looper.getMainLooper())

    var session by mutableStateOf<Session?>(store.load())
        private set
    var host by mutableStateOf<HostInfo?>(null)
    var agent by mutableStateOf<AgentInfo?>(null)
    var sample by mutableStateOf<Sample?>(null)
    var series by mutableStateOf(Series())
    var history by mutableStateOf<List<Point>>(emptyList())
    var procs by mutableStateOf<List<Proc>>(emptyList())

    var transport by mutableStateOf(Transport.Idle)
    var latency by mutableStateOf<Long?>(null)
    var endpointIndex by mutableStateOf(0)

    var screen by mutableStateOf(Screen.Home)
    var netRange by mutableStateOf("live")
    var procSort by mutableStateOf("cpu")
    var procFilter by mutableStateOf("")

    var onboardStep by mutableStateOf(OnboardStep.Intro)
    var pending by mutableStateOf<PairingPayload?>(null)
    var pairingError by mutableStateOf<String?>(null)
    var bootError by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)

    private var pendingSession: Session? = null
    private var autoEnter = false
    private var api: VitalsApi? = null
    private var stream: EventSource? = null
    private var generation = 0
    private var streamFailures = 0
    private var pollJob: Job? = null
    private var procJob: Job? = null

    val endpoints: List<String> get() = session?.endpoints.orEmpty()
    val activeEndpoint: String get() = endpoints.getOrElse(endpointIndex) { "" }

    init {
        if (session != null) boot()
    }

    /* ------------------------------- lifecycle ------------------------------ */

    fun onVisible() {
        if (session != null && transport != Transport.Sse) startStream()
    }

    fun onHidden() {
        stream?.cancel()
        stream = null
        stopPolling()
        stopProcPolling()
    }

    /* -------------------------------- identity ------------------------------ */

    private fun boot() {
        val current = session ?: return
        // Keep the endpoint that pairing proved reachable.
        val client = VitalsApi(current).also { it.selectEndpoint(endpointIndex) }
        api = client
        endpointIndex = client.endpointIndex
        streamFailures = 0
        bootError = null

        viewModelScope.launch {
            try {
                host = client.host()
                agent = runCatching { client.info() }.getOrNull()
                val first = client.metrics()
                main.post {
                    latency = client.latencyMs
                    applySample(first)
                }
                seedSeries(client)
                startStream()
            } catch (e: Throwable) {
                if (e is ApiException && e.status == 401) {
                    forget("La sesión ya no es válida. Vuelve a emparejar.")
                } else {
                    bootError = friendly(e)
                    transport = Transport.Polling
                    startPolling()
                }
            }
        }
    }

    private suspend fun seedSeries(client: VitalsApi) {
        val points = runCatching { client.history("15m") }.getOrNull().orEmpty().takeLast(Series.MAX)
        if (points.size < 2) return
        main.post {
            series = Series(
                cpu = points.map { it.cpu.toFloat() },
                rx = points.map { it.netRx.toFloat() },
                tx = points.map { it.netTx.toFloat() },
            )
        }
    }

    fun reconnect() {
        bootError = null
        startStream()
        viewModelScope.launch { refreshMetrics() }
    }

    fun selectEndpoint(index: Int) {
        api?.selectEndpoint(index)
        endpointIndex = index
        toast = "Probando ${kindOf(endpoints.getOrElse(index) { "" })}"
        reconnect()
    }

    /* --------------------------------- stream ------------------------------- */

    private fun startStream() {
        val client = api ?: return
        val current = ++generation
        stream?.cancel()

        stream = client.openStream(object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                main.post {
                    if (current != generation) return@post
                    transport = Transport.Sse
                    streamFailures = 0
                    stopPolling()
                    endpointIndex = client.endpointIndex
                    bootError = null
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "snapshot" -> runCatching { Wire.sample(data) }.getOrNull()?.let { snapshot ->
                        main.post { if (current == generation) applySample(snapshot) }
                    }
                    "resync" -> viewModelScope.launch { refreshMetrics() }
                }
            }

            override fun onClosed(eventSource: EventSource) = retry(current, client)

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) =
                retry(current, client)
        })
    }

    private fun retry(current: Int, client: VitalsApi) {
        main.post {
            if (current != generation || session == null) return@post
            transport = Transport.Polling
            startPolling()
            streamFailures++
            if (streamFailures >= 2 && client.session.endpoints.size > 1) {
                endpointIndex = client.rotateEndpoint()
                toast = "Sin respuesta: probando ${kindOf(client.activeEndpoint)}"
            }
            val wait = minOf(1500L * streamFailures, 8000L)
            main.postDelayed({
                if (current == generation && session != null) startStream()
            }, wait)
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshMetrics()
                delay(2500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refreshMetrics() {
        val client = api ?: return
        try {
            val snapshot = client.metrics()
            main.post {
                latency = client.latencyMs
                endpointIndex = client.endpointIndex
                applySample(snapshot)
            }
        } catch (e: Throwable) {
            if (e is ApiException && e.status == 401) forget("La sesión ya no es válida. Vuelve a emparejar.")
        }
    }

    private fun applySample(snapshot: Sample) {
        sample = snapshot
        series = series.push(snapshot)
    }

    /* --------------------------------- screens ------------------------------ */

    fun selectScreen(target: Screen) {
        screen = target
        if (target == Screen.Procs) startProcPolling() else stopProcPolling()
        if (target == Screen.Net && netRange != "live" && history.isEmpty()) loadHistory(netRange)
    }

    fun loadHistory(range: String) {
        netRange = range
        if (range == "live") return
        viewModelScope.launch {
            val client = api ?: return@launch
            runCatching { client.history(range) }
                .onSuccess { history = it }
                .onFailure { toast = "Histórico no disponible" }
        }
    }

    fun startProcPolling() {
        if (procJob?.isActive == true) return
        procJob = viewModelScope.launch {
            while (isActive) {
                val client = api ?: break
                runCatching { client.processes(procSort) }.onSuccess { procs = it }
                delay(4000)
            }
        }
    }

    private fun stopProcPolling() {
        procJob?.cancel()
        procJob = null
    }

    fun reloadProcs() {
        viewModelScope.launch {
            val client = api ?: return@launch
            runCatching { client.processes(procSort) }.onSuccess { procs = it }
        }
    }

    fun sortProcs(sort: String) {
        procSort = sort
        reloadProcs()
    }

    fun filterProcs(text: String) {
        procFilter = text
    }

    /* --------------------------------- pairing ------------------------------ */

    fun acceptPayload(raw: String) {
        try {
            pending = Wire.pairingPayload(raw)
            onboardStep = OnboardStep.Found
        } catch (e: Exception) {
            toast = "Código inválido: ${e.message}"
        }
    }

    fun autoPair(raw: String) {
        pending = runCatching { Wire.pairingPayload(raw) }.getOrNull() ?: return
        autoEnter = true
        pair()
    }

    fun beginScanning() {
        onboardStep = OnboardStep.Scanning
    }

    fun cancelScanning() {
        onboardStep = OnboardStep.Intro
    }

    fun backToIntro() {
        pending = null
        onboardStep = OnboardStep.Intro
    }

    fun pair() {
        val payload = pending ?: return
        onboardStep = OnboardStep.Pairing
        pairingError = null
        viewModelScope.launch {
            try {
                val (result, index) = Pairing.exchange(payload, payload.device ?: deviceName())
                val created = Session(
                    name = payload.name.ifBlank { "host" },
                    pin = payload.pin,
                    endpoints = payload.endpoints,
                    token = result.token,
                    deviceId = result.deviceId,
                    pairedAt = System.currentTimeMillis(),
                )
                pendingSession = created
                endpointIndex = index
                onboardStep = OnboardStep.Done
                if (autoEnter) enterApp()
            } catch (e: Throwable) {
                pairingError = friendlyPairing(e)
                onboardStep = OnboardStep.Error
            }
        }
    }

    fun enterApp() {
        val next = pendingSession
        pendingSession = null
        pending = null
        autoEnter = false
        onboardStep = OnboardStep.Intro
        if (next != null) {
            store.save(next)
            session = next
        }
        if (session != null) boot()
    }

    fun forget(reason: String? = null) {
        generation++
        stream?.cancel()
        stream = null
        stopPolling()
        stopProcPolling()
        store.clear()
        session = null
        api = null
        host = null
        agent = null
        sample = null
        series = Series()
        history = emptyList()
        procs = emptyList()
        endpointIndex = 0
        transport = Transport.Idle
        pending = null
        pendingSession = null
        autoEnter = false
        bootError = null
        pairingError = reason
        onboardStep = if (reason != null) OnboardStep.Error else OnboardStep.Intro
    }

    /* --------------------------------- helpers ------------------------------ */

    private fun friendlyPairing(e: Throwable): String = when {
        e is ApiException && e.status == 401 -> "Código rechazado o caducado. Genera otro con `vitals pair`."
        e is ApiException && e.status == 403 -> "El host alcanzó el límite de dispositivos."
        isPinMismatch(e) -> "El certificado no coincide con el pin del QR."
        else -> "No se pudo conectar: ${e.message ?: "error desconocido"}"
    }

    private fun friendly(e: Throwable): String = when {
        isPinMismatch(e) -> "El certificado no coincide con el pin. Vuelve a emparejar."
        e is SSLException -> "TLS rechazado: ${e.message}"
        e is IOException -> "Sin conexión con el host: ${e.message}"
        else -> e.message ?: e::class.java.simpleName
    }

    private fun isPinMismatch(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is CertificateException && cause.message?.contains("pin") == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun deviceName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() }?.let { "android-$it" } ?: "android"

    companion object {
        fun kindOf(url: String): String {
            val hostPart = url.removePrefix("https://").substringBefore(':')
            return when {
                hostPart.startsWith("[") -> "Tailscale"
                hostPart.startsWith("100.") -> "Tailscale"
                hostPart.startsWith("10.") ||
                    hostPart.startsWith("192.168.") ||
                    Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(hostPart) -> "LAN"
                hostPart.startsWith("127.") || hostPart == "localhost" -> "Local"
                else -> "Remoto"
            }
        }
    }
}
