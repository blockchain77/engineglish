package com.yeopjari.app

import com.yeopjari.app.ai.AgentOrchestrator
import com.yeopjari.app.ai.cloud.CloudReasoner
import com.yeopjari.app.ai.cloud.ScriptedReasoner
import com.yeopjari.app.ai.context.AppSignals
import com.yeopjari.app.ai.context.ContextEngine
import com.yeopjari.app.ai.context.DriveContext
import com.yeopjari.app.ai.context.EnergyModel
import com.yeopjari.app.ai.context.FatigueModel
import com.yeopjari.app.ai.context.LoadLevel
import com.yeopjari.app.ai.context.LoadModel
import com.yeopjari.app.ai.context.UserProfile
import com.yeopjari.app.ai.nlu.KeywordIntentEngine
import com.yeopjari.app.ai.nlu.OnDeviceIntentEngine
import com.yeopjari.app.ai.tools.ToolHost
import com.yeopjari.app.ai.tools.ToolRegistry
import com.yeopjari.app.core.gateway.NaviGateway
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.gateway.SpeechInput
import com.yeopjari.app.core.gateway.SpeechOutput
import com.yeopjari.app.core.gateway.SttEvent
import com.yeopjari.app.core.gateway.VehicleGateway
import com.yeopjari.app.core.model.AgentCard
import com.yeopjari.app.core.model.AgentPhase
import com.yeopjari.app.core.model.AgentTurn
import com.yeopjari.app.core.model.AiTier
import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.core.model.Moment
import com.yeopjari.app.core.model.Routine
import com.yeopjari.app.core.model.Speaker
import com.yeopjari.app.core.model.Urgency
import com.yeopjari.app.platform.gleo.GleoBridge
import com.yeopjari.app.sim.DemoHistory
import com.yeopjari.app.sim.DriveSimulator
import com.yeopjari.app.sim.ScenarioId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen(val label: String) {
    HOME("홈"), AGENT("옆자리 AI"), CHARGE("충전"), CARE("케어"), SAFETY("안전"), CAR("차량"), ROUTINES("루틴"), SETTINGS("설정")
}

data class PetModeState(val active: Boolean = false, val targetC: Float = 22f, val startedClockSec: Float = 0f, val minSoc: Int = 20)
data class RestModeState(val active: Boolean = false, val minutes: Int = 15, val startedClockSec: Float = 0f)
data class KidsGameState(val active: Boolean = false, val question: Int = 1, val score: Int = 0, val lastAnswerCorrect: Boolean? = null)
data class ParkingMemo(val note: String, val clockLabel: String, val placeLabel: String)

data class AiSettings(
    val cloudEnabled: Boolean = true,
    val proactiveLevel: Int = 2,       // 1 긴급만 · 2 균형 · 3 적극
    val voiceReplies: Boolean = true,
    val shareDrivingData: Boolean = true,
)

/**
 * 앱 전역 상태 저장소 (단방향 데이터 흐름).
 * 차량 게이트웨이 → DriveContext → (ContextEngine · AgentOrchestrator) → UI
 */
