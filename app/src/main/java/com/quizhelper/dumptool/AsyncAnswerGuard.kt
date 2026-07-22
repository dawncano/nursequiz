package com.quizhelper.dumptool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class AsyncAnswerGuard {
    private val clock = AtomicLong(0L)
    private val generations = ConcurrentHashMap<String, Long>()

    fun begin(key: String): Long {
        val generation = clock.incrementAndGet()
        generations[key] = generation
        return generation
    }

    fun isCurrent(key: String, generation: Long): Boolean = generations[key] == generation

    fun invalidateAll() = generations.clear()
}
