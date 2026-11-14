package com.yeopjari.app.platform.pleos

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.gateway.UxRestrictionGateway
import com.yeopjari.app.core.gateway.VehicleGateway
import com.yeopjari.app.core.model.Gear
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Connect 실차/에뮬레이터 연동 게이트웨이.
 *
 * ┌ Pleos Vehicle SDK ─ EvBattery(SOC·용량·전비·충전 스위치·충전 한도) · Gear · HVAC(전원·온도·풍량·내기순환)
 * │                     Door(좌우·뒷문·트렁크) · Seat(탑승 감지) · Safety(경고등) · Odometer(연속 주행 시간)
 * └ AAOS Car API ───── PERF_VEHICLE_SPEED · ENV_OUTSIDE_TEMPERATURE · RANGE_REMAINING
 *                      EV_BATTERY_INSTANTANEOUS_CHARGE_RATE · CarUxRestrictions(주의분산 제한)
 *
 * 모든 호출은 비동기 IPC 이며, 미지원 속성은 조용히 무시하고 기본값을 유지한다.
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
    private val onFail: (Exception) -> Unit = { e -> Log.d(TAG, "vehicle call failed: ${e.message}") }

    override fun start() {
        if (vehicle != null) return
        runCatching {
            val v = Vehicle(context)
            v.initialize()
            vehicle = v
            subscribe(v)
            pollJob = scope.launch { poll(v) }
        }.onFailure { Log.i(TAG, "Pleos Vehicle SDK 사용 불가 → 시뮬레이터 사용", it) }
        runCatching { connectCarApi() }.onFailure { Log.i(TAG, "AAOS Car API 사용 불가", it) }
    }

    override fun stop() {
        pollJob?.cancel()
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

        val hvac = v.getHVAC()
        hvac.getHvacPowerState(driverSeat, { on -> on?.let { p -> _state.update { it.copy(hvac = it.hvac.copy(power = p)) } } }, onFail)
        hvac.registerHvacPowerState(driverSeat, object : HvacPowerStateListener {
            override fun onHvacPowerStateUpdated(state: Boolean?) { state?.let { p -> _state.update { it.copy(hvac = it.hvac.copy(power = p)) } } }
            override fun onFailed(e: Exception) = onFail(e)
        })
        // 공조 꺼짐 등으로 범위 밖 값(0 등)이 오면 무시하고 마지막 유효값 유지
        hvac.getHvacTemperature(driverSeat, TemperatureUnit.CELSIUS, { t -> t?.takeIf { it in 16f..30f }?.let { c -> _state.update { it.copy(hvac = it.hvac.copy(setTempC = c)) } } }, onFail)
        hvac.registerHvacTemperature(driverSeat, object : HvacTemperatureValueListener {
            override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) { celsius?.takeIf { it in 16f..30f }?.let { c -> _state.update { it.copy(hvac = it.hvac.copy(setTempC = c)) } } }
            override fun onFailed(e: Exception) = onFail(e)
        })
        hvac.getHvacFanSpeed(driverSeat, { f -> f?.let { lv -> _state.update { it.copy(hvac = it.hvac.copy(fan = lv)) } } }, onFail)
        hvac.registerHvacFanSpeed(driverSeat, object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(speed: Int?) { speed?.let { lv -> _state.update { it.copy(hvac = it.hvac.copy(fan = lv)) } } }
            override fun onFailed(e: Exception) = onFail(e)
        })
        hvac.registerHvacRecirculationState(driverSeat, object : HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) { state?.let { r -> _state.update { it.copy(hvac = it.hvac.copy(recirculation = r)) } } }
            override fun onFailed(e: Exception) = onFail(e)
        })

        // 도어 — 뒷문 개폐 이력은 '뒷좌석 확인' 리마인더의 근거
        val door = v.getDoor()
        listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT, DoorValue.REAR).forEach { dv ->
            val area = DoorArea(dv)
            door.getCurrentDoorPosition(area, { pos -> pos?.let { applyDoor(dv, it > 0) } }, onFail)
            door.registerDoorPosition(area, object : DoorPositionListener {
                override fun onPositionUpdated(position: Int?) { position?.let { applyDoor(dv, it > 0) } }
                override fun onFailed(e: Exception) = onFail(e)
            })
        }

        // 좌석 탑승 감지 — 동승 구성(아이·가족) 추론
        val seat = v.getSeat()
        mapOf(SeatValue.ROW_1_RIGHT to Seat.PASSENGER, SeatValue.ROW_2_LEFT to Seat.REAR_LEFT, SeatValue.ROW_2_RIGHT to Seat.REAR_RIGHT).forEach { (sv, s) ->
            val area = SeatArea(sv)
            seat.getCurrentSeatOccupancy(area, { occ -> occ?.let { applySeat(s, it) } }, onFail)
            seat.registerSeatOccupancy(area, object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) { occupied?.let { applySeat(s, it) } }
                override fun onFailed(e: Exception) = onFail(e)
            })
        }

        // 경고등 — 차량 닥터의 진단 입력
        val safety = v.getSafety()
        safety.getTelltalesInfo({ t -> t?.let { applyTelltales(it) } }, onFail)
        safety.registerTelltales(object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) { telltales?.let { applyTelltales(it) } }
            override fun onFailed(e: Exception) = onFail(e)
        })
    }

    /** 리스너가 없는 값들은 15초 주기로 가볍게 조회 (주행 중 CPU 5% 이하 가이드 준수) */
    private suspend fun poll(v: Vehicle) {
        while (scope.isActive) {
            runCatching {
                v.getEvBattery().getEvChargeSwitchState({ on -> on?.let { c ->
                    _state.update { s -> s.copy(isCharging = c, chargeStartedSec = if (c) s.chargeStartedSec ?: nowSec() else null) }
                } }, onFail)
                v.getEvBattery().getCurrentEvEfficiency({ e -> e?.let { k -> if (k in 2f..12f) _state.update { it.copy(efficiencyKmPerKwh = k) } } }, onFail)
                v.getOdometer().getTripCurrentDrivingTime({ sec -> sec?.let { s -> _state.update { it.copy(continuousDriveMin = s / 60) } } }, onFail)
                v.getOdometer().getCurrentDistanceDriven({ km -> km?.let { k -> _state.update { it.copy(odometerKm = k.toInt()) } } }, onFail)
                v.getHVAC().getHvacTemperatureCurrent(driverSeat, { t -> t?.let { c -> _state.update { it.copy(cabinTempC = c) } } }, onFail)
                v.getSafety().hasLowWasherFluidWarning({ low -> low?.let { l -> _state.update { it.copy(telltales = if (l) it.telltales + Telltale.LOW_WASHER_FLUID else it.telltales - Telltale.LOW_WASHER_FLUID) } } }, onFail)
            }
            delay(15_000)
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

    private fun applyDoor(dv: DoorValue, open: Boolean) = _state.update {
        val d = it.doors
        it.copy(doors = when (dv) {
            DoorValue.ROW_1_LEFT -> d.copy(driver = open)
            DoorValue.ROW_1_RIGHT -> d.copy(passenger = open)
            DoorValue.ROW_2_LEFT -> d.copy(rearLeft = open)
            DoorValue.ROW_2_RIGHT -> d.copy(rearRight = open)
            DoorValue.REAR -> d.copy(trunk = open)
            else -> d
        })
    }

    private fun applySeat(seat: Seat, occupied: Boolean) = _state.update {
        it.copy(occupants = if (occupied) it.occupants + seat else it.occupants - seat)
    }

    private fun applyTelltales(t: Telltales) {
        val active = t.telltales.filter { it.state != TelltaleState.OFF }.mapNotNull {
            when (it.type) {
                TelltaleType.TPMS -> Telltale.TPMS
                TelltaleType.LOW_VOLT_BATTERY -> Telltale.LOW_12V_BATTERY
                TelltaleType.LOW_EV_BATTERY -> Telltale.LOW_EV_BATTERY
                TelltaleType.SEAT_BELT -> Telltale.SEAT_BELT
                else -> null
            }
        }.toSet()
        _state.update { s -> s.copy(telltales = active + (s.telltales.filter { it == Telltale.LOW_WASHER_FLUID })) }
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
                listOf(
                    VehiclePropertyIds.PERF_VEHICLE_SPEED to CarPropertyManager.SENSOR_RATE_NORMAL,
                    VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE to CarPropertyManager.SENSOR_RATE_ONCHANGE,
                    VehiclePropertyIds.RANGE_REMAINING to CarPropertyManager.SENSOR_RATE_ONCHANGE,
                    VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE to CarPropertyManager.SENSOR_RATE_ONCHANGE,
                ).forEach { (id, rate) -> runCatching { pm.registerCallback(cb, id, rate) } }
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
        when (v.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value as? Float)?.let { ms -> _state.update { it.copy(speedKph = (ms * 3.6f).coerceAtLeast(0f)) } }
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> (value as? Float)?.let { c -> _state.update { it.copy(outsideTempC = c) } }
            // 0 m 는 '미보고'로 보고 SOC×용량×전비로 추정
            VehiclePropertyIds.RANGE_REMAINING -> (value as? Float)?.let { m ->
                _state.update { s -> s.copy(rangeKm = if (m > 0f) (m / 1000f).toInt() else (s.socPercent / 100f * s.batteryKwh * s.efficiencyKmPerKwh).toInt()) }
            }
            VehiclePropertyIds.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE -> (value as? Float)?.let { mw -> _state.update { it.copy(chargePowerKw = (mw / 1_000_000f).coerceAtLeast(0f)) } }
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

    companion object { private const val TAG = "PleosVehicle" }
}
