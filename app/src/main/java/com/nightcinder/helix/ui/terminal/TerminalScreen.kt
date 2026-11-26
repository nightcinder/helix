package com.nightcinder.helix.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightcinder.helix.core.model.TerminalConnectionState
import com.nightcinder.helix.core.model.TerminalTabState
import com.nightcinder.helix.core.model.TerminalTarget
import com.nightcinder.helix.terminal.TerminalCell
import com.nightcinder.helix.terminal.TerminalScreenSnapshot
import com.nightcinder.helix.terminal.Vt100KeyEncoder
import com.nightcinder.helix.terminal.Vt100SpecialKey
import com.nightcinder.helix.ui.state.SpecialTerminalKey
import com.nightcinder.helix.ui.state.TerminalUiState
import com.nightcinder.helix.ui.state.TerminalViewModel
import com.nightcinder.helix.ui.theme.AmberAccent
import com.nightcinder.helix.ui.theme.CyanPrimary
import com.nightcinder.helix.ui.theme.EmeraldAccent
import com.nightcinder.helix.ui.theme.RoseError

private val DEFAULT_TERMINAL_FG = Color(0xFFE2E8F0)
private val DEFAULT_TERMINAL_BG = Color(0xFF030507)

private val ANSI_16_PALETTE = arrayOf(
    Color(0xFF1E293B), // 0: Black
    Color(0xFFEF4444), // 1: Red
    Color(0xFF10B981), // 2: Green
    Color(0xFFF59E0B), // 3: Yellow
    Color(0xFF3B82F6), // 4: Blue
    Color(0xFFA855F7), // 5: Magenta
    Color(0xFF06B6D4), // 6: Cyan
    Color(0xFFCBD5E1), // 7: White
    Color(0xFF64748B), // 8: Bright Black (Gray)
    Color(0xFFF87171), // 9: Bright Red
    Color(0xFF34D399), // 10: Bright Green
    Color(0xFFFBBF24), // 11: Bright Yellow
    Color(0xFF60A5FA), // 12: Bright Blue
    Color(0xFFC084FC), // 13: Bright Magenta
    Color(0xFF22D3EE), // 14: Bright Cyan
    Color(0xFFF8FAFC), // 15: Bright White
)

private val XTERM_CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

fun resolveTerminalColor(colorCode: Int, defaultColor: Color): Color {
    if (colorCode == TerminalCell.DEFAULT_FG || colorCode == TerminalCell.DEFAULT_BG) {
        return defaultColor
    }
    if (TerminalCell.isTrueColor(colorCode)) {
        val rgb = TerminalCell.extractRgb(colorCode)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return Color(r, g, b)
    }
    return when (colorCode) {
        in 0..15 -> ANSI_16_PALETTE[colorCode]
        in 16..231 -> {
            val idx = colorCode - 16
            val r = XTERM_CUBE_LEVELS[(idx / 36) % 6]
            val g = XTERM_CUBE_LEVELS[(idx / 6) % 6]
            val b = XTERM_CUBE_LEVELS[idx % 6]
            Color(r, g, b)
        }
        in 232..255 -> {
            val gray = (8 + (colorCode - 232) * 10).coerceIn(0, 255)
            Color(gray, gray, gray)
        }
        else -> defaultColor
    }
}

/**
 * Builds an [AnnotatedString] from a [TerminalScreenSnapshot] supporting:
 * - 16-color standard/bright ANSI palette
 * - 256-color xterm color cube & grayscale ramp
 * - 24-bit TrueColor (`38;2;r;g;b` / `48;2;r;g;b`)
 * - Bold, dim, italic, underline, strikethrough, and inverse attributes
 * - Active cursor cell highlight
 */
