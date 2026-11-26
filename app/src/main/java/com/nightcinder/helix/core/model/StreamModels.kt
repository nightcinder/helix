package com.nightcinder.helix.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class StreamTransportMode(val displayName: String) {
    AUTO("Auto (Direct / 443 Fallback)"),
    DIRECT_UDP("Direct UDP (LAN / WireGuard)"),
    WEBSOCKET_443("WebSocket Tunnel (TCP:443)"),
    EXTERNAL_NATIVE("External Moonlight / Artemis"),
}

@Serializable
enum class StreamCodec(val mimeType: String, val wireName: String) {
    HEVC("video/hevc", "HEVC"),
    AV1("video/av01", "AV1"),
    H264("video/avc", "H264"),
}

@Serializable
enum class StreamResolution(
    val width: Int,
    val height: Int,
    val label: String,
    val recommendedBitrateKbps: Int = 20000,
) {
    WUXGA_1200P(1920, 1200, "1200p (16:10)", 20000),
    WQXGA_1600P(2560, 1600, "1600p (16:10)", 30000),
    ULTRAWIDE_1200P(2560, 1200, "2560x1200 (21:10)", 30000),
    UWQHD_1440P(3440, 1440, "3440x1440 (21:9)", 40000),
    SUPER_ULTRAWIDE_1200P(3840, 1200, "3840x1200 (32:10)", 40000),
    HD_1080P(1920, 1080, "1080p (16:9)", 20000),
    QHD_1440P(2560, 1440, "1440p (16:9)", 30000),
    UHD_4K(3840, 2160, "4K (16:9)", 50000);

    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()

    fun spatialPanelWidthDp(
        videoHeightDp: Int = 520,
        chromeHorizontalDp: Int = 24,
        minWidthDp: Int = 900,
        maxWidthDp: Int = 1680,
    ): Int = ((videoHeightDp * aspectRatio).toInt() + chromeHorizontalDp).coerceIn(minWidthDp, maxWidthDp)

    fun spatialWingOffsetXDp(
        centerWidthDp: Int = spatialPanelWidthDp(),
        wingWidthDp: Int = 640,
        gapDp: Int = 40,
    ): Int = (centerWidthDp / 2) + (wingWidthDp / 2) + gapDp
}

@Serializable
data class StreamConfig(
    val hostIp: String = DEFAULT_HOST_IP,
    val port: Int = DEFAULT_PORT,
    val wsUrl: String = DEFAULT_WS_URL,
    val fps: Int = DEFAULT_FPS,
    val bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
    val codec: StreamCodec = StreamCodec.HEVC,
    val resolution: StreamResolution = StreamResolution.WUXGA_1200P,
    val transportMode: StreamTransportMode = StreamTransportMode.AUTO,
    /** Host app id picked from `/applist`; null means "resolve [appTitle] on the host". */
    val appId: Int? = null,
    val appTitle: String = DEFAULT_APP_TITLE,
    /** When another app is running on the host: stop it (`/cancel`) before launching [appId]. */
    val replaceRunningApp: Boolean = false,
) {
    val width: Int get() = resolution.width
    val height: Int get() = resolution.height

    companion object {
        const val DEFAULT_APP_TITLE = "Desktop"
        /** Build-time default stream host (HELIX_STREAM_HOST); blank until a Termix host is picked. */
        const val DEFAULT_HOST_IP = SiteConfig.STREAM_HOST
        const val DEFAULT_PORT = 47989
        const val WS_STREAM_PATH = "/api/host/stream/web_socket"
        val DEFAULT_WS_URL: String = wsUrlFor(SiteConfig.MOONLIGHT_URL)

        /** moonlight-web stream WebSocket URL for a Moonlight base URL (`https://host` -> `wss://host/...`); blank in, blank out. */
        fun wsUrlFor(moonlightBaseUrl: String): String {
            val base = moonlightBaseUrl.trim().trimEnd('/')
            if (base.isEmpty()) return ""
            val ws = base
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            return ws + WS_STREAM_PATH
        }
        const val DEFAULT_FPS = 60
        const val DEFAULT_BITRATE_KBPS = 20000
    }
}

@Serializable
enum class StreamConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    DataExpired,
    Error,

    /** Native direct path: the host has no pairing for this client yet (or pairing was cancelled). */
    NotPaired,

    /** Native direct path: waiting for the owner to type [StreamConnectionState.pairingPin] on the host. */
    Pairing,
}

@Serializable
data class StreamConnectionState(
    val status: StreamConnectionStatus = StreamConnectionStatus.Disconnected,
    val activeTransport: StreamTransportMode = StreamTransportMode.AUTO,
    val targetHostIp: String = StreamConfig.DEFAULT_HOST_IP,
    val errorMessage: String? = null,
    val connectedEpochMs: Long? = null,
    /** True only while an auth refresh (DATA_EXPIRED recovery) is actually running. */
    val isReauthenticating: Boolean = false,
    /** 4-digit PIN to enter on the host while [status] is [StreamConnectionStatus.Pairing]. */
    val pairingPin: String? = null,
    /** Human-readable connect stage while [status] is [StreamConnectionStatus.Connecting] (native path). */
    val stage: String? = null,
)

