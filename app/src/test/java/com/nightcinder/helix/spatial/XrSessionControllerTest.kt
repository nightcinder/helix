package com.nightcinder.helix.spatial

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.DisplayEnvironmentProbe
import com.nightcinder.helix.core.spatial.ExternalDisplayDescriptor
import com.nightcinder.helix.core.spatial.SpatialMode
import com.nightcinder.helix.core.spatial.SpatialPanelId
import com.nightcinder.helix.core.spatial.UsbDeviceDescriptor
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.core.spatial.XrSessionGateState
import com.nightcinder.helix.core.spatial.XrSessionLifecycleGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XrSessionControllerTest {

    private class TestProbe(
        var features: Set<String> = emptySet(),
        var usbDevices: List<UsbDeviceDescriptor> = emptyList(),
        var displays: List<ExternalDisplayDescriptor> = emptyList(),
    ) : DisplayEnvironmentProbe {
        override fun hasSystemFeature(featureName: String): Boolean = featureName in features
        override fun connectedUsbDevices(): List<UsbDeviceDescriptor> = usbDevices
        override fun externalPresentationDisplays(): List<ExternalDisplayDescriptor> = displays
    }

    private class DummyContext : ContextWrapper(null) {
        override fun getPackageName(): String = "com.nightcinder.helix"
        override fun getPackageManager(): PackageManager? = null
    }

    private lateinit var probe: TestProbe
    private lateinit var detector: DisplayBackendDetector
    private lateinit var gate: XrSessionLifecycleGate
    private lateinit var controller: XrSessionController

    @Before
    fun setUp() {
        probe = TestProbe(features = setOf(DisplayBackendDetector.FEATURE_XR_API_SPATIAL))
        detector = DisplayBackendDetector(probe)
        gate = XrSessionLifecycleGate(
            initialAvailable = true,
            initialPermissionsGranted = false,
            initialResumed = false,
        )
        controller = XrSessionController(detector, gate)
    }

    @Test
    fun initialConfiguration_matchesXrSpatialCockpitDefaults() {
        assertEquals(WorkspaceDisplayBackend.AndroidXrSpatial(hasSpatialApi = true), controller.displayBackend.value)
        assertEquals(SpatialMode.HOME_SPACE, controller.spatialMode.value)
        assertEquals(XrSessionGateState.AWAITING_PERMISSIONS, controller.gateState.value)
        assertFalse(controller.canCreateSession())

        val panels = controller.panels.value
        assertEquals(4, panels.size)

        val center = panels.first { it.id == SpatialPanelId.CENTER_DESKTOP }
        assertTrue(center.visible)
        assertEquals(0f, center.yawDegrees, 0.01f)

        val left = panels.first { it.id == SpatialPanelId.LEFT_TERMINAL }
        assertTrue(left.visible)
        assertEquals(22f, left.yawDegrees, 0.01f)

        val right = panels.first { it.id == SpatialPanelId.RIGHT_WORKSPACE }
        assertTrue(right.visible)
        assertEquals(-22f, right.yawDegrees, 0.01f)

        val media = panels.first { it.id == SpatialPanelId.FLOATING_MEDIA }
        assertFalse(media.visible)
    }

    @Test
    fun togglePanelVisibility_modifiesOnlyTargetPanel() {
        val initialMediaVisible = controller.panels.value.first { it.id == SpatialPanelId.FLOATING_MEDIA }.visible
        assertFalse(initialMediaVisible)

        controller.togglePanelVisibility(SpatialPanelId.FLOATING_MEDIA)
        val mediaAfterToggle = controller.panels.value.first { it.id == SpatialPanelId.FLOATING_MEDIA }.visible
        assertTrue(mediaAfterToggle)

        controller.togglePanelVisibility(SpatialPanelId.FLOATING_MEDIA)
        assertFalse(controller.panels.value.first { it.id == SpatialPanelId.FLOATING_MEDIA }.visible)
    }

    @Test
    fun legacyWebViewMediaPresets_areGone() {
        val names = (XrSessionController::class.java.declaredFields + XrSessionController.Companion::class.java.declaredFields)
            .map { it.name }
        assertTrue(names.none { it.startsWith("DEFAULT_MEDIA_") })
        assertTrue(XrSessionController::class.java.declaredMethods.none { it.name.contains("FloatingMediaUrl", ignoreCase = true) })
    }

    @Test
    fun requestSpatialModes_updatesStateFlow() {
        controller.requestFullSpace()
        assertEquals(SpatialMode.FULL_SPACE, controller.spatialMode.value)

        controller.requestHomeSpace()
        assertEquals(SpatialMode.HOME_SPACE, controller.spatialMode.value)
    }

    @Test
    fun lifecycleAndPermissionsFlow_controlsSessionReadiness() {
        assertFalse(controller.canCreateSession())

        controller.onPermissionsResult(true)
        assertEquals(XrSessionGateState.AWAITING_RESUMED, controller.gateState.value)
        assertFalse(controller.canCreateSession())

        controller.onLifecycleChanged(true)
        assertEquals(XrSessionGateState.READY, controller.gateState.value)
        assertTrue(controller.canCreateSession())

        // Error transitions
        controller.onSessionError("XR Compositor lost")
        assertEquals(XrSessionGateState.ERROR, controller.gateState.value)
        assertFalse(controller.canCreateSession())

        controller.clearError()
        assertEquals(XrSessionGateState.READY, controller.gateState.value)
        assertTrue(controller.canCreateSession())
    }
}
