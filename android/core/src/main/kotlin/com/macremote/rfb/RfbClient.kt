package com.macremote.rfb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** RFB 客户端事件回调。注意：回调运行在 RFB 读线程上，UI 层需自行切线程。 */
interface RfbListener {
    fun onConnected(width: Int, height: Int, desktopName: String)

    /** 一个矩形解码完成，可立即从 [RfbClient.framebuffer] 取走该区域像素。 */
    fun onFramebufferRect(x: Int, y: Int, w: Int, h: Int)

    /** 一帧（FramebufferUpdate 消息）全部矩形处理完毕，适合触发重绘。 */
    fun onFrameComplete()

    fun onDesktopResized(width: Int, height: Int) {}
    fun onBell() {}
    fun onServerCutText(text: String) {}

    /** 连接结束。userInitiated=true 表示是本地调用 close() 主动断开。 */
    fun onDisconnected(reason: String, userInitiated: Boolean)
}

/**
 * RFB/VNC 客户端，面向 macOS 自带“屏幕共享”（也兼容标准 VNC 服务器）。
 *
 * 线程模型：
 * - [start] 启动读线程：建连、握手、认证、初始化，然后循环解析服务器消息；
 * - 握手完成后另起写线程，所有出站消息经 [sendQueue] 串行写出，
 *   因此 sendXxx 方法可以在任意线程（包括 UI 线程）调用；
 * - 每收完一帧自动发出增量更新请求，形成持续的画面流。
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val listener: RfbListener,
) {
    lateinit var framebuffer: Framebuffer
        private set

    @Volatile var desktopName: String = ""
        private set

    private var socket: Socket? = null
    private val sendQueue = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    private val userClosed = AtomicBoolean(false)
    private val zrle = ZrleDecoder()
    private var serverMinor = 8

    fun start() {
        Thread({ runReader() }, "rfb-reader").start()
    }

    fun close() {
        userClosed.set(true)
        shutdown("已断开连接")
    }

    // ---------------- 出站消息 ----------------

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        if (!::framebuffer.isInitialized) return
        val cx = x.coerceIn(0, framebuffer.width - 1)
        val cy = y.coerceIn(0, framebuffer.height - 1)
        val buf = ByteBuffer.allocate(6)
        buf.put(RfbProto.MSG_POINTER_EVENT.toByte())
        buf.put(buttonMask.toByte())
        buf.putShort(cx.toShort())
        buf.putShort(cy.toShort())
        enqueue(buf.array())
    }

    fun sendKeyEvent(keysym: Int, down: Boolean) {
        val buf = ByteBuffer.allocate(8)
        buf.put(RfbProto.MSG_KEY_EVENT.toByte())
        buf.put(if (down) 1 else 0)
        buf.putShort(0)
        buf.putInt(keysym)
        enqueue(buf.array())
    }

    /** 按下并松开一个键。 */
    fun tapKey(keysym: Int) {
        sendKeyEvent(keysym, true)
        sendKeyEvent(keysym, false)
    }

    /** 滚轮：方向用 RfbProto.BTN_WHEEL_UP / BTN_WHEEL_DOWN，一次一格。 */
    fun sendWheel(x: Int, y: Int, wheelButton: Int) {
        sendPointerEvent(x, y, wheelButton)
        sendPointerEvent(x, y, 0)
    }

    fun sendClientCutText(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val buf = ByteBuffer.allocate(8 + bytes.size)
        buf.put(RfbProto.MSG_CLIENT_CUT_TEXT.toByte())
        buf.put(0); buf.put(0); buf.put(0)
        buf.putInt(bytes.size)
        buf.put(bytes)
        enqueue(buf.array())
    }

    fun requestFullUpdate() {
        if (::framebuffer.isInitialized) {
            enqueue(updateRequest(false, framebuffer.width, framebuffer.height))
        }
    }

    private fun enqueue(msg: ByteArray) {
        if (!closed.get()) sendQueue.offer(msg)
    }

    private fun updateRequest(incremental: Boolean, w: Int, h: Int): ByteArray {
        val buf = ByteBuffer.allocate(10)
        buf.put(RfbProto.MSG_FRAMEBUFFER_UPDATE_REQUEST.toByte())
        buf.put(if (incremental) 1 else 0)
        buf.putShort(0); buf.putShort(0)
        buf.putShort(w.toShort()); buf.putShort(h.toShort())
        return buf.array()
    }

    // ---------------- 读线程主流程 ----------------

    private fun runReader() {
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), 10_000)
            socket = sock
            val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 16 * 1024))

            handshake(input, output)
            val (w, h, name) = serverInit(input, output)
            framebuffer = Framebuffer(w, h)
            desktopName = name
            listener.onConnected(w, h, name)

            startWriter(output)
            sendSetPixelFormat()
            sendSetEncodings()
            enqueue(updateRequest(false, w, h))

            readLoop(input)
        } catch (e: Exception) {
            if (!userClosed.get()) {
                val reason = when (e) {
                    is RfbException -> e.message ?: "协议错误"
                    is EOFException -> "服务器关闭了连接"
                    else -> "连接失败: ${e.javaClass.simpleName}: ${e.message}"
                }
                shutdown(reason)
            } else {
                shutdown("已断开连接")
            }
        }
    }

    private fun handshake(input: DataInputStream, output: DataOutputStream) {
        val versionBytes = ByteArray(12)
        input.readFully(versionBytes)
        val version = String(versionBytes, Charsets.US_ASCII)
        if (!version.startsWith("RFB ")) throw RfbException("不是 RFB 服务器: $version")
        val major = version.substring(4, 7).toIntOrNull() ?: 0
        val minor = version.substring(8, 11).toIntOrNull() ?: 0
        // macOS 上报 003.889，按 3.8 协商
        serverMinor = when {
            major > 3 || (major == 3 && minor >= 8) -> 8
            major == 3 && minor == 7 -> 7
            else -> 3
        }
        output.writeBytes("RFB 003.00$serverMinor\n")
        output.flush()

        val secType: Int
        if (serverMinor >= 7) {
            val n = input.readUnsignedByte()
            if (n == 0) throw RfbException("服务器拒绝连接: ${readReason(input)}")
            val types = IntArray(n) { input.readUnsignedByte() }
            secType = chooseSecurityType(types)
            output.writeByte(secType)
            output.flush()
        } else {
            secType = input.readInt()
            if (secType == RfbProto.SEC_INVALID) {
                throw RfbException("服务器拒绝连接: ${readReason(input)}")
            }
        }

        when (secType) {
            RfbProto.SEC_NONE -> {
                // 3.8 在 None 之后也有 SecurityResult；3.3/3.7 没有
                if (serverMinor >= 8) checkSecurityResult(input)
            }
            RfbProto.SEC_VNC_AUTH -> {
                if (password.isEmpty()) {
                    throw RfbException("服务器要求 VNC 密码，请在连接页填写（Mac 端设置的 VNC 密码，最长 8 位）")
                }
                val challenge = ByteArray(16)
                input.readFully(challenge)
                output.write(VncAuth.encryptChallenge(password, challenge))
                output.flush()
                checkSecurityResult(input)
            }
            else -> throw RfbException("不支持的认证类型 $secType")
        }
    }

    private fun chooseSecurityType(types: IntArray): Int {
        return when {
            password.isNotEmpty() && RfbProto.SEC_VNC_AUTH in types -> RfbProto.SEC_VNC_AUTH
            RfbProto.SEC_NONE in types -> RfbProto.SEC_NONE
            RfbProto.SEC_VNC_AUTH in types -> RfbProto.SEC_VNC_AUTH
            RfbProto.SEC_APPLE_DH in types ->
                throw RfbException(
                    "服务器只提供 macOS 账户认证（暂不支持）。" +
                        "请在 Mac 上：系统设置 → 通用 → 共享 → 屏幕共享 ⓘ → " +
                        "勾选“VNC 检视程序可以使用密码控制屏幕”并设置密码。"
                )
            else -> throw RfbException("没有可用的认证方式（服务器提供: ${types.joinToString()}）")
        }
    }

    private fun checkSecurityResult(input: DataInputStream) {
        val result = input.readInt()
        if (result != 0) {
            val reason = if (serverMinor >= 8) {
                try { readReason(input) } catch (_: Exception) { "" }
            } else ""
            throw RfbException(if (reason.isNotBlank()) "认证失败: $reason" else "认证失败，请检查 VNC 密码")
        }
    }

    private fun readReason(input: DataInputStream): String {
        val len = input.readInt()
        if (len < 0 || len > 4096) return "(无说明)"
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun serverInit(input: DataInputStream, output: DataOutputStream): Triple<Int, Int, String> {
        output.writeByte(1) // ClientInit: shared = true
        output.flush()
        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        PixelFormat.readFrom(input) // 服务器原生格式，随后会被我们覆盖
        val nameLen = input.readInt()
        if (nameLen < 0 || nameLen > 4096) throw RfbException("ServerInit 名称长度异常: $nameLen")
        val nameBytes = ByteArray(nameLen)
        input.readFully(nameBytes)
        return Triple(w, h, String(nameBytes, Charsets.UTF_8))
    }

    private fun sendSetPixelFormat() {
        val bos = java.io.ByteArrayOutputStream(20)
        val out = DataOutputStream(bos)
        out.writeByte(RfbProto.MSG_SET_PIXEL_FORMAT)
        out.writeByte(0); out.writeByte(0); out.writeByte(0)
        PixelFormat.RGB888.writeTo(out)
        enqueue(bos.toByteArray())
    }

    private fun sendSetEncodings() {
        val encodings = intArrayOf(
            RfbProto.ENC_ZRLE,
            RfbProto.ENC_COPY_RECT,
            RfbProto.ENC_RAW,
            RfbProto.ENC_DESKTOP_SIZE,
        )
        val buf = ByteBuffer.allocate(4 + encodings.size * 4)
        buf.put(RfbProto.MSG_SET_ENCODINGS.toByte())
        buf.put(0)
        buf.putShort(encodings.size.toShort())
        encodings.forEach { buf.putInt(it) }
        enqueue(buf.array())
    }

    private fun startWriter(output: DataOutputStream) {
        Thread({
            try {
                while (!closed.get()) {
                    val msg = sendQueue.take()
                    if (msg.isEmpty()) break // 毒丸退出
                    output.write(msg)
                    if (sendQueue.isEmpty()) output.flush()
                }
            } catch (_: Exception) {
                // socket 关闭或中断，由读线程负责上报
            }
        }, "rfb-writer").start()
    }

    // ---------------- 服务器消息循环 ----------------

    private fun readLoop(input: DataInputStream) {
        val raw = RawDecoder()
        while (!closed.get()) {
            when (val msgType = input.readUnsignedByte()) {
                RfbProto.SMSG_FRAMEBUFFER_UPDATE -> {
                    input.skipBytes(1)
                    val numRects = input.readUnsignedShort()
                    for (i in 0 until numRects) {
                        val x = input.readUnsignedShort()
                        val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort()
                        val h = input.readUnsignedShort()
                        when (val enc = input.readInt()) {
                            RfbProto.ENC_RAW -> {
                                raw.decode(input, framebuffer, x, y, w, h)
                                listener.onFramebufferRect(x, y, w, h)
                            }
                            RfbProto.ENC_COPY_RECT -> {
                                val srcX = input.readUnsignedShort()
                                val srcY = input.readUnsignedShort()
                                framebuffer.copyRect(srcX, srcY, x, y, w, h)
                                listener.onFramebufferRect(x, y, w, h)
                            }
                            RfbProto.ENC_ZRLE -> {
                                zrle.decode(input, framebuffer, x, y, w, h)
                                listener.onFramebufferRect(x, y, w, h)
                            }
                            RfbProto.ENC_DESKTOP_SIZE -> {
                                framebuffer.resize(w, h)
                                listener.onDesktopResized(w, h)
                                enqueue(updateRequest(false, w, h))
                            }
                            else -> throw RfbException("服务器使用了未协商的编码: $enc")
                        }
                    }
                    listener.onFrameComplete()
                    enqueue(updateRequest(true, framebuffer.width, framebuffer.height))
                }
                RfbProto.SMSG_SET_COLOUR_MAP -> {
                    input.skipBytes(1)
                    input.skipBytes(2)
                    val n = input.readUnsignedShort()
                    input.skipBytes(n * 6)
                }
                RfbProto.SMSG_BELL -> listener.onBell()
                RfbProto.SMSG_SERVER_CUT_TEXT -> {
                    input.skipBytes(3)
                    val len = input.readInt()
                    if (len < 0) throw RfbException("ServerCutText 长度异常")
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    listener.onServerCutText(String(bytes, Charsets.ISO_8859_1))
                }
                else -> throw RfbException("未知的服务器消息类型: $msgType")
            }
        }
    }

    private fun shutdown(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        sendQueue.offer(ByteArray(0)) // 唤醒写线程退出
        try { socket?.close() } catch (_: Exception) {}
        listener.onDisconnected(reason, userClosed.get())
    }
}

/** Raw 编码：每像素 4 字节 [B,G,R,X]（对应我们请求的 RGB888 小端格式）。 */
internal class RawDecoder {
    private var rowBytes = ByteArray(0)

    fun decode(input: DataInputStream, fb: Framebuffer, x: Int, y: Int, w: Int, h: Int) {
        val needed = w * 4
        if (rowBytes.size < needed) rowBytes = ByteArray(needed)
        val row = IntArray(w)
        for (r in 0 until h) {
            input.readFully(rowBytes, 0, needed)
            var p = 0
            for (c in 0 until w) {
                val b = rowBytes[p].toInt() and 0xFF
                val g = rowBytes[p + 1].toInt() and 0xFF
                val rr = rowBytes[p + 2].toInt() and 0xFF
                row[c] = -0x1000000 or (rr shl 16) or (g shl 8) or b
                p += 4
            }
            fb.setRect(x, y + r, w, 1, row)
        }
    }
}
