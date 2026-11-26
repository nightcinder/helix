package com.nightcinder.helix.core.spatial

import kotlinx.serialization.Serializable

@Serializable
enum class SpatialMode {
    HOME_SPACE,
    FULL_SPACE,
}

@Serializable
enum class SpatialPanelId {
    CENTER_DESKTOP,
    LEFT_TERMINAL,
    RIGHT_WORKSPACE,
    FLOATING_MEDIA,
}

@Serializable
data class SpatialPanelConfig(
    val id: SpatialPanelId,
    val title: String,
    val visible: Boolean = true,
    val widthDp: Int,
    val heightDp: Int,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    val offsetZDp: Float = 0f,
    val yawDegrees: Float = 0f,
)

/**
 * Hardware endpoint and interface specifications for USB-attached URXR glasses.
 */
object UrxrUsbBulkSpec {
    const val VID = 0x371A
    const val PID = 0x0101
    const val DEV_VID = 0x2207
    const val DEV_PID = 0x0006

    const val INTERFACE_INDEX = 2
    const val EP_CMD_OUT = 0x01
    const val EP_CMD_IN = 0x81
    const val EP_IMU_IN = 0x84
    const val EP_AUDIO_IN = 0x82
    const val EP_APIF_OUT = 0x03
    const val EP_APIF_IN = 0x83
    const val IMU_PACKET_BYTES = 128

    fun matchesVendor(vendorId: Int): Boolean =
        vendorId == VID || vendorId == DEV_VID

    fun matchesDevice(vendorId: Int, productId: Int): Boolean =
        (vendorId == VID && productId == PID) ||
            (vendorId == DEV_VID && productId == DEV_PID)
}
