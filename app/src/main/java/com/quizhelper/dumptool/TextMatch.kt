package com.quizhelper.dumptool

/**
 * 纯文本匹配算法：题干/题库名的归一化、Levenshtein 距离、相似度。
 * 从 [AnswerStore] 提取——无 Android 依赖，可直接本机 JVM 单测；阈值策略仍留在 AnswerStore。
 * 归一化正则原本在 normalizeQuestion 和 bankId 各写一遍，这里统一一处。
 */
object TextMatch {

    // 只保留 中文/数字/字母，去掉标点/空白/竖线/括号等噪声（PaO2、ABO血型 等字母数字不丢）。
    private val NON_CJK_ALNUM = Regex("[^a-zA-Z0-9\\u4e00-\\u9fff]")

    /** 归一化：删掉中文/数字/字母以外的一切。题干 key 与题库 id 共用同一条规则。 */
    fun normalize(s: String): String = NON_CJK_ALNUM.replace(s, "")

    /** 两串相似度 0..1。长度差超过 35% 直接判 0（快速剪枝，省掉编辑距离计算）。
     *  长串(>60)额外要求末尾 40 字也相似：案例组合题共享很长病史前缀、只末尾不同，
     *  只看全串相似度会把不同子题误并——两个门槛都过才算同一题。 */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        if (kotlin.math.abs(a.length - b.length) > maxLen * 0.35) return 0.0
        val fullSim = 1.0 - levenshtein(a, b).toDouble() / maxLen
        if (maxLen > 60) {
            val sfxLen = minOf(40, maxLen)
            val sfxSim = 1.0 - levenshtein(a.takeLast(sfxLen), b.takeLast(sfxLen)).toDouble() / sfxLen
            return minOf(fullSim, sfxSim)
        }
        return fullSim
    }

    /** 经典 Levenshtein 编辑距离，滚动数组实现（O(min) 空间）。 */
    fun levenshtein(a: String, b: String): Int {
        val n = b.length
        if (a.isEmpty()) return n
        if (b.isEmpty()) return a.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = curr; curr = t
        }
        return prev[n]
    }
}
