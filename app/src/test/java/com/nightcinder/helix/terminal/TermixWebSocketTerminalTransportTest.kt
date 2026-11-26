package com.nightcinder.helix.terminal

import com.nightcinder.helix.api.TermixApiClient
import com.nightcinder.helix.core.model.TerminalConnectionState
import com.nightcinder.helix.core.model.TerminalSessionConfig
import com.nightcinder.helix.core.model.TerminalSize
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.core.terminal.TerminalEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TermixWebSocketTerminalTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var termixApi: TermixApiClient
    private lateinit var transport: TermixWebSocketTerminalTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        termixApi = TermixApiClient(
            client = okHttpClient,
            baseUrl = server.url("/").toString(),
        )

        transport = TermixWebSocketTerminalTransport(
            client = okHttpClient,
            termixApi = termixApi,
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            transport.disconnectAll()
        }
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        try {
            server.shutdown()
        } catch (_: Exception) {
            // Ignore MockWebServer teardown race on Windows
        }
    }

    @Test
    fun resolveEndpointUrl_convertsHttpsToWssWithVerifiedPaths() {
        val prodTransport = TermixWebSocketTerminalTransport(
            client = okHttpClient,
            termixApi = termixApi,
            baseUrl = "https://termix.example.com",
        )

        val sshUrl = prodTransport.resolveEndpointUrl(
            TerminalTarget.SshHost(hostId = 1, hostName = "core")
        )
        assertEquals("wss://termix.example.com/plugin-ws/ssh-terminal/terminal", sshUrl)

        val dockerUrl = prodTransport.resolveEndpointUrl(
            TerminalTarget.DockerConsole(hostId = 1, containerId = "c123")
        )
        assertEquals("wss://termix.example.com/plugin-ws/docker/console", dockerUrl)
    }

    @Test
    fun connectSsh_mintsWsTicket_setsSubprotocol_sendsConnectToHost_and_handlesFrames() = runBlocking {
        // 1. Response for POST /users/ws-ticket
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticket":"ticket_ssh_999","expiresInSeconds":60}""")
        )

        val serverReceivedMessages = CopyOnWriteArrayList<String>()
        val initLatch = CountDownLatch(1)
        // 6 frames expected: connectToHost, pong, input, resize, tmux_attach, tmux_detach
        val allFramesLatch = CountDownLatch(6)
        val closedLatch = CountDownLatch(1)
        var serverWebSocket: WebSocket? = null

        // 2. WebSocket upgrade response for /plugin-ws/ssh-terminal/terminal
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverWebSocket = webSocket
                        webSocket.send("""{"type":"output","data":"Welcome to core\r\n"}""")
                        webSocket.send("""{"type":"ping"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverReceivedMessages.add(text)
                        initLatch.countDown()
                        allFramesLatch.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        closedLatch.countDown()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closedLatch.countDown()
                    }
                })
        )

        val collectedEvents = CopyOnWriteArrayList<TerminalEvent>()
        val outputLatch = CountDownLatch(1)
        val collectJob: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            transport.events.collect { event ->
                collectedEvents.add(event)
                if (event is TerminalEvent.Output) {
                    outputLatch.countDown()
                }
            }
        }

        val config = TerminalSessionConfig(
            tabId = "tab-ssh-1",
            target = TerminalTarget.SshHost(
                hostId = 1,
                hostName = "core",
                initialPath = "/srv/docker",
                executeCommand = "ls -la",
                tmuxSession = "homelab",
            ),
            size = TerminalSize(cols = 120, rows = 36),
            hostIp = "192.168.1.10",
            hostPort = 22,
            hostUsername = "user",
        )

        transport.connect(config)

        // Verify ticket request was POST /users/ws-ticket with X-Electron-App: true
        val ticketReq = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", ticketReq.method)
        assertEquals("/users/ws-ticket", ticketReq.path)
        assertEquals("true", ticketReq.getHeader("X-Electron-App"))

        // Verify WS handshake request had Sec-WebSocket-Protocol: termix.jwt.ticket_ssh_999
        val wsReq = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/plugin-ws/ssh-terminal/terminal", wsReq.path)
        assertEquals("termix.jwt.ticket_ssh_999", wsReq.getHeader("Sec-WebSocket-Protocol"))

        assertTrue(initLatch.await(3, TimeUnit.SECONDS))
        assertTrue(outputLatch.await(3, TimeUnit.SECONDS))

        // Send input, resize, tmux_attach, tmux_detach
        transport.sendInput("tab-ssh-1", "htop\r")
        transport.resize("tab-ssh-1", cols = 132, rows = 42)
        transport.attachTmux("tab-ssh-1", "ops")
        transport.detachTmux("tab-ssh-1")

        assertTrue(allFramesLatch.await(3, TimeUnit.SECONDS))

        val joinedClientFrames = serverReceivedMessages.joinToString("\n")
        assertTrue(joinedClientFrames.contains(""""type":"connectToHost""""))
        assertTrue(joinedClientFrames.contains(""""initialPath":"/srv/docker""""))
        assertTrue(joinedClientFrames.contains(""""executeCommand":"ls -la""""))
        assertTrue(joinedClientFrames.contains(""""tmuxAttachSession":"homelab""""))
        assertTrue(joinedClientFrames.contains(""""type":"pong""""))
        assertTrue(joinedClientFrames.contains(""""type":"input""""))
        assertTrue(joinedClientFrames.contains(""""type":"resize""""))
        assertTrue(joinedClientFrames.contains(""""cols":132"""))
        assertTrue(joinedClientFrames.contains(""""rows":42"""))
        assertTrue(joinedClientFrames.contains(""""type":"tmux_attach""""))
        assertTrue(joinedClientFrames.contains(""""type":"tmux_detach""""))

        assertTrue(
            collectedEvents.any {
                it is TerminalEvent.Output && it.tabId == "tab-ssh-1" && it.data.contains("Welcome to core")
            }
        )

        transport.disconnect("tab-ssh-1")
        serverWebSocket?.close(1000, "bye")
        closedLatch.await(2, TimeUnit.SECONDS)
        collectJob.cancel()
    }

    @Test
    fun connectDockerConsole_and_close1008_emitsDataExpired() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ticket":"ticket_docker_777"}""")
        )

        val connectFrameLatch = CountDownLatch(1)
        val serverMessages = CopyOnWriteArrayList<String>()

        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverMessages.add(text)
                        connectFrameLatch.countDown()
                        // Simulate Termix server closing WS with 1008 DATA_EXPIRED
                        webSocket.close(1008, "DATA_EXPIRED")
                    }
                })
        )

        // Wait for both TerminalEvent.DataExpired and TerminalEvent.StateChanged(DataExpired)
        val dataExpiredLatch = CountDownLatch(2)
        val collectedEvents = CopyOnWriteArrayList<TerminalEvent>()
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            transport.events.collect { event ->
                collectedEvents.add(event)
                if (event is TerminalEvent.DataExpired) {
                    dataExpiredLatch.countDown()
                } else if (event is TerminalEvent.StateChanged &&
                    event.state == TerminalConnectionState.DataExpired
                ) {
                    dataExpiredLatch.countDown()
                }
            }
        }

        val config = TerminalSessionConfig(
            tabId = "tab-docker-1",
            target = TerminalTarget.DockerConsole(
                hostId = 2,
                hostName = "desktop",
                containerId = "abc123def456",
                containerName = "traefik",
                shell = "/bin/bash",
            ),
            size = TerminalSize(cols = 90, rows = 28),
        )

        transport.connect(config)

        val ticketReq = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/users/ws-ticket", ticketReq.path)

        val wsReq = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/plugin-ws/docker/console", wsReq.path)
        assertEquals("termix.jwt.ticket_docker_777", wsReq.getHeader("Sec-WebSocket-Protocol"))

        assertTrue(connectFrameLatch.await(3, TimeUnit.SECONDS))
        assertTrue(dataExpiredLatch.await(3, TimeUnit.SECONDS))

        val firstMsg = serverMessages.first()
        assertTrue(firstMsg.contains(""""type":"connect""""))
        assertTrue(firstMsg.contains(""""containerId":"abc123def456""""))
        assertTrue(firstMsg.contains(""""shell":"/bin/bash""""))

        assertTrue(
            collectedEvents.any {
                it is TerminalEvent.DataExpired && it.tabId == "tab-docker-1" && it.closeCode == 1008
            }
        )
        assertTrue(
            collectedEvents.any {
                it is TerminalEvent.StateChanged &&
                    it.tabId == "tab-docker-1" &&
                    it.state == TerminalConnectionState.DataExpired
            }
        )

        collectJob.cancel()
    }
}
