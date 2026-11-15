# 옆자리 영어 (YEOPJARI English) — Engineering Spec v1

Owner of this document and of `english/core/**`: **architect**. Engineers implement modules in parallel from this spec without talking to each other. If something here conflicts with the code in `app/src/main/java/com/yeopjari/app/english/core/`, **the code wins** (it compiles); this document explains it.

**v1.2 (2026-11-15)** — the driving lesson now ends 60 s before arrival (§7), the evening commute asks before starting (§7.1), a parked dashboard shows results and progress when the car is put in P (§7.2), and answers get short sound effects (§7.3).

Contents
1. Product, principles, hard rules
2. Package layout, ownership, entry points, build hygiene
3. Data flow
4. Driving: signals, load engine, policy, hooks, commute, map, simulator, media keys, feedback
5. Speech: normaliser, judge, commands, pronunciation, fluency, SpeechPort, audio
6. Learning: SRS, placement, progression, planner, repository
7. Coach state machine (the orchestrator), evening ask, parked dashboard, sound effects
8. UI screens
9. Demo / PT support, adb extras
10. Gleo App Schema
11. Content JSON schema + examples
12. Pleos SDK signatures (javap-verified) and permissions
13. Integration plan
14. Testing

---

## 1. Product, principles, hard rules

**Problem.** Busy commuters drive 70–80 min/day and cannot use phone apps. Audio apps over Bluetooth do not know when the driver is busy.
**Product.** An in-car English coach for Pleos Connect (Android Automotive 14) that **adapts in real time to driving load**: it asks short spoken questions when the road is calm, goes silent at intersections, and resumes with “아까 그 문제 다시 할게요.” when the load drops.
**Core value** = learning content + curriculum + driving-load engine. The answer judge is an **in-app rule engine**. A LoRA model is being trained separately and is **not integrated**: never fake AI latency, never say a model is running, never label anything “AI 모델”. Allowed wording: “앱 안에서 채점해요 (서버 전송 없음)”.

Hard rules (all agents):
- Only public Pleos SDKs (Vehicle 2.3.2, NaviHelper 3.0.5, SpeechToText 3.3.1, TextToSpeech 3.3.1, FusedLocation 1.1.3) + standard AAOS/Android APIs (CarUxRestrictions, CarPropertyManager speed, AudioManager focus + playback callback, MediaSession, android.speech.tts.TextToSpeech for output only) + Gleo App Schema.
- Never call `@PrivilegedApi`/internal APIs: no `getAmbientAi()`, `getCallApi()`, `NaviHelper.getInternalApi()`, `setLMBoostConfig`, `CarInfo.getVINInfo/getCRNInfo`.
- Vehicle microphone audio only through the SpeechToText SDK: **no** `RECORD_AUDIO`, `AudioRecord`, `MediaRecorder`, `android.speech.SpeechRecognizer`, `SpeechToText.sendAudio`.
- No external map APIs. No network calls at all (nothing is sent to servers).
- No personal data anywhere: no real names, phone numbers, emails, home addresses. Map data = public landmarks, coordinates rounded to 3 decimals. The learner persona is fictional and unnamed.
- Never `Log.*` user utterances, answers, transcripts, locations or place names. Log only codes/counts.
- User-facing text and spoken Korean: natural, short Korean (해요체). English learning content: natural English.
- Do NOT git commit/push/stash/reset/checkout. Do not modify files you do not own. Do not delete files unless this spec says so.

---

## 2. Package layout, ownership, entry points, build hygiene

Root package `com.yeopjari.app`. Sources `app/src/main/java/com/yeopjari/app/`, tests `app/src/test/java/com/yeopjari/app/`.

