# 옆자리 온디바이스 명령 해석 모델 — Qwen3-0.6B + LoRA 학습 파이프라인

차량 음성인식(Pleos STT SDK)이 준 **문장 한 줄**을 앱이 바로 실행할 수 있는 **JSON 한 줄**로 바꾸는 소형 언어모델을 만드는 재현 가능한 파이프라인이다. 앱의 `OnDeviceIntentEngine`(현재는 규칙 기반 `KeywordIntentEngine`)을 같은 인터페이스로 교체하는 것이 목표다.

```
"옆자리야 서심다응 들렀다 가자"
  → {"intent":"NAV_PLACE","place":"서심다응","stop":"waypoint","route":"edge"}
```

| 항목 | 내용 |
|---|---|
| 기반 모델 | `Qwen/Qwen3-0.6B` (Apache-2.0) · 생각 모드 끔 |
| 미세조정 | LoRA r=16, α=32, dropout 0.05, 7개 선형층 · bf16 · 3 epoch |
| 데이터 | 합성 6,009 / 600 / 600 (train/valid/test) · 27개 의도 · 자모 단위 음성인식 오류 주입 |
| 차량 배포 | LoRA 병합 → GGUF Q4_K_M(약 0.4 GB) → llama.cpp CPU/NPU, 목표 명령당 < 300 ms |
| 개인화 | 이 모델은 **하지 않는다** — 장소 보정은 차 안의 `PersonalBrain`이 맡는다 |

---

## 1. 왜 필요한가 — "문장 → 구조화 명령"과 "장소 보정"을 나눈다

공모전 규칙상 차량 마이크 음성은 **Pleos STT SDK**로만 인식해야 하고, 앱은 **최종 인식 문장(텍스트)만** 받는다. 차량 음성인식은 흔한 말은 잘 알아듣지만 상호·지명은 소리대로 틀린다(`성심당` → `서심다응`).

옆자리는 이 문제를 두 단계로 푼다.

```
[차량 마이크] → Pleos STT SDK (텍스트만)
      │ "서심다응 가자"
      ▼
① 명령 해석 (이 모델, 모든 사용자 공통)        ← ml/ 에서 학습
      │ {"intent":"NAV_PLACE","place":"서심다응","stop":"destination","route":"edge"}
      ▼
② 개인화 보정 (PersonalBrain, 차 안에서만)      ← app/.../ai/personal/
      │ 발음 유사도(Hangul.kt) × 90일 방문 빈도 × 요일·시간대 × 거리 × 최근성 × 선호
      │ → 성심당 대전역점 p=0.78 → CONFIRM "성심당 대전역점으로 갈까요?"
      ▼
③ NaviHelper 경로 안내 + 학습(가중치 1스텝 · '서심다응'=성심당 기억)
```

**이 모델은 장소 이름을 고치지 않고 '들린 그대로' 옮겨 적는다.** 이유:

1. **정답이 사람마다 다르다.** 같은 "성심당"도 대전 본가에 다니는 서준에게는 토요일 오전의 *대전역점*이고, 다른 사람에게는 *본점*이다. 모두가 쓰는 모델의 가중치로는 알 수 없다.
2. **개인 기록은 차 밖으로 나가면 안 된다.** 방문 기록은 차 안 암호화 저장소에만 있고, 보정은 그 기록을 읽는 순위 모델(숫자 6개)이 한다.
3. **개인 장소는 끝없이 새로 생긴다.** 가중치에 외우게 하면 없는 장소를 지어내고, 지우기도 어렵다. 기록을 '수첩'(DB)으로 두면 새 장소가 바로 반영되고 지우라고 하면 바로 지워진다.

그래서 이 모델의 일은 좁다 — **의도 분류 + 구간 복사 + 처리 위치 판단**. 0.6B 모델로 충분하고, 그래서 차 안에서 빠르게 돈다.

## 2. 모델 선택과 라이선스

