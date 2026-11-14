# 옆자리 AI 아키텍처 — 온디바이스 SLM × 자체 추론 LLM 하이브리드

> 현재(공모전 1차) 앱에는 **AI 모델이 탑재되어 있지 않습니다.** 대신 아래 인터페이스와 동일한 계약을 지키는 대체 구현으로 전체 UX, Tool 실행, 차량 SDK 연동 경로를 검증했습니다. 모델을 교체해도 앱 코드는 바뀌지 않습니다.

## 1. 설계 원칙
1. **안전 규칙이 AI보다 먼저 적용됩니다**: 주행 중 영상 차단, 뒷좌석 확인 같은 판단은 결정론적 규칙 엔진(`AiTier.RULE`)이 맡습니다.
2. **차 안에서 끝낼 수 있는 일은 차 안에서 끝냅니다**: 공조, 목적지, 상태 질의는 온디바이스에서 300ms 안에, 오프라인에서도 처리합니다.
3. **클라우드에는 추론에 필요한 최소한만 보냅니다**: 음성 원본, 정밀 GPS, 실명, VIN은 차 밖으로 나가지 않습니다(`PrivacyGuard`).
4. **AI가 행동할 때는 Tool로 행동합니다**: 모든 차량 제어는 스키마가 정의된 Tool을 통해서만 이뤄지며, 경로 변경처럼 영향이 큰 동작은 사용자 확인을 거칩니다.
5. **처리 위치를 보여 줍니다**: 모든 응답에 ⚡온디바이스 / ☁클라우드 / 🛡안전 규칙 배지와 지연시간을 표시합니다.
6. **Gleo를 대체하지 않고 보완합니다**: 호출어, 범용 대화, 기본 명령은 차량 기본 AI(Gleo)가 맡습니다. 옆자리는 App Schema로 호출되는 **도메인 에이전트**이면서, 차량 이벤트로 **스스로 깨어나는** 선제 에이전트입니다.

## 2. 처리 흐름
```
[차량 마이크] → Pleos STT SDK (ON_DEVICE, 텍스트만 반환)
                     │
                     ▼
① SafetyPolicy (규칙) ── 차단/안내 ─────────────────────────────▶ 응답 (RULE)
                     │
                     ▼
② OnDeviceIntentEngine (Edge SLM)
   출력: intent, slots, confidence, complex(다중 제약 여부)
                     │
        ┌────────────┴─────────────┐
   단순·고신뢰               복합·저신뢰·생성형
        ▼                          ▼
③ Edge 실행 (Tool 직접)     ④ PrivacyGuard.minimize(ctx)
   < 300ms, 오프라인 OK          → CloudReasoner (자체 LLM)
        │                          출력: reasoning[], toolCalls[], reply, speak, cards[]
        │                          (오프라인이면 ③의 대체 응답)
        └────────────┬─────────────┘
                     ▼
⑤ ToolRegistry 실행 (Vehicle / NaviHelper SDK) — requiresConfirm이면 확인 카드
                     ▼
⑥ 응답 카드 + Pleos TTS SDK + 상태바 자막
```

**컨텍스트 엔진 경로**(사용자 발화 없이 시작):
`Vehicle·Navi·Location 이벤트 → DriveContext → ContextEngine(트리거 후보) → [SLM 재순위화] → InterruptionManager(운전 부하) → 모먼트 카드 / 음성`

## 3. 계층별 모델 계획

### Tier-0 · 안전 규칙 엔진 (탑재 완료)
- 결정론적 규칙(차량 신호 조합)으로 동작하며, AI의 판단을 덮어쓸 수 있습니다.

