package com.quizhelper.dumptool

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点解析：从护理助手当前页节点树直接读出题目/视频信息（替代早期的截图+OCR）。
 * 产出 [ScreenModel]（答题）/ [VideoModel]（挂课），接入 act()/actVideo() 主循环。
 * 优点：零识别错误、不漏选项、不怕 FLAG_SECURE、不需要替换表/几何外推。
 */

data class NodeOption(val text: String, val box: Rect)

data class NodeQuestion(
    val kind: String,                 // 单选/多选/判断/填空/未知
    val questionRaw: String,
    val questionKey: String,          // 归一化（保留中文+数字+字母，删标点空格）
    val options: List<NodeOption>,    // 按 A→E
    val correctIdx: List<Int>,        // 反馈页"正确答案"字母下标(0=A)
    val correctTexts: List<String>,   // 正确答案映射成的选项文字
    val yourIdx: List<Int>,           // 反馈页"你的答案"字母下标
    val confirm: Rect?,
    val nextBtn: Rect?
)

// ---- 视频自动挂课（5.14）：界面模型 ----
enum class VideoKind { DETAIL, PLAYER, OTHER }
/** 一个视频条目：名称 + 已学(累计)秒 + 总长秒 + 名称行可点中心(用于切到该视频) + 真实覆盖率。
 *  coverage=绿色进度条填充比(0..1，读不到=-1)：这才是"看完没"的真信号——累计会 replay 超总长、不可靠。 */
data class VideoRow(val name: String, val watchedSec: Int, val totalSec: Int, val tap: XY, val coverage: Double)
data class VideoModel(
    val kind: VideoKind,
    val watchPct: Double,      // 观看进度/完成进度 %，读不到 = -1
    val startBtn: XY?,         // 开始观看
    val posSec: Int,           // 播放器当前位置秒，读不到 = -1
    val totalSec: Int,         // 播放器总时长秒，读不到 = -1
    val centerPlay: XY?,       // 暂停时中间▶覆盖层(点它=播放)
    val paused: Boolean,       // 中间▶存在 = 暂停
    val rows: List<VideoRow>,
    val dismissBtn: XY?        // 取消/关闭(下一集弹窗等)
)

// ---- 考试模式（错峰 + 只显示答案，见 REQUIREMENTS 5.8）：绝不点击，故只需题干+选项，无按钮/标志字段 ----
data class ExamModel(
    val questionText: String,
    val options: List<XY>,               // A→E 坐标(仅供展示，考试不点)
    val optionTexts: List<String>
)

object NodeParser {

    private val SINGLE_LETTER = Regex("^[A-Ea-e]$")
    // 归一化：删中文/数字/字母以外的一切，保留字母（PaO2、ABO血型 等不丢）
    private val NORMALIZE = Regex("[^\\u4e00-\\u9fa5\\dA-Za-z]")

    private data class Leaf(val text: String, val box: Rect, val clickable: Boolean)

    private fun walk(node: AccessibilityNodeInfo?, leaves: ArrayList<Leaf>) {
        node ?: return
        val box = Rect(); node.getBoundsInScreen(box)
        val t = node.text?.toString()?.trim() ?: ""
        if (t.isNotBlank()) leaves.add(Leaf(t, box, node.isClickable))
        for (i in 0 until node.childCount) walk(node.getChild(i), leaves)
    }

    /** 收集整棵树的非空文本叶子（题目/视频/考试/调试解析共用入口；root 为 null 返回空）。 */
    private fun leavesOf(root: AccessibilityNodeInfo?): List<Leaf> {
        val leaves = ArrayList<Leaf>(); walk(root, leaves); return leaves
    }

    /** 收集整棵树里**所有**节点的 bounds（含无文字的 View）——视频进度条没文字，leavesOf 收不到，
     *  读绿条填充比要用这个。 */
    private fun allRects(node: AccessibilityNodeInfo?, out: ArrayList<Rect>) {
        node ?: return
        val r = Rect(); node.getBoundsInScreen(r); out.add(r)
        for (i in 0 until node.childCount) allRects(node.getChild(i), out)
    }

