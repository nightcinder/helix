package com.nightcinder.helix.ui.spatial

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.ui.state.DashboardUiState
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.StreamUiState
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalUiState
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.state.WorkspaceUiState
import com.nightcinder.helix.ui.state.WorkspaceViewModel
import com.nightcinder.helix.ui.stream.StreamScreen
import com.nightcinder.helix.ui.terminal.TerminalScreen
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.HelixTheme
import com.nightcinder.helix.ui.workspace.WorkspaceTreeScreen

/**
 * URXR 3DoF External Presentation Cockpit.
 * Renders a 3-column ultrawide presentation layout (Terminal | Moonlight Desktop | Workspace)
 * on connected URXR glasses or external presentation display (3840x1200 or 1920x1200).
 * Strict URXR USB/DP Alt Mode compliance.
 */
@Composable
fun UrxrExternalCockpit(
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
    terminalViewModel: TerminalViewModel,
    terminalUiState: TerminalUiState,
    workspaceViewModel: WorkspaceViewModel,
    workspaceUiState: WorkspaceUiState,
    dashboardUiState: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030507))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Left Column: Terminal Screen
        Card(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "HELIX TERMINAL",
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                TerminalScreen(
                    viewModel = terminalViewModel,
                    uiState = terminalUiState,
                )
            }
        }

        // Center Column: Moonlight Low-Latency Desktop Stream
        Card(
            modifier = Modifier
                .weight(1.8f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
        ) {
            StreamScreen(
                viewModel = streamViewModel,
                uiState = streamUiState,
            )
        }

        // Right Column: XPipe Workspace & Telemetry Tree
        Card(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "XPipe DOCKER & SFTP",
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                WorkspaceTreeScreen(
                    viewModel = workspaceViewModel,
                    uiState = workspaceUiState,
                    hosts = dashboardUiState.hosts,
                    onOpenDockerConsole = { hostId, hostName, containerId, containerName ->
                        terminalViewModel.openDockerConsoleTab(hostId, hostName, containerId, containerName)
                    },
                )
            }
        }
    }
}

/**
 * Android Presentation helper to render the URXR Cockpit on an external presentation display.
 */
class UrxrCockpitPresentation(
    outerContext: Context,
    display: Display,
    private val streamViewModel: StreamViewModel,
    private val streamUiState: StreamUiState,
    private val terminalViewModel: TerminalViewModel,
    private val terminalUiState: TerminalUiState,
    private val workspaceViewModel: WorkspaceViewModel,
    private val workspaceUiState: WorkspaceUiState,
    private val dashboardUiState: DashboardUiState,
) : Presentation(outerContext, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(context).apply {
            setContent {
                HelixTheme {
                    UrxrExternalCockpit(
                        streamViewModel = streamViewModel,
                        streamUiState = streamUiState,
                        terminalViewModel = terminalViewModel,
                        terminalUiState = terminalUiState,
                        workspaceViewModel = workspaceViewModel,
                        workspaceUiState = workspaceUiState,
                        dashboardUiState = dashboardUiState,
                    )
                }
            }
        }
        setContentView(composeView)
    }
}
