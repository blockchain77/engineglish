package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.AlignOp
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.Verdict
import com.yeopjari.app.english.speech.SpeechTestItems.callItADay
import com.yeopjari.app.english.speech.SpeechTestItems.commuteToeic
import com.yeopjari.app.english.speech.SpeechTestItems.postpone
import com.yeopjari.app.english.speech.SpeechTestItems.pushMeeting
import com.yeopjari.app.english.speech.SpeechTestItems.rightPair
import com.yeopjari.app.english.speech.SpeechTestItems.rightVocab
import com.yeopjari.app.english.speech.SpeechTestItems.runningLate
import com.yeopjari.app.english.speech.SpeechTestItems.thinkPair
import com.yeopjari.app.english.speech.SpeechTestItems.touchBase
import com.yeopjari.app.english.speech.SpeechTestItems.trafficJam
import com.yeopjari.app.english.speech.SpeechTestItems.turnRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleAnswerJudgeTest {

    private val judge = RuleAnswerJudge(EnglishNormalizer, KoreanL1PronunciationAnalyzer())

    // ───────── 응답 없음 ─────────

    @Test fun emptyOrFillerOnlyIsNoAnswer() {
        for (heard in listOf("", "   ", "um", "uh, hmm...")) {
            val r = judge.judge(pushMeeting, heard)
            assertEquals(heard, Verdict.NO_ANSWER, r.verdict)
            assertEquals(0, r.score)
            assertEquals("잘 안 들렸어요.", r.feedbackKo)
        }
    }

    @Test fun koreanOnlyAsksForEnglish() {
        val r = judge.judge(postpone, "연기하다")
        assertEquals(Verdict.NO_ANSWER, r.verdict)
        assertEquals("영어로 답해 주세요.", r.feedbackKo)
    }

    @Test fun koreanFillerIsIgnored() {
        assertEquals(Verdict.CORRECT, judge.judge(postpone, "음 postpone").verdict)
    }

    // ───────── VOCAB ─────────

    @Test fun vocabExactAndArticleAndAlternatives() {
        assertEquals(100, judge.judge(postpone, "Postpone.").score)
        assertEquals(Verdict.CORRECT, judge.judge(postpone, "to postpone").verdict)
        val alt = judge.judge(postpone, "delay")
        assertEquals(Verdict.CORRECT, alt.verdict)
        assertEquals("delay", alt.matchedAnswer)
        assertEquals(Verdict.CORRECT, judge.judge(trafficJam, "a traffic jam").verdict)
    }

    @Test fun vocabContainedWithFewExtraWordsIsCorrect() {
        val r = judge.judge(postpone, "it is postpone")
        assertEquals(Verdict.CORRECT, r.verdict)
        assertEquals(90, r.score)
        val long = judge.judge(postpone, "I think the right answer is postpone")
        assertEquals(Verdict.PARTIAL, long.verdict)
    }

    @Test fun vocabMorphologicalVariantIsPartial() {
        val r = judge.judge(postpone, "postponed")
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertEquals(70, r.score)
        assertTrue(r.feedbackKo, r.feedbackKo.startsWith("거의 맞았어요."))
    }

    @Test fun vocabL1ConfusionIsPartialWithPronIssue() {
        val r = judge.judge(rightVocab, "light")
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertEquals(70, r.score)
        assertEquals(PronIssueType.R_L, r.pronIssues.single().type)
        assertEquals("거의 맞았어요. R 발음이 L로 들렸어요.", r.feedbackKo)
    }

    @Test fun vocabWrongWord() {
        val r = judge.judge(postpone, "prepare")
        assertEquals(Verdict.WRONG, r.verdict)
        assertTrue(r.score <= 40)
        assertEquals("아쉬워요. 이렇게 말해요.", r.feedbackKo)
    }

    @Test fun vocabSelfCorrectionJudgesLastAttempt() {
        assertEquals(Verdict.CORRECT, judge.judge(postpone, "propose, sorry, postpone").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(postpone, "prepare... I mean postpone").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(postpone, "post... postpone").verdict) // 말 더듬기
    }

    @Test fun vocabDistractorIsWrongEvenIfItContainsAShortAnswer() {
        val jam = SpeechTestItems.item(
            "toe.b2.01", com.yeopjari.app.english.core.Skill.VOCAB, listOf("traffic jam", "traffic"), listOf("traffic", "jam"),
            distractors = listOf("traffic light", "traffic sign"),
        )
        assertEquals(Verdict.CORRECT, judge.judge(jam, "traffic jam").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(jam, "It's a traffic jam").verdict)
        assertEquals(Verdict.WRONG, judge.judge(jam, "traffic light").verdict)
        assertEquals(Verdict.WRONG, judge.judge(jam, "a traffic sign").verdict)
        // 오답 보기가 아니어도 내용어가 붙으면 정답으로 치지 않는다
        assertEquals(Verdict.PARTIAL, judge.judge(jam, "traffic police").verdict)
    }

    @Test fun vocabDistractorThatIsAnInflectionIsWrong() {
        val relaxing = SpeechTestItems.item(
            "toe.b3.03", com.yeopjari.app.english.core.Skill.VOCAB, listOf("relaxing"), listOf("relaxing"), distractors = listOf("relaxed", "relating"),
        )
        assertEquals(Verdict.WRONG, judge.judge(relaxing, "relaxed").verdict)
        assertEquals(Verdict.PARTIAL, judge.judge(relaxing, "relax").verdict) // 오답 보기가 아닌 형태 변화는 부분 정답
    }

    @Test fun shortAnswerCannotSwallowADistractorSentence() {
        val check = SpeechTestItems.item(
            "day.i1.02", com.yeopjari.app.english.core.Skill.EXPRESSION,
            listOf("Could I get the check, please?", "Can I get the check, please?", "Check, please."), listOf("check"),
            distractors = listOf("Could I get the chick, please?", "Could I give the check, please?"),
        )
        assertEquals(Verdict.WRONG, judge.judge(check, "Could I give the check, please?").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(check, "Check, please.").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(check, "Could I give the check, sorry, could I get the check, please?").verdict)
    }

    @Test fun vocabHomophoneCountsAsCorrect() {
        assertEquals(Verdict.CORRECT, judge.judge(rightVocab, "write").verdict)
    }

    // ───────── EXPRESSION · SENTENCE_BUILD ─────────

    @Test fun sentenceExactIsPerfect() {
        val r = judge.judge(pushMeeting, "Can we push the meeting to tomorrow?")
        assertEquals(Verdict.CORRECT, r.verdict)
        assertEquals(100, r.score)
        assertEquals("완벽해요!", r.feedbackKo)
        assertTrue(r.alignment.all { it.op == AlignOp.MATCH })
        assertTrue(r.missingKeywords.isEmpty())
    }

    @Test fun sentenceAcceptsAnyAllowedAnswer() {
        val a = judge.judge(pushMeeting, "Could we move the meeting to tomorrow")
        assertEquals(Verdict.CORRECT, a.verdict)
        assertEquals("Could we move the meeting to tomorrow?", a.matchedAnswer)
        assertEquals(Verdict.CORRECT, judge.judge(pushMeeting, "can we postpone the meeting until tomorrow").verdict)
    }

    @Test fun keywordsOnlyRequiredWhenInMatchedAnswer() {
        // 'call', 'day' 는 첫 정답의 키워드지만 두 번째 정답에는 없다 → 불이익 없음
        val r = judge.judge(callItADay, "Let's wrap it up for today.")
        assertEquals(Verdict.CORRECT, r.verdict)
        assertTrue(r.missingKeywords.isEmpty())
    }

    @Test fun contractionVariantsAreCorrect() {
        assertEquals(Verdict.CORRECT, judge.judge(runningLate, "I am running late").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(runningLate, "I'm gonna be late").verdict)
    }

    @Test fun missingKeywordCapsScoreAndGivesPartial() {
        val r = judge.judge(pushMeeting, "Can we push the meeting to")
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertTrue(r.score <= 84)
        assertEquals(listOf("tomorrow"), r.missingKeywords)
        assertEquals("거의 맞았어요. ‘tomorrow’ 부분이 빠졌어요.", r.feedbackKo)
    }

    @Test fun halfTheKeywordsIsAtLeastPartial() {
        val r = judge.judge(pushMeeting, "meeting")
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertTrue(r.score < 50)
    }

    @Test fun keywordMatchAllowsInflection() {
        val r = judge.judge(pushMeeting, "Can we push the meetings to tomorrow")
        assertTrue(r.missingKeywords.isEmpty())
        assertEquals(Verdict.CORRECT, r.verdict)
    }

    @Test fun fillersDoNotHurt() {
        val r = judge.judge(pushMeeting, "Um, can we, uh, push the meeting to tomorrow?")
        assertEquals(Verdict.CORRECT, r.verdict)
        assertEquals(100, r.score)
    }

    @Test fun restartIsJudgedOnLastAttempt() {
        assertEquals(Verdict.CORRECT, judge.judge(pushMeeting, "Can we push the... can we push the meeting to tomorrow?").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(pushMeeting, "Can I, could we move the meeting to tomorrow?").verdict)
    }

    @Test fun selfCorrectionUsesLastAttempt() {
        assertEquals(
            Verdict.CORRECT,
            judge.judge(pushMeeting, "Can we push the meeting to Monday? Sorry, I mean, can we push the meeting to tomorrow?").verdict,
        )
        assertEquals(Verdict.CORRECT, judge.judge(runningLate, "I'm running fast, I mean, I'm running late").verdict)
        // 맞게 말했다가 틀리게 고치면 마지막(틀린) 시도로 채점
        assertNotEquals(Verdict.CORRECT, judge.judge(runningLate, "I'm running late, I mean, I'm running fast").verdict)
        // 끝에 붙은 sorry 는 군더더기
        assertEquals(Verdict.CORRECT, judge.judge(pushMeeting, "Can we push the meeting to tomorrow, sorry").verdict)
        // 정답 안의 sorry 는 그대로
        val sorry = SpeechTestItems.item("day.b1.09", com.yeopjari.app.english.core.Skill.EXPRESSION, listOf("Sorry, I'm late."), listOf("late"))
        assertEquals(Verdict.CORRECT, judge.judge(sorry, "Sorry, I'm late").verdict)
    }

    @Test fun closerToADistractorIsWrong() {
        // 어순이 바뀐 문장 — 정렬 점수만으로는 PARTIAL 이지만 오답 보기와 똑같다
        assertEquals(Verdict.WRONG, judge.judge(pushMeeting, "Can we push tomorrow to the meeting?").verdict)
        assertEquals(Verdict.WRONG, judge.judge(pushMeeting, "Can we pull the meeting tomorrow?").verdict)
        // 'to' 하나 빠진 정답은 오답 보기보다 정답에 더 가깝다
        assertEquals(Verdict.CORRECT, judge.judge(pushMeeting, "Can we push the meeting tomorrow?").verdict)
    }

    @Test fun wrongSentence() {
        val r = judge.judge(pushMeeting, "I like pizza very much")
        assertEquals(Verdict.WRONG, r.verdict)
        assertEquals("아쉬워요. 이렇게 말해요.", r.feedbackKo)
    }

    @Test fun pronunciationSlipDowngradesCorrectToPartial() {
        val r = judge.judge(turnRight, "please turn light here")
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertTrue(r.score <= 84)
        assertEquals(PronIssueType.R_L, r.pronIssues.first().type)
        assertEquals("거의 맞았어요. R 발음이 L로 들렸어요.", r.feedbackKo)
    }

    @Test fun l1SwapInsideKeywordGivesPronFeedback() {
        // 데모 L1_ERROR: tomorrow → tomollow (r→l)
        val r = judge.judge(pushMeeting, "Can we push the meeting to tomollow?")
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertEquals(PronIssueType.R_L, r.pronIssues.first().type)
        assertTrue(r.feedbackKo, r.feedbackKo.contains("R 발음이 L로 들렸어요"))
    }

    @Test fun partialFeedbackPointsAtFirstDifference() {
        val r = judge.judge(runningLate, "I am walking late")
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertEquals("거의 맞았어요. ‘running’ 대신 ‘walking’라고 들렸어요.", r.feedbackKo)
    }

    // ───────── LISTEN_REPEAT ─────────

    @Test fun listenRepeatThresholds() {
        assertEquals(Verdict.CORRECT, judge.judge(touchBase, "let's touch base next week").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(touchBase, "let us touch base next week").verdict)
        val partial = judge.judge(touchBase, "touch base next week")
        assertEquals(Verdict.PARTIAL, partial.verdict)
        assertEquals(67, partial.score)
        assertEquals(Verdict.WRONG, judge.judge(touchBase, "next week").verdict)
    }

    @Test fun listenRepeatMissingKeyWordIsPartial() {
        // 점수는 83 이지만 핵심어 touch 가 빠졌다
        val r = judge.judge(touchBase, "Let's base next week.")
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertTrue(r.score <= 79)
        assertEquals(listOf("touch"), r.missingKeywords)
    }

    @Test fun unrelatedAnswersCarryNoPronIssues() {
        val r = judge.judge(pushMeeting, "I really like rice and fried fish")
        assertEquals(Verdict.WRONG, r.verdict)
        assertTrue(r.pronIssues.isEmpty())
        val seat = SpeechTestItems.item(
            "pron.fcd.03", com.yeopjari.app.english.core.Skill.MINIMAL_PAIR, listOf("seat"), listOf("seat"),
            promptEn = "seat", contrast = "sea", pronFocus = PronIssueType.FINAL_CONSONANT_DROP,
        )
        val long = judge.judge(seat, "I have no idea about that.")
        assertEquals(Verdict.WRONG, long.verdict)
        assertTrue(long.pronIssues.isEmpty())
        assertEquals(Verdict.WRONG, judge.judge(seat, "sea").verdict)
        assertEquals(PronIssueType.FINAL_CONSONANT_DROP, judge.judge(seat, "sea").pronIssues.single().type)
    }

    // ───────── TOEIC_QA ─────────

    @Test fun toeicFullAnswerIsCorrect() {
        val r = judge.judge(commuteToeic, "I usually drive to work. It takes about 30 minutes, and I listen to English lessons on the way.")
        assertEquals(Verdict.CORRECT, r.verdict)
        assertEquals(100, r.score)
        assertTrue(r.grammarNotesKo.isEmpty())
        assertTrue(r.alignment.isEmpty())
    }

    @Test fun toeicTooShortIsNoAnswer() {
        assertEquals(Verdict.NO_ANSWER, judge.judge(commuteToeic, "I drive.").verdict)
    }

    @Test fun toeicScoreFormula() {
        // k = 1/4 (drive), l = 9/15, g = 1 → 0.45*0.25 + 0.35*0.6 + 0.2 = 0.5225 → 52
        val r = judge.judge(commuteToeic, "I drive to my office every day by car")
        assertEquals(52, r.score)
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertTrue(r.missingKeywords.containsAll(listOf("usually", "minutes", "work")))
        assertTrue(r.feedbackKo.startsWith("괜찮아요."))
    }

    @Test fun toeicGrammarIssuesLowerScore() {
        val clean = judge.judge(commuteToeic, "I usually drive to work and it takes about thirty minutes because the road is busy in the morning")
        val messy = judge.judge(commuteToeic, "I am agree I usually drive to work and she take about thirty minutes because the road is more busier")
        assertTrue(clean.grammarNotesKo.isEmpty())
        assertTrue(messy.grammarNotesKo.size >= 2)
        assertTrue(messy.score < clean.score)
    }

    @Test fun toeicLowScoreIsWrong() {
        val r = judge.judge(commuteToeic, "I like pizza and pasta")
        assertEquals(Verdict.WRONG, r.verdict)
    }

    @Test fun grammarHeuristics() {
        assertTrue(ToeicGrammar.check(EnglishNormalizer.tokens("I am agree with that")).any { it.contains("I am agree") })
        assertTrue(ToeicGrammar.check(EnglishNormalizer.tokens("She go to work every day")).any { it.contains("she goes") })
        assertTrue(ToeicGrammar.check(EnglishNormalizer.tokens("He always take the bus")).any { it.contains("takes") })
        assertTrue(ToeicGrammar.check(EnglishNormalizer.tokens("I did went there")).any { it.contains("did go") })
        assertTrue(ToeicGrammar.check(EnglishNormalizer.tokens("Did you went there")).any { it.contains("did go") })
        assertTrue(ToeicGrammar.check(EnglishNormalizer.tokens("This way is more better")).any { it.contains("more better") })
        assertTrue(ToeicGrammar.check(EnglishNormalizer.tokens("Think it is good")).any { it.contains("주어") })
        // 맞는 문장은 메모 없음
        for (ok in listOf(
            "I agree with that", "She goes to work every day", "Does she like coffee", "Let it go", "I am going to work",
            "I did not go there", "This way is better", "I think it is good", "Make it work", "He can drive",
        )) {
            assertTrue(ok, ToeicGrammar.check(EnglishNormalizer.tokens(ok)).isEmpty())
        }
    }

    // ───────── MINIMAL_PAIR ─────────

    @Test fun minimalPairTargetIsCorrect() {
        assertEquals(Verdict.CORRECT, judge.judge(rightPair, "right").verdict)
        assertEquals(Verdict.CORRECT, judge.judge(rightPair, "Write.").verdict) // 동음이의어
        assertEquals(Verdict.CORRECT, judge.judge(rightPair, "light... right").verdict) // 마지막 시도
        assertEquals(Verdict.CORRECT, judge.judge(rightPair, "right, not light").verdict)
    }

    @Test fun minimalPairContrastIsWrongWithFocusIssue() {
        val r = judge.judge(rightPair, "light")
        assertEquals(Verdict.WRONG, r.verdict)
        val issue = r.pronIssues.single()
        assertEquals(PronIssueType.R_L, issue.type)
        assertEquals(0.9f, issue.confidence, 1e-6f)
        assertEquals("right", issue.drill?.target)
        assertEquals("light", issue.drill?.contrast)
        assertEquals("아쉬워요. R 발음이 L로 들렸어요.", r.feedbackKo)
        val th = judge.judge(thinkPair, "sink")
        assertEquals(PronIssueType.TH_S, th.pronIssues.single().type)
        assertEquals("아쉬워요. TH 발음이 S로 들렸어요.", th.feedbackKo)
    }

    @Test fun minimalPairSimilarIsPartialOtherIsWrong() {
        val partial = judge.judge(thinkPair, "tink")
        assertEquals(Verdict.PARTIAL, partial.verdict)
        assertEquals(PronIssueType.TH_S, partial.pronIssues.first().type)
        assertEquals(Verdict.WRONG, judge.judge(rightPair, "banana").verdict)
    }

    // ───────── 보기 고르기 ─────────

    @Test fun judgeChoice() {
        val ok = judge.judgeChoice(postpone, "postpone")
        assertEquals(Verdict.CORRECT, ok.verdict)
        assertEquals(100, ok.score)
        val bad = judge.judgeChoice(postpone, "prepare")
        assertEquals(Verdict.WRONG, bad.verdict)
        assertEquals(0, bad.score)
        assertEquals("postpone", bad.matchedAnswer)
        assertEquals(Verdict.CORRECT, judge.judgeChoice(pushMeeting, "Could we move the meeting to tomorrow?").verdict)
        assertFalse(judge.judgeChoice(pushMeeting, "Can we pull the meeting tomorrow?").verdict == Verdict.CORRECT)
    }

    // ───────── 정렬 ─────────

    @Test fun alignmentWeighsFunctionWordsHalf() {
        val missingThe = WordAligner.align(listOf("push", "the", "meeting"), listOf("push", "meeting"))
        assertEquals(0.5, missingThe.errors, 1e-9)
        assertEquals(80, missingThe.score) // 1 - 0.5/2.5
        assertEquals(AlignOp.DEL, missingThe.ops[1].op)
        val sub = WordAligner.align(listOf("turn", "right", "here"), listOf("turn", "light", "here"))
        assertEquals(AlignOp.SUB, sub.ops[1].op)
        assertEquals("right", sub.ops[1].expected)
        assertEquals("light", sub.ops[1].heard)
    }
}
