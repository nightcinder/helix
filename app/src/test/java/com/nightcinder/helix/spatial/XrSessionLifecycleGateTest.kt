package com.nightcinder.helix.spatial

import com.nightcinder.helix.core.spatial.XrSessionGateState
import com.nightcinder.helix.core.spatial.XrSessionLifecycleGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrSessionLifecycleGateTest {

    @Test
    fun initialState_defaultsToAwaitingPermissions() {
        val gate = XrSessionLifecycleGate()
        assertTrue(gate.isAvailable)
        assertFalse(gate.hasRequiredPermissions)
        assertFalse(gate.isResumed)
        assertNull(gate.lastErrorMessage)
        assertEquals(XrSessionGateState.AWAITING_PERMISSIONS, gate.currentState)
        assertFalse(gate.canCreateSession())
    }

    @Test
    fun initialState_whenUnavailable_isUnavailable() {
        val gate = XrSessionLifecycleGate(initialAvailable = false)
        assertFalse(gate.isAvailable)
        assertEquals(XrSessionGateState.UNAVAILABLE, gate.currentState)
        assertFalse(gate.canCreateSession())
    }

    @Test
    fun permissionsGranted_whileNotResumed_awaitsResumed() {
        val gate = XrSessionLifecycleGate()
        val newState = gate.onPermissionsChanged(true)
        assertTrue(gate.hasRequiredPermissions)
        assertEquals(XrSessionGateState.AWAITING_RESUMED, newState)
        assertEquals(XrSessionGateState.AWAITING_RESUMED, gate.currentState)
        assertFalse(gate.canCreateSession())
    }

    @Test
    fun bothPermissionsAndResumed_transitionsToReady() {
        val gate = XrSessionLifecycleGate()
        gate.onPermissionsChanged(true)
        val newState = gate.onLifecycleChanged(true)

        assertTrue(gate.hasRequiredPermissions)
        assertTrue(gate.isResumed)
        assertEquals(XrSessionGateState.READY, newState)
        assertEquals(XrSessionGateState.READY, gate.currentState)
        assertTrue(gate.canCreateSession())
    }

    @Test
    fun paused_fromReady_transitionsToAwaitingResumed() {
        val gate = XrSessionLifecycleGate(
            initialAvailable = true,
            initialPermissionsGranted = true,
            initialResumed = true,
        )
        assertTrue(gate.canCreateSession())

        val stateAfterPause = gate.onLifecycleChanged(false)
        assertFalse(gate.isResumed)
        assertEquals(XrSessionGateState.AWAITING_RESUMED, stateAfterPause)
        assertFalse(gate.canCreateSession())
    }

    @Test
    fun permissionsRevoked_fromReady_transitionsToAwaitingPermissions() {
        val gate = XrSessionLifecycleGate(
            initialAvailable = true,
            initialPermissionsGranted = true,
            initialResumed = true,
        )
        assertTrue(gate.canCreateSession())

        val stateAfterRevocation = gate.onPermissionsChanged(false)
        assertFalse(gate.hasRequiredPermissions)
        assertEquals(XrSessionGateState.AWAITING_PERMISSIONS, stateAfterRevocation)
        assertFalse(gate.canCreateSession())
    }

    @Test
    fun sessionError_transitionsToError_andClearErrorRecovers() {
        val gate = XrSessionLifecycleGate(
            initialAvailable = true,
            initialPermissionsGranted = true,
            initialResumed = true,
        )
        assertTrue(gate.canCreateSession())

        val errState = gate.onSessionError("XR Session Failed: device disconnected")
        assertEquals(XrSessionGateState.ERROR, errState)
        assertEquals("XR Session Failed: device disconnected", gate.lastErrorMessage)
        assertFalse(gate.canCreateSession())

        val recoveredState = gate.clearError()
        assertNull(gate.lastErrorMessage)
        assertEquals(XrSessionGateState.READY, recoveredState)
        assertTrue(gate.canCreateSession())
    }

    @Test
    fun availabilityChangedToFalse_overridesReadyState() {
        val gate = XrSessionLifecycleGate(
            initialAvailable = true,
            initialPermissionsGranted = true,
            initialResumed = true,
        )
        assertTrue(gate.canCreateSession())

        val stateAfterUnavailable = gate.onAvailabilityChanged(false)
        assertEquals(XrSessionGateState.UNAVAILABLE, stateAfterUnavailable)
        assertFalse(gate.canCreateSession())
    }
}
