package com.macremote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.macremote.rfb.Keysym
import com.macremote.rfb.RfbProto
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 画布产生的远端输入事件，坐标为帧缓冲坐标。 */
interface RemoteInputSink {
    fun pointer(fx: Int, fy: Int, buttonMask: Int)
    fun wheel(fx: Int, fy: Int, scrollUp: Boolean)
    fun typeChar(c: Char)
    fun tapKeysym(keysym: Int)
}

/**
 * 远程桌面画布：渲染帧缓冲 Bitmap，并把触摸手势翻译成鼠标/滚轮事件。
 *
 * 手势设计：
 * - 单指拖动 = 平移视口；双指捏合 = 缩放；双指上下拖 = 滚轮
 * - 单击 = 左键；双击 = 双击左键；长按后拖动 = 按住左键拖拽；双指轻点 = 右键
 * - 文字输入走软键盘（TYPE_NULL 原始按键模式 + commitText 兜底）
 */
class RemoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var sink: RemoteInputSink? = null

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()

    private var scale = 1f
    private var minScale = 0.25f
    private var offsetX = 0f
    private var offsetY = 0f

    // 长按拖拽状态
    private var dragging = false

    // 用户是否主动缩放过（键盘弹出等导致视图改变尺寸时，保留用户的缩放，不强制回到适配大小）
    private var userZoomed = false

    // 双指手势状态
    private var multiMode = MULTI_NONE
    private var multiStartTime = 0L
    private var multiStartSpan = 0f
    private var prevSpan = 0f
    private var multiStartMidX = 0f
    private var multiStartMidY = 0f
    private var prevMidY = 0f
    private var wheelAccum = 0f
    private var ignoreUntilAllUp = false

    private val density = resources.displayMetrics.density
    private val wheelTickPx = 32f * density
    private val wheelDecidePx = 24f * density

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---------------- 渲染 ----------------

    /** 必须在 UI 线程调用。传入的 Bitmap 由 RFB 读线程持续写入。 */
    fun setRemoteBitmap(bmp: Bitmap?) {
        bitmap = bmp
        userZoomed = false
        fitToScreen()
        invalidate()
    }

    private fun fitToScreen() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val fit = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        minScale = fit
        scale = fit
        offsetX = (width - bmp.width * fit) / 2f
        offsetY = (height - bmp.height * fit) / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bmp = bitmap
        if (bmp == null || !userZoomed) {
            fitToScreen()
        } else if (w > 0 && h > 0) {
            minScale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            if (scale < minScale) scale = minScale
            clampPan()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(bmp, drawMatrix, paint)
    }

    private fun clampPan() {
        val bmp = bitmap ?: return
        val bw = bmp.width * scale
        val bh = bmp.height * scale
        offsetX = if (bw <= width) (width - bw) / 2f else offsetX.coerceIn(width - bw, 0f)
        offsetY = if (bh <= height) (height - bh) / 2f else offsetY.coerceIn(height - bh, 0f)
    }

    /** 屏幕坐标 → 帧缓冲坐标（已钳位）。bitmap 为空时返回 null。 */
    private fun mapToFb(sx: Float, sy: Float): Pair<Int, Int>? {
        val bmp = bitmap ?: return null
        val fx = ((sx - offsetX) / scale).toInt().coerceIn(0, bmp.width - 1)
        val fy = ((sy - offsetY) / scale).toInt().coerceIn(0, bmp.height - 1)
        return fx to fy
    }

    // ---------------- 触摸手势 ----------------

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (ignoreUntilAllUp) return true
            click(e.x, e.y, 1)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (ignoreUntilAllUp) return true
            click(e.x, e.y, 2)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (ignoreUntilAllUp || e.pointerCount != 1 || multiMode != MULTI_NONE) return
            val (fx, fy) = mapToFb(e.x, e.y) ?: return
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            sink?.pointer(fx, fy, 0)
            sink?.pointer(fx, fy, RfbProto.BTN_LEFT)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (ignoreUntilAllUp || dragging || e2.pointerCount != 1 || multiMode != MULTI_NONE) return true
            offsetX -= dx
            offsetY -= dy
            clampPan()
            invalidate()
            return true
        }
    })

    private fun click(sx: Float, sy: Float, times: Int) {
        val (fx, fy) = mapToFb(sx, sy) ?: return
        sink?.pointer(fx, fy, 0)
        repeat(times) {
            sink?.pointer(fx, fy, RfbProto.BTN_LEFT)
            sink?.pointer(fx, fy, 0)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2 && !dragging) {
                multiMode = MULTI_UNDECIDED
                multiStartTime = ev.eventTime
                multiStartSpan = span(ev)
                prevSpan = multiStartSpan
                multiStartMidX = midX(ev)
                multiStartMidY = midY(ev)
                prevMidY = multiStartMidY
                wheelAccum = 0f
            } else if (ev.pointerCount > 2) {
                multiMode = MULTI_NONE
                ignoreUntilAllUp = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val mapped = mapToFb(ev.x, ev.y)
                    if (mapped != null) sink?.pointer(mapped.first, mapped.second, RfbProto.BTN_LEFT)
                } else if (ev.pointerCount == 2 && multiMode != MULTI_NONE && !ignoreUntilAllUp) {
                    handleTwoFingerMove(ev)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> if (ev.pointerCount == 2) {
                // 双指轻点 = 右键（短促且没有触发缩放/滚动）
                if (multiMode == MULTI_UNDECIDED && ev.eventTime - multiStartTime < 350) {
                    val mapped = mapToFb(multiStartMidX, multiStartMidY)
                    if (mapped != null) {
                        sink?.pointer(mapped.first, mapped.second, 0)
                        sink?.pointer(mapped.first, mapped.second, RfbProto.BTN_RIGHT)
                        sink?.pointer(mapped.first, mapped.second, 0)
                    }
                }
                multiMode = MULTI_NONE
                ignoreUntilAllUp = true // 剩余手指不再当作平移，避免画面跳动
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val mapped = mapToFb(ev.x, ev.y)
                    if (mapped != null) sink?.pointer(mapped.first, mapped.second, 0)
                    dragging = false
                }
                multiMode = MULTI_NONE
                ignoreUntilAllUp = false
            }
        }
        return true
    }

    private fun handleTwoFingerMove(ev: MotionEvent) {
        val curSpan = span(ev)
        val curMidY = midY(ev)
        val curMidX = midX(ev)

        if (multiMode == MULTI_UNDECIDED) {
            val ratio = if (multiStartSpan > 0) curSpan / multiStartSpan else 1f
            when {
                abs(ratio - 1f) > 0.15f -> multiMode = MULTI_ZOOM
                abs(curMidY - multiStartMidY) > wheelDecidePx -> multiMode = MULTI_WHEEL
            }
        }

        when (multiMode) {
            MULTI_ZOOM -> {
                if (prevSpan > 0 && curSpan > 0) {
                    userZoomed = true
                    val factor = curSpan / prevSpan
                    val newScale = (scale * factor).coerceIn(minScale, max(6f, minScale * 10f))
                    val applied = newScale / scale
                    offsetX = curMidX - (curMidX - offsetX) * applied
                    offsetY = curMidY - (curMidY - offsetY) * applied
                    scale = newScale
                    clampPan()
                    invalidate()
                }
            }
            MULTI_WHEEL -> {
                wheelAccum += curMidY - prevMidY
                val mapped = mapToFb(curMidX, curMidY)
                while (mapped != null && abs(wheelAccum) >= wheelTickPx) {
                    // 手指向下挥 → 查看上方内容 → 滚轮向上
                    sink?.wheel(mapped.first, mapped.second, scrollUp = wheelAccum > 0)
                    wheelAccum += if (wheelAccum > 0) -wheelTickPx else wheelTickPx
                }
            }
        }
        prevSpan = curSpan
        prevMidY = curMidY
    }

    private fun span(ev: MotionEvent): Float =
        if (ev.pointerCount < 2) 0f
        else hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0))

    private fun midX(ev: MotionEvent): Float =
        if (ev.pointerCount < 2) ev.x else (ev.getX(0) + ev.getX(1)) / 2f

    private fun midY(ev: MotionEvent): Float =
        if (ev.pointerCount < 2) ev.y else (ev.getY(0) + ev.getY(1)) / 2f

    // ---------------- 软键盘 / 硬件键盘 ----------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.forEach { sink?.typeChar(it) }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(max(1, beforeLength)) { sink?.tapKeysym(Keysym.BACKSPACE) }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) return super.onKeyDown(keyCode, event)

        val special = specialKeysym(keyCode)
        if (special != null) {
            sink?.tapKeysym(special)
            return true
        }
        val uni = event.unicodeChar
        if (uni != 0 && (uni and android.view.KeyCharacterMap.COMBINING_ACCENT) == 0) {
            sink?.typeChar(uni.toChar())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) return super.onKeyUp(keyCode, event)
        // 按下时已发送 press+release，这里只消费事件
        return specialKeysym(keyCode) != null || event.unicodeChar != 0 || super.onKeyUp(keyCode, event)
    }

    private fun specialKeysym(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> Keysym.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Keysym.DELETE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keysym.RETURN
        KeyEvent.KEYCODE_TAB -> Keysym.TAB
        KeyEvent.KEYCODE_ESCAPE -> Keysym.ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> Keysym.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Keysym.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Keysym.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Keysym.DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> Keysym.HOME
        KeyEvent.KEYCODE_MOVE_END -> Keysym.END
        KeyEvent.KEYCODE_PAGE_UP -> Keysym.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Keysym.PAGE_DOWN
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Keysym.F1 + (keyCode - KeyEvent.KEYCODE_F1)
        else -> null
    }

    companion object {
        private const val MULTI_NONE = 0
        private const val MULTI_UNDECIDED = 1
        private const val MULTI_ZOOM = 2
        private const val MULTI_WHEEL = 3
    }
}
