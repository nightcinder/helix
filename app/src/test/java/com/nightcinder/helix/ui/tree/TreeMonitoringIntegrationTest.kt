package com.nightcinder.helix.ui.tree

import com.nightcinder.helix.TestSite
import com.nightcinder.helix.core.model.AlertSeverity
import com.nightcinder.helix.core.model.AlertSource
import com.nightcinder.helix.core.model.MonitoringAlert
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.spatial.DisplayBackendDetector
import com.nightcinder.helix.core.spatial.DisplayEnvironmentProbe
import com.nightcinder.helix.core.spatial.ExternalDisplayDescriptor
import com.nightcinder.helix.core.spatial.UsbDeviceDescriptor
import com.nightcinder.helix.ui.FakeAuthManager
import com.nightcinder.helix.ui.FakeClipboardBridge
import com.nightcinder.helix.ui.FakeHomelabRepository
import com.nightcinder.helix.ui.FakeMoonlightStreamClient
import com.nightcinder.helix.ui.FakeTerminalTransport
import com.nightcinder.helix.ui.FakeWorkspaceTreeRepository
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.MonitoringViewModel
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.TreeViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TreeMonitoringIntegrationTest {
    init {
        TestSite.apply()
    }


    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun tappingAlert_resolvesHostAndSwitchesFromAlertsTabToSshTerminalTab() = runTest(testDispatcher) {
        val probe = object : DisplayEnvironmentProbe {
            override fun hasSystemFeature(featureName: String) = false
            override fun connectedUsbDevices(): List<UsbDeviceDescriptor> = emptyList()
            override fun externalPresentationDisplays(): List<ExternalDisplayDescriptor> = emptyList()
        }
        val homelabRepo = FakeHomelabRepository()
        val desktop = TermixHost(id = 20L, name = "desktop", ip = "192.0.2.20", enableTerminal = true)
        val noTermHost = TermixHost(id = 30L, name = "server1", ip = "192.0.2.30", enableTerminal = false)
        val hosts = listOf(desktop, noTermHost)
        homelabRepo._hosts.value = hosts

        val dashboardVm = DashboardViewModel(
            authManager = FakeAuthManager(),
            repository = homelabRepo,
            displayBackendDetector = DisplayBackendDetector(probe),
        )
        val transport = FakeTerminalTransport()
        val workspaceRepo = FakeWorkspaceTreeRepository()
        val terminalVm = TerminalViewModel(transport, workspaceRepo)
        val treeVm = TreeViewModel(workspaceRepo)
        val streamVm = StreamViewModel(FakeMoonlightStreamClient(), FakeClipboardBridge())
        val controller = TreeController(treeVm, terminalVm, streamVm, dashboardVm)
        advanceUntilIdle()

        // Open global Alerts tab first
        assertTrue(controller.openGlobal(TabKind.ALERTS))
        assertEquals(TabKind.ALERTS, treeVm.uiState.value.tabs.active?.kind)

        // Tap alert targeting desktop -> resolves host and switches active tab to SSH terminal
        val alert = MonitoringAlert(
            id = "kuma:sunshine",
            source = AlertSource.UPTIME_KUMA,
            severity = AlertSeverity.CRITICAL,
            title = "Sunshine Down",
            summary = "monitor_status=0",
            targetHostName = "desktop",
        )
        val matched = MonitoringViewModel.resolveAlertTargetHost(alert, hosts)
        assertNotNull(matched)
        assertTrue(controller.openTerminal(matched!!))
        advanceUntilIdle()

        val activeTab = treeVm.uiState.value.tabs.active
        assertNotNull(activeTab)
        assertEquals(TabKind.TERMINAL, activeTab?.kind)
        assertEquals(20L, activeTab?.hostId)
        assertEquals(activeTab?.terminalTabId, terminalVm.uiState.value.activeTabId)
        assertEquals("192.0.2.20", transport.lastConnectedConfig?.hostIp)

        // Host with enableTerminal = false does not open a broken terminal tab
        assertFalse(controller.openTerminal(noTermHost))
    }
}
