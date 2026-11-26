package com.nightcinder.helix.terminal

import com.nightcinder.helix.ui.terminal.buildTerminalAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Vt100XtermUpgradeTest {

    @Test
    fun trueColor24Bit_andExtendedSgrStyles_parsedAndReset() {
        val buffer = Vt100ScreenBuffer(initialCols = 30, initialRows = 5)
        // TrueColor FG (18, 52, 86), TrueColor BG (200, 100, 50), Dim (2), Italic (3), Strikethrough (9)
        buffer.write("\u001B[2;3;9;38;2;18;52;86;48;2;200;100;50mX\u001B[22;23;29;39;49mY")

        val cellX = buffer.getCell(0, 0)
        assertEquals('X', cellX.char)
        assertTrue(cellX.dim)
        assertTrue(cellX.italic)
        assertTrue(cellX.strikethrough)
        assertTrue(TerminalCell.isTrueColor(cellX.fgColor))
        assertTrue(TerminalCell.isTrueColor(cellX.bgColor))
        assertEquals((18 shl 16) or (52 shl 8) or 86, TerminalCell.extractRgb(cellX.fgColor))
        assertEquals((200 shl 16) or (100 shl 8) or 50, TerminalCell.extractRgb(cellX.bgColor))

        val cellY = buffer.getCell(0, 1)
        assertEquals('Y', cellY.char)
        assertFalse(cellY.dim)
        assertFalse(cellY.italic)
        assertFalse(cellY.strikethrough)
        assertEquals(TerminalCell.DEFAULT_FG, cellY.fgColor)
        assertEquals(TerminalCell.DEFAULT_BG, cellY.bgColor)
    }

    @Test
    fun alternateScreenBuffer_1049_savesAndRestoresMainScreenAndCursor() {
        val buffer = Vt100ScreenBuffer(initialCols = 40, initialRows = 5, maxScrollbackLines = 20)
        buffer.write("shell$ ls -la\r\ntotal 42\r\nshell$ vim notes.txt")
        val savedRow = buffer.cursorRow
        val savedCol = buffer.cursorCol

        // Enter alternate screen (?1049h)
        buffer.write("\u001B[?1049h")
        assertTrue(buffer.isAlternateScreen)
        assertTrue(buffer.snapshot.value.isAlternateScreen)
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
        assertEquals("", buffer.getVisibleText())

        // Draw TUI content in alternate screen and scroll lines (should NOT pollute main scrollback)
        buffer.write("~ VIM BUFFER LINE 1\r\n~ VIM BUFFER LINE 2\r\n~ VIM BUFFER LINE 3\r\n~ VIM BUFFER LINE 4\r\n~ VIM BUFFER LINE 5\r\n~ VIM BUFFER LINE 6")
        assertEquals(0, buffer.getScrollbackLines().size)

        // Exit alternate screen (?1049l)
        buffer.write("\u001B[?1049l")
        assertFalse(buffer.isAlternateScreen)
        assertFalse(buffer.snapshot.value.isAlternateScreen)
        assertEquals(savedRow, buffer.cursorRow)
        assertEquals(savedCol, buffer.cursorCol)
        assertEquals("shell$ ls -la", buffer.getLineText(0))
        assertEquals("total 42", buffer.getLineText(1))
        assertEquals("shell$ vim notes.txt", buffer.getLineText(2))
    }

    @Test
    fun decstbm_scrollRegions_andReverseIndex_stayWithinBounds() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 5)
        buffer.write("HEADER\r\nROW1\r\nROW2\r\nROW3\r\nFOOTER")

        // Set scroll region to rows 2..4 (1-based) -> 0-based 1..3
        buffer.write("\u001B[2;4r")
        assertEquals(1, buffer.scrollTop)
        assertEquals(3, buffer.scrollBottom)
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)

        // Move to row 4 (1-based) -> bottom of scroll region (0-based 3) and emit newline
        buffer.write("\u001B[4;1H\nNEW3")
        assertEquals("HEADER", buffer.getLineText(0))
        assertEquals("ROW2", buffer.getLineText(1))
        assertEquals("ROW3", buffer.getLineText(2))
        assertEquals("NEW3", buffer.getLineText(3))
        assertEquals("FOOTER", buffer.getLineText(4))

        // Move to top of scroll region (row 2, 1-based -> 0-based 1) and send ESC M (Reverse Index)
        buffer.write("\u001B[2;1H\u001BMTOP_INS")
        assertEquals("HEADER", buffer.getLineText(0))
        assertEquals("TOP_INS", buffer.getLineText(1))
        assertEquals("ROW2", buffer.getLineText(2))
        assertEquals("ROW3", buffer.getLineText(3))
        assertEquals("FOOTER", buffer.getLineText(4))
    }

    @Test
    fun insertAndDelete_linesAndCharacters_andEraseChars() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 4)
        buffer.write("ABCDEF\r\nLINE1\r\nLINE2\r\nLINE3")

        // Move to row 1, col 3 (0-based 0, 2 -> 'C') and insert 2 blank chars (CSI 2 @)
        buffer.write("\u001B[1;3H\u001B[2@")
        assertEquals("AB  CDEF", buffer.getLineText(0))

        // Delete 2 chars at col 3 (CSI 2 P) -> back to ABCDEF
        buffer.write("\u001B[2P")
        assertEquals("ABCDEF", buffer.getLineText(0))

        // Erase 2 chars at col 3 (CSI 2 X) -> 'C' and 'D' become spaces
        buffer.write("\u001B[2X")
        assertEquals("AB  EF", buffer.getLineText(0))

        // Insert 1 line at row 2 (1-based -> 0-based 1) via CSI 1 L
        buffer.write("\u001B[2;1H\u001B[1LINS_LINE")
        assertEquals("AB  EF", buffer.getLineText(0))
        assertEquals("INS_LINE", buffer.getLineText(1))
        assertEquals("LINE1", buffer.getLineText(2))
        assertEquals("LINE2", buffer.getLineText(3))

        // Delete 1 line at row 2 (1-based -> 0-based 1) via CSI 1 M
        buffer.write("\u001B[2;1H\u001B[1M")
        assertEquals("AB  EF", buffer.getLineText(0))
        assertEquals("LINE1", buffer.getLineText(1))
        assertEquals("LINE2", buffer.getLineText(2))
        assertEquals("", buffer.getLineText(3))
    }

    @Test
    fun scrollUp_andScrollDown_commandsRespectScrollRegion() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 5)
        buffer.write("TOP\r\nA\r\nB\r\nC\r\nBOT")

        // Set scroll region rows 2..4 (1-based)
        buffer.write("\u001B[2;4r")
        // Scroll Up 1 line (CSI 1 S)
        buffer.write("\u001B[1S")
        assertEquals("TOP", buffer.getLineText(0))
        assertEquals("B", buffer.getLineText(1))
        assertEquals("C", buffer.getLineText(2))
        assertEquals("", buffer.getLineText(3))
        assertEquals("BOT", buffer.getLineText(4))

        // Scroll Down 1 line (CSI 1 T)
        buffer.write("\u001B[1T")
        assertEquals("TOP", buffer.getLineText(0))
        assertEquals("", buffer.getLineText(1))
        assertEquals("B", buffer.getLineText(2))
        assertEquals("C", buffer.getLineText(3))
        assertEquals("BOT", buffer.getLineText(4))
    }

    @Test
    fun oscWindowTitle_parsedWithBelAndStAcrossChunks() {
        val buffer = Vt100ScreenBuffer(initialCols = 40, initialRows = 5)
        assertNull(buffer.windowTitle)

        // OSC 0 with BEL terminator
        buffer.write("\u001B]0;user@core: ~/helix\u0007prompt$ ")
        assertEquals("user@core: ~/helix", buffer.windowTitle)
        assertEquals("user@core: ~/helix", buffer.snapshot.value.title)
        assertEquals("prompt$", buffer.getLineText(0))

        // Multi-chunk OSC 2 with ST (ESC \) terminator
        buffer.write("\u001B]2;htop - ")
        buffer.write("desktop cluster\u001B\\")
        assertEquals("htop - desktop cluster", buffer.windowTitle)
        assertEquals("htop - desktop cluster", buffer.snapshot.value.title)
    }

    @Test
    fun buildTerminalAnnotatedString_rendersColorsAndCursorCleanly() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 4)
        buffer.write("\u001B[1;32mREADY\u001B[0m > ")
        val snap = buffer.snapshot.value

        val annotated = buildTerminalAnnotatedString(
            snapshot = snap,
            isAlternateScreen = snap.isAlternateScreen,
            showCursor = true,
        )
        assertTrue(annotated.text.startsWith("READY > "))
        assertTrue(annotated.spanStyles.isNotEmpty())
    }
}