### Tier-1 · 온디바이스 의도 분석 (`OnDeviceIntentEngine`)
| 항목 | 계획 |
|---|---|
| 1단계 (PT) | 경량 의도분류기: 한국어 소형 인코더 파인튜닝, int8 ONNX/TFLite **~50MB**. 앱 프로세스 RAM 400MB 가이드 준수 |
| 2단계 (출시) | 차량 도메인 한국어 **SLM 1~3B, 4bit 양자화**(GGUF/QNN). 의도·슬롯·라우팅을 단일 프롬프트(JSON 출력)로 처리. 앱 프로세스 메모리 제약 때문에 **플랫폼 호스팅**(Pleos LLM SDK `Mode.ON_DEVICE`) 또는 별도 NPU 서비스 사용 |
| 입력 | 발화 + 압축 컨텍스트 토큰(주행 상태, SOC 구간, 경로 유형, 동승 구성) |
| 출력 계약 | `NluResult(intent, slots, confidence, complex, latencyMs)` |
| 라우팅 규칙 | `intent.tier == CLOUD` ∨ `complex` ∨ `confidence < 0.6` → 클라우드 |
| 목표 | p95 < 300ms, 의도 정확도 ≥ 95%, 불필요한 클라우드 호출률 ≤ 10% |

### Tier-2 · 자체 추론 LLM (`CloudReasoner`)
| 항목 | 계획 |
|---|---|
| 모델 | 오픈 LLM(7~14B급) 기반 **차량 도메인 SFT + Tool-use 학습** |
| 입력 | 발화 + `MinimizedContext` + Tool 스키마(JSON, `ToolRegistry.specs`) + 최근 6턴 |
| 출력 계약 | `ReasonerPlan(reasoning[], toolCalls[], reply, speak, cards[], latencyMs)` — 화면용 답변과 음성용 짧은 답변을 분리 |
| 지식 | 차종 매뉴얼 RAG, 충전소 Open API, 휴게소·졸음쉼터 데이터 |
| 목표 | p95 < 2.5s(스트리밍으로 추론 단계 노출), Tool 호출 유효성 ≥ 98% |
| 대체 | Pleos LLM SDK `Mode.SERVER` 공개 시 같은 인터페이스로 교체 가능 |

### 컨텍스트 엔진 고도화
- 현재: 설명 가능한 규칙 트리거(도착 SOC < 12%, 졸음 점수 단계, P+운전석 문+외기온 등)
- 계획: 트리거 후보를 SLM이 **사용자 이력으로 재순위화**하고 문구를 개인화합니다. 모먼트 수락/거절 로그로 지속 개선합니다(온디바이스 개인화 메모리).

## 4. 학습 데이터 전략
1. **스키마 기반 합성**: Tool 스키마 × 8개 시나리오 × 차량 상태 조합 × 한국어 구어 변형("엉뜨", "히터 좀", "덥다", "애들 심심해해")으로 의도·슬롯·Tool 호출 데이터를 생성합니다.
2. **커뮤니티 페인포인트 시드**: 충전 고장, 과태료, 지하주차장, 졸음, 펫, 아이 상황 발화를 시드로 씁니다.
3. **안전 레드팀 세트**: 주행 중 영상·게임 요청, 위험한 제어 요청, 모호한 경로 변경을 포함합니다.
4. **실사용(동의 기반)**: 모먼트 수락률과 수정 발화로 지속 학습합니다. 음성 원본은 수집하지 않습니다.

## 5. 성능·자원 (Pleos 가이드)
- 주행 중 CPU < 5%: SDK는 리스너를 우선 사용하고, 리스너가 없는 값만 15초 주기로 조회합니다. 주행 중에는 대기 애니메이션을 정지합니다.
- RAM ≤ 400MB: 대형 모델은 앱 밖(플랫폼/NPU 서비스)에 둡니다.
- 버튼 응답: SDK 제어 호출은 2초 타임아웃의 suspend 래퍼로 감쌉니다.

## 6. 코드 위치
| 인터페이스 / 구현 | 파일 |
|---|---|
| 오케스트레이터 · PrivacyGuard | `app/src/main/java/com/yeopjari/app/ai/AgentOrchestrator.kt` |
| 온디바이스 의도 엔진 (인터페이스 + 규칙 대체) | `.../ai/nlu/OnDeviceIntentEngine.kt` |
| 추론 LLM (인터페이스 + 시나리오 대체) | `.../ai/cloud/CloudReasoner.kt` |
| Tool 레지스트리 (LLM·Gleo 공용 스키마) | `.../ai/tools/ToolRegistry.kt` |
| 컨텍스트·에너지·졸음·운전부하 모델 | `.../ai/context/DriveContext.kt`, `ContextEngine.kt` |
| Gleo App Schema | `app/src/main/assets/schema.json`, `.../platform/gleo/GleoBridge.kt` |
