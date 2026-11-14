package com.yeopjari.app

import android.app.Application
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.platform.hub.NaviHub
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
    val navi = NaviHub(scope, pleosNavi, sim.navi, sourceMode, pleosNavi.connected)

    val speechIn = PleosSpeechInput(app)
    val speechOut = CompositeSpeechOutput(PleosSpeechOutput(app), AndroidSpeechOutput(app))

    val store = YeopjariStore(
        scope = scope, sim = sim, vehicle = vehicle, navi = navi,
        speechIn = speechIn, speechOut = speechOut,
        uxRestricted = pleosVehicle.restricted,
        sourceMode = sourceMode,
        pleosConnected = combine(pleosVehicle.connected, pleosNavi.connected) { v, n -> v || n },
        onSourceChange = { src -> userPinnedSource = true; sourceMode.value = src },
    )

    init {
        pleosNavi.start()
        scope.launch {
            pleosVehicle.connected.collect { ok -> if (ok && !userPinnedSource) sourceMode.value = Source.PLEOS_SDK }
        }
    }

    fun onLocationPermissionGranted() = pleosLocation.start()
}
