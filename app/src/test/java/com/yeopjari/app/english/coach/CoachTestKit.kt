package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CardState
import com.yeopjari.app.english.core.CoachDeps
import com.yeopjari.app.english.core.CoachPhase
import com.yeopjari.app.english.core.CommuteMap
import com.yeopjari.app.english.core.CommutePlace
import com.yeopjari.app.english.core.CommuteRoute
import com.yeopjari.app.english.core.ContextHookContent
import com.yeopjari.app.english.core.DailyGoal
import com.yeopjari.app.english.core.DestinationCategory
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.HookTrigger
import com.yeopjari.app.english.core.Kst
import com.yeopjari.app.english.core.LatLng
import com.yeopjari.app.english.core.LearnerProfile
import com.yeopjari.app.english.core.LearnerSettings
import com.yeopjari.app.english.core.LearnerState
import com.yeopjari.app.english.core.LearningItem
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.MutableSignalSource
import com.yeopjari.app.english.core.PlacementItem
import com.yeopjari.app.english.core.PlanKind
import com.yeopjari.app.english.core.PlannedItem
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.RoadKind
import com.yeopjari.app.english.core.RouteSegment
import com.yeopjari.app.english.core.Skill
import com.yeopjari.app.english.core.Track
import com.yeopjari.app.english.core.VirtualTime

/** 테스트 콘텐츠 · 지도 (가상, 공공 랜드마크만) */
object TestContent {
    private fun item(
        id: String, skill: Skill, promptKo: String, answers: List<String>, meaningKo: String,
        promptEn: String? = null, keywords: List<String> = emptyList(), hint: String? = null,
        distractors: List<String> = emptyList(), maxLoad: LoadLevel = LoadLevel.NORMAL, unit: Int = 1,
        track: Track = Track.DAILY, level: Level = Level.BEGINNER, minWords: Int = 0, listenSec: Int? = null,
        contrast: String? = null, pron: PronIssueType? = null, hook: HookTrigger? = null,
    ) = LearningItem(
        id = id, track = track, level = level, unit = unit, skill = skill, promptKo = promptKo, promptEn = promptEn,
        answers = answers, keywords = keywords, hintWord = hint, meaningKo = meaningKo, explanationKo = "해설: $meaningKo",
        maxLoad = maxLoad, distractors = distractors, contextHook = hook, minWords = minWords, listenSec = listenSec,
        contrast = contrast, pronFocus = pron,
    )

    val vocab = item("day.b1.01", Skill.VOCAB, "‘미루다’를 영어 한 단어로?", listOf("postpone", "delay"), "미루다, 연기하다",
        keywords = listOf("postpone"), hint = "postpone", distractors = listOf("prepare", "propose"))
    val expression = item("day.b1.02", Skill.EXPRESSION, "약속에 늦을 것 같을 때 한마디?", listOf("I'm running late.", "I'm running a little late."),
        "좀 늦을 것 같아요.", keywords = listOf("late"), hint = "running", distractors = listOf("I'm running fast.", "I'm late running."))
    val sentence = item("day.b1.03", Skill.SENTENCE_BUILD, "‘회의를 내일로 미룰 수 있을까요?’를 영어로",
        listOf("Can we push the meeting to tomorrow?", "Could we move the meeting to tomorrow?"), "회의를 내일로 미룰 수 있을까요?",
        keywords = listOf("meeting", "tomorrow"), hint = "push",
        distractors = listOf("Can we pull the meeting tomorrow?", "Can we push tomorrow to the meeting?"))
    val repeat = item("day.b1.04", Skill.LISTEN_REPEAT, "듣고 따라 말해 보세요.", listOf("Traffic is really heavy this morning."),
        "오늘 아침 차가 정말 많이 막혀요.", promptEn = "Traffic is really heavy this morning.", keywords = listOf("traffic", "heavy"), hint = "heavy")
    val minimal = item("day.b1.05", Skill.MINIMAL_PAIR, "R 발음 연습. ‘light’가 아니라 ‘right’예요.", listOf("right"), "오른쪽, 옳은",
        promptEn = "right", keywords = listOf("right"), contrast = "light", pron = PronIssueType.R_L, listenSec = 6)
    val toeic = item("day.b1.06", Skill.TOEIC_QA, "토익 스피킹 질문이에요. 30초 동안 답해 보세요.",
        listOf("I usually drive to work and it takes about thirty minutes."), "보통 어떻게 출근하나요?",
        promptEn = "How do you usually get to work?", keywords = listOf("usually", "drive", "work"), hint = "usually",
        maxLoad = LoadLevel.RELAXED, minWords = 15, listenSec = 30)

