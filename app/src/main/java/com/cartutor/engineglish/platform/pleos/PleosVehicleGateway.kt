package com.cartutor.engineglish.platform.pleos

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import com.cartutor.engineglish.core.gateway.AmbientLightGateway
import com.cartutor.engineglish.core.gateway.Source
import com.cartutor.engineglish.core.gateway.UxRestrictionGateway
import com.cartutor.engineglish.core.gateway.VehicleGateway
import com.cartutor.engineglish.core.model.Gear
import com.cartutor.engineglish.core.model.VehicleSnapshot
import com.cartutor.engineglish.english.core.TurnSignalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Connect 실차/에뮬레이터 연동 게이트웨이.
 *
 * ┌ Pleos Vehicle SDK ─ EvBattery(SOC·충전 스위치) · Gear
 * │                     운전 부하: TurnSignal · SteeringWheel · Brake · DrivingMode(크루즈) · Wiper · Light(로빔)
 * │                     차량 피드백: Light 앰비언트 색 ([ambientLight])
 * └ AAOS Car API ───── PERF_VEHICLE_SPEED · CarUxRestrictions(주의분산 제한)
 *
 * 모든 호출은 비동기 IPC 이며, 미지원 속성·권한 없음은 조용히 무시하고 기본값을 유지한다 (로그는 오류 종류만).
 * 공개 API 만 쓴다 (@PrivilegedApi·내부 API 호출 없음).
 */
