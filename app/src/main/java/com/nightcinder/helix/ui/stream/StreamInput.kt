package com.nightcinder.helix.ui.stream

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.nightcinder.helix.core.model.StreamInputEvent
import com.nightcinder.helix.stream.KeyboardTranslator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Touch + keyboard + mouse + Android XR hand-pinch input for the native stream:
 * - one finger / XR pinch moves the host pointer (absolute position); a short tap (tolerating XR micro-jitter)
 *   is a left click; holding still for [LONG_PRESS_DRAG_MS] latches left-button drag until release
 * - a two-finger tap is a right click; a two-finger drag scrolls vertically & horizontally
 * - a mouse (or XR pointer reported as one) sends real button presses/releases (including side buttons),
 *   hover moves the cursor, and the wheel scrolls vertically & horizontally
 * - hardware keys (and soft keyboard key events) are translated to Windows VK codes, consuming Tab/Esc/arrows
 *   and releasing held modifiers deterministically on cancel, focus loss, keyboard toggle, or disposal
 * - the "Keyboard" toggle opens the soft keyboard through a hidden text field whose characters are replayed as keys
 */
@Composable
fun StreamInputLayer(
    onInput: (StreamInputEvent) -> Unit,
    keyboardRequested: Boolean,
    modifier: Modifier = Modifier,
    hudExpanded: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val textFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hiddenText by remember { mutableStateOf("") }
    val modifierTracker = remember { ModifierKeyTracker() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(keyboardRequested, hudExpanded) {
        modifierTracker.releaseAll(onInput)
        if (keyboardRequested) {
            runCatching { textFocusRequester.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            if (!hudExpanded) {
                runCatching { focusRequester.requestFocus() }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { modifierTracker.releaseAll(onInput) }
    }

    val restoreStreamFocus = {
        if (!keyboardRequested) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (!focusState.hasFocus) {
                    modifierTracker.releaseAll(onInput)
                }
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                val type = event.type
                if (type != KeyEventType.KeyDown && type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                modifierTracker.handleKeyEvent(
                    keyCode = event.key.nativeKeyCode,
                    isDown = type == KeyEventType.KeyDown,
                    metaState = native.metaState,
                    flags = native.flags,
                    source = native.source,
                    onInput = onInput,
                )
            }
            .pointerInput(keyboardRequested) { hoverAndWheel(onInput, restoreStreamFocus) }
            .pointerInput(keyboardRequested) { touchGestures(onInput, restoreStreamFocus) },
    ) {
        if (keyboardRequested) {
            BasicTextField(
                value = hiddenText,
                onValueChange = { new ->
                    for (c in new) sendChar(c, onInput)
                    hiddenText = ""
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(textFocusRequester),
            )
        }
    }
}

private fun sendChar(c: Char, onInput: (StreamInputEvent) -> Unit) {
    val (vk, shift) = KeyboardTranslator.vkForChar(c) ?: return
    val mods: Byte = if (shift) KeyboardTranslator.MODIFIER_SHIFT else 0
    val code = ((0x80 shl 8) or vk).toShort()
    onInput(StreamInputEvent.Key(code, StreamInputEvent.Key.ACTION_DOWN, mods))
    onInput(StreamInputEvent.Key(code, StreamInputEvent.Key.ACTION_UP, mods))
}

private const val TAP_MAX_MS = 400L
private const val LONG_PRESS_DRAG_MS = 420L
private const val PINCH_TAP_SLOP_PX = 24f

// 120 high-resolution units are one wheel notch.
private const val WHEEL_NOTCH_UNITS = 120
private const val WHEEL_DEADZONE = 0.001f
private const val BASE_SCROLL_UNITS_PER_PX = 4f
private const val BASE_VIEWPORT_HEIGHT = 1200f
private const val SCROLL_DEADZONE_PX = 2f

private suspend fun PointerInputScope.touchGestures(
    onInput: (StreamInputEvent) -> Unit,
    onViewportFocusRequested: () -> Unit,
) {
    val tapSlop = maxOf(viewConfiguration.touchSlop, PINCH_TAP_SLOP_PX)
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        onViewportFocusRequested()

        val w = size.width.coerceIn(1, Short.MAX_VALUE.toInt())
        val h = size.height.coerceIn(1, Short.MAX_VALUE.toInt())
        val refW = w.toShort()
        val refH = h.toShort()

        fun sendPosition(x: Float, y: Float) {
            val px = x.roundToInt().coerceIn(0, w - 1).toShort()
            val py = y.roundToInt().coerceIn(0, h - 1).toShort()
            onInput(StreamInputEvent.MousePosition(px, py, refW, refH))
        }

        first.consume()
        sendPosition(first.position.x, first.position.y)

        if (first.type == PointerType.Mouse) {
            val downButtons = currentEvent.buttons
            var mask = MouseButtonDiff.mask(
                primary = downButtons.isPrimaryPressed,
                secondary = downButtons.isSecondaryPressed,
                tertiary = downButtons.isTertiaryPressed,
                back = downButtons.isBackPressed,
                forward = downButtons.isForwardPressed,
            )
            MouseButtonDiff.diff(0, mask).forEach(onInput)
            try {
                do {
                    val event = awaitPointerEvent()
                    val exited = event.type == PointerEventType.Exit
                    val reported = MouseButtonDiff.mask(
                        primary = event.buttons.isPrimaryPressed,
                        secondary = event.buttons.isSecondaryPressed,
                        tertiary = event.buttons.isTertiaryPressed,
                        back = event.buttons.isBackPressed,
                        forward = event.buttons.isForwardPressed,
                    )
                    val stillPressed = event.changes.any { it.pressed }
                    val now = MouseButtonDiff.next(mask, reported, stillPressed = stillPressed, exited = exited)
                    event.changes.firstOrNull()?.let { sendPosition(it.position.x, it.position.y) }
                    MouseButtonDiff.diff(mask, now).forEach(onInput)
                    mask = now
                    event.changes.forEach { it.consume() }
                } while (!exited && stillPressed)
            } finally {
                if (mask != 0) {
                    MouseButtonDiff.diff(mask, 0).forEach(onInput)
                }
            }
            return@awaitEachGesture
        }

        val tracker = PinchTouchTracker(
            startX = first.position.x,
            startY = first.position.y,
            startMs = first.uptimeMillis,
            tapSlop = tapSlop,
            viewportHeight = h,
        )
        var lastEventMs = first.uptimeMillis
        var completed = false
        try {
            while (true) {
                val remainingLongPressMs = tracker.remainingLongPressMs()
                val event = if (remainingLongPressMs != null) {
                    if (remainingLongPressMs == 0L) {
                        tracker.triggerLongPressIfEligible(onInput)
                        continue
                    }
                    val timedEvent = withTimeoutOrNull(remainingLongPressMs) { awaitPointerEvent() }
                    if (timedEvent == null) {
                        tracker.triggerLongPressIfEligible(onInput)
                        continue
                    }
                    timedEvent
                } else {
                    awaitPointerEvent()
                }

                val pressed = event.changes.filter { it.pressed }
                val eventMs = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastEventMs
                lastEventMs = eventMs
                tracker.onPointerEvent(
                    pressedPositions = pressed.map { it.position.x to it.position.y },
                    uptimeMs = eventMs,
                    onMove = ::sendPosition,
                    onInput = onInput,
                )
                event.changes.forEach { it.consume() }
                if (pressed.isEmpty()) {
                    tracker.onRelease(eventMs, onInput)
                    completed = true
                    break
                }
            }
        } finally {
            if (!completed) {
                tracker.onCancel(onInput)
            }
        }
    }
}

/**
 * Pointer hover (mouse, stylus, XR ray) moves the host cursor without a click, and a mouse wheel scrolls
 * vertically and horizontally. Runs before [touchGestures] and never consumes move/enter events.
 */
private suspend fun PointerInputScope.hoverAndWheel(
    onInput: (StreamInputEvent) -> Unit,
    onViewportFocusRequested: () -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            when (event.type) {
                PointerEventType.Enter -> {
                    onViewportFocusRequested()
                    if (event.changes.none { it.pressed }) {
                        val w = size.width.coerceIn(1, Short.MAX_VALUE.toInt())
                        val h = size.height.coerceIn(1, Short.MAX_VALUE.toInt())
                        val px = change.position.x.roundToInt().coerceIn(0, w - 1).toShort()
                        val py = change.position.y.roundToInt().coerceIn(0, h - 1).toShort()
                        onInput(StreamInputEvent.MousePosition(px, py, w.toShort(), h.toShort()))
                    }
                }
                PointerEventType.Move -> {
                    if (event.changes.none { it.pressed }) {
                        val w = size.width.coerceIn(1, Short.MAX_VALUE.toInt())
                        val h = size.height.coerceIn(1, Short.MAX_VALUE.toInt())
                        val px = change.position.x.roundToInt().coerceIn(0, w - 1).toShort()
                        val py = change.position.y.roundToInt().coerceIn(0, h - 1).toShort()
                        onInput(StreamInputEvent.MousePosition(px, py, w.toShort(), h.toShort()))
                    }
                }
                PointerEventType.Scroll -> {
                    val unitsY = MouseButtonDiff.wheelUnits(change.scrollDelta.y)
                    if (unitsY != 0) onInput(StreamInputEvent.HighResScroll(unitsY.toShort()))
                    val unitsX = MouseButtonDiff.horizontalWheelUnits(change.scrollDelta.x)
                    if (unitsX != 0) onInput(StreamInputEvent.HighResHScroll(unitsX.toShort()))
                    change.consume()
                }
                else -> Unit
            }
        }
    }
}

/** Tracks pressed hardware modifier keys and guarantees deterministic ACTION_UP release. */
internal class ModifierKeyTracker {
    private val pressedModifiers = LinkedHashSet<Short>()

    fun activeModifierMask(): Byte {
        var mask = 0
        for (vk in pressedModifiers) {
            mask = mask or modifierBitForVk(vk)
        }
        return mask.toByte()
    }

    fun hasPressedModifiers(): Boolean = pressedModifiers.isNotEmpty()

    fun handleKeyEvent(
        keyCode: Int,
        isDown: Boolean,
        metaState: Int,
        flags: Int,
        source: Int,
        onInput: (StreamInputEvent) -> Unit,
    ): Boolean {
        if (isMouseBackEvent(keyCode, source)) return true

        val translated = KeyboardTranslator.translate(keyCode)
        if (translated.toInt() == 0) return false

        val isCanceled = (flags and KeyEvent.FLAG_CANCELED) != 0
        return if (isDown && !isCanceled) {
            if (isModifierVk(translated)) {
                pressedModifiers.add(translated)
            }
            val mods = (KeyboardTranslator.modifiersFromMetaState(metaState).toInt() or activeModifierMask().toInt()).toByte()
            onInput(StreamInputEvent.Key(translated, StreamInputEvent.Key.ACTION_DOWN, mods))
            true
        } else {
            if (isModifierVk(translated)) {
                pressedModifiers.remove(translated)
                onInput(StreamInputEvent.Key(translated, StreamInputEvent.Key.ACTION_UP, activeModifierMask()))
            } else {
                onInput(StreamInputEvent.Key(translated, StreamInputEvent.Key.ACTION_UP, activeModifierMask()))
                if (isCanceled) {
                    releaseAll(onInput)
                }
            }
            true
        }
    }

    fun releaseAll(onInput: (StreamInputEvent) -> Unit) {
        if (pressedModifiers.isEmpty()) return
        val toRelease = pressedModifiers.toList().asReversed()
        for (vk in toRelease) {
            pressedModifiers.remove(vk)
            onInput(StreamInputEvent.Key(vk, StreamInputEvent.Key.ACTION_UP, activeModifierMask()))
        }
    }

    companion object {
        private const val VK_LSHIFT: Short = 0x80A0.toShort()
        private const val VK_RSHIFT: Short = 0x80A1.toShort()
        private const val VK_LCONTROL: Short = 0x80A2.toShort()
        private const val VK_RCONTROL: Short = 0x80A3.toShort()
        private const val VK_LMENU: Short = 0x80A4.toShort()
        private const val VK_RMENU: Short = 0x80A5.toShort()
        private const val VK_LWIN: Short = 0x805B.toShort()
        private const val VK_RWIN: Short = 0x805C.toShort()

        fun isMouseBackEvent(keyCode: Int, source: Int): Boolean {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false
            return (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        }

        private fun isModifierVk(vk: Short): Boolean = modifierBitForVk(vk) != 0

        private fun modifierBitForVk(vk: Short): Int = when (vk) {
            VK_LSHIFT, VK_RSHIFT -> KeyboardTranslator.MODIFIER_SHIFT.toInt()
            VK_LCONTROL, VK_RCONTROL -> KeyboardTranslator.MODIFIER_CTRL.toInt()
            VK_LMENU, VK_RMENU -> KeyboardTranslator.MODIFIER_ALT.toInt()
            VK_LWIN, VK_RWIN -> KeyboardTranslator.MODIFIER_META.toInt()
            else -> 0
        }
    }
}

/** Deterministic 1-tap system shortcut sequences for the stream HUD. */
internal object StreamShortcuts {
    private val VK_LWIN: Short = 0x805B.toShort()
    private val VK_LMENU: Short = 0x80A4.toShort()
    private val VK_TAB: Short = 0x8009.toShort()
    private val VK_LCONTROL: Short = 0x80A2.toShort()
    private val VK_LSHIFT: Short = 0x80A0.toShort()
    private val VK_ESCAPE: Short = 0x801B.toShort()

    fun sendWin(onInput: (StreamInputEvent) -> Unit) {
        onInput(StreamInputEvent.Key(VK_LWIN, StreamInputEvent.Key.ACTION_DOWN, 0))
        onInput(StreamInputEvent.Key(VK_LWIN, StreamInputEvent.Key.ACTION_UP, 0))
    }

    fun sendAltTab(onInput: (StreamInputEvent) -> Unit) {
        val alt = KeyboardTranslator.MODIFIER_ALT
        onInput(StreamInputEvent.Key(VK_LMENU, StreamInputEvent.Key.ACTION_DOWN, alt))
        onInput(StreamInputEvent.Key(VK_TAB, StreamInputEvent.Key.ACTION_DOWN, alt))
        onInput(StreamInputEvent.Key(VK_TAB, StreamInputEvent.Key.ACTION_UP, alt))
        onInput(StreamInputEvent.Key(VK_LMENU, StreamInputEvent.Key.ACTION_UP, 0))
    }

    fun sendCtrlShiftEsc(onInput: (StreamInputEvent) -> Unit) {
        val ctrl = KeyboardTranslator.MODIFIER_CTRL
        val ctrlShift = (KeyboardTranslator.MODIFIER_CTRL.toInt() or KeyboardTranslator.MODIFIER_SHIFT.toInt()).toByte()
        onInput(StreamInputEvent.Key(VK_LCONTROL, StreamInputEvent.Key.ACTION_DOWN, ctrl))
        onInput(StreamInputEvent.Key(VK_LSHIFT, StreamInputEvent.Key.ACTION_DOWN, ctrlShift))
        onInput(StreamInputEvent.Key(VK_ESCAPE, StreamInputEvent.Key.ACTION_DOWN, ctrlShift))
        onInput(StreamInputEvent.Key(VK_ESCAPE, StreamInputEvent.Key.ACTION_UP, ctrlShift))
        onInput(StreamInputEvent.Key(VK_LSHIFT, StreamInputEvent.Key.ACTION_UP, ctrl))
        onInput(StreamInputEvent.Key(VK_LCONTROL, StreamInputEvent.Key.ACTION_UP, 0))
    }
}

/** Pure state machine for Android XR hand-pinch & touch click/drag/scroll gestures. */
internal class PinchTouchTracker(
    private val startX: Float,
    private val startY: Float,
    private val startMs: Long,
    private val tapSlop: Float,
    private val viewportHeight: Int,
) {
    var maxPointers: Int = 1
        private set
    var isDragging: Boolean = false
        private set
    var hasMovedPastSlop: Boolean = false
        private set

    private var lastMs: Long = startMs
    private var lastCenterX: Float = startX
    private var lastCenterY: Float = startY
    private var scrollRemainderX: Float = 0f
    private var scrollRemainderY: Float = 0f

    fun remainingLongPressMs(nowMs: Long = lastMs): Long? {
        if (isDragging || hasMovedPastSlop || maxPointers > 1) return null
        return (LONG_PRESS_DRAG_MS - (nowMs - startMs)).coerceAtLeast(0L)
    }

    fun triggerLongPressIfEligible(onInput: (StreamInputEvent) -> Unit): Boolean {
        if (isDragging || hasMovedPastSlop || maxPointers > 1) return false
        isDragging = true
        onInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, true))
        return true
    }

    fun onPointerEvent(
        pressedPositions: List<Pair<Float, Float>>,
        uptimeMs: Long,
        onMove: (Float, Float) -> Unit,
        onInput: (StreamInputEvent) -> Unit,
    ) {
        lastMs = uptimeMs
        if (pressedPositions.isEmpty()) return

        if (pressedPositions.size > maxPointers) {
            maxPointers = pressedPositions.size
            lastCenterX = pressedPositions.map { it.first }.average().toFloat()
            lastCenterY = pressedPositions.map { it.second }.average().toFloat()
        }

        if (pressedPositions.size >= 2) {
            val cx = pressedPositions.map { it.first }.average().toFloat()
            val cy = pressedPositions.map { it.second }.average().toFloat()
            val dx = cx - lastCenterX
            val dy = cy - lastCenterY
            lastCenterX = cx
            lastCenterY = cy
            if (hypot(dx, dy) > tapSlop) {
                hasMovedPastSlop = true
            }
            scrollRemainderY += MouseButtonDiff.dragScrollUnits(dy, viewportHeight)
            val unitsY = scrollRemainderY.toInt()
            if (unitsY != 0) {
                scrollRemainderY -= unitsY
                onInput(StreamInputEvent.HighResScroll(unitsY.coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()))
            }
            scrollRemainderX += MouseButtonDiff.dragScrollUnits(dx, viewportHeight)
            val unitsX = scrollRemainderX.toInt()
            if (unitsX != 0) {
                scrollRemainderX -= unitsX
                onInput(StreamInputEvent.HighResHScroll(unitsX.coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()))
            }
        } else if (pressedPositions.size == 1 && maxPointers == 1) {
            val (x, y) = pressedPositions[0]
            val displacement = hypot(x - startX, y - startY)
            if (displacement > tapSlop) {
                hasMovedPastSlop = true
            }
            if (!isDragging && !hasMovedPastSlop && (uptimeMs - startMs) >= LONG_PRESS_DRAG_MS) {
                triggerLongPressIfEligible(onInput)
            }
            if (isDragging || hasMovedPastSlop) {
                onMove(x, y)
            }
        }
    }

    fun onRelease(releaseMs: Long, onInput: (StreamInputEvent) -> Unit) {
        lastMs = releaseMs
        if (isDragging) {
            isDragging = false
            onInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, false))
            return
        }
        if (!hasMovedPastSlop && (releaseMs - startMs) < TAP_MAX_MS) {
            val button = if (maxPointers >= 2) {
                StreamInputEvent.MouseButton.BUTTON_RIGHT
            } else {
                StreamInputEvent.MouseButton.BUTTON_LEFT
            }
            onInput(StreamInputEvent.MouseButton(button, true))
            onInput(StreamInputEvent.MouseButton(button, false))
        }
    }

    fun onCancel(onInput: (StreamInputEvent) -> Unit) {
        if (isDragging) {
            isDragging = false
            onInput(StreamInputEvent.MouseButton(StreamInputEvent.MouseButton.BUTTON_LEFT, false))
        }
    }
}

