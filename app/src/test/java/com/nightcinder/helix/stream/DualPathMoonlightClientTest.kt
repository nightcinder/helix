package com.nightcinder.helix.stream

import android.content.pm.PackageManager
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamResolution
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.stream.VideoFrameSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DualPathMoonlightClientTest {

    private class Harness(
        testScope: TestScope,
        onAuthExpired: (suspend () -> Boolean)? = null,
        var probeReturn: Boolean = true,
    ) {
        val dispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val http = FakeWebSocketClient()
        val sink = FakeVideoFrameSink()
        val transport = MoonlightWebSocketTransport(
            client = http,
            frameSink = sink,
            audioSink = null,
            wrapNoRedirectClient = false,
            probeBeforeConnect = false,
        )
        val client = DualPathMoonlightClient(
            nativeBridge = MoonlightNativeBridge(),
            webSocketTransport = transport,
            frameSink = sink,
            directUdpProber = { _, _ -> probeReturn },
            onAuthExpired = onAuthExpired,
            nativeDispatcher = dispatcher,
            clientScope = scope,
            nowMs = { testScope.testScheduler.currentTime },
        )
        val wsConfig = StreamConfig(
            hostIp = "192.0.2.20",
            wsUrl = "wss://mock.local/stream",
            transportMode = StreamTransportMode.WEBSOCKET_443,
        )

        fun close() = scope.cancel()
    }

    @Test
    fun nativeBridge_withoutLibrary_reportsFailureAndNoFakeTelemetry() {
        val bridge = MoonlightNativeBridge()
        assertFalse(MoonlightNativeBridge.isLibraryLoaded)
        val params = NativeStartParams(
            "192.0.2.20", "7.1.431.-1", "3.23.0.74", "rtsp://192.0.2.20:48010", 0,
            1920, 1080, 60, 20000, MoonlightNativeBridge.VIDEO_FORMAT_H264, ByteArray(16), 1,
        )
        assertFalse(bridge.start(params, null))
        assertNull(bridge.getStats())
        bridge.stop() // no session: must be a harmless no-op, twice
        bridge.stop()
    }

    @Test
    fun connect_directUdp_withoutGateway_reportsErrorInsteadOfConnected() = runTest {
        val h = Harness(this)
        h.client.connect(
            StreamConfig(
                hostIp = "192.0.2.20",
                codec = StreamCodec.HEVC,
                resolution = StreamResolution.HD_1080P,
                transportMode = StreamTransportMode.DIRECT_UDP,
            )
        )

        assertEquals(StreamConnectionStatus.Error, h.client.state.value.status)
        assertEquals(StreamTransportMode.DIRECT_UDP, h.client.state.value.activeTransport)
        assertEquals(DualPathMoonlightClient.NATIVE_UNAVAILABLE_MESSAGE, h.client.state.value.errorMessage)
        assertEquals(0.0, h.client.telemetry.value.fps, 0.0)
        assertEquals(0, h.client.telemetry.value.bitrateKbps)

        h.client.disconnect()
        assertEquals(StreamConnectionStatus.Disconnected, h.client.state.value.status)
        h.close()
    }

    @Test
    fun connect_autoMode_selectsNativeDirectWhenHostReachable() = runTest {
        val h = Harness(this, probeReturn = true)
        h.client.connect(StreamConfig(transportMode = StreamTransportMode.AUTO))

        assertEquals(StreamTransportMode.DIRECT_UDP, h.client.state.value.activeTransport)
        assertEquals(0, h.http.sockets.size)
        // Never Connected without a real native connectionStarted callback.
        assertTrue(h.client.state.value.status != StreamConnectionStatus.Connected)

        h.client.disconnect()
        h.close()
    }
    @Test
    fun connect_autoMode_fallsBackToWebSocketWhenDirectUnreachable() = runTest {
        val h = Harness(this, probeReturn = false)
        h.client.connect(h.wsConfig.copy(transportMode = StreamTransportMode.AUTO))

        assertEquals(StreamTransportMode.WEBSOCKET_443, h.client.state.value.activeTransport)
        assertEquals(1, h.http.sockets.size)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun webSocket_telemetryStaysZeroUntilServerReportsMeasurements() = runTest {
        val h = Harness(this)
        h.client.connect(h.wsConfig)
        h.http.open(0)
        assertEquals(StreamConnectionStatus.Connecting, h.client.state.value.status)

        h.listeners0().onMessage(h.http.sockets[0], """{"Response":{"Ok":{}}}""")
        assertEquals(StreamConnectionStatus.Connected, h.client.state.value.status)
        val t = h.client.telemetry.value
        assertEquals(0.0, t.fps, 0.0)
        assertEquals(0, t.bitrateKbps)
        assertEquals(0, t.rttMs)
        assertEquals(0, t.width)
        assertEquals(0, t.height)
        assertFalse(t.hasData)

        h.listeners0().onMessage(h.http.sockets[0], """{"Stats":{"RelayRtt":11}}""")
        assertEquals(11, h.client.telemetry.value.rttMs)
        assertTrue(h.client.telemetry.value.hasData)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun disconnect_cancelsCollectors_soStaleTransportStateDoesNotOverwriteClient() = runTest {
        val h = Harness(this)
        h.client.connect(h.wsConfig)
        h.client.disconnect()
        assertEquals(StreamConnectionStatus.Disconnected, h.client.state.value.status)

        // Drive the transport directly: a leaked collector would copy Connected into the client state.
        h.transport.connect(h.wsConfig)
        h.http.open(h.http.sockets.lastIndex)
        h.http.listeners[h.http.sockets.lastIndex].onMessage(h.http.sockets.last(), """{"Response":{"Ok":{}}}""")
        assertEquals(StreamConnectionStatus.Connected, h.transport.connectionState.value.status)

        assertEquals(StreamConnectionStatus.Disconnected, h.client.state.value.status)
        h.close()
    }

    @Test
    fun reconnect_replacesCollectors_soOnlyLatestConnectionDrivesState() = runTest {
        val h = Harness(this)
        h.client.connect(h.wsConfig)
        h.client.connect(h.wsConfig)
        assertEquals(2, h.http.sockets.size)

        h.http.open(0) // stale socket: ignored by transport
        assertEquals(StreamConnectionStatus.Connecting, h.client.state.value.status)
        h.http.open(1)
        h.http.listeners[1].onMessage(h.http.sockets[1], """{"Response":{"Ok":{}}}""")
        assertEquals(StreamConnectionStatus.Connected, h.client.state.value.status)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun authExpired_refreshSucceeds_reconnectsWithCurrentConfig() = runTest {
        var calls = 0
        val h = Harness(this, onAuthExpired = { calls++; true })
        h.client.connect(h.wsConfig)
        h.http.open(0)

        h.listeners0().onClosing(h.http.sockets[0], 1008, "DATA_EXPIRED")

        assertEquals(1, calls)
        assertEquals(2, h.http.sockets.size)
        assertEquals(h.wsConfig.wsUrl, h.http.sockets[1].request().url.toString().replace("https://", "wss://").trimEnd('/'))

        h.client.disconnect()
        h.close()
    }

    @Test
    fun authExpired_refreshFails_keepsDataExpiredAndClearsReauthFlag() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val h = Harness(this, onAuthExpired = { gate.await() })
        h.client.connect(h.wsConfig)
        h.http.open(0)

        h.listeners0().onClosing(h.http.sockets[0], 1008, "DATA_EXPIRED")
        assertEquals(StreamConnectionStatus.DataExpired, h.client.state.value.status)
        assertTrue(h.client.state.value.isReauthenticating)

        gate.complete(false)
        assertEquals(StreamConnectionStatus.DataExpired, h.client.state.value.status)
        assertFalse(h.client.state.value.isReauthenticating)
        assertEquals(1, h.http.sockets.size)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun authExpired_isRateLimitedToOncePerMinute() = runTest {
        var calls = 0
        val h = Harness(this, onAuthExpired = { calls++; false })

        suspend fun expireOnce() {
            h.client.connect(h.wsConfig)
            val i = h.http.sockets.lastIndex
            h.http.open(i)
            h.http.listeners[i].onClosing(h.http.sockets[i], 1008, "DATA_EXPIRED")
        }

        expireOnce()
        assertEquals(1, calls)
        expireOnce()
        assertEquals(1, calls)

        advanceTimeBy(61_000L)
        expireOnce()
        assertEquals(2, calls)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun authExpired_withoutHandler_keepsDataExpired() = runTest {
        val h = Harness(this)
        h.client.connect(h.wsConfig)
        h.http.open(0)
        h.listeners0().onFailure(h.http.sockets[0], IOException("unauthorized"), h.http.response(0, 401))

        assertEquals(StreamConnectionStatus.DataExpired, h.client.state.value.status)
        assertFalse(h.client.state.value.isReauthenticating)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun decoderCreationFailure_showsErrorAndDoesNotConnect() = runTest {
        val h = Harness(this)
        h.sink.error = "Video decoder unavailable for HEVC"
        h.client.connect(h.wsConfig)

        assertEquals(StreamConnectionStatus.Error, h.client.state.value.status)
        assertEquals("Video decoder unavailable for HEVC", h.client.state.value.errorMessage)
        assertEquals(0, h.http.sockets.size)

        h.close()
    }

    @Test
    fun asyncDecoderError_surfacesAsErrorState() = runTest {
        val h = Harness(this)
        h.client.connect(h.wsConfig)
        h.http.open(0)

        h.sink.fireError("codec died")

        assertEquals(StreamConnectionStatus.Error, h.client.state.value.status)
        assertEquals("codec died", h.client.state.value.errorMessage)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun buildExternalMoonlightIntent_withoutInstalledApps_returnsIntent() = runTest {
        val h = Harness(this)
        val intent = h.client.buildExternalMoonlightIntent(DummyContext())

        assertNotNull(intent)
        h.close()
    }

    @Test
    fun connect_autoMode_capabilityProbeDenied_setsErrorWithoutOpeningSocket() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val okHttp = okhttp3.OkHttpClient()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"id":1,"role_id":2}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"allow_transport_websockets":false}"""))

            val sink = FakeVideoFrameSink()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val transport = MoonlightWebSocketTransport(
                client = okHttp,
                frameSink = sink,
                audioSink = null,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            )
            val client = DualPathMoonlightClient(
                nativeBridge = MoonlightNativeBridge(),
                webSocketTransport = transport,
                frameSink = sink,
                directUdpProber = { _, _ -> false },
                nativeDispatcher = dispatcher,
                clientScope = scope,
            )

            val wsUrl = server.url("/api/host/stream/web_socket").toString().replace("http://", "ws://")
            client.connect(StreamConfig(wsUrl = wsUrl, transportMode = StreamTransportMode.AUTO))

            assertEquals(StreamConnectionStatus.Error, client.state.value.status)
            assertEquals(StreamTransportMode.WEBSOCKET_443, client.state.value.activeTransport)
            assertTrue(client.state.value.errorMessage.orEmpty().contains("not permitted"))
            client.disconnect()
            scope.cancel()
        } finally {
            okHttp.dispatcher.executorService.shutdown()
            okHttp.connectionPool.evictAll()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun connect_autoMode_302ProbeTriggersSingleFlightAuthRefreshAndReconnect() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val okHttp = okhttp3.OkHttpClient()
        try {
            var refreshCalls = 0
            var authed = false
            val wsUpgradeCount = java.util.concurrent.atomic.AtomicInteger(0)
            val connectedLatch = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (!authed) {
                        return okhttp3.mockwebserver.MockResponse()
                            .setResponseCode(302)
                            .setHeader("Location", "https://authentik.example.com/login")
                    }
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/api/user") -> okhttp3.mockwebserver.MockResponse().setBody("""{"id":1,"role_id":2}""")
                        path.startsWith("/api/role") -> okhttp3.mockwebserver.MockResponse().setBody("""{"allow_transport_websockets":true}""")
                        path == "/api/hosts" -> okhttp3.mockwebserver.MockResponse().setBody("""{"hosts":[{"host_id":7,"name":"desktop","paired":"Paired"}]}""")
                        path.startsWith("/api/host?") -> okhttp3.mockwebserver.MockResponse().setBody("""{"host_id":7,"name":"desktop","address":"192.0.2.20","paired":"Paired"}""")
                        path.startsWith("/api/apps") -> okhttp3.mockwebserver.MockResponse().setBody("""[{"id":1,"title":"Desktop"}]""")
                        path == "/api/host/stream/web_socket" -> okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(
                            object : okhttp3.WebSocketListener() {
                                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                                    wsUpgradeCount.incrementAndGet()
                                }

                                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                                    if (text.contains("\"Request\"")) {
                                        webSocket.send("""{"Response":{"Ok":{}}}""")
                                        connectedLatch.countDown()
                                    }
                                }
                            },
                        )
                        else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                    }
                }
            }

            val sink = FakeVideoFrameSink()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val transport = MoonlightWebSocketTransport(
                client = okHttp,
                frameSink = sink,
                audioSink = null,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            )
            val client = DualPathMoonlightClient(
                nativeBridge = MoonlightNativeBridge(),
                webSocketTransport = transport,
                frameSink = sink,
                directUdpProber = { _, _ -> false },
                onAuthExpired = {
                    refreshCalls++
                    authed = true
                    true
                },
                nativeDispatcher = dispatcher,
                clientScope = scope,
                nowMs = { testScheduler.currentTime },
            )

            val wsUrl = server.url("/api/host/stream/web_socket").toString().replace("http://", "ws://")
            client.connect(StreamConfig(wsUrl = wsUrl, transportMode = StreamTransportMode.AUTO))

            assertTrue(
                "WebSocket should reopen after auth refresh",
                connectedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS),
            )
            val deadline = System.currentTimeMillis() + 2000L
            while (client.state.value.status != StreamConnectionStatus.Connected && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(1, refreshCalls)
            assertEquals(1, wsUpgradeCount.get())
            assertTrue(client.capabilityStatus.value is com.nightcinder.helix.core.model.WebSocketCapabilityStatus.Available)
            assertEquals(StreamConnectionStatus.Connected, client.state.value.status)
            client.disconnect()
            scope.cancel()
        } finally {
            okHttp.dispatcher.executorService.shutdown()
            okHttp.connectionPool.evictAll()
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun authExpired_concurrentTriggers_singleFlightRefreshAndReconnectsOn1008() = runTest {
        val gate = CompletableDeferred<Boolean>()
        var refreshCalls = 0
        val h = Harness(this, onAuthExpired = {
            refreshCalls++
            gate.await()
        })
        h.client.connect(h.wsConfig)
        h.http.open(0)
        h.listeners0().onMessage(h.http.sockets[0], """{"Response":{"Ok":{}}}""")
        assertEquals(StreamConnectionStatus.Connected, h.client.state.value.status)

        // Fire 1008 DATA_EXPIRED twice before refresh completes: must coalesce to 1 refresh call.
        h.listeners0().onClosing(h.http.sockets[0], 1008, "DATA_EXPIRED")
        h.listeners0().onFailure(h.http.sockets[0], IOException("expired"), h.http.response(0, 401))
        assertEquals(StreamConnectionStatus.DataExpired, h.client.state.value.status)
        assertEquals(1, refreshCalls)

        gate.complete(true)
        assertEquals(2, h.http.sockets.size)
        h.http.open(1)
        h.http.listeners[1].onMessage(h.http.sockets[1], """{"Response":{"Ok":{}}}""")
        assertEquals(StreamConnectionStatus.Connected, h.client.state.value.status)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun frameDrop_throttlesRequestIdrTo500ms() = runTest {
        val h = Harness(this)
        h.client.connect(h.wsConfig)
        h.http.open(0)
        h.listeners0().onMessage(h.http.sockets[0], """{"Response":{"Ok":{}}}""")
        assertEquals(StreamConnectionStatus.Connected, h.client.state.value.status)

        val expectedIdr = MoonlightWsProtocol.encodeRequestIdr()
        fun idrCount() = h.http.sockets[0].sentBytes.count { it.contentEquals(expectedIdr) }

        assertEquals(0, idrCount())
        h.sink.fireFrameDrop()
        assertEquals(1, idrCount())

        advanceTimeBy(100L)
        h.sink.fireFrameDrop()
        assertEquals(1, idrCount())

        advanceTimeBy(399L)
        h.sink.fireFrameDrop()
        assertEquals(1, idrCount())

        advanceTimeBy(1L)
        h.sink.fireFrameDrop()
        assertEquals(2, idrCount())

        h.client.disconnect()
        h.close()
    }

    @Test
    fun connectWebSocketWithProbe_abortsIfDisconnectedDuringProbe() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val okHttp = okhttp3.OkHttpClient()
        val probeStarted = java.util.concurrent.CountDownLatch(1)
        val releaseProbe = java.util.concurrent.CountDownLatch(1)
        val wsUpgradeAttempted = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/api/user") -> {
                            probeStarted.countDown()
                            releaseProbe.await(3, java.util.concurrent.TimeUnit.SECONDS)
                            okhttp3.mockwebserver.MockResponse().setBody("""{"id":1,"role_id":2}""")
                        }
                        path.startsWith("/api/role") -> okhttp3.mockwebserver.MockResponse().setBody("""{"allow_transport_websockets":true}""")
                        path == "/api/hosts" -> okhttp3.mockwebserver.MockResponse().setBody("""{"hosts":[{"host_id":7,"name":"desktop","paired":"Paired"}]}""")
                        path.startsWith("/api/host?") -> okhttp3.mockwebserver.MockResponse().setBody("""{"host_id":7,"name":"desktop","address":"192.0.2.20","paired":"Paired"}""")
                        path.startsWith("/api/apps") -> okhttp3.mockwebserver.MockResponse().setBody("""[{"id":1,"title":"Desktop"}]""")
                        path == "/api/host/stream/web_socket" -> {
                            wsUpgradeAttempted.incrementAndGet()
                            okhttp3.mockwebserver.MockResponse().setResponseCode(500)
                        }
                        else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                    }
                }
            }

            val sink = FakeVideoFrameSink()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val transport = MoonlightWebSocketTransport(
                client = okHttp,
                frameSink = sink,
                audioSink = null,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            )
            val client = DualPathMoonlightClient(
                nativeBridge = MoonlightNativeBridge(),
                webSocketTransport = transport,
                frameSink = sink,
                directUdpProber = { _, _ -> false },
                nativeDispatcher = dispatcher,
                clientScope = scope,
            )

            val wsUrl = server.url("/api/host/stream/web_socket").toString().replace("http://", "ws://")
            val connectFinished = java.util.concurrent.CountDownLatch(1)
            val connectThread = Thread {
                kotlinx.coroutines.runBlocking {
                    client.connect(StreamConfig(wsUrl = wsUrl, transportMode = StreamTransportMode.WEBSOCKET_443))
                }
                connectFinished.countDown()
            }
            connectThread.start()

            assertTrue(probeStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            client.disconnect()
            releaseProbe.countDown()
            assertTrue(connectFinished.await(3, java.util.concurrent.TimeUnit.SECONDS))

            assertEquals(0, wsUpgradeAttempted.get())
            assertEquals(StreamConnectionStatus.Disconnected, client.state.value.status)
            scope.cancel()
        } finally {
            releaseProbe.countDown()
            okHttp.dispatcher.executorService.shutdown()
            okHttp.connectionPool.evictAll()
            runCatching { server.shutdown() }
        }
    }

    private fun Harness.listeners0() = http.listeners[0]

    private class FakeVideoFrameSink : VideoFrameSink {
        var error: String? = null
        private var listener: ((String) -> Unit)? = null
        private var dropListener: (() -> Unit)? = null

        override val lastError: String?
            get() = error

        override fun setErrorListener(listener: ((String) -> Unit)?) {
            this.listener = listener
        }

        override fun setFrameDropListener(listener: (() -> Unit)?) {
            this.dropListener = listener
        }

        fun fireError(message: String) {
            error = message
            listener?.invoke(message)
        }

        fun fireFrameDrop() {
            dropListener?.invoke()
        }

        override fun configure(width: Int, height: Int, codec: StreamCodec, surface: android.view.Surface?) {}
        override fun submitAccessUnit(data: ByteArray, offset: Int, length: Int, ptsUs: Long, flags: Int) {}
        override fun release() {}
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getPackageManager(): PackageManager? = null
    }
}
