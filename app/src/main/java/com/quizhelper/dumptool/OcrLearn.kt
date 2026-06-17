package com.quizhelper.dumptool

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * OCR 等价对的"自动学习"——只攒候选、不自动生效。
 *
 * 当 AnswerStore 把两段题干判成"同一道题"(模糊命中)时，逐位比对差异字符，把"同一字位上
 * 出现的两种写法"记成一条候选等价对并计数(持久化)。达到门槛(总数≥30 且 该对占两边混淆的
 * ≥97%)的候选会在 MainActivity 列出来，**由用户点确认**才写入 `ocrfix_user.json`、给 OcrFix
 * 用。永久行为必须用户可控，所以绝不自动入表(参考 6.1)。
 */
object OcrLearn {
    private const val TAG = "DumpTool"
    private const val FILE = "ocrfix_candidates.json"
    const val MIN_TOTAL = 30        // 一条候选对至少被观察到这么多次
    const val MIN_DOMINANCE = 0.97  // 且占 X 或 Y 各自全部混淆的比例都要这么高(说明是干净的成对混淆)

    /** 一条可展示的候选：rep=更可能对的字(代表/放前面)，other=另一种写法，count=次数，
     *  example=一句实际出现过这俩字混淆的上下文，让用户看明白在替换什么。 */
    data class Candidate(val rep: String, val other: String, val count: Int, val example: String)

    // 候选对(无向，key="较小字|较大字") -> 被混淆计数
    private val pairCount = HashMap<String, Int>()
    // 候选对 -> 一段实际出现过的上下文例句(给用户看懂在替换什么)
    private val pairExample = HashMap<String, String>()
    // 每个字在所有题干里出现的总次数 -> 用来定"哪个更可能是对的"：正确字在每次正确识别里
    // 都出现(频率高)，错字只在偶尔误识别里出现(频率低)，所以频率高的优先当代表。
    private val charFreq = HashMap<String, Int>()
    private var appCtx: Context? = null
    private var dirty = 0

    /** 统计字频(每处理一段题干就喂一次)，给候选定方向用。 */
    fun noteText(s: String) {
        for (c in s) { val k = c.toString(); charFreq[k] = (charFreq[k] ?: 0) + 1 }
    }

    fun init(context: Context) {
        if (appCtx != null) return   // 进程内单例，已初始化就别再 load 覆盖内存里的累积
        appCtx = context.applicationContext
        load()
    }

    /** 两段"同一题"的文本：同长度、且只差极少数单字时，把每个差异字位记成一条候选等价对。 */
    fun observe(a: String, b: String) {
        if (appCtx == null || a.length != b.length || a == b) return
        var diffs = 0
        for (i in a.indices) if (a[i] != b[i]) diffs++
        if (diffs == 0 || diffs > 3) return   // 差太多更像是两道不同题，不是 OCR 噪声
        for (i in a.indices) {
            if (a[i] != b[i]) {
                val key = pairKey(a[i].toString(), b[i].toString())
                pairCount[key] = (pairCount[key] ?: 0) + 1
                // 存一段上下文例句(取 a 在该字位附近的窗口)，凑够一定长度就不再换。
                if ((pairExample[key]?.length ?: 0) < 10) {
                    pairExample[key] = a.substring(maxOf(0, i - 6), minOf(a.length, i + 7))
                }
            }
        }
        if (++dirty >= 20) save()
    }

    /** 达标、可展示给用户确认的候选(含上下文例句)，按次数降序。 */
    fun qualifying(): List<Candidate> {
        // 每个字"参与混淆"的总次数 = 含它的所有对的计数之和
        val memberTotal = HashMap<String, Int>()
        for ((k, c) in pairCount) {
            val (x, y) = k.split("|")
            memberTotal[x] = (memberTotal[x] ?: 0) + c
            memberTotal[y] = (memberTotal[y] ?: 0) + c
        }
        val out = ArrayList<Candidate>()
        for ((k, c) in pairCount) {
            if (c < MIN_TOTAL) continue
            val (a, b) = k.split("|")
            val denom = maxOf(memberTotal[a] ?: c, memberTotal[b] ?: c)
            if (denom > 0 && c.toDouble() / denom >= MIN_DOMINANCE) {
                // 字频高的当代表(更可能是对的)放前面，确认入表时它就是规范写法。
                val (x, y) = if ((charFreq[a] ?: 0) >= (charFreq[b] ?: 0)) a to b else b to a
                out.add(Candidate(x, y, c, pairExample[k] ?: ""))
            }
        }
        return out.sortedByDescending { it.count }
    }

    /** 用户确认某候选：把 [x,y] 作为等价组追加进 ocrfix_user.json，并从候选里清掉。 */
    fun confirm(x: String, y: String) {
        val ctx = appCtx ?: return
        runCatching {
            val uf = File(ctx.getExternalFilesDir(null), "ocrfix_user.json")
            val root = if (uf.exists()) JSONObject(uf.readText()) else JSONObject()
            val arr = root.optJSONArray("groups") ?: org.json.JSONArray().also { root.put("groups", it) }
            arr.put(org.json.JSONArray().put(x).put(y))
            uf.writeText(root.toString())
            pairCount.remove(pairKey(x, y))
            save()
            OcrFix.load(ctx)   // 立即重载，确认后立刻生效
            Log.i(TAG, "OCR 候选确认入表: $x = $y")
        }.onFailure { Log.e(TAG, "confirm failed", it) }
    }

    /** 用户忽略某候选：从候选里清掉(下次还会重新攒，但至少先不烦)。 */
    fun dismiss(x: String, y: String) {
        pairCount.remove(pairKey(x, y))
        save()
    }

    /** 清空内存里的候选(配合 AnswerStore.wipeAllData；候选文件已被整目录删除)。 */
    fun clearAll() {
        pairCount.clear()
        charFreq.clear()
        pairExample.clear()
    }

    private fun pairKey(a: String, b: String): String =
        if (a <= b) "$a|$b" else "$b|$a"

    private fun load() {
        val ctx = appCtx ?: return
        runCatching {
            val f = File(ctx.getExternalFilesDir(null), FILE)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            root.optJSONObject("pairs")?.let { o -> for (k in o.keys()) pairCount[k] = o.getInt(k) }
            root.optJSONObject("freq")?.let { o -> for (k in o.keys()) charFreq[k] = o.getInt(k) }
            root.optJSONObject("examples")?.let { o -> for (k in o.keys()) pairExample[k] = o.getString(k) }
        }.onFailure { Log.e(TAG, "OcrLearn load failed", it) }
    }

    fun save() {
        val ctx = appCtx ?: return
        dirty = 0
        runCatching {
            val pairs = JSONObject()
            for ((k, c) in pairCount) pairs.put(k, c)
            // 只存"出现在候选对里的字"的频率(定方向够用)，避免整张字频表越存越大。
            val needed = HashSet<String>()
            for (k in pairCount.keys) k.split("|").forEach { needed.add(it) }
            val freq = JSONObject()
            for (k in needed) charFreq[k]?.let { freq.put(k, it) }
            val examples = JSONObject()
            for ((k, v) in pairExample) if (pairCount.containsKey(k)) examples.put(k, v)
            val f = File(ctx.getExternalFilesDir(null), FILE)
            f.writeText(JSONObject().put("pairs", pairs).put("freq", freq).put("examples", examples).toString())
        }.onFailure { Log.e(TAG, "OcrLearn save failed", it) }
    }
}
