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
import com.yeopjari.app.ai.nlu.Intent
import com.yeopjari.app.ai.nlu.KeywordIntentEngine
import com.yeopjari.app.ai.nlu.NluResult
import com.yeopjari.app.ai.nlu.OnDeviceIntentEngine
import com.yeopjari.app.ai.placeCard
import com.yeopjari.app.ai.personal.Aside
import com.yeopjari.app.ai.personal.Candidate
import com.yeopjari.app.ai.personal.Hangul
import com.yeopjari.app.ai.personal.PersonalBrain
import com.yeopjari.app.ai.personal.PersonalService
import com.yeopjari.app.ai.personal.PersonalUi
import com.yeopjari.app.ai.personal.PlaceQuery
import com.yeopjari.app.ai.personal.QueryParser
import com.yeopjari.app.ai.personal.Reply
import com.yeopjari.app.ai.personal.Resolution
import com.yeopjari.app.ai.personal.Situation
import com.yeopjari.app.ai.personal.StopMode
import com.yeopjari.app.ai.personal.Tier
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
import com.yeopjari.app.core.model.GeoPoint
import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.core.model.Moment
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.Routine
import com.yeopjari.app.core.model.Speaker
import com.yeopjari.app.core.model.ToolCall
import com.yeopjari.app.core.model.ToolStatus
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

enum class Screen(val label: String) {
    PERSONAL("나를 아는 AI"), HOME("홈"), AGENT("옆자리 AI"), CHARGE("충전"), CARE("케어"), SAFETY("안전"), CAR("차량"), ROUTINES("루틴"), SETTINGS("설정")
}

/** 되묻기 단계 — 확신도가 낮을 때만 묻는다 */
enum class ClarifyStage {
    /** "성심당 대전역점으로 갈까요?" — 응/아니야 */
    CONFIRM,
    /** HUD에 후보 최대 3곳 — 번호로 선택 */
    CHOOSE,
    /** 확신이 높아 바로 실행함 — 몇 초 동안 "아니야"로 되돌릴 수 있음 */
    UNDO,
}

