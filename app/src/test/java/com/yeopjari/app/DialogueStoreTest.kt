package com.yeopjari.app

import com.yeopjari.app.ai.context.DriveContext
import com.yeopjari.app.ai.context.EnergyModel
import com.yeopjari.app.ai.context.FatigueModel
import com.yeopjari.app.ai.context.LoadLevel
import com.yeopjari.app.ai.context.LoadModel
import com.yeopjari.app.ai.context.UserProfile
import com.yeopjari.app.ai.nlu.KeywordIntentEngine
import com.yeopjari.app.ai.nlu.NluResult
import com.yeopjari.app.ai.personal.PersonaFactory
import com.yeopjari.app.ai.personal.PersonalBrain
import com.yeopjari.app.ai.personal.PlaceQuery
import com.yeopjari.app.ai.personal.QueryParser
import com.yeopjari.app.ai.personal.Reply
import com.yeopjari.app.ai.personal.Resolution
import com.yeopjari.app.ai.personal.Situation
import com.yeopjari.app.ai.personal.StopMode
import com.yeopjari.app.ai.personal.Tier
import com.yeopjari.app.platform.gleo.GleoBridge
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.model.AgentPhase
import com.yeopjari.app.core.model.Moment
import com.yeopjari.app.core.model.MomentType
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.Urgency
import com.yeopjari.app.core.model.VehicleSnapshot
import com.yeopjari.app.sim.ScenarioId
import com.yeopjari.app.sim.SimFlags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 되묻기 대화 흐름의 회귀 테스트 (재검토 D1~D5 · D8 · D9 · D11).
 *  - 앞부분: 상태를 바꾸지 않는 판단 규칙([DialoguePolicy] · [MomentPolicy])을 엔진의 실제 보정 결과로 확인
 *  - 뒷부분: 실제 저장소(YeopjariStore) + 시뮬레이터 + 개인화 서비스를 가상 시계 위에서 돌려 경로·학습·마이크를 확인
 */
class DialogueStoreTest {

    // ───────────────────────── 판단 규칙 (순수 함수) ─────────────────────────

    private val base = PersonaFactory.BASE_DAY
    private val parents = PersonaFactory.poiById("parents")!!.place

    /** P_DAEJEON: 토요일 10:04, 경부고속도로 위, 목적지 대전 본가 */
    private fun daejeon() = Situation(base, LocalDate.ofEpochDay(base.toLong()).dayOfWeek.value, 10 * 60 + 4, 36.482, 127.347, parents.lat, parents.lng)

    private fun ctx(): DriveContext {
        val v = VehicleSnapshot()
        val r = RouteState()
        return DriveContext(
            vehicle = v, route = r, clockSec = 10 * 3600f, clockLabel = "10:00", flags = SimFlags(), profile = UserProfile(),
            energy = EnergyModel.forecast(v, r), fatigue = FatigueModel.assess(v, r, 10), load = LoadModel.estimate(v, r),
        )
    }

    private val classify: (String) -> NluResult = { KeywordIntentEngine.rules(it, ctx()) }

    /** 처음 '서심다응' → 대전역점 한 번 확인 */
    private val first: Resolution = PersonalBrain().resolve(PlaceQuery("서심다응"), daejeon())

    /** "응" 한 번 배운 뒤 같은 말 → 대전역점 바로 실행 */
    private val learned: Resolution = PersonalBrain().let { b ->
        b.learn(b.resolve(PlaceQuery("서심다응"), daejeon()), "ssd_station")
        b.resolve(PlaceQuery("서심다응"), daejeon())
    }

    private fun confirm() = Clarify(first, ClarifyStage.CONFIRM, listOf(first.top!!))
    private fun undo() = Clarify(learned, ClarifyStage.UNDO, listOf(learned.top!!), prevRoute = RouteState(active = true, destination = parents))
    private fun choose() = Clarify(first, ClarifyStage.CHOOSE, first.candidates.filter { it.poi.place.id != "ssd_station" }.take(3), rejectedId = "ssd_station")
    private fun decide(text: String, c: Clarify, late: Boolean = false) = DialoguePolicy.decide(text, c, late, classify)
    private fun cand(r: Resolution, id: String) = r.candidates.first { it.poi.place.id == id }

    @Test
    fun scenario_isAsExpected() {
        assertEquals("ssd_station", first.top?.poi?.place?.id)
        assertEquals(Tier.CONFIRM, first.tier)
        assertEquals(Tier.AUTO, learned.tier)
        assertTrue("대흥동 성당은 '서심다응'과 소리가 다르다", cand(learned, "church_dh").f.phon < PersonalBrain.AUTO_MIN_PHON)
    }

