package com.yeopjari.app.english.drive

import android.content.Context
import android.util.Log
import com.yeopjari.app.english.core.AudioEnvironment
import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.CommuteDetector
import com.yeopjari.app.english.core.CommuteMap
import com.yeopjari.app.english.core.ContextHookDetector
import com.yeopjari.app.english.core.DemoControl
import com.yeopjari.app.english.core.DemoScenario
import com.yeopjari.app.english.core.DemoSignal
import com.yeopjari.app.english.core.DrivingLoadEngine
import com.yeopjari.app.english.core.LatLng
import com.yeopjari.app.english.core.LoadPolicy
import com.yeopjari.app.english.core.MediaKeyPort
import com.yeopjari.app.english.core.SignalOrigin
import com.yeopjari.app.english.core.SignalSource
import com.yeopjari.app.english.core.VehicleFeedback
import com.yeopjari.app.platform.pleos.PleosLocationGateway
import com.yeopjari.app.platform.pleos.PleosNaviGateway
import com.yeopjari.app.platform.pleos.PleosVehicleGateway
import com.yeopjari.app.sim.CommuteSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 운전 모듈 조립 (명세 §2.1) — 통합(AppContainer)은 이 클래스만 만든다.
 *
 * 신호 원천: Pleos Vehicle SDK(방향지시등·핸들·브레이크·크루즈·와이퍼·로빔·기어·충전) + AAOS 속도·주의분산 제한 +
 * NaviHelper(안내 지점·카메라·주의 구간·남은 시간·목적지·제한 속도) + FusedLocation/내비 위치 + 오디오 환경,
 * 데모 시나리오 중에는 [CommuteSimulator].
 *
 * @param demoAllowed false 면 [demo] 는 아무것도 하지 않는다 (릴리스 빌드에 ADB 꺼짐 — 시나리오·신호 주입 불가)
 */
class DriveModule(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock,
    audio: AudioEnvironment,
    demoAllowed: Boolean,
) {
    private val app: Context = context.applicationContext ?: context

    val map: CommuteMap = CommuteMaps.default
    val simulator: CommuteSimulator = CommuteSimulator(scope, clock, map)
    val demo: DemoControl = if (demoAllowed) simulator else LockedDemoControl(simulator)

    private val pleosVehicle = PleosVehicleGateway(app, scope)
    private val pleosNavi = PleosNaviGateway(app, scope, personalPlaceSync = false)
    private val pleosLocation = PleosLocationGateway(app)

    private val carLocation: StateFlow<LatLng?> =
        combine(pleosLocation.location, pleosNavi.currentLocation) { fused, navi -> (fused ?: navi)?.let { LatLng(it.lat, it.lng) } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val hub = DrivingSignalHub(
        scope = scope,
        clock = clock,
        map = map,
        simulator = simulator,
        audio = audio,
        car = CarInputs(
            connected = pleosVehicle.connected,
            vehicle = pleosVehicle.snapshot,
            route = pleosNavi.route,
            location = carLocation,
            uxRestricted = pleosVehicle.restricted,
            arrivals = pleosNavi.arrivedEvents,
        ),
    )
    val signals: SignalSource = hub

    val loadEngine: DrivingLoadEngine = RuleLoadEngine()
    val policy: LoadPolicy = TableLoadPolicy()
    val hooks: ContextHookDetector = RuleContextHookDetector()
    val commute: CommuteDetector = RouteCommuteDetector(map)

    private val vehicleFeedback = PleosVehicleFeedback(scope, clock, pleosVehicle.ambientLight)
    val feedback: VehicleFeedback = vehicleFeedback

    private val mediaKeyHub = MediaKeyHub(app, clock)
    val mediaKeys: MediaKeyPort = mediaKeyHub

    /** 시뮬레이터 경로를 실제 차량 내비에도 반영 — 기본 꺼짐 (실차 내비 오염 방지). 설정 mirrorSimRouteToCar 와 묶는다 */
    val mirrorToCar = MutableStateFlow(false)

    /** 지금 신호 원천 (Pleos 차량 / 시뮬레이터) — 설정·데모 화면 표시용 */
    val signalOrigin: StateFlow<SignalOrigin> = hub.origin

    private var mirrored = false

    /**
     * MainActivity.onKeyDown/onKeyUp 에서: `drive.onKeyEvent(keyCode, down = true, event.repeatCount)`.
     * 미디어 키(다음·이전·재생/일시정지)면 true (소비). 길게 누름 반복은 한 번으로 친다.
     */
    fun onKeyEvent(keyCode: Int, down: Boolean, repeatCount: Int = 0): Boolean = mediaKeyHub.onKeyEvent(keyCode, down, repeatCount)

    /** 위치 권한(pleos.car.permission.FUSED_LOCATION) 허용 뒤 */
    fun onLocationPermissionGranted() = pleosLocation.start()

    /** 차속·에너지 권한 허용 뒤 AAOS Car API 재연결 */
    fun refreshCarApi() = pleosVehicle.refreshCarApi()

    fun release() {
        simulator.release()
        mediaKeyHub.release()
        vehicleFeedback.release()
        if (mirrored) scope.launch { pleosNavi.cancelGuidance() }
        mirrored = false
        pleosLocation.stop()
        pleosNavi.stop()
        pleosVehicle.stop()
    }

    /** 새 데모 주행: 상황 훅 초기화, (설정을 켰을 때만) 실차 내비에 같은 목적지 */
    private fun onNewRun() {
        hooks.reset()
        val destId = when (simulator.scenario.value) {
            DemoScenario.MORNING -> CommuteMaps.PLACE_OFFICE
            DemoScenario.EVENING -> CommuteMaps.PLACE_HOME
            else -> null
        }
        val dest = map.place(destId)
        scope.launch {
            if (mirrored) {
                pleosNavi.cancelGuidance()
                mirrored = false
            }
            if (dest != null && mirrorToCar.value && pleosNavi.connected.value) {
                mirrored = pleosNavi.setDestination(CommuteSimulator.toLegacyPlace(dest)).isSuccess
                if (!mirrored) Log.i(TAG, "sim route mirror failed")
            }
        }
    }

    init {
        pleosVehicle.start()
        pleosNavi.start()
        scope.launch { simulator.runId.drop(1).collect { onNewRun() } }
        scope.launch {
            simulator.scenario.collect { sc ->
                if (sc == null && mirrored) {
                    pleosNavi.cancelGuidance()
                    mirrored = false
                }
            }
        }
    }

    private companion object {
        const val TAG = "DriveModule"
    }
}

/** 데모가 허용되지 않은 빌드 — 상태는 보여 주되 조작은 무시 */
private class LockedDemoControl(private val delegate: DemoControl) : DemoControl by delegate {
    override fun play(scenario: DemoScenario) {}
    override fun inject(signal: DemoSignal) {}
    override fun setTimeScale(scale: Float) {}
    override fun stop() {}
}