/** 진행 중인 되묻기 (HUD와 화면이 함께 본다) */
data class Clarify(
    val res: Resolution,
    val stage: ClarifyStage,
    /** 지금 HUD에 보이는 후보 (CONFIRM·UNDO는 1곳, CHOOSE는 최대 3곳) */
    val options: List<Candidate>,
    val rejectedId: String? = null,
    val id: Long = System.nanoTime(),
    /** 바로 실행(UNDO) 직전의 경로 — "아니야"·"취소"로 되돌릴 때 그대로 복원한다 */
    val prevRoute: RouteState? = null,
) {
    /**
     * 번호("2번")가 가리키는 목록 — 대화 카드에 번호를 붙여 보여 준 그대로.
     * 선택(CHOOSE)은 HUD와 같은 [options], 확인·되돌리기는 카드의 상위 3곳.
     */
    val numbered: List<Candidate> get() = if (stage == ClarifyStage.CHOOSE) options else res.candidates.take(3)
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

/** 선제 안내 음성의 판단 재료 */
private data class MomentGate(
    val list: List<Moment>, val load: LoadLevel, val phase: AgentPhase, val asking: Boolean, val screen: Screen, val source: Source,
)

/** 내비 실행 결과 — 경유지로 넣었는지, 목적지로 바꿨는지, 실패했는지 */
private enum class NaviResult { DESTINATION, WAYPOINT, FAILED }

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
    /** 개인화 보정 엔진 (차 안 암호화 저장소와 연결) */
    val personal: PersonalService,
    /** 현재 위치 — Pleos 모드는 FusedLocation SDK, 시나리오 모드는 시뮬레이터 */
    val location: StateFlow<GeoPoint?>,
    val intentEngine: OnDeviceIntentEngine = KeywordIntentEngine(),
    val reasoner: CloudReasoner = ScriptedReasoner(),
) : ToolHost {

    val tools = ToolRegistry(this)
    private val orchestrator = AgentOrchestrator(intentEngine, reasoner, tools) { q -> resolveForDialogue(q) }
    val profile = MutableStateFlow(UserProfile())
    val pleosConnected: StateFlow<Boolean> = pleosConnected.stateIn(scope, SharingStarted.Eagerly, false)

    /** 개인화 엔진 상태 (가중치·기억한 표현·학습 기록 사슬·전송 미리보기) */
    val personalUi: StateFlow<PersonalUi> get() = personal.ui
    /** 진행 중인 되묻기 — HUD 시뮬레이터와 화면이 함께 본다 */
    val clarify = MutableStateFlow<Clarify?>(null)
    /** HUD에 잠깐 띄우는 알림 (예: "기억했어요 · ‘서심다응’ = 성심당") */
    val hudFlash = MutableStateFlow<String?>(null)
    private var flashJob: Job? = null

    /**
     * 바로 실행(UNDO)한 결과. HUD에서 내려간 뒤에도 [LATE_UNDO_MS] 동안 남겨 늦게 온 "아니야"도 되돌리고,
     * 그 창이 정말 닫힐 때(또는 "응"·다른 명령으로 받아들였을 때) 비로소 학습한다.
     */
    private var pendingAuto: Clarify? = null
    private var autoJob: Job? = null
    /** 확인·선택 질문의 시간 제한 + 자동 듣기 */
    private var questionJob: Job? = null
    /** 자동으로 마이크를 연 되묻기 id와 횟수 (질문 한 번 + 다시 묻기 한 번까지) */
    private var listenFor = 0L
    private var listenCount = 0
    /** 머뭇거림("어…") 뒤 마이크를 한 번 더 연 되묻기 id — 질문의 자동 듣기 횟수와 따로 센다 */
    private var hesitateFor = 0L
    private var relistenJob: Job? = null
    /**
     * 대화 하나 — 운전자가 먼저 꺼낸 말(버튼·직접 연 마이크)에서 시작하고, 자동 듣기로 이어진 대답은 같은 대화다.
     * "장소 이름을 한 번 더 말씀해 주세요" 뒤의 자동 듣기는 대화마다 한 번만 (두 번째 '못 찾음'에서는 다시 열지 않는다).
     */
    private var dialogueId = 0L
    private var resayListened = -1L
    private var resayJob: Job? = null
    /** 되묻기 뒤 자동으로 연 음성 인식 — 말이 없어 끝나도 오류 알림을 띄우지 않는다 */
    private var autoListening = false
    /** 다른 입력(버튼·추천 문장·시나리오 전환)이 먼저 와서 마이크를 닫았으면, 그 뒤 늦게 오는 인식 결과는 버린다 */
    private var discardStt = false
    /** 음성 인식 세션이 열려 있는지 (시작 ~ 끝·오류 알림) — 머뭇거림 뒤 다시 열 때 앞 세션이 끝나길 기다린다 */
    private val sttOpen = MutableStateFlow(false)
    /** 듣기 감시 — STT가 끝·오류 알림을 주지 않아도 LISTENING에 갇히지 않게 (갇히면 되묻기 시간 제한·선제 안내가 모두 멈춘다) */
    private var listenWatch: Job? = null
    /** 대화 정책으로 바꾼 보정 결과의 설명 (결과 id → 추론 한 줄) */
    private var policyNote: Pair<Long, String>? = null

    private val _screen = MutableStateFlow(Screen.PERSONAL)
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
        // 모먼트 음성 안내 — 긴급(HIGH 이상)·미룰 수 없는 안내는 되묻는 중이든 어느 화면이든 바로 말한다.
        // 비긴급만 보류: 운전 부하 HIGH(여유 구간에서 재개) · 되묻는 중 · 시뮬레이터 시연 중의 개인화 화면(대화가 끊기지 않게).
        // 듣는 중에는 기다리되, 미룰 수 없는 위급 안내(뒷좌석 아이 등)는 마이크를 닫고 먼저 말한다.
        scope.launch {
            combine(
                moments, context.map { it.load.level }.distinctUntilChanged(), phase,
                clarify.map { it != null }.distinctUntilChanged(), combine(_screen, sourceMode) { s, src -> s to src },
            ) { m, load, ph, asking, where -> MomentGate(m, load, ph, asking, where.first, where.second) }.collect { g ->
                if (!settings.value.voiceReplies) return@collect
                val fresh = g.list.filter { it.speak != null && it.id !in spokenMoments }
                val next = when (g.phase) {
                    AgentPhase.IDLE -> fresh.firstOrNull { mayInterrupt(it, g) }
                    AgentPhase.LISTENING -> fresh.firstOrNull { it.urgency == Urgency.CRITICAL && !it.deferrable }?.also { closeMic() }
                    else -> null
                } ?: return@collect
                spokenMoments += next.id
                say(next.speak!!)
            }
        }
        scope.launch {
            speechIn.events.collect { e ->
                when (e) {
                    is SttEvent.Partial -> _partial.value = e.text
                    is SttEvent.Final -> {
                        _partial.value = ""
                        val auto = autoListening
                        autoListening = false
                        listenWatch?.cancel()
                        if (!discardStt) {
                            // 인식이 끝났으니 '듣는 중'을 내린다 — IDLE이 아니라 처리 중으로 (그 틈에 선제 안내가 끼어들지 않게)
                            if (_phase.value == AgentPhase.LISTENING) _phase.value = AgentPhase.ROUTING
                            onUtterance(e.text, fromAutoListen = auto)
                            // 대답으로도 명령으로도 이어지지 않았으면(머뭇거림·빈 말) 대기로
                            if (_phase.value == AgentPhase.ROUTING && agentJob?.isActive != true) _phase.value = AgentPhase.IDLE
                        }
                    }
                    is SttEvent.Error -> {
                        listenWatch?.cancel()
                        sttOpen.value = false
                        // 이미 다음 단계(말하기·처리)로 넘어간 뒤 늦게 온 오류는 상태를 건드리지 않는다
                        val wasListening = _phase.value == AgentPhase.LISTENING
                        if (wasListening) _phase.value = AgentPhase.IDLE
                        if (wasListening && !autoListening) toast.value = "음성 인식 오류: ${e.message}"
                        autoListening = false
                    }
                    SttEvent.End -> {
                        listenWatch?.cancel()
                        sttOpen.value = false
                        autoListening = false
                        if (_phase.value == AgentPhase.LISTENING) { _phase.value = AgentPhase.IDLE; _partial.value = "" }
                    }
                    // 감시가 이미 닫은 세션의 늦은 '준비' 알림으로 다시 LISTENING에 갇히지 않게
                    SttEvent.Ready -> if (sttOpen.value && (_phase.value == AgentPhase.IDLE || _phase.value == AgentPhase.LISTENING)) _phase.value = AgentPhase.LISTENING
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

    /** 이 모먼트를 지금 음성으로 끼어들어 말해도 되는지 */
    private fun mayInterrupt(m: Moment, g: MomentGate): Boolean = MomentPolicy.mayInterrupt(m, g.load, g.asking, g.screen, g.source)

    // ───────────────────────── Navigation ─────────────────────────
    fun go(s: Screen) { _screen.value = s }
    fun openAgent() { agentOverlay.value = true }
    fun closeAgent() { agentOverlay.value = false }

    // ───────────────────────── Voice / Agent ─────────────────────────
    /** @param openPanel false면 대화 패널을 열지 않고(HUD 화면 유지) 듣기만 한다 */
    fun startVoice(openPanel: Boolean = true) = startListening(openPanel, auto = false)

    private fun startListening(openPanel: Boolean, auto: Boolean) {
        if (openPanel) agentOverlay.value = true
        speechOut.stop()
        if (speechIn.available) {
            autoListening = auto
            discardStt = false
            sttOpen.value = true
            _phase.value = AgentPhase.LISTENING
            speechIn.start()
            watchListening(if (auto) AUTO_LISTEN_LIMIT_MS else MANUAL_LISTEN_LIMIT_MS)
        } else {
            _phase.value = AgentPhase.LISTENING
            toast.value = "이 환경에서는 Pleos 음성 인식을 쓸 수 없어요. 추천 문장을 누르거나 입력해 주세요."
            scope.launch { delay(1600); if (_phase.value == AgentPhase.LISTENING) _phase.value = AgentPhase.IDLE }
        }
    }

    /**
     * 듣기 감시 — STT 세션이 끝·오류 알림 없이 멈춰도(SDK 문제, 마이크 입력 없는 에뮬레이터) [ms] 뒤에는 마이크를 닫고 대기로.
     * 그대로 두면 LISTENING에 갇혀 되묻기 시간 제한도, 위급 안내도 멈춘다. 말하는 중(부분 인식이 있음)이면 한 번 조금 더 기다린다.
     */
    private fun watchListening(ms: Long) {
        listenWatch?.cancel()
        listenWatch = scope.launch {
            delay(ms)
            if (_phase.value == AgentPhase.LISTENING && _partial.value.isNotBlank()) delay(LISTEN_GRACE_MS)
            if (_phase.value == AgentPhase.LISTENING) {
                stopVoice()
                _partial.value = ""
            }
        }
    }

    fun stopVoice() {
        listenWatch?.cancel()
        autoListening = false
        sttOpen.value = false
        speechIn.stop()
        if (_phase.value == AgentPhase.LISTENING) _phase.value = AgentPhase.IDLE
    }

    /** 다른 입력이 먼저 와서 듣기를 끝낸다 — 그 뒤 늦게 오는 인식 결과는 버린다 */
    private fun closeMic() {
        listenWatch?.cancel()
        autoListening = false
        discardStt = true
        sttOpen.value = false
        _partial.value = ""
        speechIn.stop()
    }

    /** 화면 버튼·추천 문장·입력창·시연 자동화(hear)에서 들어온 문장 — 열려 있는 마이크는 닫고 처리한다 */
    fun send(text: String) {
        if (text.isBlank()) return
        if (_phase.value == AgentPhase.LISTENING) {
            closeMic()
            // IDLE로 내리면 그 틈에 선제 안내가 끼어들 수 있어, 처리 중 상태로 넘긴다 (이어서 말하기/분석이 덮어씀)
            _phase.value = AgentPhase.ROUTING
        }
        onUtterance(text)
    }

    /**
     * 사용자 발화 하나 — 되묻는 중이면 먼저 대답(응·아니야·2번·"본점")으로 해석.
     * @param fromAutoListen 앱이 자동으로 연 마이크에서 온 말 (같은 대화의 이어지는 대답)
     */
    private fun onUtterance(text: String, fromAutoListen: Boolean = false) {
        if (text.isBlank()) return
        if (!fromAutoListen) dialogueId++
        resayJob?.cancel()
        relistenJob?.cancel()
        val pending = clarify.value ?: pendingAuto
        if (pending != null) handleReply(text, pending) else startCommand(text)
    }

    /**
     * 새 명령 실행 (의도 분석 → 엣지/클라우드).
     * @param shown 대화에 남길 사용자 말 (null이면 남기지 않음) — "아니, 집으로 가자"는 말 전체를 남기고 '집으로 가자'만 실행
     * @param before 의도 분석 전에 할 일 (예: 바로 실행했던 경로 되돌리기)
     */
    private fun startCommand(text: String, shown: String? = text, before: (suspend () -> Unit)? = null) {
        agentJob?.cancel()
        resayJob?.cancel()
        speechOut.stop()
        if (shown != null) _turns.update { it + AgentTurn(System.nanoTime(), Speaker.USER, shown) }
        agentJob = scope.launch {
            if (before != null) {
                _phase.value = AgentPhase.ROUTING
                before()
            }
            val result = orchestrator.handle(text, context.value, _turns.value, settings.value.cloudEnabled) { ph, detail ->
                _phase.value = ph; _phaseDetail.value = detail
            }
            _phaseDetail.value = null
            val res = result.resolution
            if (res == null) {
                _turns.update { it + result.turn }
                say(result.speak)
                return@launch
            }
            // 장소 명령: 대화 정책(경유지 확인·실패 안내)을 반영한 문장으로 말한다
            val line = onResolved(res)
            val note = policyNote?.takeIf { it.first == res.id }?.second
            _turns.update { it + result.turn.copy(text = line, reasoning = result.turn.reasoning + listOfNotNull(note)) }
            say(line)
            watchClarify(res)
        }
    }

    // ───────────────────────── 개인화 보정 · 되묻기 ─────────────────────────

    /** 지금 상황 — 날짜·요일·시각·위치·안내 중 목적지 */
    fun situation(): Situation {
        val ctx = context.value
        val day = if (sourceMode.value == Source.PLEOS_SDK) java.time.LocalDate.now().toEpochDay().toInt() else sim.epochDay.value
        val dow = java.time.LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value
        val loc = location.value
        val dest = ctx.route.destination?.takeIf { ctx.route.active }
        return Situation(day, dow, (ctx.clockSec / 60f).toInt().coerceIn(0, 1439), loc?.lat, loc?.lng, dest?.lat, dest?.lng)
    }

    /**
     * 개인화 보정 + 대화 정책.
     *  - 안내 중인 경로가 없으면 "들렀다 가자"도 목적지로 안내한다 (들를 경로가 없다)
     *  - 경유지는 내비에 지우는 기능(removeWaypoint)이 없어 되돌릴 수 없으므로 바로 실행하지 않고 한 번 확인한다
     */
    private fun resolveForDialogue(q: PlaceQuery): Resolution {
        var note: String? = null
        val query = if (q.mode == StopMode.WAYPOINT && !navi.route.value.active) {
            note = "안내 중인 경로가 없어 경유지 대신 목적지로 안내"
            q.copy(mode = StopMode.DESTINATION)
        } else q
        var res = personal.resolve(query, situation())
        if (res.tier == Tier.AUTO && query.mode == StopMode.WAYPOINT) {
            res = res.copy(tier = Tier.CONFIRM)
            note = "경유지 추가는 되돌릴 수 없어 바로 실행 대신 한 번 확인"
        }
        policyNote = note?.let { res.id to it }
        return res
    }

    /** 보정 결과에 맞는 첫 문장 (바로 실행 · 확인 질문 · 선택지 · 못 찾음) */
    private fun placeLine(res: Resolution): String {
        val q = res.query
        val top = res.top ?: return "‘${q.text}’${Hangul.eunNeun(q.text)} 제 기록에서 찾지 못했어요. 장소 이름을 한 번 더 말씀해 주시겠어요?"
        val name = top.poi.spoken
        val waypoint = q.mode == StopMode.WAYPOINT
        return when (res.tier) {
            // "늘 가시던"은 정말 단골일 때만 (90일 4회 이상 또는 이 말로 고른 적 있음)
            Tier.AUTO -> (if (top.habitual) "늘 가시던 " else "") + if (waypoint) "${name}에 들렀다 갈게요." else "$name${Hangul.ro(name)} 안내할게요."
            Tier.CONFIRM -> if (waypoint) "${name}에 들를까요?" else "$name${Hangul.ro(name)} 갈까요?"
            else -> "어느 곳인지 헷갈려요. 화면에 ${res.candidates.size.coerceAtMost(3)}곳을 띄웠어요. 번호로 말씀해 주세요."
        }
    }

    /** HUD에 띄운 질문 그대로 (Gleo 콜백의 asking 메시지) */
    private fun questionText(c: Clarify): String {
        val waypoint = c.res.query.mode == StopMode.WAYPOINT
        return when (c.stage) {
            ClarifyStage.CHOOSE -> (if (waypoint) "어디에 들를까요? " else "어디로 갈까요? ") +
                c.options.mapIndexed { i, o -> "${i + 1} ${o.poi.spoken}" }.joinToString(" · ")
            else -> {
                val n = c.options.first().poi.spoken
                if (waypoint) "${n}에 들를까요?" else "$n${Hangul.ro(n)} 갈까요?"
            }
        }
    }

    /** 보정 결과 → 되묻기 상태 (바로 실행이면 내비까지). @return 말할 문장 */
    private suspend fun onResolved(res: Resolution): String {
        questionJob?.cancel()
        val top = res.top
        if (top == null || res.tier == Tier.NONE) { clarify.value = null; return placeLine(res) }
        return when (res.tier) {
            Tier.AUTO -> {
                // 되돌릴 수 있게 실행 전 경로를 남겨 둔다
                val prev = navi.route.value
                if (navigate(top, res.query.mode) == NaviResult.FAILED) {
                    clarify.value = null
                    NAVI_FAIL
                } else {
                    // 앞의 바로 실행이 아직 열려 있으면 목적지가 바뀐 것 → 학습 없이 닫는다
                    pendingAuto?.let { closeAuto(it, learn = false) }
                    val c = Clarify(res, ClarifyStage.UNDO, listOf(top), prevRoute = prev)
                    pendingAuto = c
                    clarify.value = c
                    placeLine(res)
                }
            }
            Tier.CONFIRM -> { clarify.value = Clarify(res, ClarifyStage.CONFIRM, listOf(top)); placeLine(res) }
            else -> { clarify.value = Clarify(res, ClarifyStage.CHOOSE, res.candidates.take(3)); placeLine(res) }
        }
    }

    /**
     * 첫 문장을 말한 뒤 — 되돌리기 창 또는 질문 타이머·자동 듣기 시작.
     * 되묻기가 없으면(못 찾음 · 내비 실패) "다시 말씀해 주세요"를 했으니 마이크를 한 번 연다.
     */
    private fun watchClarify(res: Resolution) {
        val c = clarify.value?.takeIf { it.res.id == res.id }
        when {
            c == null -> watchResay()
            c.stage == ClarifyStage.UNDO -> watchUndo(c)
            else -> watchQuestion(c)
        }
    }

    /**
     * "장소 이름을 한 번 더 말씀해 주세요"류 안내 뒤 — 다 말하면 마이크를 자동으로 한 번 연다 (운전 중 화면을 보고 누르지 않게).
     * 대화마다 한 번만: 다시 말한 장소도 못 찾으면 더 열지 않는다.
     */
    private fun watchResay() {
        if (!speechIn.available || resayListened == dialogueId) return
        resayListened = dialogueId
        resayJob?.cancel()
        resayJob = scope.launch {
            awaitSpeechDone()
            if (clarify.value == null && _phase.value == AgentPhase.IDLE) startListening(openPanel = false, auto = true)
        }
    }

    /**
     * 되돌리기 창: 안내 문장을 다 말한 뒤 [UNDO_WINDOW_MS] (말하는·듣는·처리 중에는 멈춤) 동안 HUD에 둔다.
     * 그 뒤에도 [LATE_UNDO_MS] 동안은 늦게 온 "아니야"를 받고, 그때까지 없으면 맞게 고른 것으로 보고 조용히 학습한다.
     */
    private fun watchUndo(c: Clarify) {
        autoJob?.cancel()
        autoJob = scope.launch {
            awaitSpeechDone()
            idleDelay(UNDO_WINDOW_MS)
            if (clarify.value?.id == c.id) clarify.value = null
            idleDelay(LATE_UNDO_MS)
            endAuto(c, learn = true)
        }
    }

    /** 확인·선택 질문: 다 말한 뒤 마이크를 한 번 자동으로 열고, 대답 없이 [CLARIFY_TIMEOUT_MS] 지나면 조용히 내린다 */
    private fun watchQuestion(c: Clarify) {
        questionJob?.cancel()
        questionJob = scope.launch {
            awaitSpeechDone()
            if (clarify.value?.id != c.id) return@launch
            autoListen(c)
            idleDelay(CLARIFY_TIMEOUT_MS)
            if (clarify.value?.id == c.id) clarify.value = null
        }
    }

    /** 운전자가 화면을 보지 않고 바로 대답할 수 있게 — 되묻기마다 최대 [MAX_AUTO_LISTEN]번 (STT는 한 번 듣고 끝난다) */
    private fun autoListen(c: Clarify) {
        if (!speechIn.available || _phase.value != AgentPhase.IDLE) return
        if (listenFor != c.id) { listenFor = c.id; listenCount = 0 }
        if (listenCount >= MAX_AUTO_LISTEN) return
        listenCount++
        startListening(openPanel = false, auto = true)
    }

    /** 방금 시작한 말(사이에 끼어든 안내 음성 포함)이 끝날 때까지 */
    private suspend fun awaitSpeechDone() {
        while (true) {
            phase.first { it != AgentPhase.SPEAKING }
            delay(300)
            if (_phase.value != AgentPhase.SPEAKING) return
        }
    }

    /** 대기(IDLE) 상태인 시간만 센다 — 말하는·듣는·처리 중에는 멈춘다 */
    private suspend fun idleDelay(ms: Long) {
        var left = ms
        while (left > 0) {
            phase.first { it == AgentPhase.IDLE }
            val step = minOf(250L, left)
            delay(step)
            if (_phase.value == AgentPhase.IDLE) left -= step
        }
    }

    private fun clearClarify() {
        questionJob?.cancel()
        clarify.value = null
    }

    /**
     * 바로 실행 결과를 끝낸다 — [learn]이면 맞게 고른 것으로 보고 약하게 학습.
     * 단, 그사이 내비 화면·다른 명령으로 목적지를 바꿨거나 안내를 끝냈으면 받아들인 게 아니다 → 학습하지 않는다.
     */
    private fun endAuto(c: Clarify, learn: Boolean) {
        if (pendingAuto?.id != c.id) return
        pendingAuto = null
        if (clarify.value?.id == c.id) clarify.value = null
        if (learn && stillGuidingTo(c)) personal.learn(c.res, c.options.first().poi.place.id, lr = 0.3)
    }

    /** 바로 실행한 곳으로 아직 안내 중인지 (실차 내비는 장소 id를 바꿔 알려 주므로 이름·좌표로도 맞춰 본다) */
    private fun stillGuidingTo(c: Clarify): Boolean {
        val p = c.options.first().poi.place
        val r = navi.route.value
        return r.active && (listOfNotNull(r.destination) + r.waypoints).any { samePlace(it, p) }
    }

    private fun samePlace(a: Place, b: Place): Boolean =
        a.id == b.id || QueryParser.norm(a.name) == QueryParser.norm(b.name) ||
            ((a.lat != 0.0 || a.lng != 0.0) && PersonalBrain.haversineKm(a.lat, a.lng, b.lat, b.lng) < SAME_PLACE_KM)

    /** 타이머를 멈추고 지금 끝낸다 */
    private fun closeAuto(c: Clarify, learn: Boolean) {
        if (pendingAuto?.id != c.id) return
        autoJob?.cancel()
        endAuto(c, learn)
    }

    /**
     * 되묻는 중(또는 바로 실행 직후)의 말 — 대답·머뭇거림·다시 묻기·새 명령을 가려 처리한다 ([DialoguePolicy]).
     * 대답이 아닌 말이 질문을 지우는 것은 '진짜 새 명령'일 때뿐이다 ("어…", "잠깐만", 동승자 말은 질문을 지킨다).
     */
    private fun handleReply(text: String, c: Clarify) {
        // HUD에서 내려간 바로 실행(늦은 되돌리기 창)은 되돌리는 말·다른 후보 이름만 받는다
        val late = c.stage == ClarifyStage.UNDO && clarify.value?.id != c.id
        val classify: (String) -> NluResult = { intentEngine.classify(it, context.value) }
        when (val a = DialoguePolicy.decide(text, c, late, classify)) {
            is ReplyAction.Answer -> { userTurn(text); answer(a.reply, c, a.mode) }
            is ReplyAction.Pick -> { userTurn(text); choose(c, a.cand, a.mode ?: c.res.query.mode) }
            ReplyAction.Repeat -> { userTurn(text); reprompt(c, repeatLine(c)) }
            ReplyAction.AskNumber -> { userTurn(text); reprompt(c, "번호로 말씀해 주세요.") }
            ReplyAction.Hesitate -> { userTurn(text); keepWaiting(c, relisten = true) }
            ReplyAction.Ignore -> { userTurn(text); keepWaiting(c, relisten = false) }
            // "아니, 집으로 가자" — 제안은 거절(바로 실행이면 경로도 되돌림)하고 뒤의 명령을 실행. 학습은 거절 기록뿐
            is ReplyAction.RejectThen -> {
                val prev = decline(c)
                startCommand(a.command, shown = text, before = prev?.let { p -> suspend { restoreRoute(p) } })
            }
            is ReplyAction.NewCommand -> {
                // 늦은 창의 알아듣지 못한 말 — 바로 실행한 곳을 고쳐 말하려던 것일 수 있다. 뒤이은 대답(say)이 받아들인 것으로 세지 않게
                // 학습 없이 닫는다 (되돌리지도 않는다)
                if (late && DialoguePolicy.unclearLate(text, classify)) closeAuto(c, learn = false)
                val prev = supersede(c, a.destChange)
                startCommand(text, before = prev?.let { p -> suspend { restoreRoute(p) } })
            }
        }
    }

    /**
     * 되묻는 중에 새 명령이 들어옴 — 진행 중인 되묻기를 정리.
     *  - 확인·선택: 질문을 내린다
     *  - 바로 실행: 목적지를 바꾸는 명령(새 장소·집·회사)이면 학습 없이 닫는다 (화면에 떠 있던 중이면 경로도 되돌린 뒤 새 명령).
     *    그 밖의 명령(공조·경유지 추가 등)은 받아들였는지 아직 모르니 창을 그대로 둔다 — 끝날 때 경로를 보고 학습한다.
     * @return 새 명령 전에 되돌릴 경로
     */
    private fun supersede(c: Clarify, destChange: Boolean): RouteState? {
        if (c.stage != ClarifyStage.UNDO) {
            clearClarify()
            return null
        }
        if (!destChange) return null
        val visible = clarify.value?.id == c.id
        closeAuto(c, learn = false)
        return if (visible) c.prevRoute else null
    }

    /** @param mode 대답이 바꾼 들르는 방식 (목적지를 묻는데 "응 들르자" → 경유지). null이면 질문 그대로 */
    private fun answer(reply: Reply, c: Clarify, mode: StopMode? = null) {
        val how = mode ?: c.res.query.mode
        when (reply) {
            Reply.Yes -> when (c.stage) {
                // 목적지로 바로 실행한 곳에 "들렀다 가자" → 원래 경로로 되돌리고 그곳을 경유지로
                ClarifyStage.UNDO -> if (mode != null && mode != c.res.query.mode) choose(c, c.options.first(), mode)
                else { closeAuto(c, learn = true); agentSay("네, 그대로 안내할게요.") }
                ClarifyStage.CONFIRM -> choose(c, c.options.first(), how)
                // 선택지에서 "응"은 어느 곳인지 알 수 없다
                ClarifyStage.CHOOSE -> reprompt(c, "번호로 말씀해 주세요.")
            }
            is Reply.Pick -> {
                val list = c.numbered
                val opt = list.getOrNull(reply.index)
                if (opt == null) reprompt(c, "${list.size}번까지 있어요. 다시 말씀해 주세요.") else choose(c, opt, how)
            }
            Reply.No -> reject(c)
            is Reply.NoAnd -> when (val r = QueryParser.reply(reply.rest)) {
                // "아니, 2번" · "아니 취소" · "아니 맞아"
                Reply.Yes, Reply.Cancel, is Reply.Pick -> answer(r, c, mode)
                else -> {
                    val declined = if (c.stage == ClarifyStage.CHOOSE) c.rejectedId else c.options.firstOrNull()?.poi?.place?.id
                    val pool = c.res.candidates.filter { it.poi.place.id != declined }
                    val q = QueryParser.parse(reply.rest)
                    val m = QueryParser.matchOption(q?.text ?: reply.rest, pool)
                    when {
                        m != null -> choose(c, m, how)
                        // "아니, 스타벅스 가자" — 후보에 없는 새 장소: 제안은 거절하고 그 장소로 새로 보정
                        q != null -> {
                            val prev = decline(c)
                            runPlaceQuery(q, "‘아니’ 뒤 새 장소 ‘${q.text}’ → 개인화 보정", before = { if (prev != null) restoreRoute(prev) })
                        }
                        else -> reject(c)
                    }
                }
            }
            Reply.Cancel -> cancel(c)
            else -> Unit
        }
    }

    /** 같은 되묻기를 유지한 채 다시 묻기 (질문 타이머·자동 듣기 다시 시작) */
    private fun reprompt(c: Clarify, line: String) {
        agentSay(line)
        if (c.stage != ClarifyStage.UNDO && clarify.value?.id == c.id) watchQuestion(c)
    }

    /** "뭐라고?"에 다시 하는 말 — 질문은 그대로, 바로 실행이면 지금 어디로 가는지 */
    private fun repeatLine(c: Clarify): String {
        if (c.stage != ClarifyStage.UNDO) return questionText(c)
        val n = c.options.first().poi.spoken
        return if (c.res.query.mode == StopMode.WAYPOINT) "${n}에 들렀다 가요." else "$n${Hangul.ro(n)} 안내하고 있어요."
    }

    /**
     * 대답도 명령도 아닌 말("어…", "잠깐만", 동승자 말) — 질문은 그대로 둔다.
     * 머뭇거림이면 질문마다 한 번 마이크를 다시 연다 (질문의 자동 듣기 횟수와 따로 센다). 앞 인식 세션이 끝난 뒤에 연다.
     */
    private fun keepWaiting(c: Clarify, relisten: Boolean) {
        if (_phase.value == AgentPhase.ROUTING && agentJob?.isActive != true) _phase.value = AgentPhase.IDLE
        if (!relisten || c.stage == ClarifyStage.UNDO || clarify.value?.id != c.id || hesitateFor == c.id || !speechIn.available) return
        hesitateFor = c.id
        relistenJob?.cancel()
        relistenJob = scope.launch {
            withTimeoutOrNull(STT_END_WAIT_MS) { sttOpen.first { !it } }
            delay(300)
            if (clarify.value?.id == c.id && _phase.value == AgentPhase.IDLE) startListening(openPanel = false, auto = true)
        }
    }

    private fun cancel(c: Clarify) {
        if (c.stage == ClarifyStage.UNDO) {
            closeAuto(c, learn = false)
            val prev = c.prevRoute
            scope.launch { restoreRoute(prev) }
            agentSay(if (prev?.active == true && prev.destination != null) "알겠어요, 원래 경로로 되돌렸어요." else "알겠어요, 안내를 취소했어요.")
        } else {
            clearClarify()
            agentSay("알겠어요, 취소했어요.")
        }
    }

    /** 제안을 거절만 하고 정리 — 처음 제안한 곳은 이 말의 선택 기억에서 덜어 낸다. @return 되돌릴 경로 (바로 실행이었을 때) */
    private fun decline(c: Clarify): RouteState? {
        val proposed = c.options.firstOrNull()?.poi?.place?.id
        if (c.stage != ClarifyStage.CHOOSE && proposed != null) personal.forget(c.res, proposed)
        return if (c.stage == ClarifyStage.UNDO) { closeAuto(c, learn = false); c.prevRoute } else { clearClarify(); null }
    }

    /** 후보 화면(대화 카드·HUD 버튼)에서 직접 고를 때 — 지금 진행 중인 되묻기의 후보만 받는다 */
    fun pickPlace(placeId: String, resolutionId: Long? = null) {
        val c = clarify.value ?: return
        if (resolutionId != null && resolutionId != c.res.id) return
        val cand = c.res.candidates.firstOrNull { it.poi.place.id == placeId } ?: return
        if (_phase.value == AgentPhase.LISTENING) { closeMic(); _phase.value = AgentPhase.ROUTING }
        choose(c, cand)
    }

    /** 확인·되돌리기 단계에서 "아니야" → (바로 실행했던 경로를 되돌리고) 나머지 후보를 HUD 선택지로 */
    private fun reject(c: Clarify) {
        if (c.stage == ClarifyStage.CHOOSE) {
            clearClarify()
            agentSay("알겠어요. 장소 이름을 한 번 더 말씀해 주세요.")
            watchResay()
            return
        }
        val rejected = c.options.first().poi.place.id
        if (c.stage == ClarifyStage.UNDO) {
            closeAuto(c, learn = false)
            val prev = c.prevRoute
            scope.launch { restoreRoute(prev) }
        }
        // 거절도 학습 신호 — 같은 말에 같은 곳을 다시 1순위로 밀지 않게
        personal.forget(c.res, rejected)
        val rest = c.res.candidates.filter { it.poi.place.id != rejected }.take(3)
        if (rest.isEmpty()) {
            clearClarify()
            agentSay("다른 후보가 없어요. 장소 이름을 한 번 더 말씀해 주세요.")
            watchResay()
            return
        }
        val next = Clarify(c.res, ClarifyStage.CHOOSE, rest, rejectedId = rejected)
        val text = "그럼 이 중에 있나요? 화면에 ${rest.size}곳을 띄웠어요. 번호로 말씀해 주세요."
        _turns.update { it + AgentTurn(System.nanoTime(), Speaker.AGENT, text, AiTier.ON_DEVICE, cards = listOf(placeCard(c.res, rest, "선택지 제시"))) }
        clarify.value = next
        say(text)
        watchQuestion(next)
    }

    /**
     * 후보 확정 → 내비 실행 + 차 안 학습 + HUD 알림 (내비가 실패하면 학습하지 않는다).
     * @param mode 들르는 방식 — 대답이 바꿀 수 있다 (목적지를 묻는데 "들렀다 가자" → 경유지: 지금 경로는 그대로 두고 들른다)
     */
    private fun choose(c: Clarify, cand: Candidate, mode: StopMode = c.res.query.mode) {
        val proposed = c.options.firstOrNull()?.poi?.place?.id
        // 목적지로 바로 실행한 결과를 경유지로 바꿀 때는 실행 전 경로로 되돌린 뒤 들른다. 되돌릴 경로가 없었으면 들를 경로가 없다 → 목적지 그대로
        val toStop = c.stage == ClarifyStage.UNDO && mode == StopMode.WAYPOINT && c.res.query.mode != StopMode.WAYPOINT
        val restore = if (toStop && c.prevRoute?.active == true) c.prevRoute else null
        val how = if (toStop && restore == null) c.res.query.mode else mode
        // 이미 그곳으로 같은 방식으로 안내 중일 때만 내비를 다시 부르지 않는다
        val alreadyRunning = c.stage == ClarifyStage.UNDO && proposed == cand.poi.place.id && how == c.res.query.mode
        // 바로 실행 결과는 여기서 한 번만 학습한다 (타이머 학습은 취소)
        if (c.stage == ClarifyStage.UNDO) closeAuto(c, learn = false)
        if (clarify.value?.id == c.id) clearClarify()
        agentJob = scope.launch {
            // 실행 전 경로로 되돌리지 못했으면 바로 실행한 경로에 경유지를 얹지 않는다 (아래 내비 실패와 같게 학습 없이 다시 묻기)
            val r = if (restore != null && !restoreRoute(restore)) NaviResult.FAILED
            else if (alreadyRunning) NaviResult.DESTINATION else navigate(cand, how)
            if (r == NaviResult.FAILED) {
                agentSay(NAVI_FAIL)
                watchResay()
                return@launch
            }
            // 처음 제안한 곳을 두고 다른 곳을 골랐으면, 그 곳은 이 말의 선택 기억에서 덜어 낸다
            if (c.stage != ClarifyStage.CHOOSE && proposed != null && proposed != cand.poi.place.id) personal.forget(c.res, proposed)
            val outcome = personal.learn(c.res, cand.poi.place.id)
            val name = cand.poi.spoken
            val asWaypoint = r == NaviResult.WAYPOINT
            val msg = buildString {
                append(if (asWaypoint) "${name}에 들렀다 갈게요." else "$name${Hangul.ro(name)} 안내할게요.")
                outcome?.alias?.let { append(" 다음부터는 ‘${it.first}’도 ${it.second}${Hangul.ro(it.second)} 알아들을게요.") }
            }
            flash(outcome?.alias?.let { "기억했어요 · ‘${it.first}’ = ${it.second}" } ?: "학습했어요 · ${name}")
            _turns.update { it + AgentTurn(System.nanoTime(), Speaker.SYSTEM, "✓ $name · ${if (asWaypoint) "경유지 추가" else "길 안내 시작"} · 개인 가중치 갱신 (차 안)", AiTier.ON_DEVICE) }
            say(msg)
        }
    }

    /** 경유지는 안내 중일 때만 — 경로가 없으면 목적지로 안내한다 */
    private suspend fun navigate(cand: Candidate, mode: StopMode): NaviResult {
        val place = cand.poi.place
        val asWaypoint = mode == StopMode.WAYPOINT && navi.route.value.active
        val r = if (asWaypoint) navi.addWaypoint(place) else navi.setDestination(place)
        return r.fold(
            onSuccess = { if (asWaypoint) NaviResult.WAYPOINT else NaviResult.DESTINATION },
            onFailure = { toast.value = "내비 연동 실패: ${it.message}"; NaviResult.FAILED },
        )
    }

    /** 바로 실행 전 경로로 되돌리기 — 경로가 없었으면 안내 취소, 있었으면 목적지·경유지 다시 설정. @return 내비가 받아들였는지 */
    private suspend fun restoreRoute(prev: RouteState?): Boolean {
        prev ?: return true
        val dest = prev.destination
        val r = if (!prev.active || dest == null) navi.cancelGuidance()
        else navi.setDestination(dest).also { res -> if (res.isSuccess) prev.waypoints.forEach { navi.addWaypoint(it) } }
        r.onFailure { toast.value = "내비 연동 실패: ${it.message}" }
        // 시뮬레이터는 남은 거리·도로 정보까지 그대로 되돌린다 (실차 내비 미러링은 위 호출로 반영됨)
        if (r.isSuccess && navi.source == Source.SIMULATOR) sim.restoreRoute(prev)
        return r.isSuccess
    }

    /**
     * 장소 질의를 바로 보정해 실행 — Gleo GO_TO_MY_PLACE, "아니, 스타벅스 가자"처럼 대답 속 새 장소.
     * @param before 보정 전에 할 일 (예: 바로 실행했던 경로 되돌리기)
     * @param after 보정·실행·첫 문장 뒤 (Gleo 콜백)
     */
    private fun runPlaceQuery(
        q: PlaceQuery,
        head: String,
        before: suspend () -> Unit = {},
        after: (Resolution, Clarify?) -> Unit = { _, _ -> },
    ): Job {
        agentJob?.cancel()
        speechOut.stop()
        val job = scope.launch {
            _phase.value = AgentPhase.ROUTING
            before()
            _phaseDetail.value = "내 기록으로 장소 보정"
            val res = resolveForDialogue(q)
            _phaseDetail.value = null
            val line = onResolved(res)
            val top = res.top
            val reasoning = buildList {
                add(head)
                if (top != null) {
                    add("들린 말 ‘${q.text}’" + (res.correctedTo?.let { " → 발음 보정 ‘$it’" } ?: "") + if (res.aliasHit) " · 전에 배운 표현" else "")
                    add("확신도 ${(top.prob * 100).roundToInt()}% → ${res.tier.label}")
                }
                policyNote?.takeIf { it.first == res.id }?.let { add(it.second) }
            }
            _turns.update {
                it + AgentTurn(
                    System.nanoTime(), Speaker.AGENT, line, AiTier.ON_DEVICE, reasoning = reasoning,
                    cards = if (top != null) listOf(placeCard(res)) else emptyList(),
                    privacyNote = "개인 방문 기록은 차 안 암호화 저장소에서만 읽었어요 · 전송 없음",
                )
            }
            say(line)
            watchClarify(res)
            after(res, clarify.value?.takeIf { it.res.id == res.id })
        }
        agentJob = job
        return job
    }

    private fun userTurn(text: String) { _turns.update { it + AgentTurn(System.nanoTime(), Speaker.USER, text) } }

    private fun agentSay(text: String) {
        _turns.update { it + AgentTurn(System.nanoTime(), Speaker.AGENT, text, AiTier.ON_DEVICE) }
        say(text)
    }

    private fun flash(text: String) {
        hudFlash.value = text
        flashJob?.cancel()
        flashJob = scope.launch { delay(6000); if (hudFlash.value == text) hudFlash.value = null }
    }

    /**
     * 진행 중인 대화·음성을 모두 멈춘다 (시나리오 전환·기록 삭제·학습 초기화).
     * 바로 실행 결과는 학습하지 않고 버린다 — 늦게 끝난 응답이 새 상황에 섞이지 않게.
     * @param settle false면 IDLE로 내리지 않는다 (시나리오를 바꾼 뒤 호출한 쪽에서 내림)
     */
    private fun resetDialogue(settle: Boolean = true) {
        val wasListening = _phase.value == AgentPhase.LISTENING
        // 정리하는 동안(되묻기를 지우는 순간 등) 보류된 선제 안내가 끼어들지 않게 잠시 처리 중 상태로
        _phase.value = AgentPhase.ROUTING
        agentJob?.cancel(); agentJob = null
        questionJob?.cancel()
        autoJob?.cancel()
        resayJob?.cancel()
        relistenJob?.cancel()
        listenWatch?.cancel()
        pendingAuto = null
        clarify.value = null
        flashJob?.cancel(); hudFlash.value = null
        if (wasListening) closeMic()
        autoListening = false
        speakWatch?.cancel()
        speechOut.stop()
        _phaseDetail.value = null
        _partial.value = ""
        _caption.value = null
        if (settle) _phase.value = AgentPhase.IDLE
    }

    fun resetPersonalLearning() { resetDialogue(); personal.resetLearning(); toast.value = "학습 초기화 — 방문 기록은 그대로예요" }
    fun erasePersonalData() { resetDialogue(); personal.erase(); toast.value = "개인 기록을 모두 지웠어요 (차 안 저장소)" }
    fun restorePersonalData() { personal.restore(); toast.value = "가상 운전자 기록을 다시 불러왔어요" }
    fun sendPersonalUpdate() { personal.markSynced(); toast.value = "가중치 변화량만 서명해 전송했어요 (모의)" }

    private fun say(text: String) {
        // 바로 실행 결과가 HUD에서 내려간 뒤(늦은 되돌리기 창) 앱이 다른 말을 하면, 그 뒤의 "아니야"는 그 말에 대한 대답일 수 있다 →
        // 받아들인 것으로 보고 지금 닫는다 (학습은 아직 그곳으로 안내 중일 때만)
        pendingAuto?.let { p -> if (clarify.value?.id != p.id) closeAuto(p, learn = true) }
        _caption.value = text
        speakWatch?.cancel()
        _phase.value = AgentPhase.SPEAKING
        if (settings.value.voiceReplies && speechOut.available) {
            speechOut.speak(text)
            speakWatch = scope.launch {
                // 말이 끝나면 IDLE. 앞 발화가 끊기며 생기는 잠깐의 '말 안 함'은 건너뛰고,
                // TTS가 시작·종료 알림을 주지 않아도 상한 시간 뒤에는 풀어 준다 (되묻기 타이머·자동 듣기가 이 신호를 기다린다)
                withTimeoutOrNull((4000 + text.length * 160L).coerceAtMost(20_000)) {
                    delay(300)
                    withTimeoutOrNull(2000) { speechOut.speaking.first { it } }
                    speechOut.speaking.first { !it }
                }
                if (_phase.value == AgentPhase.SPEAKING) _phase.value = AgentPhase.IDLE
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
        // 이전 상황의 응답·되묻기·음성이 새 시나리오에 섞이지 않게 모두 멈춘다
        resetDialogue(settle = false)
        dismissed.value = emptySet()
        spokenMoments.clear()
        pet.value = PetModeState(); rest.value = RestModeState(); kids.value = KidsGameState(); parking.value = null
        onSourceChange(Source.SIMULATOR)
        sim.play(id)
        go(if (id.personal) Screen.PERSONAL else Screen.HOME)
        demoPanel.value = false
        toast.value = "시나리오: ${id.title}"
        // 새 상황이 자리 잡은 뒤에 대기 상태로 — 이제부터의 선제 안내는 새 시나리오 기준
        _phase.value = AgentPhase.IDLE
    }

    fun useSource(src: Source) = onSourceChange(src)

    // ───────────────────────── Gleo Interface ─────────────────────────
    fun logGleo(action: GleoBridge.Action) {
        gleoCalls.update { it + 1 }
        _turns.update { it + AgentTurn(System.nanoTime(), Speaker.SYSTEM, "Gleo → 옆자리 호출 · ${GleoBridge.describe(action)}", AiTier.ON_DEVICE) }
    }

    /** component = activity 액션 (PLAN_EV_TRIP · GO_TO_MY_PLACE) — 화면에서 처리 후 Gleo 에는 silent 로 응답 */
    fun handleGleoActivity(action: GleoBridge.Action, respond: (status: String, message: String) -> Unit) {
        logGleo(action)
        if (action.name == GleoBridge.GO_TO_MY_PLACE) {
            gleoGoToMyPlace(action, respond)
            return
        }
        agentOverlay.value = true
        when (action.name) {
            GleoBridge.PLAN_EV_TRIP -> {
                val dest = action.params.optString("destination", "목적지")
                val who = when (action.params.optString("companions")) { "kids" -> "아이들이랑 "; "pet" -> "강아지랑 "; else -> "" }
                send("${who}${dest} 가는데 충전이랑 쉬는 곳 같이 들러서 가는 계획 세워줘")
                respond(GleoBridge.SUCCESS, "Yeopjari is planning the trip to $dest with charging and rest stops on screen.")
            }
        }
    }

    /**
     * GO_TO_MY_PLACE — Gleo가 알아들은 장소 이름을 옆자리가 개인 기록으로 보정하고, 애매하면 HUD로 되묻는다.
     * 문장을 다시 만들어 의도 분석에 넣지 않고 장소 질의로 바로 보정한 뒤, 결과에 맞춰 7초 안에 한 번 응답한다
     * (바로 실행 → success · 확인/선택 → asking + HUD 질문 · 못 찾음/내비 실패 → fail).
     */
    private fun gleoGoToMyPlace(action: GleoBridge.Action, respond: (status: String, message: String) -> Unit) {
        val place = action.params.optString("place", "").ifBlank { action.params.optString("destination", "") }.trim()
        val mode = if (action.params.optString("stop_type") == "waypoint") StopMode.WAYPOINT else StopMode.DESTINATION
        goToMyPlace(place, mode, respond)
    }

    /** GO_TO_MY_PLACE 본체 — Gleo 인자(JSON)를 풀어 둔 값으로 받는다 (JVM 테스트도 이 경로로 Gleo 호출을 재현한다) */
    internal fun goToMyPlace(place: String, mode: StopMode, respond: (status: String, message: String) -> Unit) {
        agentOverlay.value = false
        go(Screen.PERSONAL)
        if (place.isBlank()) { respond(GleoBridge.FAIL, "No place name was given."); return }

        var answered = false
        val reply: (String, String) -> Unit = { status, message ->
            if (!answered) { answered = true; respond(status, message) }
        }
        val watchdog = scope.launch { delay(GLEO_DEADLINE_MS); reply(GleoBridge.SUCCESS, "Yeopjari is handling '$place' on screen.") }

        if (_phase.value == AgentPhase.LISTENING) { closeMic(); _phase.value = AgentPhase.ROUTING }
        resayJob?.cancel()
        relistenJob?.cancel()
        // 장소 부분의 습관·근처·종류 표현만 뽑는다 (의도 분석은 거치지 않음)
        val q = (QueryParser.parse("$place 가자") ?: PlaceQuery(place)).copy(mode = mode, raw = place)
        val fixed = when (QueryParser.norm(place)) {
            "집", "우리집", "home" -> "home"
            "회사", "work" -> "work"
            else -> null
        }
        // 되묻는 중이면 정리 — 바로 실행 직후의 새 목적지는 학습 없이 닫고 경로를 되돌린 뒤 실행 (말로 한 명령과 같은 규칙)
        val prev = (clarify.value ?: pendingAuto)?.let { supersede(it, destChange = fixed != null || mode == StopMode.DESTINATION) }
        val restore: suspend () -> Unit = { if (prev != null) restoreRoute(prev) }

        val job = if (fixed != null) goFixedPlace(fixed, reply, restore) else runPlaceQuery(q, "Gleo GO_TO_MY_PLACE(place=‘$place’) → 개인화 보정", before = restore) { res, c ->
            when {
                res.top == null -> reply(GleoBridge.FAIL, "'$place' is not among the driver's places.")
                c == null -> reply(GleoBridge.FAIL, "Navigation could not be set.")
                c.stage == ClarifyStage.UNDO -> reply(GleoBridge.SUCCESS, "Navigating to ${c.options.first().poi.spoken}.")
                else -> reply(GleoBridge.ASKING, questionText(c))
            }
        }
        // 다른 명령에 밀려 중간에 멈추면 실패로 한 번 응답
        job.invokeOnCompletion { watchdog.cancel(); reply(GleoBridge.FAIL, "The request was interrupted.") }
    }

    /** 집·회사 — 기존 NAV_HOME / NAV_WORK와 같은 경로 (개인화 보정 대상이 아님) */
    private fun goFixedPlace(placeId: String, done: (status: String, message: String) -> Unit, before: suspend () -> Unit = {}): Job {
        agentJob?.cancel()
        speechOut.stop()
        val job = scope.launch {
            _phase.value = AgentPhase.ROUTING
            before()
            val prof = context.value.profile
            val label = if (placeId == "home") prof.homeLabel else prof.workLabel
            val r = tools.execute("navi_set_destination", mapOf("place_id" to placeId))
            val line = if (r.isSuccess) "$label${Hangul.ro(label)} 안내를 시작할게요." else NAVI_FAIL
            _turns.update {
                it + AgentTurn(
                    System.nanoTime(), Speaker.AGENT, line, AiTier.ON_DEVICE,
                    toolCalls = listOf(ToolCall("navi_set_destination", mapOf("place_id" to placeId), if (r.isSuccess) ToolStatus.DONE else ToolStatus.BLOCKED, r.getOrElse { e -> e.message ?: "실패" })),
                )
            }
            say(line)
            done(if (r.isSuccess) GleoBridge.SUCCESS else GleoBridge.FAIL, if (r.isSuccess) "Navigation to $label started." else "Navigation could not be set.")
        }
        agentJob = job
        return job
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

    companion object {
        /** 바로 실행 뒤 HUD에 "아니야"를 띄워 두는 시간 (안내 문장을 다 말한 뒤, 대기 시간만 셈) */
        const val UNDO_WINDOW_MS = 10_000L
        /** HUD에서 내려간 뒤에도 늦게 온 "아니야"를 받는 시간 — 이게 지나야 바로 실행 결과를 학습 */
        const val LATE_UNDO_MS = 20_000L
        /** 확인·선택 질문에 대답이 없으면 조용히 내리는 시간 (질문을 다 말한 뒤, 대기 시간만 셈) */
        const val CLARIFY_TIMEOUT_MS = 25_000L
        /** 되묻기 하나에 마이크를 자동으로 여는 최대 횟수 (질문 + 다시 묻기 한 번) */
        const val MAX_AUTO_LISTEN = 2
        /** 듣기 감시: 자동으로 연 마이크 / 직접 연 마이크가 끝 알림 없이 열려 있을 수 있는 최대 시간 */
        const val AUTO_LISTEN_LIMIT_MS = 8_000L
        const val MANUAL_LISTEN_LIMIT_MS = 15_000L
        /** 감시 시각에 말하는 중(부분 인식 있음)이면 한 번 더 기다리는 시간 */
        const val LISTEN_GRACE_MS = 3_000L
        /** 머뭇거림 뒤 마이크를 다시 열기 전 앞 인식 세션의 끝 알림을 기다리는 최대 시간 */
        const val STT_END_WAIT_MS = 1_500L
        /** 바로 실행한 곳과 내비 목적지를 같은 곳으로 보는 거리 (실차 내비는 장소 id를 바꿔 알려 준다) */
        const val SAME_PLACE_KM = 0.3
        /** Gleo 콜백 7초 제한 안에서 늦어질 때 먼저 답하는 시각 */
        const val GLEO_DEADLINE_MS = 6_000L
        const val NAVI_FAIL = "내비 연결에 실패했어요. 다시 말씀해 주세요."
    }

    fun setTemp(c: Float) = scope.launch { vehicle.setHvacTemperature(c) }
    fun toggleRoutine(id: String) = routines.update { list -> list.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }
    fun updateSettings(f: (AiSettings) -> AiSettings) = settings.update(f)
    fun clearToast() { toast.value = null }
}

// ───────────────────────── 되묻는 중 들어온 말 판단 (순수 함수 · 단위 테스트 대상) ─────────────────────────

/** 되묻는 중(또는 바로 실행 직후) 들어온 말을 어떻게 다룰지 */
internal sealed interface ReplyAction {
    /**
     * 응·아니야·n번·취소·"아니, 본점"·"아니, 스타벅스 가자" — 대답으로 처리.
     * [mode]: 대답이 바꾼 들르는 방식 — 목적지를 묻는데 "응 들르자"·"2번 들러 줘"·"아니 본점 들렀다 가자"면 경유지 (null이면 질문 그대로)
     */
    data class Answer(val reply: Reply, val mode: StopMode? = null) : ReplyAction
    /** 후보 이름 일부("본점", "대전역 거", "본점으로 가자") → 그 후보로. [mode]는 [Answer]와 같다 ("본점 들렀다 가자" → 경유지) */
    data class Pick(val cand: Candidate, val mode: StopMode? = null) : ReplyAction
    /** "뭐라고?"·"다시 말해 줘"·"잘 못 들었어" → 같은 질문을 다시 */
    data object Repeat : ReplyAction
    /** 선택지 여러 곳을 다 부르는 이름("성심당") → 번호로 다시 묻기 */
    data object AskNumber : ReplyAction
    /** 머뭇거림("어…", "음") → 질문은 그대로, 마이크를 한 번 더 */
    data object Hesitate : ReplyAction
    /** "잠깐만"·동승자 대화처럼 대답도 명령도 아닌 말 → 질문은 그대로 */
    data object Ignore : ReplyAction
    /** "아니, 집으로 가자"·"아니, 에어컨 켜 줘" — 제안은 거절하고(바로 실행이면 되돌리고) 뒤의 명령을 실행 */
    data class RejectThen(val command: String) : ReplyAction
    /**
     * 대답이 아닌 진짜 새 명령 → 되묻기를 정리하고 새 명령으로.
     * [destChange]: 목적지를 바꾸는 명령(새 장소·집·회사) — 바로 실행한 결과는 학습 없이 닫는다.
     * 늦은 되돌리기 창에서는 받지 않는 말이 모두 여기로 온다 (평소처럼 처리하고 되돌리지 않는다).
     */
    data class NewCommand(val destChange: Boolean) : ReplyAction
}

/**
 * 되묻는 중 들어온 말의 판단 규칙 — 상태를 바꾸지 않는 순수 함수라 JVM 단위 테스트로 확인한다.
 * 실행(경로·학습·음성)은 [YeopjariStore.handleReply]가 맡는다.
 */
internal object DialoguePolicy {
    /**
     * @param late HUD에서 내려간 바로 실행의 늦은 되돌리기 창 — 맨 "아니야"와 후보·장소를 말한 "아니, …", "응", 다른 후보 이름만 받는다
     * @param classify 일시 중단 없는 의도 분류 ([com.yeopjari.app.ai.nlu.OnDeviceIntentEngine.classify])
     */
    fun decide(text: String, c: Clarify, late: Boolean, classify: (String) -> NluResult): ReplyAction {
        val reply = QueryParser.reply(text)
        // 목적지를 묻던 중 "응 들르자"·"성심당 들렀다 가 줘"처럼 들르자고 하면 경유지로 (지금 경로는 그대로 두고 들른다)
        val stop = stopInstead(text, c)
        val proposed = c.options.firstOrNull()
        if (late) {
            return when {
                reply == Reply.No -> ReplyAction.Answer(reply)
                reply == Reply.Yes -> ReplyAction.Answer(reply, stop)
                reply is Reply.NoAnd && noAndTarget(reply.rest, c) -> ReplyAction.Answer(reply, stop)
                // 바로 실행한 곳이 아닌 다른 후보 이름만 말함 ("본점", "본점이요", "디씨씨점", "대흥동 성당") → 고쳐 고른 것
                // (그 곳을 고르고, 바로 실행한 곳은 이 말의 선택 기억에서 덜어 낸다 — 받아들인 것으로 학습하지 않는다)
                else -> lateCorrection(text, reply, c)?.let { ReplyAction.Pick(it, stop) }
                    // "본점 들렀다 가자"·"성심당 들렀다 가 줘" — 후보를 부르며 들르자고 함 → 원래 경로에 경유지로
                    ?: lateStop(text, c, stop)
                    // "취소"·"그만"·"아니 괜찮아"는 다른 것에 대한 말일 수 있다 → 되돌리지 않고 평소처럼
                    ?: ReplyAction.NewCommand(changesDestination(classify(text)))
            }
        }
        if (reply is Reply.NoAnd) {
            val again = QueryParser.reply(reply.rest)
            if (again == Reply.Yes || again == Reply.Cancel || again is Reply.Pick || noAndTarget(reply.rest, c)) return ReplyAction.Answer(reply, stop)
            // 장소가 아닌 진짜 명령("아니, 집으로 가자") → 제안을 거절하고 그 명령으로. 그 밖의 말은 "아니야"로
            return if (classify(reply.rest).intent != Intent.OPEN_TALK) ReplyAction.RejectThen(reply.rest) else ReplyAction.Answer(reply)
        }
        if (reply == Reply.Yes || reply is Reply.Pick) return ReplyAction.Answer(reply, stop)
        if (reply != Reply.None) return ReplyAction.Answer(reply)

        // 후보 이름 일부를 말했는지 ("본점", "본점으로 가자", "역에 있는 거") — 말한 장소 부분만 비교
        val q = QueryParser.parse(text)
        val pool = if (c.stage == ClarifyStage.CHOOSE) c.options else c.res.candidates
        QueryParser.matchOption(q?.text ?: text, pool)?.let { m ->
            // 확인·되돌리기 중의 완전한 장소 명령인데 그 후보가 처음 들린 말과 소리가 다르면, 고쳐 말한 게 아니라 새 질의다
            // ('서심다응' 바로 실행 중 "대흥동 성당 가자"가 '서심다응'의 선택 기억을 지우고 대흥동 성당 쪽으로 학습하던 문제)
            if (c.stage != ClarifyStage.CHOOSE && q != null && m.f.phon < PersonalBrain.AUTO_MIN_PHON) {
                return ReplyAction.NewCommand(changesDestination(classify(text)))
            }
            return ReplyAction.Pick(m, stop)
        }
        // 제안한 곳의 이름을 다시 말함 ("성심당", "응 성심당", "성심당 맞아", "성심당 가자") → 네 ("성심당 들렀다 가 줘"면 경유지로)
        if (c.stage != ClarifyStage.CHOOSE && proposed != null && QueryParser.namesOption(text, proposed)) return ReplyAction.Answer(Reply.Yes, stop)
        // 선택지 여러 곳을 다 부르는 이름 ("성심당") → 번호로
        if (c.stage == ClarifyStage.CHOOSE && c.options.any { QueryParser.namesOption(text, it) }) return ReplyAction.AskNumber
        // 진짜 새 명령일 때만 질문을 내린다
        val nlu = classify(text)
        if (q != null || nlu.intent != Intent.OPEN_TALK) return ReplyAction.NewCommand(changesDestination(nlu))
        return when (QueryParser.aside(text)) {
            Aside.REPEAT -> ReplyAction.Repeat
            Aside.FILLER -> ReplyAction.Hesitate
            Aside.HOLD, null -> ReplyAction.Ignore
        }
    }

    /**
     * 목적지를 묻던 중(확인·선택·되돌리기) 대답이 '들러 가자'는 뜻이면 [StopMode.WAYPOINT] — "응 들르자", "들렀다 가자",
     * "성심당 들렀다 가 줘", "본점 들렀다 가자", "2번 들러 줘". 경유지 질문이면 이미 경유지라 null (질문 그대로).
     * 바로 실행 전에 안내 중인 경로가 없었으면 들를 경로가 없으니 null (그대로 목적지).
     */
    fun stopInstead(text: String, c: Clarify): StopMode? {
        if (c.res.query.mode != StopMode.DESTINATION) return null
        if (c.stage == ClarifyStage.UNDO && c.prevRoute?.active != true) return null
        return if (QueryParser.asksStop(text)) StopMode.WAYPOINT else null
    }

    /**
     * 늦은 되돌리기 창에서 바로 실행한 곳이 아닌 다른 후보의 이름만 말했는지 ("본점", "본점이요", "디씨씨점", "대흥동 성당") → 그 후보.
     * 장소 명령("본점으로 가자")·대답(번호·취소)은 여기서 고르지 않는다. 후보 전체에서 가장 그럴듯한 곳이 바로 실행한 곳이면
     * (그 이름을 다시 말함) 고친 게 아니다 — 바로 실행한 곳을 빼고 견주면 소리가 조금 닮은 다른 후보가 뽑힐 수 있어서다.
     */
    fun lateCorrection(text: String, reply: Reply, c: Clarify): Candidate? {
        if (reply != Reply.None || QueryParser.parse(text) != null) return null
        val auto = c.options.firstOrNull()?.poi?.place?.id
        return QueryParser.matchOption(text, c.res.candidates)?.takeIf { it.poi.place.id != auto }
    }

    /**
     * 늦은 되돌리기 창에서 후보를 부르며 들르자고 한 말 ("본점 들렀다 가자" → 본점을 경유지로,
     * "성심당 들렀다 가 줘" → 바로 실행한 곳을 경유지로). 처음 들린 말과 소리가 다른 장소면 고쳐 말한 게 아니라 새 질의다.
     */
    fun lateStop(text: String, c: Clarify, stop: StopMode?): ReplyAction? {
        if (stop == null) return null
        val q = QueryParser.parse(text)?.takeIf { it.mode == StopMode.WAYPOINT } ?: return null
        val auto = c.options.firstOrNull()
        val m = QueryParser.matchOption(q.text, c.res.candidates)?.takeIf { it.f.phon >= PersonalBrain.AUTO_MIN_PHON }
        if (m != null && m.poi.place.id != auto?.poi?.place?.id) return ReplyAction.Pick(m, stop)
        if (m != null || (auto != null && QueryParser.namesOption(text, auto))) return ReplyAction.Answer(Reply.Yes, stop)
        return null
    }

    /**
     * 늦은 되돌리기 창에서 알아듣지 못한 말(자유 대화)인지 — 바로 실행한 곳을 고쳐 말하려던 것일 수 있어서,
     * 뒤이은 앱의 대답이 '받아들임'으로 세지 않게 창을 학습 없이 닫는다. "그만"·"취소"는 다른 것에 대한 말로 보고 평소처럼 둔다 (D3).
     */
    fun unclearLate(text: String, classify: (String) -> NluResult): Boolean =
        QueryParser.reply(text) != Reply.Cancel && classify(text).intent == Intent.OPEN_TALK

    /** "아니, …"의 뒷말이 남은 후보(거절한 곳 제외)를 부르거나 장소 명령으로 읽히는지 */
    fun noAndTarget(rest: String, c: Clarify): Boolean {
        val declined = if (c.stage == ClarifyStage.CHOOSE) c.rejectedId else c.options.firstOrNull()?.poi?.place?.id
        val pool = c.res.candidates.filter { it.poi.place.id != declined }
        val q = QueryParser.parse(rest)
        return q != null || QueryParser.matchOption(rest, pool) != null
    }

    /** 목적지를 바꾸는 명령인지 — 새 장소(목적지), 집·회사 */
    fun changesDestination(nlu: NluResult): Boolean = when (nlu.intent) {
        Intent.NAV_HOME, Intent.NAV_WORK -> true
        Intent.NAV_PLACE -> nlu.place?.mode != StopMode.WAYPOINT
        else -> false
    }
}

/** 선제 안내(모먼트)를 지금 음성으로 끼어들어 말해도 되는지 — 순수 함수 (단위 테스트 대상) */
internal object MomentPolicy {
    fun mayInterrupt(m: Moment, load: LoadLevel, asking: Boolean, screen: Screen, source: Source): Boolean {
        // 기존 인터럽션 매니저: 부하가 높으면 미룰 수 있는 안내는 여유 구간까지 미룬다 (deferredCount와 같은 기준)
        if (m.deferrable && load == LoadLevel.HIGH) return false
        // 긴급하거나 미룰 수 없는 안내(뒷좌석 아이·졸음 3단계·더운 차 안 반려견 …)는 막지 않는다
        if (!m.deferrable || m.urgency.weight >= Urgency.HIGH.weight) return true
        if (asking) return false
        // '나를 아는 AI' 화면의 보류는 시뮬레이터 시연 중에만 (시연 대화가 끊기지 않게).
        // 실차(Pleos SDK)에서는 이 화면이 기본 화면이라, 주차 위치·우회 안내 같은 보통 안내를 막으면 아무 데서도 들리지 않는다.
        return !(screen == Screen.PERSONAL && source == Source.SIMULATOR)
    }
}