    /** 회귀 테스트 (재검토 D1): 바로 실행 중 '아냐'·'그거 아니야'가 대답으로 읽히지 않아 그대로 학습되던 문제 */
    @Test
    fun policy_d1_rejectionsAndUnrecognisedTextInUndo() {
        for (u in listOf("아냐", "아녜요", "그거 아니야", "거기 아니고", "그게 아니고", "잘못 들었어", "노노"))
            assertEquals("'$u'", ReplyAction.Answer(Reply.No), decide(u, undo()))
        assertEquals(ReplyAction.Answer(Reply.NoAnd("본점")), decide("아냐 본점", undo()))
        // 알아듣지 못한 말로 학습을 정하지 않는다 — 바로 실행 창은 그대로 (질문 지우기·학습 없음)
        assertEquals(ReplyAction.Ignore, decide("엄마 오늘 몇 시에 와?", undo()))
        assertEquals(ReplyAction.Ignore, decide("잠깐만", undo()))
        assertEquals(ReplyAction.Hesitate, decide("어", undo()))
        // 목적지를 바꾸지 않는 명령은 창을 닫지 않는다 (끝날 때 경로를 보고 학습)
        assertEquals(ReplyAction.NewCommand(destChange = false), decide("에어컨 켜 줘", undo()))
    }

    /** 회귀 테스트 (재검토 D2): 머뭇거림·동승자 말·다시 묻기·자연스러운 긍정이 질문을 지우고 자유 대화로 넘어가던 문제 */
    @Test
    fun policy_d2_questionSurvivesAnythingButARealCommand() {
        for (c in listOf(confirm(), undo())) {
            for (u in listOf("어", "어…", "음")) assertEquals("'$u'", ReplyAction.Hesitate, decide(u, c))
            for (u in listOf("잠깐만", "엄마 오늘 몇 시에 와?", "아 진짜?")) assertEquals("'$u'", ReplyAction.Ignore, decide(u, c))
            for (u in listOf("뭐라고", "다시 말해줘", "한 번 더", "잘 못 들었어")) assertEquals("'$u'", ReplyAction.Repeat, decide(u, c))
            for (u in listOf("네에", "좋지", "예스", "yes", "그러자", "가 보자", "성심당", "응 성심당", "성심당 맞아", "응 고마워", "성심당 가자"))
                assertEquals("'$u'", ReplyAction.Answer(Reply.Yes), decide(u, c))
        }
        // 진짜 새 명령만 질문을 내린다
        assertEquals(ReplyAction.NewCommand(destChange = true), decide("스타벅스 가자", confirm()))
        assertEquals(ReplyAction.NewCommand(destChange = false), decide("에어컨 켜 줘", confirm()))
        // 선택지: 지점 이름 일부는 그 후보, 브랜드만이면 번호로 다시
        val ch = choose()
        assertEquals(ReplyAction.Pick(cand(first, "ssd_main")), decide("본점", ch))
        assertEquals(ReplyAction.AskNumber, decide("성심당", ch))
        assertEquals(ReplyAction.Hesitate, decide("음…", ch))
    }

    /** 회귀 테스트 (재검토 D3): HUD에서 내려간 뒤 '그만'·'취소'·'아니 괜찮아'가 바로 실행을 되돌리던 문제 */
    @Test
    fun policy_d3_lateWindowAcceptsOnlyRealUndo() {
        val c = undo()
        assertEquals(ReplyAction.Answer(Reply.No), decide("아니야", c, late = true))
        assertEquals(ReplyAction.Answer(Reply.NoAnd("본점")), decide("아니 본점", c, late = true))
        assertEquals(ReplyAction.Answer(Reply.NoAnd("스타벅스 가자")), decide("아니 스타벅스 가자", c, late = true))
        assertEquals("늦은 '응'은 받아들임(학습)으로 닫고 자유 대화로 보내지 않는다", ReplyAction.Answer(Reply.Yes), decide("응", c, late = true))
        for (u in listOf("그만", "취소", "아니 괜찮아", "어", "2번"))
            assertEquals("'$u' → 평소처럼, 되돌리지 않음", ReplyAction.NewCommand(destChange = false), decide(u, c, late = true))
        assertEquals(ReplyAction.NewCommand(destChange = true), decide("아니 집으로 가자", c, late = true))
    }

    /** 회귀 테스트 (재검토 D4): 바로 실행·확인 중의 다른 장소 명령이 옛 결과의 정답으로 학습되던 문제 */
    @Test
    fun policy_d4_commandForPlaceThatSoundsDifferentIsNewQuery() {
        assertEquals(ReplyAction.NewCommand(destChange = true), decide("대흥동 성당 가자", undo()))
        assertEquals(ReplyAction.NewCommand(destChange = true), decide("대흥동 성당 가자", confirm()))
        // 이름만 말하면(명령이 아니면) 화면의 후보를 고른 것
        assertEquals(ReplyAction.Pick(cand(first, "church_dh")), decide("대흥동 성당", confirm()))
        // 명시적인 "아니, …"는 그 후보를 고를 수 있다
        assertEquals(ReplyAction.Answer(Reply.NoAnd("대흥동 성당 가자")), decide("아니, 대흥동 성당 가자", undo()))
        // 소리가 같은 지점을 명령으로 말하면 고쳐 말한 것 (R0.9: '본점으로 가자' → 본점 + 표현 학습)
        assertEquals(ReplyAction.Pick(cand(first, "ssd_main")), decide("성심당 본점으로 가자", confirm()))
        assertEquals(ReplyAction.Pick(cand(learned, "ssd_main")), decide("본점으로 가자", undo()))
    }

