package com.nightcinder.helix.stream

import android.media.MediaCodec
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.WebSocketAudioState
import com.nightcinder.helix.core.model.WebSocketCapabilityStatus
import com.nightcinder.helix.core.stream.VideoFrameSink
import com.nightcinder.helix.core.stream.WebSocketAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MoonlightWebSocketTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var fakeFrameSink: FakeVideoFrameSink
    private lateinit var fakeAudioSink: FakeAudioSink
    private lateinit var transport: MoonlightWebSocketTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        fakeFrameSink = FakeVideoFrameSink()
        fakeAudioSink = FakeAudioSink()
        transport = MoonlightWebSocketTransport(
            client = okHttpClient,
            frameSink = fakeFrameSink,
            audioSink = fakeAudioSink,
        )
    }

    @After
    fun tearDown() {
        transport.disconnect()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        try {
            server.shutdown()
        } catch (_: Exception) {}
    }

    private fun enqueueProbeResponses(
        allowWs: Boolean = true,
        hostAddress: String = "192.0.2.20",
        localIp: String = "192.0.2.20",
        paired: String = "Paired",
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/user") -> MockResponse().setBody("""{"id":1,"name":"ada","role_id":2}""")
                    path.startsWith("/api/role") -> MockResponse().setBody(
                        """{"id":2,"permissions":{"allow_transport_websockets":$allowWs,"max_bitrate_kbps":50000}}"""
                    )
                    path == "/api/hosts" -> MockResponse()
                        .setHeader("Content-Type", "application/x-ndjson")
                        .setBody(
                            """{"hosts":[{"host_id":7,"name":"desktop","paired":"NotPaired"}]}""" + "\n" +
                                """{"host_id":7,"paired":"$paired"}"""
                        )
                    path.startsWith("/api/host?") -> MockResponse().setBody(
                        """{"host_id":7,"name":"desktop","address":"$hostAddress","local_ip":"$localIp","paired":"$paired"}"""
                    )
                    path.startsWith("/api/apps") -> MockResponse().setBody(
                        """[{"id":101,"title":"Desktop"},{"id":102,"title":"Steam"}]"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Test
    fun connect_probesCapabilities_holdsConnectingUntilResponse_demuxesChannels_andSendsBinaryInput(): Unit = runBlocking {
        val serverReceivedText = CopyOnWriteArrayList<String>()
        val serverReceivedBinary = CopyOnWriteArrayList<ByteArray>()
        val handshakeLatch = CountDownLatch(1)
        val inputLatch = CountDownLatch(1)
        var serverWs: WebSocket? = null

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverWs = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                serverReceivedText.add(text)
                if (text.contains("\"Request\"")) {
                    handshakeLatch.countDown()
                    webSocket.send("""{"Response":{"Ok":{}}}""")
                    // Channel 0x01 video: 0x01 + frameType=1 (IDR) + ptsUs=0x00010203 + NAL
                    val videoFrame = byteArrayOf(
                        0x01, 0x01, 0x00, 0x01, 0x02, 0x03,
                        0x00, 0x00, 0x00, 0x01, 0x65,
                    )
                    webSocket.send(videoFrame.toByteString())
                    // Channel 0x02 audio: must go to audioSink, NEVER to VideoFrameSink
                    val audioFrame = byteArrayOf(0x02, 0xAA.toByte(), 0xBB.toByte())
                    webSocket.send(audioFrame.toByteString())
                    // Channel 0x00 control (unknown type 0x0100): must NOT leak to VideoFrameSink
                    val ctrlFrame = byteArrayOf(0x00, 0x00, 0x01, 0x02, 0x00, 0x00, 0x00)
                    webSocket.send(ctrlFrame.toByteString())
                    // Measured RTT via Stats.RelayRtt
                    webSocket.send("""{"Stats":{"RelayRtt":14}}""")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                serverReceivedBinary.add(bytes.toByteArray())
                inputLatch.countDown()
            }
        }

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/user") -> MockResponse().setBody("""{"id":1,"role_id":2}""")
                    path.startsWith("/api/role") -> MockResponse().setBody("""{"allow_transport_websockets":true}""")
                    path == "/api/hosts" -> MockResponse()
                        .setHeader("Content-Type", "application/x-ndjson")
                        .setBody(
                            """{"hosts":[{"host_id":7,"name":"desktop","paired":"NotPaired"}]}""" + "\n" +
                                """{"host_id":7,"paired":"Paired"}"""
                        )
                    path.startsWith("/api/host?") -> MockResponse().setBody(
                        """{"host_id":7,"name":"desktop","address":"192.0.2.20","local_ip":"192.0.2.20","paired":"Paired"}"""
                    )
                    path.startsWith("/api/apps") -> MockResponse().setBody("""[{"id":42,"title":"Desktop"}]""")
                    path == "/api/host/stream/web_socket" -> MockResponse().withWebSocketUpgrade(wsListener)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val wsUrl = server.url("/api/host/stream/web_socket").toString()
            .replace("http://", "ws://")
            .replace("https://", "wss://")

        val config = StreamConfig(
            hostIp = "192.0.2.20",
            port = 47989,
            wsUrl = wsUrl,
            fps = 60,
            bitrateKbps = 20000,
            codec = StreamCodec.HEVC,
            appTitle = "Desktop",
        )

        transport.connect(config)

        assertTrue(handshakeLatch.await(3, TimeUnit.SECONDS))
        assertTrue(fakeFrameSink.frameLatch.await(3, TimeUnit.SECONDS))

        var waitConnected = 0
        while ((transport.connectionState.value.status != StreamConnectionStatus.Connected ||
                transport.telemetry.value.rttMs == 0) && waitConnected < 30
        ) {
            Thread.sleep(100)
            waitConnected++
        }

        assertEquals(StreamConnectionStatus.Connected, transport.connectionState.value.status)
        assertEquals(StreamTransportMode.WEBSOCKET_443, transport.connectionState.value.activeTransport)
        assertTrue(transport.capabilityStatus.value is WebSocketCapabilityStatus.Available)

        // Verify Request JSON handshake fields
        val reqJson = serverReceivedText.first()
        assertTrue(reqJson.contains(""""Request""""))
        assertTrue(reqJson.contains(""""host_id":7"""))
        assertTrue(reqJson.contains(""""app_id":42"""))
        assertTrue(reqJson.contains(""""bitrate":20000"""))
        assertTrue(reqJson.contains(""""preferred_codecs":${MoonlightNativeBridge.VIDEO_FORMAT_H265}"""))

        // Verify only channel 0x01 reached VideoFrameSink, with 6-byte header stripped and keyframe flagged
        assertEquals(1, fakeFrameSink.submittedCount.get())
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65), fakeFrameSink.lastPayload)
        assertEquals(0x00010203L, fakeFrameSink.lastPtsUs)
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME, fakeFrameSink.lastFlags)

        // Verify channel 0x02 went to AudioSink
        assertEquals(1, fakeAudioSink.submittedCount.get())
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), fakeAudioSink.lastPayload)

        // Verify measured RTT without fabricated fps/bitrate
        assertEquals(14, transport.telemetry.value.rttMs)
        assertEquals(0.0, transport.telemetry.value.fps, 0.0)
        assertEquals(0, transport.telemetry.value.bitrateKbps)

        // Send binary MouseMove input
        transport.sendInput(StreamInputEvent.MouseMove(deltaX = 15, deltaY = -10))
        assertTrue(inputLatch.await(3, TimeUnit.SECONDS))
        assertArrayEquals(
            MoonlightWsProtocol.encodeInputPacket(StreamInputEvent.MouseMove(15, -10)),
            serverReceivedBinary.first(),
        )

        serverWs?.close(1000, "Normal closure")
    }

    @Test
    fun probeCapabilities_nonRedirectingClient_treats302AsAuthExpired(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://authentik.example.com/application/o/authorize/")
        )

        val wsUrl = server.url("/api/host/stream/web_socket").toString().replace("http://", "ws://")
        val result = transport.probeCapabilities(StreamConfig(wsUrl = wsUrl))

        assertTrue(result is WebSocketProbeResult.AuthExpired)
        assertEquals(WebSocketCapabilityStatus.AuthRequired, transport.capabilityStatus.value)
        assertFalse(transport.transportClient.followRedirects)
        assertFalse(transport.transportClient.followSslRedirects)
    }

    @Test
    fun probeCapabilities_resolvesHostByNameOrSinglePairedFallback(): Unit = runBlocking {
        enqueueProbeResponses(hostAddress = "10.99.0.5", localIp = "10.99.0.5", paired = "Paired")
        val wsUrl = server.url("/api/host/stream/web_socket").toString().replace("http://", "ws://")

        // Matches by case-insensitive name
        val byName = transport.probeCapabilities(StreamConfig(hostIp = "DESKTOP", wsUrl = wsUrl))
        assertTrue(byName is WebSocketProbeResult.Available)
        assertEquals(7, (byName as WebSocketProbeResult.Available).hostId)

        // Single-paired-host fallback even if hostIp differs
        val singleFallback = transport.probeCapabilities(StreamConfig(hostIp = "192.0.2.20", wsUrl = wsUrl))
        assertTrue(singleFallback is WebSocketProbeResult.Available)
        assertEquals(101, (singleFallback as WebSocketProbeResult.Available).appId)
    }

    @Test
    fun probeCapabilities_deniesWhenAllowTransportWebsocketsFalse(): Unit = runBlocking {
        enqueueProbeResponses(allowWs = false)
        val wsUrl = server.url("/api/host/stream/web_socket").toString().replace("http://", "ws://")

        val result = transport.probeCapabilities(StreamConfig(wsUrl = wsUrl))
        assertTrue(result is WebSocketProbeResult.Unavailable)
        assertTrue(transport.capabilityStatus.value is WebSocketCapabilityStatus.Unavailable)
    }

    @Test
    fun protocol_exactByteAssertions_requestIdr_serverTermination_andAllInputVariants() {
        // RequestIdr: 00 + 02 03 + 02 00 + 00 00
        assertArrayEquals(
            byteArrayOf(0x00, 0x02, 0x03, 0x02, 0x00, 0x00, 0x00),
            MoonlightWsProtocol.encodeRequestIdr(),
        )

        // ServerTermination graceful 0x80030023 (4-byte u32 BE)
        val gracefulBytes = byteArrayOf(
            0x00, 0x09, 0x01, 0x04, 0x00,
            0x80.toByte(), 0x03, 0x00, 0x23,
        )
        val gracefulParsed = MoonlightWsProtocol.parseControlPacket(gracefulBytes)
            as MoonlightWsProtocol.ControlPacket.ServerTermination
        assertEquals(0x80030023L, gracefulParsed.code)
        assertTrue(gracefulParsed.isGraceful)

        // ServerTermination non-graceful 2-byte u16 BE
        val errBytes = byteArrayOf(0x00, 0x09, 0x01, 0x02, 0x00, 0x00, 0x05)
        val errParsed = MoonlightWsProtocol.parseControlPacket(errBytes)
            as MoonlightWsProtocol.ControlPacket.ServerTermination
        assertEquals(5L, errParsed.code)
        assertFalse(errParsed.isGraceful)

        // MouseMove: input_len=8, magic=0x00000007, deltaX=15 (00 0F), deltaY=-10 (FF F6)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x0C, 0x00,
                0x00, 0x00, 0x00, 0x08,
                0x07, 0x00, 0x00, 0x00,
                0x00, 0x0F, 0xFF.toByte(), 0xF6.toByte(),
            ),
            MoonlightWsProtocol.encodeInputPacket(StreamInputEvent.MouseMove(15, -10)),
        )

        // MousePosition: input_len=14, magic=0x00000005, x=300 (01 2C), y=200 (00 C8), 0, refW=1920 (07 80), refH=1200 (04 B0)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x12, 0x00,
                0x00, 0x00, 0x00, 0x0E,
                0x05, 0x00, 0x00, 0x00,
                0x01, 0x2C, 0x00, 0xC8.toByte(), 0x00, 0x00, 0x07, 0x80.toByte(), 0x04, 0xB0.toByte(),
            ),
            MoonlightWsProtocol.encodeInputPacket(StreamInputEvent.MousePosition(300, 200, 1920, 1200)),
        )

        // MouseButton Press (0x08) & Release (0x09)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x09, 0x00,
                0x00, 0x00, 0x00, 0x05,
                0x08, 0x00, 0x00, 0x00,
                0x01,
            ),
            MoonlightWsProtocol.encodeInputPacket(
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, down = true)
            ),
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x09, 0x00,
                0x00, 0x00, 0x00, 0x05,
                0x09, 0x00, 0x00, 0x00,
                0x03,
            ),
            MoonlightWsProtocol.encodeInputPacket(
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_RIGHT, down = false)
            ),
        )

        // Key Down (0x03) & Up (0x04): keyCode=0x0041 LE (41 00), modifiers=0x01
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x0E, 0x00,
                0x00, 0x00, 0x00, 0x0A,
                0x03, 0x00, 0x00, 0x00,
                0x00, 0x41, 0x00, 0x01, 0x00, 0x00,
            ),
            MoonlightWsProtocol.encodeInputPacket(
                StreamInputEvent.Key(0x0041, StreamInputEvent.Key.ACTION_DOWN, 0x01)
            ),
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x0E, 0x00,
                0x00, 0x00, 0x00, 0x0A,
                0x04, 0x00, 0x00, 0x00,
                0x00, 0x41, 0x00, 0x00, 0x00, 0x00,
            ),
            MoonlightWsProtocol.encodeInputPacket(
                StreamInputEvent.Key(0x0041, StreamInputEvent.Key.ACTION_UP, 0x00)
            ),
        )

        // Scroll (clicks=2 -> 240 = 0x00F0 BE) & HighResScroll (amount=60 = 0x003C BE)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x0E, 0x00,
                0x00, 0x00, 0x00, 0x0A,
                0x0A, 0x00, 0x00, 0x00,
                0x00, 0xF0.toByte(), 0x00, 0xF0.toByte(), 0x00, 0x00,
            ),
            MoonlightWsProtocol.encodeInputPacket(StreamInputEvent.Scroll(2)),
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x0E, 0x00,
                0x00, 0x00, 0x00, 0x0A,
                0x0A, 0x00, 0x00, 0x00,
                0x00, 0x3C, 0x00, 0x3C, 0x00, 0x00,
            ),
            MoonlightWsProtocol.encodeInputPacket(StreamInputEvent.HighResScroll(60)),
        )

        // HighResHScroll: input_len=6, magic=0x55000001 (01 00 00 55), amount=-120 (FF 88 BE)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x06, 0x02, 0x0A, 0x00,
                0x00, 0x00, 0x00, 0x06,
                0x01, 0x00, 0x00, 0x55,
                0xFF.toByte(), 0x88.toByte(),
            ),
            MoonlightWsProtocol.encodeInputPacket(StreamInputEvent.HighResHScroll(-120)),
        )
    }

    private fun fakeTransport(): Pair<FakeWebSocketClient, MoonlightWebSocketTransport> {
        val http = FakeWebSocketClient()
        return http to MoonlightWebSocketTransport(
            client = http,
            frameSink = FakeVideoFrameSink(),
            audioSink = null,
            wrapNoRedirectClient = false,
            probeBeforeConnect = false,
        )
    }

    @Test
    fun onOpen_holdsConnectingUntilResponseArrives() {
        val (http, t) = fakeTransport()
        t.connect(StreamConfig(wsUrl = "wss://mock.local/stream"))
        http.open(0)

        // Must stay Connecting until {"Response":...} arrives
        assertEquals(StreamConnectionStatus.Connecting, t.connectionState.value.status)

        http.listeners[0].onMessage(http.sockets[0], """{"Response":{"Ok":{}}}""")
        assertEquals(StreamConnectionStatus.Connected, t.connectionState.value.status)
    }

    @Test
    fun serverTermination_gracefulVsError_updatesStateWithoutLeakingToVideoSink() {
        val http = FakeWebSocketClient()
        val sink = FakeVideoFrameSink()
        val t = MoonlightWebSocketTransport(
            client = http,
            frameSink = sink,
            audioSink = null,
            wrapNoRedirectClient = false,
            probeBeforeConnect = false,
        )
        t.connect(StreamConfig(wsUrl = "wss://mock.local/stream"))
        http.open(0)
        http.listeners[0].onMessage(http.sockets[0], """{"Response":{"Ok":{}}}""")

        http.listeners[0].onMessage(
            http.sockets[0],
            byteArrayOf(0x00, 0x09, 0x01, 0x04, 0x00, 0x80.toByte(), 0x03, 0x00, 0x23).toByteString(),
        )
        assertEquals(StreamConnectionStatus.Disconnected, t.connectionState.value.status)
        assertEquals(0, sink.submittedCount.get())
    }

    @Test
    fun staleSocketCallbacks_doNotAffectStateAfterReconnect() {
        val (http, t) = fakeTransport()
        val config = StreamConfig(wsUrl = "wss://mock.local/stream")

        t.connect(config)
        t.connect(config)
        assertEquals(2, http.sockets.size)
        http.open(1)
        http.listeners[1].onMessage(http.sockets[1], """{"Response":{"Ok":{}}}""")
        assertEquals(StreamConnectionStatus.Connected, t.connectionState.value.status)

        // Late callbacks of the first (replaced) socket must be ignored.
        http.listeners[0].onClosed(http.sockets[0], 1000, "old closed")
        http.listeners[0].onFailure(http.sockets[0], java.io.IOException("old failure"), null)
        http.listeners[0].onClosing(http.sockets[0], 1008, "DATA_EXPIRED")

        assertEquals(StreamConnectionStatus.Connected, t.connectionState.value.status)
        assertEquals(null, t.connectionState.value.errorMessage)

        // The live socket must still be usable (activeSocket not nulled by the stale callback).
        t.sendInput(StreamInputEvent.MouseMove(1, 2))
        assertEquals(1, http.sockets[1].sentBytes.size)
    }

    @Test
    fun closeIsReportedOnce_onClosingThenOnClosed() {
        val (http, t) = fakeTransport()
        t.connect(StreamConfig(wsUrl = "wss://mock.local/stream"))
        http.open(0)

        http.listeners[0].onClosing(http.sockets[0], 1000, "first")
        assertEquals(StreamConnectionStatus.Disconnected, t.connectionState.value.status)
        assertEquals("first", t.connectionState.value.errorMessage)

        http.listeners[0].onClosed(http.sockets[0], 1000, "second")
        assertEquals("first", t.connectionState.value.errorMessage)
    }

    @Test
    fun failureAfterDataExpiredClose_keepsDataExpired() {
        val (http, t) = fakeTransport()
        t.connect(StreamConfig(wsUrl = "wss://mock.local/stream"))
        http.open(0)

        http.listeners[0].onClosing(http.sockets[0], 1008, "DATA_EXPIRED")
        http.listeners[0].onFailure(http.sockets[0], java.io.IOException("socket reset"), null)

        assertEquals(StreamConnectionStatus.DataExpired, t.connectionState.value.status)
    }

    @Test
    fun http401And200HtmlUpgradeFailures_setDataExpired() {
        val (http, t) = fakeTransport()
        t.connect(StreamConfig(wsUrl = "wss://mock.local/stream"))
        http.listeners[0].onFailure(http.sockets[0], java.io.IOException("Expected HTTP 101"), http.response(0, 401))
        assertEquals(StreamConnectionStatus.DataExpired, t.connectionState.value.status)

        t.connect(StreamConfig(wsUrl = "wss://mock.local/stream"))
        val htmlResp = http.response(1, 200).newBuilder().header("Content-Type", "text/html; charset=utf-8").build()
        http.listeners[1].onFailure(http.sockets[1], java.io.IOException("Expected HTTP 101"), htmlResp)
        assertEquals(StreamConnectionStatus.DataExpired, t.connectionState.value.status)
    }

    @Test
    fun telemetry_hasNoConfiguredValuesBeforeServerReports() {
        val (_, t) = fakeTransport()
        t.connect(StreamConfig(wsUrl = "wss://mock.local/stream", fps = 60, bitrateKbps = 20000))

        val telemetry = t.telemetry.value
        assertEquals(0.0, telemetry.fps, 0.0)
        assertEquals(0, telemetry.bitrateKbps)
        assertEquals(0, telemetry.width)
        assertEquals(0, telemetry.height)
    }

    private class FakeVideoFrameSink : VideoFrameSink {
        val submittedCount = AtomicInteger(0)
        val frameLatch = CountDownLatch(1)
        var lastPayload: ByteArray = ByteArray(0)
        var lastPtsUs: Long = 0L
        var lastFlags: Int = 0
        override fun configure(width: Int, height: Int, codec: StreamCodec, surface: android.view.Surface?) {}
        override fun submitAccessUnit(data: ByteArray, offset: Int, length: Int, ptsUs: Long, flags: Int) {
            lastPayload = data.copyOfRange(offset, offset + length)
            lastPtsUs = ptsUs
            lastFlags = flags
            submittedCount.incrementAndGet()
            frameLatch.countDown()
        }
        override fun release() {}
    }

    private class FakeAudioSink : WebSocketAudioSink {
        private val _audioState = MutableStateFlow<WebSocketAudioState>(WebSocketAudioState.Inactive)
        override val audioState: StateFlow<WebSocketAudioState> = _audioState
        val submittedCount = AtomicInteger(0)
        var lastPayload: ByteArray = ByteArray(0)
        override fun configure(sampleRate: Int, channels: Int) {
            _audioState.value = WebSocketAudioState.Playing
        }
        override fun submitOpusPacket(data: ByteArray, offset: Int, length: Int) {
            lastPayload = data.copyOfRange(offset, offset + length)
            submittedCount.incrementAndGet()
        }
        override fun release() {
            _audioState.value = WebSocketAudioState.Inactive
        }
    }
}