@Serializable
sealed interface WebSocketCapabilityStatus {
    @Serializable
    data object Unknown : WebSocketCapabilityStatus

    @Serializable
    data object Checking : WebSocketCapabilityStatus

    @Serializable
    data class Available(val hostName: String? = null, val appCount: Int = 0) : WebSocketCapabilityStatus

    @Serializable
    data object AuthRequired : WebSocketCapabilityStatus

    @Serializable
    data class Unavailable(val reason: String) : WebSocketCapabilityStatus
}

@Serializable
sealed interface WebSocketAudioState {
    @Serializable
    data object Inactive : WebSocketAudioState

    @Serializable
    data object Playing : WebSocketAudioState

    @Serializable
    data class Muted(val reason: String) : WebSocketAudioState
}

/**
 * Measured stream telemetry. All numeric fields default to zero meaning "no data yet";
 * never populate them with configured (requested) values.
 */
@Serializable
data class StreamTelemetry(
    val fps: Double = 0.0,
    val bitrateKbps: Int = 0,
    val rttMs: Int = 0,
    val droppedFrames: Long = 0L,
    val activeTransport: StreamTransportMode = StreamTransportMode.AUTO,
    val activeCodec: StreamCodec = StreamCodec.HEVC,
    val width: Int = 0,
    val height: Int = 0,
    val audioState: WebSocketAudioState = WebSocketAudioState.Inactive,
) {
    val hasData: Boolean
        get() = fps > 0.0 || bitrateKbps > 0 || rttMs > 0
}

@Serializable
sealed class StreamInputEvent {
    @Serializable
    data class MouseMove(val deltaX: Short, val deltaY: Short) : StreamInputEvent()

    @Serializable
    data class MouseButton(val button: Byte, val down: Boolean) : StreamInputEvent() {
        companion object {
            const val BUTTON_LEFT: Byte = 1
            const val BUTTON_MIDDLE: Byte = 2
            const val BUTTON_RIGHT: Byte = 3
            const val BUTTON_X1: Byte = 4
            const val BUTTON_X2: Byte = 5
        }
    }

    @Serializable
    data class Scroll(val scrollAmount: Byte) : StreamInputEvent()

    /** Absolute pointer position (touch). [x]/[y] are within a [referenceWidth] x [referenceHeight] box. */
    @Serializable
    data class MousePosition(
        val x: Short,
        val y: Short,
        val referenceWidth: Short,
        val referenceHeight: Short,
    ) : StreamInputEvent()

    /** Smooth vertical scroll; 120 units equal one wheel notch (positive = up). */
    @Serializable
    data class HighResScroll(val amount: Short) : StreamInputEvent()

    /** Smooth horizontal scroll; 120 units equal one wheel notch (positive = right). */
    @Serializable
    data class HighResHScroll(val amount: Short) : StreamInputEvent()

    @Serializable
    data class Key(
        val keyCode: Short,
        val keyAction: Byte,
        val modifiers: Byte = 0,
    ) : StreamInputEvent() {
        companion object {
            const val ACTION_DOWN: Byte = 3
            const val ACTION_UP: Byte = 4
        }
    }
}

@Serializable
enum class ClipboardSource {
    ANDROID,
    SSH_TERMINAL,
    HOST_REMOTE,
}

@Serializable
data class ClipboardSyncState(
    val autoSyncEnabled: Boolean = true,
    val lastSyncedText: String? = null,
    val lastSyncedHash: Int = 0,
    val lastSource: ClipboardSource? = null,
    val lastSyncEpochMs: Long = 0L,
    val isSyncing: Boolean = false,
    val lastError: String? = null,
)

/** One launchable entry of the host's `/applist` (Vibepollo apps and virtual displays). */
data class HostApp(val id: Int, val title: String, val isHdrSupported: Boolean = false)

/** Virtual-display readiness advertised by the host's `/serverinfo`. */
data class VirtualDisplayStatus(
    val capable: Boolean = false,
    val driverReady: Boolean = false,
    val hdrCapable: Boolean = false,
)

/** What the host reported for a paired client: its apps and the app currently running (0 = none). */
data class HostAppCatalog(
    val apps: List<HostApp>,
    val currentGame: Int,
    val virtualDisplay: VirtualDisplayStatus = VirtualDisplayStatus(),
) {
    val runningApp: HostApp? get() = apps.firstOrNull { it.id == currentGame }
}

/** Result of asking a host for its apps. Failures are thrown, never turned into placeholder apps. */
sealed interface AppDiscovery {
    /** This client is not paired with the host, so the mTLS `/applist` was not requested. */
    data object Unpaired : AppDiscovery

    data class Loaded(val catalog: HostAppCatalog) : AppDiscovery

    data class Unavailable(val reason: String) : AppDiscovery
}
