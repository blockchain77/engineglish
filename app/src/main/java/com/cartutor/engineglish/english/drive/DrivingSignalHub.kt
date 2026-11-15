package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.core.model.RouteState
import com.cartutor.engineglish.core.model.VehicleSnapshot
import com.cartutor.engineglish.english.core.AudioEnvironment
import com.cartutor.engineglish.english.core.Clock
import com.cartutor.engineglish.english.core.CommuteMap
import com.cartutor.engineglish.english.core.DrivingSignals
import com.cartutor.engineglish.english.core.GearPosition
import com.cartutor.engineglish.english.core.LatLng
import com.cartutor.engineglish.english.core.SignalOrigin
import com.cartutor.engineglish.english.core.SignalSource
import com.cartutor.engineglish.sim.CommuteSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 실차(Pleos) 입력 묶음 — DriveModule 이 Pleos 게이트웨이로 채우고, 테스트는 MutableStateFlow 로 채운다.
 * @param connected Vehicle SDK 와 실제로 통신 중 (PleosVehicleGateway.connected)
 * @param arrivals NaviHelper onDestinationArrived 마다 한 번
 */
class CarInputs(
    val connected: StateFlow<Boolean>,
    val vehicle: StateFlow<VehicleSnapshot>,
    val route: StateFlow<RouteState>,
    val location: StateFlow<LatLng?>,
    val uxRestricted: StateFlow<Boolean>,
    val arrivals: Flow<Unit> = emptyFlow(),
) {
    companion object {
        /** Pleos 없음 (에뮬레이터 밖·테스트) */
        fun disconnected(): CarInputs = CarInputs(
            connected = MutableStateFlow(false),
            vehicle = MutableStateFlow(VehicleSnapshot()),
            route = MutableStateFlow(RouteState()),
            location = MutableStateFlow<LatLng?>(null),
            uxRestricted = MutableStateFlow(false),
        )
    }
}

/**
 * 운전 신호 허브 (명세 §4.1 · §4.8 원천 선택) — 코치가 보는 단 하나의 SignalSource.
 *
 * - 데모 시나리오 재생 중(scenario != null): 모든 차량·내비 값은 시뮬레이터 (origin SIMULATOR, 움직이면 uxRestricted)
 * - 시나리오 없음: Vehicle SDK 가 연결돼 있으면 실차(origin PLEOS), 아니면 집 앞에 서 있는 시뮬레이터
 * - 오디오 환경(내비 안내 음성·포커스 손실)은 시뮬레이터 주입과 항상 OR 로 합친다
 * - 원천이 바뀌거나 값이 바뀌면 즉시, 아니어도 [heartbeatMs] 마다 atMs 를 새로 찍어 내보낸다 (히스테리시스 타이머용)
 */
class DrivingSignalHub(
    private val scope: CoroutineScope,
    private val clock: Clock,
    map: CommuteMap,
    private val simulator: CommuteSimulator,
    private val audio: AudioEnvironment?,
    private val car: CarInputs = CarInputs.disconnected(),
    private val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS,
) : SignalSource {

    private val assembler = RealSignalAssembler(map)
    private val _signals = MutableStateFlow(simulator.signals.value.copy(atMs = clock.nowMs()))
    override val signals: StateFlow<DrivingSignals> = _signals.asStateFlow()

    private val _origin = MutableStateFlow(SignalOrigin.SIMULATOR)
    /** 지금 원천 (설정·데모 화면 표시용) */
    val origin: StateFlow<SignalOrigin> = _origin.asStateFlow()

    private var lastArrivalMs: Long? = null
    private var lastEmitMs = Long.MIN_VALUE / 2

    /** 지금 원천으로 신호를 다시 만든다 (값 변화·하트비트) */
    fun recompute() {
        val now = clock.nowMs()
        lastEmitMs = now
        val useSim = simulator.scenario.value != null || !car.connected.value
        var s = if (useSim) {
            val sim = simulator.signals.value
            sim.copy(atMs = now, origin = SignalOrigin.SIMULATOR, uxRestricted = sim.gear != GearPosition.P)
        } else {
            assembler.assemble(
                RealSignalAssembler.Inputs(
                    vehicle = car.vehicle.value,
                    route = car.route.value,
                    location = car.location.value,
                    uxRestricted = car.uxRestricted.value,
                    lastArrivalMs = lastArrivalMs,
                ),
                now,
            )
        }
        val navPrompt = audio?.navPromptActive?.value == true || simulator.injectedNavPrompt(now)
        val focusLost = audio?.focusLost?.value == true
        if (navPrompt || focusLost) {
            s = s.copy(navVoicePrompt = s.navVoicePrompt || navPrompt, audioFocusLost = s.audioFocusLost || focusLost)
        }
        _origin.value = s.origin
        _signals.value = s
    }

    init {
        scope.launch {
            car.arrivals.collect {
                lastArrivalMs = clock.nowMs()
                recompute()
            }
        }
        scope.launch {
            val sources: List<Flow<Any?>> = listOfNotNull(
                simulator.signals, simulator.scenario,
                car.connected, car.vehicle, car.route, car.location, car.uxRestricted,
                audio?.navPromptActive, audio?.focusLost,
            )
            sources.merge().collect { recompute() }
        }
        // 하트비트: 다른 이유로 방금 내보냈으면 건너뛴다 (최대 간격 = 2 × heartbeatMs ≤ 500ms)
        scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                if (clock.nowMs() - lastEmitMs >= heartbeatMs) recompute()
            }
        }
    }

    companion object {
        /** 최대 간격 500ms 보다 촘촘하게 */
        const val DEFAULT_HEARTBEAT_MS = 250L
    }
}