| Path | Owner | Contents |
|---|---|---|
| `english/core/**` (+ tests `english/core/**`) | architect (done) | Models.kt, Driving.kt, UiState.kt, Contracts.kt, Thresholds.kt, Support.kt, MiniJson.kt; test utils VirtualTime.kt (+ MutableSignalSource) |
| `app/src/main/assets/content/*.json`, `english/content/**`, tests `english/content/**` | **content** | JSON curriculum, `JsonContentSource`, `ContentModule`, validator |
| `english/learn/**`, tests `english/learn/**` | **learn** | `SrsScheduler`, `AdaptivePlacement`, `UnitProgression`, `CommuteSessionPlanner`, `VaultLearnerRepository` + codec, `VaultSecureStore`, `LearnModule` |
| `english/speech/**`, tests `english/speech/**` | **speech** | `EnglishNormalizer`, `RuleAnswerJudge`, `BilingualCommandParser`, `KoreanL1PronunciationAnalyzer`, `EpdFluencyMeter`, `PleosSpeechPort`, `AndroidAudioEnvironment`, `SpeechModule` |
| `english/drive/**`, `sim/**`, `platform/pleos/PleosNaviGateway.kt`, `platform/pleos/PleosVehicleGateway.kt`, `platform/hub/Hubs.kt`, `core/gateway/Gateways.kt`, `core/model/Vehicle.kt`, `core/model/Navigation.kt`, tests `english/drive/**` | **drive** | `RuleLoadEngine`, `TableLoadPolicy`, `DrivingSignalHub`, `CommuteMaps` (map data), `RuleContextHookDetector`, `RouteCommuteDetector`, `MediaKeyHub`, `PleosVehicleFeedback`, `sim/CommuteSimulator.kt`, `DriveModule` |
| `english/coach/**`, tests `english/coach/**` | **coach** | `DefaultEnglishCoach` (state machine), `DemoAnswers`, scenario tests on virtual time |
| `ui/english/**`, `ui/theme/**` | **ui** | all new screens/components/navigation, `EnglishApp` composable, theme refresh |
| `YeopjariApp.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `assets/schema.json`, `platform/gleo/**`, `app/build.gradle.kts`, deletions of legacy code, `README.md` | **integration** (later) | wiring, extras, Gleo, permissions, cleanup |

### 2.1 Entry points every module MUST provide (exact names and constructors)

Integration wires only these. Pure classes inside must have Android-free constructors (JVM-testable).

```kotlin
// content — com.yeopjari.app.english.content
class JsonContentSource(read: (path: String) -> String?) : ContentSource   // path = ContentFiles.* ; null = file missing
class ContentModule(context: android.content.Context) { val source: ContentSource }   // reads assets via context.assets

// learn — com.yeopjari.app.english.learn
class SrsScheduler : Scheduler
class AdaptivePlacement : PlacementEngine
class UnitProgression(scheduler: Scheduler) : Progression
class CommuteSessionPlanner(scheduler: Scheduler, progression: Progression) : SessionPlanner
class VaultLearnerRepository(store: SecureStore, scope: CoroutineScope, clock: Clock) : LearnerRepository
class VaultSecureStore(vault: com.yeopjari.app.platform.secure.SecureVault) : SecureStore
class LearnModule(vault: SecureVault, scope: CoroutineScope, clock: Clock) {
    val store: SecureStore; val repository: LearnerRepository; val scheduler: Scheduler
    val placement: PlacementEngine; val progression: Progression; val planner: SessionPlanner
}

// speech — com.yeopjari.app.english.speech
object EnglishNormalizer : TextNormalizer
class RuleAnswerJudge(normalizer: TextNormalizer, pronunciation: PronunciationAnalyzer) : AnswerJudge
class BilingualCommandParser(normalizer: TextNormalizer) : CommandParser
class KoreanL1PronunciationAnalyzer : PronunciationAnalyzer
class EpdFluencyMeter : FluencyMeter
class AndroidAudioEnvironment(context: Context, scope: CoroutineScope, clock: Clock) : AudioEnvironment
class PleosSpeechPort(context: Context, scope: CoroutineScope, clock: Clock, audio: AndroidAudioEnvironment) : SpeechPort
class SpeechModule(context: Context, scope: CoroutineScope, clock: Clock) {
    val audio: AudioEnvironment; val port: SpeechPort; val normalizer: TextNormalizer; val judge: AnswerJudge
    val commands: CommandParser; val pronunciation: PronunciationAnalyzer; val fluency: FluencyMeter
    fun setEnglishRate(rate: Float)   // from LearnerSettings.englishRate
    fun release()
}

// drive — com.yeopjari.app.english.drive (simulator in com.yeopjari.app.sim)
class RuleLoadEngine : DrivingLoadEngine
class TableLoadPolicy : LoadPolicy
class RuleContextHookDetector : ContextHookDetector
class RouteCommuteDetector(map: CommuteMap) : CommuteDetector
object CommuteMaps { val default: CommuteMap }                     // english/drive/map/CommuteMapData.kt
class CommuteSimulator(scope: CoroutineScope, clock: Clock, map: CommuteMap) : DemoControl   // sim/CommuteSimulator.kt
class DriveModule(context: Context, scope: CoroutineScope, clock: Clock, audio: AudioEnvironment, demoAllowed: Boolean) {
    val map: CommuteMap; val simulator: CommuteSimulator; val demo: DemoControl
    val signals: SignalSource; val loadEngine: DrivingLoadEngine; val policy: LoadPolicy
    val hooks: ContextHookDetector; val commute: CommuteDetector
    val feedback: VehicleFeedback; val mediaKeys: MediaKeyPort
    val mirrorToCar: kotlinx.coroutines.flow.MutableStateFlow<Boolean>   // default false
    fun onLocationPermissionGranted(); fun refreshCarApi(); fun release()
}

// coach — com.yeopjari.app.english.coach
class DefaultEnglishCoach(deps: CoachDeps, scope: CoroutineScope) : EnglishCoach

// sound — com.yeopjari.app.english.sound (res/raw/sfx_*.wav are synthesised by us: additive sines + exponential decay, 44.1 kHz mono 16-bit)
class SoundPoolPlayer(context: Context, allowed: () -> Boolean = { true }) : SoundPort   // SoundPool, USAGE_ASSISTANCE_SONIFICATION, no audio focus

// ui — com.yeopjari.app.ui.english
@Composable fun EnglishApp(coach: EnglishCoach, navigator: EnglishNavigator, demo: DemoControl?, modifier: Modifier = Modifier)
```

### 2.2 Build hygiene (shared working tree!)

All agents edit the same tree; one broken file breaks everyone's build.
- After every file you add/change run `./gradlew :app:compileDebugKotlin --console=plain -q` (Git Bash, `JAVA_HOME = JDK 17~21`). Never leave your files non-compiling. Gradle serializes concurrent builds; use 10-min timeouts.
- Run your tests with a filter: `./gradlew :app:testDebugUnitTest --tests 'com.yeopjari.app.english.<module>.*' --console=plain`. All test sources compile together — keep your test files compiling at all times.
- If a compile error is in a file you don't own, don't fix it; wait and retry (someone is mid-edit). Report it if it persists.
- **Legacy code stays until integration.** `ai/**`, `YeopjariStore.kt`, `ui/Shell.kt`, `ui/screens/**`, `ui/components/**`, `sim/DriveSimulator.kt`, `sim/DemoData.kt` are still compiled. Owners of shared legacy files (drive: `core/model/*.kt`, `core/gateway/Gateways.kt`, `platform/hub/Hubs.kt`, Pleos gateways; ui: `ui/theme/**`) make **additive** changes only: keep every existing public name/parameter; new fields need defaults. Drive writes the new simulator in **new** files (`sim/CommuteSimulator.kt` …) and does not delete `sim/DriveSimulator.kt`/`sim/DemoData.kt` — integration deletes them together with the legacy features (this is the “지도 정보 초기화”: old persona places, visit history, old scenarios disappear; `CommuteMaps.default` replaces them).
- New code must not import legacy packages (`com.yeopjari.app.ai.*`, `YeopjariStore`, `ui.screens`, `ui.components`, `ui.Shell`, `sim.DriveSimulator`, `sim.Places/Chargers/DemoHistory`, `core.model.Agent/Moment`). Old `ai.context.LoadLevel` ≠ new `english.core.LoadLevel`.
- Kotlin gotcha: never write `/*` inside a KDoc/comment (e.g. `content/*.json`) — Kotlin nests block comments.
- `org.json` does not work in JVM unit tests → use `english.core.MiniJson` (+ `jStr/jInt/jObj/jEnum…` helpers). No kotlinx-serialization, no coroutines-test: use `english.core.VirtualTime` (test dir) for virtual time.

---

## 3. Data flow

```
 Pleos Vehicle SDK ─┐  CarPropertyManager speed ─┐  CarUxRestrictions ─┐
 NaviHelper (TBT,   │                            │                     │
 camera, caution,   ├─► VehicleHub / NaviHub ─────┴──► DrivingSignalHub ◄┘◄── AudioEnvironment (nav prompt, focus)
 route, arrival)    │   (Pleos or CommuteSimulator)      (SignalSource, ≥2 Hz heartbeat, map-matching → segment)
 FusedLocation ─────┘                                         │ StateFlow<DrivingSignals>
                                                              ▼
                       ┌─────────────── DefaultEnglishCoach (single orchestrator, main thread) ───────────────┐
                       │ loadEngine.update → LoadAssessment → policy.rules → what may happen now           │
                       │ hooks.update → HookEvent queue      commute.detect → slot / remaining time        │
                       │ planner.plan → SessionPlan          sound.play(SoundCue) — never in CRITICAL      │
                       │ speech.speak/listen (suspend, cancellable) → commands.parse | judge.judge         │
                       │ fluency.measure, scheduler.gradeFor, progression.record → repository.update       │
                       │ feedback.pulse / showHudHint        mediaKeys.keys → onMediaKey                   │
                       └──────────────► StateFlow<EnglishUiState> ──► ui/english (EnglishApp) ◄─ EnglishNavigator
 LearnerRepository ◄──► SecureStore (SecureVault, AES-256-GCM, Android Keystore) — never leaves the car
```

The coach is the only component that mutates learner state (via `repository.update`). Engines are pure or self-contained.

---

## 4. Driving

### 4.1 DrivingSignals (english/core/Driving.kt)

Produced by drive's `DrivingSignalHub`. `atMs` is always `Clock.nowMs()` (real time, never simulator-scaled time). Emit at least every 500 ms (heartbeat) even if nothing changed, so hysteresis timers advance. Sign convention: `steeringDeg` + = right, − = left; `accelMps2` − = decelerating.

Source mapping (real car):

| Field | Source |
|---|---|
| gear | `Vehicle.getGear().registerGearSelection(GearSelectionListener)`; GEAR_PARK→P, GEAR_REVERSE→R, GEAR_NEUTRAL→N, GEAR_UNKNOWN→UNKNOWN, GEAR_DRIVE/1..9→D |
| speedKph | CarPropertyManager `PERF_VEHICLE_SPEED` (m/s ×3.6) |
| speedLimitKph | NaviHelper `onCurrentRoadSpeedLimit(int)` (>0), else camera limit |
| accelMps2 | derived: Δspeed/Δt over the last ≥300 ms window (sim provides directly) |
| brakePedal | `Vehicle.getBrake().registerBrakePedalStatus` |
| turnSignal | `Vehicle.getTurnSignal().registerTurnSignalState` (TurnSignalValue OFF/LEFT/RIGHT) |
| steeringDeg / steeringRateDps | `Vehicle.getSteeringWheel().registerSteeringWheelAngle`; rate derived from consecutive samples (abs deg/s). SDK sign unverified → if left turns produce + values, invert in the gateway |
| cruiseActive | `Vehicle.getDrivingMode().registerCruiseControlState`; true only for `CruiseControl.ACTIVATED` |
| wipersOn | `Vehicle.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), …)`; `WiperState.ON` |
| headlightsOn | `Vehicle.getLight().registerLowBeamLightState`; `LightState.ON` (DAYTIME_RUNNING = false) |
| uxRestricted | CarUxRestrictionsManager `isRequiresDistractionOptimization` (existing code) |
| maneuver | NaviHelper `onTBTInfo(List<TBTInfo>)` first item; `getTBTInfo()` poll ≤1 s while routing. TurnType map below |
| camera | `onCameraAlert(List<CameraAlert>)` nearest by `remainDistance` |
| caution | `onCautionPointInfo(List<CautionPoint>)` nearest relevant |
| navVoicePrompt / audioFocusLost | `AudioEnvironment` (speech) OR simulator injection |
| charging / socPercent | EvBattery charge switch / SOC (existing) |
| routeActive / routeRemainingSec / routeRemainingM / destinationName | `onDestinationInfo` (`remainTime` s, `remainDistance` m, `name`), `onNavigationStatus(ROUTE_DRIVE)` |
| destinationCategory / destinationPlaceId / originPlaceId | match destination (name or within 300 m) against `CommuteMap.places`; airport names (“공항”/“Airport”) → AIRPORT |
| location | FusedLocation `LocationData.latitude/longitude` |
| segmentId / segmentName / roadKind | map-match location to nearest `RouteSegment` line within 150 m (sim: exact) |
| inTunnel | sim; real: current segment road == TUNNEL |
| trafficJam | sim; real: gear D and speed < 15 km/h for ≥ 30 s |
| arrived | `onDestinationArrived` → true for 5 s |
| driveId | new id (`Ids.next("drive", now)`) when gear leaves P after ≥ 60 s in P or a demo scenario starts; kept while parked |

TurnType → ManeuverType: STRAIGHT←TURN_STRAIGHT; LEFT←TURN_LEFT, TURN_SHARP_L; RIGHT←TURN_RIGHT, TURN_SHARP_R; KEEP_LEFT←TURN_KEEP_L, *_UNDER_L, *_OVER_L; KEEP_RIGHT←TURN_KEEP_R, *_UNDER_R, *_OVER_R; STRAIGHT←*_UNDER_C, *_OVER_C; U_TURN←TURN_UTURN; EXIT←TURN_EXIT_IC_*; ENTER←TURN_ENTER_IC_*; MERGE←TURN_DEVIDE_MERGE; ROUNDABOUT←TURN_ENTER_ROUNDABOUT_*, TURN_EXIT_ROUNDABOUT; TOLL←TURN_TOLL; TUNNEL←TURN_TUNNEL; ARRIVE←TURN_ARRIVE, TURN_WAY; REST_AREA←TURN_ENTER_SERVICEAREA, TURN_RESTAREA; OTHER←TURN_UNKNOWN.
CameraType → CameraKind: SPEED_CAMERA_FIX, SPEED_CAMERA_REAR, VARIABLE_SPEED_CAMERA→SPEED_FIXED; SPEED_CAMERA_MOV→SPEED_MOBILE; SPEED_CAMERA_SECTION_START, SECTION_SPEED_ALERT, SPEED_CAMERA_SECTION_END→SECTION; SPEED_CAMERA_TRAFFIC_LIGHT→SIGNAL; SPEED_CAMERA_SCHOOL_ZONE(_TRAFFIC_LIGHT)→SCHOOL_ZONE.
CautionPointType → CautionKind: SCHOOL_ZONE→SCHOOL_ZONE; SPEED_BUMP→SPEED_BUMP; ACCIDENT_PRONE_AREA→ACCIDENT_AREA; RAILROAD_CROSSING→RAILROAD; SLIPPERY_ROAD_ALERT, FOG_AREA→SLIPPERY; HIGHWAY_CONSTRUCTION, ROAD_DAMAGE_ALERT→CONSTRUCTION; enforcement/notice types (BUS_LANE_*, *_ENFORCEMENT, NO_PARKING_ZONE, TRAFFIC_INFO_COLLECTION, SIGN_MALFUNCION, LANE_CHANGE_CAMERA_ALERT) → ignore; others→OTHER.

### 4.2 Load engine (`RuleLoadEngine`)

`instant(s)` collects reasons; level = max of reason levels (`ReasonCode.level`). All numbers below are constants in `LoadThresholds` — use them, don't re-type literals:

1. **PARKED** — `gear == P`, or (`charging` and speed < 1). Reason PARKED “정차 중” / CHARGING “충전 중 {soc}%”. Nothing else is evaluated.
2. **CRITICAL** reasons: `accelMps2 <= -3.5` → HARD_BRAKE “급제동”; `speedLimitKph != null && speed > limit + 10` → OVERSPEED “과속 · 제한 {limit}km/h”; `navVoicePrompt` → NAV_PROMPT “내비 음성 안내 중”; `audioFocusLost` → AUDIO_FOCUS “다른 음성 안내 중”.
3. **FOCUS** reasons: `turnSignal != OFF` → TURN_SIGNAL “방향지시등”; maneuver with `type.demanding` and (`distanceM <= 300` or (`speed >= 1` and `distanceM / (speed/3.6) <= 8`)) → MANEUVER (text by type, distance rounded to 10 m): LEFT/RIGHT/U_TURN/ROUNDABOUT/OTHER “교차로 접근 {d}m”, EXIT “나들목 진출 {d}m”, ENTER/MERGE “합류 구간 {d}m”, KEEP_* “차로 변경 {d}m”, TOLL “요금소 {d}m”, ARRIVE “목적지 {d}m”, REST_AREA “휴게소 진입 {d}m”; `|steeringDeg| > 25 || steeringRateDps > 60` → STEERING “핸들 조작”; `camera.distanceM <= 200` → CAMERA “단속 카메라 {d}m”; `caution.inside || caution.distanceM <= 200` → SCHOOL_ZONE “어린이 보호구역” (kind SCHOOL_ZONE) else CAUTION_ZONE “{kind.ko}”; `wipersOn && headlightsOn` → RAIN_NIGHT “야간 빗길”; `gear == R` → REVERSE “후진 중”.
4. **NORMAL** info: `trafficJam` → TRAFFIC_JAM “정체 구간” (stop-and-go stays NORMAL, never FOCUS by itself); else speed < 1 → STOPPED “신호 대기”; `wipersOn && !headlightsOn` → RAIN “비”; `headlightsOn && !wipersOn` → NIGHT “야간”; `uxRestricted` guarantees ≥ NORMAL.
5. **RELAXED** only if there is no FOCUS/CRITICAL reason, no rain, no jam, `turnSignal == OFF`, and either `cruiseActive && speed >= 30` → CRUISE “크루즈 주행”, or *steady highway*: speed > 60, `|steering| <= 8`, `|accel| <= 1.0`, no demanding maneuver within 1000 m, no camera within 500 m, no caution → STEADY_HIGHWAY “고속 직진 구간”.
6. Otherwise NORMAL with CITY “시내 주행”.

Reasons are sorted by level desc, at most 3. `index = (level.bandStart + floor(speed/130*10) + 4*(reasons.size-1)).coerceIn(level.bandStart, level.bandEnd)`.

`update(s)` (stateful, time = `s.atMs`):
- STEADY_HIGHWAY counts only after the steady condition has held continuously for 15 s (`RELAXED_STEADY_MS`); before that the raw level is NORMAL. CRUISE is RELAXED immediately. (`instant()` ignores the 15 s rule.)
- **Escalate immediately**: raw > current → current = raw, `sinceMs = atMs`.
- **De-escalate** when raw < current: start a window at the first lower sample, track `windowMax` = max raw level in the window. If current is CRITICAL: after 3 s (`CRITICAL_MIN_HOLD_MS`) → current = max(windowMax, FOCUS) (always steps through FOCUS). Otherwise after 4 s (`DEESCALATE_STABLE_MS`) → current = windowMax. Any raw ≥ current resets the window.
- While holding a higher level, `reasons` keep the reasons from when that level was last raw (so the screen still says “급제동”); `rawLevel` shows the instant value.
- Net effect: hard brake → ≥3 s CRITICAL + ≥4 s FOCUS of silence.

### 4.3 Policy (`TableLoadPolicy`)

| level | newQuestions | listening | tts | skills | maxAnswerWords | maxListenSec | screen | choices | hudHint | pulse |
|---|---|---|---|---|---|---|---|---|---|---|
| PARKED | ✓ | ✓ | FULL | all | 40 | 60 | FULL | ✓ | ✗ | ✓ |
| RELAXED | ✓ | ✓ | FULL | VOCAB, EXPRESSION, SENTENCE_BUILD, LISTEN_REPEAT, TOEIC_QA, MINIMAL_PAIR | 20 | 45 | DRIVE | ✗ | ✓ | ✓ |
| NORMAL | ✓ | ✓ | FULL | VOCAB, EXPRESSION, SENTENCE_BUILD, LISTEN_REPEAT, MINIMAL_PAIR | 12 | 12 | DRIVE | ✗ | ✓ | ✓ |
| FOCUS | ✗ | ✗ | FINISH_SHORT | — | 0 | 0 | DRIVE | ✗ | ✗ | ✗ |
| CRITICAL | ✗ | ✗ | STOP_NOW | — | 0 | 0 | DRIVE | ✗ | ✗ | ✗ |

`allows(item, level)` = `rules.newQuestions && level.isAtMost(item.maxLoad) && item.skill in rules.skills && item.answerWords <= rules.maxAnswerWords`. Listen window = `min(item.listenSeconds, rules.maxListenSec)` seconds.
FINISH_SHORT: if the utterance in progress has < 2 s left (`SpeechDurations.estimateMs` minus elapsed) let it finish, else `stopAll()`. STOP_NOW: `stopAll()` immediately.

### 4.4 Context hooks (`RuleContextHookDetector`)

`update(signals, load)` returns events at most once per trigger per `driveId` (the detector resets itself when `driveId` changes) and never twice within 10 min. Rules (time from `atMs`; constants in `HookThresholds`):

| Trigger | Fires when |
|---|---|
| JAM_EXIT | `trafficJam` was true continuously ≥ 20 s, then false with speed ≥ 30 for ≥ 5 s |
| RAIN | `wipersOn` continuously ≥ 10 s |
| TUNNEL | `inTunnel` true → false (after leaving) |
| ARRIVING_OFFICE | `destinationCategory == OFFICE` and `routeRemainingSec <= 300` |
| ARRIVING_HOME | `destinationCategory == HOME` and `routeRemainingSec <= 300` |
| AIRPORT | `destinationCategory == AIRPORT` (once) |
| CHARGING | `charging` ≥ 5 s |
| NIGHT_DRIVE | `headlightsOn` ≥ 60 s while moving |
| CRUISE | `cruiseActive` ≥ 20 s |
| SCHOOL_ZONE | after leaving a SCHOOL_ZONE caution (inside → not inside for ≥ 5 s) |

### 4.5 (reserved)

Intentionally empty — the number is kept so that references to §4.6–§4.10 stay valid.

### 4.6 Commute (`RouteCommuteDetector(map)`)

1. If `originPlaceId`/`destinationPlaceId` are known: HOME→OFFICE = MORNING, OFFICE→HOME = EVENING (confidence 0.9, `routeId` from `map.routeBetween`).
2. Else destination category OFFICE → MORNING, HOME → EVENING (0.7).
3. Else time of day (KST): 05:00–10:59 → MORNING, 16:00–21:59 → EVENING (0.4), else OTHER (0.2).
`learn(pattern, ctx, departedMs)`: append `Kst.minuteOfDay(departedMs)` to morning/evening list (keep last 10), `trips + 1`. No coordinates are stored.

### 4.7 Commute map (`CommuteMaps.default`, english/drive/map/CommuteMapData.kt)

Fictional map “가상 출퇴근 지도 · 잠실 ↔ 판교”, version 1. Public landmarks only, coordinates with 3 decimals. Places (ids fixed — coach/tests use them): `place.home` “집 (잠실역 인근)” HOME ≈ (37.513, 127.100); `place.office` “회사 (판교테크노밸리)” OFFICE ≈ (37.401, 127.108); `place.charger` “탄천 공영 충전소 (가상)” CHARGER; `place.airport` “김포공항” AIRPORT ≈ (37.559, 126.802) (only for the airport demo). Routes: `route.morning` (home→office) and `route.evening` (office→home, same segments reversed with ids suffixed `.r` or the same ids — drive decides, but ids must be stable). Nominal 32 min / ≈ 22 km. Segments (ids `seg.*`, names Korean, each with speed limit + typical speed + events):

1. 집 앞 이면도로 (LOCAL, 30) → 2. 잠실역 사거리 (CITY, 50; MANEUVER LEFT near end) → 3. 잠실 어린이 보호구역 (SCHOOL_ZONE, 30; SCHOOL_ZONE zone + school-zone camera) → 4. 송파대로 (CITY, 60; SPEED_CAMERA) → 5. 탄천 강변길 (RIVERSIDE, 70) → 6. 도심 터널 구간 (TUNNEL, 70; TUNNEL zone) → 7. 분당수서로 고속 구간 (HIGHWAY, 80; cruise stretch, no events for ≥ 3 km) → 8. 판교 진입 정체 구간 (JAM, 60; JAM zone) → 9. 판교IC 진출로 (EXPRESSWAY_EXIT, 50; MANEUVER EXIT) → 10. 판교역로 (CITY, 50; MANEUVER RIGHT, then ARRIVE).

### 4.8 Simulator (`sim/CommuteSimulator.kt`, implements DemoControl)

Exposes `vehicle: VehicleGateway`, `navi: NaviGateway`, `location: LocationGateway` (legacy gateway interfaces, extended additively) plus the extra signals DrivingSignalHub needs. Route progress uses a **time scale** (default 6×; 1–20 via `setTimeScale`) — only distance/remaining time are scaled; `atMs`, hysteresis and hook timers stay real time. Events fire when the car passes their `atM`; steering ≈ 30–40° for ~3 s at turns, 0 on straights; cruise auto-on on HIGHWAY.

Scenarios: MORNING (P at `place.home` 2 s → D → `route.morning`, remaining 1920 s), EVENING (reverse from `place.office`), CHARGING (P at `place.charger`, charging, SoC rising), PARKED (P at `place.home`, no route). `stop()` → `scenario = null`.

Source selection (DrivingSignalHub): while `demo.scenario != null` every vehicle/navi field comes from the simulator (origin SIMULATOR, `uxRestricted = !parked`); when `scenario == null` → Pleos (origin PLEOS) if the Vehicle SDK is connected, else the simulator idling parked at `place.home`. AudioEnvironment flags are always merged (OR) with simulator injections.

DemoSignal semantics:
| Signal | Effect |
|---|---|
| TURN_ON / TURN_OFF | turn signal LEFT (RIGHT if next maneuver is RIGHT); auto-off after 10 s |
| INTERSECTION | maneuver RIGHT at 280 m counting down (speed ≥ 30); steering 35° for 3 s at 0 m; then cleared |
| HARD_BRAKE | accel −5.5 m/s² and brakePedal for 1.5 s, speed −25 km/h (≥ 0), recover over 5 s |
| RAIN_ON / RAIN_OFF | wipers on/off |
| JAM | trafficJam for 25 s (speed oscillating 0–12), then clears to typical speed (→ JAM_EXIT hook) |
| CRUISE_ON / CRUISE_OFF | cruise ACTIVATED, speed → 90, steering 0 / off |
| NAV_PROMPT | navVoicePrompt true for 3 s |
| ARRIVE | slow to 0 over 3 s, gear P, `arrived` 5 s, route cleared |
| NEAR_ARRIVAL | jump progress so remaining = 75 s (just before the 60 s lesson end, §7); time scale lowered to ≤ 2× so the closing line and the parked dashboard can be watched |
| TUNNEL | inTunnel 15 s |
| SCHOOL_ZONE | caution SCHOOL_ZONE inside 12 s, limit 30, speed 28 |
| NIGHT_ON / NIGHT_OFF | headlights |
| AIRPORT_DESTINATION | destination → `place.airport` (AIRPORT) |

Mirroring sim routes to the real car navigation (legacy `NaviHub.mirrorToCar`) **defaults to OFF** (`DriveModule.mirrorToCar = false`); integration binds it to `LearnerSettings.mirrorSimRouteToCar`.

### 4.9 Media keys (`MediaKeyHub`)

`android.media.session.MediaSession` (tag “YeopjariEnglish”), callback `onSkipToNext→NEXT`, `onSkipToPrevious→PREVIOUS`, `onPlay/onPause→PLAY_PAUSE`, PlaybackState with ACTION_SKIP_TO_NEXT|PREVIOUS|PLAY_PAUSE|PLAY|PAUSE; `setActive(true)` only while a session runs. `onKeyEvent(keyCode, down)` maps with `MediaKey.fromKeyCode`, emits on key-down (repeat 0) and returns true for media keys. Coach mapping: NEXT = SKIP, PREVIOUS = REPEAT, PLAY_PAUSE = PAUSE/RESUME toggle.

### 4.10 Vehicle feedback (`PleosVehicleFeedback`)

`pulse(kind)`: publish `AmbientPulse(id++, kind, now)` on `lastPulse` (always — the UI shows a 1 s screen-edge glow for the emulator). If Light API works: read `getAmbientLightBrightnessColor(SeatArea(SeatValue.ROW_1_LEFT))`, set `setAmbientLightBrightnessColor(area, color)` with `PulseKind.argb` (try RGB `0xRRGGBB` int; verify on emulator Virtual Console), restore the previous colour after 1 s; on any failure set `ambientSupported=false` and stop trying. Requires `pleos.car.permission.CONTROL_CAR_LIGHTS` (integration adds it). `showHudHint(text)`: publish on `hudHint` (in-app windshield HUD simulator; no public HUD API).

---

## 5. Speech

### 5.1 Normaliser (`EnglishNormalizer`)

lowercase → unify quotes (’ → ') → expand contractions (i'm→i am, you're→you are, it's→it is, that's→that is, let's→let us, can't→can not, won't→will not, don't→do not, doesn't→does not, didn't→did not, isn't→is not, aren't→are not, i'd→i would, i'll→i will, we'll→we will, i've→i have, we're→we are, they're→they are, couldn't→could not, wouldn't→would not, shouldn't→should not, what's→what is, there's→there is, how's→how is) → numbers to words (0–999, “10:30”→“ten thirty”, “2nd”→“second”, “%”→“percent”) → strip punctuation except apostrophes inside words → remove fillers: um, uh, er, ah, hmm, “you know”, utterance-initial “well”/“so”/“like”, and “like” only when utterance-initial or repeated (“like like”) — never inside “would like”, “i would like”, “looks like”, “feel like” → collapse spaces. Apply the same normalisation to accepted answers.

### 5.2 Judge (`RuleAnswerJudge`)

Thresholds are in `SpeechThresholds`. Empty / filler-only → NO_ANSWER (score 0, feedback “잘 안 들렸어요.”). Otherwise choose the accepted answer with the lowest word error (Levenshtein alignment on tokens; function words a/an/the/to/please weigh 0.5) and build `alignment`. `score = round(100*(1 - weightedErrors/weightedExpected)).coerceIn(0,100)`.

| Skill | CORRECT | PARTIAL | WRONG |
|---|---|---|---|
| VOCAB | heard (minus leading a/an/the/to) equals an answer, or contains it with ≤ 2 extra words | morphological variant (-s/-ed/-ing) or L1 confusion (R/L…) of an answer; score 70 | else |
| EXPRESSION, SENTENCE_BUILD | score ≥ 85 and all keywords present (keyword match allows -s/-ed/-ing) | score ≥ 50 or ≥ half keywords; missing keywords cap score at 84 | else |
| LISTEN_REPEAT | score ≥ 80 vs promptEn/answers | score ≥ 50 | else |
| TOEIC_QA | `score = round(100*(0.45k + 0.35l + 0.2g))` ≥ 70 where k = keywords hit / keywords, l = min(1, words/minWords), g = 1 − 0.2·grammarIssues (≥ 0) | ≥ 40 | < 40; fewer than 3 words → NO_ANSWER |
| MINIMAL_PAIR | heard contains target | similar but not target | heard contains `contrast` (+ PronIssue of `pronFocus`, confidence 0.9) |

Grammar heuristics (TOEIC_QA, each adds a `grammarNotesKo` line): “i am + base verb” (i am go), 3rd-person singular without -s (he go / she like), “did + past” (did went), “more + -er” (more better), missing subject at the start when the first word is a verb.
`pronIssues` = `pronunciation.analyze(item, alignment)`. `feedbackKo` (one sentence): CORRECT “정확해요!” (≥ 95 “완벽해요!”); PARTIAL “거의 맞았어요. ‘{missing}’가 빠졌어요.” or with a PronIssue “‘{expected}’가 ‘{heard}’로 들렸어요.”; WRONG “아쉬워요. 이렇게 말해요.”.
`judgeChoice(item, choice)` → CORRECT (score 100) if `choice` normalises to one of the answers, else WRONG (score 0).

### 5.3 Commands (`BilingualCommandParser`)

ANSWER context: the **whole** normalised utterance (after removing leading “please/can you/could you” and trailing “please”) must equal a phrase; Korean: utterance contains Hangul and contains a phrase. Longest phrase wins (“다시 시작” → RESUME, not REPEAT). YES/NO only in CONFIRM context.

| Command | English | Korean |
|---|---|---|
| REPEAT | repeat, again, one more time, say again, say it again, repeat that, repeat please, come again, pardon | 다시, 한 번 더, 다시 말해, 뭐라고 |
| SLOWER | slower, slowly, slow down, more slowly, speak slowly | 천천히, 느리게 |
| SKIP | skip, next, pass, next one, skip it, skip this | 넘겨, 넘어가, 다음, 패스, 건너뛰 |
| HINT | hint, give me a hint, clue | 힌트 |
| MEANING | meaning, what does it mean, what is the meaning, translate, in korean | 뜻, 무슨 뜻, 의미 |
| PAUSE | pause, stop, wait, hold on, hang on | 잠깐, 멈춰, 정지, 잠시만 |
| RESUME | resume, continue, go on, keep going, go ahead, let us continue | 계속, 다시 시작, 이어서 |
| DONT_KNOW | i do not know, no idea, i have no idea, i forgot, not sure | 모르겠, 몰라 |
| END_SESSION | end session, stop session, that is enough, quit, i am done, let us stop here | 그만, 끝내, 종료 |
| YES (CONFIRM) | yes, yeah, yep, sure, okay, ok, of course, sounds good, let us do it | 네, 응, 좋아, 그래, 예 |
| NO (CONFIRM) | no, nope, not now, no thanks, maybe later | 아니, 괜찮아, 싫어, 다음에 |

Also accept common romanisations from an English STT model (“dasi”→REPEAT, “geuman”→END_SESSION).

### 5.4 Pronunciation (`KoreanL1PronunciationAnalyzer`)

For each `SUB(expected e, heard h)` (and VOCAB whole-word mismatches): R_L if replacing r↔l positions turns one into the other (right/light, correct/collect, pray/play, road/load); F_P f↔p (fan/pan, coffee/copy, file/pile); V_B v↔b (very/berry, vote/boat, vest/best); TH_S “th”↔“s” (think/sink, thank/sank, mouth/mouse); TH_D “th”↔“d” (they/day, then/den, there/dare); Z_J z↔j or z↔g before e/i (zone/joan, zip/jip); SH_S “sh”↔“s” (she/see, ship/sip, shell/sell); FINAL_CONSONANT_DROP if `h == e` minus its final consonant(s) and `e.length >= 3` (book/boo, worked/work). Confidence 0.8 (digraph/letter swaps), 0.6 (final drop). Return ≤ 2 issues, highest confidence first, one per word, with `drill = MinimalPair(e, h)`.
Tips (`tip(type)`): R_L “R은 혀끝이 어디에도 닿지 않게 살짝 말고, L은 혀끝을 윗니 뒤에 붙여요.” F_P “F는 윗니를 아랫입술에 살짝 대고 바람을 내보내요.” V_B “V는 윗니를 아랫입술에 대고 목을 울려요. 두 입술을 붙이지 않아요.” TH_S “TH는 혀끝을 윗니 사이로 살짝 내밀고 바람을 내보내요.” TH_D “they의 TH는 혀를 이 사이에 두고 목을 울려요.” Z_J “Z는 S 소리에 목 울림만 더해요. ‘지’처럼 혀를 붙이지 않아요.” SH_S “SH는 입술을 동그랗게 내밀고 ‘쉬’, S는 입꼬리를 당겨 ‘스’.” FINAL_CONSONANT_DROP “끝소리를 삼키지 말고 짧게라도 들리게 마무리해요.” `pairs(type)` ≥ 4 pairs each.

### 5.5 Fluency (`EpdFluencyMeter`)

latency = speechStart − listenStart; speech = speechEnd − speechStart; wpm = words / (speech/60000) when speech ≥ 400 ms. Pauses: within [speechStart, speechEnd] take RMS samples, threshold = 0.35 × median RMS; runs below threshold ≥ 400 ms = pause. Rating: SMOOTH if latency ≤ 1500 and (wpm == null or ≥ 100) and pauses ≤ 1; HESITANT if latency > 4000 or wpm < 60 or pauses ≥ 3; else OK. `noteKo` e.g. “1.2초 만에 답했어요 · 분당 118단어”; `estimated = timing.injected` (note ends with “ (추정)”).

### 5.6 SpeechPort (`PleosSpeechPort`)

- **STT**: one `SpeechToText(context, Mode.ON_DEVICE)`, `initialize(OnInitializeListener)`, `addListener(ResultListener)`. Before each `request()` call `setLocale(Locale.US)` (EN) or `Locale.KOREA` (KO). `onReady` → `listenStartMs`; `onUpdated(text, completed)` → partial/final; `onUpdatedEpdData(on, off)` → treat as speech start/end markers (record arrival time with the clock; values’ units are unverified); `onUpdatedRms(rms)` → RmsSample; `onEndedRecognition` without final → Silence; `onError(ErrorType)`: UNAVAILABLE_LANGUAGE for US → `status.sttEnglish=false`.
- **Listen window**: `listen(lang, timeoutMs)` keeps the window open for the full `timeoutMs` even if the engine is unavailable or errored, so `inject()` can still answer (emulator demo). At timeout: final text → Heard; else partial → Heard(partial); else engine error → `Failed(code, reasonKo)`; else Silence. `stopAll()` → Stopped.
- **inject(text)**: if listening → complete with `Heard(text, SpeechTiming(listenStart, speechStart = listenStart+900, speechEnd = +380 ms per word, finalMs = now, injected = true))`; else keep for 15 s and return immediately from the next `listen()`.
- **TTS**: KO → Pleos `TextToSpeech(context, Mode.ON_DEVICE)` with `setLocale(Locale.KOREA)`, fallback Android TTS ko-KR. EN NORMAL → a second Pleos TTS instance with `setLocale(Locale.US)` if it initialises and never reports UNAVAILABLE_LANGUAGE, else Android TTS `Locale.US`. EN SLOW → Android TTS `Locale.US` at `0.75 × englishRate`; if unavailable, Pleos EN with commas between words. `speak` suspends until `onDone`/`onStop`/error (+ safety timeout = estimate × 2 + 3 s). Report engine names in `status`.
- **Audio focus**: request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (usage `USAGE_ASSISTANT`, content `SPEECH`) before speaking/listening, abandon after. Cancellation/`stopAll()` stops engines within 100 ms.
- **AndroidAudioEnvironment**: `navPromptActive` from `AudioManager.registerAudioPlaybackCallback` (any active config with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`); `focusLost` true on AUDIOFOCUS_LOSS* while we hold focus, for ≥ 3 s or until regained.

---

## 6. Learning

### 6.1 SRS (`SrsScheduler`, SM-2 lite)

`q = grade.quality`; `ease' = (ease + 0.1 − (5−q)(0.08 + (5−q)·0.02)).coerceIn(1.3, 3.0)`.
AGAIN → reps 0, lapses+1, interval 10 min (due = now + 10 min). Otherwise reps+1 and interval days: reps 1 → HARD 0.5 / GOOD 1 / EASY 2; reps 2 → HARD 2 / GOOD 3 / EASY 4; later → previous × ease' × (HARD 0.6 / GOOD 1.0 / EASY 1.3), max 180. `gradeFor`: CORRECT & latency < 1500 → EASY; CORRECT → GOOD; PARTIAL → HARD; WRONG/NO_ANSWER → AGAIN. `due` sorts by `dueAtMs` ascending.

### 6.2 Placement (`AdaptivePlacement`)

12 items (`PLACEMENT_ITEMS`), start at INTERMEDIATE; correct → next level up (cap ADVANCED), wrong → down (floor BEGINNER); never repeat an item; pick deterministically (track-specific and general alternate, by id order); if a level is exhausted use the nearest level with items. Result level = highest level with ≥ 2 answers and ≥ 60 % correct, else BEGINNER. `summaryKo` e.g. “12문항 중 8개 정답 · 중급부터 시작할게요.”

### 6.3 Progression (`UnitProgression`)

`record(state, outcome, content, now)` (pure):
1. Day = `Kst.epochDay(now)`. Streak: same day → unchanged; yesterday → +1 (`StreakExtended`); else 1. `bestStreak` = max.
2. Today’s `DayStat`: answered+1, correct/partial, `newLearned+1` if `isNew` and grade ≠ AGAIN, `reviewed+1` if !isNew, xp. Keep 30 days.
3. XP: CORRECT 10, PARTIAL 5, EASY bonus +2, checkpoint +50, level-up +100.
4. `cards[item.id] = scheduler.schedule(...)`.
5. Unit record (if `item.unitKey`): `scores[item.id]` = 1 / 0.5 / 0; when all unit items have a score and mean ≥ 0.8 and not yet passed → passed (`CheckpointPassed`, `UnitUnlocked(next)` if it exists).
6. Level-up: if the item is in the profile’s track & level and passed units at that level ≥ min(3, units at level) → `profile.level = next` (`LevelUp`).
7. Mistakes: verdict ≠ CORRECT → add (one per item per day), keep 7 days. Pron: count each `pronIssues` type, `recent` ≤ 20. Fluency: update today’s `FluencyDay` running averages when latency known.
8. `DailyGoalReached` when today’s answered crosses `profile.goal.items`.
`unlockedUnits`: unit 1 always; unit n+1 after n passed. `currentUnit`: first unlocked unpassed unit of the profile track/level. `snapshot`: fill `ProgressSnapshot` (week = last 7 days incl. empty days; dueReviews = cards due now; pronTop = top 3).

### 6.4 Planner (`CommuteSessionPlanner`)

Budget: route known and not parked → `routeRemainingSec × 0.35`; else 600 s; minimum 180 s. New share by mode: NEW_EXPRESSIONS 0.55, MIXED 0.4, REVIEW 0.2 (`reviewOnly` → 0). `new = floor(budget·share/60)` clamped 1..8 (REVIEW mode 0..2, reviewOnly 0); `review = floor((budget − new·60)/30)` clamped 0..30. Checks: 32-min morning → **새 표현 6개 + 복습 10개**; 32-min evening → **복습 18개 + 새 표현 2개**.
Review pool: (REVIEW/EVENING) today’s mistakes first (`MISTAKE`), then due cards most overdue (`REVIEW`), then lowest-ease learned cards if short. New items: unseen items of `progression.currentUnit`, then the next unlocked unit, then other units of the level. Driving plans keep ≥ 70 % of items with `maxLoad == NORMAL`. Order: NEW mode = 2 warm-up reviews, then alternate 1 new : 2 reviews; REVIEW mode = mistakes, reviews, then new. `pronDrill` → 6 `DRILL` items from `content.pronDrills(type)`. `titleKo`: “출근길 32분 → 새 표현 6개 + 복습 10개”, “퇴근길 32분 → 오늘 틀린 표현 3개 + 복습 15개 + 새 표현 2개”, no route “오늘의 세션 → 새 표현 5개 + 복습 10개”. `wrapUpItemId` = first NEW (else first MISTAKE). `extend` adds `count` more (reviews first) excluding `request.exclude`.

### 6.5 Repository (`VaultLearnerRepository`)

One encrypted file `english_learner_v1.bin` = UTF-8 `MiniJson` of the full `LearnerState` (enums by name, maps with string keys, `PronIssueType` keys by name). Unknown fields ignored, missing → defaults, undecodable → empty state. `update` applies on the caller thread and schedules a save (debounce 1 s) on `scope`; `flush` saves now; `eraseAll` deletes the file and resets. `VaultSecureStore` delegates to `SecureVault.write/read/delete/size`, `encrypted = vault.available`.

---

## 7. Coach (`DefaultEnglishCoach`) — state machine

One coroutine “turn loop” per session in the coach scope; load changes cancel the current turn job (structured concurrency → SpeechPort stops engines). All timestamps from `deps.clock`. UI state = `MutableStateFlow<EnglishUiState>`; update it on every phase change.

**Inputs collected in `start()`**: `repository.load()`; `signals.signals` → `loadEngine.update` → `load` + `rules = policy.rules(level)` + `drive` status + `screenMode` (FULL iff level == PARKED and !uxRestricted) + `routeStrip` (from `map`, current route/segment) ; `hooks.update(signals, load)` → hook queue; `mediaKeys.keys` → `onMediaKey`; `speech.status/partial/listening` → UI.

Phases: BOOTING → (profile == null) ONBOARDING → PLACEMENT (parked) → IDLE. Session: BRIEFING → [PROMPTING → LISTENING → JUDGING → FEEDBACK]* with HOLD/PAUSED/CONFIRM interludes → SUMMARY → IDLE. The evening ask (§7.1) and the parked dashboard (§7.2) are overlays on IDLE, not phases.

**Session start** (`startSession`): needs profile (else ActionResult fail “먼저 정차 중에 시작하기를 완료해 주세요.”). Commute = `commute.detect`. Mode = request.mode ?: settings.modeFor(slot). Plan = `planner.plan(PlanRequest(...))`. `mediaKeys.setActive(true)`. BRIEFING says (KO): “{slot.ko} {min}분, 새 표현 {n}개와 복습 {m}개로 시작할게요.” (REVIEW mode: “{slot.ko} {min}분, 오늘 틀린 표현부터 복습 {m}개, 새 표현 {n}개 할게요.”; no route: “오늘의 세션, 새 표현 {n}개와 복습 {m}개로 시작할게요.”); the screen shows `plan.titleKo`. **Commute start** (decided once per `driveId`): new `driveId` with MORNING/EVENING confidence ≥ 0.7, profile exists, no session → wait until moving ≥ 3 s and load ≤ NORMAL; do nothing if the route has < 180 s left. MORNING → start with origin AUTO_COMMUTE when `settings.autoStartOnCommute`. EVENING → follow `settings.eveningStudy` (§7.1; the default is to ask first, never to auto-start). A manual start while driving with ≤ 60 s of route left is refused (toast “곧 도착해요. 도착한 뒤에 시작해 주세요”). Placement not done → use profile level and add suggestion PLACEMENT_NEEDED (shown when parked).

**Per item**:
1. Pick the next queued item that `policy.allows(item, load.level)`; items not allowed now are deferred (stay in queue). If none is allowed: RELAXED/NORMAL → silently wait (re-check on every load change); queue empty → extend/finish logic.
2. PROMPTING: titleKo (PlanKind.ko) + `displayKo` = promptKo; `displayEn` (promptEn, else answers[0] for LISTEN_REPEAT) **only when parked** — while driving English is heard, never read (HUD shows one hint word); choices only when `rules.onScreenChoices` and distractors exist (stable order: sort by hash of item id). Speak promptKo (KO) then promptEn (EN) if present.
3. LISTENING: `hud = HudUi(hint = item.hintWord if settings.hudHint && rules.hudHint, icon LISTENING)` + `feedback.showHudHint`; `speech.listen(EN, min(item.listenSeconds, rules.maxListenSec)*1000)`.
4. Heard → `commands.parse(text, ANSWER)` → command handling, else JUDGING: `judge.judge(item, text)`, `fluency.measure(timing, text)`.
5. Attempts (max 2): attempt 1 CORRECT → grade `scheduler.gradeFor(CORRECT, latency)` (hint used → HARD). Attempt 1 PARTIAL/WRONG → FEEDBACK (feedbackKo + model answer in EN) + “한 번 더 말해 볼까요?” → attempt 2: CORRECT → HARD, else AGAIN, then model answer once more. DONT_KNOW → reveal (“이렇게 말해요.” + EN) → AGAIN. NO_ANSWER/Silence/Failed → once “다시 들려드릴게요.” and re-ask; second time → reveal, **no grade**, requeue once at the end (a second double-silence on the requeued copy → AGAIN).
6. Record: `progression.record(...)` via `repository.update` (exactly once per graded item, `isNew = card == null`); pulse GREEN (CORRECT) / AMBER (else) only if `settings.ambientPulse && rules.ambientPulse`; sound cue (§7.3); UI `feedback`, `pulse`; speak short praise for events (“레벨 업! 이제 {level.ko}예요.”, “유닛 통과! 다음 유닛이 열렸어요.”) only if load ≤ NORMAL.
7. Session bookkeeping for the dashboard (§7.2): `streak` (consecutive first-try CORRECT answers; any other graded result resets it), `bestStreak`, `mistakes` (items whose graded verdict was not CORRECT; a later CORRECT removes the item), pronunciation issues and response latencies.
8. Context hooks (if `settings.contextHooks`): between items and load ≤ NORMAL, deliver the oldest queued HookEvent: intro `content.hook(trigger).introKo`, then one allowed hook item as kind HOOK (prefer items without a card). AIRPORT → CONFIRM “공항 가시는 길이네요. 오늘은 여행 영어로 바꿔 볼까요?” (listen KO, `commands.parse(text, CONFIRM)`; YES → switch session track to TRAVEL by re-planning remaining new items) + suggestion card SWITCH_TRACK_TRAVEL.
9. Gap 600 ms between items.

**Load interruption** (observe `load.level` continuously):
- level → CRITICAL: `speech.stopAll()` now, cancel the turn, phase HOLD, `hud = HudUi(icon=HOLD)`, held item = current item (current attempt void, attempt counter kept, never graded).
- level → FOCUS: if PROMPTING/FEEDBACK/BRIEFING and remaining speech < 2 s → let it finish, then HOLD; otherwise `stopAll()` now → HOLD. LISTENING → stop listening immediately → HOLD.
- HOLD shows no question text, only “잠시 운전에 집중할게요” + load reasons.
- Load back to ≤ NORMAL (the engine already enforces 4 s stability) → wait 1.5 s → speak **“아까 그 문제 다시 할게요.”** → re-ask the held item from PROMPTING if `policy.allows` it at the new level; else keep it queued and continue with the next allowed item (“다음 문제로 갈게요.”). Count `interrupted` in SessionUi.

**Commands**: REPEAT (PREVIOUS key) re-speak prompt (+EN), attempt not consumed. SLOWER → re-speak the EN part with `SpeechRate.SLOW`. SKIP (NEXT key) → “넘어갈게요.”, no grade, next. HINT → speak KO “힌트!” then EN hintWord (two speak calls — never mix languages in one call), show on HUD, `hintShown`, max grade HARD. MEANING → “뜻은 ‘{meaningKo}’예요.” PAUSE (PLAY_PAUSE) → `stopAll`, PAUSED, “잠깐 멈출게요. ‘계속’이라고 하시면 이어서 할게요.” RESUME/PLAY_PAUSE in PAUSED → re-ask current item (“다시 할게요.”). END_SESSION → summary. In HOLD nothing is spoken: PAUSE → stays silent and becomes PAUSED (no automatic resume when the load drops), END_SESSION → summary without speech, everything else is ignored (resume after HOLD is automatic).

**onAnswerText(text)**: phases LISTENING → `speech.inject(text)`; PROMPTING/BRIEFING/FEEDBACK → `stopAll()`, go to LISTENING, then `inject` (barge-in emulation); HOLD/PAUSED/IDLE → if it parses as a command handle it, else toast “지금은 답을 받지 않아요” (no grading). CONFIRM → inject. `chooseOption(i)` (parked): stop listening, `judge.judgeChoice`, latency = now − prompt shown. `injectDemoAnswer(kind)` uses `DemoAnswers.make(item, kind)`: CORRECT = answers[0]; PARTIAL = answers[0] minus one keyword; WRONG = a distractor or “I have no idea about that.”; L1_ERROR = answers[0] with one word swapped by an L1 rule (r→l, f→p, v→b, th→s, z→j, sh→s; prefer `item.contrast` for MINIMAL_PAIR); DONT_KNOW = “I don't know”.

**Lesson end before arrival**: while a session runs, the route is active, the gear is not P and `routeRemainingSec ≤ 60` (`LearnThresholds.END_BEFORE_ARRIVAL_SEC`), the session ends at once (`EndReason.NEAR_ARRIVAL`): the current item is dropped without grading, nothing more is asked, phase SUMMARY, and the coach says “곧 도착해요. 오늘 학습은 여기까지 할게요.” (it waits up to 15 s for load ≤ NORMAL, otherwise stays silent). There is no spoken “오늘의 한 문장” turn while driving; the weakest short sentence of the session is only shown on the result screens (`bestSentence`). **Arrival** (`arrived`, or gear P with ≤ 300 s of route left or the route finished) → `EndReason.ARRIVED`. **Mid-way stop**: gear P after driving with ≥ 1 graded answer → `EndReason.PARKED` (with 0 answers the session continues as a parked lesson); if the same drive goes on afterwards, the commute start logic is re-armed. Every end: `progression.recordSession`; `commute.learn` for ARRIVED/NEAR_ARRIVAL; `mediaKeys.setActive(false)`; SUMMARY. Spoken line when load ≤ NORMAL: NEAR_ARRIVAL as above; USER/PLAN_DONE “오늘은 여기까지 할게요. {answered}문제 중 {correct}개 맞혔어요. 수고했어요!”; ARRIVED without answers “도착했어요. 오늘도 수고했어요!”; a session that will open the parked dashboard greets there instead (§7.2). Plan exhausted with > 120 s of route left → CONFIRM “준비한 문장을 다 했어요. 복습 몇 개 더 할까요?” YES → `planner.extend(..., 5)`; otherwise (declined, parked, no route, nothing to add) → finish (`PLAN_DONE`).

**Placement**: only when parked (else toast “정차 후 이용할 수 있어요”). `placement.start(track, content.placement(track))`; each `choosePlacementAnswer` → `answer`; show `lastCorrect` 0.8 s; finished → `result` → `finishPlacement` sets `profile.level`, `placementDone`.

**Suggestions/toasts**: PLACEMENT_NEEDED, PRON_DRILL (a PronIssueType seen ≥ 3 times, parked), REVIEW_DUE (≥ 10 due, parked), SWITCH_TRACK_TRAVEL, START_COMMUTE_SESSION (parked, commute pattern says departure within 15 min). Toast examples: “정차 후 이용할 수 있어요”, “표현을 저장했어요”, “학습 기록을 모두 지웠어요”.

**library** (`EnglishUiState.library`): rebuild when parked and the learner state changes — today’s mistakes, due-soon cards (≤ 20), saved, `pronunciation.tip/pairs` for `pron.counts` (≥ 1, most first), last sessions. **progress** = `progression.snapshot(...)`; **storage** = `repository.storageInfo()`.
**saveExpression(text)** → `saved` (dedupe, max 100) “표현을 저장했어요”. **reviewNow** → in session: move due reviews to the queue front; else `startReview()`. **eraseAllData** → stop session, `repository.eraseAll()`, phase ONBOARDING. **nextPlanPreviewKo** (IDLE) = planner preview for the next slot (before 12:00 KST → MORNING, else EVENING) with the last session’s route minutes or 32.

### 7.1 Evening commute ask (`EveningAskFlow`, `EnglishUiState.eveningAsk`)

The evening commute never starts a lesson by itself. Setting `LearnerSettings.eveningStudy` (“퇴근길 학습”): `ASK` 물어보기 (default) · `ALWAYS` 항상 시작 · `NEVER` 안 함. The morning commute keeps `autoStartOnCommute`.
- `ASK`: when the commute start condition holds for an EVENING drive, the coach speaks (KO) “퇴근길에도 영어 복습할까요?” and shows a card with two large buttons, “할게요” / “오늘은 쉴게요” (`eveningAsk = EveningAskUi(visible = true, attempt = 1, …)`, phase stays IDLE). After the question it listens (KO) for 10 s (`EVENING_ASK_TIMEOUT_MS`); `eveningAsk.listening` / `deadlineMs` drive the mic indicator.
- Answers (`EveningAnswers.parse`, then `commands.parse(text, CONFIRM)` as a fallback): yes = 응 · 네 · 예 · 좋아 · 할게 · 하자 · 그래 · yes · yeah · sure · okay (also 예스 · 오케이, because the Korean recogniser may transliterate); no = 아니 · 괜찮아 · 쉴게 · 다음에 · 나중에 · 안 할래 · no · nope · not today · no thanks · later. A refusal phrase wins over an acceptance in the same utterance (“다음에 할게요” → no). Unrecognised speech is ignored and listening continues for the rest of the window. The buttons call `answerEveningAsk(yes)`; typed/adb text (`onAnswerText`), `onCommand(YES/NO)`, the PLAY_PAUSE key (= yes) and the demo answer buttons take the same path.
- Yes → card hidden, session starts (origin AUTO_COMMUTE, evening mode). No → card hidden, “알겠어요. 오늘은 쉴게요.”, no session. The answer is remembered for that day: after a short stop the same evening commute is continued (yes) or left alone (no) without asking again.
- No answer within 10 s, or load ≥ FOCUS at any time during the question (speech and microphone stop immediately, card hidden) → wait 2 s and until load ≤ NORMAL, then ask once more (`attempt = 2`). If that also gets no answer or is interrupted → give up silently: no session, nothing spoken.
- The question is dropped without a word when the car is put in P, a session is started manually, a new drive begins, or < 180 s of route remain.

### 7.2 Parked dashboard (`EnglishUiState.dashboard`, `showParkedDashboard`)

Every finished session with ≥ 1 graded answer fills `dashboard: ParkedDashboardState` (`CoachViews.dashboard`), which stays available afterwards (Home “지난 학습 결과”). It is opened automatically — `showParkedDashboard = true`, phase IDLE, `summary = null` so the old summary sheet does not overlap — when a session **that involved driving** has ended and the screen becomes FULL (gear P, load PARKED, no UX restriction; because of the 4 s load hysteresis this is about 4 s after shifting to P). This covers arrival, a mid-way stop and a session that was finished earlier in the drive. Sessions done entirely while parked keep the summary sheet and do not open it by themselves. Driving off hides it; starting a session hides it.
- When it opens: `SoundCue.SESSION_COMPLETE`, then after 2.4 s (0.4 s if sound effects are off) the greeting — “도착했어요. 오늘 {answered}문제 중 {correct}개 맞혔어요. 수고했어요!” after an arrival, otherwise “오늘 {answered}문제 중 {correct}개 맞혔어요. 수고했어요!”.
- Results: `headlineKo` (“오늘도 수고했어요!”, evening “오늘 하루도 수고했어요!”), `subtitleKo`, `minutes`, `answered`, `correct`, `partial`, `wrong`, `skipped`, `interrupted`, `accuracyPercent` (CORRECT 1, PARTIAL 0.5), `avgResponseMs` (spoken answers only), `newExpressions`, `reviewed`, `bestStreak`, `pronWeakPoint` (most frequent issue type of the session with tip and an example: “‘right’ 대신 ‘light’처럼 들렸어요”), `mistakes` (expressions to review), `bestSentence`.
- Progress, always as before → after pairs so bars, ring and numbers can animate: `xpBefore → xpAfter` (`xpGained`), `levelProgressBefore → levelProgressAfter` with `level`, `leveledUp`, `levelGoalKo`; the unit studied at session start: `unitTitleKo`, `unitProgressBefore → unitProgressAfter`, `unitPassed`, `nextUnitTitleKo`; today's goal: `goalBefore → goalDone` of `goalTarget`; `streakDays`, `streakExtended`; `events`.
- API: `dismissDashboard()` (“홈으로”, data kept), `showDashboard()` (Home “지난 학습 결과”; parked only, no jingle), `reviewMistakes()` (“틀린 표현 복습”: closes the dashboard and starts a session with exactly `dashboard.mistakes` as MISTAKE items — “틀린 표현 {n}개를 다시 해 볼게요.”; falls back to `startReview()` when there were none).

### 7.3 Sound effects (`SoundPort`, `SoundCue`, `CoachContext.cue`)

`CoachDeps.sound: SoundPort` (default `NoSound`; the app passes `SoundPoolPlayer`). The coach plays a cue only if `settings.soundEffects` (“효과음”, default on) and the load level is not CRITICAL. Cues never take audio focus, so they mix over the coach's TTS instead of cutting it; the coach does not delay or stop speech for them.
- After judging an answer: CORRECT (bright two/three-note marimba arpeggio, ≤ 0.6 s) · PARTIAL (soft neutral tone) · WRONG (gentle low blip). No cue for silence, skip or “모르겠어요”.
- STREAK replaces CORRECT on every 5th consecutive first-try correct answer (`LearnThresholds.STREAK_CUE_EVERY`); `SessionUi.streak` shows the running count.
- LEVEL_UP right before the praise line “레벨 업! 이제 {level}이에요.” (load ≤ NORMAL only).
- SESSION_COMPLETE (≤ 2.5 s) when the parked dashboard opens automatically.
- Integration also stops any ringing cue when the level becomes CRITICAL and mirrors the setting into `SoundPoolPlayer.enabled`.

---

## 8. UI (`ui/english/**`)

### 8.1 Effective screen (in this order)
1. `!state.ready` → splash. 2. `profile == null` → ONBOARDING. 3. `phase == PLACEMENT` → PLACEMENT. 4. `screenMode == DRIVE` → **DRIVE** (all other routes locked). 5. session phase and parked → LESSON (unless the user picked another route since). 6. `phase == SUMMARY` → summary sheet over the current route. 7. else `navigator.route`. Overlays above the effective screen: the parked dashboard when `showParkedDashboard && dashboard != null` and the screen is FULL (it takes the place of the summary sheet), and the evening ask card when `eveningAsk.visible` (also on DRIVE: one question, two large buttons, no animation).
While moving the nav rail stays visible but non-DRIVE items show “정차 후 이용할 수 있어요” instead of navigating.

### 8.2 Car UX rules
Dark automotive theme; drive prompt text ≥ 34 sp; touch targets ≥ 76 dp; max 4 buttons on the drive screen; no scrolling or lists while moving; partial transcripts only when parked; animation ≤ 1 s and none behind text while moving. Load colours: PARKED cyan/blue, RELAXED green, NORMAL teal, FOCUS amber, CRITICAL red (define tokens in `ui/theme` — keep legacy `Y`, `YeopjariTheme`, `YTypography`, `YShapes` names).

### 8.3 Screens
- **Onboarding**: track cards (4, with Korean descriptions), daily goal (3), privacy note “학습 기록은 이 차 안에만 암호화해 저장돼요”, start → `completeOnboarding` → `startPlacement` or `skipPlacement(level)`.
- **Placement**: “{index+1}/12”, questionKo, questionEn, 3–4 big choice buttons, correct/incorrect flash, result card (level, correct/total, summaryKo) → `finishPlacement`.
- **Home** (parked): today ring (todayAnswered/todayGoal), streak, XP, level + unit progress, due reviews, `nextPlanPreviewKo`, big “지금 시작” (`startSession`), suggestion card, saved expressions count, “지난 학습 결과” (`showDashboard()`, shown when `dashboard != null`).
- **Drive**: top: load chip (level colour + up to 2 reasons) · speed · slot · remaining minutes; HUD strip (hint keyword + icon); centre: PlanKind title + huge `displayKo` (+ `displayEn` when present); mic ring for LISTENING; feedback glyph (✓ / ↻) with verdict colour; bottom buttons: 다시(REPEAT) · 힌트(HINT) · 넘기기(SKIP) · 일시정지/계속. HOLD: “잠시 운전에 집중할게요” + reasons only. Route strip (segments with current marker). Screen-edge glow when `pulse.id` changes (green/amber, 1 s).
- **Lesson** (parked session): same flow with choices, explanationKo, alignment (green/red words), pron issues with tip + “발음 연습” (`startPronDrill`), fluency note.
- **Review**: due count, `library.mistakesToday`, `library.dueSoon`, `library.saved`, “복습 시작” (`startReview`).
- **Report**: `progress.week` bars (answered/correct), accuracy, fluency (`progress.fluency`), pronunciation profile (`library.pronTips` + drill buttons → `startPronDrill`), `library.recentSessions`.
- **Settings**: track, goal, morning/evening/other mode (SlotMode), morning auto-start, “퇴근길 학습” (물어보기 / 항상 시작 / 안 함), “효과음”, ambient pulse, HUD hint, English voice speed, context hooks, “시뮬레이터 경로를 실제 내비에 반영” (default off), speech engine status, storage info, “학습 기록 모두 지우기” (confirm dialog) → `eraseAllData`.
- **Summary sheet**: SessionSummaryUi (sessions done while parked).
- **Parked dashboard** (full screen, §7.2): big headline, result tiles, progress (level bar, unit bar, today-goal ring, streak, XP count-up), an original mascot animation drawn in Compose with confetti, buttons “틀린 표현 복습” (`reviewMistakes`) and “홈으로” (`dismissDashboard`).
- **Evening ask card** (§7.1): question + “할게요” / “오늘은 쉴게요” (`answerEveningAsk`), mic indicator while `eveningAsk.listening`.
- **Demo panel** (only if `state.demoEnabled && demo != null`, floating button): scenarios (4), signals (all DemoSignal), answers (DemoAnswerKind + command phrases: “repeat”, “slower”, “skip”, “hint”, “meaning”, “pause”, “continue”, “다시”, “넘겨”), media keys (via `coach.onMediaKey`), time scale.

---

## 9. Demo / PT support

Gate (integration): `demoAllowed = (applicationInfo.flags and FLAG_DEBUGGABLE) != 0 || Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1`. When false: no demo panel, all extras below ignored.

MainActivity extras:
```
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es en_scenario MORNING
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es signal TURN_ON
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es hear "Can we push the meeting to tomorrow?"
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es key NEXT
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es screen REPORT
```
`en_scenario` → `demo.play(DemoScenario.valueOf)`; `signal` → `demo.inject(DemoSignal.valueOf)`; `hear` → `coach.onAnswerText`; `key` → `drive.mediaKeys.inject(MediaKey.valueOf)` (same path as the steering-wheel buttons; the UI demo panel uses `coach.onMediaKey`); `screen` → `navigator.go(EnglishRoute.valueOf)`. Invalid values ignored.

Suggested PT flow: MORNING → briefing “출근길 32분 → 새 표현 6개 + 복습 10개” → NORMAL question → inject CORRECT (green pulse) → TURN_ON (HOLD, “방향지시등”) → TURN_OFF → “아까 그 문제 다시 할게요” → TUNNEL → hook → CRUISE_ON (RELAXED, TOEIC question) → HARD_BRAKE (CRITICAL, TTS cut) → L1_ERROR answer (R/L feedback) → JAM → JAM_EXIT hook “I'm stuck in traffic” → NEAR_ARRIVAL (“곧 도착해요. 오늘 학습은 여기까지 할게요.”) → arrival, P → parked dashboard (mascot + jingle) → “틀린 표현 복습” or “홈으로” → Report. Evening: EVENING → “퇴근길에도 영어 복습할까요?” → say “응” or tap “할게요”.

**Speaking to the emulator.** The emulator does not pass the host microphone to apps by default. After every emulator boot run `adb -s emulator-5554 emu avd hostmicon` (same as Extended controls → Microphone → “Virtual microphone uses host audio input”), and allow microphone access for desktop apps in the host OS. The app itself holds no `RECORD_AUDIO` permission — audio reaches it only as text through the Pleos SpeechToText service (`pleos.car.permission.STT_SERVICE`). The emulator has no echo cancellation, so use earphones or the coach's own TTS is picked up again. Quick check: answer a question while parked and watch the partial transcript; if nothing appears, one of the three switches is off (the demo panel and `--es hear "…"` keep working without a microphone).

---

## 10. Gleo App Schema (integration; max 3 functions)

```json
{ "name": "START_ENGLISH_SESSION", "description": "Start a hands-free English practice session that adapts to driving load.",
  "parameters": { "type": "object", "properties": { "track": { "type": "string", "enum": ["business", "daily", "travel", "toeic_speaking"], "description": "Optional learning track." } } }, "component": "activity" }
{ "name": "REVIEW_NOW", "description": "Review the expressions that are due now, including today's mistakes.",
  "parameters": { "type": "object", "properties": {} }, "component": "activity" }
{ "name": "SAVE_EXPRESSION", "description": "Save the current English expression (or the given text) to the learner's notebook.",
  "parameters": { "type": "object", "properties": { "text": { "type": "string", "description": "Optional expression text." } } }, "component": "service" }
```
`Track.parse()` handles the track string. Respond with `ActionResult.messageEn` (Gleo callback within 7 s, mode “silent” because the app speaks itself).

---

## 11. Content JSON schema (content agent)

Files (`ContentFiles`): `content/business.json`, `content/daily.json`, `content/travel.json`, `content/toeic_speaking.json`, `content/placement.json`, `content/context_hooks.json`, `content/pronunciation.json`. UTF-8, valid JSON (MiniJson tolerates trailing commas, but don't rely on it).

### 11.1 Track file
```json
{
  "schema": 1,
  "track": "BUSINESS",
  "titleKo": "비즈니스 영어",
  "units": [
    { "level": "BEGINNER", "unit": 1, "titleKo": "회의 일정 잡기", "titleEn": "Scheduling meetings", "items": [ /* exactly 10 items */ ] }
  ]
}
```
Item fields (track/level/unit come from the enclosing file/unit):

| field | type | rule |
|---|---|---|
| id | string | unique across all files, `^[a-z0-9]+(\.[a-z0-9_]+)+$` (e.g. `biz.b1.03`) |
| skill | Skill | required |
| promptKo | string | required; ≤ 40 chars when maxLoad is NORMAL/RELAXED (it is read aloud while driving), ≤ 80 when PARKED |
| promptEn | string? | required for LISTEN_REPEAT (the sentence), TOEIC_QA (the question), MINIMAL_PAIR (target word/short phrase); null otherwise |
| answers | string[] | ≥ 1; VOCAB/EXPRESSION/SENTENCE_BUILD give 2–4 natural variants; LISTEN_REPEAT = [promptEn, contraction variant?]; TOEIC_QA = 1–2 model answers |
| keywords | string[] | lowercase base forms present in answers[0]; TOEIC_QA ≥ 3, others 1–3 |
| hintWord | string? | one English word from answers[0] (required except MINIMAL_PAIR) |
| meaningKo | string | required |
| explanationKo | string? | ≤ 80 chars |
| maxLoad | "PARKED"\|"RELAXED"\|"NORMAL" | default NORMAL. TOEIC_QA → RELAXED; answers[0] > 12 words → RELAXED; > 20 words → PARKED |
| tags | string[] | topic tags |
| distractors | string[] | 2–3 for VOCAB/EXPRESSION/SENTENCE_BUILD (same form as the answer, plausibly wrong) |
| contextHook | HookTrigger? | only in context_hooks.json |
| minWords | int | TOEIC_QA 15 (BEGINNER) / 25 / 35 |
| listenSec | int? | TOEIC_QA 30 (BEGINNER) / 40 / 45 |
| contrast | string? | MINIMAL_PAIR confusable word |
| pronFocus | PronIssueType? | MINIMAL_PAIR required |

Volume (minimum; more is better if time allows): per track BEGINNER ≥ 3 units, INTERMEDIATE ≥ 2, ADVANCED ≥ 2, each exactly 10 items (≥ 280 track items in total). Unit mix (non-TOEIC): 3 VOCAB, 2 EXPRESSION, 3 SENTENCE_BUILD, 1 LISTEN_REPEAT, 1 MINIMAL_PAIR; TOEIC track: 2 VOCAB, 1 EXPRESSION, 2 SENTENCE_BUILD, 1 LISTEN_REPEAT, 4 TOEIC_QA. ≥ 6 of 10 items per unit have maxLoad NORMAL. Natural Korean (해요체 prompts like “‘…’를 영어로 말해 보세요.”), natural English, no personal data, no brand names.

### 11.2 Examples (two per item type)

VOCAB
```json
{ "id": "biz.b1.01", "skill": "VOCAB", "promptKo": "‘미루다, 연기하다’를 영어 한 단어로?", "promptEn": null,
  "answers": ["postpone", "delay"], "keywords": ["postpone"], "hintWord": "postpone", "meaningKo": "미루다, 연기하다",
  "explanationKo": "회의·일정을 뒤로 미룰 때 postpone을 써요.", "maxLoad": "NORMAL", "tags": ["meeting", "schedule"],
  "distractors": ["prepare", "propose"], "contextHook": null, "minWords": 0, "listenSec": null, "contrast": null, "pronFocus": null }
{ "id": "trv.b1.02", "skill": "VOCAB", "promptKo": "‘수하물’을 영어로?", "promptEn": null,
  "answers": ["baggage", "luggage"], "keywords": ["baggage"], "hintWord": "baggage", "meaningKo": "수하물, 짐",
  "explanationKo": "공항에서는 baggage와 luggage를 둘 다 써요.", "maxLoad": "NORMAL", "tags": ["airport"],
  "distractors": ["package", "passage"], "contextHook": null, "minWords": 0, "listenSec": null, "contrast": null, "pronFocus": null }
