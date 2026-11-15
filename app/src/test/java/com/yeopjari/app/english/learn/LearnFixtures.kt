package com.yeopjari.app.english.learn

import com.yeopjari.app.english.core.AnswerOutcome
import com.yeopjari.app.english.core.CardState
import com.yeopjari.app.english.core.ContentSource
import com.yeopjari.app.english.core.ContextHookContent
import com.yeopjari.app.english.core.DailyGoal
import com.yeopjari.app.english.core.FluencyMetrics
import com.yeopjari.app.english.core.FluencyRating
import com.yeopjari.app.english.core.Grade
import com.yeopjari.app.english.core.HookTrigger
import com.yeopjari.app.english.core.InMemorySecureStore
import com.yeopjari.app.english.core.JudgeResult
import com.yeopjari.app.english.core.Kst
import com.yeopjari.app.english.core.LearnerProfile
import com.yeopjari.app.english.core.LearningItem
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.PlacementItem
import com.yeopjari.app.english.core.PronIssue
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.SecureStore
import com.yeopjari.app.english.core.Skill
import com.yeopjari.app.english.core.Track
import com.yeopjari.app.english.core.UnitInfo
import com.yeopjari.app.english.core.UnitKey
import com.yeopjari.app.english.core.Verdict
import com.yeopjari.app.english.core.VirtualTime

/** 학습 모듈 테스트용 가짜 콘텐츠 (실제 JSON 로더는 content 담당) */
class FakeContent(
    private val all: List<LearningItem>,
    private val unitInfos: List<UnitInfo>,
    private val placementPool: List<PlacementItem> = emptyList(),
    private val hooks: Map<HookTrigger, ContextHookContent> = emptyMap(),
) : ContentSource {
    private val byId = all.associateBy { it.id }
    override val version: String = "test-${all.size}"
    override fun items(): List<LearningItem> = all
    override fun item(id: String): LearningItem? = byId[id]
    override fun units(track: Track, level: Level): List<UnitInfo> =
        unitInfos.filter { it.key.track == track && it.key.level == level }.sortedBy { it.key.unit }
    override fun unit(key: UnitKey): UnitInfo? = unitInfos.firstOrNull { it.key == key }
    override fun unitItems(key: UnitKey): List<LearningItem> = unit(key)?.itemIds?.mapNotNull { byId[it] }.orEmpty()
    override fun placement(track: Track): List<PlacementItem> = placementPool.filter { it.track == null || it.track == track }
    override fun hook(trigger: HookTrigger): ContextHookContent? = hooks[trigger]
    override fun pronDrills(type: PronIssueType): List<LearningItem> =
        all.filter { it.unit == 0 && it.skill == Skill.MINIMAL_PAIR && it.pronFocus == type }
    override fun quizItems(track: Track, level: Level): List<LearningItem> =
        all.filter { it.track == track && it.level == level && it.distractors.size >= 2 }
}

/** 쓰기·읽기 횟수를 세는 저장소 */
class CountingStore(override val encrypted: Boolean = true) : SecureStore {
    val inner = InMemorySecureStore()
    var writes = 0
    var reads = 0
    var deletes = 0
    override fun write(name: String, bytes: ByteArray): Boolean { writes++; return inner.write(name, bytes) }
    override fun read(name: String): ByteArray? { reads++; return inner.read(name) }
    override fun delete(name: String) { deletes++; inner.delete(name) }
    override fun size(name: String): Long = inner.size(name)
}

object LearnFixtures {
    val NOW: Long = VirtualTime.START_MS // 2026-11-16(월) 07:40 KST
    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR
    val TODAY: Long = Kst.epochDay(NOW)

    fun prefix(track: Track) = when (track) {
        Track.BUSINESS -> "biz"
        Track.DAILY -> "day"
        Track.TRAVEL -> "trv"
        Track.TOEIC_SPEAKING -> "toe"
    }

    fun id(track: Track, level: Level, unit: Int, n: Int) =
        "${prefix(track)}.${level.name.first().lowercaseChar()}$unit.${n.toString().padStart(2, '0')}"

    private const val SHORT_SENTENCE = "Can we push the meeting to tomorrow?"
    private const val LONG_SENTENCE = "Could you please send me the updated version of the report before the meeting tomorrow?"
    private const val TOEIC_ANSWER =
        "I usually drive to work. It takes about thirty minutes, and I listen to English lessons on the way."