| 후보 | 판단 |
|---|---|
| **Qwen3-0.6B** (채택) | 한국어 포함 다국어 사전학습, 0.6B(28층, hidden 1024), 생각 모드를 끌 수 있어 짧은 JSON만 낸다. Q4_K_M 약 0.4 GB로 차량 CPU/NPU에 올릴 수 있다. |
| Qwen3-1.7B | 정확도가 모자랄 때의 대안. 같은 스크립트에 `--model Qwen/Qwen3-1.7B`만 바꾸면 된다(지연·메모리 약 3배). |
| 인코더 분류기(int8 ~50 MB) | 의도 분류는 되지만 '들린 그대로의 장소 구간' 같은 생성형 슬롯을 다루기 어렵다. |

- **라이선스:** Qwen3-0.6B는 **Apache-2.0** — 상업적 사용·수정·재배포가 허용된다. 배포할 때 라이선스 전문과 NOTICE(있다면)를 함께 넣고, 수정했다는 사실을 밝힌다. LoRA 가중치와 합성 데이터는 우리 산출물이다.
- 학습 데이터의 상호·지명은 사람들이 실제로 부르는 **이름일 뿐**이며, 영업 정보나 제휴와 무관하다. 사용자 데이터·음성은 한 줄도 쓰지 않는다(§8).

## 3. 출력 계약 — 앱 Kotlin 타입과 1:1

출력은 JSON 한 줄, 키 순서 고정, **해당 없는 키는 생략**한다(`nlu_schema.py`).

| 키 | 값 | 앱 쪽 대응 |
|---|---|---|
| `intent` | `Intent` enum 이름 27개 (`NAV_PLACE`, `NAV_HOME`, `HVAC_SET_TEMP` …) | `NluResult.intent` |
| `place` | 장소를 가리킨 말, **들린 그대로** (`서심다응`, `엄마 집`, `빵집`) | `PlaceQuery.text` |
| `stop` | `destination` \| `waypoint` | `PlaceQuery.mode` (`StopMode`) |
| `category` | `BAKERY` `CAFE` `MART` `DEPT` `KIDS` `HOSPITAL` `PARK` — 장소 자리에 종류 단어만 왔을 때 | `PlaceQuery.category` |
| `habit` / `nearby` | `true` ("늘 가던", "자주 가는" / "근처", "가까운") | `PlaceQuery.habit` / `nearby` |
| `temp` / `percent` | 숫자 (22, 22.5 / 80) | `slots["temp"]` / `slots["percent"]` |
| `note` | 주차 위치 문자열 (`지하 2층 C구역 15번`) | `slots["note"]` |
| `route` | `edge` \| `cloud` | `complex = route=="cloud" && intent.tier==EDGE` |

라벨 규칙(애매한 경우):

- `집·우리 집 / 회사·사무실 / 휴게소·졸음쉼터·화장실 / 충전소` 같은 **일반 명칭**은 각각 `NAV_HOME / NAV_WORK / ADD_REST_STOP / FIND_CHARGER` (앱 `QueryParser`가 장소 명령에서 빼는 단어와 같다). `엄마 집`, `회사 앞 카페`, `안성휴게소`처럼 **특정 장소**는 `NAV_PLACE`.
- `집에 가는 길에 빵집 들렀다 가자` → `NAV_PLACE`(place=`빵집`, waypoint, BAKERY). 경유 맥락(`집에 가는 길에`)은 장소가 아니다.
- `졸린데 휴게소 들렀다 가야겠어` → `DROWSY` (안전 우선: 앱의 졸음 처리가 졸음쉼터 경유를 함께 제안한다).
- `아빠 아직 멀었어?`처럼 아이 말투가 분명하면 `KIDS_ETA`, 아니면 `ETA_QUERY` (앱은 `ctx.hasRearPassengers`로 다시 바꿀 수 있다).
- 숫자 온도가 있으면 `추워, 24도로 올려 줘`도 `HVAC_SET_TEMP`.
- 클라우드 의도(`TRIP_PLAN`, `DIAGNOSE`, `MANUAL_QA`, `ROUTINE_CREATE`, `BRIEFING`, `KIDS_GAME`, `OPEN_TALK`)는 슬롯을 내지 않는다 — 원문은 클라우드 추론기가 다시 읽는다.
- 엣지 의도라도 조건이 여럿이면(`충전소 찾아 줘 근데 카페 있는 데로`) `route:"cloud"`.