```
EXPRESSION
```json
{ "id": "day.b1.04", "skill": "EXPRESSION", "promptKo": "약속에 늦을 것 같을 때 한마디?", "promptEn": null,
  "answers": ["I'm running late.", "I'm running a little late.", "I'm going to be late."], "keywords": ["late"], "hintWord": "running",
  "meaningKo": "좀 늦을 것 같아요.", "explanationKo": "run late는 ‘예정보다 늦어지다’라는 뜻이에요.", "maxLoad": "NORMAL",
  "tags": ["time"], "distractors": ["I'm running fast.", "I'm late running."], "contextHook": null, "minWords": 0, "listenSec": null, "contrast": null, "pronFocus": null }
{ "id": "biz.i1.05", "skill": "EXPRESSION", "promptKo": "회의를 마무리할 때 ‘오늘은 여기까지 하죠’", "promptEn": null,
  "answers": ["Let's call it a day.", "Let's wrap it up for today."], "keywords": ["call", "day"], "hintWord": "call",
  "meaningKo": "오늘은 여기까지 하죠.", "explanationKo": "call it a day는 하루 일을 끝낼 때 쓰는 관용 표현이에요.", "maxLoad": "NORMAL",
  "tags": ["meeting"], "distractors": ["Let's call the day.", "Let's make it a day."], "contextHook": null, "minWords": 0, "listenSec": null, "contrast": null, "pronFocus": null }
