package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.ScreenMode
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.TtsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableLoadPolicyTest {

    private val policy = TableLoadPolicy()

    @Test fun tableMatchesSpec() {
        val parked = policy.rules(LoadLevel.PARKED)
        assertTrue(parked.newQuestions && parked.listening && parked.onScreenChoices && parked.ambientPulse)
        assertFalse(parked.hudHint)
        assertEquals(TtsRule.FULL, parked.tts)
        assertEquals(Skill.entries.toSet(), parked.skills)
        assertEquals(40, parked.maxAnswerWords)
        assertEquals(60, parked.maxListenSec)
        assertEquals(ScreenMode.FULL, parked.screen)

        val relaxed = policy.rules(LoadLevel.RELAXED)
        assertTrue(Skill.TOEIC_QA in relaxed.skills)
        assertEquals(20, relaxed.maxAnswerWords)
        assertEquals(45, relaxed.maxListenSec)
        assertEquals(ScreenMode.DRIVE, relaxed.screen)
        assertFalse(relaxed.onScreenChoices)
        assertTrue(relaxed.hudHint && relaxed.ambientPulse)

        val normal = policy.rules(LoadLevel.NORMAL)
        assertEquals(setOf(Skill.VOCAB, Skill.EXPRESSION, Skill.SENTENCE_BUILD, Skill.LISTEN_REPEAT, Skill.MINIMAL_PAIR), normal.skills)
        assertEquals(12, normal.maxAnswerWords)
        assertEquals(12, normal.maxListenSec)
        assertTrue(normal.newQuestions && normal.listening)

        val focus = policy.rules(LoadLevel.FOCUS)
        assertFalse(focus.newQuestions || focus.listening || focus.hudHint || focus.ambientPulse)
        assertEquals(TtsRule.FINISH_SHORT, focus.tts)
        assertTrue(focus.skills.isEmpty())

        val critical = policy.rules(LoadLevel.CRITICAL)
        assertFalse(critical.newQuestions || critical.listening)
        assertEquals(TtsRule.STOP_NOW, critical.tts)
        for (lv in LoadLevel.entries) assertEquals(lv, policy.rules(lv).level)
    }

    @Test fun allowsNormalItemsUpToNormalOnly() {
        val vocab = item(skill = Skill.VOCAB, answer = "postpone")
        assertTrue(policy.allows(vocab, LoadLevel.PARKED))
        assertTrue(policy.allows(vocab, LoadLevel.RELAXED))
        assertTrue(policy.allows(vocab, LoadLevel.NORMAL))
        assertFalse(policy.allows(vocab, LoadLevel.FOCUS))
        assertFalse(policy.allows(vocab, LoadLevel.CRITICAL))
    }

    @Test fun toeicIsRelaxedOrParkedAndJudgedByListenWindowNotModelLength() {
        val toeic = item(
            skill = Skill.TOEIC_QA, maxLoad = LoadLevel.RELAXED, listenSec = 40,
            answer = "I think the best way is to practice a little every day. For example, I practice English during my commute, so I never skip a day.",
        )
        assertTrue(toeic.answerWords > 20)
        assertTrue(policy.allows(toeic, LoadLevel.RELAXED))
        assertTrue(policy.allows(toeic, LoadLevel.PARKED))
        assertFalse(policy.allows(toeic, LoadLevel.NORMAL))
        assertEquals(40, policy.listenSeconds(toeic, LoadLevel.RELAXED))
        assertEquals(0, policy.listenSeconds(toeic, LoadLevel.FOCUS))
    }

    @Test fun maxLoadAndAnswerLengthGates() {
        val parkedOnly = item(skill = Skill.SENTENCE_BUILD, maxLoad = LoadLevel.PARKED, answer = "Could you send me the report by Friday?")
        assertTrue(policy.allows(parkedOnly, LoadLevel.PARKED))
        assertFalse(policy.allows(parkedOnly, LoadLevel.RELAXED))

        val longSentence = item(
            skill = Skill.SENTENCE_BUILD, maxLoad = LoadLevel.NORMAL,
            answer = "Could you please send me the final version of the report by Friday afternoon?",
        )
        assertEquals(14, longSentence.answerWords)
        assertFalse("12단어 넘는 문장은 보통 단계에서 내지 않는다", policy.allows(longSentence, LoadLevel.NORMAL))
        assertTrue(policy.allows(longSentence, LoadLevel.RELAXED))

        val listen = item(skill = Skill.LISTEN_REPEAT, answer = "Let's touch base next week.", listenSec = 10)
        assertEquals(10, policy.listenSeconds(listen, LoadLevel.NORMAL))
        val sentence = item(skill = Skill.SENTENCE_BUILD, answer = "Can we push the meeting to tomorrow?")
        assertEquals(12, policy.listenSeconds(sentence, LoadLevel.NORMAL))
        assertEquals(12, policy.listenSeconds(sentence, LoadLevel.PARKED))
    }
}
