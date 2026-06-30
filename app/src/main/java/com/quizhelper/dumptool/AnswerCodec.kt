package com.quizhelper.dumptool

/**
 * 答案字母 ↔ 下标 ↔ 选项文字 的纯转换。答题(act)与考试(actExam)共用，从服务类提取以便复用+单测。
 */
object AnswerCodec {

    /** 选项下标列表 → 排序后的字母串（[0,2] → "AC"）。 */
    fun idxToLetters(idx: List<Int>): String =
        idx.sorted().joinToString("") { ('A' + it).toString() }

    /** 存档的“正确答案文字”(多选用 | 分隔)在当前选项文字里找下标：精确优先，再退包含匹配容错。
     *  存文字而非字母 → 选项乱序也能按内容点对；匹配不上(旧字母数据/选项变了)返回空，调用方退回盲选。 */
    fun textsToIdx(stored: String, optionTexts: List<String>): List<Int> =
        stored.split("|").mapNotNull { raw ->
            val a = raw.trim()
            if (a.isEmpty()) return@mapNotNull null
            var idx = optionTexts.indexOfFirst { it == a }
            if (idx < 0) idx = optionTexts.indexOfFirst { it.isNotEmpty() && (it.contains(a) || a.contains(it)) }
            idx.takeIf { it >= 0 }
        }.distinct()
}
