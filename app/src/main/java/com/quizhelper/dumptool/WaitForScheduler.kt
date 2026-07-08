package com.quizhelper.dumptool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * waitFor 地基：动作后不再傻等固定间隔，而是轮询等"屏变了且稳住一拍"再继续(带超时兜底)——
 * 屏切得快就走得快、且更稳(根治"读太早"竞态)；屏静止/卡住则到 waitMaxMs 再读一次兜底。
 * 从 [DumpAccessibilityService] 拆出，让服务只管循环分派；本类只管"下一拍什么时候走"。
 *
 * 拟人随机延时是叠在其上的另一层：waitFor 管"对不对(屏就绪)"，随机延时管"像不像人(节奏)"。
 *
 * @param active     循环是否在跑(auto && !paused)——停/暂停后不再排下一拍。
 * @param targetRoot 取目标窗口根，用于读当前屏算签名。
 * @param onStep     该走下一拍时的回调(= 服务的 loopStep)。
 */
class WaitForScheduler(
    private val ctx: Context,
    private val active: () -> Boolean,
    private val targetRoot: () -> AccessibilityNodeInfo?,
    private val onStep: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    private val waitPollMs = 90L
    private val waitMaxMs = 1600L
    // 拟人随机延时区间(叠在 waitFor 之上)：屏就绪后再随机等这么久，模拟人手节奏。竞品每题约 2~2.5s，
    // 我们一题 2 步(答题/反馈各一步)，每步补 0.8~1.5s → 一题约 2.5~3.5s，拟人且防风控。关掉则不补。
    private val humanMinMs = 800L
    private val humanMaxMs = 1500L

    private var waitStart = 0L
    private var lastStepSig = 0       // 上一步实际操作的那张屏的签名
    private var lastPollSig = 0       // 上一拍轮询读到的签名(判"稳定")

    /** 新一轮运行开始时清签名(startAuto 用)。 */
    fun reset() { lastStepSig = 0; lastPollSig = 0 }

    /** 一帧屏的轻量签名：类型+题干+标题。够区分页面切换，又不引入新解析成本。 */
    private fun sigOf(m: ScreenModel): Int = (m.kind.name + "|" + m.questionText + "|" + m.title).hashCode()
    private fun sigNow(): Int = runCatching { sigOf(NodeParser.toModel(targetRoot())) }.getOrNull() ?: 0

    /** 记录"本步操作的屏签名"，waitFor 据此判断屏变没变。 */
    fun markStepSig(model: ScreenModel) { lastStepSig = sigOf(model) }
    /** 同上，但即时重读当前屏算签名(考试模式：先记签名再作答)。 */
    fun markStepSigNow() { lastStepSig = sigNow() }

    /** 初始踢一脚：开始/继续时延一小段再读首屏(仅 startAuto/resume 用)。步与步之间的等待走 [scheduleNextStep]。 */
    fun kick() {
        if (!active()) return
        handler.postDelayed({ onStep() }, Prefs.stepIntervalMs(ctx))
    }

    /** 动作后等下一屏就绪再走下一步。 */
    fun scheduleNextStep() {
        if (!active()) return
        waitStart = System.currentTimeMillis()
        lastPollSig = lastStepSig
        pollForReady()
    }

    private fun pollForReady() {
        if (!active()) return
        handler.postDelayed({
            if (!active()) return@postDelayed
            val sig = sigNow()
            val changed = sig != lastStepSig                 // 和"上一步操作的屏"不同=切走了
            val stable = sig == lastPollSig                  // 和上一拍相同=不在动画中途
            lastPollSig = sig
            val timedOut = System.currentTimeMillis() - waitStart >= waitMaxMs
            when {
                changed && stable -> {
                    // 屏已就绪：拟人开则补一段随机延时(像人手)，关则立刻走(飞速)。
                    val pad = if (Prefs.humanize(ctx)) (humanMinMs..humanMaxMs).random() else 0L
                    handler.postDelayed({ onStep() }, pad)
                }
                timedOut -> onStep()          // 屏没变(静止/卡住)兜底：不补拟人延时，免拖慢卡死检测
                else -> pollForReady()
            }
        }, waitPollMs)
    }
}
