package com.quizhelper.dumptool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncAnswerGuardTest {
    @Test
    fun currentGeneration_isAccepted() {
        val guard = AsyncAnswerGuard()
        val generation = guard.begin("题目")

        assertTrue(guard.isCurrent("题目", generation))
    }

    @Test
    fun newerGeneration_invalidatesOlderRequest() {
        val guard = AsyncAnswerGuard()
        val old = guard.begin("题目")
        val current = guard.begin("题目")

        assertFalse(guard.isCurrent("题目", old))
        assertTrue(guard.isCurrent("题目", current))
    }

    @Test
    fun invalidateAll_rejectsExistingRequests() {
        val guard = AsyncAnswerGuard()
        val generation = guard.begin("题目")

        guard.invalidateAll()

        assertFalse(guard.isCurrent("题目", generation))
    }
}
