package com.cartutor.engineglish.sim

import com.cartutor.engineglish.core.gateway.LocationGateway
import com.cartutor.engineglish.core.gateway.NaviGateway
import com.cartutor.engineglish.core.gateway.Source
import com.cartutor.engineglish.core.gateway.VehicleGateway
import com.cartutor.engineglish.core.model.GeoPoint
import com.cartutor.engineglish.core.model.Gear
import com.cartutor.engineglish.core.model.Place
import com.cartutor.engineglish.core.model.PlaceKind
import com.cartutor.engineglish.core.model.RouteState
import com.cartutor.engineglish.core.model.VehicleSnapshot
import com.cartutor.engineglish.english.core.CameraAhead
import com.cartutor.engineglish.english.core.CautionAhead
import com.cartutor.engineglish.english.core.CautionKind
import com.cartutor.engineglish.english.core.Clock
import com.cartutor.engineglish.english.core.CommuteMap
import com.cartutor.engineglish.english.core.CommutePlace
import com.cartutor.engineglish.english.core.DemoControl
import com.cartutor.engineglish.english.core.DemoScenario
import com.cartutor.engineglish.english.core.DemoSignal
import com.cartutor.engineglish.english.core.DestinationCategory
import com.cartutor.engineglish.english.core.DrivingSignals
import com.cartutor.engineglish.english.core.GearPosition
import com.cartutor.engineglish.english.core.LatLng
import com.cartutor.engineglish.english.core.Maneuver
import com.cartutor.engineglish.english.core.ManeuverType
import com.cartutor.engineglish.english.core.RoadKind
import com.cartutor.engineglish.english.core.SegmentEventKind
import com.cartutor.engineglish.english.core.SignalOrigin
import com.cartutor.engineglish.english.core.SignalSource
import com.cartutor.engineglish.english.core.TurnSignalState
import com.cartutor.engineglish.english.drive.CommuteMaps
import com.cartutor.engineglish.english.drive.DriveIdTracker
import com.cartutor.engineglish.english.drive.MapMatcher
import com.cartutor.engineglish.english.drive.RoutePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 출퇴근 시뮬레이터 (명세 §4.8) — 가상 출퇴근 지도(잠실 ↔ 판교, [CommuteMaps]) 위를 달리며 운전 신호를 만든다.
 *
 * - 배속([timeScale], 기본 6배)은 경로 진행(거리·남은 시간)에만 적용한다. 속도 변화·핸들·방향지시등·
 *   atMs·히스테리시스·훅 타이머는 실시간이다 (그래서 6배속에서도 부하 엔진이 현실적인 시간으로 움직인다).
 * - 안내 지점을 지나면 핸들 30~40°를 3초, 그 150m 앞부터 방향지시등, 회전·구역·정체 앞에서는 미리 감속한다
 *   (시뮬레이터가 스스로 과속·급제동을 만들지 않게).
 * - 고속 구간에서는 크루즈가 저절로 켜지고, 브레이크를 밟으면 꺼진다.
 * - 데모 주입([inject]).
 * - 시나리오가 없으면 집 앞에 P 로 서 있다 (Pleos 차량이 연결되지 않았을 때의 기본 원천).
 * - 예전 화면 호환용 [vehicle] · [navi] · [location] 게이트웨이도 함께 내보낸다.
 */
