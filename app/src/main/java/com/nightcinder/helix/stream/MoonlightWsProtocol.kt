package com.nightcinder.helix.stream

import android.media.MediaCodec
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamInputEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire-level serialization and demux helpers for `moonlight-web-stream:v3.0.2`
 * (`ServerVersion(7,0,0,0), encrypted = true`).
 */
internal object MoonlightWsProtocol {

    const val CHANNEL_CONTROL: Byte = 0x00
    const val CHANNEL_VIDEO: Byte = 0x01
    const val CHANNEL_AUDIO: Byte = 0x02

    const val VIDEO_HEADER_SIZE = 6 // 1 byte channel (0x01) + 1 byte frameType + 4 bytes ptsUs (u32 BE)

    const val CONTROL_TYPE_INPUT_DATA = 0x0206
    const val CONTROL_TYPE_REQUEST_IDR = 0x0302
    const val CONTROL_TYPE_SERVER_TERMINATION = 0x0109

    const val TERMINATION_GRACEFUL = 0x80030023L

    const val INPUT_MAGIC_KEY_DOWN = 0x00000003
    const val INPUT_MAGIC_KEY_UP = 0x00000004
    const val INPUT_MAGIC_MOUSE_POSITION = 0x00000005
    const val INPUT_MAGIC_MOUSE_MOVE = 0x00000007
    const val INPUT_MAGIC_MOUSE_BUTTON_DOWN = 0x00000008
    const val INPUT_MAGIC_MOUSE_BUTTON_UP = 0x00000009
    const val INPUT_MAGIC_SCROLL = 0x0000000A
    const val INPUT_MAGIC_HSCROLL = 0x55000001

    fun codecBitmask(codec: StreamCodec): Int = when (codec) {
        StreamCodec.H264 -> MoonlightNativeBridge.VIDEO_FORMAT_H264
        StreamCodec.HEVC -> MoonlightNativeBridge.VIDEO_FORMAT_H265
        StreamCodec.AV1 -> MoonlightNativeBridge.VIDEO_FORMAT_AV1_MAIN8
    }

