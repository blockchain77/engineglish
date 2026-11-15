package com.yeopjari.app.english.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** english/core 공용 계약·유틸 기본 동작 */
class CoreContractsTest {

    @Test fun miniJsonRoundTripKeepsTypesAndKorean() {
        val value = linkedMapOf(
            "id" to "biz.b1.01",
            "promptKo" to "‘회의를 내일로 미룰 수 있을까요?’를 영어로 말해 보세요.",
            "answers" to listOf("Can we push the meeting to tomorrow?", "Could we move the meeting to tomorrow?"),
            "n" to 3,
            "ease" to 2.5,
            "flag" to true,
            "none" to null,
            "level" to Level.INTERMEDIATE,
            "nested" to mapOf("quote" to "He said \"hi\"\n\ttab \\ slash", "ctrl" to "\u0001"),
        )
        for (pretty in listOf(false, true)) {
            val text = MiniJson.stringify(value, pretty)
            val back = MiniJson.parseObject(text)
            assertEquals("biz.b1.01", back.jStr("id"))
            assertEquals(value["promptKo"], back.jStr("promptKo"))
            assertEquals(listOf("Can we push the meeting to tomorrow?", "Could we move the meeting to tomorrow?"), back.jStrList("answers"))
            assertEquals(3, back.jInt("n"))
            assertEquals(3L, back["n"])
            assertEquals(2.5, back.jDouble("ease")!!, 1e-9)
            assertEquals(true, back.jBool("flag"))
            assertTrue(back.containsKey("none"))
            assertNull(back["none"])
            assertEquals(Level.INTERMEDIATE, back.jEnum<Level>("level"))
            assertEquals("He said \"hi\"\n\ttab \\ slash", back.jObj("nested")!!.jStr("quote"))
            assertEquals("\u0001", back.jObj("nested")!!.jStr("ctrl"))
        }
    }

    @Test fun miniJsonIsLenientWithTrailingCommasAndBom() {
        val m = MiniJson.parseObject("\uFEFF{ \"a\": [1, 2, 3,], \"b\": {\"c\": -1.5e2,}, }")
        assertEquals(listOf(1L, 2L, 3L), m.jArr("a"))
        assertEquals(-150.0, m.jObj("b")!!.jDouble("c")!!, 1e-9)
        assertEquals("\uD55C", MiniJson.parse("\"\\uD55C\""))
    }

    @Test(expected = JsonException::class)
    fun miniJsonRejectsBrokenInput() {
        MiniJson.parse("{\"a\": [1, 2}")
    }

    @Test fun trackParseAcceptsLooseNames() {
        assertEquals(Track.BUSINESS, Track.parse("business"))
        assertEquals(Track.TOEIC_SPEAKING, Track.parse("toeic speaking"))
        assertEquals(Track.TOEIC_SPEAKING, Track.parse("TOEIC_SPEAKING"))
        assertEquals(Track.TRAVEL, Track.parse("여행"))
        assertNull(Track.parse(""))
        assertNull(Track.parse(null))
    }

    @Test fun loadLevelOrderingMatchesPolicyMeaning() {
        assertTrue(LoadLevel.PARKED.isAtMost(LoadLevel.NORMAL))
        assertTrue(LoadLevel.NORMAL.isAtMost(LoadLevel.NORMAL))
        assertFalse(LoadLevel.FOCUS.isAtMost(LoadLevel.NORMAL))
        assertTrue(LoadLevel.CRITICAL.isAbove(LoadLevel.FOCUS))
        assertEquals(LoadLevel.FOCUS, LoadLevel.max(LoadLevel.NORMAL, LoadLevel.FOCUS))
        LoadLevel.entries.zipWithNext().forEach { (a, b) -> assertTrue(a.bandEnd < b.bandStart) }
    }

