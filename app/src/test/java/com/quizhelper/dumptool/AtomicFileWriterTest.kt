package com.quizhelper.dumptool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun write_success_replacesTarget() {
        val target = folder.newFile("bank.json").also { it.writeText("old") }

        AtomicFileWriter.write(target) { it.writeText("new") }

        assertEquals("new", target.readText())
    }

    @Test
    fun write_failure_preservesPreviousTarget() {
        val target = folder.newFile("bank.json").also { it.writeText("old") }

        try {
            AtomicFileWriter.write(target) {
                it.writeText("partial")
                throw IllegalStateException("simulated failure")
            }
        } catch (_: IllegalStateException) {
        }

        assertEquals("old", target.readText())
        assertTrue(target.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
