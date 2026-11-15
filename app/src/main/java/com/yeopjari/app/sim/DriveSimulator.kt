package com.yeopjari.app.sim

import com.yeopjari.app.ai.personal.PersonaFactory
import com.yeopjari.app.core.gateway.LocationGateway
import com.yeopjari.app.core.gateway.NaviGateway
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.gateway.VehicleGateway
import com.yeopjari.app.core.model.Doors
import com.yeopjari.app.core.model.GeoPoint
import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.core.model.Hvac
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.Seat
import com.yeopjari.app.core.model.Telltale
import com.yeopjari.app.core.model.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 시연 시나리오 — PT 심사 및 시연 영상용. Pleos Virtual Console 조작과 병행 가능 */
enum class ScenarioId(val title: String, val subtitle: String, val personal: Boolean = false) {
    P_DAEJEON("토요일 · 대전 본가 가는 길", "‘서심다응 가자’ → 성심당 보정 · HUD 선택지 · 학습", true),
    P_WEEKDAY("평일 아침 · 판교 출근길", "‘스타벅스 가자’ → 늘 가던 판교점 바로 안내", true),
    P_WEEKEND("토요일 오전 · 잠실 집 앞", "같은 ‘스타벅스’도 주말엔 잠실새내역점", true),
    FREE("자유 주행", "집 앞 정차 · 기본 상태"),
    COMMUTE("평일 아침 출근", "D 기어 → 루틴 실행 · 출근 브리핑 · 정체 우회"),
    PARKING("회사 지하주차장 도착", "GPS 끊김 감지 → 주차 위치 음성 메모"),
    ROADTRIP("강릉 가족여행", "도착 SOC 부족 예측 → 충전+휴식 통합 계획"),
    CHARGING("고속 충전 중", "E-pit 충전 세션 · 과태료 타이머 · 쉼 모드"),
    DROWSY("오후 고속도로 졸음", "연속 2시간 주행 → 단계별 졸음 케어"),
    PET("반려견과 잠깐 정차", "한여름 주차 → 펫 케어 모드"),
    TPMS("타이어 경고등 점등", "AI 차량 닥터 원인 추론 → 공기 주입소 경유"),
    ARRIVAL("하원길 도착", "뒷좌석 아이 하차 확인 리마인더"),
}

/** 시뮬레이터 전용 플래그 (실차에서는 사용자 설정·센서로 대체) */
data class SimFlags(
    val petOnBoard: Boolean = false,
    val petName: String = "콩이",
    val kidsOnBoard: Boolean = false,
    val rearOpenedAtStart: Boolean = false,
    val online: Boolean = true,
    /** 지하주차장 진입 (FusedLocation 신호 상실) */
    val underground: Boolean = false,
    /** HUD 시뮬레이터의 도로 움직임 — 시연 영상용. 기본은 꺼짐(주행 중 중앙 화면 움직임 금지 원칙) */
    val hudRoadMotion: Boolean = false,
)

/**
 * 주행 시뮬레이터 — VehicleGateway / NaviGateway / LocationGateway 를 동시에 구현한다.
 * 실제 Pleos 에뮬레이터에서는 Virtual Console 이 차량 신호를 바꾸고, 이 시뮬레이터는
 * 에뮬레이터에 없는 신호(차속·주행 경로·시간 흐름)를 재현해 시나리오 시연을 돕는다.
 */
class DriveSimulator(private val scope: CoroutineScope) {

    private val _vehicle = MutableStateFlow(VehicleSnapshot())
    private val _route = MutableStateFlow(RouteState())
    private val _location = MutableStateFlow<GeoPoint?>(GeoPoint(Places.home.lat, Places.home.lng))
    private val _clock = MutableStateFlow(9 * 60 * 60f) // 하루 중 초
    private val _scenario = MutableStateFlow(ScenarioId.FREE)
    private val _flags = MutableStateFlow(SimFlags())
    private val _timeScale = MutableStateFlow(20f)
    /** 시뮬레이터 날짜 (epochDay) — 개인화 엔진의 요일·최근성 계산에 쓴다 */
    private val _day = MutableStateFlow(PersonaFactory.BASE_DAY)

    val epochDay: StateFlow<Int> = _day.asStateFlow()
    val clockSec: StateFlow<Float> = _clock.asStateFlow()
    val scenario: StateFlow<ScenarioId> = _scenario.asStateFlow()
    val flags: StateFlow<SimFlags> = _flags.asStateFlow()
    val timeScale: StateFlow<Float> = _timeScale.asStateFlow()

