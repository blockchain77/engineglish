package com.yeopjari.app.ai.personal

import com.yeopjari.app.ai.context.DriveContext
import com.yeopjari.app.ai.context.EnergyModel
import com.yeopjari.app.ai.context.FatigueModel
import com.yeopjari.app.ai.context.LoadModel
import com.yeopjari.app.ai.context.UserProfile
import com.yeopjari.app.ai.nlu.Intent
import com.yeopjari.app.ai.nlu.KeywordIntentEngine
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.VehicleSnapshot
import com.yeopjari.app.sim.SimFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 되묻기 대답·장소 명령 해석의 회귀 테스트 (재검토 D1·D2·D6·D7·D10) — 앱이 쓰는 QueryParser·PersonalBrain·의도 엔진을 그대로 돌린다.
 * 시나리오 좌표·시각은 sim/DriveSimulator.kt 의 P_DAEJEON · P_WEEKDAY · P_WEEKEND 와 같다.
 */
class DialogueParsingTest {

    private val base = PersonaFactory.BASE_DAY
    private val parents = PersonaFactory.poiById("parents")!!.place

    private fun sit(day: Int, h: Int, m: Int, lat: Double?, lng: Double?, dLat: Double? = null, dLng: Double? = null) =
        Situation(day, LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value, h * 60 + m, lat, lng, dLat, dLng)

    private fun daejeon() = sit(base, 10, 4, 36.482, 127.347, parents.lat, parents.lng)
    private fun weekdayCommute() = sit(base - 2, 8, 31, 37.4176, 127.1074, 37.4020, 127.1086)
    private fun weekendHome() = sit(base, 10, 50, 37.5133, 127.1001)
    private fun q(text: String) = PlaceQuery(text)

    private fun ctx(): DriveContext {
        val v = VehicleSnapshot()
        val r = RouteState()
        return DriveContext(
            vehicle = v, route = r, clockSec = 10 * 3600f, clockLabel = "10:00", flags = SimFlags(), profile = UserProfile(),
            energy = EnergyModel.forecast(v, r), fatigue = FatigueModel.assess(v, r, 10), load = LoadModel.estimate(v, r),
        )
    }

    // ───────────────────────── D1: 부정 대답 ─────────────────────────

    /** 회귀 테스트 (재검토 D1): '아냐'·'그거 아니야'·'잘못 들었어'·'노노'가 대답으로 읽히지 않아 바로 실행이 학습되던 문제 */
    @Test
    fun reply_d1_everydayRejections() {
        for (u in listOf(
            "아냐", "아녜요", "아니거든", "노노", "그거 아니야", "거기 아니야", "거기 아니고", "그게 아니고", "이거 아니야",
            "여기 아닌데", "그곳 아니라", "잘못 들었어", "잘못 들었어요", "잘못 알아들었어", "응 아냐", "음, 아냐",
        )) assertEquals("'$u'", Reply.No, QueryParser.reply(u))
        // 뒤에 다른 이름을 말하면 그대로 '아니, …'
        assertEquals(Reply.NoAnd("본점"), QueryParser.reply("아냐 본점"))
        assertEquals(Reply.NoAnd("본점"), QueryParser.reply("그거 아니고 본점"))
        assertEquals(Reply.NoAnd("본점이야"), QueryParser.reply("잘못 들었어 본점이야"))
        // 뒤의 말은 들린 그대로 (띄어쓰기 유지) — 새 명령으로 다시 해석한다
        assertEquals(Reply.NoAnd("대흥동 성당 가자"), QueryParser.reply("아니, 대흥동 성당 가자"))
        assertEquals(Reply.NoAnd("집으로 가자"), QueryParser.reply("아니 집으로 가자"))
        // '잘 못 들었어'(못 알아들음)는 부정이 아니라 다시 묻기 — '잘못 들었어'(오인식)와 띄어쓰기로만 갈린다
        assertEquals(Reply.None, QueryParser.reply("잘 못 들었어"))
        assertEquals(Aside.REPEAT, QueryParser.aside("잘 못 들었어"))
        // 이름 안의 비슷한 글자는 부정이 아니다
        assertEquals(Reply.NoAnd("에버랜드"), QueryParser.reply("아니 에버랜드"))
        assertEquals(Reply.None, QueryParser.reply("노래 틀어줘"))
    }

