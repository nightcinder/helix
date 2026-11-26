package com.nightcinder.helix.core.terminal

import com.nightcinder.helix.core.model.TerminalConnectionState
import com.nightcinder.helix.core.model.TerminalSessionConfig
import kotlinx.coroutines.flow.Flow

sealed class TerminalEvent {
    abstract val tabId: String

    data class Output(
        override val tabId: String,
        val data: String,
    ) : TerminalEvent()

    data class StateChanged(
        override val tabId: String,
        val state: TerminalConnectionState,
        val sessionId: String? = null,
        val message: String? = null,
    ) : TerminalEvent()

    data class Error(
        override val tabId: String,
        val message: String,
        val cause: Throwable? = null,
    ) : TerminalEvent()

    data class DataExpired(
        override val tabId: String,
        val closeCode: Int = 1008,
        val reason: String = "DATA_EXPIRED",
    ) : TerminalEvent()
}

/**
 * Multiplexed WebSocket transport contract for Termix SSH terminals (`/plugin-ws/ssh-terminal/terminal`)
 * and Docker container interactive consoles (`/plugin-ws/docker/console`).
 *
 * Implementations must authenticate each WebSocket handshake using a one-time ticket from
 * `POST /users/ws-ticket` passed via `Sec-WebSocket-Protocol: termix.jwt.<ticket>`.
 */
interface TerminalTransport {
    val events: Flow<TerminalEvent>

    suspend fun connect(config: TerminalSessionConfig)

    suspend fun sendInput(tabId: String, data: String)

    suspend fun resize(tabId: String, cols: Int, rows: Int)

    suspend fun attachTmux(tabId: String, session: String)

    suspend fun detachTmux(tabId: String)

    suspend fun disconnect(tabId: String)

    suspend fun disconnectAll() {}
}
