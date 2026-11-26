package com.nightcinder.helix.terminal

import com.nightcinder.helix.core.model.TerminalSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TerminalCell(
    val char: Char = ' ',
    val fgColor: Int = DEFAULT_FG,
    val bgColor: Int = DEFAULT_BG,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
) {
    companion object {
        const val DEFAULT_FG: Int = -1
        const val DEFAULT_BG: Int = -1
        const val TRUECOLOR_FLAG: Int = 0x01000000
        val EMPTY = TerminalCell()

        fun encodeTrueColor(r: Int, g: Int, b: Int): Int {
            val rr = r.coerceIn(0, 255)
            val gg = g.coerceIn(0, 255)
            val bb = b.coerceIn(0, 255)
            return TRUECOLOR_FLAG or (rr shl 16) or (gg shl 8) or bb
        }

        fun isTrueColor(color: Int): Boolean =
            color >= 0 && (color and TRUECOLOR_FLAG) != 0

        fun extractRgb(color: Int): Int =
            color and 0x00FFFFFF
    }
}

data class TerminalLine(
    val cells: List<TerminalCell>,
) {
    val text: String
        get() = buildString(cells.size) {
            for (cell in cells) {
                append(cell.char)
            }
        }.trimEnd()
}

data class TerminalScreenSnapshot(
    val cols: Int,
    val rows: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val lines: List<TerminalLine>,
    val scrollback: List<TerminalLine>,
    val visibleText: String,
    val isAlternateScreen: Boolean = false,
    val title: String? = null,
)

enum class Vt100ArrowKey(val escapeSequence: String) {
    UP("\u001B[A"),
    DOWN("\u001B[B"),
    RIGHT("\u001B[C"),
    LEFT("\u001B[D"),
}

enum class Vt100SpecialKey(val sequence: String) {
    ESC("\u001B"),
    TAB("\t"),
    ENTER("\r"),
    BACKSPACE("\u007F"),
    UP("\u001B[A"),
    DOWN("\u001B[B"),
    RIGHT("\u001B[C"),
    LEFT("\u001B[D"),
    HOME("\u001B[H"),
    END("\u001B[F"),
    PAGE_UP("\u001B[5~"),
    PAGE_DOWN("\u001B[6~"),
    DELETE("\u001B[3~"),
    INSERT("\u001B[2~"),
    F1("\u001BOP"),
    F2("\u001BOQ"),
    F3("\u001BOR"),
    F4("\u001BOS"),
    F5("\u001B[15~"),
    F6("\u001B[17~"),
    F7("\u001B[18~"),
    F8("\u001B[19~"),
    F9("\u001B[20~"),
    F10("\u001B[21~"),
    F11("\u001B[23~"),
    F12("\u001B[24~"),
    CTRL_C("\u0003"),
    CTRL_D("\u0004"),
    CTRL_Z("\u001A"),
    CTRL_L("\u000C"),
    TMUX_PREFIX("\u0002"),
}

/**
 * Pure-Kotlin VT100/ANSI key and modifier sequence encoder.
 */
object Vt100KeyEncoder {
    const val ESC = "\u001B"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val BACKSPACE = "\u007F"
    const val ARROW_UP = "\u001B[A"
    const val ARROW_DOWN = "\u001B[B"
    const val ARROW_RIGHT = "\u001B[C"
    const val ARROW_LEFT = "\u001B[D"

    fun encodeSpecial(key: Vt100SpecialKey): String = key.sequence

    fun encodeArrow(arrow: Vt100ArrowKey): String = arrow.escapeSequence

    fun encodeCtrl(char: Char): String {
        val upper = char.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> (upper.code - 64).toChar().toString()
            upper == '@' || upper == ' ' -> "\u0000"
            upper == '[' -> "\u001B"
            upper == '\\' -> "\u001C"
            upper == ']' -> "\u001D"
            upper == '^' -> "\u001E"
            upper == '_' || upper == '/' -> "\u001F"
            else -> char.toString()
        }
    }

    fun encodeAlt(char: Char): String = "\u001B$char"

    fun encodeModifiedInput(
        input: String,
        ctrlActive: Boolean = false,
        altActive: Boolean = false,
    ): String {
        if (input.isEmpty()) return ""
        if (!ctrlActive && !altActive) return input
        val first = input.first()
        val base = if (ctrlActive) encodeCtrl(first) else input
        return if (altActive) "\u001B$base" else base
    }
}

