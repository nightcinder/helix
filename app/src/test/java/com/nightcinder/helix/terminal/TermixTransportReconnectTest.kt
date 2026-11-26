package com.nightcinder.helix.terminal

import com.nightcinder.helix.core.api.TermixApi
import com.nightcinder.helix.core.api.WsTicket
import com.nightcinder.helix.core.model.TerminalConnectionState
import com.nightcinder.helix.core.model.TerminalSessionConfig
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.core.terminal.TerminalEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TermixTransportReconnectTest {

    private class FakeSocket(val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        var closedWith: Pair<Int, String>? = null
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean { closedWith = code to (reason ?: ""); return true }
        override fun cancel() {}
    }

    private class FakeFactory : WebSocket.Factory {
        val sockets = mutableListOf<FakeSocket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request, listener).also { sockets += it }
    }

    private val ticketCounter = AtomicInteger(0)

    private fun fakeApi(): TermixApi =
        Proxy.newProxyInstance(
            TermixApi::class.java.classLoader,
            arrayOf(TermixApi::class.java),
        ) { _, method, _ ->
            if (method.name == "createWsTicket") WsTicket(ticket = "ticket-${ticketCounter.incrementAndGet()}")
            else throw UnsupportedOperationException(method.name)
        } as TermixApi

    private fun response(socket: FakeSocket): Response =
        Response.Builder().request(socket.request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build()

    private fun sshConfig(tabId: String, tmux: String? = null) = TerminalSessionConfig(
        tabId = tabId,
        target = TerminalTarget.SshHost(hostId = 1, hostName = "core", tmuxSession = tmux),
    )

    private fun TestScope.newTransport(
        factory: FakeFactory,
        reauth: (suspend () -> Boolean)? = null,
    ) = TermixWebSocketTerminalTransport(
        client = factory,
        termixApi = fakeApi(),
        baseUrlProvider = { "https://termix.test" },
        onDataExpiredReauth = reauth,
        scope = backgroundScope,
        clockMs = { currentTime },
    )

    private fun TestScope.collect(transport: TermixWebSocketTerminalTransport): MutableList<TerminalEvent> {
        val events = mutableListOf<TerminalEvent>()
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)).launch { transport.events.toList(events) }
        return events
    }

    @Test
    fun staleSocketCallbacksAreIgnored_andNewSessionKeepsReceivingInput() = runTest {
        val factory = FakeFactory()
        val transport = newTransport(factory)
        val events = collect(transport)

        transport.connect(sshConfig("tab"))
        transport.connect(sshConfig("tab"))
        assertEquals(2, factory.sockets.size)
        val (old, new) = factory.sockets
        events.clear()

        old.listener.onClosing(old, 1006, "stale")
        old.listener.onClosed(old, 1006, "stale")
        old.listener.onFailure(old, RuntimeException("stale"), null)
        old.listener.onMessage(old, "stale output")
        runCurrent()

        assertTrue("stale socket emitted $events", events.isEmpty())
        transport.sendInput("tab", "ls\r")
        assertTrue(new.sent.any { it.contains("\"type\":\"input\"") })
        assertEquals(2, ticketCounter.get())
    }

    @Test
    fun closeIsHandledOncePerSocket() = runTest {
        val factory = FakeFactory()
        val transport = newTransport(factory)
        val events = collect(transport)
        transport.connect(sshConfig("tab"))
        val ws = factory.sockets.single()
        events.clear()

        ws.listener.onClosing(ws, 1000, "bye")
        ws.listener.onClosed(ws, 1000, "bye")

        assertEquals(
            1,
            events.count { it is TerminalEvent.StateChanged && it.state == TerminalConnectionState.Disconnected },
        )
    }

    @Test
    fun unexpectedFailure_reconnectsWithBackoffAndFreshTicketUntilGivingUp() = runTest {
        val factory = FakeFactory()
        val transport = newTransport(factory)
        val events = collect(transport)
        transport.connect(sshConfig("tab", tmux = "ops"))

        val expectedDelays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        expectedDelays.forEachIndexed { index, delayMs ->
            val before = factory.sockets.size
            val last = factory.sockets.last()
            last.listener.onFailure(last, RuntimeException("boom"), null)
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals("no reconnect before ${delayMs}ms", before, factory.sockets.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("reconnect after ${delayMs}ms", before + 1, factory.sockets.size)
            assertEquals("termix.jwt.ticket-${index + 2}", factory.sockets.last().request.header("Sec-WebSocket-Protocol"))
        }
        assertTrue(events.any { it is TerminalEvent.StateChanged && it.state == TerminalConnectionState.Reconnecting })

        // 7th failure: out of attempts
        val last = factory.sockets.last()
        events.clear()
        last.listener.onFailure(last, RuntimeException("boom"), null)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(7, factory.sockets.size)
        assertTrue(events.any { it is TerminalEvent.StateChanged && it.state == TerminalConnectionState.Error })
    }

    @Test
    fun disconnectCancelsPendingReconnect() = runTest {
        val factory = FakeFactory()
        val transport = newTransport(factory)
        transport.connect(sshConfig("tab"))
        val ws = factory.sockets.single()
        ws.listener.onClosed(ws, 1006, "drop")
        transport.disconnect("tab")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, factory.sockets.size)
    }

    @Test
    fun dataExpired_reauthIsSingleFlightAcrossTabs_thenTabsReconnect() = runTest {
        val factory = FakeFactory()
        val reauthCalls = AtomicInteger(0)
        val transport = newTransport(factory) {
            reauthCalls.incrementAndGet()
            delay(100)
            true
        }
        val events = collect(transport)
        transport.connect(sshConfig("a"))
        transport.connect(sshConfig("b"))
        val (a, b) = factory.sockets

        a.listener.onClosing(a, 1008, "DATA_EXPIRED")
        b.listener.onClosing(b, 1008, "DATA_EXPIRED")
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, reauthCalls.get())
        assertEquals(4, factory.sockets.size)
        assertTrue(events.none { it is TerminalEvent.DataExpired })

        // Second expiry for the same tab inside 60 s: no loop, DataExpired surfaces.
        val a2 = factory.sockets[2]
        a2.listener.onClosing(a2, 1008, "DATA_EXPIRED")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, reauthCalls.get())
        assertTrue(events.any { it is TerminalEvent.DataExpired && it.tabId == "a" })
    }

    @Test
    fun dataExpired_reauthFalse_keepsDataExpiredBehaviour() = runTest {
        val factory = FakeFactory()
        val transport = newTransport(factory) { false }
        val events = collect(transport)
        transport.connect(sshConfig("a"))
        val a = factory.sockets.single()

        a.listener.onClosing(a, 1008, "DATA_EXPIRED")
        runCurrent()

        assertEquals(1, factory.sockets.size)
        assertEquals(1, events.count { it is TerminalEvent.DataExpired })
        assertTrue(
            events.any { it is TerminalEvent.StateChanged && it.state == TerminalConnectionState.DataExpired },
        )
    }

    @Test
    fun cleanRemoteClose1000_reconnects() = runTest {
        val factory = FakeFactory()
        val transport = newTransport(factory)
        transport.connect(sshConfig("tab", tmux = "ops"))
        val ws = factory.sockets.single()
        ws.listener.onClosed(ws, 1000, "exit")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, factory.sockets.size)
        assertEquals("termix.jwt.ticket-2", factory.sockets.last().request.header("Sec-WebSocket-Protocol"))
    }

    @Test
    fun userDisconnect_closesWith1000_andDoesNotReconnect() = runTest {
        val factory = FakeFactory()
        val transport = newTransport(factory)
        transport.connect(sshConfig("tab"))
        val ws = factory.sockets.single()
        transport.disconnect("tab")
        assertEquals(1000, ws.closedWith?.first)
        // OkHttp still delivers close callbacks for the socket we closed; they must not revive the tab.
        ws.listener.onClosing(ws, 1000, "Client disconnected")
        ws.listener.onClosed(ws, 1000, "Client disconnected")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, factory.sockets.size)
    }
}