    private var targetSpeed = 0f
    private var driveSecAcc = 0f
    private var scriptJob: Job? = null
    private var tickJob: Job? = null
    private var origin: Place = Places.home

    // ───────────────────────── Gateway 구현 ─────────────────────────
    val vehicle = object : VehicleGateway {
        override val source = Source.SIMULATOR
        override val snapshot: StateFlow<VehicleSnapshot> = _vehicle.asStateFlow()
        override suspend fun setHvacPower(on: Boolean) = ok { _vehicle.update { it.copy(hvac = it.hvac.copy(power = on)) } }
        override suspend fun setHvacTemperature(celsius: Float) = ok { _vehicle.update { it.copy(hvac = it.hvac.copy(power = true, setTempC = (Math.round(celsius * 2) / 2f).coerceIn(16f, 30f))) } }
        override suspend fun setFanSpeed(level: Int) = ok { _vehicle.update { it.copy(hvac = it.hvac.copy(fan = level.coerceIn(0, 8))) } }
        override suspend fun setRecirculation(on: Boolean) = ok { _vehicle.update { it.copy(hvac = it.hvac.copy(recirculation = on)) } }
        override suspend fun setChargeLimit(percent: Int) = ok { _vehicle.update { it.copy(chargeLimitPercent = percent.coerceIn(50, 100)) } }
        override fun start() = startTicking()
        override fun stop() { tickJob?.cancel() }
    }

    val navi = object : NaviGateway {
        override val source = Source.SIMULATOR
        override val route: StateFlow<RouteState> = _route.asStateFlow()
        override suspend fun setDestination(place: Place) = ok {
            // 지금 있는 곳에서 출발 — 시나리오 출발지(잠실 집)로 되돌아가 거리를 부풀리지 않게
            val here = _location.value?.let { Place("here", "현재 위치", PlaceKind.DESTINATION, it.lat, it.lng) } ?: origin
            origin = here
            val km = estimateKm(here, place)
            _route.value = RouteState(
                active = true, destination = place, totalKm = km, remainingKm = km,
                remainingMin = (km / 55f * 60f).toInt() + 4, nextGuidance = "경로 안내를 시작합니다",
                roadName = if (km > 60) "고속도로" else "시내 도로", isHighway = km > 60,
            )
        }
        override suspend fun addWaypoint(place: Place) = ok {
            _route.update { r ->
                if (r.waypoints.any { it.id == place.id }) r
                else r.copy(waypoints = (r.waypoints + place).takeLast(2), remainingMin = r.remainingMin + 6, nextGuidance = "경유지 '${place.name}' 추가됨")
            }
        }
        override suspend fun cancelGuidance() = ok { _route.value = RouteState() }
    }

    val location = object : LocationGateway {
        override val source = Source.SIMULATOR
        override val location: StateFlow<GeoPoint?> = _location.asStateFlow()
    }

    private inline fun ok(block: () -> Unit): Result<Unit> = runCatching(block)

