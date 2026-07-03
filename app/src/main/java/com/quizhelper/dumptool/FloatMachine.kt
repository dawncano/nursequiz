package com.quizhelper.dumptool

import android.util.Log

/**
 * 悬浮答案模式：不点任何东西，只把答案显示在悬浮标签上，让用户自己点。
 * 答案链路：查库 → AI 兜底 → 都没有则显示 unknown。反馈页一律读正确答案建库(顺便攒库)。
 * 从 [DumpAccessibilityService] 原样抽出，行为不变；仅把 store/overlay/排步走 [AutoHost]。
 */
class FloatMachine(private val host: AutoHost) {

    private var lastQuestionText = ""   // QUESTION页题干，用于FEEDBACK存答案

    fun reset() { lastQuestionText = "" }

    /** 一拍：读屏→记签名(waitFor)→显示答案。屏读不到则等下一拍。 */
    fun step() {
        val m = runCatching { NodeParser.toModel(host.targetRoot()) }.getOrNull()
        if (m == null) { host.scheduleNextStep(); return }
        host.markStepSig(m)   // 记下本帧屏签名，waitFor 据此判断"屏变没变"
        act(m)
    }

    private fun act(m: ScreenModel) {
        when (m.kind) {
            ScreenKind.TASK_DETAIL -> {
                if (m.title.isNotEmpty()) host.store.setBank(m.title, m.project)  // 载入对应题库供查询
                host.showAnswer("。。。")   // 非答题页保留占位，别让标签消失
            }
            ScreenKind.QUESTION -> {
                lastQuestionText = m.questionText
                var known = host.store.get(m.questionText)
                if (known == null) known = AiHook.resolve(host.appContext, m.questionText, m.optionTexts)
                // 仿竞品 win2：只显示正确答案【原文】(选项文字)，不是字母、不带前缀。多选用空格分隔。
                // 是题但查不到答案(库无+AI关)→ 显示 unknown(区别于非答题页的占位「。。。」)。
                host.showAnswer(AnswerCodec.forDisplay(known))
                Log.i(TAG, "FLOAT QUESTION q='${m.questionText}' show=${known ?: "unknown"}")
            }
            ScreenKind.FEEDBACK -> {
                val storeKey = lastQuestionText.takeIf { it.isNotEmpty() } ?: m.questionText
                lastQuestionText = ""
                val ans = m.correctIdx.mapNotNull { m.optionTexts.getOrNull(it) }.joinToString("|")
                if (ans.isNotEmpty() && storeKey.isNotEmpty()) host.store.put(storeKey, ans)
                // 同样只显示正确答案原文；读不到正确答案显示 unknown。
                host.showAnswer(AnswerCodec.forDisplay(ans))
                Log.i(TAG, "FLOAT FEEDBACK correct='$ans' 已建库")
            }
            else -> host.showAnswer("。。。")   // 非答题页保留占位，标签不消失(用户知道模式还在)
        }
        host.scheduleNextStep()   // 悬浮模式同样 waitFor：用户翻到下一题(屏变了)就尽快更新标签
    }

    companion object { private const val TAG = "DumpTool" }
}