    /** 회귀 테스트 (재검토 D5): '아니 집으로 가자'가 옛 후보 선택지로, '집으로 가자'가 바로 실행 학습으로 가던 문제 */
    @Test
    fun policy_d5_homeAndWorkAreDestinationChanges() {
        for (c in listOf(undo(), confirm())) {
            assertEquals(ReplyAction.RejectThen("집으로 가자"), decide("아니 집으로 가자", c))
            assertEquals(ReplyAction.RejectThen("회사로 가자"), decide("아니, 회사로 가자", c))
            assertEquals(ReplyAction.RejectThen("에어컨 켜 줘"), decide("아니 에어컨 켜 줘", c))
            assertEquals(ReplyAction.NewCommand(destChange = true), decide("집으로 가자", c))
            assertEquals(ReplyAction.NewCommand(destChange = true), decide("회사로 가자", c))
        }
        // 장소 이름이 아닌 다른 말 뒤의 '아니'는 그대로 거절
        assertEquals(ReplyAction.Answer(Reply.NoAnd("괜찮아")), decide("아니 괜찮아", confirm()))
        assertTrue(DialoguePolicy.changesDestination(classify("집으로 가자")))
        assertFalse(DialoguePolicy.changesDestination(classify("성심당 들렀다 가 줘")))
    }

    /** 회귀 테스트 (재검토 D11): '나를 아는 AI' 화면이면 실차에서도 보통 안내를 막아 아무 데서도 들리지 않던 문제 */
    @Test
    fun momentPolicy_d11_personalScreenHoldOnlyWhileSimulating() {
        val parking = Moment("parking", MomentType.entries.first(), Urgency.NORMAL, "주차 위치", "기억할까요?", speak = "주차 위치를 기억해 둘까요?")
        assertTrue(MomentPolicy.mayInterrupt(parking, LoadLevel.LOW, asking = false, screen = Screen.PERSONAL, source = Source.PLEOS_SDK))
        assertFalse(MomentPolicy.mayInterrupt(parking, LoadLevel.LOW, asking = false, screen = Screen.PERSONAL, source = Source.SIMULATOR))
        assertTrue(MomentPolicy.mayInterrupt(parking, LoadLevel.LOW, asking = false, screen = Screen.HOME, source = Source.SIMULATOR))
        assertFalse(MomentPolicy.mayInterrupt(parking, LoadLevel.LOW, asking = true, screen = Screen.HOME, source = Source.PLEOS_SDK))
        assertFalse(MomentPolicy.mayInterrupt(parking, LoadLevel.HIGH, asking = false, screen = Screen.HOME, source = Source.PLEOS_SDK))
        val rear = parking.copy(id = "rear", urgency = Urgency.CRITICAL, deferrable = false)
        assertTrue(MomentPolicy.mayInterrupt(rear, LoadLevel.HIGH, asking = true, screen = Screen.PERSONAL, source = Source.SIMULATOR))
    }

    // ───────────────────────── 실제 저장소 (가상 시계) ─────────────────────────

    /** P_DAEJEON에서 '서심다응'을 한 번 "응"으로 배운 뒤, 경로를 대전 본가로 되돌리고 다시 말해 바로 실행까지 */
    private fun learnedDaejeonAuto(): StoreHarness {
        val h = StoreHarness(ScenarioId.P_DAEJEON)
        h.send("서심다응 가자"); h.adv(6_000)
        assertEquals(ClarifyStage.CONFIRM, h.clarify?.stage)
        h.hear("응"); h.adv(8_000)
        assertEquals("ssd_station", h.destination)
        h.store.playScenario(ScenarioId.P_DAEJEON); h.adv(1_500) // 학습은 그대로, 경로만 대전 본가로
        assertEquals("parents", h.destination)
        h.send("서심다응 가자"); h.adv(4_000)
        assertEquals(ClarifyStage.UNDO, h.clarify?.stage)
        assertEquals("ssd_station", h.destination)
        return h
    }

    /** 회귀 테스트 (재검토 D1): 바로 실행 직후 "아냐" → 경로를 되돌리고 학습하지 않는다 */
    @Test
    fun store_d1_anyaUndoesAutoWithoutLearning() {
        val h = learnedDaejeonAuto()
        val before = h.learnCount
        h.tapAndSay("아냐"); h.adv(4_000)
        assertEquals("parents", h.destination)
        assertEquals(before, h.learnCount)
        assertEquals(ClarifyStage.CHOOSE, h.clarify?.stage)
        assertNull("거절한 곳은 이 말의 선택 기억에서 덜어 낸다", h.personal.brain.choiceMemory["서심다응"]?.get("ssd_station"))
        h.close()
    }

    /** 회귀 테스트 (재검토 D1): 알아듣지 못한 말은 바로 실행 창을 닫지도 학습하지도 않는다 — 창이 끝나야 받아들인 것으로 학습 */
    @Test
    fun store_d1_unrecognisedTextKeepsUndoWindow() {
        val h = learnedDaejeonAuto()
        val before = h.learnCount
        h.tapAndSay("엄마 오늘 몇 시에 와?"); h.adv(2_000)
        assertEquals(ClarifyStage.UNDO, h.clarify?.stage)
        assertEquals(before, h.learnCount)
        assertEquals("ssd_station", h.destination)
        h.adv(60_000)
        assertNull(h.clarify)
        assertEquals("창이 끝나고 아직 그곳으로 안내 중 → 받아들인 것으로 학습", before + 1, h.learnCount)
        h.close()
    }

