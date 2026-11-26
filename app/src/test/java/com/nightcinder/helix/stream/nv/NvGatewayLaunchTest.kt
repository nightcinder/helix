package com.nightcinder.helix.stream.nv

import com.nightcinder.helix.auth.InMemoryCryptoStore
import com.nightcinder.helix.core.model.AppDiscovery
import com.nightcinder.helix.core.model.StreamConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val APPS = listOf(
    NvApp(1, "Desktop"),
    NvApp(2, "Steam Big Picture"),
    NvApp(3, "Remote Input"),
    NvApp(4, "Remote Monitor"),
)

private fun serverInfo(currentGame: Int) = NvServerInfo(
    hostname = "Desktop",
    appVersion = "7.1.431.-1",
    gfeVersion = "3.23.0.74",
    httpsPort = 47984,
    pairStatus = 1,
    currentGame = currentGame,
    state = "SUNSHINE_SERVER_FREE",
    serverCodecModeSupport = 1,
    virtualDisplayCapable = true,
    virtualDisplayDriverReady = true,
    virtualDisplayHdrCapable = true,
)

private class FakeHost(
    override val isPinned: Boolean = true,
    currentGame: Int = 0,
    var apps: List<NvApp> = APPS,
) : NvHostApi {
    var info = serverInfo(currentGame)
    var appsError: Exception? = null
    var cancelResult = true
    var cancelError: Exception? = null
    var launchError: Exception? = null
    var appListCalls = 0
    val calls = mutableListOf<String>()

    override suspend fun getServerInfo() = info

    override suspend fun getAppList(): List<NvApp> {
        appListCalls++
        appsError?.let { throw it }
        return apps
    }

    override suspend fun launch(params: NvLaunchParams): NvLaunchResponse {
        calls += "launch:${params.appId}"
        launchError?.let { throw it }
        return NvLaunchResponse("rtsp://host:48010", "")
    }

    override suspend fun resume(params: NvLaunchParams): NvLaunchResponse {
        calls += "resume:${params.appId}"
        return NvLaunchResponse("rtsp://host:48010", "")
    }

    override suspend fun cancel(): Boolean {
        calls += "cancel"
        cancelError?.let { throw it }
        return cancelResult
    }
}

class NvGatewayLaunchTest {

    private fun gateway(host: FakeHost) = DefaultNvGateway(
        identityStore = NvIdentityStore(InMemoryCryptoStore()),
        hostApiFactory = { host },
    )

    private suspend fun launch(host: FakeHost, config: StreamConfig) =
        gateway(host).launch("192.0.2.20", config, "")

    private suspend fun launchFailure(host: FakeHost, config: StreamConfig): NvHttpException {
        try {
            launch(host, config)
        } catch (e: NvHttpException) {
            return e
        }
        fail("launch should have been refused")
        throw AssertionError()
    }

    @Test
    fun noRunningApp_launchesSelectedApp() = runTest {
        val host = FakeHost()
        val result = launch(host, StreamConfig(appId = 2, appTitle = "Steam Big Picture"))
        assertEquals(listOf("launch:2"), host.calls)
        assertEquals("Steam Big Picture", result.appTitle)
        assertFalse(result.resumed)
    }

    @Test
    fun defaultConfig_resolvesDesktopByTitle() = runTest {
        val host = FakeHost()
        launch(host, StreamConfig())
        assertEquals(listOf("launch:1"), host.calls)
    }

    @Test
    fun runningAppIsSelected_resumes() = runTest {
        val host = FakeHost(currentGame = 2)
        val result = launch(host, StreamConfig(appId = 2, appTitle = "Steam Big Picture"))
        assertEquals(listOf("resume:2"), host.calls)
        assertTrue(result.resumed)
    }

    @Test
    fun otherAppRunning_concurrentVirtualApp_launchesWithoutCancel() = runTest {
        val host = FakeHost(currentGame = 1)
        launch(host, StreamConfig(appId = 4, appTitle = "Remote Monitor"))
        assertEquals(listOf("launch:4"), host.calls)
    }

    @Test
    fun otherAppRunning_replaceTrue_cancelsThenLaunches() = runTest {
        val host = FakeHost(currentGame = 1)
        launch(host, StreamConfig(appId = 2, appTitle = "Steam Big Picture", replaceRunningApp = true))
        assertEquals(listOf("cancel", "launch:2"), host.calls)
    }

    @Test
    fun otherAppRunning_replaceFalse_refusesWithoutAnyHostCall() = runTest {
        val host = FakeHost(currentGame = 1)
        val e = launchFailure(host, StreamConfig(appId = 2, appTitle = "Steam Big Picture"))
        assertTrue(e.message!!.contains("Replace & Launch"))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun replace_cancelThrows_neverLaunches() = runTest {
        val host = FakeHost(currentGame = 1).apply { cancelError = NvHttpException("cancel failed") }
        launchFailure(host, StreamConfig(appId = 2, replaceRunningApp = true))
        assertEquals(listOf("cancel"), host.calls)
    }

    @Test
    fun replace_cancelRefused_neverLaunches() = runTest {
        val host = FakeHost(currentGame = 1).apply { cancelResult = false }
        launchFailure(host, StreamConfig(appId = 2, replaceRunningApp = true))
        assertEquals(listOf("cancel"), host.calls)
    }

    @Test
    fun replace_launchThrowsAfterCancel_propagates() = runTest {
        val host = FakeHost(currentGame = 1).apply { launchError = NvHttpException("launch failed") }
        val e = launchFailure(host, StreamConfig(appId = 2, replaceRunningApp = true))
        assertEquals("launch failed", e.message)
        assertEquals(listOf("cancel", "launch:2"), host.calls)
    }

    @Test
    fun emptyAppList_refusesLaunch() = runTest {
        val host = FakeHost(apps = emptyList())
        val e = launchFailure(host, StreamConfig())
        assertEquals("Host has no apps to stream", e.message)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun selectedAppGoneFromHost_refusesInsteadOfLaunchingAnother() = runTest {
        val host = FakeHost()
        launchFailure(host, StreamConfig(appId = 99, appTitle = "Gone"))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun discover_pairedHost_returnsHostAppsAndVirtualDisplay() = runTest {
        val host = FakeHost(currentGame = 1)
        val result = gateway(host).discoverApps("192.0.2.20") as AppDiscovery.Loaded
        assertEquals(
            listOf("Desktop", "Steam Big Picture", "Remote Input", "Remote Monitor"),
            result.catalog.apps.map { it.title },
        )
        assertEquals(1, result.catalog.currentGame)
        assertEquals("Desktop", result.catalog.runningApp?.title)
        val display = result.catalog.virtualDisplay
        assertTrue(display.capable && display.driverReady && display.hdrCapable)
    }

    @Test
    fun discover_unpairedHost_skipsApplist() = runTest {
        val host = FakeHost(isPinned = false)
        assertEquals(AppDiscovery.Unpaired, gateway(host).discoverApps("192.0.2.20"))
        assertEquals(0, host.appListCalls)
    }

    @Test
    fun discover_fetchError_propagatesWithoutFakeApps() = runTest {
        val host = FakeHost().apply { appsError = java.io.IOException("timeout") }
        try {
            gateway(host).discoverApps("192.0.2.20")
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals("timeout", e.message)
        }
    }
}
