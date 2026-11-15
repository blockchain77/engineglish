package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.AlignOp
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.WordAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanL1PronunciationAnalyzerTest {

    private val analyzer = KoreanL1PronunciationAnalyzer()

    private fun type(expected: String, heard: String): PronIssueType? = analyzer.detect(expected, heard)?.type

    @Test fun rL() {
        assertEquals(PronIssueType.R_L, type("right", "light"))
        assertEquals(PronIssueType.R_L, type("correct", "collect"))
        assertEquals(PronIssueType.R_L, type("pray", "play"))
        assertEquals(PronIssueType.R_L, type("road", "load"))
        assertEquals(PronIssueType.R_L, type("wrong", "long"))
        assertEquals(0.8f, analyzer.detect("right", "light")!!.confidence, 1e-6f)
    }

    @Test fun fP() {
        assertEquals(PronIssueType.F_P, type("fan", "pan"))
        assertEquals(PronIssueType.F_P, type("coffee", "copy"))
        assertEquals(PronIssueType.F_P, type("file", "pile"))
        assertEquals(PronIssueType.F_P, type("phone", "pone"))
    }

    @Test fun vB() {
        assertEquals(PronIssueType.V_B, type("very", "berry"))
        assertEquals(PronIssueType.V_B, type("vest", "best"))
        val vote = analyzer.detect("vote", "boat")!!
        assertEquals(PronIssueType.V_B, vote.type)
        assertEquals(0.7f, vote.confidence, 1e-6f) // 모음 철자 차이 → 느슨한 일치
    }

    @Test fun thS() {
        assertEquals(PronIssueType.TH_S, type("think", "sink"))
        assertEquals(PronIssueType.TH_S, type("thank", "sank"))
        assertEquals(PronIssueType.TH_S, type("mouth", "mouse"))
        assertEquals(PronIssueType.TH_S, type("three", "tree"))
    }

    @Test fun thD() {
        assertEquals(PronIssueType.TH_D, type("they", "day"))
        assertEquals(PronIssueType.TH_D, type("then", "den"))
        assertEquals(PronIssueType.TH_D, type("there", "dare"))
    }

    @Test fun zJ() {
        assertEquals(PronIssueType.Z_J, type("zest", "jest"))
        assertEquals(PronIssueType.Z_J, type("zealous", "jealous"))
        assertEquals(PronIssueType.Z_J, type("zoo", "joo"))
        assertEquals(PronIssueType.Z_J, type("zone", "joan"))
    }

    @Test fun shS() {
        assertEquals(PronIssueType.SH_S, type("she", "see"))
        assertEquals(PronIssueType.SH_S, type("ship", "sip"))
        assertEquals(PronIssueType.SH_S, type("shell", "sell"))
    }

    @Test fun finalConsonantDrop() {
        val book = analyzer.detect("book", "boo")!!
        assertEquals(PronIssueType.FINAL_CONSONANT_DROP, book.type)
        assertEquals(0.6f, book.confidence, 1e-6f)
        assertEquals(PronIssueType.FINAL_CONSONANT_DROP, type("worked", "work"))
        assertEquals(PronIssueType.FINAL_CONSONANT_DROP, type("liked", "like"))
        assertEquals(PronIssueType.FINAL_CONSONANT_DROP, type("want", "wan"))
    }

    @Test fun partialSwapsInsideDoubleLettersAreDetected() {
        assertEquals(PronIssueType.R_L, type("tomorrow", "tomolrow")) // rr 중 하나만 l
        assertEquals(PronIssueType.R_L, type("sorry", "solry"))
        assertEquals(PronIssueType.R_L, type("already", "alleady"))
        assertEquals(PronIssueType.F_P, type("office", "opfice"))
        assertEquals(PronIssueType.F_P, type("efficient", "epficient"))
        assertEquals(PronIssueType.R_L, type("warranty", "walranty"))
    }

    @Test fun notPronunciationIssues() {
        assertNull(type("cats", "cat")) // 복수 -s 는 문법
        assertNull(type("meeting", "meetings"))
        assertNull(type("right", "write")) // 동음이의어
        assertNull(type("apple", "banana"))
        assertNull(type("and", "an"))
        assertNull(type("right", "right"))
        assertNull(type("tomorrow", "today"))
        assertNull(type("seat", "sea")?.takeIf { it != PronIssueType.FINAL_CONSONANT_DROP }) // 끝소리 탈락이지 TH/S 가 아님
        assertNull(type("card", "car")?.takeIf { it != PronIssueType.FINAL_CONSONANT_DROP })
        assertNull(type("warranty", "warrant")) // y 는 모음 소리
    }

    @Test fun everyListedPairIsDetectedAsItsType() {
        for (t in PronIssueType.entries) {
            val pairs = analyzer.pairs(t)
            assertTrue("$t has ≥ 4 pairs", pairs.size >= 4)
            for (p in pairs) assertEquals("${p.target} / ${p.contrast}", t, type(p.target, p.contrast))
            assertTrue(analyzer.tip(t).isNotBlank())
        }
    }

    @Test fun analyzeUsesSubstitutionsHighestConfidenceFirstMaxTwo() {
        val alignment = listOf(
            WordAlign("they", "day", AlignOp.SUB), // 0.7
            WordAlign("very", "very", AlignOp.MATCH),
            WordAlign("right", "light", AlignOp.SUB), // 0.8
            WordAlign("book", "boo", AlignOp.SUB), // 0.6
            WordAlign("fan", null, AlignOp.DEL),
        )
        val issues = analyzer.analyze(SpeechTestItems.turnRight, alignment)
        assertEquals(2, issues.size)
        assertEquals(PronIssueType.R_L, issues[0].type)
        assertEquals(PronIssueType.TH_D, issues[1].type)
        assertEquals("right", issues[0].drill?.target)
        assertEquals("light", issues[0].drill?.contrast)
    }

    @Test fun analyzeReportsEachExpectedWordOnce() {
        val alignment = listOf(
            WordAlign("right", "light", AlignOp.SUB),
            WordAlign("right", "light", AlignOp.SUB),
        )
        assertEquals(1, analyzer.analyze(SpeechTestItems.turnRight, alignment).size)
    }

    @Test fun feedbackSentencesNameTheSounds() {
        fun spoken(e: String, h: String) = PronFeedback.spokenKo(analyzer.detect(e, h)!!)
        assertEquals("R 발음이 L로 들렸어요", spoken("right", "light"))
        assertEquals("L 발음이 R로 들렸어요", spoken("light", "right"))
        assertEquals("F 발음이 P로 들렸어요", spoken("coffee", "copy"))
        assertEquals("V 발음이 B로 들렸어요", spoken("very", "berry"))
        assertEquals("TH 발음이 S로 들렸어요", spoken("think", "sink"))
        assertEquals("TH 발음이 T로 들렸어요", spoken("three", "tree"))
        assertEquals("TH 발음이 D로 들렸어요", spoken("they", "day"))
        assertEquals("Z 발음이 J로 들렸어요", spoken("zest", "jest"))
        assertEquals("SH 발음이 S로 들렸어요", spoken("she", "see"))
        assertEquals("끝소리가 약하게 들렸어요", spoken("book", "boo"))
        assertEquals("R 발음이 L로 들렸어요 (right → light)", PronFeedback.describeKo(analyzer.detect("right", "light")!!))
    }

    @Test fun preferredFocusBreaksTies() {
        assertNotNull(analyzer.detect("think", "tink", PronIssueType.TH_S))
        assertEquals(PronIssueType.TH_S, analyzer.detect("think", "tink", PronIssueType.TH_S)!!.type)
    }
}
