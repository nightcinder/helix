package com.nightcinder.helix.stream

import android.view.Surface
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamTelemetry
import com.nightcinder.helix.core.model.StreamTransportMode

/** Events reported by moonlight-common-c through the JNI layer. */
sealed class NativeStreamEvent {
    data class StageStarting(val stage: Int, val name: String) : NativeStreamEvent()
    data class StageComplete(val stage: Int, val name: String) : NativeStreamEvent()
    data class StageFailed(val stage: Int, val name: String, val errorCode: Int) : NativeStreamEvent()

    /** The `connectionStarted` callback: the only thing that may turn the UI to "Connected". */
    object ConnectionStarted : NativeStreamEvent()

    /** [errorCode] 0 means the host ended the stream gracefully. */
    data class ConnectionTerminated(val errorCode: Int) : NativeStreamEvent()

    /** Return value of `LiStartConnection` (0 = success). */
    data class StartResult(val result: Int) : NativeStreamEvent()

    data class ConnectionStatus(val poor: Boolean) : NativeStreamEvent()
}

/** Everything `LiStartConnection` needs, taken from serverinfo and the /launch response. */
class NativeStartParams(
    val host: String,
    val appVersion: String,
    val gfeVersion: String,
    val rtspSessionUrl: String,
    val serverCodecModeSupport: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val supportedVideoFormats: Int,
    val riKey: ByteArray,
    val riKeyId: Int,
)

/**
 * JNI wrapper for the native Moonlight engine (`libhelix_moonlight.so`: moonlight-common-c + AMediaCodec
 * video + Opus/AAudio audio). Every call is a safe no-op when the library is not loaded (JVM tests).
 */
open class MoonlightNativeBridge {

    companion object {
        const val LIBRARY_NAME = "helix_moonlight"

        // Mirrors VIDEO_FORMAT_* in Limelight.h.
        const val VIDEO_FORMAT_H264 = 0x0001
        const val VIDEO_FORMAT_H265 = 0x0100
        const val VIDEO_FORMAT_AV1_MAIN8 = 0x1000
        const val VIDEO_FORMAT_MASK_H264 = 0x000F
        const val VIDEO_FORMAT_MASK_H265 = 0x0F00
        const val VIDEO_FORMAT_MASK_AV1 = 0xF000

        @Volatile
        var isLibraryLoaded: Boolean = false
            private set

        init {
            try {
                System.loadLibrary(LIBRARY_NAME)
                isLibraryLoaded = true
            } catch (_: Throwable) {
                isLibraryLoaded = false
            }
        }

        fun codecForVideoFormat(format: Int): StreamCodec? = when {
            format and VIDEO_FORMAT_MASK_AV1 != 0 -> StreamCodec.AV1
            format and VIDEO_FORMAT_MASK_H265 != 0 -> StreamCodec.HEVC
            format and VIDEO_FORMAT_MASK_H264 != 0 -> StreamCodec.H264
            else -> null
        }

        /** Formats offered to the host for the preferred [codec], filtered by what this device can decode. */
        fun supportedVideoFormats(
            codec: StreamCodec,
            canDecode: (StreamCodec) -> Boolean = DecoderSupport::canDecode,
        ): Int {
            var formats = VIDEO_FORMAT_H264
            if (codec != StreamCodec.H264 && canDecode(StreamCodec.HEVC)) formats = formats or VIDEO_FORMAT_H265
            if (codec == StreamCodec.AV1 && canDecode(StreamCodec.AV1)) formats = formats or VIDEO_FORMAT_AV1_MAIN8
            return formats
        }
    }

    @Volatile
    var listener: ((NativeStreamEvent) -> Unit)? = null

    /** Called from native threads by `emitEvent()` in helix_moonlight_jni.cpp. Signature is fixed by JNI. */
    @Suppress("unused")
    fun onNativeEvent(type: Int, a: Int, b: Int, text: String?) {
        val event = when (type) {
            1 -> NativeStreamEvent.StageStarting(a, text.orEmpty())
            2 -> NativeStreamEvent.StageComplete(a, text.orEmpty())
            3 -> NativeStreamEvent.StageFailed(a, text.orEmpty(), b)
            4 -> NativeStreamEvent.ConnectionStarted
            5 -> NativeStreamEvent.ConnectionTerminated(a)
            6 -> NativeStreamEvent.StartResult(a)
            7 -> NativeStreamEvent.ConnectionStatus(poor = a != 0)
            else -> return
        }
        listener?.invoke(event)
    }

    /** Query parameters moonlight-common-c wants appended to /launch and /resume. */
    open fun launchQueryParameters(): String {
        if (!isLibraryLoaded) return ""
        return try {
            nativeLaunchQueryParameters()
        } catch (_: UnsatisfiedLinkError) {
            ""
        }
    }

