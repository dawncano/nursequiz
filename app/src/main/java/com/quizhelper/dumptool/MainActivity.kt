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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        storageInfo = findViewById(R.id.storageInfo)
        banksContainer = findViewById(R.id.banksContainer)
        settingsContainer = findViewById(R.id.settingsContainer)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.clearTempButton).setOnClickListener {
            AnswerStore.clearTemp(this)
            refreshBanks()
        }
        buildSettings()
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
        settingsContainer.addView(numberRow("单步间隔毫秒（越小越快，太小会出错）", Prefs.stepIntervalMs(this).toString()) {
            it.toLongOrNull()?.let { v -> Prefs.setStepIntervalMs(this, v) }
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val info = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 14f
            val title = b.title.ifEmpty { "(未命名)" }
            text = "$title\n${b.project}　·　已记 ${b.count} 题　·　${b.sizeBytes / 1024}KB"
        }
        val del = Button(this).apply {
            text = "删除"
            setOnClickListener { confirmDelete(b) }
        }
        row.addView(info)
        row.addView(del)
        return row
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
