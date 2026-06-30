package com.quizhelper.dumptool

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 自动开启无障碍服务——学习自竞品「免手动设置」的思路（root / Shizuku / 直接写 secure settings 三选一），
 * 这里落地成最适合"自己有 adb 的个人工具"的那条：**直接写 secure settings**。
 *
 * 原理：`enabled_accessibility_services` / `accessibility_enabled` 是受保护的 Secure 设置，普通 App 无权写；
 * 但只要本 App 持有 `WRITE_SECURE_SETTINGS`，就能用标准 `Settings.Secure.putString` 自己把服务打开。
 * 这个权限是签名/特权级，应用商店装不来，但**用 adb 一次性授予即可（重启不丢，卸载才掉）**：
 *
 *     adb shell pm grant com.quizhelper.dumptool android.permission.WRITE_SECURE_SETTINGS
 *
 * `build.ps1` 装机后已自动执行这条。没授权时本类所有方法安全失败（返回 false），调用方退回打开系统设置。
 *
 * 注意：[SERVICE] 必须和 AndroidManifest 里注册的（伪装后的）服务组件名**逐字一致**，否则系统认不出。
 */
object A11yEnabler {
    private const val TAG = "DumpTool"

    /** 已注册的服务组件名（伪装成 Google「选择朗读」）。改服务名/包名时这里要同步。 */
    const val SERVICE =
        "com.quizhelper.dumptool/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    /** 是否已拿到 WRITE_SECURE_SETTINGS（= 能自助开关无障碍）。 */
    fun canSelfEnable(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 把本服务加入"已启用无障碍服务"列表并打开无障碍总开关。
     * 保留已开启的其它服务（只追加自己），不覆盖。成功/已开启返回 true；无权限或异常返回 false。
     */
    fun enable(ctx: Context): Boolean {
        if (!canSelfEnable(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val set = cur.split(':').filter { it.isNotEmpty() }.toMutableSet()
            if (set.add(SERVICE)) {
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, set.joinToString(":")
                )
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "self-enable a11y ok")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "self-enable a11y failed", t)
            false
        }
    }

    /** 从列表移除本服务（一键关闭）。无权限或异常返回 false。 */
    fun disable(ctx: Context): Boolean {
        if (!canSelfEnable(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val kept = cur.split(':').filter { it.isNotEmpty() && it != SERVICE }
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, kept.joinToString(":")
            )
            Log.i(TAG, "self-disable a11y ok")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "self-disable a11y failed", t)
            false
        }
    }
}
