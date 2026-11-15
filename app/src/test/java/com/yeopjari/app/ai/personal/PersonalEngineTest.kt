package com.yeopjari.app.ai.personal

import com.yeopjari.app.ai.context.DriveContext
import com.yeopjari.app.ai.context.EnergyModel
import com.yeopjari.app.ai.context.FatigueModel
import com.yeopjari.app.ai.context.LoadModel
import com.yeopjari.app.ai.context.UserProfile
import com.yeopjari.app.ai.nlu.Intent
import com.yeopjari.app.ai.nlu.KeywordIntentEngine
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.VehicleSnapshot
import com.yeopjari.app.sim.SimFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 개인화 장소 이해 엔진의 동작 테스트 — 앱이 쓰는 실제 엔진 코드(Hangul · QueryParser · PersonalBrain)를
 * JVM에서 그대로 돌린다. 시나리오 좌표·시각은 sim/DriveSimulator.kt 의 P_DAEJEON · P_WEEKDAY · P_WEEKEND 와 같다.
 */
class PersonalEngineTest {

    // ───────────────────────── 상황 ─────────────────────────

    private val base = PersonaFactory.BASE_DAY
    private val parents = PersonaFactory.poiById("parents")!!.place

    private fun sit(day: Int, h: Int, m: Int, lat: Double?, lng: Double?, dLat: Double? = null, dLng: Double? = null) =
        Situation(day, LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value, h * 60 + m, lat, lng, dLat, dLng)

    /** P_DAEJEON: 토요일 10:04, 경부고속도로 위, 목적지 대전 본가 */
    private fun daejeon() = sit(base, 10, 4, 36.482, 127.347, parents.lat, parents.lng)

    /** P_WEEKDAY: 목요일 08:31, 판교역로, 목적지 회사 ([daysBefore]를 바꾸면 같은 시각·위치의 다른 평일 — 3: 수, 4: 화) */
    private fun weekdayCommute(daysBefore: Int = 2) = sit(base - daysBefore, 8, 31, 37.4176, 127.1074, 37.4020, 127.1086)

    /** P_WEEKEND: 토요일 10:50, 잠실 집 앞 정차, 목적지 없음 */
    private fun weekendHome() = sit(base, 10, 50, 37.5133, 127.1001)

    /** 평일(목) 18:30, 집 근처, 목적지 없음 */
    private fun weekdayEveningHome() = sit(base - 2, 18, 30, 37.5133, 127.1001)

    private fun q(text: String) = PlaceQuery(text)

    private fun describe(r: Resolution): String = buildString {
        append("'${r.query.text}' tier=${r.tier} corrected=${r.correctedTo} aliasHit=${r.aliasHit}\n")
        for (c in r.candidates) {
            val f = c.f
            append("  %-14s p=%.3f score=%.2f phon=%.2f freq=%.2f time=%.2f dist=%.2f recent=%.2f pref=%.2f %s\n".format(
                c.poi.place.id, c.prob, c.score, f.phon, f.freq, f.time, f.dist, f.recent, f.pref, c.tags,
            ))
        }
    }

    private fun prob(r: Resolution, id: String): Double = r.candidates.firstOrNull { it.poi.place.id == id }?.prob ?: 0.0

    // ───────────────────────── Hangul ─────────────────────────

    @Test
    fun hangul_soundAlikeBrandBeatsSpellingNeighbour() {
        val toStore = Hangul.similarity("서심다응", "성심당 대전역점")
        val toChurch = Hangul.similarity("서심다응", "대흥동 성당")
        assertTrue("서심다응→성심당 $toStore vs →대흥동 성당 $toChurch", toStore > toChurch)
        assertTrue("오인식이 남아도 후보 기준(${PersonalBrain.MIN_PHON}) 이상이어야 함: $toStore", toStore >= PersonalBrain.MIN_PHON)
    }

    @Test
    fun hangul_brandInsideBranchNameIsExact() {
        assertEquals(1.0, Hangul.similarity("스타벅스", "스타벅스 잠실새내역점"), 1e-9)
        assertEquals("띄어쓰기는 소리에 영향 없음", 1.0, Hangul.similarity("올림픽 공원", "올림픽공원"), 1e-9)
    }

    @Test
    fun hangul_roParticle() {
        assertEquals("으로", Hangul.ro("성심당 대전역점"))
        assertEquals("로", Hangul.ro("대전 본가"))
        assertEquals("ㄹ 받침은 '로'", "로", Hangul.ro("서울"))
    }

    // ───────────────────────── QueryParser.parse ─────────────────────────

    @Test
    fun parse_destinationName() {
        val p = QueryParser.parse("서심다응 가자")
        assertNotNull(p)
        assertEquals("서심다응", p!!.text)
        assertEquals(StopMode.DESTINATION, p.mode)
        assertNull(p.category)
    }

    @Test
    fun parse_stripsParticleBeforeVerb() {
        assertEquals("성심당", QueryParser.parse("성심당으로 가 줘")?.text)
        assertEquals("엄마 집", QueryParser.parse("엄마 집 가자")?.text)
        assertEquals("엄마 집", QueryParser.parse("엄마 집에 가자")?.text)
    }

