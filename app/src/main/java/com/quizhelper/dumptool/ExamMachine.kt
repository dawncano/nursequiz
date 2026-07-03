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
    private var awaitingQ = ""   // 正在等 AI 异步回答的题干：结果回来时核对，防止显示到已翻过去的旧题上

    fun step() {
        val em = runCatching { NodeParser.toExamModel(host.targetRoot()) }
            .onFailure { Log.e(TAG, "exam parse fail", it) }.getOrNull()
        if (em == null) { host.scheduleNextStep(); return }
        host.markStepSigNow()   // 复用 waitFor：屏(题)变了才走下一拍，避免同题反复搜索
        act(em)
    }

    private fun act(em: ExamModel) {
        if (em.options.isNotEmpty() && em.questionText.isNotBlank()) {
            val q = em.questionText
            // 跨所有题库搜答案 → AI 兜底 → 都没有则 unknown。全程不点、不建库。
            // 考试无对错反馈、新题只能靠 AI；且停在同一题时屏不变不会重读，故传 onLate：
            // AI 异步答案回来后主动刷新浮窗(仅当用户仍停在这道题上)。
            val known = host.store.searchAll(q)
                ?: AiHook.resolve(host.appContext, q, em.optionTexts) { late ->
                    if (late != null && q == awaitingQ) {
                        val t = AnswerCodec.forDisplay(late)
                        lastShown = t
                        host.showAnswer(t)
                        Log.i(TAG, "EXAM late-AI q='${q.take(16)}' show=$t")
                    }
                }
            awaitingQ = q
            val show = AnswerCodec.forDisplay(known)   // 多选竖线→空格，空则 unknown
            if (show != lastShown) {
                lastShown = show
                host.showAnswer(show)
                host.incAnswered()
                Log.i(TAG, "EXAM q='${q.take(16)}' show=$show known=${known != null}")
            }
        }
        host.scheduleNextStep()   // 用户翻到下一题(屏变了)就尽快更新浮窗
    }

    fun reset() { lastShown = ""; awaitingQ = "" }

    companion object { private const val TAG = "DumpTool" }
}
