package com.nightcinder.helix.ui.stream

import android.view.InputDevice
import android.view.KeyEvent
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.stream.KeyboardTranslator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamInputLayerTest {

    @Test
    fun modifierTracker_tracksDownUpAndReleasesOnCancelOrReleaseAll() {
        val tracker = ModifierKeyTracker()
        val events = mutableListOf<StreamInputEvent>()

        // Alt down + Tab down
        assertTrue(
            tracker.handleKeyEvent(
                keyCode = 57, // KEYCODE_ALT_LEFT
                isDown = true,
                metaState = 0x2,
                flags = 0,
                source = InputDevice.SOURCE_KEYBOARD,
                onInput = events::add,
            ),
        )
        assertTrue(tracker.hasPressedModifiers())
        assertTrue(
            tracker.handleKeyEvent(
                keyCode = 61, // KEYCODE_TAB
                isDown = true,
                metaState = 0x2,
                flags = 0,
                source = InputDevice.SOURCE_KEYBOARD,
                onInput = events::add,
            ),
        )

        // OS intercepts Tab and cancels it -> Alt must also be released
        assertTrue(
            tracker.handleKeyEvent(
                keyCode = 61,
                isDown = false,
                metaState = 0x2,
                flags = KeyEvent.FLAG_CANCELED,
                source = InputDevice.SOURCE_KEYBOARD,
                onInput = events::add,
            ),
        )
        assertFalse(tracker.hasPressedModifiers())
        assertEquals(
            listOf(
                StreamInputEvent.Key(0x80A4.toShort(), StreamInputEvent.Key.ACTION_DOWN, KeyboardTranslator.MODIFIER_ALT),
                StreamInputEvent.Key(0x8009.toShort(), StreamInputEvent.Key.ACTION_DOWN, KeyboardTranslator.MODIFIER_ALT),
                StreamInputEvent.Key(0x8009.toShort(), StreamInputEvent.Key.ACTION_UP, KeyboardTranslator.MODIFIER_ALT),
                StreamInputEvent.Key(0x80A4.toShort(), StreamInputEvent.Key.ACTION_UP, 0),
            ),
            events,
        )

        // Idempotent when already empty
        events.clear()
        tracker.releaseAll(events::add)
        assertTrue(events.isEmpty())
    }

    @Test
    fun modifierTracker_releaseAllReleasesWinCtrlShiftInReverseOrder() {
        val tracker = ModifierKeyTracker()
        val events = mutableListOf<StreamInputEvent>()

        tracker.handleKeyEvent(113, isDown = true, metaState = 0, flags = 0, source = InputDevice.SOURCE_KEYBOARD, onInput = events::add)
        tracker.handleKeyEvent(59, isDown = true, metaState = 0, flags = 0, source = InputDevice.SOURCE_KEYBOARD, onInput = events::add)
        tracker.handleKeyEvent(117, isDown = true, metaState = 0, flags = 0, source = InputDevice.SOURCE_KEYBOARD, onInput = events::add)
        assertTrue(tracker.hasPressedModifiers())

        events.clear()
        tracker.releaseAll(events::add)
        assertFalse(tracker.hasPressedModifiers())
        assertEquals(
            listOf(
                StreamInputEvent.Key(0x805B.toShort(), StreamInputEvent.Key.ACTION_UP, (KeyboardTranslator.MODIFIER_CTRL.toInt() or KeyboardTranslator.MODIFIER_SHIFT.toInt()).toByte()),
                StreamInputEvent.Key(0x80A0.toShort(), StreamInputEvent.Key.ACTION_UP, KeyboardTranslator.MODIFIER_CTRL),
                StreamInputEvent.Key(0x80A2.toShort(), StreamInputEvent.Key.ACTION_UP, 0),
            ),
            events,
        )
    }

    @Test
    fun modifierTracker_consumesTabEscArrowsAndSuppressesMouseBack() {
        val tracker = ModifierKeyTracker()
        val events = mutableListOf<StreamInputEvent>()

        // Tab, Esc, Arrow keys are consumed
        assertTrue(tracker.handleKeyEvent(61, true, 0, 0, InputDevice.SOURCE_KEYBOARD, events::add))
        assertTrue(tracker.handleKeyEvent(111, true, 0, 0, InputDevice.SOURCE_KEYBOARD, events::add))
        assertTrue(tracker.handleKeyEvent(19, true, 0, 0, InputDevice.SOURCE_KEYBOARD, events::add))
        assertEquals(3, events.size)

        // Secondary mouse button KEYCODE_BACK is suppressed without emitting events
        events.clear()
        assertTrue(tracker.handleKeyEvent(KeyEvent.KEYCODE_BACK, true, 0, 0, InputDevice.SOURCE_MOUSE, events::add))
        assertTrue(tracker.handleKeyEvent(KeyEvent.KEYCODE_BACK, false, 0, 0, InputDevice.SOURCE_MOUSE_RELATIVE, events::add))
        assertTrue(events.isEmpty())

        // Non-mouse KEYCODE_BACK is not swallowed by ModifierKeyTracker
        assertFalse(tracker.handleKeyEvent(KeyEvent.KEYCODE_BACK, true, 0, 0, InputDevice.SOURCE_KEYBOARD, events::add))
    }

    @Test
    fun streamShortcuts_emitBalancedWinAltTabAndCtrlShiftEsc() {
        val winEvents = mutableListOf<StreamInputEvent>()
        StreamShortcuts.sendWin(winEvents::add)
        assertEquals(
            listOf(
                StreamInputEvent.Key(0x805B.toShort(), StreamInputEvent.Key.ACTION_DOWN, 0),
                StreamInputEvent.Key(0x805B.toShort(), StreamInputEvent.Key.ACTION_UP, 0),
            ),
            winEvents,
        )

        val altTabEvents = mutableListOf<StreamInputEvent>()
        StreamShortcuts.sendAltTab(altTabEvents::add)
        assertEquals(4, altTabEvents.size)
        assertEquals(StreamInputEvent.Key.ACTION_UP, (altTabEvents.last() as StreamInputEvent.Key).keyAction)
        assertEquals(0.toByte(), (altTabEvents.last() as StreamInputEvent.Key).modifiers)

        val taskMgrEvents = mutableListOf<StreamInputEvent>()
        StreamShortcuts.sendCtrlShiftEsc(taskMgrEvents::add)
        assertEquals(6, taskMgrEvents.size)
        assertEquals(StreamInputEvent.Key.ACTION_UP, (taskMgrEvents.last() as StreamInputEvent.Key).keyAction)
        assertEquals(0.toByte(), (taskMgrEvents.last() as StreamInputEvent.Key).modifiers)
    }

    @Test
    fun pinchTracker_toleratesMicroJitterOnTap() {
        val tracker = PinchTouchTracker(startX = 500f, startY = 500f, startMs = 1000L, tapSlop = 24f, viewportHeight = 1200)
        val moves = mutableListOf<Pair<Float, Float>>()
        val events = mutableListOf<StreamInputEvent>()

        // Oscillating XR hand-pinch micro-jitter within 24px radius (total Manhattan > 24px, displacement <= 24px)
        tracker.onPointerEvent(listOf(510f to 508f), 1040L, { x, y -> moves += x to y }, events::add)
        tracker.onPointerEvent(listOf(492f to 494f), 1080L, { x, y -> moves += x to y }, events::add)
        tracker.onPointerEvent(listOf(505f to 503f), 1120L, { x, y -> moves += x to y }, events::add)
        tracker.onRelease(1150L, events::add)

        assertTrue(moves.isEmpty())
        assertEquals(
            listOf(
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, true),
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, false),
            ),
            events,
        )
    }

    @Test
    fun pinchTracker_longPressLatchesDragAndReleasesOnReleaseOrCancel() {
        val tracker = PinchTouchTracker(startX = 400f, startY = 400f, startMs = 1000L, tapSlop = 24f, viewportHeight = 1600)
        val moves = mutableListOf<Pair<Float, Float>>()
        val events = mutableListOf<StreamInputEvent>()

        assertTrue(tracker.triggerLongPressIfEligible(events::add))
        assertTrue(tracker.isDragging)
        tracker.onPointerEvent(listOf(600f to 450f), 1500L, { x, y -> moves += x to y }, events::add)
        tracker.onRelease(1600L, events::add)

        assertFalse(tracker.isDragging)
        assertEquals(listOf(600f to 450f), moves)
        assertEquals(
            listOf(
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, true),
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, false),
            ),
            events,
        )

        // Cancel also releases held left button
        events.clear()
        val cancelTracker = PinchTouchTracker(startX = 100f, startY = 100f, startMs = 2000L, tapSlop = 24f, viewportHeight = 1200)
        cancelTracker.triggerLongPressIfEligible(events::add)
        cancelTracker.onCancel(events::add)
        assertEquals(
            listOf(
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, true),
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, false),
            ),
            events,
        )
    }

    @Test
    fun pinchTracker_twoFingerDragEmitsVerticalAndHorizontalHighResScroll() {
        val tracker = PinchTouchTracker(startX = 500f, startY = 500f, startMs = 1000L, tapSlop = 24f, viewportHeight = 1600)
        val events = mutableListOf<StreamInputEvent>()

        tracker.onPointerEvent(listOf(480f to 500f, 520f to 500f), 1020L, { _, _ -> }, events::add)
        // Drag 160px down and 160px right at 1600p -> 480 vertical + 480 horizontal units
        tracker.onPointerEvent(listOf(640f to 660f, 680f to 660f), 1100L, { _, _ -> }, events::add)
        tracker.onRelease(1120L, events::add)

        assertEquals(
            listOf(
                StreamInputEvent.HighResScroll(480),
                StreamInputEvent.HighResHScroll(480),
            ),
            events,
        )
    }

    @Test
    fun pinchTracker_remainingLongPressMsCountsDownFromStartAcrossJitter() {
        val tracker = PinchTouchTracker(startX = 500f, startY = 500f, startMs = 1000L, tapSlop = 24f, viewportHeight = 1200)
        assertEquals(420L, tracker.remainingLongPressMs())

        tracker.onPointerEvent(listOf(505f to 504f), 1300L, { _, _ -> }, {})
        assertEquals(120L, tracker.remainingLongPressMs())
        assertEquals(0L, tracker.remainingLongPressMs(1420L))

        assertTrue(tracker.triggerLongPressIfEligible({}))
        assertEquals(null, tracker.remainingLongPressMs())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun pinchTracker_timerDrivenLongPressLatchesAt420msDespiteJitterAt300ms() = runTest {
        val startMs = 1000L
        val tracker = PinchTouchTracker(startX = 500f, startY = 500f, startMs = startMs, tapSlop = 24f, viewportHeight = 1200)
        val pointerEvents = Channel<Pair<List<Pair<Float, Float>>, Long>>(Channel.UNLIMITED)
        val moves = mutableListOf<Pair<Float, Float>>()
        val events = mutableListOf<StreamInputEvent>()

        val job = launch {
            while (true) {
                val remaining = tracker.remainingLongPressMs()
                val next = if (remaining != null) {
                    if (remaining == 0L) {
                        tracker.triggerLongPressIfEligible(events::add)
                        continue
                    }
                    withTimeoutOrNull(remaining) { pointerEvents.receive() }
                        ?: run {
                            tracker.triggerLongPressIfEligible(events::add)
                            continue
                        }
                } else {
                    pointerEvents.receive()
                }
                val (positions, uptimeMs) = next
                tracker.onPointerEvent(positions, uptimeMs, { x, y -> moves += x to y }, events::add)
                if (positions.isEmpty()) {
                    tracker.onRelease(uptimeMs, events::add)
                    break
                }
            }
        }

        // Jitter at +300ms within 24px slop: remaining timeout must shrink to 120ms, not reset to 420ms.
        advanceTimeBy(300L)
        pointerEvents.send(listOf(505f to 504f) to (startMs + 300L))
        runCurrent()
        assertFalse(tracker.isDragging)
        assertTrue(events.isEmpty())

        // At +419ms, timer has not fired yet.
        advanceTimeBy(119L)
        runCurrent()
        assertFalse(tracker.isDragging)
        assertTrue(events.isEmpty())

        // At +420ms (120ms after the 300ms jitter), timer-driven latch fires BUTTON_LEFT down.
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(tracker.isDragging)
        assertEquals(
            listOf(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, true)),
            events,
        )

        // Subsequent drag move and release emit move + BUTTON_LEFT up.
        delay(50L)
        pointerEvents.send(listOf(620f to 540f) to (startMs + 470L))
        runCurrent()
        delay(50L)
        pointerEvents.send(emptyList<Pair<Float, Float>>() to (startMs + 520L))
        runCurrent()
        job.join()

        assertFalse(tracker.isDragging)
        assertEquals(listOf(620f to 540f), moves)
        assertEquals(
            listOf(
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, true),
                StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, false),
            ),
            events,
        )
    }
}
