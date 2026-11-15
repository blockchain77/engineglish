package com.yeopjari.app.ai

import com.yeopjari.app.ai.cloud.ScriptedReasoner
import com.yeopjari.app.ai.context.AppSignals
import com.yeopjari.app.ai.context.ContextEngine
import com.yeopjari.app.ai.context.DriveContext
import com.yeopjari.app.ai.context.EnergyModel
import com.yeopjari.app.ai.context.FatigueModel
import com.yeopjari.app.ai.context.LoadModel
import com.yeopjari.app.ai.context.UserProfile
import com.yeopjari.app.ai.nlu.KeywordIntentEngine
import com.yeopjari.app.ai.personal.PersonaFactory
import com.yeopjari.app.ai.personal.PersonalBrain
import com.yeopjari.app.ai.personal.Situation
import com.yeopjari.app.ai.personal.Tier
import com.yeopjari.app.ai.tools.ToolHost
import com.yeopjari.app.ai.tools.ToolRegistry
import com.yeopjari.app.core.gateway.NaviGateway
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.gateway.VehicleGateway
import com.yeopjari.app.core.model.Doors
import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.core.model.MomentType
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.Routine
import com.yeopjari.app.core.model.VehicleSnapshot
import com.yeopjari.app.sim.SimFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 오케스트레이터·컨텍스트 엔진·툴 문구 테스트 — 앱이 말하고 보여 주는 한국어 문장이
 * 단골 여부(Candidate.habitual)와 받침에 맞는 조사를 쓰는지 JVM에서 확인한다.
 */
class AgentLayerTest {

    private val base = PersonaFactory.BASE_DAY

    private fun sit(day: Int, h: Int, m: Int, lat: Double, lng: Double, dLat: Double? = null, dLng: Double? = null) =
        Situation(day, LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value, h * 60 + m, lat, lng, dLat, dLng)

    /** P_WEEKEND: 토요일 10:50, 잠실 집 앞 */
    private fun weekendHome() = sit(base, 10, 50, 37.5133, 127.1001)

    /** P_WEEKDAY: 목요일 08:31, 판교역로, 목적지 회사 */
    private fun weekdayCommute() = sit(base - 2, 8, 31, 37.4176, 127.1074, 37.4020, 127.1086)

    private fun ctx(v: VehicleSnapshot = VehicleSnapshot(), flags: SimFlags = SimFlags()): DriveContext {
        val r = RouteState()
        return DriveContext(
            vehicle = v, route = r, clockSec = 10 * 3600f, clockLabel = "10:00", flags = flags, profile = UserProfile(),
            energy = EnergyModel.forecast(v, r), fatigue = FatigueModel.assess(v, r, 10), load = LoadModel.estimate(v, r),
        )
    }

    private class FakeNavi : NaviGateway {
        override val source = Source.SIMULATOR
        override val route = MutableStateFlow(RouteState())
        override suspend fun setDestination(place: Place): Result<Unit> = Result.success(Unit)
        override suspend fun addWaypoint(place: Place): Result<Unit> = Result.success(Unit)
        override suspend fun cancelGuidance(): Result<Unit> = Result.success(Unit)
    }

