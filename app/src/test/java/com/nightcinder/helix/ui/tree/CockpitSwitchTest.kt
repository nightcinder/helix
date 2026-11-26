package com.nightcinder.helix.ui.tree

import com.nightcinder.helix.core.model.StreamConnectionState
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.DisplayEnvironmentProbe
import com.nightcinder.helix.core.spatial.ExternalDisplayDescriptor
import com.nightcinder.helix.core.spatial.SpatialPanelId
import com.nightcinder.helix.core.spatial.UsbDeviceDescriptor
import com.nightcinder.helix.ui.FakeTerminalTransport
import com.nightcinder.helix.ui.FakeWorkspaceTreeRepository
import com.nightcinder.helix.ui.dashboard.DashboardDestination
import com.nightcinder.helix.ui.dashboard.resolvePanelsInitialArea
import com.nightcinder.helix.ui.panels.CockpitArea
import com.nightcinder.helix.ui.state.SpatialViewModel
import com.nightcinder.helix.ui.state.StreamUiState
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.TreeViewModel
import com.nightcinder.helix.terminal.TerminalBufferRegistry
import kotlinx.coroutines.CoroutineScope
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitSwitchTest {

    private val testDispatcher = StandardTestDispatcher()
    private val desktop = TermixHost(id = 20L, name = "desktop", ip = "192.0.2.20", enableTerminal = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun panelsToTree_autoFocusesStreamSynchronouslyOnFrame1ForActiveTransports() {
        val existingTabs = TabsState().open(contentTab(TabKind.METRICS, hostId = 1L, hostName = "core"))
        val activeStatuses = listOf(
            StreamConnectionStatus.Connecting,
            StreamConnectionStatus.Pairing,
            StreamConnectionStatus.Connected,
        )
        for (status in activeStatuses) {
            val streamUi = StreamUiState(
                streamState = StreamConnectionState(
                    status = status,
                    activeTransport = StreamTransportMode.DIRECT_UDP,
                    targetHostIp = "192.0.2.20",
                ),
            )
            val resolved = resolveEffectiveTreeTabs(existingTabs, streamUi, listOf(desktop))
            assertEquals("Expected STREAM tab for status=$status", TabKind.STREAM, resolved.active?.kind)
            assertEquals(desktop.id, resolved.active?.hostId)
        }

        // Disconnected or EXTERNAL_NATIVE does not hijack active tab
        val disconnected = StreamUiState(
            streamState = StreamConnectionState(status = StreamConnectionStatus.Disconnected),
        )
        assertEquals(existingTabs, resolveEffectiveTreeTabs(existingTabs, disconnected, listOf(desktop)))

        val externalNative = StreamUiState(
            streamState = StreamConnectionState(
                status = StreamConnectionStatus.Connected,
                activeTransport = StreamTransportMode.EXTERNAL_NATIVE,
            ),
        )
        assertEquals(existingTabs, resolveEffectiveTreeTabs(existingTabs, externalNative, listOf(desktop)))
    }

    @Test
    fun treeToPanels_autoFocusesStreamSynchronouslyEvenWhenInitialDestinationIsNotHosts() {
        val activeStream = StreamUiState(
            streamState = StreamConnectionState(
                status = StreamConnectionStatus.Connected,
                activeTransport = StreamTransportMode.WEBSOCKET_443,
            ),
        )
        assertEquals(
            CockpitArea.STREAM,
            resolvePanelsInitialArea(DashboardDestination.TERMINAL, activeStream),
        )
        assertEquals(
            CockpitArea.STREAM,
            resolvePanelsInitialArea(DashboardDestination.WORKSPACE, activeStream),
        )

        val disconnected = StreamUiState(
            streamState = StreamConnectionState(status = StreamConnectionStatus.Disconnected),
        )
        assertEquals(
            CockpitArea.TERMINAL,
            resolvePanelsInitialArea(DashboardDestination.TERMINAL, disconnected),
        )
        assertEquals(
            CockpitArea.WORKSPACE,
            resolvePanelsInitialArea(DashboardDestination.WORKSPACE, disconnected),
        )
    }

    @Test
    fun terminalTabsSyncWithoutTeardownAndTreeVisibilityDoesNotMutateSpatialViewModel() = runTest(testDispatcher) {
        val transport = FakeTerminalTransport()
        val workspaceRepo = FakeWorkspaceTreeRepository()
        val terminalVm = TerminalViewModel(transport, workspaceRepo, TerminalBufferRegistry(CoroutineScope(testDispatcher)))
        val treeVm = TreeViewModel(workspaceRepo)

        val probe = object : DisplayEnvironmentProbe {
            override fun hasSystemFeature(featureName: String) = false
            override fun connectedUsbDevices(): List<UsbDeviceDescriptor> = emptyList()
            override fun externalPresentationDisplays(): List<ExternalDisplayDescriptor> = emptyList()
        }
        val spatialVm = SpatialViewModel(DisplayBackendDetector(probe))

        // Open SSH tab -> returns tabId and syncs into TreeViewModel without tearing down
        val tabId = terminalVm.openHostSshTab(desktop)
        advanceUntilIdle()
        treeVm.syncTerminalTabs(terminalVm.uiState.value.tabs)
        assertEquals(1, treeVm.uiState.value.tabs.tabs.size)
        assertEquals(tabId, treeVm.uiState.value.tabs.active?.terminalTabId)

        // Toggle Tree visibility & modals -> shared SpatialViewModel panels stay untouched
        val beforeCenter = spatialVm.uiState.value.panels[SpatialPanelId.CENTER_DESKTOP]
        val beforeLeft = spatialVm.uiState.value.panels[SpatialPanelId.LEFT_TERMINAL]

        treeVm.toggleTreeVisible()
        treeVm.toggleTabsVisible()
        treeVm.setShowAlertsModal(true)
        treeVm.setShowSessionModal(true)

        assertFalse(treeVm.uiState.value.treeVisible)
        assertFalse(treeVm.uiState.value.tabsVisible)
        assertTrue(treeVm.uiState.value.showAlertsModal)
        assertTrue(treeVm.uiState.value.showSessionModal)
        assertEquals(beforeCenter, spatialVm.uiState.value.panels[SpatialPanelId.CENTER_DESKTOP])
        assertEquals(beforeLeft, spatialVm.uiState.value.panels[SpatialPanelId.LEFT_TERMINAL])
    }
}
