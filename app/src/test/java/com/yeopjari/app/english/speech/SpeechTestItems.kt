package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.LearningItem
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.Skill
import com.yeopjari.app.english.core.Track

/** 말하기 테스트용 문항 (명세 §11.2 예시 기반) */
object SpeechTestItems {

    fun item(
        id: String,
        skill: Skill,
        answers: List<String>,
        keywords: List<String> = emptyList(),
        promptEn: String? = null,
        minWords: Int = 0,
        contrast: String? = null,
        pronFocus: PronIssueType? = null,
        maxLoad: LoadLevel = LoadLevel.NORMAL,
        distractors: List<String> = emptyList(),
    ) = LearningItem(
        id = id, track = Track.BUSINESS, level = Level.BEGINNER, unit = 1, skill = skill,
        promptKo = "테스트 문항", promptEn = promptEn, answers = answers, keywords = keywords,
        hintWord = answers.firstOrNull()?.split(' ')?.firstOrNull(), meaningKo = "뜻", maxLoad = maxLoad,
        minWords = minWords, contrast = contrast, pronFocus = pronFocus, distractors = distractors,
    )

    val postpone = item("biz.b1.01", Skill.VOCAB, listOf("postpone", "delay"), listOf("postpone"))
    val rightVocab = item("day.b1.05", Skill.VOCAB, listOf("right"), listOf("right"))
    val trafficJam = item("day.b1.06", Skill.VOCAB, listOf("traffic jam"), listOf("traffic"))

    val pushMeeting = item(
        "biz.b1.06", Skill.SENTENCE_BUILD,
        listOf("Can we push the meeting to tomorrow?", "Could we move the meeting to tomorrow?", "Can we postpone the meeting until tomorrow?"),
        listOf("meeting", "tomorrow"),
        distractors = listOf("Can we pull the meeting tomorrow?", "Can we push tomorrow to the meeting?"),
    )
    val callItADay = item("biz.i1.05", Skill.EXPRESSION, listOf("Let's call it a day.", "Let's wrap it up for today."), listOf("call", "day"))
    val runningLate = item("day.b1.04", Skill.EXPRESSION, listOf("I'm running late.", "I'm running a little late.", "I'm going to be late."), listOf("late"))
    val turnRight = item("day.b1.07", Skill.SENTENCE_BUILD, listOf("Please turn right here."), listOf("turn"))

    val touchBase = item(
        "biz.b2.09", Skill.LISTEN_REPEAT, listOf("Let's touch base next week.", "Let us touch base next week."),
        listOf("touch", "base"), promptEn = "Let's touch base next week.",
    )

    val commuteToeic = item(
        "toe.b1.07", Skill.TOEIC_QA,
        listOf("I usually drive to work. It takes about thirty minutes, and I listen to English lessons on the way."),
        listOf("usually", "drive", "minutes", "work"),
        promptEn = "How do you usually get to work, and how long does it take?", minWords = 15, maxLoad = LoadLevel.RELAXED,
    )

    val rightPair = item(
        "biz.b1.10", Skill.MINIMAL_PAIR, listOf("right"), listOf("right"), promptEn = "right", contrast = "light", pronFocus = PronIssueType.R_L,
    )
    val thinkPair = item(
        "pron.th_s.01", Skill.MINIMAL_PAIR, listOf("think"), listOf("think"), promptEn = "think", contrast = "sink", pronFocus = PronIssueType.TH_S,
    )
}
