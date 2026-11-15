package com.yeopjari.app

import android.app.Application
import com.yeopjari.app.ai.personal.PersonalService
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.platform.hub.NaviHub
import com.yeopjari.app.platform.secure.SecureVault
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.yeopjari.app.platform.hub.VehicleHub
import com.yeopjari.app.platform.pleos.AndroidSpeechOutput
import com.yeopjari.app.platform.pleos.CompositeSpeechOutput
import com.yeopjari.app.platform.pleos.PleosLocationGateway
import com.yeopjari.app.platform.pleos.PleosNaviGateway
import com.yeopjari.app.platform.pleos.PleosSpeechInput
import com.yeopjari.app.platform.pleos.PleosSpeechOutput
import com.yeopjari.app.platform.pleos.PleosVehicleGateway
import com.yeopjari.app.sim.DriveSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class YeopjariApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 수동 DI 컨테이너 — 플랫폼 게이트웨이 · 시뮬레이터 · AI 스토어 조립 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val sim = DriveSimulator(scope)
    val pleosVehicle = PleosVehicleGateway(app, scope)
    val pleosNavi = PleosNaviGateway(app, scope)
    val pleosLocation = PleosLocationGateway(app)

    /** 데이터 소스 — Pleos SDK 연결이 확인되면 자동 전환, 시나리오 재생 시 시뮬레이터 */
    val sourceMode = MutableStateFlow(Source.SIMULATOR)
    private var userPinnedSource = false

    val vehicle = VehicleHub(scope, pleosVehicle, sim.vehicle, sourceMode, pleosVehicle.connected)
    /** 시나리오 재생 중 경로 변경을 실제 차량 내비에도 반영 (시연 녹화 때 adb extra 'mirror'로 끌 수 있음) */
    val mirrorToCar = MutableStateFlow(true)
    val navi = NaviHub(scope, pleosNavi, sim.navi, sourceMode, pleosNavi.connected, mirrorToCar)

    val speechIn = PleosSpeechInput(app)
    val speechOut = CompositeSpeechOutput(PleosSpeechOutput(app), AndroidSpeechOutput(app))

    /** 차 안 비밀 금고(Android Keystore) + 개인화 보정 엔진 */
    val vault = SecureVault(app)
    val personal = PersonalService(vault)

    /** 현재 위치 — Pleos 모드는 FusedLocation SDK(없으면 시뮬레이터), 시나리오 모드는 시뮬레이터 */
    val location = combine(sourceMode, sim.location.location, pleosLocation.location) { src, s, p -> if (src == Source.PLEOS_SDK) (p ?: s) else s }
        .stateIn(scope, SharingStarted.Eagerly, sim.location.location.value)

    val store = YeopjariStore(
        scope = scope, sim = sim, vehicle = vehicle, navi = navi,
        speechIn = speechIn, speechOut = speechOut,
        uxRestricted = pleosVehicle.restricted,
        sourceMode = sourceMode,
        pleosConnected = combine(pleosVehicle.connected, pleosNavi.connected) { v, n -> v || n },
        onSourceChange = { src -> userPinnedSource = true; sourceMode.value = src },
        personal = personal,
        location = location,
    )

    init {
        pleosNavi.start()
        scope.launch {
            pleosVehicle.connected.collect { ok -> if (ok && !userPinnedSource) sourceMode.value = Source.PLEOS_SDK }
        }
        // 실차 내비의 최근 목적지·즐겨찾기 → 개인 장소 사전 (차 안 암호화 저장)
        scope.launch {
            combine(pleosNavi.recentDestinations, pleosNavi.bookmarks) { r, b -> r to b }
                .collect { (r, b) -> if (r.isNotEmpty() || b.isNotEmpty()) personal.syncVehiclePlaces(r, b) }
        }
        // 실제 목적지 도착 → 방문 기록
        scope.launch { pleosNavi.arrivals.collect { p -> personal.recordArrival(p, store.situation()) } }
    }

    fun onLocationPermissionGranted() = pleosLocation.start()
}