    /** 유닛 하나(10문항). 트랙 파일 규칙: 일반 = 9 NORMAL + 1 RELAXED(긴 문장), 토익 = 6 NORMAL + 4 TOEIC_QA(RELAXED) */
    fun unitItems(track: Track, level: Level, unit: Int): List<LearningItem> {
        val skills = if (track == Track.TOEIC_SPEAKING) listOf(
            Skill.VOCAB, Skill.VOCAB, Skill.EXPRESSION, Skill.SENTENCE_BUILD, Skill.SENTENCE_BUILD,
            Skill.LISTEN_REPEAT, Skill.TOEIC_QA, Skill.TOEIC_QA, Skill.TOEIC_QA, Skill.TOEIC_QA,
        ) else listOf(
            Skill.VOCAB, Skill.VOCAB, Skill.VOCAB, Skill.EXPRESSION, Skill.EXPRESSION,
            Skill.SENTENCE_BUILD, Skill.SENTENCE_BUILD, Skill.SENTENCE_BUILD, Skill.LISTEN_REPEAT, Skill.MINIMAL_PAIR,
        )
        return skills.mapIndexed { i, skill ->
            val n = i + 1
            val longOne = track != Track.TOEIC_SPEAKING && n == 8
            val answer = when (skill) {
                Skill.VOCAB -> "postpone"
                Skill.EXPRESSION -> "I'm running a little late."
                Skill.SENTENCE_BUILD -> if (longOne) LONG_SENTENCE else SHORT_SENTENCE
                Skill.LISTEN_REPEAT -> "Let's touch base next week."
                Skill.TOEIC_QA -> TOEIC_ANSWER
                Skill.MINIMAL_PAIR -> "right"
            }
            LearningItem(
                id = id(track, level, unit, n),
                track = track,
                level = level,
                unit = unit,
                skill = skill,
                promptKo = "문항 $n",
                promptEn = if (skill == Skill.LISTEN_REPEAT || skill == Skill.TOEIC_QA || skill == Skill.MINIMAL_PAIR) answer else null,
                answers = listOf(answer),
                keywords = listOf(answer.split(' ').first().lowercase()),
                hintWord = answer.split(' ').first(),
                meaningKo = "뜻 $n",
                maxLoad = if (skill == Skill.TOEIC_QA || longOne) LoadLevel.RELAXED else LoadLevel.NORMAL,
                distractors = if (skill == Skill.VOCAB) listOf("prepare", "propose") else emptyList(),
                minWords = if (skill == Skill.TOEIC_QA) 15 else 0,
                contrast = if (skill == Skill.MINIMAL_PAIR) "light" else null,
                pronFocus = if (skill == Skill.MINIMAL_PAIR) PronIssueType.R_L else null,
            )
        }
    }

    fun hookItem(id: String, trigger: HookTrigger) = LearningItem(
        id = id, track = Track.DAILY, level = Level.BEGINNER, unit = 0, skill = Skill.EXPRESSION,
        promptKo = "‘비가 억수같이 와요’를 영어로", answers = listOf("It's pouring."), keywords = listOf("pouring"),
        hintWord = "pouring", meaningKo = "비가 억수같이 와요.", contextHook = trigger,
    )

    fun drill(n: Int, type: PronIssueType = PronIssueType.R_L) = LearningItem(
        id = "pron.${type.name.lowercase()}.${n.toString().padStart(2, '0')}", track = Track.DAILY, level = Level.BEGINNER,
        unit = 0, skill = Skill.MINIMAL_PAIR, promptKo = "발음 연습 $n", promptEn = "right", answers = listOf("right"),
        keywords = listOf("right"), meaningKo = "오른쪽", contrast = "light", pronFocus = type,
    )

    /** 레벨 테스트 풀: 트랙마다 레벨당 4개, 공통 레벨당 8개 */
    fun placementPool(tracks: List<Track> = Track.entries, trackPerLevel: Int = 4, generalPerLevel: Int = 8): List<PlacementItem> {
        val out = ArrayList<PlacementItem>()
        for (level in Level.entries) {
            val l = level.name.first().lowercaseChar()
            repeat(generalPerLevel) { i -> out += placement("pl.g.$l.${(i + 1).toString().padStart(2, '0')}", null, level) }
            for (t in tracks) repeat(trackPerLevel) { i ->
                out += placement("pl.${prefix(t)}.$l.${(i + 1).toString().padStart(2, '0')}", t, level)
            }
        }
        return out
    }

    fun placement(id: String, track: Track?, level: Level, answerIndex: Int = 0) = PlacementItem(
        id = id, track = track, level = level, skill = Skill.VOCAB, questionKo = "알맞은 단어를 고르세요.",
        choices = listOf("meeting", "message", "machine"), answerIndex = answerIndex,
    )