앱 연결 예시 (모델 출력 → `NluResult`, 앱 코드는 아직 바꾸지 않았다):

```kotlin
fun toNluResult(utterance: String, line: String, confidence: Float, latencyMs: Int): NluResult {
    val o = JSONObject(line)                                   // org.json (안드로이드 기본)
    val intent = runCatching { Intent.valueOf(o.getString("intent")) }.getOrDefault(Intent.OPEN_TALK)
    val slots = buildMap {
        for (k in listOf("temp", "percent", "note")) o.optString(k).takeIf { it.isNotEmpty() }?.let { put(k, it) }
    }
    val place = o.optString("place").takeIf { intent == Intent.NAV_PLACE && it.isNotEmpty() }?.let { text ->
        PlaceQuery(
            text = text,
            mode = if (o.optString("stop") == "waypoint") StopMode.WAYPOINT else StopMode.DESTINATION,
            category = o.optString("category").takeIf { it.isNotEmpty() }?.let { runCatching { Category.valueOf(it) }.getOrNull() },
            habit = o.optBoolean("habit"), nearby = o.optBoolean("nearby"), raw = utterance,
        )
    }
    val complex = o.optString("route") == "cloud" && intent.tier == RouteHint.EDGE
    return NluResult(intent, slots + (place?.let { mapOf("query" to it.text) } ?: emptyMap()), confidence, complex, latencyMs, place)
}
```

`confidence`는 `intent` 값 토큰들의 평균 로그확률을 `exp` 해서 쓴다(llama.cpp는 토큰 확률을 돌려준다). 0.6 미만이면 기존 규칙대로 클라우드로 보내고, JSON이 깨지거나 시간 초과(300 ms)면 기존 `KeywordIntentEngine` 결과를 쓴다.

## 4. 데이터 설계 — `gen_data.py`

**결정적 생성기**다. 같은 seed면 같은 파일이 나온다(문자열 seed → SHA-512, 집합 순회 없음).

```
python ml/gen_data.py            # → ml/data/{train,valid,test}.jsonl  (총 3.3 MB, 1초 이내)
```

한 줄 형식 (chat 형식 + 분석용 `meta`, 학습 입력에는 `messages`만 쓴다):

```json
{"messages":[{"role":"system","content":"너는 차량 음성비서 '옆자리'의 명령 해석기다. …"},
             {"role":"user","content":"지금 본죽 수원역섬으로 가자"},
             {"role":"assistant","content":"{\"intent\":\"NAV_PLACE\",\"place\":\"본죽 수원역섬\",\"stop\":\"destination\",\"route\":\"edge\"}"}],
 "meta":{"intent":"NAV_PLACE","tpl":"nd07","held_tpl":false,"noise":"place","clean":"본죽 수원역점","src":"chain"}}
```

### 4.1 문장 틀 (`templates.py`)

- 27개 의도에 **학습용 문장 틀 191개** + **평가 전용 틀 82개**(학습에 절대 쓰지 않음, 이번 test에는 그중 80개가 뽑혔다).
- 틀 문법: `{a|b|c}` 선택, `[GO] [VIA] [HAB] [NEAR]` 공용 표현 묶음, `<P:로>` 슬롯 + 받침에 맞는 조사(으로/로, 을/를, 이라고/라고 — 숫자·영문은 읽는 소리 기준).
- 구어 변형: 말머리(`옆자리야`, `음…`, `저기`, `있잖아`), 존댓말/반말(`가 줘`·`가 주세요`·`가 주실래요?`), 띄어쓰기 변형(STT 출력마다 다름), 문장부호 유무, 한자어 수사(`이십이 도`, `팔십 프로`), 소수 온도(`22.5도`), 주차 위치(`B2 C구역 15번`, `지하 3층 D7 기둥`).
- 목적지 / 경유지: `가자·안내해 줘·목적지로 설정해 줘` ↔ `들렀다 가자·들러 줘·경유지로 추가해 줘·X 가는 길에 P 들르자·P 찍고 가자`. 평가 전용 변형: `거쳐서 가자`, `P부터 들렀다 가자`, `가는 김에 P도 들르자`, `X 가기 전에 P 먼저 들르자`.
- **헷갈리기 쉬운 반례**: `유튜브에서 성심당 리뷰 영상 틀어 줘`(VIDEO_REQUEST), `성심당 몇 시까지 해?`(OPEN_TALK), `대전 가는 길에 성심당 들러서 점심 먹는 코스 짜 줘`(TRIP_PLAN), `이걸로 강릉까지 갈 수 있어?`(CHARGE_STATUS), `평일 아침마다 회사로 안내하고 21도로 맞추는 루틴 만들어 줘`(ROUTINE_CREATE, 슬롯 없음), `강아지 있으니까 에어컨 켜 놔 줘`(PET_MODE_ON).
- 앱 시연 문장 15개(`서심다응 가자`, `빵집 들렀다 가자` …)는 학습에 고정 포함한다(`nlu_schema.DEMO_EXAMPLES`, 이미 생성된 6개 제외 9개 추가).

