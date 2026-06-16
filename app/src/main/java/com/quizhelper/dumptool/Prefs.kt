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
    private const val KEY_STEP = "step_interval_ms"
    private const val KEY_UNKNOWN = "unknown_limit"

    // 默认值（也是文档里写定的）：默认暴力模式、14组、1300ms、连续5次UNKNOWN停。
    const val DEF_BRUTE = true
    const val DEF_TARGET = 14
    const val DEF_STEP = 1300L
    const val DEF_UNKNOWN = 5

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** true=暴力模式(纯盲选+建库，不查表)，false=智能模式(查表替换预选)。 */
    fun bruteMode(c: Context): Boolean = sp(c).getBoolean(KEY_BRUTE, DEF_BRUTE)
    fun setBruteMode(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_BRUTE, v).apply()

    fun targetGroups(c: Context): Int = sp(c).getInt(KEY_TARGET, DEF_TARGET).coerceIn(1, 999)
    fun setTargetGroups(c: Context, v: Int) = sp(c).edit().putInt(KEY_TARGET, v.coerceIn(1, 999)).apply()

    fun stepIntervalMs(c: Context): Long = sp(c).getLong(KEY_STEP, DEF_STEP).coerceIn(300L, 5000L)
    fun setStepIntervalMs(c: Context, v: Long) = sp(c).edit().putLong(KEY_STEP, v.coerceIn(300L, 5000L)).apply()

    fun unknownLimit(c: Context): Int = sp(c).getInt(KEY_UNKNOWN, DEF_UNKNOWN).coerceIn(1, 50)
    fun setUnknownLimit(c: Context, v: Int) = sp(c).edit().putInt(KEY_UNKNOWN, v.coerceIn(1, 50)).apply()
}
