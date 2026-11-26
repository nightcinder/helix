package com.nightcinder.helix.stream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.nightcinder.helix.core.model.WebSocketAudioState
import com.nightcinder.helix.core.stream.WebSocketAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes Opus packets from moonlight-web channel `0x02` using Android [MediaCodec] + [AudioTrack].
 * On JVM unit tests (`SDK_INT == 0`) or devices without Opus support, isolates packets safely and
 * surfaces [WebSocketAudioState.Muted] without crashing the video stream.
 */
class OpusAudioSink : WebSocketAudioSink {

    companion object {
        private const val TAG = "OpusAudioSink"
        private const val MIME_OPUS = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val DEQUEUE_TIMEOUT_US = 0L
    }

    private val _audioState = MutableStateFlow<WebSocketAudioState>(WebSocketAudioState.Inactive)
    override val audioState: StateFlow<WebSocketAudioState> = _audioState.asStateFlow()

    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var isConfigured = false

    private fun isJvmStub(): Boolean = Build.VERSION.SDK_INT == 0

    @Synchronized
    override fun configure(sampleRate: Int, channels: Int) {
        releaseInternal()
        if (isJvmStub()) {
            isConfigured = true
            _audioState.value = WebSocketAudioState.Playing
            return
        }

        try {
            val format = MediaFormat.createAudioFormat(MIME_OPUS, sampleRate, channels).apply {
                setByteBuffer("csd-0", buildOpusHead(sampleRate, channels))
                setByteBuffer("csd-1", buildLongCsd(6_500_000L))
                setByteBuffer("csd-2", buildLongCsd(80_000_000L))
            }
            val decoder = MediaCodec.createDecoderByType(MIME_OPUS)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val channelConfig = if (channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(sampleRate / 10 * channels * 2)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            codec = decoder
            audioTrack = track
            isConfigured = true
            _audioState.value = WebSocketAudioState.Playing
        } catch (t: Throwable) {
            Log.w(TAG, "Opus audio init failed; muting WS audio: ${t.message}")
            releaseInternal()
            _audioState.value = WebSocketAudioState.Muted("Audio unavailable: ${t.message ?: "init failed"}")
        }
    }

    @Synchronized
    override fun submitOpusPacket(data: ByteArray, offset: Int, length: Int) {
        if (!isConfigured || length <= 0) return
        val decoder = codec ?: return
        val track = audioTrack ?: return

        try {
            val inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIdx >= 0) {
                val buf = decoder.getInputBuffer(inIdx)
                if (buf != null && buf.capacity() >= length) {
                    buf.clear()
                    buf.put(data, offset, length)
                    decoder.queueInputBuffer(inIdx, 0, length, System.nanoTime() / 1000L, 0)
                } else {
                    decoder.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                }
            }

            val info = MediaCodec.BufferInfo()
            var outIdx = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            while (outIdx >= 0) {
                val outBuf = decoder.getOutputBuffer(outIdx)
                if (outBuf != null && info.size > 0) {
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm, 0, info.size)
                    track.write(pcm, 0, pcm.size)
                }
                decoder.releaseOutputBuffer(outIdx, false)
                outIdx = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Opus decode failed; muting WS audio: ${t.message}")
            releaseInternal()
            _audioState.value = WebSocketAudioState.Muted("Audio decode error")
        }
    }

    @Synchronized
    override fun release() {
        releaseInternal()
        _audioState.value = WebSocketAudioState.Inactive
    }

    private fun releaseInternal() {
        isConfigured = false
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        audioTrack?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioTrack = null
    }

    private fun buildOpusHead(sampleRate: Int, channels: Int): ByteBuffer =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1.toByte()) // version
            put(channels.toByte())
            putShort(0.toShort()) // pre-skip
            putInt(sampleRate)
            putShort(0.toShort()) // output gain
            put(0.toByte()) // channel mapping family
            flip()
        }

    private fun buildLongCsd(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply {
            putLong(value)
            flip()
        }
}