    private val words = listOf(
        "사과" to "apple", "창문" to "window", "우산" to "umbrella", "열쇠" to "key", "의자" to "chair", "시계" to "clock",
        "기차" to "train", "다리" to "bridge", "도서관" to "library", "병원" to "hospital", "주차장" to "parking lot",
        "교차로" to "intersection", "신호등" to "traffic light", "사무실" to "office",
    )
    val fillers: List<LearningItem> = words.mapIndexed { i, (ko, en) ->
        item("day.b2.%02d".format(i + 1), Skill.VOCAB, "‘$ko’를 영어로?", listOf(en), ko, keywords = listOf(en.split(' ').first()),
            hint = en.split(' ').first(), distractors = listOf("table", "paper"), unit = 2)
    }

    val jamHook = item("hook.jam.01", Skill.SENTENCE_BUILD, "‘차가 막혀서 꼼짝 못 했어요’를 영어로",
        listOf("I was stuck in traffic.", "I got stuck in traffic."), "차가 막혀서 꼼짝 못 했어요.", keywords = listOf("stuck", "traffic"),
        hint = "stuck", unit = 0, hook = HookTrigger.JAM_EXIT)
    val airportHook = item("hook.air.01", Skill.VOCAB, "‘탑승권’을 영어로?", listOf("boarding pass"), "탑승권",
        keywords = listOf("boarding"), hint = "boarding", unit = 0, track = Track.TRAVEL, hook = HookTrigger.AIRPORT)
    val travelNew = item("trv.b1.01", Skill.VOCAB, "‘수하물’을 영어로?", listOf("baggage", "luggage"), "수하물, 짐",
        keywords = listOf("baggage"), hint = "baggage", distractors = listOf("package", "passage"), track = Track.TRAVEL)

    const val JAM_INTRO = "드디어 정체를 빠져나왔네요. 방금 같은 상황, 영어로는요."

    val trackItems = listOf(vocab, expression, sentence, repeat, minimal, toeic) + fillers + travelNew

    val placementItems: List<PlacementItem> = Level.entries.flatMap { l ->
        (1..5).map { n -> PlacementItem("pl.${l.name.lowercase()}.$n", null, l, Skill.VOCAB, "${l.ko} 문제 $n", null, listOf("정답", "오답 1", "오답 2"), 0) }
    }

    fun source() = FakeContent(
        trackItems,
        mapOf(
            HookTrigger.JAM_EXIT to ContextHookContent(HookTrigger.JAM_EXIT, JAM_INTRO, listOf(jamHook)),
            HookTrigger.AIRPORT to ContextHookContent(HookTrigger.AIRPORT, "공항 가는 길이네요.", listOf(airportHook)),
        ),
        placementItems,
    )

    private fun seg(id: String, name: String, road: RoadKind) =
        RouteSegment(id, name, road, LatLng(37.5, 127.1), LatLng(37.4, 127.1), 2_000, 60, 50)

    val map = CommuteMap(
        id = "map.test", version = 1, nameKo = "가상 출퇴근 지도 · 테스트",
        places = listOf(
            CommutePlace("place.home", "집 (잠실역 인근)", DestinationCategory.HOME, LatLng(37.513, 127.100)),
            CommutePlace("place.office", "회사 (판교테크노밸리)", DestinationCategory.OFFICE, LatLng(37.401, 127.108)),
        ),
        segments = listOf(
            seg("seg.local", "집 앞 이면도로", RoadKind.LOCAL),
            seg("seg.tancheon", "탄천 강변길", RoadKind.RIVERSIDE),
            seg("seg.highway", "분당수서로 고속 구간", RoadKind.HIGHWAY),
        ),
        routes = listOf(
            CommuteRoute("route.morning", "place.home", "place.office", listOf("seg.local", "seg.tancheon", "seg.highway")),
            CommuteRoute("route.evening", "place.office", "place.home", listOf("seg.highway", "seg.tancheon", "seg.local")),
        ),
    )

    fun profile(placementDone: Boolean = true) =
        LearnerProfile(Track.DAILY, DailyGoal.STEADY, Level.BEGINNER, placementDone = placementDone, createdDay = Kst.epochDay(VirtualTime.START_MS) - 3)

    /** 복습 예정 카드 n 개 (filler 문항) */
    fun dueCards(n: Int, nowMs: Long): Map<String, CardState> =
        fillers.take(n).associate { it.id to CardState(it.id, reps = 1, dueAtMs = nowMs - 60_000L, lastReviewedMs = nowMs - 86_400_000L) }
}