    @Test
    fun parse_waypointCategoryAndHabit() {
        val p = QueryParser.parse("빵집 들렀다 가자")!!
        assertEquals(StopMode.WAYPOINT, p.mode)
        assertEquals(Category.BAKERY, p.category)
        assertFalse(p.habit)

        val h = QueryParser.parse("늘 가던 빵집 들렀다 가자")!!
        assertTrue(h.habit)
        assertEquals(Category.BAKERY, h.category)
        assertEquals(StopMode.WAYPOINT, h.mode)
    }

    @Test
    fun parse_leavesOtherIntentsAlone() {
        for (u in listOf(
            "집으로 가자", "회사로 가자", "다음 휴게소 들렀다 가자", "충전소 가자", "졸려",
            "아이들이랑 강릉 가는데 충전이랑 쉬는 거 같이 계획 세워줘",
        )) assertNull("'$u' 는 장소 보정 대상이 아님", QueryParser.parse(u))
    }

    /**
     * 회귀 테스트: 말머리 군말(음·어·그…) 제거가 단어 경계를 보지 않아
     * '어린이집' → '린이집', '어마 집'(엄마 집 오인식) → '마 집' 으로 잘리던 문제.
     */
    @Test
    fun parse_fillerIsStrippedOnlyAsSeparateWord() {
        assertEquals("성심당", QueryParser.parse("음 성심당 가자")?.text)
        assertEquals("스타벅스", QueryParser.parse("옆자리야, 스타벅스 가자")?.text)
        assertEquals("성심당", QueryParser.parse("어 성심당 들렀다 가자")?.text)
        val kids = QueryParser.parse("어린이집 들렀다 가자")
        assertEquals("어린이집", kids?.text)
        assertEquals(Category.KIDS, kids?.category)
        assertEquals("어마 집", QueryParser.parse("어마 집 가자")?.text)
        assertEquals("그랜드 하얏트", QueryParser.parse("그랜드 하얏트 가자")?.text)
    }

    /** 앱의 온디바이스 의도 엔진에서도 같은 판단인지 (장소 보정 vs 여정 계획) */
    @Test
    fun appIntentEngine_routesPlaceCommandsOnly() {
        val v = VehicleSnapshot()
        val r = RouteState()
        val ctx = DriveContext(
            vehicle = v, route = r, clockSec = 10 * 3600f, clockLabel = "10:00", flags = SimFlags(), profile = UserProfile(),
            energy = EnergyModel.forecast(v, r), fatigue = FatigueModel.assess(v, r, 10), load = LoadModel.estimate(v, r),
        )
        val engine = KeywordIntentEngine()
        runBlocking {
            val nav = engine.parse("서심다응 가자", ctx)
            assertEquals(Intent.NAV_PLACE, nav.intent)
            assertEquals("서심다응", nav.place?.text)

            val plan = engine.parse("아이들이랑 강릉 가는데 충전이랑 쉬는 거 같이 계획 세워줘", ctx)
            assertNotEquals(Intent.NAV_PLACE, plan.intent)
            assertNull(plan.place)

            assertEquals(Intent.NAV_HOME, engine.parse("집으로 가자", ctx).intent)
        }
    }

    // ───────────────────────── QueryParser.reply / matchOption ─────────────────────────

    @Test
    fun reply_understandsShortAnswers() {
        assertEquals(Reply.Yes, QueryParser.reply("응"))
        assertEquals(Reply.Yes, QueryParser.reply("네 맞아요"))
        assertEquals(Reply.No, QueryParser.reply("아니야"))
        assertEquals(Reply.NoAnd("본점"), QueryParser.reply("아니 본점"))
        assertEquals(Reply.Pick(1), QueryParser.reply("두 번째"))
        assertEquals(Reply.Pick(1), QueryParser.reply("2번"))
        assertEquals(Reply.Pick(0), QueryParser.reply("1번이요"))
        assertEquals(Reply.Pick(2), QueryParser.reply("셋"))
        assertEquals(Reply.Cancel, QueryParser.reply("취소"))
        assertEquals(Reply.None, QueryParser.reply("어디야"))
        assertEquals(Reply.None, QueryParser.reply("노래 틀어줘"))
    }

    @Test
    fun matchOption_picksBranchFromPartialName() {
        val res = PersonalBrain().resolve(q("서심다응"), daejeon())
        val opts = res.candidates.take(3)
        assertEquals("ssd_main", QueryParser.matchOption("본점", opts)?.poi?.place?.id)
        assertEquals("ssd_station", QueryParser.matchOption("역에 있는 거", opts)?.poi?.place?.id)
    }

    /**
     * 회귀 테스트 (리뷰 R0.4): 대답을 닫힌 낱말 목록 전체 일치로만 읽어서
     * "네 가 주세요"·"그렇게 해 줘"가 None → 새 장소 명령('네')으로 처리되던 문제. 혼자 말한 '어'(머뭇거림)는 '네'가 아니다.
     */
    @Test
    fun reply_yesWithVerbTailsAndDemonstratives() {
        for (u in listOf(
            "네 가주세요", "네 안내해 주세요", "응 가자", "네 출발", "그렇게 해 줘", "거기로 가자", "그대로 가자", "그걸로", "그거요", "그리로 가 줘",
            "어 그래", "어 가자", "출발", "안내 시작", "좋아 그걸로 가자", "네, 맞아요",
        )) assertEquals("'$u'", Reply.Yes, QueryParser.reply(u))
        for (u in listOf("어", "음", "어…", "네비 꺼줘", "좋아하는 노래 틀어줘", "그래도 본점")) assertEquals("'$u'", Reply.None, QueryParser.reply(u))
    }

