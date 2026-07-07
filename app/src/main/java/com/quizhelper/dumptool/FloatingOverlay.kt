package com.quizhelper.dumptool

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮窗需要从外部(服务)读取的运行态 + 按钮动作回调。
 * UI 只负责"怎么显示/怎么收回"，"开始/暂停/结束做什么"全交给宿主(逻辑层)。
 */
interface OverlayHost {
    fun overlayRunning(): Boolean
    fun overlayPaused(): Boolean
    fun overlayGroupsDone(): Int
    fun overlayTarget(): Int          // 运行中=本次锁定目标；空闲=设置页当前值
    fun overlayMode(): AnswerMode     // 当前作答模式——组进度只对答题有意义，视频挂课等要换成对应文案
    fun overlayStart()
    fun overlayPause()
    fun overlayResume()
    fun overlayStop()
    fun overlayClose()                // 拖到✕：停答题(宿主决定)，UI 随后移除自己
}

/**
 * 自动答题的悬浮控制球。三级形态：球(依附) → 胶囊(进度) → 完整(按钮)，无操作自动收回成球。
 * 直接拖拽(超阈值即拖、完整态也能拖)，松手吸最近左右边；拖到底部✕关闭。
 * 纯 UI 组件，不碰答题逻辑——通过 [OverlayHost] 读状态、回调动作。
 */
