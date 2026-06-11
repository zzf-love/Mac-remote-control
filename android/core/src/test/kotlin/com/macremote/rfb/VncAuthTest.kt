package com.macremote.rfb

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class VncAuthTest {

    @Test
    fun reverseBitsKnownValues() {
        assertEquals(0x80, VncAuth.reverseBits(0x01))
        assertEquals(0x01, VncAuth.reverseBits(0x80))
        assertEquals(0xFF, VncAuth.reverseBits(0xFF))
        assertEquals(0x00, VncAuth.reverseBits(0x00))
        assertEquals(0xA5, VncAuth.reverseBits(0xA5)) // 1010_0101 是比特回文
        assertEquals(0x40, VncAuth.reverseBits(0x02))
    }

    @Test
    fun encryptChallengeRoundTrip() {
        val challenge = ByteArray(16) { (it * 7 + 3).toByte() }
        val password = "test1234"
        val encrypted = VncAuth.encryptChallenge(password, challenge)
        assertEquals(16, encrypted.size)

        // 用同样的“按字节反转比特”的密钥解密，应还原出原始挑战
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        val key = ByteArray(8) { i -> if (i < pw.size) VncAuth.reverseBits(pw[i].toInt()).toByte() else 0 }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DES"))
        assertContentEquals(challenge, cipher.doFinal(encrypted))
    }

    @Test
    fun shortPasswordIsZeroPadded() {
        val challenge = ByteArray(16) { it.toByte() }
        // 不抛异常即可（不足 8 位补零）
        assertEquals(16, VncAuth.encryptChallenge("ab", challenge).size)
    }
}
