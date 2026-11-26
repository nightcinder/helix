package com.nightcinder.helix.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.stream.VideoFrameSink
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android [MediaCodec] hardware/software video decoder implementing [VideoFrameSink].
 *
 * Runs the codec in asynchronous mode on a dedicated [HandlerThread]: output buffers are released
 * (and rendered to the attached [Surface]) the moment they become available, and input is fed from
 * a small bounded queue so the caller's thread (e.g. the OkHttp reader) never blocks on the codec.
 *
 * Configured with [MediaFormat.KEY_LOW_LATENCY] = 1 (API 30+) for zero-lag rendering
 * directly to the attached [Surface].
 */
class MediaCodecVideoDecoder : VideoFrameSink {

    companion object {
        private const val TAG = "MediaCodecVideoDecoder"
        private const val MAX_PENDING_UNITS = 8
    }

    private class PendingUnit(val data: ByteArray, val ptsUs: Long, val flags: Int)

    @Volatile
    private var codecInstance: MediaCodec? = null
    private var callbackThread: HandlerThread? = null
    private val isConfigured = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Input side: access units waiting for a free codec input buffer + the free buffer indices.
    private val queueLock = Any()
    private val pending = ArrayDeque<PendingUnit>()
    private val freeInputs = ArrayDeque<Int>()

    val renderedFrames = AtomicLong(0L)
    val droppedFrames = AtomicLong(0L)
    val submittedUnits = AtomicLong(0L)

    @Volatile
    private var failure: String? = null

    @Volatile
    private var errorListener: ((String) -> Unit)? = null

    @Volatile
    private var frameDropListener: (() -> Unit)? = null

    override val lastError: String?
        get() = failure

    override fun setErrorListener(listener: ((String) -> Unit)?) {
        errorListener = listener
    }

    override fun setFrameDropListener(listener: (() -> Unit)?) {
        frameDropListener = listener
    }

    private var currentWidth: Int = 1920
    private var currentHeight: Int = 1080
    private var currentCodec: StreamCodec = StreamCodec.HEVC

    @Volatile
    private var attachedSurface: Surface? = null

    // android.jar stubs on the JVM report SDK_INT == 0: keep the accounting-only fallback for unit tests.
    private fun isJvmStub(): Boolean = Build.VERSION.SDK_INT == 0

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== codecInstance) return
            synchronized(queueLock) { freeInputs.addLast(index) }
            pumpInput(codec)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                // Render immediately; frames are never held back waiting for the next input.
                codec.releaseOutputBuffer(index, attachedSurface != null)
                renderedFrames.incrementAndGet()
            } catch (t: Throwable) {
                Log.w(TAG, "releaseOutputBuffer failed: ${t.message}")
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (codec !== codecInstance) return
            fail("Video decoder error: ${e.message}")
        }
    }

    @Synchronized
    override fun configure(
        width: Int,
        height: Int,
        codec: StreamCodec,
        surface: Surface?,
    ) {
        if (isConfigured.get() && failure == null &&
            width == currentWidth && height == currentHeight && codec == currentCodec
        ) {
            if (surface === attachedSurface) return
            val decoder = codecInstance
            if (decoder == null) {
                // JVM fallback: nothing to rewire.
                attachedSurface = surface
                return
            }
            if (surface == null) {
                // Surface gone: keep the codec alive, just stop rendering until a new one arrives.
                attachedSurface = null
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    decoder.setOutputSurface(surface)
                    attachedSurface = surface
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "setOutputSurface failed, recreating decoder: ${t.message}")
                }
            }
        }

        releaseInternal()

        currentWidth = width
        currentHeight = height
        currentCodec = codec
        attachedSurface = surface
        failure = null

        var thread: HandlerThread? = null
        try {
            val format = MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
                // Key low latency for real-time 60fps game/desktop streaming (API 30+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            thread = HandlerThread("helix-video-decoder").also { it.start() }
            val decoder = MediaCodec.createDecoderByType(codec.mimeType)
            decoder.setCallback(codecCallback, Handler(thread.looper))
            decoder.configure(format, surface, null, 0)
            codecInstance = decoder
            callbackThread = thread
            decoder.start()

            isConfigured.set(true)
            isRunning.set(true)
        } catch (t: Throwable) {
            runCatching { thread?.quitSafely() }
            codecInstance?.let { runCatching { it.release() } }
            codecInstance = null
            callbackThread = null
            isConfigured.set(true)
            if (isJvmStub()) {
                // In JVM unit tests there is no MediaCodec: operate in accounting-only fallback.
                Log.w(TAG, "MediaCodec unavailable on JVM, using test fallback: ${t.message}")
                isRunning.set(true)
            } else {
                // On a device this is a real failure: surface it instead of a silent black screen.
                Log.e(TAG, "MediaCodec initialization failed: ${t.message}", t)
                isRunning.set(false)
                fail("Video decoder unavailable for ${codec.name}: ${t.message}")
            }
        }
    }

    override fun submitAccessUnit(
        data: ByteArray,
        offset: Int,
        length: Int,
        ptsUs: Long,
        flags: Int,
    ) {
        if (!isRunning.get()) return
        submittedUnits.incrementAndGet()

        val decoder = codecInstance
        if (decoder == null) {
            // Fallback accounting for tests / mock environments
            renderedFrames.incrementAndGet()
            return
        }
        if (length <= 0) return

        val unit = PendingUnit(data.copyOfRange(offset, offset + length), ptsUs, flags)
        synchronized(queueLock) {
            if (pending.size >= MAX_PENDING_UNITS) {
                // Drop the oldest non-keyframe; fall back to the oldest unit if all are keyframes/config.
                val keep = MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                val victim = pending.indexOfFirst { it.flags and keep == 0 }
                pending.removeAt(if (victim >= 0) victim else 0)
                droppedFrames.incrementAndGet()
                frameDropListener?.invoke()
            }
            pending.addLast(unit)
        }
        pumpInput(decoder)
    }

    /** Moves queued access units into free codec input buffers. Never blocks on the codec. */
    private fun pumpInput(decoder: MediaCodec) {
        synchronized(queueLock) {
            while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
                val unit = pending.removeFirst()
                val index = freeInputs.removeFirst()
                try {
                    val buffer = decoder.getInputBuffer(index)
                    if (buffer == null || unit.data.size > buffer.capacity()) {
                        freeInputs.addFirst(index)
                        droppedFrames.incrementAndGet()
                        continue
                    }
                    buffer.clear()
                    buffer.put(unit.data)
                    decoder.queueInputBuffer(index, 0, unit.data.size, unit.ptsUs, unit.flags)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error submitting access unit to MediaCodec: ${t.message}", t)
                    droppedFrames.incrementAndGet()
                }
            }
        }
    }

    private fun fail(message: String) {
        failure = message
        isRunning.set(false)
        errorListener?.invoke(message)
    }

    @Synchronized
    override fun release() {
        releaseInternal()
        failure = null
    }

    private fun releaseInternal() {
        isRunning.set(false)
        isConfigured.set(false)
        val decoder = codecInstance
        codecInstance = null
        synchronized(queueLock) {
            pending.clear()
            freeInputs.clear()
        }
        decoder?.let {
            try {
                it.stop()
            } catch (_: Throwable) {}
            try {
                it.release()
            } catch (_: Throwable) {}
        }
        callbackThread?.let { runCatching { it.quitSafely() } }
        callbackThread = null
    }
}
