package com.quizhelper.dumptool

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var learnContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        storageInfo = findViewById(R.id.storageInfo)
        banksContainer = findViewById(R.id.banksContainer)
        settingsContainer = findViewById(R.id.settingsContainer)
        learnContainer = findViewById(R.id.learnContainer)
        OcrLearn.init(this)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.clearTempButton).setOnClickListener {
            AnswerStore.clearTemp(this)
            refreshBanks()
        }
        buildSettings()
        requestNotifyPermissionIfNeeded()
    }

    /** Android13+ 发通知要运行期权限——首次进来弹一次，授不授都不影响主流程(通知只是锦上添花)。 */
    private fun requestNotifyPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** 设置区：暴力/智能模式开关 + 目标组数 / step间隔 / UNKNOWN阈值 三个数字输入。
     *  全部即时写入 Prefs，运行中的无障碍服务下一拍就会读到。 */
    private fun buildSettings() {
        settingsContainer.removeAllViews()

        val modeSwitch = Switch(this).apply {
            text = "暴力模式（关=智能模式）"
            textSize = 15f
            isChecked = Prefs.bruteMode(this@MainActivity)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, checked -> Prefs.setBruteMode(this@MainActivity, checked) }
        }
        settingsContainer.addView(modeSwitch)

        settingsContainer.addView(TextView(this).apply {
            text = "暴力=纯盲选+建库不查表；智能=用题库答案替换盲选。建议先暴力跑几遍攒库，再切智能。"
            textSize = 12f
            setPadding(0, 0, 0, 12)
        })

        settingsContainer.addView(numberRow("目标组数（做满自动停）", Prefs.targetGroups(this).toString()) {
            it.toIntOrNull()?.let { v -> Prefs.setTargetGroups(this, v) }
        })
        settingsContainer.addView(numberRow("暴力速度·单步毫秒（小=快，默认700）", Prefs.stepIntervalBruteMs(this).toString()) {
            it.toLongOrNull()?.let { v -> Prefs.setStepIntervalBruteMs(this, v) }
        })
        settingsContainer.addView(numberRow("智能速度·单步毫秒（要干净OCR，默认1300）", Prefs.stepIntervalSmartMs(this).toString()) {
            it.toLongOrNull()?.let { v -> Prefs.setStepIntervalSmartMs(this, v) }
        })
        settingsContainer.addView(numberRow("连续无法识别多少次就停下", Prefs.unknownLimit(this).toString()) {
            it.toIntOrNull()?.let { v -> Prefs.setUnknownLimit(this, v) }
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
        refreshLearn()
    }

    /** OCR 等价对候选：列出达标的"老被认混的两个字"，确认→入 ocrfix_user.json，忽略→清掉。 */
    private fun refreshLearn() {
        learnContainer.removeAllViews()
        val cands = OcrLearn.qualifying()
        if (cands.isEmpty()) {
            learnContainer.addView(TextView(this).apply {
                text = "（暂无达标候选。攒够 ${OcrLearn.MIN_TOTAL} 次、且足够稳定才会出现在这里。）"
                textSize = 13f
            })
            return
        }
        for ((x, y, n) in cands) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 15f
                text = "「$x」=「$y」　·　$n 次"
            })
            row.addView(Button(this).apply {
                text = "确认等价"
                setOnClickListener { OcrLearn.confirm(x, y); refreshLearn() }
            })
            row.addView(Button(this).apply {
                text = "忽略"
                setOnClickListener { OcrLearn.dismiss(x, y); refreshLearn() }
            })
            learnContainer.addView(row)
        }
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
            val title = b.title.ifEmpty { "(未命名)" }
            text = "$title\n${b.project}　·　已记 ${b.count} 题　·　${b.sizeBytes / 1024}KB"
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

    /** 把题库的每条"题干 → 答案"渲染成可编辑行：答案 EditText 失焦保存，✕ 删除。 */
    private fun fillEntries(container: LinearLayout, b: BankInfo) {
        container.removeAllViews()
        val map = AnswerStore.loadEntries(b.file)
        if (map.isEmpty()) {
            container.addView(TextView(this).apply { text = "（这个题库还没有条目）"; textSize = 13f })
            return
        }
        for ((question, letters) in map) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            item.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 12f
                text = if (question.length > 40) question.take(40) + "…" else question
            })
            item.addView(EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(150, ViewGroup.LayoutParams.WRAP_CONTENT)
                inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                setText(letters)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val v = text.toString().uppercase().filter { it in 'A'..'E' }
                        if (v.isNotEmpty()) AnswerStore.saveEntry(b.file, question, v)
                    }
                }
            })
            item.addView(Button(this).apply {
                text = "✕"
                setOnClickListener {
                    AnswerStore.deleteEntry(b.file, question)
                    fillEntries(container, b)   // 重新渲染
                }
            })
            container.addView(item)
        }
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
