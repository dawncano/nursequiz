package com.quizhelper.dumptool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 题库未命中时的 AI 兜底钩子。把题干+选项发第三方聚合平台 **LuckyCola**（`luckycola.com.cn/aliai/tyqw`，
 * 通义千问中转，与竞品同一个平台）取正确答案，供答题/悬浮/考试三种模式共用。
 *
 * 【为什么是异步】答题循环 `loopStep` 跑在【主线程】(Handler(mainLooper))，主线程做网络请求会抛
 * `NetworkOnMainThreadException`。所以 [resolve] 绝不阻塞：
 *  - 命中内存缓存 → 立即返回；
 *  - 未命中 → 后台线程发请求、【本次先返回 null】(调用方自然走盲选 / 显示 unknown)，结果回来存缓存，
 *    同题下次重现即命中；若调用方传了 `onLate`，结果回来时在主线程回调最新答案(考试浮窗这种
 *    "停在同一题、屏不变就不会重读"的场景据此主动刷新，见 [ExamMachine])。
 *
 * 【AI 真正有用的地方是考试模式】考试无逐题对错反馈，新题只能靠 AI；答题/悬浮有反馈页，盲选一次即从
 * 反馈学到正确答案入库自愈，AI 只是把"第一遍也答对"锦上添花(且会消耗 LuckyCola 额度)。故默认关，
 * 由 [Prefs.aiEnabled] + appKey/uid 控制。
 */
object AiHook {

    private const val TAG = "DumpTool"
    private const val ENDPOINT = "https://luckycola.com.cn/aliai/tyqw"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 归一化题干 → 答案原文。空串="问过了但 AI 也没给出可用答案"，用于不再重复请求。 */
    private val cache = ConcurrentHashMap<String, String>()
    /** 正在请求中的题干 key，去重：同题在结果回来前不重复发请求。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val requestGuard = AsyncAnswerGuard()

    fun invalidatePending() = requestGuard.invalidateAll()

    /** 是否启用：开关开 + appKey/uid 都填了才算。任一缺失都当没开(静默降级为纯盲选/建库)。 */
    fun enabled(ctx: Context): Boolean =
        Prefs.aiEnabled(ctx) && Prefs.aiAppKey(ctx).isNotEmpty() && Prefs.aiUid(ctx).isNotEmpty()

    /**
     * 解析正确答案，返回选项文字（多选用 `|` 分隔，交给 [AnswerCodec.textsToIdx]/[AnswerCodec.forDisplay] 用）；
     * 无法（本次）给出返回 null。**主线程安全、绝不阻塞**（见类注释）。
     *
     * @param onLate 后台请求成功后在【主线程】回调最新答案(可为 null=AI 也没解出)。只用于"屏不变不会重读"的
     *               场景(考试浮窗)主动刷新；答题/悬浮会自然重读下一屏，传 null 即可。
     */
    fun resolve(
        ctx: Context,
        question: String,
        options: List<String>,
        onLate: ((String?) -> Unit)? = null
    ): String? {
        if (!enabled(ctx)) return null
        val key = TextMatch.normalize(question)
        if (key.isEmpty()) return null
        cache[key]?.let { return it.ifEmpty { null } }   // 已问过：有答案返回、空串(问过无解)返回 null

        if (inFlight.add(key)) {   // 首次遇到此题：后台发一次请求
            val appKey = Prefs.aiAppKey(ctx)
            val uid = Prefs.aiUid(ctx)
            val generation = requestGuard.begin(key)
            io.execute {
                val ans = runCatching { query(question, options, appKey, uid) }
                    .onFailure { Log.w(TAG, "AI 请求异常", it) }
                    .getOrNull().orEmpty()
                cache[key] = ans          // 空串也存：标记"问过无解"，不再重试同题
                inFlight.remove(key)
                Log.i(TAG, "AI 兜底 q='${question.take(16)}' -> '${ans.take(24)}'")
                if (onLate != null && requestGuard.isCurrent(key, generation)) {
                    main.post {
                        if (requestGuard.isCurrent(key, generation)) onLate(ans.ifEmpty { null })
                    }
                }
            }
        }
        return null   // 本次先没有：调用方走盲选/显示 unknown，结果回来后走缓存或 onLate
    }

    /** 同步 HTTP POST → LuckyCola(通义千问中转)，取 `data.result`。**仅在后台线程调用**。 */
    private fun query(question: String, options: List<String>, appKey: String, uid: String): String? {
        // Prompt 参考竞品并加上选项：让模型只回选项【文字原文】(多选竖线分隔)，好被 textsToIdx 按内容对上。
        val prompt = buildString {
            append("回答以下选择题，从给出的选项里选出正确答案，只回答正确选项的【文字原文】")
            append("（多选用竖线 | 分隔），不要序号、不要解释。\n")
            append(question).append('\n')
            options.forEachIndexed { i, o -> append('A' + i).append(". ").append(o).append('\n') }
        }
        val body = JSONObject()
            .put("ques", prompt)
            .put("appKey", appKey)
            .put("uid", uid)
            .put("isLongChat", 0)   // 单轮、不带上下文
            .toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "okhttp/4.9.1")   // 竞品同款 UA
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            if (code !in 200..299) {
                Log.w(TAG, "AI HTTP $code: ${text?.take(200)}")
                return null
            }
            // LuckyCola 返回形如 { code, data:{ result:"..." }, msg }
            JSONObject(text.orEmpty()).optJSONObject("data")
                ?.optString("result")?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }
}