```
SENTENCE_BUILD
```json
{ "id": "biz.b1.06", "skill": "SENTENCE_BUILD", "promptKo": "‘회의를 내일로 미룰 수 있을까요?’를 영어로", "promptEn": null,
  "answers": ["Can we push the meeting to tomorrow?", "Could we move the meeting to tomorrow?", "Can we postpone the meeting until tomorrow?"],
  "keywords": ["meeting", "tomorrow"], "hintWord": "push", "meaningKo": "회의를 내일로 미룰 수 있을까요?",
  "explanationKo": "push A to B는 일정을 B로 미룰 때 자주 써요.", "maxLoad": "NORMAL", "tags": ["meeting", "schedule"],
  "distractors": ["Can we pull the meeting tomorrow?", "Can we push tomorrow to the meeting?"], "contextHook": null, "minWords": 0, "listenSec": null, "contrast": null, "pronFocus": null }
{ "id": "trv.i1.07", "skill": "SENTENCE_BUILD", "promptKo": "‘창가 자리로 바꿀 수 있을까요?’를 영어로", "promptEn": null,
  "answers": ["Could I change to a window seat?", "Can I switch to a window seat?", "Is it possible to change to a window seat?"],
  "keywords": ["window", "seat"], "hintWord": "window", "meaningKo": "창가 자리로 바꿀 수 있을까요?",
  "explanationKo": "좌석을 바꿀 땐 change to / switch to를 써요.", "maxLoad": "NORMAL", "tags": ["airport", "flight"],
  "distractors": ["Could I change a window seat?", "Can I sit to a window?"], "contextHook": null, "minWords": 0, "listenSec": null, "contrast": null, "pronFocus": null }