### 4.2 장소 사전 (`lexicon.py`) — train/test 이름이 겹치지 않는다

| 출처 | 학습 | 평가 전용 | 예 |
|---|---|---|---|
| 체인·브랜드 × 지점 | 43 | 14 | `스타벅스 판교점`, `대전역 성심당` / test: `할리스`, `뚜레쥬르`, `AK플라자` |
| 명소·시설 | 40 | 16 | `올림픽공원`, `서울아산병원` / test: `동대문디자인플라자`, `오죽헌` |
| 개인 별칭 | 24 | 11 | `엄마 집`, `회사 앞 카페`, `도윤이 유치원` / test: `외할머니 댁`, `남편 회사` |
| 이름 있는 휴게소 | 7 | 4 | `덕평자연휴게소` / test: `행담도휴게소` |
| 합성 상호 | 160 | 60 | `늘호담꽃집`, `민새곱창` — 처음 보는 문자열을 그대로 옮겨 적는 능력 |
| 종류 단어 | 공통 | 공통 | `빵집`→BAKERY, `카페`→CAFE, `마트`→MART … (앱 `Category.words`와 같다) |

생성기가 **누출 검사**를 한다: 평가 전용 이름 105개 중 학습 사전과 겹치는 것 0개, 학습·검증 문장 안에 나타나는 것 0개.

### 4.3 음성인식 오류 주입 (`asr_noise.py`) — 앱의 `Hangul.kt`를 거울처럼

앱의 발음 비교(`Hangul.kt`)가 '싸게' 매기는 혼동을 그대로 오류로 만든다. 음절을 초성·중성·종성으로 풀어 바꾼 뒤 다시 조합한다.

| 연산 | 예 | 근거 |
|---|---|---|
| 모음 혼동 | ㅐ/ㅔ, ㅓ/ㅗ, ㅡ/ㅜ, ㅕ/ㅛ, ㅘ/ㅏ … `휴게소→휴개소` | `Hangul.kt` SIMILAR 모음 묶음·비용 |
| 자음 혼동 | ㄱ/ㄲ/ㅋ, ㄷ/ㄸ/ㅌ, ㅅ/ㅆ, ㄴ/ㄹ, ㅈ/ㅅ … `성심당→성짐당` | SIMILAR 자음 묶음, ㅎ 약화 |
| 받침 탈락 | `성→서` | ㅇ·ㅎ 삽입/삭제 비용 0.5 |
| 받침 분리 | `당→다응` (→ **서심다응**) | ㅡ 삽입 비용 0.5 |
| 받침 교체 | ㅇ/ㄴ/ㅁ, ㄱ/ㄷ/ㅂ | 대표음 표 |
| 발음대로 적기 | 연음 `음악→으막`, 비음화 `국물→궁물`, 유음화 `신라→실라`, 경음화 `식당→식땅`, 격음화 `입학→이팍` | 표준 발음 규칙 |
| 띄어쓰기·음절 탈락 | `롯데마트↔롯데 마트`, `파리바게뜨→파리바게` | STT 띄어쓰기 불안정 |

