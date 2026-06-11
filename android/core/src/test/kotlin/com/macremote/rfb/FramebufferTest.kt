package com.macremote.rfb

import kotlin.test.Test
import kotlin.test.assertEquals

class FramebufferTest {

    @Test
    fun setRectAndFill() {
        val fb = Framebuffer(4, 4)
        fb.fillRect(1, 1, 2, 2, 0xFF112233.toInt())
        assertEquals(0xFF112233.toInt(), fb.pixels[1 * 4 + 1])
        assertEquals(0xFF112233.toInt(), fb.pixels[2 * 4 + 2])
        assertEquals(0, fb.pixels[0])
    }

    @Test
    fun copyRectHandlesOverlap() {
        val fb = Framebuffer(4, 1)
        fb.pixels[0] = 1; fb.pixels[1] = 2; fb.pixels[2] = 3; fb.pixels[3] = 4
        fb.copyRect(0, 0, 1, 0, 3, 1) // 右移一格，源/目标重叠
        assertEquals(1, fb.pixels[0])
        assertEquals(1, fb.pixels[1])
        assertEquals(2, fb.pixels[2])
        assertEquals(3, fb.pixels[3])
    }

    @Test
    fun setRectClipsOutOfBounds() {
        val fb = Framebuffer(2, 2)
        // 写一个超出右下边界的矩形，不应崩溃
        fb.setRect(1, 1, 2, 2, IntArray(4) { 9 })
        assertEquals(9, fb.pixels[1 * 2 + 1])
        assertEquals(0, fb.pixels[0])
    }

    @Test
    fun resizeReallocates() {
        val fb = Framebuffer(2, 2)
        fb.resize(3, 5)
        assertEquals(3, fb.width)
        assertEquals(5, fb.height)
        assertEquals(15, fb.pixels.size)
    }
}
