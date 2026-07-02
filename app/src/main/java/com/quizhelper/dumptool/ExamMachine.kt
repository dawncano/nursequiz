package com.quizhelper.dumptool

import android.util.Log

/**
 * 考试模式（错峰 + 只显示答案，见 REQUIREMENTS 5.8 / 反作弊逆向见 2.3）。
 *
 * 【绝不自动点、绝不建库】——护理助手考试页一刀切检测无障碍，自动答题必被拦；且考试只 1 次机会，
 * 不容许乱点。本机只做：读考试页题目 → 跨所有题库搜答案 → AI 兜底 → 都没有则 unknown →
 * 把答案原文【广播】给 [ExamOverlayService] 显示在浮窗上，用户自己点。
 *
 * 与悬浮答案模式(FloatMachine)的区别：① 答案来自 `store.searchAll`（跨所有库，考试题库常没单独刷过）；
 * ② 答案显示在独立前台服务的浮窗(错峰时无障碍会被关、其 overlay 会消失，故不能用无障碍的浮窗)。
 */
class ExamMachine(private val host: AutoHost) {

    private var lastShown = ""   // 去重：同一屏(同题)已显示过就不重复广播

    fun step() {
        val em = runCatching { NodeParser.toExamModel(host.targetRoot()) }
            .onFailure { Log.e(TAG, "exam parse fail", it) }.getOrNull()
        if (em == null) { host.scheduleNextStep(); return }
        host.markStepSigNow()   // 复用 waitFor：屏(题)变了才走下一拍，避免同题反复搜索
        act(em)
    }

    private fun act(em: ExamModel) {
        if (em.options.isNotEmpty() && em.questionText.isNotBlank()) {
            // 跨所有题库搜答案 → AI 兜底 → 都没有则 unknown。全程不点、不建库。
            val known = host.store.searchAll(em.questionText)
                ?: AiHook.resolve(em.questionText, em.optionTexts)
            val show = AnswerCodec.forDisplay(known)   // 多选竖线→空格，空则 unknown
            if (show != lastShown) {
                lastShown = show
                host.showAnswer(show)
                host.incAnswered()
                Log.i(TAG, "EXAM q='${em.questionText.take(16)}' show=$show known=${known != null}")
            }
        }
        host.scheduleNextStep()   // 用户翻到下一题(屏变了)就尽快更新浮窗
    }

    fun reset() { lastShown = "" }

    companion object { private const val TAG = "DumpTool" }
}
