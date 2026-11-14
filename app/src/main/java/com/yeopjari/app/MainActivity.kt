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

    /** Gleo Interface — component: activity 액션 (PLAN_EV_TRIP · DIAGNOSE_VEHICLE) */
    private fun handleGleo(intent: Intent?) {
        handleDemoExtras(intent)
        val action = GleoBridge.parse(intent) ?: return
        container.store.handleGleoActivity(action) { status, message ->
            // 옆자리가 자체 TTS로 안내하므로 Gleo는 silent 모드로 응답 (7초 타임아웃 이내)
            GleoBridge.respond(this, action.name, status, message, mode = "silent")
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
        intent.getStringExtra("confirm")?.let { a -> store.confirm(a) }
        if (intent.getBooleanExtra("demoPanel", false)) store.demoPanel.value = true
        if (intent.getBooleanExtra("closeAgent", false)) store.closeAgent()
    }
}
