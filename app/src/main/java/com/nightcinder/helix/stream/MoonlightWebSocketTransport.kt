package com.nightcinder.helix.stream

import com.nightcinder.helix.api.DefaultTermixJson
import com.nightcinder.helix.core.model.AuthError
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionState
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.core.model.StreamTelemetry
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.WebSocketAudioState
import com.nightcinder.helix.core.model.WebSocketCapabilityStatus
import com.nightcinder.helix.core.stream.VideoFrameSink
import com.nightcinder.helix.core.stream.WebSocketAudioSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Result of probing `moonlight-web-stream:v3.0.2` HTTP capability endpoints before WebSocket connect.
 */
sealed interface WebSocketProbeResult {
    data class Available(
        val hostId: Int,
        val appId: Int,
        val hostName: String? = null,
        val appCount: Int = 0,
        val maxBitrateKbps: Int? = null,
    ) : WebSocketProbeResult

    data class AuthExpired(val reason: String) : WebSocketProbeResult

    data class Unavailable(val reason: String) : WebSocketProbeResult
}

/**
 * WebSocket streaming transport connecting to `wss://<moonlight-web>/api/host/stream/web_socket`
 * (`moonlight-web-stream:v3.0.2`) using a derived non-redirecting [OkHttpClient] that shares
 * Authentik proxy cookies without following `302` login redirects to `200 text/html`.
 */
