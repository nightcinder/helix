package com.nightcinder.helix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nightcinder.helix.di.AppContainer
import com.nightcinder.helix.ui.auth.BiometricLockHost
import com.nightcinder.helix.ui.layout.CockpitLayoutMode
import com.nightcinder.helix.ui.layout.LocalCockpitLayoutManager
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.TreeViewModel
import com.nightcinder.helix.ui.stream.StreamScreen
import com.nightcinder.helix.ui.state.WorkspaceViewModel
import com.nightcinder.helix.ui.terminal.TerminalScreen
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.HelixTheme
import com.nightcinder.helix.ui.tree.DetachedTreeWorkspace
import com.nightcinder.helix.ui.workspace.WorkspaceTreeScreen

/**
 * Dedicated standard-launch activity for detached Home Space multi-window panels
 * (Terminal or XPipe Workspace), sharing the active [AppContainer] session state.
 */
class DetachedPanelActivity : FragmentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var dashboardViewModel: DashboardViewModel
    private var terminalViewModel: TerminalViewModel? = null

    override fun onStart() {
        super.onStart()
        if (::appContainer.isInitialized && ::dashboardViewModel.isInitialized) {
            appContainer.biometricLockManager.onActivityStarted(
                dashboardViewModel.uiState.value.authState.isAuthenticated,
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (::appContainer.isInitialized && ::dashboardViewModel.isInitialized) {
            appContainer.biometricLockManager.onActivityStopped(
                dashboardViewModel.uiState.value.authState.isAuthenticated,
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Only the focused window may resize the shared terminal tabs.
        terminalViewModel?.setWindowFocused(hasFocus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val helixApp = application as HelixApp
        val appContainer = helixApp.getOrCreateContainer { app ->
            AppContainer.create(app)
        }
        this.appContainer = appContainer

        val panelId = intent?.getStringExtra(EXTRA_HELIX_PANEL_ID) ?: PANEL_TERMINAL

        val dashboardViewModel = ViewModelProvider(
            this,
            DashboardViewModel.provideFactory(
                authManager = appContainer.authManager,
                repository = appContainer.homelabRepository,
                displayBackendDetector = appContainer.displayBackendDetector,
                monitoringRepository = appContainer.monitoringRepository,
            ),
        )[DashboardViewModel::class.java]
        this.dashboardViewModel = dashboardViewModel

        val terminalViewModel = ViewModelProvider(
            this,
            TerminalViewModel.provideFactory(
                transport = appContainer.terminalTransport,
                workspaceRepo = appContainer.workspaceTreeRepository,
            ),
        )[TerminalViewModel::class.java]
        // Starts unfocused; onWindowFocusChanged flips it once the window actually has focus.
        terminalViewModel.setWindowFocused(false)
        this.terminalViewModel = terminalViewModel

        val workspaceViewModel = ViewModelProvider(
            this,
            WorkspaceViewModel.provideFactory(
                workspaceRepo = appContainer.workspaceTreeRepository,
            ),
        )[WorkspaceViewModel::class.java]

        // The stream client lives in the shared AppContainer, so a detached stream window
        // shows the same session as the main window (one session, last attached surface renders).
        val streamViewModel = ViewModelProvider(
            this,
            StreamViewModel.provideFactory(
                streamClient = appContainer.moonlightStreamClient,
                clipboardBridge = appContainer.clipboardBridge,
                moonlightBaseUrlProvider = { appContainer.authManager.sessionState.value.config.moonlightBaseUrl },
            ),
        )[StreamViewModel::class.java]

        val treeViewModel = ViewModelProvider(
            this,
            TreeViewModel.provideFactory(
                workspaceRepo = appContainer.workspaceTreeRepository,
            ),
        )[TreeViewModel::class.java]

        setContent {
            HelixTheme {
                val dashboardUiState = dashboardViewModel.uiState.collectAsStateWithLifecycle().value
                val terminalUiState = terminalViewModel.uiState.collectAsStateWithLifecycle().value
                val workspaceUiState = workspaceViewModel.uiState.collectAsStateWithLifecycle().value
                val streamUiState = streamViewModel.uiState.collectAsStateWithLifecycle().value
                val treeUiState = treeViewModel.uiState.collectAsStateWithLifecycle().value
                val layoutMode = appContainer.cockpitLayoutManager.mode.collectAsStateWithLifecycle().value

                val title = when (panelId) {
                    PANEL_WORKSPACE -> "PROJECT HELIX · WORKSPACE"
                    PANEL_STREAM -> "PROJECT HELIX · STREAM"
                    else -> "PROJECT HELIX · TERMINAL"
                }
                val isActivelyStreaming = panelId == PANEL_STREAM &&
                    streamUiState.streamState.status == com.nightcinder.helix.core.model.StreamConnectionStatus.Connected &&
                    streamUiState.streamState.activeTransport != com.nightcinder.helix.core.model.StreamTransportMode.EXTERNAL_NATIVE

                CompositionLocalProvider(
                    LocalCockpitLayoutManager provides appContainer.cockpitLayoutManager,
                ) {
                    BiometricLockHost(
                        activity = this@DetachedPanelActivity,
                        lockManager = appContainer.biometricLockManager,
                        isAuthenticated = dashboardUiState.authState.isAuthenticated,
                        onSignOut = { dashboardViewModel.signOut() },
                    ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!isActivelyStreaming) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = title,
                                        color = CyanPrimary,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    IconButton(onClick = { finish() }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close Window",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            when (panelId) {
                                PANEL_WORKSPACE -> if (layoutMode == CockpitLayoutMode.TREE) {
                                    DetachedTreeWorkspace(
                                        dashboardViewModel = dashboardViewModel,
                                        dashboardUiState = dashboardUiState,
                                        terminalViewModel = terminalViewModel,
                                        terminalUiState = terminalUiState,
                                        streamViewModel = streamViewModel,
                                        streamUiState = streamUiState,
                                        treeViewModel = treeViewModel,
                                        treeUiState = treeUiState,
                                    )
                                } else {
                                    WorkspaceTreeScreen(
                                        viewModel = workspaceViewModel,
                                        uiState = workspaceUiState,
                                        hosts = dashboardUiState.hosts,
                                        onOpenDockerConsole = { hostId, hostName, containerId, containerName ->
                                            terminalViewModel.openDockerConsoleTab(
                                                hostId = hostId,
                                                hostName = hostName,
                                                containerId = containerId,
                                                containerName = containerName,
                                            )
                                        },
                                    )
                                }

                                PANEL_STREAM -> StreamScreen(
                                    viewModel = streamViewModel,
                                    uiState = streamUiState,
                                )

                                else -> TerminalScreen(
                                    viewModel = terminalViewModel,
                                    uiState = terminalUiState,
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_HELIX_PANEL_ID = "helix_panel_id"
        const val PANEL_TERMINAL = "terminal"
        const val PANEL_WORKSPACE = "workspace"
        const val PANEL_STREAM = "stream"

        /**
         * Opens [panelId] as its own top-level window. In Android XR Home Space each task is a
         * separate system panel; MULTIPLE_TASK lets the same panel id open more than once.
         */
        fun launch(context: Context, panelId: String) {
            val intent = Intent(context, DetachedPanelActivity::class.java).apply {
                putExtra(EXTRA_HELIX_PANEL_ID, panelId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            }
            context.startActivity(intent)
        }
    }
}