```
LISTEN_REPEAT
```json
{ "id": "biz.b2.09", "skill": "LISTEN_REPEAT", "promptKo": "듣고 따라 말해 보세요.", "promptEn": "Let's touch base next week.",
  "answers": ["Let's touch base next week.", "Let us touch base next week."], "keywords": ["touch", "base"], "hintWord": "touch",
  "meaningKo": "다음 주에 잠깐 연락해요.", "explanationKo": "touch base는 짧게 연락·확인할 때 써요.", "maxLoad": "NORMAL",
  "tags": ["email"], "distractors": [], "contextHook": null, "minWords": 0, "listenSec": 10, "contrast": null, "pronFocus": null }
{ "id": "day.i2.09", "skill": "LISTEN_REPEAT", "promptKo": "듣고 따라 말해 보세요.", "promptEn": "Traffic is really heavy this morning.",
  "answers": ["Traffic is really heavy this morning."], "keywords": ["traffic", "heavy"], "hintWord": "heavy",
  "meaningKo": "오늘 아침 차가 정말 많이 막혀요.", "explanationKo": "교통이 막힐 땐 heavy traffic이라고 해요.", "maxLoad": "NORMAL",
  "tags": ["traffic"], "distractors": [], "contextHook": null, "minWords": 0, "listenSec": 10, "contrast": null, "pronFocus": null }
