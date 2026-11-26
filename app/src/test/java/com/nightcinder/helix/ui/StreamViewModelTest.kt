package com.nightcinder.helix.ui

import android.content.Context
import android.content.Intent
import android.view.Surface
import com.nightcinder.helix.core.model.AppDiscovery
import com.nightcinder.helix.core.model.ClipboardSource
import com.nightcinder.helix.core.model.ClipboardSyncState
import com.nightcinder.helix.core.model.HostApp
import com.nightcinder.helix.core.model.HostAppCatalog
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConfig
import com.nightcinder.helix.core.model.StreamConnectionState
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.core.model.StreamResolution
import com.nightcinder.helix.core.model.StreamTelemetry
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.core.stream.ClipboardBridge
import com.nightcinder.helix.core.stream.MoonlightStreamClient
import com.nightcinder.helix.ui.state.AppListStatus
import com.nightcinder.helix.ui.state.StreamViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeMoonlightStreamClient : MoonlightStreamClient {
    val _state = MutableStateFlow(StreamConnectionState())
    override val state: StateFlow<StreamConnectionState> = _state.asStateFlow()

    val _telemetry = MutableStateFlow(StreamTelemetry())
    override val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    var lastConnectedConfig: StreamConfig? = null
    var isConnected = false
    var attachedSurface: Surface? = null
    var lastSentInput: StreamInputEvent? = null
    var updatedConfig: StreamConfig? = null
    var discovery: () -> AppDiscovery = { AppDiscovery.Unpaired }

    override suspend fun discoverApps(config: StreamConfig): AppDiscovery = discovery()

    override suspend fun connect(config: StreamConfig) {
        lastConnectedConfig = config
        isConnected = true
        _state.value = StreamConnectionState(status = StreamConnectionStatus.Connected, activeTransport = config.transportMode)
    }

    override suspend fun disconnect() {
        isConnected = false
        _state.value = StreamConnectionState(status = StreamConnectionStatus.Disconnected)
    }

    override fun attachSurface(surface: Surface) {
        attachedSurface = surface
    }

    override fun detachSurface() {
        attachedSurface = null
    }

    override suspend fun sendInput(event: StreamInputEvent) {
        lastSentInput = event
    }

    override suspend fun updateConfig(config: StreamConfig) {
        updatedConfig = config
    }

    override fun buildExternalMoonlightIntent(context: Context): Intent {
        return Intent("com.limelight.LAUNCH")
    }
}

class FakeClipboardBridge : ClipboardBridge {
    val _state = MutableStateFlow(ClipboardSyncState())
    override val state: StateFlow<ClipboardSyncState> = _state.asStateFlow()

    var autoSyncEnabled = false
    var lastPushedText: String? = null
    var lastPastedToTerminalText: String? = null

    override fun setAutoSync(enabled: Boolean) {
        autoSyncEnabled = enabled
        _state.value = _state.value.copy(autoSyncEnabled = enabled)
    }

    override suspend fun pullFromRemote(): Result<String> {
        val text = "Remote desktop clipboard"
        _state.value = _state.value.copy(lastSyncedText = text)
        return Result.success(text)
    }

    override suspend fun pushToRemote(text: String): Result<Unit> {
        lastPushedText = text
        _state.value = _state.value.copy(lastSyncedText = text)
        return Result.success(Unit)
    }

    override suspend fun pasteToActiveTerminal(text: String): Result<Unit> {
        lastPastedToTerminalText = text
        return Result.success(Unit)
    }

