package com.nightcinder.helix.ui.auth

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nightcinder.helix.auth.BiometricLockManager
import com.nightcinder.helix.auth.launchBiometricPrompt
import com.nightcinder.helix.ui.theme.CyanPrimary

/** Provides the active [BiometricLockManager] to nested Session screens across all layouts. */
val LocalBiometricLockManager = staticCompositionLocalOf<BiometricLockManager?> { null }

/**
 * Shared activity-level host wrapper that renders [content] and overlays [BiometricLockOverlay]
 * whenever the active session is locked, automatically triggering the OS biometric/credential
 * prompt when [activity] is resumed.
 */
@Composable
fun BiometricLockHost(
    activity: FragmentActivity,
    lockManager: BiometricLockManager,
    isAuthenticated: Boolean,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val lockState = lockManager.state.collectAsStateWithLifecycle().value
    val isLocked = isAuthenticated && lockState.isLocked

    val triggerPrompt: () -> Unit = {
        lockManager.setPromptInFlight(true)
        launchBiometricPrompt(
            activity = activity,
            onSuccess = { lockManager.onAuthenticated() },
            onError = { lockManager.onAuthError(it) },
        )
    }

    LaunchedEffect(isLocked) {
        if (isLocked && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            triggerPrompt()
        }
    }

    CompositionLocalProvider(LocalBiometricLockManager provides lockManager) {
        Box(modifier = modifier.fillMaxSize()) {
            content()
            if (isLocked) {
                BiometricLockOverlay(
                    errorMessage = lockState.lastError,
                    onUnlock = triggerPrompt,
                    onSignOut = {
                        lockManager.onSignOut()
                        onSignOut()
                    },
                )
            }
        }
    }
}

/**
 * Full-screen opaque security gate rendered over all underlying panels/terminals whenever
 * [BiometricLockState.isLocked] is true, preventing shoulder-surfing or unauthorized access
 * to cached SSH shells and Authentik/Termix credentials.
 */
@Composable
fun BiometricLockOverlay(
    errorMessage: String?,
    onUnlock: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = CyanPrimary,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        text = "Project Helix Locked",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Verify biometric or device PIN to resume active SSH, Docker, and homelab sessions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!errorMessage.isNullOrBlank()) {
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onUnlock,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Unlock", fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        OutlinedButton(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sign Out", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
