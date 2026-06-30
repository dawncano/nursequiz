package com.quizhelper.dumptool

import android.util.Log

/**
 * 考试模式（5.16，与竞品一致）：考试无逐题反馈→不建库，靠练习已建的库作答。
 * 每屏：查库(命中)/盲选A/AI → 选选项 → 点确定(若有)或下一题推进 → 末题点交卷+确认 → 停。
 * 复用 waitFor(host.scheduleNextStep)：每屏只作答一次，等切到下一题再继续。
 *
 * 从 [DumpAccessibilityService] 原样抽出，行为不变。结构为"竞品逻辑+练习页"的待校准骨架
 * （详见 REQUIREMENTS 5.16），独立成类后将来校准只动本文件。
 */
class ExamMachine(private val host: AutoHost) {

    fun step() {
        if (!host.active) return
        val em = runCatching { NodeParser.toExamModel(host.targetRoot()) }
            .onFailure { Log.e(TAG, "exam parse fail", it) }.getOrNull()
        if (em == null) { host.scheduleNextStep(); return }
        host.markStepSigNow()   // 复用 waitFor：屏(题)变了才走下一步，避免同题重复作答
        act(em)
    }

    private fun act(em: ExamModel) {
        // 交卷确认弹窗 → 确定 → 交卷完成，停。
        if (em.dialogConfirm != null) {
            host.tap(em.dialogConfirm)
            Log.i(TAG, "EXAM 交卷确认 -> 确定")
            host.stopAuto("已交卷")
            return
        }
        if (em.options.isNotEmpty()) {
            // 查库命中→选库；没命中→AI→盲选 A。考试不建库。
            val stored = host.store.get(em.questionText) ?: AiHook.resolve(em.questionText, em.optionTexts)
            val idx = if (stored != null) AnswerCodec.textsToIdx(stored, em.optionTexts).ifEmpty { listOf(0) } else listOf(0)
            val pts = idx.mapNotNull { em.options.getOrNull(it) }.ifEmpty { listOf(em.options[0]) }
            val advance = em.confirm ?: em.nextBtn ?: em.submitBtn   // 确定优先→下一题→末题交卷
            host.incAnswered()
            Log.i(TAG, "EXAM 作答 q='${em.questionText.take(16)}' idx=$idx known=${stored != null}")
            host.tapSequence(pts) { advance?.let { host.tap(it) }; host.scheduleNextStep() }
            return
        }
        // 没读到题：有交卷就交卷；否则可能已交完/异常，关掉疑似弹窗后继续观察。
        when {
            em.submitBtn != null -> { host.tap(em.submitBtn); Log.i(TAG, "EXAM 无题, 点交卷"); host.scheduleNextStep() }
            em.dismissBtn != null -> { host.tap(em.dismissBtn); host.scheduleNextStep() }
            else -> host.scheduleNextStep()
        }
    }

    companion object { private const val TAG = "DumpTool" }
}