    /** 회귀 테스트 (리뷰 R0.4): "1번 안내해줘"·"2번 갈래"가 장소 '1번'으로, "4번"·"네 번째"가 자유 대화로 가던 문제 */
    @Test
    fun reply_ordinalsWithVerbTailsAndOutOfRangeNumbers() {
        assertEquals(Reply.Pick(0), QueryParser.reply("1번 안내해줘"))
        assertEquals(Reply.Pick(1), QueryParser.reply("2번 갈래"))
        assertEquals(Reply.Pick(1), QueryParser.reply("두 번째로 가 주세요"))
        assertEquals(Reply.Pick(0), QueryParser.reply("첫 번째 걸로"))
        assertEquals(Reply.Pick(2), QueryParser.reply("세 번째 거요"))
        assertEquals(Reply.Pick(2), QueryParser.reply("3번으로 들러 줘"))
        assertEquals(Reply.Pick(1), QueryParser.reply("음, 2번"))
        // 화면보다 큰 번호도 번호 그대로 → 받는 쪽이 "3번까지 있어요"로 안내
        assertEquals(Reply.Pick(3), QueryParser.reply("4번"))
        assertEquals(Reply.Pick(3), QueryParser.reply("네 번째"))
        assertEquals(Reply.Pick(3), QueryParser.reply("넷"))
        assertEquals(Reply.Pick(4), QueryParser.reply("다섯 번째 거"))
        assertEquals("'네' 혼자는 번호가 아니라 대답", Reply.Yes, QueryParser.reply("네"))
        assertEquals(Reply.None, QueryParser.reply("한 번 더 말해 줘"))
    }

    @Test
    fun reply_negationVariants() {
        assertEquals(Reply.Cancel, QueryParser.reply("아니 됐어"))
        assertEquals(Reply.No, QueryParser.reply("아니 다른 데로 가자"))
        assertEquals(Reply.No, QueryParser.reply("다른 데"))
        assertEquals(Reply.No, QueryParser.reply("응 아니야"))
        assertEquals(Reply.No, QueryParser.reply("아니요"))
        assertEquals("'에버랜드'의 '에'를 조사로 떼지 않음", Reply.NoAnd("에버랜드"), QueryParser.reply("아니 에버랜드"))
        val r = QueryParser.reply("아니야, 거기 말고 본점으로")
        assertTrue("$r", r is Reply.NoAnd)
        val opts = PersonalBrain().resolve(q("서심다응"), daejeon()).candidates
        assertEquals("ssd_main", QueryParser.matchOption((r as Reply.NoAnd).rest, opts)?.poi?.place?.id)
    }

    /**
     * 회귀 테스트 (리뷰 R0.1): 군말·짧은 말이 긴 지점 이름의 일부에 붙던 문제.
     * '그거요' → '그요' → (판)'교', '빨리' → (테크노)'밸리', '노래' → '노밸' … P_WEEKEND 확인 단계에서 판교점으로 출발하고 학습까지 했다.
     */
    @Test
    fun matchOption_ignoresFillersAndFragmentsOfLongNames() {
        val starbucks = PersonalBrain().resolve(q("스타벅스"), weekendHome()).candidates
        val ssd = PersonalBrain().resolve(q("서심다응"), daejeon()).candidates
        for (opts in listOf(starbucks, ssd)) for (u in listOf("그거요", "그거", "이거", "거기", "빨리", "노래", "다시", "이전", "아까 거", "음", "어", "점", "노래 틀어줘")) {
            assertNull("'$u' → ${QueryParser.matchOption(u, opts)?.poi?.spoken}", QueryParser.matchOption(u, opts))
        }
    }

    /** 회귀 테스트 (리뷰 R0.1·R0.9): 대답의 조사·동사 꼬리·'요'를 떼고 지점 이름 단어·별칭과 비교한다 */
    @Test
    fun matchOption_stripsParticlesVerbTailsAndEndings() {
        val ssd = PersonalBrain().resolve(q("서심다응"), daejeon()).candidates
        val expect = linkedMapOf(
            "본점이요" to "ssd_main", "본점으로 가자" to "ssd_main", "성심당 본점으로 가자" to "ssd_main", "응 본점" to "ssd_main",
            "대전역점" to "ssd_station", "대전역" to "ssd_station", "DCC점" to "ssd_dcc", "디씨씨" to "ssd_dcc", "봉점" to "ssd_main",
        )
        for ((u, id) in expect) assertEquals("'$u'", id, QueryParser.matchOption(u, ssd)?.poi?.place?.id)
        val starbucks = PersonalBrain().resolve(q("스타벅스"), weekendHome()).candidates
        assertEquals("sb_pangyo", QueryParser.matchOption("판교점", starbucks)?.poi?.place?.id)
        assertEquals("sb_jamsil", QueryParser.matchOption("잠실이요", starbucks)?.poi?.place?.id)
        assertEquals("sb_gangnam", QueryParser.matchOption("강남", starbucks)?.poi?.place?.id)
    }

