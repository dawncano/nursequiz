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

    // 候选对(无向，key="较小字|较大字") -> 被混淆计数
    private val pairCount = HashMap<String, Int>()
    private var appCtx: Context? = null
    private var dirty = 0

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
            }
        }
        if (++dirty >= 20) save()
    }

    /** 达标、可展示给用户确认的候选：返回 (字X, 字Y, 次数)，按次数降序。 */
    fun qualifying(): List<Triple<String, String, Int>> {
        // 每个字"参与混淆"的总次数 = 含它的所有对的计数之和
        val memberTotal = HashMap<String, Int>()
        for ((k, c) in pairCount) {
            val (x, y) = k.split("|")
            memberTotal[x] = (memberTotal[x] ?: 0) + c
            memberTotal[y] = (memberTotal[y] ?: 0) + c
        }
        val out = ArrayList<Triple<String, String, Int>>()
        for ((k, c) in pairCount) {
            if (c < MIN_TOTAL) continue
            val (x, y) = k.split("|")
            val denom = maxOf(memberTotal[x] ?: c, memberTotal[y] ?: c)
            if (denom > 0 && c.toDouble() / denom >= MIN_DOMINANCE) out.add(Triple(x, y, c))
        }
        return out.sortedByDescending { it.third }
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

    private fun pairKey(a: String, b: String): String =
        if (a <= b) "$a|$b" else "$b|$a"

    private fun load() {
        val ctx = appCtx ?: return
        runCatching {
            val f = File(ctx.getExternalFilesDir(null), FILE)
            if (!f.exists()) return
            val obj = JSONObject(f.readText()).optJSONObject("pairs") ?: return
            for (k in obj.keys()) pairCount[k] = obj.getInt(k)
        }.onFailure { Log.e(TAG, "OcrLearn load failed", it) }
    }

    fun save() {
        val ctx = appCtx ?: return
        dirty = 0
        runCatching {
            val pairs = JSONObject()
            for ((k, c) in pairCount) pairs.put(k, c)
            val f = File(ctx.getExternalFilesDir(null), FILE)
            f.writeText(JSONObject().put("pairs", pairs).toString())
        }.onFailure { Log.e(TAG, "OcrLearn save failed", it) }
    }
}
