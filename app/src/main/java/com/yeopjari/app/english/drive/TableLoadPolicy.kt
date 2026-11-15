package com.yeopjari.app.english.drive

import com.yeopjari.app.english.core.LearningItem
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.LoadPolicy
import com.yeopjari.app.english.core.LoadRules
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.english.core.Skill
import com.yeopjari.app.english.core.TtsRule

/**
 * 부하 단계별 학습 정책 표 (명세 §4.3).
 *
 * 정차 → 화면 학습 전부 · 여유 → 말하기 중심(토익 답변 45초) · 보통 → 짧은 따라 말하기/문장/단어 ·
 * 집중 → 새 문제 없음·마이크 닫음·짧은 TTS 만 끝냄 · 위험 → 즉시 멈춤.
 *
 * allows: 단어 수 상한은 '모범 답안 길이'로 판단한다. 단, TOEIC_QA 는 열린 답이라 모범 답안 길이 대신
 * 듣기 시간(maxListenSec)으로 제한한다 — 운전 중에도 여유 구간이면 토익 질문을 낼 수 있게.
 */
class TableLoadPolicy : LoadPolicy {

    override fun rules(level: LoadLevel): LoadRules = TABLE.getValue(level)

    override fun allows(item: LearningItem, level: LoadLevel): Boolean {
        val r = rules(level)
        if (!r.newQuestions) return false
        if (!level.isAtMost(item.maxLoad)) return false
        if (item.skill !in r.skills) return false
        val wordsOk = item.skill == Skill.TOEIC_QA || item.answerWords <= r.maxAnswerWords
        return wordsOk
    }

    /** 이 단계에서 이 문항을 들을 시간(초) = min(문항 듣기 시간, 단계 상한). 듣기 불가 단계면 0 */
    fun listenSeconds(item: LearningItem, level: LoadLevel): Int =
        minOf(item.listenSeconds, rules(level).maxListenSec).coerceAtLeast(0)

    companion object {
        private val DRIVING_SKILLS = setOf(
            Skill.VOCAB, Skill.EXPRESSION, Skill.SENTENCE_BUILD, Skill.LISTEN_REPEAT, Skill.MINIMAL_PAIR,
        )

        val TABLE: Map<LoadLevel, LoadRules> = mapOf(
            LoadLevel.PARKED to LoadRules(
                level = LoadLevel.PARKED, newQuestions = true, listening = true, tts = TtsRule.FULL,
                skills = Skill.entries.toSet(), maxAnswerWords = 40, maxListenSec = 60,
                screen = ScreenMode.FULL, onScreenChoices = true, hudHint = false, ambientPulse = true,
            ),
            LoadLevel.RELAXED to LoadRules(
                level = LoadLevel.RELAXED, newQuestions = true, listening = true, tts = TtsRule.FULL,
                skills = DRIVING_SKILLS + Skill.TOEIC_QA, maxAnswerWords = 20, maxListenSec = 45,
                screen = ScreenMode.DRIVE, onScreenChoices = false, hudHint = true, ambientPulse = true,
            ),
            LoadLevel.NORMAL to LoadRules(
                level = LoadLevel.NORMAL, newQuestions = true, listening = true, tts = TtsRule.FULL,
                skills = DRIVING_SKILLS, maxAnswerWords = 12, maxListenSec = 12,
                screen = ScreenMode.DRIVE, onScreenChoices = false, hudHint = true, ambientPulse = true,
            ),
            LoadLevel.FOCUS to LoadRules(
                level = LoadLevel.FOCUS, newQuestions = false, listening = false, tts = TtsRule.FINISH_SHORT,
                skills = emptySet(), maxAnswerWords = 0, maxListenSec = 0,
                screen = ScreenMode.DRIVE, onScreenChoices = false, hudHint = false, ambientPulse = false,
            ),
            LoadLevel.CRITICAL to LoadRules(
                level = LoadLevel.CRITICAL, newQuestions = false, listening = false, tts = TtsRule.STOP_NOW,
                skills = emptySet(), maxAnswerWords = 0, maxListenSec = 0,
                screen = ScreenMode.DRIVE, onScreenChoices = false, hudHint = false, ambientPulse = false,
            ),
        )
    }
}
