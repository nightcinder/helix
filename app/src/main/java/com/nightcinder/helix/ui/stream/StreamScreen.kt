package com.nightcinder.helix.ui.stream

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nightcinder.helix.core.model.ClipboardSource
import com.nightcinder.helix.core.model.StreamCodec
import com.nightcinder.helix.core.model.StreamConnectionStatus
import com.nightcinder.helix.core.model.StreamResolution
import com.nightcinder.helix.core.model.StreamTelemetry
import com.nightcinder.helix.core.model.StreamTransportMode
import com.nightcinder.helix.core.model.WebSocketAudioState
import com.nightcinder.helix.core.model.WebSocketCapabilityStatus
import com.nightcinder.helix.ui.state.AppListStatus
import com.nightcinder.helix.ui.state.StreamUiState
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScreen(
    viewModel: StreamViewModel,
    uiState: StreamUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var resolutionExpanded by remember { mutableStateOf(false) }
    val streamState = uiState.streamState
    val status = streamState.status
    val isActivelyStreaming = status == StreamConnectionStatus.Connected &&
        streamState.activeTransport != StreamTransportMode.EXTERNAL_NATIVE

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B0E))
            .padding(if (isActivelyStreaming) 0.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (isActivelyStreaming) 0.dp else 8.dp),
    ) {
        // Stream Viewport with Overlay HUD
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(if (isActivelyStreaming) 0.dp else 12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val targetAspect = uiState.config.resolution.aspectRatio
            val availableAspect = if (maxHeight > 0.dp) maxWidth / maxHeight else targetAspect
            val (surfaceWidth, surfaceHeight) = if (availableAspect > targetAspect) {
                (maxHeight * targetAspect) to maxHeight
            } else {
                maxWidth to (maxWidth / targetAspect)
            }

            val directConnected = status == StreamConnectionStatus.Connected &&
                streamState.activeTransport == StreamTransportMode.DIRECT_UDP
            val inputConnected = status == StreamConnectionStatus.Connected &&
                (streamState.activeTransport == StreamTransportMode.DIRECT_UDP ||
                    streamState.activeTransport == StreamTransportMode.WEBSOCKET_443)
            var keyboardRequested by remember { mutableStateOf(false) }
            var controlsExpanded by remember { mutableStateOf(false) }
            // A SurfaceView punches a hole through the window. With no video in it, Android XR shows the
            // environment through that hole, so the view only exists while a session can render into it.
            val needsSurface = streamState.activeTransport != StreamTransportMode.EXTERNAL_NATIVE &&
                status in SURFACE_STATUSES

            // Embedded SurfaceView Viewport sized to stream aspect ratio, with the touch/keyboard layer on top.
            Box(modifier = Modifier.size(width = surfaceWidth, height = surfaceHeight)) {
                if (needsSurface) AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    viewModel.attachSurface(holder.surface)
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    // Only detaches when this is the surface currently rendering the shared stream.
                                    viewModel.detachSurface(holder.surface)
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (inputConnected) {
                    StreamInputLayer(
                        onInput = viewModel::sendInput,
                        keyboardRequested = keyboardRequested,
                        modifier = Modifier.fillMaxSize(),
                        hudExpanded = controlsExpanded,
                    )
                }
            }

            // Collapsible TopCenter HUD pill while streaming so all 4 Windows desktop corners remain clickable.
            if (isActivelyStreaming) {
                val telemetry = uiState.telemetry
                val fpsText = if (telemetry.fps > 0.0) "${telemetry.fps.toInt()} FPS" else "-- FPS"
                val bitrateText = if (telemetry.bitrateKbps > 0) "${telemetry.bitrateKbps / 1000} Mbps" else "-- Mbps"
                val rttText = if (telemetry.rttMs > 0) "${telemetry.rttMs} ms" else "-- ms"

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.clickable { controlsExpanded = !controlsExpanded },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldAccent),
                            )
                            Text(
                                text = "$fpsText · $bitrateText · $rttText · ${telemetry.activeCodec.name}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = Color(0xFFE2E8F0),
                            )
                            Text(
                                text = if (controlsExpanded) "Hide ▴" else "Controls ▾",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CyanPrimary,
                            )
                        }
                        if (controlsExpanded) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = { viewModel.disconnect() },
                                    colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Disconnect", fontSize = 11.sp)
                                }
                                if (inputConnected) {
                                    OutlinedButton(
                                        onClick = { keyboardRequested = !keyboardRequested },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Text(if (keyboardRequested) "Hide keyboard" else "Keyboard", fontSize = 11.sp)
                                    }
                                }
                                if (directConnected) {
                                    OutlinedButton(
                                        onClick = { viewModel.pullClipboard() },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = "Copy from PC", modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Copy from PC", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.pushLocalClipboard() },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Icon(Icons.Default.Upload, contentDescription = "Send to PC", modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Send to PC", fontSize = 11.sp)
                                    }
                                }
                            }
                            if (inputConnected) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = { StreamShortcuts.sendWin(viewModel::sendInput) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                    ) {
                                        Text("Win", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { StreamShortcuts.sendAltTab(viewModel::sendInput) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                    ) {
                                        Text("Alt+Tab", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { StreamShortcuts.sendCtrlShiftEsc(viewModel::sendInput) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                    ) {
                                        Text("Ctrl+Shift+Esc", fontSize = 11.sp)
                                    }
                                }
                            }
                            uiState.clipboardState.lastError?.let { clipErr ->
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(text = clipErr, fontSize = 10.sp, color = RoseError)
                            } ?: if (uiState.clipboardState.lastSyncEpochMs > 0L) {
                                Spacer(modifier = Modifier.height(3.dp))
                                val dir = if (uiState.clipboardState.lastSource == ClipboardSource.HOST_REMOTE) {
                                    "Copied from PC"
                                } else {
                                    "Sent to PC"
                                }
                                Text(text = "Clipboard: $dir", fontSize = 10.sp, color = EmeraldAccent)
                            } else {
                                Unit
                            }
                        }
                    }
                }
            }

            // Status / pairing / connect overlay
            if (status != StreamConnectionStatus.Connected ||
                streamState.activeTransport == StreamTransportMode.EXTERNAL_NATIVE
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        if (status == StreamConnectionStatus.Pairing) {
                            Text(
                                text = "Pair with ${streamState.targetHostIp}",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = streamState.pairingPin.orEmpty().toCharArray().joinToString(" "),
                                color = CyanPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 40.sp,
                            )
                            Text(
                                text = "Enter this PIN in Sunshine/Vibepollo on the host (https://localhost:47990 > PIN). Waiting for the host...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                text = when (status) {
                                    StreamConnectionStatus.Connected -> "Streaming runs in Moonlight / Artemis. Tap Launch to open it."
                                    StreamConnectionStatus.Connecting ->
                                        "Connecting: ${streamState.stage ?: "starting"}..."
                                    StreamConnectionStatus.Reconnecting -> "Reconnecting Moonlight Stream..."
                                    StreamConnectionStatus.DataExpired ->
                                        if (streamState.isReauthenticating) {
                                            "DEK Expired. Re-authenticating..."
                                        } else {
                                            "DEK Expired. Sign in again, then tap Start Stream."
                                        }
                                    StreamConnectionStatus.Error -> "Stream failed: ${streamState.errorMessage}"
                                    StreamConnectionStatus.NotPaired ->
                                        "Not paired with ${streamState.targetHostIp}. Tap Start Stream to pair."
                                    StreamConnectionStatus.Pairing -> ""
                                    StreamConnectionStatus.Disconnected ->
                                        streamState.errorMessage
                                            ?: "Moonlight Ultra-Low Latency Desktop Stream (${uiState.config.hostIp} · ${uiState.config.resolution.label})"
                                },
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (status) {
                                StreamConnectionStatus.Pairing -> {
                                    OutlinedButton(onClick = { viewModel.cancelPairing() }) {
                                        Text("Cancel pairing")
                                    }
                                }
                                StreamConnectionStatus.Connecting -> {
                                    OutlinedButton(onClick = { viewModel.disconnect() }) {
                                        Text("Cancel")
                                    }
                                }
                                StreamConnectionStatus.Connected -> {
                                    Button(
                                        onClick = { viewModel.disconnect() },
                                        colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Disconnect")
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = { viewModel.connect() },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Start Stream")
                                    }
                                }
                            }

                            // Secondary option: hand off to the standalone Moonlight / Artemis app.
                            OutlinedButton(
                                onClick = {
                                    val intent = viewModel.launchNativeMoonlight(context)
                                    context.startActivity(intent)
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Launch in Artemis")
                            }
                        }

                        if (status == StreamConnectionStatus.Error || status == StreamConnectionStatus.Disconnected) {
                            OutlinedButton(onClick = { viewModel.forgetPairing() }) {
                                Text("Forget pairing", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Pre-stream Telemetry HUD Pill (consolidated into TopCenter pill while streaming).
            if (!isActivelyStreaming) {
                StreamHudOverlay(
                    telemetry = uiState.telemetry,
                    status = uiState.streamState.status,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                )
            }
        }

        if (!isActivelyStreaming) {
        HostAppPicker(viewModel = viewModel, uiState = uiState)

        // Control Bar (Resolution, Codec, Transport)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Resolution dropdown
                ExposedDropdownMenuBox(
                    expanded = resolutionExpanded,
                    onExpandedChange = { resolutionExpanded = !resolutionExpanded },
                    modifier = Modifier.width(210.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.config.resolution.label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Resolution") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = resolutionExpanded,
                        onDismissRequest = { resolutionExpanded = false },
                    ) {
                        StreamResolution.entries.forEach { resolution ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(resolution.label, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${resolution.width}x${resolution.height} · ${resolution.recommendedBitrateKbps / 1000} Mbps",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setResolution(resolution)
                                    resolutionExpanded = false
                                },
                            )
                        }
                    }
                }

                // Codec selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Codec:", style = MaterialTheme.typography.labelSmall)
                    StreamCodec.entries.forEach { codec ->
                        FilterChip(
                            selected = uiState.config.codec == codec,
                            onClick = { viewModel.setCodec(codec) },
                            label = { Text(codec.name, fontSize = 11.sp) },
                            modifier = Modifier.height(30.dp),
                        )
                    }
                }

                // Transport mode selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Path:", style = MaterialTheme.typography.labelSmall)
                    FilterChip(
                        selected = uiState.config.transportMode == StreamTransportMode.AUTO,
                        onClick = { viewModel.setTransportMode(StreamTransportMode.AUTO) },
                        label = { Text("AUTO", fontSize = 11.sp) },
                        modifier = Modifier.height(30.dp),
                    )
                    FilterChip(
                        selected = uiState.config.transportMode == StreamTransportMode.DIRECT_UDP,
                        onClick = { viewModel.setTransportMode(StreamTransportMode.DIRECT_UDP) },
                        label = { Text("UDP", fontSize = 11.sp) },
                        modifier = Modifier.height(30.dp),
                    )
                    FilterChip(
                        selected = uiState.config.transportMode == StreamTransportMode.WEBSOCKET_443,
                        onClick = { viewModel.setTransportMode(StreamTransportMode.WEBSOCKET_443) },
                        label = { Text("WS 443", fontSize = 11.sp) },
                        modifier = Modifier.height(30.dp),
                    )
                    val (capText, capColor) = when (val cap = uiState.wsCapabilityStatus) {
                        WebSocketCapabilityStatus.Unknown -> "WS 443: ready to probe" to MaterialTheme.colorScheme.onSurfaceVariant
                        WebSocketCapabilityStatus.Checking -> "WS 443: probing..." to AmberAccent
                        is WebSocketCapabilityStatus.Available -> {
                            val hostSuffix = cap.hostName?.let { " ($it)" } ?: ""
                            "WS 443: available$hostSuffix" to EmeraldAccent
                        }
                        WebSocketCapabilityStatus.AuthRequired -> "WS 443: auth required" to AmberAccent
                        is WebSocketCapabilityStatus.Unavailable -> "WS 443: ${cap.reason}" to RoseError
                    }
                    Text(capText, style = MaterialTheme.typography.labelSmall, color = capColor)
                }
            }
        }

        // Vibepollo Shared Clipboard Toolbar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Vibepollo Clipboard (${uiState.config.hostIp})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = "Auto-send Aura copies to PC while streaming · Manual buttons copy from / send to Windows",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Switch(
                            checked = uiState.clipboardState.autoSyncEnabled,
                            onCheckedChange = { viewModel.toggleAutoSyncClipboard(it) },
                            modifier = Modifier.height(24.dp),
                            thumbContent = {
                                if (uiState.clipboardState.isSyncing) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(12.dp))
                                }
                            },
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.pullClipboard() },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy from PC", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { viewModel.pushLocalClipboard() },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Send to PC", fontSize = 11.sp)
                        }
                    }
                }
                uiState.clipboardState.lastError?.let { clipErr ->
                    Text(
                        text = clipErr,
                        style = MaterialTheme.typography.labelSmall,
                        color = RoseError,
                    )
                }
            }
        }
        }
    }
}