/** Pure helpers for mouse input; unit tested in StreamInputMouseTest. */
internal object MouseButtonDiff {
    const val PRIMARY = 1
    const val SECONDARY = 2
    const val TERTIARY = 4
    const val BACK = 8
    const val FORWARD = 16

    fun mask(
        primary: Boolean,
        secondary: Boolean,
        tertiary: Boolean,
        back: Boolean = false,
        forward: Boolean = false,
    ): Int =
        (if (primary) PRIMARY else 0) or
            (if (secondary) SECONDARY else 0) or
            (if (tertiary) TERTIARY else 0) or
            (if (back) BACK else 0) or
            (if (forward) FORWARD else 0)

    /**
     * Button state to use for this event:
     * - on exit or when no pointer is pressed, releases all held buttons (returns 0)
     * - when [reported] is 0 while still pressed, preserves [previous] (never promotes 0 to PRIMARY)
     */
    fun next(previous: Int, reported: Int, stillPressed: Boolean, exited: Boolean = false): Int = when {
        exited || !stillPressed -> 0
        reported == 0 -> previous
        else -> reported
    }

    /** Press/release events turning [previous] into [now]; releases come first. */
    fun diff(previous: Int, now: Int): List<StreamInputEvent.MouseButton> {
        val out = ArrayList<StreamInputEvent.MouseButton>(5)
        for ((bit, button) in BUTTONS) {
            if (previous and bit != 0 && now and bit == 0) out += StreamInputEvent.MouseButton(button, false)
        }
        for ((bit, button) in BUTTONS) {
            if (previous and bit == 0 && now and bit != 0) out += StreamInputEvent.MouseButton(button, true)
        }
        return out
    }