class YeopjariStore(
    private val scope: CoroutineScope,
    val sim: DriveSimulator,
    override val vehicle: VehicleGateway,
    override val navi: NaviGateway,
    val speechIn: SpeechInput,
    val speechOut: SpeechOutput,
    uxRestricted: StateFlow<Boolean>,
    val sourceMode: StateFlow<Source>,
    pleosConnected: Flow<Boolean>,
    private val onSourceChange: (Source) -> Unit,
    val intentEngine: OnDeviceIntentEngine = KeywordIntentEngine(),
    val reasoner: CloudReasoner = ScriptedReasoner(),
) : ToolHost {

    val tools = ToolRegistry(this)
    private val orchestrator = AgentOrchestrator(intentEngine, reasoner, tools)
    val profile = MutableStateFlow(UserProfile())
    val pleosConnected: StateFlow<Boolean> = pleosConnected.stateIn(scope, SharingStarted.Eagerly, false)

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _turns = MutableStateFlow(
        listOf(AgentTurn(1, Speaker.AGENT, "안녕하세요, 옆자리예요. 운전대 밖의 일은 제게 맡겨 주세요. 말로 하거나 아래 제안을 눌러 보세요.", AiTier.ON_DEVICE))
    )
    val turns: StateFlow<List<AgentTurn>> = _turns.asStateFlow()

    private val _phase = MutableStateFlow(AgentPhase.IDLE)
    val phase: StateFlow<AgentPhase> = _phase.asStateFlow()
    private val _phaseDetail = MutableStateFlow<String?>(null)
    val phaseDetail: StateFlow<String?> = _phaseDetail.asStateFlow()
    private val _partial = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partial.asStateFlow()
    private val _caption = MutableStateFlow<String?>(null)
    val caption: StateFlow<String?> = _caption.asStateFlow()

    val pet = MutableStateFlow(PetModeState())
    val rest = MutableStateFlow(RestModeState())
    val kids = MutableStateFlow(KidsGameState())
    val parking = MutableStateFlow<ParkingMemo?>(null)
    val settings = MutableStateFlow(AiSettings())
    val routines = MutableStateFlow(DemoHistory.routines)
    val trips = MutableStateFlow(DemoHistory.trips)
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())
    private val spokenMoments = mutableSetOf<String>()
    val agentOverlay = MutableStateFlow(false)
    val demoPanel = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)
    val gleoCalls = MutableStateFlow(0)

    /** 실차(Pleos SDK) 모드에서는 실제 시계, 시나리오 모드에서는 시뮬레이터 시계 */
    private val realClock = kotlinx.coroutines.flow.flow {
        while (true) {
            val c = java.util.Calendar.getInstance()
            emit((c.get(java.util.Calendar.HOUR_OF_DAY) * 3600 + c.get(java.util.Calendar.MINUTE) * 60 + c.get(java.util.Calendar.SECOND)).toFloat())
            delay(10_000)
        }
    }
    private val clock: Flow<Float> = combine(sourceMode, sim.clockSec, realClock) { src, simC, real -> if (src == Source.PLEOS_SDK) real else simC }

    val context: StateFlow<DriveContext> = combine(
        vehicle.snapshot, navi.route, clock, sim.flags, profile,
    ) { v, r, clock, flags, prof ->
        DriveContext(
            vehicle = v, route = r, clockSec = clock, clockLabel = DriveSimulator.formatClock(clock), flags = flags, profile = prof,
            energy = EnergyModel.forecast(v, r),
            fatigue = FatigueModel.assess(v, r, (clock / 3600f).toInt()),
            load = LoadModel.estimate(v, r),
        )
    }.stateIn(scope, SharingStarted.Eagerly, initialContext())

    /** 운전자 주의분산 제한: AAOS CarUxRestrictions 또는 주행 상태 */
    val restricted: StateFlow<Boolean> = combine(context.map { it.vehicle.driveMode.restricted }, uxRestricted) { a, b -> a || b }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val moments: StateFlow<List<Moment>> = combine(context, pet, parking, dismissed, settings) { ctx, p, pk, d, s ->
        ContextEngine.evaluate(ctx, AppSignals(petModeActive = p.active, parkingSaved = pk != null))
            .filterNot { it.id in d }
            .filter { s.proactiveLevel >= 2 || it.urgency.weight >= Urgency.HIGH.weight }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 운전 부하가 높아 음성 안내를 보류 중인 모먼트 수 (인터럽션 매니저) */
    val deferredCount: StateFlow<Int> = combine(moments, context) { m, ctx ->
        if (ctx.load.level == LoadLevel.HIGH) m.count { it.deferrable && it.speak != null && it.id !in spokenMoments } else 0
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private var agentJob: Job? = null
    private var speakWatch: Job? = null

    init {
        vehicle.start()
        // 모먼트 음성 안내 — 운전 부하 HIGH 이면 비긴급 안내 보류, 여유 구간에서 재개
        scope.launch {
            combine(moments, context.map { it.load.level }.distinctUntilChanged(), phase) { m, load, ph -> Triple(m, load, ph) }.collect { (list, load, ph) ->
                if (ph != AgentPhase.IDLE || !settings.value.voiceReplies) return@collect
                val next = list.firstOrNull { it.speak != null && it.id !in spokenMoments && (!it.deferrable || load != LoadLevel.HIGH) }
                if (next != null) {
                    spokenMoments += next.id
                    say(next.speak!!)
                }
            }
        }
        scope.launch {
            speechIn.events.collect { e ->
                when (e) {
                    is SttEvent.Partial -> _partial.value = e.text
                    is SttEvent.Final -> { _partial.value = ""; send(e.text) }
                    is SttEvent.Error -> { _phase.value = AgentPhase.IDLE; toast.value = "음성 인식 오류: ${e.message}" }
                    SttEvent.End -> if (_phase.value == AgentPhase.LISTENING) _phase.value = AgentPhase.IDLE
                    SttEvent.Ready -> _phase.value = AgentPhase.LISTENING
                }
            }
        }
        // 펫 케어 중 공조 유지 + 배터리 하한 보호
        scope.launch {
            combine(pet, context.map { it.vehicle.socPercent.toInt() }.distinctUntilChanged()) { p, soc -> p to soc }.collect { (p, soc) ->
                if (p.active && soc <= p.minSoc) { say("배터리가 ${p.minSoc}퍼센트에 도달해 펫 케어를 안전 모드로 전환했어요."); }
                if (p.active) vehicle.setHvacTemperature(p.targetC)
            }
        }
        // 충전 목표 도달 알림 (루틴: 충전 완료 알림)
        scope.launch {
            vehicle.snapshot.map { it.isCharging && it.socPercent >= it.chargeLimitPercent - 0.1f }.distinctUntilChanged().collect { done ->
                if (done) say("충전이 ${vehicle.snapshot.value.chargeLimitPercent}퍼센트까지 완료됐어요. 출발 준비가 끝났어요.")
            }
        }
        // 기어 D 진입 시 주차 메모 초기화
        scope.launch {
            vehicle.snapshot.map { it.gear }.distinctUntilChanged().collect { g -> if (g == Gear.D) parking.value = null }
        }
    }

    private fun initialContext(): DriveContext {
        val v = vehicle.snapshot.value
        val r = navi.route.value
        return DriveContext(v, r, sim.clockSec.value, sim.clockLabel(), sim.flags.value, profile.value,
            EnergyModel.forecast(v, r), FatigueModel.assess(v, r, 9), LoadModel.estimate(v, r))
    }

    // ───────────────────────── Navigation ─────────────────────────
    fun go(s: Screen) { _screen.value = s }
    fun openAgent() { agentOverlay.value = true }
    fun closeAgent() { agentOverlay.value = false }

    // ───────────────────────── Voice / Agent ─────────────────────────
    fun startVoice() {
        agentOverlay.value = true
        speechOut.stop()
        if (speechIn.available) {
            _phase.value = AgentPhase.LISTENING
            speechIn.start()
        } else {
            _phase.value = AgentPhase.LISTENING
            toast.value = "이 환경에서는 Pleos 음성 인식을 쓸 수 없어요. 추천 문장을 누르거나 입력해 주세요."
            scope.launch { delay(1600); if (_phase.value == AgentPhase.LISTENING) _phase.value = AgentPhase.IDLE }
        }
    }

    fun stopVoice() { speechIn.stop(); if (_phase.value == AgentPhase.LISTENING) _phase.value = AgentPhase.IDLE }

    fun send(text: String) {
        if (text.isBlank()) return
        agentJob?.cancel()
        speechOut.stop()
        _turns.update { it + AgentTurn(System.nanoTime(), Speaker.USER, text) }
        agentJob = scope.launch {
            val result = orchestrator.handle(text, context.value, _turns.value, settings.value.cloudEnabled) { ph, detail ->
                _phase.value = ph; _phaseDetail.value = detail
            }
            _turns.update { it + result.turn }
            _phaseDetail.value = null
            say(result.speak)
        }
    }

    private fun say(text: String) {
        _caption.value = text
        speakWatch?.cancel()
        _phase.value = AgentPhase.SPEAKING
        if (settings.value.voiceReplies && speechOut.available) {
            speechOut.speak(text)
            speakWatch = scope.launch {
                delay(600)
                speechOut.speaking.collect { speaking -> if (!speaking && _phase.value == AgentPhase.SPEAKING) _phase.value = AgentPhase.IDLE }
            }
        } else {
            speakWatch = scope.launch { delay((1200 + text.length * 45L).coerceAtMost(6000)); if (_phase.value == AgentPhase.SPEAKING) _phase.value = AgentPhase.IDLE }
        }
        scope.launch { delay((2500 + text.length * 60L).coerceAtMost(9000)); if (_caption.value == text) _caption.value = null }
    }

    /** 카드의 확인 버튼 (경로 변경 등 사용자 확인이 필요한 툴) */
    fun confirm(actionId: String) {
        scope.launch {
            val msg = when {
                actionId.startsWith("add_waypoint:") -> tools.execute("navi_add_waypoint", mapOf("place_id" to actionId.substringAfter(":"))).fold({ it }, { it.message ?: "실패" })
                actionId == "save_routine" -> {
                    _turns.value.asReversed().firstNotNullOfOrNull { t -> t.cards.filterIsInstance<AgentCard.RoutineDraft>().firstOrNull() }?.let { addRoutine(it.routine) }
                    "루틴 저장 완료"
                }
                else -> "완료"
            }
            _turns.update { it + AgentTurn(System.nanoTime(), Speaker.SYSTEM, "✓ $msg", AiTier.ON_DEVICE) }
            toast.value = msg
            say(when {
                actionId.contains("hoengseong") -> "횡성휴게소를 경유지로 넣었어요. 18분 충전하면서 쉬어 가요."
                actionId.contains("shelter") -> "졸음쉼터를 경유지로 넣었어요. 조금만 힘내세요."
                actionId.contains("tire") -> "공기 주입소를 경유지로 넣었어요."
                actionId == "save_routine" -> "루틴을 저장했어요. 조건이 맞으면 자동으로 실행할게요."
                else -> "완료했어요."
            })
        }
    }

    /** 확인 카드의 '괜찮아요' */
    fun decline() {
        _turns.update { it + AgentTurn(System.nanoTime(), Speaker.SYSTEM, "건너뛰었어요 — 필요하면 다시 말씀해 주세요", AiTier.ON_DEVICE) }
    }

    /** 모먼트 카드 액션 */
    fun onMomentAction(moment: Moment, actionId: String) {
        when (actionId) {
            "pet_on" -> startPetMode(22f).also { dismiss(moment.id) }
            "pet_no", "later", "keep", "rear_ok", "parking_skip" -> dismiss(moment.id)
            "rear_horn" -> say("뒷좌석을 확인할 때까지 계속 알려 드릴게요.")
            "fresh_air" -> scope.launch { tools.execute("hvac_fresh_air", emptyMap()); tools.execute("hvac_set_temperature", mapOf("celsius" to "21")); say("바깥 공기를 들이고 21도로 낮췄어요.") }
            "wake_talk" -> { agentOverlay.value = true; send("졸려, 잠 좀 깨워줘") }
            "rest_stop" -> { confirm("add_waypoint:shelter"); dismiss(moment.id) }
            "rest_stop_family" -> { confirm("add_waypoint:hoengseong"); dismiss(moment.id) }
            "charge_plan" -> { agentOverlay.value = true; send("아이들이랑 강릉 가는데 충전이랑 쉬는 거 같이 해결할 수 있게 계획 세워줘") }
            "charge_options" -> go(Screen.CHARGE)
            "rest_mode" -> startRestMode(15)
            "kids_game" -> { agentOverlay.value = true; send("아이들 심심해하는데 퀴즈 해줘") }
            "diagnose_tire" -> { agentOverlay.value = true; send("타이어 공기압 경고등 왜 켜진 거야?") }
            "tire_shop" -> confirm("add_waypoint:tire")
            "reroute" -> { toast.value = "우회 경로로 변경했어요 (−6분)"; dismiss(moment.id); say("분당수서로로 우회할게요. 6분 빨라져요.") }
            "briefing" -> { agentOverlay.value = true; send("오늘 브리핑 해줘") }
            "preheat" -> scope.launch { vehicle.setHvacTemperature(profile.value.preferredTempC); say("${profile.value.preferredTempC}도로 예열을 시작했어요.") }
            "parking_voice" -> {
                // 실차: STT SDK 로 바로 받아쓰기. 시뮬레이터: 예시 발화로 대체
                if (speechIn.available) startVoice() else { agentOverlay.value = true; send("지하 3층 C구역 12번 기둥 옆에 주차했어") }
            }
        }
    }

    fun dismiss(id: String) = dismissed.update { it + id }

    fun playScenario(id: ScenarioId) {
        dismissed.value = emptySet()
        spokenMoments.clear()
        pet.value = PetModeState(); rest.value = RestModeState(); kids.value = KidsGameState(); parking.value = null
        onSourceChange(Source.SIMULATOR)
        sim.play(id)
        go(Screen.HOME)
        demoPanel.value = false
        toast.value = "시나리오: ${id.title}"
    }

    fun useSource(src: Source) = onSourceChange(src)

    // ───────────────────────── Gleo Interface ─────────────────────────
    fun logGleo(action: GleoBridge.Action) {
        gleoCalls.update { it + 1 }
        _turns.update { it + AgentTurn(System.nanoTime(), Speaker.SYSTEM, "Gleo → 옆자리 호출 · ${GleoBridge.describe(action)}", AiTier.ON_DEVICE) }
    }

    /** component = activity 액션 (PLAN_EV_TRIP · DIAGNOSE_VEHICLE) — 화면에서 처리 후 Gleo 에는 silent 로 응답 */
    fun handleGleoActivity(action: GleoBridge.Action, respond: (status: String, message: String) -> Unit) {
        logGleo(action)
        agentOverlay.value = true
        when (action.name) {
            GleoBridge.PLAN_EV_TRIP -> {
                val dest = action.params.optString("destination", "목적지")
                val who = when (action.params.optString("companions")) { "kids" -> "아이들이랑 "; "pet" -> "강아지랑 "; else -> "" }
                send("${who}${dest} 가는데 충전이랑 쉬는 곳 같이 들러서 가는 계획 세워줘")
                respond("success", "Yeopjari is planning the trip to $dest with charging and rest stops on screen.")
            }
            GleoBridge.DIAGNOSE_VEHICLE -> {
                val symptom = action.params.optString("symptom", "경고등")
                send("$symptom 이거 왜 그런 거야? 이상해")
                respond("success", "Yeopjari is explaining the vehicle symptom on screen.")
            }
        }
    }

    // ───────────────────────── ToolHost ─────────────────────────
    override fun startPetMode(tempC: Float) {
        pet.value = PetModeState(active = true, targetC = tempC, startedClockSec = context.value.clockSec)
        go(Screen.CARE)
    }
    override fun stopPetMode() { pet.value = PetModeState() }
    override fun startRestMode(minutes: Int) {
        rest.value = RestModeState(active = true, minutes = minutes, startedClockSec = context.value.clockSec)
        go(Screen.CARE)
        scope.launch { vehicle.setHvacTemperature(22f); vehicle.setFanSpeed(2) }
    }
    fun stopRestMode() { rest.value = RestModeState() }
    override fun startKidsGame() { kids.value = KidsGameState(active = true) }
    override fun addRoutine(routine: Routine) { routines.update { listOf(routine) + it } }
    override fun saveParking(note: String) {
        val clean = note.replace("주차했어", "").replace("에 ", " ").trim().ifBlank { "지하 주차장" }
        parking.value = ParkingMemo(clean, context.value.clockLabel, navi.route.value.destination?.name ?: "현재 위치")
    }

    fun answerQuiz(choice: String) {
        val correct = choice == "문어"
        kids.update { it.copy(active = true, score = it.score + if (correct) 1 else 0, lastAnswerCorrect = correct) }
        say(if (correct) "정답! 문어는 다리가 여덟 개야. 다음 문제 갈까?" else "아쉽다! 정답은 문어야. 오징어는 다리가 열 개란다.")
    }

    fun setChargeLimit(percent: Int) = scope.launch {
        vehicle.setChargeLimit(percent).onSuccess { toast.value = "충전 한도 ${percent}%로 설정 (EvBattery.setEvChargePercentLimit)" }
            .onFailure { toast.value = "충전 한도 설정 실패: ${it.message}" }
    }

    fun setTemp(c: Float) = scope.launch { vehicle.setHvacTemperature(c) }
    fun toggleRoutine(id: String) = routines.update { list -> list.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }
    fun updateSettings(f: (AiSettings) -> AiSettings) = settings.update(f)
    fun clearToast() { toast.value = null }
}
