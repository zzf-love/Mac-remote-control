package com.macremote.rfb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端测试：起一个脚本化的假 RFB 服务器（3.8 + VNC 密码认证 + Raw 编码），
 * 用 RfbClient 真实建连，验证握手、认证、ServerInit、首帧解码全链路。
 */
class FakeServerTest {

    @Test(timeout = 15_000)
    fun fullHandshakeAuthAndFirstFrame() {
        val password = "test1234"
        val fbW = 8
        val fbH = 4
        val expectedPixels = IntArray(fbW * fbH)

        val server = ServerSocket(0)
        val serverThread = Thread {
            server.accept().use { sock ->
                val sin = DataInputStream(sock.getInputStream())
                val sout = DataOutputStream(sock.getOutputStream())

                // 版本协商（模拟 macOS 的非标准版本号）
                sout.writeBytes("RFB 003.889\n"); sout.flush()
                val clientVersion = ByteArray(12).also { sin.readFully(it) }
                assertEquals("RFB 003.008\n", String(clientVersion, Charsets.US_ASCII))

                // 安全类型列表：[VncAuth]
                sout.writeByte(1); sout.writeByte(2); sout.flush()
                assertEquals(2, sin.readUnsignedByte())

                // 挑战-应答
                val challenge = ByteArray(16) { (it * 11).toByte() }
                sout.write(challenge); sout.flush()
                val response = ByteArray(16).also { sin.readFully(it) }
                assertContentEquals(VncAuth.encryptChallenge(password, challenge), response)
                sout.writeInt(0) // SecurityResult OK

                // ClientInit / ServerInit
                sin.readUnsignedByte()
                sout.writeShort(fbW); sout.writeShort(fbH)
                // 服务器原生像素格式（会被客户端覆盖，内容不重要但要合法）
                sout.writeByte(32); sout.writeByte(24); sout.writeByte(0); sout.writeByte(1)
                sout.writeShort(255); sout.writeShort(255); sout.writeShort(255)
                sout.writeByte(16); sout.writeByte(8); sout.writeByte(0)
                sout.writeByte(0); sout.writeByte(0); sout.writeByte(0)
                val name = "TestMac".toByteArray(Charsets.UTF_8)
                sout.writeInt(name.size); sout.write(name); sout.flush()

                // 处理客户端消息，直到收到全量更新请求后发一帧
                var sentFrame = false
                while (!sentFrame) {
                    when (sin.readUnsignedByte()) {
                        0 -> sin.skipBytes(19) // SetPixelFormat
                        2 -> { sin.skipBytes(1); val n = sin.readUnsignedShort(); sin.skipBytes(n * 4) }
                        3 -> {
                            val incremental = sin.readUnsignedByte()
                            sin.skipBytes(8)
                            if (incremental == 0) {
                                sout.writeByte(0); sout.writeByte(0) // FramebufferUpdate
                                sout.writeShort(1)
                                sout.writeShort(0); sout.writeShort(0)
                                sout.writeShort(fbW); sout.writeShort(fbH)
                                sout.writeInt(0) // Raw
                                for (y in 0 until fbH) for (x in 0 until fbW) {
                                    val r = 100 + x; val g = y * 50; val b = x * 10
                                    sout.writeByte(b); sout.writeByte(g); sout.writeByte(r); sout.writeByte(0)
                                    expectedPixels[y * fbW + x] =
                                        -0x1000000 or (r shl 16) or (g shl 8) or b
                                }
                                sout.flush()
                                sentFrame = true
                            }
                        }
                        else -> return@use
                    }
                }
                // 等客户端断开
                try { while (sin.read() != -1) { /* drain */ } } catch (_: Exception) {}
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val connected = CountDownLatch(1)
        val frameDone = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        var gotW = 0; var gotH = 0; var gotName = ""
        var disconnectUserInitiated = false

        lateinit var client: RfbClient
        client = RfbClient("127.0.0.1", server.localPort, password, object : RfbListener {
            override fun onConnected(width: Int, height: Int, desktopName: String) {
                gotW = width; gotH = height; gotName = desktopName
                connected.countDown()
            }
            override fun onFramebufferRect(x: Int, y: Int, w: Int, h: Int) {}
            override fun onFrameComplete() { frameDone.countDown() }
            override fun onDisconnected(reason: String, userInitiated: Boolean) {
                disconnectUserInitiated = userInitiated
                disconnected.countDown()
            }
        })
        client.start()

        assertTrue(connected.await(5, TimeUnit.SECONDS), "握手超时")
        assertEquals(8, gotW); assertEquals(4, gotH); assertEquals("TestMac", gotName)

        assertTrue(frameDone.await(5, TimeUnit.SECONDS), "首帧超时")
        assertContentEquals(expectedPixels, client.framebuffer.pixels)

        client.close()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS), "断开回调超时")
        assertTrue(disconnectUserInitiated)
        server.close()
    }

    @Test(timeout = 15_000)
    fun reportsHelpfulErrorWhenOnlyAppleAuthOffered() {
        val server = ServerSocket(0)
        val serverThread = Thread {
            server.accept().use { sock ->
                val sin = DataInputStream(sock.getInputStream())
                val sout = DataOutputStream(sock.getOutputStream())
                sout.writeBytes("RFB 003.889\n"); sout.flush()
                ByteArray(12).also { sin.readFully(it) }
                // 只提供 Apple DH(30)
                sout.writeByte(1); sout.writeByte(30); sout.flush()
                try { while (sin.read() != -1) { /* drain */ } } catch (_: Exception) {}
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val disconnected = CountDownLatch(1)
        var reason = ""
        val client = RfbClient("127.0.0.1", server.localPort, "pw", object : RfbListener {
            override fun onConnected(width: Int, height: Int, desktopName: String) {}
            override fun onFramebufferRect(x: Int, y: Int, w: Int, h: Int) {}
            override fun onFrameComplete() {}
            override fun onDisconnected(r: String, userInitiated: Boolean) {
                reason = r
                disconnected.countDown()
            }
        })
        client.start()

        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        assertTrue("VNC 检视程序" in reason, "错误信息应指引用户开启 VNC 密码，实际: $reason")
        server.close()
    }
}
