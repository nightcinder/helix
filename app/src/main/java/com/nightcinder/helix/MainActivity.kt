package com.nightcinder.helix

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.xr.compose.platform.SpaceRequestResult
import androidx.xr.compose.platform.requestFullSpace
import androidx.xr.compose.platform.requestHomeSpace
import com.nightcinder.helix.core.auth.ActivityProvider
import com.nightcinder.helix.core.spatial.WorkspaceDisplayBackend
import com.nightcinder.helix.di.AppContainer
import com.nightcinder.helix.media.MediaViewModel
import com.nightcinder.helix.spotify.SpotifyViewModel
import com.nightcinder.helix.ui.auth.BiometricLockHost
import com.nightcinder.helix.ui.media.LocalMediaViewModel
import com.nightcinder.helix.ui.spotify.LocalSpotifyViewModel
import com.nightcinder.helix.ui.dashboard.DashboardDestination
import com.nightcinder.helix.ui.dashboard.DashboardScreen
import com.nightcinder.helix.ui.layout.LocalCockpitLayoutManager
import kotlinx.coroutines.launch
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.MonitoringViewModel
import com.nightcinder.helix.ui.state.SpatialViewModel
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.TreeViewModel
import com.nightcinder.helix.ui.state.WorkspaceViewModel
import com.nightcinder.helix.ui.theme.HelixTheme

