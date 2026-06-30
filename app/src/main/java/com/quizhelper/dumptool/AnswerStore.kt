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

    private var currentFile: File? = null
    private var currentTitle = ""
    private var currentProject = ""
    // 用 LinkedHashMap 保留插入顺序——题库管理的"最近入库十条"靠它(取末尾N条=最近学到的)。
    // persist 按此顺序写文件、load 按文件顺序读回，顺序跨运行稳定。新题 put 追加到末尾。
    private val answers = LinkedHashMap<String, String>()

    // 已见过的题干规范键，用于模糊归并 OCR 噪声造成的近似变体。
    private val seenKeys = ArrayList<String>()

    private val simThreshold = 0.82

    // 题库同一性的模糊阈值：同一题库的 OCR 噪声变体相似度约 0.92，不同题库(靠括号内容+分册号
    // 区分)约 0.73，0.84 居中能干净分开。偏保守，误并风险用 6.8 题库管理里手动改/删兜底。
    private val bankSim = 0.84

    /** 切换当前题库（标题/项目来自任务详情页节点）。
     *  不要求"标题+项目完全相等"——读到的标点/细微差异仍可能把同一题库拆成多个文件。
     *  改为模糊匹配复用已有库；若发现多个重复库，合并答案并删掉多余文件(不丢答案)。 */
    fun setBank(title: String, project: String) {
        val incoming = bankId(title, project)
        // 和当前库够像就不切换(避免每帧 TASK_DETAIL 都重载)。
        if (currentFile != null && similarity(incoming, bankId(currentTitle, currentProject)) >= bankSim) return
        persist()
        answers.clear()
        seenKeys.clear()

        val matches = listBanks(context).filter {
            similarity(incoming, bankId(it.title, it.project)) >= bankSim
        }
        if (matches.isNotEmpty()) {
            // 文件复用最大的(答案最多)，显示名取多数(并列取最长)。
            val target = matches.maxByOrNull { it.sizeBytes }!!
            currentFile = target.file
            currentTitle = bestName(matches.map { it.title } + title)
            currentProject = bestName(matches.map { it.project } + project)
            for (m in matches) answers.putAll(loadEntries(m.file))
            val extra = matches.filter { it.file != target.file }
            extra.forEach { it.file.delete() }
            persist()   // 合并结果落盘到 target
            if (extra.isNotEmpty()) Log.i(TAG, "题库去重: 合并并删除 ${extra.size} 个重复库 -> '$currentTitle'")
        } else {
            currentTitle = cleanName(title)
            currentProject = cleanName(project)
            currentFile = newFile(incoming)
            load()
        }
        seenKeys.addAll(answers.keys)
        Log.i(TAG, "切换题库: $currentTitle / $currentProject (已存${answers.size}题)")
    }

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

    /**
     * 把题干解析成"规范键"：和已见过的键比相似度，>=阈值就复用那个旧键(视为同一题)，否则视为新题。
     * 控件直读题干通常稳定，快路径即命中；慢路径容忍极少数细微差异(空格/标点变体)。
     * addIfNew=true 时把新题记入 seenKeys；get 用 false(只查不登记)。
     */
    private fun canonical(question: String, addIfNew: Boolean): String? {
        val norm = normalizeQuestion(question)
        if (norm.isEmpty()) return null
        // 快路径：归一化后正好命中已存答案的 key（题干稳定时是常态）→ O(1) 直接返回，
        // 免去对整库逐题算 Levenshtein 的慢扫描。题库越大，这条快路径省得越多。
        if (answers.containsKey(norm)) return norm
        // 慢路径：找当前库里最相近的已见 key。
        var best: String? = null
        var bestSim = 0.0
        for (k in seenKeys) {
            val sim = similarity(norm, k)
            if (sim > bestSim) { bestSim = sim; best = k }
        }
        if (best != null && bestSim >= simThreshold) {
            return best
        }
        if (addIfNew) seenKeys.add(norm)
        return norm
    }

    // 模糊匹配算法已提取到 [TextMatch]（无 Android 依赖、可单测）；此处薄包装供本类调用点不变。
    private fun similarity(a: String, b: String): Double = TextMatch.similarity(a, b)

    // ---------------------------------------------------------------

    private fun file(): File? = currentFile

    /** 题库同一性的归一化串：标题+项目只留中文/数字/字母，去掉引号/竖线/括号/加号等噪声，
     *  专供模糊匹配同一题库用(分册号、括号内容、科室、年份这些区分信息都保留)。 */
    private fun bankId(title: String, project: String): String =
        TextMatch.normalize(title + project)

    /** 名字清洗：去首尾空白(控件直读的文本已干净，不再需要 OCR 纠错表)。 */
    private fun cleanName(raw: String): String = raw.trim()

    /** 从一堆变体里挑显示名：各自清洗后取出现最多的(并列取最长)。 */
    private fun bestName(variants: List<String>): String {
        val cleaned = variants.map { cleanName(it) }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return variants.firstOrNull()?.let { cleanName(it) } ?: ""
        return cleaned.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .first().key
    }

    private fun newFile(id: String): File {
        val dir = File(context.getExternalFilesDir(null), "banks").apply { mkdirs() }
        return File(dir, "bank_" + Integer.toHexString(id.hashCode()) + "_" + id.length + ".json")
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

    /**
     * 题干归一化：只保留 中文/数字/字母，去掉所有标点、空白、竖线等噪声。
     * 因为同一题在"答题页"和"反馈页"的 OCR 标点常不一致(如多出 | 、,、: )，
     * 只有去掉这些噪声，存和查的 key 才能对上。
     */
    private fun normalizeQuestion(s: String): String = TextMatch.normalize(s)

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

        /** 临时文件(调试 dump 等)占用字节数。 */
        fun tempBytes(context: Context): Long =
            File(context.getExternalFilesDir(null), "dumps").listFiles()
                ?.sumOf { it.length() } ?: 0L

        fun clearTemp(context: Context) {
            File(context.getExternalFilesDir(null), "dumps").listFiles()?.forEach { it.delete() }
        }

        /** 清空本 App 的全部数据：题库、临时文件、所有设置。
         *  给"卸载前手动清干净 / 想从零开始"用——Android/data/<pkg> 下整个清掉。 */
        fun wipeAllData(context: Context) {
            runCatching { context.getExternalFilesDir(null)?.deleteRecursively() }
            Prefs.clearAll(context)
        }

        // --- 题库条目的查看/修正(供 MainActivity 6.8 用)。直接读写题库 JSON，
        //     不经过运行期实例。改动在下次 setBank 加载该库时生效。---

        /** 读出某题库文件里的全部"题干 → 答案"条目。 */
        fun loadEntries(file: File): LinkedHashMap<String, String> {
            val map = LinkedHashMap<String, String>()
            runCatching {
                val ans = JSONObject(file.readText()).optJSONObject("answers") ?: return map
                for (k in ans.keys()) map[k] = ans.getString(k)
            }
            return map
        }

        /** 改写/新增一条答案，保留标题/项目并刷新 updated。 */
        fun saveEntry(file: File, question: String, letters: String) {
            runCatching {
                val o = JSONObject(file.readText())
                val ans = o.optJSONObject("answers") ?: JSONObject()
                ans.put(question, letters)
                o.put("answers", ans).put("updated", System.currentTimeMillis())
                file.writeText(o.toString())
            }.onFailure { Log.e(TAG, "saveEntry failed", it) }
        }

        /** 修正一条：题干和/或答案。改题干=换 key(删旧键、插新键到末尾)，保留/更新答案。
         *  一次文件读写，避免删+存两次 IO。newQuestion 为空则忽略(当作只改答案)。 */
        fun editEntry(file: File, oldQuestion: String, newQuestion: String, letters: String) {
            runCatching {
                val o = JSONObject(file.readText())
                val ans = o.optJSONObject("answers") ?: JSONObject()
                val newQ = newQuestion.ifBlank { oldQuestion }
                if (newQ != oldQuestion) ans.remove(oldQuestion)   // 题干变了→删旧键
                ans.put(newQ, letters)                              // 插/更新(放到末尾=最近)
                o.put("answers", ans).put("updated", System.currentTimeMillis())
                file.writeText(o.toString())
            }.onFailure { Log.e(TAG, "editEntry failed", it) }
        }

        /** 删除一条答案。 */
        fun deleteEntry(file: File, question: String) {
            runCatching {
                val o = JSONObject(file.readText())
                val ans = o.optJSONObject("answers") ?: return
                ans.remove(question)
                o.put("answers", ans).put("updated", System.currentTimeMillis())
                file.writeText(o.toString())
            }.onFailure { Log.e(TAG, "deleteEntry failed", it) }
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
