package com.nightcinder.helix.spatial

import android.content.pm.PackageManager
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.DisplayEnvironmentProbe
import com.nightcinder.helix.core.spatial.ExternalDisplayDescriptor
import com.nightcinder.helix.core.spatial.UsbDeviceDescriptor
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayBackendDetectorTest {

    private class FakeDisplayEnvironmentProbe(
        var features: Set<String> = emptySet(),
        var usbDevices: List<UsbDeviceDescriptor> = emptyList(),
        var presentationDisplays: List<ExternalDisplayDescriptor> = emptyList(),
    ) : DisplayEnvironmentProbe {
        override fun hasSystemFeature(featureName: String): Boolean = featureName in features
        override fun connectedUsbDevices(): List<UsbDeviceDescriptor> = usbDevices
        override fun externalPresentationDisplays(): List<ExternalDisplayDescriptor> = presentationDisplays
    }

    @Test
    fun detect_whenXrSpatialFeaturePresent_returnsAndroidXrSpatial() {
        val probe = FakeDisplayEnvironmentProbe(
            features = setOf(DisplayBackendDetector.FEATURE_XR_API_SPATIAL),
            // Even if URXR glasses are also plugged in, Android XR platform takes precedence!
            usbDevices = listOf(
                UsbDeviceDescriptor(
                    vendorId = DisplayBackendDetector.VENDOR_ID_URXR,
                    productId = 0x0101,
                    productName = "URXR Smart Glasses",
                ),
            ),
        )
        val detector = DisplayBackendDetector(probe)
        val backend = detector.detect()

        assertTrue(backend is WorkspaceDisplayBackend.AndroidXrSpatial)
        val xr = backend as WorkspaceDisplayBackend.AndroidXrSpatial
        assertTrue(xr.hasSpatialApi)
        assertEquals("Android XR Spatial", backend.displayLabel)
    }

    @Test
    fun detect_whenXrOpenXrFeaturePresent_returnsAndroidXrSpatial() {
        val probe = FakeDisplayEnvironmentProbe(
            features = setOf(DisplayBackendDetector.FEATURE_XR_API_OPENXR),
        )
        val detector = DisplayBackendDetector(probe)
        val backend = detector.detect()

        assertTrue(backend is WorkspaceDisplayBackend.AndroidXrSpatial)
        val xr = backend as WorkspaceDisplayBackend.AndroidXrSpatial
        assertTrue(xr.hasOpenXrApi)
    }

    @Test
    fun detect_whenUrxrUsbDeviceAttached_returnsUrxr3Dof() {
        val probe = FakeDisplayEnvironmentProbe(
            features = setOf(PackageManager.FEATURE_USB_HOST),
            usbDevices = listOf(
                UsbDeviceDescriptor(
                    vendorId = DisplayBackendDetector.VENDOR_ID_URXR,
                    productId = 0x0101,
                    productName = "URXR Glasses",
                ),
            ),
        )
        val detector = DisplayBackendDetector(probe)
        val backend = detector.detect()

        assertTrue(backend is WorkspaceDisplayBackend.Urxr3Dof)
        val urxr = backend as WorkspaceDisplayBackend.Urxr3Dof
        assertEquals("URXR", urxr.vendorName)
        assertEquals(DisplayBackendDetector.VENDOR_ID_URXR, urxr.vendorId)
        assertEquals(0x0101, urxr.productId)
        assertEquals("URXR 3DoF XR", backend.displayLabel)
    }

    @Test
    fun detect_whenUrxrDevUsbDeviceAttached_returnsUrxr3Dof() {
        val probe = FakeDisplayEnvironmentProbe(
            features = setOf(PackageManager.FEATURE_USB_HOST),
            usbDevices = listOf(
                UsbDeviceDescriptor(
                    vendorId = DisplayBackendDetector.VENDOR_ID_URXR_DEV,
                    productId = 0x0006,
                    productName = "URXR Dev Kit",
                ),
            ),
        )
        val detector = DisplayBackendDetector(probe)
        val backend = detector.detect()

        assertTrue(backend is WorkspaceDisplayBackend.Urxr3Dof)
        val urxr = backend as WorkspaceDisplayBackend.Urxr3Dof
        assertEquals("URXR", urxr.vendorName)
        assertEquals(0x2207, urxr.vendorId)
        assertEquals(0x0006, urxr.productId)
    }

    @Test
    fun detect_whenXrealUsbDeviceAttached_returnsUrxr3Dof() {
        val probe = FakeDisplayEnvironmentProbe(
            features = setOf(PackageManager.FEATURE_USB_HOST),
            usbDevices = listOf(
                UsbDeviceDescriptor(
                    vendorId = DisplayBackendDetector.VENDOR_ID_XREAL,
                    productId = 0x0424,
                    productName = "Air 2 Pro",
                ),
            ),
        )
        val detector = DisplayBackendDetector(probe)
        val backend = detector.detect()

        assertTrue(backend is WorkspaceDisplayBackend.Urxr3Dof)
        val xreal = backend as WorkspaceDisplayBackend.Urxr3Dof
        assertEquals("XREAL", xreal.vendorName)
        assertEquals(0x3318, xreal.vendorId)
        assertEquals(0x0424, xreal.productId)
        assertEquals("XREAL 3DoF XR", backend.displayLabel)
    }

    @Test
    fun detect_whenExternalPresentationDisplayAttached_returnsExternalPresentation() {
        val probe = FakeDisplayEnvironmentProbe(
            presentationDisplays = listOf(
                ExternalDisplayDescriptor(displayId = 2, name = "DP-Alt AR Glasses"),
            ),
        )
        val detector = DisplayBackendDetector(probe)
        val backend = detector.detect()

        assertTrue(backend is WorkspaceDisplayBackend.ExternalPresentation)
        val ext = backend as WorkspaceDisplayBackend.ExternalPresentation
        assertEquals(2, ext.displayId)
        assertEquals("External Display (DP-Alt AR Glasses)", backend.displayLabel)
    }

    @Test
    fun detect_whenNoXrOrExternalPresent_returnsStandard2D() {
        val probe = FakeDisplayEnvironmentProbe()
        val detector = DisplayBackendDetector(probe)
        val backend = detector.detect()

        assertEquals(WorkspaceDisplayBackend.Standard2D, backend)
        assertEquals("Standard 2D", backend.displayLabel)
    }

    @Test
    fun detect_precedenceStrictness() {
        // When all 3 are present: Android XR spatial MUST take precedence over USB and display
        val probeAll = FakeDisplayEnvironmentProbe(
            features = setOf(
                DisplayBackendDetector.FEATURE_XR_API_SPATIAL,
                PackageManager.FEATURE_USB_HOST,
            ),
            usbDevices = listOf(
                UsbDeviceDescriptor(
                    vendorId = DisplayBackendDetector.VENDOR_ID_URXR,
                    productId = 0x0101,
                ),
            ),
            presentationDisplays = listOf(
                ExternalDisplayDescriptor(displayId = 2, name = "External Monitor"),
            ),
        )
        val detectorAll = DisplayBackendDetector(probeAll)
        assertTrue(detectorAll.detect() is WorkspaceDisplayBackend.AndroidXrSpatial)

        // When USB glasses AND external display are present (no XR feature): USB 3DoF takes precedence
        val probeUsbAndDisplay = FakeDisplayEnvironmentProbe(
            features = setOf(PackageManager.FEATURE_USB_HOST),
            usbDevices = listOf(
                UsbDeviceDescriptor(
                    vendorId = DisplayBackendDetector.VENDOR_ID_URXR,
                    productId = 0x0101,
                ),
            ),
            presentationDisplays = listOf(
                ExternalDisplayDescriptor(displayId = 2, name = "External Display"),
            ),
        )
        val detectorUsb = DisplayBackendDetector(probeUsbAndDisplay)
        assertTrue(detectorUsb.detect() is WorkspaceDisplayBackend.Urxr3Dof)
    }
}