    /**
     * 회귀 테스트 (리뷰 R0.6): 동사 앞에 다른 절이 붙은 명령이 통째로 장소 이름이 되던 문제
     * ('졸려서 휴게소' → 못 찾음, '급한데 화장실' → 116km 떨어진 파리바게뜨 확인, '스타벅스 말고 회사로' → 스타벅스 확인).
     */
    @Test
    fun parse_rejectsMixedCommands() {
        for (u in listOf(
            "졸려서 휴게소 들렀다 가자", "급한데 화장실 들렀다 가자", "에어컨 켜고 집으로 가자", "스타벅스 말고 회사로 가자",
            "스타벅스 대신 투썸 가자", "배고파서 맥도날드 가자", "스타벅스 들렀다 회사로 가자", "피곤한데 성심당 가자", "횡성휴게소 가자",
        )) assertNull("'$u' → ${QueryParser.parse(u)?.text}", QueryParser.parse(u))
        // 이름 안의 비슷한 글자는 막지 않는다
        assertEquals("엄마 집", QueryParser.parse("엄마 집으로 가자")?.text)
        assertEquals("롯데 마트", QueryParser.parse("롯데 마트 가자")?.text)
        assertEquals("경찰서", QueryParser.parse("경찰서 가자")?.text)
        assertEquals("대신동 성당", QueryParser.parse("대신동 성당 가자")?.text)
        assertEquals("바다가 보이는 카페", QueryParser.parse("바다가 보이는 카페 가자")?.text)
        // 한 음절 앞에서 끊긴 '…고'는 절 끝이 아니라 음성인식의 띄어쓰기 ('올림픽공원' → '올림픽고 원')
        assertEquals("올림픽고 원", QueryParser.parse("올림픽고 원 가자")?.text)
        // '좀'은 띄어 썼을 때만 꼬리 ('현대백화점' → '현대백화좀'의 끝을 자르지 않음)
        assertEquals("현대백화좀", QueryParser.parse("현대백화좀 가자")?.text)
        assertEquals("성심당", QueryParser.parse("성심당 좀 가자")?.text)
    }

    /** 회귀 테스트 (리뷰 R0.6): 앱 의도 엔진에서도 섞인 명령은 각자의 의도로 간다 */
    @Test
    fun appIntentEngine_mixedCommandsGoToTheirOwnIntent() {
        val v = VehicleSnapshot()
        val r = RouteState()
        val ctx = DriveContext(
            vehicle = v, route = r, clockSec = 10 * 3600f, clockLabel = "10:00", flags = SimFlags(), profile = UserProfile(),
            energy = EnergyModel.forecast(v, r), fatigue = FatigueModel.assess(v, r, 10), load = LoadModel.estimate(v, r),
        )
        val engine = KeywordIntentEngine()
        runBlocking {
            assertEquals("졸음 케어(환기 + 쉼터 경유 제안)", Intent.DROWSY, engine.parse("졸려서 휴게소 들렀다 가자", ctx).intent)
            assertNotEquals(Intent.NAV_PLACE, engine.parse("급한데 화장실 들렀다 가자", ctx).intent)
            assertEquals(Intent.HVAC_COOLER, engine.parse("에어컨 켜고 집으로 가자", ctx).intent)
            assertEquals(Intent.NAV_WORK, engine.parse("스타벅스 말고 회사로 가자", ctx).intent)
            val mom = engine.parse("엄마 집으로 가자", ctx)
            assertEquals("'엄마 집으로'는 집 안내가 아니라 개인 장소", Intent.NAV_PLACE, mom.intent)
            assertEquals("엄마 집", mom.place?.text)
            assertEquals(Intent.NAV_PLACE, engine.parse("서심다응 들렀다 가자", ctx).intent)
        }
    }

    // ───────────────────────── 학습: 무엇을 기억하나 ─────────────────────────

    /**
     * 회귀 테스트 (리뷰 R0.2): 고른 곳이 무엇이든 '들린 말 → 장소'를 기억해서,
     * 확인 단계의 "아니, 병원"이 '스타벅스 = 소아과'로 남고 다음 '스타벅스'의 1위가 소아과가 되던 문제.
     */
    @Test
    fun learn_onlyMemorizesCorrectedMishearings() {
        // (a) 발음이 약하게만 비슷한 후보(소아과, 발음 0.56)를 골라도 표현·선택 기억은 남기지 않는다
        val brain = PersonalBrain()
        val r = brain.resolve(q("스타벅스"), weekendHome())
        assertTrue(describe(r), r.candidates.any { it.poi.place.id == "pedi" })
        val out = brain.learn(r, "pedi")!!
        assertNull(out.alias)
        assertTrue(brain.aliases.isEmpty())
        assertTrue(brain.choiceMemory.isEmpty())
        assertEquals("sb_jamsil", brain.resolve(q("스타벅스"), weekendHome()).top?.poi?.place?.id)

        // (b) 이미 정확한 이름('스타벅스')은 기억할 필요가 없다 — 순위 가중치만 배운다
        val exact = PersonalBrain()
        exact.learn(exact.resolve(q("스타벅스"), weekendHome()), "sb_pangyo")
        assertTrue(exact.aliases.isEmpty())
        assertTrue(exact.choiceMemory.isEmpty())
        assertEquals(1, exact.learnCount)

        // (c) 오인식을 바로잡은 선택은 기억한다 (시연의 핵심 흐름)
        val misheard = PersonalBrain()
        val o = misheard.learn(misheard.resolve(q("서심다응"), daejeon()), "ssd_station")!!
        assertEquals("서심다응" to "성심당", o.alias)
        assertEquals(1, misheard.choiceMemory["서심다응"]?.get("ssd_station"))

        // (d) 같은 오인식이라도 발음이 먼 후보(대흥동 성당 0.61)를 고르면 기억하지 않는다
        val far = PersonalBrain()
        val rf = far.resolve(q("서심다응"), daejeon())
        assertTrue(describe(rf), rf.candidates.any { it.poi.place.id == "church_dh" && it.f.phon < PersonalBrain.AUTO_MIN_PHON })
        far.learn(rf, "church_dh")
        assertTrue(far.aliases.isEmpty())
        assertTrue(far.choiceMemory.isEmpty())
    }

