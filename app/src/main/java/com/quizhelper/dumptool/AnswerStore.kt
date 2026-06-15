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

    // 每道题"已盲选几次"（仅本次运行内存，跨题库不清也无妨）。
    private val blindAttempts = HashMap<String, Int>()

    /** 切换当前题库（标题/项目来自任务详情页 OCR）。换库时先存旧库再载新库。 */
    fun setBank(title: String, project: String) {
        val key = normalizeKey(title) + "||" + normalizeKey(project)
        if (key == currentKey) return
        persist()
        currentKey = key
        currentTitle = title
        currentProject = project
        answers.clear()
        load()
        Log.i(TAG, "切换题库: $title / $project (已存${answers.size}题)")
    }

    fun hasBank(): Boolean = currentKey != null

    fun bankLabel(): String = "$currentTitle / $currentProject"

    /** 查正确答案（返回如 "C" 或多选 "ABD"，没有则 null）。 */
    fun get(question: String): String? = answers[normalizeQuestion(question)]

    /** 记入正确答案并立即落盘。 */
    fun put(question: String, letters: String) {
        if (letters.isEmpty()) return
        answers[normalizeQuestion(question)] = letters
        persist()
    }

    fun size(): Int = answers.size

    /** 没学到答案时的盲选下标：第1次返回0(A)、第2次1(B)…，递增。调用方对选项数取模即可循环。 */
    fun nextBlindIndex(question: String): Int {
        val k = normalizeQuestion(question)
        val n = blindAttempts.getOrDefault(k, 0)
        blindAttempts[k] = n + 1
        return n
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
    }
}
