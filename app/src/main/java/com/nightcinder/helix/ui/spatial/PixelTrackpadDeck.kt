package com.nightcinder.helix.ui.spatial

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.core.model.TermixHost
import com.nightcinder.helix.ui.state.DashboardUiState
import com.nightcinder.helix.ui.state.DashboardViewModel
import com.nightcinder.helix.ui.state.SpatialViewModel
import com.nightcinder.helix.ui.state.SpecialTerminalKey
import com.nightcinder.helix.ui.state.StreamViewModel
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent

/**
 * Pixel Trackpad Deck: True-black OLED phone controller for glasses presentation.
 * Features a 60% touch trackpad, terminal modifier keys, host switcher macros, and 3DoF recenter indicator.
 */
@Composable
fun PixelTrackpadDeck(
    streamViewModel: StreamViewModel,
    terminalViewModel: TerminalViewModel,
    dashboardViewModel: DashboardViewModel,
    spatialViewModel: SpatialViewModel,
    dashboardUiState: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Top Bar: Recenter indicator & Host Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.TouchApp, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                Text("PIXEL DECK", color = CyanPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            // Recenter 3DoF Indicator / Button
            OutlinedButton(
                onClick = { spatialViewModel.recenter3Dof() },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Recenter (Vol-Hold)", fontSize = 10.sp)
            }
        }

        // Host Switcher Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            dashboardUiState.hosts.forEach { host ->
                val isSelected = host.id == dashboardUiState.selectedHostId
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            dashboardViewModel.selectHost(host.id)
                            streamViewModel.selectHost(host)
                        },
                    color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = host.displayName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.Black else Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // 60% Touch Trackpad Area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dx = (dragAmount.x * 1.5f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        val dy = (dragAmount.y * 1.5f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        streamViewModel.sendInput(StreamInputEvent.MouseMove(dx, dy))
                    }
                },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070B0E)),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.3f)),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "SWIPE TO MOVE CURSOR\nTAP BUTTONS BELOW TO CLICK",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        // Mouse Click Buttons (Left / Middle / Right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    streamViewModel.sendInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, true))
                    streamViewModel.sendInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, false))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Left Click", fontSize = 11.sp)
            }

            Button(
                onClick = {
                    streamViewModel.sendInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_MIDDLE, true))
                    streamViewModel.sendInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_MIDDLE, false))
                },
                modifier = Modifier.weight(0.7f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Middle", fontSize = 11.sp)
            }

            Button(
                onClick = {
                    streamViewModel.sendInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_RIGHT, true))
                    streamViewModel.sendInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_RIGHT, false))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Right Click", fontSize = 11.sp)
            }
        }

        // Terminal Modifiers Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModifierKey("ESC") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.ESC) }
            ModifierKey("TAB") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.TAB) }
            ModifierKey("CTRL") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.TOGGLE_CTRL) }
            ModifierKey("ALT") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.TOGGLE_ALT) }
            ModifierKey("CTRL+C") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.CTRL_C) }
            ModifierKey("↑") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.UP) }
            ModifierKey("↓") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.DOWN) }
            ModifierKey("←") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.LEFT) }
            ModifierKey("→") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.RIGHT) }
            ModifierKey("TMUX") { terminalViewModel.sendSpecialKey(SpecialTerminalKey.TMUX_ATTACH) }
        }
    }
}

@Composable
fun ModifierKey(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
