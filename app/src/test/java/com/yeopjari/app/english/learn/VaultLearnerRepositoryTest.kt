package com.yeopjari.app.english.learn

import com.yeopjari.app.english.core.CardState
import com.yeopjari.app.english.core.CommutePattern
import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.DailyGoal
import com.yeopjari.app.english.core.DayStat
import com.yeopjari.app.english.core.EveningStudy
import com.yeopjari.app.english.core.FluencyDay
import com.yeopjari.app.english.core.Grade
import com.yeopjari.app.english.core.LearnerProfile
import com.yeopjari.app.english.core.LearnerSettings
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.Mistake
import com.yeopjari.app.english.core.PronIssueRecord
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.PronProfile
import com.yeopjari.app.english.core.ProgressState
import com.yeopjari.app.english.core.SavedExpression
import com.yeopjari.app.english.core.SecureStore
import com.yeopjari.app.english.core.SessionRecord
import com.yeopjari.app.english.core.SlotMode
import com.yeopjari.app.english.core.Track
import com.yeopjari.app.english.core.UnitRecord
import com.yeopjari.app.english.core.Verdict
import com.yeopjari.app.english.core.VirtualTime
import com.yeopjari.app.english.learn.LearnFixtures.DAY
import com.yeopjari.app.english.learn.LearnFixtures.HOUR
import com.yeopjari.app.english.learn.LearnFixtures.NOW
import com.yeopjari.app.english.learn.LearnFixtures.TODAY
import com.yeopjari.app.english.learn.LearnFixtures.profile
import com.yeopjari.app.english.learn.VaultLearnerRepository.Companion.FILE_NAME
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 암호화 저장소 왕복 · 1초 저장 · 지우기 · 망가진 파일 · 너그러운 해석 (명세 §6.5) */
class VaultLearnerRepositoryTest {

    private fun repo(store: SecureStore, t: VirtualTime = VirtualTime()) = VaultLearnerRepository(store, t.scope(), t)

    /** 모든 필드를 기본값이 아닌 값으로 채운 상태 */
    private fun fullState() = LearnerState(
        profile = LearnerProfile(Track.TRAVEL, DailyGoal.INTENSIVE, Level.ADVANCED, placementDone = true, placementCorrect = 9, createdDay = TODAY - 40),
        settings = LearnerSettings(
            morningMode = SlotMode.MIXED, eveningMode = SlotMode.NEW_EXPRESSIONS, otherMode = SlotMode.REVIEW,
            autoStartOnCommute = false, ambientPulse = false, hudHint = false, englishRate = 0.9f,
            eveningStudy = EveningStudy.NEVER, soundEffects = false, contextHooks = false, mirrorSimRouteToCar = true,
        ),
        progress = ProgressState(
            xp = 1234, streakDays = 5, bestStreak = 9, lastActiveDay = TODAY,
            days = listOf(DayStat(TODAY - 1, 12, 9, 2, 4, 8, 110, 32), DayStat(TODAY, 3, 3, 0, 1, 2, 30, 0)),
            units = mapOf(
                "TRAVEL:ADVANCED:1" to UnitRecord("TRAVEL:ADVANCED:1", mapOf("trv.a1.01" to 1f, "trv.a1.02" to 0.5f, "trv.a1.03" to 0f), passed = true, passedAtMs = NOW - DAY),
                "TRAVEL:ADVANCED:2" to UnitRecord("TRAVEL:ADVANCED:2", mapOf("trv.a2.01" to 1f)),
            ),
            totalAnswered = 321, totalCorrect = 250,
        ),
        cards = linkedMapOf(
            "trv.a1.01" to CardState("trv.a1.01", reps = 3, lapses = 1, ease = 2.36, intervalDays = 7.08, dueAtMs = NOW + 7 * DAY, lastReviewedMs = NOW - HOUR, lastGrade = Grade.HARD, introducedMs = NOW - 20 * DAY),
            "hook.rain.01" to CardState("hook.rain.01", reps = 0, lapses = 2, ease = 1.3, intervalDays = SrsScheduler.AGAIN_INTERVAL_DAYS, dueAtMs = NOW + 600_000, lastReviewedMs = NOW, lastGrade = Grade.AGAIN, introducedMs = NOW - DAY),
        ),
        pron = PronProfile(
            counts = mapOf(PronIssueType.R_L to 4, PronIssueType.TH_S to 1),
            recent = listOf(PronIssueRecord(PronIssueType.R_L, "right", "light", NOW - HOUR), PronIssueRecord(PronIssueType.TH_S, "think", "sink", NOW)),
        ),
        fluency = listOf(FluencyDay(TODAY - 1, 6, 1_850L, 104, 1.5f), FluencyDay(TODAY, 4, 1_350L, 112, 0.75f)),
        commute = CommutePattern(morningDepartures = listOf(460, 465, 452), eveningDepartures = listOf(1090), trips = 4),
        saved = listOf(
            SavedExpression("Let's call it a day.", "biz.i1.05", NOW - HOUR),
            SavedExpression("‘미루다’는 \"postpone\" \\ 탭\t줄\n바꿈", null, NOW),
        ),
        mistakes = listOf(Mistake("trv.a1.02", TODAY, Verdict.PARTIAL, NOW - HOUR), Mistake("trv.a1.03", TODAY, Verdict.NO_ANSWER, NOW)),
        sessions = listOf(
            SessionRecord("s-1", NOW - DAY, NOW - DAY + 32 * 60_000, CommuteSlot.MORNING, SlotMode.NEW_EXPRESSIONS, Track.BUSINESS, 16, 12, 2, 3, 150),
            SessionRecord("s-2", NOW - HOUR, NOW, CommuteSlot.EVENING, SlotMode.REVIEW, Track.TRAVEL, 20, 15, 3, 0, 180),
        ),
    )

