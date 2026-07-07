package com.quizhelper.dumptool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast

// toast 专用的主线程 Handler：让 Context.toast 可在任意线程调用而不必每个类各存一个
// Handler(Looper.getMainLooper())。需要自持延时任务的类(悬浮窗收球动画等)仍自建自己的 Handler。
private val toastHandler = Handler(Looper.getMainLooper())

/** 统一 toast：内部 post 到主线程，调用方无需持有 Handler、也不必已在主线程。
 *  默认 LENGTH_LONG(与原服务/悬浮窗多数提示一致)；需要短提示传 [Toast.LENGTH_SHORT]。 */
fun Context.toast(msg: String, length: Int = Toast.LENGTH_LONG) {
    toastHandler.post { Toast.makeText(this, msg, length).show() }
}

/** SDK26+ 幂等确保通知渠道存在，返回渠道 id 便于直接接 `Notification.Builder(this, ensureChannel(...))`。
 *  收敛了停止提醒与考试前台服务两处各写一遍的 SDK 判断 + createNotificationChannel。 */
fun Context.ensureChannel(id: String, name: String, importance: Int): String {
    if (Build.VERSION.SDK_INT >= 26) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(id, name, importance))
    }
    return id
}