```
TOEIC_QA
```json
{ "id": "toe.b1.07", "skill": "TOEIC_QA", "promptKo": "토익 스피킹 질문이에요. 30초 동안 답해 보세요.", "promptEn": "How do you usually get to work, and how long does it take?",
  "answers": ["I usually drive to work. It takes about thirty minutes, and I listen to English lessons on the way."],
  "keywords": ["usually", "drive", "minutes", "work"], "hintWord": "usually", "meaningKo": "보통 어떻게 출근하고, 얼마나 걸리나요?",
  "explanationKo": "빈도 부사 usually와 It takes about ~를 쓰면 답이 자연스러워요.", "maxLoad": "RELAXED", "tags": ["commute"],
  "distractors": [], "contextHook": null, "minWords": 15, "listenSec": 30, "contrast": null, "pronFocus": null }
{ "id": "toe.i2.08", "skill": "TOEIC_QA", "promptKo": "토익 스피킹 질문이에요. 40초 동안 답해 보세요.", "promptEn": "What is the best way to learn a new skill? Give a reason and an example.",
  "answers": ["I think the best way is to practice a little every day. For example, I practice English during my commute, so I never skip a day."],
  "keywords": ["best", "practice", "example", "because"], "hintWord": "practice", "meaningKo": "새 기술을 배우는 가장 좋은 방법은? 이유와 예를 들어 답하기",
  "explanationKo": "I think → because → For example 순서로 말하면 구조가 잡혀요.", "maxLoad": "RELAXED", "tags": ["opinion"],
  "distractors": [], "contextHook": null, "minWords": 25, "listenSec": 40, "contrast": null, "pronFocus": null }
