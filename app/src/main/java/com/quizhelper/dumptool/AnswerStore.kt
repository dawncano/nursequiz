package com.quizhelper.dumptool

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 答案记忆库：按"题库"分文件存。题库 = 标题 + 项目。
 * 每个题库一个 json 文件：{ title, project, updated, answers:{题干:正确字母} }。
 * 这样以后能单独删除过期题库。
 */
class AnswerStore(private val context: Context) {

    private var currentKey: String? = null
    private var currentTitle = ""
    private var currentProject = ""
    private val answers = HashMap<String, String>()

    // 每道题"已盲选几次"（仅本次运行内存）。
    private val blindAttempts = HashMap<String, Int>()

    // 已见过的题干规范键，用于模糊归并 OCR 噪声造成的近似变体。
    private val seenKeys = ArrayList<String>()

    private val simThreshold = 0.82

    /** 切换当前题库（标题/项目来自任务详情页 OCR）。换库时先存旧库再载新库。 */
    fun setBank(title: String, project: String) {
        val key = normalizeKey(title) + "||" + normalizeKey(project)
        if (key == currentKey) return
        persist()
        currentKey = key
        currentTitle = title
        currentProject = project
        answers.clear()
        seenKeys.clear()
        blindAttempts.clear()
        load()
        Log.i(TAG, "切换题库: $title / $project (已存${answers.size}题)")
    }

    fun hasBank(): Boolean = currentKey != null

    fun bankLabel(): String = "$currentTitle / $currentProject"

    /** 查正确答案（返回如 "C" 或多选 "ABD"，没有则 null）。模糊匹配题干。 */
    fun get(question: String): String? {
        val k = canonical(question, addIfNew = false) ?: return null
        return answers[k]
    }

    /** 给外部(失败计数等)用的稳定题干key，复用与 get() 相同的模糊归并逻辑，只读不登记。 */
    fun keyFor(question: String): String? = canonical(question, addIfNew = false)

    /** 记入正确答案并立即落盘。 */
    fun put(question: String, letters: String) {
        if (letters.isEmpty()) return
        val k = canonical(question, addIfNew = true) ?: return
        answers[k] = letters
        persist()
    }

    fun size(): Int = answers.size

    /** 没学到答案时的盲选下标：第1次返回0(A)、第2次1(B)…，递增。调用方对选项数取模即可循环。
     *  用模糊归并后的稳定 key 计数，OCR 噪声不会让计数每次都从头开始。 */
    fun nextBlindIndex(question: String): Int {
        val k = canonical(question, addIfNew = true) ?: return 0
        val n = blindAttempts.getOrDefault(k, 0)
        blindAttempts[k] = n + 1
        return n
    }

    /**
     * 把题干解析成"规范键"：和已见过的键比相似度，>=阈值就复用那个旧键(视为同一题)，
     * 否则视为新题。这样 OCR 让题干每次略有不同时，仍能归并到同一道题。
     * addIfNew=true 时把新题记入 seenKeys；get 用 false(只查不登记)。
     */
    private fun canonical(question: String, addIfNew: Boolean): String? {
        val norm = normalizeQuestion(question)
        if (norm.isEmpty()) return null
        var best: String? = null
        var bestSim = 0.0
        for (k in seenKeys) {
            val sim = similarity(norm, k)
            if (sim > bestSim) { bestSim = sim; best = k }
        }
        if (best != null && bestSim >= simThreshold) return best
        if (addIfNew) seenKeys.add(norm)
        return norm
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        if (kotlin.math.abs(a.length - b.length) > maxLen * 0.35) return 0.0
        val dist = levenshtein(a, b)
        val fullSim = 1.0 - dist.toDouble() / maxLen
        // 案例组合题：多道子题共享很长的病史前缀，只有末尾10-20字不同。
        // 仅用全串相似度会把不同子题归并成同一题。
        // 额外检查末尾40字的相似度，末尾不同的两题必须同时通过两个门槛才算同一题。
        if (maxLen > 60) {
            val sfxLen = minOf(40, maxLen)
            val sfxSim = 1.0 - levenshtein(a.takeLast(sfxLen), b.takeLast(sfxLen)).toDouble() / sfxLen
            return minOf(fullSim, sfxSim)
        }
        return fullSim
    }

    private fun levenshtein(a: String, b: String): Int {
        val n = b.length
        if (a.isEmpty()) return n
        if (b.isEmpty()) return a.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = curr; curr = t
        }
        return prev[n]
    }

    // ---------------------------------------------------------------

    private fun file(): File? {
        val key = currentKey ?: return null
        val dir = File(context.getExternalFilesDir(null), "banks").apply { mkdirs() }
        val name = "bank_" + Integer.toHexString(key.hashCode()) + "_" + key.length + ".json"
        return File(dir, name)
    }

    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching {
            val obj = JSONObject(f.readText())
            val ans = obj.optJSONObject("answers") ?: return@runCatching
            for (k in ans.keys()) answers[k] = ans.getString(k)
            seenKeys.addAll(answers.keys)
        }.onFailure { Log.e(TAG, "load failed", it) }
    }

    private fun persist() {
        val f = file() ?: return
        runCatching {
            val ansObj = JSONObject()
            for ((k, v) in answers) ansObj.put(k, v)
            val obj = JSONObject()
                .put("title", currentTitle)
                .put("project", currentProject)
                .put("updated", System.currentTimeMillis())
                .put("answers", ansObj)
            f.writeText(obj.toString())
        }.onFailure { Log.e(TAG, "persist failed", it) }
    }

    private fun normalizeKey(s: String): String =
        s.replace("\\s".toRegex(), "").replace("进行中", "")

    /**
     * 题干归一化：只保留 中文/数字/字母，去掉所有标点、空白、竖线等噪声。
     * 因为同一题在"答题页"和"反馈页"的 OCR 标点常不一致(如多出 | 、,、: )，
     * 只有去掉这些噪声，存和查的 key 才能对上。
     */
    private fun normalizeQuestion(s: String): String =
        s.replace("[^a-zA-Z0-9\\u4e00-\\u9fff]".toRegex(), "")

    companion object {
        private const val TAG = "DumpTool"

        /** 列出所有已存题库(供管理界面用)。 */
        fun listBanks(context: Context): List<BankInfo> {
            val dir = File(context.getExternalFilesDir(null), "banks")
            val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
            return files.mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    BankInfo(
                        file = f,
                        title = o.optString("title"),
                        project = o.optString("project"),
                        count = o.optJSONObject("answers")?.length() ?: 0,
                        sizeBytes = f.length()
                    )
                }.getOrNull()
            }.sortedByDescending { it.sizeBytes }
        }

        /** 临时文件(调试截图/OCR等)占用字节数。 */
        fun tempBytes(context: Context): Long =
            File(context.getExternalFilesDir(null), "dumps").listFiles()
                ?.sumOf { it.length() } ?: 0L

        fun clearTemp(context: Context) {
            File(context.getExternalFilesDir(null), "dumps").listFiles()?.forEach { it.delete() }
        }
    }
}

/** 题库概要信息（管理界面展示用）。 */
data class BankInfo(
    val file: java.io.File,
    val title: String,
    val project: String,
    val count: Int,
    val sizeBytes: Long
)
