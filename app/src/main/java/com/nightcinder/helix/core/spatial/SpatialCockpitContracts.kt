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

@Serializable
enum class XrSessionGateState {
    UNAVAILABLE,
    AWAITING_PERMISSIONS,
    AWAITING_RESUMED,
    READY,
    ERROR,
}

/**
 * Pure-Kotlin state machine that gates Android XR session creation strictly on BOTH
 * [hasRequiredPermissions] == true AND [isResumed] == true.
 *
 * This guarantees that `Session.create(activity)` is NEVER invoked while paused or awaiting
 * runtime permission dialogs, which would fail or crash on Android XR runtimes.
 */
class XrSessionLifecycleGate(
    initialAvailable: Boolean = true,
    initialPermissionsGranted: Boolean = false,
    initialResumed: Boolean = false,
) {
    var isAvailable: Boolean = initialAvailable
        private set

    var hasRequiredPermissions: Boolean = initialPermissionsGranted
        private set

    var isResumed: Boolean = initialResumed
        private set

    var lastErrorMessage: String? = null
        private set

    val currentState: XrSessionGateState
        get() = evaluateState()

    fun canCreateSession(): Boolean = currentState == XrSessionGateState.READY

    fun onAvailabilityChanged(available: Boolean): XrSessionGateState {
        isAvailable = available
        if (!available) {
            lastErrorMessage = null
        }
        return evaluateState()
    }

    fun onPermissionsChanged(granted: Boolean): XrSessionGateState {
        hasRequiredPermissions = granted
        if (granted) {
            lastErrorMessage = null
        }
        return evaluateState()
    }

    fun onLifecycleChanged(resumed: Boolean): XrSessionGateState {
        isResumed = resumed
        return evaluateState()
    }

    fun onSessionError(message: String): XrSessionGateState {
        lastErrorMessage = message
        return XrSessionGateState.ERROR
    }

    fun clearError(): XrSessionGateState {
        lastErrorMessage = null
        return evaluateState()
    }

    private fun evaluateState(): XrSessionGateState {
        if (lastErrorMessage != null) return XrSessionGateState.ERROR
        if (!isAvailable) return XrSessionGateState.UNAVAILABLE
        if (!hasRequiredPermissions) return XrSessionGateState.AWAITING_PERMISSIONS
        if (!isResumed) return XrSessionGateState.AWAITING_RESUMED
        return XrSessionGateState.READY
    }
}

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
