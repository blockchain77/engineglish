#!/usr/bin/env bash
# 옆자리 영어 응답 이해 LoRA: 데이터 생성 → 규칙 비교군 → Qwen3-4B·1.7B 학습·평가 (B200 MIG 1g.23gb 기준)
#   tmux new -d -s en "bash run_en.sh > run.log 2>&1"
set -uo pipefail
cd "$(dirname "$0")"
source "${VENV:-.venv}/bin/activate"   # VENV=가상환경 경로
export TOKENIZERS_PARALLELISM=false PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
mkdir -p out
python gen_en.py --out data --per-item 64 2>&1 | tee gen.log
python eval_en.py --baseline --test data/test.jsonl --out out/baseline 2>&1 | tee eval_baseline.log
for M in ${MODELS:-4B 1.7B}; do
  echo "=== train $M $(date +%T)"
  python noao.py train_lora.py --model Qwen/Qwen3-$M --train data/train.jsonl --valid data/valid.jsonl \
      --out out/q$M/lora --epochs ${EPOCHS:-2} --batch ${BATCH:-8} --grad-accum ${ACCUM:-2} --max-len 320 \
      --grad-ckpt --quick-check 0 --logging-steps 20 2>&1 | tee train_$M.log
  echo "=== eval $M $(date +%T)"
  python noao.py eval_en.py --model Qwen/Qwen3-$M --adapter out/q$M/lora --test data/test.jsonl --out out/q$M --batch 32 2>&1 | tee eval_$M.log
done
echo "ALL_DONE $(date +%T)"
