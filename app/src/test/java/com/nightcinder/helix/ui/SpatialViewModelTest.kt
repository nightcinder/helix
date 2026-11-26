package com.nightcinder.helix.ui

import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.DisplayEnvironmentProbe
import com.nightcinder.helix.core.spatial.ExternalDisplayDescriptor
import com.nightcinder.helix.core.spatial.SpatialMode
import com.nightcinder.helix.core.spatial.SpatialPanelId
import com.nightcinder.helix.core.spatial.UsbDeviceDescriptor
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.ui.state.SpatialViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeSpatialProbe(
    var xrPresent: Boolean = false,
    var openXrPresent: Boolean = false,
    var usbDevices: List<UsbDeviceDescriptor> = emptyList(),
    var externalDisplays: List<ExternalDisplayDescriptor> = emptyList(),
) : DisplayEnvironmentProbe {
    override fun hasSystemFeature(featureName: String): Boolean {
        return when (featureName) {
            DisplayBackendDetector.FEATURE_XR_API_SPATIAL -> xrPresent
            DisplayBackendDetector.FEATURE_XR_API_OPENXR -> openXrPresent
            "android.hardware.usb.host" -> usbDevices.isNotEmpty()
            else -> false
        }
    }

    override fun connectedUsbDevices(): List<UsbDeviceDescriptor> = usbDevices
    override fun externalPresentationDisplays(): List<ExternalDisplayDescriptor> = externalDisplays
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpatialViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var probe: FakeSpatialProbe
    private lateinit var detector: DisplayBackendDetector
    private lateinit var viewModel: SpatialViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        probe = FakeSpatialProbe()
        detector = DisplayBackendDetector(probe)
        viewModel = SpatialViewModel(detector)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_standard2dAndHomeSpace() = runTest {
        advanceUntilIdle()
        assertEquals(WorkspaceDisplayBackend.Standard2D, viewModel.uiState.value.displayBackend)
        assertEquals(SpatialMode.HOME_SPACE, viewModel.uiState.value.spatialMode)
        assertFalse(viewModel.uiState.value.isAndroidXr)
        assertFalse(viewModel.uiState.value.isUrxrGlasses)
    }

    @Test
    fun detectAndroidXr_updatesBackend() = runTest {
        probe.xrPresent = true
        viewModel.detectDisplayBackend()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isAndroidXr)
    }

    @Test
    fun detectUrxrGlasses_setsUrxr3Dof() = runTest {
        probe.usbDevices = listOf(UsbDeviceDescriptor(vendorId = 0x371A, productId = 0x0101, productName = "URXR"))
        viewModel.detectDisplayBackend()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isUrxrGlasses)
        val backend = viewModel.uiState.value.displayBackend as? WorkspaceDisplayBackend.Urxr3Dof
        assertEquals("URXR", backend?.vendorName)
    }

    @Test
    fun fullSpaceAndHomeSpace_toggleModes() = runTest {
        viewModel.setFullSpace()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFullSpace)

        viewModel.setHomeSpace()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFullSpace)
    }

    @Test
    fun togglePanel_showsFloatingMedia() = runTest {
        assertFalse(viewModel.uiState.value.panels[SpatialPanelId.FLOATING_MEDIA]?.visible == true)

        viewModel.togglePanel(SpatialPanelId.FLOATING_MEDIA, true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.panels[SpatialPanelId.FLOATING_MEDIA]?.visible == true)
    }

    @Test
    fun recenter3Dof_updatesEpochMs() = runTest {
        val initial = viewModel.uiState.value.recenterEpochMs
        viewModel.recenter3Dof()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.recenterEpochMs >= initial)
    }
}