    /** 회귀 테스트 (재검토 D2): 자동으로 열린 마이크에 "어"가 들려도 질문은 그대로, 마이크를 한 번 더 열고 "좋지"로 출발 */
    @Test
    fun store_d2_hesitationKeepsQuestionAndReopensMic() {
        val h = StoreHarness(ScenarioId.P_DAEJEON)
        h.send("서심다응 가자"); h.adv(6_000)
        val c = h.clarify!!
        assertEquals(ClarifyStage.CONFIRM, c.stage)
        assertEquals(AgentPhase.LISTENING, h.store.phase.value)
        val starts = h.mic.starts
        val spoken = h.tts.spoken.size
        h.hear("어"); h.adv(3_000)
        assertEquals(c.id, h.clarify?.id)
        assertEquals("parents", h.destination)
        assertEquals("자유 대화 답을 하지 않는다", spoken, h.tts.spoken.size)
        assertEquals("머뭇거림 뒤 마이크를 한 번 더", starts + 1, h.mic.starts)
        h.hear("뭐라고?"); h.adv(5_000)
        assertEquals(c.id, h.clarify?.id)
        assertEquals("같은 질문을 다시", "성심당 대전역점으로 갈까요?", h.tts.spoken.last())
        h.hear("좋지"); h.adv(6_000)
        assertEquals("ssd_station", h.destination)
        assertNull(h.clarify)
        h.close()
    }

    /** 회귀 테스트 (재검토 D3): HUD에서 내려간 뒤의 "그만"은 되돌리지 않고, "아니야"는 되돌린다 */
    @Test
    fun store_d3_lateWindow() {
        val h = StoreHarness(ScenarioId.P_WEEKDAY)
        h.send("스타벅스 가자"); h.adv(4_000)
        assertEquals(ClarifyStage.UNDO, h.clarify?.stage)
        assertEquals("sb_pangyo", h.destination)
        h.adv(14_000)
        assertNull("화면 창이 끝나 늦은 되돌리기 창", h.clarify)
        val before = h.learnCount
        h.tapAndSay("그만"); h.adv(10_000)
        assertEquals("sb_pangyo", h.destination)
        assertEquals("앱이 다른 말을 하면 늦은 창을 받아들인 것으로 닫는다", before + 1, h.learnCount)
        h.close()

        val h2 = StoreHarness(ScenarioId.P_WEEKDAY)
        h2.send("스타벅스 가자"); h2.adv(18_000)
        assertNull(h2.clarify)
        val before2 = h2.learnCount
        h2.tapAndSay("아니야"); h2.adv(4_000)
        assertEquals("work", h2.destination)
        assertEquals(before2, h2.learnCount)
        assertEquals(ClarifyStage.CHOOSE, h2.clarify?.stage)
        h2.close()
    }

    /** 회귀 테스트 (재검토 D4): 바로 실행 중 "대흥동 성당 가자" → 새 질의, 옛 말('서심다응')의 기억·가중치는 그대로 */
    @Test
    fun store_d4_newPlaceDuringAutoLeavesOldResolutionAlone() {
        val h = learnedDaejeonAuto()
        val w = h.personal.brain.weights.copyOf()
        val memory = h.personal.brain.choiceMemory["서심다응"]?.toMap()
        h.tapAndSay("대흥동 성당 가자"); h.adv(5_000)
        assertEquals(memory, h.personal.brain.choiceMemory["서심다응"]?.toMap())
        assertArrayEquals(w, h.personal.brain.weights, 1e-12)
        val target = h.clarify?.options?.firstOrNull()?.poi?.place?.id
        assertEquals("church_dh", target)
        assertEquals("새 질의로 보정", "대흥동 성당", h.clarify?.res?.query?.text)
        h.close()
    }

    /** 회귀 테스트 (재검토 D5): 바로 실행 중 "아니 집으로 가자" → 집으로 안내, 옛 후보 선택지 없음, 학습 없음 */
    @Test
    fun store_d5_noThenHomeGoesHome() {
        val h = learnedDaejeonAuto()
        val before = h.learnCount
        h.tapAndSay("아니 집으로 가자"); h.adv(6_000)
        assertEquals("home", h.destination)
        assertNull(h.clarify)
        assertEquals(before, h.learnCount)
        h.close()

        val h2 = learnedDaejeonAuto()
        val before2 = h2.learnCount
        h2.tapAndSay("회사로 가자"); h2.adv(6_000)
        assertEquals("work", h2.destination)
        assertNull(h2.clarify)
        h2.adv(60_000)
        assertEquals("목적지를 바꾼 바로 실행은 학습하지 않는다", before2, h2.learnCount)
        h2.close()
    }

