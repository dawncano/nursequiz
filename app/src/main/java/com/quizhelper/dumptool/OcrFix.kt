package com.quizhelper.dumptool

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * OCR 等价对照表：把同一文字的 OCR 变体归一成同一个写法。
 *
 * 不分"对/错"——只把一组互相等价(可互换)的写法都替换成组内第一个(规范代表)。
 * 这样既绕开了"哪个才是对的"这种判不准的难题，又能让同一题在不同界面/不同轮次的
 * OCR 结果一致，配合 AnswerStore 的模糊匹配，使题库 key、界面按钮匹配更稳定。
 *
 * 表来源(叠加)：
 *  ① 内置 `assets/ocrfix.json`(随 APK 发布的初始全表)；
 *  ② `files/ocrfix_user.json`(用户在界面里确认新增的，同格式)，存在则叠加在内置表之上。
 * `load(context)` 在服务启动时调一次；读不到就退回下面的 FALLBACK，保证 fix() 永远可用。
 */
object OcrFix {

    // 内置兜底(assets 读取失败时用)。完整初始表在 assets/ocrfix.json。
    private val FALLBACK: List<List<String>> = listOf(
        listOf("一", "ー"),
        listOf("正确答案", "正确苔案"),
        listOf("答案", "苔案"),
        listOf("单选题", "半选题", "里还题"),
        listOf("下一题", "下ー题"),
    )

    @Volatile private var groups: List<List<String>> = FALLBACK

    /** 从内置 assets + 用户 files 两个 JSON 加载等价组；任一失败都不影响(各自跳过)。 */
    fun load(context: Context) {
        val all = ArrayList<List<String>>()
        runCatching {
            context.assets.open("ocrfix.json").use { all.addAll(parse(it.readBytes().decodeToString())) }
        }.onFailure { Log.e(TAG, "load assets ocrfix.json failed", it) }
        runCatching {
            val uf = File(context.getExternalFilesDir(null), "ocrfix_user.json")
            if (uf.exists()) all.addAll(parse(uf.readText()))
        }.onFailure { Log.e(TAG, "load user ocrfix failed", it) }
        if (all.isNotEmpty()) {
            groups = all
            Log.i(TAG, "OcrFix 载入 ${all.size} 组等价写法")
        }
    }

    /** 把文本里每组的非代表写法替换成该组代表(组内第一个)。 */
    fun fix(s: String): String {
        if (s.isEmpty()) return s
        var t = s
        for (g in groups) {
            if (g.size < 2) continue
            val rep = g[0]
            for (i in 1 until g.size) {
                if (t.contains(g[i])) t = t.replace(g[i], rep)
            }
        }
        return t
    }

    private fun parse(json: String): List<List<String>> {
        val arr = JSONObject(json).optJSONArray("groups") ?: return emptyList()
        val out = ArrayList<List<String>>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONArray(i) ?: continue
            val members = ArrayList<String>()
            for (j in 0 until g.length()) g.optString(j).takeIf { it.isNotEmpty() }?.let { members.add(it) }
            if (members.size >= 2) out.add(members)
        }
        return out
    }

    private const val TAG = "DumpTool"
}
