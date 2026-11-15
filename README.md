# 옆자리 (YEOPJARI) — 나를 아는 AI 동승자

> **"잘못 알아들어도, 이 사람의 기록으로 바로잡고 · 헷갈리면 HUD로 묻고 · 고른 답으로 차 안에서 배운다."**
> 차량 음성인식이 "성심당"을 "서심다응"으로 들어도, 옆자리는 이 운전자의 방문 기록(차 안에만 저장)으로 바로잡습니다.
> 확신이 낮을 때만 앞유리 HUD에 선택지 3개를 띄우고, 운전자가 고른 답으로 그 자리에서 학습합니다. 개인 데이터는 차 밖으로 나가지 않습니다.

현대자동차그룹 차량용 앱 개발 공모전(2026) 출품작 · Pleos Connect (AAOS) 전용 앱

---

## 1. 한눈에 보기

| 구분 | 내용 |
|---|---|
| 플랫폼 | Pleos Connect (Android Automotive OS 기반), Kotlin + Jetpack Compose |
| 사용 SDK | **SpeechToText 3.3.1 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · Vehicle 2.3.2 · TextToSpeech 3.3.1** + AAOS Car API |
| 차량 AI 연동 | **Gleo Interface (App Schema)** — `GO_TO_MY_PLACE` · `PLAN_EV_TRIP` · `START_CARE_MODE` (`assets/schema.json`) |
| 핵심 AI | **개인화 보정 엔진**(차 안): 자모 단위 발음 비교 × 개인 방문 기록 6개 특징 → 확률 → 바로 실행 / 확인 / HUD 선택지 → 고른 답으로 즉시 학습 |
| 명령 해석 모델 | Qwen3-0.6B + 차량 명령 LoRA (학습·평가 파이프라인 `ml/`, 앱에는 같은 출력 구조의 규칙 기반 대체물이 들어 있음) |
| 개인정보 | 방문 기록 AES-256-GCM(Android Keystore) · 학습 기록 해시 체인 · 서버로는 서명된 가중치 변화량 6개만(연합학습) |
| minSdk / target / compile | 28 / 34 / 36 |
| APK | `apk/Yeopjari_release.apk` — v1.1.0 (versionCode 2), release 서명 |

### 핵심 흐름 (시연 시나리오 "토요일 · 대전 본가 가는 길")
1. "서심다응 가자" → STT 텍스트 → 명령 해석 `{NAV_PLACE, place:"서심다응"}`
2. 보정 엔진: **성심당 대전역점**(발음 0.89 · 토요일 오전에 주로 · 5회 · 가는 길) > 본점 > DCC점 > 대흥동 성당
3. HUD: "성심당 대전역점으로 갈까요?" → "응" → NaviHelper 안내 + "기억했어요 · ‘서심다응’ = 성심당"
4. 같은 말 두 번째: 묻지 않고 바로 안내(10초 안에 "아니야"로 취소 가능)
5. "아니야" → HUD 선택지(본점·DCC점·대흥동 성당) → "1번" → 그 선택을 학습

같은 "스타벅스 가자"도 **평일 아침 판교 출근길**이면 판교테크노밸리점으로 바로, **토요일 잠실 집 앞**이면 잠실새내역점을 확인합니다.

### 그 밖의 기능 (같은 대화·안전·SDK 계층 위)
충전 코파일럿(도착 SOC 예측·충전소 신뢰도·과태료 타이머·90% 한도) · 단계별 졸음 케어 · 펫 케어 · 아이 동승 · 뒷좌석 확인 · 주차 위치 음성 메모 · 경고등 해설 · 말로 만드는 루틴

---

## 2. 실행 방법

### 2-1. APK 바로 설치 (심사용)
```bash
adb install -r apk/Yeopjari_release.apk
```
런처에서 **옆자리** 실행 → 권한 허용(위치·차속·에너지) → 첫 화면 **맞춤 AI**의 상황 칩(토요일·대전 가는 길 / 평일 아침·판교 / 토요일 오전·잠실)을 고르고 말하기 또는 예시 문장을 누릅니다.

### 2-2. 소스 빌드 · 테스트
요구 사항: Android Studio Narwhal 3 Feature Drop(2025.1.3) 이상(AGP 8.13.2), **JDK 17~21**, Android SDK Platform 36(compileSdk 36), Gradle 8.14.3 (wrapper 포함)

```bash
./gradlew :app:assembleDebug          # 디버그
./gradlew :app:assembleRelease        # 릴리스 (keystore.properties 없으면 debug 키로 서명)
./gradlew :app:testDebugUnitTest      # 개인화 엔진 단위 테스트 + 정확도 평가 (docs/eval/personal_eval.md 생성)
```
Pleos Connect SDK 는 공식 Maven 저장소에서 자동으로 받아옵니다 (`settings.gradle.kts`):
```kotlin
maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
```