걸러내기: (1) 결과 음절이 모두 **KS X 1001 상용 2,350자**여야 한다 — 음성인식기는 언어모델 때문에 `퍈·싀` 같은 희귀 음절을 거의 내지 않는다. (2) 파이썬으로 옮긴 `Hangul.similarity(오류, 원래 이름) ≥ 0.6` — 앱의 순위 모델(`MIN_PHON = 0.55`)이 되돌릴 수 있는 오류만 학습한다. 이식이 맞는지는 `similarity("서심다응", "성심당 대전역점") = 0.889`로 확인했다(앱과 같은 값).

**정답의 `place`도 오류가 들어간 그대로다.** 모델은 고치지 말고 옮겨 적어야 한다. 그래야 `PersonalBrain.learn`이 "`서심다응` = 성심당"을 차 안에서 배울 수 있다.

### 4.4 생성 결과 (이 PC에서 실행, seed 7)

```
split sizes: train 6009 | valid 600 | test 600          (총 3.29 MB)
NAV_PLACE 2255/225/225 (37.5%) · 나머지 26개 의도 90~210개씩 · 클라우드 의도 7개
ASR noise
  train: NAV_PLACE place-noise 790/2255 = 35.0% | other light-noise 375/3754 = 10.0% | all noisy 19.4% | sim mean 0.938 min 0.667
  valid: NAV_PLACE place-noise  79/225  = 35.1% | other light-noise  38/375  = 10.1% | all noisy 19.5% | sim mean 0.937
  test : NAV_PLACE place-noise  79/225  = 35.1% | other light-noise  38/375  = 10.1% | all noisy 19.5% | sim mean 0.952
held-out check (test)
  test-only place names: 105 | shared with train lexicon: 0 | found inside train/valid utterances: 0
  test examples from test-only templates: 302/600 = 50.3% (80 templates; used in train: 0)
  test utterances identical to a train utterance: 0
lengths: utterance avg 14.7 chars | answer JSON avg 54.7 chars
```

의도별 개수 전체 표는 `python ml/gen_data.py` 출력에 있다. 표본:

```
[nd07/place] 지금 본죽 수원역섬으로 가자        → {"intent":"NAV_PLACE","place":"본죽 수원역섬","stop":"destination","route":"edge"}
[nw06]       옆자리야, 중간에 김밥천국 들렀다 가자 → {"intent":"NAV_PLACE","place":"김밥천국","stop":"waypoint","route":"edge"}
[cl04]       있잖아 목표 충전량 95%로 해 줘        → {"intent":"CHARGE_LIMIT","percent":95,"route":"edge"}
[c03]        저기 중간에 충전소들렀다 가자         → {"intent":"FIND_CHARGER","route":"edge"}
[ro05]       아 금요일마다 본가 가는 길 안내 자동으로 해 줘 → {"intent":"ROUTINE_CREATE","route":"cloud"}
```

## 5. 학습 레시피 — `train_lora.py`

| 항목 | 값 |
|---|---|
| 프레임워크 | transformers `Trainer` + peft LoRA (+ accelerate) |
| 프롬프트 | `apply_chat_template(system+user, add_generation_prompt=True, enable_thinking=False)` — Qwen3의 빈 생각 블록 `<think>\n\n</think>\n\n`까지 프롬프트에 포함 |
| 손실 | **assistant JSON + 끝 토큰 `<\|im_end\|>`에만** (프롬프트·패딩은 -100) |
| LoRA | r=16, α=32, dropout 0.05, `q_proj k_proj v_proj o_proj gate_proj up_proj down_proj`, 학습 파라미터 약 1,010만 개(전체의 약 1.7%) |
| 최적화 | lr 2e-4 cosine, warmup 3%, 3 epoch, 장치당 batch 32, bf16, max_len 256(넘으면 제외하고 개수 출력) |
| 평가 | epoch마다 valid loss, 끝나고 valid 32개를 바로 디코딩해 완전 일치율 출력 |
| 저장 | 어댑터 `ml/out/lora/` (+ `train_log.json`), `--merge` 시 병합 bf16 가중치 `ml/out/merged/` |