class CommuteSimulator(
    private val scope: CoroutineScope,
    private val clock: Clock,
    val map: CommuteMap,
) : DemoControl, SignalSource {

    private class SteerProfile(val startMs: Long, val peakDeg: Float, val turn: TurnSignalState, val durationMs: Long = STEER_PROFILE_MS)
    private class ArriveSeq(val startMs: Long, val fromKph: Double, val durationMs: Long)
    private class InjectedManeuver(val type: ManeuverType, var remainingM: Double, val startedMs: Long)

    // ───────────────────────── 공개 상태 ─────────────────────────

    private val _scenario = MutableStateFlow<DemoScenario?>(null)
    override val scenario: StateFlow<DemoScenario?> = _scenario.asStateFlow()

    private val _timeScale = MutableStateFlow(DEFAULT_TIME_SCALE)
    /** NEAR_ARRIVAL 이 낮추기 전 배속 — 다음 시나리오에서 되돌린다 */
    private var scaleBeforeArrival: Float? = null
    override val timeScale: StateFlow<Float> = _timeScale.asStateFlow()

    private val _runId = MutableStateFlow(0L)
    /** play() 마다 1 증가 — 새 데모 주행 시작 신호 (훅 초기화·내비 미러링) */
    val runId: StateFlow<Long> = _runId.asStateFlow()

    private val _signals = MutableStateFlow(DrivingSignals(atMs = clock.nowMs()))
    override val signals: StateFlow<DrivingSignals> = _signals.asStateFlow()

    // 예전 게이트웨이 호환 (레거시 화면·NaviHub)
    private val _vehicle = MutableStateFlow(VehicleSnapshot())
    private val _route = MutableStateFlow(RouteState())
    private val _geo = MutableStateFlow<GeoPoint?>(null)

    val vehicle: VehicleGateway = object : VehicleGateway {
        override val source = Source.SIMULATOR
        override val snapshot: StateFlow<VehicleSnapshot> = _vehicle.asStateFlow()
        override suspend fun setHvacPower(on: Boolean): Result<Unit> =
            runCatching { _vehicle.update { it.copy(hvac = it.hvac.copy(power = on)) } }
        override suspend fun setHvacTemperature(celsius: Float): Result<Unit> =
            runCatching { _vehicle.update { it.copy(hvac = it.hvac.copy(power = true, setTempC = celsius.coerceIn(16f, 30f))) } }
        override suspend fun setFanSpeed(level: Int): Result<Unit> =
            runCatching { _vehicle.update { it.copy(hvac = it.hvac.copy(fan = level.coerceIn(0, 8))) } }
        override suspend fun setRecirculation(on: Boolean): Result<Unit> =
            runCatching { _vehicle.update { it.copy(hvac = it.hvac.copy(recirculation = on)) } }
        override suspend fun setChargeLimit(percent: Int): Result<Unit> =
            runCatching { _vehicle.update { it.copy(chargeLimitPercent = percent.coerceIn(50, 100)) } }
        override fun start() {}
        override fun stop() {}
    }

    val navi: NaviGateway = object : NaviGateway {
        override val source = Source.SIMULATOR
        override val route: StateFlow<RouteState> = _route.asStateFlow()
        override suspend fun setDestination(place: Place): Result<Unit> = runCatching {
            val target = map.place(place.id) ?: MapMatcher.nearestPlace(map, LatLng(place.lat, place.lng))
                ?: error("지도 밖 목적지")
            val from = placeId?.takeIf { gear == GearPosition.P } ?: error("정차 중에만 경로를 바꿀 수 있어요")
            val r = map.routeBetween(from, target.id) ?: error("경로 없음")
            val now = clock.nowMs()
            startRoute(r.id, now)
            publish(now)
        }
        override suspend fun addWaypoint(place: Place): Result<Unit> =
            Result.failure(UnsupportedOperationException("경유지는 지원하지 않아요"))
        override suspend fun cancelGuidance(): Result<Unit> = runCatching {
            plan = null
            destinationOverride = null
            publish(clock.nowMs())
        }
    }

    val location: LocationGateway = object : LocationGateway {
        override val source = Source.SIMULATOR
        override val location: StateFlow<GeoPoint?> = _geo.asStateFlow()
    }

    // ───────────────────────── 차 상태 ─────────────────────────

    private val plans: Map<String, RoutePlan> = RoutePlan.all(map)
    private val driveIds = DriveIdTracker()
    private var plan: RoutePlan? = null
    private var posM = 0.0
    private var placeId: String? = null
    private var originPlaceId: String? = null
    private var destinationOverride: CommutePlace? = null

    private var gear = GearPosition.P
    private var speed = 0.0
    private var accel = 0f
    private var braking = false
    private var soc = DEFAULT_SOC
    private var charging = false
    private var wipers = false
    private var headlights = false
    private var cruiseOverride = false
    private var cruiseAuto = false
    private var highwaySinceMs: Long? = null
    private var cruiseBlockedUntilMs = 0L

    private var departAtMs: Long? = null
    private var arrive: ArriveSeq? = null
    private var arrivedUntilMs = 0L
    private var steer: SteerProfile? = null
    private var lastTickMs = clock.nowMs()
    private var lastSteerOut = 0f
    private var lastSteerAtMs = 0L
    private var steerRateOut = 0f

    // 데모 주입
    private var injectedTurn = TurnSignalState.OFF
    private var injectedTurnUntilMs = 0L
    private var injectedManeuver: InjectedManeuver? = null
    private var hardBrakeStartMs: Long? = null
    private var jamUntilMs = 0L
    private var postJamUntilMs = 0L
    private var navPromptUntilMs = 0L
    private var tunnelUntilMs = 0L
    private var schoolZoneUntilMs = 0L

    private var tickJob: Job? = null

    /** 지금 경로 (없으면 null) */
    val currentPlan: RoutePlan? get() = plan

    /** 경로 위 진행 거리(m) */
    val progressM: Double get() = posM

    // ───────────────────────── DemoControl ─────────────────────────

    override fun play(scenario: DemoScenario) {
        val now = clock.nowMs()
        scaleBeforeArrival?.let { _timeScale.value = it }
        scaleBeforeArrival = null
        resetCar(now)
        when (scenario) {
            DemoScenario.MORNING -> startRoute(CommuteMaps.ROUTE_MORNING, now)
            DemoScenario.EVENING -> startRoute(CommuteMaps.ROUTE_EVENING, now)
            DemoScenario.CHARGING -> {
                parkAt(CommuteMaps.PLACE_CHARGER)
                charging = true
                soc = CHARGING_START_SOC
            }
            DemoScenario.PARKED -> parkAt(CommuteMaps.PLACE_HOME)
        }
        driveIds.forceNew(now, parked = true)
        _scenario.value = scenario
        publish(now)
        _runId.value = _runId.value + 1
    }

    override fun inject(signal: DemoSignal) {
        val now = clock.nowMs()
        when (signal) {
            DemoSignal.TURN_ON -> {
                val next = injectedManeuver?.type ?: plan?.nextManeuver(posM)?.type
                injectedTurn = if (next != null && next in RIGHTISH) TurnSignalState.RIGHT else TurnSignalState.LEFT
                injectedTurnUntilMs = now + TURN_ON_MS
            }
            DemoSignal.TURN_OFF -> {
                injectedTurn = TurnSignalState.OFF
                injectedTurnUntilMs = 0L
            }
            DemoSignal.INTERSECTION -> injectedManeuver = InjectedManeuver(ManeuverType.RIGHT, INTERSECTION_START_M, now)
            DemoSignal.HARD_BRAKE -> if (gear == GearPosition.D) {
                hardBrakeStartMs = now
                cruiseOverride = false
                cruiseAuto = false
                cruiseBlockedUntilMs = now + CRUISE_BLOCK_AFTER_BRAKE_MS
            }
            DemoSignal.RAIN_ON -> wipers = true
            DemoSignal.RAIN_OFF -> wipers = false
            DemoSignal.JAM -> {
                jamUntilMs = now + JAM_INJECT_MS
                postJamUntilMs = jamUntilMs + POST_JAM_MS
                cruiseOverride = false
                cruiseAuto = false
                cruiseBlockedUntilMs = postJamUntilMs
            }
            DemoSignal.CRUISE_ON -> {
                cruiseOverride = true
                cruiseBlockedUntilMs = 0L
            }
            DemoSignal.CRUISE_OFF -> {
                cruiseOverride = false
                cruiseAuto = false
                cruiseBlockedUntilMs = now + CRUISE_BLOCK_AFTER_BRAKE_MS
            }
            DemoSignal.NAV_PROMPT -> navPromptUntilMs = now + NAV_PROMPT_MS
            DemoSignal.ARRIVE -> beginArrive(now)
            DemoSignal.NEAR_ARRIVAL -> plan?.let { p ->
                val target = p.posForRemainingSec(NEAR_ARRIVAL_SEC)
                if (target > posM) {
                    posM = target
                    speed = minOf(speed, p.legAt(posM).segment.typicalSpeedKph.toDouble())
                    injectedManeuver = null
                }
                // 도착 1분 전 마무리 인사와 주차 대시보드를 볼 시간이 있게 배속을 낮춘다 (6배속이면 남은 1분이 10초 만에 끝난다)
                if (_timeScale.value > NEAR_ARRIVAL_MAX_SCALE) {
                    scaleBeforeArrival = _timeScale.value
                    _timeScale.value = NEAR_ARRIVAL_MAX_SCALE
                }
            }
            DemoSignal.TUNNEL -> tunnelUntilMs = now + TUNNEL_INJECT_MS
            DemoSignal.SCHOOL_ZONE -> {
                schoolZoneUntilMs = now + SCHOOL_INJECT_MS
                // 구역에 '들어온' 상태로 — 제한 30km 에 갑자기 과속(CRITICAL)이 되지 않게
                speed = minOf(speed, SCHOOL_ZONE_KPH + 4.0)
            }
            DemoSignal.NIGHT_ON -> headlights = true
            DemoSignal.NIGHT_OFF -> headlights = false
            DemoSignal.AIRPORT_DESTINATION -> destinationOverride = map.place(CommuteMaps.PLACE_AIRPORT)
        }
        publish(now)
    }

    override fun setTimeScale(scale: Float) {
        if (scale.isNaN()) return
        scaleBeforeArrival = null
        _timeScale.value = scale.coerceIn(MIN_TIME_SCALE, MAX_TIME_SCALE)
    }

    override fun stop() {
        val now = clock.nowMs()
        resetCar(now)
        parkAt(CommuteMaps.PLACE_HOME)
        _scenario.value = null
        publish(now)
    }

    /** 데모로 주입한 내비 음성 안내가 나오는 중인가 (실차 원천일 때도 허브가 OR 로 합친다) */
    fun injectedNavPrompt(nowMs: Long): Boolean = nowMs < navPromptUntilMs

    /** 코루틴 정리 */
    fun release() {
        tickJob?.cancel()
        tickJob = null
    }

    // ───────────────────────── 시나리오 준비 ─────────────────────────

    private fun resetCar(now: Long) {
        plan = null
        posM = 0.0
        originPlaceId = null
        destinationOverride = null
        gear = GearPosition.P
        speed = 0.0
        accel = 0f
        braking = false
        soc = DEFAULT_SOC
        charging = false
        wipers = false
        headlights = false
        cruiseOverride = false
        cruiseAuto = false
        highwaySinceMs = null
        cruiseBlockedUntilMs = 0L
        departAtMs = null
        arrive = null
        arrivedUntilMs = 0L
        steer = null
        lastTickMs = now
        injectedTurn = TurnSignalState.OFF
        injectedTurnUntilMs = 0L
        injectedManeuver = null
        hardBrakeStartMs = null
        jamUntilMs = 0L
        postJamUntilMs = 0L
        navPromptUntilMs = 0L
        tunnelUntilMs = 0L
        schoolZoneUntilMs = 0L
    }

    private fun startRoute(routeId: String, now: Long): Boolean {
        val p = plans[routeId] ?: return false
        plan = p
        posM = 0.0
        placeId = p.route.fromPlaceId
        originPlaceId = p.route.fromPlaceId
        destinationOverride = null
        gear = GearPosition.P
        speed = 0.0
        arrive = null
        departAtMs = now + DEPART_DELAY_MS
        return true
    }

    private fun parkAt(id: String) {
        plan = null
        posM = 0.0
        placeId = id
        originPlaceId = null
        gear = GearPosition.P
        speed = 0.0
        departAtMs = null
    }

    // ───────────────────────── 주행 루프 ─────────────────────────

    private fun tick() {
        val now = clock.nowMs()
        advance(now)
        driveIds.update(gear, now)
        publish(now)
    }

    private fun advance(now: Long) {
        val dtMs = (now - lastTickMs).coerceIn(0L, MAX_STEP_MS)
        lastTickMs = now
        if (dtMs <= 0L) return
        val dt = dtMs / 1000.0
        val scale = _timeScale.value.toDouble()

        if (charging) {
            soc = (soc + CHARGE_PCT_PER_SIM_SEC * dt * scale).coerceAtMost(CHARGE_LIMIT_SOC)
            if (soc >= CHARGE_LIMIT_SOC) charging = false
        }
        departAtMs?.let { at ->
            if (now >= at) {
                gear = GearPosition.D
                departAtMs = null
            }
        }

        // 도착 감속
        val arr = arrive
        if (arr != null) {
            val t = (now - arr.startMs).toDouble()
            val newSpeed = if (t >= arr.durationMs) 0.0 else arr.fromKph * (1.0 - t / arr.durationMs)
            accel = ((newSpeed - speed) / 3.6 / dt).toFloat().coerceAtLeast(-ARRIVE_DECEL_MPS2.toFloat())
            speed = newSpeed
            braking = true
            move(now, dt)
            if (t >= arr.durationMs) finishArrive(now)
            return
        }

        if (gear != GearPosition.D) {
            speed = 0.0
            accel = 0f
            braking = false
            injectedManeuver?.let { if (now - it.startedMs > INJECTED_MANEUVER_TTL_MS) injectedManeuver = null }
            updateCruise(now)
            return
        }

        // 급제동 (데모)
        val hb = hardBrakeStartMs
        if (hb != null && now - hb <= HARD_BRAKE_MS) {
            speed = (speed - HARD_BRAKE_DROP_KPH * dtMs / HARD_BRAKE_MS).coerceAtLeast(0.0)
            accel = HARD_BRAKE_ACCEL
            braking = true
            move(now, dt)
            updateCruise(now)
            return
        }
        if (hb != null) hardBrakeStartMs = null

        val target = targetSpeed(now)
        val diff = target - speed
        // 작은 속도 차이는 부드럽게 (|가속도| ≤ 0.7 m/s² — 고속 직진 판단을 깨지 않게), 큰 차이는 힘껏
        val up = if (diff < GENTLE_BAND_KPH) GENTLE_ACCEL_MPS2 else ACCEL_UP_MPS2
        val down = if (diff > -GENTLE_BAND_KPH) GENTLE_ACCEL_MPS2 else ACCEL_DOWN_MPS2
        val step = diff.coerceIn(-down * 3.6 * dt, up * 3.6 * dt)
        speed = (speed + step).coerceAtLeast(0.0)
        accel = (step / 3.6 / dt).toFloat()
        braking = accel < BRAKE_DECEL_MPS2
        if (braking) cruiseOverride = false
        move(now, dt)
        updateCruise(now)
    }

    /** 경로 진행 (배속 적용) + 지나친 안내 지점의 핸들 조작 + 도착 */
    private fun move(now: Long, dt: Double) {
        val scale = _timeScale.value.toDouble()
        // 주입한 교차로는 D 단에서 최소 시속 30km 로 다가간다 (정차 중엔 advance 가 TTL 로 지운다)
        injectedManeuver?.let { inj ->
            inj.remainingM -= maxOf(speed, INTERSECTION_MIN_KPH) / 3.6 * dt * scale
            if (inj.remainingM <= 0.0) {
                startSteer(inj.type, now)
                injectedManeuver = null
            }
        }
        val p = plan ?: return
        val ds = speed / 3.6 * dt * scale
        if (ds <= 0.0) return
        val before = posM
        posM = (posM + ds).coerceAtMost(p.totalM.toDouble())
        for (m in p.maneuvers) {
            if (m.type != ManeuverType.ARRIVE && m.atM > before && m.atM <= posM) startSteer(m.type, now)
        }
        soc = (soc - (posM - before) / 1000.0 * SOC_PER_KM).coerceAtLeast(MIN_SOC)
        if (posM >= p.totalM && arrive == null) beginArrive(now)
    }

    private fun updateCruise(now: Long) {
        val onHighway = plan?.legAt(posM)?.segment?.road == RoadKind.HIGHWAY
        val ok = gear == GearPosition.D && onHighway && speed >= CRUISE_AUTO_MIN_KPH && !braking &&
            now >= cruiseBlockedUntilMs && now >= jamUntilMs && arrive == null
        if (ok) {
            val since = highwaySinceMs ?: now.also { highwaySinceMs = it }
            if (now - since >= CRUISE_AUTO_DELAY_MS) cruiseAuto = true
        } else {
            highwaySinceMs = null
            cruiseAuto = false
        }
    }

    private fun targetSpeed(now: Long): Double {
        val p = plan
        val limit = speedLimitNow(now)
        var target = p?.legAt(posM)?.segment?.typicalSpeedKph?.toDouble() ?: FREE_DRIVE_KPH
        // 크루즈(데모): 제한 속도까지, 최대 90 (과속을 만들지 않는다)
        if (cruiseOverride) target = (limit?.toDouble() ?: CRUISE_KPH).coerceIn(CRUISE_MIN_KPH, CRUISE_KPH)
        val inJam = (p?.zoneAt(posM, SegmentEventKind.JAM) != null) || now < jamUntilMs
        if (inJam) {
            target = jamWave(now)
        } else if (now < postJamUntilMs) {
            // 정체가 풀리면 시속 30km 이상으로 잠시 (빠져나옴 훅이 느린 길에서도 생기게)
            target = maxOf(target, POST_JAM_KPH)
        }
        val inSchool = (p?.zoneAt(posM, SegmentEventKind.SCHOOL_ZONE) != null) || now < schoolZoneUntilMs
        if (inSchool) target = minOf(target, SCHOOL_ZONE_KPH)
        if (p != null) target = minOf(target, lookAhead(p))
        // 사람이 모는 듯한 작은 속도 변화 — 크루즈는 속도를 정확히 유지
        val holding = cruiseOverride || cruiseAuto
        if (target >= 20.0 && !inJam && !holding) target += SPEED_WOBBLE_KPH * sin(now / 3_000.0)
        return target.coerceAtLeast(0.0)
    }

    /** 앞의 회전·구역·느린 구간에 맞춰 미리 줄일 속도 */
    private fun lookAhead(p: RoutePlan): Double {
        var cap = Double.MAX_VALUE
        fun consider(distM: Double, vTarget: Double) {
            if (distM < 0.0) return
            if (distM <= brakingDistance(speed, vTarget)) cap = minOf(cap, vTarget)
        }
        val idx = p.legIndexAt(posM)
        val leg = p.legs[idx]
        p.legs.getOrNull(idx + 1)?.let { next ->
            val vNext = next.segment.typicalSpeedKph.toDouble()
            if (vNext < leg.segment.typicalSpeedKph) consider(leg.endM - posM, vNext)
        }
        p.nextZone(posM, SegmentEventKind.SCHOOL_ZONE)?.let { consider(it.startM - posM, SCHOOL_ZONE_KPH) }
        p.nextZone(posM, SegmentEventKind.JAM)?.let { consider(it.startM - posM, JAM_ENTRY_KPH) }
        p.nextManeuver(posM)?.let { m -> maneuverSpeed(m.type)?.let { consider(m.atM - posM, it) } }
        return cap
    }

    /** 배속을 고려한 제동 거리(m, 경로 위) + 여유 */
    private fun brakingDistance(vKph: Double, targetKph: Double): Double {
        val scale = _timeScale.value.toDouble()
        val v = vKph / 3.6
        val vt = targetKph / 3.6
        val physical = if (v > vt) (v * v - vt * vt) / (2 * PLAN_DECEL_MPS2) else 0.0
        return physical * scale + v * scale * LOOKAHEAD_MARGIN_S + LOOKAHEAD_MARGIN_M
    }

    private fun speedLimitNow(now: Long): Int? {
        val p = plan ?: return if (now < schoolZoneUntilMs) SCHOOL_ZONE_LIMIT else null
        var limit = p.speedLimitAt(posM)
        if (now < schoolZoneUntilMs) limit = minOf(limit, SCHOOL_ZONE_LIMIT)
        return limit
    }

    private fun jamWave(now: Long): Double {
        val phase = (now % JAM_WAVE_PERIOD_MS).toDouble() / JAM_WAVE_PERIOD_MS
        return JAM_WAVE_MAX_KPH * (0.5 - 0.5 * cos(2 * PI * phase))
    }

    private fun startSteer(type: ManeuverType, now: Long) {
        val peak = when (type) {
            ManeuverType.LEFT -> -35f
            ManeuverType.RIGHT -> 35f
            ManeuverType.U_TURN -> -40f
            ManeuverType.ROUNDABOUT -> 30f
            ManeuverType.EXIT -> 18f
            ManeuverType.ENTER, ManeuverType.MERGE -> -12f
            ManeuverType.KEEP_LEFT -> -10f
            ManeuverType.KEEP_RIGHT -> 10f
            else -> return
        }
        steer = SteerProfile(now, peak, signalFor(type))
    }

    private fun profileDeg(p: SteerProfile, now: Long): Float {
        val t = (now - p.startMs).toFloat()
        if (t < 0f || t >= p.durationMs) return 0f
        val f = when {
            t < STEER_RAMP_MS -> t / STEER_RAMP_MS
            t > p.durationMs - STEER_RAMP_MS -> (p.durationMs - t) / STEER_RAMP_MS
            else -> 1f
        }
        return p.peakDeg * f
    }

    private fun beginArrive(now: Long) {
        if (arrive != null || gear != GearPosition.D) return
        val duration = maxOf(ARRIVE_MIN_MS, (speed / 3.6 / ARRIVE_DECEL_MPS2 * 1000.0).toLong())
        arrive = ArriveSeq(now, speed, duration)
        cruiseOverride = false
        cruiseAuto = false
        injectedManeuver = null
        hardBrakeStartMs = null
    }

    private fun finishArrive(now: Long) {
        val p = plan
        arrive = null
        speed = 0.0
        accel = 0f
        braking = false
        gear = GearPosition.P
        arrivedUntilMs = now + ARRIVED_MS
        placeId = destinationOverride?.id ?: p?.route?.toPlaceId ?: placeId
        plan = null
        posM = 0.0
        destinationOverride = null
        cruiseOverride = false
        cruiseAuto = false
        injectedTurnUntilMs = 0L
        injectedManeuver = null
        steer = null
    }

    // ───────────────────────── 신호 내보내기 ─────────────────────────

    private fun publish(now: Long) {
        val p = plan
        val leg = p?.legAt(posM)
        val parked = gear == GearPosition.P

        val routeManeuver = p?.nextManeuver(posM)?.let {
            Maneuver(it.type, (it.atM - posM).roundToInt().coerceAtLeast(0), it.descriptionKo)
        }
        val injected = injectedManeuver?.let { Maneuver(it.type, it.remainingM.roundToInt().coerceAtLeast(0), INTERSECTION_TEXT) }
        val maneuver = when {
            routeManeuver == null -> injected
            injected == null -> routeManeuver
            injected.distanceM < routeManeuver.distanceM -> injected
            else -> routeManeuver
        }
        val camera = p?.nextCamera(posM)?.let { c ->
            val d = c.atM - posM
            if (d <= CAMERA_VISIBLE_M) CameraAhead(c.kind, d.roundToInt(), c.limitKph) else null
        }
        val inSchool = (p?.zoneAt(posM, SegmentEventKind.SCHOOL_ZONE) != null) || now < schoolZoneUntilMs
        val caution = if (inSchool) {
            CautionAhead(CautionKind.SCHOOL_ZONE, 0, inside = true)
        } else {
            p?.nextZone(posM, SegmentEventKind.SCHOOL_ZONE)?.let { z ->
                val d = z.startM - posM
                if (d <= CAUTION_VISIBLE_M) CautionAhead(CautionKind.SCHOOL_ZONE, d.roundToInt(), inside = false) else null
            }
        }
        val inTunnel = (p?.zoneAt(posM, SegmentEventKind.TUNNEL) != null) || now < tunnelUntilMs
        val jam = (p?.zoneAt(posM, SegmentEventKind.JAM) != null && gear == GearPosition.D) || now < jamUntilMs
        val cruiseOn = (cruiseOverride || cruiseAuto) && gear == GearPosition.D && speed >= CRUISE_MIN_KPH && !braking

        // 핸들 — 회전 프로파일 > 차로 유지 미세 움직임
        val profile = steer?.takeIf { now - it.startMs < it.durationMs }
        if (profile == null) steer = null
        val steering = when {
            profile != null -> profileDeg(profile, now)
            gear == GearPosition.D && speed > 5.0 && !cruiseOn -> (LANE_WOBBLE_DEG * sin(now / 2_300.0)).toFloat()
            else -> 0f
        }
        if (now - lastSteerAtMs >= STEER_RATE_MIN_DT_MS) {
            steerRateOut = if (lastSteerAtMs == 0L) 0f else abs(steering - lastSteerOut) / ((now - lastSteerAtMs) / 1000f)
            lastSteerOut = steering
            lastSteerAtMs = now
        }

        // 방향지시등 — 주입 > 회전 중 > 안내 지점 150m 앞
        val turn = when {
            now < injectedTurnUntilMs && injectedTurn != TurnSignalState.OFF -> injectedTurn
            profile != null && profile.turn != TurnSignalState.OFF -> profile.turn
            maneuver != null && gear == GearPosition.D && maneuver.distanceM <= TURN_SIGNAL_LEAD_M -> signalFor(maneuver.type)
            else -> TurnSignalState.OFF
        }

        val dest = destinationOverride ?: p?.destination
        val point = if (p != null) p.locationAt(posM) else map.place(placeId)?.point
        val s = DrivingSignals(
            atMs = now,
            origin = SignalOrigin.SIMULATOR,
            gear = gear,
            speedKph = speed.toFloat(),
            speedLimitKph = speedLimitNow(now),
            accelMps2 = if (gear == GearPosition.D) accel else 0f,
            brakePedal = !parked && (braking || speed < 0.5),
            turnSignal = turn,
            steeringDeg = steering,
            steeringRateDps = steerRateOut,
            cruiseActive = cruiseOn,
            wipersOn = wipers,
            headlightsOn = headlights,
            uxRestricted = !parked,
            maneuver = maneuver,
            camera = camera,
            caution = caution,
            navVoicePrompt = now < navPromptUntilMs,
            audioFocusLost = false,
            charging = charging,
            socPercent = soc.roundToInt().coerceIn(0, 100),
            routeActive = p != null,
            routeRemainingSec = p?.remainingSec(posM),
            routeRemainingM = p?.let { (it.totalM - posM).roundToInt().coerceAtLeast(0) },
            destinationName = dest?.nameKo,
            destinationCategory = dest?.category,
            destinationPlaceId = dest?.id,
            originPlaceId = originPlaceId,
            location = point?.let { MapMatcher.coarse(it) },
            segmentId = leg?.segment?.id,
            segmentName = leg?.segment?.nameKo,
            roadKind = leg?.segment?.road,
            inTunnel = inTunnel,
            trafficJam = jam,
            arrived = now < arrivedUntilMs,
            driveId = driveIds.driveId,
        )
        _signals.value = s
        updateLegacyViews(s, p)
    }

    private fun updateLegacyViews(s: DrivingSignals, p: RoutePlan?) {
        _vehicle.update {
            it.copy(
                gear = Gear.valueOf(s.gear.name),
                speedKph = s.speedKph,
                socPercent = soc.toFloat(),
                rangeKm = (soc / 100.0 * it.batteryKwh * it.efficiencyKmPerKwh).roundToInt(),
                isCharging = charging,
                chargePowerKw = if (charging) CHARGE_POWER_KW else 0f,
                turnSignal = s.turnSignal,
                steeringDeg = s.steeringDeg,
                brakePedal = s.brakePedal,
                cruiseActive = s.cruiseActive,
                wipersOn = s.wipersOn,
                headlightsOn = s.headlightsOn,
            )
        }
        _route.value = if (p == null) {
            RouteState(speedLimitKph = s.speedLimitKph)
        } else {
            val dest = destinationOverride ?: p.destination
            RouteState(
                active = true,
                destination = dest?.let { toLegacyPlace(it) },
                totalKm = p.totalM / 1000f,
                remainingKm = (s.routeRemainingM ?: 0) / 1000f,
                remainingMin = (s.routeRemainingSec ?: 0) / 60,
                nextGuidance = s.maneuver?.descriptionKo.orEmpty(),
                nextTurnMeters = s.maneuver?.distanceM,
                roadName = s.segmentName.orEmpty(),
                isHighway = s.roadKind == RoadKind.HIGHWAY,
                speedLimitKph = s.speedLimitKph,
                maneuver = s.maneuver,
                camera = s.camera,
                caution = s.caution,
                remainingSec = s.routeRemainingSec,
                remainingM = s.routeRemainingM,
            )
        }
        _geo.value = s.location?.let { GeoPoint(it.lat, it.lng) }
    }

    companion object {
        const val DEFAULT_TIME_SCALE = 6f
        const val MIN_TIME_SCALE = 1f
        const val MAX_TIME_SCALE = 20f
        const val TICK_MS = 250L
        const val MAX_STEP_MS = 1_000L
        const val DEPART_DELAY_MS = 2_000L

        const val ACCEL_UP_MPS2 = 1.4
        const val ACCEL_DOWN_MPS2 = 2.5
        const val GENTLE_ACCEL_MPS2 = 0.7
        const val GENTLE_BAND_KPH = 8.0
        const val PLAN_DECEL_MPS2 = 2.0
        const val BRAKE_DECEL_MPS2 = -1.0f
        const val LOOKAHEAD_MARGIN_S = 0.5
        const val LOOKAHEAD_MARGIN_M = 15.0
        const val FREE_DRIVE_KPH = 35.0
        const val SPEED_WOBBLE_KPH = 1.2
        const val LANE_WOBBLE_DEG = 1.5

        const val CRUISE_KPH = 90.0
        const val CRUISE_MIN_KPH = 30.0
        const val CRUISE_AUTO_MIN_KPH = 60.0
        const val CRUISE_AUTO_DELAY_MS = 2_000L
        const val CRUISE_BLOCK_AFTER_BRAKE_MS = 8_000L

        const val SCHOOL_ZONE_KPH = 28.0
        const val SCHOOL_ZONE_LIMIT = 30
        const val JAM_ENTRY_KPH = 15.0
        const val JAM_WAVE_MAX_KPH = 12.0
        const val JAM_WAVE_PERIOD_MS = 8_000L
        const val POST_JAM_KPH = 35.0

        const val HARD_BRAKE_MS = 1_500L
        const val HARD_BRAKE_DROP_KPH = 25.0
        const val HARD_BRAKE_ACCEL = -5.5f

        const val ARRIVE_MIN_MS = 3_000L
        const val ARRIVE_DECEL_MPS2 = 3.0
        const val ARRIVED_MS = 5_000L

        const val STEER_PROFILE_MS = 3_000L
        const val STEER_RAMP_MS = 600f
        const val STEER_RATE_MIN_DT_MS = 100L
        const val TURN_SIGNAL_LEAD_M = 150

        const val TURN_ON_MS = 10_000L
        const val INTERSECTION_START_M = 280.0
        const val INTERSECTION_MIN_KPH = 30.0
        const val INTERSECTION_TEXT = "교차로에서 우회전"
        const val INJECTED_MANEUVER_TTL_MS = 30_000L
        const val JAM_INJECT_MS = 25_000L
        const val POST_JAM_MS = 15_000L
        const val NAV_PROMPT_MS = 3_000L
        const val TUNNEL_INJECT_MS = 15_000L
        const val SCHOOL_INJECT_MS = 12_000L
        /** '도착 1분 전으로' — 마무리(남은 60초) 직전으로 건너뛴다 */
        const val NEAR_ARRIVAL_SEC = 75
        const val NEAR_ARRIVAL_MAX_SCALE = 2f

        const val CAMERA_VISIBLE_M = 600
        const val CAUTION_VISIBLE_M = 300

        const val DEFAULT_SOC = 72.0
        const val CHARGING_START_SOC = 38.0
        const val CHARGE_LIMIT_SOC = 80.0
        const val CHARGE_PCT_PER_SIM_SEC = 0.02
        const val CHARGE_POWER_KW = 100f
        const val SOC_PER_KM = 0.23
        const val MIN_SOC = 5.0

        private val RIGHTISH = setOf(ManeuverType.RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.EXIT, ManeuverType.ROUNDABOUT)

        /** 안내 지점 종류 → 방향지시등 (방향이 없는 지점은 OFF) */
        fun signalFor(type: ManeuverType): TurnSignalState = when (type) {
            ManeuverType.LEFT, ManeuverType.KEEP_LEFT, ManeuverType.U_TURN, ManeuverType.ENTER, ManeuverType.MERGE -> TurnSignalState.LEFT
            ManeuverType.RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.EXIT, ManeuverType.ROUNDABOUT -> TurnSignalState.RIGHT
            else -> TurnSignalState.OFF
        }

        /** 안내 지점 앞 목표 속도 (없으면 null = 제한 없음) */
        fun maneuverSpeed(type: ManeuverType): Double? = when (type) {
            ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.U_TURN, ManeuverType.ROUNDABOUT -> 25.0
            ManeuverType.EXIT, ManeuverType.ENTER, ManeuverType.MERGE, ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT -> 45.0
            ManeuverType.TOLL -> 30.0
            ManeuverType.REST_AREA -> 40.0
            ManeuverType.ARRIVE -> 15.0
            else -> null
        }

        /** 출퇴근 지도 장소 → 예전 Place (실차 내비 미러링·레거시 화면용, 좌표는 지도 그대로 3자리) */
        fun toLegacyPlace(p: CommutePlace): Place = Place(
            id = p.id,
            name = p.nameKo,
            kind = when (p.category) {
                DestinationCategory.HOME -> PlaceKind.HOME
                DestinationCategory.OFFICE -> PlaceKind.WORK
                DestinationCategory.CHARGER -> PlaceKind.CHARGER
                DestinationCategory.AIRPORT, DestinationCategory.OTHER -> PlaceKind.DESTINATION
            },
            lat = p.point.lat,
            lng = p.point.lng,
        )
    }

    init {
        // 모든 필드가 준비된 뒤 (즉시 디스패처에서도 안전하게) 집 앞 정차로 시작
        parkAt(CommuteMaps.PLACE_HOME)
        publish(clock.nowMs())
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }
}