    /** 회귀 테스트 (리뷰 R0.2): 기억한 표현이 다른 곳 한 번 고른 것으로 조용히 덮어써지던 문제 */
    @Test
    fun learn_aliasNeedsMoreVotesToChange() {
        val pois = listOf(
            Poi(Place("daon", "다온 카페", PlaceKind.CAFE, 37.5100, 127.1000, "가상"), "다온", Category.CAFE),
            Poi(Place("taon", "타온 식당", PlaceKind.DESTINATION, 37.5200, 127.1100, "가상"), null, Category.OTHER),
        )
        val brain = PersonalBrain(pois, emptyList())
        val s = weekendHome()
        // '따온'은 두 곳 모두와 발음 0.9 — 처음 고른 다온 카페로 표현을 기억
        assertEquals("따온" to "다온", brain.learn(brain.resolve(q("따온"), s), "daon")?.alias)
        // 같은 말로 타온 식당을 한 번 골라도 (1:1) 표현은 그대로
        assertNull(brain.learn(brain.resolve(q("따온"), s), "taon")?.alias)
        assertEquals("다온", brain.aliases["따온"])
        // 타온 식당을 더 많이 고르면 (2:1) 그때 바꾼다
        assertEquals("따온" to "타온 식당", brain.learn(brain.resolve(q("따온"), s), "taon")?.alias)
        assertEquals("taon", brain.aliases["따온"])
    }

    /**
     * 회귀 테스트 (리뷰 R0.11 + 평가 발견): 선택 기억이 '골랐으면 선호 1.0'이라
     * 평일에 고른 지점이 주말의 같은 말까지 끌고 가고, 거절을 반영하지 못하던 문제.
     * 이제 선호 = 같은 요일 종류(평일·주말)에 고른 기록 중 이 곳의 몫, 없으면 전체 몫의 절반.
     */
    @Test
    fun choiceMemory_isShareSplitByWeekdayWeekend() {
        val misheard = q("스타버스") // 스타벅스의 오인식 (발음 0.88)
        val brain = PersonalBrain()
        for (d in 2..4) brain.learn(brain.resolve(misheard, weekdayCommute(d)), "sb_pangyo") // 목·수·화 출근길
        assertEquals(3, brain.choiceMemory["스타버스"]?.get("sb_pangyo"))
        assertEquals(3, brain.choiceByDay["스타버스"]?.get("sb_pangyo")?.get(0))

        // 주말: 같은 요일 종류의 기록이 없으니 선호는 전체 몫의 절반 → 평소 주말 지점(잠실)이 그대로 1위
        val sat = brain.resolve(misheard, weekendHome())
        val msg = describe(sat)
        assertEquals(msg, 0.5, sat.candidates.first { it.poi.place.id == "sb_pangyo" }.f.pref, 1e-9)
        assertEquals(msg, "sb_jamsil", sat.top?.poi?.place?.id)

        // 주말에 잠실을 한 번 고르면 주말 선호는 잠실 1.0 · 판교 0, 평일은 그대로 판교
        brain.learn(sat, "sb_jamsil")
        val sat2 = brain.resolve(misheard, weekendHome())
        assertEquals(describe(sat2), 1.0, sat2.candidates.first { it.poi.place.id == "sb_jamsil" }.f.pref, 1e-9)
        assertEquals(describe(sat2), 0.0, sat2.candidates.first { it.poi.place.id == "sb_pangyo" }.f.pref, 1e-9)
        assertEquals("sb_jamsil", sat2.top?.poi?.place?.id)
        assertEquals("sb_pangyo", brain.resolve(misheard, weekdayCommute(2)).top?.poi?.place?.id)

        // 같은 말로 여러 곳을 골랐으면 고른 몫으로 나눈다 (예전: 둘 다 1.0)
        val mixed = PersonalBrain()
        for ((d, id) in listOf(2 to "sb_pangyo", 3 to "sb_pangyo", 4 to "sb_gangnam")) mixed.learn(mixed.resolve(misheard, weekdayCommute(d)), id)
        val m = mixed.resolve(misheard, weekdayCommute(2))
        assertEquals(2.0 / 3, m.candidates.first { it.poi.place.id == "sb_pangyo" }.f.pref, 1e-9)
        assertEquals(1.0 / 3, m.candidates.first { it.poi.place.id == "sb_gangnam" }.f.pref, 1e-9)

        // "아니야"로 거절하면 그 요일 종류의 횟수에서 한 번 덜어 낸다
        assertTrue(mixed.forgetChoice("스타버스", "sb_pangyo", weekdayCommute(2).dow))
        val m2 = mixed.resolve(misheard, weekdayCommute(2))
        assertEquals(0.5, m2.candidates.first { it.poi.place.id == "sb_pangyo" }.f.pref, 1e-9)
        assertEquals(1, mixed.choiceByDay["스타버스"]?.get("sb_pangyo")?.get(0))
    }

