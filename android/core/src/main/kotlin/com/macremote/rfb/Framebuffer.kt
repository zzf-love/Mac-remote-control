package com.macremote.rfb

/**
 * 远端桌面的帧缓冲，像素为 ARGB_8888 整数（alpha 恒为 0xFF），
 * 可直接通过 Bitmap.setPixels 上屏。
 *
 * 写入发生在 RFB 读线程，UI 在 onFramebufferRect 回调里同步取走脏区，
 * 因此不需要额外加锁。
 */
class Framebuffer(width: Int, height: Int) {
    var width = width; private set
    var height = height; private set
    var pixels = IntArray(width * height); private set

    fun resize(w: Int, h: Int) {
        width = w
        height = h
        pixels = IntArray(w * h)
    }

    /** 将 buf 中 w*h 个像素写入 (x,y) 起始的矩形，越界部分裁剪丢弃。 */
    fun setRect(x: Int, y: Int, w: Int, h: Int, buf: IntArray) {
        for (row in 0 until h) {
            val dy = y + row
            if (dy < 0 || dy >= height) continue
            val copyW = minOf(w, width - x)
            if (copyW <= 0) continue
            System.arraycopy(buf, row * w, pixels, dy * width + x, copyW)
        }
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (row in 0 until h) {
            val dy = y + row
            if (dy < 0 || dy >= height) continue
            val start = dy * width + x
            val end = start + minOf(w, width - x)
            if (end <= start) continue
            pixels.fill(color, start, end)
        }
    }

    /** CopyRect：把 (srcX,srcY) 的矩形拷到 (dstX,dstY)。用临时缓冲避免重叠问题。 */
    fun copyRect(srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        val tmp = IntArray(w * h)
        for (row in 0 until h) {
            val sy = srcY + row
            if (sy < 0 || sy >= height) continue
            val copyW = minOf(w, width - srcX)
            if (copyW <= 0) continue
            System.arraycopy(pixels, sy * width + srcX, tmp, row * w, copyW)
        }
        setRect(dstX, dstY, w, h, tmp)
    }
}
