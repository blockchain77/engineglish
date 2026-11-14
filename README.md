# 옆자리 (YEOPJARI) — 차를 이해하는 AI 동승자

> **"운전대 밖의 일은, 옆자리가."**
> 기어·도어·배터리·충전·경고등 같은 **차량 이벤트**로 먼저 움직이는 AI 동승 에이전트.
> 충전 계획, 졸음 케어, 펫·휴식 케어, 아이 동승, 주차 위치 기억, 경고등 해설을 하나의 대화형 경험으로 묶었습니다.

현대자동차그룹 차량용 앱 개발 공모전(2026) 출품작 · Pleos Connect (AAOS) 전용 앱

---

## 1. 한눈에 보기

| 구분 | 내용 |
|---|---|
| 플랫폼 | Pleos Connect (Android Automotive OS 기반), Kotlin + Jetpack Compose |
| 사용 SDK | **Vehicle 2.3.2 · NaviHelper 3.0.5 · SpeechToText 3.3.1 · TextToSpeech 3.3.1 · FusedLocation 1.1.3** + AAOS Car API |
| 차량 AI 연동 | **Gleo Interface (App Schema)** — `assets/schema.json` 3개 액션 |
| AI 구조 | 온디바이스 SLM(의도·라우팅) + 자체 추론 LLM(복합 계획) 하이브리드 — *모델은 미탑재, 동일 인터페이스의 대체 구현으로 전체 UX 검증* |
| minSdk / target / compile | 28 / 34 / 36 |
| APK | `apk/` 폴더 (release 서명) |

### 핵심 기능 (커뮤니티 조사 기반)

| 모먼트 | 차량 이벤트(트리거) | 옆자리의 행동 | 사용 SDK |
|---|---|---|---|
| ⚡ 충전 코파일럿 | 경로 설정 + 도착 SOC < 12% 예측 | 충전+휴식 통합 계획, 신뢰도 높은 충전소·예비 충전소, 경유지 자동 추가 | EvBattery · NaviHelper(requestTrip/addWaypoint) |
| ⏱ 충전 세션 | 충전 스위치 ON | 완료 예상 시각, **급속충전 1시간 과태료 타이머**, **지하주차장 90% 한도** | EvBattery(getEvChargeSwitchState · setEvChargePercentLimit) |
| 😴 졸음 케어 | 연속 주행 시간·시간대·단조 주행 | 1단계 환기 → 2단계 대화로 깨우기 → 3단계 졸음쉼터 경유 + 쉼 모드 | Odometer · HVAC · NaviHelper · TTS |
| 🐶 펫 케어 | P 기어 + 운전석 문 열림 + 극한 외기온 | 실내 온도 유지, 외부 안내 화면, 배터리 하한 보호 | Gear · Door · HVAC · EvBattery |
| 👨‍👩‍👧 아이 동승 | 뒷좌석 탑승 + 장거리 | "만화 n편 보면 도착", 목적지 연계 음성 퀴즈, 아이 맞춤 휴식 | Seat · NaviHelper · STT/TTS |
| 🚸 뒷좌석 확인 | 출발 시 뒷문 개폐 → 도착 후 뒷문 미개폐 | 하차 전 리마인더 (안전 규칙 엔진) | Door · Gear |
| 🅿 주차 위치 기억 | P 기어 + 위치 신호 상실(지하) | "지하 3층 C-12" 음성 메모 | Gear · FusedLocation · STT |
| 🩺 차량 닥터 | 경고등 점등 (TPMS 등) | 실시간 데이터로 원인 추론 + 다음 행동(공기 주입소 경유) | Safety(Telltales) · NaviHelper |
| 🗓 출근 브리핑·루틴 | D 기어 + 평일 아침 | 회사 안내·예열·브리핑, **말로 루틴 만들기** | Gear · HVAC · NaviHelper |

---

## 2. 실행 방법

### 2-1. APK 바로 설치 (심사용)
```bash
adb install -r apk/Yeopjari_release.apk
```
런처에서 **옆자리** 실행 → 권한 허용(위치·차속·에너지) → 좌측 하단 **시연** 버튼으로 시나리오 재생.

### 2-2. 소스 빌드
요구 사항: Android Studio (Narwhal 이상), **JDK 17~21**, Android SDK Platform 34 이상, Gradle 8.14 (wrapper 포함)

```bash
./gradlew :app:assembleDebug        # 디버그
./gradlew :app:assembleRelease      # 릴리스 (keystore.properties 없으면 debug 키로 서명)
```
Pleos Connect SDK 는 공식 Maven 저장소에서 자동으로 받아옵니다 (`settings.gradle.kts`):
```kotlin
maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
```

---

