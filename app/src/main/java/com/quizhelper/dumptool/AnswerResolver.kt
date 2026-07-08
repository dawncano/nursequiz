package com.quizhelper.dumptool

/**
 * 取答收口：把"先查题库、没有再问 AI 兜底"这条策略集中一处，供 Quiz/Float/Exam 三机复用。
 * 之前三个机器各自重写一遍 bank→AI 的顺序与转换，加来源或调顺序要三处同步改。
 * 返回【存储形态】的答案串(正确选项原文，多选用 '|' 分隔)或 null；调用方再自行转下标/显示串。
 */
object AnswerResolver {

    /** 查库范围：普通/悬浮只查当前库；考试跨所有库(考试题库常没单独刷过)。 */
    enum class Scope { CURRENT_BANK, ALL_BANKS }

    /**
     * @param onLate 传给 [AiHook.resolve] 的迟到回调(仅考试浮窗用：AI 异步答案回来时主动刷新)。
     * @return 题库命中或 AI 兜底的答案串；都没有返回 null。
     */
    fun resolve(
        host: AutoHost,
        question: String,
        options: List<String>,
        scope: Scope,
        onLate: ((String?) -> Unit)? = null
    ): String? {
        val fromBank = when (scope) {
            Scope.CURRENT_BANK -> host.store.get(question)
            Scope.ALL_BANKS -> host.store.searchAll(question)
        }
        return fromBank ?: AiHook.resolve(host.appContext, question, options, onLate)
    }
}
