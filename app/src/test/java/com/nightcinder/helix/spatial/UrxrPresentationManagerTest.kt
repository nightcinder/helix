package com.nightcinder.helix.spatial

import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.DisplayEnvironmentProbe
import com.nightcinder.helix.core.spatial.ExternalDisplayDescriptor
import com.nightcinder.helix.core.spatial.UrxrUsbBulkSpec
import com.nightcinder.helix.core.spatial.UsbDeviceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrxrPresentationManagerTest {

    private class TestProbe(
        var features: Set<String> = emptySet(),
        var usbDevices: List<UsbDeviceDescriptor> = emptyList(),
        var displays: List<ExternalDisplayDescriptor> = emptyList(),
    ) : DisplayEnvironmentProbe {
        override fun hasSystemFeature(featureName: String): Boolean = featureName in features
        override fun connectedUsbDevices(): List<UsbDeviceDescriptor> = usbDevices
        override fun externalPresentationDisplays(): List<ExternalDisplayDescriptor> = displays
    }

    @Test
    fun selectPreferredMode_prioritizes3840x1200UltrawideHighestHz() {
        val candidates = listOf(
            DisplayModeCandidate(modeId = 1, width = 1920, height = 1080, refreshRate = 60f),
            DisplayModeCandidate(modeId = 2, width = 1920, height = 1200, refreshRate = 60f),
            DisplayModeCandidate(modeId = 3, width = 3840, height = 1200, refreshRate = 60f),
            DisplayModeCandidate(modeId = 4, width = 3840, height = 1200, refreshRate = 90f),
            DisplayModeCandidate(modeId = 5, width = 1920, height = 1080, refreshRate = 120f),
        )

        val selected = UrxrPresentationManager.selectPreferredMode(candidates)
        assertNotNull(selected)
        assertEquals(4, selected?.modeId)
        assertEquals(3840, selected?.width)
        assertEquals(1200, selected?.height)
        assertEquals(90f, selected?.refreshRate)
    }

    @Test
    fun selectPreferredMode_prioritizes1920x1200Over1080pWhen3840Unavailable() {
        val candidates = listOf(
            DisplayModeCandidate(modeId = 1, width = 1920, height = 1080, refreshRate = 120f),
            DisplayModeCandidate(modeId = 2, width = 1920, height = 1200, refreshRate = 60f),
            DisplayModeCandidate(modeId = 3, width = 1280, height = 720, refreshRate = 60f),
        )

        val selected = UrxrPresentationManager.selectPreferredMode(candidates)
        assertNotNull(selected)
        assertEquals(2, selected?.modeId)
        assertEquals(1920, selected?.width)
        assertEquals(1200, selected?.height)
    }

    @Test
    fun selectPreferredMode_emptyCandidatesReturnsNull() {
        val selected = UrxrPresentationManager.selectPreferredMode(emptyList())
        assertNull(selected)
    }

    @Test
    fun inspectDeviceDescriptors_matchesStandardUrxrGlassesAndEndpoints() {
        val interfaces = listOf(
            UrxrUsbInterfaceDescriptor(
                interfaceId = UrxrUsbBulkSpec.INTERFACE_INDEX,
                endpoints = listOf(
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_CMD_OUT, isOut = true),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_CMD_IN, isOut = false),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_IMU_IN, isOut = false),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_AUDIO_IN, isOut = false),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_APIF_OUT, isOut = true),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_APIF_IN, isOut = false),
                ),
            ),
        )

        val result = UrxrPresentationManager.inspectDeviceDescriptors(
            vendorId = UrxrUsbBulkSpec.VID,
            productId = UrxrUsbBulkSpec.PID,
            interfaces = interfaces,
        )

        assertTrue(result.matched)
        assertEquals(UrxrUsbBulkSpec.VID, result.vendorId)
        assertEquals(UrxrUsbBulkSpec.PID, result.productId)
        assertTrue(result.hasInterfaceIndex2)
        assertEquals(6, result.matchedEndpoints.size)
    }

    @Test
    fun inspectDeviceDescriptors_matchesUrxrDevKitVidPid() {
        val result = UrxrPresentationManager.inspectDeviceDescriptors(
            vendorId = UrxrUsbBulkSpec.DEV_VID,
            productId = UrxrUsbBulkSpec.DEV_PID,
        )

        assertTrue(result.matched)
        assertEquals(UrxrUsbBulkSpec.DEV_VID, result.vendorId)
        assertEquals(UrxrUsbBulkSpec.DEV_PID, result.productId)
    }

    @Test
    fun inspectDeviceDescriptors_rejectsNonMatchingVidPid() {
        val result = UrxrPresentationManager.inspectDeviceDescriptors(
            vendorId = 0x1234,
            productId = 0x5678,
        )

        assertFalse(result.matched)
    }

    @Test
    fun inspectDeviceDescriptors_rejectsWhenMissingRequiredEndpoints() {
        // Missing EP_IMU_IN (0x84)
        val interfaces = listOf(
            UrxrUsbInterfaceDescriptor(
                interfaceId = UrxrUsbBulkSpec.INTERFACE_INDEX,
                endpoints = listOf(
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_CMD_OUT, isOut = true),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_CMD_IN, isOut = false),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_AUDIO_IN, isOut = false),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_APIF_OUT, isOut = true),
                    UrxrUsbEndpointInfo(address = UrxrUsbBulkSpec.EP_APIF_IN, isOut = false),
                ),
            ),
        )

        val result = UrxrPresentationManager.inspectDeviceDescriptors(
            vendorId = UrxrUsbBulkSpec.VID,
            productId = UrxrUsbBulkSpec.PID,
            interfaces = interfaces,
        )

        assertFalse(result.matched)
        assertTrue(result.message?.contains("Missing required URXR endpoints") == true)
    }

    private class DummyContext : android.content.ContextWrapper(null)

    @Test
    fun probeUrxrUsb_whenAndroidXrPlatformDetected_bypassesUsbProbe() {
        val probe = TestProbe(
            features = setOf(DisplayBackendDetector.FEATURE_XR_API_SPATIAL),
        )
        val detector = DisplayBackendDetector(probe)

        // Dummy context used when XR spatial is detected early
        val manager = UrxrPresentationManager(
            context = DummyContext(),
            backendDetector = detector,
        )

        val result = manager.probeUrxrUsb()
        assertFalse(result.matched)
        assertTrue(result.message?.contains("Android XR platform detected") == true)
    }
}
