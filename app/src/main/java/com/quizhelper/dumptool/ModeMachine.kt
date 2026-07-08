package com.quizhelper.dumptool

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 模式状态机基类：把四个机器共有的一拍骨架收口——
 *   读屏 → 读不到就等下一屏(waitFor) → 记签名 → 按屏动作。
 * 各模式只提供 parse(读哪种屏模型)/markSig(签名策略)/act(动作)；解析出的模型类型 M 各异故用泛型。
 * 视频挂课节奏不同(慢轮询、不记签名)，重写 [step]。
 */
abstract class ModeMachine<M : Any>(protected val host: AutoHost) {

    /** 从目标窗口根解析出本模式的屏模型；读不到(null)则本拍等下一屏。 */
    protected abstract fun parse(root: AccessibilityNodeInfo?): M?

    /** 记录本帧屏签名供 waitFor 判断"屏变没变"。普通/悬浮用解析出的 model；考试即时重读；视频不记。 */
    protected abstract fun markSig(model: M)

    /** 按屏模型执行本模式动作。 */
    protected abstract fun act(model: M)

    /** 开始/继续新一轮时清空战术状态。 */
    open fun reset() {}

    /** 一拍：读屏→记签名→动作；读不到屏则等下一屏。 */
    open fun step() {
        val m = runCatching { parse(host.targetRoot()) }
            .onFailure { Log.e(TAG, "node parse fail", it) }.getOrNull()
        if (m == null) { host.scheduleNextStep(); return }
        markSig(m)
        act(m)
    }

    companion object { private const val TAG = "DumpTool" }
}

/**
 * 答题类屏机(普通 [QuizMachine] / 悬浮 [FloatMachine])公共基：都读 [NodeParser.toModel]、
 * 都用 model 记签名，都要把 QUESTION 帧题干暂存给 FEEDBACK 存库——反馈页题干末尾带解析说明，
 * 长度不同会让模糊匹配失败，故存库 key 必须取 QUESTION 帧的题干。
 */
abstract class ScreenModeMachine(host: AutoHost) : ModeMachine<ScreenModel>(host) {

    /** QUESTION 帧题干，供 FEEDBACK 存答案用(见 [feedbackStoreKey])。 */
    protected var lastQuestionText = ""

    override fun parse(root: AccessibilityNodeInfo?): ScreenModel? = NodeParser.toModel(root)
    override fun markSig(model: ScreenModel) = host.markStepSig(model)
    override fun reset() { lastQuestionText = "" }

    /** FEEDBACK 存库用的稳定 key：优先用 QUESTION 帧记下的题干，取用后清空。 */
    protected fun feedbackStoreKey(m: ScreenModel): String {
        val k = lastQuestionText.takeIf { it.isNotEmpty() } ?: m.questionText
        lastQuestionText = ""
        return k
    }
}
