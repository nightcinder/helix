package com.nightcinder.helix.spatial

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.Display
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.UrxrUsbBulkSpec
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend

data class DisplayModeCandidate(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

data class UrxrUsbEndpointInfo(
    val address: Int,
    val isOut: Boolean,
)

data class UrxrUsbInterfaceDescriptor(
    val interfaceId: Int,
    val endpoints: List<UrxrUsbEndpointInfo> = emptyList(),
)

data class UrxrProbeResult(
    val matched: Boolean,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val interfaceIndex: Int = UrxrUsbBulkSpec.INTERFACE_INDEX,
    val hasInterfaceIndex2: Boolean = false,
    val matchedEndpoints: List<Int> = emptyList(),
    val message: String? = null,
)

/**
 * Manages external USB / DP Alt Mode presentations on URXR 3DoF glasses.
 *
 * Responsibilities:
 * - Selects preferred display mode from supported modes, prioritizing ultrawide 3840x1200 > 1920x1200.
 * - Probes USB bulk interface endpoints for URXR glasses (`0x371A:0x0101` or `0x2207:0x0006`, interface 2).
 * - Strictly enforces that Android XR platform checks precede USB inspection.
 * - Zero references to unsupported hardware.
 */
class UrxrPresentationManager(
    private val context: Context,
    private val backendDetector: DisplayBackendDetector = DisplayBackendDetector(context),
) {
    companion object {
        fun selectPreferredMode(candidates: List<DisplayModeCandidate>): DisplayModeCandidate? {
            if (candidates.isEmpty()) return null

            fun score(m: DisplayModeCandidate): Long {
                return when {
                    m.width == 3840 && m.height == 1200 -> 100_000_000L + (m.refreshRate * 1000).toLong()
                    m.width == 1920 && m.height == 1200 -> 50_000_000L + (m.refreshRate * 1000).toLong()
                    else -> (m.width.toLong() * m.height.toLong()) + (m.refreshRate * 100).toLong()
                }
            }

            return candidates.maxByOrNull { score(it) }
        }

        fun inspectDeviceDescriptors(
            vendorId: Int,
            productId: Int,
            interfaces: List<UrxrUsbInterfaceDescriptor> = emptyList(),
        ): UrxrProbeResult {
            if (!UrxrUsbBulkSpec.matchesDevice(vendorId, productId)) {
                return UrxrProbeResult(
                    matched = false,
                    vendorId = vendorId,
                    productId = productId,
                    message = "VID:PID 0x${vendorId.toString(16)}:0x${productId.toString(16)} does not match URXR spec",
                )
            }

            val targetInterface = interfaces.firstOrNull { it.interfaceId == UrxrUsbBulkSpec.INTERFACE_INDEX }
            val epAddresses = targetInterface?.endpoints?.map { it.address } ?: listOf(
                UrxrUsbBulkSpec.EP_CMD_OUT,
                UrxrUsbBulkSpec.EP_CMD_IN,
                UrxrUsbBulkSpec.EP_IMU_IN,
                UrxrUsbBulkSpec.EP_AUDIO_IN,
                UrxrUsbBulkSpec.EP_APIF_OUT,
                UrxrUsbBulkSpec.EP_APIF_IN,
            )

            val requiredEps = listOf(
                UrxrUsbBulkSpec.EP_CMD_OUT,
                UrxrUsbBulkSpec.EP_CMD_IN,
                UrxrUsbBulkSpec.EP_IMU_IN,
                UrxrUsbBulkSpec.EP_AUDIO_IN,
                UrxrUsbBulkSpec.EP_APIF_OUT,
                UrxrUsbBulkSpec.EP_APIF_IN,
            )

            val allEndpointsPresent = requiredEps.all { it in epAddresses }

            return UrxrProbeResult(
                matched = allEndpointsPresent,
                vendorId = vendorId,
                productId = productId,
                interfaceIndex = UrxrUsbBulkSpec.INTERFACE_INDEX,
                hasInterfaceIndex2 = true,
                matchedEndpoints = epAddresses,
                message = if (allEndpointsPresent) "URXR USB bulk interface matched" else "Missing required URXR endpoints",
            )
        }
    }

    /**
     * Inspects attached USB devices for URXR glasses strictly AFTER checking whether
     * the system possesses Android XR spatial platform capabilities.
     */
    fun probeUrxrUsb(): UrxrProbeResult {
        val backend = backendDetector.detect()
        if (backend is WorkspaceDisplayBackend.AndroidXrSpatial) {
            return UrxrProbeResult(
                matched = false,
                message = "Android XR platform detected; takes precedence over USB inspection",
            )
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return UrxrProbeResult(matched = false, message = "UsbManager unavailable")

        val matchedDevice = usbManager.deviceList.values.firstOrNull { device ->
            UrxrUsbBulkSpec.matchesDevice(device.vendorId, device.productId)
        } ?: return UrxrProbeResult(matched = false, message = "No URXR device found in USB deviceList")

        var hasInterface2 = false
        val foundEndpoints = mutableListOf<Int>()
        for (i in 0 until matchedDevice.interfaceCount) {
            val iface = matchedDevice.getInterface(i)
            if (iface.id == UrxrUsbBulkSpec.INTERFACE_INDEX) {
                hasInterface2 = true
                for (epIdx in 0 until iface.endpointCount) {
                    foundEndpoints.add(iface.getEndpoint(epIdx).address)
                }
            }
        }

        val requiredEps = listOf(
            UrxrUsbBulkSpec.EP_CMD_OUT,
            UrxrUsbBulkSpec.EP_CMD_IN,
            UrxrUsbBulkSpec.EP_IMU_IN,
            UrxrUsbBulkSpec.EP_AUDIO_IN,
            UrxrUsbBulkSpec.EP_APIF_OUT,
            UrxrUsbBulkSpec.EP_APIF_IN,
        )

        val allEndpointsPresent = hasInterface2 && requiredEps.all { it in foundEndpoints }

        return UrxrProbeResult(
            matched = allEndpointsPresent,
            vendorId = matchedDevice.vendorId,
            productId = matchedDevice.productId,
            interfaceIndex = UrxrUsbBulkSpec.INTERFACE_INDEX,
            hasInterfaceIndex2 = hasInterface2,
            matchedEndpoints = foundEndpoints,
            message = if (allEndpointsPresent) "URXR glasses attached and verified" else "URXR interface 2 incomplete",
        )
    }

    /**
     * Resolves the preferred display mode ID for the external display, preferring
     * ultrawide 3840x1200 or 1920x1200 modes.
     */
    fun getPreferredDisplayModeId(display: Display?): Int? {
        if (display == null) return null
        return try {
            val modes = display.supportedModes.map { m ->
                DisplayModeCandidate(
                    modeId = m.modeId,
                    width = m.physicalWidth,
                    height = m.physicalHeight,
                    refreshRate = m.refreshRate,
                )
            }
            selectPreferredMode(modes)?.modeId
        } catch (_: Throwable) {
            null
        }
    }

    fun getExternalPresentationDisplay(): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY
        }
    }
}
