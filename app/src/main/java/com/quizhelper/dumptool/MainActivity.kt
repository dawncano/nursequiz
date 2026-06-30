package com.quizhelper.dumptool

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 引导页 + 题库管理：
 *  - 引导开启无障碍服务
 *  - 查看已存题库(标题/项目/题数/占用)，可单独删除
 *  - 查看并清理临时文件
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var storageInfo: TextView
    private lateinit var banksContainer: LinearLayout
    private lateinit var settingsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        storageInfo = findViewById(R.id.storageInfo)
        banksContainer = findViewById(R.id.banksContainer)
        settingsContainer = findViewById(R.id.settingsContainer)
        findViewById<TextView>(R.id.versionText).text = "NurseQuiz v${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            when {
                DumpAccessibilityService.isRunning -> toast("无障碍服务已经开启了")
                // 有 WRITE_SECURE_SETTINGS → App 内直接开，免进系统设置
                A11yEnabler.enable(this) -> {
                    toast("已自动开启无障碍服务")
                    statusText.postDelayed({ refreshStatus() }, 600)  // 等服务绑定后刷新状态
                }
                // 没授权 → 退回打开系统设置手动开（保底）
                else -> {
                    toast("未授予自助开启权限，转到系统设置手动开启")
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        findViewById<Button>(R.id.clearTempButton).setOnClickListener {
            AnswerStore.clearTemp(this)
            refreshBanks()
        }
        findViewById<Button>(R.id.wipeAllButton).setOnClickListener { confirmWipeAll() }
        buildSettings()
        requestNotifyPermissionIfNeeded()
        // 自动开启无障碍：每次启动若服务没开、且已 adb 授予 WRITE_SECURE_SETTINGS，就静默开起来。
        // 没授权时 enable() 安全失败、无副作用，用户仍可用上面的按钮（会退回系统设置）。
        if (!DumpAccessibilityService.isRunning) A11yEnabler.enable(this)
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Android13+ 发通知要运行期权限——首次进来弹一次，授不授都不影响主流程(通知只是锦上添花)。 */
    private fun requestNotifyPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** 三个作答模式（悬浮答案/视频挂课/考试）互斥：开一个就关掉另外两个，避免冲突
     *  （如悬浮答案+考试同时开、考试优先级更高会抢去自动点击，导致"不显示答案还乱点"）。 */
    private fun onModeToggle(which: String, checked: Boolean) {
        when (which) {
            "float" -> { Prefs.setFloatMode(this, checked); if (checked) { Prefs.setVideoMode(this, false); Prefs.setExamMode(this, false) } }
            "video" -> { Prefs.setVideoMode(this, checked); if (checked) { Prefs.setFloatMode(this, false); Prefs.setExamMode(this, false) } }
            "exam"  -> { Prefs.setExamMode(this, checked); if (checked) { Prefs.setFloatMode(this, false); Prefs.setVideoMode(this, false) } }
        }
        // 通知服务刷新悬浮形态（悬浮答案=小标签无大球；其余=控制球）。
        sendBroadcast(Intent("com.quizhelper.dumptool.MODE").setPackage(packageName))
        if (which == "float") toast(if (checked) "悬浮答案模式：无大球，按【音量+】键开始/结束" else "已退出悬浮答案模式")
        buildSettings()   // 重建设置区，刷新三个开关的勾选态（互斥结果可见）
    }

    /** 设置区：作答方式 / 视频挂课 / 考试 / 拟人操作 开关 + 目标组数 / UNKNOWN阈值 / 收回延时 数字输入。
     *  全部即时写入 Prefs，运行中的无障碍服务下一拍就会读到。 */
    private fun buildSettings() {
        settingsContainer.removeAllViews()

        val floatSwitch = Switch(this).apply {
            text = "悬浮答案模式（只在悬浮窗提示答案，自己点）"
            textSize = 15f
            isChecked = Prefs.floatMode(this@MainActivity)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, checked -> onModeToggle("float", checked) }
        }
        settingsContainer.addView(floatSwitch)

        settingsContainer.addView(TextView(this).apply {
            text = "关=自动点击：自动答题+建库（用悬浮球▶开始）。开=悬浮答案：进题目时只在小标签显示正确答案、你自己点；" +
                "此模式【没有大悬浮球】，按手机【音量+】键开始/结束。两种方式都顺便建库。"
            textSize = 12f
            setPadding(0, 4, 0, 12)
        })

        val videoSwitch = Switch(this).apply {
            text = "视频自动挂课模式"
            textSize = 15f
            isChecked = Prefs.videoMode(this@MainActivity)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, checked -> onModeToggle("video", checked) }
        }
        settingsContainer.addView(videoSwitch)
        settingsContainer.addView(TextView(this).apply {
            text = "开：点▶后改为自动看视频——进入视频任务详情页即自动播放、保持亮屏、看完一个换下一个，到95%自动停。开此项时不答题。"
            textSize = 12f
            setPadding(0, 4, 0, 12)
        })

        val examSwitch = Switch(this).apply {
            text = "考试模式"
            textSize = 15f
            isChecked = Prefs.examMode(this@MainActivity)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, checked -> onModeToggle("exam", checked) }
        }
        settingsContainer.addView(examSwitch)
        settingsContainer.addView(TextView(this).apply {
            text = "开：进入考试时自动查题库作答、点下一题、最后交卷（考试无对错反馈，全程不建库，靠练习已建好的库；先把练习刷全再考）。"
            textSize = 12f
            setPadding(0, 4, 0, 12)
        })

        val humanSwitch = Switch(this).apply {
            text = "拟人操作（推荐开启）"
            textSize = 15f
            isChecked = Prefs.humanize(this@MainActivity)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, checked -> Prefs.setHumanize(this@MainActivity, checked) }
        }
        settingsContainer.addView(humanSwitch)
        settingsContainer.addView(TextView(this).apply {
            text = "开（默认）：每题之间补一段随机延时，模拟人手节奏、降低被风控识别的风险。\n" +
                "关：飞速刷题——屏幕一就绪立刻答下一题，最快但很机械（赶时间临时用，平时建议开）。"
            textSize = 12f
            setPadding(0, 4, 0, 12)
        })

        settingsContainer.addView(numberRow("目标组数（做满自动停）", Prefs.targetGroups(this).toString()) {
            it.toIntOrNull()?.let { v -> Prefs.setTargetGroups(this, v) }
        })
        settingsContainer.addView(numberRow("连续无法识别多少次就停下", Prefs.unknownLimit(this).toString()) {
            it.toIntOrNull()?.let { v -> Prefs.setUnknownLimit(this, v) }
        })
        settingsContainer.addView(numberRow("悬浮窗无操作多少毫秒收回成球（默认3000）", Prefs.attachDelayMs(this).toString()) {
            it.toLongOrNull()?.let { v -> Prefs.setAttachDelayMs(this, v) }
        })
    }

    /** 一行"标签 + 数字输入框"，失焦时把内容写进 Prefs（save 回调）。 */
    private fun numberRow(label: String, initial: String, save: (String) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = label
            textSize = 14f
        })
        row.addView(EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(220, ViewGroup.LayoutParams.WRAP_CONTENT)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial)
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) save(text.toString()) }
        })
        return row
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshBanks()
        // 悬浮球若被拖到✕关闭过，打开App时让服务重新唤出（服务没开则无副作用）。
        sendBroadcast(Intent("com.quizhelper.dumptool.SHOW").apply { setPackage(packageName) })
    }

    private fun refreshStatus() {
        statusText.text = if (DumpAccessibilityService.isRunning) {
            "状态：✅ 无障碍服务已开启\n点屏幕底部悬浮条的 ▶开始 即可自动答题。"
        } else {
            "状态：⛔ 无障碍服务未开启\n请点下面按钮，在系统设置里打开「" +
                getString(R.string.service_label) + "」。"
        }
    }

    private fun refreshBanks() {
        val banks = AnswerStore.listBanks(this)
        val tempKb = AnswerStore.tempBytes(this) / 1024
        val bankKb = banks.sumOf { it.sizeBytes } / 1024
        storageInfo.text = "题库 ${banks.size} 个，共 ${bankKb}KB；临时文件 ${tempKb}KB"

        banksContainer.removeAllViews()
        if (banks.isEmpty()) {
            banksContainer.addView(TextView(this).apply {
                text = "（还没有题库。开始答题后会自动按「标题+项目」分库记录答案。）"
                textSize = 13f
            })
            return
        }
        for (b in banks) {
            banksContainer.addView(buildBankRow(b))
        }
    }

    private fun buildBankRow(b: BankInfo): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 14f
            val title = b.title.trim().ifEmpty { "(未命名)" }
            val proj = b.project.trim()
            text = "$title\n$proj　·　已记 ${b.count} 题　·　${b.sizeBytes / 1024}KB"
        }
        // 展开/收起：展开后列出该库所有"题干→答案"条目，可改可删。
        val entries = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }
        val expand = Button(this).apply {
            text = "展开"
            setOnClickListener {
                if (entries.visibility == View.GONE) {
                    fillEntries(entries, b)
                    entries.visibility = View.VISIBLE
                    text = "收起"
                } else {
                    entries.visibility = View.GONE
                    text = "展开"
                }
            }
        }
        val del = Button(this).apply {
            text = "删除"
            setOnClickListener { confirmDelete(b) }
        }
        row.addView(info)
        row.addView(expand)
        row.addView(del)
        wrap.addView(row)
        wrap.addView(entries)
        return wrap
    }

    /** 展开题库（仿竞品题库 tab）：搜索框(空=最近10条) + 结果列表；点条目弹"修正题目"框(改题干/答案/删除)。
     *  不一股脑列全部——题库大了几百条没法看；默认只给最近入库的 10 条，要找具体题就搜。 */
    private fun fillEntries(container: LinearLayout, b: BankInfo) {
        container.removeAllViews()
        val all = AnswerStore.loadEntries(b.file).toList()   // LinkedHashMap→插入顺序, 末尾=最近入库
        if (all.isEmpty()) {
            container.addView(TextView(this).apply { text = "（这个题库还没有条目）"; textSize = 13f })
            return
        }
        val hintTv = TextView(this).apply { textSize = 12f; setPadding(0, 4, 0, 4) }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun render(query: String) {
            results.removeAllViews()
            val q = query.trim()
            val matched: List<Pair<String, String>>
            if (q.isEmpty()) {
                matched = all.takeLast(10).reversed()   // 最近10条，最新在最上
                hintTv.text = "共 ${all.size} 题 · 显示最近入库 10 条（输入关键字搜索全部）"
            } else {
                // 模糊搜索：按空格拆词，题干或答案里都包含(忽略大小写)才算命中(竞品的 LIKE 等价)。
                val tokens = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val hits = all.filter { (question, ans) ->
                    val hay = (question + " " + ans).lowercase()
                    tokens.all { hay.contains(it) }
                }
                matched = hits.takeLast(50).reversed()
                hintTv.text = "命中 ${hits.size} 题" + if (hits.size > 50) "（只显示最近 50）" else ""
            }
            if (matched.isEmpty()) {
                results.addView(TextView(this).apply { text = "（没有匹配的题）"; textSize = 12f })
                return
            }
            for ((question, letters) in matched) results.addView(buildEntryRow(container, b, question, letters))
        }

        val search = EditText(this).apply {
            hint = "搜索题目/答案（空=最近10条）"
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { render(s?.toString() ?: "") }
            })
        }
        container.addView(search)
        container.addView(hintTv)
        container.addView(results)
        render("")
    }

    /** 一条题库记录行：题干预览 + 答案，点一下弹修正框。 */
    private fun buildEntryRow(container: LinearLayout, b: BankInfo, question: String, letters: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 12f
                text = if (question.length > 38) question.take(38) + "…" else question
            })
            addView(TextView(this@MainActivity).apply {
                text = letters; textSize = 15f; setPadding(20, 0, 8, 0)
            })
            setOnClickListener { showEditEntryDialog(container, b, question, letters) }
        }
    }

    /** 修正题目：弹框可改题干(多行)+答案，或删除。改完只刷新本题库展开区(保持展开态)。 */
    private fun showEditEntryDialog(container: LinearLayout, b: BankInfo, oldQuestion: String, letters: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val qEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize = 13f
            setText(oldQuestion)
        }
        val aEdit = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "答案 如 C 或 ABD"
            setText(letters)
        }
        box.addView(TextView(this).apply { text = "题目"; textSize = 12f })
        box.addView(qEdit)
        box.addView(TextView(this).apply { text = "答案"; textSize = 12f; setPadding(0, pad / 2, 0, 0) })
        box.addView(aEdit)
        AlertDialog.Builder(this)
            .setTitle("修正题目")
            .setView(box)
            .setNeutralButton("删除") { _, _ ->
                AnswerStore.deleteEntry(b.file, oldQuestion)
                fillEntries(container, b)
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newQ = qEdit.text.toString().trim()
                val ans = aEdit.text.toString().uppercase().filter { it in 'A'..'E' }
                if (ans.isEmpty()) {
                    toast("答案要填 A-E（如 C 或 ABD）")
                } else {
                    AnswerStore.editEntry(b.file, oldQuestion, newQ, ans)
                    fillEntries(container, b)
                }
            }
            .show()
    }

    private fun confirmWipeAll() {
        AlertDialog.Builder(this)
            .setTitle("清空所有数据")
            .setMessage("会删除：全部题库答案、临时文件、所有设置（恢复默认）。不可恢复，确定？")
            .setNegativeButton("取消", null)
            .setPositiveButton("全部清空") { _, _ ->
                AnswerStore.wipeAllData(this)
                refreshStatus(); refreshBanks(); buildSettings()
            }
            .show()
    }

    private fun confirmDelete(b: BankInfo) {
        AlertDialog.Builder(this)
            .setTitle("删除题库")
            .setMessage("确定删除「${b.title}」的已存答案(${b.count}题)？删除后这个题库要重新学习。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                b.file.delete()
                refreshBanks()
            }
            .show()
    }
}
