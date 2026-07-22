package com.quizhelper.dumptool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerCancellationTest {
    @Test
    fun gate_blocksCallbacksAfterCancel() {
        val gate = SchedulerCancellation()

        assertTrue(gate.isActive())
        gate.cancel()
        assertFalse(gate.isActive())
    }
}
