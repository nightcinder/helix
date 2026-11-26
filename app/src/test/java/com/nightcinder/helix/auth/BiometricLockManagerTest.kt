package com.nightcinder.helix.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BiometricLockManagerTest {

    private class FakeStore(
        var prefs: BiometricLockPrefs = BiometricLockPrefs(enabled = true, timeoutMinutes = 5),
    ) : BiometricLockStore {
        override suspend fun read(): BiometricLockPrefs = prefs
        override suspend fun write(prefs: BiometricLockPrefs) {
            this.prefs = prefs
        }
    }

    @Test
    fun locksAfterTimeoutElapsesInBackground() = runTest {
        var now = 1_000_000L
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = BiometricLockManager(
            store = FakeStore(BiometricLockPrefs(enabled = true, timeoutMinutes = 5)),
            scope = scope,
            deviceSupportsAuth = { true },
            clock = { now },
        )
        scope.advanceUntilIdle()

        manager.onActivityStarted(isAuthenticated = true)
        assertFalse(manager.state.value.isLocked)

        manager.onActivityStopped(isAuthenticated = true)
        now += 5 * 60_000L
        manager.onActivityStarted(isAuthenticated = true)

        assertTrue(manager.state.value.isLocked)
    }

    @Test
    fun staysUnlockedWhenReturningBeforeTimeout() = runTest {
        var now = 1_000_000L
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = BiometricLockManager(
            store = FakeStore(BiometricLockPrefs(enabled = true, timeoutMinutes = 5)),
            scope = scope,
            deviceSupportsAuth = { true },
            clock = { now },
        )
        scope.advanceUntilIdle()

        manager.onActivityStarted(isAuthenticated = true)
        manager.onActivityStopped(isAuthenticated = true)
        now += 4 * 60_000L + 59_000L
        manager.onActivityStarted(isAuthenticated = true)

        assertFalse(manager.state.value.isLocked)
        assertNull(manager.state.value.lastBackgroundEpochMs)
    }

    @Test
    fun multiWindowDetachedActivityKeepsSessionUnlocked() = runTest {
        var now = 1_000_000L
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = BiometricLockManager(
            store = FakeStore(BiometricLockPrefs(enabled = true, timeoutMinutes = 0)),
            scope = scope,
            deviceSupportsAuth = { true },
            clock = { now },
        )
        scope.advanceUntilIdle()

        manager.onActivityStarted(isAuthenticated = true) // MainActivity
        manager.onActivityStarted(isAuthenticated = true) // DetachedPanelActivity
        manager.onActivityStopped(isAuthenticated = true) // MainActivity stops while detached stays visible
        assertNull(manager.state.value.lastBackgroundEpochMs)

        now += 10 * 60_000L
        manager.onActivityStarted(isAuthenticated = true)
        assertFalse(manager.state.value.isLocked)
    }

    @Test
    fun immediateTimeoutZeroLocksEvenOnShortRoundTrip() = runTest {
        var now = 1_000_000L
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = BiometricLockManager(
            store = FakeStore(BiometricLockPrefs(enabled = true, timeoutMinutes = 0)),
            scope = scope,
            deviceSupportsAuth = { true },
            clock = { now },
        )
        scope.advanceUntilIdle()

        manager.onActivityStarted(isAuthenticated = true)
        manager.onActivityStopped(isAuthenticated = true)
        now += 500L
        manager.onActivityStarted(isAuthenticated = true)

        assertTrue(manager.state.value.isLocked)
    }

    @Test
    fun neverLocksWhenDeviceHasNoAuthOrUserSignedOut() = runTest {
        var now = 1_000_000L
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val noAuthMgr = BiometricLockManager(
            store = FakeStore(BiometricLockPrefs(enabled = true, timeoutMinutes = 0)),
            scope = scope,
            deviceSupportsAuth = { false },
            clock = { now },
        )
        scope.advanceUntilIdle()

        noAuthMgr.onActivityStarted(isAuthenticated = true)
        noAuthMgr.onActivityStopped(isAuthenticated = true)
        now += 600_000L
        noAuthMgr.onActivityStarted(isAuthenticated = true)
        noAuthMgr.lockNow(isAuthenticated = true)
        assertFalse(noAuthMgr.state.value.isLocked)

        val signedOutMgr = BiometricLockManager(
            store = FakeStore(BiometricLockPrefs(enabled = true, timeoutMinutes = 0)),
            scope = scope,
            deviceSupportsAuth = { true },
            clock = { now },
        )
        scope.advanceUntilIdle()
        signedOutMgr.onActivityStarted(isAuthenticated = false)
        signedOutMgr.onActivityStopped(isAuthenticated = false)
        now += 600_000L
        signedOutMgr.onActivityStarted(isAuthenticated = false)
        signedOutMgr.lockNow(isAuthenticated = false)
        assertFalse(signedOutMgr.state.value.isLocked)
    }

    @Test
    fun promptInFlightDuringDeviceCredentialRoundTripDoesNotRelockAfterSuccess() = runTest {
        var now = 1_000_000L
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = BiometricLockManager(
            store = FakeStore(BiometricLockPrefs(enabled = true, timeoutMinutes = 0)),
            scope = scope,
            deviceSupportsAuth = { true },
            clock = { now },
        )
        scope.advanceUntilIdle()

        manager.lockNow(isAuthenticated = true)
        assertTrue(manager.state.value.isLocked)

        manager.setPromptInFlight(true)
        manager.onActivityStopped(isAuthenticated = true)
        now += 5_000L
        manager.onAuthenticated()
        manager.onActivityStarted(isAuthenticated = true)

        assertFalse(manager.state.value.isLocked)
        assertNull(manager.state.value.lastError)
    }

    @Test
    fun disableOrSignOutUnlocksAndPersistsSettings() = runTest {
        val store = FakeStore()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = BiometricLockManager(
            store = store,
            scope = scope,
            deviceSupportsAuth = { true },
        )
        scope.advanceUntilIdle()

        manager.lockNow(isAuthenticated = true)
        manager.onAuthError("Cancelled")
        assertEquals("Cancelled", manager.state.value.lastError)

        manager.setTimeoutMinutes(15)
        manager.setEnabled(false)
        scope.advanceUntilIdle()

        assertFalse(manager.state.value.isLocked)
        assertFalse(manager.state.value.enabled)
        assertEquals(15, store.prefs.timeoutMinutes)
        assertFalse(store.prefs.enabled)

        manager.setEnabled(true)
        manager.lockNow(isAuthenticated = true)
        assertTrue(manager.state.value.isLocked)

        manager.onSignOut()
        assertFalse(manager.state.value.isLocked)
        assertNull(manager.state.value.lastError)
    }
}