@Composable
fun StreamHudOverlay(
    telemetry: StreamTelemetry,
    status: StreamConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (status) {
        StreamConnectionStatus.Connected -> EmeraldAccent
        StreamConnectionStatus.Connecting -> AmberAccent
        StreamConnectionStatus.Pairing -> AmberAccent
        StreamConnectionStatus.NotPaired -> MaterialTheme.colorScheme.outline
        StreamConnectionStatus.Reconnecting -> AmberAccent
        StreamConnectionStatus.DataExpired -> RoseError
        StreamConnectionStatus.Disconnected -> MaterialTheme.colorScheme.outline
        StreamConnectionStatus.Error -> RoseError
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.65f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            // Zero means "no measured data": show a dash instead of a number.
            val fpsText = if (telemetry.fps > 0.0) "${telemetry.fps.toInt()} FPS" else "-- FPS"
            val bitrateText = if (telemetry.bitrateKbps > 0) "${telemetry.bitrateKbps / 1000} Mbps" else "-- Mbps"
            val rttText = if (telemetry.rttMs > 0) "${telemetry.rttMs} ms" else "-- ms"
            val audioSuffix = when (val audio = telemetry.audioState) {
                is WebSocketAudioState.Muted -> " · ${audio.reason}"
                else -> ""
            }
            Text(
                text = "${telemetry.activeTransport.name} · $fpsText · $bitrateText · $rttText · ${telemetry.activeCodec.name}$audioSuffix",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color(0xFFE2E8F0),
            )
        }
    }
}

