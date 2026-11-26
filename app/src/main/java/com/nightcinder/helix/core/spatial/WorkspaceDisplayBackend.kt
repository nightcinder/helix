package com.nightcinder.helix.core.spatial

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.view.Display

/**
 * Active spatial / display presentation backend detected for the Project Helix workspace.
 */
sealed class WorkspaceDisplayBackend {
    abstract val displayLabel: String

    data object Standard2D : WorkspaceDisplayBackend() {
        override val displayLabel: String = "Standard 2D"
    }

    data class ExternalPresentation(
        val displayId: Int? = null,
        val displayName: String? = null,
    ) : WorkspaceDisplayBackend() {
        override val displayLabel: String =
            displayName?.takeIf { it.isNotBlank() }?.let { "External Display ($it)" }
                ?: "External Presentation"
    }

    data class Urxr3Dof(
        val vendorName: String? = null,
        val vendorId: Int? = null,
        val productId: Int? = null,
    ) : WorkspaceDisplayBackend() {
        override val displayLabel: String =
            vendorName?.takeIf { it.isNotBlank() }?.let { "$it 3DoF XR" }
                ?: "USB 3DoF XR"
    }

    data class AndroidXrSpatial(
        val hasSpatialApi: Boolean = true,
        val hasOpenXrApi: Boolean = false,
    ) : WorkspaceDisplayBackend() {
        override val displayLabel: String = "Android XR Spatial"
    }
}

data class UsbDeviceDescriptor(
    val vendorId: Int,
    val productId: Int,
    val productName: String? = null,
)

data class ExternalDisplayDescriptor(
    val displayId: Int,
    val name: String? = null,
)

interface DisplayEnvironmentProbe {
    fun hasSystemFeature(featureName: String): Boolean
    fun connectedUsbDevices(): List<UsbDeviceDescriptor>
    fun externalPresentationDisplays(): List<ExternalDisplayDescriptor>
}

class AndroidDisplayEnvironmentProbe(
    context: Context,
) : DisplayEnvironmentProbe {
    private val appContext: Context = context.applicationContext

    override fun hasSystemFeature(featureName: String): Boolean =
        appContext.packageManager.hasSystemFeature(featureName)

    override fun connectedUsbDevices(): List<UsbDeviceDescriptor> {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()
        return usbManager.deviceList.values.map { device ->
            UsbDeviceDescriptor(
                vendorId = device.vendorId,
                productId = device.productId,
                productName = device.productName,
            )
        }
    }

    override fun externalPresentationDisplays(): List<ExternalDisplayDescriptor> {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return emptyList()
        return displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .map { display ->
                ExternalDisplayDescriptor(
                    displayId = display.displayId,
                    name = display.name,
                )
            }
    }
}

/**
 * Detects the active [WorkspaceDisplayBackend].
 *
 * Detection order is strictly:
 * 1. Android XR platform features (`android.software.xr.api.spatial`, `android.software.xr.api.openxr`)
 *    checked via [PackageManager.hasSystemFeature] BEFORE any USB VID/PID or external display checks.
 * 2. USB-attached 3DoF XR glasses (URXR / XREAL vendor IDs).
 * 3. External presentation displays.
 * 4. Standard 2D adaptive layout fallback.
 */
class DisplayBackendDetector(
    private val probe: DisplayEnvironmentProbe,
) {
    constructor(context: Context) : this(AndroidDisplayEnvironmentProbe(context))

    fun detect(): WorkspaceDisplayBackend {
        // 1. Check Android XR platform features strictly BEFORE any USB VID/PID or display inspection.
        val hasSpatialApi = probe.hasSystemFeature(FEATURE_XR_API_SPATIAL)
        val hasOpenXrApi = probe.hasSystemFeature(FEATURE_XR_API_OPENXR)
        if (hasSpatialApi || hasOpenXrApi) {
            return WorkspaceDisplayBackend.AndroidXrSpatial(
                hasSpatialApi = hasSpatialApi,
                hasOpenXrApi = hasOpenXrApi,
            )
        }

        // 2. USB host 3DoF glasses detection (URXR / XREAL).
        if (probe.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            val matchedGlasses = probe.connectedUsbDevices().firstOrNull { device ->
                device.vendorId in KNOWN_XR_GLASSES_VENDORS
            }
            if (matchedGlasses != null) {
                return WorkspaceDisplayBackend.Urxr3Dof(
                    vendorName = KNOWN_XR_GLASSES_VENDORS[matchedGlasses.vendorId],
                    vendorId = matchedGlasses.vendorId,
                    productId = matchedGlasses.productId,
                )
            }
        }

        // 3. External presentation display (wired DP-Alt / casting presentation).
        val presentationDisplay = probe.externalPresentationDisplays().firstOrNull()
        if (presentationDisplay != null) {
            return WorkspaceDisplayBackend.ExternalPresentation(
                displayId = presentationDisplay.displayId,
                displayName = presentationDisplay.name,
            )
        }

        // 4. Default adaptive 2D workspace.
        return WorkspaceDisplayBackend.Standard2D
    }

    companion object {
        const val FEATURE_XR_API_SPATIAL = "android.software.xr.api.spatial"
        const val FEATURE_XR_API_OPENXR = "android.software.xr.api.openxr"

        const val VENDOR_ID_URXR = 0x371A
        const val VENDOR_ID_URXR_DEV = 0x2207
        const val VENDOR_ID_XREAL = 0x3318

        val KNOWN_XR_GLASSES_VENDORS: Map<Int, String> = mapOf(
            VENDOR_ID_URXR to "URXR",
            VENDOR_ID_URXR_DEV to "URXR",
            VENDOR_ID_XREAL to "XREAL",
        )
    }
}
