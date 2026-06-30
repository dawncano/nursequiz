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
/** 一个视频条目：名称 + 已学(累计)秒 + 总长秒 + 名称行可点中心(用于切到该视频)。 */
data class VideoRow(val name: String, val watchedSec: Int, val totalSec: Int, val tap: XY)
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

// ---- 考试模式（与竞品一致：考试无逐题反馈，靠练习已建的库作答 + 下一题 + 交卷，全程不建库）----
// 结构为"照竞品逻辑 + 复用练习题目解析"的待校准骨架，按钮/标志需一场真实考试校准。
data class ExamModel(
    val isExam: Boolean,                 // 有"交卷/考生/答题卡/剩余时间"等考试标志
    val questionText: String,
    val isMulti: Boolean,
    val options: List<XY>,               // A→E 可点坐标
    val optionTexts: List<String>,
    val confirm: XY?,                    // "确定"(若考试每题也要确定)
    val nextBtn: XY?,                    // "下一题"
    val submitBtn: XY?,                  // "交卷"
    val dialogConfirm: XY?,              // 交卷确认弹窗"确定"
    val dismissBtn: XY?
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
        val isMulti = q.kind == "多选"
        val hasAns = leaves.any { it.text.contains("正确答案") }
        if (q.options.isNotEmpty() && hasAns) {
            // 本轮最后一题的反馈页：底部"下一题"是失效的，必须点中部"提 交"交卷才能推进。
            // 因此动作按钮(nextBtn)取舍：有"提交"就用"提交"(提交优先)，否则用"下一题"。
            // 经真机验证：最后一题反馈页点"下一题"无反应→死循环；点"提交"才进入下一题。
            val submit = leaves.firstOrNull { it.text.replace(" ", "") == "提交" }?.box
            val next = leaves.firstOrNull { it.text.trim() == "下一题" }?.box
            val action = submit ?: next
            return ScreenModel(
                ScreenKind.FEEDBACK, questionText = q.questionRaw, isMulti = isMulti,
                correctIdx = q.correctIdx, yourIdx = q.yourIdx, nextBtn = action?.let(::center),
                isSubmit = submit != null,
                optionTexts = q.options.map { it.text }
            )
        }
        if (q.options.isNotEmpty() && q.confirm != null) {
            return ScreenModel(
                ScreenKind.QUESTION, questionText = q.questionRaw, isMulti = isMulti,
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

    /** "MM:SS" / "HH:MM:SS" → 秒；不匹配返回 -1。internal：供本 module 单测。 */
    internal fun toSec(s: String): Int {
        val m = SEC_TIME.matchEntire(s.trim()) ?: return -1
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
        val c = m.groupValues[3]
        return if (c.isEmpty()) a * 60 + b else a * 3600 + b * 60 + c.toInt()
    }

    fun toVideoModel(root: AccessibilityNodeInfo?): VideoModel {
        root ?: return VideoModel(VideoKind.OTHER, -1.0, null, -1, -1, null, false, emptyList(), null)
        val leaves = leavesOf(root)
        val rb = Rect(); root.getBoundsInScreen(rb)

        val watchPct = leaves.asSequence()
            .mapNotNull { PCT.find(it.text)?.groupValues?.get(1)?.toDoubleOrNull() }
            .maxOrNull() ?: -1.0
        val startBtn = leaves.firstOrNull { it.text.contains("开始观看") }?.box?.let(::center)
        val isPlayer = leaves.any { SPEED.matches(it.text.trim()) }   // 倍速=播放器独有

        // 视频条目：以 .mp4 名称行为锚，右侧的 MM:SS：上=总长、下=已学(累计)
        val rows = ArrayList<VideoRow>()
        leaves.filter { it.text.contains(".mp4") }.sortedBy { it.box.top }.forEach { name ->
            val times = leaves.filter {
                it !== name && it.box.left > name.box.right &&
                    it.box.top < name.box.bottom + 160 && it.box.bottom > name.box.top - 20 &&
                    toSec(it.text) >= 0
            }.sortedBy { it.box.top }
            if (times.isNotEmpty()) {
                val total = toSec(times.first().text)
                val watched = if (times.size >= 2) toSec(times.last().text) else -1
                rows.add(VideoRow(name.text.trim(), watched, total, center(name.box)))
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

    /** 考试模式解析（待校准骨架）：复用 parseLeaves 读题干+选项，另找 下一题/交卷/确定 与交卷弹窗。 */
    fun toExamModel(root: AccessibilityNodeInfo?): ExamModel {
        root ?: return ExamModel(false, "", false, emptyList(), emptyList(), null, null, null, null, null)
        val leaves = leavesOf(root)
        val isExam = leaves.any {
            it.text.contains("交卷") || it.text.contains("考生") ||
                it.text.contains("答题卡") || it.text.contains("剩余时间")
        }
        val q = parseLeaves(leaves)
        // 交卷确认弹窗：出现"确定交卷/确认交卷/确定要交卷"之类 → 取弹窗里的"确定"
        val submitDialog = leaves.any { it.text.contains("交卷") && (it.text.contains("确定") || it.text.contains("确认")) }
        val dialogConfirm = if (submitDialog) leaves.lastOrNull { it.text.trim() == "确定" }?.box?.let(::center) else null
        val nextBtn = leaves.firstOrNull { it.text.trim() == "下一题" }?.box?.let(::center)
        val submitBtn = leaves.firstOrNull { it.text.trim() == "交卷" }?.box?.let(::center)
        val dismiss = closeBtn(leaves)?.let(::center)
        return ExamModel(
            isExam, q.questionRaw, q.kind == "多选",
            q.options.map { center(it.box) }, q.options.map { it.text },
            q.confirm?.let(::center), nextBtn, submitBtn, dialogConfirm, dismiss
        )
    }
}