    // ───────────────────────── D2: 긍정·머뭇거림·다시 묻기 ─────────────────────────

    /** 회귀 테스트 (재검토 D2): 자연스러운 긍정('좋지'·'그러자'·'가 보자'·'예스'·'네에'·'응 고마워')이 대답으로 읽히지 않던 문제 */
    @Test
    fun reply_d2_naturalAffirmatives() {
        for (u in listOf(
            "좋지", "그러자", "가 보자", "예스", "yes", "Yes", "네에", "응 고마워", "네 감사합니다", "그럼요", "물론이지", "응응",
            "좋아 가자", "응 가 보자", "그래 그러자",
        )) assertEquals("'$u'", Reply.Yes, QueryParser.reply(u))
        // 인사만으로는 대답이 아니다 (동승자에게 한 말일 수 있다)
        assertEquals(Reply.None, QueryParser.reply("고마워"))
        // 기존 판단은 그대로
        for (u in listOf("어", "음", "어…", "그래도 본점", "네비 꺼줘")) assertEquals("'$u'", Reply.None, QueryParser.reply(u))
        assertEquals(Reply.Pick(3), QueryParser.reply("네 번째"))
    }

    /** 회귀 테스트 (재검토 D2): 다시 묻기·기다리기·머뭇거림을 가려 질문을 지운 채 자유 대화로 넘기지 않는다 */
    @Test
    fun aside_d2_repeatHoldAndFiller() {
        for (u in listOf("뭐라고", "뭐라고?", "다시 말해줘", "다시 말해 줘", "다시", "한 번 더", "한번 더 말해 줘", "잘 못 들었어", "못 들었어", "어 뭐라고?", "안 들려")) {
            assertEquals("'$u'", Reply.None, QueryParser.reply(u))
            assertEquals("'$u'", Aside.REPEAT, QueryParser.aside(u))
        }
        for (u in listOf("잠깐만", "잠깐만요", "잠시만", "기다려 봐", "음 잠깐만")) assertEquals("'$u'", Aside.HOLD, QueryParser.aside(u))
        for (u in listOf("어", "어…", "음", "음…", "어어", "글쎄", "음 어")) assertEquals("'$u'", Aside.FILLER, QueryParser.aside(u))
        for (u in listOf("엄마 오늘 몇 시에 와?", "노래 틀어줘", "성심당", "다시 성심당 가자")) assertNull("'$u'", QueryParser.aside(u))
    }

    /** 회귀 테스트 (재검토 D2): 확인 단계에서 제안한 곳의 이름("성심당", "응 성심당", "성심당 맞아")을 다시 말하면 '네' */
    @Test
    fun namesOption_d2_proposedNameCountsAsYes() {
        val res = PersonalBrain().resolve(q("서심다응"), daejeon())
        val station = res.candidates.first { it.poi.place.id == "ssd_station" }
        for (u in listOf("성심당", "응 성심당", "성심당 맞아", "성심당 맞아요", "네 성심당이요", "성심당 가자", "대전역점", "성심당 대전역점 맞아"))
            assertTrue("'$u'", QueryParser.namesOption(u, station))
        for (u in listOf("어", "응", "그거", "대흥동 성당", "스타벅스", "본점", "성심당 본점", "점", "당", "역"))
            assertFalse("'$u'", QueryParser.namesOption(u, station))
        // 브랜드만 말하면 세 지점이 같은 점수 → 어느 지점인지는 고르지 않는다 (확인 단계에서 '네'로 받는 이유)
        assertNull(QueryParser.matchOption("성심당", res.candidates))
    }

    // ───────────────────────── D6: 부사는 장소가 아니다 ─────────────────────────