class FloatingOverlay(private val ctx: Context, private val host: OverlayHost) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private enum class Level { BALL, ARCH, FULL }
    private var level = Level.BALL

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var ballView: TextView? = null
    private var closeTargetView: TextView? = null
    private var overClose = false

    // 悬浮答案模式的答案标签（独立小窗，与控制球解耦——控制球会为点击让路而隐藏，答案标签不受影响）。
    // 与考试浮窗共用 [AnswerLabel]，仅窗口类型不同（此处无障碍 overlay）。
    private val answerLabel = AnswerLabel(ctx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, maxWidthDp = 132)

    private val collapseRunnable = Runnable { collapseToBall() }

    private val colorIdle = 0xFF3F51B5.toInt()
    private val colorRun = 0xFF2E7D32.toInt()
    private val colorPause = 0xFFEF6C00.toInt()
    private val colorFail = 0xFFC62828.toInt()

    private fun dp(v: Int) = (ctx.resources.displayMetrics.density * v).toInt()
    private fun stateColor() = when {
        !host.overlayRunning() -> colorIdle
        host.overlayPaused() -> colorPause
        else -> colorRun
    }

    // ---- 对外 API（服务调用）-------------------------------------------------

    /** 添加悬浮球（已存在则忽略）。被✕关闭后可再次调用重新唤出。 */
    fun show() {
        if (root != null) return
        val frame = DragFrame(ctx)
        root = frame

        val screenH = wm.currentWindowMetrics.bounds.height()
        val savedY = Prefs.overlayY(ctx)
        val initY = if (savedY != Int.MIN_VALUE) savedY else (screenH * 0.15f).toInt()
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 0; y = initY }

        renderLevel()
        frame.post { snapToEdge() }
        runCatching { wm.addView(frame, params) }
            .onFailure { ctx.toast("悬浮窗添加失败：${it.message}") }
    }

    /** 悬浮答案模式不要大球：只移除/恢复控制球本身（答案小标签独立、不受影响）。
     *  visible=true 等于 show()；false 只摘掉球，留着答案标签照常显示。 */
    fun setBallVisible(visible: Boolean) {
        if (visible) { show(); return }
        cancelCollapse(); hideCloseTarget()
        root?.let { v -> runCatching { wm.removeView(v) } }
        root = null; params = null; ballView = null
    }

    /** 移除悬浮球（服务销毁或✕关闭时）。 */
    fun remove() {
        cancelCollapse()
        hideCloseTarget()
        hideAnswer()
        root?.let { v -> runCatching { wm.removeView(v) } }
        root = null; params = null; ballView = null
    }

    /** 把悬浮球设为可点(true)/穿透(false)。只切 FLAG_NOT_TOUCHABLE、不改可见性。
     *  答题主循环已不再每步调它(球在左边缘、点击在屏幕中部，不重叠，无需藏球)，现仅暂停/停止时用。 */
    fun setVisible(visible: Boolean) {
        val p = params ?: return
        val r = root ?: return
        p.flags = if (visible)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(r, p) }
    }

    /** 运行态/进度变化后刷新显示（重建当前层级即可）。 */
    fun refresh() = renderLevel()

    /**
     * 悬浮答案模式：显示/更新答案。切到该模式即显示占位「。。。」，命中后变正确答案原文。空串才隐藏。
     * 标签实现见 [AnswerLabel]（与考试浮窗共用）。
     */
    fun showAnswer(text: String) {
        if (text.isBlank()) hideAnswer() else answerLabel.show(text)
    }

    /** 隐藏答案标签（切回自动模式/停止/服务销毁时）。 */
    fun hideAnswer() = answerLabel.remove()

    /** 防息屏：给悬浮球窗口加/去 FLAG_KEEP_SCREEN_ON（视频挂课时开，保持亮屏，竞品"防息屏"等价）。 */
    fun setKeepScreenOn(on: Boolean) {
        handler.post {
            val p = params ?: return@post
            val r = root ?: return@post
            p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                      else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            runCatching { wm.updateViewLayout(r, p) }
        }
    }

    fun collapseToBall() { cancelCollapse(); setLevel(Level.BALL) }

    /** 无操作 N 秒后自动收回成球。每次交互后重排。 */
    fun scheduleCollapse() {
        cancelCollapse()
        handler.postDelayed(collapseRunnable, Prefs.attachDelayMs(ctx))
    }

    // ---- 触摸/拖拽 -----------------------------------------------------------

    /**
     * 根容器。重写 onInterceptTouchEvent：手指移动超阈值就【从子按钮手里抢过手势】进入拖拽，
     * 这样完整态(有按钮)也能拖；没移动才让按钮正常点击。拖完立刻收回成球(吸附)。
     */
    private inner class DragFrame(c: Context) : FrameLayout(c) {
        private var ix = 0; private var iy = 0; private var downX = 0f; private var downY = 0f
        private var moved = false
        private val slop = dp(6)
        private fun begin(e: MotionEvent) {
            val p = params ?: return
            ix = p.x; iy = p.y; downX = e.rawX; downY = e.rawY; moved = false
            cancelCollapse()
        }
        private fun beyondSlop(e: MotionEvent) = abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(e)
                MotionEvent.ACTION_MOVE -> if (beyondSlop(e)) { moved = true; return true }
            }
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { begin(e); return true }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && beyondSlop(e)) moved = true
                    if (moved) {
                        val p = params ?: return true
                        p.x = ix + (e.rawX - downX).toInt(); p.y = iy + (e.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(this, p) }
                        showCloseTarget()
                        updateCloseHover(p.x + width / 2, p.y + height / 2)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasOverClose = moved && overClose
                    hideCloseTarget()
                    when {
                        wasOverClose -> { host.overlayClose(); remove() }   // 拖进✕=关闭
                        moved -> collapseToBall()                           // 拖完立刻吸附成球
                        else -> { onTap(); scheduleCollapse() }             // 没动=单击展开一级
                    }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }

    /** 单击展开一级：球→胶囊→完整。完整态点空白不再展开。 */
    private fun onTap() {
        when (level) {
            Level.BALL -> setLevel(Level.ARCH)
            Level.ARCH -> setLevel(Level.FULL)
            Level.FULL -> {}
        }
    }

    private fun cancelCollapse() = handler.removeCallbacks(collapseRunnable)
    private fun setLevel(l: Level) { level = l; renderLevel() }

    /** 悬浮球只贴左右边：松手/重建后按中心点判定吸最近边，y 夹在屏内并持久化。 */
    private fun snapToEdge() {
        val r = root ?: return
        val p = params ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val vw = r.width.takeIf { it > 0 } ?: dp(48)
        val vh = r.height.takeIf { it > 0 } ?: dp(48)
        val isRight = (p.x + vw / 2) >= bounds.width() / 2
        p.x = if (isRight) (bounds.width() - vw) else 0
        p.y = p.y.coerceIn(0, (bounds.height() - vh).coerceAtLeast(0))
        Prefs.setOverlayPos(ctx, p.y, isRight)
        if (level == Level.BALL) ballView?.background = ballShape(stateColor(), isRight)
        runCatching { wm.updateViewLayout(r, p) }
    }

    // ---- ✕ 关闭靶 -----------------------------------------------------------

    /** 拖拽时在底部中央浮出 ✕ 关闭靶（不拦截触摸，只做视觉）。 */
    private fun showCloseTarget() {
        if (closeTargetView != null) return
        val tv = TextView(ctx).apply {
            text = "✕"; setTextColor(0xFFFFFFFF.toInt()); textSize = 26f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA000000.toInt()) }
            alpha = 0.85f
        }
        val d = dp(64)
        val lp = WindowManager.LayoutParams(
            d, d, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(72) }
        closeTargetView = tv
        runCatching { wm.addView(tv, lp) }
    }

    private fun hideCloseTarget() {
        closeTargetView?.let { v -> runCatching { wm.removeView(v) } }
        closeTargetView = null
        overClose = false
    }

    /** 球中心是否压在 ✕ 靶上：压上=放大高亮，记 overClose 供松手判定。 */
    private fun updateCloseHover(ballCx: Int, ballCy: Int) {
        val b = wm.currentWindowMetrics.bounds
        val tx = b.width() / 2
        val ty = b.height() - dp(72) - dp(32)   // 靶中心：底部 margin + 半径
        val rad = dp(72)
        val dx = ballCx - tx; val dy = ballCy - ty
        overClose = dx * dx + dy * dy < rad * rad
        closeTargetView?.apply {
            val s = if (overClose) 1.3f else 1f
            scaleX = s; scaleY = s; alpha = if (overClose) 1f else 0.85f
        }
    }

    // ---- 渲染各层级 ---------------------------------------------------------

    /** 按当前层级 + 运行态重建内容，重建后重新吸边（宽度变了要保持贴边）。 */
    private fun renderLevel() {
        handler.post {
            val r = root ?: return@post
            r.removeAllViews()
            r.addView(
                when (level) {
                    Level.BALL -> buildBall()
                    Level.ARCH -> buildArch()
                    Level.FULL -> buildFull()
                }
            )
            r.post { snapToEdge() }
        }
    }

    private fun pillBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(22).toFloat(); setColor(color)
    }

    /** 半球(D形)：贴边那侧切平、朝屏内那侧半圆，看起来嵌在屏幕边缘里。随贴左/贴右切换圆弧朝向。 */
    private fun ballShape(color: Int, isRight: Boolean): GradientDrawable {
        val r = dp(24).toFloat()
        val radii = if (isRight)
            floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)   // 贴右：左侧圆(朝内)、右侧平(贴边)
        else
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)   // 贴左：右侧圆(朝内)、左侧平(贴边)
        return GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadii = radii; setColor(color) }
    }

    /** 球态：依附边缘的半球，透明度高(更不挡)。图标随运行态变。 */
    private fun buildBall(): View {
        val glyph = when { !host.overlayRunning() -> "▶"; host.overlayPaused() -> "‖"; else -> "●" }
        return TextView(ctx).apply {
            text = glyph
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            background = ballShape(stateColor(), Prefs.overlaySideRight(ctx))
            alpha = 0.6f
            val d = dp(48)
            layoutParams = FrameLayout.LayoutParams(d, d)
            ballView = this
        }
    }

    /** 胶囊态：从边缘探出，显示进度。答题=组进度"当前/目标"；视频挂课没有"组"概念，显示"挂课"。 */
    private fun buildArch(): View = TextView(ctx).apply {
        text = if (host.overlayMode() == AnswerMode.VIDEO) "挂课"
        else "${host.overlayGroupsDone()}/${host.overlayTarget()}"
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        gravity = Gravity.CENTER
        background = pillBg(stateColor())
        alpha = 0.88f
        setPadding(dp(18), dp(8), dp(18), dp(8))
    }

    /** 完整态：按运行态显示按钮。空闲=开始(进度)；运行=暂停|结束；暂停=继续(进度)|结束。 */
    private fun buildFull(): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBg(0xCC222222.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
            alpha = 0.95f
        }
        val g = host.overlayGroupsDone(); val t = host.overlayTarget()
        val video = host.overlayMode() == AnswerMode.VIDEO   // 视频挂课不显示"组"进度
        when {
            !host.overlayRunning() ->
                bar.addView(makeButton(if (video) "▶ 开始挂课" else "▶ 开始 (0/$t)", colorIdle) { host.overlayStart(); collapseToBall() })
            host.overlayPaused() -> {
                bar.addView(makeButton(if (video) "▶ 继续" else "▶ 继续 ($g/$t)", colorRun) { host.overlayResume(); collapseToBall() })
                bar.addView(makeButton("■ 结束", colorFail) { host.overlayStop() })
            }
            else -> {
                bar.addView(makeButton("‖ 暂停", colorPause) { host.overlayPause(); scheduleCollapse() })
                bar.addView(makeButton("■ 结束", colorFail) { host.overlayStop() })
            }
        }
        return bar
    }

    private fun makeButton(text: String, color: Int, onClick: () -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            background = pillBg(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }

}
