package com.quizhelper.dumptool

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AnswerCodec] 纯函数单测：存档答案文字 ↔ 选项下标的转换，答题与考试共用。
 * textsToIdx 是"按文字而非字母点对"的关键——选项乱序也能命中，旧字母数据/选项变了则返回空触发盲选。
 */
class AnswerCodecTest {

    @Test fun idxToLetters_sortsAndMaps() {
        assertEquals("A", AnswerCodec.idxToLetters(listOf(0)))
        assertEquals("AC", AnswerCodec.idxToLetters(listOf(2, 0)))   // 自动排序
        assertEquals("ABD", AnswerCodec.idxToLetters(listOf(3, 0, 1)))
        assertEquals("", AnswerCodec.idxToLetters(emptyList()))
    }

    private val opts = listOf("胃底静脉曲张", "肝硬化", "门脉高压", "脾肿大")

    @Test fun textsToIdx_exactMatchSingle() {
        assertEquals(listOf(2), AnswerCodec.textsToIdx("门脉高压", opts))
    }

    @Test fun textsToIdx_multiPipeSeparated() {
        assertEquals(listOf(0, 3), AnswerCodec.textsToIdx("胃底静脉曲张|脾肿大", opts))
    }

    @Test fun textsToIdx_containsFallback() {
        // 存的文字与选项不完全相等时退包含匹配（存档带标点/选项有前缀等）
        assertEquals(listOf(1), AnswerCodec.textsToIdx("肝硬化。", opts))
    }

    @Test fun textsToIdx_noMatchReturnsEmpty() {
        // 匹配不上（旧字母数据/选项变了）→ 空，调用方据此退回盲选
        assertEquals(emptyList<Int>(), AnswerCodec.textsToIdx("C", opts))
    }

    @Test fun textsToIdx_dedupsAndSkipsBlank() {
        assertEquals(listOf(1), AnswerCodec.textsToIdx("肝硬化||肝硬化", opts))
    }
}
