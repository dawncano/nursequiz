package com.quizhelper.dumptool

import android.content.Context

/**
 * 自动循环的作答模式。三个开关在设置页互斥，[Prefs.mode] 按固定优先级解析成单一模式。
 * 各模式的"呈现策略"(显不显控制球/答案渲染到哪/停止后是否留占位)由下面的属性单点定义，
 * 免得 Service 在 showAnswer/applyOverlayMode/onKeyEvent/stopAuto 到处重问 examMode/floatMode。
 */
enum class AnswerMode {
    QUIZ, FLOAT, VIDEO, EXAM;

    /** 是否显示无障碍控制悬浮球——悬浮/考试模式不显大球(改用小标签/独立前台浮窗)。 */
    val showsControlBall get() = this == QUIZ || this == VIDEO
    /** 答案是否渲染到独立前台服务浮窗——考试错峰时无障碍会被关，不能用无障碍 overlay。 */
    val usesExamOverlay get() = this == EXAM
    /** 是否用无障碍悬浮小标签显示答案(悬浮模式)。 */
    val usesAnswerLabel get() = this == FLOAT
    /** 停止后是否保留占位「。。。」——悬浮模式模式还在，留着让用户知道。 */
    val keepsPlaceholderOnStop get() = this == FLOAT
}

/**
 * 用户设置(存 SharedPreferences)。设置项在 MainActivity 改、由无障碍服务运行期读取，
 * 两者是不同组件，靠这份偏好文件传递。所有读取都带默认值，没设置过也能正常跑。
 */
object Prefs {
    private const val FILE = "quiz_settings"

    private const val KEY_TARGET = "target_groups"
    private const val KEY_STEP = "step_interval_ms"
    private const val KEY_UNKNOWN = "unknown_limit"
    private const val KEY_FLOAT = "float_answer_mode"
    private const val KEY_HUMANIZE = "humanize"
    private const val KEY_VIDEO = "video_mode"
    private const val KEY_EXAM = "exam_mode"
    private const val KEY_AI = "ai_enabled"
    private const val KEY_AI_APPKEY = "ai_appkey"
    private const val KEY_AI_UID = "ai_uid"

    // 默认值：14组、连续5次UNKNOWN停。
    // 步与步之间的"等下一屏"已改走 waitFor(等屏就绪)；DEF_STEP 现仅作内部"基础间隔"：
    // 给 tapSequence(多选连点 gap/选完到确定 settle)、captureAndParse 隐藏球延时、初始踢一脚用，
    // 不再是用户面的"速度"设置(已由「拟人操作」开关收编)。拟人开=屏就绪后补随机延时；关=飞速。
    const val DEF_TARGET = 14
    const val DEF_STEP = 700L
    const val DEF_UNKNOWN = 5

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun targetGroups(c: Context): Int = sp(c).getInt(KEY_TARGET, DEF_TARGET).coerceIn(1, 999)
    fun setTargetGroups(c: Context, v: Int) = sp(c).edit().putInt(KEY_TARGET, v.coerceIn(1, 999)).apply()

    /** 单步间隔——`scheduleStep` 直接调它，运行期改了下一拍生效。 */
    fun stepIntervalMs(c: Context): Long = sp(c).getLong(KEY_STEP, DEF_STEP).coerceIn(300L, 5000L)
    fun setStepIntervalMs(c: Context, v: Long) = sp(c).edit().putLong(KEY_STEP, v.coerceIn(300L, 5000L)).apply()

    fun unknownLimit(c: Context): Int = sp(c).getInt(KEY_UNKNOWN, DEF_UNKNOWN).coerceIn(1, 50)
    fun setUnknownLimit(c: Context, v: Int) = sp(c).edit().putInt(KEY_UNKNOWN, v.coerceIn(1, 50)).apply()

    /** 作答方式：false=自动点击(默认，自动答+建库)；true=悬浮答案(只在悬浮窗显示答案，自己点，顺便建库)。 */
    fun floatMode(c: Context): Boolean = sp(c).getBoolean(KEY_FLOAT, false)
    fun setFloatMode(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_FLOAT, v).apply()