    /** 회귀 테스트 (재검토 D6): '빨리 가자'·'그냥 가자'의 부사가 장소 이름이 되어 엉뚱한 곳을 묻던 문제 */
    @Test
    fun parse_d6_adverbsAreNotPlaces() {
        for (u in listOf(
            "빨리 가자", "그냥 가자", "얼른 가자", "어서 가자", "천천히 가자", "다시 가자", "이제 가자", "일단 가자", "계속 가자",
            "그대로 가자", "이대로 가자", "같이 가자", "좀 빨리 가자", "거기로 가자", "응 가자", "아니 다른 데로 가자",
        )) assertNull("'$u' → ${QueryParser.parse(u)?.text}", QueryParser.parse(u))
        // 앞에 붙은 부사·대답 낱말은 떼고 장소만
        assertEquals("스타벅스", QueryParser.parse("그냥 스타벅스 가자")?.text)
        assertEquals("스타벅스", QueryParser.parse("빨리 스타벅스 가자")?.text)
        assertEquals("성심당", QueryParser.parse("응 성심당 가자")?.text)
        assertEquals("성심당", QueryParser.parse("엄마랑 같이 성심당 가자")?.text)
        val wp = QueryParser.parse("일단 성심당 들렀다 가자")
        assertEquals("성심당", wp?.text)
        assertEquals(StopMode.WAYPOINT, wp?.mode)
        // 앱 의도 엔진에서도 장소 명령이 아니다
        val e = KeywordIntentEngine()
        for (u in listOf("빨리 가자", "그냥 가자", "천천히 가자")) assertNotEquals("'$u'", Intent.NAV_PLACE, e.classify(u, ctx()).intent)
    }

    /** 회귀 테스트 (재검토 D6): 두 음절 이하의 말이 긴 이름 안의 일부('…밸리점')에 붙어 발음 0.8이 되던 문제 */
    @Test
    fun phonetic_d6_shortWordsCompareWithWholeNameWords() {
        val brain = PersonalBrain()
        val pangyo = PersonaFactory.poiById("sb_pangyo")!!
        for (w in listOf("빨리", "노래", "얼른", "그냥")) {
            assertTrue("'$w' ↔ 판교테크노밸리점 ${brain.phonetic(q(w), pangyo)}", brain.phonetic(q(w), pangyo) < PersonalBrain.MIN_PHON)
            for (s in listOf(weekdayCommute(), weekendHome(), daejeon())) assertEquals("'$w'", Tier.NONE, brain.resolve(q(w), s).tier)
        }
        // 짧은 이름 단어·별칭은 그대로 맞는다
        assertEquals(1.0, brain.phonetic(q("본점"), PersonaFactory.poiById("ssd_main")!!), 1e-9)
        assertEquals(1.0, brain.phonetic(q("본가"), PersonaFactory.poiById("parents")!!), 1e-9)
        assertEquals(1.0, brain.phonetic(q("성당"), PersonaFactory.poiById("church_dh")!!), 1e-9)
        assertEquals("parents", brain.resolve(q("본가"), daejeon()).top?.poi?.place?.id)
        // 글자 그대로 단어의 앞머리면 0.9, 한 음절은 바로 실행 기준 아래
        assertEquals(0.9, brain.phonetic(q("판교"), pangyo), 1e-9)
        assertTrue(brain.phonetic(q("역"), PersonaFactory.poiById("ssd_station")!!) < PersonalBrain.AUTO_MIN_PHON)
        // 오인식된 짧은 이름도 소리 전체로 비교한다 ('봉가' ↔ '본가')
        assertTrue(brain.phonetic(q("봉가"), PersonaFactory.poiById("parents")!!) >= PersonalBrain.AUTO_MIN_PHON)
    }

    // ───────────────────────── D7: 경유지 부탁 꼴 ─────────────────────────

    /** 회귀 테스트 (재검토 D7): '성심당 들렀다 가 줘'가 목적지 '성심당 들렀다'로 읽혀 대전 본가 안내를 덮어쓰던 문제 */
    @Test
    fun parse_d7_waypointRequestForms() {
        for (u in listOf(
            "성심당 들렀다 가 줘", "성심당 들렀다 가 주세요", "성심당 들렀다 가 줄래", "성심당 들렀다 가요", "성심당 들러 주세요",
            "성심당 들러 줄래", "성심당 들렀다 갈게", "성심당 들렀다가 가 줘", "성심당에 잠깐 들렀다 가 줘", "성심당 들러서 가 주세요",
        )) {
            val p = QueryParser.parse(u)
            assertEquals("'$u'", "성심당", p?.text)
            assertEquals("'$u'", StopMode.WAYPOINT, p?.mode)
        }
        // 경유 동사가 장소 말 끝에 남으면 장소 이름이 아니다
        assertNull(QueryParser.parse("성심당 들렀다 안내해 줘"))
        // 앱 의도 엔진도 경유지 장소 명령으로
        val nav = KeywordIntentEngine().classify("성심당 들렀다 가 줘", ctx())
        assertEquals(Intent.NAV_PLACE, nav.intent)
        assertEquals(StopMode.WAYPOINT, nav.place?.mode)
    }