```
MINIMAL_PAIR
```json
{ "id": "biz.b1.10", "skill": "MINIMAL_PAIR", "promptKo": "R 발음 연습. ‘light’가 아니라 ‘right’예요.", "promptEn": "right",
  "answers": ["right"], "keywords": ["right"], "hintWord": null, "meaningKo": "오른쪽, 옳은",
  "explanationKo": "R은 혀끝이 입천장에 닿지 않게 말아요.", "maxLoad": "NORMAL", "tags": ["pronunciation"],
  "distractors": [], "contextHook": null, "minWords": 0, "listenSec": 6, "contrast": "light", "pronFocus": "R_L" }
{ "id": "pron.th_s.01", "track": "DAILY", "level": "BEGINNER", "skill": "MINIMAL_PAIR", "promptKo": "TH 발음 연습. ‘sink’가 아니라 ‘think’예요.", "promptEn": "think",
  "answers": ["think"], "keywords": ["think"], "hintWord": null, "meaningKo": "생각하다",
  "explanationKo": "혀끝을 윗니 사이로 살짝 내밀고 바람을 내보내요.", "maxLoad": "NORMAL", "tags": ["pronunciation"],
  "distractors": [], "contextHook": null, "minWords": 0, "listenSec": 6, "contrast": "sink", "pronFocus": "TH_S" }
```

### 11.3 Placement (`content/placement.json`)
```json
{ "schema": 1, "items": [
  { "id": "pl.g.b.01", "track": null, "level": "BEGINNER", "skill": "VOCAB", "questionKo": "‘회의’에 알맞은 단어를 고르세요.", "questionEn": null,
    "choices": ["meeting", "message", "machine"], "answerIndex": 0, "explanationKo": "meeting은 회의예요.", "audioEn": null },
  { "id": "pl.biz.i.01", "track": "BUSINESS", "level": "INTERMEDIATE", "skill": "SENTENCE_BUILD", "questionKo": "빈칸에 알맞은 말을 고르세요.", "questionEn": "Could you ___ me the report by Friday?",
    "choices": ["send", "say", "tell", "speak"], "answerIndex": 0, "explanationKo": "보고서를 ‘보내다’는 send예요.", "audioEn": null }
] }
```
Volume: general (track null) ≥ 8 per level; each track ≥ 4 per level (≥ 72 items total).

### 11.4 Context hooks (`content/context_hooks.json`) — items carry their own track/level, unit 0
```json
{ "schema": 1, "hooks": [
  { "trigger": "RAIN", "introKo": "비가 쏟아지네요. 이럴 땐 이렇게 말해요.", "items": [
    { "id": "hook.rain.01", "track": "DAILY", "level": "BEGINNER", "skill": "EXPRESSION", "promptKo": "‘비가 억수같이 와요’를 영어로", "promptEn": null,
      "answers": ["It's pouring.", "It's pouring rain.", "It's raining really hard."], "keywords": ["pouring"], "hintWord": "pouring",
      "meaningKo": "비가 억수같이 와요.", "explanationKo": "pour는 ‘쏟아지다’. 비가 세차게 올 때 써요.", "maxLoad": "NORMAL",
      "tags": ["weather"], "distractors": ["It's pouring water.", "It's raining cats."], "contextHook": "RAIN", "minWords": 0, "listenSec": null, "contrast": null, "pronFocus": null } ] },
  { "trigger": "JAM_EXIT", "introKo": "드디어 정체를 빠져나왔네요. 방금 같은 상황, 영어로는요.", "items": [
    { "id": "hook.jam.01", "track": "DAILY", "level": "BEGINNER", "skill": "SENTENCE_BUILD", "promptKo": "‘차가 막혀서 꼼짝 못 했어요’를 영어로", "promptEn": null,
      "answers": ["I was stuck in traffic.", "I got stuck in traffic."], "keywords": ["stuck", "traffic"], "hintWord": "stuck",
      "meaningKo": "차가 막혀서 꼼짝 못 했어요.", "explanationKo": "be stuck in traffic은 늦은 이유를 말할 때 딱이에요.", "maxLoad": "NORMAL",
      "tags": ["traffic"], "distractors": ["I was stuck on traffic.", "I stuck the traffic."], "contextHook": "JAM_EXIT", "minWords": 0, "listenSec": null, "contrast": null, "pronFocus": null } ] }
] }
```
Volume: every HookTrigger present with ≥ 2 items (ARRIVING_OFFICE → BUSINESS small talk, AIRPORT → TRAVEL, TUNNEL → tunnel/road vocab).

### 11.5 Pronunciation (`content/pronunciation.json`)
`{ "schema": 1, "items": [ MINIMAL_PAIR items with track/level, ≥ 3 per PronIssueType ] }` (see the second MINIMAL_PAIR example).

`JsonContentSource` validates on load: unique ids, required fields, enum names, answers non-empty, `maxLoad` ∈ {PARKED, RELAXED, NORMAL}, MINIMAL_PAIR has `pronFocus`. Invalid items are skipped (count them in a `problems` list for tests), never crash.

---

## 12. Pleos SDK signatures (javap-verified, 2026-11-15)

Callbacks are Kotlin lambdas `(T?) -> Unit` / `(Exception) -> Unit`; listener interfaces extend `VehicleListener { fun onFailed(e: Exception) }`. Areas: `SeatArea(vararg SeatValue)`, `WindowArea(vararg WindowValue)`, `TurnSignalArea(vararg TurnSignalValue)`.

**Vehicle 2.3.2** (`ai.pleos.playground.vehicle.Vehicle(context)`, `initialize()`, `release()`; getters `getGear() getBrake() getTurnSignal() getSteeringWheel() getDrivingMode() getWiper() getLight() getEvBattery() getOdometer() …`)
```
api.TurnSignal   getCurrentTurnSignalState((TurnSignalValue?)->Unit, (Exception)->Unit); registerTurnSignalState(TurnSignalStateListener); unregister…   [CAR_LIGHTS]
  TurnSignalStateListener.onTurnSignalStateUpdated(TurnSignalValue?)      TurnSignalValue = OFF | RIGHT | LEFT
api.SteeringWheel getCurrentSteeringWheelAngle((Float?)->Unit, fail); registerSteeringWheelAngle(SteeringWheelAngleListener)   [CAR_DRIVE]
  SteeringWheelAngleListener.onSteeringWheelAngleUpdated(Float?)
