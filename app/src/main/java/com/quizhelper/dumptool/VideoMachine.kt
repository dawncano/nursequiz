package com.quizhelper.dumptool

import android.util.Log

/**
 * 视频自动挂课（5.14）：就在"视频任务详情页"里循环它的视频列表——看完一个点下一个。
 * 模型(用户确认)：进播放器 → 跟踪"当前在看第几个" → 该集累计≥总长就点列表下一个没看完的 →
 * 在看的这集若暂停/没进度就点中间恢复 → 列表全看完 或 观看进度≥95% 则停。靠时间/进度推进，不靠"播完检测"。
 *
 * 从 [DumpAccessibilityService] 原样抽出，行为不变。这部分尚未真机端到端校准（详见 REQUIREMENTS 5.14.F），
 * 独立成类后将来校准只动本文件，不碰已验证的答题路径。
 */
class VideoMachine(private val host: AutoHost) {

    private var lastVideoPos = -1
    private var currentVideoIdx = -1     // 当前正在看列表里第几个(行下标)
    private val doneCoverage = 0.98       // 单条覆盖率≥98%视为该集看完(绿条填满)
    private val donePct = 95.0            // 整体完成进度≥95%=任务完成(页面明写的完成条件)
    private val videoStepMs = 3000L      // 挂课慢节奏：每 3s 查一次(防暂停/进度推进)

    fun reset() { lastVideoPos = -1; currentVideoIdx = -1 }

    fun step() {
        if (!host.active) return
        val vm = runCatching { NodeParser.toVideoModel(host.targetRoot()) }
            .onFailure { Log.e(TAG, "video parse fail", it) }.getOrNull()
        if (vm == null) { scheduleNext(); return }
        act(vm)
    }

    /** 单条"看完没"以**真实覆盖率**(绿条填充比)为准——累计时长会 replay 超总长、完全不可靠(真机实测
     *  三条累计全超总长、覆盖却只有 59%/84%/100%、整体卡 83%)。覆盖读不到(-1)时保守当"没看完"、继续补。 */
    private fun rowDone(r: VideoRow) = r.coverage in doneCoverage..1.0
    /** 第一个"没看完"的列表下标(覆盖<98%或读不到)；没有则 -1。 */
    private fun firstPendingIdx(rows: List<VideoRow>): Int =
        rows.indexOfFirst { !rowDone(it) }

    private fun act(vm: VideoModel) {
        when (vm.kind) {
            VideoKind.DETAIL -> {
                if (vm.watchPct >= donePct) { host.stopAuto("视频完成进度已达 ${vm.watchPct}%"); return }
                currentVideoIdx = -1; lastVideoPos = -1
                host.tap(vm.startBtn)                 // 进播放器，列表迭代在 PLAYER 里做
            }
            VideoKind.PLAYER -> {
                // 真机实测：播放器页的"整体完成进度%"是**缓存死值**(播放中一直不动，详情页也要手动刷新才更新)，
                // 所以播放时**不能**靠它判完成。真正可靠、实时的完成信号是**每条绿色进度条**——全填满(覆盖≥98%)
                // 即整体≥95%(加权平均、充分条件)。以"绿条全填满"为完成信号；watchPct 仅作偶尔为鲜时的早停。
                val pendIdx = firstPendingIdx(vm.rows)
                if (vm.rows.isNotEmpty() && pendIdx < 0) { host.stopAuto("所有视频已看完(绿条全填满)"); return }
                if (vm.watchPct >= donePct) { host.stopAuto("视频完成进度已达 ${vm.watchPct}%"); return }

                if (pendIdx >= 0 && pendIdx != currentVideoIdx) {
                    // 需要换视频(初次进入 或 上一个已看完)→ 点列表里这一行播它。"看完一个点下一个"。
                    currentVideoIdx = pendIdx; lastVideoPos = -1
                    val r = vm.rows[pendIdx]
                    val cov = if (r.coverage >= 0) "${(r.coverage * 100).toInt()}%" else "?"
                    Log.i(TAG, "VIDEO 点第${pendIdx + 1}个: ${r.name} 覆盖$cov (整体${vm.watchPct}%)")
                    host.tap(r.tap)
                } else {
                    // 在看正确的这个视频：位置没推进(暂停/卡)就点中间恢复，否则让它继续放。
                    val advancing = vm.posSec >= 0 && vm.posSec != lastVideoPos
                    if (!advancing) {
                        vm.centerPlay?.let { host.tap(it) }
                        Log.i(TAG, "VIDEO 位置未推进(pos=${vm.posSec}), 点中间恢复播放")
                    }
                    lastVideoPos = vm.posSec
                }
            }
            VideoKind.OTHER -> {
                // 可能是"下一集/继续播放"等弹窗 → 点取消/关闭(自己控制进度，不让它乱跳)。
                vm.dismissBtn?.let { host.tap(it); Log.i(TAG, "VIDEO 关弹窗") }
            }
        }
        scheduleNext()
    }

    private fun scheduleNext() {
        if (!host.active) return
        host.postDelayed(videoStepMs) { step() }
    }

    companion object { private const val TAG = "DumpTool" }
}