    fun buildRequestHandshake(
        config: StreamConfig,
        hostId: Int,
        appId: Int,
        supportedCodecs: Int = MoonlightNativeBridge.supportedVideoFormats(config.codec),
        json: Json,
    ): String {
        val obj = buildJsonObject {
            putJsonObject("Request") {
                put("host_id", hostId)
                put("app_id", appId)
                put("width", config.width)
                put("height", config.height)
                put("fps", config.fps)
                put("bitrate", config.bitrateKbps)
                put("hdr", false)
                put("local_audio_play_mode", 0)
                put("supported_codecs", supportedCodecs)
                put("preferred_codecs", codecBitmask(config.codec))
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** `00` + `02 03` (`0x0302` LE) + `02 00` (`len = 2` LE) + `00 00`. */
    fun encodeRequestIdr(): ByteArray = byteArrayOf(
        CHANNEL_CONTROL,
        0x02, 0x03,
        0x02, 0x00,
        0x00, 0x00,
    )

    /**
     * Serializes [event] into the `0x00` `InputData` (`ty = 0x0206` LE) mixed-endian binary envelope.
     */
    fun encodeInputPacket(event: StreamInputEvent): ByteArray {
        val inputLen: Int
        val magic: Int
        val payloadWriter: (ByteBuffer) -> Unit

        when (event) {
            is StreamInputEvent.MouseMove -> {
                inputLen = 8
                magic = INPUT_MAGIC_MOUSE_MOVE
                payloadWriter = { buf ->
                    buf.order(ByteOrder.BIG_ENDIAN)
                    buf.putShort(event.deltaX)
                    buf.putShort(event.deltaY)
                }
            }
            is StreamInputEvent.MousePosition -> {
                inputLen = 14
                magic = INPUT_MAGIC_MOUSE_POSITION
                payloadWriter = { buf ->
                    buf.order(ByteOrder.BIG_ENDIAN)
                    buf.putShort(event.x)
                    buf.putShort(event.y)
                    buf.putShort(0)
                    buf.putShort(event.referenceWidth)
                    buf.putShort(event.referenceHeight)
                }
            }
            is StreamInputEvent.MouseButton -> {
                inputLen = 5
                magic = if (event.down) INPUT_MAGIC_MOUSE_BUTTON_DOWN else INPUT_MAGIC_MOUSE_BUTTON_UP
                payloadWriter = { buf ->
                    buf.put(event.button)
                }
            }
            is StreamInputEvent.Key -> {
                inputLen = 10
                magic = if (event.keyAction == StreamInputEvent.Key.ACTION_UP) {
                    INPUT_MAGIC_KEY_UP
                } else {
                    INPUT_MAGIC_KEY_DOWN
                }
                payloadWriter = { buf ->
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    buf.put(0) // flags
                    buf.putShort(event.keyCode)
                    buf.put(event.modifiers)
                    buf.putShort(0)
                }
            }
            is StreamInputEvent.Scroll -> {
                inputLen = 10
                magic = INPUT_MAGIC_SCROLL
                val amt = (event.scrollAmount.toInt() * 120)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                payloadWriter = { buf ->
                    buf.order(ByteOrder.BIG_ENDIAN)
                    buf.putShort(amt)
                    buf.putShort(amt)
                    buf.putShort(0)
                }
            }
            is StreamInputEvent.HighResScroll -> {
                inputLen = 10
                magic = INPUT_MAGIC_SCROLL
                payloadWriter = { buf ->
                    buf.order(ByteOrder.BIG_ENDIAN)
                    buf.putShort(event.amount)
                    buf.putShort(event.amount)
                    buf.putShort(0)
                }
            }
            is StreamInputEvent.HighResHScroll -> {
                inputLen = 6
                magic = INPUT_MAGIC_HSCROLL
                payloadWriter = { buf ->
                    buf.order(ByteOrder.BIG_ENDIAN)
                    buf.putShort(event.amount)
                }
            }
        }

        val packetLen = 4 + inputLen
        val buf = ByteBuffer.allocate(1 + 2 + 2 + packetLen)
        buf.put(CHANNEL_CONTROL)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(CONTROL_TYPE_INPUT_DATA.toShort())
        buf.putShort(packetLen.toShort())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(inputLen)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(magic)
        payloadWriter(buf)
        return buf.array()
    }

    data class VideoHeader(
        val isKeyFrame: Boolean,
        val ptsUs: Long,
        val payloadOffset: Int,
        val payloadLength: Int,
    ) {
        val mediaCodecFlags: Int
            get() = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
    }

    fun parseVideoFrame(data: ByteArray): VideoHeader? {
        if (data.size <= VIDEO_HEADER_SIZE || data[0] != CHANNEL_VIDEO) return null
        val frameType = data[1].toInt() and 0xFF
        val ptsUs = ((data[2].toLong() and 0xFFL) shl 24) or
            ((data[3].toLong() and 0xFFL) shl 16) or
            ((data[4].toLong() and 0xFFL) shl 8) or
            (data[5].toLong() and 0xFFL)
        return VideoHeader(
            isKeyFrame = frameType != 0,
            ptsUs = ptsUs,
            payloadOffset = VIDEO_HEADER_SIZE,
            payloadLength = data.size - VIDEO_HEADER_SIZE,
        )
    }

    sealed interface ControlPacket {
        data class ServerTermination(val code: Long, val isGraceful: Boolean) : ControlPacket
        data class Unknown(val type: Int) : ControlPacket
    }

    fun parseControlPacket(data: ByteArray): ControlPacket? {
        if (data.size < 5 || data[0] != CHANNEL_CONTROL) return null
        val type = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val len = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
        if (type != CONTROL_TYPE_SERVER_TERMINATION) {
            return ControlPacket.Unknown(type)
        }
        val available = (data.size - 5).coerceAtMost(len)
        val code: Long = when {
            available >= 4 -> {
                ((data[5].toLong() and 0xFFL) shl 24) or
                    ((data[6].toLong() and 0xFFL) shl 16) or
                    ((data[7].toLong() and 0xFFL) shl 8) or
                    (data[8].toLong() and 0xFFL)
            }
            available >= 2 -> {
                ((data[5].toLong() and 0xFFL) shl 8) or
                    (data[6].toLong() and 0xFFL)
            }
            else -> 0L
        }
        val graceful = code == 0L || code == TERMINATION_GRACEFUL
        return ControlPacket.ServerTermination(code = code, isGraceful = graceful)
    }
}
