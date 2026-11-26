package com.nightcinder.helix.ui.tree

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nightcinder.helix.core.model.HostStatus
import com.nightcinder.helix.core.model.TermixSnippet
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError

/**
 * The connection tree (hosts > stacks > containers). Row tap expands (or opens logs for a container);
 * everything else lives in the per-row overflow menu.
 */
@Composable
fun TreePane(
    rows: List<TreeRow>,
    loadingHosts: Set<Long>,
    snippets: List<TermixSnippet>,
    highlightKeys: Set<String>,
    onTap: (TreeRow) -> Unit,
    onAction: (TreeNode, NodeAction) -> Unit,
    onRunSnippet: (hostId: Long, snippetId: Long) -> Unit,
    modifier: Modifier = Modifier,
    filter: String = "",
    onFilterChange: (String) -> Unit = {},
    onRequestConfirm: ((TreeNode, NodeAction) -> Unit)? = null,
) {
    Surface(modifier) {
      Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilterChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            placeholder = { Text("Filter hosts") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (filter.isNotEmpty()) {
                    IconButton(onClick = { onFilterChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear filter", modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (filter.isBlank()) "No hosts yet. Check the Termix session." else "No hosts match \"$filter\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
            ) {
                items(rows, key = { it.node.key }) { row ->
                    TreeRowItem(
                        row = row,
                        loading = row.node is TreeNode.Host && row.node.hostId in loadingHosts,
                        highlighted = row.node.key in highlightKeys,
                        snippets = snippets,
                        onTap = { onTap(row) },
                        onAction = { onAction(row.node, it) },
                        onRunSnippet = onRunSnippet,
                        onRequestConfirm = onRequestConfirm?.let { cb -> { action -> cb(row.node, action) } },
                    )
                }
            }
        }
      }
    }
}

@Composable
private fun Surface(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    loading: Boolean,
    highlighted: Boolean,
    snippets: List<TermixSnippet>,
    onTap: () -> Unit,
    onAction: (NodeAction) -> Unit,
    onRunSnippet: (Long, Long) -> Unit,
    onRequestConfirm: ((NodeAction) -> Unit)? = null,
) {
    val node = row.node
    var pendingConfirm by remember { mutableStateOf<NodeAction?>(null) }
    pendingConfirm?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(actionLabel(node, action)) },
            text = { Text(confirmationMessage(node)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfirm = null
                    onAction(action)
                }) { Text("Confirm", color = RoseError) }
            },
            dismissButton = { TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") } },
        )
    }
    val triggerAction: (NodeAction) -> Unit = { action ->
        if (needsConfirmation(node, action)) {
            if (onRequestConfirm != null) onRequestConfirm(action) else pendingConfirm = action
        } else {
            onAction(action)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) MaterialTheme.colorScheme.surfaceBright else Color.Transparent)
            .clickable(onClick = onTap)
            .padding(start = (8 + row.depth * 14).dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (row.expandable) {
                Icon(
                    imageVector = if (row.expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (row.expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        when (node) {
            is TreeNode.Host -> Icon(Icons.Default.Computer, null, tint = CyanPrimary, modifier = Modifier.size(18.dp))
            is TreeNode.Stack -> Icon(Icons.Default.Layers, null, tint = CyanPrimary, modifier = Modifier.size(18.dp))
            is TreeNode.Container -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (node.container.isRunning) EmeraldAccent else RoseError),
            )
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = node.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (node is TreeNode.Container) FontWeight.Normal else FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when (node) {
                is TreeNode.Host -> node.host.displayAddress
                is TreeNode.Stack -> "${node.running}/${node.total} running"
                is TreeNode.Container -> node.container.image
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = if (node is TreeNode.Stack) FontFamily.Default else FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (node is TreeNode.Host) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = CyanPrimary)
            } else {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(hostStatusColor(node.status)),
                )
            }
        }
        InlineRowQuickActions(node = node, onTriggerAction = triggerAction)
        NodeOverflowMenu(
            node = node,
            snippets = snippets,
            onTriggerAction = triggerAction,
            onRunSnippet = onRunSnippet,
        )
    }
}

