package com.nightcinder.helix.ui.stream

import com.nightcinder.helix.core.model.StreamInputEvent.MouseButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamInputMouseTest {

    private val left = MouseButton.BUTTON_LEFT
    private val right = MouseButton.BUTTON_RIGHT
    private val middle = MouseButton.BUTTON_MIDDLE
    private val x1 = MouseButton.BUTTON_X1
    private val x2 = MouseButton.BUTTON_X2

    @Test
    fun pressAndReleaseOfOneButton() {
        assertEquals(listOf(MouseButton(left, true)), MouseButtonDiff.diff(0, MouseButtonDiff.PRIMARY))
        assertEquals(listOf(MouseButton(left, false)), MouseButtonDiff.diff(MouseButtonDiff.PRIMARY, 0))
        assertTrue(MouseButtonDiff.diff(MouseButtonDiff.PRIMARY, MouseButtonDiff.PRIMARY).isEmpty())
    }

    @Test
    fun chordReleasesBeforePressesIncludingSideButtons() {
        val events = MouseButtonDiff.diff(
            MouseButtonDiff.PRIMARY or MouseButtonDiff.BACK,
            MouseButtonDiff.SECONDARY or MouseButtonDiff.TERTIARY or MouseButtonDiff.FORWARD,
        )
        assertEquals(
            listOf(
                MouseButton(left, false),
                MouseButton(x1, false),
                MouseButton(right, true),
                MouseButton(middle, true),
                MouseButton(x2, true),
            ),
            events,
        )
    }

    @Test
    fun maskMapsComposeButtonsIncludingSideButtons() {
        assertEquals(0, MouseButtonDiff.mask(primary = false, secondary = false, tertiary = false))
        assertEquals(7, MouseButtonDiff.mask(primary = true, secondary = true, tertiary = true))
        assertEquals(MouseButtonDiff.SECONDARY, MouseButtonDiff.mask(primary = false, secondary = true, tertiary = false))
        assertEquals(
            MouseButtonDiff.BACK or MouseButtonDiff.FORWARD,
            MouseButtonDiff.mask(primary = false, secondary = false, tertiary = false, back = true, forward = true),
        )
    }

    @Test
    fun nextNeverPromotesZeroMaskAndReleasesOnExit() {
        assertEquals(0, MouseButtonDiff.next(0, 0, stillPressed = true))
        assertEquals(MouseButtonDiff.PRIMARY, MouseButtonDiff.next(MouseButtonDiff.PRIMARY, 0, stillPressed = true))
        assertEquals(0, MouseButtonDiff.next(MouseButtonDiff.PRIMARY, MouseButtonDiff.PRIMARY, stillPressed = false))
        assertEquals(
            0,
            MouseButtonDiff.next(
                MouseButtonDiff.PRIMARY or MouseButtonDiff.SECONDARY,
                MouseButtonDiff.PRIMARY or MouseButtonDiff.SECONDARY,
                stillPressed = true,
                exited = true,
            ),
        )
        assertEquals(MouseButtonDiff.SECONDARY, MouseButtonDiff.next(MouseButtonDiff.PRIMARY, MouseButtonDiff.SECONDARY, stillPressed = true))
    }

    @Test
    fun wheelUnitsVerticalAndHorizontalAreScaledWithDeadzone() {
        assertEquals(-120, MouseButtonDiff.wheelUnits(1f)) // Compose: positive = down
        assertEquals(240, MouseButtonDiff.wheelUnits(-2f))
        assertEquals(Short.MAX_VALUE.toInt(), MouseButtonDiff.wheelUnits(-1000f))
        assertEquals(0, MouseButtonDiff.wheelUnits(0.0005f))

        assertEquals(120, MouseButtonDiff.horizontalWheelUnits(1f)) // Compose: positive = right
        assertEquals(-240, MouseButtonDiff.horizontalWheelUnits(-2f))
        assertEquals(Short.MAX_VALUE.toInt(), MouseButtonDiff.horizontalWheelUnits(1000f))
        assertEquals(0, MouseButtonDiff.horizontalWheelUnits(-0.0005f))
    }

    @Test
    fun dragScrollUnitsNormalizeAcross1200pAnd1600pWithDeadzone() {
        assertEquals(0f, MouseButtonDiff.dragScrollUnits(1.5f, 1200))
        assertEquals(0f, MouseButtonDiff.dragScrollUnits(-1.9f, 1600))
        // 10% of viewport height yields the exact same scroll units at 1200p and 1600p.
        assertEquals(480f, MouseButtonDiff.dragScrollUnits(120f, 1200), 0.01f)
        assertEquals(480f, MouseButtonDiff.dragScrollUnits(160f, 1600), 0.01f)
    }
}