### 2-3. 명령 해석 LoRA 학습 (GPU)
`ml/README.md` 참고 — `bash ml/run_gpu.sh` (데이터 생성 → Qwen3-0.6B LoRA 학습 → 평가). NVIDIA B200 1/7 MIG(약 23GB)에서 돌아가도록 구성했습니다.

---

## 3. 테스트 환경 및 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** (System Image: `system-images;android-34;pleos_car_emulator;x86_64`, Android 14 / API 34) |
| 화면 규격 | **2560 × 1440 px, 160 dpi (mdpi), 12.8″** — 가로 전체·반쪽 세로 창·12.9″~17″ 폼팩터 반응형 |
| AVD 권장 설정 | Cold boot, RAM 4GB 이상, Graphics: Hardware |
| 추가 도구 | Virtual Console (`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088) |

### 시연 자동화 (adb, 에뮬레이터 지정)
```bash
# 상황 재현: P_DAEJEON(토요일·대전 가는 길) · P_WEEKDAY(평일 아침·판교) · P_WEEKEND(토요일 오전·잠실)
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es scenario P_DAEJEON
# STT 최종 텍스트와 같은 경로로 말 넣기 (대화창을 열지 않고 HUD 유지)
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es hear "서심다응 가자"
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es hear "아니야"
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es hear "1번"
# 정차로 바꿔 분석 화면 보기 · 학습 초기화 / 기록 삭제 / 복원 / 변화량 전송(모의)
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es gear P
adb -s emulator-5554 shell am start -n com.yeopjari.app/.MainActivity --es personal reset   # erase · restore · sync
```
전체 시연 녹화: `bash tools/record_personal_demo.sh` → `docs/demo/yeopjari_personal_demo.mp4`

### Gleo Interface 테스트 (adb 로 Gleo Action 재현)
```bash
adb shell am start -a GO_TO_MY_PLACE -p com.yeopjari.app --es ACTION_PARAM '{"place":"서심다응","stop_type":"waypoint"}'
adb shell am start -a PLAN_EV_TRIP -p com.yeopjari.app --es ACTION_PARAM '{"destination":"강릉","companions":"kids"}'
adb shell am broadcast -a START_CARE_MODE -p com.yeopjari.app --es ACTION_PARAM '{"mode":"pet","temperature":22}'
```

### Virtual Console 로 확인할 수 있는 반응
| Virtual Console 조작 | 옆자리 반응 |
|---|---|
| Gear → D | 주행 중 화면: HUD 시뮬레이터 + 큰 버튼 3개만(분석 패널 숨김) |
| Gear P + Door Position(운전석) Open | 펫 케어 제안(반려견 동승 시) / 뒷좌석 확인 |
| Battery SOC 낮춤 · Charge Switch ON | 충전 필요 예측 · 충전 세션·과태료 타이머 |
| Telltales → TPMS On | 경고등 해설 |

---

## 4. 정확도 평가 (요약)

가상 운전자 1명이 엔진이 본 적 없는 미래 4주 동안 간 곳 41건을 세 가지 말투(이름 그대로 · 음성인식 오류가 섞인 이름 · "빵집" 같은 종류어)로 불러 만든 **질의 121개**입니다. 앱의 엔진 코드를 그대로 JVM 테스트로 돌렸습니다(`./gradlew :app:testDebugUnitTest`, seed 고정, 손으로 고친 숫자 없음).

| 방법 | Top-1 | HUD 3개 안에 정답 | 확인 없이 엉뚱한 곳 출발 | 바로 맞게 출발 |
|---|---:|---:|---:|---:|
| A 일반 검색(글자 일치 + 가까운 순) | 38.0% | 51.2% | 0.0% | 34.7% |
| B 발음 보정만 | 62.0% | 100.0% | 0.0% | 53.7% |
| **C 발음 + 개인 기록 (옆자리)** | **95.0%** | **100.0%** | **0.0%** | 56.2% |
| **D C + 차 안 학습** | **95.0%** | 100.0% | **0.0%** | **73.6%** |

- 음성인식 오류가 섞인 이름만 보면 A 0% → B 63.4% → **C 95.1%**입니다.
- 평가 데이터를 5번 새로 뽑아 반복한 평균 Top-1은 A 38.5% · B 66.1% · C 95.9% · D 95.9%입니다.
- 장소 사전에 없는 곳(처음 가는 흔한 목적지 25곳 × 상황 4개)을 말하면, 발음 보정만 쓰는 B는 52%를 엉뚱한 아는 곳으로 바로 출발시켰고 옆자리(C·D)는 0%였습니다. 확신이 낮으면 바로 출발하지 않고 되묻기 때문입니다.
- 한계: 합성 페르소나와 흉내 낸 음성인식 오류로 잰 값입니다. 실제 주행 기록과 실제 STT 결과로 다시 재야 합니다. 상세 내용(신뢰구간, 부호검정, 기간별 결과, 실패 사례)은 [`docs/eval/personal_eval.md`](docs/eval/personal_eval.md)에 있습니다.

---

## 5. 아키텍처

```
STT SDK 텍스트 ─▶ 명령 해석(Qwen3-0.6B+LoRA 목표 / 규칙 대체물) ─▶ 개인화 보정 엔진(차 안)
   발음 유사도 × [자주·시간대·거리/경로·최근·선호] → softmax → 바로 실행 · 확인 · HUD 선택지(≤3)
   ─▶ NaviHelper 안내 · TTS · 앞유리 HUD 시뮬레이터 ─▶ 선택 즉시 학습(가중치 6개·표현 기억)
   ─▶ (선택 동의) 서명된 가중치 변화량만 연합학습 서버로
```

| 패키지 | 역할 |
|---|---|
| `ai/personal` | **개인화 보정 엔진** — Hangul(발음 비교), QueryParser, PersonalBrain(순위·학습), PersonaFactory(가상 운전자), PrivacyLedger(해시 체인·연합 업데이트), PersonalService |
| `platform/secure` | SecureVault — Android Keystore AES-256-GCM 암호화 저장, ECDSA P-256 서명 |
| `ai/nlu` · `ai/cloud` | 온디바이스 명령 해석 / 추론 LLM 인터페이스와 대체 구현 |
| `ai/context` · `ai/tools` | 컨텍스트 엔진(충전·졸음·펫 등), Function Calling 툴 레지스트리 |
| `platform/pleos` · `platform/gleo` · `platform/hub` | Pleos SDK 실연동, Gleo Interface, SDK⇄시뮬레이터 전환 |
| `ui` | 디자인 시스템, 셸, **PersonalScreen(맞춤 AI)** · **HudView(앞유리 HUD 시뮬레이터)** · 기타 화면 |
| `ml/` | 명령 해석 LoRA 데이터 생성·학습·평가 파이프라인 |

자세한 설계: [`docs/AI_ARCHITECTURE.md`](docs/AI_ARCHITECTURE.md) · 기획서 원고: [`docs/PROPOSAL.md`](docs/PROPOSAL.md)

---

## 6. 정책·안전·개인정보
- 차량 마이크 음성은 **Pleos SpeechToText SDK(온디바이스)** 로만 처리합니다. 외부 STT로 보내지 않고, 앱은 STT 텍스트 뒤에서 보정합니다.
- HUD 그리기 API가 공개 SDK에 없어 앱 안 **HUD 시뮬레이터**로 재현했습니다. HUD가 없는 차량을 위해 중앙 화면 큰 버튼과 음성도 같은 선택지를 제공합니다.
- 주행 중: HUD와 큰 버튼(≥76dp) 3개만 보이고, 분석 패널·키보드·장문은 숨깁니다. 영상은 차단합니다.
- 개인 방문 기록·기억한 표현·가중치는 **차 안 암호화 저장소에만** 둡니다. "내 기록 지우기"로 즉시 삭제됩니다.
- 학습 기록은 해시 체인으로 위·변조를 검증합니다. 서버로 보낼 수 있는 것은 클리핑·잡음·서명을 거친 **가중치 변화량 6개**뿐이고, 화면에서 그대로 미리 보여 줍니다.
- Release: 서명 키 별도 보관(`keystore.properties`, 저장소 제외), debuggable=false

## 7. 제출물·자료 위치

| 자료 | 경로 |
|---|---|
| 접수양식(기획서) — 이메일 제출용이라 저장소에는 넣지 않음. 같은 내용의 원고는 `docs/PROPOSAL.md` | (저장소 미포함) |
| 별지: 화면 시안 | `docs/submission/옆자리_별지_화면시안.pdf` |
| 시연 영상 — 개인화 보정·HUD (에뮬레이터 실구동) | `docs/demo/yeopjari_personal_demo.mp4` |
| 시연 영상 — 생활 기능 8개 시나리오 (1차본) | `docs/demo/yeopjari_demo.mp4` |
| 정확도 평가 보고서 | `docs/eval/personal_eval.md` |
| 명령 해석 LoRA 파이프라인 | `ml/` |
| Gleo App Schema | `app/src/main/assets/schema.json` |
| 빌드·설치 / 캡처 / 시연 녹화 스크립트 | `tools/build_install.sh` · `tools/capture.sh` · `tools/record_personal_demo.sh` · `tools/record_demo.sh` |

> APK 파일명 규칙은 `팀명_앱이름_release.apk` 입니다. 제출 전 `apk/Yeopjari_release.apk` 를 `<팀명>_옆자리_release.apk` 로 바꿔 주세요.

## 8. 데모 데이터 고지
가상 운전자 '서준'의 장소 21곳과 90일 방문 기록은 **시연·평가용 합성 데이터**입니다(좌표는 근사치, 실제 상호의 영업 정보와 무관). 실차에서는 P 기어 주차 위치·NaviHelper 목적지 이력·FusedLocation 체류 기록이 이 자리를 채웁니다. 충전소 가용 대수·가격 등 생활 기능의 수치도 시연용 가상 데이터입니다.
