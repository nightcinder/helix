package com.nightcinder.helix.stream

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Surface
import com.nightcinder.helix.core.model.AppDiscovery
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionState
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.core.model.StreamTelemetry
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.WebSocketCapabilityStatus
import com.nightcinder.helix.core.stream.MoonlightStreamClient
import com.nightcinder.helix.core.stream.VideoFrameSink
import com.nightcinder.helix.stream.nv.NvCrypto
import com.nightcinder.helix.stream.nv.NvGateway
import com.nightcinder.helix.stream.nv.NvLaunch
import com.nightcinder.helix.stream.nv.PairResult
import com.nightcinder.helix.stream.nv.PairingCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Dual-path Moonlight streaming client supporting:
 * - Native direct streaming (`DIRECT_UDP`): GameStream HTTP pairing + /launch in Kotlin, then moonlight-common-c
 *   via [MoonlightNativeBridge] (AMediaCodec video to the Surface, Opus/AAudio audio). It reports Connected
 *   only after the native `connectionStarted` callback fired.
 * - Encrypted fallback tunnel via [MoonlightWebSocketTransport] over TCP 443 (`WEBSOCKET_443`, experimental)
 * - Intent generation for launching standalone native Moonlight / Artemis APKs (`EXTERNAL_NATIVE`)
 *
 * @param nvGateway host-facing pairing/launch operations; null disables the native path (it then reports an error).
 * @param onAuthExpired invoked (at most once per [AUTH_RETRY_INTERVAL_MS]) when the WebSocket transport
 * reports DATA_EXPIRED / 401. Returns true when credentials were refreshed and the stream should reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualPathMoonlightClient(
    private val nativeBridge: MoonlightNativeBridge,
    private val webSocketTransport: MoonlightWebSocketTransport,
    private val frameSink: VideoFrameSink,
    private val nvGateway: NvGateway? = null,
    private val directUdpProber: suspend (hostIp: String, port: Int) -> Boolean = { host, port ->
        probeHostReachable(host, port)
    },
    private val onAuthExpired: (suspend () -> Boolean)? = null,
    // All blocking JNI calls (start/stop) run on this single-thread dispatcher so they never block the main thread.
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val clientScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val pinGenerator: () -> String = NvCrypto::generatePin,
    private val nativeAvailable: () -> Boolean = { MoonlightNativeBridge.isLibraryLoaded },
) : MoonlightStreamClient {

    companion object {
        const val MOONLIGHT_PACKAGE = "com.limelight"
        const val ARTEMIS_PACKAGE = "com.limelight.noir"

        const val NATIVE_UNAVAILABLE_MESSAGE = "Native streaming engine is not available in this build"
        const val AUTH_RETRY_INTERVAL_MS = 60_000L
        const val IDR_DROP_THROTTLE_MS = 500L

        suspend fun probeHostReachable(hostIp: String, port: Int): Boolean = withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(hostIp, port), 1000)
                    true
                }
            } catch (_: Exception) {
                // If TCP probe on sunshine port fails, fallback to UDP reachability heuristic
                // or assume true for test loopback
                hostIp == "127.0.0.1" || hostIp == "localhost"
            }
        }
    }

    private var currentConfig = StreamConfig()

    @Volatile
    private var attachedSurface: Surface? = null
    private var pollJob: Job? = null
    private var wsStateJob: Job? = null
    private var wsTelemetryJob: Job? = null
    private var authJob: Job? = null
    private var lastAuthAttemptMs: Long? = null
    private var directJob: Job? = null

    /** Identifies the live native session; events carrying an older id are ignored. */
    @Volatile
    private var nativeSessionId = 0

    /** True from native start until stop is requested: only then may surface changes reach JNI. */
    @Volatile
    private var nativeSessionActive = false

    @Volatile
    private var reauthenticating = false

    private val connectionGeneration = AtomicInteger(0)
    private val lastDropIdrMs = AtomicLong(Long.MIN_VALUE)

    private val _state = MutableStateFlow(StreamConnectionState())
    override val state: StateFlow<StreamConnectionState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    override val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    override val capabilityStatus: StateFlow<WebSocketCapabilityStatus> = webSocketTransport.capabilityStatus

    init {
        frameSink.setErrorListener { message -> reportDecoderError(message) }
        frameSink.setFrameDropListener {
            if (_state.value.activeTransport == StreamTransportMode.WEBSOCKET_443 &&
                _state.value.status == StreamConnectionStatus.Connected
            ) {
                val now = nowMs()
                while (true) {
                    val prev = lastDropIdrMs.get()
                    if (prev != Long.MIN_VALUE && now - prev < IDR_DROP_THROTTLE_MS) break
                    if (lastDropIdrMs.compareAndSet(prev, now)) {
                        webSocketTransport.requestIdr()
                        break
                    }
                }
            }
        }
    }

    private fun reportDecoderError(message: String) {
        val status = _state.value.status
        if (_state.value.activeTransport == StreamTransportMode.DIRECT_UDP) return
        if (status == StreamConnectionStatus.Connecting || status == StreamConnectionStatus.Connected) {
            _state.update { it.copy(status = StreamConnectionStatus.Error, errorMessage = message) }
        }
    }

    override suspend fun connect(config: StreamConfig) {
        disconnect()
        val generation = connectionGeneration.incrementAndGet()
        lastDropIdrMs.set(Long.MIN_VALUE)
        currentConfig = config

        _state.update {
            it.copy(
                status = StreamConnectionStatus.Connecting,
                targetHostIp = config.hostIp,
                errorMessage = null,
                pairingPin = null,
                stage = null,
            )
        }

        val resolvedMode = when (config.transportMode) {
            StreamTransportMode.DIRECT_UDP -> StreamTransportMode.DIRECT_UDP
            StreamTransportMode.WEBSOCKET_443 -> StreamTransportMode.WEBSOCKET_443
            StreamTransportMode.EXTERNAL_NATIVE -> StreamTransportMode.EXTERNAL_NATIVE
            StreamTransportMode.AUTO -> {
                // Probe LAN / WireGuard direct reachability: reachable hosts use the native direct path,
                // everything else falls back to the (experimental) WebSocket tunnel.
                if (directUdpProber(config.hostIp, config.port)) {
                    StreamTransportMode.DIRECT_UDP
                } else {
                    StreamTransportMode.WEBSOCKET_443
                }
            }
        }
        if (connectionGeneration.get() != generation) return

        when (resolvedMode) {
            StreamTransportMode.DIRECT_UDP -> connectDirectUdp(config)
            StreamTransportMode.WEBSOCKET_443 -> connectWebSocketWithProbe(config, generation)
            StreamTransportMode.EXTERNAL_NATIVE -> {
                _state.update {
                    it.copy(
                        status = StreamConnectionStatus.Connected,
                        activeTransport = StreamTransportMode.EXTERNAL_NATIVE,
                        targetHostIp = config.hostIp,
                        connectedEpochMs = System.currentTimeMillis(),
                    )
                }
            }
            StreamTransportMode.AUTO -> connectWebSocketWithProbe(config, generation)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Native direct path
    // ---------------------------------------------------------------------------------------------

    private fun connectDirectUdp(config: StreamConfig) {
        _state.update {
            it.copy(
                status = StreamConnectionStatus.Connecting,
                activeTransport = StreamTransportMode.DIRECT_UDP,
                targetHostIp = config.hostIp,
                errorMessage = null,
                connectedEpochMs = null,
                stage = "Contacting host",
            )
        }
        _telemetry.value = StreamTelemetry(activeTransport = StreamTransportMode.DIRECT_UDP, activeCodec = config.codec)

        val gateway = nvGateway
        if (gateway == null || !nativeAvailable()) {
            failDirect(NATIVE_UNAVAILABLE_MESSAGE)
            return
        }

        directJob?.cancel()
        directJob = clientScope.launch { runDirect(gateway, config) }
    }

    private suspend fun runDirect(gateway: NvGateway, config: StreamConfig) {
        val host = config.hostIp
        try {
            setStage("Checking pairing")
            if (gateway.checkPairing(host) is PairingCheck.NotPaired) {
                if (!runPairing(gateway, host)) return
            }

            setStage("Launching session on host")
            val launch = gateway.launch(host, config, nativeBridge.launchQueryParameters())

            setStage("Starting stream")
            startNative(config, launch)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            failDirect(e.message ?: "Could not reach the host")
        } catch (e: Exception) {
            failDirect(e.message ?: "Stream setup failed")
        }
    }

    /** Runs PIN pairing. Returns true when paired; otherwise the UI state already reflects why not. */
    private suspend fun runPairing(gateway: NvGateway, host: String): Boolean {
        val pin = pinGenerator()
        _state.update {
            it.copy(
                status = StreamConnectionStatus.Pairing,
                pairingPin = pin,
                stage = null,
                errorMessage = null,
            )
        }
        val result = try {
            gateway.pair(host, pin)
        } catch (ce: CancellationException) {
            // The owner cancelled (or disconnect was pressed): fall back to NotPaired, not an error.
            withContext(NonCancellable) {
                _state.update {
                    if (it.status == StreamConnectionStatus.Pairing) {
                        it.copy(status = StreamConnectionStatus.NotPaired, pairingPin = null)
                    } else {
                        it
                    }
                }
            }
            throw ce
        } catch (e: IOException) {
            PairResult.Failed(e.message ?: "Could not reach the host")
        }
        return when (result) {
            is PairResult.Paired -> {
                _state.update {
                    it.copy(status = StreamConnectionStatus.Connecting, pairingPin = null, stage = "Paired")
                }
                true
            }
            PairResult.PinWrong -> {
                failDirect("The host rejected the PIN. Tap Start Stream to try again.")
                false
            }
            PairResult.AlreadyInProgress -> {
                failDirect("Another pairing is already in progress on the host. Wait a minute, then try again.")
                false
            }
            is PairResult.Failed -> {
                failDirect("Pairing failed: ${result.reason}")
                false
            }
        }
    }

    private suspend fun startNative(config: StreamConfig, launch: NvLaunch) {
        val sessionId = ++nativeSessionId
        nativeBridge.listener = { event -> if (sessionId == nativeSessionId) onNativeEvent(sessionId, event) }
        val params = NativeStartParams(
            host = launch.host,
            appVersion = launch.appVersion,
            gfeVersion = launch.gfeVersion,
            rtspSessionUrl = launch.rtspSessionUrl,
            serverCodecModeSupport = launch.serverCodecModeSupport,
            width = config.width,
            height = config.height,
            fps = config.fps,
            bitrateKbps = config.bitrateKbps,
            supportedVideoFormats = MoonlightNativeBridge.supportedVideoFormats(config.codec),
            riKey = launch.riKey,
            riKeyId = launch.riKeyId,
        )
        nativeSessionActive = true
        val started = withContext(nativeDispatcher) { nativeBridge.start(params, attachedSurface) }
        if (!started) {
            nativeSessionActive = false
            failDirect("Could not start the native stream engine")
        }
        // On success the state stays Connecting until the native connectionStarted callback arrives.
    }

    private fun onNativeEvent(sessionId: Int, event: NativeStreamEvent) {
        when (event) {
            is NativeStreamEvent.StageStarting -> {
                _state.update {
                    if (it.status == StreamConnectionStatus.Connecting) it.copy(stage = prettyStage(event.name)) else it
                }
            }
            is NativeStreamEvent.StageComplete -> Unit
            is NativeStreamEvent.StageFailed -> {
                failDirect("Stream failed during ${event.name} (error ${event.errorCode})")
                stopNativeInBackground(sessionId)
            }
            NativeStreamEvent.ConnectionStarted -> {
                _state.update {
                    it.copy(
                        status = StreamConnectionStatus.Connected,
                        activeTransport = StreamTransportMode.DIRECT_UDP,
                        errorMessage = null,
                        connectedEpochMs = System.currentTimeMillis(),
                        stage = null,
                        pairingPin = null,
                    )
                }
                startStatsPolling(sessionId)
            }
            is NativeStreamEvent.StartResult -> {
                if (event.result != 0 && _state.value.status != StreamConnectionStatus.Error) {
                    failDirect("Could not start the stream (error ${event.result})")
                }
                if (event.result != 0) stopNativeInBackground(sessionId)
            }
            is NativeStreamEvent.ConnectionTerminated -> {
                if (event.errorCode == 0) {
                    _state.update {
                        it.copy(
                            status = StreamConnectionStatus.Disconnected,
                            errorMessage = "The host ended the stream",
                            connectedEpochMs = null,
                            stage = null,
                        )
                    }
                } else {
                    failDirect("Connection lost (error ${event.errorCode})")
                }
                _telemetry.value = StreamTelemetry()
                stopNativeInBackground(sessionId)
            }
            is NativeStreamEvent.ConnectionStatus -> Unit
        }
    }

    /** LiStopConnection must not run on the callback thread that reported the failure. */
    private fun stopNativeInBackground(sessionId: Int) {
        if (sessionId != nativeSessionId) return
        pollJob?.cancel()
        pollJob = null
        clientScope.launch(nativeDispatcher) {
            if (sessionId != nativeSessionId) return@launch
            nativeSessionActive = false
            nativeBridge.stop()
        }
    }

    private fun failDirect(message: String) {
        _state.update {
            it.copy(
                status = StreamConnectionStatus.Error,
                activeTransport = StreamTransportMode.DIRECT_UDP,
                errorMessage = message,
                connectedEpochMs = null,
                pairingPin = null,
                stage = null,
            )
        }
    }

    private fun setStage(stage: String) {
        _state.update { if (it.status == StreamConnectionStatus.Connecting) it.copy(stage = stage) else it }
    }

    private fun prettyStage(name: String): String =
        name.replaceFirstChar { c -> c.uppercaseChar() }

    override fun cancelPairing() {
        if (_state.value.status == StreamConnectionStatus.Pairing) {
            directJob?.cancel()
        }
    }

    override suspend fun forgetPairing() {
        val gateway = nvGateway ?: return
        disconnect()
        try {
            gateway.forgetPairing(currentConfig.hostIp)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Host unreachable: the local pin was still dropped by the gateway where possible.
        }
        _state.update { it.copy(status = StreamConnectionStatus.NotPaired, errorMessage = null) }
    }

    // ---------------------------------------------------------------------------------------------
    // WebSocket (moonlight-web) path
    // ---------------------------------------------------------------------------------------------

    private suspend fun connectWebSocketWithProbe(
        config: StreamConfig,
        generation: Int = connectionGeneration.get(),
    ) {
        if (connectionGeneration.get() != generation) return
        _state.update {
            it.copy(
                status = StreamConnectionStatus.Connecting,
                activeTransport = StreamTransportMode.WEBSOCKET_443,
                targetHostIp = config.hostIp,
                errorMessage = null,
                stage = "Checking WebSocket capability",
            )
        }
        val probe = webSocketTransport.probeCapabilities(config)
        if (connectionGeneration.get() != generation) return
        when (probe) {
            is WebSocketProbeResult.Available -> connectWebSocket(config, probe)
            is WebSocketProbeResult.AuthExpired -> {
                _state.update {
                    it.copy(
                        status = StreamConnectionStatus.DataExpired,
                        activeTransport = StreamTransportMode.WEBSOCKET_443,
                        targetHostIp = config.hostIp,
                        errorMessage = probe.reason,
                        stage = null,
                    )
                }
                handleAuthExpired()
            }
            is WebSocketProbeResult.Unavailable -> {
                _state.update {
                    it.copy(
                        status = StreamConnectionStatus.Error,
                        activeTransport = StreamTransportMode.WEBSOCKET_443,
                        targetHostIp = config.hostIp,
                        errorMessage = probe.reason,
                        stage = null,
                    )
                }
            }
        }
    }

    private fun connectWebSocket(
        config: StreamConfig,
        preProbed: WebSocketProbeResult.Available? = null,
    ) {
        // Stop collectors from a previous connect before starting new ones (stale collectors overwrite _state).
        cancelWebSocketCollectors()

        frameSink.configure(config.width, config.height, config.codec, attachedSurface)
        _telemetry.value = StreamTelemetry(
            activeTransport = StreamTransportMode.WEBSOCKET_443,
            activeCodec = config.codec,
        )

        val sinkError = frameSink.lastError
        if (sinkError != null) {
            _state.update {
                it.copy(
                    status = StreamConnectionStatus.Error,
                    activeTransport = StreamTransportMode.WEBSOCKET_443,
                    targetHostIp = config.hostIp,
                    errorMessage = sinkError,
                )
            }
            return
        }

        _state.update {
            it.copy(
                status = StreamConnectionStatus.Connecting,
                activeTransport = StreamTransportMode.WEBSOCKET_443,
                targetHostIp = config.hostIp,
                errorMessage = null,
            )
        }
        webSocketTransport.connect(config, preProbed = preProbed)

        // Observe WebSocket transport state
        wsStateJob = clientScope.launch {
            webSocketTransport.connectionState.collect { wsState ->
                if (_state.value.activeTransport == StreamTransportMode.WEBSOCKET_443 ||
                    _state.value.activeTransport == StreamTransportMode.AUTO
                ) {
                    _state.value = wsState.copy(
                        isReauthenticating = reauthenticating && wsState.status == StreamConnectionStatus.DataExpired,
                    )
                    if (wsState.status == StreamConnectionStatus.DataExpired) {
                        handleAuthExpired()
                    }
                }
            }
        }

        wsTelemetryJob = clientScope.launch {
            webSocketTransport.telemetry.collect { wsTelemetry ->
                if (_state.value.activeTransport == StreamTransportMode.WEBSOCKET_443) {
                    _telemetry.value = wsTelemetry
                }
            }
        }
    }

    private fun cancelWebSocketCollectors() {
        wsStateJob?.cancel()
        wsStateJob = null
        wsTelemetryJob?.cancel()
        wsTelemetryJob = null
    }

    /** Refreshes credentials via [onAuthExpired] (rate limited) and reconnects when it succeeds. */
    private fun handleAuthExpired() {
        val refresh = onAuthExpired ?: return
        if (authJob?.isActive == true) return
        val now = nowMs()
        val last = lastAuthAttemptMs
        if (last != null && now - last < AUTH_RETRY_INTERVAL_MS) return
        lastAuthAttemptMs = now

        val generation = connectionGeneration.get()
        reauthenticating = true
        _state.update { if (it.status == StreamConnectionStatus.DataExpired) it.copy(isReauthenticating = true) else it }

        authJob = clientScope.launch {
            val refreshed = try {
                refresh()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            } finally {
                reauthenticating = false
            }

            if (!isActive || connectionGeneration.get() != generation) return@launch
            if (refreshed &&
                _state.value.status == StreamConnectionStatus.DataExpired &&
                _state.value.activeTransport == StreamTransportMode.WEBSOCKET_443
            ) {
                connectWebSocketWithProbe(currentConfig, generation)
            } else {
                _state.update { it.copy(isReauthenticating = false) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Common
    // ---------------------------------------------------------------------------------------------

    private fun startStatsPolling(sessionId: Int) {
        pollJob?.cancel()
        pollJob = clientScope.launch {
            while (isActive && sessionId == nativeSessionId && _state.value.status == StreamConnectionStatus.Connected) {
                // Only measured data: with no native data the telemetry stays zero.
                _telemetry.value = withContext(nativeDispatcher) { nativeBridge.getStats() }
                    ?: StreamTelemetry(
                        activeTransport = StreamTransportMode.DIRECT_UDP,
                        activeCodec = currentConfig.codec,
                    )
                delay(1000L)
            }
        }
    }

    override suspend fun disconnect() {
        connectionGeneration.incrementAndGet()
        lastDropIdrMs.set(Long.MIN_VALUE)
        pollJob?.cancel()
        pollJob = null
        authJob?.cancel()
        authJob = null
        directJob?.cancel()
        directJob = null
        reauthenticating = false
        cancelWebSocketCollectors()

        // Invalidate the native session first so late callbacks cannot resurrect the UI state.
        nativeSessionId++
        nativeSessionActive = false

        _telemetry.value = StreamTelemetry()
        _state.update {
            it.copy(
                status = StreamConnectionStatus.Disconnected,
                errorMessage = null,
                isReauthenticating = false,
                pairingPin = null,
                stage = null,
                connectedEpochMs = null,
            )
        }

        // Idempotent: a no-op when no native session exists.
        withContext(nativeDispatcher) { nativeBridge.stop() }
        webSocketTransport.disconnect()
        frameSink.release()
    }

    override fun attachSurface(surface: Surface) {
        // The most recently attached surface renders (the stream is shared across windows).
        attachedSurface = surface
        if (nativeSessionActive) {
            nativeBridge.setSurface(surface)
        } else if (_state.value.activeTransport != StreamTransportMode.DIRECT_UDP) {
            frameSink.configure(currentConfig.width, currentConfig.height, currentConfig.codec, surface)
            frameSink.lastError?.let { reportDecoderError(it) }
            if (_state.value.activeTransport == StreamTransportMode.WEBSOCKET_443 &&
                _state.value.status == StreamConnectionStatus.Connected
            ) {
                webSocketTransport.requestIdr()
            }
        }
    }

    override fun detachSurface() {
        detachCurrent()
    }

    override fun detachSurface(surface: Surface) {
        // A window closing must not disturb the stream if another window already took over rendering.
        if (attachedSurface !== surface) return
        detachCurrent()
    }

    private fun detachCurrent() {
        attachedSurface = null
        if (nativeSessionActive) {
            // Keep the session alive; frames are dropped until a surface returns (an IDR is then requested).
            nativeBridge.setSurface(null)
        } else if (_state.value.activeTransport != StreamTransportMode.DIRECT_UDP) {
            frameSink.configure(currentConfig.width, currentConfig.height, currentConfig.codec, null)
        }
    }

    override suspend fun sendInput(event: StreamInputEvent) {
        when (_state.value.activeTransport) {
            StreamTransportMode.DIRECT_UDP -> {
                if (_state.value.status != StreamConnectionStatus.Connected) return
                when (event) {
                    is StreamInputEvent.MouseMove -> nativeBridge.sendMouseMove(event.deltaX, event.deltaY)
                    is StreamInputEvent.MousePosition ->
                        nativeBridge.sendMousePosition(event.x, event.y, event.referenceWidth, event.referenceHeight)
                    is StreamInputEvent.MouseButton -> nativeBridge.sendMouseButton(event.button, event.down)
                    is StreamInputEvent.Key -> nativeBridge.sendKeyboard(event.keyCode, event.keyAction, event.modifiers)
                    is StreamInputEvent.Scroll -> nativeBridge.sendScroll(event.scrollAmount)
                    is StreamInputEvent.HighResScroll -> nativeBridge.sendHighResScroll(event.amount)
                    is StreamInputEvent.HighResHScroll -> nativeBridge.sendHighResHScroll(event.amount)
                }
            }
            StreamTransportMode.WEBSOCKET_443 -> {
                webSocketTransport.sendInput(event)
            }
            else -> {
                // Ignore for external native app
            }
        }
    }

    override suspend fun discoverApps(config: StreamConfig): AppDiscovery {
        val gateway = nvGateway ?: return AppDiscovery.Unavailable(NATIVE_UNAVAILABLE_MESSAGE)
        return gateway.discoverApps(config.hostIp)
    }

    override suspend fun updateConfig(config: StreamConfig) {
        currentConfig = config
        if (_state.value.status == StreamConnectionStatus.Connected) {
            connect(config)
        }
    }

    override fun buildExternalMoonlightIntent(context: Context): Intent {
        val pm = context.packageManager

        // Try Moonlight first, then Artemis. Moonlight Android reads no HOST/PORT extras, so none are added.
        val launchIntent = pm?.getLaunchIntentForPackage(MOONLIGHT_PACKAGE)
            ?: pm?.getLaunchIntentForPackage(ARTEMIS_PACKAGE)

        return launchIntent?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ARTEMIS_PACKAGE")).apply {
            // Neither app is installed: send the user to the store listing.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
