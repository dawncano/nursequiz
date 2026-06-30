package com.quizhelper.dumptool

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 自动循环宿主：[DumpAccessibilityService] 实现本接口，把各状态机需要的公共能力暴露出去。
 * 把 [FloatMachine]/[VideoMachine]/[ExamMachine] 从服务类里拆出来后，它们只依赖这组能力，
 * 不再直接耦合 AccessibilityService——各模式自包含、可独立校准（考试/视频尤其需要）。
 */
interface AutoHost {
    /** 答案题库（按题库分文件存）。 */
    val store: AnswerStore

    /** 悬浮球/答案标签 UI。 */
    val overlay: FloatingOverlay

    /** 循环是否在跑（auto && !paused）。状态机排下一拍前自检，停/暂停后不再继续。 */
    val active: Boolean

    /** 坐标点击（无障碍手势）。 */
    fun tap(p: XY?)

    /** 依次点击多个坐标（多选题），点完回调。 */
    fun tapSequence(points: List<XY>, done: () -> Unit)

    /** 取目标 App 的窗口根节点（排除自己的悬浮窗）。 */
    fun targetRoot(): AccessibilityNodeInfo?

    /** waitFor：动作后等下一屏就绪再走下一步。 */
    fun scheduleNextStep()

    /** 停止整个自动循环（达标/卡死/手动）。 */
    fun stopAuto(reason: String)

    /** 固定延时后回到主线程执行（视频挂课的慢节奏轮询用）。 */
    fun postDelayed(delayMs: Long, action: () -> Unit)

    /** 记录“本步操作的屏签名”= sigOf(model)，waitFor 据此判断屏变没变。 */
    fun markStepSig(model: ScreenModel)

    /** 同上，但即时重读当前屏算签名（考试模式：先记签名再作答）。 */
    fun markStepSigNow()

    /** 已答题计数 +1（停止时的汇总用）。 */
    fun incAnswered()
}