    /**
     * Starts the native session asynchronously. Returns false only when the library is missing or the
     * request is invalid; success of the connection itself arrives as [NativeStreamEvent.ConnectionStarted].
     */
    open fun start(params: NativeStartParams, surface: Surface?): Boolean {
        if (!isLibraryLoaded) return false
        return try {
            nativeStart(
                params.host, params.appVersion, params.gfeVersion, params.rtspSessionUrl,
                params.serverCodecModeSupport,
                params.width, params.height, params.fps, params.bitrateKbps,
                params.supportedVideoFormats,
                params.riKey, params.riKeyId,
                surface,
            )
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Swaps the render surface of a running session; null detaches (video frames are dropped). */
    open fun setSurface(surface: Surface?) {
        if (!isLibraryLoaded) return
        try {
            nativeSetSurface(surface)
        } catch (_: UnsatisfiedLinkError) {
            // Ignored in stub/test mode
        }
    }

    /** Blocking (joins native threads, LiStopConnection): call off the main thread. Idempotent. */
    open fun stop() {
        listener = null
        if (!isLibraryLoaded) return
        try {
            nativeStop()
        } catch (_: UnsatisfiedLinkError) {
            // Ignored in stub/test mode
        }
    }

    open fun sendMouseMove(deltaX: Short, deltaY: Short) = guarded { nativeSendMouseMove(deltaX, deltaY) }

    open fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) =
        guarded { nativeSendMousePosition(x, y, referenceWidth, referenceHeight) }

    open fun sendMouseButton(button: Byte, down: Boolean) = guarded { nativeSendMouseButton(button, down) }

    open fun sendKeyboard(keyCode: Short, keyAction: Byte, modifiers: Byte) =
        guarded { nativeSendKeyboard(keyCode, keyAction, modifiers) }

    open fun sendScroll(scrollAmount: Byte) = guarded { nativeSendScroll(scrollAmount) }

    open fun sendHighResScroll(amount: Short) = guarded { nativeSendHighResScroll(amount) }

    open fun sendHighResHScroll(amount: Short) = guarded { nativeSendHighResHScroll(amount) }

    private inline fun guarded(block: () -> Unit) {
        if (!isLibraryLoaded) return
        try {
            block()
        } catch (_: UnsatisfiedLinkError) {
            // Ignored
        }
    }

    /**
     * Returns measured native telemetry, or null when the library is missing or no connection is active.
     * Never fabricates values.
     */
    open fun getStats(): StreamTelemetry? {
        if (!isLibraryLoaded) return null
        val v = try {
            nativeGetStats()
        } catch (_: UnsatisfiedLinkError) {
            null
        } ?: return null
        if (v.size < 7) return null
        return StreamTelemetry(
            fps = v[0],
            bitrateKbps = v[1].toInt(),
            rttMs = v[2].toInt(),
            droppedFrames = v[3].toLong(),
            activeTransport = StreamTransportMode.DIRECT_UDP,
            activeCodec = codecForVideoFormat(v[6].toInt()) ?: StreamCodec.HEVC,
            width = v[4].toInt(),
            height = v[5].toInt(),
        )
    }

    // --- JNI ---

    private external fun nativeLaunchQueryParameters(): String

    private external fun nativeStart(
        host: String,
        appVersion: String,
        gfeVersion: String,
        rtspSessionUrl: String,
        serverCodecModeSupport: Int,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        supportedVideoFormats: Int,
        riKey: ByteArray,
        riKeyId: Int,
        surface: Surface?,
    ): Boolean

    private external fun nativeSetSurface(surface: Surface?)

    private external fun nativeStop()

    private external fun nativeSendMouseMove(deltaX: Short, deltaY: Short)

    private external fun nativeSendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short)

    private external fun nativeSendMouseButton(button: Byte, down: Boolean)

    private external fun nativeSendKeyboard(keyCode: Short, keyAction: Byte, modifiers: Byte)

    private external fun nativeSendScroll(scrollAmount: Byte)

    private external fun nativeSendHighResScroll(amount: Short)

    private external fun nativeSendHighResHScroll(amount: Short)

    private external fun nativeGetStats(): DoubleArray?
}

/** Whether this device has a decoder for [StreamCodec]; optimistic when the platform query fails. */
object DecoderSupport {
    fun canDecode(codec: StreamCodec): Boolean {
        if (codec == StreamCodec.H264) return true
        return try {
            val infos = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            infos.any { info -> !info.isEncoder && info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) } }
        } catch (_: Throwable) {
            codec != StreamCodec.AV1
        }
    }
}