    // ───────────────────────── D10: '…고'로 끝나는 이름 ─────────────────────────

    /** 회귀 테스트 (재검토 D10): '…고'로 끝나는 낱말을 모두 이어 주는 말로 봐서 '잠실고'·'망고'·'중고'가 든 이름이 장소 명령이 아니게 되던 문제 */
    @Test
    fun parse_d10_namesEndingInGo() {
        for ((u, t) in listOf(
            "잠실고 정문 가자" to "잠실고 정문", "휘문고 운동장 가자" to "휘문고 운동장", "망고 식스 가자" to "망고 식스",
            "알라딘 중고 서점 가자" to "알라딘 중고 서점", "중고 매장 가자" to "중고 매장",
        )) assertEquals("'$u'", t, QueryParser.parse(u)?.text)
        // 동사·형용사 줄기 뒤의 '…고'는 여전히 앞 절을 이어 주는 말
        for (u in listOf("밥 먹고 스타벅스 가자", "불 켜고 성심당 가자", "기름 넣고 홈플러스 가자", "애들 데리고 성심당 가자", "커피 사고 회사 가자", "늦었고 성심당 가자"))
            assertNull("'$u' → ${QueryParser.parse(u)?.text}", QueryParser.parse(u))
        assertEquals("올림픽고 원", QueryParser.parse("올림픽고 원 가자")?.text)
        assertEquals(Intent.NAV_PLACE, KeywordIntentEngine().classify("잠실고 정문 가자", ctx()).intent)
    }

    // ───────────────────────── 의도 엔진: 동기 분류 ─────────────────────────

    /** 되묻는 중 쓰는 동기 분류([KeywordIntentEngine.classify])가 실제 의도 분석과 같은 판단인지 */
    @Test
    fun intentEngine_classifyMatchesParse() {
        val e = KeywordIntentEngine()
        val c = ctx()
        for (u in listOf("집으로 가자", "회사로 가자", "에어컨 켜 줘", "서심다응 가자", "어", "잠깐만", "뭐라고", "졸려", "스타벅스 들렀다 가 줘", "추워")) {
            val sync = e.classify(u, c)
            val full = runBlocking { e.parse(u, c) }
            assertEquals("'$u'", full.intent, sync.intent)
            assertEquals("'$u'", full.place, sync.place)
        }
        assertEquals(Intent.NAV_HOME, e.classify("집으로 가자", c).intent)
        assertEquals(Intent.OPEN_TALK, e.classify("어", c).intent)
        assertEquals(Intent.OPEN_TALK, e.classify("뭐라고", c).intent)
        // 반려동물 이름이 비어 있어도 모든 말이 펫 모드로 가지 않는다
        val noPet = c.copy(flags = SimFlags(petName = ""))
        assertEquals(Intent.OPEN_TALK, e.classify("음", noPet).intent)
    }

    // ───────────────────────── 최종 검토 1: 경유지 질문에 경유 동사로 대답 ─────────────────────────

    /**
     * 회귀 테스트 (최종 검토 1): 경유지 질문 "성심당 대전역점에 들를까요?"(HUD 버튼 "응, 들르자")에
     * "응 들르자"·"그래 들러 줘"·"들렀다 가자"가 대답으로 읽히지 않고, "아니 들렀다 가자"는 다른 명령이 되던 문제
     */
    @Test
    fun reply_final1_stopVerbsAnswerAWaypointQuestion() {
        for (u in listOf(
            "응 들르자", "그래 들러 줘", "응 들러 줘", "들렀다 가자", "응 들렀다 가자",
            "응, 들르자", "들르자", "들러 줘", "들러 주세요", "네 들렀다 가 주세요", "응 들러서 가자", "거기 들르자", "그래 경유해 줘", "들를게요",
            "잠깐 들렀다 가자", "응 일단 들르자",
        )) assertEquals("'$u'", Reply.Yes, QueryParser.reply(u))
        // 뒤에 장소 없이 경유 동사만 이어진 "아니 …"는 그냥 '아니야' (다른 명령으로 넘기지 않는다)
        for (u in listOf("아니 들렀다 가자", "아니 들러 줘", "아니야 들르자", "아니 잠깐 들렀다 가자")) assertEquals("'$u'", Reply.No, QueryParser.reply(u))
        // 지난 일·장소 명령·'그냥 가자'(들르지 말고 가자)는 '네'가 아니다
        for (u in listOf("들렀다", "아까 들렀다 왔어", "그냥 가자", "성심당 들렀다 가자", "본점 들르자")) assertEquals("'$u'", Reply.None, QueryParser.reply(u))
        // 번호 대답·기존 대답은 그대로
        assertEquals(Reply.Pick(2), QueryParser.reply("3번으로 들러 줘"))
        assertEquals(Reply.NoAnd("본점 들렀다 가자"), QueryParser.reply("아니 본점 들렀다 가자"))
        assertEquals(Reply.None, QueryParser.reply("경유 넣어 줘"))
    }