fun buildTerminalAnnotatedString(
    snapshot: TerminalScreenSnapshot,
    isAlternateScreen: Boolean,
    showCursor: Boolean = true,
): AnnotatedString = buildAnnotatedString {
    val linesToRender = if (isAlternateScreen) {
        snapshot.lines
    } else {
        snapshot.scrollback + snapshot.lines
    }
    val cursorTargetRow = if (isAlternateScreen) {
        snapshot.cursorRow
    } else {
        snapshot.scrollback.size + snapshot.cursorRow
    }

    var lastNonEmptyRow = -1
    for (r in linesToRender.indices) {
        val rowCells = linesToRender[r].cells
        val hasContent = rowCells.any { cell ->
            cell.char != ' ' ||
                cell.bgColor != TerminalCell.DEFAULT_BG ||
                cell.inverse ||
                cell.underline ||
                cell.strikethrough
        }
        if (hasContent) {
            lastNonEmptyRow = r
        }
    }

    val maxRow = if (isAlternateScreen) {
        (linesToRender.size - 1).coerceAtLeast(0)
    } else {
        maxOf(lastNonEmptyRow, if (showCursor) cursorTargetRow else -1)
    }

    if (maxRow < 0 || linesToRender.isEmpty()) {
        return@buildAnnotatedString
    }

    val upperRow = maxRow.coerceAtMost(linesToRender.size - 1)
    for (r in 0..upperRow) {
        val cells = linesToRender[r].cells
        var lastCol = -1
        for (c in cells.indices) {
            val cell = cells[c]
            if (cell.char != ' ' ||
                cell.bgColor != TerminalCell.DEFAULT_BG ||
                cell.inverse ||
                cell.underline ||
                cell.strikethrough
            ) {
                lastCol = c
            }
        }
        if (showCursor && r == cursorTargetRow) {
            lastCol = maxOf(lastCol, snapshot.cursorCol.coerceAtMost((cells.size - 1).coerceAtLeast(0)))
        }

        for (c in 0..lastCol) {
            val cell = cells.getOrElse(c) { TerminalCell.EMPTY }
            val isCursorCell = showCursor && r == cursorTargetRow && c == snapshot.cursorCol

            val resolvedFg = resolveTerminalColor(
                colorCode = if (cell.bold && cell.fgColor in 0..7) cell.fgColor + 8 else cell.fgColor,
                defaultColor = DEFAULT_TERMINAL_FG,
            )
            val resolvedBg = resolveTerminalColor(
                colorCode = cell.bgColor,
                defaultColor = Color.Unspecified,
            )

            val (effectiveFg, effectiveBg) = when {
                isCursorCell -> DEFAULT_TERMINAL_BG to CyanPrimary
                cell.inverse -> {
                    val invFg = if (resolvedBg == Color.Unspecified) DEFAULT_TERMINAL_BG else resolvedBg
                    val invBg = resolvedFg
                    invFg to invBg
                }
                else -> resolvedFg to resolvedBg
            }

            val finalFg = if (cell.dim && !isCursorCell) {
                effectiveFg.copy(alpha = 0.6f)
            } else {
                effectiveFg
            }

            val decorations = buildList {
                if (cell.underline) add(TextDecoration.Underline)
                if (cell.strikethrough) add(TextDecoration.LineThrough)
            }
            val textDecoration = if (decorations.isEmpty()) {
                TextDecoration.None
            } else {
                TextDecoration.combine(decorations)
            }

            val hasCustomStyle = isCursorCell ||
                cell.fgColor != TerminalCell.DEFAULT_FG ||
                cell.bgColor != TerminalCell.DEFAULT_BG ||
                cell.bold ||
                cell.dim ||
                cell.italic ||
                cell.underline ||
                cell.strikethrough ||
                cell.inverse

            if (hasCustomStyle) {
                val start = length
                append(cell.char)
                addStyle(
                    style = SpanStyle(
                        color = finalFg,
                        background = effectiveBg,
                        fontWeight = if (cell.bold || isCursorCell) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = textDecoration,
                    ),
                    start = start,
                    end = length,
                )
            } else {
                append(cell.char)
            }
        }
        if (r < upperRow) {
            append('\n')
        }
    }
}