    /**
     * 시연 녹화 순서(tools/record_personal_demo.sh 2~5단계)를 엔진 하나로 이어서 확인한다.
     * 주말 '스타벅스'를 "응"으로 확인한 학습이 평일 출근길의 같은 말을 잠실로 끌고 가면 안 된다.
     */
    @Test
    fun demoScript_sequenceKeepsEachSituationRight() {
        val brain = PersonalBrain()
        // 2) 토요일 집 앞 "스타벅스 가자" → 잠실새내역점 한 번 확인 → "응"
        val sat = brain.resolve(q("스타벅스"), weekendHome())
        assertEquals(describe(sat), "sb_jamsil", sat.top?.poi?.place?.id)
        assertEquals(describe(sat), Tier.CONFIRM, sat.tier)
        brain.learn(sat, "sb_jamsil")
        // 3) 평일 출근길 같은 말 → 판교점 바로 안내
        val wd = brain.resolve(q("스타벅스"), weekdayCommute())
        assertEquals(describe(wd), "sb_pangyo", wd.top?.poi?.place?.id)
        assertEquals(describe(wd), Tier.AUTO, wd.tier)
        // 4) 고속도로 "서심다응 가자" → 대전역점 확인 → "아니야"(거절) → 선택지 1번 = 본점
        val ssd = brain.resolve(q("서심다응"), daejeon())
        assertEquals(describe(ssd), "ssd_station", ssd.top?.poi?.place?.id)
        assertEquals(describe(ssd), Tier.CONFIRM, ssd.tier)
        brain.forgetChoice(ssd.query.text, "ssd_station", ssd.situation?.dow)
        val rest = ssd.candidates.filter { it.poi.place.id != "ssd_station" }.take(3)
        assertEquals("ssd_main", rest.first().poi.place.id)
        assertEquals("서심다응" to "성심당", brain.learn(ssd, rest.first().poi.place.id)?.alias)
        // 5) 같은 말 다시 → 배운 대로 본점이 1위 (되묻기 없이 선택지로 떨어지지 않음)
        val again = brain.resolve(q("서심다응"), daejeon())
        assertEquals(describe(again), "ssd_main", again.top?.poi?.place?.id)
        assertNotEquals(describe(again), Tier.CHOOSE, again.tier)
        assertTrue(again.aliasHit)
    }

    @Test
    fun candidateTags_readNaturallyAndHabitualIsTyped() {
        val r = PersonalBrain().resolve(q("서심다응"), daejeon())
        val station = r.candidates.first { it.poi.place.id == "ssd_station" }
        assertTrue(describe(r), "주로 토요일 오전" in station.tags)
        assertTrue(describe(r), station.tags.none { it.endsWith("에 주로") })
        assertTrue("단골 (90일 5회)", station.habitual)
        val main = r.candidates.first { it.poi.place.id == "ssd_main" }
        assertFalse("1회 방문은 단골이 아님", main.habitual)
        // 거리 태그는 현재 위치에서의 직선거리 (순위에 쓴 경로 우회 거리가 아님)
        val dcc = r.candidates.first { it.poi.place.id == "ssd_dcc" }
        assertTrue(describe(r), dcc.onRoute && "가는 길" in dcc.tags)
        assertTrue(describe(r), dcc.kmDirect!! > dcc.km!!)
        assertTrue(describe(r), "${dcc.kmDirect!!.roundToInt()}km" in dcc.tags)
        // 서울아산병원은 이름이 정확해 바로 실행되지만 단골은 아니다 → "늘 가던" 표현 금지
        val asan = PersonalBrain().resolve(q("서울아산병원"), weekendHome())
        assertEquals(describe(asan), Tier.AUTO, asan.tier)
        assertFalse(describe(asan), asan.top!!.habitual)
    }

    // ───────────────────────── 서비스: 서명·학습 기록 사슬 ─────────────────────────

    /**
     * 회귀 테스트 (리뷰 R0.16·R1.12): 화면 갱신마다 연합학습 묶음을 다시 만들고 Keystore 서명을 하던 문제,
     * 잡음 씨앗이 '기록 수·회차'라 서버가 몇백 번 대입으로 잡음을 빼낼 수 있던 문제.
     */
    @Test
    fun service_previewIsUnsignedAndSignedOnlyWhenSent() {
        val s = PersonalService(null)
        val p0 = s.ui.value.packet
        assertEquals(FederatedUpdate.UNSIGNED, p0.signature)
        val r = s.resolve(q("서심다응"), daejeon())
        assertSame("가중치가 그대로면 미리보기를 다시 만들지 않는다", p0, s.ui.value.packet)
        s.learn(r, "ssd_station")
        val p1 = s.ui.value.packet
        assertNotSame(p0, p1)
        assertEquals(FederatedUpdate.UNSIGNED, p1.signature)
        assertNull(s.ui.value.sentPacket)

        s.markSynced()
        val sent = s.ui.value.sentPacket!!
        assertEquals("보낸 본문 = 화면에 보이던 미리보기", p1.body, sent.body)
        assertEquals(HashChain.sha256Hex(sent.body), sent.sha256)
        assertNotEquals(FederatedUpdate.UNSIGNED, sent.signature)
        assertTrue(s.ui.value.ledgerValid)
        assertTrue(s.ui.value.ledger.any { it.summary.contains(sent.sha256.take(8)) })

        // 잡음은 예측할 수 없는 난수에서 — 같은 상태의 두 서비스가 같은 잡음을 내지 않는다
        val a = PersonalService(null).ui.value.packet.delta
        val b = PersonalService(null).ui.value.packet.delta
        assertNotEquals(a, b)
    }