    /** 회귀 테스트 (재검토 D8): 끝 알림 없는 STT 세션에 LISTENING이 갇히지 않고, 위급 안내는 듣는 중에도 먼저 말한다 */
    @Test
    fun store_d8_listeningWatchdogAndCriticalPreempt() {
        val h = StoreHarness(ScenarioId.P_DAEJEON)
        h.send("서심다응 가자"); h.adv(4_000)
        assertEquals(AgentPhase.LISTENING, h.store.phase.value)
        h.adv(7_000) // 자동 듣기 감시 8초
        assertEquals(AgentPhase.IDLE, h.store.phase.value)
        assertTrue(h.mic.stops >= 1)
        assertEquals(ClarifyStage.CONFIRM, h.clarify?.stage)
        h.adv(30_000) // 질문 시간 제한이 다시 흐른다
        assertNull(h.clarify)
        h.close()

        val a = StoreHarness(ScenarioId.ARRIVAL)
        a.store.startVoice(openPanel = false); a.adv(500)
        assertEquals(AgentPhase.LISTENING, a.store.phase.value)
        val stops = a.mic.stops
        a.adv(7_000) // 8초: 기어 P + 운전석 문 열림 → 뒷좌석 아이 확인 (미룰 수 없는 위급 안내)
        assertTrue(a.tts.spoken.toString(), a.tts.spoken.any { it.startsWith("잠깐만요! 뒷좌석에") })
        assertTrue("마이크를 닫고 말한다", a.mic.stops > stops)
        a.close()
    }

    /** 회귀 테스트 (재검토 D9): "장소 이름을 한 번 더 말씀해 주세요" 뒤 마이크를 한 번 자동으로 열고, 두 번째 '못 찾음'에서는 다시 열지 않는다 */
    @Test
    fun store_d9_resayPromptOpensMicOncePerDialogue() {
        val h = StoreHarness(ScenarioId.P_DAEJEON)
        h.send("서심다응 가자"); h.adv(6_000)
        h.hear("아니야"); h.adv(8_000)
        assertEquals(ClarifyStage.CHOOSE, h.clarify?.stage)
        val starts = h.mic.starts
        h.hear("아니야"); h.adv(6_000)
        assertNull(h.clarify)
        assertEquals("알겠어요. 장소 이름을 한 번 더 말씀해 주세요.", h.lastAgentLine)
        assertEquals(starts + 1, h.mic.starts)
        assertEquals(AgentPhase.LISTENING, h.store.phase.value)
        h.hear("에펠탑 가자"); h.adv(8_000)
        assertEquals(Tier.NONE, h.personal.ui.value.lastResolution?.tier)
        assertEquals("같은 대화의 두 번째 '못 찾음'은 다시 열지 않는다", starts + 1, h.mic.starts)
        h.send("에펠탑 가자"); h.adv(8_000)
        assertEquals("운전자가 새로 꺼낸 말은 새 대화", starts + 2, h.mic.starts)
        h.close()
    }

    // ───────────────────────── 최종 검토 1~3: 판단 규칙 ─────────────────────────

    /** 경유지 질문 "성심당 대전역점에 들를까요?" — 말로 한 "성심당 들렀다 가 줘" / Gleo GO_TO_MY_PLACE(place=성심당, stop_type=waypoint) */
    private fun waypointQuestion(gleo: Boolean): Clarify {
        val q = if (gleo) (QueryParser.parse("성심당 가자") ?: PlaceQuery("성심당")).copy(mode = StopMode.WAYPOINT, raw = "성심당")
        else QueryParser.parse("성심당 들렀다 가 줘")!!
        val r = PersonalBrain().resolve(q, daejeon())
        return Clarify(r, ClarifyStage.CONFIRM, listOf(r.top!!))
    }

    /** 회귀 테스트 (최종 검토 1): 경유지 질문에 "응 들르자"는 무시되고 "들렀다 가자"는 여정 계획 새 명령, "아니 들렀다 가자"는 거절+새 명령이 되던 문제 */
    @Test
    fun policy_final1_stopVerbRepliesToWaypointQuestion() {
        for (gleo in listOf(false, true)) {
            val c = waypointQuestion(gleo)
            assertEquals(StopMode.WAYPOINT, c.res.query.mode)
            assertEquals("ssd_station", c.options.first().poi.place.id)
            for (u in listOf("응 들르자", "그래 들러 줘", "응 들러 줘", "들렀다 가자", "응 들렀다 가자", "들러 줘", "응, 들르자"))
                assertEquals("'$u' (gleo=$gleo)", ReplyAction.Answer(Reply.Yes), decide(u, c))
            assertEquals("gleo=$gleo", ReplyAction.Answer(Reply.No), decide("아니 들렀다 가자", c))
            // 경유지 질문에 다른 지점 + 경유 동사는 그 지점을 경유지로 (질문이 이미 경유지라 방식은 그대로)
            assertEquals("gleo=$gleo", ReplyAction.Pick(cand(c.res, "ssd_main")), decide("본점 들렀다 가자", c))
        }
    }

