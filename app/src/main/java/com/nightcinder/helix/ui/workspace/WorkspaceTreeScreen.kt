package com.nightcinder.helix.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.core.model.DockerContainer
import com.nightcinder.helix.core.model.DockerContainerAction
import com.nightcinder.helix.core.model.DockerStack
import com.nightcinder.helix.core.model.SftpEntry
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.ui.state.WorkspaceDialog
import com.nightcinder.helix.ui.state.WorkspaceUiState
import com.nightcinder.helix.ui.state.WorkspaceViewModel
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTreeScreen(
    viewModel: WorkspaceViewModel,
    uiState: WorkspaceUiState,
    hosts: List<TermixHost>,
    onOpenDockerConsole: (Long, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    showHostPicker: Boolean = true,
) {
    var hostDropdownExpanded by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableIntStateOf(0) } // 0: Docker Tree, 1: SFTP Files
    val currentHost = hosts.firstOrNull { it.id == uiState.selectedHostId } ?: hosts.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Target Host Selector Bar (hosted panels pick the host with their own chip row)
        if (showHostPicker) Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExposedDropdownMenuBox(
                expanded = hostDropdownExpanded,
                onExpandedChange = { hostDropdownExpanded = !hostDropdownExpanded },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = currentHost?.displayName ?: "Select Host",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Workspace Host") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = hostDropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                ExposedDropdownMenu(
                    expanded = hostDropdownExpanded,
                    onDismissRequest = { hostDropdownExpanded = false },
                ) {
                    hosts.forEach { host ->
                        DropdownMenuItem(
                            text = { Text(host.displayName, fontWeight = FontWeight.Bold) },
                            onClick = {
                                viewModel.selectHost(host.id)
                                hostDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CyanPrimary)
            }
        }

        // Sub-tabs: Docker Stacks & Containers vs SFTP File Manager
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = CyanPrimary,
        ) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text("Docker Infrastructure") },
                icon = { Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text("SFTP File Manager") },
                icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }

        // Main Tab Content
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTabIndex) {
                0 -> DockerTreePanel(
                    stacks = uiState.stacks,
                    containers = uiState.containers,
                    onContainerAction = { containerId, action ->
                        viewModel.performContainerAction(containerId, action)
                    },
                    onViewLogs = { container -> viewModel.viewContainerLogs(container) },
                    onOpenConsole = { container ->
                        currentHost?.let { host ->
                            onOpenDockerConsole(host.id, host.displayName, container.id, container.name)
                        }
                    },
                )
                1 -> SftpFileManagerPanel(
                    currentPath = uiState.sftpCurrentPath,
                    entries = uiState.sftpEntries,
                    onBrowse = { path -> viewModel.browseDirectory(path) },
                    onOpenFile = { entry -> viewModel.openFile(entry) },
                )
            }
        }
    }

    // Dialogs: Logs or File Viewer
    when (val dialog = uiState.activeDialog) {
        is WorkspaceDialog.ContainerLogs -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("Logs: ${dialog.container.name}", color = CyanPrimary) },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF030507))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = uiState.selectedContainerLogs?.combinedText?.ifBlank { "No logs available" }
                                ?: "Loading...",
                            color = Color(0xFFE2E8F0),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text("Close")
                    }
                },
            )
        }
        is WorkspaceDialog.EditFile -> {
            var fileContent by remember(dialog.content) { mutableStateOf(dialog.content) }
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("Edit: ${dialog.entry.name}", color = CyanPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(dialog.entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = fileContent,
                            onValueChange = { fileContent = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.saveFile(dialog.entry.path, fileContent) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text("Cancel")
                    }
                },
            )
        }
        is WorkspaceDialog.EditCompose -> {
            // Dismissible compose dialog
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("Docker Compose: ${dialog.stackName}", color = CyanPrimary) },
                text = {
                    Text(dialog.content, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) { Text("Close") }
                },
            )
        }
        null -> {}
    }
}

@Composable
fun DockerTreePanel(
    stacks: List<DockerStack>,
    containers: List<DockerContainer>,
    onContainerAction: (String, DockerContainerAction) -> Unit,
    onViewLogs: (DockerContainer) -> Unit,
    onOpenConsole: (DockerContainer) -> Unit,
) {
    if (stacks.isEmpty() && containers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No Docker stacks or containers found for this host.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Stacks
            items(stacks, key = { it.name }) { stack ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Layers, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(18.dp))
                                Text(stack.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                "${stack.runningCount}/${stack.serviceCount} running",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldAccent,
                            )
                        }

                        // Containers in stack
                        stack.containers.forEach { container ->
                            DockerContainerRow(
                                container = container,
                                onContainerAction = onContainerAction,
                                onViewLogs = onViewLogs,
                                onOpenConsole = onOpenConsole,
                            )
                        }
                    }
                }
            }

            // Standalone Containers
            val standaloneContainers = containers.filter { it.stackName.isNullOrBlank() }
            if (standaloneContainers.isNotEmpty()) {
                item {
                    Text("Standalone Containers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                items(standaloneContainers, key = { it.id }) { container ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        DockerContainerRow(
                            container = container,
                            onContainerAction = onContainerAction,
                            onViewLogs = onViewLogs,
                            onOpenConsole = onOpenConsole,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DockerContainerRow(
    container: DockerContainer,
    onContainerAction: (String, DockerContainerAction) -> Unit,
    onViewLogs: (DockerContainer) -> Unit,
    onOpenConsole: (DockerContainer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = if (container.isRunning) EmeraldAccent else RoseError
    var confirmStop by remember(container.id) { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(container.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
            Text(container.image, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (container.isRunning) {
                IconButton(onClick = { onOpenConsole(container) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Terminal, contentDescription = "Console", tint = CyanPrimary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { confirmStop = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = RoseError, modifier = Modifier.size(18.dp))
                }
                if (confirmStop) {
                    AlertDialog(
                        onDismissRequest = { confirmStop = false },
                        title = { Text("Stop ${container.name}?") },
                        text = { Text("The container will stop running. Its services become unavailable until it is started again.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmStop = false
                                onContainerAction(container.id, DockerContainerAction.STOP)
                            }) {
                                Text("Stop", color = RoseError)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmStop = false }) { Text("Cancel") }
                        },
                    )
                }
            } else {
                IconButton(onClick = { onContainerAction(container.id, DockerContainerAction.START) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start", tint = EmeraldAccent, modifier = Modifier.size(18.dp))
                }
            }
            OutlinedButton(
                onClick = { onViewLogs(container) },
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text("Logs", fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun SftpFileManagerPanel(
    currentPath: String,
    entries: List<SftpEntry>,
    onBrowse: (String) -> Unit,
    onOpenFile: (SftpEntry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Path navigation bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (currentPath != "/") {
                IconButton(
                    onClick = {
                        val parent = currentPath.substringBeforeLast('/', "").ifEmpty { "/" }
                        onBrowse(parent)
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = currentPath,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                color = CyanPrimary,
                modifier = Modifier.weight(1f),
            )
        }

        // File list
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty directory or connecting to SFTP...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    if (entry.isDirectory) onBrowse(entry.path)
                                    else onOpenFile(entry)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) AmberAccent else CyanPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                when {
                                    entry.isDirectory -> "DIR"
                                    entry.isSymlink -> "LINK"
                                    else -> "${entry.sizeBytes / 1024} KB"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