    @Test
    fun service_forgetUsesTheDayOfTheRejectedQuestion() {
        val s = PersonalService(null)
        val r = s.resolve(q("서심다응"), daejeon())
        s.learn(r, "ssd_station")
        assertEquals(1, s.brain.choiceByDay["서심다응"]?.get("ssd_station")?.get(1))
        s.forget(s.resolve(q("서심다응"), daejeon()), "ssd_station")
        assertTrue(s.brain.choiceMemory.isEmpty())
        assertTrue(s.brain.choiceByDay.isEmpty())
        assertTrue(s.ui.value.ledger.last().type == "거절 학습")
        assertTrue(s.ui.value.ledgerValid)
    }

    @Test
    fun ledger_linksChecksOnlyTheNewEntry() {
        val chain = mutableListOf<LedgerEntry>()
        val e1 = HashChain.append(chain, "선택 학습", "‘서심다응’ → 성심당 대전역점", 1L)
        assertTrue(HashChain.links(null, e1))
        chain += e1
        val e2 = HashChain.append(chain, "표현 기억", "‘서심다응’ = 성심당", 2L)
        assertTrue(HashChain.links(e1, e2))
        assertFalse("다른 기록 뒤에는 이어지지 않음", HashChain.links(null, e2))
        assertFalse("고친 기록", HashChain.links(e1, e2.copy(summary = "‘서심다응’ = 대흥동 성당")))
    }

    // ───────────────────────── 조사 ─────────────────────────

    /** 회귀 테스트 (리뷰 R0.17): '‘에펠탑’는', '하율가', '집로' */
    @Test
    fun hangul_particlesFollowFinalConsonant() {
        assertEquals("은", Hangul.eunNeun("에펠탑"))
        assertEquals("는", Hangul.eunNeun("스타벅스"))
        assertEquals("하율이가", Hangul.nameSubject("하율"))
        assertEquals("아이가", Hangul.nameSubject("아이"))
        assertEquals("하율이", Hangul.nameStem("하율"))
        assertEquals("콩이", Hangul.nameStem("콩이"))
        assertEquals("아", Hangul.aYa("하율"))
        assertEquals("야", Hangul.aYa("콩이"))
        assertEquals("과", Hangul.waGwa("성심당"))
        assertEquals("와", Hangul.waGwa("스타벅스"))
        assertEquals("으로", Hangul.ro("집"))
    }

    // ───────────────────────── PersonalBrain 시나리오 ─────────────────────────

    /** P_DAEJEON: '서심다응' → 성심당 대전역점 한 번 확인 → "응" → 학습 → 다시 말하면 바로 실행 */
    @Test
    fun daejeon_misheardStore_confirmThenAutoAfterLearning() {
        val brain = PersonalBrain()
        val r1 = brain.resolve(q("서심다응"), daejeon())
        val msg = describe(r1)
        assertEquals(msg, "ssd_station", r1.top?.poi?.place?.id)
        assertEquals(msg, Tier.CONFIRM, r1.tier)
        assertEquals(msg, "성심당", r1.correctedTo)
        assertFalse(r1.aliasHit)

        val out = brain.learn(r1, "ssd_station")
        assertNotNull(out)
        assertEquals("HUD 알림 '기억했어요 · ‘서심다응’ = 성심당'", "서심다응" to "성심당", out!!.alias)

        val r2 = brain.resolve(q("서심다응"), daejeon())
        val msg2 = msg + "--- after learn ---\n" + describe(r2)
        assertEquals(msg2, "ssd_station", r2.top?.poi?.place?.id)
        assertEquals(msg2, Tier.AUTO, r2.tier)
        assertTrue(msg2, r2.aliasHit)
        assertTrue(msg2, prob(r2, "ssd_station") > prob(r1, "ssd_station"))
        // 바로 실행 후 "아니야" → 나머지 후보를 HUD 선택지로 (최대 3개)
        val rest = r2.candidates.filter { it.poi.place.id != "ssd_station" }.take(3)
        assertTrue(msg2, rest.any { it.poi.place.id == "ssd_main" })
    }

    /** 확인 단계에서 "아니야" → 본점을 고르면 다음엔 본점 쪽으로 기운다 */
    @Test
    fun daejeon_rejectAndPickMain_shiftsTowardMain() {
        val brain = PersonalBrain()
        val r1 = brain.resolve(q("서심다응"), daejeon())
        val before = prob(r1, "ssd_main")
        assertNotNull(brain.learn(r1, "ssd_main"))
        val r2 = brain.resolve(q("서심다응"), daejeon())
        val msg = describe(r1) + "--- after picking ssd_main ---\n" + describe(r2)
        assertTrue(msg, prob(r2, "ssd_main") > before)
        assertEquals(msg, "ssd_main", r2.top?.poi?.place?.id)
    }