    /** 회귀 테스트 (최종 검토 2): 목적지 확인·바로 실행 중 경유 동사 대답이 목적지를 바꾸던 문제 — 대답의 방식(경유지)을 그대로 넘긴다 */
    @Test
    fun policy_final2_stopVerbReplyToDestinationQuestionCarriesWaypointMode() {
        for (c in listOf(confirm(), undo())) {
            val stage = c.stage
            for (u in listOf("성심당 들렀다 가 줘", "응 들르자", "들렀다 가자", "그래 들러 줘", "성심당 들르자"))
                assertEquals("'$u' ($stage)", ReplyAction.Answer(Reply.Yes, StopMode.WAYPOINT), decide(u, c))
            assertEquals("$stage", ReplyAction.Pick(cand(c.res, "ssd_main"), StopMode.WAYPOINT), decide("본점 들렀다 가자", c))
            assertEquals("$stage", ReplyAction.Answer(Reply.NoAnd("본점 들렀다 가자"), StopMode.WAYPOINT), decide("아니 본점 들렀다 가자", c))
            // 경유 동사뿐인 "아니 …"는 그냥 '아니야', 목적지 대답은 그대로 목적지
            assertEquals("$stage", ReplyAction.Answer(Reply.No), decide("아니 들렀다 가자", c))
            assertEquals("$stage", ReplyAction.Answer(Reply.Yes), decide("성심당 가자", c))
            assertEquals("$stage", ReplyAction.Pick(cand(c.res, "ssd_main")), decide("본점으로 가자", c))
        }
        // 선택지에서도 후보 이름 + 경유 동사는 경유지로, 번호 + 경유 동사도 같다
        assertEquals(ReplyAction.Pick(cand(first, "ssd_main"), StopMode.WAYPOINT), decide("본점 들렀다 가자", choose()))
        assertEquals(ReplyAction.Answer(Reply.Pick(0), StopMode.WAYPOINT), decide("1번 들러 줘", choose()))
        assertEquals(ReplyAction.Answer(Reply.Pick(0)), decide("1번", choose()))
        // 바로 실행 전에 안내 중인 경로가 없었으면 들를 경로가 없다 → 그대로 '네'
        val noRoute = undo().copy(prevRoute = RouteState())
        assertEquals(ReplyAction.Answer(Reply.Yes), decide("들렀다 가자", noRoute))
        assertEquals(ReplyAction.Answer(Reply.Yes), decide("성심당 들렀다 가 줘", noRoute))
    }

    /** 회귀 테스트 (최종 검토 3): 늦은 되돌리기 창에서 다른 후보 이름만 말하면 새 명령(자유 대화)이 되어 바로 실행한 곳 쪽으로 학습되던 문제 */
    @Test
    fun policy_final3_lateWindowCandidateNameIsACorrection() {
        val c = undo()
        for ((u, id) in listOf("본점" to "ssd_main", "본점이요" to "ssd_main", "디씨씨점" to "ssd_dcc", "대흥동 성당" to "church_dh", "응 본점" to "ssd_main"))
            assertEquals("'$u'", ReplyAction.Pick(cand(learned, id)), decide(u, c, late = true))
        // 바로 실행한 곳 이름을 다시 말한 것·장소 명령은 고친 게 아니다 (평소처럼)
        assertEquals(ReplyAction.NewCommand(destChange = false), decide("대전역점", c, late = true))
        assertEquals(ReplyAction.NewCommand(destChange = true), decide("본점으로 가자", c, late = true))
        // 늦은 창의 경유 동사 대답 → 경유지로 (원래 경로로 되돌리고 들른다)
        assertEquals(ReplyAction.Answer(Reply.Yes, StopMode.WAYPOINT), decide("들렀다 가자", c, late = true))
        assertEquals(ReplyAction.Answer(Reply.Yes, StopMode.WAYPOINT), decide("응 들르자", c, late = true))
        // 알아듣지 못한 말(자유 대화)은 받아들인 것으로 세지 않고 닫는다 · "그만"·"취소"(D3)와 진짜 명령은 평소처럼
        for (u in listOf("엄마 오늘 몇 시에 와?", "어", "2번", "아니 괜찮아", "대전역점")) assertTrue("'$u'", DialoguePolicy.unclearLate(u, classify))
        for (u in listOf("그만", "취소", "에어컨 켜 줘", "집으로 가자", "본점으로 가자")) assertFalse("'$u'", DialoguePolicy.unclearLate(u, classify))
    }

    // ───────────────────────── 최종 검토 1~3: 실제 저장소 (가상 시계) ─────────────────────────

    /** P_DAEJEON(대전 본가 안내 중)에서 "성심당 들렀다 가 줘" → "성심당 대전역점에 들를까요?" + 자동 듣기 */
    private fun spokenWaypointQuestion(): StoreHarness {
        val h = StoreHarness(ScenarioId.P_DAEJEON)
        h.send("성심당 들렀다 가 줘"); h.adv(6_000)
        assertEquals(ClarifyStage.CONFIRM, h.clarify?.stage)
        assertEquals("성심당 대전역점에 들를까요?", h.tts.spoken.last())
        assertEquals(AgentPhase.LISTENING, h.store.phase.value)
        return h
    }

    /** P_DAEJEON에서 Gleo GO_TO_MY_PLACE(place=성심당, stop_type=waypoint) → asking "성심당 대전역점에 들를까요?" + 자동 듣기 */
    private fun gleoWaypointQuestion(): StoreHarness {
        val h = StoreHarness(ScenarioId.P_DAEJEON)
        val replies = mutableListOf<Pair<String, String>>()
        h.store.goToMyPlace("성심당", StopMode.WAYPOINT) { status, message -> replies += status to message }
        h.adv(6_000)
        assertEquals(listOf(GleoBridge.ASKING to "성심당 대전역점에 들를까요?"), replies)
        assertEquals(ClarifyStage.CONFIRM, h.clarify?.stage)
        assertEquals(AgentPhase.LISTENING, h.store.phase.value)
        return h
    }

