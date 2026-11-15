package com.cartutor.engineglish.english.learn

import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.PlacementAnswer
import com.cartutor.engineglish.english.core.PlacementItem
import com.cartutor.engineglish.english.core.PlacementState
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.learn.LearnFixtures.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 적응형 레벨 테스트 — 계단식 이동 · 수렴 · 결과 (명세 §6.2) */
class AdaptivePlacementTest {
    private val engine = AdaptivePlacement()
    private val pool = LearnFixtures.placementPool()

    /** answerFor(item) 로 끝까지 풀기 (문항마다 2초) */
    private fun run(track: Track, pool: List<PlacementItem>, answerFor: (PlacementItem, Int) -> Boolean): PlacementState {
        var s = engine.start(track, pool, NOW)
        var t = NOW
        var guard = 0
        while (!s.finished) {
            val item = s.current!!
            t += 2_000
            val choice = if (answerFor(item, s.index)) item.answerIndex else (item.answerIndex + 1) % item.choices.size
            s = engine.answer(s, pool, choice, t)
            assertTrue("무한 반복", ++guard <= 20)
        }
        return s
    }

    /** 실력이 [ability] 인 학습자: 그 레벨 이하 문항만 맞힘 */
    private fun learner(ability: Level): (PlacementItem, Int) -> Boolean = { item, _ -> item.level.ordinal <= ability.ordinal }

    @Test fun startsAtIntermediateWithTwelveItems() {
        val s = engine.start(Track.BUSINESS, pool, NOW)
        assertEquals(12, s.total)
        assertEquals(Level.INTERMEDIATE, s.currentLevel)
        assertEquals(Level.INTERMEDIATE, s.current!!.level)
        assertEquals("pl.biz.i.01", s.current!!.id) // 첫 문항: 트랙 문항, id 순
        assertEquals(0, s.index)
        assertFalse(s.finished)
        assertEquals(NOW, s.currentShownMs)
    }

    @Test fun correctGoesUpWrongGoesDownAndAlternatesTrackAndGeneral() {
        var s = engine.start(Track.BUSINESS, pool, NOW)
        s = engine.answer(s, pool, s.current!!.answerIndex, NOW + 3_000) // 맞힘 → 고급
        assertEquals(Level.ADVANCED, s.current!!.level)
        assertEquals("pl.g.a.01", s.current!!.id) // 두 번째: 공통 문항
        assertEquals(3_000L, s.answers.single().latencyMs)
        s = engine.answer(s, pool, s.current!!.answerIndex + 1, NOW + 5_000) // 틀림 → 중급
        assertEquals(Level.INTERMEDIATE, s.current!!.level)
        assertEquals("pl.biz.i.02", s.current!!.id) // 세 번째: 다시 트랙 문항
        s = engine.answer(s, pool, s.current!!.answerIndex + 1, NOW + 7_000) // 틀림 → 초급
        assertEquals(Level.BEGINNER, s.currentLevel)
        assertEquals("pl.g.b.01", s.current!!.id)
        s = engine.answer(s, pool, s.current!!.answerIndex + 1, NOW + 9_000) // 초급에서 틀림 → 초급 유지
        assertEquals(Level.BEGINNER, s.current!!.level)
    }

    @Test fun convergesToTheLearnersTrueLevel() {
        for (ability in Level.entries) {
            for (track in Track.entries) {
                val s = run(track, pool, learner(ability))
                assertEquals(12, s.answers.size)
                assertEquals("트랙 $track 실력 $ability", ability, engine.result(s).level)
            }
        }
    }

