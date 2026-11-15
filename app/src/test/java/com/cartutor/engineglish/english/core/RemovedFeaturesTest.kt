package com.cartutor.engineglish.english.core

import com.cartutor.engineglish.english.learn.LearnerStateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 뺀 기능이 남아 있지 않은지 — '길 위의 기억 궁전'과 정차 중 '핸들 퀴즈'(레이싱), 그리고 주행 중 '오늘의 한 문장' 마무리.
 * 계약(열거형·상태·코치 함수)과 소스·리소스 글자를 함께 본다.
 */
class RemovedFeaturesTest {

    private fun fieldNames(c: Class<*>): Set<String> = c.declaredFields.map { it.name }.toSet()
    private fun methodNames(c: Class<*>): Set<String> = c.methods.map { it.name }.toSet()

    @Test fun contractsHaveNoRaceOrMemoryPalaceOrWrapUpTurn() {
        assertEquals(
            listOf("HOME", "DRIVE", "LESSON", "REVIEW", "REPORT", "SETTINGS", "ONBOARDING", "PLACEMENT"),
            EnglishRoute.entries.map { it.name },
        )
        assertTrue(CoachPhase.entries.none { it.name == "QUIZ_RACE" || it.name == "WRAP_UP" })
        assertEquals(listOf("NEW", "REVIEW", "MISTAKE", "HOOK", "DRILL"), PlanKind.entries.map { it.name })

        val ui = fieldNames(EnglishUiState::class.java)
        assertFalse("quizRace" in ui)
        assertTrue(setOf("eveningAsk", "dashboard", "showParkedDashboard").all { it in ui })
        assertFalse("anchors" in fieldNames(LibraryUi::class.java))
        assertFalse("anchors" in fieldNames(RouteStripItem::class.java))
        assertFalse("wrapUpDone" in fieldNames(SessionUi::class.java))

        assertFalse("anchors" in fieldNames(LearnerState::class.java))
        assertFalse("anchors" in fieldNames(ProgressSnapshot::class.java))
        val settings = fieldNames(LearnerSettings::class.java)
        assertFalse("memoryPalace" in settings)
        assertTrue("eveningStudy" in settings && "soundEffects" in settings)

        val coach = methodNames(EnglishCoach::class.java)
        assertFalse("startQuizRace" in coach || "stopQuizRace" in coach)
        assertTrue(setOf("answerEveningAsk", "dismissDashboard", "showDashboard", "reviewMistakes").all { it in coach })
        val demo = methodNames(DemoControl::class.java)
        assertFalse("setSteering" in demo || "setBrake" in demo || "getSteeringDeg" in demo)
        assertFalse("routeMemory" in fieldNames(CoachDeps::class.java))
        assertTrue("sound" in fieldNames(CoachDeps::class.java))
    }

    @Test fun newSettingsDefaultsAndThresholds() {
        val s = LearnerSettings()
        assertTrue(s.soundEffects)
        assertEquals(EveningStudy.ASK, s.eveningStudy)
        assertTrue(s.autoStartOnCommute)
        assertEquals(listOf("물어보기", "항상 시작", "안 함"), EveningStudy.entries.map { it.ko })
        assertEquals(60, LearnThresholds.END_BEFORE_ARRIVAL_SEC)
        assertEquals(10_000L, LearnThresholds.EVENING_ASK_TIMEOUT_MS)
        assertEquals(2, LearnThresholds.EVENING_ASK_MAX_ATTEMPTS)
        assertEquals(5, LearnThresholds.STREAK_CUE_EVERY)
        assertEquals(
            listOf("CORRECT", "PARTIAL", "WRONG", "STREAK", "LEVEL_UP", "SESSION_COMPLETE"),
            SoundCue.entries.map { it.name },
        )
        NoSound.play(SoundCue.CORRECT) // 소리 없는 구현은 아무 일도 하지 않는다
        val ask = EveningAskUi()
        assertFalse(ask.visible)
        assertEquals("퇴근길에도 영어 복습할까요?", ask.questionKo)
        assertEquals("할게요", ask.yesLabelKo)
        assertEquals("오늘은 쉴게요", ask.noLabelKo)
    }

    @Test fun oldSavedDataWithRemovedKeysStillLoadsAndDropsThem() {
        val json = """
            { "schema": 1,
              "profile": { "track": "BUSINESS", "goal": "STEADY", "level": "INTERMEDIATE", "placementDone": true, "createdDay": 20000 },
              "settings": { "memoryPalace": true, "autoStartOnCommute": false },
              "anchors": [ { "itemId": "biz.i1.01", "segmentId": "seg.tancheon", "driveId": "d1", "createdMs": 1 } ] }
        """.trimIndent()
        val s = LearnerStateCodec.decode(json.toByteArray())
        assertEquals(Track.BUSINESS, s.profile?.track)
        assertFalse(s.settings.autoStartOnCommute)
        assertEquals(EveningStudy.ASK, s.settings.eveningStudy)
        assertTrue(s.settings.soundEffects)
        val again = String(LearnerStateCodec.encode(s), Charsets.UTF_8)
        assertFalse(again.contains("anchors"))
        assertFalse(again.contains("memoryPalace"))
        assertTrue(again.contains("eveningStudy") && again.contains("soundEffects"))
        assertEquals(s, LearnerStateCodec.decode(again.toByteArray()))
    }

    /** 소스·리소스·매니페스트에 뺀 기능의 이름이 남아 있지 않다 (app/src/main 전체) */
    @Test fun sourcesAndResourcesDoNotMentionRemovedFeatures() {
        val main = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
        assumeTrue("소스 폴더를 찾을 수 없으면 건너뛴다", main != null)
        val banned = listOf(
            "기억 궁전", "기억궁전", "기억의 궁전", "핸들 퀴즈", "핸들퀴즈", "레이싱", "아케이드",
            "QuizRace", "quizRace", "QUIZ_RACE", "RaceScreen", "RouteAnchor", "RouteMemory", "memoryPalace", "MemoryPalace",
            "ANCHOR_RECALL", "AnchorUi", "setSteering", "WRAP_UP", "wrapUpDone",
        )
        val text = setOf("kt", "kts", "xml", "json", "md", "txt")
        val hits = main!!.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in text }
            .flatMap { f ->
                f.readLines().asSequence().mapIndexedNotNull { i, line ->
                    banned.firstOrNull { it in line }?.let { "${f.relativeTo(main).invariantSeparatorsPath}:${i + 1} ‘$it’" }
                }
            }
            .toList()
        assertTrue("뺀 기능의 흔적:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
