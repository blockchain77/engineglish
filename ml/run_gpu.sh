#!/usr/bin/env bash
# 옆자리 온디바이스 명령 해석 모델 — GPU 서버 한 번에 실행 (Linux + NVIDIA GPU, VRAM 약 23GB면 충분)
#
#   bash ml/run_gpu.sh                       # 데이터 생성 → LoRA 학습(+병합) → 평가
#   bash ml/run_gpu.sh --epochs 2 --lr 1e-4  # 추가 인자는 train_lora.py 로 전달
#
# 환경 변수
#   PYTHON=python3.11                               venv 를 만들 파이썬 (기본 python3)
#   VENV=.venv                                      venv 위치 (ml/ 기준)
#   TORCH_INDEX_URL=https://download.pytorch.org/whl/cu128
#                                                   torch 를 특정 CUDA 빌드로 먼저 설치 (B200·Blackwell 은 cu128 이상)
#   CUDA_VISIBLE_DEVICES=MIG-xxxxxxxx               MIG 슬라이스 지정 (nvidia-smi -L 로 UUID 확인)
set -euo pipefail
cd "$(dirname "$0")"

PY="${PYTHON:-python3}"
VENV="${VENV:-.venv}"

if [ ! -d "$VENV" ]; then
  "$PY" -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
python -m pip install --upgrade pip
if [ -n "${TORCH_INDEX_URL:-}" ]; then
  pip install "torch>=2.4" --index-url "$TORCH_INDEX_URL"
fi
pip install -r requirements.txt

nvidia-smi || echo "[warn] nvidia-smi 실행 실패 — GPU 가 보이는지 확인"
python - <<'PY'
import torch
print("torch", torch.__version__, "cuda", torch.version.cuda, "available", torch.cuda.is_available())
if torch.cuda.is_available():
    print("device", torch.cuda.get_device_name(0), "bf16", torch.cuda.is_bf16_supported())
PY

mkdir -p out
python gen_data.py | tee out/gen_data.log
python train_lora.py --merge "$@" 2>&1 | tee out/train.log
python eval_nlu.py 2>&1 | tee out/eval.log

echo
echo "완료 — 평가표: ml/out/eval.md · 어댑터: ml/out/lora · 병합 가중치: ml/out/merged"
echo "다음 단계(GGUF Q4_K_M 변환·차량 지연 측정)는 ml/README.md '차량으로 내보내기' 참고"
