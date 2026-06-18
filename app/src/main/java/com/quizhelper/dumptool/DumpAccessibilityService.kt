package com.quizhelper.dumptool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.text.Text
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 自动答题服务（截图 + OCR + 坐标点击）。
 *  - 悬浮按钮：▶自动/■停止；并保留 OCR/节点 调试导出。
 *  - adb 广播调试：
 *      am broadcast -a com.quizhelper.dumptool.START   开始自动答题
 *      am broadcast -a com.quizhelper.dumptool.STOP    停止
 *      am broadcast -a com.quizhelper.dumptool.STEP    单步：截图+解析，把模型写文件(不点击)
 *      am broadcast -a com.quizhelper.dumptool.OCR     导出原始OCR
 *      am broadcast -a com.quizhelper.dumptool.NODES   导出节点树
 */
class DumpAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DumpTool"
        @Volatile var isRunning: Boolean = false; private set
    }

    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var autoButton: Button? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var store: AnswerStore

    // 自动答题状态
    @Volatile private var auto = false
    private var targetGroups = Prefs.DEF_TARGET   // 目标组数，startAuto 时从 Prefs 读
    private var groupsDone = 0
    private var answered = 0
    private var blindRound = 0             // 本组当前轮次(0=A,1=B…)，每次"提交"+1
    private var groupInProgress = false    // 是否正在做某一组(用于判断回到任务页=一组完成)
    private var lastQuestionText = ""      // QUESTION页的题干，用于FEEDBACK存答案(避免FEEDBACK多出解析文字)
    private var lastStuckQ = ""            // 连续卡在同一题的检测
    private var sameQCount = 0

    // 兜底：已存答案连续"判错"次数(按题干稳定key)。达到上限就不再信任存档答案，
    // 改走 blindRound 轮流盲选——保证哪怕坐标/识别出了我们没预料到的错，也能跳出死循环。
    private val failCount = HashMap<String, Int>()
    private var lastTapKey: String? = null
    private var lastTapIntended: List<Int> = emptyList()   // QUESTION页本打算点的下标，FEEDBACK拿来和"你的答案"比对

    // 连续识别成 UNKNOWN 的次数。每帧先尝试自救(点掉疑似弹窗)，连续到上限仍没恢复
    // 就判定无法自愈：提示用户 + 停止 + 等人工处理，而不是无限死等。上限从 Prefs 读。
    private var unknownStreak = 0
    private val failLimit = 2

    private val colorIdle = 0xFF3F51B5.toInt()
    private val colorRun = 0xFF2E7D32.toInt()
    private val colorFail = 0xFFC62828.toInt()

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.quizhelper.dumptool.START" -> {
                    intent.getIntExtra("groups", -1).takeIf { it > 0 }?.let { targetGroups = it }
                    startAuto()
                }
                "com.quizhelper.dumptool.STOP" -> stopAuto("广播停止")
                "com.quizhelper.dumptool.STEP" -> debugStep()
                "com.quizhelper.dumptool.OCR" -> ocrDump()
                "com.quizhelper.dumptool.NODES" -> nodeDump()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        store = AnswerStore(this)
        OcrFix.load(this)   // 载入等价对照表(内置 assets + 用户 files)
        OcrLearn.init(this) // 载入 OCR 等价对候选(自动学习只攒不生效)
        installCrashCleanup()
        addOverlay()
        val filter = IntentFilter().apply {
            addAction("com.quizhelper.dumptool.START")
            addAction("com.quizhelper.dumptool.STOP")
            addAction("com.quizhelper.dumptool.STEP")
            addAction("com.quizhelper.dumptool.OCR")
            addAction("com.quizhelper.dumptool.NODES")
        }
        ContextCompat.registerReceiver(this, cmdReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        toast("自动答题服务已开启")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        isRunning = false
        auto = false
        clearDumps()   // 服务被解绑/系统关闭也算"结束"，不能依赖手动点清理按钮才删临时文件
        runCatching { unregisterReceiver(cmdReceiver) }
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
        autoButton = null
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
    // 悬浮控制条
    // ---------------------------------------------------------------------

    private fun addOverlay() {
        if (overlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // 悬浮条只留"开始/停止"。OCR/节点 是调试导出，对用户没意义，从界面去掉；
        // 仍可用 adb 广播(OCR/NODES/STEP)触发，方便排查。
        autoButton = makeButton("▶ 开始") { toggleAuto() }
        container.addView(autoButton)
        overlay = container

        // 默认位置：屏幕高度 15% 处偏右下角（百分比，适配不同分辨率）。
        // 若用户拖动过则恢复上次位置。
        val screenH = wm.currentWindowMetrics.bounds.height()
        val savedX = Prefs.overlayX(this)
        val savedY = Prefs.overlayY(this)
        val initX = if (savedX != Int.MIN_VALUE) savedX else 0
        val initY = if (savedY != Int.MIN_VALUE) savedY else (screenH * 0.15f).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = initX; y = initY }
        overlayParams = params

        container.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0; private var iy = 0; private var dx = 0f; private var dy = 0f; private var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; dx = e.rawX; dy = e.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                        if (abs(mx) > 12 || abs(my) > 12) moved = true
                        params.x = ix + mx; params.y = iy + my
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) Prefs.setOverlayPos(this@DumpAccessibilityService, params.x, params.y)
                    }
                }
                return moved
            }
        })
        runCatching { wm.addView(container, params) }
            .onFailure { toast("悬浮条添加失败：${it.message}") }
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(colorIdle)
            alpha = 0.9f
            setOnClickListener { onClick() }
        }

    /**
     * 隐藏时不仅看不见，还要让它【不拦截触摸】(FLAG_NOT_TOUCHABLE)，
     * 否则我们点选项/确定时会点到悬浮条自己。
     */
    private fun setOverlayVisible(visible: Boolean) {
        val p = overlayParams ?: return
        p.flags = if (visible)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        overlay?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        runCatching { windowManager?.updateViewLayout(overlay, p) }
    }

    private fun updateAutoButton() {
        mainHandler.post {
            autoButton?.let {
                it.setBackgroundColor(if (auto) colorRun else colorIdle)
                it.text = if (auto) "■ 停($groupsDone/$targetGroups)" else "▶ 开始"
            }
        }
    }

    // ---------------------------------------------------------------------
    // 自动答题循环
    // ---------------------------------------------------------------------

    private fun toggleAuto() { if (auto) stopAuto("手动停止") else startAuto() }

    private fun startAuto() {
        if (auto) return
        auto = true
        targetGroups = Prefs.targetGroups(this)   // 本次运行的目标组数，取设置页的值
        groupsDone = 0
        answered = 0
        blindRound = 0
        groupInProgress = false
        lastQuestionText = ""
        lastStuckQ = ""
        sameQCount = 0
        failCount.clear()
        lastTapKey = null
        lastTapIntended = emptyList()
        unknownStreak = 0
        updateAutoButton()
        val mode = if (Prefs.bruteMode(this)) "暴力" else "智能"
        toast("开始自动答题（$mode 模式），目标 $targetGroups 组")
        scheduleStep()
    }

    private fun stopAuto(reason: String) {
        if (!auto) return
        auto = false
        setOverlayVisible(true)
        updateAutoButton()
        clearDumps()   // 不管是手动停止还是达到目标组数自动停止，都顺手清掉本次运行的调试临时文件
        OcrLearn.save()   // 落盘本次攒到的 OCR 等价对候选
        Log.i(TAG, "STOP: $reason (groups=$groupsDone answered=$answered)")
        val summary = "$reason（完成 $groupsDone 组，答 $answered 题）"
        toast("已停止：$summary")
        // 非手动停止(达标完成 / 卡住需人工)再发一条通知——无人值守时 toast 一闪而过看不到。
        if (reason != "手动停止") notifyStop(summary)
    }

    /** 发一条"自动答题已停止"的通知。Android13+ 没授通知权限时系统会静默丢弃，不影响其它逻辑。 */
    private fun notifyStop(msg: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "quiz_auto_stop"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "自动答题停止提醒", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val n = Notification.Builder(this, chId)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("自动答题已停止")
                .setContentText(msg)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, n)
        }
    }

    private fun scheduleStep() {
        if (!auto) return
        // 每次都从 Prefs 读，运行期在设置页改了 step 间隔下一拍就生效。
        mainHandler.postDelayed({ loopStep() }, Prefs.stepIntervalMs(this))
    }

    private fun loopStep() {
        if (!auto) return
        captureAndParse { model ->
            if (model == null) { finishStep(); return@captureAndParse }
            act(model)
        }
    }

    private fun act(m: ScreenModel) {
        if (m.kind != ScreenKind.UNKNOWN) unknownStreak = 0
        when (m.kind) {
            ScreenKind.TASK_DETAIL -> {
                if (groupInProgress) {
                    groupsDone++
                    blindRound = 0
                    groupInProgress = false
                    failCount.clear()
                    updateAutoButton()
                    Log.i(TAG, "ACT TASK_DETAIL 一组完成 groupsDone=$groupsDone")
                    if (groupsDone >= targetGroups) { stopAuto("已达目标 $targetGroups 组"); return }
                }
                if (m.title.isNotEmpty()) store.setBank(m.title, m.project)
                Log.i(TAG, "ACT TASK_DETAIL 开始新组 bank='${m.title}'/'${m.project}'")
                groupInProgress = true
                tap(m.startBtn); finishStep()
            }
            ScreenKind.QUESTION -> {
                if (m.options.isEmpty() || m.confirm == null) {
                    Log.w(TAG, "ACT QUESTION 无选项或无确定, 跳过 q='${m.questionText}'")
                    finishStep(); return
                }
                lastQuestionText = m.questionText   // 保存题干，供 FEEDBACK 存答案用
                val brute = Prefs.bruteMode(this)
                val key = store.keyFor(m.questionText)
                // 暴力模式：单选题纯盲选不查表(刷分快)；但**多选题破例查表**——多选答案是多个
                // 字母，单字母盲选永远凑不出来，不查表会一直错、卡死那一组。配合 FEEDBACK 照常
                // 建库：第1轮盲选错→学到完整答案(如ABD)→第2轮查表命中。
                // 智能模式：都查表；若存档答案连续判错够次数(distrusted)则不信任、退回盲选。
                // 兜底：同一题连续出现多次=画面没推进(卡住)。降级这个key+换盲选字母打破死循环。
                if (m.questionText == lastStuckQ) sameQCount++ else { sameQCount = 0; lastStuckQ = m.questionText }
                if (sameQCount >= 6) {
                    if (key != null) failCount[key] = failLimit
                    blindRound++
                    sameQCount = 0
                    Log.w(TAG, "ACT QUESTION 同一题卡了6次 -> 降级+换盲选字母 blindRound=$blindRound")
                }

                val distrusted = key != null && failCount.getOrDefault(key, 0) >= failLimit
                val skipBank = distrusted || (brute && !m.isMulti)
                val known = if (skipBank) null else store.get(m.questionText)
                var idxs = if (known != null) {
                    lettersToIdx(known)
                } else {
                    // 没学到/暴力模式/已被标记不可信：本轮统一用 blindRound 对应的字母(A=0,B=1…)。
                    listOf(blindRound % m.options.size)
                }
                // 关键修复：存档答案的下标若全部超出当前选项数(题库存错/选项数变了，如4选项却存了E)，
                // 空点会卡死——退回盲选并标记不可信，随后反馈页会用正确答案覆盖、自愈。
                if (idxs.none { it < m.options.size }) {
                    if (key != null) failCount[key] = failLimit
                    Log.w(TAG, "ACT QUESTION 存档答案下标超界(idx=$idxs opts=${m.options.size}) -> 退回盲选")
                    idxs = listOf(blindRound % m.options.size)
                }
                val valid = idxs.filter { it < m.options.size }
                lastTapKey = key
                lastTapIntended = valid
                Log.i(TAG, "ACT QUESTION brute=$brute q='${m.questionText}' opts=${m.options.size} known=$known distrusted=$distrusted tapIdx=$valid")
                tapSequence(valid.map { m.options[it] }) {
                    tap(m.confirm)
                    finishStep()
                }
            }
            ScreenKind.FEEDBACK -> {
                val letters = idxToLetters(m.correctIdx)
                // 用 QUESTION 页保存的题干存答案——FEEDBACK 页题干末尾会多出解析说明文字，
                // 长度不同导致模糊匹配失败，下次 get() 找不到答案。
                val storeKey = lastQuestionText.takeIf { it.isNotEmpty() } ?: m.questionText
                lastQuestionText = ""
                val key = lastTapKey
                val intended = lastTapIntended
                lastTapKey = null
                lastTapIntended = emptyList()

                // "实际选中的(你的答案) == 本来打算点的"？yourIdx 读不到或没记录预设时，无从判断，
                // 保守当作"点中了"(tapHit=true)，维持原行为。
                val tapHit = m.yourIdx.isEmpty() || intended.isEmpty() ||
                    m.yourIdx.toSet() == intended.toSet()
                Log.i(TAG, "ACT FEEDBACK q='${m.questionText}' correct=$letters your=${idxToLetters(m.yourIdx)} intended=$intended tapHit=$tapHit")

                if (m.correctIdx.isNotEmpty() && storeKey.isNotEmpty()) {
                    if (tapHit) {
                        // 确认是"我们点的就是选中的"，这一帧可信 → 学/覆盖正确答案。
                        store.put(storeKey, letters)
                    } else {
                        // 点击没点中(点偏/被遮挡)，这一帧可能整体不可信 → 不写题库，免得把好答案改坏。
                        Log.w(TAG, "ACT FEEDBACK 点击没命中(your!=intended)，本次不写题库")
                    }
                }
                // failCount 只统计"确实点中了、但答案还是错"——即答案本身的问题；
                // 点击没点中不算答案错，不计入，免得把一条本来正确的存档误判成不可信。
                if (key != null && tapHit && m.yourIdx.isNotEmpty() && m.correctIdx.isNotEmpty()) {
                    if (m.yourIdx.toSet() != m.correctIdx.toSet()) {
                        val n = failCount.getOrDefault(key, 0) + 1
                        failCount[key] = n
                        Log.w(TAG, "ACT FEEDBACK 答案错 第${n}次 your=${m.yourIdx} correct=${m.correctIdx}")
                    } else {
                        failCount.remove(key)
                    }
                }
                answered++
                // 动作按钮是"提交"=本轮最后一题，点它进入下一轮——盲选字母在这里换(A→B→C…)。
                // 不能等到 SUBMIT_DIALOG 才换：那个弹窗只在整组全对那一轮出现一次，
                // 组内各轮之间根本不触发，会导致字母不换、未知题一直错卡死。
                if (m.isSubmit) {
                    blindRound++
                    Log.i(TAG, "ACT FEEDBACK 提交按钮 -> 进入下一轮, blindRound=${blindRound - 1}->$blindRound")
                }
                tap(m.nextBtn); finishStep()
            }
            ScreenKind.SUBMIT_DIALOG -> {
                // 这个弹窗只在整组全部答对那一轮出现，点确定=整组完成(回任务详情页)。
                // 盲选字母推进已挪到 FEEDBACK 的"提交"按钮分支，这里不再 blindRound++。
                Log.i(TAG, "ACT SUBMIT_DIALOG -> 确定, 整组完成")
                tap(m.dialogConfirm)
                clearDumps()
                finishStep()
            }
            ScreenKind.UNKNOWN -> {
                unknownStreak++
                val limit = Prefs.unknownLimit(this)
                Log.w(TAG, "ACT UNKNOWN 第${unknownStreak}/${limit}次 dismiss=${m.dismissBtn}")
                if (unknownStreak >= limit) {
                    // 自救也没用，连续多帧识别不出——别再死等，提示用户后停下等人工。
                    stopAuto("连续${limit}次画面无法识别，需人工处理后重新开始")
                    return
                }
                // 先试着点掉疑似提示弹窗(确定/关闭/我知道了)，下一帧再看是否恢复。
                m.dismissBtn?.let { tap(it) }
                finishStep()
            }
        }
    }

    /** 依次点击多个坐标（多选题用），点完回调。
     *  点击间隔/选完到点确定的等待都按当前速度(stepInterval)成比例缩放——
     *  这样"速度"一个设置就能整体压到最快，又各留安全下限。 */
    private fun tapSequence(points: List<XY>, done: () -> Unit) {
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

    private fun lettersToIdx(s: String): List<Int> =
        s.filter { it in 'A'..'E' }.map { it - 'A' }

    private fun idxToLetters(idx: List<Int>): String =
        idx.sorted().joinToString("") { ('A' + it).toString() }

    // ---------------------------------------------------------------------
    // 单步调试：截图+解析，把模型写文件，不点击
    // ---------------------------------------------------------------------

    private fun debugStep() {
        setOverlayVisible(false)
        mainHandler.postDelayed({
            captureScreen { bmp ->
                setOverlayVisible(true)
                if (bmp == null) { saveToFile("step", "<no bitmap>"); return@captureScreen }
                OcrEngine.recognize(bmp) { text ->
                    val lines = if (text != null) toLines(text) else emptyList()
                    val model = ScreenParser.parse(lines, bmp.width, bmp.height)
                    val sb = StringBuilder("==== STEP ").append(humanTime()).append(" ====\n")
                    sb.append(model.describe()).append("\n--- RAW OCR (").append(lines.size).append(" lines) ---\n")
                    for (l in lines) sb.append(l.box.toShortString()).append(" \"").append(l.text).append("\"\n")
                    saveToFile("step", sb.toString())
                    copyToClipboard(sb.toString())
                    Log.i(TAG, "STEP ${model.kind} lines=${lines.size}")
                    bmp.recycle()
                }
            }
        }, 150)
    }

    // ---------------------------------------------------------------------
    // 截图 + OCR + 解析
    // ---------------------------------------------------------------------

    // 注意：捕获后【不】恢复悬浮条可见，要等点击全部完成(finishStep)才恢复，
    // 否则点击会落到悬浮条上。
    private fun captureAndParse(onModel: (ScreenModel?) -> Unit) {
        setOverlayVisible(false)
        // 隐藏悬浮条到截图之间的等待，也随速度缩放(留 60ms 下限让它真消失)。
        val preCapture = (Prefs.stepIntervalMs(this) / 8).coerceIn(60L, 150L)
        mainHandler.postDelayed({
            captureScreen { bmp ->
                if (bmp == null) { onModel(null); return@captureScreen }
                OcrEngine.recognize(bmp) { text ->
                    val model = if (text == null) null else ScreenParser.parse(toLines(text), bmp.width, bmp.height)
                    bmp.recycle()   // OCR 完即弃，高频循环下别留给 GC——每张 ~10MB
                    onModel(model)
                }
            }
        }, preCapture)
    }

    /** 一步点击全部完成后调用：恢复悬浮条 + 安排下一步。 */
    private fun finishStep() {
        setOverlayVisible(true)
        scheduleStep()
    }

    private fun toLines(text: Text): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val b = line.boundingBox ?: continue
            out.add(OcrLine(OcrFix.fix(line.text), b))
        }
        return out
    }

    private fun captureScreen(onBitmap: (Bitmap?) -> Unit) {
        val started = runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (t: Throwable) { Log.e(TAG, "convert", t); null } finally { buffer.close() }
                    onBitmap(bmp)
                }
                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "screenshot fail $errorCode"); onBitmap(null)
                }
            })
        }
        if (started.isFailure) { Log.e(TAG, "takeScreenshot threw", started.exceptionOrNull()); onBitmap(null) }
    }

    // ---------------------------------------------------------------------
    // 点击手势
    // ---------------------------------------------------------------------

    private fun tap(p: XY?) {
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
    // 调试导出（OCR / 节点）
    // ---------------------------------------------------------------------

    private fun ocrDump() {
        setOverlayVisible(false)
        mainHandler.postDelayed({
            captureScreen { bmp ->
                setOverlayVisible(true)
                if (bmp == null) { toast("截图失败"); return@captureScreen }
                OcrEngine.recognize(bmp) { text ->
                    if (text == null) { toast("OCR失败"); return@recognize }
                    val sb = StringBuilder("==== OCR ").append(humanTime())
                        .append(" img=").append(bmp.width).append("x").append(bmp.height).append(" ====\n")
                    for (b in text.textBlocks) for (l in b.lines)
                        sb.append("LINE ").append(l.boundingBox?.toShortString() ?: "[?]")
                            .append(" \"").append(l.text).append("\"\n")
                    saveToFile("ocr", sb.toString()); copyToClipboard(sb.toString())
                    toast("OCR 已导出")
                    bmp.recycle()
                }
            }
        }, 200)
    }

    private fun nodeDump() {
        val sb = StringBuilder("==== NODES ").append(humanTime()).append(" ====\n")
        val wins = windows
        if (!wins.isNullOrEmpty()) {
            for ((i, w) in wins.withIndex()) {
                sb.append("WIN#$i ").append(w.root?.packageName).append("\n")
                dumpNode(w.root, 1, sb)
            }
        }
        saveToFile("node", sb.toString()); copyToClipboard(sb.toString())
        toast("节点已导出")
    }

    private fun dumpNode(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        node ?: return
        val r = Rect(); node.getBoundsInScreen(r)
        sb.append("  ".repeat(depth))
        node.text?.takeIf { it.isNotEmpty() }?.let { sb.append(" text=\"$it\"") }
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { sb.append(" id=$it") }
        sb.append(" ").append(r.toShortString()).append("\n")
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), depth + 1, sb)
    }

    // ---------------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------------

    /** 清空临时文件目录(调试导出的截图/OCR/STEP等)。自动答题不写这些，仅调试广播会写。
     *  每组/每轮提交、手动或自动停止、服务被解绑、进程崩溃前都会调用，避免越用越大。 */
    private fun clearDumps() = AnswerStore.clearTemp(this)

    private fun saveToFile(prefix: String, text: String) {
        runCatching {
            val dir = File(getExternalFilesDir(null), "dumps").apply { mkdirs() }
            File(dir, "${prefix}_" + fileTime() + ".txt").writeText(text)
        }.onFailure { Log.e(TAG, "save", it) }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("dump", text))
        }
    }

    private fun humanTime() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun fileTime() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun toast(msg: String) { mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
}
