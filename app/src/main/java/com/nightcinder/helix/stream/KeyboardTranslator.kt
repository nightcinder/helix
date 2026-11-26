// SPDX-License-Identifier: GPL-3.0-only
// Portions derived from moonlight-android (https://github.com/moonlight-stream/moonlight-android),
// Copyright (C) the Moonlight project contributors, licensed under the GNU General Public License v3.0.

package com.nightcinder.helix.stream

/**
 * Maps Android key codes to the Windows virtual-key codes GameStream/Sunshine expects
 * (port of moonlight-android's KeyboardTranslator). Mapped keys carry the 0x80 high byte that tells
 * the host the value is a VK code; unmapped keys return 0.
 *
 * Android key code values are written as literals so this stays a pure JVM class.
 */
object KeyboardTranslator {
    private const val PREFIX = 0x80

    const val MODIFIER_SHIFT: Byte = 0x01
    const val MODIFIER_CTRL: Byte = 0x02
    const val MODIFIER_ALT: Byte = 0x04
    const val MODIFIER_META: Byte = 0x08

    // android.view.KeyEvent meta state bits
    private const val META_SHIFT_ON = 0x1
    private const val META_ALT_ON = 0x2
    private const val META_CTRL_ON = 0x1000
    private const val META_META_ON = 0x10000

    fun modifiersFromMetaState(metaState: Int): Byte {
        var m = 0
        if (metaState and META_SHIFT_ON != 0) m = m or MODIFIER_SHIFT.toInt()
        if (metaState and META_CTRL_ON != 0) m = m or MODIFIER_CTRL.toInt()
        if (metaState and META_ALT_ON != 0) m = m or MODIFIER_ALT.toInt()
        if (metaState and META_META_ON != 0) m = m or MODIFIER_META.toInt()
        return m.toByte()
    }

    /** @return `(0x80 << 8) | VK`, or 0 when [keyCode] has no mapping. */
    fun translate(keyCode: Int): Short {
        val vk = vkFor(keyCode)
        return if (vk == 0) 0 else ((PREFIX shl 8) or vk).toShort()
    }

    private const val SHIFTED_DIGITS = ")!@#$%^&*("

    /**
     * US-layout VK for a typed character (soft keyboard text), plus whether Shift is needed.
     * Returns null for characters with no US-layout key.
     */
    fun vkForChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> (0x41 + (c - 'a')) to false
        in 'A'..'Z' -> (0x41 + (c - 'A')) to true
        in '0'..'9' -> (0x30 + (c - '0')) to false
        ' ' -> 0x20 to false
        '\n' -> 0x0D to false
        '\t' -> 0x09 to false
        ',' -> 0xBC to false
        '<' -> 0xBC to true
        '.' -> 0xBE to false
        '>' -> 0xBE to true
        '/' -> 0xBF to false
        '?' -> 0xBF to true
        ';' -> 0xBA to false
        ':' -> 0xBA to true
        '\'' -> 0xDE to false
        '"' -> 0xDE to true
        '[' -> 0xDB to false
        '{' -> 0xDB to true
        ']' -> 0xDD to false
        '}' -> 0xDD to true
        '\\' -> 0xDC to false
        '|' -> 0xDC to true
        '-' -> 0xBD to false
        '_' -> 0xBD to true
        '=' -> 0xBB to false
        '+' -> 0xBB to true
        '`' -> 0xC0 to false
        '~' -> 0xC0 to true
        else -> {
            val i = SHIFTED_DIGITS.indexOf(c)
            if (i >= 0) (0x30 + i) to true else null
        }
    }

    private fun vkFor(k: Int): Int = when (k) {
        // KEYCODE_0..9
        in 7..16 -> 0x30 + (k - 7)
        // KEYCODE_A..Z
        in 29..54 -> 0x41 + (k - 29)
        // KEYCODE_NUMPAD_0..9
        in 144..153 -> 0x60 + (k - 144)
        // KEYCODE_F1..F12
        in 131..142 -> 0x70 + (k - 131)
        19 -> 0x26 // DPAD_UP
        20 -> 0x28 // DPAD_DOWN
        21 -> 0x25 // DPAD_LEFT
        22 -> 0x27 // DPAD_RIGHT
        23 -> 0x0D // DPAD_CENTER
        55 -> 0xBC // COMMA
        56 -> 0xBE // PERIOD
        57 -> 0xA4 // ALT_LEFT
        58 -> 0xA5 // ALT_RIGHT
        59 -> 0xA0 // SHIFT_LEFT
        60 -> 0xA1 // SHIFT_RIGHT
        61 -> 0x09 // TAB
        62 -> 0x20 // SPACE
        66 -> 0x0D // ENTER
        67 -> 0x08 // DEL (backspace)
        68 -> 0xC0 // GRAVE
        69 -> 0xBD // MINUS
        70 -> 0xBB // EQUALS
        71 -> 0xDB // LEFT_BRACKET
        72 -> 0xDD // RIGHT_BRACKET
        73 -> 0xDC // BACKSLASH
        74 -> 0xBA // SEMICOLON
        75 -> 0xDE // APOSTROPHE
        76 -> 0xBF // SLASH
        81 -> 0xBB // PLUS
        82 -> 0x5D // MENU (application key)
        92 -> 0x21 // PAGE_UP
        93 -> 0x22 // PAGE_DOWN
        111 -> 0x1B // ESCAPE
        112 -> 0x2E // FORWARD_DEL
        113 -> 0xA2 // CTRL_LEFT
        114 -> 0xA3 // CTRL_RIGHT
        115 -> 0x14 // CAPS_LOCK
        116 -> 0x91 // SCROLL_LOCK
        117 -> 0x5B // META_LEFT
        118 -> 0x5C // META_RIGHT
        120 -> 0x2C // SYSRQ (print screen)
        121 -> 0x13 // BREAK (pause)
        122 -> 0x24 // MOVE_HOME
        123 -> 0x23 // MOVE_END
        124 -> 0x2D // INSERT
        143 -> 0x90 // NUM_LOCK
        154 -> 0x6F // NUMPAD_DIVIDE
        155 -> 0x6A // NUMPAD_MULTIPLY
        156 -> 0x6D // NUMPAD_SUBTRACT
        157 -> 0x6B // NUMPAD_ADD
        158 -> 0x6E // NUMPAD_DOT
        160 -> 0x0D // NUMPAD_ENTER
        else -> 0
    }
}