    /** 拟人操作：默认开。开=每步在 waitFor 屏就绪后再补一段随机延时，模拟人手节奏、防风控(竞品同思路)；
     *  关=飞速模式，屏一就绪立刻走(只受 waitFor 节制)。叠在 waitFor 之上。 */
    fun humanize(c: Context): Boolean = sp(c).getBoolean(KEY_HUMANIZE, true)
    fun setHumanize(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_HUMANIZE, v).apply()

    /** 视频自动挂课模式：开=▶ 启动后走视频挂课状态机(进视频任务详情自动看视频)；关=正常答题。默认关。 */
    fun videoMode(c: Context): Boolean = sp(c).getBoolean(KEY_VIDEO, false)
    fun setVideoMode(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_VIDEO, v).apply()

    /** 考试模式：开=进入考试时查库作答+下一题+交卷(全程不建库，靠练习已建的库)；关=正常练习。默认关。 */
    fun examMode(c: Context): Boolean = sp(c).getBoolean(KEY_EXAM, false)
    fun setExamMode(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_EXAM, v).apply()

    /** AI 兜底开关：开=题库没查到的题，先问一次云端 AI(LuckyCola 通义千问中转，同竞品)当兜底答案。
     *  默认关。这是【正交增强】而非答题模式，不参与上面三个模式的互斥；对答题/悬浮/考试都生效
     *  (实际最有用的是考试模式——考试无对错反馈、新题只能靠 AI；答题/悬浮有反馈页会自愈建库，AI 只是锦上添花)。 */
    fun aiEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_AI, false)
    fun setAiEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_AI, v).apply()

    /** LuckyCola 平台账号的 appKey(在 luckycola.com.cn 注册后拿)。空则 AI 兜底不生效。 */
    fun aiAppKey(c: Context): String = sp(c).getString(KEY_AI_APPKEY, "")?.trim().orEmpty()
    fun setAiAppKey(c: Context, v: String) = sp(c).edit().putString(KEY_AI_APPKEY, v.trim()).apply()

    /** LuckyCola 平台账号的 uid(同上，与 appKey 成对使用)。空则 AI 兜底不生效。 */
    fun aiUid(c: Context): String = sp(c).getString(KEY_AI_UID, "")?.trim().orEmpty()
    fun setAiUid(c: Context, v: String) = sp(c).edit().putString(KEY_AI_UID, v.trim()).apply()

    /** 三个模式开关在设置页互斥，这里按固定优先级(视频>考试>悬浮>普通)解析成单一模式，loopStep 据此分派。
     *  优先级与原 loopStep 里的 if 顺序一致——万一多个开关同时为真也只会进一个分支，行为不变。 */
    fun mode(c: Context): AnswerMode = when {
        videoMode(c) -> AnswerMode.VIDEO
        examMode(c) -> AnswerMode.EXAM
        floatMode(c) -> AnswerMode.FLOAT
        else -> AnswerMode.QUIZ
    }

    // 悬浮球只贴左右边，位置 = 垂直 y + 贴哪边。Int.MIN_VALUE 表示"未设置，用默认百分比高度"。
    fun overlayY(c: Context): Int = sp(c).getInt("overlay_y", Int.MIN_VALUE)
    fun overlaySideRight(c: Context): Boolean = sp(c).getBoolean("overlay_right", true)
    fun setOverlayPos(c: Context, y: Int, isRight: Boolean) =
        sp(c).edit().putInt("overlay_y", y).putBoolean("overlay_right", isRight).apply()

    // 悬浮窗无操作多少毫秒后自动收回成小球(默认3000，用户可调)。
    fun attachDelayMs(c: Context): Long = sp(c).getLong("attach_delay_ms", 3000L).coerceIn(1000L, 30000L)
    fun setAttachDelayMs(c: Context, v: Long) = sp(c).edit().putLong("attach_delay_ms", v.coerceIn(1000L, 30000L)).apply()

    /** 清空所有设置(恢复默认)。配合 AnswerStore.wipeAllData 用。 */
    fun clearAll(c: Context) = sp(c).edit().clear().apply()
}
