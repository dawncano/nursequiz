package com.quizhelper.dumptool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextMatch] 单测——钉住题库模糊匹配的核心算法与阈值边界。
 * 这些是全 App 最脆弱的逻辑：一旦相似度/归一化跑偏，题库查找会"悄无声息"地对不上。
 * AnswerStore 用到的两个阈值：题干去重 simThreshold=0.82、题库同一性 bankSim=0.84。
 */
class TextMatchTest {

    // ---- levenshtein ----

    @Test fun levenshtein_identical_isZero() {
        assertEquals(0, TextMatch.levenshtein("护理学", "护理学"))
    }

    @Test fun levenshtein_emptyOperands() {
        assertEquals(3, TextMatch.levenshtein("", "abc"))
        assertEquals(3, TextMatch.levenshtein("abc", ""))
        assertEquals(0, TextMatch.levenshtein("", ""))
    }

    @Test fun levenshtein_singleEdits() {
        assertEquals(1, TextMatch.levenshtein("kitten", "kittes"))   // 替换
        assertEquals(1, TextMatch.levenshtein("abc", "abcd"))         // 插入
        assertEquals(1, TextMatch.levenshtein("abcd", "abc"))         // 删除
        assertEquals(3, TextMatch.levenshtein("kitten", "sitting"))   // 经典样例
    }

    // ---- normalize ----

    @Test fun normalize_stripsPunctAndWhitespaceAndPipes() {
        assertEquals("正确答案C", TextMatch.normalize("正确答案: C"))
        assertEquals("急诊科2026", TextMatch.normalize("急诊科 | 2026"))
        assertEquals("病人男65岁", TextMatch.normalize("病人，男，65岁。"))
    }

    @Test fun normalize_keepsCjkDigitsLetters() {
        // PaO2 / ABO血型 这类字母数字混排不能被删
        assertEquals("PaO2分析", TextMatch.normalize("PaO2 分析"))
        assertEquals("ABO血型", TextMatch.normalize("（ABO血型）"))
    }

    @Test fun normalize_emptyAndPunctOnly() {
        assertEquals("", TextMatch.normalize(""))
        assertEquals("", TextMatch.normalize("：，。|（） "))
    }

    // ---- similarity ----

    @Test fun similarity_identicalIsOne() {
        assertEquals(1.0, TextMatch.similarity("完全相同的题干", "完全相同的题干"), 1e-9)
    }

    @Test fun similarity_bothEmptyIsOne() {
        assertEquals(1.0, TextMatch.similarity("", ""), 1e-9)
    }

    @Test fun similarity_lengthDiffOver35pct_prunedToZero() {
        // 长度差超过 35% 直接判 0（剪枝），避免长短悬殊误判
        assertEquals(0.0, TextMatch.similarity("短题", "这是一个明显长得多的题干内容啊啊啊"), 1e-9)
    }

    @Test fun similarity_minorNoiseStaysAboveBankThreshold() {
        // 同一题库的标点/竖线噪声变体：归一化后应高于 bankSim=0.84
        val a = TextMatch.normalize("三基分册6外科护理学(烧伤+移植+神外)急诊科2026年考试")
        val b = TextMatch.normalize("三基分册6外科护理学烧伤移植神外 急诊科2026年考试")
        assertTrue("expected >=0.84 but was ${TextMatch.similarity(a, b)}",
            TextMatch.similarity(a, b) >= 0.84)
    }

    @Test fun similarity_differentBanksStayBelowThreshold() {
        // 不同题库：真机库名带括号区分信息(分册号+科室+范围)，差异足够大，应低于 bankSim=0.84 不误并。
        // 注意：若只差"6外科"vs"5内科"两字(相似度≈0.89)反而会被合并——区分全靠括号里的范围词。
        val a = TextMatch.normalize("三基分册6外科护理学(烧伤+移植+神外)急诊科2026年考试")
        val b = TextMatch.normalize("三基分册5基础护理学(医院感染+无菌注射)内科2026年考试")
        assertTrue("expected <0.84 but was ${TextMatch.similarity(a, b)}",
            TextMatch.similarity(a, b) < 0.84)
    }

    @Test fun similarity_longSharedPrefix_differentTail_failsSuffixGate() {
        // 案例组合题：共享很长病史前缀(总长须>60才触发后缀门槛)，只有末尾子问题不同——
        // 后缀门槛必须把它们判为不同题。没有这道门，全串相似度≈0.86 会把它们误并成同一题。
        val prefix = "病人男65岁有高血压及糖尿病病史十余年近期因反复胸闷气短伴心悸就诊查体发现" +
            "心率加快血压偏高双下肢轻度水肿心电图提示ST段缺血改变实验室检查血糖偏高"
        val q1 = prefix + "最可能的诊断是什么"
        val q2 = prefix + "首选的治疗措施是哪项"
        val sim = TextMatch.similarity(TextMatch.normalize(q1), TextMatch.normalize(q2))
        assertTrue("长共享前缀+不同尾巴应低于去重阈值0.82，实际=$sim", sim < 0.82)
    }
}
