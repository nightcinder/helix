package com.nightcinder.helix.ui.auth

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.auth.BiometricLockState
import com.nightcinder.helix.core.model.AuthComponentState
import com.nightcinder.helix.core.model.AuthSessionState
import com.nightcinder.helix.core.model.AuthentikConfig
import com.nightcinder.helix.core.model.HostAuthStatus
import com.nightcinder.helix.ui.layout.CockpitLayoutMode
import com.nightcinder.helix.ui.layout.LocalCockpitLayoutManager
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError

@Composable
fun AuthScreen(
    sessionState: AuthSessionState,
    isLoading: Boolean,
    errorMessage: String?,
    onSignInPasskey: (AuthentikConfig?) -> Unit,
    onSilentRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onUpdateConfig: (AuthentikConfig) -> Unit,
    modifier: Modifier = Modifier,
    biometricLockState: BiometricLockState? = null,
    onToggleBiometricLock: (Boolean) -> Unit = {},
    onSelectBiometricTimeout: (Int) -> Unit = {},
    onLockNow: () -> Unit = {},
    layoutMode: CockpitLayoutMode? = null,
    onSelectLayoutMode: (CockpitLayoutMode) -> Unit = {},
) {
    var showConfigDialog by remember { mutableStateOf(false) }
    var authentikUrlInput by remember(sessionState.config.authentikBaseUrl) {
        mutableStateOf(sessionState.config.authentikBaseUrl)
    }
    var termixUrlInput by remember(sessionState.config.termixBaseUrl) {
        mutableStateOf(sessionState.config.termixBaseUrl)
    }
    var moonlightUrlInput by remember(sessionState.config.moonlightBaseUrl) {
        mutableStateOf(sessionState.config.moonlightBaseUrl)
    }
    var prometheusUrlInput by remember(sessionState.config.prometheusBaseUrl) {
        mutableStateOf(sessionState.config.prometheusBaseUrl)
    }
    var grafanaUrlInput by remember(sessionState.config.grafanaBaseUrl) {
        mutableStateOf(sessionState.config.grafanaBaseUrl)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Project Helix Branded Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "PROJECT HELIX",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        ),
                        color = CyanPrimary,
                    )
                    Text(
                        text = "Homelab Cockpit & Spatial Operations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Passkey Action Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (sessionState.isAuthenticated) "Authenticated Session" else "Hardware Passkey Required",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = if (sessionState.isAuthenticated) {
                            "Logged in as ${sessionState.username ?: "Cockpit Operator"} with unlocked Termix DEK."
                        } else {
                            "Hardware Passkey (WebAuthn / FIDO2) via Authentik SSO flow unlocks Termix & Moonlight services."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (errorMessage != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    if (!sessionState.isAuthenticated) {
                        Button(
                            onClick = { onSignInPasskey(null) },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanPrimary,
                                contentColor = Color.Black,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Sign in with Passkey",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = onSilentRefresh,
                                enabled = !isLoading,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refresh Outpost")
                            }
                            Button(
                                onClick = onSignOut,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Sign Out")
                            }
                        }
                    }
                }
            }

            // Live Diagnostics Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Session Diagnostics & Proxy Outposts",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    // Authentik SSO Pill
                    HostStatusRow(hostStatus = sessionState.authentikStatus)

                    // Termix Proxy Pill
                    HostStatusRow(hostStatus = sessionState.termixStatus)

                    // Moonlight Proxy Pill
                    HostStatusRow(hostStatus = sessionState.moonlightStatus)

                    // Termix DEK Unlock Status Pill
                    DekStatusRow(
                        isUnlocked = sessionState.isDekUnlocked,
                        dataStatusDetail = sessionState.dataStatus?.code ?: sessionState.dataStatus?.status,
                    )
                }
            }

            // Server URLs & Overrides Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Server Endpoints",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        OutlinedButton(
                            onClick = { showConfigDialog = !showConfigDialog },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (showConfigDialog) "Hide" else "Edit")
                        }
                    }

                    if (!showConfigDialog) {
                        Text(
                            text = "Authentik: ${sessionState.config.authentikBaseUrl}\nTermix: ${sessionState.config.termixBaseUrl}\nMoonlight: ${sessionState.config.moonlightBaseUrl}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = authentikUrlInput,
                            onValueChange = { authentikUrlInput = it },
                            label = { Text("Authentik URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = termixUrlInput,
                            onValueChange = { termixUrlInput = it },
                            label = { Text("Termix URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = moonlightUrlInput,
                            onValueChange = { moonlightUrlInput = it },
                            label = { Text("Moonlight URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = prometheusUrlInput,
                            onValueChange = { prometheusUrlInput = it },
                            label = { Text("Prometheus URL (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = grafanaUrlInput,
                            onValueChange = { grafanaUrlInput = it },
                            label = { Text("Grafana URL (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                onUpdateConfig(
                                    sessionState.config.copy(
                                        authentikBaseUrl = authentikUrlInput.trim(),
                                        termixBaseUrl = termixUrlInput.trim(),
                                        moonlightBaseUrl = moonlightUrlInput.trim(),
                                        prometheusBaseUrl = prometheusUrlInput.trim(),
                                        grafanaBaseUrl = grafanaUrlInput.trim(),
                                    )
                                )
                                showConfigDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Save Endpoints")
                        }
                    }
                }
            }

            val envLayoutManager = LocalCockpitLayoutManager.current
            val resolvedLayoutMode = layoutMode
                ?: envLayoutManager?.mode?.collectAsStateWithLifecycle()?.value
                ?: CockpitLayoutMode.PANELS
            CockpitLayoutCard(
                selectedMode = resolvedLayoutMode,
                onSelectMode = { mode ->
                    if (layoutMode != null) onSelectLayoutMode(mode)
                    else envLayoutManager?.setMode(mode)
                },
            )

            val envLockManager = LocalBiometricLockManager.current
            val resolvedLockState = biometricLockState ?: envLockManager?.state?.collectAsStateWithLifecycle()?.value
            if (sessionState.isAuthenticated && resolvedLockState != null) {
                SessionLockCard(
                    state = resolvedLockState,
                    onToggleEnabled = { enabled ->
                        if (biometricLockState != null) onToggleBiometricLock(enabled)
                        else envLockManager?.setEnabled(enabled)
                    },
                    onSelectTimeout = { mins ->
                        if (biometricLockState != null) onSelectBiometricTimeout(mins)
                        else envLockManager?.setTimeoutMinutes(mins)
                    },
                    onLockNow = {
                        if (biometricLockState != null) onLockNow()
                        else envLockManager?.lockNow(true)
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private val LOCK_TIMEOUT_OPTIONS = listOf(0, 1, 5, 15, 30)

@Composable
private fun CockpitLayoutCard(
    selectedMode: CockpitLayoutMode,
    onSelectMode: (CockpitLayoutMode) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Cockpit Layout",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "Switch between Panels (Default) and Tree without tearing down active streams or SSH tabs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedMode == CockpitLayoutMode.PANELS,
                    onClick = { onSelectMode(CockpitLayoutMode.PANELS) },
                    label = { Text("Panels (Default)") },
                )
                FilterChip(
                    selected = selectedMode == CockpitLayoutMode.TREE,
                    onClick = { onSelectMode(CockpitLayoutMode.TREE) },
                    label = { Text("Tree") },
                )
            }
        }
    }
}

@Composable
private fun SessionLockCard(
    state: BiometricLockState,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectTimeout: (Int) -> Unit,
    onLockNow: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Session Lock",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = "Require biometric or device PIN after leaving Helix",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }
            if (state.enabled) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LOCK_TIMEOUT_OPTIONS, key = { it }) { minutes ->
                        val label = if (minutes == 0) "Immediate" else "${minutes}m"
                        FilterChip(
                            selected = state.timeoutMinutes == minutes,
                            onClick = { onSelectTimeout(minutes) },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedButton(
                    onClick = onLockNow,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Lock Now")
                }
            }
        }
    }
}

@Composable
fun HostStatusRow(hostStatus: HostAuthStatus) {
    val (statusColor, statusText) = when (hostStatus.state) {
        AuthComponentState.VALID -> EmeraldAccent to "READY"
        AuthComponentState.REFRESHING -> AmberAccent to "REFRESHING"
        AuthComponentState.EXPIRED -> RoseError to "EXPIRED"
        AuthComponentState.UNAUTHENTICATED -> MaterialTheme.colorScheme.onSurfaceVariant to "DISCONNECTED"
        AuthComponentState.ERROR -> RoseError to "ERROR"
        AuthComponentState.UNKNOWN -> MaterialTheme.colorScheme.outline to "UNKNOWN"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hostStatus.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hostStatus.baseUrl,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = statusColor,
            )
        }
    }
}

@Composable
fun DekStatusRow(isUnlocked: Boolean, dataStatusDetail: String?) {
    val (statusColor, statusText) = if (isUnlocked) {
        EmeraldAccent to "DEK UNLOCKED"
    } else {
        RoseError to (dataStatusDetail ?: "DEK LOCKED / EXPIRED")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp),
            )
            Column {
                Text(
                    text = "Termix Data Encryption Key (DEK)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "GET /users/data-status verification",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = statusColor,
        )
    }
}
