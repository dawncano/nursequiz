package com.quizhelper.dumptool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
    private var targetGroups = 14          // 默认14组(=210分)
    private var groupsDone = 0
    private var answered = 0
    private var blindRound = 0             // 本组当前轮次(0=A,1=B…)，每次"提交"+1
    private var groupInProgress = false    // 是否正在做某一组(用于判断回到任务页=一组完成)
    private var lastQuestionText = ""      // QUESTION页的题干，用于FEEDBACK存答案(避免FEEDBACK多出解析文字)
    private val stepIntervalMs = 1300L

    // 兜底：已存答案连续"判错"次数(按题干稳定key)。达到上限就不再信任存档答案，
    // 改走 blindRound 轮流盲选——保证哪怕坐标/识别出了我们没预料到的错，也能跳出死循环。
    private val failCount = HashMap<String, Int>()
    private var lastTapKey: String? = null
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

        autoButton = makeButton("▶ 开始") { toggleAuto() }
        val ocrBtn = makeButton("OCR") { ocrDump() }
        val nodeBtn = makeButton("节点") { nodeDump() }
        container.addView(autoButton)
        container.addView(ocrBtn)
        container.addView(nodeBtn)
        overlay = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        // 放到底部偏右的空白区（确定按钮下方、导航栏上方），避免压住题目/选项。
        // 仍可拖动到任意位置。
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 0; y = 160 }
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
        groupsDone = 0
        answered = 0
        blindRound = 0
        groupInProgress = false
        lastQuestionText = ""
        failCount.clear()
        lastTapKey = null
        updateAutoButton()
        toast("开始自动答题，目标 $targetGroups 组")
        scheduleStep()
    }

    private fun stopAuto(reason: String) {
        if (!auto) return
        auto = false
        setOverlayVisible(true)
        updateAutoButton()
        clearDumps()   // 不管是手动停止还是达到目标组数自动停止，都顺手清掉本次运行的调试临时文件
        Log.i(TAG, "STOP: $reason (groups=$groupsDone answered=$answered)")
        toast("已停止：$reason（完成 $groupsDone 组，答 $answered 题）")
    }

    private fun scheduleStep() {
        if (!auto) return
        mainHandler.postDelayed({ loopStep() }, stepIntervalMs)
    }

    private fun loopStep() {
        if (!auto) return
        captureAndParse { model ->
            if (model == null) { finishStep(); return@captureAndParse }
            act(model)
        }
    }

    private fun act(m: ScreenModel) {
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
                val key = store.keyFor(m.questionText)
                // 兜底：这题用存档答案已经连续判错够次数，说明存档答案本身没问题(FEEDBACK每次
                // 都会用当时识别的"正确答案"覆盖存档)，但点击始终没能命中——不再信任存档，
                // 改走 blindRound 轮流盲选(每次提交换一个字母)，保证最终能跳出死循环。
                val distrusted = key != null && failCount.getOrDefault(key, 0) >= failLimit
                val known = if (distrusted) null else store.get(m.questionText)
                val idxs = if (known != null) {
                    lettersToIdx(known)
                } else {
                    // 没学到或已被标记不可信：本轮统一用 blindRound 对应的字母(A=0,B=1…)，每次提交后+1。
                    listOf(blindRound % m.options.size)
                }
                lastTapKey = key
                Log.i(TAG, "ACT QUESTION q='${m.questionText}' opts=${m.options.size} known=$known distrusted=$distrusted tapIdx=$idxs")
                tapSequence(idxs.filter { it < m.options.size }.map { m.options[it] }) {
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
                Log.i(TAG, "ACT FEEDBACK q='${m.questionText}' correct=$letters your=${idxToLetters(m.yourIdx)}")
                if (m.correctIdx.isNotEmpty() && storeKey.isNotEmpty()) {
                    store.put(storeKey, letters)
                }
                // 用页面上"你的答案"核实点击是否真的命中——而不是看我们打算点哪个下标，
                // 这样才能查出"答案查对了但点击没点中"这类问题(如选项坐标行被OCR换行残字顶歪)。
                val key = lastTapKey
                if (key != null && m.yourIdx.isNotEmpty() && m.correctIdx.isNotEmpty()) {
                    if (m.yourIdx.toSet() != m.correctIdx.toSet()) {
                        val n = failCount.getOrDefault(key, 0) + 1
                        failCount[key] = n
                        Log.w(TAG, "ACT FEEDBACK 命中失败 第${n}次 your=${m.yourIdx} correct=${m.correctIdx}")
                    } else {
                        failCount.remove(key)
                    }
                }
                lastTapKey = null
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
            ScreenKind.UNKNOWN -> { Log.w(TAG, "ACT UNKNOWN, 等待"); finishStep() }
        }
    }

    /** 依次点击多个坐标（多选题用），点完回调。 */
    private fun tapSequence(points: List<XY>, done: () -> Unit) {
        if (points.isEmpty()) { mainHandler.postDelayed(done, 300); return }
        var i = 0
        fun next() {
            if (i >= points.size) { mainHandler.postDelayed(done, 400); return }
            tap(points[i]); i++
            mainHandler.postDelayed({ next() }, 350)
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
                    val model = ScreenParser.parse(lines)
                    val sb = StringBuilder("==== STEP ").append(humanTime()).append(" ====\n")
                    sb.append(model.describe()).append("\n--- RAW OCR (").append(lines.size).append(" lines) ---\n")
                    for (l in lines) sb.append(l.box.toShortString()).append(" \"").append(l.text).append("\"\n")
                    saveToFile("step", sb.toString())
                    copyToClipboard(sb.toString())
                    Log.i(TAG, "STEP ${model.kind} lines=${lines.size}")
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
        mainHandler.postDelayed({
            captureScreen { bmp ->
                if (bmp == null) { onModel(null); return@captureScreen }
                OcrEngine.recognize(bmp) { text ->
                    if (text == null) { onModel(null); return@recognize }
                    onModel(ScreenParser.parse(toLines(text)))
                }
            }
        }, 120)
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