class MainActivity : FragmentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var monitoringViewModel: MonitoringViewModel
    private lateinit var terminalViewModel: TerminalViewModel
    private lateinit var workspaceViewModel: WorkspaceViewModel
    private lateinit var streamViewModel: StreamViewModel
    private lateinit var spatialViewModel: SpatialViewModel
    private lateinit var treeViewModel: TreeViewModel
    private lateinit var spotifyViewModel: SpotifyViewModel
    private lateinit var mediaViewModel: MediaViewModel

    private val requestXrPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        spatialViewModel.onXrPermissionsResult(allGranted)
        appContainer.xrSessionController.onPermissionsResult(allGranted)
    }

    /** True while the URXR glasses / external presentation trackpad deck is in use. */
    private fun isTrackpadDeckActive(): Boolean {
        val s = spatialViewModel.uiState.value
        return s.isUrxrGlasses || s.urxrPresentationActive ||
            s.displayBackend is WorkspaceDisplayBackend.ExternalPresentation
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val helixApp = application as HelixApp
        appContainer = helixApp.getOrCreateContainer { app ->
            AppContainer.create(app)
        }

        // Attach Activity to ActivityProvider for CredentialManager Passkey assertions
        (appContainer.activityProvider as? ActivityProvider)?.attachActivity(this)

        val dashboardFactory = DashboardViewModel.provideFactory(
            authManager = appContainer.authManager,
            repository = appContainer.homelabRepository,
            displayBackendDetector = appContainer.displayBackendDetector,
            monitoringRepository = appContainer.monitoringRepository,
        )
        dashboardViewModel = ViewModelProvider(this, dashboardFactory)[DashboardViewModel::class.java]

        val monitoringFactory = MonitoringViewModel.provideFactory(appContainer.monitoringRepository)
        monitoringViewModel = ViewModelProvider(this, monitoringFactory)[MonitoringViewModel::class.java]

        val terminalFactory = TerminalViewModel.provideFactory(
            transport = appContainer.terminalTransport,
            workspaceRepo = appContainer.workspaceTreeRepository,
        )
        terminalViewModel = ViewModelProvider(this, terminalFactory)[TerminalViewModel::class.java]

        val workspaceFactory = WorkspaceViewModel.provideFactory(
            workspaceRepo = appContainer.workspaceTreeRepository,
        )
        workspaceViewModel = ViewModelProvider(this, workspaceFactory)[WorkspaceViewModel::class.java]

        val streamFactory = StreamViewModel.provideFactory(
            streamClient = appContainer.moonlightStreamClient,
            clipboardBridge = appContainer.clipboardBridge,
            moonlightBaseUrlProvider = { appContainer.authManager.sessionState.value.config.moonlightBaseUrl },
        )
        streamViewModel = ViewModelProvider(this, streamFactory)[StreamViewModel::class.java]

        val spatialFactory = SpatialViewModel.provideFactory(
            displayBackendDetector = appContainer.displayBackendDetector,
            lifecycleGate = appContainer.xrSessionController.gate,
        )
        spatialViewModel = ViewModelProvider(this, spatialFactory)[SpatialViewModel::class.java]

        val treeFactory = TreeViewModel.provideFactory(
            workspaceRepo = appContainer.workspaceTreeRepository,
        )
        treeViewModel = ViewModelProvider(this, treeFactory)[TreeViewModel::class.java]

        val spotifyFactory = SpotifyViewModel.provideFactory(
            auth = appContainer.spotifyAuthService,
            api = appContainer.spotifyWebApi,
            localPlayer = appContainer.localSpotifyPlayer,
            config = appContainer.spotifyClientConfig,
        )
        spotifyViewModel = ViewModelProvider(this, spotifyFactory)[SpotifyViewModel::class.java]

        val mediaFactory = MediaViewModel.provideFactory(
            jellyfin = appContainer.jellyfinClient,
            plex = appContainer.plexClient,
            player = appContainer.mediaPlayerController,
        )
        mediaViewModel = ViewModelProvider(this, mediaFactory)[MediaViewModel::class.java]

        // OLED keep-screen-on only while the Pixel Trackpad Deck / external presentation is active
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                spatialViewModel.uiState.collect {
                    if (isTrackpadDeckActive()) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        handleOidcIntent(intent)
        checkAndRequestXrPermissions()

        val initialPanelId = intent?.getStringExtra("helix_panel_id")
        val initialDestination = when (initialPanelId) {
            "terminal" -> DashboardDestination.TERMINAL
            "workspace" -> DashboardDestination.WORKSPACE
            else -> DashboardDestination.HOSTS
        }

        setContent {
            HelixTheme {
                val dashboardUiState = dashboardViewModel.uiState.collectAsStateWithLifecycle().value
                val monitoringUiState = monitoringViewModel.uiState.collectAsStateWithLifecycle().value
                val terminalUiState = terminalViewModel.uiState.collectAsStateWithLifecycle().value
                val workspaceUiState = workspaceViewModel.uiState.collectAsStateWithLifecycle().value
                val streamUiState = streamViewModel.uiState.collectAsStateWithLifecycle().value
                val spatialUiState = spatialViewModel.uiState.collectAsStateWithLifecycle().value
                val treeUiState = treeViewModel.uiState.collectAsStateWithLifecycle().value

                CompositionLocalProvider(
                    LocalCockpitLayoutManager provides appContainer.cockpitLayoutManager,
                    LocalSpotifyViewModel provides spotifyViewModel,
                    LocalMediaViewModel provides mediaViewModel,
                ) {
                    BiometricLockHost(
                        activity = this@MainActivity,
                        lockManager = appContainer.biometricLockManager,
                        isAuthenticated = dashboardUiState.authState.isAuthenticated,
                        onSignOut = { dashboardViewModel.signOut() },
                    ) {
                        DashboardScreen(
                            viewModel = dashboardViewModel,
                            uiState = dashboardUiState,
                            terminalViewModel = terminalViewModel,
                            terminalUiState = terminalUiState,
                            workspaceViewModel = workspaceViewModel,
                            workspaceUiState = workspaceUiState,
                            streamViewModel = streamViewModel,
                            streamUiState = streamUiState,
                            spatialViewModel = spatialViewModel,
                            spatialUiState = spatialUiState,
                            monitoringViewModel = monitoringViewModel,
                            monitoringUiState = monitoringUiState,
                            treeViewModel = treeViewModel,
                            treeUiState = treeUiState,
                            initialDestination = initialDestination,
                            initialPanelId = initialPanelId,
                            onDetachWindow = { panelId -> launchDetachedPanelWindow(panelId) },
                            onRequestFullSpace = {
                                lifecycleScope.launch {
                                    runCatching { requestFullSpace() }
                                        .onSuccess { res ->
                                            if (res is SpaceRequestResult.Success) {
                                                spatialViewModel.setFullSpace()
                                            }
                                        }
                                }
                            },
                            onRequestHomeSpace = {
                                lifecycleScope.launch {
                                    runCatching { requestHomeSpace() }
                                        .onSuccess { res ->
                                            if (res is SpaceRequestResult.Success) {
                                                spatialViewModel.setHomeSpace()
                                            }
                                        }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOidcIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        appContainer.biometricLockManager.onActivityStarted(
            dashboardViewModel.uiState.value.authState.isAuthenticated,
        )
    }

    override fun onStop() {
        super.onStop()
        appContainer.biometricLockManager.onActivityStopped(
            dashboardViewModel.uiState.value.authState.isAuthenticated,
        )
    }

    override fun onResume() {
        super.onResume()
        (appContainer.activityProvider as? ActivityProvider)?.attachActivity(this)
        dashboardViewModel.detectDisplayBackend()
        spatialViewModel.detectDisplayBackend()
        spatialViewModel.onLifecycleResumed(true)
        appContainer.xrSessionController.onLifecycleChanged(true)
    }

    override fun onPause() {
        super.onPause()
        spatialViewModel.onLifecycleResumed(false)
        appContainer.xrSessionController.onLifecycleChanged(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        (appContainer.activityProvider as? ActivityProvider)?.detachActivity(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && isTrackpadDeckActive()) {
            // Track the press so onKeyLongPress fires once; the system volume stays untouched on the deck.
            if (event?.repeatCount == 0) event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && isTrackpadDeckActive()) {
            // Long press volume down triggers 3DoF recenter (once per press)
            spatialViewModel.recenter3Dof()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && isTrackpadDeckActive()) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleOidcIntent(intent: Intent?) {
        val data = intent?.data ?: return
        // Termix OIDC callback intent filter: scheme="termix-mobile", host="oidc-callback"
        if (data.scheme == "termix-mobile" && data.host == "oidc-callback") {
            dashboardViewModel.onOidcCallbackUri(data.toString())
        }
    }

    private fun checkAndRequestXrPermissions() {
        val backend = appContainer.displayBackendDetector.detect()
        if (backend is WorkspaceDisplayBackend.AndroidXrSpatial) {
            val permissions = arrayOf(
                "android.permission.SCENE_UNDERSTANDING_COARSE",
                "android.permission.HAND_TRACKING",
                "android.permission.HEAD_TRACKING",
            )
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                requestXrPermissionsLauncher.launch(notGranted.toTypedArray())
            } else {
                spatialViewModel.onXrPermissionsResult(true)
                appContainer.xrSessionController.onPermissionsResult(true)
            }
        }
    }

    private fun launchDetachedPanelWindow(panelId: String) {
        DetachedPanelActivity.launch(this, panelId)
    }
}
