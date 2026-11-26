package com.nightcinder.helix.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nightcinder.helix.media.MediaItem
import com.nightcinder.helix.media.MediaProvider
import com.nightcinder.helix.media.MediaUiState
import com.nightcinder.helix.media.MediaViewModel
import com.nightcinder.helix.media.PlaybackState
import com.nightcinder.helix.media.SpatialFinState
import com.nightcinder.helix.ui.theme.CyanPrimary
import kotlinx.coroutines.delay

/** Provided by MainActivity; null in detached panel windows. */
val LocalMediaViewModel = staticCompositionLocalOf<MediaViewModel?> { null }

/**
 * Library + transport controls for the native Media3 player. Opaque `#0A0F14` (no SurfaceView hole-punch). Video
 * itself renders in a separate `SpatialExternalSurface` owned by the cockpit.
 */
@Composable
fun MediaPanel(
    viewModel: MediaViewModel,
    spatialFin: SpatialFinState,
    onOpenSpatialFin: () -> Unit,
    onOpenSpatialFinStandalone: () -> Unit,
    onReloadSpatialFin: () -> Unit,
    onCloseSpatialFin: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF0A0F14)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Media",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close media panel") }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (state.nowPlaying != null) NowPlaying(viewModel, state)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            item { SignIn(viewModel, state) }
            if (state.continueWatching.isNotEmpty()) {
                item { SectionTitle("Continue Watching") }
                items(state.continueWatching, key = { "${it.provider}:${it.id}" }) { MediaRow(it) { viewModel.play(it) } }
            }
            if (state.upNext.isNotEmpty()) {
                item { SectionTitle("Up Next") }
                items(state.upNext, key = { "n${it.provider}:${it.id}" }) { MediaRow(it) { viewModel.play(it) } }
            }
            item { SpatialFinSection(spatialFin, onOpenSpatialFin, onOpenSpatialFinStandalone, onReloadSpatialFin, onCloseSpatialFin) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = CyanPrimary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun MediaRow(item: MediaItem, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title}", tint = CyanPrimary)
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            val resume = if (item.resumePositionMs > 0) "resume ${formatClock(item.resumePositionMs)}" else null
            val line = listOfNotNull(item.subtitle, resume, if (item.provider == MediaProvider.PLEX) "Plex" else "Jellyfin")
            Text(line.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun SignIn(viewModel: MediaViewModel, state: MediaUiState) {
    val uriHandler = LocalUriHandler.current
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    state.pending?.let { p ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val name = if (p.provider == MediaProvider.PLEX) "Plex" else "Jellyfin"
            Text("$name code: ${p.code}", style = MaterialTheme.typography.titleMedium)
            Text(
                if (p.provider == MediaProvider.PLEX) "Approve this code at plex.tv/link, then return here."
                else "Approve this code under Settings > Quick Connect in a signed-in Jellyfin client.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.url?.let { url -> Button(onClick = { uriHandler.openUri(url) }) { Text("Open Plex sign-in") } }
                OutlinedButton(onClick = viewModel::cancelSignIn) { Text("Cancel") }
            }
        }
        return
    }
    if (!state.jellyfinSignedIn) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Jellyfin")
            Button(onClick = viewModel::startJellyfinQuickConnect) { Text("Sign in with Quick Connect") }
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { viewModel.signInJellyfinPassword(user.trim(), password) }, enabled = user.isNotBlank()) {
                Text("Sign in")
            }
        }
    } else {
        OutlinedButton(onClick = { viewModel.signOut(MediaProvider.JELLYFIN) }) { Text("Sign out of Jellyfin") }
    }
    if (!state.plexSignedIn) {
        Button(onClick = viewModel::startPlexPin) { Text("Sign in with Plex") }
    } else {
        OutlinedButton(onClick = { viewModel.signOut(MediaProvider.PLEX) }) { Text("Sign out of Plex") }
    }
}

@Composable
private fun NowPlaying(viewModel: MediaViewModel, state: MediaUiState) {
    val item = state.nowPlaying ?: return
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(state.playback) {
        while (state.playback is PlaybackState.Playing || state.playback is PlaybackState.Paused) {
            if (!scrubbing) {
                position = viewModel.positionMs().toFloat()
                duration = viewModel.durationMs().toFloat()
            }
            delay(500)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleSmall)
        Text(playbackLabel(state.playback), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = viewModel::togglePlayPause) {
                val playing = state.playback is PlaybackState.Playing
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
            }
            IconButton(onClick = viewModel::stopPlayback) { Icon(Icons.Default.Stop, contentDescription = "Stop") }
            Slider(
                value = position.coerceIn(0f, duration.coerceAtLeast(1f)),
                onValueChange = { scrubbing = true; position = it },
                onValueChangeFinished = { viewModel.seekTo(position.toLong()); scrubbing = false },
                valueRange = 0f..duration.coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
            )
            Text(formatClock(position.toLong()), style = MaterialTheme.typography.bodySmall)
        }
        if (state.tracks.audio.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Audio", style = MaterialTheme.typography.bodySmall)
                state.tracks.audio.forEach { t ->
                    FilterChip(selected = t.selected, onClick = { viewModel.selectAudio(t.id) }, label = { Text(t.label) })
                }
            }
        }
        if (state.tracks.subtitles.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Subtitles", style = MaterialTheme.typography.bodySmall)
                FilterChip(selected = !state.tracks.subtitlesEnabled, onClick = { viewModel.selectSubtitle(null) }, label = { Text("Off") })
                state.tracks.subtitles.forEach { t ->
                    FilterChip(selected = t.selected, onClick = { viewModel.selectSubtitle(t.id) }, label = { Text(t.label) })
                }
            }
        }
    }
}

@Composable
private fun SpatialFinSection(
    state: SpatialFinState,
    onOpen: () -> Unit,
    onStandalone: () -> Unit,
    onReload: () -> Unit,
    onCloseEmbedded: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        SectionTitle("SpatialFin")
        Button(onClick = onOpen) { Text("Open in SpatialFin") }
        when (state) {
            SpatialFinState.NotInstalled ->
                Text("SpatialFin (dev.spatialfin) is not installed on this device.", style = MaterialTheme.typography.bodySmall)
            is SpatialFinState.Rejected -> {
                Text(state.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onStandalone) { Text("Open SpatialFin as its own app") }
            }
            is SpatialFinState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReload) { Text("Reload") }
                OutlinedButton(onClick = onCloseEmbedded) { Text("Close SpatialFin") }
                OutlinedButton(onClick = onStandalone) { Text("Open as own app") }
            }
            SpatialFinState.Closed -> Unit
        }
    }
}

internal fun playbackLabel(state: PlaybackState): String = when (state) {
    PlaybackState.Idle -> "Idle"
    PlaybackState.Loading -> "Loading…"
    PlaybackState.Playing -> "Playing"
    PlaybackState.Paused -> "Paused"
    PlaybackState.Ended -> "Ended"
    is PlaybackState.Error -> "Error: ${state.message}"
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