    @Test fun roundTripThroughStoreRestoresEveryField() {
        val t = VirtualTime()
        val store = CountingStore()
        val r = repo(store, t)
        runBlocking { r.load() }
        assertTrue(r.loaded.value)
        assertEquals(LearnerState(), r.state.value)
        val full = fullState()
        r.update { full }
        runBlocking { r.flush() }
        assertEquals(1, store.writes)

        val again = repo(store, t)
        assertFalse(again.loaded.value)
        runBlocking { again.load() }
        assertEquals(full, again.state.value)
        assertTrue(again.loaded.value)
        // 코덱만으로도 같은 결과
        assertEquals(full, LearnerStateCodec.decode(LearnerStateCodec.encode(full)))
    }

    @Test fun savesOneSecondAfterFirstChangeWithLatestState() {
        val t = VirtualTime()
        val store = CountingStore()
        val r = repo(store, t)
        runBlocking { r.load() }
        r.update { it.copy(settings = it.settings.copy(hudHint = false)) }
        t.advanceBy(500)
        r.update { it.copy(settings = it.settings.copy(ambientPulse = false)) }
        t.advanceBy(499)
        assertEquals(0, store.writes)
        assertNull(r.storageInfo().lastSavedMs)
        t.advanceBy(1) // 첫 변경 후 1초
        assertEquals(1, store.writes)
        val stored = LearnerStateCodec.decode(store.inner.read(FILE_NAME))
        assertFalse(stored.settings.hudHint)
        assertFalse(stored.settings.ambientPulse)
        assertEquals(t.nowMs(), r.storageInfo().lastSavedMs)

        t.advanceBy(5_000)
        assertEquals(1, store.writes) // 바뀐 게 없으면 안 씀
        r.update { it.copy(saved = listOf(SavedExpression("It's pouring.", null, t.nowMs()))) }
        t.advanceBy(999)
        assertEquals(1, store.writes)
        t.advanceBy(1)
        assertEquals(2, store.writes)
        assertEquals("It's pouring.", LearnerStateCodec.decode(store.inner.read(FILE_NAME)).saved.single().text)
    }

    @Test fun flushWritesImmediatelyAndCancelsPendingSave() {
        val t = VirtualTime()
        val store = CountingStore()
        val r = repo(store, t)
        runBlocking { r.load() }
        r.update { it.copy(profile = profile()) }
        runBlocking { r.flush() }
        assertEquals(1, store.writes)
        t.advanceBy(2_000)
        assertEquals(1, store.writes) // 예약된 저장은 취소됨 (같은 내용 두 번 안 씀)
        runBlocking { r.flush() }
        assertEquals(1, store.writes) // 바뀐 것 없음
    }

    @Test fun neverWritesBeforeLoadAndReplaysEarlyUpdates() {
        val t = VirtualTime()
        val store = CountingStore()
        store.inner.write(FILE_NAME, LearnerStateCodec.encode(LearnerState(profile = profile(), progress = ProgressState(xp = 99))))
        val r = repo(store, t)
        val early = r.update { it.copy(settings = it.settings.copy(hudHint = false)) }
        assertFalse(early.settings.hudHint)
        t.advanceBy(5_000)
        runBlocking { r.flush() }
        assertEquals(0, store.writes) // 불러오기 전엔 저장된 기록을 덮어쓰지 않는다
        runBlocking { r.load() }
        assertEquals(99, r.state.value.progress.xp) // 저장된 기록 유지
        assertFalse(r.state.value.settings.hudHint) // 이른 변경도 반영
        t.advanceBy(1_000)
        assertEquals(1, store.writes)
        assertFalse(LearnerStateCodec.decode(store.inner.read(FILE_NAME)).settings.hudHint)
    }