## 3. 테스트 환경 및 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** (System Image: `system-images;android-34;pleos_car_emulator;x86_64`, Android 14 / API 34) |
| 화면 규격 | **2560 × 1440 px, 160 dpi (mdpi), 12.8″** — 앱은 가로 전체(2560dp)·반쪽 세로 창(≈840dp)·12.9″~17″ 폼팩터 모두 대응하는 반응형 레이아웃 |
| AVD 권장 설정 | Cold boot, RAM 4GB 이상, Graphics: Hardware |
| 추가 도구 | Virtual Console (`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088) |

### Virtual Console 로 확인할 수 있는 반응
| Virtual Console 조작 | 옆자리 반응 |
|---|---|
| Gear → D (평일 아침) | 출근 루틴 · 출근 브리핑 모먼트 |
| Gear P + Door Position(운전석) Open | 펫 케어 제안(반려견 동승 시) / 뒷좌석 확인(출발 시 뒷문 개폐 시) |
| Battery SOC 낮춤 | 도착 SOC 재계산 → 충전 필요 모먼트 |
| Charge Switch ON | 충전 세션 · 과태료 타이머 |
| Telltales → TPMS On | 차량 닥터 경고등 해설 |
| Climate Target Temp | 홈/케어 화면 공조 상태 동기화 |

> Virtual Console 에 없는 신호(차속·경로 진행·시간 흐름)는 앱 내 **시연 디렉터**(좌측 하단 🧪)가 재현합니다. 시뮬레이터 모드에서도 AI 가 변경한 공조·경유지는 연결된 에뮬레이터 차량/내비에 함께 반영됩니다.

### Gleo Interface 테스트 (adb 로 Gleo Action 재현)
```bash
# component=activity
adb shell am start -a PLAN_EV_TRIP -p com.yeopjari.app --es ACTION_PARAM '{"destination":"강릉","companions":"kids"}'
adb shell am start -a DIAGNOSE_VEHICLE -p com.yeopjari.app --es ACTION_PARAM '{"symptom":"타이어 경고등"}'
# component=service
adb shell am broadcast -a START_CARE_MODE -p com.yeopjari.app --es ACTION_PARAM '{"mode":"pet","temperature":22}'
```

---

## 4. 시연 시나리오 (시연 디렉터)

| # | 시나리오 | 보여주는 것 |
|---|---|---|
| 1 | 평일 아침 출근 | D 기어 → 루틴(회사 안내·예열) → 출근 브리핑 → 사고 정체 우회 제안 |
| 2 | 회사 지하주차장 도착 | GPS 상실 감지 → 주차 위치 음성 메모 |
| 3 | 강릉 가족여행 | 도착 SOC 3% 예측 → **클라우드 추론**: 충전+간식+휴식 통합 계획 → 경유지 추가(NaviHelper) |
| 4 | 고속 충전 중 | 충전 세션 · 과태료 타이머 · 쉼 모드 · 아이 퀴즈 |
| 5 | 오후 고속도로 졸음 | 졸음 위험 점수 상승 → 단계별 케어 → 졸음쉼터 경유 |
| 6 | 반려견과 잠깐 정차 | 31°C · P · 운전석 문 열림 → 펫 케어 + 외부 안내 화면 |
| 7 | 타이어 경고등 점등 | TPMS 점등 → 원인 추론(저온 vs 펑크) → 공기 주입소 경유 |
| 8 | 하원길 도착 | 뒷문 미개폐 → 뒷좌석 확인 리마인더 (안전 규칙) |

주행 중(D·차속>0)에는 **글랜스 모드**로 전환: 카드 1장 + 대형 버튼 3개, 키보드·긴 텍스트·애니메이션 비활성 (CarUxRestrictions 준수).

---

## 5. 아키텍처

```
┌──────────────── UI (Jetpack Compose, 반응형 · 주행 중 글랜스 모드) ────────────────┐
│ 홈(모먼트) · 옆자리 AI 패널 · 충전 · 케어 · 안전 · 차량 · 루틴 · AI 설정 · 시연 디렉터 │
└───────────────▲──────────────────────────────────────────────▲────────────────────┘
                │ StateFlow (단방향)                             │
┌───────────────┴──────────── YeopjariStore ───────────────────┴────────────────────┐
│ DriveContext = Vehicle + Route + 시간 + 동승 + EnergyForecast + Fatigue + Load      │
│  ├ ContextEngine ── 차량 이벤트 → 모먼트(선제 제안) · InterruptionManager(운전 부하) │
│  └ AgentOrchestrator ─ ① 안전 정책 ② 온디바이스 의도분석 ③ 엣지 실행                 │
│                       ④ PrivacyGuard → 클라우드 추론 LLM ⑤ ToolRegistry 실행 ⑥ TTS  │
└──────▲──────────────────────────▲───────────────────────────▲──────────────────────┘
       │ VehicleHub / NaviHub (Pleos SDK ⇄ 시뮬레이터, 쓰기는 미러링)                 │
┌──────┴───────────┐ ┌────────────┴──────────┐ ┌──────────────┴───────────┐ ┌─────────┴────────┐
│ Pleos Vehicle SDK │ │ Pleos NaviHelper SDK   │ │ Pleos STT/TTS (ON_DEVICE)│ │ Gleo App Schema   │
│ + AAOS Car API    │ │ + FusedLocation SDK    │ │                          │ │ (Intent/Callback) │
└───────────────────┘ └────────────────────────┘ └──────────────────────────┘ └──────────────────┘
```

| 패키지 | 역할 |
|---|---|
| `core/model` · `core/gateway` | 도메인 모델, 플랫폼 추상화 인터페이스 |
| `platform/pleos` | Pleos SDK 실연동 (Vehicle·NaviHelper·STT·TTS·FusedLocation) + AAOS Car API |
| `platform/gleo` | Gleo Interface: Intent 파싱(`ACTION_PARAM`), `APP_ACTION_RESPONSE` 콜백, 서비스 수신기 |
| `platform/hub` | Pleos SDK ⇄ 시뮬레이터 소스 전환·쓰기 미러링 |
| `ai/context` | DriveContext, 에너지·졸음·운전부하 모델, 컨텍스트 엔진 |
| `ai/nlu` · `ai/cloud` | 온디바이스 SLM / 추론 LLM **인터페이스**와 대체 구현 |
| `ai/tools` | Function Calling 툴 레지스트리 (LLM 스키마 = Gleo 스키마 공용) |
| `sim` | 시연 시뮬레이터·데모 데이터 |
| `ui` | 디자인 시스템(Night Drive), 셸, 화면 |

### AI 모델 교체 지점 (미탑재 → 탑재)
| 인터페이스 | 현재 (공모전 1차) | 목표 |
|---|---|---|
| `OnDeviceIntentEngine` | `KeywordIntentEngine` (규칙) | 차량 도메인 한국어 SLM (1~3B, 4bit 양자화) 또는 Pleos LLM SDK `Mode.ON_DEVICE` |
| `CloudReasoner` | `ScriptedReasoner` (컨텍스트 반영 시나리오 응답) | 옆자리 자체 추론 LLM 서버 (차량 도메인 SFT + Tool-use) / Pleos LLM SDK `Mode.SERVER` |
| `ContextEngine` | 설명 가능한 규칙 트리거 | 규칙 후보 + SLM 재순위화·개인화 |

자세한 설계는 [`docs/AI_ARCHITECTURE.md`](docs/AI_ARCHITECTURE.md), 기획서 원고는 [`docs/PROPOSAL.md`](docs/PROPOSAL.md).

---

## 6. 정책·안전 준수
- 차량 마이크 음성은 **Pleos SpeechToText SDK(온디바이스)** 로만 처리, 외부 STT 전송 없음
- 클라우드에는 최소화된 컨텍스트만 전송 (음성 원본·정밀 GPS·실명·VIN 미전송)
- 주행 중: 영상 차단(안전 규칙 엔진), 키보드·장문·대기 애니메이션 비활성, 버튼 최소 76dp
- CPU 절약: 리스너 우선, 리스너 없는 값만 15초 주기 조회
- Release: 서명 키 별도 보관(`keystore.properties`, 저장소 제외), debuggable=false

## 7. 제출물·자료 위치

| 자료 | 경로 |
|---|---|
| 접수양식(기획서) — 이메일 제출용이라 저장소에는 넣지 않음. 같은 내용의 원고는 `docs/PROPOSAL.md` | (저장소 미포함) |
| 별지: 화면 시안 (에뮬레이터 캡처 16장 + 설명) | `docs/submission/옆자리_별지_화면시안.pdf` |
| 시연 영상 (에뮬레이터 실구동 녹화, 약 2분 40초) | `docs/demo/yeopjari_demo.mp4` |
| 기획서 원고(Markdown) · AI 아키텍처 | `docs/PROPOSAL.md` · `docs/AI_ARCHITECTURE.md` |
| 스크린샷 원본(전체 화면) · 앱 영역 확대본 | `docs/screenshots/` · `docs/screenshots/app/` |
| Gleo App Schema | `app/src/main/assets/schema.json` |
| 빌드·설치 / 캡처 / 시연 녹화 스크립트 | `tools/build_install.sh` · `tools/capture.sh` · `tools/record_demo.sh` |

> APK 파일명 규칙은 `팀명_앱이름_release.apk` 입니다. 제출 전 `apk/Yeopjari_release.apk` 를 `<팀명>_옆자리_release.apk` 로 바꿔 주세요.
> 영상(74MB)·APK(70MB)는 GitHub 100MB 제한 안이지만, 저장소가 무거우면 GitHub Releases 에 올리고 링크를 기재하는 방식을 권장합니다.

### 시연 자동화 (adb)
```bash
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es scenario ROADTRIP --es screen HOME
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es say "아이들이랑 강릉 가는데 충전이랑 쉬는 거 같이 계획 세워줘"
```
`scenario`: FREE · COMMUTE · PARKING · ROADTRIP · CHARGING · DROWSY · PET · TPMS · ARRIVAL / `screen`: HOME · CHARGE · CARE · SAFETY · CAR · ROUTINES · SETTINGS

## 8. 데모 데이터 고지
충전소 가용 대수·가격·신뢰도, 일정·날씨, 여정 기록 등은 **시연용 가상 데이터**입니다. 실서비스에서는 환경부 충전소 Open API, 한국도로공사 휴게소·졸음쉼터 API, 사용자 캘린더(동의 기반)로 대체합니다. 등장 인물·반려동물 이름은 가상의 페르소나입니다.