    /** 회귀 테스트 (최종 검토 1): 경유지 질문(말로 · Gleo)에 "응 들르자"·"들렀다 가자"로 대답하면 대전 본가 경로에 경유지로 넣는다 */
    @Test
    fun store_final1_stopVerbAnswersAddTheWaypoint() {
        for (gleo in listOf(false, true)) for (u in listOf("응 들르자", "그래 들러 줘", "응 들러 줘", "들렀다 가자", "응 들렀다 가자")) {
            val h = if (gleo) gleoWaypointQuestion() else spokenWaypointQuestion()
            val before = h.learnCount
            h.hear(u); h.adv(6_000)
            val at = "'$u' (gleo=$gleo)"
            assertNull(at, h.clarify)
            assertEquals("$at — 대전 본가 안내는 그대로", "parents", h.destination)
            assertEquals(at, listOf("ssd_station"), h.route.waypoints.map { it.id })
            assertEquals(at, "성심당 대전역점에 들렀다 갈게요.", h.tts.spoken.last())
            assertFalse("$at — 여정 계획으로 가지 않는다", h.tts.spoken.any { it.contains("횡성휴게소") })
            assertEquals(at, before + 1, h.learnCount)
            h.close()
        }
        // "아니 들렀다 가자"는 그냥 '아니야' → 나머지 후보 선택지, 경유지는 넣지 않는다
        for (gleo in listOf(false, true)) {
            val h = if (gleo) gleoWaypointQuestion() else spokenWaypointQuestion()
            h.hear("아니 들렀다 가자"); h.adv(6_000)
            assertEquals("gleo=$gleo", ClarifyStage.CHOOSE, h.clarify?.stage)
            assertEquals("gleo=$gleo", "parents", h.destination)
            assertTrue("gleo=$gleo", h.route.waypoints.isEmpty())
            assertFalse("gleo=$gleo", h.tts.spoken.any { it.contains("횡성휴게소") })
            h.close()
        }
    }

    /** 회귀 테스트 (최종 검토 2): 목적지 확인 "성심당 대전역점으로 갈까요?"에 "성심당 들렀다 가 줘"·"본점 들렀다 가자" → 대전 본가 경로는 그대로, 경유지로 */
    @Test
    fun store_final2_stopVerbReplyToDestinationConfirmAddsWaypoint() {
        for ((u, id) in listOf("성심당 들렀다 가 줘" to "ssd_station", "응 들르자" to "ssd_station", "본점 들렀다 가자" to "ssd_main")) {
            val h = StoreHarness(ScenarioId.P_DAEJEON)
            h.send("서심다응 가자"); h.adv(6_000)
            assertEquals(ClarifyStage.CONFIRM, h.clarify?.stage)
            assertEquals("성심당 대전역점으로 갈까요?", h.tts.spoken.last())
            val before = h.learnCount
            h.hear(u); h.adv(8_000)
            assertNull("'$u'", h.clarify)
            assertEquals("'$u' — 경로를 바꾸지 않는다", "parents", h.destination)
            assertEquals("'$u'", listOf(id), h.route.waypoints.map { it.id })
            val name = PersonaFactory.poiById(id)!!.spoken
            assertTrue("'$u' ${h.tts.spoken.last()}", h.tts.spoken.last().startsWith("${name}에 들렀다 갈게요."))
            assertEquals("'$u'", before + 1, h.learnCount)
            h.close()
        }
    }

    /**
     * 회귀 테스트 (최종 검토 2): 바로 실행(대전역점으로 안내) 중 "성심당 들렀다 가 줘" → "네, 그대로 안내할게요"로 받아
     * 대전 본가 경로를 잃던 문제. 실행 전 경로(대전 본가)로 되돌리고 그곳을 경유지로, 바로 실행 타이머 학습은 하지 않는다.
     */
    @Test
    fun store_final2_stopVerbDuringAutoTurnsItIntoAWaypoint() {
        for (u in listOf("성심당 들렀다 가 줘", "들렀다 가자")) {
            val h = learnedDaejeonAuto()
            val before = h.learnCount
            h.tapAndSay(u); h.adv(6_000)
            assertNull("'$u'", h.clarify)
            assertEquals("'$u' — 실행 전 경로로 되돌림", "parents", h.destination)
            assertEquals("'$u'", listOf("ssd_station"), h.route.waypoints.map { it.id })
            assertEquals("'$u'", "성심당 대전역점에 들렀다 갈게요.", h.tts.spoken.last())
            assertFalse("'$u'", h.tts.spoken.contains("네, 그대로 안내할게요."))
            assertEquals("'$u' — 고른 곳으로 한 번만 학습", before + 1, h.learnCount)
            h.adv(60_000)
            assertEquals("'$u' — 바로 실행 창의 학습은 더 없다", before + 1, h.learnCount)
            h.close()
        }
        // 다른 지점 + 경유 동사 → 바로 실행한 곳은 덜어 내고, 대전 본가 경로에 그 지점을 경유지로
        val h = learnedDaejeonAuto()
        h.tapAndSay("본점 들렀다 가자"); h.adv(6_000)
        assertEquals("parents", h.destination)
        assertEquals(listOf("ssd_main"), h.route.waypoints.map { it.id })
        assertNull(h.personal.brain.choiceMemory["서심다응"]?.get("ssd_station"))
        h.close()
    }