    /** P_WEEKDAY: 평일 출근길 '스타벅스' → 늘 가던 판교점 바로 실행 */
    @Test
    fun weekday_starbucksGoesStraightToPangyo() {
        val r = PersonalBrain().resolve(q("스타벅스"), weekdayCommute())
        val msg = describe(r)
        assertEquals(msg, "sb_pangyo", r.top?.poi?.place?.id)
        assertEquals(msg, Tier.AUTO, r.tier)
        assertNull("정확히 말했으면 보정 표시 없음", r.correctedTo)
    }

    /** P_WEEKEND: 같은 '스타벅스'도 토요일 오전 집 앞에선 잠실새내역점 */
    @Test
    fun weekend_starbucksMeansJamsil() {
        val r = PersonalBrain().resolve(q("스타벅스"), weekendHome())
        assertEquals(describe(r), "sb_jamsil", r.top?.poi?.place?.id)
    }

    @Test
    fun alias_momsHouseIsParents() {
        for (s in listOf(weekendHome(), daejeon())) {
            val r = PersonalBrain().resolve(q("엄마 집"), s)
            assertEquals(describe(r), "parents", r.top?.poi?.place?.id)
            assertNotEquals(describe(r), Tier.CHOOSE, r.tier)
        }
    }

    @Test
    fun category_bakeryDependsOnSituation() {
        val brain = PersonalBrain()
        val sat = brain.resolve(PlaceQuery("빵집", StopMode.WAYPOINT, Category.BAKERY), daejeon())
        assertEquals(describe(sat), "ssd_station", sat.top?.poi?.place?.id)
        assertNull("범주어는 보정 표시 없음", sat.correctedTo)

        val eve = brain.resolve(PlaceQuery("빵집", StopMode.WAYPOINT, Category.BAKERY), weekdayEveningHome())
        assertEquals(describe(eve), "pb_lake", eve.top?.poi?.place?.id)
    }

    @Test
    fun unknownPlace_returnsNoneInsteadOfInventing() {
        val r = PersonalBrain().resolve(q("에펠탑"), weekendHome())
        assertEquals(describe(r), Tier.NONE, r.tier)
        assertTrue(r.candidates.isEmpty())
    }

    /**
     * 회귀 테스트: 사전에 없는 곳을 말했는데 소리가 약하게만 비슷한 후보 하나가 남으면 확률이 1.0이 되어
     * 확인 없이 엉뚱한 곳으로 출발하던 문제 ('다이소' → 성심당 본점, '한강공원' → 올림픽공원).
     */
    @Test
    fun weakSoundAlike_neverStartsWithoutAsking() {
        for (s in listOf(weekdayCommute(), weekendHome(), daejeon())) for (said in listOf("다이소", "한강공원", "하나로마트", "롯데월드", "도서관")) {
            val r = PersonalBrain().resolve(q(said), s)
            assertNotEquals(describe(r), Tier.AUTO, r.tier)
        }
        // 이름을 제대로 말했거나 배운 표현이면 여전히 바로 실행된다
        assertEquals(Tier.AUTO, PersonalBrain().resolve(q("스타벅스"), weekdayCommute()).tier)
    }

    @Test
    fun erasedHistory_fallsBackToSoundOnly() {
        val r = PersonalBrain(visits = emptyList()).resolve(q("스타벅스"), weekdayCommute())
        val msg = describe(r)
        assertNotEquals(msg, Tier.AUTO, r.tier)
        // 기록이 없으면 거리만 남는다 → 가장 가까운 판교점이 1위지만 확신하지 않는다
        assertEquals(msg, "sb_pangyo", r.top?.poi?.place?.id)
    }

    // ───────────────────────── 학습 기록 사슬 · 연합학습 묶음 ─────────────────────────

    @Test
    fun ledger_detectsTampering() {
        val chain = mutableListOf<LedgerEntry>()
        chain += HashChain.append(chain, "선택 학습", "‘서심다응’ → 성심당 대전역점", 1L)
        chain += HashChain.append(chain, "표현 기억", "‘서심다응’ = 성심당", 2L)
        chain += HashChain.append(chain, "기록 삭제", "사용자 요청", 3L)
        assertTrue(HashChain.verify(chain))
        val tampered = chain.toMutableList().also { it[1] = it[1].copy(summary = "‘서심다응’ = 대흥동 성당") }
        assertFalse(HashChain.verify(tampered))
        assertFalse("중간 기록 삭제도 탐지", HashChain.verify(chain.filterIndexed { i, _ -> i != 1 }))
    }

    @Test
    fun federatedUpdate_carriesOnlyClippedNoisyWeightDeltas() {
        val synced = PersonalBrain.DEFAULT_WEIGHTS.copyOf()
        val w = doubleArrayOf(8.0, 0.2, 5.0, 1.0, 1.0, 9.0) // 일부러 크게 흔든 가중치
        val p = FederatedUpdate.build(w, synced, round = 3, events = 12, seed = 42L, sign = { "sig" }, keyId = "k1")
        assertEquals(PersonalBrain.N_FEATURES, p.delta.size)
        val norm = kotlin.math.sqrt(p.delta.sumOf { it * it })
        assertTrue("클리핑(1.0) + 잡음 여유: $norm", norm <= FederatedUpdate.CLIP + 0.5)
        assertEquals(HashChain.sha256Hex(p.body), p.sha256)
        for (secret in listOf("성심당", "parents", "서심다응", "lat", "lng")) assertFalse(p.body.contains(secret))
    }
}