- 동적 패딩이라 실제 길이(대략 100~150 토큰)만큼만 메모리를 쓴다. 0.6B bf16 + batch 32면 **약 23 GB(B200 1/7 MIG 슬라이스)로 충분**하다. 부족하면 `--grad-ckpt` 또는 `--batch 16 --grad-accum 2`.
- 564 step(= ⌈6,009 / 32⌉ × 3). 학습 토큰이 수백만 개 수준이라 1/7 슬라이스에서도 수 분~십여 분이면 끝날 것으로 본다(추정, 실측 아님 — 실제 시간은 `ml/out/lora/train_log.json`).
- bf16을 못 쓰는 GPU(V100·T4)는 `--no-bf16`.
- 손실 마스킹은 이 PC에서 가짜 토크나이저로 검증했다: train 2,000줄 모두 '학습 대상 구간 = 정답 JSON + `<|im_end|>`', 프롬프트는 빈 생각 블록으로 끝남.

## 6. 평가 — `eval_nlu.py`

test 600개를 탐욕 디코딩으로 두 번 돌린다.

- (a) **기반 모델 + few-shot**: 스키마 설명 + 의도 목록 + 예시 11개를 넣은 긴 프롬프트(`nlu_schema.FEWSHOT_*`)
- (b) **기반 모델 + LoRA**: 학습과 같은 짧은 시스템 프롬프트, 어댑터를 병합해 추론(차에 실리는 형태)

| 지표 | 정의 |
|---|---|
| valid JSON | 출력 전체가 JSON 객체 하나인 비율 |
| 의도 정확도 | `intent` 일치 |
| JSON 완전 일치 | 모든 키·값 일치 (생략/`false`/`22.0==22` 정규화) |
| 장소 EM · 글자 F1 | 정답이 NAV_PLACE인 예제에서 `place` 완전 일치 · 글자 단위 F1(공백 무시) |
| stop 정확도 | NAV_PLACE의 destination/waypoint 일치 |
| 지연 | 배치 1 평균·p95(ms), 배치 처리 ms/예제, 평균 출력 토큰 수 |

모두 **깨끗한 발화 / 음성인식 오류 발화(장소 구간 오류 따로) / 학습 틀 / 평가 전용 틀**로 나눠 보고하고, 의도별 정확도와 가장 많이 헷갈린 의도, 오답 예시 25개, 시연 문장 15개 출력(학습 포함이라 지표에서는 뺀다)을 `ml/out/eval.md`에 쓴다. 예측 전체는 `ml/out/eval_predictions.jsonl`.

목표(`docs/AI_ARCHITECTURE.md`): 의도 정확도 ≥ 95%, 장소 구간 오류 발화의 장소 EM은 깨끗한 발화와 비슷한 수준(오류를 '고치지 않고' 옮겨 적는지). **아직 GPU에서 돌리지 않았으므로 결과 수치는 없다** — `run_gpu.sh` 실행 후 `ml/out/eval.md`를 보면 된다.

## 7. 차량으로 내보내기 — GGUF Q4_K_M + llama.cpp

```bash
# 1) LoRA 병합 (train_lora.py --merge 로 이미 했다면 생략)
python ml/train_lora.py --merge            # → ml/out/merged (safetensors, bf16)

# 2) llama.cpp 빌드 + 변환 + 양자화
git clone https://github.com/ggml-org/llama.cpp && cd llama.cpp
pip install -r requirements.txt            # 변환 스크립트 의존성
cmake -B build && cmake --build build --config Release -j
python convert_hf_to_gguf.py ../ml/out/merged --outtype f16 --outfile ../ml/out/yeopjari-nlu-f16.gguf
./build/bin/llama-quantize ../ml/out/yeopjari-nlu-f16.gguf ../ml/out/yeopjari-nlu-Q4_K_M.gguf Q4_K_M

# 3) 지연 측정 (차량과 같은 ARM 보드에서): 새 프롬프트 32토큰 + 출력 24토큰, 4스레드
./build/bin/llama-bench -m ../ml/out/yeopjari-nlu-Q4_K_M.gguf -p 32 -n 24 -t 4

# 4) 동작 확인 (학습과 똑같은 프롬프트 문자열)
./build/bin/llama-server -m ../ml/out/yeopjari-nlu-Q4_K_M.gguf -c 512 --port 8080 &
curl -s localhost:8080/completion -H 'Content-Type: application/json' -d @- <<'EOF'
{"prompt":"<|im_start|>system\n너는 차량 음성비서 '옆자리'의 명령 해석기다. 음성인식 결과 문장을 JSON 한 줄로 바꿔라. 장소 이름은 들린 그대로 쓰고 고치지 마라.<|im_end|>\n<|im_start|>user\n서심다응 가자<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n","n_predict":64,"temperature":0,"stop":["<|im_end|>"]}
EOF
```

