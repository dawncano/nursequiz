package com.quizhelper.dumptool

import android.util.Log

/**
 * 自动答题主状态机（普通模式）：控件读题 → 查库/盲选 → 点选项+确定 → 反馈页建库 → 下一题，整组循环。
 * 覆盖 TASK_DETAIL/QUESTION/FEEDBACK/SUBMIT_DIALOG/UNKNOWN 五态。
 *
 * 2026-07-01 从 [DumpAccessibilityService] 抽出，与 Float/Video/Exam Machine 对称，逻辑等价照搬未改。
 * 战术状态（题干/失败计数/过渡去重/提交冷却）由本机私有持有；组进度是跨切面状态，经 [AutoHost] 读写。
 */
class QuizMachine(private val host: AutoHost) {

    private var groupInProgress = false    // 是否正在做某一组(用于判断回到任务页=一组完成)
    private var lastQuestionText = ""      // QUESTION页题干，供 FEEDBACK 存答案(避免 FEEDBACK 多出解析文字)
    private var lastStuckQ = ""            // 连续卡在同一题的检测
    private var sameQCount = 0

    // 兜底：已存答案连续"判错"次数(按题干稳定key)。达上限就不再信任存档答案、退回盲选A，反馈页覆盖自愈。
    private val failCount = HashMap<String, Int>()
    private var lastTapKey: String? = null
    private var lastTapIntended: List<Int> = emptyList()   // QUESTION页本打算点的下标，FEEDBACK拿来核对
    private var unknownStreak = 0
    private val failLimit = 2

    // 过渡屏去重：点完"开始答题/确定"后下一拍常又读到同一张过渡屏，再操作=组数虚假+1/按钮重复点。
    private var lastTransitionKind: ScreenKind? = null
    private var transitionRepeat = 0
    private val transitionRetryAfter = 4   // 同一过渡屏连读这么多拍(约3s)仍没变→允许重试一次

    // 提交后冷却：点完提交确认后反馈页会闪回、弹窗被连读，冷却期内不操作，避免重复提交冲过头。
    private var suppressUntil = 0L

    /** 开始/继续新一轮运行时清空战术状态（组计数由 Service 重置）。 */
    fun reset() {
        groupInProgress = false
        lastQuestionText = ""
        lastStuckQ = ""; sameQCount = 0
        failCount.clear()
        lastTapKey = null; lastTapIntended = emptyList()
        unknownStreak = 0
        lastTransitionKind = null; transitionRepeat = 0
        suppressUntil = 0L
    }

    /** 一拍：读题→记签名(waitFor)→按页面类型动作。读不到则等下一屏。 */
    fun step() {
        val model = runCatching { NodeParser.toModel(host.targetRoot()) }
            .onFailure { Log.e(TAG, "node parse fail", it) }.getOrNull()
        if (model == null) { host.scheduleNextStep(); return }
        host.markStepSig(model)
        act(model)
    }

