package com.macremote.rfb

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * RFB 像素格式（16 字节结构）。
 *
 * 客户端在握手后立刻用 [RGB888] 覆盖服务器的默认格式，
 * 这样所有解码器只需要处理一种固定格式：
 * 32bpp、小端、真彩色，内存字节序为 [B, G, R, X]，
 * 与 Android Bitmap 的 ARGB_8888 整数像素一一对应。
 */
data class PixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val bigEndian: Boolean,
    val trueColour: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int,
) {
    fun writeTo(out: DataOutputStream) {
        out.writeByte(bitsPerPixel)
        out.writeByte(depth)
        out.writeByte(if (bigEndian) 1 else 0)
        out.writeByte(if (trueColour) 1 else 0)
        out.writeShort(redMax)
        out.writeShort(greenMax)
        out.writeShort(blueMax)
        out.writeByte(redShift)
        out.writeByte(greenShift)
        out.writeByte(blueShift)
        out.writeByte(0); out.writeByte(0); out.writeByte(0) // padding
    }

    companion object {
        val RGB888 = PixelFormat(
            bitsPerPixel = 32, depth = 24,
            bigEndian = false, trueColour = true,
            redMax = 255, greenMax = 255, blueMax = 255,
            redShift = 16, greenShift = 8, blueShift = 0,
        )

        fun readFrom(input: DataInputStream): PixelFormat {
            val bpp = input.readUnsignedByte()
            val depth = input.readUnsignedByte()
            val bigEndian = input.readUnsignedByte() != 0
            val trueColour = input.readUnsignedByte() != 0
            val rMax = input.readUnsignedShort()
            val gMax = input.readUnsignedShort()
            val bMax = input.readUnsignedShort()
            val rShift = input.readUnsignedByte()
            val gShift = input.readUnsignedByte()
            val bShift = input.readUnsignedByte()
            input.skipBytes(3)
            return PixelFormat(bpp, depth, bigEndian, trueColour, rMax, gMax, bMax, rShift, gShift, bShift)
        }
    }
}
