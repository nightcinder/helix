package com.nightcinder.helix.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed class TerminalTarget {
    abstract val hostId: Long
    abstract val hostName: String

    val defaultTitle: String
        get() = when (this) {
            is SshHost -> {
                val base = hostName.ifBlank { "SSH #$hostId" }
                tmuxSession?.takeIf { it.isNotBlank() }?.let { "$base [$it]" } ?: base
            }
            is DockerConsole -> {
                val containerLabel = containerName.ifBlank { containerId.take(12) }
                val hostLabel = hostName.ifBlank { "host-$hostId" }
                "$containerLabel@$hostLabel"
            }
        }

    @Serializable
    data class SshHost(
        override val hostId: Long,
        override val hostName: String = "",
        val initialPath: String? = null,
        val executeCommand: String? = null,
        val tmuxSession: String? = null,
    ) : TerminalTarget()

    @Serializable
    data class DockerConsole(
        override val hostId: Long,
        override val hostName: String = "",
        val containerId: String,
        val containerName: String = "",
        val shell: String = "/bin/sh",
    ) : TerminalTarget()
}

@Serializable
data class TerminalSize(
    val cols: Int = DEFAULT_COLS,
    val rows: Int = DEFAULT_ROWS,
) {
    companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}

@Serializable
data class TerminalSessionConfig(
    val tabId: String,
    val target: TerminalTarget,
    val size: TerminalSize = TerminalSize(),
    val hostIp: String? = null,
    val hostPort: Int = 22,
    val hostUsername: String? = null,
)

@Serializable
enum class TerminalConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    DataExpired,
    Error,
}

@Serializable
data class TerminalTabState(
    val id: String,
    val title: String,
    val target: TerminalTarget,
    val state: TerminalConnectionState = TerminalConnectionState.Disconnected,
    val size: TerminalSize = TerminalSize(),
    val sessionId: String? = null,
    val errorMessage: String? = null,
)
