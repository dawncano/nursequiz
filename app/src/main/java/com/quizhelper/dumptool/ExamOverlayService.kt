package com.quizhelper.dumptool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 考试模式的独立浮窗服务（错峰方案的核心，见 REQUIREMENTS 5.8）。
 *
 * 为什么单独一个前台 Service：护理助手考试页进场(WebView onLoadEnd)一刀切检测无障碍(见 2.3)，
 * 所以进考试前必须【关掉】无障碍——可无障碍一关，[DumpAccessibilityService] 就被系统销毁，
 * 它托管的 `TYPE_ACCESSIBILITY_OVERLAY` 悬浮窗随之消失。故考试浮窗改由本服务用
 * `TYPE_APPLICATION_OVERLAY`(需 SYSTEM_ALERT_WINDOW)托管，独立于无障碍存活。
 *
 * 生命周期 / 流程：
 *  - onCreate：弹「。。。」浮窗 + 自动关无障碍 → 用户切护理助手、签到进考试(检测已关，放行)。
 *  - 双击浮窗：自动开无障碍 + 文字变「开始」→ 无障碍服务重连、见 examMode 自动起只读循环。
 *  - 收到广播 EXAM_ANSWER(text)：把答案原文显示到浮窗(无障碍服务读题+跨库搜索后广播来的)。
 *  - 收到广播 EXAM_TOGGLE：隐藏/显示浮窗(无障碍服务拦音量+键转发来的)。
 *  - onDestroy(关考试模式)：移除浮窗 + 恢复开启无障碍(供普通模式用)。
 */
class ExamOverlayService : Service() {

    companion object {
        private const val TAG = "DumpTool"
        const val ACTION_ANSWER = "com.quizhelper.dumptool.EXAM_ANSWER"   // 无障碍→本服务：显示答案
        const val ACTION_TOGGLE = "com.quizhelper.dumptool.EXAM_TOGGLE"   // 无障碍→本服务：隐/显浮窗
        const val EXTRA_TEXT = "text"
        private const val CH_ID = "quiz_exam_overlay"
        private const val NOTIF_ID = 1002
        private const val PLACEHOLDER = "。。。"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var label: TextView? = null
    private var armed = false     // 是否已双击开始(开无障碍后)
    private var hidden = false    // 音量+切换的隐/显态

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ANSWER -> setText(intent.getStringExtra(EXTRA_TEXT) ?: "unknown")
                ACTION_TOGGLE -> toggleHidden()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        showLabel(PLACEHOLDER)
        val f = IntentFilter().apply { addAction(ACTION_ANSWER); addAction(ACTION_TOGGLE) }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        // 进场前关无障碍——骗过考试页 onLoadEnd 的检测(见 2.3)。没授 WRITE_SECURE_SETTINGS 时安全失败。
        val disabled = A11yEnabler.disable(this)
        toast(if (disabled) "考试模式：已关无障碍，进考试后双击「。。。」开始" else "考试模式已开，但未能自动关无障碍(需adb授权)")
        Log.i(TAG, "ExamOverlay onCreate disabledA11y=$disabled")
    }

    // NOT_STICKY：万一被系统杀掉，不要用 null intent 自动重启——那会重跑 onCreate 又关一次无障碍、
    // 把浮窗重置回「。。。」，考试中途很突兀。宁可不重启，让用户重开考试模式。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        removeLabel()
        // 关考试模式：把无障碍恢复开起来，普通/悬浮/视频模式才能用。
        A11yEnabler.enable(this)
        Log.i(TAG, "ExamOverlay onDestroy 恢复无障碍")
        super.onDestroy()
    }

    // ---- 前台服务 ----------------------------------------------------------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "考试悬浮答案", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = Notification.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle("考试模式")
            .setContentText("悬浮答案运行中")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---- 浮窗 --------------------------------------------------------------

    /** 样式对齐悬浮答案标签(5.2)：浅灰小字、无底色、极淡阴影，尽量不引人注意。可拖动、双击开始。 */
    private fun showLabel(text: String) {
        if (label != null) { setText(text); return }
        val tv = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setShadowLayer(1.5f, 0f, 1f, 0x33000000)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            alpha = 0.95f
            maxWidth = dp(200)
            this.text = text
        }
        val b = wm.currentWindowMetrics.bounds
        val savedY = Prefs.overlayY(this)
        val y0 = if (savedY != Int.MIN_VALUE) savedY else (b.height() * 0.15f).toInt()
        val onRight = Prefs.overlaySideRight(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or (if (onRight) Gravity.RIGHT else Gravity.LEFT); x = dp(4); y = y0 }
        attachTouch(tv, lp)
        label = tv
        runCatching { wm.addView(tv, lp) }
            .onFailure { toast("考试浮窗添加失败：${it.message}") }
    }

    private fun setText(text: String) = handler.post { label?.text = text }

    private fun removeLabel() {
        label?.let { v -> runCatching { wm.removeView(v) } }
        label = null
    }

    /** 音量+：隐藏/显示浮窗(遮答案，不引人注意)。用穿透+透明切换，保留窗口以便再显示。 */
    private fun toggleHidden() = handler.post {
        val v = label ?: return@post
        hidden = !hidden
        v.alpha = if (hidden) 0f else 0.95f
    }

    /** 双击=开始：开无障碍 + 文字变「开始」。无障碍服务重连后见 examMode 自动起只读循环。 */
    private fun onDoubleTap() {
        if (armed) return
        armed = true
        val ok = A11yEnabler.enable(this)
        setText(if (ok) "开始" else "开无障碍失败")
        toast(if (ok) "已开启无障碍，开始读题显示答案" else "未能自动开无障碍(需adb授权)")
        Log.i(TAG, "ExamOverlay 双击开始 enableA11y=$ok")
    }

    /** 触摸：GestureDetector 认双击(=开始)，同时手动处理拖动(与悬浮答案标签一致)。 */
    private fun attachTouch(tv: TextView, lp: WindowManager.LayoutParams) {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTap(); return true }
        })
        var ix = 0; var iy = 0; var downX = 0f; var downY = 0f
        tv.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ix + (e.rawX - downX).toInt(); lp.y = iy + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(tv, lp) }
                }
            }
            true
        }
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()
    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
