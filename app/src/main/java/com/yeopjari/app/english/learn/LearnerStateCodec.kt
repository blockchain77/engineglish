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
import com.yeopjari.app.english.core.MiniJson
import com.yeopjari.app.english.core.Mistake
import com.yeopjari.app.english.core.PronIssueRecord
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.PronProfile
import com.yeopjari.app.english.core.ProgressState
import com.yeopjari.app.english.core.SavedExpression
import com.yeopjari.app.english.core.SessionRecord
import com.yeopjari.app.english.core.SlotMode
import com.yeopjari.app.english.core.Track
import com.yeopjari.app.english.core.UnitRecord
import com.yeopjari.app.english.core.Verdict
import com.yeopjari.app.english.core.jArr
import com.yeopjari.app.english.core.jBool
import com.yeopjari.app.english.core.jDouble
import com.yeopjari.app.english.core.jEnum
import com.yeopjari.app.english.core.jFloat
import com.yeopjari.app.english.core.jInt
import com.yeopjari.app.english.core.jLong
import com.yeopjari.app.english.core.jObj
import com.yeopjari.app.english.core.jObjList
import com.yeopjari.app.english.core.jStr

/*
 * 학습자 상태 ↔ JSON (명세 §6.5) — UTF-8 MiniJson, enum 은 이름, 맵 키는 문자열(PronIssueType 은 이름).
 * 너그럽게 읽는다: 모르는 필드는 무시, 빠진 필드는 기본값, 망가진 항목 하나는 건너뛰고, 통째로 해석이 안 되면 빈 상태.
 * 이 내용은 SecureVault 로 암호화되어 차 안에만 저장된다. 로그로 남기지 않는다.
 */
object LearnerStateCodec {
    const val SCHEMA = 1

    fun encode(state: LearnerState): ByteArray = MiniJson.toBytes(toJson(state))

    /** null·빈 바이트·해석 불가 → 빈 상태 (profile = null) */
    fun decode(bytes: ByteArray?): LearnerState {
        if (bytes == null || bytes.isEmpty()) return LearnerState()
        return runCatching { fromJson(MiniJson.parseObject(String(bytes, Charsets.UTF_8))) }.getOrNull() ?: LearnerState()
    }

    // ───────────── 쓰기 ─────────────

    fun toJson(s: LearnerState): Map<String, Any?> = obj(
        "schema" to SCHEMA,
        "profile" to s.profile?.let { p ->
            obj(
                "track" to p.track, "goal" to p.goal, "level" to p.level, "placementDone" to p.placementDone,
                "placementCorrect" to p.placementCorrect, "createdDay" to p.createdDay,
            )
        },
        "settings" to s.settings.let { t ->
            obj(
                "morningMode" to t.morningMode, "eveningMode" to t.eveningMode, "otherMode" to t.otherMode,
                "autoStartOnCommute" to t.autoStartOnCommute, "eveningStudy" to t.eveningStudy, "soundEffects" to t.soundEffects,
                "ambientPulse" to t.ambientPulse, "hudHint" to t.hudHint,
                "englishRate" to t.englishRate, "contextHooks" to t.contextHooks,
                "mirrorSimRouteToCar" to t.mirrorSimRouteToCar,
            )
        },
        "progress" to s.progress.let { p ->
            obj(
                "xp" to p.xp, "streakDays" to p.streakDays, "bestStreak" to p.bestStreak, "lastActiveDay" to p.lastActiveDay,
                "days" to p.days.map { d ->
                    obj(
                        "day" to d.day, "answered" to d.answered, "correct" to d.correct, "partial" to d.partial,
                        "newLearned" to d.newLearned, "reviewed" to d.reviewed, "xp" to d.xp, "driveMinutes" to d.driveMinutes,
                    )
                },
                "units" to p.units.entries.associate { (k, u) ->
                    k to obj("unitId" to u.unitId, "scores" to LinkedHashMap(u.scores), "passed" to u.passed, "passedAtMs" to u.passedAtMs)
                },
                "totalAnswered" to p.totalAnswered, "totalCorrect" to p.totalCorrect,
            )
        },
        "cards" to s.cards.entries.associate { (k, c) ->
            k to obj(
                "itemId" to c.itemId, "reps" to c.reps, "lapses" to c.lapses, "ease" to c.ease, "intervalDays" to c.intervalDays,
                "dueAtMs" to c.dueAtMs, "lastReviewedMs" to c.lastReviewedMs, "lastGrade" to c.lastGrade, "introducedMs" to c.introducedMs,
            )
        },
        "pron" to obj(
            "counts" to s.pron.counts.entries.associate { (t, n) -> t.name to n },
            "recent" to s.pron.recent.map { r -> obj("type" to r.type, "expected" to r.expected, "heard" to r.heard, "atMs" to r.atMs) },
        ),
        "fluency" to s.fluency.map { f ->
            obj("day" to f.day, "samples" to f.samples, "avgLatencyMs" to f.avgLatencyMs, "avgWpm" to f.avgWpm, "avgPauses" to f.avgPauses)
        },
        "commute" to obj(
            "morningDepartures" to s.commute.morningDepartures, "eveningDepartures" to s.commute.eveningDepartures, "trips" to s.commute.trips,
        ),
        "saved" to s.saved.map { e -> obj("text" to e.text, "itemId" to e.itemId, "savedMs" to e.savedMs) },
        "mistakes" to s.mistakes.map { e -> obj("itemId" to e.itemId, "day" to e.day, "verdict" to e.verdict, "atMs" to e.atMs) },
        "sessions" to s.sessions.map { r ->
            obj(
                "id" to r.id, "startedMs" to r.startedMs, "endedMs" to r.endedMs, "slot" to r.slot, "mode" to r.mode, "track" to r.track,
                "answered" to r.answered, "correct" to r.correct, "partial" to r.partial, "interrupted" to r.interrupted, "xp" to r.xp,
            )
        },
    )

