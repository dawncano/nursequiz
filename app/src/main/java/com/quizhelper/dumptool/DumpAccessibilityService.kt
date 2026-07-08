package com.quizhelper.dumptool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * 自动答题/挂课服务（无障碍节点树直读 + 坐标点击）——逻辑层。
 *  - 悬浮控制 UI 已抽到 [FloatingOverlay]；本类只负责答题循环、状态机、广播、通知。
 *  - 实现 [OverlayHost]：把运行态/进度暴露给悬浮球，并接收其按钮动作。
 *  - adb 广播调试（仅 debug 包；release 包接收器锁为 NOT_EXPORTED，下列广播不对外开放）：
 *      am broadcast -a com.quizhelper.dumptool.START   开始自动答题
 *      am broadcast -a com.quizhelper.dumptool.STOP    停止
 *      am broadcast -a com.quizhelper.dumptool.SHOW    重新唤出悬浮球
 *      am broadcast -a com.quizhelper.dumptool.NODES   导出节点树(调试)
 *      am broadcast -a com.quizhelper.dumptool.NODEQ   导出题目解析(调试)
 */
// open：允许伪装子类 com.google.android.accessibility.selecttospeak.SelectToSpeakService 继承本类
// （Kotlin 类默认 final 不可继承）。逻辑全在本类，子类只是个改名外壳。
open class DumpAccessibilityService : AccessibilityService(), OverlayHost, AutoHost {