    /** 节点 bounds 中心 → 可点坐标。 */
    private fun center(r: Rect): XY = XY(r.centerX(), r.centerY())

    /** 过渡/提示弹窗里"取消/关闭/知道了"类可关闭按钮（视频/考试共用自救）。 */
    private fun closeBtn(leaves: List<Leaf>): Rect? =
        leaves.firstOrNull { val t = it.text.trim(); t == "取消" || t.contains("关闭") || t.contains("知道了") }?.box

    /** 题目解析（基于已收集的 leaves）。 */
    private fun parseLeaves(leaves: List<Leaf>): NodeQuestion {
        val kind = when {
            leaves.any { it.text.contains("多选题") } -> "多选"
            leaves.any { it.text.contains("单选题") || it.text.contains("选择题") } -> "单选"
            leaves.any { it.text.contains("判断题") } -> "判断"
            leaves.any { it.text.contains("填空题") || it.text.contains("简答") || it.text.contains("问答") } -> "填空"
            else -> "未知"
        }

        // 选项：单字母 A-E 标签 + 同一行右侧文字（不依赖 clickable，反馈态也能读）。
        // 字母可能出现两次（圈里+无障碍label），按字母去重取第一行；整行 box 供点击取中心。
        val options = ArrayList<NodeOption>()
        val used = HashSet<Char>()
        leaves.filter { SINGLE_LETTER.matches(it.text) }.sortedBy { it.box.top }.forEach { ll ->
            val letter = ll.text.uppercase()[0]
            if (letter in used) return@forEach
            // 同行 = 与字母节点垂直区间有重叠（长选项跨两行也覆盖），且在字母右侧
            val sameRow = leaves.filter {
                it !== ll && !SINGLE_LETTER.matches(it.text) &&
                    it.box.top < ll.box.bottom && it.box.bottom > ll.box.top && it.box.left >= ll.box.left
            }.sortedBy { it.box.left }
            val txt = sameRow.joinToString("") { it.text }
            if (txt.isNotBlank() && !isButtonWord(txt)) {
                used.add(letter)
                val union = Rect(ll.box); sameRow.forEach { union.union(it.box) }
                options.add(NodeOption(txt, union))
            }
        }

        // 题干：排除 题型/正确答案/纠错/选项文字 后最长的文本
        val optTexts = options.map { it.text }.toSet()
        val questionRaw = leaves.asSequence().map { it.text }
            .filter { it.length >= 6 }
            .filter { t ->
                !t.contains("选题") && !t.contains("正确答案") && !t.contains("纠错") &&
                    optTexts.none { it.isNotBlank() && t.contains(it) }
            }
            .maxByOrNull { it.length } ?: ""
        val questionKey = NORMALIZE.replace(questionRaw, "")

        val ansLine = leaves.firstOrNull { it.text.contains("正确答案") }?.text ?: ""
        val correctIdx = lettersBetween(ansLine, null, "你的")
        val yourIdx = lettersBetween(ansLine, "你的", null)
        val correctTexts = correctIdx.mapNotNull { options.getOrNull(it)?.text }

        val confirm = leaves.firstOrNull { it.text.trim() == "确定" }?.box
        val nextBtn = leaves.firstOrNull { it.text.trim() == "下一题" }?.box

        return NodeQuestion(kind, questionRaw, questionKey, options, correctIdx, correctTexts, yourIdx, confirm, nextBtn)
    }

    fun parse(root: AccessibilityNodeInfo?): NodeQuestion? {
        root ?: return null
        return parseLeaves(leavesOf(root))
    }