    override suspend fun onLocalClipboardChanged(text: String, source: ClipboardSource): Result<Unit> {
        return Result.success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var streamClient: FakeMoonlightStreamClient
    private lateinit var clipboardBridge: FakeClipboardBridge
    private lateinit var viewModel: StreamViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        streamClient = FakeMoonlightStreamClient()
        clipboardBridge = FakeClipboardBridge()
        viewModel = StreamViewModel(streamClient, clipboardBridge)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun connect_derivesWebSocketUrlFromRuntimeMoonlightUrl() = runTest {
        val vm = StreamViewModel(streamClient, clipboardBridge, moonlightBaseUrlProvider = { "https://m.example.com/" })
        vm.connect()
        advanceUntilIdle()
        assertEquals("wss://m.example.com/api/host/stream/web_socket", streamClient.lastConnectedConfig?.wsUrl)
    }

    @Test
    fun connect_blankRuntimeMoonlightUrlKeepsConfiguredWsUrl() = runTest {
        val vm = StreamViewModel(streamClient, clipboardBridge, moonlightBaseUrlProvider = { "" })
        vm.connect()
        advanceUntilIdle()
        assertEquals(vm.uiState.value.config.wsUrl, streamClient.lastConnectedConfig?.wsUrl)
    }

    @Test
    fun selectHost_updatesConfigHostIp() = runTest {
        val host = TermixHost(id = 12L, name = "desktop", ip = "192.0.2.20")
        viewModel.selectHost(host)
        advanceUntilIdle()

        assertEquals(host, viewModel.uiState.value.selectedHost)
        assertEquals("192.0.2.20", viewModel.uiState.value.config.hostIp)
    }

    @Test
    fun connectAndDisconnect_updatesConnectionState() = runTest {
        viewModel.connect()
        advanceUntilIdle()

        assertTrue(streamClient.isConnected)
        assertEquals(StreamConnectionStatus.Connected, viewModel.uiState.value.streamState.status)

        viewModel.disconnect()
        advanceUntilIdle()

        assertEquals(false, streamClient.isConnected)
        assertEquals(StreamConnectionStatus.Disconnected, viewModel.uiState.value.streamState.status)
    }

    @Test
    fun setTransportModeAndCodec_updatesConfigAndClient() = runTest {
        viewModel.setTransportMode(StreamTransportMode.WEBSOCKET_443)
        viewModel.setCodec(StreamCodec.AV1)
        advanceUntilIdle()

        assertEquals(StreamTransportMode.WEBSOCKET_443, viewModel.uiState.value.config.transportMode)
        assertEquals(StreamCodec.AV1, viewModel.uiState.value.config.codec)
        assertEquals(StreamCodec.AV1, streamClient.updatedConfig?.codec)
    }

    @Test
    fun clipboardBridge_pullAndPush_syncsState() = runTest {
        viewModel.pullClipboard()
        advanceUntilIdle()

        assertEquals("Remote desktop clipboard", viewModel.uiState.value.clipboardState.lastSyncedText)

        viewModel.pushClipboard("Pushed from UI")
        advanceUntilIdle()

        assertEquals("Pushed from UI", clipboardBridge.lastPushedText)
        assertEquals("Pushed from UI", viewModel.uiState.value.clipboardState.lastSyncedText)
    }

    @Test
    fun toggleAutoSyncClipboard_updatesState() = runTest {
        viewModel.toggleAutoSyncClipboard(true)
        advanceUntilIdle()

        assertTrue(clipboardBridge.autoSyncEnabled)
        assertTrue(viewModel.uiState.value.clipboardState.autoSyncEnabled)
    }

    @Test
    fun defaultTelemetry_isAllZeroNotConfiguredValues() {
        val telemetry = viewModel.uiState.value.telemetry
        assertEquals(0.0, telemetry.fps, 0.0)
        assertEquals(0, telemetry.bitrateKbps)
        assertEquals(0, telemetry.rttMs)
        assertEquals(0, telemetry.width)
        assertEquals(0, telemetry.height)
        assertEquals(false, telemetry.hasData)
    }

    @Test
    fun defaultResolution_isWuxga1200p16by10() {
        val config = viewModel.uiState.value.config
        assertEquals(StreamResolution.WUXGA_1200P, config.resolution)
        assertEquals(1920, config.width)
        assertEquals(1200, config.height)
    }

    @Test
    fun setResolution_superUltrawideUpdatesSizeAndRecommendedBitrate() = runTest {
        viewModel.setResolution(StreamResolution.SUPER_ULTRAWIDE_1200P)
        advanceUntilIdle()

        val config = viewModel.uiState.value.config
        assertEquals(StreamResolution.SUPER_ULTRAWIDE_1200P, config.resolution)
        assertEquals(3840, config.width)
        assertEquals(1200, config.height)
        assertEquals(40000, config.bitrateKbps)
        assertEquals(StreamResolution.SUPER_ULTRAWIDE_1200P, streamClient.updatedConfig?.resolution)
        assertEquals(40000, streamClient.updatedConfig?.bitrateKbps)
    }

    @Test
    fun spatialPanelDimensions_scaleAcrossAspectRatiosWithoutWingOverlap() {
        val presets = listOf(
            StreamResolution.WUXGA_1200P,
            StreamResolution.ULTRAWIDE_1200P,
            StreamResolution.UWQHD_1440P,
            StreamResolution.SUPER_ULTRAWIDE_1200P,
        )
        val wingWidthDp = 640
        val widths = presets.map { it.spatialPanelWidthDp() }
        val offsets = presets.map { it.spatialWingOffsetXDp(wingWidthDp = wingWidthDp) }

        for (i in 0 until presets.lastIndex) {
            assertTrue(widths[i] < widths[i + 1])
            assertTrue(offsets[i] < offsets[i + 1])
        }
        presets.forEachIndexed { index, _ ->
            assertTrue(offsets[index] > (widths[index] / 2) + (wingWidthDp / 2))
        }
    }

    private val desktop = HostApp(1, "Desktop")
    private val remoteMonitor = HostApp(7, "Remote Monitor")
    private val streamHost = TermixHost(id = 12L, name = "desktop", ip = "192.0.2.20")

    @Test
    fun selectHost_pairedHost_populatesAppsFromHost() = runTest {
        streamClient.discovery = { AppDiscovery.Loaded(HostAppCatalog(listOf(desktop, remoteMonitor), 0)) }
        viewModel.selectHost(streamHost)
        advanceUntilIdle()
        val picker = viewModel.uiState.value.appPicker
        assertEquals(AppListStatus.Loaded, picker.status)
        assertEquals(listOf(desktop, remoteMonitor), picker.apps)
        assertEquals(1, viewModel.uiState.value.config.appId)
    }

    @Test
    fun selectHost_unpairedHost_keepsDesktopDefaultWithoutApps() = runTest {
        viewModel.selectHost(streamHost)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(AppListStatus.Unpaired, state.appPicker.status)
        assertTrue(state.appPicker.apps.isEmpty())
        assertEquals("Desktop", state.config.appTitle)
        assertNull(state.config.appId)
    }

    @Test
    fun refreshApps_offline_surfacesErrorAndKeepsLastSelection() = runTest {
        streamClient.discovery = { AppDiscovery.Loaded(HostAppCatalog(listOf(desktop, remoteMonitor), 0)) }
        viewModel.selectHost(streamHost)
        advanceUntilIdle()
        viewModel.selectApp(remoteMonitor)
        streamClient.discovery = { throw java.io.IOException("host unreachable") }
        viewModel.refreshApps()
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(AppListStatus.Error, state.appPicker.status)
        assertEquals("host unreachable", state.appPicker.message)
        assertEquals(listOf(desktop, remoteMonitor), state.appPicker.apps)
        assertEquals(7, state.config.appId)
        viewModel.connect()
        advanceUntilIdle()
        assertEquals(7, streamClient.lastConnectedConfig?.appId)
    }

    @Test
    fun connect_emptyAppList_refusesWithExplicitMessage() = runTest {
        streamClient.discovery = { AppDiscovery.Loaded(HostAppCatalog(emptyList(), 0)) }
        viewModel.selectHost(streamHost)
        advanceUntilIdle()
        viewModel.connect()
        advanceUntilIdle()
        assertEquals("Host has no apps to stream", viewModel.uiState.value.errorMessage)
        assertNull(streamClient.lastConnectedConfig)
    }

    @Test
    fun connect_firstConnect_refreshesAppListAfterPairing() = runTest {
        viewModel.selectHost(streamHost)
        advanceUntilIdle()
        streamClient.discovery = { AppDiscovery.Loaded(HostAppCatalog(listOf(desktop), 1)) }
        viewModel.connect()
        advanceUntilIdle()
        assertEquals(AppListStatus.Loaded, viewModel.uiState.value.appPicker.status)
        assertEquals(1, viewModel.uiState.value.appPicker.catalog?.currentGame)
    }

    @Test
    fun selectApp_resetsReplaceFlag() = runTest {
        viewModel.setReplaceRunningApp(true)
        viewModel.selectApp(remoteMonitor)
        assertEquals(false, viewModel.uiState.value.config.replaceRunningApp)
        assertEquals("Remote Monitor", viewModel.uiState.value.config.appTitle)
    }
}