- **크기:** Q4_K_M 약 0.4 GB(어휘 151,936 × 1024의 임베딩이 큰 몫). 앱 프로세스 RAM 가이드(≤ 400 MB)를 지키기 위해 모델은 **별도 프로세스의 추론 서비스**(예: `android:process=":nlu"`, llama.cpp JNI) 또는 플랫폼/NPU 서비스에 둔다. 가중치는 mmap으로 읽는다.
- **지연 목표 < 300 ms/명령.** 디코딩은 메모리 대역폭에 묶인다(토큰마다 가중치 약 0.4 GB를 읽음). 추정 예산:

| 구간 | 토큰 | 조건 | 추정 |
|---|---|---|---|
| 프롬프트 처리 | 약 20~30 (발화 + 틀) | 고정된 시스템 프롬프트의 KV 캐시를 미리 만들어 재사용 | 수십 ms |
| JSON 생성 | 평균 약 20 (의도만 있는 명령 약 10, 장소 명령 약 25 — 추정, 실제 평균은 eval.md '출력 토큰') | 응답 앞부분 `{"intent":"`를 프롬프트에 미리 채우면 3~4토큰 절약 | 토큰당 ≤ 12 ms 필요 |

  즉 **토큰당 12 ms(≈ 80 tok/s) 이상**이 나와야 300 ms 안에 든다. 차량 SoC의 큰 코어 4개 + Q4_K_M에서 경계선이고, NPU 경로(SoC 벤더 런타임)면 여유가 있다. 실제 값은 3)의 `llama-bench`로 대상 보드에서 재고, 모자라면 ① NPU, ② 응답 앞부분 미리 채우기, ③ JSON 스키마 제약 디코딩(llama-server `json_schema`, 잘못된 enum 원천 차단), ④ `KeywordIntentEngine`이 먼저 답하고 모델이 장소 명령·저신뢰 문장만 맡는 2단 구성 순으로 줄인다. 평가표의 GPU 지연은 참고값이다.

## 8. 개인정보 — 무엇이 어디서 학습되나

| 단계 | 어디서 | 무엇으로 | 밖으로 나가는 것 |
|---|---|---|---|
| 기반·도메인 LoRA (이 폴더) | 서버 GPU | **합성 데이터만** (사용자 문장·음성·방문 기록 0건) | – |
| 개인화 (`PersonalBrain`) | **차 안** | 사용자가 고른 결과 → 가중치 6개 1스텝, 표현 기억(`서심다응`→성심당), 선택 기억 | 없음. 개인 데이터 파일은 Android Keystore 키로 **AES-256-GCM** 암호화, 학습 이벤트는 SHA-256 해시 체인으로 기록, 사용자가 지우면 즉시 삭제 |
| (선택) 연합 학습 | 차 → 서버 | `FederatedUpdate`: **가중치 변화량 6개만**, 노름 1.0으로 자르고 가우시안 잡음 σ=0.05, 본문 SHA-256 + Keystore ECDSA P-256 서명 | 숫자 6개 (문장·장소·위치 없음) |
| (선택·향후) 야간 온디바이스 LoRA | 차 안, 주차·충전 중 | 사용자가 **확인한** 텍스트→JSON 쌍만으로 작은 어댑터(r=4) 갱신 | 없음. 어댑터도 차 안에 암호화 보관 |

- 음성 원본은 Pleos STT SDK 안에서만 처리되고(공모전 규칙), 이 파이프라인은 음성을 다루지 않는다.
- 개인 장소를 모델 가중치에 외우게 하지 않는다 — 지우기 어렵고, 없는 장소를 지어내고, 다른 사람에게 새어 나갈 수 있기 때문이다(§1).