    // ───────────────────────── 시뮬레이션 루프 ─────────────────────────
    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(500)
                tick(0.5f * _timeScale.value)
            }
        }
    }

    private fun tick(dt: Float) {
        _clock.update { (it + dt) % (24 * 3600f) }
        val v = _vehicle.value
        var speed = v.speedKph
        if (v.gear == Gear.D || v.gear == Gear.R) {
            speed += (targetSpeed - speed).coerceIn(-6f, 4f)
            if (targetSpeed > 5f) speed += Random.nextFloat() * 2f - 1f
            speed = max(0f, speed)
        } else speed = 0f

        val distKm = speed * dt / 3600f
        val kwhUsed = distKm / v.efficiencyKmPerKwh + (if (v.hvac.power) 1.2f * dt / 3600f else 0f)
        var soc = v.socPercent - kwhUsed / v.batteryKwh * 100f
        var power = 0f
        if (v.isCharging) {
            val taper = when {
                soc < 50f -> 1f
                soc < 70f -> 0.82f
                soc < 80f -> 0.6f
                else -> 0.25f
            }
            power = 235f * taper
            soc += power * dt / 3600f / v.batteryKwh * 100f
            if (soc >= v.chargeLimitPercent) { soc = v.chargeLimitPercent.toFloat(); power = 0f }
        }
        soc = soc.coerceIn(0f, 100f)
        if (speed > 3f) driveSecAcc += dt else if (v.gear == Gear.P) driveSecAcc = max(0f, driveSecAcc - dt * 0.5f)

        val target = if (v.hvac.power) v.hvac.setTempC else v.outsideTempC
        val cabin = v.cabinTempC + (target - v.cabinTempC) * min(1f, dt / 600f)

        _vehicle.value = v.copy(
            speedKph = speed,
            socPercent = soc,
            rangeKm = (soc / 100f * v.batteryKwh * v.efficiencyKmPerKwh).toInt(),
            chargePowerKw = power,
            continuousDriveMin = (driveSecAcc / 60f).toInt(),
            cabinTempC = cabin,
            gpsGood = !_flags.value.underground,
        )

        val r = _route.value
        if (r.active && distKm > 0f) {
            val remain = max(0f, r.remainingKm - distKm)
            val avg = if (r.isHighway) 92f else 38f
            val turn = r.nextTurnMeters?.let { max(0, it - (distKm * 1000).toInt()) }
            _route.value = r.copy(
                remainingKm = remain,
                remainingMin = (remain / avg * 60f).toInt() + r.trafficDelayMin,
                nextTurnMeters = if (turn == 0) null else turn,
            )
            val p = if (r.totalKm > 0) 1f - remain / r.totalKm else 0f
            r.destination?.let { d -> _location.value = GeoPoint(origin.lat + (d.lat - origin.lat) * p, origin.lng + (d.lng - origin.lng) * p) }
        }
    }

    // ───────────────────────── 수동 조작 (Virtual Console 대용 데모 패널) ─────────────────────────
    fun setTimeScale(f: Float) { _timeScale.value = f }
    fun setOnline(online: Boolean) = _flags.update { it.copy(online = online) }
    fun setPetOnBoard(on: Boolean) = _flags.update { it.copy(petOnBoard = on) }
    fun setKidsOnBoard(on: Boolean) = _flags.update { it.copy(kidsOnBoard = on) }
    fun setHudRoadMotion(on: Boolean) = _flags.update { it.copy(hudRoadMotion = on) }

    /**
     * 되돌리기(바로 실행 → "아니야"·"취소")용 — 실행 전 경로를 남은 거리·도로 정보까지 그대로 복원한다.
     * 차는 이미 지금 위치에 있으므로, 이 위치에서 진행률이 이어지도록 가상의 출발점을 다시 잡는다.
     */
    fun restoreRoute(r: RouteState) {
        _route.value = r
        val d = r.destination ?: return
        val here = _location.value ?: return
        val p = r.progress.toDouble()
        origin = if (p >= 0.999) Place("here", "현재 위치", PlaceKind.DESTINATION, here.lat, here.lng)
        else Place("origin", "출발지", PlaceKind.DESTINATION, (here.lat - d.lat * p) / (1 - p), (here.lng - d.lng * p) / (1 - p))
    }

    fun manualGear(g: Gear) {
        _vehicle.update { it.copy(gear = g, speedKph = if (g == Gear.P) 0f else it.speedKph) }
        targetSpeed = if (g == Gear.D) max(targetSpeed, 40f) else 0f
    }
    fun manualTargetSpeed(kph: Float) { targetSpeed = kph }
    fun manualSoc(pct: Float) = _vehicle.update { it.copy(socPercent = pct, rangeKm = (pct / 100f * it.batteryKwh * it.efficiencyKmPerKwh).toInt()) }
    fun manualDriverDoor(open: Boolean) = _vehicle.update { it.copy(doors = it.doors.copy(driver = open)) }
    fun manualDriveMinutes(min: Int) { driveSecAcc = min * 60f; _vehicle.update { it.copy(continuousDriveMin = min) } }
    fun manualTelltale(t: Telltale, on: Boolean) = _vehicle.update { it.copy(telltales = if (on) it.telltales + t else it.telltales - t) }

    fun play(id: ScenarioId) {
        scriptJob?.cancel()
        _scenario.value = id
        startTicking()
        scriptJob = scope.launch { runScript(id) }
    }

    private fun setClock(h: Int, m: Int) { _clock.value = (h * 3600 + m * 60).toFloat() }

    private fun base(
        gear: Gear = Gear.P, speed: Float = 0f, soc: Float = 72f, outside: Float = 14f, cabin: Float = 22f,
        occupants: Set<Seat> = setOf(Seat.DRIVER), hvac: Hvac = Hvac(), telltales: Set<Telltale> = emptySet(),
        charging: Boolean = false, driveMin: Int = 0,
    ) {
        targetSpeed = speed
        driveSecAcc = driveMin * 60f
        _vehicle.value = VehicleSnapshot(
            gear = gear, speedKph = speed, socPercent = soc,
            rangeKm = (soc / 100f * 84f * 5.2f).toInt(), outsideTempC = outside, cabinTempC = cabin,
            occupants = occupants, hvac = hvac, telltales = telltales, isCharging = charging,
            chargePowerKw = if (charging) 228f else 0f, continuousDriveMin = driveMin,
            chargeStartedSec = if (charging) _clock.value - 9 * 60 else null,
        )
    }

    private fun route(
        dest: Place, totalKm: Float, remainKm: Float, min: Int, delay: Int, road: String, highway: Boolean,
        guidance: String, turnM: Int? = null, from: Place = Places.home, naviSoc: Int? = null,
    ) {
        origin = from
        _route.value = RouteState(
            active = true, destination = dest, totalKm = totalKm, remainingKm = remainKm, remainingMin = min,
            trafficDelayMin = delay, roadName = road, isHighway = highway, nextGuidance = guidance,
            nextTurnMeters = turnM, naviEstimatedSoc = naviSoc,
        )
    }

    private suspend fun runScript(id: ScenarioId) {
        _flags.update { SimFlags(online = it.online, petName = it.petName, hudRoadMotion = it.hudRoadMotion) }
        _day.value = PersonaFactory.BASE_DAY
        when (id) {
            ScenarioId.P_DAEJEON -> {
                // 격주 토요일 — 대전 본가 가는 경부고속도로. 성심당은 보통 이 길에 들른다.
                setClock(10, 4); _timeScale.value = 4f
                _flags.update { it.copy(kidsOnBoard = true) }
                base(
                    gear = Gear.D, speed = 96f, soc = 54f, outside = 13f, cabin = 22f, driveMin = 24,
                    occupants = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT),
                )
                val parents = PersonaFactory.poiById("parents")!!.place
                route(parents, 168f, 19f, 17, 0, "경부고속도로", true, "대전IC 방면 오른쪽 차로", turnM = 7400, from = Places.home, naviSoc = 41)
                _route.update { it.copy(speedLimitKph = 110) }
                _location.value = GeoPoint(36.482, 127.347)
            }

            ScenarioId.P_WEEKDAY -> {
                // 목요일 아침 — 판교 출근길
                _day.value = PersonaFactory.BASE_DAY - 2
                setClock(8, 31); _timeScale.value = 4f
                base(gear = Gear.D, speed = 34f, soc = 71f, outside = 6f, cabin = 21f, hvac = Hvac(power = true, setTempC = 21f), driveMin = 22)
                route(Places.work, 18.4f, 2.6f, 7, 0, "판교역로", false, "300m 앞 우회전", turnM = 300, from = Places.home)
                _route.update { it.copy(speedLimitKph = 50) }
                _location.value = GeoPoint(37.4176, 127.1074)
            }

            ScenarioId.P_WEEKEND -> {
                // 토요일 오전 — 잠실 집 앞 정차
                setClock(10, 50); _timeScale.value = 4f
                base(gear = Gear.P, soc = 83f, outside = 12f, cabin = 18f)
                _route.value = RouteState()
                origin = Places.home
                _location.value = GeoPoint(Places.home.lat, Places.home.lng)
            }

            ScenarioId.FREE -> {
                setClock(9, 0); base(); _route.value = RouteState(); _timeScale.value = 20f
            }

            ScenarioId.COMMUTE -> {
                setClock(7, 46); _timeScale.value = 12f
                base(soc = 64f, outside = 4f, cabin = 7f, hvac = Hvac(power = false, setTempC = 21f))
                _route.value = RouteState()
                delay(2500)
                _vehicle.update { it.copy(gear = Gear.D) }
                targetSpeed = 38f
                route(Places.work, 18.4f, 18.4f, 41, 7, "올림픽대로", false, "잠실IC 방면 우측 차로", turnM = 1200)
                delay(18_000)
                _route.update { it.copy(trafficDelayMin = 13, remainingMin = it.remainingMin + 6, nextGuidance = "탄천변 사고로 정체 증가", nextTurnMeters = null) }
                targetSpeed = 18f
            }

            ScenarioId.PARKING -> {
                setClock(8, 39); _timeScale.value = 8f
                base(gear = Gear.D, speed = 14f, soc = 59f, outside = 6f, cabin = 21f, driveMin = 44)
                route(Places.workGarage, 18.4f, 0.2f, 1, 0, "판교 사옥 진입로", false, "지하주차장 입구", turnM = 150, from = Places.home)
                delay(2500)
                _flags.update { it.copy(underground = true) }
                targetSpeed = 6f
                delay(3500)
                targetSpeed = 0f
                delay(1500)
                _vehicle.update { it.copy(gear = Gear.P, speedKph = 0f) }
            }

            ScenarioId.ROADTRIP -> {
                setClock(10, 12); _timeScale.value = 30f
                _flags.update { it.copy(kidsOnBoard = true, rearOpenedAtStart = true) }
                base(
                    gear = Gear.D, speed = 96f, soc = 58f, outside = 11f, cabin = 22f,
                    occupants = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT), driveMin = 38,
                )
                route(Places.gyeongpo, 236f, 204f, 151, 9, "영동고속도로", true, "여주JCT 강릉 방면", turnM = 17_000, naviSoc = 3)
                delay(26_000)
                manualDriveMinutes(92)
            }

            ScenarioId.CHARGING -> {
                setClock(11, 58); _timeScale.value = 40f
                base(gear = Gear.P, soc = 22f, outside = 9f, cabin = 21f, charging = true,
                    occupants = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT))
                _vehicle.update { it.copy(chargeLimitPercent = 80) }
                _flags.update { it.copy(kidsOnBoard = true) }
                _route.value = RouteState(active = true, destination = Places.gyeongpo, totalKm = 236f, remainingKm = 118f, remainingMin = 84, roadName = "횡성휴게소", isHighway = true, nextGuidance = "충전 후 경로 재개")
            }

            ScenarioId.DROWSY -> {
                setClock(14, 38); _timeScale.value = 20f
                base(gear = Gear.D, speed = 103f, soc = 66f, outside = 19f, cabin = 24f, driveMin = 108, hvac = Hvac(setTempC = 24f, recirculation = true))
                val busan = Place("busan", "부산 해운대", PlaceKind.DESTINATION, 35.1587, 129.1604)
                route(busan, 398f, 262f, 171, 0, "경부고속도로", true, "34km 구간 차로 유지", turnM = 34_000)
                delay(14_000)
                manualDriveMinutes(126)
            }

            ScenarioId.PET -> {
                setClock(13, 10); _timeScale.value = 15f
                _flags.update { it.copy(petOnBoard = true) }
                base(gear = Gear.D, speed = 12f, soc = 61f, outside = 31f, cabin = 24f, occupants = setOf(Seat.DRIVER, Seat.REAR_LEFT))
                route(Places.deokpyeong, 61f, 0.4f, 1, 0, "덕평자연휴게소 진입로", false, "목적지 부근입니다")
                delay(3000); targetSpeed = 0f
                delay(2500); _vehicle.update { it.copy(gear = Gear.P, speedKph = 0f) }
                delay(2500); _vehicle.update { it.copy(doors = Doors(driver = true)) }
            }

            ScenarioId.TPMS -> {
                setClock(7, 52); _timeScale.value = 12f
                base(gear = Gear.D, speed = 46f, soc = 47f, outside = -3f, cabin = 18f, driveMin = 6)
                route(Places.work, 18.4f, 15.1f, 34, 3, "송파대로", false, "좌회전 후 탄천로", turnM = 800)
                delay(5000)
                _vehicle.update { it.copy(telltales = it.telltales + Telltale.TPMS) }
            }

            ScenarioId.ARRIVAL -> {
                setClock(16, 42); _timeScale.value = 10f
                _flags.update { it.copy(kidsOnBoard = true, rearOpenedAtStart = true) }
                base(gear = Gear.D, speed = 24f, soc = 55f, outside = 8f, cabin = 22f, occupants = setOf(Seat.DRIVER, Seat.REAR_RIGHT), driveMin = 26)
                route(Places.home, 9.8f, 0.3f, 1, 0, "잠실로", false, "목적지에 도착했습니다", from = Places.work)
                delay(2500); targetSpeed = 0f
                delay(3000); _vehicle.update { it.copy(gear = Gear.P, speedKph = 0f) }
                delay(2500); _vehicle.update { it.copy(doors = Doors(driver = true)) }
            }
        }
    }

    private fun estimateKm(a: Place, b: Place): Float {
        val dLat = abs(a.lat - b.lat) * 111.0
        val dLng = abs(a.lng - b.lng) * 88.0
        return (kotlin.math.sqrt(dLat * dLat + dLng * dLng) * 1.3).toFloat().coerceAtLeast(2f)
    }

    fun clockLabel(sec: Float = _clock.value): String = formatClock(sec)

    companion object {
        fun formatClock(sec: Float): String {
            val total = sec.toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val ampm = if (h < 12) "오전" else "오후"
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "$ampm $h12:${m.toString().padStart(2, '0')}"
        }
    }
}
