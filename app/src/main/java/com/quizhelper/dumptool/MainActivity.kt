package com.quizhelper.dumptool

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        storageInfo = findViewById(R.id.storageInfo)
        banksContainer = findViewById(R.id.banksContainer)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.clearTempButton).setOnClickListener {
            AnswerStore.clearTemp(this)
            refreshBanks()
        }
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
