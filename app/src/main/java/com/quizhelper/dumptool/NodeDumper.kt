package com.quizhelper.dumptool

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试导出与未识别画面取证：NODES/NODEQ adb 广播的节点树 dump、剪贴板/文件落盘，以及 UNKNOWN 帧自动 dump。
 * 与答题循环无关——从 [DumpAccessibilityService] 拆出，让服务只管"循环/状态机/广播/通知"。
 *
 * @param svc  取 rootInActiveWindow/windows(AccessibilityService API) 及做 Context(剪贴板/文件/toast)。
 * @param targetRoot 取目标 App 窗口根(排除自身悬浮窗)——逻辑属服务，注入进来复用。
 */
class NodeDumper(
    private val svc: AccessibilityService,
    private val targetRoot: () -> AccessibilityNodeInfo?
) {
    private val ctx: Context get() = svc
    private var lastUnhandledHash = 0   // 自动捕获去重：同一未识别画面只 dump 一次

    /**
     * 自动捕获未识别画面：act() 判成 UNKNOWN 的画面(题型没适配、或组完成边界的过渡屏)dump 到
     * unhandled 目录，供照真实结构精确补适配。先用廉价叶签名去重(不拼整棵树)——卡在同一屏时
     * 每帧都 debugDump 会反复 string-build 整树。写到独立 unhandled 目录(不进 dumps、不被 clearDumps 清)。
     */
    fun captureUnhandled() {
        val root = targetRoot() ?: return
        val sig = NodeParser.leafSignature(root)
        if (sig == lastUnhandledHash) return
        lastUnhandledHash = sig
        val dump = NodeParser.debugDump(root)   // 确认是新屏才拼完整 dump 落盘
        runCatching {
            File(AnswerStore.unhandledDir(ctx), "unhandled_" + fileTime() + ".txt").writeText(
                "==== UNHANDLED " + humanTime() + " pkg=" + root.packageName + " ====\n" + dump)
        }.onFailure { Log.e(TAG, "save unhandled", it) }
        Log.w(TAG, "UNHANDLED 未识别画面 已dump节点树到 files/unhandled/")
    }

    /** 重构探针：解析当前页题目 + 诊断各窗口 root 可读性（排查反无障碍/刷新时机）。 */
    fun nodeQuestionDump() {
        val sb = StringBuilder("==== NODEQ " + humanTime() + " ====\n")
        val ria = svc.rootInActiveWindow
        sb.append("[diag] rootInActiveWindow pkg=").append(ria?.packageName)
            .append(" childCount=").append(ria?.childCount).append("\n")
        for (w in svc.windows ?: emptyList()) {
            val rt = w.root ?: continue
            val before = rt.childCount
            val refreshed = runCatching { rt.refresh() }.getOrDefault(false)
            sb.append("[diag] win type=").append(w.type).append(" pkg=").append(rt.packageName)
                .append(" childCount=").append(before).append("->").append(rt.childCount)
                .append(" refresh=").append(refreshed).append("\n")
        }
        val root = targetRoot()
        sb.append(NodeParser.debugDump(root))
        saveToFile("nodeq", sb.toString()); copyToClipboard(sb.toString())
        ctx.toast("NODEQ 已导出")
        Log.i(TAG, "NODEQ done root=" + (root?.packageName ?: "null"))
    }

    fun nodeDump() {
        val sb = StringBuilder("==== NODES ").append(humanTime()).append(" ====\n")
        val wins = svc.windows
        if (!wins.isNullOrEmpty()) {
            for ((i, w) in wins.withIndex()) {
                sb.append("WIN#$i ").append(w.root?.packageName).append("\n")
                dumpNode(w.root, 1, sb)
            }
        }
        saveToFile("node", sb.toString()); copyToClipboard(sb.toString())
        ctx.toast("节点已导出")
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        node ?: return
        val r = Rect(); node.getBoundsInScreen(r)
        sb.append("  ".repeat(depth))
        node.text?.takeIf { it.isNotEmpty() }?.let { sb.append(" text=\"$it\"") }
        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { sb.append(" id=$it") }
        sb.append(" ").append(r.toShortString()).append("\n")
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), depth + 1, sb)
    }

    private fun saveToFile(prefix: String, text: String) {
        runCatching {
            File(AnswerStore.dumpsDir(ctx), "${prefix}_" + fileTime() + ".txt").writeText(text)
        }.onFailure { Log.e(TAG, "save", it) }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("dump", text))
        }
    }

    private fun humanTime() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun fileTime() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object { private const val TAG = "DumpTool" }
}