/** 가상 시간 위의 코치 + 모든 가짜 포트 */
class CoachHarness(
    state: LearnerState = LearnerState(profile = TestContent.profile(), settings = LearnerSettings(autoStartOnCommute = false)),
    demo: Boolean = true,
) {
    val t = VirtualTime()
    val scope = t.scope()
    val signals = MutableSignalSource(t)
    val content = TestContent.source()
    val repo = FakeRepository(state)
    val scheduler = FakeScheduler()
    val placementEngine = FakePlacement()
    val progression = FakeProgression(scheduler)
    val planner = FakePlanner(scheduler)
    val judge = FakeJudge()
    val commands = FakeCommands()
    val pron = FakePron()
    val fluency = FakeFluency()
    val speech = FakeSpeech(t)
    val loadEngine = FakeLoadEngine()
    val policy = FakePolicy()
    val hooks = FakeHooks()
    val commute = FakeCommute()
    val feedback = FakeFeedback(t)
    val keys = FakeMediaKeys()
    val sound = FakeSound()
    val deps = CoachDeps(
        t, content, repo, scheduler, placementEngine, progression, planner, judge, commands, pron, fluency, speech,
        signals, loadEngine, policy, hooks, commute, feedback, keys, TestContent.map, demo, sound,
    )
    val coach = DefaultEnglishCoach(deps, scope)
    val ui: EnglishUiState get() = coach.state.value

    fun start(): CoachHarness {
        coach.start()
        t.runCurrent()
        return this
    }

    /** 가상 시간을 흘리며 250ms 마다 신호 하트비트 */
    fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(250L, left)
            t.advanceBy(step)
            signals.tick()
            left -= step
        }
    }

    /** 조건이 될 때까지 (최대 maxMs) 시간을 흘린다 */
    fun advanceUntil(maxMs: Long = 60_000L, cond: () -> Boolean): Boolean {
        var waited = 0L
        while (!cond()) {
            if (waited >= maxMs) return false
            advance(50L)
            waited += 50L
        }
        return true
    }

    fun waitPhase(phase: CoachPhase, maxMs: Long = 60_000L): Boolean = advanceUntil(maxMs) { ui.phase == phase }

    fun set(transform: (DrivingSignals) -> DrivingSignals) = signals.set(transform)

    fun driveMorning(remainingSec: Int = 1_920, segment: String = "seg.local", driveId: String = "drive-1", speed: Float = 50f) = set {
        it.copy(
            gear = GearPosition.D, speedKph = speed, speedLimitKph = 60, uxRestricted = true, routeActive = true,
            routeRemainingSec = remainingSec, originPlaceId = "place.home", destinationPlaceId = "place.office",
            destinationCategory = DestinationCategory.OFFICE, segmentId = segment, segmentName = TestContent.map.segment(segment)?.nameKo,
            driveId = driveId,
        )
    }

    /** 퇴근길: 회사 → 집 (가짜 출퇴근 판단이 경로 방향으로 EVENING 0.9 를 준다) */
    fun driveEvening(remainingSec: Int = 1_680, segment: String = "seg.highway", driveId: String = "drive-e1", speed: Float = 50f) = set {
        it.copy(
            gear = GearPosition.D, speedKph = speed, speedLimitKph = 60, uxRestricted = true, routeActive = true,
            routeRemainingSec = remainingSec, originPlaceId = "place.office", destinationPlaceId = "place.home",
            destinationCategory = DestinationCategory.HOME, segmentId = segment, segmentName = TestContent.map.segment(segment)?.nameKo,
            driveId = driveId,
        )
    }

    fun park() = set { it.copy(gear = GearPosition.P, speedKph = 0f, uxRestricted = false) }

    /** 다음 문항을 듣는 단계까지 */
    fun waitListening(itemId: String, maxMs: Long = 20_000L): Boolean =
        advanceUntil(maxMs) { ui.prompt?.itemId == itemId && ui.phase == CoachPhase.LISTENING }

    fun script(vararg items: Pair<LearningItem, PlanKind>) {
        planner.script = items.map { PlannedItem(it.first.id, it.second) }
    }

    /** 세션 시작 → 첫 문항 듣기까지 */
    fun startAndListen(maxMs: Long = 30_000L): Boolean {
        coach.startSession()
        return waitPhase(CoachPhase.LISTENING, maxMs)
    }
}