    /** 바로 실행 뒤 HUD 창이 끝나고 늦은 되돌리기 창이 열려 있을 때까지 */
    private fun lateDaejeonAuto(): StoreHarness {
        val h = learnedDaejeonAuto()
        val learned = h.learnCount
        h.adv(16_000)
        assertNull("HUD 창이 끝나 늦은 되돌리기 창", h.clarify)
        assertEquals("ssd_station", h.destination)
        assertEquals("늦은 창은 아직 열려 있다 (학습 전)", learned, h.learnCount)
        return h
    }

    /** 회귀 테스트 (최종 검토 3): 늦은 되돌리기 창에서 다른 지점 이름만 말하면("본점") 그곳으로 고친다 — 고치려던 곳이 아니라 바로 실행한 곳 쪽으로 학습하던 문제 */
    @Test
    fun store_final3_lateWindowCandidateNameCorrectsTheAutoPick() {
        for ((u, id) in listOf("본점" to "ssd_main", "본점이요" to "ssd_main", "디씨씨점" to "ssd_dcc", "대흥동 성당" to "church_dh")) {
            val h = lateDaejeonAuto()
            val before = h.learnCount
            h.tapAndSay(u); h.adv(6_000)
            assertEquals("'$u'", id, h.destination)
            assertNull("'$u' — 바로 실행한 곳은 이 말의 선택 기억에서 덜어 낸다", h.personal.brain.choiceMemory["서심다응"]?.get("ssd_station"))
            assertEquals("'$u' — 고른 곳으로 한 번만 학습", before + 1, h.learnCount)
            assertFalse("'$u' — 자유 대화로 넘기지 않는다", h.tts.spoken.any { it.contains("정차하시면") })
            h.adv(60_000)
            assertEquals("'$u' — 바로 실행한 곳을 받아들인 것으로 학습하지 않는다", before + 1, h.learnCount)
            h.close()
        }
        // 늦은 창의 경유 동사 대답 → 원래 경로(대전 본가)로 되돌리고 경유지로
        val h = lateDaejeonAuto()
        h.tapAndSay("들렀다 가자"); h.adv(6_000)
        assertEquals("parents", h.destination)
        assertEquals(listOf("ssd_station"), h.route.waypoints.map { it.id })
        h.close()
    }

    /** 회귀 테스트 (5차 검토): 늦은 창에서 후보를 부르며 들르자고 하면 원래 경로(대전 본가)에 경유지로 — 바로 실행한 곳 쪽으로 학습하던 문제 */
    @Test
    fun store_round5_lateWindowStopVerbWithNameAddsWaypoint() {
        // 다른 지점을 부르며 들르자 → 본점을 경유지로, 바로 실행한 곳은 선택 기억에서 덜어 낸다
        var h = lateDaejeonAuto()
        var before = h.learnCount
        h.tapAndSay("본점 들렀다 가자"); h.adv(6_000)
        assertEquals("parents", h.destination)
        assertEquals(listOf("ssd_main"), h.route.waypoints.map { it.id })
        assertNull(h.personal.brain.choiceMemory["서심다응"]?.get("ssd_station"))
        assertEquals("고른 곳으로 한 번만 학습", before + 1, h.learnCount)
        h.adv(60_000)
        assertEquals(before + 1, h.learnCount)
        h.close()
        // 바로 실행한 곳 이름 + 들르자 → 그곳을 원래 경로에 경유지로
        h = lateDaejeonAuto()
        before = h.learnCount
        h.tapAndSay("성심당 들렀다 가 줘"); h.adv(6_000)
        assertEquals("parents", h.destination)
        assertEquals(listOf("ssd_station"), h.route.waypoints.map { it.id })
        h.adv(60_000)
        assertTrue("두 번 학습하지 않는다", h.learnCount <= before + 1)
        h.close()
    }

    /** 회귀 테스트 (최종 검토 3): 늦은 창의 알아듣지 못한 말 뒤 앱의 대답이 바로 실행을 '받아들임'으로 학습하던 문제 — 학습 없이 닫는다 */
    @Test
    fun store_final3_lateWindowUnclearTalkClosesWithoutLearning() {
        val h = lateDaejeonAuto()
        val before = h.learnCount
        val memory = h.personal.brain.choiceMemory["서심다응"]?.toMap()
        h.tapAndSay("엄마 오늘 몇 시에 와?"); h.adv(10_000)
        assertEquals("되돌리지 않는다", "ssd_station", h.destination)
        assertEquals(before, h.learnCount)
        h.adv(60_000)
        assertEquals("창이 학습 없이 닫혔다", before, h.learnCount)
        assertEquals(memory, h.personal.brain.choiceMemory["서심다응"]?.toMap())
        h.close()
    }
}