    /** 产出 [ScreenModel]，接入 act() 主循环。覆盖 题目/反馈/任务详情/提交弹窗/未知 五态。 */
    fun toModel(root: AccessibilityNodeInfo?): ScreenModel {
        root ?: return ScreenModel(ScreenKind.UNKNOWN)
        val leaves = leavesOf(root)

        // 1) 提交弹窗
        if (leaves.any { it.text.contains("确定要提交") || it.text.contains("所有题目已作答") }) {
            val dc = leaves.lastOrNull { it.text.trim() == "确定" }?.box
            return ScreenModel(ScreenKind.SUBMIT_DIALOG, dialogConfirm = dc?.let(::center))
        }
        // 2) 任务详情（组完成后回到这，用于开始下一组）
        if (leaves.any { it.text.contains("任务详情") || it.text.contains("完成条件") || it.text.contains("开始答题") }) {
            val start = leaves.firstOrNull { it.text.contains("开始答题") }?.box
            val projLeaf = leaves.firstOrNull { it.text.contains("项目") }
            val project = projLeaf?.text?.substringAfter("项目")?.trimStart('：', ':', ' ')?.trim() ?: ""
            val projTop = projLeaf?.box?.top ?: Int.MAX_VALUE
            val title = leaves.filter {
                it.box.top < projTop && it.text.length in 4..40 &&
                    !it.text.contains("任务详情") && !it.text.contains("项目")
            }.minByOrNull { it.box.top }?.text?.replace("进行中", "")?.trim() ?: ""
            return ScreenModel(ScreenKind.TASK_DETAIL, startBtn = start?.let(::center), title = title, project = project)
        }
        // 3) 题目 / 反馈
        val q = parseLeaves(leaves)
        val hasAns = leaves.any { it.text.contains("正确答案") }
        if (q.options.isNotEmpty() && hasAns) {
            // 本轮最后一题的反馈页：底部"下一题"是失效的，必须点中部"提 交"交卷才能推进。
            // 因此动作按钮(nextBtn)取舍：有"提交"就用"提交"(提交优先)，否则用"下一题"。
            // 经真机验证：最后一题反馈页点"下一题"无反应→死循环；点"提交"才进入下一题。
            val submit = leaves.firstOrNull { it.text.replace(" ", "") == "提交" }?.box
            val next = leaves.firstOrNull { it.text.trim() == "下一题" }?.box
            val action = submit ?: next
            return ScreenModel(
                ScreenKind.FEEDBACK, questionText = q.questionRaw,
                correctIdx = q.correctIdx, yourIdx = q.yourIdx, nextBtn = action?.let(::center),
                optionTexts = q.options.map { it.text }
            )
        }
        if (q.options.isNotEmpty() && q.confirm != null) {
            return ScreenModel(
                ScreenKind.QUESTION, questionText = q.questionRaw,
                options = q.options.map { center(it.box) }, confirm = center(q.confirm),
                optionTexts = q.options.map { it.text }
            )
        }
        // 4) 未知：找一个可关闭的按钮自救
        val dismiss = leaves.firstOrNull {
            val t = it.text
            t.contains("我知道") || t.contains("知道了") || t.contains("关闭") ||
                t.trim() == "确定" || t.trim() == "取消" || t.trim() == "好的"
        }
        return ScreenModel(ScreenKind.UNKNOWN, dismissBtn = dismiss?.box?.let(::center))
    }

    private fun isButtonWord(s: String): Boolean =
        s == "确定" || s == "下一题" || s == "上一题" || s.contains("纠错") || s.contains("提交")

    /** 取 [start..end) 区间内的 A-E 下标；start/end 为分隔串，null 表示从头/到尾。
     *  internal：供本 module 单测直接调用（解析正确答案/你的答案的核心逻辑）。 */
    internal fun lettersBetween(text: String, start: String?, end: String?): List<Int> {
        if (text.isBlank()) return emptyList()
        var s = text
        if (start != null) { if (!s.contains(start)) return emptyList(); s = s.substringAfter(start) }
        if (end != null && s.contains(end)) s = s.substringBefore(end)
        return s.filter { it in 'A'..'E' }.map { it - 'A' }.distinct()
    }

