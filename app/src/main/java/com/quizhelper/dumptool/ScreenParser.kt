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
    val dismissBtn: XY? = null,              // UNKNOWN 画面里疑似可关闭的按钮(确定/关闭/我知道了)，用于自救
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

    // 全部阈值原先按 1080×2400 物理像素写死(测试机 FHD+ 截图就是这个尺寸)。换分辨率手机
    // (如小米17 截图 1220×2656)绝对值就对不上——元素落在阈值外被误判 UNKNOWN，5选项题尤甚。
    // 现改为【相对定位】：① 屏幕地理类阈值(状态栏/表头带/左右半边)按当前截图宽高取比率；
    // ② 内容驱动类(确定/下一题的位置由上方选项多少决定)锚到真实地标——选项块下方、底部
    // 导航栏(上一题|纠错上报|下一题)上方。地标每帧 OCR 都在、随分辨率一起浮动，彻底脱离写死像素。
    private const val BASE_W = 1080.0
    private const val BASE_H = 2400.0

    private fun Rect.cx() = (left + right) / 2
    private fun Rect.cy() = (top + bottom) / 2

    fun parse(raw: List<OcrLine>, screenW: Int = 1080, screenH: Int = 2400): ScreenModel {
        val W = screenW; val H = screenH
        // 比率换算：rx/ry 把"基准1080×2400下的像素"映射到当前截图尺寸。
        fun rx(r: Double) = (W * r).toInt()
        fun ry(r: Double) = (H * r).toInt()
        // 像素距离类常量(行距/补偿)按高度密度因子缩放。
        val f = H / BASE_H

        // 状态栏(顶)按比率裁掉；底部不再硬裁(原 2395 在高分屏会砍掉导航栏+确定)，保留到近底，
        // 让底部导航栏地标可见。导航栏自身由后续 cy 约束单独排除，不靠这个总过滤。
        val lines = raw.filter { it.box.top in ry(0.05)..ry(0.998) && it.text.isNotBlank() }
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
                ?: lines.firstOrNull { it.box.cy() > ry(0.896) && it.box.cx() > rx(0.333) && strip(it.text).contains("答题") }
            val startBtn = startText?.let { XY(it.box.cx(), it.box.cy()) }
                ?: lines.firstOrNull { it.text.contains("错题集") }
                    ?.let { XY(it.box.right + it.box.width() * 3, it.box.cy()) }

            val projLine = lines.firstOrNull { it.text.contains("项目") }
            val project = projLine?.text
                ?.substringAfter("项目")?.trimStart(':', '：', ' ')?.trim()
                ?: ""
            val projTop = projLine?.box?.top ?: ry(0.25)
            val title = lines.filter {
                it.box.top in ry(0.075) until projTop && it.box.left > rx(0.167) && !it.text.contains("任务详情")
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

        // 【底部导航栏地标】上一题|纠错上报|下一题。"纠错"只在导航栏出现，是最稳的底部锚点。
        // 限定在下半屏(>0.55H)避免误匹配。确定/下一题都夹在"选项块"和这个地标之间。
        val navLine = lines.lastOrNull {
            val t = strip(it.text)
            (t.contains("纠错") || t.contains("上一题")) && it.box.cy() > ry(0.55)
        }
        val navTop = navLine?.box?.top

        // 【确定】=含"确定"、在导航栏地标【上方】(地标缺失则退回比率 0.95H 兜底)、且不在顶部表头区。
        //   原先 cy<2000 是写死上限，5选项题把确定推过 2000 就找不到→UNKNOWN。改成相对导航栏后，
        //   不管几个选项把确定推多低，只要它还在导航栏上面就能稳定命中。
        val confirmUpper = navTop ?: ry(0.95)
        val confirmLine = lines.firstOrNull {
            strip(it.text).contains("确定") && it.box.top < confirmUpper && it.box.cy() > ry(0.30)
        }

        // 【反馈页动作按钮(下一题/提交)】OCR 常把"一"认成"ー"，改用【位置】定位：右半边(cx>0.40W)、
        //   在"正确答案"行/选项块下方、导航栏地标上方、文字含 题/交。导航栏自己的"下一题"因 cy≥navTop
        //   被上界排除，不会误命中。
        val actUpper = navTop ?: ry(0.93)
        val actLower = ansLine?.box?.bottom ?: ry(0.45)
        val nextLine = lines.firstOrNull {
            it.box.cx() > rx(0.40) && it.box.top < actUpper && it.box.cy() > actLower &&
                run { val t = strip(it.text); t.contains("题") || t.contains("交") }
        }
        // 动作按钮是"提交"(本轮最后一题)还是"下一题"：含"提/交"=提交，含"题"=下一题。
        // "下一题"不含提/交，"提交"不含题，OCR 噪声下也基本能分。这是盲选字母换轮的边界信号。
        val isSubmit = nextLine?.let { val t = strip(it.text); t.contains("提") || t.contains("交") } ?: false

        // 选项行存在即可作为 QUESTION 的兜底信号（确定那行偶尔被 OCR 漏读时仍能判定）。
        val optLetter = Regex("^[A-E]([ .、，|]|$)")
        val hasOptions = lines.any { optLetter.containsMatchIn(it.text.trim()) }

        // 有"正确答案" => 反馈页；有"确定"或检测到选项行 => 答题页；都没有 => 未知
        val kind = when {
            ansLine != null -> ScreenKind.FEEDBACK
            confirmLine != null || hasOptions -> ScreenKind.QUESTION
            else -> {
                // 未知画面：尽力找一个可关闭的按钮(确定/关闭/我知道了/取消)供自救点掉。
                val dismiss = lines.firstOrNull {
                    val t = strip(it.text)
                    t.contains("我知道") || t.contains("知道了") || t.contains("关闭") ||
                        t == "确定" || t == "取消" || t == "好的"
                }
                return ScreenModel(
                    ScreenKind.UNKNOWN,
                    dismissBtn = dismiss?.box?.let { XY(it.cx(), it.cy()) }
                )
            }
        }

        // 题干起点：顶部区域里"以题号数字开头 或 含题型字样"的行的底边。
        // 这样即使"单选题"被 OCR 认错(半选题/里还题…)，也能靠题号 N. 把表头排除掉。
        val headerBottom = lines
            .filter { it.box.cy() in ry(0.083)..ry(0.171) }
            .filter { l ->
                val t = l.text.trim()
                t.firstOrNull()?.isDigit() == true ||
                    t.contains("单选") || t.contains("多选") || t.contains("选题") || t.contains("判断")
            }
            .maxOfOrNull { it.box.bottom }
        val regionTop = headerBottom ?: typeLine?.box?.bottom ?: ry(0.117)
        // 选项区下界：取"正确答案/确定/下一题/导航栏"中最靠上的那条——把确定本身挡在选项之外。
        val regionBottom = listOfNotNull(ansLine?.box?.top, confirmLine?.box?.top, nextLine?.box?.top, navTop)
            .minOrNull() ?: ry(0.833)
        val regionLines = lines.filter { it.box.cy() in (regionTop + 1) until regionBottom }

        // 阈值原 65px(基准2400)：选项文字换行后多出的单字残行(~51px)会被误判成独立选项行，把后面
        // 选项坐标全部顶歪一位；选项间真实间距≥110px，65px 仍有安全边际。按密度因子缩放保持比例。
        val rows = clusterRows(regionLines, (65 * f).toInt())
        // 选项靠"行首是 A-E 字母(后跟空格/标点/结尾)"识别——选项前都有圈字母。
        // 这样不管题干多长(含大段病史+问句)都不会被误切。字母后紧跟中文的(如"A型血")不算。
        var optStart = rows.indexOfFirst { optLetter.containsMatchIn(it.text.trim()) }
        if (optStart < 0) {
            // 回退：选项字母全被 OCR 认错时，用间距法(题干内换行~60 < 选项间距~120)。
            val gapThr = (90 * f).toInt()
            for (i in 1 until rows.size) {
                if (rows[i].cy - rows[i - 1].cy > gapThr) { optStart = i; break }
            }
        }
        val optRows = if (optStart < 0) emptyList() else rows.subList(optStart, rows.size)

        // 题干文字【按阅读顺序(先上下后左右)直接从原始行拼】，绕开 clusterRows——
        // 它的合并会把长题干临界行距的换行并进同一簇、又只按左右(x)拼接，把上下两行横着串起来
        // 打乱(同一题每轮文字都不同、污染题库key，真机已复现)。top 量化到桶降低同行抖动。
        val qBottom = optRows.firstOrNull()?.box?.top ?: Int.MAX_VALUE
        val bucket = (30 * f).toInt().coerceAtLeast(1)
        val questionText = regionLines.filter { it.box.cy() < qBottom }
            .sortedWith(compareBy({ it.box.top / bucket }, { it.box.left }))
            .joinToString("") { it.text }.trim()

        // 确定行偶尔被 OCR 漏读两字(按钮在屏上、只是这帧没返回该行)。用地标兜底估算：
        // 夹在最后选项行与底部导航栏正中间，x 取屏幕中央。两地标缺一就放弃(act 会跳过本帧、下帧重试)。
        val lastOptBottom = optRows.lastOrNull()?.box?.bottom
        val fallbackConfirmY = if (lastOptBottom != null && navTop != null) (lastOptBottom + navTop) / 2 else null
        val confirmXY = confirmLine?.box?.let { XY(it.cx(), it.cy()) }
            ?: fallbackConfirmY?.let { XY(W / 2, it) }

        val options = inferOptions(optRows, confirmLine?.box?.top ?: fallbackConfirmY, f)
        val correct = ansLine?.let { lettersFrom(it.text) } ?: emptyList()
        val yours = ansLine?.let { yoursFrom(it.text) } ?: emptyList()

        return ScreenModel(
            kind = kind,
            questionText = questionText,
            isMulti = isMulti,
            options = options,
            confirm = confirmXY,
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

    /** 选项点击坐标。ML Kit 偶尔漏识别某个选项(尤其最后一个 E：圈和文字都没返回)，但选项圈
     *  是等间距排列的——按行间距外推，补齐"确定"按钮之前还放得下的空槽位，用圈所在的 x 坐标兜底
     *  点击(点击靠坐标、不需要文字)。这样 5 选项题不会被当成 4 选项、盲选也能轮到 E。
     *  f=密度因子，把间距过滤/圈补偿按当前分辨率缩放。 */
    private fun inferOptions(optRows: List<Row>, confirmTop: Int?, f: Double): List<XY> {
        val detected = optRows.map { XY(it.box.cx(), it.cy) }
        if (detected.size < 2 || confirmTop == null) return detected
        val ys = optRows.map { it.cy }
        val lo = (60 * f).toInt(); val hi = (220 * f).toInt()
        val gaps = ys.zipWithNext { a, b -> b - a }.filter { it in lo..hi }.sorted()
        if (gaps.isEmpty()) return detected
        val spacing = gaps[gaps.size / 2]
        val circleX = optRows.minOf { it.box.left } + (12 * f).toInt()
        val full = ArrayList<XY>()
        var slot = ys.first()
        var guard = 0
        while (slot < confirmTop - spacing * 0.6 && guard++ < 8) {
            val near = detected.minByOrNull { abs(it.y - slot) }
            if (near != null && abs(near.y - slot) < spacing * 0.4) full.add(near)
            else full.add(XY(circleX, slot))   // 该槽位没检测到选项 = 被漏识别，补齐
            slot += spacing
        }
        return if (full.size > detected.size) full else detected
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