@Composable
private fun InlineRowQuickActions(
    node: TreeNode,
    onTriggerAction: (NodeAction) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when (node) {
            is TreeNode.Host -> {
                if (node.host.enableTerminal) {
                    QuickActionIcon(Icons.Default.Terminal, "SSH Terminal", CyanPrimary) {
                        onTriggerAction(NodeAction.TERMINAL)
                    }
                }
                QuickActionIcon(
                    Icons.Default.Tv,
                    if (isStreamHost(node.host)) "Stream Desktop (recommended)" else "Stream Desktop",
                    if (isStreamHost(node.host)) EmeraldAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    onTriggerAction(NodeAction.STREAM)
                }
                QuickActionIcon(Icons.Default.Folder, "Files (SFTP)", MaterialTheme.colorScheme.onSurfaceVariant) {
                    onTriggerAction(NodeAction.FILES)
                }
                if (canWake(node.host, node.status)) {
                    QuickActionIcon(Icons.Default.FlashOn, "Wake on LAN", AmberAccent) {
                        onTriggerAction(NodeAction.WAKE)
                    }
                }
            }
            is TreeNode.Container -> {
                QuickActionIcon(Icons.Default.Description, "Logs", MaterialTheme.colorScheme.onSurfaceVariant) {
                    onTriggerAction(NodeAction.LOGS)
                }
                if (node.container.isRunning) {
                    QuickActionIcon(Icons.Default.Terminal, "Docker Console", CyanPrimary) {
                        onTriggerAction(NodeAction.TERMINAL)
                    }
                    QuickActionIcon(Icons.Default.Refresh, "Restart", MaterialTheme.colorScheme.onSurfaceVariant) {
                        onTriggerAction(NodeAction.RESTART)
                    }
                    QuickActionIcon(Icons.Default.Stop, "Stop", RoseError) {
                        onTriggerAction(NodeAction.STOP)
                    }
                } else {
                    QuickActionIcon(Icons.Default.PlayArrow, "Start", EmeraldAccent) {
                        onTriggerAction(NodeAction.START)
                    }
                }
            }
            is TreeNode.Stack -> {
                QuickActionIcon(Icons.Default.PlayArrow, "Up", EmeraldAccent) {
                    onTriggerAction(NodeAction.UP)
                }
                QuickActionIcon(Icons.Default.Refresh, "Restart", MaterialTheme.colorScheme.onSurfaceVariant) {
                    onTriggerAction(NodeAction.RESTART)
                }
                QuickActionIcon(Icons.Default.Stop, "Down", RoseError) {
                    onTriggerAction(NodeAction.STOP)
                }
            }
        }
    }
}

@Composable
private fun QuickActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun hostStatusColor(status: HostStatus): Color = when (status) {
    HostStatus.ONLINE -> EmeraldAccent
    HostStatus.DEGRADED, HostStatus.SLEEPING -> AmberAccent
    HostStatus.OFFLINE -> RoseError
    HostStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun NodeOverflowMenu(
    node: TreeNode,
    snippets: List<TermixSnippet>,
    onTriggerAction: (NodeAction) -> Unit,
    onRunSnippet: (Long, Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var snippetMode by remember { mutableStateOf(false) }
    val close = {
        open = false
        snippetMode = false
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Actions for ${node.label}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = close) {
            if (snippetMode) {
                DropdownMenuItem(text = { Text("Back") }, onClick = { snippetMode = false })
                if (snippets.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No snippets in Termix", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = close,
                    )
                }
                snippets.forEach { snippet ->
                    DropdownMenuItem(
                        text = { Text(snippet.name) },
                        onClick = {
                            onRunSnippet(node.hostId, snippet.id)
                            close()
                        },
                    )
                }
            } else {
                actionsFor(node).forEach { action ->
                    DropdownMenuItem(
                        text = { Text(actionLabel(node, action)) },
                        onClick = {
                            close()
                            onTriggerAction(action)
                        },
                    )
                }
                if (node is TreeNode.Host) {
                    DropdownMenuItem(
                        text = { Text("Run snippet…") },
                        onClick = { snippetMode = true },
                    )
                }
            }
        }
    }
}
