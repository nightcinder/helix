package com.nightcinder.helix.core.stream

import android.content.Context
import android.content.Intent
import android.view.Surface
import com.nightcinder.helix.core.model.AppDiscovery
import com.nightcinder.helix.core.model.ClipboardSource
import com.nightcinder.helix.core.model.ClipboardSyncState
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionState
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.core.model.StreamTelemetry
import com.nightcinder.helix.core.model.WebSocketAudioState
import com.nightcinder.helix.core.model.WebSocketCapabilityStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hardware / software video decoder frame sink that consumes compressed video access units
 * (HEVC, AV1, H.264) and renders directly to an Android [Surface].
 */
interface VideoFrameSink {
    fun configure(
        width: Int,
        height: Int,
        codec: StreamCodec,
        surface: Surface?,
    )

    fun submitAccessUnit(
        data: ByteArray,
        offset: Int,
        length: Int,
        ptsUs: Long,
        flags: Int = 0,
    )

    fun release()

    /** Non-null when the decoder could not be created or failed; null while healthy. */
    val lastError: String?
        get() = null

    /** Registers a callback invoked (from any thread) when the decoder fails asynchronously. */
    fun setErrorListener(listener: ((String) -> Unit)?) {}

    /** Registers a callback invoked when a video frame is dropped so the transport can request an IDR. */
    fun setFrameDropListener(listener: (() -> Unit)?) {}
}

/**
 * Audio sink consuming raw Opus packets from moonlight-web channel `0x02`.
 */
interface WebSocketAudioSink {
    val audioState: StateFlow<WebSocketAudioState>

    fun configure(sampleRate: Int = 48_000, channels: Int = 2)

    fun submitOpusPacket(data: ByteArray, offset: Int, length: Int)

    fun release()
}

/**
 * High-level Moonlight / Sunshine streaming client contract for Project Helix.
 *
 * Supports dual-path operation:
 * - Direct UDP streaming via native bridge (`DIRECT_UDP` on LAN / VPN, `<host>:47989`)
 * - Encrypted WebSocket tunnel fallback (`WEBSOCKET_443` over port 443 via `wss://<moonlight-web>/...`)
 * - Intent builder to launch external native Moonlight / Artemis APKs.
 */
interface MoonlightStreamClient {
    val state: StateFlow<StreamConnectionState>
    val telemetry: StateFlow<StreamTelemetry>
    val capabilityStatus: StateFlow<WebSocketCapabilityStatus>
        get() = MutableStateFlow(WebSocketCapabilityStatus.Unknown)

    suspend fun connect(config: StreamConfig = StreamConfig())

    suspend fun disconnect()

    fun attachSurface(surface: Surface)

    fun detachSurface()

    /**
     * Detaches [surface] only if it is the currently rendering one. The stream is shared across windows,
     * so a window that is not current closing must not stop or blank the stream.
     */
    fun detachSurface(surface: Surface) = detachSurface()

    /** Cancels a pairing that is waiting for the PIN to be entered on the host. No-op otherwise. */
    fun cancelPairing() {}

    /** Forgets the stored pairing with the host (the next Start Stream pairs again). */
    suspend fun forgetPairing() {}

    /** Lists the host's apps / virtual displays for the pre-stream picker. Throws [java.io.IOException] when unreachable. */
    suspend fun discoverApps(config: StreamConfig): AppDiscovery =
        AppDiscovery.Unavailable("App discovery is not supported by this client")

    suspend fun sendInput(event: StreamInputEvent)

    suspend fun updateConfig(config: StreamConfig)

    fun buildExternalMoonlightIntent(context: Context): Intent
}

/**
 * Unified cross-device clipboard bridge synchronizing text between Android local clipboard,
 * Termix SSH terminal sessions (via bracketed paste), and remote host clipboard (stream host).
 */
interface ClipboardBridge {
    val state: StateFlow<ClipboardSyncState>

    fun setAutoSync(enabled: Boolean)

    suspend fun pullFromRemote(): Result<String>

    suspend fun pushToRemote(text: String): Result<Unit>

    suspend fun pushLocalClipboard(): Result<Unit> = Result.success(Unit)

    suspend fun pasteToActiveTerminal(text: String): Result<Unit>

    suspend fun onLocalClipboardChanged(text: String, source: ClipboardSource): Result<Unit>
}