internal fun mapHardwareKeyEventToVt100(keyEvent: KeyEvent): String? {
    if (keyEvent.type != KeyEventType.KeyDown) return null

    when (keyEvent.key) {
        Key.Escape -> return Vt100SpecialKey.ESC.sequence
        Key.Tab -> return Vt100SpecialKey.TAB.sequence
        Key.Enter, Key.NumPadEnter -> return Vt100SpecialKey.ENTER.sequence
        Key.Backspace -> return Vt100SpecialKey.BACKSPACE.sequence
        Key.Delete -> return Vt100SpecialKey.DELETE.sequence
        Key.Insert -> return Vt100SpecialKey.INSERT.sequence
        Key.DirectionUp -> return Vt100SpecialKey.UP.sequence
        Key.DirectionDown -> return Vt100SpecialKey.DOWN.sequence
        Key.DirectionRight -> return Vt100SpecialKey.RIGHT.sequence
        Key.DirectionLeft -> return Vt100SpecialKey.LEFT.sequence
        Key.MoveHome -> return Vt100SpecialKey.HOME.sequence
        Key.MoveEnd -> return Vt100SpecialKey.END.sequence
        Key.PageUp -> return Vt100SpecialKey.PAGE_UP.sequence
        Key.PageDown -> return Vt100SpecialKey.PAGE_DOWN.sequence
        Key.F1 -> return Vt100SpecialKey.F1.sequence
        Key.F2 -> return Vt100SpecialKey.F2.sequence
        Key.F3 -> return Vt100SpecialKey.F3.sequence
        Key.F4 -> return Vt100SpecialKey.F4.sequence
        Key.F5 -> return Vt100SpecialKey.F5.sequence
        Key.F6 -> return Vt100SpecialKey.F6.sequence
        Key.F7 -> return Vt100SpecialKey.F7.sequence
        Key.F8 -> return Vt100SpecialKey.F8.sequence
        Key.F9 -> return Vt100SpecialKey.F9.sequence
        Key.F10 -> return Vt100SpecialKey.F10.sequence
        Key.F11 -> return Vt100SpecialKey.F11.sequence
        Key.F12 -> return Vt100SpecialKey.F12.sequence
    }

    if (keyEvent.isCtrlPressed || keyEvent.isAltPressed) {
        val baseChar = keyToPrintableChar(keyEvent.key, keyEvent.isShiftPressed)
            ?: keyEvent.utf16CodePoint.takeIf { it in 0x20..0x7E }?.toChar()
        if (baseChar != null) {
            return Vt100KeyEncoder.encodeModifiedInput(
                input = baseChar.toString(),
                ctrlActive = keyEvent.isCtrlPressed,
                altActive = keyEvent.isAltPressed,
            )
        }
    }

    val codePoint = keyEvent.utf16CodePoint
    if (codePoint >= 0x20) {
        return codePoint.toChar().toString()
    }
    return null
}

