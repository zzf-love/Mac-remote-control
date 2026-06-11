package com.macremote.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.macremote.rfb.Keysym
import com.macremote.rfb.RfbClient
import com.macremote.rfb.RfbListener
import com.macremote.rfb.RfbProto

/** 远程桌面页：持有 RfbClient 连接，桥接画布手势与工具栏按键。 */
class RemoteActivity : Activity(), RemoteInputSink {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PASSWORD = "password"
    }

    private lateinit var canvasView: RemoteCanvasView
    private lateinit var statusCard: View
    private lateinit var statusText: TextView
    private var client: RfbClient? = null
    private var bitmap: Bitmap? = null
    private val ui = Handler(Looper.getMainLooper())

    /** 工具栏 sticky 修饰键当前按下状态：keysym -> 是否按下 */
    private val stickyModifiers = HashMap<Int, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        canvasView = findViewById(R.id.remote_canvas)
        canvasView.sink = this
        statusCard = findViewById(R.id.status_card)
        statusText = findViewById(R.id.status_text)
        setupToolbar()

        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 5900)
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        statusText.text = getString(R.string.connecting, host)
        val c = RfbClient(host, port, password, rfbListener)
        client = c
        c.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.close()
        client = null
    }

    private val rfbListener = object : RfbListener {
        override fun onConnected(width: Int, height: Int, desktopName: String) {
            // 在读线程上同步创建，保证后续矩形回调时 bitmap 已就绪
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            ui.post {
                statusCard.animate().alpha(0f).setDuration(220).withEndAction {
                    statusCard.visibility = View.GONE
                }.start()
                canvasView.setRemoteBitmap(bmp)
            }
        }

        override fun onFramebufferRect(x: Int, y: Int, w: Int, h: Int) {
            val bmp = bitmap ?: return
            val fb = client?.framebuffer ?: return
            if (bmp.width != fb.width || bmp.height != fb.height) return
            bmp.setPixels(fb.pixels, y * fb.width + x, fb.width, x, y, w, h)
        }

        override fun onFrameComplete() {
            canvasView.postInvalidate()
        }

        override fun onDesktopResized(width: Int, height: Int) {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            ui.post { canvasView.setRemoteBitmap(bmp) }
        }

        override fun onServerCutText(text: String) {
            ui.post {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("mac", text))
            }
        }

        override fun onDisconnected(reason: String, userInitiated: Boolean) {
            ui.post {
                if (!isFinishing) {
                    if (!userInitiated) {
                        Toast.makeText(this@RemoteActivity, reason, Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }

    // ---------------- RemoteInputSink（画布 → 连接）----------------

    override fun pointer(fx: Int, fy: Int, buttonMask: Int) {
        client?.sendPointerEvent(fx, fy, buttonMask)
    }

    override fun wheel(fx: Int, fy: Int, scrollUp: Boolean) {
        client?.sendWheel(fx, fy, if (scrollUp) RfbProto.BTN_WHEEL_UP else RfbProto.BTN_WHEEL_DOWN)
    }

    override fun typeChar(c: Char) {
        client?.tapKey(Keysym.fromChar(c))
    }

    override fun tapKeysym(keysym: Int) {
        client?.tapKey(keysym)
    }

    // ---------------- 工具栏 ----------------

    private fun setupToolbar() {
        findViewById<Button>(R.id.btn_keyboard).setOnClickListener {
            canvasView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(canvasView, InputMethodManager.SHOW_IMPLICIT)
        }

        wireTapKey(R.id.btn_esc, Keysym.ESCAPE)
        wireTapKey(R.id.btn_tab, Keysym.TAB)
        wireTapKey(R.id.btn_left, Keysym.LEFT)
        wireTapKey(R.id.btn_up, Keysym.UP)
        wireTapKey(R.id.btn_down, Keysym.DOWN)
        wireTapKey(R.id.btn_right, Keysym.RIGHT)

        wireModifier(R.id.btn_cmd, Keysym.META_L)    // ⌘
        wireModifier(R.id.btn_opt, Keysym.ALT_L)     // ⌥
        wireModifier(R.id.btn_ctrl, Keysym.CONTROL_L)
        wireModifier(R.id.btn_shift, Keysym.SHIFT_L)

        findViewById<Button>(R.id.btn_paste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                text.forEach { typeChar(it) }
            }
        }

        findViewById<Button>(R.id.btn_disconnect).setOnClickListener { finish() }
    }

    private fun wireTapKey(id: Int, keysym: Int) {
        findViewById<Button>(id).setOnClickListener { client?.tapKey(keysym) }
    }

    private fun wireModifier(id: Int, keysym: Int) {
        val button = findViewById<Button>(id)
        button.setOnClickListener {
            val down = !(stickyModifiers[keysym] ?: false)
            stickyModifiers[keysym] = down
            client?.sendKeyEvent(keysym, down)
            // 视觉状态由 btn_toolkey/key_text 的 state_selected 选择器呈现
            button.isSelected = down
        }
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
}