class MoonlightWebSocketTransport(
    client: OkHttpClient,
    private val frameSink: VideoFrameSink,
    private val json: Json = DefaultTermixJson,
    private val audioSink: WebSocketAudioSink? = OpusAudioSink(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    wrapNoRedirectClient: Boolean = true,
    private val probeBeforeConnect: Boolean = true,
) {
    companion object {
        const val WS_CLOSE_NORMAL = 1000
        const val WS_CLOSE_DATA_EXPIRED = AuthError.WS_CLOSE_DATA_EXPIRED
        private val REDIRECT_STATUS_CODES = setOf(302, 303, 307, 308)

        internal fun isAuthExpiredResponse(
            response: Response?,
            bodyOrMessage: String? = null,
            expectedHost: String? = null,
        ): Boolean {
            if (response != null) {
                if (response.code == 401 || response.code in REDIRECT_STATUS_CODES) return true
                if (expectedHost != null && !response.request.url.host.equals(expectedHost, ignoreCase = true)) {
                    return true
                }
                val contentType = response.header("Content-Type")
                if (response.code == 200 && contentType?.contains("text/html", ignoreCase = true) == true) {
                    return true
                }
            }
            return AuthError.isDataExpiredBody(bodyOrMessage)
        }
    }

    /** Derived non-redirecting client inheriting Authentik cookies from [client]. */
    internal val transportClient: OkHttpClient = if (wrapNoRedirectClient) {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    } else {
        client
    }

    private val transportScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val socketLock = Any()

    @Volatile
    private var activeSocket: WebSocket? = null
    private var probeJob: Job? = null
    private var connectAttemptId: Int = 0
    private var currentConfig: StreamConfig = StreamConfig()
    private var resolvedHostId: Int = 0
    private var resolvedAppId: Int = 0
    private var expectedWsHost: String? = null

    // True once the close of the current socket has been reported (onClosing and onClosed both fire).
    @Volatile
    private var closeReported = false

    /** True only for callbacks of the live socket; stale sockets from a previous connect are ignored. */
    private fun isCurrent(webSocket: WebSocket): Boolean = synchronized(socketLock) { webSocket === activeSocket }

    private val _connectionState = MutableStateFlow(StreamConnectionState())
    val connectionState: StateFlow<StreamConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(
        StreamTelemetry(
            activeTransport = StreamTransportMode.WEBSOCKET_443,
            activeCodec = StreamCodec.HEVC,
        )
    )
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private val _capabilityStatus = MutableStateFlow<WebSocketCapabilityStatus>(WebSocketCapabilityStatus.Unknown)
    val capabilityStatus: StateFlow<WebSocketCapabilityStatus> = _capabilityStatus.asStateFlow()

    /**
     * Probes `/api/user`, `/api/role`, `/api/hosts`, `/api/host`, and `/api/apps` using [transportClient].
     * Never follows Authentik `302` redirects.
     */
    suspend fun probeCapabilities(config: StreamConfig): WebSocketProbeResult {
        if (!probeBeforeConnect) {
            val available = WebSocketProbeResult.Available(
                hostId = 0,
                appId = config.appId ?: 0,
                hostName = config.hostIp,
                appCount = 1,
            )
            _capabilityStatus.value = WebSocketCapabilityStatus.Available(available.hostName, available.appCount)
            return available
        }

        _capabilityStatus.value = WebSocketCapabilityStatus.Checking

        val result = withContext(ioDispatcher) {
            runCapabilityProbe(config)
        }

        _capabilityStatus.value = when (result) {
            is WebSocketProbeResult.Available -> WebSocketCapabilityStatus.Available(result.hostName, result.appCount)
            is WebSocketProbeResult.AuthExpired -> WebSocketCapabilityStatus.AuthRequired
            is WebSocketProbeResult.Unavailable -> WebSocketCapabilityStatus.Unavailable(result.reason)
        }
        return result
    }

    private fun runCapabilityProbe(config: StreamConfig): WebSocketProbeResult {
        val httpBaseString = config.wsUrl
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
        val baseUrl = httpBaseString.toHttpUrlOrNull()
            ?: return WebSocketProbeResult.Unavailable("Invalid WebSocket URL")
        val host = baseUrl.host

        if (config.bitrateKbps <= 0) {
            return WebSocketProbeResult.Unavailable("Invalid bitrate (${config.bitrateKbps} kbps)")
        }
        if (MoonlightNativeBridge.supportedVideoFormats(config.codec) == 0) {
            return WebSocketProbeResult.Unavailable("Unsupported codec: ${config.codec.name}")
        }

        // 1. /api/user
        val userResp = executeProbeGet(baseUrl, "/api/user", host)
        if (userResp is ProbeHttpResult.Failure) return userResp.result
        val userObj = parseJsonObject((userResp as ProbeHttpResult.Ok).body)
            ?: return WebSocketProbeResult.Unavailable("Invalid /api/user response")
        val roleId = userObj["role_id"]?.jsonPrimitive?.intOrNull
            ?: userObj["role"]?.let { it as? JsonObject }?.get("id")?.jsonPrimitive?.intOrNull

        // 2. /api/role (or /api/role?id=<role_id>)
        val rolePath = if (roleId != null) "/api/role?id=$roleId" else "/api/role"
        val roleResp = executeProbeGet(baseUrl, rolePath, host)
        if (roleResp is ProbeHttpResult.Failure) return roleResp.result
        val roleObj = parseJsonObject((roleResp as ProbeHttpResult.Ok).body)
            ?: return WebSocketProbeResult.Unavailable("Invalid /api/role response")
        val rolePerms = roleObj["permissions"]?.let { it as? JsonObject } ?: roleObj
        val allowWs = rolePerms["allow_transport_websockets"]?.jsonPrimitive?.booleanOrNull
            ?: roleObj["allow_transport_websockets"]?.jsonPrimitive?.booleanOrNull
            ?: false
        if (!allowWs) {
            return WebSocketProbeResult.Unavailable("WebSocket transport not permitted for this role")
        }
        val maxBitrate = rolePerms["max_bitrate_kbps"]?.jsonPrimitive?.intOrNull
            ?: rolePerms["max_bitrate"]?.jsonPrimitive?.intOrNull
            ?: roleObj["max_bitrate_kbps"]?.jsonPrimitive?.intOrNull
            ?: roleObj["max_bitrate"]?.jsonPrimitive?.intOrNull
        if (maxBitrate != null && maxBitrate > 0 && config.bitrateKbps > maxBitrate) {
            return WebSocketProbeResult.Unavailable("Bitrate ${config.bitrateKbps} kbps exceeds role limit ($maxBitrate kbps)")
        }

        // 3. /api/hosts (multi-line application/x-ndjson)
        val hostsResp = executeProbeGet(baseUrl, "/api/hosts", host)
        if (hostsResp is ProbeHttpResult.Failure) return hostsResp.result
        val hosts = parseHostsNdjson((hostsResp as ProbeHttpResult.Ok).body)
        val pairedHosts = hosts.filter { it.paired.equals("Paired", ignoreCase = true) }
        if (pairedHosts.isEmpty()) {
            return WebSocketProbeResult.Unavailable("No paired hosts available on moonlight-web")
        }

        // 4. /api/host?host_id=<id>
        val detailedHosts = mutableListOf<DetailedHost>()
        for (h in pairedHosts) {
            val detailResp = executeProbeGet(baseUrl, "/api/host?host_id=${h.hostId}", host)
            if (detailResp is ProbeHttpResult.Failure) {
                if (detailResp.result is WebSocketProbeResult.AuthExpired) return detailResp.result
                continue
            }
            val detailObj = parseJsonObject((detailResp as ProbeHttpResult.Ok).body) ?: continue
            detailedHosts += DetailedHost(
                hostId = detailObj["host_id"]?.jsonPrimitive?.intOrNull
                    ?: detailObj["id"]?.jsonPrimitive?.intOrNull
                    ?: h.hostId,
                name = detailObj["name"]?.jsonPrimitive?.contentOrNull ?: h.name,
                address = detailObj["address"]?.jsonPrimitive?.contentOrNull?.substringBefore(":"),
                localIp = detailObj["local_ip"]?.jsonPrimitive?.contentOrNull?.substringBefore(":"),
                paired = detailObj["paired"]?.jsonPrimitive?.contentOrNull ?: h.paired,
            )
        }

        val targetIp = config.hostIp.trim()
        val matchedHost = detailedHosts.firstOrNull {
            it.address.equals(targetIp, ignoreCase = true) ||
                it.localIp.equals(targetIp, ignoreCase = true)
        } ?: detailedHosts.firstOrNull {
            it.name != null && it.name.equals(targetIp, ignoreCase = true)
        } ?: if (detailedHosts.size == 1) {
            detailedHosts.first()
        } else {
            null
        }

        if (matchedHost == null) {
            return WebSocketProbeResult.Unavailable("Host $targetIp not found on moonlight-web")
        }
        if (!matchedHost.paired.equals("Paired", ignoreCase = true)) {
            return WebSocketProbeResult.Unavailable("Host ${matchedHost.name ?: matchedHost.hostId} is not paired")
        }

        // 5. /api/apps?host_id=<id>
        val appsResp = executeProbeGet(baseUrl, "/api/apps?host_id=${matchedHost.hostId}", host)
        if (appsResp is ProbeHttpResult.Failure) return appsResp.result
        val apps = parseApps((appsResp as ProbeHttpResult.Ok).body)
        if (apps.isEmpty()) {
            return WebSocketProbeResult.Unavailable("No apps available on ${matchedHost.name ?: "host"}")
        }

        val matchedApp = config.appId?.let { id -> apps.firstOrNull { it.first == id } }
            ?: apps.firstOrNull { it.second.equals(config.appTitle, ignoreCase = true) }
            ?: apps.firstOrNull { it.second.equals(StreamConfig.DEFAULT_APP_TITLE, ignoreCase = true) }
            ?: apps.first()

        return WebSocketProbeResult.Available(
            hostId = matchedHost.hostId,
            appId = matchedApp.first,
            hostName = matchedHost.name,
            appCount = apps.size,
            maxBitrateKbps = maxBitrate,
        )
    }

    private sealed interface ProbeHttpResult {
        data class Ok(val body: String) : ProbeHttpResult
        data class Failure(val result: WebSocketProbeResult) : ProbeHttpResult
    }

    private fun executeProbeGet(baseUrl: okhttp3.HttpUrl, pathAndQuery: String, expectedHost: String): ProbeHttpResult {
        val url = baseUrl.resolve(pathAndQuery)
            ?: return ProbeHttpResult.Failure(WebSocketProbeResult.Unavailable("Invalid probe path: $pathAndQuery"))
        val request = Request.Builder().url(url).get().build()
        return try {
            transportClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (isAuthExpiredResponse(response, body, expectedHost)) {
                    return ProbeHttpResult.Failure(
                        WebSocketProbeResult.AuthExpired("Auth expired (${response.code}) at $pathAndQuery")
                    )
                }
                if (response.code == 403) {
                    return ProbeHttpResult.Failure(
                        WebSocketProbeResult.Unavailable("Permission denied (403) at $pathAndQuery")
                    )
                }
                if (!response.isSuccessful) {
                    return ProbeHttpResult.Failure(
                        WebSocketProbeResult.Unavailable("Probe $pathAndQuery failed (HTTP ${response.code})")
                    )
                }
                ProbeHttpResult.Ok(body)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ProbeHttpResult.Failure(
                WebSocketProbeResult.Unavailable(e.message ?: "Could not reach moonlight-web")
            )
        }
    }

    internal data class UndetailedHost(
        val hostId: Int,
        val name: String? = null,
        val paired: String? = null,
    )

    private data class DetailedHost(
        val hostId: Int,
        val name: String?,
        val address: String?,
        val localIp: String?,
        val paired: String?,
    )

    /**
     * Parses `application/x-ndjson` `/api/hosts` output: line 1 `{"hosts":[...]}` plus
     * trailing `UndetailedHost` update lines merged by `host_id`.
     */
    internal fun parseHostsNdjson(body: String): List<UndetailedHost> {
        val merged = LinkedHashMap<Int, UndetailedHost>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            try {
                val elem = json.parseToJsonElement(line)
                when (elem) {
                    is JsonArray -> {
                        for (item in elem) {
                            (item as? JsonObject)?.let { mergeHostObj(merged, it) }
                        }
                    }
                    is JsonObject -> {
                        val list = elem["hosts"] as? JsonArray
                        if (list != null) {
                            for (item in list) {
                                (item as? JsonObject)?.let { mergeHostObj(merged, it) }
                            }
                        } else {
                            mergeHostObj(merged, elem)
                        }
                    }
                    else -> Unit
                }
            } catch (_: Exception) {
                // Ignore malformed trailing/partial NDJSON lines
            }
        }
        return merged.values.toList()
    }

    private fun mergeHostObj(merged: MutableMap<Int, UndetailedHost>, obj: JsonObject) {
        val id = obj["host_id"]?.jsonPrimitive?.intOrNull
            ?: obj["id"]?.jsonPrimitive?.intOrNull
            ?: return
        val prev = merged[id]
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: prev?.name
        val paired = obj["paired"]?.jsonPrimitive?.contentOrNull ?: prev?.paired
        merged[id] = UndetailedHost(hostId = id, name = name, paired = paired)
    }

    private fun parseApps(body: String): List<Pair<Int, String>> {
        return try {
            val elem = json.parseToJsonElement(body.trim())
            val arr = when (elem) {
                is JsonArray -> elem
                is JsonObject -> elem["apps"]?.jsonArray ?: return emptyList()
                else -> return emptyList()
            }
            arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.intOrNull
                    ?: obj["app_id"]?.jsonPrimitive?.intOrNull
                    ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                id to title
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseJsonObject(body: String): JsonObject? = try {
        json.parseToJsonElement(body.trim()).jsonObject
    } catch (_: Exception) {
        null
    }

    fun connect(config: StreamConfig, preProbed: WebSocketProbeResult.Available? = null) {
        disconnect()
        currentConfig = config
        expectedWsHost = config.wsUrl
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .toHttpUrlOrNull()
            ?.host

        val attemptId = synchronized(socketLock) {
            ++connectAttemptId
        }

        _connectionState.update {
            it.copy(
                status = StreamConnectionStatus.Connecting,
                activeTransport = StreamTransportMode.WEBSOCKET_443,
                targetHostIp = config.hostIp,
                errorMessage = null,
            )
        }

        _telemetry.value = StreamTelemetry(
            activeTransport = StreamTransportMode.WEBSOCKET_443,
            activeCodec = config.codec,
            audioState = audioSink?.audioState?.value ?: WebSocketAudioState.Inactive,
        )

        if (preProbed != null) {
            openWebSocket(config, attemptId, preProbed.hostId, preProbed.appId)
        } else if (!probeBeforeConnect) {
            openWebSocket(config, attemptId, 0, config.appId ?: 0)
        } else {
            probeJob = transportScope.launch {
                when (val probe = probeCapabilities(config)) {
                    is WebSocketProbeResult.Available -> {
                        openWebSocket(config, attemptId, probe.hostId, probe.appId)
                    }
                    is WebSocketProbeResult.AuthExpired -> {
                        synchronized(socketLock) {
                            if (attemptId != connectAttemptId) return@launch
                        }
                        _connectionState.update {
                            it.copy(
                                status = StreamConnectionStatus.DataExpired,
                                activeTransport = StreamTransportMode.WEBSOCKET_443,
                                errorMessage = probe.reason,
                            )
                        }
                    }
                    is WebSocketProbeResult.Unavailable -> {
                        synchronized(socketLock) {
                            if (attemptId != connectAttemptId) return@launch
                        }
                        _connectionState.update {
                            it.copy(
                                status = StreamConnectionStatus.Error,
                                activeTransport = StreamTransportMode.WEBSOCKET_443,
                                errorMessage = probe.reason,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openWebSocket(config: StreamConfig, attemptId: Int, hostId: Int, appId: Int) {
        val request = Request.Builder()
            .url(config.wsUrl)
            .build()

        // Hold the lock while assigning so listener callbacks that fire early wait for activeSocket.
        synchronized(socketLock) {
            if (attemptId != connectAttemptId) return
            resolvedHostId = hostId
            resolvedAppId = appId
            closeReported = false
            activeSocket = transportClient.newWebSocket(request, createListener())
        }
    }

    fun disconnect() {
        val old = synchronized(socketLock) {
            connectAttemptId++
            probeJob?.cancel()
            probeJob = null
            val s = activeSocket
            activeSocket = null
            s
        }
        old?.close(WS_CLOSE_NORMAL, "Client disconnect")
        audioSink?.release()
        _connectionState.update {
            it.copy(
                status = StreamConnectionStatus.Disconnected,
                errorMessage = null,
            )
        }
    }

    fun sendInput(event: StreamInputEvent) {
        val socket = activeSocket ?: return
        if (_connectionState.value.status != StreamConnectionStatus.Connected) return
        val bytes = MoonlightWsProtocol.encodeInputPacket(event)
        socket.send(bytes.toByteString())
    }

    fun requestIdr() {
        val socket = activeSocket ?: return
        if (_connectionState.value.status != StreamConnectionStatus.Connected) return
        socket.send(MoonlightWsProtocol.encodeRequestIdr().toByteString())
    }

    internal fun createListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket)) return
                // Hold Connecting until {"Response":...} arrives; never claim Connected on raw onOpen.
                val handshake = MoonlightWsProtocol.buildRequestHandshake(
                    config = currentConfig,
                    hostId = resolvedHostId,
                    appId = resolvedAppId,
                    json = json,
                )
                webSocket.send(handshake)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent(webSocket)) return
                val byteArray = bytes.toByteArray()
                if (byteArray.isEmpty()) return

                when (byteArray[0]) {
                    MoonlightWsProtocol.CHANNEL_VIDEO -> {
                        val header = MoonlightWsProtocol.parseVideoFrame(byteArray) ?: return
                        frameSink.submitAccessUnit(
                            data = byteArray,
                            offset = header.payloadOffset,
                            length = header.payloadLength,
                            ptsUs = header.ptsUs,
                            flags = header.mediaCodecFlags,
                        )
                    }
                    MoonlightWsProtocol.CHANNEL_CONTROL -> {
                        when (val ctrl = MoonlightWsProtocol.parseControlPacket(byteArray)) {
                            is MoonlightWsProtocol.ControlPacket.ServerTermination -> {
                                closeReported = true
                                synchronized(socketLock) {
                                    if (webSocket === activeSocket) activeSocket = null
                                }
                                webSocket.close(WS_CLOSE_NORMAL, "ServerTermination")
                                audioSink?.release()
                                if (ctrl.isGraceful) {
                                    _connectionState.update {
                                        it.copy(
                                            status = StreamConnectionStatus.Disconnected,
                                            errorMessage = "The host ended the stream",
                                        )
                                    }
                                } else {
                                    _connectionState.update {
                                        it.copy(
                                            status = StreamConnectionStatus.Error,
                                            errorMessage = "Stream terminated by host (0x${ctrl.code.toString(16)})",
                                        )
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                    MoonlightWsProtocol.CHANNEL_AUDIO -> {
                        if (byteArray.size > 1) {
                            audioSink?.submitOpusPacket(byteArray, 1, byteArray.size - 1)
                            updateAudioTelemetry()
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket)) return
                handleTextMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) {
                    webSocket.close(code, reason)
                    return
                }
                reportCloseOnce(code, reason)
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val current = synchronized(socketLock) {
                    if (webSocket === activeSocket) {
                        activeSocket = null
                        true
                    } else {
                        false
                    }
                }
                if (!current) return
                reportCloseOnce(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val current = synchronized(socketLock) {
                    if (webSocket === activeSocket) {
                        activeSocket = null
                        true
                    } else {
                        false
                    }
                }
                if (!current) return
                // A failure after a reported close (e.g. DATA_EXPIRED) must not overwrite that state.
                if (closeReported) return
                audioSink?.release()
                val bodyOrReason = t.message.orEmpty()
                if (isAuthExpiredResponse(response, bodyOrReason, expectedWsHost)) {
                    _connectionState.update {
                        it.copy(
                            status = StreamConnectionStatus.DataExpired,
                            errorMessage = bodyOrReason.ifBlank { "DATA_EXPIRED" },
                        )
                    }
                } else {
                    _connectionState.update {
                        it.copy(
                            status = StreamConnectionStatus.Error,
                            errorMessage = t.message ?: "WebSocket transport failure",
                        )
                    }
                }
            }
        }
    }

    private fun updateAudioTelemetry() {
        val state = audioSink?.audioState?.value ?: return
        if (_telemetry.value.audioState != state) {
            _telemetry.update { it.copy(audioState = state) }
        }
    }

    private fun handleTextMessage(text: String) {
        val trimmed = text.trim()
        if (AuthError.isDataExpiredBody(trimmed)) {
            _connectionState.update {
                it.copy(
                    status = StreamConnectionStatus.DataExpired,
                    errorMessage = trimmed,
                )
            }
            return
        }

        if (!trimmed.startsWith("{")) return

        try {
            val root = json.parseToJsonElement(trimmed).jsonObject

            if (root.containsKey("Response")) {
                handleResponseFrame(root["Response"])
                return
            }

            if (root.containsKey("Stats")) {
                handleStatsFrame(root["Stats"] as? JsonObject)
                return
            }

            if (root.containsKey("Error")) {
                val msg = root["Error"]?.let {
                    (it as? JsonPrimitive)?.contentOrNull ?: it.toString()
                } ?: trimmed
                reportErrorOrExpired(msg)
                return
            }

            val type = root["type"]?.jsonPrimitive?.content ?: ""
            when (type) {
                "telemetry", "stats" -> {
                    val data = root["data"]?.jsonObject ?: root
                    val fps = data["fps"]?.jsonPrimitive?.doubleOrNull ?: _telemetry.value.fps
                    val bitrate = data["bitrateKbps"]?.jsonPrimitive?.intOrNull
                        ?: data["bitrate"]?.jsonPrimitive?.intOrNull ?: _telemetry.value.bitrateKbps
                    val rtt = data["rttMs"]?.jsonPrimitive?.intOrNull
                        ?: data["rtt"]?.jsonPrimitive?.intOrNull ?: _telemetry.value.rttMs
                    val dropped = data["droppedFrames"]?.jsonPrimitive?.longOrNull ?: _telemetry.value.droppedFrames

                    _telemetry.update {
                        it.copy(
                            fps = fps,
                            bitrateKbps = bitrate,
                            rttMs = rtt,
                            droppedFrames = dropped,
                            activeTransport = StreamTransportMode.WEBSOCKET_443,
                            activeCodec = currentConfig.codec,
                            width = data["width"]?.jsonPrimitive?.intOrNull ?: it.width,
                            height = data["height"]?.jsonPrimitive?.intOrNull ?: it.height,
                        )
                    }
                }
                "error" -> {
                    val msg = root["message"]?.jsonPrimitive?.content ?: trimmed
                    reportErrorOrExpired(msg)
                }
            }
        } catch (_: Exception) {
            // Non-fatal parse failure
        }
    }

    private fun handleResponseFrame(responseElem: kotlinx.serialization.json.JsonElement?) {
        val errorMsg = when (responseElem) {
            is JsonPrimitive -> responseElem.contentOrNull?.takeIf { it.contains("error", ignoreCase = true) }
            is JsonObject -> {
                responseElem["Error"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
                    ?: responseElem["error"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
                    ?: if (responseElem["success"]?.jsonPrimitive?.booleanOrNull == false) {
                        responseElem["message"]?.jsonPrimitive?.contentOrNull ?: "Stream request rejected"
                    } else {
                        null
                    }
            }
            else -> null
        }

        if (errorMsg != null) {
            reportErrorOrExpired(errorMsg)
            return
        }

        audioSink?.configure()
        _connectionState.update {
            it.copy(
                status = StreamConnectionStatus.Connected,
                activeTransport = StreamTransportMode.WEBSOCKET_443,
                targetHostIp = currentConfig.hostIp,
                connectedEpochMs = System.currentTimeMillis(),
                errorMessage = null,
            )
        }
        updateAudioTelemetry()
    }

    private fun handleStatsFrame(stats: JsonObject?) {
        if (stats == null) return
        val rtt = extractRttMs(stats["RelayRtt"])
            ?: extractRttMs(stats["Pong"])
            ?: extractRttMs(stats["Ping"])
            ?: return
        _telemetry.update {
            it.copy(
                rttMs = rtt,
                activeTransport = StreamTransportMode.WEBSOCKET_443,
                activeCodec = currentConfig.codec,
                audioState = audioSink?.audioState?.value ?: it.audioState,
            )
        }
    }

    private fun extractRttMs(elem: kotlinx.serialization.json.JsonElement?): Int? = when (elem) {
        is JsonPrimitive -> elem.intOrNull
        is JsonObject -> elem["rtt_ms"]?.jsonPrimitive?.intOrNull
            ?: elem["rttMs"]?.jsonPrimitive?.intOrNull
            ?: elem["rtt"]?.jsonPrimitive?.intOrNull
        else -> null
    }

    private fun reportErrorOrExpired(msg: String) {
        if (AuthError.isDataExpiredBody(msg)) {
            _connectionState.update {
                it.copy(status = StreamConnectionStatus.DataExpired, errorMessage = msg)
            }
        } else {
            _connectionState.update {
                it.copy(status = StreamConnectionStatus.Error, errorMessage = msg)
            }
        }
    }

    private fun reportCloseOnce(code: Int, reason: String) {
        val first = synchronized(socketLock) {
            val already = closeReported
            closeReported = true
            !already
        }
        if (first) handleClose(code, reason)
    }

    private fun handleClose(code: Int, reason: String) {
        audioSink?.release()
        if (code == WS_CLOSE_DATA_EXPIRED || AuthError.isDataExpiredBody(reason)) {
            _connectionState.update {
                it.copy(
                    status = StreamConnectionStatus.DataExpired,
                    errorMessage = reason.ifBlank { "DATA_EXPIRED" },
                )
            }
        } else {
            _connectionState.update {
                it.copy(
                    status = StreamConnectionStatus.Disconnected,
                    errorMessage = reason.takeIf { r -> r.isNotBlank() },
                )
            }
        }
    }
}