    /**
     * Compose reports wheel deltas in notches with positive = scroll down; moonlight-common-c wants
     * 120 units per notch with positive = up.
     */
    fun wheelUnits(deltaY: Float): Int {
        if (abs(deltaY) < WHEEL_DEADZONE) return 0
        return (-deltaY * WHEEL_NOTCH_UNITS).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    /**
     * Compose reports horizontal wheel deltas in notches with positive = scroll right;
     * moonlight-common-c `LiSendHighResHScrollEvent` uses positive = right.
     */
    fun horizontalWheelUnits(deltaX: Float): Int {
        if (abs(deltaX) < WHEEL_DEADZONE) return 0
        return (deltaX * WHEEL_NOTCH_UNITS).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    /** Scales two-finger drag scroll deltas consistently across 1920x1200 and 2560x1600 viewports. */
    fun dragScrollUnits(deltaPx: Float, viewportHeight: Int): Float {
        if (abs(deltaPx) < SCROLL_DEADZONE_PX) return 0f
        val scale = ((BASE_VIEWPORT_HEIGHT / viewportHeight.coerceAtLeast(1)) * BASE_SCROLL_UNITS_PER_PX)
            .coerceIn(2f, 8f)
        return deltaPx * scale
    }

    private val BUTTONS = listOf(
        PRIMARY to StreamInputEvent.MouseButton.BUTTON_LEFT,
        SECONDARY to StreamInputEvent.MouseButton.BUTTON_RIGHT,
        TERTIARY to StreamInputEvent.MouseButton.BUTTON_MIDDLE,
        BACK to StreamInputEvent.MouseButton.BUTTON_X1,
        FORWARD to StreamInputEvent.MouseButton.BUTTON_X2,
    )
}