api.Brake        getCurrentBrakePedalStatus((Boolean?)->Unit, fail); registerBrakePedalStatus(BrakePedalStatusListener)   [CAR_DRIVE]
  BrakePedalStatusListener.onBrakePedalStatusUpdated(Boolean?)
api.DrivingMode  getCruiseControlState((CruiseControl?)->Unit, fail); registerCruiseControlState(CruiseControlStateListener);
                 getCruiseControlTargetSpeed((Float?)->Unit, fail); registerCruiseControlTargetSpeed(…); getCurrentDrivingModeState(…)   [CAR_DRIVE]
  CruiseControlStateListener.onCruiseControlStateUpdated(CruiseControl?)  CruiseControl = OTHER | ENABLED | ACTIVATED | USER_OVERRIDE | SUSPENDED | FORCED_DEACTIVATION_WARNING
  DrivingModeType = NORMAL | ECO | SPORT
api.Wiper        getCurrentWiperState(WindowArea, (WiperState?)->Unit, fail); registerWiperState(WindowArea, WiperStateListener)   [CAR_WINDOWS]
  WiperStateListener.onWiperStateUpdated(WiperState?)  WiperState = OTHER | OFF | ON | SERVICE   WindowValue.FRONT_WINDSHIELD
api.Light        registerLowBeamLightState(LightStateListener); getCurrentLowBeamLightState((LightState?)->Unit, fail)   [CAR_LIGHTS]
  LightStateListener.onLightStateUpdated(LightState?)  LightState = OFF | ON | DAYTIME_RUNNING
                 getAmbientLightBrightnessColor(SeatArea, (Int?)->Unit, fail)   [CAR_LIGHTS]
                 setAmbientLightBrightnessColor(SeatArea, Int, (SeatArea, Int?)->Unit, fail)   [CONTROL_CAR_LIGHTS]
                 setAmbientLightSwitch(SeatArea, LightSwitch, …) [CONTROL_CAR_LIGHTS]; checkAmbientLightBrightnessColorCapabilities((CapabilityConfig, List<SeatArea>)->Unit, fail)
api.Gear         getCurrentGearSelectionState((VehicleGear?)->Unit, fail); registerGearSelection(GearSelectionListener{ onSelected(VehicleGear?) })
  VehicleGear = GEAR_UNKNOWN | GEAR_NEUTRAL | GEAR_REVERSE | GEAR_PARK | GEAR_DRIVE | GEAR_1..GEAR_9
api.EvBattery    getEvBatteryStateOfCharge((Int?)->Unit, fail); registerEvBatteryStateOfCharge(EvBatteryStateOfChargeListener{ onEvBatteryStateOfChargeUpdated(Int?) });
                 getEvChargeSwitchState((Boolean?)->Unit, fail); getEvBatteryCapacity((Float?)->Unit, fail)   [CAR_ENERGY]
```
**NaviHelper 3.0.5** (`NaviHelper(context)`, `initialize(onReady: () -> Unit, onError: (Throwable) -> Unit)`, `addListener/removeListener(NaviHelperEventListener)`, `release()`; requests `getTBTInfo() getDestinationInfo() getRouteStateInfo() getCurrentLocationInfo() getWaypointInfo() requestRoute(RouteInfo) cancelRoute()`; **never** `getInternalApi()`). Listener methods all have default bodies:
```
onTBTInfo(List<TBTInfo>)            TBTInfo(type: TurnType, distance: Double /*m*/, description: String)
onCameraAlert(List<CameraAlert>)    CameraAlert(id: String, type: CameraType, status: Boolean, speedLimit: Int, remainDistance: Int, sectionAvrSpeed: Int, isOverSpeed: Boolean, isOverSectionAvrSpeed: Boolean)
onCautionPointInfo(List<CautionPoint>)  CautionPoint(id: String, type: CautionPointType, remainDistance: Int)
onCurrentRoadSpeedLimit(Int)
onDestinationInfo(DestinationInfo)  latitude, longitude: Double; displayLatitude/displayLongitude: Double?; name, address: String; remainDistance: Int /*m*/; remainTime: Int /*s*/; tollFare: Int; poiId, poiSubId, poiProvider: String
onRouteStateInfo(RouteStateInfo)    termsAgreement, naviMode: NaviMode(NOT_WORKING|SAFE_GUIDING|ROUTE_GUIDING|SIMULATION_GUIDING), isRouting, routeOption, remainDestinationChargingInt, remainWaypointChargingInt: List<Int>, trafficStatus: TrafficStatus(summary, congestionItemList, trafficEventList)
onNavigationStatus(NavigationStatus) INACTIVE | INITIALIZING | FREE_DRIVE | ROUTE_DRIVE
onCurrentLocationInfo(CurrentLocationInfo) latitude, longitude, heading: Double; address, routeId: String; currentRouteIndex: Int?
onDestinationArrived(DestinationArrivedInfo) duration, distance: Int; name, address: String
onRouteStarted(RouteStartInfo) onRouteEnded() onRouteCancelled() onDrivingInfo(DrivingInfo) onTripInfo(TripInfo) onError(NaviErrorCode) …
TrafficCongestionType = UNKNOWN | FAST | MODERATE | SLOW | HEAVY
```
TurnType / CameraType / CautionPointType constants: see §4.1 maps.

**SpeechToText 3.3.1**: `SpeechToText(context, Mode.ON_DEVICE)`, `initialize(OnInitializeListener{ onSuccess(); onFailure(ErrorType) })`, `addListener/removeListener(ResultListener)`, `request()`, `stop()`, `setLocale(Locale)`, `release()`. `ResultListener { onReady(); onStartedRecognition(); onUpdated(text: String, completed: Boolean); onUpdatedEpdData(on: Long, off: Long); onUpdatedRms(rms: Double); onEndedRecognition(); onError(ErrorType) }`. ErrorType = AUDIO_ERROR, MIC_ERROR, SERVER_INTERNAL_ERROR, NETWORK_ERROR, INVALID_REQUEST_ERROR, TIMEOUT, UNAUTHENTICATED, PERMISSION_DENIED, ON_DEVICE_ENGINE_ERROR, UNKNOWN, UNAVAILABLE_LANGUAGE, NOT_SUPPORTED_API. Forbidden: `getAmbientAi()`, `getCallApi()` (`@PrivilegedApi`), `sendAudio`.

**TextToSpeech 3.3.1**: `TextToSpeech(context, Mode.ON_DEVICE)`, `initialize(OnInitializeListener)`, `addEventListener(EventListener{ onReady(); onStart(); onDone(); onStop(); onUpdatedRms(Double); onError(ErrorType) })`, `speak(text: String, flush: Boolean)`, `stop()`, `setLocale(Locale)`, `setSpeakerType(String)`, `setAudioAttributes(AudioAttributes)`, `setAudioZoneId(Int)`, `release()`. No speech rate. Forbidden: `getAmbientAi()`.

**FusedLocation 1.1.3**: `FusedLocation(context)`, `isSupported()`, `initialize()`, `registerFusedLocationCallback(FusedLocationListener{ onChanged(LocationData?); onError(Exception) })`; `LocationData(latitude, longitude, altitude: Double, heading: Float, confidence: Float, timestampMillis: Long)`. Permission `pleos.car.permission.FUSED_LOCATION` (runtime).

Permissions the new features need (integration adds to the manifest): `pleos.car.permission.CAR_LIGHTS` (turn signal, low beam, read ambient), `pleos.car.permission.CONTROL_CAR_LIGHTS` (ambient pulse), `pleos.car.permission.CAR_WINDOWS` (wipers), `pleos.car.permission.CAR_DRIVE` (steering, brake, cruise — already declared). If a permission is denied the gateway logs the code only and keeps defaults.

---

## 13. Integration plan (integration agent)

`AppContainer`:
```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
val clock: Clock = WallClock
val demoAllowed = /* §9 gate */
val vault = SecureVault(app)
val content = ContentModule(app).source
val learn = LearnModule(vault, scope, clock)
val speech = SpeechModule(app, scope, clock)
val drive = DriveModule(app, scope, clock, speech.audio, demoAllowed)
val navigator = EnglishNavigator()
val coach: EnglishCoach = DefaultEnglishCoach(CoachDeps(clock, content, learn.repository, learn.scheduler, learn.placement,
    learn.progression, learn.planner, speech.judge, speech.commands, speech.pronunciation, speech.fluency, speech.port,
    drive.signals, drive.loadEngine, drive.policy, drive.hooks, drive.commute, drive.feedback,
    drive.mediaKeys, drive.map, demoAllowed, sound = SoundPoolPlayer(app)), scope)
init { coach.start(); scope.launch { coach.state.map { it.settings }.distinctUntilChanged().collect { drive.mirrorToCar.value = it.mirrorSimRouteToCar; speech.setEnglishRate(it.englishRate) } } }
```
MainActivity: `setContent { YeopjariTheme { EnglishApp(container.coach, container.navigator, if (container.demoAllowed) container.drive.demo else null) } }`; `onKeyDown/onKeyUp` → `drive.mediaKeys.onKeyEvent(keyCode, down)` (return true if consumed); runtime permissions (FUSED_LOCATION, CAR_SPEED, CAR_ENERGY) → `drive.onLocationPermissionGranted()` / `drive.refreshCarApi()`; extras (§9) only when `demoAllowed`; Gleo (§10).
Manifest: add the permissions in §12, Gleo intent filters, keep `distractionOptimized`; consider removing `INTERNET` (nothing goes to servers) after checking Pleos STT/TTS still initialise on the emulator.
Delete legacy (after the new app compiles): `ai/**`, `YeopjariStore.kt`, `ui/Shell.kt`, `ui/screens/**`, `ui/components/**`, `sim/DriveSimulator.kt`, `sim/DemoData.kt`, `core/model/Agent.kt`, `core/model/Moment.kt`, `platform/pleos/PleosSpeech.kt` (if unused), legacy tests (`DialogueStoreTest.kt`, `VirtualClock.kt`, `ai/**`), old schema functions. Update README.md (features, privacy, demo commands, “AI is an assistant; LoRA not integrated”).

---

## 14. Testing

Shared test utilities (`app/src/test/java/com/yeopjari/app/english/core/VirtualTime.kt`): `VirtualTime` (CoroutineDispatcher + Delay + Clock; `scope()`, `advanceBy(ms)`, `runCurrent()`, `START_MS` = 2026-11-16 07:40 KST) and `MutableSignalSource(clock)` (`set { it.copy(...) }`, `tick()`). `InMemorySecureStore` is in main core.

Required tests (JVM, no Android):
- content: every file parses with `JsonContentSource { File("src/main/assets/" + it).readText() }` (Gradle runs unit tests with working dir `app/`), zero problems, ids unique, volume/mix rules (§11), every HookTrigger and PronIssueType covered, each placement level/track pool ≥ 12, prompts length limits.
- learn: SM-2 intervals & EASY/latency mapping; placement staircase & result; checkpoint at 80 %, level-up after 3 units, streak across days (KST), daily goal event; planner counts for 32-min morning (6 + 10) and evening (2 + 18), driving NORMAL share; repository round trip through `InMemorySecureStore`, erase, corrupt bytes → empty.
- speech: normaliser cases (contractions, numbers, fillers, “I'd like” kept); judge per skill incl. multiple answers, keyword caps, TOEIC scoring, NO_ANSWER; commands EN/KO incl. “the next meeting” is NOT SKIP and “다시 시작” is RESUME; each PronIssueType detection; fluency rating & pauses; SpeechPort logic that can be tested without Android (inject TTL, timeout fallback) via an extracted pure helper.
- drive: each reason rule; hysteresis (escalate now, 4 s de-escalation, CRITICAL 3 s → FOCUS); RELAXED after 15 s steady; index bands; policy table & `allows`; hook rules incl. once-per-drive and cooldown; commute detection; TurnType/CameraType/CautionPointType mapping (pure functions); simulator scenario smoke on VirtualTime.
- coach (virtual time, fakes for every port): morning session briefing title; correct answer → graded + pulse; FOCUS during LISTENING → HOLD, not graded, resume line “아까 그 문제 다시 할게요.” then same item; CRITICAL stops TTS immediately; RELAXED allows TOEIC_QA but NORMAL defers it; commands (REPEAT/SKIP/HINT/PAUSE/RESUME/DONT_KNOW); media keys mapping; two silences → no grade + requeue; lesson end at ≤ 60 s before arrival (closing line, nothing asked afterwards); arrival summary; context hook delivery after load drops; placement flow; evening ask (yes / no / timeout → one re-ask → silent give-up / FOCUS interruption / ALWAYS / NEVER); parked dashboard numbers on P; sound cues (verdict, streak, level-up, session complete; none in CRITICAL or with the setting off); removed features leave no trace (`RemovedFeaturesTest`); `onAnswerText` in HOLD is ignored.
- ui: compile only (no instrumentation); keep previews optional.
