package com.quizhelper.dumptool

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮答案小标签：浅灰小字、无底色、极淡阴影，落在原悬浮球位置(同一套 Prefs 的贴边+Y)，可拖动。
 * 仿竞品 win2——尽量不引人注意。
 *
 * 悬浮答案模式([FloatingOverlay]，无障碍 overlay)与考试模式([ExamOverlayService]，应用 overlay)共用：
 * 样式/定位/拖动单一实现，仅**窗口类型 / 最大宽度 / 是否需要双击**由构造参数区分。
 *
 * @param windowType `TYPE_ACCESSIBILITY_OVERLAY`(无障碍存活时) 或 `TYPE_APPLICATION_OVERLAY`(考试错峰、无障碍被关时)。
 * @param onDoubleTap 非空则支持双击(考试用"双击=开始")；为空则只拖动。
 * @param onAddError addView 失败回调(如未授 SYSTEM_ALERT_WINDOW)，为空则静默。
 */
class AnswerLabel(
    private val ctx: Context,
    private val windowType: Int,
    private val maxWidthDp: Int = 132,
    private val onDoubleTap: (() -> Unit)? = null,
    private val onAddError: ((Throwable) -> Unit)? = null,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var lp: WindowManager.LayoutParams? = null

    val isShown: Boolean get() = view != null

    private fun dp(v: Int) = (ctx.resources.displayMetrics.density * v).toInt()

    /** 显示/更新文本：首次创建并挂窗，之后仅更新文字。post 到主线程，可任意线程调用。 */
    fun show(text: String) = handler.post {
        val tv = view ?: create().also { v ->
            runCatching { wm.addView(v, lp) }.onFailure { onAddError?.invoke(it) }
        }
        tv.text = text
    }

    /** 移除浮窗。 */
    fun remove() = handler.post {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null; lp = null
    }

    /** 隐藏/显示(保留窗口只切 alpha)——考试音量+键遮答案用。 */
    fun toggleHidden() = handler.post {
        val v = view ?: return@post
        v.alpha = if (v.alpha == 0f) 0.95f else 0f
    }

    private fun create(): TextView {
        val tv = TextView(ctx).apply {
            setTextColor(0xFFCCCCCC.toInt())   // 浅灰字、无底色，尽量不引人注意
            textSize = 11f
            gravity = Gravity.CENTER
            setShadowLayer(1.5f, 0f, 1f, 0x33000000)   // 极淡阴影，白底上还认得出
            setPadding(dp(4), dp(2), dp(4), dp(2))
            alpha = 0.95f
            maxWidth = dp(maxWidthDp)
        }
        // 落在原悬浮球的位置(贴左右边 + 垂直 Y)，让用户一眼认得这是球变来的。
        val b = wm.currentWindowMetrics.bounds
        val savedY = Prefs.overlayY(ctx)
        val y0 = if (savedY != Int.MIN_VALUE) savedY else (b.height() * 0.15f).toInt()
        val onRight = Prefs.overlaySideRight(ctx)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or (if (onRight) Gravity.RIGHT else Gravity.LEFT); x = dp(4); y = y0 }
        attachTouch(tv, p)
        view = tv; lp = p
        return tv
    }

    /** 手动拖动(与控制球一致的直拖)；若给了 onDoubleTap 再叠一个双击识别。 */
    private fun attachTouch(tv: TextView, p: WindowManager.LayoutParams) {
        val gd = onDoubleTap?.let { cb ->
            GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean { cb(); return true }
            })
        }
        var ix = 0; var iy = 0; var downX = 0f; var downY = 0f; var moved = false
        val slop = dp(6)
        tv.setOnTouchListener { _, e ->
            gd?.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ix = p.x; iy = p.y; downX = e.rawX; downY = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ix + (e.rawX - downX).toInt(); p.y = iy + (e.rawY - downY).toInt()
                    if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) moved = true
                    runCatching { wm.updateViewLayout(tv, p) }
                }
                // 拖动后记住位置：与控制球共用 Prefs 的 Y+贴边，下次(球或标签)从这儿出现。
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (moved) Prefs.setOverlayPos(ctx, p.y, Prefs.overlaySideRight(ctx))
            }
            true
        }
    }
}