    private fun act(m: ScreenModel) {
        // 提交后冷却期：一律不操作，让 App 从"提交确认"安心过渡到任务详情页(防反馈页闪回被重复点提交)。
        if (System.currentTimeMillis() < suppressUntil) { host.scheduleNextStep(); return }
        if (m.kind != ScreenKind.UNKNOWN) unknownStreak = 0
        // 非过渡屏(题目/反馈/未知)出现就清掉过渡去重状态，让下一张"真正新的"过渡屏能被正常处理。
        if (m.kind != ScreenKind.TASK_DETAIL && m.kind != ScreenKind.SUBMIT_DIALOG) {
            lastTransitionKind = null; transitionRepeat = 0
        }
        when (m.kind) {
            ScreenKind.TASK_DETAIL -> {
                // 组计数与重试点击解耦：只有"本次进入任务详情页的首帧"才结算上一组+开新组(计数一次)；
                // 点完"开始答题"后题目没立刻加载、又连读到本页的后续帧，只重试点击、绝不重复计数。
                val firstVisit = lastTransitionKind != ScreenKind.TASK_DETAIL
                if (firstVisit) {
                    lastTransitionKind = ScreenKind.TASK_DETAIL
                    transitionRepeat = 0
                    if (groupInProgress) {
                        host.groupsDone++
                        groupInProgress = false
                        failCount.clear()
                        host.overlay.refresh()
                        Log.i(TAG, "ACT TASK_DETAIL 一组完成 groupsDone=${host.groupsDone}")
                        if (host.groupsDone >= host.targetGroups) { host.stopAuto("已达目标 ${host.targetGroups} 组"); return }
                    }
                    if (m.title.isNotEmpty()) host.store.setBank(m.title, m.project)
                    Log.i(TAG, "ACT TASK_DETAIL 开始新组 bank='${m.title}'/'${m.project}'")
                    groupInProgress = true
                    host.tap(m.startBtn); host.scheduleNextStep(); return
                }
                // 非首帧：题目还没加载出来。多等几拍；卡到阈值说明首次"开始答题"没生效→只重试点击。
                transitionRepeat++
                if (transitionRepeat >= transitionRetryAfter) {
                    transitionRepeat = 0
                    Log.w(TAG, "ACT TASK_DETAIL 开始答题疑似未生效, 重试点击(不重复计数)")
                    host.tap(m.startBtn)
                }
                host.scheduleNextStep()
            }
            ScreenKind.QUESTION -> {
                if (m.options.isEmpty() || m.confirm == null) {
                    Log.w(TAG, "ACT QUESTION 无选项或无确定, 跳过 q='${m.questionText}'")
                    host.scheduleNextStep(); return
                }
                lastQuestionText = m.questionText   // 保存题干，供 FEEDBACK 存答案用
                val key = host.store.keyFor(m.questionText)
                // 统一行为(参考竞品，无模式之分)：每题先查库，命中就按存档答案点、没命中就盲选，
                // 反馈页一律读正确答案建库——一边刷题一边攒库。
                // distrusted：存档答案连续判错够次数→不再信任、退回盲选(反馈页会用正确答案覆盖、自愈)。
                // 兜底：同一题连续出现多次=画面没推进(卡住)。降级这个key+换盲选字母打破死循环。
                if (m.questionText == lastStuckQ) sameQCount++ else { sameQCount = 0; lastStuckQ = m.questionText }
                if (sameQCount >= 6) {
                    if (key != null) failCount[key] = failLimit   // 同一题卡住=画面没推进，标记不可信→退回盲选A
                    sameQCount = 0
                    Log.w(TAG, "ACT QUESTION 同一题卡了6次 -> 降级退回盲选")
                }

                val distrusted = key != null && failCount.getOrDefault(key, 0) >= failLimit
                val skipBank = distrusted   // 只有"存档答案不可信"才跳过查库；正常每题都查
                // 题库命中优先；未命中且未被标记不可信，给 AI 兜底一次(当前空实现返回 null → 自然走盲选)。
                var known = if (skipBank) null else host.store.get(m.questionText)
                // AI 兜底(异步)：本拍多半仍返回 null→照常盲选A；反馈页会学到正确答案入库自愈。
                // AI 结果回来后进缓存，同题下轮重现时(若还没入库)即命中。答题模式 AI 属锦上添花。
                if (known == null && !skipBank) known = AiHook.resolve(host.appContext, m.questionText, m.optionTexts)
                var idxs = if (known != null) {
                    AnswerCodec.textsToIdx(known, m.optionTexts)
                } else {
                    // 没学到/已被标记不可信：盲选 A。反馈页会学到精确答案，错题重现时即命中(≤2次必对)；
                    // 控件直读+反馈100%可靠，旧的"按轮换字母"对冲已无意义，故不再轮换。
                    listOf(0)
                }
                // 关键修复：存档答案的下标若全部超出当前选项数(题库存错/选项数变了，如4选项却存了E)，
                // 空点会卡死——退回盲选并标记不可信，随后反馈页会用正确答案覆盖、自愈。
                if (idxs.none { it < m.options.size }) {
                    if (key != null) failCount[key] = failLimit
                    Log.w(TAG, "ACT QUESTION 存档答案下标超界(idx=$idxs opts=${m.options.size}) -> 退回盲选A")
                    idxs = listOf(0)
                }
                val valid = idxs.filter { it < m.options.size }
                lastTapKey = key
                lastTapIntended = valid
                Log.i(TAG, "ACT QUESTION q='${m.questionText}' opts=${m.options.size} known=$known distrusted=$distrusted tapIdx=$valid")
                host.tapSequence(valid.map { m.options[it] }) {
                    host.tap(m.confirm)
                    host.scheduleNextStep()
                }
            }
            ScreenKind.FEEDBACK -> {
                val letters = AnswerCodec.idxToLetters(m.correctIdx)
                // 用 QUESTION 页保存的题干存答案——FEEDBACK 页题干末尾会多出解析说明文字，
                // 长度不同导致模糊匹配失败，下次 get() 找不到答案。
                val storeKey = lastQuestionText.takeIf { it.isNotEmpty() } ?: m.questionText
                lastQuestionText = ""
                val key = lastTapKey
                val intended = lastTapIntended
                lastTapKey = null
                lastTapIntended = emptyList()

                // "实际选中的(你的答案) == 本来打算点的"？yourIdx 读不到或没记录预设时，无从判断，
                // 保守当作"点中了"(tapHit=true)，维持原行为。
                val tapHit = m.yourIdx.isEmpty() || intended.isEmpty() ||
                    m.yourIdx.toSet() == intended.toSet()
                Log.i(TAG, "ACT FEEDBACK q='${m.questionText}' correct=$letters your=${AnswerCodec.idxToLetters(m.yourIdx)} intended=$intended tapHit=$tapHit")

                if (m.correctIdx.isNotEmpty() && storeKey.isNotEmpty()) {
                    if (tapHit) {
                        // 确认是"我们点的就是选中的"，这一帧可信 → 学/覆盖正确答案(存选项文字，抗乱序)。
                        val ans = m.correctIdx.mapNotNull { m.optionTexts.getOrNull(it) }.joinToString("|")
                        if (ans.isNotEmpty()) host.store.put(storeKey, ans)
                    } else {
                        // 点击没点中(点偏/被遮挡)，这一帧可能整体不可信 → 不写题库，免得把好答案改坏。
                        Log.w(TAG, "ACT FEEDBACK 点击没命中(your!=intended)，本次不写题库")
                    }
                }
                // failCount 只统计"确实点中了、但答案还是错"——即答案本身的问题；
                // 点击没点中不算答案错，不计入，免得把一条本来正确的存档误判成不可信。
                if (key != null && tapHit && m.yourIdx.isNotEmpty() && m.correctIdx.isNotEmpty()) {
                    if (m.yourIdx.toSet() != m.correctIdx.toSet()) {
                        val n = failCount.getOrDefault(key, 0) + 1
                        failCount[key] = n
                        Log.w(TAG, "ACT FEEDBACK 答案错 第${n}次 your=${m.yourIdx} correct=${m.correctIdx}")
                    } else {
                        failCount.remove(key)
                    }
                }
                host.incAnswered()
                // 翻页：点动作按钮(下一题/提交均可，m.nextBtn 已是当前那个)。不再轮换盲选字母，
                // 因此无需识别/计数"轮"边界——错题靠重现+查库答对(≤2次)，不靠轮换。
                host.tap(m.nextBtn); host.scheduleNextStep()
            }
            ScreenKind.SUBMIT_DIALOG -> {
                // 这个弹窗只在整组全部答对那一轮出现，点确定=整组完成(回任务详情页)。
                // 点完确定后进入冷却期：反馈页会闪回、弹窗也会被连读，期间一律不操作，避免重复点提交/确定冲过头。
                Log.i(TAG, "ACT SUBMIT_DIALOG -> 确定, 整组完成(进入冷却)")
                host.tap(m.dialogConfirm)
                host.clearDumps()
                suppressUntil = System.currentTimeMillis() + 2500
                host.scheduleNextStep()
            }
            ScreenKind.UNKNOWN -> {
                unknownStreak++
                host.captureUnhandled()   // 疑似"没适配的题型"则自动 dump 节点树，供精确补适配
                val limit = host.unknownLimit()
                Log.w(TAG, "ACT UNKNOWN 第${unknownStreak}/${limit}次 dismiss=${m.dismissBtn}")
                if (unknownStreak >= limit) {
                    // 自救也没用，连续多帧识别不出——别再死等，提示用户后停下等人工。
                    host.stopAuto("连续${limit}次画面无法识别，需人工处理后重新开始")
                    return
                }
                // 先试着点掉疑似提示弹窗(确定/关闭/我知道了)，下一帧再看是否恢复。
                m.dismissBtn?.let { host.tap(it) }
                host.scheduleNextStep()
            }
        }
    }

    companion object { private const val TAG = "DumpTool" }
}
