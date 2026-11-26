package com.nightcinder.helix.ui.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nightcinder.helix.spotify.LocalPlayerState
import com.nightcinder.helix.spotify.LocalPlaybackSnapshot
import com.nightcinder.helix.spotify.SpotifyAuthService
import com.nightcinder.helix.spotify.SpotifyAuthState
import com.nightcinder.helix.spotify.SpotifyUiState
import com.nightcinder.helix.spotify.SpotifyViewModel
import com.nightcinder.helix.ui.theme.CyanPrimary

/** Provided by MainActivity; null when Spotify is unavailable (e.g. detached panel windows). */
val LocalSpotifyViewModel = staticCompositionLocalOf<SpotifyViewModel?> { null }

private val PanelBackground = Color(0xFF0A0F14)

/**
 * Compact Spotify mini-player + Connect controls. Opaque `#0A0F14` so it never punches through a Full Space
 * SurfaceView. The same composable serves the Full Space panel and the Home Space / 2D dialog.
 */
@Composable
fun SpotifyPanel(
    viewModel: SpotifyViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }
    val context = LocalContext.current
    val currentClose by rememberUpdatedState(onClose)

    Column(
        modifier = modifier.fillMaxSize().background(PanelBackground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Spotify",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = CyanPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { currentClose() }) {
                Icon(Icons.Default.Close, contentDescription = "Close Spotify")
            }
        }

        state.message?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::dismissMessage) { Text("Dismiss", fontSize = 11.sp) }
            }
        }

        LocalPlayerCard(
            local = state.local,
            onToggle = viewModel::togglePlayPause,
            onNext = viewModel::skipNext,
            onPrevious = viewModel::skipPrevious,
            onSeek = viewModel::seekTo,
            onOpenSettings = { openNotificationAccessSettings(context) },
        )

        when (state.auth) {
            SpotifyAuthState.NotConfigured -> NotConfiguredCard(state, viewModel::setClientIdOverride)
            else -> WebApiSection(
                state = state,
                onSignIn = { viewModel.signIn { url -> openBrowser(context, url) } },
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun LocalPlayerCard(
    local: LocalPlayerState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (local) {
        LocalPlayerState.NeedsNotificationAccess -> StatusRow(
            text = "Allow Notification access so Helix can control Spotify on Aura.",
            action = "Open Settings",
            onAction = onOpenSettings,
        )
        is LocalPlayerState.LocalPlayerUnavailable -> StatusRow(
            text = if (local.installed) "Spotify is not playing on Aura. Start playback in Spotify." else "Spotify is not installed on Aura.",
            action = "Notification access",
            onAction = onOpenSettings,
        )
        is LocalPlayerState.Active -> NowPlaying(local.snapshot, onToggle, onNext, onPrevious, onSeek)
    }
}

@Composable
private fun NowPlaying(
    s: LocalPlaybackSnapshot,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = s.artwork
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = "Album artwork",
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(s.title.ifBlank { "Nothing playing" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.album, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous") }
            IconButton(onClick = onToggle) {
                Icon(
                    if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (s.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
        }
        if (s.durationMs > 0) {
            Slider(
                value = dragging ?: (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek((it * s.durationMs).toLong()) }
                    dragging = null
                },
            )
        }
    }
}

@Composable
private fun StatusRow(text: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onAction) { Text(action, fontSize = 11.sp) }
    }
}

@Composable
private fun NotConfiguredCard(state: SpotifyUiState, onSetOverride: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf(state.clientIdOverride.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(SpotifyAuthService.NOT_CONFIGURED, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Text(
            "Set HELIX_SPOTIFY_CLIENT_ID at build time, or enter your Spotify app's public client id (no secret needed).",
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Spotify client id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSetOverride(input) }) { Text("Save client id") }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WebApiSection(state: SpotifyUiState, onSignIn: () -> Unit, viewModel: SpotifyViewModel) {
    if (!state.isSignedIn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSignIn) {
                Text(if (state.auth == SpotifyAuthState.Authorizing) "Waiting for browser..." else "Connect Spotify")
            }
            (state.auth as? SpotifyAuthState.Failed)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Devices", "Playlists", "Liked Songs")
    TabRow(selectedTabIndex = tab, containerColor = PanelBackground) {
        titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 12.sp) }) }
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (tab) {
            0 -> {
                if (state.devices.isEmpty()) item { Text("No Connect devices available.", fontSize = 12.sp) }
                items(state.devices, key = { it.id }) { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${d.name} (${d.type})" + if (d.isActive) "  • active" else "",
                            modifier = Modifier.weight(1f),
                            color = if (d.isActive) CyanPrimary else Color.Unspecified,
                        )
                        OutlinedButton(onClick = { viewModel.transferTo(d) }, enabled = !d.isActive && !d.isRestricted) {
                            Text("Play here", fontSize = 11.sp)
                        }
                    }
                }
                item { OutlinedButton(onClick = viewModel::refreshDevices) { Text("Refresh devices", fontSize = 11.sp) } }
            }
            1 -> items(state.playlists, key = { it.id }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OutlinedButton(onClick = { viewModel.playPlaylist(p) }) { Text("Play", fontSize = 11.sp) }
                }
            }
            else -> items(state.likedSongs, key = { it.id }) { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artists, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { viewModel.playTrack(t) }) { Text("Play", fontSize = 11.sp) }
                }
            }
        }
    }
    Spacer(Modifier.size(2.dp))
    OutlinedButton(onClick = viewModel::signOut) { Text("Sign out of Spotify", fontSize = 11.sp) }
}

private fun openNotificationAccessSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
