package com.nightcinder.helix.terminal

import com.nightcinder.helix.api.DefaultTermixJson
import com.nightcinder.helix.core.api.TermixApi
import com.nightcinder.helix.core.model.AuthError
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.TerminalConnectionState
import com.nightcinder.helix.core.model.TerminalSessionConfig
import com.nightcinder.helix.core.model.TerminalSize
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.core.model.TermixApiException
import com.nightcinder.helix.core.terminal.TerminalEvent
import com.nightcinder.helix.core.terminal.TerminalTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Termix v26.11.2 WebSocket transport for SSH terminals (`/plugin-ws/ssh-terminal/terminal`)
 * and Docker container interactive consoles (`/plugin-ws/docker/console`).
 *
 * Handshake authentication:
 * 1. Mints a 60s single-use ticket via [TermixApi.createWsTicket] (`POST /users/ws-ticket`).
 * 2. Connects with `Sec-WebSocket-Protocol: termix.jwt.<ticket>`.
 * 3. Sends initial `connectToHost` (SSH) or `connect` (Docker console) frame on open.
 * 4. Maps close code `1008` or `DATA_EXPIRED` payloads to [TerminalEvent.DataExpired].
 */
class TermixWebSocketTerminalTransport(
    private val client: WebSocket.Factory,
    private val termixApi: TermixApi,
    private val baseUrlProvider: () -> String,
    private val json: Json = DefaultTermixJson,
    private val onDataExpiredReauth: (suspend () -> Boolean)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clockMs: () -> Long = System::currentTimeMillis,
) : TerminalTransport {

    constructor(
        client: OkHttpClient,
        termixApi: TermixApi,
        baseUrl: String = AuthentikConfig.DEFAULT_TERMIX_URL,
        json: Json = DefaultTermixJson,
        onDataExpiredReauth: (suspend () -> Boolean)? = null,
    ) : this(
        client = client,
        termixApi = termixApi,
        baseUrlProvider = { baseUrl },
        json = json,
        onDataExpiredReauth = onDataExpiredReauth,
    )

    companion object {
        const val SSH_WS_PATH = "/plugin-ws/ssh-terminal/terminal"
        const val DOCKER_CONSOLE_WS_PATH = "/plugin-ws/docker/console"
        const val SUBPROTOCOL_PREFIX = "termix.jwt."
        const val WS_CLOSE_NORMAL = 1000
        const val WS_CLOSE_DATA_EXPIRED = AuthError.WS_CLOSE_DATA_EXPIRED
        const val MAX_RECONNECT_ATTEMPTS = 6
        const val REAUTH_MIN_INTERVAL_MS = 60_000L
        private const val BACKOFF_BASE_MS = 1_000L
        private const val BACKOFF_MAX_MS = 30_000L

        /** 1 s, 2 s, 4 s ... capped at 30 s. [attempt] is 1-based. */
        fun backoffDelayMs(attempt: Int): Long =
            (BACKOFF_BASE_MS shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(BACKOFF_MAX_MS)
    }

    private data class ActiveSocketSession(
        val config: TerminalSessionConfig,
        val webSocket: WebSocket,
    )

    private sealed interface OpenResult {
        object Opened : OpenResult
        data class Expired(val code: Int, val reason: String) : OpenResult
        data class Failed(val message: String, val cause: Throwable?) : OpenResult
    }

    private val sessions = ConcurrentHashMap<String, ActiveSocketSession>()
    private val retryJobs = ConcurrentHashMap<String, Job>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val lastReauthAtMs = ConcurrentHashMap<String, Long>()
    private val lastSizes = ConcurrentHashMap<String, TerminalSize>()
    private val reauthLock = Any()
    private var reauthInFlight: Deferred<Boolean>? = null

    private val _events = MutableSharedFlow<TerminalEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<TerminalEvent> = _events.asSharedFlow()

    override suspend fun connect(config: TerminalSessionConfig) {
        val tabId = config.tabId
        // A new connect() supersedes any pending auto-reconnect for this tab
        cancelRetry(tabId)
        reconnectAttempts.remove(tabId)
        lastSizes[tabId] = config.size
        // Close any existing socket for this tabId first (it becomes stale and stays silent)
        sessions.remove(tabId)?.webSocket?.close(WS_CLOSE_NORMAL, "Reconnecting")

        _events.tryEmit(
            TerminalEvent.StateChanged(
                tabId = tabId,
                state = TerminalConnectionState.Connecting,
            )
        )

        when (val result = openSocket(config)) {
            is OpenResult.Opened -> Unit
            is OpenResult.Expired -> handleDataExpired(config, result.code, result.reason)
            is OpenResult.Failed -> {
                _events.tryEmit(
                    TerminalEvent.Error(
                        tabId = tabId,
                        message = result.message,
                        cause = result.cause,
                    )
                )
                _events.tryEmit(
                    TerminalEvent.StateChanged(
                        tabId = tabId,
                        state = TerminalConnectionState.Error,
                        message = result.message,
                    )
                )
            }
        }
    }

    /** Mints a fresh ws-ticket (they expire after 60 s) and opens + registers a new socket. */
    private suspend fun openSocket(config: TerminalSessionConfig): OpenResult {
        val tabId = config.tabId
        val wsTicket = try {
            termixApi.createWsTicket()
        } catch (e: TermixApiException.DataExpired) {
            return OpenResult.Expired(e.wsCloseCode ?: WS_CLOSE_DATA_EXPIRED, e.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return OpenResult.Failed(e.message ?: "Failed to mint WebSocket ticket", e)
        }

        val ticketValue = wsTicket.ticket.trim()
        if (ticketValue.isEmpty()) {
            return OpenResult.Failed("Received empty WebSocket ticket from /users/ws-ticket", null)
        }

        val wsUrl = resolveEndpointUrl(config.target)
        val request = Request.Builder()
            .url(wsUrl)
            .header("Sec-WebSocket-Protocol", "$SUBPROTOCOL_PREFIX$ticketValue")
            .build()

        // Callbacks synchronize on [lock] so none can run before the session is registered.
        val lock = Any()
        val listener = createListener(config, lock)
        synchronized(lock) {
            val webSocket = client.newWebSocket(request, listener)
            sessions.put(tabId, ActiveSocketSession(config = config, webSocket = webSocket))
                ?.webSocket?.takeIf { it !== webSocket }?.close(WS_CLOSE_NORMAL, "Superseded")
        }
        return OpenResult.Opened
    }

    private fun cancelRetry(tabId: String) {
        retryJobs.remove(tabId)?.cancel()
    }

    /** Schedules one reconnect attempt with exponential backoff (1 s, 2 s, 4 s ... max 30 s, max 6). */
    private fun scheduleReconnect(config: TerminalSessionConfig) {
        val tabId = config.tabId
        val attempt = reconnectAttempts.merge(tabId, 1) { old, one -> old + one } ?: 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts.remove(tabId)
            val msg = "Reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts"
            _events.tryEmit(TerminalEvent.Error(tabId = tabId, message = msg))
            _events.tryEmit(
                TerminalEvent.StateChanged(
                    tabId = tabId,
                    state = TerminalConnectionState.Error,
                    message = msg,
                )
            )
            return
        }
        _events.tryEmit(
            TerminalEvent.StateChanged(
                tabId = tabId,
                state = TerminalConnectionState.Reconnecting,
                message = "Reconnecting (attempt $attempt/$MAX_RECONNECT_ATTEMPTS)",
            )
        )
        val job = scope.launch {
            delay(backoffDelayMs(attempt))
            // SSH targets re-attach to tmux via tmuxAttachSession in the initial handshake frame.
            val reconnectConfig = config.copy(size = lastSizes[tabId] ?: config.size)
            when (val result = openSocket(reconnectConfig)) {
                is OpenResult.Opened -> Unit
                is OpenResult.Expired -> handleDataExpired(reconnectConfig, result.code, result.reason)
                is OpenResult.Failed -> scheduleReconnect(reconnectConfig)
            }
        }
        retryJobs.put(tabId, job)?.cancel()
    }

    private fun handleDataExpired(config: TerminalSessionConfig, code: Int, reason: String) {
        val tabId = config.tabId
        val reauth = onDataExpiredReauth
        val now = clockMs()
        val last = lastReauthAtMs[tabId]
        if (reauth == null || (last != null && now - last < REAUTH_MIN_INTERVAL_MS)) {
            emitDataExpired(tabId, code, reason)
            return
        }
        lastReauthAtMs[tabId] = now
        cancelRetry(tabId)
        sessions.remove(tabId)?.webSocket?.close(WS_CLOSE_NORMAL, "Re-authenticating")
        _events.tryEmit(
            TerminalEvent.StateChanged(
                tabId = tabId,
                state = TerminalConnectionState.Reconnecting,
                message = "Re-authenticating",
            )
        )
        val job = scope.launch {
            val ok = try {
                reauthSingleFlight(reauth).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                emitDataExpired(tabId, code, reason)
                return@launch
            }
            val reconnectConfig = config.copy(size = lastSizes[tabId] ?: config.size)
            when (val result = openSocket(reconnectConfig)) {
                is OpenResult.Opened -> Unit
                is OpenResult.Expired -> emitDataExpired(tabId, result.code, result.reason)
                is OpenResult.Failed -> scheduleReconnect(reconnectConfig)
            }
        }
        retryJobs.put(tabId, job)?.cancel()
    }

    /** Runs [reauth] once; concurrent callers (other tabs) await the same in-flight attempt. */
    private fun reauthSingleFlight(reauth: suspend () -> Boolean): Deferred<Boolean> =
        synchronized(reauthLock) {
            reauthInFlight?.takeIf { it.isActive } ?: scope.async { reauth() }.also { reauthInFlight = it }
        }

    override suspend fun sendInput(tabId: String, data: String) {
        val session = sessions[tabId] ?: return
        val payload = buildJsonObject {
            put("type", "input")
            put("data", data)
        }
        session.webSocket.send(json.encodeToString(JsonObject.serializer(), payload))
    }

    override suspend fun resize(tabId: String, cols: Int, rows: Int) {
        val session = sessions[tabId] ?: return
        val safeCols = cols.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)
        lastSizes[tabId] = TerminalSize(cols = safeCols, rows = safeRows)
        val payload = buildJsonObject {
            put("type", "resize")
            put("cols", safeCols)
            put("rows", safeRows)
            putJsonObject("data") {
                put("cols", safeCols)
                put("rows", safeRows)
            }
        }
        session.webSocket.send(json.encodeToString(JsonObject.serializer(), payload))
    }

    override suspend fun attachTmux(tabId: String, session: String) {
        val active = sessions[tabId] ?: return
        val payload = buildJsonObject {
            put("type", "tmux_attach")
            put("session", session)
            putJsonObject("data") {
                put("session", session)
            }
        }
        active.webSocket.send(json.encodeToString(JsonObject.serializer(), payload))
    }

    override suspend fun detachTmux(tabId: String) {
        val active = sessions[tabId] ?: return
        val payload = buildJsonObject {
            put("type", "tmux_detach")
            putJsonObject("data") {}
        }
        active.webSocket.send(json.encodeToString(JsonObject.serializer(), payload))
    }

    suspend fun attachSession(
        tabId: String,
        sessionId: String,
        size: TerminalSize = TerminalSize(),
    ) {
        val active = sessions[tabId] ?: return
        val payload = buildJsonObject {
            put("type", "attachSession")
            put("sessionId", sessionId)
            putJsonObject("data") {
                put("sessionId", sessionId)
                put("cols", size.cols)
                put("rows", size.rows)
            }
        }
        active.webSocket.send(json.encodeToString(JsonObject.serializer(), payload))
    }

    suspend fun sendPing(tabId: String) {
        val active = sessions[tabId] ?: return
        val payload = buildJsonObject {
            put("type", "ping")
        }
        active.webSocket.send(json.encodeToString(JsonObject.serializer(), payload))
    }

    override suspend fun disconnect(tabId: String) {
        cancelRetry(tabId)
        reconnectAttempts.remove(tabId)
        lastSizes.remove(tabId)
        val removed = sessions.remove(tabId)
        removed?.webSocket?.close(WS_CLOSE_NORMAL, "Client disconnected")
        _events.tryEmit(
            TerminalEvent.StateChanged(
                tabId = tabId,
                state = TerminalConnectionState.Disconnected,
            )
        )
    }

    override suspend fun disconnectAll() {
        val keys = (sessions.keys + retryJobs.keys).toSet()
        for (tabId in keys) {
            disconnect(tabId)
        }
    }

    fun resolveEndpointUrl(target: TerminalTarget): String {
        val path = when (target) {
            is TerminalTarget.SshHost -> SSH_WS_PATH
            is TerminalTarget.DockerConsole -> DOCKER_CONSOLE_WS_PATH
        }
        val rawBase = baseUrlProvider().trim().trimEnd('/')
        val wsBase = when {
            rawBase.startsWith("https://", ignoreCase = true) ->
                "wss://" + rawBase.substring("https://".length)
            rawBase.startsWith("http://", ignoreCase = true) ->
                "ws://" + rawBase.substring("http://".length)
            else -> rawBase
        }
        return "$wsBase$path"
    }

    fun buildInitialHandshakeFrame(config: TerminalSessionConfig): String {
        val cols = config.size.cols.coerceAtLeast(1)
        val rows = config.size.rows.coerceAtLeast(1)

        val root = when (val target = config.target) {
            is TerminalTarget.SshHost -> buildJsonObject {
                put("type", "connectToHost")
                putJsonObject("data") {
                    putJsonObject("hostConfig") {
                        put("id", target.hostId)
                        if (target.hostName.isNotBlank()) put("name", target.hostName)
                        config.hostIp?.takeIf { it.isNotBlank() }?.let { put("ip", it) }
                        put("port", config.hostPort)
                        config.hostUsername?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                    }
                    put("cols", cols)
                    put("rows", rows)
                    target.initialPath?.takeIf { it.isNotBlank() }?.let { put("initialPath", it) }
                    target.executeCommand?.takeIf { it.isNotBlank() }?.let { put("executeCommand", it) }
                    target.tmuxSession?.takeIf { it.isNotBlank() }?.let { put("tmuxAttachSession", it) }
                }
            }

            is TerminalTarget.DockerConsole -> buildJsonObject {
                put("type", "connect")
                putJsonObject("data") {
                    putJsonObject("hostConfig") {
                        put("id", target.hostId)
                        if (target.hostName.isNotBlank()) put("name", target.hostName)
                    }
                    put("containerId", target.containerId)
                    put("shell", target.shell.ifBlank { "/bin/sh" })
                    put("cols", cols)
                    put("rows", rows)
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Every callback acts only if its socket is still the registered one for the tab; stale sockets
     * (replaced by connect() or closed by disconnect()) emit nothing. Close/failure is handled once.
     */
    private fun createListener(config: TerminalSessionConfig, lock: Any): WebSocketListener {
        val tabId = config.tabId
        val ended = AtomicBoolean(false)
        fun isCurrent(webSocket: WebSocket) = sessions[tabId]?.webSocket === webSocket

        /** Claims the single end-of-life handling for this socket; false if stale or already handled. */
        fun claimEnd(webSocket: WebSocket): Boolean {
            val session = sessions[tabId]
            if (session == null || session.webSocket !== webSocket) return false
            if (!ended.compareAndSet(false, true)) return false
            return sessions.remove(tabId, session)
        }

        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (!isCurrent(webSocket)) return
                    reconnectAttempts.remove(tabId)
                    val initFrame = buildInitialHandshakeFrame(config)
                    webSocket.send(initFrame)
                    _events.tryEmit(
                        TerminalEvent.StateChanged(
                            tabId = tabId,
                            state = TerminalConnectionState.Connected,
                        )
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(lock) {
                    if (!isCurrent(webSocket)) return
                    handleIncomingMessage(config, webSocket, text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    if (claimEnd(webSocket)) handleClose(config, code, reason)
                }
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    if (claimEnd(webSocket)) handleClose(config, code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    if (!claimEnd(webSocket)) return
                    val bodyOrReason = t.message.orEmpty()
                    val responseCode = response?.code
                    if (responseCode == 401 || AuthError.isDataExpiredBody(bodyOrReason)) {
                        handleDataExpired(
                            config = config,
                            code = WS_CLOSE_DATA_EXPIRED,
                            reason = bodyOrReason.ifBlank { AuthError.CODE_DATA_EXPIRED },
                        )
                    } else {
                        _events.tryEmit(
                            TerminalEvent.Error(
                                tabId = tabId,
                                message = t.message ?: "WebSocket connection failure",
                                cause = t,
                            )
                        )
                        _events.tryEmit(
                            TerminalEvent.StateChanged(
                                tabId = tabId,
                                state = TerminalConnectionState.Error,
                                message = t.message,
                            )
                        )
                        scheduleReconnect(config)
                    }
                }
            }
        }
    }

    private fun handleIncomingMessage(config: TerminalSessionConfig, webSocket: WebSocket, text: String) {
        val tabId = config.tabId
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            _events.tryEmit(TerminalEvent.Output(tabId = tabId, data = text))
            return
        }

        try {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()

            when (type) {
                "ping" -> {
                    val pong = buildJsonObject { put("type", "pong") }
                    webSocket.send(json.encodeToString(JsonObject.serializer(), pong))
                }
                "pong" -> {
                    // Heartbeat acknowledged
                }
                "output", "data", "stdout", "stderr", "terminalData" -> {
                    val dataField = obj["data"]
                    val output = when (dataField) {
                        is JsonPrimitive -> dataField.contentOrNull.orEmpty()
                        is JsonObject -> dataField["data"]?.jsonPrimitive?.contentOrNull
                            ?: dataField["output"]?.jsonPrimitive?.contentOrNull
                            ?: dataField.toString()
                        else -> obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    _events.tryEmit(TerminalEvent.Output(tabId = tabId, data = output))
                }
                "connected", "ready", "sessionCreated", "attached" -> {
                    val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull
                        ?: (obj["data"] as? JsonObject)?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    _events.tryEmit(
                        TerminalEvent.StateChanged(
                            tabId = tabId,
                            state = TerminalConnectionState.Connected,
                            sessionId = sessionId,
                        )
                    )
                }
                "error" -> {
                    val errorMsg = obj["message"]?.jsonPrimitive?.contentOrNull
                        ?: obj["error"]?.jsonPrimitive?.contentOrNull
                        ?: (obj["data"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["data"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: trimmed
                    val code = obj["code"]?.jsonPrimitive?.contentOrNull
                        ?: (obj["data"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull

                    if (AuthError.isDataExpiredBody(errorMsg) || AuthError.isDataExpiredBody(code)) {
                        handleDataExpired(config, WS_CLOSE_DATA_EXPIRED, errorMsg)
                    } else {
                        // Also print it in the tab: otherwise a failed SSH login (e.g. "All configured
                        // authentication methods failed") leaves the tab at "Waiting for shell prompt".
                        _events.tryEmit(TerminalEvent.Output(tabId = tabId, data = "\r\n[Termix] $errorMsg\r\n"))
                        _events.tryEmit(TerminalEvent.Error(tabId = tabId, message = errorMsg))
                    }
                }
                // Interactive auth prompts Helix cannot answer yet: say so in the tab instead of hanging.
                "password_required", "passphrase_required", "totp_required", "totp_retry",
                "auth_method_not_available" -> {
                    val detail = obj["message"]?.jsonPrimitive?.contentOrNull
                        ?: (obj["data"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    val msg = "Host asks for $type" + (detail?.let { ": $it" } ?: "") +
                        ". Helix cannot answer interactive SSH prompts; give this host key or stored-password auth in Termix."
                    _events.tryEmit(TerminalEvent.Output(tabId = tabId, data = "\r\n[Termix] $msg\r\n"))
                    _events.tryEmit(TerminalEvent.Error(tabId = tabId, message = msg))
                }
                "disconnected", "exit", "closed" -> {
                    val msg = obj["message"]?.jsonPrimitive?.contentOrNull
                    _events.tryEmit(
                        TerminalEvent.StateChanged(
                            tabId = tabId,
                            state = TerminalConnectionState.Disconnected,
                            message = msg,
                        )
                    )
                }
                else -> {
                    val fallbackData = (obj["data"] as? JsonPrimitive)?.contentOrNull
                    if (fallbackData != null) {
                        _events.tryEmit(TerminalEvent.Output(tabId = tabId, data = fallbackData))
                    }
                }
            }
        } catch (_: Exception) {
            _events.tryEmit(TerminalEvent.Output(tabId = tabId, data = text))
        }
    }

    private fun handleClose(config: TerminalSessionConfig, code: Int, reason: String) {
        val tabId = config.tabId
        if (code == WS_CLOSE_DATA_EXPIRED || AuthError.isDataExpiredBody(reason)) {
            handleDataExpired(
                config = config,
                code = code,
                reason = reason.ifBlank { AuthError.CODE_DATA_EXPIRED },
            )
        } else {
            _events.tryEmit(
                TerminalEvent.StateChanged(
                    tabId = tabId,
                    state = TerminalConnectionState.Disconnected,
                    message = reason.takeIf { it.isNotBlank() },
                )
            )
            // Reconnect on any server-side close, including a clean 1000 (owner decision 2026-11-22).
            // A user disconnect() removes the session first, so claimEnd() skips this path and it stays final.
            scheduleReconnect(config)
        }
    }

    private fun emitDataExpired(tabId: String, closeCode: Int, reason: String) {
        _events.tryEmit(
            TerminalEvent.DataExpired(
                tabId = tabId,
                closeCode = closeCode,
                reason = reason,
            )
        )
        _events.tryEmit(
            TerminalEvent.StateChanged(
                tabId = tabId,
                state = TerminalConnectionState.DataExpired,
                message = reason,
            )
        )
    }
}
