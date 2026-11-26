package com.nightcinder.helix.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Vt100ScreenBufferTest {

    @Test
    fun plainText_and_controlCharacters_crLfBackspaceTab() {
        val buffer = Vt100ScreenBuffer(initialCols = 40, initialRows = 10)
        buffer.write("hello\r\nworld")

        assertEquals("hello", buffer.getLineText(0))
        assertEquals("world", buffer.getLineText(1))
        assertEquals(1, buffer.cursorRow)
        assertEquals(5, buffer.cursorCol)

        // Backspace + overwrite
        buffer.write("\b\bLD!")
        assertEquals("worLD!", buffer.getLineText(1))

        // Carriage return + overwrite start of line
        buffer.write("\rWOR")
        assertEquals("WORLD!", buffer.getLineText(1))

        // Tab advances to next 8-col stop
        buffer.write("\r\nA\tB")
        assertEquals("A       B", buffer.getLineText(2))
    }

    @Test
    fun cursorPositioning_cupAndRelativeMoves() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 6)
        // Move to row 3, col 5 (1-based) -> 0-based (2, 4)
        buffer.write("\u001B[3;5H*")
        assertEquals(2, buffer.cursorRow)
        assertEquals(5, buffer.cursorCol)
        assertEquals('*', buffer.getCell(2, 4).char)

        // Move up 1, left 2 -> row 1, col 3 -> write '#'
        buffer.write("\u001B[1A\u001B[2D#")
        assertEquals('#', buffer.getCell(1, 3).char)

        // Save and restore cursor
        buffer.write("\u001B[s\u001B[6;1Hbottom\u001B[u!")
        assertEquals("bottom", buffer.getLineText(5))
        assertEquals('!', buffer.getCell(1, 4).char)
    }

    @Test
    fun csiErase_inLineAndDisplay() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 4)
        buffer.write("0123456789\r\nabcdefghij")

        // Move to row 1 (0-based 0), col 5 (0-based 4) and erase to end of line (CSI 0K)
        buffer.write("\u001B[1;5H\u001B[K")
        assertEquals("0123", buffer.getLineText(0))
        assertEquals("abcdefghij", buffer.getLineText(1))

        // Erase entire line 2 (CSI 2K)
        buffer.write("\u001B[2;1H\u001B[2K")
        assertEquals("", buffer.getLineText(1))

        // Write again and test Erase in Display (CSI 2J)
        buffer.write("fresh line")
        buffer.write("\u001B[2J")
        assertEquals("", buffer.getVisibleText())
    }

    @Test
    fun sgrColorsAndStyles_appliedAndReset() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 4)
        // Bold (1), Underline (4), Red FG (31 -> index 1), Blue BG (44 -> index 4), then Bright Green FG (92 -> index 10), then reset (0)
        buffer.write("\u001B[1;4;31;44mR\u001B[92mG\u001B[0mN")

        val cellR = buffer.getCell(0, 0)
        assertEquals('R', cellR.char)
        assertTrue(cellR.bold)
        assertTrue(cellR.underline)
        assertEquals(1, cellR.fgColor)
        assertEquals(4, cellR.bgColor)

        val cellG = buffer.getCell(0, 1)
        assertEquals('G', cellG.char)
        assertTrue(cellG.bold)
        assertEquals(10, cellG.fgColor)
        assertEquals(4, cellG.bgColor)

        val cellN = buffer.getCell(0, 2)
        assertEquals('N', cellN.char)
        assertFalse(cellN.bold)
        assertFalse(cellN.underline)
        assertEquals(TerminalCell.DEFAULT_FG, cellN.fgColor)
        assertEquals(TerminalCell.DEFAULT_BG, cellN.bgColor)
    }

    @Test
    fun scrollback_capturesLinesScrolledOffTop() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 3, maxScrollbackLines = 10)
        buffer.write("line1\r\nline2\r\nline3\r\nline4\r\nline5")

        val scrollback = buffer.getScrollbackLines()
        assertEquals(2, scrollback.size)
        assertEquals("line1", scrollback[0].text)
        assertEquals("line2", scrollback[1].text)

        assertEquals("line3", buffer.getLineText(0))
        assertEquals("line4", buffer.getLineText(1))
        assertEquals("line5", buffer.getLineText(2))
    }

    @Test
    fun resize_updatesDimensionsAndPreservesContent() {
        val buffer = Vt100ScreenBuffer(initialCols = 20, initialRows = 5)
        buffer.write("row0\r\nrow1\r\nrow2\r\nrow3")

        buffer.resize(newCols = 100, newRows = 30)
        assertEquals(100, buffer.cols)
        assertEquals(30, buffer.rows)
        assertEquals("row0", buffer.getLineText(0))
        assertEquals("row3", buffer.getLineText(3))

        // Shrinking rows below cursorRow shifts top lines into scrollback
        buffer.resize(newCols = 40, newRows = 2)
        assertEquals(40, buffer.cols)
        assertEquals(2, buffer.rows)
        assertTrue(buffer.cursorRow in 0 until 2)
        assertEquals("row2", buffer.getLineText(0))
        assertEquals("row3", buffer.getLineText(1))
        assertEquals(2, buffer.getScrollbackLines().size)
    }

    @Test
    fun keyEncoder_encodesModifiersAndSpecialKeys() {
        val buffer = Vt100ScreenBuffer()

        assertEquals("\u001B", buffer.encodeSpecial(Vt100SpecialKey.ESC))
        assertEquals("\t", buffer.encodeSpecial(Vt100SpecialKey.TAB))
        assertEquals("\u0003", buffer.encodeCtrl('c'))
        assertEquals("\u0003", buffer.encodeCtrl('C'))
        assertEquals("\u0004", buffer.encodeCtrl('d'))
        assertEquals("\u001A", buffer.encodeCtrl('z'))
        assertEquals("\u001Bx", buffer.encodeAlt('x'))
        assertEquals("\u001B[A", buffer.encodeArrow(Vt100ArrowKey.UP))
        assertEquals("\u001B[B", buffer.encodeArrow(Vt100ArrowKey.DOWN))
        assertEquals("\u001B[C", buffer.encodeArrow(Vt100ArrowKey.RIGHT))
        assertEquals("\u001B[D", buffer.encodeArrow(Vt100ArrowKey.LEFT))
    }
}