## 9. GPU 서버에서 실행 (B200 1/7 MIG ≈ 23 GB면 충분)

한 번에:

```bash
git clone <이 저장소> yeopjari && cd yeopjari
nvidia-smi -L                                         # MIG 슬라이스 UUID 확인
export CUDA_VISIBLE_DEVICES=MIG-xxxxxxxx-xxxx-...     # 슬라이스 하나만 쓸 때
export TORCH_INDEX_URL=https://download.pytorch.org/whl/cu128   # B200(Blackwell)은 CUDA 12.8+ 빌드 torch 2.7 이상
bash ml/run_gpu.sh                                    # venv → pip → gen_data → train(--merge) → eval
```

단계별로:

```bash
cd ml
python3 -m venv .venv && source .venv/bin/activate
pip install -U pip
pip install "torch>=2.4" --index-url https://download.pytorch.org/whl/cu128   # GPU에 맞는 CUDA 빌드
pip install -r requirements.txt
python gen_data.py                                    # ml/data/*.jsonl (결정적)
python train_lora.py --merge                          # ml/out/lora, ml/out/merged
python eval_nlu.py                                    # ml/out/eval.md, eval_predictions.jsonl
# 빠른 동작 확인: python train_lora.py --max-steps 20 --limit-train 512 && python eval_nlu.py --limit 50 --skip-base
```

- 기반 모델은 Hugging Face Hub에서 내려받는다(공개 모델, 토큰 불필요, bf16 가중치 약 1.2 GB). 캐시 위치는 `HF_HOME`.
- 로그는 `ml/out/{gen_data,train,eval}.log`에 남는다.

## 10. 파일

| 파일 | 역할 |
|---|---|
| `nlu_schema.py` | 출력 계약(의도 27개·키 순서·종류 단어), 시스템 프롬프트, few-shot 프롬프트, 시연 문장, JSON 파싱·정규화 |
| `asr_noise.py` | 자모 분해·조합, `Hangul.kt` 발음 유사도 이식, 오류 연산, 조사(받침) 선택 |
| `lexicon.py` | 장소 사전(train/test 분리), 합성 상호, 동행·도시·반려동물 등 보조 어휘 |
| `templates.py` | 의도별 문장 틀(학습/평가 전용), 공용 표현 묶음, 의도 비율 |
| `gen_data.py` | 데이터 생성 + 통계·누출 검사 + 표본 출력 |
| `train_lora.py` | LoRA 학습(손실 마스킹), 어댑터 저장, `--merge` 병합 |
| `eval_nlu.py` | 기반 few-shot vs LoRA 평가, `out/eval.md` |
| `requirements.txt`, `run_gpu.sh` | GPU 서버 설치·일괄 실행 |
| `data/` | 생성된 jsonl (재생성 가능, 3.3 MB) |
| `out/` | 학습·평가 산출물 — **커밋하지 않는다**(어댑터·병합 가중치·GGUF는 수백 MB~GB, 언제든 다시 만든다) |

## 11. 이 PC에서 확인한 것과 한계

- 확인: `python ml/gen_data.py` 실행(통계 §4.4), 모든 `.py`의 `py_compile`, 가짜 토크나이저로 손실 마스킹 검증, 가짜 예측으로 평가 지표·보고서 생성 경로 검증. **GPU·torch가 없어 실제 학습·평가·GGUF 변환은 아직 돌리지 않았다.** Qwen3 채팅 틀 처리(`enable_thinking=False`)는 공개 문서 기준으로 구현했고, 실제 토크나이저에서의 길이·마스킹은 `train_lora.py`가 실행 시 출력한다.
- 합성 데이터만으로 학습한다. 실제 차량 STT 출력(동의 기반 텍스트) 소량으로 검증 세트를 따로 만들어 오류 모델(`asr_noise.py`)의 현실성을 맞춰야 한다.
- `KIDS_ETA`처럼 문장만으로 애매한 의도는 앱 문맥(뒷좌석 탑승)과 함께 판단한다.
