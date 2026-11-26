package com.nightcinder.helix.clipboard

import com.nightcinder.helix.core.model.ClipboardSource
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.TerminalSessionConfig
import com.nightcinder.helix.core.terminal.TerminalEvent
import com.nightcinder.helix.core.terminal.TerminalTransport
import com.nightcinder.helix.stream.nv.NvGateway
import com.nightcinder.helix.stream.nv.NvLaunch
import com.nightcinder.helix.stream.nv.PairResult
import com.nightcinder.helix.stream.nv.PairingCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class UnifiedClipboardBridgeTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var memoryAdapter: InMemoryClipboardAdapter
    private lateinit var fakeTerminalTransport: FakeTerminalTransport
    private lateinit var bridge: UnifiedClipboardBridge
    private var activeTabId: String? = "tab-ssh-main"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        okHttpClient = OkHttpClient()
        memoryAdapter = InMemoryClipboardAdapter()
        fakeTerminalTransport = FakeTerminalTransport()

        bridge = UnifiedClipboardBridge(
            clipboardAdapter = memoryAdapter,
            terminalTransport = fakeTerminalTransport,
            activeTabProvider = { activeTabId },
            client = okHttpClient,
            remoteBaseUrlProvider = { server.url("/").toString() },
            mainDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pullFromRemote_updatesLocalAdapterAndState() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5..."}""")
        )

        val result = bridge.pullFromRemote()
        assertTrue(result.isSuccess)
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5...", result.getOrThrow())
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5...", memoryAdapter.getText())

        val state = bridge.state.value
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5...", state.lastSyncedText)
        assertEquals(ClipboardSource.HOST_REMOTE, state.lastSource)
        assertFalse(state.isSyncing)
    }

    @Test
    fun pushToRemote_sendsPostWithXElectronApp() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true}""")
        )

        val result = bridge.pushToRemote("echo 'deploying'")
        assertTrue(result.isSuccess)

        val recorded = server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/clipboard", recorded.path)
        assertEquals("true", recorded.getHeader("X-Electron-App"))
        assertTrue(recorded.body.readUtf8().contains("echo 'deploying'"))

        assertEquals("echo 'deploying'", bridge.state.value.lastSyncedText)
        assertEquals(ClipboardSource.ANDROID, bridge.state.value.lastSource)
    }

    @Test
    fun pushToRemote_preventsEchoLoopsForIdenticalContent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true}""")
        )

        bridge.pushToRemote("test content")
        assertEquals(1, server.requestCount)

        // Second push of identical content is suppressed by hash check
        bridge.pushToRemote("test content")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun pasteToActiveTerminal_wrapsInBracketedPasteEscapeCodes() = runBlocking {
        val result = bridge.pasteToActiveTerminal("sudo apt update\nsudo apt upgrade")
        assertTrue(result.isSuccess)

        val sent = fakeTerminalTransport.sentInputs
        assertEquals(1, sent.size)
        assertEquals("tab-ssh-main", sent[0].tabId)
        assertEquals(
            "\u001B[200~sudo apt update\nsudo apt upgrade\u001B[201~",
            sent[0].data,
        )

        assertEquals("sudo apt update\nsudo apt upgrade", bridge.state.value.lastSyncedText)
        assertEquals(ClipboardSource.SSH_TERMINAL, bridge.state.value.lastSource)
    }

    @Test
    fun onLocalClipboardChanged_autoSyncsWhenEnabled() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true}""")
        )

        bridge.setAutoSync(true)
        val res = bridge.onLocalClipboardChanged("new snippet", ClipboardSource.ANDROID)
        assertTrue(res.isSuccess)

        val req = server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)!!
        assertTrue(req.body.readUtf8().contains("new snippet"))

        // When source is HOST_REMOTE, it does not re-push to remote (avoiding infinite remote loop)
        bridge.onLocalClipboardChanged("remote snippet", ClipboardSource.HOST_REMOTE)
        assertEquals(1, server.requestCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun vibepolloGateway_pullDoesNotEcho_pushLocalReadsLiveClip_andAutoSyncPushes() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val fakeGateway = FakeClipboardGateway(remoteText = "from-win-clipboard")
            val testScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
            val liveAdapter = InMemoryClipboardAdapter()
            val nvBridge = UnifiedClipboardBridge(
                clipboardAdapter = liveAdapter,
                terminalTransport = fakeTerminalTransport,
                activeTabProvider = { activeTabId },
                client = okHttpClient,
                nvGateway = fakeGateway,
                streamHostIpProvider = { "192.0.2.20" },
                canAutoPushProvider = { true },
                bridgeScope = testScope,
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
            )

            // 1. Pull updates local adapter; echo suppression ensures PrimaryClip listener doesn't re-POST it.
            val pulled = nvBridge.pullFromRemote()
            assertTrue(pulled.isSuccess)
            assertEquals("from-win-clipboard", liveAdapter.getText())
            assertEquals(0, fakeGateway.setCalls.size)

            // 2. Local copy with autoSync enabled triggers automatic push to 192.0.2.20.
            liveAdapter.setText("copied-on-aura")
            assertEquals(listOf("192.0.2.20" to "copied-on-aura"), fakeGateway.setCalls.toList())

            // 3. Disabling autoSync skips auto-push on copy, while manual pushLocalClipboard() pushes current clip.
            nvBridge.setAutoSync(false)
            liveAdapter.setText("manual-push-text")
            assertEquals(1, fakeGateway.setCalls.size)

            val pushed = nvBridge.pushLocalClipboard()
            assertTrue(pushed.isSuccess)
            assertEquals(2, fakeGateway.setCalls.size)
            assertEquals("192.0.2.20" to "manual-push-text", fakeGateway.setCalls.last())
            testScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeClipboardGateway(var remoteText: String = "") : NvGateway {
        val setCalls = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun checkPairing(host: String): PairingCheck = PairingCheck.Paired
        override suspend fun pair(host: String, pin: String): PairResult = PairResult.PinWrong
        override suspend fun launch(host: String, config: StreamConfig, launchQueryParams: String): NvLaunch =
            error("unused")
        override suspend fun forgetPairing(host: String) {}
        override suspend fun getClipboard(host: String): String = remoteText
        override suspend fun setClipboard(host: String, text: String) {
            remoteText = text
            setCalls.add(host to text)
        }
    }

    private data class SentInput(val tabId: String, val data: String)

    private class FakeTerminalTransport : TerminalTransport {
        val sentInputs = CopyOnWriteArrayList<SentInput>()
        override val events: Flow<TerminalEvent> = emptyFlow()
        override suspend fun connect(config: TerminalSessionConfig) {}
        override suspend fun sendInput(tabId: String, data: String) {
            sentInputs.add(SentInput(tabId, data))
        }
        override suspend fun resize(tabId: String, cols: Int, rows: Int) {}
        override suspend fun attachTmux(tabId: String, session: String) {}
        override suspend fun detachTmux(tabId: String) {}
        override suspend fun disconnect(tabId: String) {}
    }
}
