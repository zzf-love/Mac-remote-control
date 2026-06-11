package com.macremote.rfb

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 标准 VNC 密码认证（安全类型 2）。
 *
 * 服务器发来 16 字节随机挑战，客户端用密码作为 DES 密钥加密后回传。
 * VNC 的历史怪癖：密码最多取前 8 字节，且**每个字节的比特序要先反转**
 * 再作为 DES 密钥使用。macOS 的“使用密码的 VNC 检视程序”就是这种认证，
 * 这也是 macOS 限制 VNC 密码不超过 8 个字符的原因。
 */
object VncAuth {

    /** 反转一个字节内的比特顺序，如 0b0000_0001 -> 0b1000_0000。 */
    fun reverseBits(b: Int): Int {
        var x = b and 0xFF
        var r = 0
        repeat(8) {
            r = (r shl 1) or (x and 1)
            x = x shr 1
        }
        return r
    }

    fun encryptChallenge(password: String, challenge: ByteArray): ByteArray {
        require(challenge.size == 16) { "VNC challenge 必须是 16 字节" }
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        val key = ByteArray(8)
        for (i in 0 until 8) {
            key[i] = if (i < pw.size) reverseBits(pw[i].toInt()).toByte() else 0
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }
}
