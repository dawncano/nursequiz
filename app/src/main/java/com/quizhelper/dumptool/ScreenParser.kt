package com.quizhelper.dumptool

import android.graphics.Rect
import kotlin.math.abs

data class XY(val x: Int, val y: Int)

data class OcrLine(val text: String, val box: Rect)

enum class ScreenKind { QUESTION, FEEDBACK, TASK_DETAIL, SUBMIT_DIALOG, UNKNOWN }

data class ScreenModel(
    val kind: ScreenKind,
    val questionText: String = "",
    val isMulti: Boolean = false,
    val options: List<XY> = emptyList(),     // 按 A,B,C,D,E 顺序的可点坐标
    val confirm: XY? = null,                 // "确定"
    val nextBtn: XY? = null,                 // 反馈区"下一题"
    val startBtn: XY? = null,                // "开始答题"
    val dialogConfirm: XY? = null,           // 提交弹窗"确定"
    val correctIdx: List<Int> = emptyList(), // 正确答案的选项下标(0=A)
    val title: String = "",
    val project: String = ""
) {
    fun describe(): String = buildString {
        append("kind=$kind multi=$isMulti\n")
        if (questionText.isNotEmpty()) append("Q=\"$questionText\"\n")
        if (options.isNotEmpty()) append("options(${options.size})=$options\n")
        confirm?.let { append("confirm=$it\n") }
        nextBtn?.let { append("next=$it\n") }
        startBtn?.let { append("start=$it\n") }
        dialogConfirm?.let { append("dialogConfirm=$it\n") }
        if (correctIdx.isNotEmpty()) append("correctIdx=$correctIdx\n")
        if (title.isNotEmpty()) append("title=\"$title\" project=\"$project\"\n")
    }
}

object ScreenParser {

    private fun Rect.cx() = (left + right) / 2
    private fun Rect.cy() = (top + bottom) / 2

