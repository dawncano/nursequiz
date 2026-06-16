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
    val nextBtn: XY? = null,                 // 反馈区"下一题"或"提交"按钮
    val isSubmit: Boolean = false,           // 反馈区动作按钮是"提交"(本轮最后一题)而非"下一题"——盲选字母推进的边界
    val startBtn: XY? = null,                // "开始答题"
    val dialogConfirm: XY? = null,           // 提交弹窗"确定"
    val correctIdx: List<Int> = emptyList(), // 正确答案的选项下标(0=A)
    val yourIdx: List<Int> = emptyList(),    // FEEDBACK页"你的答案"实际选项下标(0=A)，用于核实点击是否真的命中
    val title: String = "",
    val project: String = ""
) {
    fun describe(): String = buildString {
        append("kind=$kind multi=$isMulti\n")
        if (questionText.isNotEmpty()) append("Q=\"$questionText\"\n")
        if (options.isNotEmpty()) append("options(${options.size})=$options\n")
        confirm?.let { append("confirm=$it\n") }
        nextBtn?.let { append("next=$it submit=$isSubmit\n") }
        startBtn?.let { append("start=$it\n") }
        dialogConfirm?.let { append("dialogConfirm=$it\n") }
        if (correctIdx.isNotEmpty()) append("correctIdx=$correctIdx\n")
        if (yourIdx.isNotEmpty()) append("yourIdx=$yourIdx\n")
        if (title.isNotEmpty()) append("title=\"$title\" project=\"$project\"\n")
    }
}

object ScreenParser {

    private fun Rect.cx() = (left + right) / 2
    private fun Rect.cy() = (top + bottom) / 2

    fun parse(raw: List<OcrLine>): ScreenModel {
        // 上限放到 2395：要保留任务详情页底部的"开始答题/错题集"按钮(top~2298)。
        // 底部导航栏(下一题等)由各处的 cy 约束(<2000/<2150)单独排除。
        val lines = raw.filter { it.box.top in 120..2395 && it.text.isNotBlank() }
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

        // 2) 任务详情页（组完成后回到这，用于开始下一组）。
        //    用页面特征词判断，不死磕"开始答题"四个字(OCR 偶尔读不到)。
        val isTaskDetail = lines.any {
            it.text.contains("任务详情") || it.text.contains("完成条件") ||
                it.text.contains("出题规则") || it.text.contains("错题集") ||
                strip(it.text).contains("开始答题")
        }
        if (isTaskDetail) {
            // "开始答题"按钮：优先文字匹配；读不到则按"错题集"(底部左)的右侧推算(底部右那个按钮)。
            val startText = lines.firstOrNull { strip(it.text).contains("开始答题") }
                ?: lines.firstOrNull { it.box.cy() > 2150 && it.box.cx() > 360 && strip(it.text).contains("答题") }
            val startBtn = startText?.let { XY(it.box.cx(), it.box.cy()) }
                ?: lines.firstOrNull { it.text.contains("错题集") }
                    ?.let { XY(it.box.right + it.box.width() * 3, it.box.cy()) }

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
                startBtn = startBtn,
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
        // 动作按钮是"提交"(本轮最后一题)还是"下一题"：含"提/交"=提交，含"题"=下一题。
        // "下一题"不含提/交，"提交"不含题，OCR 噪声下也基本能分。这是盲选字母换轮的边界信号。
        val isSubmit = nextLine?.let { val t = strip(it.text); t.contains("提") || t.contains("交") } ?: false

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

        // 阈值50px时，选项文字换行后多出的单字行(如"血")离主行约51px，会被
        // 误判成独立的"选项行"，把后面选项坐标全部顶歪一位。选项间真实间距≥110px，
        // 提到65px仍有足够安全边际，同时能把换行残字并回原行。
        val rows = clusterRows(regionLines, 65)
        // 选项靠"行首是 A-E 字母(后跟空格/标点/结尾)"识别——选项前都有圈字母。
        // 这样不管题干多长(含大段病史+问句)都不会被误切。字母后紧跟中文的(如"A型血")不算。
        val optLetter = Regex("^[A-E]([ .、，|]|$)")
        var optStart = rows.indexOfFirst { optLetter.containsMatchIn(it.text.trim()) }
        if (optStart < 0) {
            // 回退：选项字母全被 OCR 认错时，用间距法(题干内换行~60 < 选项间距~120)。
            for (i in 1 until rows.size) {
                if (rows[i].cy - rows[i - 1].cy > 90) { optStart = i; break }
            }
        }
        val qRows = if (optStart < 0) rows else rows.subList(0, optStart)
        val optRows = if (optStart < 0) emptyList() else rows.subList(optStart, rows.size)

        val questionText = qRows.joinToString("") { it.text }.trim()
        val options = optRows.map { XY(it.box.cx(), it.cy) }
        val correct = ansLine?.let { lettersFrom(it.text) } ?: emptyList()
        val yours = ansLine?.let { yoursFrom(it.text) } ?: emptyList()

        return ScreenModel(
            kind = kind,
            questionText = questionText,
            isMulti = isMulti,
            options = options,
            confirm = confirmLine?.box?.let { XY(it.cx(), it.cy()) },
            nextBtn = nextLine?.box?.let { XY(it.cx(), it.cy()) },
            isSubmit = isSubmit,
            correctIdx = correct,
            yourIdx = yours
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

    /** 从 "正确答案:C你的答案:A" 提取"你的答案"部分的下标——核实点击是否真的命中了打算选的项。 */
    private fun yoursFrom(text: String): List<Int> {
        if (!text.contains("你的")) return emptyList()
        return text.substringAfter("你的").filter { it in 'A'..'E' }.map { it - 'A' }.distinct()
    }

    private fun strip(s: String): String = s.replace(" ", "").replace("|", "")
}