/**
 * Pure-Kotlin VT100/xterm terminal screen buffer supporting:
 * - Cursor positioning (`H`, `f`, `A`, `B`, `C`, `D`, `E`, `F`, `G`, `d`, `s`, `u`, `ESC 7`, `ESC 8`)
 * - Control chars (`\r`, `\n`, `\b`, `\t`)
 * - CSI erase in display (`J`: 0, 1, 2, 3), erase in line (`K`: 0, 1, 2), and erase chars (`X`)
 * - Line & character insert/delete (`L`, `M`, `@`, `P`) and region scroll (`S`, `T`, `ESC M`)
 * - DECSTBM scroll regions (`CSI top ; bottom r`)
 * - Alternate Screen Buffer (`CSI ? 1049 h/l`, `CSI ? 47 h/l`, `CSI ? 1047 h/l`)
 * - SGR attributes (reset, bold, dim, italic, underline, inverse, strikethrough, 16/256-color & 24-bit TrueColor)
 * - OSC window title (`ESC ] 0;title BEL` / `ESC ] 2;title BEL` / `ESC \`)
 * - Scrollback history ring & dynamic `resize(cols, rows)`
 */
class Vt100ScreenBuffer(
    initialCols: Int = TerminalSize.DEFAULT_COLS,
    initialRows: Int = TerminalSize.DEFAULT_ROWS,
    val maxScrollbackLines: Int = 500,
) {
    var cols: Int = initialCols.coerceAtLeast(1)
        private set

    var rows: Int = initialRows.coerceAtLeast(1)
        private set

    var cursorRow: Int = 0
        private set

    var cursorCol: Int = 0
        private set

    var currentFgColor: Int = TerminalCell.DEFAULT_FG
        private set

    var currentBgColor: Int = TerminalCell.DEFAULT_BG
        private set

    var isBold: Boolean = false
        private set

    var isDim: Boolean = false
        private set

    var isItalic: Boolean = false
        private set

    var isUnderline: Boolean = false
        private set

    var isInverse: Boolean = false
        private set

    var isStrikethrough: Boolean = false
        private set

    var isAlternateScreen: Boolean = false
        private set

    var scrollTop: Int = 0
        private set

    var scrollBottom: Int = rows - 1
        private set

    var windowTitle: String? = null
        private set

    private var savedCursorRow: Int = 0
    private var savedCursorCol: Int = 0

    // Saved main screen state for alternate screen buffer (1049 / 47 / 1047)
    private var mainGrid: Array<Array<TerminalCell>>? = null
    private var mainCursorRow: Int = 0
    private var mainCursorCol: Int = 0
    private var mainSavedCursorRow: Int = 0
    private var mainSavedCursorCol: Int = 0

    private var grid: Array<Array<TerminalCell>> = createGrid(cols, rows)
    private val scrollbackBuffer: ArrayDeque<TerminalLine> = ArrayDeque()

    // Parser state machine
    private enum class ParseState {
        GROUND,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESC,
    }

    private var parseState: ParseState = ParseState.GROUND
    private val csiBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<TerminalScreenSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun write(data: String) {
        if (data.isEmpty()) return
        for (i in data.indices) {
            processChar(data[i])
        }
        _snapshot.value = buildSnapshot()
    }

    /**
     * Feeds [data] without rebuilding the published snapshot (which copies scrollback).
     * Callers must follow up with [publishSnapshot] at their own cadence.
     */
    @Synchronized
    fun writeWithoutPublishing(data: String) {
        if (data.isEmpty()) return
        for (i in data.indices) {
            processChar(data[i])
        }
    }

    @Synchronized
    fun publishSnapshot() {
        _snapshot.value = buildSnapshot()
    }

    fun append(data: String) = write(data)

    fun feed(data: String) = write(data)

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val targetCols = newCols.coerceAtLeast(1)
        val targetRows = newRows.coerceAtLeast(1)
        if (targetCols == cols && targetRows == rows) return

        val newGrid = createGrid(targetCols, targetRows)

        // If shrinking rows and cursor is below newRows, push excess top rows to scrollback (main screen only)
        val rowShift = if (cursorRow >= targetRows) {
            (cursorRow - targetRows + 1).coerceAtMost(rows)
        } else {
            0
        }

        if (!isAlternateScreen) {
            for (r in 0 until rowShift) {
                pushScrollback(grid[r])
            }
        }

        val copyRows = (rows - rowShift).coerceAtMost(targetRows)
        for (r in 0 until copyRows) {
            val srcRow = grid[r + rowShift]
            val dstRow = newGrid[r]
            val copyCols = cols.coerceAtMost(targetCols)
            for (c in 0 until copyCols) {
                dstRow[c] = srcRow[c]
            }
        }

        // Also resize saved mainGrid if currently in alternate screen
        mainGrid?.let { savedMain ->
            val resizedMain = createGrid(targetCols, targetRows)
            val mainCopyRows = savedMain.size.coerceAtMost(targetRows)
            for (r in 0 until mainCopyRows) {
                val srcRow = savedMain[r]
                val dstRow = resizedMain[r]
                val mainCopyCols = srcRow.size.coerceAtMost(targetCols)
                for (c in 0 until mainCopyCols) {
                    dstRow[c] = srcRow[c]
                }
            }
            mainGrid = resizedMain
            mainCursorRow = mainCursorRow.coerceIn(0, targetRows - 1)
            mainCursorCol = mainCursorCol.coerceIn(0, targetCols - 1)
        }

        cols = targetCols
        rows = targetRows
        grid = newGrid
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = (cursorRow - rowShift).coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        savedCursorRow = savedCursorRow.coerceIn(0, rows - 1)
        savedCursorCol = savedCursorCol.coerceIn(0, cols - 1)
        _snapshot.value = buildSnapshot()
    }

    @Synchronized
    fun clear(clearScrollback: Boolean = false) {
        grid = createGrid(cols, rows)
        cursorRow = 0
        cursorCol = 0
        if (clearScrollback) {
            scrollbackBuffer.clear()
        }
        _snapshot.value = buildSnapshot()
    }

    @Synchronized
    fun reset() {
        grid = createGrid(cols, rows)
        mainGrid = null
        isAlternateScreen = false
        scrollbackBuffer.clear()
        cursorRow = 0
        cursorCol = 0
        savedCursorRow = 0
        savedCursorCol = 0
        mainCursorRow = 0
        mainCursorCol = 0
        mainSavedCursorRow = 0
        mainSavedCursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        windowTitle = null
        resetAttributes()
        parseState = ParseState.GROUND
        csiBuffer.clear()
        oscBuffer.clear()
        _snapshot.value = buildSnapshot()
    }

    @Synchronized
    fun getCell(row: Int, col: Int): TerminalCell {
        if (row !in 0 until rows || col !in 0 until cols) return TerminalCell.EMPTY
        return grid[row][col]
    }

    @Synchronized
    fun getLineText(row: Int): String {
        if (row !in 0 until rows) return ""
        return TerminalLine(grid[row].toList()).text
    }

    @Synchronized
    fun getVisibleLines(): List<TerminalLine> =
        grid.map { row -> TerminalLine(row.toList()) }

    @Synchronized
    fun getScrollbackLines(): List<TerminalLine> =
        scrollbackBuffer.toList()

    @Synchronized
    fun getVisibleText(): String =
        getVisibleLines().joinToString("\n") { it.text }.trimEnd('\n')

    @Synchronized
    fun getFullTextWithScrollback(): String {
        val all = if (isAlternateScreen) {
            getVisibleLines()
        } else {
            scrollbackBuffer + getVisibleLines()
        }
        return all.joinToString("\n") { it.text }.trimEnd('\n')
    }

    // Convenience key encoders directly accessible on buffer instance
    fun encodeCtrl(char: Char): String = Vt100KeyEncoder.encodeCtrl(char)
    fun encodeAlt(char: Char): String = Vt100KeyEncoder.encodeAlt(char)
    fun encodeArrow(arrow: Vt100ArrowKey): String = Vt100KeyEncoder.encodeArrow(arrow)
    fun encodeSpecial(key: Vt100SpecialKey): String = Vt100KeyEncoder.encodeSpecial(key)

    // --- Internal VT100/xterm State Machine ---

    private fun processChar(ch: Char) {
        when (parseState) {
            ParseState.GROUND -> processGroundChar(ch)
            ParseState.ESCAPE -> processEscapeChar(ch)
            ParseState.CSI -> processCsiChar(ch)
            ParseState.OSC -> {
                when (ch) {
                    '\u0007' -> {
                        handleOscCommand(oscBuffer.toString())
                        parseState = ParseState.GROUND
                    }
                    '\u001B' -> {
                        parseState = ParseState.OSC_ESC
                    }
                    else -> {
                        if (oscBuffer.length < 512) {
                            oscBuffer.append(ch)
                        }
                    }
                }
            }
            ParseState.OSC_ESC -> {
                handleOscCommand(oscBuffer.toString())
                parseState = ParseState.GROUND
                if (ch != '\\') {
                    processEscapeChar(ch)
                }
            }
        }
    }

    private fun processGroundChar(ch: Char) {
        when (ch) {
            '\u001B' -> {
                parseState = ParseState.ESCAPE
            }
            '\r' -> {
                cursorCol = 0
            }
            '\n' -> {
                lineFeed()
            }
            '\b', '\u007F' -> {
                if (cursorCol > 0) {
                    cursorCol--
                }
            }
            '\t' -> {
                val nextStop = ((cursorCol / 8) + 1) * 8
                cursorCol = nextStop.coerceAtMost(cols - 1)
            }
            '\u0007' -> {
                // BEL - ignore in ground state
            }
            else -> {
                if (ch.code >= 0x20) {
                    putGraphicChar(ch)
                }
            }
        }
    }

    private fun processEscapeChar(ch: Char) {
        when (ch) {
            '[' -> {
                csiBuffer.clear()
                parseState = ParseState.CSI
            }
            ']' -> {
                oscBuffer.clear()
                parseState = ParseState.OSC
            }
            'c' -> {
                // Full terminal reset (RIS)
                reset()
            }
            '7' -> {
                // DECSC - Save Cursor
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
                parseState = ParseState.GROUND
            }
            '8' -> {
                // DECRC - Restore Cursor
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                parseState = ParseState.GROUND
            }
            'M' -> {
                // RI - Reverse Index (respects scrollTop..scrollBottom)
                if (cursorRow == scrollTop) {
                    scrollRegionDown(scrollTop, scrollBottom, 1)
                } else if (cursorRow > 0) {
                    cursorRow--
                }
                parseState = ParseState.GROUND
            }
            'D' -> {
                // IND - Index
                lineFeed()
                parseState = ParseState.GROUND
            }
            'E' -> {
                // NEL - Next Line
                cursorCol = 0
                lineFeed()
                parseState = ParseState.GROUND
            }
            else -> {
                parseState = ParseState.GROUND
            }
        }
    }

    private fun processCsiChar(ch: Char) {
        if (ch.code in 0x40..0x7E) {
            val rawParams = csiBuffer.toString()
            handleCsiCommand(rawParams, ch)
            parseState = ParseState.GROUND
        } else {
            csiBuffer.append(ch)
            if (csiBuffer.length > 128) {
                parseState = ParseState.GROUND
            }
        }
    }

    private fun handleOscCommand(raw: String) {
        val semiIndex = raw.indexOf(';')
        if (semiIndex <= 0) return
        val code = raw.substring(0, semiIndex).trim()
        val payload = raw.substring(semiIndex + 1)
        if (code == "0" || code == "2") {
            windowTitle = payload
        }
    }

    private fun handleCsiCommand(rawParams: String, finalChar: Char) {
        val isPrivateMode = rawParams.startsWith("?")
        val cleanParams = rawParams.trimStart('?', '>', '!')
        val params = if (cleanParams.isEmpty()) {
            emptyList()
        } else {
            cleanParams.split(';').map { it.trim().toIntOrNull() ?: 0 }
        }

        fun param(index: Int, default: Int): Int {
            val v = params.getOrNull(index) ?: return default
            return if (v == 0 && default != 0) default else v
        }

        if (isPrivateMode && (finalChar == 'h' || finalChar == 'l')) {
            handleDecPrivateMode(params, setMode = (finalChar == 'h'))
            return
        }

        when (finalChar) {
            'A' -> { // CUU - Cursor Up
                val n = param(0, 1).coerceAtLeast(1)
                cursorRow = (cursorRow - n).coerceAtLeast(0)
            }
            'B' -> { // CUD - Cursor Down
                val n = param(0, 1).coerceAtLeast(1)
                cursorRow = (cursorRow + n).coerceAtMost(rows - 1)
            }
            'C' -> { // CUF - Cursor Forward
                val n = param(0, 1).coerceAtLeast(1)
                cursorCol = (cursorCol + n).coerceAtMost(cols - 1)
            }
            'D' -> { // CUB - Cursor Back
                val n = param(0, 1).coerceAtLeast(1)
                cursorCol = (cursorCol - n).coerceAtLeast(0)
            }
            'E' -> { // CNL - Cursor Next Line
                val n = param(0, 1).coerceAtLeast(1)
                cursorRow = (cursorRow + n).coerceAtMost(rows - 1)
                cursorCol = 0
            }
            'F' -> { // CPL - Cursor Previous Line
                val n = param(0, 1).coerceAtLeast(1)
                cursorRow = (cursorRow - n).coerceAtLeast(0)
                cursorCol = 0
            }
            'G', '`' -> { // CHA - Cursor Horizontal Absolute (1-based)
                val col = param(0, 1) - 1
                cursorCol = col.coerceIn(0, cols - 1)
            }
            'd' -> { // VPA - Vertical Line Position Absolute (1-based)
                val row = param(0, 1) - 1
                cursorRow = row.coerceIn(0, rows - 1)
            }
            'H', 'f' -> { // CUP / HVP - Cursor Position (1-based row;col)
                val targetRow = (param(0, 1) - 1).coerceIn(0, rows - 1)
                val targetCol = (param(1, 1) - 1).coerceIn(0, cols - 1)
                cursorRow = targetRow
                cursorCol = targetCol
            }
            'J' -> { // ED - Erase in Display
                val mode = params.getOrNull(0) ?: 0
                eraseInDisplay(mode)
            }
            'K' -> { // EL - Erase in Line
                val mode = params.getOrNull(0) ?: 0
                eraseInLine(mode)
            }
            'X' -> { // ECH - Erase Characters
                val n = param(0, 1).coerceAtLeast(1)
                eraseCharacters(n)
            }
            '@' -> { // ICH - Insert Blank Characters
                val n = param(0, 1).coerceAtLeast(1)
                insertCharacters(n)
            }
            'P' -> { // DCH - Delete Characters
                val n = param(0, 1).coerceAtLeast(1)
                deleteCharacters(n)
            }
            'L' -> { // IL - Insert Lines
                val n = param(0, 1).coerceAtLeast(1)
                insertLines(n)
            }
            'M' -> { // DL - Delete Lines
                val n = param(0, 1).coerceAtLeast(1)
                deleteLines(n)
            }
            'S' -> { // SU - Scroll Up
                val n = param(0, 1).coerceAtLeast(1)
                scrollRegionUp(scrollTop, scrollBottom, n, pushToScrollback = false)
            }
            'T' -> { // SD - Scroll Down
                val n = param(0, 1).coerceAtLeast(1)
                scrollRegionDown(scrollTop, scrollBottom, n)
            }
            'r' -> { // DECSTBM - Set Top and Bottom Margins (1-based)
                if (params.isEmpty()) {
                    scrollTop = 0
                    scrollBottom = rows - 1
                } else {
                    val top = (param(0, 1) - 1).coerceIn(0, rows - 1)
                    val bottom = (param(1, rows) - 1).coerceIn(0, rows - 1)
                    if (top < bottom) {
                        scrollTop = top
                        scrollBottom = bottom
                    }
                }
                cursorRow = 0
                cursorCol = 0
            }
            'm' -> { // SGR - Select Graphic Rendition
                applySgr(if (params.isEmpty()) listOf(0) else params)
            }
            's' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
            }
            'u' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
            }
        }
    }

    private fun handleDecPrivateMode(params: List<Int>, setMode: Boolean) {
        for (mode in params) {
            when (mode) {
                47, 1047, 1049 -> {
                    if (setMode) {
                        enterAlternateScreen()
                    } else {
                        exitAlternateScreen()
                    }
                }
            }
        }
    }

    private fun enterAlternateScreen() {
        if (!isAlternateScreen) {
            mainGrid = Array(rows) { r -> grid[r].clone() }
            mainCursorRow = cursorRow
            mainCursorCol = cursorCol
            mainSavedCursorRow = savedCursorRow
            mainSavedCursorCol = savedCursorCol
            isAlternateScreen = true
        }
        grid = createGrid(cols, rows)
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun exitAlternateScreen() {
        if (!isAlternateScreen) return
        val restored = mainGrid
        if (restored != null && restored.size == rows && restored[0].size == cols) {
            grid = restored
        } else if (restored != null) {
            val newGrid = createGrid(cols, rows)
            val copyRows = restored.size.coerceAtMost(rows)
            for (r in 0 until copyRows) {
                val copyCols = restored[r].size.coerceAtMost(cols)
                for (c in 0 until copyCols) {
                    newGrid[r][c] = restored[r][c]
                }
            }
            grid = newGrid
        } else {
            grid = createGrid(cols, rows)
        }
        mainGrid = null
        isAlternateScreen = false
        cursorRow = mainCursorRow.coerceIn(0, rows - 1)
        cursorCol = mainCursorCol.coerceIn(0, cols - 1)
        savedCursorRow = mainSavedCursorRow.coerceIn(0, rows - 1)
        savedCursorCol = mainSavedCursorCol.coerceIn(0, cols - 1)
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun insertCharacters(count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        val row = grid[cursorRow]
        for (c in (cols - 1) downTo (cursorCol + n)) {
            row[c] = row[c - n]
        }
        for (c in cursorCol until (cursorCol + n)) {
            row[c] = currentBlankCell()
        }
    }

    private fun deleteCharacters(count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        val row = grid[cursorRow]
        for (c in cursorCol until (cols - n)) {
            row[c] = row[c + n]
        }
        for (c in (cols - n) until cols) {
            row[c] = currentBlankCell()
        }
    }

    private fun eraseCharacters(count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        val row = grid[cursorRow]
        for (c in cursorCol until (cursorCol + n)) {
            row[c] = currentBlankCell()
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val maxShift = scrollBottom - cursorRow + 1
        val n = count.coerceIn(1, maxShift)
        repeat(n) {
            for (r in scrollBottom downTo (cursorRow + 1)) {
                grid[r] = grid[r - 1]
            }
            grid[cursorRow] = Array(cols) { currentBlankCell() }
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val maxShift = scrollBottom - cursorRow + 1
        val n = count.coerceIn(1, maxShift)
        repeat(n) {
            for (r in cursorRow until scrollBottom) {
                grid[r] = grid[r + 1]
            }
            grid[scrollBottom] = Array(cols) { currentBlankCell() }
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                // Cursor to end of screen
                for (c in cursorCol until cols) {
                    grid[cursorRow][c] = currentBlankCell()
                }
                for (r in (cursorRow + 1) until rows) {
                    for (c in 0 until cols) {
                        grid[r][c] = currentBlankCell()
                    }
                }
            }
            1 -> {
                // Start of screen to cursor (inclusive)
                for (r in 0 until cursorRow) {
                    for (c in 0 until cols) {
                        grid[r][c] = currentBlankCell()
                    }
                }
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
                    grid[cursorRow][c] = currentBlankCell()
                }
            }
            2 -> {
                // Entire screen
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        grid[r][c] = currentBlankCell()
                    }
                }
            }
            3 -> {
                // Entire screen + scrollback
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        grid[r][c] = currentBlankCell()
                    }
                }
                if (!isAlternateScreen) {
                    scrollbackBuffer.clear()
                }
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> {
                // Cursor to end of line
                for (c in cursorCol until cols) {
                    grid[cursorRow][c] = currentBlankCell()
                }
            }
            1 -> {
                // Start of line to cursor (inclusive)
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
                    grid[cursorRow][c] = currentBlankCell()
                }
            }
            2 -> {
                // Entire line
                for (c in 0 until cols) {
                    grid[cursorRow][c] = currentBlankCell()
                }
            }
        }
    }

    private fun applySgr(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0 -> resetAttributes()
                1 -> isBold = true
                2 -> isDim = true
                3 -> isItalic = true
                4 -> isUnderline = true
                7 -> isInverse = true
                9 -> isStrikethrough = true
                22 -> {
                    isBold = false
                    isDim = false
                }
                23 -> isItalic = false
                24 -> isUnderline = false
                27 -> isInverse = false
                29 -> isStrikethrough = false
                in 30..37 -> currentFgColor = code - 30
                39 -> currentFgColor = TerminalCell.DEFAULT_FG
                in 40..47 -> currentBgColor = code - 40
                49 -> currentBgColor = TerminalCell.DEFAULT_BG
                in 90..97 -> currentFgColor = (code - 90) + 8
                in 100..107 -> currentBgColor = (code - 100) + 8
                38 -> {
                    if (i + 2 < params.size && params[i + 1] == 5) {
                        currentFgColor = params[i + 2].coerceIn(0, 255)
                        i += 2
                    } else if (i + 4 < params.size && params[i + 1] == 2) {
                        val r = params[i + 2]
                        val g = params[i + 3]
                        val b = params[i + 4]
                        currentFgColor = TerminalCell.encodeTrueColor(r, g, b)
                        i += 4
                    }
                }
                48 -> {
                    if (i + 2 < params.size && params[i + 1] == 5) {
                        currentBgColor = params[i + 2].coerceIn(0, 255)
                        i += 2
                    } else if (i + 4 < params.size && params[i + 1] == 2) {
                        val r = params[i + 2]
                        val g = params[i + 3]
                        val b = params[i + 4]
                        currentBgColor = TerminalCell.encodeTrueColor(r, g, b)
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun resetAttributes() {
        currentFgColor = TerminalCell.DEFAULT_FG
        currentBgColor = TerminalCell.DEFAULT_BG
        isBold = false
        isDim = false
        isItalic = false
        isUnderline = false
        isInverse = false
        isStrikethrough = false
    }

    private fun putGraphicChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        grid[cursorRow][cursorCol] = TerminalCell(
            char = ch,
            fgColor = currentFgColor,
            bgColor = currentBgColor,
            bold = isBold,
            underline = isUnderline,
            inverse = isInverse,
            dim = isDim,
            italic = isItalic,
            strikethrough = isStrikethrough,
        )
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollRegionUp(scrollTop, scrollBottom, 1, pushToScrollback = (scrollTop == 0 && !isAlternateScreen))
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun scrollRegionUp(top: Int, bottom: Int, lines: Int, pushToScrollback: Boolean) {
        val validTop = top.coerceIn(0, rows - 1)
        val validBottom = bottom.coerceIn(validTop, rows - 1)
        repeat(lines.coerceAtLeast(1)) {
            if (pushToScrollback && validTop == 0 && !isAlternateScreen) {
                pushScrollback(grid[0])
            }
            for (r in (validTop + 1)..validBottom) {
                grid[r - 1] = grid[r]
            }
            grid[validBottom] = Array(cols) { TerminalCell.EMPTY }
        }
    }

    private fun scrollRegionDown(top: Int, bottom: Int, lines: Int) {
        val validTop = top.coerceIn(0, rows - 1)
        val validBottom = bottom.coerceIn(validTop, rows - 1)
        repeat(lines.coerceAtLeast(1)) {
            for (r in validBottom downTo (validTop + 1)) {
                grid[r] = grid[r - 1]
            }
            grid[validTop] = Array(cols) { TerminalCell.EMPTY }
        }
    }

    private fun pushScrollback(rowCells: Array<TerminalCell>) {
        if (isAlternateScreen || maxScrollbackLines <= 0) return
        if (scrollbackBuffer.size >= maxScrollbackLines) {
            scrollbackBuffer.removeFirst()
        }
        scrollbackBuffer.addLast(TerminalLine(rowCells.toList()))
    }

    private fun currentBlankCell(): TerminalCell =
        TerminalCell(
            char = ' ',
            fgColor = currentFgColor,
            bgColor = currentBgColor,
        )

    private fun buildSnapshot(): TerminalScreenSnapshot {
        val visible = grid.map { row -> TerminalLine(row.toList()) }
        val scrollback = scrollbackBuffer.toList()
        val text = visible.joinToString("\n") { it.text }.trimEnd('\n')
        return TerminalScreenSnapshot(
            cols = cols,
            rows = rows,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            lines = visible,
            scrollback = scrollback,
            visibleText = text,
            isAlternateScreen = isAlternateScreen,
            title = windowTitle,
        )
    }

    private fun createGrid(c: Int, r: Int): Array<Array<TerminalCell>> =
        Array(r) { Array(c) { TerminalCell.EMPTY } }
}
