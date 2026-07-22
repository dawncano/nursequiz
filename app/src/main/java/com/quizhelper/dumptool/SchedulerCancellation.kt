package com.quizhelper.dumptool

internal class SchedulerCancellation {
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }
    fun reactivate() { cancelled = false }
    fun isActive(): Boolean = !cancelled
}
