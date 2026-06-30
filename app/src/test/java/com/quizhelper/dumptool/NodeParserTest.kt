package com.quizhelper.dumptool

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NodeParser] 纯函数单测：视频时间解析 toSec、反馈页正确/你的答案解析 lettersBetween。
 * 这两个直接决定"视频是否判完成""答案是否记对"，是控件直读路径的关键边界。
 */
class NodeParserTest {

    // ---- toSec：MM:SS / HH:MM:SS → 秒 ----

    @Test fun toSec_minutesSeconds() {
        assertEquals(0, NodeParser.toSec("00:00"))
        assertEquals(1162, NodeParser.toSec("19:22"))
        assertEquals(59, NodeParser.toSec("00:59"))
    }

    @Test fun toSec_hoursMinutesSeconds() {
        assertEquals(3723, NodeParser.toSec("1:02:03"))
        assertEquals(95188, NodeParser.toSec("26:26:28"))
    }

    @Test fun toSec_trimsWhitespace() {
        assertEquals(1162, NodeParser.toSec("  19:22 "))
    }

    @Test fun toSec_invalidReturnsMinusOne() {
        assertEquals(-1, NodeParser.toSec(""))
        assertEquals(-1, NodeParser.toSec("abc"))
        assertEquals(-1, NodeParser.toSec("19"))
        assertEquals(-1, NodeParser.toSec("19:2"))      // 秒必须两位
        assertEquals(-1, NodeParser.toSec("1.0x"))      // 倍速不是时间
    }

    // ---- lettersBetween：从"正确答案:X你的答案:Y"切出 A-E 下标 ----

    @Test fun lettersBetween_correctAnswerSingle() {
        val line = "正确答案:C你的答案:A"
        assertEquals(listOf(2), NodeParser.lettersBetween(line, null, "你的"))   // 正确=C
        assertEquals(listOf(0), NodeParser.lettersBetween(line, "你的", null))   // 你的=A
    }

    @Test fun lettersBetween_multiSelect() {
        val line = "正确答案:ABD你的答案:AB"
        assertEquals(listOf(0, 1, 3), NodeParser.lettersBetween(line, null, "你的"))
        assertEquals(listOf(0, 1), NodeParser.lettersBetween(line, "你的", null))
    }

    @Test fun lettersBetween_blankReturnsEmpty() {
        assertEquals(emptyList<Int>(), NodeParser.lettersBetween("", null, "你的"))
    }

    @Test fun lettersBetween_startMarkerMissingReturnsEmpty() {
        // start 分隔串不存在 → 空（你的答案缺失时不能误读成正确答案的字母）
        assertEquals(emptyList<Int>(), NodeParser.lettersBetween("正确答案:C", "你的", null))
    }

    @Test fun lettersBetween_dedupsRepeatedLetters() {
        // distinct：同一字母重复只算一次
        assertEquals(listOf(0), NodeParser.lettersBetween("正确答案:AA", null, null))
    }
}