    companion object {
        private const val TAG = "DumpTool"
        @Volatile var isRunning: Boolean = false; private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** AutoHost：Service 本身即 Context，供 AiHook 读 Prefs/发网络请求用。 */
    override val appContext: android.content.Context get() = this

    override lateinit var overlay: FloatingOverlay

    override lateinit var store: AnswerStore

    // 各模式独立状态机（答题主逻辑在 QuizMachine；组计数等跨切面状态仍由本类经 AutoHost 暴露）。
    private lateinit var quizMachine: QuizMachine
    private lateinit var floatMachine: FloatMachine
    private lateinit var videoMachine: VideoMachine
    private lateinit var examMachine: ExamMachine

    // 调试导出/未识别画面取证(NODES/NODEQ/UNKNOWN 自动 dump)——与答题循环无关，已拆到 NodeDumper。
    private lateinit var dumper: NodeDumper

    // waitFor 调度(等下一屏就绪再走下一拍 + 拟人延时)——已拆到 WaitForScheduler，本类只管循环分派。
    private lateinit var waitFor: WaitForScheduler

    /** AutoHost：循环是否在跑（停/暂停后状态机不再排下一拍）。 */
    override val active: Boolean get() = auto && !paused

    // 自动答题状态
    @Volatile private var auto = false
    // 暂停：与 auto 正交。auto 仍为 true(不触发 startAuto 的计数重置)，只是循环不排下一个 step；
    // 点"继续"原地接着跑，groupsDone 等全部保留。
    @Volatile private var paused = false
    // 组进度是跨切面状态(悬浮球显示 + 停止汇总 + 考试共用 answered)，故留在本类由 AutoHost 暴露；
    // 答题的战术状态(题干/失败计数/过渡去重/提交冷却等)已随 act() 搬进 QuizMachine。
    override var targetGroups = Prefs.DEF_TARGET   // 目标组数，startAuto 时从 Prefs 读
    override var groupsDone = 0
    private var answered = 0

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.quizhelper.dumptool.START" -> {
                    intent.getIntExtra("groups", -1).takeIf { it > 0 }?.let { targetGroups = it }
                    startAuto()
                }
                "com.quizhelper.dumptool.STOP" -> stopAuto("广播停止")
                "com.quizhelper.dumptool.SHOW" -> applyOverlayMode()   // 球被拖到✕关闭后，打开App重新唤出
                "com.quizhelper.dumptool.MODE" -> {
                    // 切作答方式时若正在自动跑(如考试只读循环/悬浮)，先停——否则关掉考试模式后
                    // loopStep 会按新 mode 落到普通答题分支、开始【自动点击】，很危险。
                    if (auto) stopAuto("切换作答方式")
                    applyOverlayMode()
                }
                "com.quizhelper.dumptool.NODES" -> dumper.nodeDump()
                "com.quizhelper.dumptool.NODEQ" -> dumper.nodeQuestionDump()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        store = AnswerStore(this)
        installCrashCleanup()
        overlay = FloatingOverlay(this, this)
        quizMachine = QuizMachine(this)
        floatMachine = FloatMachine(this)
        videoMachine = VideoMachine(this)
        examMachine = ExamMachine(this)
        dumper = NodeDumper(this) { targetRoot() }
        waitFor = WaitForScheduler(this, { active }, { targetRoot() }, { loopStep() })
        applyOverlayMode()   // 悬浮答案模式不显大球(用音量+键控制)；其它模式显示控制球
        val filter = IntentFilter().apply {
            addAction("com.quizhelper.dumptool.START")
            addAction("com.quizhelper.dumptool.STOP")
            addAction("com.quizhelper.dumptool.SHOW")
            addAction("com.quizhelper.dumptool.MODE")
            addAction("com.quizhelper.dumptool.NODES")
            addAction("com.quizhelper.dumptool.NODEQ")
        }
        // 命令广播仅供开发期 adb 调试。release 包锁成 NOT_EXPORTED——否则设备上任意 App 都能发
        // START(触发自动点击)/NODES(把当前屏幕节点树导出到剪贴板)。App 自身(MainActivity)的
        // SHOW/MODE 广播走 setPackage(同 uid)，NOT_EXPORTED 也照收，不受影响。
        val cmdExport = if (BuildConfig.DEBUG) ContextCompat.RECEIVER_EXPORTED
                        else ContextCompat.RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(this, cmdReceiver, filter, cmdExport)
        // 考试模式(错峰)：无障碍是被 ExamOverlayService 在用户双击「。。。」后自动开起来的，
        // 这次重连 = 用户已发出"开始"信号 → 自动起只读循环(读题跨库搜答案广播给浮窗)，无需点悬浮球。
        if (Prefs.examMode(this)) { startAuto(); return }
        toast("自动答题服务已开启")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** 音量+键的隐蔽触发（竞品同款）：
     *  - 悬浮答案模式：开始/结束自动循环；
     *  - 考试模式：隐藏/显示考试浮窗（广播给 ExamOverlayService，遮答案用，不改音量）。
     *  仅这两模式拦截，不影响平时调音量。 */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return super.onKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        return when (Prefs.mode(this)) {
            // 考试：隐/显考试浮窗(遮答案，广播给 ExamOverlayService，不改音量)。
            AnswerMode.EXAM -> { if (down) ExamOverlayService.toggle(this); true }
            // 悬浮答案：开始/结束自动循环。都消费掉音量键，避免误改音量。
            AnswerMode.FLOAT -> { if (down) { if (auto) stopAuto("音量键停止") else startAuto() }; true }
            else -> super.onKeyEvent(event)   // 普通/视频不拦，正常调音量
        }
    }

    /** 按作答方式决定显示形态：悬浮答案模式不显大球(用音量+键开始/结束)，改在原球位置显示
     *  小答案标签、立刻显「。。。」占位(让用户知道已切换成功)；其余模式显示控制球、撤掉标签。 */
    private fun applyOverlayMode() {
        if (!::overlay.isInitialized) return
        // 呈现策略全由 AnswerMode 定义：考试→球关、答案走独立浮窗(本 overlay 不显)；悬浮→球关、显小标签占位；
        // 普通/视频→显控制球、撤标签。
        val mode = Prefs.mode(this)
        overlay.setBallVisible(mode.showsControlBall)
        if (mode.usesAnswerLabel) overlay.showAnswer("。。。") else overlay.hideAnswer()
    }
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        isRunning = false
        auto = false
        clearDumps()   // 服务被解绑/系统关闭也算"结束"，不能依赖手动点清理按钮才删临时文件
        runCatching { unregisterReceiver(cmdReceiver) }
        if (::overlay.isInitialized) overlay.remove()
    }

    /** 进程意外崩溃时尽力清一次临时文件再交还给系统默认处理（不吞掉崩溃，只是顺手清理）。 */
    private fun installCrashCleanup() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching { clearDumps() }
            prev?.uncaughtException(thread, ex)
        }
    }

    // ---------------------------------------------------------------------
    // OverlayHost：把运行态/进度暴露给悬浮球，并接收其按钮动作
    // ---------------------------------------------------------------------

    override fun overlayRunning() = auto
    override fun overlayPaused() = paused
    override fun overlayGroupsDone() = groupsDone
    /** 运行中取本次锁定的 targetGroups，空闲时取设置页当前值。 */
    override fun overlayTarget() = if (auto) targetGroups else Prefs.targetGroups(this)
    override fun overlayMode() = Prefs.mode(this)
    override fun overlayStart() { startAuto() }
    override fun overlayPause() { if (auto && !paused) togglePause() }
    override fun overlayResume() { if (paused) togglePause() }
    override fun overlayStop() { stopAuto("手动停止") }
    override fun overlayClose() {
        if (auto) stopAuto("手动结束")
        toast("悬浮球已关闭，打开 App 可重新显示")
    }

    // ---------------------------------------------------------------------
    // 自动答题循环
    // ---------------------------------------------------------------------

    /** 暂停/继续。暂停≠停止：auto 不变(计数全部保留)，只让循环停在原地；继续就接着跑。 */
    private fun togglePause() {
        if (!auto) { toast("未在运行，无需暂停"); return }
        paused = !paused
        overlay.refresh()
        if (paused) {
            overlay.setVisible(true)   // 暂停时露出悬浮球，方便点"继续"，也不挡屏幕
            toast("已暂停（进度保留，点继续接着跑）")
            Log.i(TAG, "PAUSE at groups=$groupsDone answered=$answered")
        } else {
            toast("继续")
            Log.i(TAG, "RESUME")
            waitFor.kick()
        }
    }

    private fun startAuto() {
        if (auto) return
        auto = true
        paused = false
        targetGroups = Prefs.targetGroups(this)   // 本次运行的目标组数，取设置页的值
        groupsDone = 0
        answered = 0
        waitFor.reset()
        quizMachine.reset()
        videoMachine.reset()
        floatMachine.reset()
        examMachine.reset()
        overlay.refresh()
        val videoMode = Prefs.videoMode(this)
        overlay.setKeepScreenOn(videoMode)   // 视频挂课防息屏
        toast(when {
            videoMode -> "视频自动挂课：进视频任务详情即自动观看"
            Prefs.examMode(this) -> "考试模式：读到题目会在浮窗显示答案，音量+隐/显"
            Prefs.floatMode(this) -> "悬浮答案模式：进题目会显示答案，自己点"
            else -> "开始自动答题，目标 $targetGroups 组"
        })
        waitFor.kick()
    }

    override fun stopAuto(reason: String) {
        if (!auto) return
        auto = false
        paused = false
        overlay.setVisible(true)
        // 悬浮答案模式停止后保留占位「。。。」(模式还在，用户能看到)；其它模式撤掉标签。
        if (Prefs.mode(this).keepsPlaceholderOnStop) overlay.showAnswer("。。。") else overlay.hideAnswer()
        overlay.setKeepScreenOn(false)   // 视频挂课的防息屏一并撤掉
        overlay.refresh()
        overlay.scheduleCollapse()   // 结束后完整态停留 N 秒再自动收回成球
        clearDumps()   // 不管是手动停止还是达到目标组数自动停止，都顺手清掉本次运行的调试临时文件
        Log.i(TAG, "STOP: $reason (groups=$groupsDone answered=$answered)")
        // 汇总/通知按模式给文案——"完成X组答Y题"只对答题有意义；视频挂课没有"组/题"，只报原因。
        val isVideo = Prefs.mode(this) == AnswerMode.VIDEO
        val summary = if (isVideo) reason else "$reason（完成 $groupsDone 组，答 $answered 题）"
        val title = if (isVideo) "视频挂课已停止" else "自动答题已停止"
        toast("已停止：$summary")
        // 非手动停止(达标完成 / 卡住需人工)再发一条通知——无人值守时 toast 一闪而过看不到。
        if (reason != "手动停止" && reason != "手动结束") notifyStop(title, summary)
    }

    /** 发一条"已停止"的通知(标题按模式)。Android13+ 没授通知权限时系统会静默丢弃，不影响其它逻辑。 */
    private fun notifyStop(title: String, msg: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = ensureChannel("quiz_auto_stop", "自动答题停止提醒", NotificationManager.IMPORTANCE_HIGH)
            val n = Notification.Builder(this, chId)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(msg)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, n)
        }
    }

    private fun loopStep() {
        if (!auto || paused) return
        // 三个特殊模式各有独立状态机；普通答题留在本类(与组计数/循环紧耦合)。
        // 互斥+优先级在 Prefs.mode 统一解析(原 if 顺序：视频>考试>悬浮>普通)，行为不变。
        when (Prefs.mode(this)) {
            AnswerMode.VIDEO -> videoMachine.step()
            AnswerMode.EXAM -> examMachine.step()
            AnswerMode.FLOAT -> floatMachine.step()
            AnswerMode.QUIZ -> quizMachine.step()
        }
    }

    // ---- AutoHost：供各状态机调用的能力（tap/tapSequence/targetRoot/scheduleNextStep/stopAuto 见下文复用） ----
    override fun postDelayed(delayMs: Long, action: () -> Unit) { mainHandler.postDelayed(action, delayMs) }
    override fun scheduleNextStep() = waitFor.scheduleNextStep()
    override fun markStepSig(model: ScreenModel) = waitFor.markStepSig(model)
    override fun markStepSigNow() = waitFor.markStepSigNow()
    override fun incAnswered() { answered++ }
    override fun unknownLimit(): Int = Prefs.unknownLimit(this)
    override fun captureUnhandled() { dumper.captureUnhandled() }
    /** 显示答案：考试模式(错峰、无障碍随时会被关)走独立前台服务的浮窗；其余走无障碍 overlay。 */
    override fun showAnswer(text: String) {
        if (Prefs.mode(this).usesExamOverlay) ExamOverlayService.showAnswer(this, text)
        else overlay.showAnswer(text)
    }

    /** 依次点击多个坐标（多选题用），点完回调。
     *  点击间隔/选完到点确定的等待都按当前速度(stepInterval)成比例缩放——
     *  这样"速度"一个设置就能整体压到最快，又各留安全下限。 */
    override fun tapSequence(points: List<XY>, done: () -> Unit) {
        val iv = Prefs.stepIntervalMs(this)
        val gap = (iv / 3).coerceIn(110L, 350L)      // 多选连续点击之间
        val settle = (iv / 2).coerceIn(140L, 450L)   // 选完到点"确定"之间(等"确定"变可点)
        if (points.isEmpty()) { mainHandler.postDelayed(done, settle); return }
        var i = 0
        fun next() {
            if (i >= points.size) { mainHandler.postDelayed(done, settle); return }
            tap(points[i]); i++
            mainHandler.postDelayed({ next() }, gap)
        }
        next()
    }

    // ---------------------------------------------------------------------
    // 点击手势
    // ---------------------------------------------------------------------

    override fun tap(p: XY?) {
        p ?: return
        // 注意：零长度路径(只有 moveTo)在部分设备上会被静默忽略，加 1px lineTo。
        val path = Path().apply {
            moveTo(p.x.toFloat(), p.y.toFloat())
            lineTo(p.x + 1f, p.y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { Log.i(TAG, "tap CALLBACK ok @${p.x},${p.y}") }
            override fun onCancelled(d: GestureDescription?) { Log.w(TAG, "tap CALLBACK cancel @${p.x},${p.y}") }
        }, null)
        Log.i(TAG, "dispatchGesture returned=$ok @${p.x},${p.y}")
    }

    // ---------------------------------------------------------------------
    // 调试导出（节点树）
    // ---------------------------------------------------------------------

    /** 取目标 App 的窗口根：排除自己的悬浮窗，取面积最大的外部窗口。 */
    override fun targetRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName != packageName) return it }
        var best: android.view.accessibility.AccessibilityNodeInfo? = null
        var bestArea = 0
        for (w in windows ?: emptyList()) {
            val rt = w.root ?: continue
            if (rt.packageName == packageName) continue
            val r = Rect(); rt.getBoundsInScreen(r)
            val a = r.width() * r.height()
            if (a > bestArea) { bestArea = a; best = rt }
        }
        return best ?: rootInActiveWindow
    }

    // ---------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------

    /** 清空临时文件目录(调试导出的节点树等)。自动答题不写这些，仅调试广播会写。
     *  每组/每轮提交、手动或自动停止、服务被解绑、进程崩溃前都会调用，避免越用越大。 */
    override fun clearDumps() = AnswerStore.clearTemp(this)
}
