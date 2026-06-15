package com.quizhelper.dumptool

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/**
 * 引导页：只有一个目标 —— 让用户去系统设置里开启无障碍服务。
 * 开启后，屏幕上会出现"抓屏"悬浮按钮。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            // 跳到系统"无障碍"设置页，用户在这里手动开启本服务。
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = if (DumpAccessibilityService.isRunning) {
            "状态：✅ 无障碍服务已开启\n\n打开护理助手的答题界面，点屏幕上的\"抓屏\"按钮即可。\n抓到的内容会自动复制到剪贴板，并存到 App 的文件目录。"
        } else {
            "状态：⛔ 无障碍服务未开启\n\n请点下面的按钮，在系统设置里找到\"" +
                getString(R.string.service_label) +
                "\"并打开它。"
        }
    }
}
