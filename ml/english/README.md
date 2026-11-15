# 엔진글리시 — 학습자 응답 이해 모델 (LoRA)

운전 중 영어 학습에서 **차량 STT(영어)가 돌려준 학습자 발화**를 문항과 함께 받아,
무엇을 했는지(답변·다시 듣기·건너뛰기·힌트·뜻·일시정지·재개·종료·잡음)와
답이 맞았는지(정답·부분 정답·오답), 어떤 오류였는지(한국어 화자 발음 혼동·문법)를 **JSON 한 줄**로 낸다.

앱 안에서는 규칙 엔진이 먼저 판정하고, 애매할 때만 이 모델을 부른다(모델이 없어도 앱은 동작).

```json
{"act":"answer","verdict":"partial","match":1,"errors":[{"t":"pron_rl","e":"room","h":"loom"}]}
```

## 파일
| 파일 | 내용 |
|---|---|
| `gen_en.py` | 합성 데이터 생성. 문항 씨앗 112개(한→영 문장, 따라 말하기, 단어, 토익 스피킹형)에 정답 변형·군말·자기 수정·발음 혼동(R/L, F/P, V/B, TH, SH/S, 받침 탈락 — 영어 STT가 내는 실제 단어로)·문법 오류(관사·시제·복수·전치사·어순)·단어 누락·엉뚱한 답·한국어 답(영어 STT로 깨진 소리)·명령(영어·한국어 발음)·잡음(내비 안내·라디오)을 섞는다. 시험은 학습에 없는 문항(unseen)과 학습 문항의 새 변형(seen)으로 나눈다. |
| `eval_en.py` | 평가: JSON 유효율, 행동 정확도, 판정 정확도, 완전 일치, 오류 유형 P/R/F1, 지연. `--baseline` 은 규칙 기반 비교군. |
| `train_lora.py` | LoRA 미세조정(손실은 assistant JSON에만), 어댑터 저장·병합 옵션 |
| `noao.py` | 학습 서버의 torchao 버전 충돌을 피하는 실행 래퍼 |
| `run_en.sh` | 생성 → 비교군 → Qwen3-4B·1.7B LoRA 학습(`train_lora.py`) → 평가를 한 번에 (tmux) |

## 실행 (NHN Cloud B200 MIG 1g.23gb)
```bash
pip install -r requirements.txt   # 가상환경 안에서
VENV=.venv tmux new -d -s en "bash run_en.sh > run.log 2>&1"
```
- 학습: LoRA r16/α32, 모든 선형층, 2 epoch, 배치 8×누적 2, max_len 320, bf16, gradient checkpointing
- 컨테이너의 torchao 0.15 를 peft 0.21 이 거부하므로 `noao.py`(torchao 를 없는 것으로 처리하는 실행 래퍼)로 학습·평가를 돌렸다.

결과는 `docs/eval/en_lora_eval.md` 에 정리한다.
