package com.nightcinder.helix.stream

import android.view.Surface
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.core.model.StreamResolution
import com.nightcinder.helix.core.model.StreamTelemetry
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.stream.VideoFrameSink
import com.nightcinder.helix.stream.nv.NvGateway
import com.nightcinder.helix.stream.nv.NvLaunch
import com.nightcinder.helix.stream.nv.PairResult
import com.nightcinder.helix.stream.nv.PairingCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native direct path state machine with a fake host gateway and a fake JNI bridge (no network, no native code). */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectStreamClientTest {

    private class FakeBridge : MoonlightNativeBridge() {
        var startOk = true
        var startParams: NativeStartParams? = null
        var startSurface: Surface? = null
        val surfaceCalls = mutableListOf<Surface?>()
        var stopCount = 0
        var fakeStats: StreamTelemetry? = null
        val inputs = mutableListOf<String>()

        override fun launchQueryParameters() = "&extra=1"

        override fun start(params: NativeStartParams, surface: Surface?): Boolean {
            startParams = params
            startSurface = surface
            return startOk
        }

        override fun setSurface(surface: Surface?) {
            surfaceCalls += surface
        }

        override fun stop() {
            stopCount++
            super.stop()
        }

        override fun getStats(): StreamTelemetry? = fakeStats

        override fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) {
            inputs += "pos:$x,$y/$referenceWidth,$referenceHeight"
        }

        override fun sendMouseButton(button: Byte, down: Boolean) {
            inputs += "btn:$button:$down"
        }

        override fun sendHighResScroll(amount: Short) {
            inputs += "scroll:$amount"
        }

        override fun sendHighResHScroll(amount: Short) {
            inputs += "hscroll:$amount"
        }

        fun emit(type: Int, a: Int = 0, b: Int = 0, text: String? = null) = onNativeEvent(type, a, b, text)
    }

    private class FakeGateway : NvGateway {
        var pairing: PairingCheck = PairingCheck.Paired
        var pairResult: PairResult = PairResult.PinWrong
        var pairGate: CompletableDeferred<Unit>? = null
        var pairedPin: String? = null
        var launches = 0
        var checkError: Exception? = null
        var launchConfig: StreamConfig? = null
        var launchQuery: String? = null

        override suspend fun checkPairing(host: String): PairingCheck {
            checkError?.let { throw it }
            return pairing
        }

        override suspend fun pair(host: String, pin: String): PairResult {
            pairedPin = pin
            pairGate?.await()
            return pairResult
        }

        override suspend fun launch(host: String, config: StreamConfig, launchQueryParams: String): NvLaunch {
            launches++
            launchConfig = config
            launchQuery = launchQueryParams
            return NvLaunch(
                host = host,
                rtspSessionUrl = "rtspenc://$host:48010",
                appVersion = "7.1.431.-1",
                gfeVersion = "3.23.0.74",
                serverCodecModeSupport = 126092033,
                riKey = ByteArray(16) { it.toByte() },
                riKeyId = 42,
                appTitle = "Desktop",
                resumed = false,
            )
        }

        override suspend fun forgetPairing(host: String) {
            pairing = PairingCheck.NotPaired
        }
    }

    private class NoopSink : VideoFrameSink {
        override fun configure(width: Int, height: Int, codec: StreamCodec, surface: Surface?) {}
        override fun submitAccessUnit(data: ByteArray, offset: Int, length: Int, ptsUs: Long, flags: Int) {}
        override fun release() {}
    }

    private class Harness(testScope: TestScope, val gateway: FakeGateway = FakeGateway()) {
        val dispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val bridge = FakeBridge()
        val sink = NoopSink()
        val client = DualPathMoonlightClient(
            nativeBridge = bridge,
            webSocketTransport = MoonlightWebSocketTransport(client = FakeWebSocketClient(), frameSink = sink),
            frameSink = sink,
            nvGateway = gateway,
            directUdpProber = { _, _ -> true },
            nativeDispatcher = dispatcher,
            clientScope = scope,
            pinGenerator = { "4821" },
            nativeAvailable = { true },
        )
        val config = StreamConfig(
            hostIp = "192.0.2.20",
            codec = StreamCodec.HEVC,
            resolution = StreamResolution.HD_1080P,
            transportMode = StreamTransportMode.DIRECT_UDP,
        )

        fun close() = scope.cancel()
    }

    private fun harnessTest(
        gateway: FakeGateway = FakeGateway(),
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        val h = Harness(this, gateway)
        try {
            body(h)
        } finally {
            h.close() // always stop background polling, or runTest would spin on it after a failed assertion
        }
    }

    // Native event ids as emitted by helix_moonlight_jni.cpp
    private val evStageStarting = 1
    private val evStageFailed = 3
    private val evStarted = 4
    private val evTerminated = 5
    private val evStartResult = 6

    @Test
    fun pairedHost_startsNativeButIsNotConnectedUntilConnectionStartedCallback() = harnessTest { h ->
        h.client.connect(h.config)

        val params = h.bridge.startParams
        assertNotNull(params)
        assertEquals("192.0.2.20", params!!.host)
        assertEquals("rtspenc://192.0.2.20:48010", params.rtspSessionUrl)
        assertEquals(16, params.riKey.size)
        assertEquals(42, params.riKeyId)
        assertEquals(1920, params.width)
        assertEquals(1080, params.height)
        assertEquals("&extra=1", h.gateway.launchQuery)
        assertTrue(params.supportedVideoFormats and MoonlightNativeBridge.VIDEO_FORMAT_H264 != 0)

        // Native engine started, but nothing was established yet: must NOT claim Connected.
        assertEquals(StreamConnectionStatus.Connecting, h.client.state.value.status)
        h.bridge.emit(evStageStarting, 4, text = "RTSP handshake")
        assertEquals("RTSP handshake", h.client.state.value.stage)
        assertEquals(StreamConnectionStatus.Connecting, h.client.state.value.status)

        h.bridge.emit(evStarted)
        assertEquals(StreamConnectionStatus.Connected, h.client.state.value.status)
        assertEquals(StreamTransportMode.DIRECT_UDP, h.client.state.value.activeTransport)

        h.client.disconnect()
        assertEquals(StreamConnectionStatus.Disconnected, h.client.state.value.status)
        assertTrue(h.bridge.stopCount >= 1)
        h.close()
    }

    @Test
    fun telemetry_comesFromNativeStatsOnly() = harnessTest { h ->
        h.client.connect(h.config)
        assertEquals(0.0, h.client.telemetry.value.fps, 0.0)

        h.bridge.fakeStats = StreamTelemetry(fps = 58.0, bitrateKbps = 19000, rttMs = 3, width = 1920, height = 1080)
        h.bridge.emit(evStarted)
        assertEquals(58.0, h.client.telemetry.value.fps, 0.0)
        assertEquals(3, h.client.telemetry.value.rttMs)

        h.client.disconnect()
        assertEquals(0.0, h.client.telemetry.value.fps, 0.0)
        h.close()
    }

    @Test
    fun stageFailure_surfacesErrorAndNeverConnected() = harnessTest { h ->
        h.client.connect(h.config)

        h.bridge.emit(evStageFailed, 4, -1, "RTSP handshake")
        h.bridge.emit(evStartResult, -1)

        val s = h.client.state.value
        assertEquals(StreamConnectionStatus.Error, s.status)
        assertTrue(s.errorMessage!!.contains("RTSP handshake"))
        h.client.disconnect()
        h.close()
    }

    @Test
    fun hostEndsStreamGracefully_becomesDisconnected_andLossBecomesError() = harnessTest { h ->
        h.client.connect(h.config)
        h.bridge.emit(evStarted)
        h.bridge.emit(evTerminated, 0)
        assertEquals(StreamConnectionStatus.Disconnected, h.client.state.value.status)

        h.client.connect(h.config)
        h.bridge.emit(evStarted)
        h.bridge.emit(evTerminated, -100)
        assertEquals(StreamConnectionStatus.Error, h.client.state.value.status)
        assertTrue(h.client.state.value.errorMessage!!.contains("-100"))
        h.client.disconnect()
        h.close()
    }

    @Test
    fun nativeStartRefused_isAnErrorNotConnected() = harnessTest { h ->
        h.bridge.startOk = false
        h.client.connect(h.config)
        assertEquals(StreamConnectionStatus.Error, h.client.state.value.status)
        h.client.disconnect()
        h.close()
    }

    @Test
    fun hostUnreachable_isError() = harnessTest { h ->
        h.gateway.checkError = java.io.IOException("Failed to connect to /192.0.2.20:47989")
        h.client.connect(h.config)
        assertEquals(StreamConnectionStatus.Error, h.client.state.value.status)
        assertTrue(h.client.state.value.errorMessage!!.contains("192.0.2.20"))
        assertNull(h.bridge.startParams)
        h.close()
    }

    @Test
    fun unpairedHost_showsPinAndLaunchesOnlyAfterPairingSucceeds() = harnessTest(FakeGateway().apply {
            pairing = PairingCheck.NotPaired
            pairGate = CompletableDeferred()
            pairResult = PairResult.Paired(com.nightcinder.helix.stream.nv.ClientIdentity.generate().certificate)
        }) { h ->
        val gateway = h.gateway
        h.client.connect(h.config)

        assertEquals(StreamConnectionStatus.Pairing, h.client.state.value.status)
        assertEquals("4821", h.client.state.value.pairingPin)
        assertEquals("4821", gateway.pairedPin)
        assertEquals(0, gateway.launches)

        gateway.pairGate!!.complete(Unit)
        assertEquals(1, gateway.launches)
        assertNotNull(h.bridge.startParams)
        assertEquals(StreamConnectionStatus.Connecting, h.client.state.value.status)
        assertNull(h.client.state.value.pairingPin)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun cancelPairing_returnsToNotPairedWithoutLaunching() = harnessTest(FakeGateway().apply {
            pairing = PairingCheck.NotPaired
            pairGate = CompletableDeferred()
        }) { h ->
        val gateway = h.gateway
        h.client.connect(h.config)
        assertEquals(StreamConnectionStatus.Pairing, h.client.state.value.status)

        h.client.cancelPairing()

        assertEquals(StreamConnectionStatus.NotPaired, h.client.state.value.status)
        assertNull(h.client.state.value.pairingPin)
        assertEquals(0, gateway.launches)
        assertNull(h.bridge.startParams)
        h.close()
    }

    @Test
    fun wrongPin_isErrorAndDoesNotLaunch() = harnessTest(FakeGateway().apply {
            pairing = PairingCheck.NotPaired
            pairResult = PairResult.PinWrong
        }) { h ->
        val gateway = h.gateway
        h.client.connect(h.config)
        assertEquals(StreamConnectionStatus.Error, h.client.state.value.status)
        assertEquals(0, gateway.launches)
        h.close()
    }

    @Test
    fun detachingANonCurrentSurface_doesNotTouchTheStream() = harnessTest { h ->
        h.client.connect(h.config)
        h.bridge.emit(evStarted)
        val windowA = Surface(android.graphics.SurfaceTexture(0))
        val windowB = Surface(android.graphics.SurfaceTexture(1))

        h.client.attachSurface(windowA)
        h.client.attachSurface(windowB) // most recent surface renders
        assertEquals(listOf<Surface?>(windowA, windowB), h.bridge.surfaceCalls)

        h.client.detachSurface(windowA) // not current: ignored
        assertEquals(2, h.bridge.surfaceCalls.size)
        assertEquals(StreamConnectionStatus.Connected, h.client.state.value.status)

        val stopsBefore = h.bridge.stopCount
        h.client.detachSurface(windowB) // current: stream keeps running, frames just dropped
        assertEquals(listOf<Surface?>(windowA, windowB, null), h.bridge.surfaceCalls)
        assertEquals(StreamConnectionStatus.Connected, h.client.state.value.status)
        assertEquals(stopsBefore, h.bridge.stopCount)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun lateNativeEventsAfterDisconnect_areIgnored_andStopIsIdempotent() = harnessTest { h ->
        h.client.connect(h.config)
        h.client.disconnect()
        h.client.disconnect()

        h.bridge.emit(evStarted)
        assertEquals(StreamConnectionStatus.Disconnected, h.client.state.value.status)
        h.close()
    }

    @Test
    fun input_isForwardedOnlyWhileConnected() = harnessTest { h ->
        h.client.connect(h.config)
        h.client.sendInput(StreamInputEvent.MousePosition(10, 20, 1000, 500))
        assertTrue(h.bridge.inputs.isEmpty())

        h.bridge.emit(evStarted)
        h.client.sendInput(StreamInputEvent.MousePosition(10, 20, 1000, 500))
        h.client.sendInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, true))
        h.client.sendInput(StreamInputEvent.HighResScroll(-120))
        h.client.sendInput(StreamInputEvent.HighResHScroll(120))
        assertEquals(listOf("pos:10,20/1000,500", "btn:1:true", "scroll:-120", "hscroll:120"), h.bridge.inputs)

        h.client.disconnect()
        h.close()
    }

    @Test
    fun forgetPairing_goesBackToNotPaired() = harnessTest { h ->
        h.client.connect(h.config)
        h.client.forgetPairing()
        assertEquals(StreamConnectionStatus.NotPaired, h.client.state.value.status)
        h.close()
    }
}