    @Test fun loadReadsTheVaultOnlyOnce() {
        val store = CountingStore()
        val r = repo(store)
        runBlocking {
            r.load()
            r.load()
        }
        assertEquals(1, store.reads)
        assertEquals(0, store.writes) // 읽기만으로는 쓰지 않음
    }

    @Test fun eraseAllDeletesFileAndResetsState() {
        val t = VirtualTime()
        val store = CountingStore()
        val r = repo(store, t)
        runBlocking { r.load() }
        r.update { fullState() }
        runBlocking { r.flush() }
        assertTrue(r.storageInfo().bytes > 0)

        r.update { it.copy(progress = it.progress.copy(xp = 5_000)) } // 저장 대기 중
        r.eraseAll()
        assertEquals(LearnerState(), r.state.value)
        assertNull(r.state.value.profile)
        assertNull(store.inner.read(FILE_NAME))
        t.advanceBy(3_000)
        assertNull(store.inner.read(FILE_NAME)) // 대기 중이던 저장이 되살리지 않음
        val info = r.storageInfo()
        assertEquals(0L, info.bytes)
        assertTrue(info.files.isEmpty())
        assertNull(info.lastSavedMs)
        assertTrue(r.loaded.value)

        // 지운 뒤 새로 시작한 기록은 다시 저장된다
        r.update { it.copy(profile = profile(track = Track.DAILY)) }
        t.advanceBy(1_000)
        assertEquals(Track.DAILY, LearnerStateCodec.decode(store.inner.read(FILE_NAME)).profile!!.track)
    }

    @Test fun corruptFileLoadsEmptyState() {
        val store = CountingStore()
        store.inner.write(FILE_NAME, byteArrayOf(0x01, 0x7f, 0x00, 0x22, 0x7b))
        val r = repo(store)
        runBlocking { r.load() }
        assertTrue(r.loaded.value)
        assertEquals(LearnerState(), r.state.value)

        assertEquals(LearnerState(), LearnerStateCodec.decode(null))
        assertEquals(LearnerState(), LearnerStateCodec.decode(ByteArray(0)))
        assertEquals(LearnerState(), LearnerStateCodec.decode("[1, 2, 3]".toByteArray()))
        assertEquals(LearnerState(), LearnerStateCodec.decode("{\"profile\": 5, \"cards\": [], \"progress\": \"x\"}".toByteArray()))
    }

    @Test fun unknownFieldsIgnoredMissingFieldsDefaultBrokenEntriesSkipped() {
        val json = """
            {
              "schema": 1,
              "futureFeature": { "x": [1, 2] },
              "profile": { "track": "TRAVEL", "level": "ADVANCED", "extra": true },
              "settings": { "hudHint": false, "morningMode": "NOT_A_MODE", "englishRate": 9.5, "memoryPalace": false, "eveningStudy": "SOMETIMES" },
              "progress": { "xp": 40, "days": [ { "day": 20000, "answered": 3 }, { "answered": 1 } ],
                            "units": { "DAILY:BEGINNER:1": { "scores": { "day.b1.01": 1, "day.b1.02": "bad" } }, "broken": 7 } },
              "cards": { "day.b1.01": { "reps": 2 }, "bad": 5 },
              "pron": { "counts": { "R_L": 3, "NOT_A_TYPE": 9 }, "recent": [ { "type": "F_P", "expected": "fan", "heard": "pan", "atMs": 5 }, { "type": "??" } ] },
              "commute": { "morningDepartures": [460, "x", 470] },
              "anchors": [ { "itemId": "a", "segmentId": "seg.x" }, { "itemId": "b", "segmentId": "seg.y", "driveId": "d1" } ],
              "mistakes": [ { "itemId": "x" }, { "itemId": "y", "day": 20000 } ],
              "sessions": [ { "id": "s-no-track" } ],
              "saved": [ { "text": "Hi there." }, { "itemId": "no-text" } ],
            }
        """.trimIndent()
        val s = LearnerStateCodec.decode(json.toByteArray())
        val p = s.profile!!
        assertEquals(Track.TRAVEL, p.track)
        assertEquals(Level.ADVANCED, p.level)
        assertEquals(DailyGoal.STEADY, p.goal)
        assertFalse(p.placementDone)
        assertFalse(s.settings.hudHint)
        assertEquals(SlotMode.NEW_EXPRESSIONS, s.settings.morningMode) // 모르는 값 → 기본값
        assertEquals(1.5f, s.settings.englishRate, 0f) // 범위 밖 → 잘라냄
        assertTrue(s.settings.ambientPulse)
        assertEquals(40, s.progress.xp)
        assertEquals(listOf(DayStat(day = 20000, answered = 3)), s.progress.days)
        assertEquals(mapOf("day.b1.01" to 1f), s.progress.units["DAILY:BEGINNER:1"]!!.scores)
        assertEquals("DAILY:BEGINNER:1", s.progress.units["DAILY:BEGINNER:1"]!!.unitId)
        assertFalse(s.progress.units.containsKey("broken"))
        assertEquals(setOf("day.b1.01"), s.cards.keys)
        assertEquals(CardState(itemId = "day.b1.01", reps = 2), s.cards["day.b1.01"])
        assertEquals(mapOf(PronIssueType.R_L to 3), s.pron.counts)
        assertEquals(listOf(PronIssueRecord(PronIssueType.F_P, "fan", "pan", 5)), s.pron.recent)
        assertEquals(listOf(460, 470), s.commute.morningDepartures)
        // 예전 버전이 저장한 키(anchors · memoryPalace)는 조용히 버리고, 새 설정은 기본값
        assertEquals(EveningStudy.ASK, s.settings.eveningStudy)
        assertTrue(s.settings.soundEffects)
        val again = String(LearnerStateCodec.encode(s), Charsets.UTF_8)
        assertFalse(again.contains("anchors"))
        assertFalse(again.contains("memoryPalace"))
        assertEquals(listOf("y"), s.mistakes.map { it.itemId })
        assertEquals(Verdict.WRONG, s.mistakes.single().verdict)
        assertTrue(s.sessions.isEmpty())
        assertEquals(listOf("Hi there."), s.saved.map { it.text })
        assertTrue(s.fluency.isEmpty())
    }