    @Test fun neverRepeatsAnItemAndOnlyUsesTrackOrGeneralItems() {
        val s = run(Track.TRAVEL, pool) { _, i -> i % 3 != 2 }
        val ids = s.answers.map { it.itemId }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("pl.g.") || it.startsWith("pl.trv.") })
    }

    @Test fun occasionalSlipDoesNotDropTheLevel() {
        // 중급 실력, 세 번째 문항에서 실수
        val s = run(Track.BUSINESS, pool) { item, i -> i != 2 && item.level.ordinal <= Level.INTERMEDIATE.ordinal }
        val r = engine.result(s)
        assertEquals(Level.INTERMEDIATE, r.level)
        assertTrue(engine.confidence(s) >= 0.7f)
    }

    @Test fun resultSummaryAndPerLevelCounts() {
        val all = run(Track.BUSINESS, pool) { _, _ -> true }
        val r = engine.result(all)
        assertEquals(Level.ADVANCED, r.level)
        assertEquals(12, r.correct)
        assertEquals(12, r.total)
        assertEquals(1 to 1, r.perLevel[Level.INTERMEDIATE])
        assertEquals(11 to 11, r.perLevel[Level.ADVANCED])
        assertNull(r.perLevel[Level.BEGINNER])
        assertTrue(r.summaryKo.startsWith("12문항 중 12개 정답 · 고급부터 시작할게요."))
        assertTrue(engine.confidence(all) > 0.9f)

        val none = run(Track.BUSINESS, pool) { _, _ -> false }
        val rn = engine.result(none)
        assertEquals(Level.BEGINNER, rn.level)
        assertEquals(0, rn.correct)
        assertTrue(rn.summaryKo.startsWith("12문항 중 0개 정답 · 초급부터 시작할게요."))
        assertTrue(engine.confidence(none) < 0.5f)

        val mid = run(Track.DAILY, pool, learner(Level.INTERMEDIATE))
        val rm = engine.result(mid)
        assertEquals("12문항 중 ${rm.correct}개 정답 · 중급부터 시작할게요. 기본 표현은 탄탄해요.", rm.summaryKo)
    }

    @Test fun resultNeedsTwoAnswersAndSixtyPercent() {
        fun state(vararg answers: Pair<Level, Boolean>) = PlacementState(
            track = Track.BUSINESS, total = 12, currentLevel = Level.INTERMEDIATE, current = null,
            answers = answers.mapIndexed { i, (l, c) -> PlacementAnswer("q$i", l, 0, c, 1_000L) },
        )
        // 고급 1문항만 맞힘 → 2문항 미만이라 인정 안 됨
        assertEquals(Level.INTERMEDIATE, engine.result(state(Level.INTERMEDIATE to true, Level.INTERMEDIATE to true, Level.ADVANCED to true)).level)
        // 고급 3/5 = 60% → 고급
        assertEquals(
            Level.ADVANCED,
            engine.result(state(Level.ADVANCED to true, Level.ADVANCED to true, Level.ADVANCED to true, Level.ADVANCED to false, Level.ADVANCED to false)).level,
        )
        // 중급 1/2 = 50%, 초급 기록 없음 → 초급
        assertEquals(Level.BEGINNER, engine.result(state(Level.INTERMEDIATE to true, Level.INTERMEDIATE to false)).level)
    }

    @Test fun exhaustedLevelFallsBackToNearestLevel() {
        val small = listOf(
            LearnFixtures.placement("pl.g.i.01", null, Level.INTERMEDIATE),
            LearnFixtures.placement("pl.g.i.02", null, Level.INTERMEDIATE),
            LearnFixtures.placement("pl.g.a.01", null, Level.ADVANCED),
            LearnFixtures.placement("pl.g.b.01", null, Level.BEGINNER),
        )
        var s = engine.start(Track.BUSINESS, small, NOW)
        assertEquals(4, s.total) // 풀이 12개보다 작으면 풀 크기만큼
        s = engine.answer(s, small, 0, NOW + 1) // 중급 맞힘 → 고급
        assertEquals("pl.g.a.01", s.current!!.id)
        s = engine.answer(s, small, 0, NOW + 2) // 고급 맞힘 → 고급 바닥 → 가장 가까운 중급
        assertEquals("pl.g.i.02", s.current!!.id)
        s = engine.answer(s, small, 1, NOW + 3) // 틀림 → 초급
        assertEquals("pl.g.b.01", s.current!!.id)
        s = engine.answer(s, small, 0, NOW + 4)
        assertTrue(s.finished)
        assertNull(s.current)
        assertEquals(4, s.answers.size)
        // 끝난 상태에 답해도 그대로
        assertEquals(s, engine.answer(s, small, 0, NOW + 5))
    }

    @Test fun emptyPoolFinishesImmediately() {
        val s = engine.start(Track.BUSINESS, emptyList(), NOW)
        assertTrue(s.finished)
        assertEquals(0, s.total)
        val r = engine.result(s)
        assertEquals(Level.BEGINNER, r.level)
        assertEquals(0, r.total)
        assertTrue(r.summaryKo.isNotBlank())
        // 다른 트랙 문항만 있으면 역시 빈 풀
        val other = LearnFixtures.placementPool(tracks = listOf(Track.TRAVEL), generalPerLevel = 0)
        assertTrue(engine.start(Track.BUSINESS, other, NOW).finished)
    }

    @Test fun invalidItemsAreSkipped() {
        val broken = listOf(
            LearnFixtures.placement("pl.g.i.01", null, Level.INTERMEDIATE, answerIndex = 7),
            LearnFixtures.placement("pl.g.i.02", null, Level.INTERMEDIATE),
            LearnFixtures.placement("pl.g.i.02", null, Level.INTERMEDIATE),
        )
        val s = engine.start(Track.DAILY, broken, NOW)
        assertEquals(1, s.total)
        assertEquals("pl.g.i.02", s.current!!.id)
    }
}