    @Test fun unitKeyAndMediaKeysMap() {
        val k = UnitKey(Track.DAILY, Level.ADVANCED, 2)
        assertEquals("DAILY:ADVANCED:2", k.id)
        assertEquals(k, UnitKey.parse(k.id))
        assertNull(UnitKey.parse("DAILY:X:2"))
        assertEquals(MediaKey.NEXT, MediaKey.fromKeyCode(87))
        assertEquals(MediaKey.PREVIOUS, MediaKey.fromKeyCode(88))
        assertEquals(MediaKey.PLAY_PAUSE, MediaKey.fromKeyCode(85))
        assertEquals(MediaKey.PLAY_PAUSE, MediaKey.fromKeyCode(127))
        assertNull(MediaKey.fromKeyCode(24))
    }

    @Test fun learningItemDerivedValues() {
        val item = LearningItem(
            id = "t.1", track = Track.DAILY, level = Level.BEGINNER, unit = 1, skill = Skill.SENTENCE_BUILD,
            promptKo = "‘늦을 것 같아요’를 영어로", answers = listOf("I'm running a little late."), meaningKo = "늦을 것 같아요",
        )
        assertEquals(5, item.answerWords)
        assertEquals(Skill.SENTENCE_BUILD.defaultListenSec, item.listenSeconds)
        assertEquals(UnitKey(Track.DAILY, Level.BEGINNER, 1), item.unitKey)
        assertEquals(LoadLevel.NORMAL, item.maxLoad)
    }

    @Test fun speechDurationEstimates() {
        val en = SpeechDurations.estimateMs("Can we push the meeting to tomorrow?", SpeechLang.EN)
        assertTrue(en in 2_500L..3_500L)
        val shortKo = SpeechDurations.estimateMs("좋아요!", SpeechLang.KO)
        assertTrue(shortKo < SpeechThresholds.FOCUS_FINISH_TTS_MS)
        assertTrue(SpeechDurations.estimateMs("Hello there", SpeechLang.EN, SpeechRate.SLOW) > SpeechDurations.estimateMs("Hello there", SpeechLang.EN))
    }

    @Test fun kstDaysAndVirtualTimeAgree() {
        val t = VirtualTime()
        assertEquals(7 * 60 + 40, t.minuteOfDay())
        val day0 = t.epochDay()
        t.advanceBy(17 * 60 * 60 * 1000L) // 다음 날 00:40
        assertEquals(day0 + 1, t.epochDay())
        assertEquals("오전 12:40", Kst.clockLabel(t.nowMs()))
    }

    @Test fun virtualTimeRunsDelaysAndTimeouts() {
        val t = VirtualTime()
        val scope = t.scope()
        var done = false
        var timedOut: Boolean? = null
        scope.launch { delay(4_000); done = true }
        scope.launch { timedOut = withTimeoutOrNull(1_000) { delay(5_000); false } == null }
        t.advanceBy(999)
        assertFalse(done)
        assertNull(timedOut)
        t.advanceBy(1)
        assertEquals(true, timedOut)
        t.advanceBy(3_000)
        assertTrue(done)
    }

    @Test fun mutableSignalSourceStampsTime() {
        val t = VirtualTime()
        val src = MutableSignalSource(t)
        t.advanceBy(500)
        src.set { it.copy(gear = GearPosition.D, speedKph = 42f) }
        assertEquals(t.nowMs(), src.value.atMs)
        assertEquals(GearPosition.D, src.value.gear)
        assertFalse(src.value.parked)
        assertTrue(src.value.moving)
    }

    @Test fun inMemoryStoreRoundTrip() {
        val s = InMemorySecureStore()
        assertTrue(s.write("a", byteArrayOf(1, 2, 3)))
        assertEquals(3L, s.size("a"))
        assertEquals(listOf<Byte>(1, 2, 3), s.read("a")!!.toList())
        s.delete("a")
        assertNull(s.read("a"))
        assertFalse(s.encrypted)
    }

    @Test fun settingsModePerSlotDefaults() {
        val s = LearnerSettings()
        assertEquals(SlotMode.NEW_EXPRESSIONS, s.modeFor(CommuteSlot.MORNING))
        assertEquals(SlotMode.REVIEW, s.modeFor(CommuteSlot.EVENING))
        assertFalse(s.mirrorSimRouteToCar)
    }
}