    /** 대답이 '들러 가자'는 뜻인지 — 목적지를 묻던 중이면 경유지로 바꿔 듣는다 (최종 검토 2) */
    @Test
    fun asksStop_final2_stopVerbRepliesAndWaypointCommands() {
        for (u in listOf("응 들르자", "들렀다 가자", "그래 들러 줘", "성심당 들렀다 가 줘", "본점 들렀다 가자", "2번 들러 줘", "아니 본점 들렀다 가자", "본점 들르자"))
            assertTrue("'$u'", QueryParser.asksStop(u))
        for (u in listOf("응", "네 가 주세요", "성심당 가자", "본점", "본점으로 가자", "2번", "아니야", "들렀다", "들러리"))
            assertFalse("'$u'", QueryParser.asksStop(u))
    }

    /** 회귀 테스트 (최종 검토 1): 장소 없이 경유 동사만 말한 대답("들렀다 가자")이 여정 계획(TRIP_PLAN)이 되어 질문을 지우던 문제 */
    @Test
    fun intentEngine_final1_bareStopVerbIsNotATripPlan() {
        val e = KeywordIntentEngine()
        val c = ctx()
        for (u in listOf("들렀다 가자", "응 들렀다 가자", "아니 들렀다 가자", "그래 들러서 가자", "거기 들렀다 가 줘", "잠깐 들렀다 가자", "어 들렀다 가자")) {
            assertTrue("'$u'", QueryParser.bareStopVerb(u))
            assertEquals("'$u'", Intent.OPEN_TALK, e.classify(u, c).intent)
        }
        // 앞에 장소가 있거나 뒤에 다른 일정이 이어지면 그대로 여정 계획
        for (u in listOf(
            "스타벅스 들렀다 회사로 가자", "다음 휴게소 들렀다 가자", "급한데 화장실 들렀다 가자", "들렀다 회사로 가자",
            "아이들이랑 강릉 가는데 충전이랑 쉬는 곳 같이 들러서 가는 계획 세워줘",
        )) {
            assertFalse("'$u'", QueryParser.bareStopVerb(u))
            assertEquals("'$u'", Intent.TRIP_PLAN, e.classify(u, c).intent)
        }
        // 장소 + 경유 동사는 경유지 장소 명령, 졸음 + 경유 동사는 졸음 케어 (그대로)
        val nav = e.classify("성심당 들렀다 가 줘", c)
        assertEquals(Intent.NAV_PLACE, nav.intent)
        assertEquals(StopMode.WAYPOINT, nav.place?.mode)
        assertEquals(Intent.DROWSY, e.classify("졸려서 휴게소 들렀다 가자", c).intent)
        assertFalse(QueryParser.bareStopVerb("성심당 가자"))
    }

    /** 회귀 테스트 (5차 검토): "쉬었다 가자"·"잠깐 쉬어 가자"가 장소 '쉬었다'로 읽혀 성심당 등을 되묻던 문제 → 휴게소 경유 */
    @Test
    fun intentEngine_round5_restPhrasesAreRestStopsNotPlaces() {
        val e = KeywordIntentEngine()
        val c = ctx()
        for (u in listOf("쉬었다 가자", "쉬어 가자", "잠깐 쉬어 가자", "좀 쉬었다 가자", "그럼 쉬었다 가자")) {
            assertNull("'$u' — 장소 명령이 아니다", QueryParser.parse(u))
            assertEquals("'$u'", Intent.ADD_REST_STOP, e.classify(u, c).intent)
        }
        // 장소 이름은 그대로
        assertEquals("성심당", QueryParser.parse("성심당 가자")?.text)
    }
}
