package com.macremote.rfb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 手工构造 ZRLE 瓦片字节流（覆盖全部 5 种子编码），用 zlib SYNC_FLUSH
 * 压缩后喂给解码器，与参考数据逐像素比对。
 * 同时验证：多个矩形必须共用同一条 zlib 流（不可重置）。
 */
class ZrleDecoderTest {

    private val deflater = Deflater()

    private fun argb(r: Int, g: Int, b: Int) = -0x1000000 or (r shl 16) or (g shl 8) or b

    private fun ByteArrayOutputStream.cpixel(color: Int) {
        write(color and 0xFF)          // B
        write((color shr 8) and 0xFF)  // G
        write((color shr 16) and 0xFF) // R
    }

    private fun compress(data: ByteArray): ByteArray {
        deflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            out.write(buf, 0, n)
            if (n < buf.size) break
        }
        return out.toByteArray()
    }

    private fun wrapRect(compressed: ByteArray): DataInputStream {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeInt(compressed.size)
            write(compressed)
        }
        return DataInputStream(ByteArrayInputStream(bos.toByteArray()))
    }

    @Test
    fun decodesAllSubEncodingsAcrossTiles() {
        // 130x70 的矩形会切成 6 个瓦片：
        // (0,0)64x64  (64,0)64x64  (128,0)2x64
        // (0,64)64x6  (64,64)64x6  (128,64)2x6
        val w = 130
        val h = 70
        val fb = Framebuffer(w, h)
        val expected = IntArray(w * h)
        val tiles = ByteArrayOutputStream()

        val red = argb(200, 0, 0)
        val green = argb(0, 200, 0)
        val blue = argb(0, 0, 200)
        val white = argb(255, 255, 255)
        val black = argb(1, 2, 3)
        val cyan = argb(0, 180, 180)
        val magenta = argb(180, 0, 180)
        val pal4 = intArrayOf(argb(10, 0, 0), argb(0, 20, 0), argb(0, 0, 30), argb(40, 40, 40))

        fun setExpected(x: Int, y: Int, color: Int) { expected[y * w + x] = color }

        // ---- 瓦片 (0,0) 64x64：子编码 1（纯色）----
        tiles.write(1)
        tiles.cpixel(red)
        for (y in 0 until 64) for (x in 0 until 64) setExpected(x, y, red)

        // ---- 瓦片 (64,0) 64x64：子编码 0（Raw 渐变）----
        tiles.write(0)
        for (y in 0 until 64) for (x in 0 until 64) {
            val c = argb(x * 3 and 0xFF, y * 3 and 0xFF, (x + y) and 0xFF)
            tiles.cpixel(c)
            setExpected(64 + x, y, c)
        }

        // ---- 瓦片 (128,0) 2x64：子编码 2（双色调色板 1bpp，棋盘格）----
        tiles.write(2)
        tiles.cpixel(green); tiles.cpixel(blue)
        for (y in 0 until 64) {
            val i0 = y % 2
            val i1 = (y + 1) % 2
            tiles.write((i0 shl 7) or (i1 shl 6)) // 每行 2 像素 ×1bit，行内补齐到字节
            setExpected(128, y, if (i0 == 0) green else blue)
            setExpected(129, y, if (i1 == 0) green else blue)
        }

        // ---- 瓦片 (0,64) 64x6：子编码 128（普通 RLE：300 白 + 84 黑）----
        tiles.write(128)
        tiles.cpixel(white); tiles.write(255); tiles.write(44) // len = 255+44+1 = 300
        tiles.cpixel(black); tiles.write(83)                   // len = 83+1 = 84
        for (i in 0 until 384) {
            val x = i % 64; val y = 64 + i / 64
            setExpected(x, y, if (i < 300) white else black)
        }

        // ---- 瓦片 (64,64) 64x6：子编码 130（双色调色板 RLE）----
        tiles.write(130)
        tiles.cpixel(cyan); tiles.cpixel(magenta)
        tiles.write(128); tiles.write(99)                    // 调色板 0，行程 100
        tiles.write(1)                                        // 单像素，调色板 1
        tiles.write(128); tiles.write(255); tiles.write(27)  // 调色板 0，行程 283
        for (i in 0 until 384) {
            val x = 64 + i % 64; val y = 64 + i / 64
            setExpected(x, y, if (i == 100) magenta else cyan)
        }

        // ---- 瓦片 (128,64) 2x6：子编码 4（四色调色板 2bpp）----
        tiles.write(4)
        pal4.forEach { tiles.cpixel(it) }
        for (y in 0 until 6) {
            val i0 = y % 4
            val i1 = (y + 1) % 4
            tiles.write((i0 shl 6) or (i1 shl 4)) // 2 像素 ×2bit，高位在前
            setExpected(128, 64 + y, pal4[i0])
            setExpected(129, 64 + y, pal4[i1])
        }

        ZrleDecoder().decode(wrapRect(compress(tiles.toByteArray())), fb, 0, 0, w, h)
        assertContentEquals(expected, fb.pixels)
    }

    @Test
    fun multipleRectsShareOneZlibStream() {
        val fb = Framebuffer(8, 8)
        val decoder = ZrleDecoder()

        // 矩形 1：(0,0) 8x8 纯色
        val t1 = ByteArrayOutputStream()
        t1.write(1); t1.cpixel(argb(9, 9, 9))
        decoder.decode(wrapRect(compress(t1.toByteArray())), fb, 0, 0, 8, 8)

        // 矩形 2：(2,2) 3x3 Raw —— 必须能在同一条 zlib 流上继续解压
        val t2 = ByteArrayOutputStream()
        t2.write(0)
        repeat(9) { t2.cpixel(argb(77, 66, 55)) }
        decoder.decode(wrapRect(compress(t2.toByteArray())), fb, 2, 2, 3, 3)

        assertEquals(argb(9, 9, 9), fb.pixels[0])
        assertEquals(argb(77, 66, 55), fb.pixels[3 * 8 + 3])
        assertEquals(argb(77, 66, 55), fb.pixels[4 * 8 + 4])
        assertEquals(argb(9, 9, 9), fb.pixels[5 * 8 + 5])
    }
}
