package com.macremote.rfb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.Inflater

/**
 * ZRLE 编码解码器（RFC 6143 §7.7.6）。
 *
 * 要点：
 * - 整条连接共用**一个** zlib 流（Inflater 不能在矩形之间重置）；
 * - 每个矩形 = u32 压缩长度 + zlib 数据，解压后按 64x64 瓦片自左向右、
 *   自上而下排列；
 * - 每个瓦片首字节是子编码：0=Raw，1=纯色，2..16=调色板位打包，
 *   128=普通 RLE，130..255=调色板 RLE；
 * - 由于我们强制了 32bpp/depth24 的像素格式，cpixel（压缩像素）固定为
 *   3 字节，顺序 [B, G, R]（小端、shift 16/8/0 的低 3 字节）。
 */
class ZrleDecoder {
    private val inflater = Inflater()
    private var data = ByteArray(0)
    private var pos = 0

    fun decode(input: DataInputStream, fb: Framebuffer, rx: Int, ry: Int, rw: Int, rh: Int) {
        val compressedLen = input.readInt()
        if (compressedLen < 0 || compressedLen > 64 * 1024 * 1024) {
            throw RfbException("ZRLE 压缩块长度异常: $compressedLen")
        }
        val compressed = ByteArray(compressedLen)
        input.readFully(compressed)
        inflateBlock(compressed)

        var ty = ry
        while (ty < ry + rh) {
            val th = minOf(64, ry + rh - ty)
            var tx = rx
            while (tx < rx + rw) {
                val tw = minOf(64, rx + rw - tx)
                decodeTile(fb, tx, ty, tw, th)
                tx += tw
            }
            ty += th
        }
    }

    private fun inflateBlock(compressed: ByteArray) {
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream(compressed.size * 4 + 64)
        val tmp = ByteArray(16 * 1024)
        while (true) {
            val n = inflater.inflate(tmp)
            if (n > 0) out.write(tmp, 0, n) else break
        }
        data = out.toByteArray()
        pos = 0
    }

    private fun u8(): Int {
        if (pos >= data.size) throw RfbException("ZRLE 数据流提前结束")
        return data[pos++].toInt() and 0xFF
    }

    private fun cpixel(): Int {
        val b = u8(); val g = u8(); val r = u8()
        return -0x1000000 or (r shl 16) or (g shl 8) or b
    }

    /** RLE 行程长度：连续读字节累加，遇到非 255 结束，总长 = 累加值 + 1。 */
    private fun runLength(): Int {
        var len = 1
        while (true) {
            val b = u8()
            len += b
            if (b != 255) return len
        }
    }

    private fun decodeTile(fb: Framebuffer, tx: Int, ty: Int, tw: Int, th: Int) {
        val count = tw * th
        when (val sub = u8()) {
            0 -> { // Raw
                val buf = IntArray(count)
                for (i in 0 until count) buf[i] = cpixel()
                fb.setRect(tx, ty, tw, th, buf)
            }
            1 -> fb.fillRect(tx, ty, tw, th, cpixel()) // 纯色
            in 2..16 -> { // 调色板，按位打包，每行补齐到字节边界
                val palette = IntArray(sub) { cpixel() }
                val bpp = when {
                    sub == 2 -> 1
                    sub <= 4 -> 2
                    else -> 4
                }
                val mask = (1 shl bpp) - 1
                val buf = IntArray(count)
                for (y in 0 until th) {
                    var bits = 0
                    var nbits = 0
                    for (x in 0 until tw) {
                        if (nbits == 0) { bits = u8(); nbits = 8 }
                        nbits -= bpp
                        val idx = (bits shr nbits) and mask
                        buf[y * tw + x] = palette[idx]
                    }
                }
                fb.setRect(tx, ty, tw, th, buf)
            }
            128 -> { // 普通 RLE
                val buf = IntArray(count)
                var i = 0
                while (i < count) {
                    val color = cpixel()
                    val len = minOf(runLength(), count - i)
                    buf.fill(color, i, i + len)
                    i += len
                }
                fb.setRect(tx, ty, tw, th, buf)
            }
            in 130..255 -> { // 调色板 RLE
                val palette = IntArray(sub - 128) { cpixel() }
                val buf = IntArray(count)
                var i = 0
                while (i < count) {
                    val b = u8()
                    if (b < 128) {
                        buf[i++] = palette[b]
                    } else {
                        val color = palette[b - 128]
                        val len = minOf(runLength(), count - i)
                        buf.fill(color, i, i + len)
                        i += len
                    }
                }
                fb.setRect(tx, ty, tw, th, buf)
            }
            else -> throw RfbException("无效的 ZRLE 子编码: $sub")
        }
    }
}
