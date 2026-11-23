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
import dev.vitals.app.core.AlertReport
import dev.vitals.app.core.ApiException
import dev.vitals.app.core.CapsReport
import dev.vitals.app.core.DiagnosticsReport
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
import dev.vitals.app.watchdog.WatchState
import dev.vitals.app.watchdog.Watchdog
import dev.vitals.app.watchdog.WatchdogState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.json.JSONArray
import org.json.JSONObject
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
    var tokenStorage by mutableStateOf(store.tokenStorage())
        private set

    /** User preference: keep a foreground watchdog for the paired host. */
    var watching by mutableStateOf(Watchdog.isEnabled(app))
        private set
    var watchState by mutableStateOf(WatchState())
        private set

    var host by mutableStateOf<HostInfo?>(null)
    var agent by mutableStateOf<AgentInfo?>(null)
    var sample by mutableStateOf<Sample?>(null)
    var series by mutableStateOf(Series())
    var history by mutableStateOf<List<Point>>(emptyList())
    var procs by mutableStateOf<List<Proc>>(emptyList())

    var alertReport by mutableStateOf<AlertReport?>(null)
    var alertsError by mutableStateOf<String?>(null)
    var alertsBusy by mutableStateOf(false)
    var capsReport by mutableStateOf<CapsReport?>(null)
    var capsError by mutableStateOf<String?>(null)
    var capsBusy by mutableStateOf(false)
    var diagnostics by mutableStateOf<DiagnosticsReport?>(null)
    var diagnosticsError by mutableStateOf<String?>(null)

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
    val isOperator: Boolean get() = session?.scope == "operator"

    init {
        viewModelScope.launch {
            WatchdogState.state.collect { watchState = it }
        }
        if (session != null) boot()
    }

    /* ------------------------------- lifecycle ------------------------------ */

    fun onVisible() {
        watching = Watchdog.isEnabled(getApplication())
        if (session != null) {
            if (watching) Watchdog.start(getApplication())
            if (transport != Transport.Sse) startStream()
        }
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
        endpointIndex = client.endpointIndex
        streamFailures = 0
        bootError = null

        viewModelScope.launch {
            try {
                val hostInfo = client.host()
                val info = runCatching { client.info() }.getOrNull()
                host = hostInfo
                agent = info

                // Addresses move (DHCP, roam, VPN). The agent reports its
                // current listener set, so adopt it instead of keeping stale
                // endpoints from the QR forever.
                var effective = client
                val fresh = info?.endpoints.orEmpty()
                if (fresh.isNotEmpty() && fresh != current.endpoints) {
                    val updated = current.copy(endpoints = fresh)
                    if (!store.save(updated)) {
                        toast = "No pude guardar la sesión cifrada"
                    }
                    session = updated
                    tokenStorage = store.tokenStorage()
                    endpointIndex = 0
                    effective = VitalsApi(updated)
                }
                api = effective

                val first = effective.metrics()
                main.post {
                    latency = effective.latencyMs
                    applySample(first)
                }
                seedSeries(effective)
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
        if (target == Screen.Rules) loadAlerts()
        if (target == Screen.Caps) loadCaps()
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

    /* --------------------------------- alerts ------------------------------- */

    fun loadAlerts() {
        if (!isOperator) {
            alertsError = "Necesitas un token operator. Reempareja con: vitals pair --operator"
            return
        }
        if (alertsBusy) return
        alertsBusy = true
        alertsError = null
        viewModelScope.launch {
            try {
                val client = api ?: error("Todavía no hay conexión con el host")
                alertReport = client.alerts()
            } catch (e: Throwable) {
                alertsError = commandFailure(e)
            } finally {
                alertsBusy = false
            }
        }
    }

    /** The three templates are deliberately small: id, kind and one threshold. */
    fun applyAlertTemplate(kind: String) {
        val template = when (kind) {
            "disk" -> JSONObject()
                .put("id", "disk-root")
                .put("kind", "disk")
                .put("mount", "/")
                .put("percent", 85)
                .put("days", 7)
            "memory" -> JSONObject()
                .put("id", "memory")
                .put("kind", "memory")
                .put("percent", 90)
            "temperature" -> JSONObject()
                .put("id", "temperature")
                .put("kind", "temperature")
                .put("celsius", 85)
            else -> return
        }
        val id = template.getString("id")
        val rules = JSONArray()
        alertReport?.rules?.filter { it.id != id }?.forEach { rules.put(it.toJson()) }
        rules.put(template)
        setAlertRules(rules, "Regla $id guardada")
    }

    fun deleteAlert(id: String) {
        if (!isOperator) return
        alertsBusy = true
        viewModelScope.launch {
            try {
                val client = api ?: error("Todavía no hay conexión con el host")
                client.command("alerts.delete", JSONObject().put("id", id))
                alertReport = client.alerts()
                toast = "Regla $id eliminada"
            } catch (e: Throwable) {
                alertsError = commandFailure(e)
            } finally {
                alertsBusy = false
            }
        }
    }

    fun silenceAlerts(minutes: Int) {
        if (!isOperator) return
        alertsBusy = true
        viewModelScope.launch {
            try {
                val client = api ?: error("Todavía no hay conexión con el host")
                client.command("alerts.silence", JSONObject().put("minutes", minutes))
                alertReport = client.alerts()
                toast = if (minutes < 60) "Alertas silenciadas ${minutes} min" else "Alertas silenciadas ${minutes / 60} h"
            } catch (e: Throwable) {
                alertsError = commandFailure(e)
            } finally {
                alertsBusy = false
            }
        }
    }

    private fun setAlertRules(rules: JSONArray, message: String) {
        if (!isOperator) {
            alertsError = "Necesitas un token operator. Reempareja con: vitals pair --operator"
            return
        }
        alertsBusy = true
        alertsError = null
        viewModelScope.launch {
            try {
                val client = api ?: error("Todavía no hay conexión con el host")
                client.command("alerts.set", JSONObject().put("rules", rules))
                alertReport = client.alerts()
                toast = message
            } catch (e: Throwable) {
                alertsError = commandFailure(e)
            } finally {
                alertsBusy = false
            }
        }
    }

    /* ------------------------------ capabilities ---------------------------- */

    fun loadCaps() {
        if (!isOperator) {
            capsError = "Necesitas un token operator. Reempareja con: vitals pair --operator"
            return
        }
        if (capsBusy) return
        capsBusy = true
        capsError = null
        viewModelScope.launch {
            try {
                val client = api ?: error("Todavía no hay conexión con el host")
                capsReport = client.caps()
            } catch (e: Throwable) {
                capsError = commandFailure(e)
            } finally {
                capsBusy = false
            }
        }
    }

    fun requestCapability(cap: String, unit: String?, maxBytesMb: Long?) {
        if (!isOperator) return
        val args = JSONObject().put("cap", cap)
        if (unit != null) {
            args.put("units", JSONArray().put(unit.trim()))
        }
        if (maxBytesMb != null) {
            args.put("max_bytes", maxBytesMb * 1024 * 1024)
        }
        capsBusy = true
        viewModelScope.launch {
            try {
                val client = api ?: error("Todavía no hay conexión con el host")
                val response = client.command("caps.request", args)
                val code = response.optJSONObject("result")?.optString("code").orEmpty()
                capsReport = client.caps()
                toast = if (code.isNotBlank()) "Solicitud enviada: código $code" else "Solicitud enviada"
            } catch (e: Throwable) {
                capsError = commandFailure(e)
            } finally {
                capsBusy = false
            }
        }
    }

    /* ------------------------------- diagnostics ---------------------------- */

    fun loadDiagnostics() {
        diagnosticsError = null
        viewModelScope.launch {
            try {
                val client = api ?: return@launch
                val findings = client.diagnostics()
                val suggestions = runCatching { client.suggestions() }.getOrDefault(emptyList())
                diagnostics = DiagnosticsReport(
                    findings = findings.findings,
                    suggestions = suggestions,
                )
            } catch (e: Throwable) {
                diagnosticsError = e.message ?: "Diagnóstico no disponible"
            }
        }
    }

    private fun commandFailure(e: Throwable): String {
        if (e is ApiException && e.status == 401) {
            forget("La sesión ya no es válida. Vuelve a emparejar.")
            return "Sesión no válida"
        }
        return when {
            e is ApiException && e.status == 403 ->
                "El host rechazó la operación: este dispositivo necesita scope operator"
            e is ApiException -> e.message ?: "Error ${e.status}"
            else -> e.message ?: "Error de red"
        }
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
                    scope = result.scope.ifBlank { payload.scope },
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
            if (!store.save(next)) {
                toast = "No pude guardar el token en el Keystore; al reiniciar tendrás que emparejar"
            }
            session = next
            tokenStorage = store.tokenStorage()
        }
        if (session != null) boot()
        if (session != null && watching) Watchdog.start(getApplication())
    }

    fun setWatchdog(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled && session == null) return
        watching = enabled
        Watchdog.setEnabled(app, enabled)
        toast = if (enabled) {
            "Vigilando ${session?.name ?: "el host"}: te aviso si deja de responder"
        } else {
            "Vigilancia desactivada"
        }
    }

    fun forget(reason: String? = null) {
        generation++
        stream?.cancel()
        stream = null
        stopPolling()
        stopProcPolling()
        Watchdog.stop(getApplication(), disable = true)
        watching = false
        store.clear()
        tokenStorage = store.tokenStorage()
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
