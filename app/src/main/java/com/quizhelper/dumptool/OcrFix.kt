package com.quizhelper.dumptool

/**
 * OCR 容错替换表：把已知的常见误识别字词替换回正确写法。
 * 目的有两个：① 提高界面/答案识别正确率；② 让同一题在不同界面的 OCR 结果更一致，
 *           配合 AnswerStore 的模糊匹配，使"学习"和"盲选轮换"的 key 更稳定。
 *
 * 发现新的稳定误识别(同一处总是错成同样的字)就往 table 里加一行即可。
 * 注意：只放"几乎不会误伤正常文本"的替换；拿不准的交给模糊匹配处理。
 */
object OcrFix {

    private val table: List<Pair<String, String>> = listOf(
        // 同形/异体符号
        "ー" to "一",          // 片假名长音符 → 汉字"一"（"下一题"常被认成"下ー题"）
        "．" to ".",
        // 已观察到的稳定误识别
        "正确苔案" to "正确答案",
        "苔案" to "答案",
        "半选题" to "单选题",
        "里还题" to "多选题",
        "池缓性" to "弛缓性",
        "肾枳水" to "肾积水",
        "骨幸引" to "骨牵引",
        "临庆" to "临床",
        "闭台复位" to "闭合复位",
    )

    fun fix(s: String): String {
        if (s.isEmpty()) return s
        var t = s
        for ((wrong, right) in table) {
            if (t.contains(wrong)) t = t.replace(wrong, right)
        }
        return t
    }
}
