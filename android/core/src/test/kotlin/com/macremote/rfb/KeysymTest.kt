package com.macremote.rfb

import kotlin.test.Test
import kotlin.test.assertEquals

class KeysymTest {

    @Test
    fun asciiMapsToItself() {
        assertEquals(0x61, Keysym.fromChar('a'))
        assertEquals(0x5A, Keysym.fromChar('Z'))
        assertEquals(0x31, Keysym.fromChar('1'))
        assertEquals(0x20, Keysym.fromChar(' '))
        assertEquals(0x7E, Keysym.fromChar('~'))
    }

    @Test
    fun latin1MapsToItself() {
        assertEquals(0xE9, Keysym.fromChar('é'))
    }

    @Test
    fun controlCharsMapToFunctionKeys() {
        assertEquals(Keysym.RETURN, Keysym.fromChar('\n'))
        assertEquals(Keysym.RETURN, Keysym.fromChar('\r'))
        assertEquals(Keysym.TAB, Keysym.fromChar('\t'))
    }

    @Test
    fun unicodeUsesOffset() {
        assertEquals(0x01004E2D, Keysym.fromChar('中'))
        assertEquals(0x0100FF1F, Keysym.fromChar('？'))
    }
}
