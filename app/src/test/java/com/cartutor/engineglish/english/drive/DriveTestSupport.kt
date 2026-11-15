package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.english.core.AudioEnvironment
import com.cartutor.engineglish.english.core.DrivingSignals
import com.cartutor.engineglish.english.core.GearPosition
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.Track
import kotlinx.coroutines.flow.MutableStateFlow

/** 운전 중(D) 기본 신호 — 테스트마다 copy 로 바꾼다 */
fun driving(t: Long, speed: Float = 50f): DrivingSignals =
    DrivingSignals(atMs = t, gear = GearPosition.D, speedKph = speed, uxRestricted = true, driveId = "drive-test")

class FakeAudio : AudioEnvironment {
    override val navPromptActive = MutableStateFlow(false)
    override val focusLost = MutableStateFlow(false)
}

fun item(
    id: String = "t.1",
    skill: Skill = Skill.VOCAB,
    answer: String = "postpone",
    maxLoad: LoadLevel = LoadLevel.NORMAL,
    listenSec: Int? = null,
): LearningItem = LearningItem(
    id = id, track = Track.BUSINESS, level = Level.BEGINNER, unit = 1, skill = skill,
    promptKo = "테스트", answers = listOf(answer), meaningKo = "뜻", maxLoad = maxLoad, listenSec = listenSec,
)