    /**
     * 표준 가짜 콘텐츠: 트랙마다 레벨당 [unitsPerLevel] 유닛 × 10문항 + 상황 훅(RAIN 2, JAM_EXIT 1) + R/L 드릴 8개 + 레벨 테스트 풀.
     * [unitsOverride] 로 특정 (트랙, 레벨)의 유닛 수를 바꾼다.
     */
    fun content(
        tracks: List<Track> = Track.entries,
        unitsPerLevel: Int = 3,
        unitsOverride: Map<Pair<Track, Level>, Int> = emptyMap(),
        extraItems: List<LearningItem> = emptyList(),
    ): FakeContent {
        val items = ArrayList<LearningItem>()
        val units = ArrayList<UnitInfo>()
        for (t in tracks) for (level in Level.entries) {
            val count = unitsOverride[t to level] ?: unitsPerLevel
            for (u in 1..count) {
                val unitItems = unitItems(t, level, u)
                items += unitItems
                units += UnitInfo(UnitKey(t, level, u), "${t.ko} ${level.ko} $u", "Unit $u", unitItems.map { it.id })
            }
        }
        val rain = listOf(hookItem("hook.rain.01", HookTrigger.RAIN), hookItem("hook.rain.02", HookTrigger.RAIN))
        val jam = listOf(hookItem("hook.jam.01", HookTrigger.JAM_EXIT))
        val drills = (1..8).map { drill(it) }
        items += rain + jam + drills + extraItems
        return FakeContent(
            all = items,
            unitInfos = units,
            placementPool = placementPool(tracks),
            hooks = mapOf(
                HookTrigger.RAIN to ContextHookContent(HookTrigger.RAIN, "비가 쏟아지네요. 이럴 땐 이렇게 말해요.", rain),
                HookTrigger.JAM_EXIT to ContextHookContent(HookTrigger.JAM_EXIT, "드디어 정체를 빠져나왔네요.", jam),
            ),
        )
    }

    fun profile(track: Track = Track.BUSINESS, level: Level = Level.BEGINNER, goal: DailyGoal = DailyGoal.STEADY) =
        LearnerProfile(track = track, goal = goal, level = level, placementDone = true, placementCorrect = 8, createdDay = TODAY)

    /** 복습 때가 된 카드 (앞쪽일수록 많이 밀림) */
    fun dueCards(ids: List<String>, nowMs: Long = NOW): Map<String, CardState> =
        ids.mapIndexed { i, id ->
            id to CardState(
                itemId = id, reps = 2, ease = 2.5, intervalDays = 3.0,
                dueAtMs = nowMs - (ids.size - i) * HOUR, lastReviewedMs = nowMs - 3 * DAY, lastGrade = Grade.GOOD,
                introducedMs = nowMs - 5 * DAY,
            )
        }.toMap(LinkedHashMap())

    /** 아직 때가 안 된 카드 */
    fun futureCards(ids: List<String>, nowMs: Long = NOW, ease: Double = 2.5): Map<String, CardState> =
        ids.associateWith { id ->
            CardState(itemId = id, reps = 1, ease = ease, intervalDays = 1.0, dueAtMs = nowMs + DAY, lastReviewedMs = nowMs - HOUR, lastGrade = Grade.GOOD, introducedMs = nowMs - HOUR)
        }

    fun judge(verdict: Verdict, pron: List<PronIssue> = emptyList()) = JudgeResult(
        verdict = verdict,
        score = when (verdict) { Verdict.CORRECT -> 100; Verdict.PARTIAL -> 70; else -> 0 },
        heard = "heard", normalized = "heard", matchedAnswer = null, pronIssues = pron,
        feedbackKo = verdict.ko,
    )

    fun fluency(latencyMs: Long?, wpm: Int? = null, pauses: Int = 0) = FluencyMetrics(
        latencyMs = latencyMs, speechMs = 2_000L, wordsPerMinute = wpm, pauseCount = pauses, longestPauseMs = 0L,
        rating = FluencyRating.OK, estimated = false, noteKo = "",
    )

    fun outcome(
        item: LearningItem,
        verdict: Verdict,
        grade: Grade = when (verdict) {
            Verdict.CORRECT -> Grade.GOOD
            Verdict.PARTIAL -> Grade.HARD
            else -> Grade.AGAIN
        },
        isNew: Boolean = true,
        pron: List<PronIssue> = emptyList(),
        fluency: FluencyMetrics? = null,
    ) = AnswerOutcome(
        item = item, result = judge(verdict, pron), grade = grade, isNew = isNew, fluency = fluency,
        driving = true, sessionId = "s-test", latencyMs = fluency?.latencyMs,
    )
}