class PleosVehicleGateway(
    private val context: Context,
    private val scope: CoroutineScope,
) : VehicleGateway, UxRestrictionGateway {

    override val source = Source.PLEOS_SDK
    private val _state = MutableStateFlow(VehicleSnapshot(model = "Pleos Connect"))
    override val snapshot: StateFlow<VehicleSnapshot> = _state.asStateFlow()

    private val _restricted = MutableStateFlow(false)
    override val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    /** Vehicle 서비스와 실제로 통신에 성공했는가 (initialize 성공 ≠ 연결 성공) */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var pollJob: Job? = null
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val onFail: (Exception) -> Unit = { e -> Log.d(TAG, "vehicle call failed: ${e.javaClass.simpleName}") }

    override fun start() {
        if (vehicle != null) return
        runCatching {
            val v = Vehicle(context)
            v.initialize()
            vehicle = v
            // 운전 부하 신호를 먼저 — 레거시 구독 중 하나가 실패해도 부하 신호는 살아 있게
            subscribeDriving(v)
            subscribe(v)
        }.onFailure { Log.i(TAG, "Pleos Vehicle SDK 사용 불가 → 시뮬레이터 사용", it) }
        vehicle?.let { v -> if (pollJob == null) pollJob = scope.launch { poll(v) } }
        runCatching { connectCarApi() }.onFailure { Log.i(TAG, "AAOS Car API 사용 불가", it) }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        vehicle = null
    }

    private fun mark() { if (!_connected.value) _connected.value = true }

    // ───────────────────────── Vehicle SDK 구독 ─────────────────────────
    private fun subscribe(v: Vehicle) {
        val ev = v.getEvBattery()
        ev.getEvBatteryStateOfCharge({ soc -> soc?.let { s -> mark(); _state.update { it.copy(socPercent = s.toFloat()) } } }, onFail)
        ev.registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) { value?.let { s -> mark(); _state.update { it.copy(socPercent = s.toFloat()) } } }
            override fun onFailed(e: Exception) = onFail(e)
        })
        ev.getEvBatteryCapacity({ cap -> cap?.let { c -> if (c in 20f..200f) _state.update { it.copy(batteryKwh = c) } } }, onFail)

        val gear = v.getGear()
        gear.getCurrentGearSelectionState({ g -> g?.let { mark(); _state.update { s -> s.copy(gear = map(it)) } } }, onFail)
        gear.registerGearSelection(object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { gear?.let { mark(); _state.update { s -> s.copy(gear = map(it)) } } }
            override fun onFailed(e: Exception) = onFail(e)
        })
    }

    /** 리스너가 없는 충전 스위치는 5초마다 가볍게 조회 (충전 시작 상황 훅, 주행 중 CPU 5% 이하 가이드) */
    private suspend fun poll(v: Vehicle) {
        while (scope.isActive) {
            runCatching {
                v.getEvBattery().getEvChargeSwitchState({ on -> on?.let { c ->
                    _state.update { s -> s.copy(isCharging = c, chargeStartedSec = if (c) s.chargeStartedSec ?: nowSec() else null) }
                } }, onFail)
            }
            delay(5_000)
        }
    }

    // ───────────────────────── 운전 부하 신호 (엔진글리시) ─────────────────────────

    /**
     * 방향지시등 · 핸들 각도 · 브레이크 · 크루즈 · 와이퍼 · 로빔.
     * 항목마다 따로 감싸서 권한(CAR_LIGHTS · CAR_DRIVE · CAR_WINDOWS)이 없는 항목만 조용히 빠지게 한다.
     */
    private fun subscribeDriving(v: Vehicle) {
        guard("turnSignal") {
            val api = v.getTurnSignal()
            api.getCurrentTurnSignalState({ s -> applyTurnSignal(s) }, onFail)
            api.registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = applyTurnSignal(state)
                override fun onFailed(e: Exception) = onFail(e)
            })
        }
        guard("steering") {
            val api = v.getSteeringWheel()
            api.getCurrentSteeringWheelAngle({ a -> applySteering(a) }, onFail)
            api.registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = applySteering(angle)
                override fun onFailed(e: Exception) = onFail(e)
            })
        }
        guard("brake") {
            val api = v.getBrake()
            api.getCurrentBrakePedalStatus({ b -> applyBrake(b) }, onFail)
            api.registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(status: Boolean?) = applyBrake(status)
                override fun onFailed(e: Exception) = onFail(e)
            })
        }
        guard("cruise") {
            val api = v.getDrivingMode()
            api.getCruiseControlState({ c -> applyCruise(c) }, onFail)
            api.registerCruiseControlState(object : CruiseControlStateListener {
                override fun onCruiseControlStateUpdated(state: CruiseControl?) = applyCruise(state)
                override fun onFailed(e: Exception) = onFail(e)
            })
        }
        guard("wiper") {
            val api = v.getWiper()
            val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
            api.getCurrentWiperState(windshield, { w -> applyWiper(w) }, onFail)
            api.registerWiperState(windshield, object : WiperStateListener {
                override fun onWiperStateUpdated(state: WiperState?) = applyWiper(state)
                override fun onFailed(e: Exception) = onFail(e)
            })
        }
        guard("lowBeam") {
            val api = v.getLight()
            api.getCurrentLowBeamLightState({ l -> applyLowBeam(l) }, onFail)
            api.registerLowBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) = applyLowBeam(state)
                override fun onFailed(e: Exception) = onFail(e)
            })
        }
    }

    private fun guard(what: String, block: () -> Unit) {
        runCatching { block() }.onFailure { Log.i(TAG, "$what unavailable: ${it.javaClass.simpleName}") }
    }

    private fun applyTurnSignal(value: TurnSignalValue?) {
        val v = value ?: return
        mark()
        val state = when (v) {
            TurnSignalValue.LEFT -> TurnSignalState.LEFT
            TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
            else -> TurnSignalState.OFF
        }
        _state.update { it.copy(turnSignal = state) }
    }

    private fun applySteering(angle: Float?) {
        val a = angle ?: return
        if (a.isNaN()) return
        _state.update { it.copy(steeringDeg = a * STEERING_SIGN) }
    }

    private fun applyBrake(pressed: Boolean?) {
        val b = pressed ?: return
        _state.update { it.copy(brakePedal = b) }
    }

    private fun applyCruise(state: CruiseControl?) {
        val c = state ?: return
        _state.update { it.copy(cruiseActive = c == CruiseControl.ACTIVATED) }
    }

    private fun applyWiper(state: WiperState?) {
        val w = state ?: return
        _state.update { it.copy(wipersOn = w == WiperState.ON) }
    }

    private fun applyLowBeam(state: LightState?) {
        val l = state ?: return
        // 주간주행등(DAYTIME_RUNNING)은 밤으로 보지 않는다
        _state.update { it.copy(headlightsOn = l == LightState.ON) }
    }

    /**
     * 앰비언트 라이트 색 (정답/오답 1초 펄스) — 읽기 CAR_LIGHTS, 쓰기 CONTROL_CAR_LIGHTS.
     * 색 값은 RGB int(0xRRGGBB)로 시도한다. 실패·시간 초과는 null/false 로 돌려준다.
     */
    val ambientLight: AmbientLightGateway = object : AmbientLightGateway {
        override suspend fun readColor(): Int? {
            val v = vehicle ?: return null
            return withTimeoutOrNull(LIGHT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Int?> { cont ->
                    runCatching {
                        v.getLight().getAmbientLightBrightnessColor(
                            driverSeat,
                            { c -> val color: Int? = c; if (cont.isActive) cont.resume(color) },
                            { e -> Log.d(TAG, "ambient read failed: ${e.javaClass.simpleName}"); if (cont.isActive) cont.resume(null) },
                        )
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }

        override suspend fun writeColor(rgb: Int): Boolean {
            val v = vehicle ?: return false
            return withTimeoutOrNull(LIGHT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    runCatching {
                        v.getLight().setAmbientLightBrightnessColor(
                            driverSeat,
                            rgb,
                            { _, _ -> if (cont.isActive) cont.resume(true) },
                            { e -> Log.d(TAG, "ambient write failed: ${e.javaClass.simpleName}"); if (cont.isActive) cont.resume(false) },
                        )
                    }.onFailure { if (cont.isActive) cont.resume(false) }
                }
            } ?: false
        }
    }

    private fun nowSec(): Float {
        val c = java.util.Calendar.getInstance()
        return (c.get(java.util.Calendar.HOUR_OF_DAY) * 3600 + c.get(java.util.Calendar.MINUTE) * 60 + c.get(java.util.Calendar.SECOND)).toFloat()
    }

    private fun map(g: VehicleGear): Gear = when (g) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.D
    }

    // ───────────────────────── AAOS Car API ─────────────────────────
    /** 런타임 권한(CAR_SPEED·CAR_ENERGY) 허용 직후 속성 구독을 다시 등록 */
    fun refreshCarApi() {
        runCatching { car?.disconnect() }
        car = null
        runCatching { connectCarApi() }.onFailure { Log.i(TAG, "AAOS Car API 재연결 실패", it) }
    }

    private fun connectCarApi() {
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            if (!ready) return@createCar
            runCatching {
                val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                val cb = object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(value: CarPropertyValue<*>) = onProperty(value)
                    override fun onErrorEvent(propertyId: Int, zone: Int) {}
                }
                runCatching { pm.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL) }
            }.onFailure { Log.i(TAG, "CarPropertyManager 등록 실패", it) }
            runCatching {
                val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                _restricted.value = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
                ux.registerListener { r -> _restricted.value = r.isRequiresDistractionOptimization }
            }.onFailure { Log.i(TAG, "CarUxRestrictionsManager 사용 불가", it) }
        }
    }

    private fun onProperty(v: CarPropertyValue<*>) {
        val value = v.value ?: return
        if (v.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
            (value as? Float)?.let { ms -> _state.update { it.copy(speedKph = (ms * 3.6f).coerceAtLeast(0f)) } }
        }
    }

    // ───────────────────────── 제어 (Vehicle SDK · CONTROL_CAR_*) ─────────────────────────
    override suspend fun setHvacPower(on: Boolean) = call { done, fail -> vehicle!!.getHVAC().setHvacPower(driverSeat, on, { _, _ -> done() }, fail) }
    override suspend fun setHvacTemperature(celsius: Float) = call { done, fail ->
        vehicle!!.getHVAC().setHvacTemperature(driverSeat, (Math.round(celsius * 2) / 2f).coerceIn(16f, 30f), TemperatureUnit.CELSIUS, { _, _ -> done() }, fail)
    }
    override suspend fun setFanSpeed(level: Int) = call { done, fail -> vehicle!!.getHVAC().setHvacFanSpeed(driverSeat, level.coerceIn(0, 8), { _, _ -> done() }, fail) }
    override suspend fun setRecirculation(on: Boolean) = call { done, fail -> vehicle!!.getHVAC().setHvacRecirculation(driverSeat, on, { _, _ -> done() }, fail) }
    override suspend fun setChargeLimit(percent: Int) = call { done, fail ->
        vehicle!!.getEvBattery().setEvChargePercentLimit(percent.coerceIn(50, 100).toFloat(), { _ -> done() }, fail)
    }

    /** 콜백형 SDK 호출을 2초 타임아웃의 suspend 함수로 감싼다 (버튼 응답 2초 가이드) */
    private suspend fun call(block: (done: () -> Unit, fail: (Exception) -> Unit) -> Unit): Result<Unit> {
        if (vehicle == null) return Result.failure(IllegalStateException("Pleos Vehicle SDK 미연결"))
        val r = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                runCatching {
                    block({ if (cont.isActive) cont.resume(Result.success(Unit)) }, { e -> if (cont.isActive) cont.resume(Result.failure(e)) })
                }.onFailure { if (cont.isActive) cont.resume(Result.failure(it)) }
            }
        }
        return r ?: Result.failure(IllegalStateException("차량 응답 시간 초과"))
    }

    companion object {
        private const val TAG = "PleosVehicle"
        /**
         * 핸들 각도 부호 (+오른쪽). AAOS PERF_STEERING_ANGLE 규약(왼쪽 −)을 따른다고 보고 1.
         * 실차에서 왼쪽 회전이 + 로 오면 −1 로 바꾼다 (명세 §4.1).
         */
        const val STEERING_SIGN = 1f
        private const val LIGHT_TIMEOUT_MS = 1_500L
    }
}
