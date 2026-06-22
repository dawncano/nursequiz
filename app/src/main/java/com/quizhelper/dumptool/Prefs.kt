package com.quizhelper.dumptool

import android.content.Context

/**
 * 用户设置(存 SharedPreferences)。设置项在 MainActivity 改、由无障碍服务运行期读取，
 * 两者是不同组件，靠这份偏好文件传递。所有读取都带默认值，没设置过也能正常跑。
 */
object Prefs {
    private const val FILE = "quiz_settings"

    private const val KEY_BRUTE = "brute_mode"
    private const val KEY_TARGET = "target_groups"
    private const val KEY_STEP_BRUTE = "step_interval_brute_ms"
    private const val KEY_STEP_SMART = "step_interval_smart_ms"
    private const val KEY_UNKNOWN = "unknown_limit"

    // 默认值（也是文档里写定的）：默认暴力模式、14组、连续5次UNKNOWN停。
    // step 间隔分两套：暴力不看题干文字、容忍更快(默认700)；智能要干净OCR匹配题库(默认1300)。
    const val DEF_BRUTE = true
    const val DEF_TARGET = 14
    const val DEF_STEP_BRUTE = 700L
    const val DEF_STEP_SMART = 1300L
    const val DEF_UNKNOWN = 5

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** true=暴力模式(纯盲选+建库，不查表)，false=智能模式(查表替换预选)。 */
    fun bruteMode(c: Context): Boolean = sp(c).getBoolean(KEY_BRUTE, DEF_BRUTE)
    fun setBruteMode(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_BRUTE, v).apply()

    fun targetGroups(c: Context): Int = sp(c).getInt(KEY_TARGET, DEF_TARGET).coerceIn(1, 999)
    fun setTargetGroups(c: Context, v: Int) = sp(c).edit().putInt(KEY_TARGET, v.coerceIn(1, 999)).apply()

    fun stepIntervalBruteMs(c: Context): Long = sp(c).getLong(KEY_STEP_BRUTE, DEF_STEP_BRUTE).coerceIn(300L, 5000L)
    fun setStepIntervalBruteMs(c: Context, v: Long) = sp(c).edit().putLong(KEY_STEP_BRUTE, v.coerceIn(300L, 5000L)).apply()

    fun stepIntervalSmartMs(c: Context): Long = sp(c).getLong(KEY_STEP_SMART, DEF_STEP_SMART).coerceIn(300L, 5000L)
    fun setStepIntervalSmartMs(c: Context, v: Long) = sp(c).edit().putLong(KEY_STEP_SMART, v.coerceIn(300L, 5000L)).apply()

    /** 按当前模式返回该用的 step 间隔——`scheduleStep` 直接调它即可，模式切换下一拍生效。 */
    fun stepIntervalMs(c: Context): Long = if (bruteMode(c)) stepIntervalBruteMs(c) else stepIntervalSmartMs(c)

    fun unknownLimit(c: Context): Int = sp(c).getInt(KEY_UNKNOWN, DEF_UNKNOWN).coerceIn(1, 50)
    fun setUnknownLimit(c: Context, v: Int) = sp(c).edit().putInt(KEY_UNKNOWN, v.coerceIn(1, 50)).apply()

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
