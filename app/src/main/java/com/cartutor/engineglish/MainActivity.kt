package com.cartutor.engineglish

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cartutor.engineglish.english.core.DemoAnswerKind
import com.cartutor.engineglish.english.core.DemoScenario
import com.cartutor.engineglish.english.core.DemoSignal
import com.cartutor.engineglish.english.core.EnglishRoute
import com.cartutor.engineglish.english.core.MediaKey
import com.cartutor.engineglish.platform.gleo.GleoBridge
import com.cartutor.engineglish.ui.english.EnglishApp
import com.cartutor.engineglish.ui.theme.EngineglishTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as EngineglishApp).container

    /** 런타임(dangerous) 권한: 위치(Pleos FusedLocation, 경로 구간) · 차속(AAOS Car API, 운전 부하) */
    private val runtimePermissions = arrayOf(
        PERMISSION_FUSED_LOCATION,
        "android.car.permission.CAR_SPEED",
    )

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[PERMISSION_FUSED_LOCATION] == true) container.onLocationPermissionGranted()
        if (result.any { (name, granted) -> granted && name != PERMISSION_FUSED_LOCATION }) container.onCarPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = container
        setContent {
            EngineglishTheme {
                EnglishApp(
                    coach = c.coach,
                    navigator = c.navigator,
                    demo = if (c.demoAllowed) c.drive.demo else null,
                    micTest = c.speech.micTest,
                )
            }
        }
        requestPermissionsIfNeeded()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        container.flush()
    }

    /**
     * 핸들 미디어 버튼 (다음 = 넘기기, 이전 = 다시, 재생/일시정지 = 잠깐/계속).
     * Compose 포커스보다 먼저 가로채야 버튼이 화면 요소에 먹히지 않는다.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            if (container.drive.onKeyEvent(event.keyCode, action == KeyEvent.ACTION_DOWN, event.repeatCount)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestPermissionsIfNeeded() {
        val missing = runtimePermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (PERMISSION_FUSED_LOCATION !in missing) container.onLocationPermissionGranted()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        handleDemoExtras(intent)
        val action = GleoBridge.parse(intent) ?: return
        // 한 번만 실행 (같은 Intent 로 액티비티가 다시 만들어져도 반복하지 않게)
        intent.action = Intent.ACTION_MAIN
        GleoBridge.dispatch(this, action)
    }

    /**
     * 시연·녹화 자동화 (디버그 빌드 또는 ADB 켜짐일 때만, 명세 §9). 잘못된 값은 무시한다.
     *   adb -s emulator-5554 shell am start -n com.cartutor.engineglish/.MainActivity --es en_scenario MORNING
     *   --es signal TURN_ON | --es hear "Can we push the meeting to tomorrow?" | --es key NEXT | --es screen REPORT
     *   --es answer CORRECT | --es timescale 6 | --ez en_seed true | --ez en_reset true
     */
    private fun handleDemoExtras(intent: Intent) {
        val c = container
        if (!c.demoAllowed) return
        val demo = c.drive.demo
        if (intent.getBooleanExtra(EXTRA_RESET, false)) c.coach.eraseAllData()
        if (intent.getBooleanExtra(EXTRA_SEED, false)) c.seedDemoHistory()
        intent.getStringExtra(EXTRA_TIME_SCALE)?.toFloatOrNull()?.let { demo.setTimeScale(it) }
        intent.getStringExtra(EXTRA_SCENARIO)?.let { s ->
            if (s.equals("STOP", ignoreCase = true)) demo.stop()
            else enumOrNull<DemoScenario>(s)?.let { demo.play(it) }
        }
        intent.getStringExtra(EXTRA_SIGNAL)?.let { s -> enumOrNull<DemoSignal>(s)?.let { demo.inject(it) } }
        intent.getStringExtra(EXTRA_KEY)?.let { s -> enumOrNull<MediaKey>(s)?.let { c.drive.mediaKeys.inject(it) } }
        intent.getStringExtra(EXTRA_HEAR)?.takeIf { it.isNotBlank() }?.let { c.coach.onAnswerText(it) }
        intent.getStringExtra(EXTRA_ANSWER)?.let { s -> enumOrNull<DemoAnswerKind>(s)?.let { c.coach.injectDemoAnswer(it) } }
        intent.getStringExtra(EXTRA_SCREEN)?.let { s -> enumOrNull<EnglishRoute>(s)?.let { c.navigator.go(it) } }
        // 같은 extra 가 화면 회전·재생성 때 다시 실행되지 않게
        listOf(EXTRA_RESET, EXTRA_SEED, EXTRA_TIME_SCALE, EXTRA_SCENARIO, EXTRA_SIGNAL, EXTRA_KEY, EXTRA_HEAR, EXTRA_ANSWER, EXTRA_SCREEN)
            .forEach { intent.removeExtra(it) }
    }

    private inline fun <reified E : Enum<E>> enumOrNull(raw: String): E? =
        enumValues<E>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    private companion object {
        const val PERMISSION_FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val EXTRA_SCENARIO = "en_scenario"
        const val EXTRA_SIGNAL = "signal"
        const val EXTRA_HEAR = "hear"
        const val EXTRA_KEY = "key"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_ANSWER = "answer"
        const val EXTRA_TIME_SCALE = "timescale"
        const val EXTRA_SEED = "en_seed"
        const val EXTRA_RESET = "en_reset"
    }
}