    fun parse(raw: List<OcrLine>): ScreenModel {
        val lines = raw.filter { it.box.top in 120..2290 && it.text.isNotBlank() }
            .sortedBy { it.box.cy() }

        // 1) 提交弹窗
        val submit = lines.firstOrNull {
            it.text.contains("确定要提交") || it.text.contains("所有题目已作答")
        }
        if (submit != null) {
            val confirmBtn = lines
                .filter { val t = strip(it.text); t.contains("确定") && t.length <= 4 }
                .maxByOrNull { it.box.cy() }
            return ScreenModel(ScreenKind.SUBMIT_DIALOG, dialogConfirm = confirmBtn?.box?.let { XY(it.cx(), it.cy()) })
        }

        // 2) 任务详情页（有"开始答题"）
        val startLine = lines.firstOrNull { strip(it.text).contains("开始答题") }
        if (startLine != null) {
            val projLine = lines.firstOrNull { it.text.contains("项目") }
            val project = projLine?.text
                ?.substringAfter("项目")?.trimStart(':', '：', ' ')?.trim()
                ?: ""
            val projTop = projLine?.box?.top ?: 600
            val title = lines.filter {
                it.box.top in 181 until projTop && it.box.left > 180 && !it.text.contains("任务详情")
            }.joinToString("") { it.text }.replace("进行中", "").trim()
            return ScreenModel(
                ScreenKind.TASK_DETAIL,
                startBtn = XY(startLine.box.cx(), startLine.box.cy()),
                title = title, project = project
            )
        }

        // 3) 用强信号判断界面类型，不依赖"单选题"那行（它常被通知横幅/噪声盖住）。
        val typeLine = lines.firstOrNull { it.text.contains("单选题") || it.text.contains("多选题") }
        val isMulti = typeLine?.text?.contains("多选") == true

        // "正确答案"行：容忍 OCR 把"答"认错(苔案等)，用 含"正确"且含"案"。
        val ansLine = lines.firstOrNull { it.text.contains("正确") && it.text.contains("案") }
        val confirmLine = lines.firstOrNull { strip(it.text).contains("确定") && it.box.cy() < 2000 }
        // 反馈页动作按钮(下一题/提交)：OCR 常把"一"认成"ー"导致"下ー题"匹配失败，
        // 改用【位置】定位——反馈区右侧(cx>430)、选项下方导航栏上方(cy 1300~2150)、文字含 题/交。
        val nextLine = lines.firstOrNull {
            it.box.cx() > 430 && it.box.cy() in 1300..2150 &&
                run { val t = strip(it.text); t.contains("题") || t.contains("交") }
        }

        // 有"正确答案" => 反馈页；有"确定" => 答题页；都没有 => 未知
        val kind = when {
            ansLine != null -> ScreenKind.FEEDBACK
            confirmLine != null -> ScreenKind.QUESTION
            else -> return ScreenModel(ScreenKind.UNKNOWN)
        }

        // 题干起点：顶部区域里"以题号数字开头 或 含题型字样"的行的底边。
        // 这样即使"单选题"被 OCR 认错(半选题/里还题…)，也能靠题号 N. 把表头排除掉。
        val headerBottom = lines
            .filter { it.box.cy() in 200..410 }
            .filter { l ->
                val t = l.text.trim()
                t.firstOrNull()?.isDigit() == true ||
                    t.contains("单选") || t.contains("多选") || t.contains("选题") || t.contains("判断")
            }
            .maxOfOrNull { it.box.bottom }
        val regionTop = headerBottom ?: typeLine?.box?.bottom ?: 280
        val regionBottom = listOfNotNull(ansLine?.box?.top, confirmLine?.box?.top, nextLine?.box?.top)
            .minOrNull() ?: 2000
        val regionLines = lines.filter { it.box.cy() in (regionTop + 1) until regionBottom }

        val rows = clusterRows(regionLines, 50)
        // 题干内部换行间距(~63) < 题干到选项/选项之间间距(~120)，用 90 作阈值切分
        var optStart = -1
        for (i in 1 until rows.size) {
            if (rows[i].cy - rows[i - 1].cy > 90) { optStart = i; break }
        }
        val qRows = if (optStart < 0) rows else rows.subList(0, optStart)
        val optRows = if (optStart < 0) emptyList() else rows.subList(optStart, rows.size)

        val questionText = qRows.joinToString("") { it.text }.trim()
        val options = optRows.map { XY(it.box.cx(), it.cy) }
        val correct = ansLine?.let { lettersFrom(it.text) } ?: emptyList()

        return ScreenModel(
            kind = kind,
            questionText = questionText,
            isMulti = isMulti,
            options = options,
            confirm = confirmLine?.box?.let { XY(it.cx(), it.cy()) },
            nextBtn = nextLine?.box?.let { XY(it.cx(), it.cy()) },
            correctIdx = correct
        )
    }

    private data class Row(val box: Rect, val text: String, val cy: Int)

    /** 把 Y 坐标相近的行合并成"一行"（圈里的字母 + 右边文字会被并到一起）。 */
    private fun clusterRows(lines: List<OcrLine>, thr: Int): List<Row> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.box.cy() }
        val groups = ArrayList<MutableList<OcrLine>>()
        for (l in sorted) {
            val g = groups.lastOrNull()
            val gcy = g?.map { it.box.cy() }?.average()?.toInt()
            if (g != null && gcy != null && abs(l.box.cy() - gcy) <= thr) g.add(l)
            else groups.add(mutableListOf(l))
        }
        return groups.map { grp ->
            val box = Rect(
                grp.minOf { it.box.left }, grp.minOf { it.box.top },
                grp.maxOf { it.box.right }, grp.maxOf { it.box.bottom }
            )
            val txt = grp.sortedBy { it.box.left }.joinToString(" ") { it.text }
            Row(box, txt, (box.top + box.bottom) / 2)
        }
    }

    /** 从 "正确答案:C你的答案:A" 提取正确字母下标。用"你的"切分(在它之前=正确答案部分)，
     *  避免把"你的答案"里的字母也算进来；兼容"答"被OCR认错。 */
    private fun lettersFrom(text: String): List<Int> {
        val seg = if (text.contains("你的")) text.substringBefore("你的") else text
        return seg.filter { it in 'A'..'E' }.map { it - 'A' }.distinct()
    }

    private fun strip(s: String): String = s.replace(" ", "").replace("|", "")
}