/** Session states in which a decoder may render, so the video SurfaceView must exist. */
private val SURFACE_STATUSES = setOf(
    StreamConnectionStatus.Connecting,
    StreamConnectionStatus.Connected,
    StreamConnectionStatus.Reconnecting,
    StreamConnectionStatus.DataExpired,
)

/** Pre-stream app / virtual-display picker backed by the host's `/applist`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostAppPicker(viewModel: StreamViewModel, uiState: StreamUiState) {
    var expanded by remember { mutableStateOf(false) }
    val picker = uiState.appPicker
    val config = uiState.config
    val catalog = picker.catalog
    val running = catalog?.runningApp
    val selectedId = config.appId
    val otherAppRunning = catalog != null && catalog.currentGame != 0 && catalog.currentGame != selectedId
    val display = catalog?.virtualDisplay

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded && picker.apps.isNotEmpty() },
                    modifier = Modifier.width(260.dp),
                ) {
                    OutlinedTextField(
                        value = config.appTitle,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("App / virtual display") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        picker.apps.forEach { app ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (app.id == catalog?.currentGame) "${app.title} (running)" else app.title)
                                },
                                onClick = {
                                    viewModel.selectApp(app)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.refreshApps() },
                    enabled = picker.status != AppListStatus.Loading,
                ) { Text(if (picker.status == AppListStatus.Loading) "Loading..." else "Refresh") }
            }

            if (otherAppRunning && running != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.selectApp(running) },
                        label = { Text("Resume ${running.title}", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = config.replaceRunningApp,
                        onClick = { viewModel.setReplaceRunningApp(!config.replaceRunningApp) },
                        label = { Text("Replace & Launch", fontSize = 11.sp) },
                    )
                }
            }

            val note = when (picker.status) {
                AppListStatus.Error -> "App list unavailable: ${picker.message}. Streaming ${config.appTitle}."
                AppListStatus.Unpaired -> picker.message
                AppListStatus.Loaded ->
                    if (picker.apps.isEmpty()) {
                        "Host has no apps to stream"
                    } else if (display != null && display.capable) {
                        "Virtual display ${if (display.driverReady) "ready" else "driver not ready"}" +
                            if (display.hdrCapable) " · HDR capable" else ""
                    } else {
                        null
                    }
                else -> null
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (picker.status == AppListStatus.Error || picker.apps.isEmpty() && picker.status == AppListStatus.Loaded) {
                        RoseError
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
