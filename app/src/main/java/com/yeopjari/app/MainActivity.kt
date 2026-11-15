package com.yeopjari.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.yeopjari.app.platform.gleo.GleoBridge
import com.yeopjari.app.ui.YeopjariAppUi
import com.yeopjari.app.ui.theme.YeopjariTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as YeopjariApp).container

    /** dangerous 등급 권한: FusedLocation(Pleos) · 차속/에너지(AAOS Car API) */
    private val runtimePermissions = arrayOf(
        "pleos.car.permission.FUSED_LOCATION",
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_ENERGY",
    )

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result["pleos.car.permission.FUSED_LOCATION"] == true) container.onLocationPermissionGranted()
        if (result["android.car.permission.CAR_SPEED"] == true || result["android.car.permission.CAR_ENERGY"] == true) container.pleosVehicle.refreshCarApi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YeopjariTheme { YeopjariAppUi(container.store) } }
        requestPermissionsIfNeeded()
        handleGleo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGleo(intent)
    }

    private fun requestPermissionsIfNeeded() {
        val missing = runtimePermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) container.onLocationPermissionGranted() else permissionLauncher.launch(missing.toTypedArray())
    }

    /** Gleo Interface — component: activity 액션 (PLAN_EV_TRIP · GO_TO_MY_PLACE) */
    private fun handleGleo(intent: Intent?) {
        handleDemoExtras(intent)
        val action = GleoBridge.parse(intent) ?: return
        // 콜백은 보정·실행 결과가 나온 뒤 한 번 온다 (늦어도 7초 타임아웃 전) — 액티비티가 그사이 사라져도 보내지도록 앱 컨텍스트로
        val app = applicationContext
        container.store.handleGleoActivity(action) { status, message ->
            // 옆자리가 자체 TTS로 안내하므로 Gleo는 silent 모드로 응답
            GleoBridge.respond(app, action.name, status, message, mode = "silent")
        }
    }

    /**
     * 시연·녹화 자동화용: adb shell am start -n com.yeopjari.app/.MainActivity --es scenario ROADTRIP --es screen CHARGE --es say "..."
     * (시뮬레이터 상태만 바꾸며 차량 제어 권한과 무관)
     */
    private fun handleDemoExtras(intent: Intent?) {
        intent ?: return
        val store = container.store
        intent.getStringExtra("scenario")?.let { s -> runCatching { store.playScenario(com.yeopjari.app.sim.ScenarioId.valueOf(s)) } }
        intent.getStringExtra("screen")?.let { s -> runCatching { store.go(Screen.valueOf(s)) } }
        intent.getStringExtra("say")?.let { t -> store.openAgent(); store.send(t) }
        // 대화창을 열지 않고(HUD 화면 유지) 음성 인식 결과처럼 넣기
        intent.getStringExtra("hear")?.let { t -> store.send(t) }
        intent.getStringExtra("confirm")?.let { a -> store.confirm(a) }
        if (intent.getBooleanExtra("demoPanel", false)) store.demoPanel.value = true
        if (intent.getBooleanExtra("closeAgent", false)) store.closeAgent()
        // 개인화 시연: reset(학습 초기화) · erase(기록 삭제) · restore(가상 기록 복원) · sync(변화량 전송 모의)
        when (intent.getStringExtra("personal")) {
            "reset" -> store.resetPersonalLearning()
            "erase" -> store.erasePersonalData()
            "restore" -> store.restorePersonalData()
            "sync" -> store.sendPersonalUpdate()
        }
        intent.getStringExtra("gear")?.let { g -> runCatching { store.sim.manualGear(com.yeopjari.app.core.model.Gear.valueOf(g)) } }
        // 시연 영상용: HUD 시뮬레이터 도로 움직임 (시뮬레이터 모드에서만, 기본 꺼짐)
        if (intent.hasExtra("hudMotion")) store.sim.setHudRoadMotion(intent.getBooleanExtra("hudMotion", false))
        // 시연 녹화용: 시나리오 경로 변경을 실제 차량 내비에도 반영할지 (기본 true)
        if (intent.hasExtra("mirror")) container.mirrorToCar.value = intent.getBooleanExtra("mirror", true)
        // 지금 진행 중인 되묻기의 후보만 고를 수 있다 (지난 질문의 후보는 무시)
        intent.getStringExtra("pick")?.let { id -> store.pickPlace(id) }
    }
}