    /** 探针：解析结果 + 原始节点清单，调试用。 */
    fun debugDump(root: AccessibilityNodeInfo?): String {
        val leaves = leavesOf(root)
        val q = parseLeaves(leaves)
        val sb = StringBuilder()
        sb.append("kind=").append(q.kind).append("\n")
        sb.append("题干Raw=\"").append(q.questionRaw).append("\"\n")
        sb.append("题干Key=\"").append(q.questionKey).append("\"\n")
        sb.append("选项(").append(q.options.size).append("):\n")
        q.options.forEachIndexed { i, o ->
            sb.append("  ").append('A' + i).append(". \"").append(o.text).append("\"  ")
                .append(o.box.toShortString()).append("\n")
        }
        sb.append("正确答案idx=").append(q.correctIdx).append(" 文字=").append(q.correctTexts)
            .append("  你的答案idx=").append(q.yourIdx).append("\n")
        sb.append("确定=").append(q.confirm?.toShortString())
            .append(" 下一题=").append(q.nextBtn?.toShortString()).append("\n")
        sb.append("\n--- 原始节点(bounds \"text\" [C=可点]) ---\n")
        for (l in leaves) {
            sb.append(l.box.toShortString()).append(" \"").append(l.text).append("\"")
                .append(if (l.clickable) " [C]" else "").append("\n")
        }
        return sb.toString()
    }

    // ---- 视频自动挂课解析（5.14）----
    private val SEC_TIME = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$")
    private val SPEED = Regex("^\\d+(?:\\.\\d+)?x$")        // 1.0x 倍速：播放器独有
    private val PCT = Regex("(\\d+(?:\\.\\d+)?)\\s*%")        // 观看进度/完成进度 X%
    private val ROWNUM = Regex("^\\d+\\.$")                   // 视频列表纯序号叶"1."/"2."(点后无内容)——区别于名称"8.1..."
    private val BARE_PCT = Regex("^\\d+(?:\\.\\d+)?\\s*%$")   // 裸百分比叶(如播放器页 "83.0776 %")——区别于"完成条件…达到95%"文案

    /** "MM:SS" / "HH:MM:SS" → 秒；不匹配返回 -1。internal：供本 module 单测。 */
    internal fun toSec(s: String): Int {
        val m = SEC_TIME.matchEntire(s.trim()) ?: return -1
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
        val c = m.groupValues[3]
        return if (c.isEmpty()) a * 60 + b else a * 3600 + b * 60 + c.toInt()
    }

    /** 读某行绿色进度条的真实覆盖率(0..1)——进度条无文字，从 allRects 按几何找：左≈88 的宽 View 簇，
     *  容器右=最大右、填充右=最小右，覆盖=(填充右−左)/(容器右−左)。找不到=-1。这才是"看完没"的真信号。 */
    private fun barCoverage(rects: List<Rect>, rowTop: Int, bandBottom: Int): Double {
        val cand = rects.filter { it.left in 70..110 && it.top > rowTop + 40 && it.top < bandBottom && (it.right - it.left) > 300 }
        if (cand.isEmpty()) return -1.0
        val left = cand.minOf { it.left }
        val full = cand.maxOf { it.right } - left
        val fill = cand.minOf { it.right } - left
        if (full <= 0) return -1.0
        return (fill.toDouble() / full).coerceIn(0.0, 1.0)
    }

