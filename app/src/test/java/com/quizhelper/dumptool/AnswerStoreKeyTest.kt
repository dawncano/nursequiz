package com.quizhelper.dumptool

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerStoreKeyTest {
    @Test
    fun editKeys_normalizesOldAndNewQuestions() {
        val keys = AnswerStore.editKeys("  哪种药物？  ", "哪种药物!")

        assertEquals("哪种药物", keys.oldKey)
        assertEquals("哪种药物", keys.newKey)
    }

    @Test
    fun editKeys_blankNewQuestion_keepsNormalizedOldQuestion() {
        val keys = AnswerStore.editKeys("原题？", "   ")

        assertEquals("原题", keys.oldKey)
        assertEquals("原题", keys.newKey)
    }
}
