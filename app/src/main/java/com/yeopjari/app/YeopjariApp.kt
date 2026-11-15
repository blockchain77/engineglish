package com.yeopjari.app

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import com.yeopjari.app.english.coach.DefaultEnglishCoach
import com.yeopjari.app.english.content.ContentModule
import com.yeopjari.app.english.content.JsonContentSource
import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.CoachDeps
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.EnglishNavigator
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.SoundPort
import com.yeopjari.app.english.core.WallClock
import com.yeopjari.app.english.drive.DriveModule
import com.yeopjari.app.english.learn.DemoLearnerSeed
import com.yeopjari.app.english.learn.LearnModule
import com.yeopjari.app.english.sound.SoundPoolPlayer
import com.yeopjari.app.english.speech.SpeechModule
import com.yeopjari.app.platform.secure.SecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class YeopjariApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 수동 DI 컨테이너 — 옆자리 영어의 모듈을 한 번만 조립한다 (명세 §13).
 *
 *  콘텐츠(assets JSON) · 학습(SRS·레벨 테스트·진도, 암호화 저장) · 말하기(Pleos STT/TTS, 앱 안 채점) ·
 *  운전(부하 엔진·정책·상황 훅·출퇴근·시뮬레이터·미디어 키·앰비언트) · 효과음 → 코치(상태 기계) → 화면.
 *
 * 학습 기록은 이 차 안의 암호화 파일에만 저장되고, 앱은 어떤 서버와도 통신하지 않는다.
 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock: Clock = WallClock

    /** 시연 기능(시뮬레이터 패널·adb extra) 허용: 디버그 빌드 또는 ADB 켜짐 (명세 §9) */
    val demoAllowed: Boolean = isDemoAllowed(app)

    val vault = SecureVault(app)
    private val contentModule = ContentModule(app)
    val content: JsonContentSource = contentModule.source
    val learn = LearnModule(vault, scope, clock)
    val speech = SpeechModule(app, scope, clock)
    val drive = DriveModule(app, scope, clock, speech.audio, demoAllowed)
    val navigator = EnglishNavigator()

    /** 효과음 (직접 합성한 짧은 WAV, SoundPool) — 언제 낼지는 코치가 정한다 (설정 '효과음' · 부하 CRITICAL 이면 무음) */
    private val soundPlayer = SoundPoolPlayer(app)
    val sound: SoundPort = soundPlayer

    val coach: EnglishCoach = DefaultEnglishCoach(
        CoachDeps(
            clock = clock,
            content = content,
            repository = learn.repository,
            scheduler = learn.scheduler,
            placement = learn.placement,
            progression = learn.progression,
            planner = learn.planner,
            judge = speech.judge,
            commands = speech.commands,
            pronunciation = speech.pronunciation,
            fluency = speech.fluency,
            speech = speech.port,
            signals = drive.signals,
            loadEngine = drive.loadEngine,
            policy = drive.policy,
            hooks = drive.hooks,
            commute = drive.commute,
            feedback = drive.feedback,
            mediaKeys = drive.mediaKeys,
            map = drive.map,
            demoEnabled = demoAllowed,
            sound = sound,
        ),
        scope,
    )

    init {
        // 콘텐츠 파싱(약 0.3MB)과 옛 버전 개인 금고 정리는 메인 스레드 밖에서 (코치가 먼저 콘텐츠를 찾으면 파싱이 끝날 때까지 기다린다)
        scope.launch(Dispatchers.IO) {
            contentModule.warmUp()
            // 지도 정보 초기화: 옛 페르소나 장소·방문 기록 파일 삭제 (없으면 아무 일 없음)
            learn.purgeLegacyFiles()
        }
        // 코치는 바로 시작 — 앱이 켜지자마자 시작된 주행(출퇴근 자동 시작)도 놓치지 않게
        coach.start()
        // 설정 → 운전·말하기 모듈 (시뮬레이터 경로의 실차 내비 반영은 기본 꺼짐)
        scope.launch {
            coach.state.map { it.settings }.distinctUntilChanged().collect { s ->
                drive.mirrorToCar.value = s.mirrorSimRouteToCar
                speech.setEnglishRate(s.englishRate)
                soundPlayer.enabled = s.soundEffects
            }
        }
        // 부하가 '위험'으로 올라가면 울리던 효과음도 바로 멈춘다 (급제동·내비 안내 중엔 어떤 소리도 보태지 않는다)
        scope.launch {
            coach.state.map { it.load.level }.distinctUntilChanged().collect { level ->
                if (level == LoadLevel.CRITICAL) soundPlayer.stop()
            }
        }
    }

    fun onLocationPermissionGranted() = drive.onLocationPermissionGranted()

    fun onCarPermissionGranted() = drive.refreshCarApi()

    /** 화면이 내려갈 때 — 1초 디바운스로 기다리는 저장을 바로 쓴다 */
    fun flush() {
        scope.launch { runCatching { learn.repository.flush() } }
    }

    /** 시연 전용 가상 학습 기록 (어제까지 6일 연속 · 복습 카드 · 틀린 표현). 시연 허용 빌드에서만 */
    fun seedDemoHistory() {
        if (!demoAllowed) return
        learn.repository.update { DemoLearnerSeed.seed(it, content, clock.nowMs()) }
    }

    private companion object {
        fun isDemoAllowed(context: Context): Boolean {
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val adb = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
            return debuggable || adb
        }
    }
}
