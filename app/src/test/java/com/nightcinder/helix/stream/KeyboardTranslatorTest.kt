package com.nightcinder.helix.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardTranslatorTest {

    private fun vk(code: Int): Int = KeyboardTranslator.translate(code).toInt() and 0xFFFF

    @Test
    fun lettersDigitsAndFunctionKeys_mapToWindowsVirtualKeysWithPrefix() {
        assertEquals(0x8041, vk(29)) // KEYCODE_A
        assertEquals(0x805A, vk(54)) // KEYCODE_Z
        assertEquals(0x8030, vk(7)) // KEYCODE_0
        assertEquals(0x8039, vk(16)) // KEYCODE_9
        assertEquals(0x8070, vk(131)) // F1
        assertEquals(0x807B, vk(142)) // F12
        assertEquals(0x8060, vk(144)) // NUMPAD_0
    }

    @Test
    fun editingNavigationAndModifierKeys() {
        assertEquals(0x800D, vk(66)) // ENTER
        assertEquals(0x8008, vk(67)) // DEL -> VK_BACK
        assertEquals(0x802E, vk(112)) // FORWARD_DEL
        assertEquals(0x801B, vk(111)) // ESCAPE
        assertEquals(0x8009, vk(61)) // TAB
        assertEquals(0x8020, vk(62)) // SPACE
        assertEquals(0x8025, vk(21)) // DPAD_LEFT
        assertEquals(0x8026, vk(19)) // DPAD_UP
        assertEquals(0x80A0, vk(59)) // SHIFT_LEFT
        assertEquals(0x80A2, vk(113)) // CTRL_LEFT
        assertEquals(0x80A4, vk(57)) // ALT_LEFT
        assertEquals(0x805B, vk(117)) // META_LEFT
        assertEquals(0x80BA, vk(74)) // SEMICOLON
    }

    @Test
    fun unmappedKeys_returnZero() {
        assertEquals(0, KeyboardTranslator.translate(0).toInt())
        assertEquals(0, KeyboardTranslator.translate(4).toInt()) // KEYCODE_BACK
        assertEquals(0, KeyboardTranslator.translate(9999).toInt())
    }

    @Test
    fun metaState_mapsToModifierBits() {
        assertEquals(0, KeyboardTranslator.modifiersFromMetaState(0).toInt())
        assertEquals(0x01, KeyboardTranslator.modifiersFromMetaState(0x1).toInt())
        assertEquals(0x02, KeyboardTranslator.modifiersFromMetaState(0x1000).toInt())
        assertEquals(0x04, KeyboardTranslator.modifiersFromMetaState(0x2).toInt())
        assertEquals(0x08, KeyboardTranslator.modifiersFromMetaState(0x10000).toInt())
        assertEquals(0x0F, KeyboardTranslator.modifiersFromMetaState(0x1 or 0x1000 or 0x2 or 0x10000).toInt())
    }

    @Test
    fun typedCharacters_mapToUsLayoutKeys() {
        assertEquals(0x41 to false, KeyboardTranslator.vkForChar('a'))
        assertEquals(0x41 to true, KeyboardTranslator.vkForChar('A'))
        assertEquals(0x31 to false, KeyboardTranslator.vkForChar('1'))
        assertEquals(0x31 to true, KeyboardTranslator.vkForChar('!'))
        assertEquals(0x30 to true, KeyboardTranslator.vkForChar(')'))
        assertEquals(0xBF to true, KeyboardTranslator.vkForChar('?'))
        assertEquals(0x20 to false, KeyboardTranslator.vkForChar(' '))
        assertNull(KeyboardTranslator.vkForChar('é'))
    }
}