private fun keyToPrintableChar(key: Key, shiftPressed: Boolean): Char? {
    val ch = when (key) {
        Key.A -> 'a'
        Key.B -> 'b'
        Key.C -> 'c'
        Key.D -> 'd'
        Key.E -> 'e'
        Key.F -> 'f'
        Key.G -> 'g'
        Key.H -> 'h'
        Key.I -> 'i'
        Key.J -> 'j'
        Key.K -> 'k'
        Key.L -> 'l'
        Key.M -> 'm'
        Key.N -> 'n'
        Key.O -> 'o'
        Key.P -> 'p'
        Key.Q -> 'q'
        Key.R -> 'r'
        Key.S -> 's'
        Key.T -> 't'
        Key.U -> 'u'
        Key.V -> 'v'
        Key.W -> 'w'
        Key.X -> 'x'
        Key.Y -> 'y'
        Key.Z -> 'z'
        Key.Spacebar -> ' '
        Key.LeftBracket -> '['
        Key.RightBracket -> ']'
        Key.Backslash -> '\\'
        Key.Slash -> '/'
        else -> null
    } ?: return null
    return if (shiftPressed) ch.uppercaseChar() else ch
}

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    uiState: TerminalUiState,
    modifier: Modifier = Modifier,
    showTabBar: Boolean = true,
) {
    var commandInput by remember { mutableStateOf("") }
    val density = LocalDensity.current
    val viewportFocusRequester = remember { FocusRequester() }
    val activeSnapshot = uiState.activeSnapshot

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B0E))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Multi-tab terminal strip with active OSC window title subtitle support
        if (showTabBar) {
            TerminalTabBar(
                tabs = uiState.tabs,
                activeTabId = uiState.activeTabId,
                activeWindowTitle = activeSnapshot?.title,
                isAlternateScreen = activeSnapshot?.isAlternateScreen == true,
                onSelectTab = { viewModel.selectTab(it) },
                onCloseTab = { viewModel.closeTab(it) },
            )
        }

        // Monospace terminal viewport with hardware keyboard focus and VT100/xterm styling
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(viewportFocusRequester)
                .focusable()
                .clickable(
                    interactionSource = null,
                    indication = null,
                ) {
                    viewportFocusRequester.requestFocus()
                }
                .onPreviewKeyEvent { keyEvent ->
                    val encoded = mapHardwareKeyEventToVt100(keyEvent)
                    if (encoded != null) {
                        viewModel.sendRawInput(encoded)
                        true
                    } else {
                        false
                    }
                }
                .onSizeChanged { size ->
                    with(density) {
                        val approxCharWidth = 8.sp.toPx()
                        val approxCharHeight = 16.sp.toPx()
                        if (approxCharWidth > 0 && approxCharHeight > 0) {
                            val cols = (size.width / approxCharWidth).toInt().coerceAtLeast(40)
                            val rows = (size.height / approxCharHeight).toInt().coerceAtLeast(10)
                            viewModel.resize(cols, rows)
                        }
                    }
                },
            colors = CardDefaults.cardColors(containerColor = DEFAULT_TERMINAL_BG),
            shape = RoundedCornerShape(12.dp),
        ) {
            val scrollState = rememberScrollState()
            val isAltScreen = activeSnapshot?.isAlternateScreen == true

            LaunchedEffect(activeSnapshot) {
                if (!isAltScreen) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                } else {
                    scrollState.scrollTo(0)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(scrollState),
            ) {
                if (uiState.tabs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No terminal tabs open. Launch SSH from Hosts or Docker Console from Workspace.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (activeSnapshot != null) {
                    val annotated = remember(activeSnapshot) {
                        buildTerminalAnnotatedString(
                            snapshot = activeSnapshot,
                            isAlternateScreen = activeSnapshot.isAlternateScreen,
                            showCursor = true,
                        )
                    }
                    if (annotated.isEmpty() && activeSnapshot.visibleText.isBlank()) {
                        Text(
                            text = "[Session connected. Waiting for shell prompt...]",
                            color = DEFAULT_TERMINAL_FG,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            ),
                        )
                    } else {
                        Text(
                            text = annotated,
                            color = DEFAULT_TERMINAL_FG,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            ),
                        )
                    }
                } else {
                    Text(
                        text = "[Session connected. Waiting for shell prompt...]",
                        color = DEFAULT_TERMINAL_FG,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        ),
                    )
                }
            }
        }

        // Quick modifier bar
        TerminalModifierBar(
            isCtrlActive = uiState.isCtrlActive,
            isAltActive = uiState.isAltActive,
            onSpecialKey = { viewModel.sendSpecialKey(it) },
        )

        // Command / Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = { Text("Enter command or text...", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (commandInput.isNotBlank()) {
                            viewModel.sendInput(commandInput + "\r")
                            commandInput = ""
                        }
                    }
                ),
                shape = RoundedCornerShape(10.dp),
            )

            Button(
                onClick = {
                    if (commandInput.isNotBlank()) {
                        viewModel.sendInput(commandInput + "\r")
                        commandInput = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun TerminalTabBar(
    tabs: List<TerminalTabState>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    activeWindowTitle: String? = null,
    isAlternateScreen: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isActive = tab.id == activeTabId
            val statusColor = when (tab.state) {
                TerminalConnectionState.Connected -> EmeraldAccent
                TerminalConnectionState.Connecting -> AmberAccent
                TerminalConnectionState.Reconnecting -> AmberAccent
                TerminalConnectionState.DataExpired -> RoseError
                TerminalConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
                TerminalConnectionState.Error -> RoseError
            }

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectTab(tab.id) },
                color = if (isActive) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary) else null,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Icon(
                        imageVector = if (tab.target is TerminalTarget.DockerConsole) Icons.Default.Layers else Icons.Default.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isActive) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = if (isActive) CyanPrimary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (isActive && isAlternateScreen) {
                                Text(
                                    text = "[ALT]",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                    ),
                                    color = AmberAccent,
                                )
                            }
                        }
                        if (isActive && !activeWindowTitle.isNullOrBlank()) {
                            Text(
                                text = activeWindowTitle,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = { onCloseTab(tab.id) },
                        modifier = Modifier.size(18.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close Tab",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalModifierBar(
    isCtrlActive: Boolean,
    isAltActive: Boolean,
    onSpecialKey: (SpecialTerminalKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModifierKeyButton("ESC", onClick = { onSpecialKey(SpecialTerminalKey.ESC) })
        ModifierKeyButton("TAB", onClick = { onSpecialKey(SpecialTerminalKey.TAB) })
        ModifierKeyButton(
            "CTRL",
            isActive = isCtrlActive,
            onClick = { onSpecialKey(SpecialTerminalKey.TOGGLE_CTRL) }
        )
        ModifierKeyButton(
            "ALT",
            isActive = isAltActive,
            onClick = { onSpecialKey(SpecialTerminalKey.TOGGLE_ALT) }
        )
        ModifierKeyButton("CTRL+C", onClick = { onSpecialKey(SpecialTerminalKey.CTRL_C) })
        ModifierKeyButton("↑", onClick = { onSpecialKey(SpecialTerminalKey.UP) })
        ModifierKeyButton("↓", onClick = { onSpecialKey(SpecialTerminalKey.DOWN) })
        ModifierKeyButton("←", onClick = { onSpecialKey(SpecialTerminalKey.LEFT) })
        ModifierKeyButton("→", onClick = { onSpecialKey(SpecialTerminalKey.RIGHT) })
        ModifierKeyButton("TMUX", onClick = { onSpecialKey(SpecialTerminalKey.TMUX_ATTACH) })
    }
}

@Composable
fun ModifierKeyButton(
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        color = if (isActive) CyanPrimary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
            color = if (isActive) Color.Black else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