    /** null 값은 쓰지 않는다 (읽을 때 기본값) */
    private fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(pairs.size)
        for ((k, v) in pairs) if (v != null) m[k] = v
        return m
    }

    // ───────────── 읽기 ─────────────

    fun fromJson(m: Map<String, Any?>): LearnerState {
        val d = LearnerState()
        return LearnerState(
            schema = SCHEMA,
            profile = m.jObj("profile")?.let { safe { profileOf(it) } },
            settings = m.jObj("settings")?.let { safe { settingsOf(it) } } ?: d.settings,
            progress = m.jObj("progress")?.let { safe { progressOf(it) } } ?: d.progress,
            cards = cardsOf(m.jObj("cards")),
            pron = m.jObj("pron")?.let { safe { pronOf(it) } } ?: d.pron,
            fluency = m.jObjList("fluency").mapNotNull { safe { fluencyOf(it) } }.sortedBy { it.day },
            commute = m.jObj("commute")?.let { safe { commuteOf(it) } } ?: d.commute,
            saved = m.jObjList("saved").mapNotNull { safe { savedOf(it) } },
            mistakes = m.jObjList("mistakes").mapNotNull { safe { mistakeOf(it) } },
            sessions = m.jObjList("sessions").mapNotNull { safe { sessionOf(it) } },
        )
    }

    private inline fun <T> safe(block: () -> T?): T? = try { block() } catch (e: Exception) { null }

    private fun profileOf(m: Map<String, Any?>): LearnerProfile? {
        val track = m.jEnum<Track>("track") ?: return null
        return LearnerProfile(
            track = track,
            goal = m.jEnum<DailyGoal>("goal") ?: DailyGoal.STEADY,
            level = m.jEnum<Level>("level") ?: Level.BEGINNER,
            placementDone = m.jBool("placementDone") ?: false,
            placementCorrect = m.jInt("placementCorrect"),
            createdDay = m.jLong("createdDay") ?: 0L,
        )
    }

    private fun settingsOf(m: Map<String, Any?>): LearnerSettings {
        val d = LearnerSettings()
        val rate = m.jFloat("englishRate")?.takeIf { !it.isNaN() }?.coerceIn(0.5f, 1.5f)
        return LearnerSettings(
            morningMode = m.jEnum<SlotMode>("morningMode") ?: d.morningMode,
            eveningMode = m.jEnum<SlotMode>("eveningMode") ?: d.eveningMode,
            otherMode = m.jEnum<SlotMode>("otherMode") ?: d.otherMode,
            autoStartOnCommute = m.jBool("autoStartOnCommute") ?: d.autoStartOnCommute,
            eveningStudy = m.jEnum<EveningStudy>("eveningStudy") ?: d.eveningStudy,
            soundEffects = m.jBool("soundEffects") ?: d.soundEffects,
            ambientPulse = m.jBool("ambientPulse") ?: d.ambientPulse,
            hudHint = m.jBool("hudHint") ?: d.hudHint,
            englishRate = rate ?: d.englishRate,
            contextHooks = m.jBool("contextHooks") ?: d.contextHooks,
            mirrorSimRouteToCar = m.jBool("mirrorSimRouteToCar") ?: d.mirrorSimRouteToCar,
        )
    }

    private fun progressOf(m: Map<String, Any?>): ProgressState {
        val units = LinkedHashMap<String, UnitRecord>()
        m.jObj("units")?.forEach { (k, v) -> asObj(v)?.let { safe { unitOf(k, it) } }?.let { units[k] = it } }
        return ProgressState(
            xp = m.jInt("xp") ?: 0,
            streakDays = m.jInt("streakDays") ?: 0,
            bestStreak = m.jInt("bestStreak") ?: 0,
            lastActiveDay = m.jLong("lastActiveDay"),
            days = m.jObjList("days").mapNotNull { safe { dayOf(it) } }.sortedBy { it.day },
            units = units,
            totalAnswered = m.jInt("totalAnswered") ?: 0,
            totalCorrect = m.jInt("totalCorrect") ?: 0,
        )
    }

    private fun dayOf(m: Map<String, Any?>): DayStat? {
        val day = m.jLong("day") ?: return null
        return DayStat(
            day = day,
            answered = m.jInt("answered") ?: 0,
            correct = m.jInt("correct") ?: 0,
            partial = m.jInt("partial") ?: 0,
            newLearned = m.jInt("newLearned") ?: 0,
            reviewed = m.jInt("reviewed") ?: 0,
            xp = m.jInt("xp") ?: 0,
            driveMinutes = m.jInt("driveMinutes") ?: 0,
        )
    }

    private fun unitOf(key: String, m: Map<String, Any?>): UnitRecord {
        val scores = LinkedHashMap<String, Float>()
        m.jObj("scores")?.forEach { (id, v) -> (v as? Number)?.toFloat()?.takeIf { !it.isNaN() }?.let { scores[id] = it } }
        return UnitRecord(
            unitId = m.jStr("unitId") ?: key,
            scores = scores,
            passed = m.jBool("passed") ?: false,
            passedAtMs = m.jLong("passedAtMs"),
        )
    }

    private fun cardsOf(m: Map<String, Any?>?): Map<String, CardState> {
        val out = LinkedHashMap<String, CardState>()
        m?.forEach { (key, v) ->
            val c = asObj(v) ?: return@forEach
            safe {
                CardState(
                    itemId = c.jStr("itemId") ?: key,
                    reps = c.jInt("reps") ?: 0,
                    lapses = c.jInt("lapses") ?: 0,
                    ease = c.jDouble("ease")?.takeIf { !it.isNaN() } ?: 2.5,
                    intervalDays = c.jDouble("intervalDays")?.takeIf { !it.isNaN() } ?: 0.0,
                    dueAtMs = c.jLong("dueAtMs") ?: 0L,
                    lastReviewedMs = c.jLong("lastReviewedMs") ?: 0L,
                    lastGrade = c.jEnum<Grade>("lastGrade"),
                    introducedMs = c.jLong("introducedMs") ?: 0L,
                )
            }?.let { out[key] = it }
        }
        return out
    }

    private fun pronOf(m: Map<String, Any?>): PronProfile {
        val counts = LinkedHashMap<PronIssueType, Int>()
        m.jObj("counts")?.forEach { (k, v) ->
            val type = PronIssueType.entries.firstOrNull { it.name == k } ?: return@forEach
            (v as? Number)?.toInt()?.let { counts[type] = it }
        }
        val recent = m.jObjList("recent").mapNotNull { r ->
            safe {
                val type = r.jEnum<PronIssueType>("type") ?: return@safe null
                PronIssueRecord(type, r.jStr("expected").orEmpty(), r.jStr("heard").orEmpty(), r.jLong("atMs") ?: 0L)
            }
        }
        return PronProfile(counts = counts, recent = recent)
    }

    private fun fluencyOf(m: Map<String, Any?>): FluencyDay? {
        val day = m.jLong("day") ?: return null
        return FluencyDay(
            day = day,
            samples = m.jInt("samples") ?: 0,
            avgLatencyMs = m.jLong("avgLatencyMs") ?: 0L,
            avgWpm = m.jInt("avgWpm") ?: 0,
            avgPauses = m.jFloat("avgPauses")?.takeIf { !it.isNaN() } ?: 0f,
        )
    }

    private fun commuteOf(m: Map<String, Any?>): CommutePattern = CommutePattern(
        morningDepartures = m.jArr("morningDepartures")?.mapNotNull { (it as? Number)?.toInt() }.orEmpty(),
        eveningDepartures = m.jArr("eveningDepartures")?.mapNotNull { (it as? Number)?.toInt() }.orEmpty(),
        trips = m.jInt("trips") ?: 0,
    )

    private fun savedOf(m: Map<String, Any?>): SavedExpression? = SavedExpression(
        text = m.jStr("text") ?: return null,
        itemId = m.jStr("itemId"),
        savedMs = m.jLong("savedMs") ?: 0L,
    )

    private fun mistakeOf(m: Map<String, Any?>): Mistake? = Mistake(
        itemId = m.jStr("itemId") ?: return null,
        day = m.jLong("day") ?: return null,
        verdict = m.jEnum<Verdict>("verdict") ?: Verdict.WRONG,
        atMs = m.jLong("atMs") ?: 0L,
    )

    private fun sessionOf(m: Map<String, Any?>): SessionRecord? = SessionRecord(
        id = m.jStr("id") ?: return null,
        startedMs = m.jLong("startedMs") ?: 0L,
        endedMs = m.jLong("endedMs") ?: 0L,
        slot = m.jEnum<CommuteSlot>("slot") ?: CommuteSlot.OTHER,
        mode = m.jEnum<SlotMode>("mode") ?: SlotMode.MIXED,
        track = m.jEnum<Track>("track") ?: return null,
        answered = m.jInt("answered") ?: 0,
        correct = m.jInt("correct") ?: 0,
        partial = m.jInt("partial") ?: 0,
        interrupted = m.jInt("interrupted") ?: 0,
        xp = m.jInt("xp") ?: 0,
    )

    @Suppress("UNCHECKED_CAST")
    private fun asObj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
}