    @Test fun missingProfileTrackMeansOnboardingAgain() {
        val s = LearnerStateCodec.decode("{\"profile\": {\"level\": \"BEGINNER\"}, \"progress\": {\"xp\": 7}}".toByteArray())
        assertNull(s.profile)
        assertEquals(7, s.progress.xp)
    }

    @Test fun jsonUsesEnumNamesAndStringKeys() {
        val text = String(LearnerStateCodec.encode(fullState()), Charsets.UTF_8)
        assertTrue(text.contains("\"R_L\":4"))
        assertTrue(text.contains("\"track\":\"TRAVEL\""))
        assertTrue(text.contains("\"TRAVEL:ADVANCED:1\":{"))
        assertTrue(text.contains("\"lastGrade\":\"HARD\""))
        assertTrue(text.contains("\"schema\":1"))
        assertFalse(text.contains("null")) // null 필드는 쓰지 않음
    }

    @Test fun storageInfoReportsEncryptionAndSize() {
        val t = VirtualTime()
        val store = CountingStore(encrypted = true)
        val r = repo(store, t)
        runBlocking { r.load() }
        val before = r.storageInfo()
        assertTrue(before.encrypted)
        assertEquals(0L, before.bytes)
        assertTrue(before.files.isEmpty())
        r.update { it.copy(profile = profile()) }
        t.advanceBy(1_000)
        val after = r.storageInfo()
        assertTrue(after.bytes > 0)
        assertEquals(listOf(FILE_NAME), after.files)
        assertNotNull(after.lastSavedMs)
        assertFalse(repo(CountingStore(encrypted = false)).storageInfo().encrypted)
    }

    @Test fun updateReturnsNewStateAndNoOpUpdateDoesNotSave() {
        val t = VirtualTime()
        val store = CountingStore()
        val r = repo(store, t)
        runBlocking { r.load() }
        val before = r.state.value
        assertSame(before, r.update { it })
        t.advanceBy(2_000)
        assertEquals(0, store.writes)
        val next = r.update { it.copy(progress = it.progress.copy(xp = 10)) }
        assertSame(next, r.state.value)
        assertEquals(10, next.progress.xp)
    }

    @Test fun failedWriteIsRetriedByNextFlush() {
        val t = VirtualTime()
        val store = object : SecureStore {
            val inner = CountingStore()
            var fail = true
            override fun write(name: String, bytes: ByteArray): Boolean = if (fail) false else inner.write(name, bytes)
            override fun read(name: String): ByteArray? = inner.read(name)
            override fun delete(name: String) = inner.delete(name)
            override fun size(name: String): Long = inner.size(name)
            override val encrypted: Boolean = true
        }
        val r = repo(store, t)
        runBlocking { r.load() }
        r.update { it.copy(profile = profile()) }
        t.advanceBy(1_000)
        assertNull(r.storageInfo().lastSavedMs)
        store.fail = false
        runBlocking { r.flush() }
        assertNotNull(r.storageInfo().lastSavedMs)
        assertEquals(Track.BUSINESS, LearnerStateCodec.decode(store.inner.inner.read(FILE_NAME)).profile!!.track)
    }
}