    fun toVideoModel(root: AccessibilityNodeInfo?): VideoModel {
        root ?: return VideoModel(VideoKind.OTHER, -1.0, null, -1, -1, null, false, emptyList(), null)
        val leaves = leavesOf(root)
        val rb = Rect(); root.getBoundsInScreen(rb)
        val rects = ArrayList<Rect>(); allRects(root, rects)

        // 整体完成进度%（完成条件按此判定，≥95=完成）：详情页在"观看进度 X%"这一叶；播放器页是**独立裸叶**
        // "83.0776 %"。都要能读到。**必须**排除静态文案"完成条件：视频进度达到95%"——取全页最大% 会把 95
        // 当成已看进度→一进就误判"已达95%"直接停(真机实测秒停根因)。故：先取"观看进度/完成进度"标签叶的%，
        // 没有再取裸百分比叶(BARE_PCT 不匹配"…达到95%"那种带别的字的文案)。
        val watchPct = leaves.asSequence()
            .filter { it.text.contains("观看进度") || it.text.contains("完成进度") }
            .mapNotNull { PCT.find(it.text)?.groupValues?.get(1)?.toDoubleOrNull() }
            .firstOrNull()
            ?: leaves.asSequence()
                .filter { BARE_PCT.matches(it.text.trim()) }
                .mapNotNull { PCT.find(it.text)?.groupValues?.get(1)?.toDoubleOrNull() }
                .firstOrNull() ?: -1.0
        val startBtn = leaves.firstOrNull { it.text.contains("开始观看") }?.box?.let(::center)
        val isPlayer = leaves.any { SPEED.matches(it.text.trim()) }   // 倍速=播放器独有

        // 视频条目（2026-07-03 真机播放器结构校准）：每行 = 纯序号叶"N. " + 名称叶(不含 .mp4，旧假设已废)
        //  + 右侧两个时刻叶(上=总长、下=累计已学)。以"纯序号叶"(^\d+\.$)为锚——名称"8.1..."也形如数字点，
        // 用"点后无内容"把序号和名称/考试行区分开。右侧时刻按 top 排：first=总长、last=累计。
        val rows = ArrayList<VideoRow>()
        val anchors = leaves.filter { ROWNUM.matches(it.text.trim()) }.sortedBy { it.box.top }
        anchors.forEachIndexed { i, num ->
            val bandBottom = anchors.getOrNull(i + 1)?.box?.top ?: (num.box.top + 220)
            fun inBand(b: Rect) = b.top >= num.box.top - 30 && b.top < bandBottom
            val name = leaves.filter {
                it !== num && it.box.left >= num.box.right - 10 && toSec(it.text) < 0 && it.text.isNotBlank() &&
                    it.box.top >= num.box.top - 30 && it.box.top < num.box.top + 60
            }.minByOrNull { it.box.left }
            val times = leaves.filter { toSec(it.text) >= 0 && it.box.left > num.box.right + 200 && inBand(it.box) }
                .sortedBy { it.box.top }
            if (times.isNotEmpty()) {
                val total = toSec(times.first().text)
                val watched = if (times.size >= 2) toSec(times.last().text) else -1
                val tap = XY(rb.centerX(), (num.box.top + num.box.bottom) / 2)
                rows.add(VideoRow(name?.text?.trim() ?: num.text.trim(), watched, total, tap, barCoverage(rects, num.box.top, bandBottom)))
            }
        }

        // 播放器控件条：最靠上的两个 MM:SS，左=当前位置、右=总时长
        var posSec = -1; var totalSec = -1
        if (isPlayer) {
            val bar = leaves.filter { toSec(it.text) >= 0 && it.box.top < rb.top + (rb.height() * 0.34).toInt() }
                .sortedBy { it.box.left }
            if (bar.size >= 2) { posSec = toSec(bar.first().text); totalSec = toSec(bar.last().text) }
        }

        // 中间播放点：视频区中心(屏幕水平中心、约高度18%处)。暂停时点它=播放；播放时点它只切控件显隐(无害)。
        val centerPlay = if (isPlayer) XY(rb.centerX(), rb.top + (rb.height() * 0.18).toInt()) else null
        val dismiss = closeBtn(leaves)?.let(::center)

        val kind = when {
            isPlayer -> VideoKind.PLAYER
            startBtn != null || leaves.any { it.text.contains("观看进度") } -> VideoKind.DETAIL
            else -> VideoKind.OTHER
        }
        return VideoModel(kind, watchPct, startBtn, posSec, totalSec, centerPlay, false, rows, dismiss)
    }

    /** 考试模式解析：只读题干+选项（错峰模式绝不点击，无需按钮/交卷/标志）。 */
    fun toExamModel(root: AccessibilityNodeInfo?): ExamModel {
        root ?: return ExamModel("", emptyList(), emptyList())
        val q = parseLeaves(leavesOf(root))
        return ExamModel(q.questionRaw, q.options.map { center(it.box) }, q.options.map { it.text })
    }
}
