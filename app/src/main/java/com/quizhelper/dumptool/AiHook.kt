package com.quizhelper.dumptool

/**
 * 题库未命中时的 AI 兜底钩子。
 * 本阶段【空实现】——返回 null，act() 会照常走盲选学习；只把"未命中→AI"这条路预留出来。
 *
 * 将来接入（参考竞品）：把题干+选项发云端文本 LLM（阿里通义千问官方 DashScope 等），
 * prompt 形如「回答以下问题，仅提供答案对应中文即可，不用额外解释。」+题干，取返回的选项文字。
 * 注意：真正接入时是网络调用，必须改成异步（回调/协程），不能在主线程同步阻塞。
 */
object AiHook {

    /** 是否启用 AI 兜底（将来配合设置开关；当前恒 false）。 */
    fun enabled(): Boolean = false

    /**
     * 解析正确答案：返回选项文字（多选用 | 分隔），无法解析返回 null。
     * 当前空实现恒返回 null。
     */
    fun resolve(questionText: String, optionTexts: List<String>): String? = null
}