    private class FakeVehicle : VehicleGateway {
        override val source = Source.SIMULATOR
        override val snapshot = MutableStateFlow(VehicleSnapshot())
        override suspend fun setHvacPower(on: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun setHvacTemperature(celsius: Float): Result<Unit> = Result.success(Unit)
        override suspend fun setFanSpeed(level: Int): Result<Unit> = Result.success(Unit)
        override suspend fun setRecirculation(on: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun setChargeLimit(percent: Int): Result<Unit> = Result.success(Unit)
        override fun start() = Unit
        override fun stop() = Unit
    }

    private class FakeHost : ToolHost {
        override val vehicle: VehicleGateway = FakeVehicle()
        override val navi: NaviGateway = FakeNavi()
        override fun startPetMode(tempC: Float) = Unit
        override fun stopPetMode() = Unit
        override fun startRestMode(minutes: Int) = Unit
        override fun startKidsGame() = Unit
        override fun addRoutine(routine: Routine) = Unit
        override fun saveParking(note: String) = Unit
    }

    private fun orchestrator(s: Situation, brain: PersonalBrain = PersonalBrain()) =
        AgentOrchestrator(KeywordIntentEngine(), ScriptedReasoner(), ToolRegistry(FakeHost())) { q -> brain.resolve(q, s) }

    private fun say(o: AgentOrchestrator, text: String, c: DriveContext = ctx()): AgentResult =
        runBlocking { o.handle(text, c, emptyList(), cloudEnabled = false) { _, _ -> } }

    /**
     * 회귀 테스트 (리뷰 R0.18·R1.4): 바로 실행이면 방문 기록이 없어도(이름이 정확해 확률 1.0) "늘 가시던"이라고 말하던 문제.
     * '늘 가던'은 단골(Candidate.habitual)일 때만.
     */
    @Test
    fun orchestrator_saysHabitOnlyForHabitualPlaces() {
        val asan = say(orchestrator(weekendHome()), "서울아산병원 가자")
        assertEquals(Tier.AUTO, asan.resolution?.tier)
        assertFalse(asan.speak, asan.speak.contains("늘 가시던"))
        assertEquals("서울아산병원으로 안내할게요.", asan.speak)

        val starbucks = say(orchestrator(weekdayCommute()), "스타벅스 가자")
        assertEquals(Tier.AUTO, starbucks.resolution?.tier)
        assertTrue(starbucks.resolution!!.top!!.habitual)
        assertEquals("늘 가시던 스타벅스 판교테크노밸리점으로 안내할게요.", starbucks.speak)

        // 기록을 지운 뒤에는 같은 곳도 단골이 아니다
        val erased = say(orchestrator(weekendHome(), PersonalBrain(visits = emptyList())), "올림픽공원 가자")
        assertFalse(erased.speak, erased.speak.contains("늘 가시던"))
    }

    /** 회귀 테스트 (리뷰 R0.17): 받침 있는 말 뒤에 '는'을 붙이던 문제 ('‘에펠탑’는') */
    @Test
    fun orchestrator_notFoundUsesTopicParticleByFinalConsonant() {
        val o = orchestrator(weekendHome())
        val eiffel = say(o, "에펠탑 가자")
        assertEquals(Tier.NONE, eiffel.resolution?.tier)
        assertTrue(eiffel.speak, eiffel.speak.startsWith("‘에펠탑’은 제 기록에서 찾지 못했어요."))
        val mcd = say(o, "맥도날드 가자")
        assertEquals(Tier.NONE, mcd.resolution?.tier)
        assertTrue(mcd.speak, mcd.speak.startsWith("‘맥도날드’는 제 기록에서 찾지 못했어요."))
    }

    /** 회귀 테스트 (리뷰 R0.17): 아이를 부를 때 '하율야' → '하율아' */
    @Test
    fun orchestrator_kidsEtaCallsTheChildByName() {
        val r = say(orchestrator(weekendHome()), "아직 멀었어?", ctx(flags = SimFlags(kidsOnBoard = true)))
        assertTrue(r.speak, r.speak.startsWith("하율아, "))
    }

    /** 회귀 테스트 (리뷰 R0.17): 위급 음성 알림의 '뒷좌석에 하율가' → '하율이가' */
    @Test
    fun contextEngine_rearSeatAlertUsesNameParticle() {
        val v = VehicleSnapshot(gear = Gear.P, doors = Doors(driver = true))
        val rear = ContextEngine.evaluate(ctx(v, SimFlags(rearOpenedAtStart = true)), AppSignals()).first { it.type == MomentType.REAR_SEAT }
        assertEquals("뒷좌석에 하율이가 있지 않나요?", rear.title)
        assertEquals("잠깐만요! 뒷좌석에 하율이가 있는지 확인해 주세요.", rear.speak)
    }

    /** 회귀 테스트 (리뷰 R0.17): 툴 결과 요약의 '집로 안내 시작' */
    @Test
    fun toolRegistry_destinationSummaryUsesRo() {
        val tools = ToolRegistry(FakeHost())
        runBlocking {
            assertEquals("집으로 안내 시작", tools.execute("navi_set_destination", mapOf("place_id" to "home")).getOrThrow())
            assertEquals("성심당 대전역점으로 안내 시작", tools.execute("navi_set_destination", mapOf("place_id" to "ssd_station")).getOrThrow())
            assertEquals("회사 · 판교 테크노밸리로 안내 시작", tools.execute("navi_set_destination", mapOf("place_id" to "work")).getOrThrow())
        }
    }
}
