# -*- coding: utf-8 -*-
"""
Qwen3 + LoRA 미세조정 — (문항 + 영어 음성인식 결과) → 응답 이해 JSON 한 줄.

    python train_lora.py --model Qwen/Qwen3-4B --train data/train.jsonl --valid data/valid.jsonl --out out/q4B/lora
    python train_lora.py --merge            # + 병합 가중치 (GGUF 변환용)
    python train_lora.py --max-steps 20 --limit-train 512   # 빠른 동작 확인

손실은 assistant 의 JSON(과 끝 토큰 <|im_end|>)에만 건다. 시스템·사용자 프롬프트와
Qwen3 의 빈 생각 블록(<think>\n\n</think>\n\n, enable_thinking=False)은 마스킹한다.
"""
import argparse
import inspect
import json
import math
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent


def parse_args():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="Qwen/Qwen3-4B")
    ap.add_argument("--train", default=str(HERE / "data" / "train.jsonl"))
    ap.add_argument("--valid", default=str(HERE / "data" / "valid.jsonl"))
    ap.add_argument("--out", default=str(HERE / "out" / "lora"))
    ap.add_argument("--merge", action="store_true", help="학습 후 LoRA 를 기반 가중치에 병합해 저장")
    ap.add_argument("--merged-out", default=str(HERE / "out" / "merged"))
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--batch", type=int, default=32, help="per-device batch size")
    ap.add_argument("--grad-accum", type=int, default=1)
    ap.add_argument("--max-len", type=int, default=256)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--lora-alpha", type=int, default=32)
    ap.add_argument("--lora-dropout", type=float, default=0.05)
    ap.add_argument("--target-modules", default="q_proj,k_proj,v_proj,o_proj,gate_proj,up_proj,down_proj")
    ap.add_argument("--warmup-ratio", type=float, default=0.03)
    ap.add_argument("--weight-decay", type=float, default=0.0)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--grad-ckpt", action="store_true", help="메모리가 부족할 때 gradient checkpointing")
    ap.add_argument("--no-bf16", action="store_true", help="bf16 미지원 GPU(예: V100·T4)에서 fp32 로 학습")
    ap.add_argument("--logging-steps", type=int, default=10)
    ap.add_argument("--num-workers", type=int, default=2)
    ap.add_argument("--max-steps", type=int, default=-1, help="동작 확인용 (기본: epochs 로 결정)")
    ap.add_argument("--limit-train", type=int, default=0, help="동작 확인용 학습 예제 수 제한")
    ap.add_argument("--quick-check", type=int, default=0, help="학습 후 valid 몇 개를 바로 디코딩해 JSON 완전 일치를 확인 (0=끔, 정식 평가는 eval_en.py)")
    return ap.parse_args()


def read_jsonl(path, limit=0):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
                if limit and len(rows) >= limit:
                    break
    return rows


def render_prompt(tok, messages):
    """system+user → 생성 프롬프트 (Qwen3: 생각 끔). 학습·평가·차량 추론이 같은 함수를 쓴다."""
    kw = dict(tokenize=False, add_generation_prompt=True)
    try:
        return tok.apply_chat_template(messages, enable_thinking=False, **kw)
    except TypeError:
        return tok.apply_chat_template(messages, **kw)


def end_token(tok):
    return "<|im_end|>" if "<|im_end|>" in tok.get_vocab() else tok.eos_token


def encode(tok, row, max_len, end):
    msgs = row["messages"]
    assert msgs[-1]["role"] == "assistant"
    prompt_ids = tok(render_prompt(tok, msgs[:-1]), add_special_tokens=False)["input_ids"]
    answer_ids = tok(msgs[-1]["content"] + end, add_special_tokens=False)["input_ids"]
    ids = prompt_ids + answer_ids
    if len(ids) > max_len:
        return None
    return {"input_ids": ids, "labels": [-100] * len(prompt_ids) + answer_ids}


class Collator:
    """오른쪽 패딩. 패딩 위치는 attention 0, label -100."""

    def __init__(self, pad_id):
        self.pad_id = pad_id

    def __call__(self, batch):
        import torch

        n = max(len(b["input_ids"]) for b in batch)
        ids = torch.full((len(batch), n), self.pad_id, dtype=torch.long)
        att = torch.zeros((len(batch), n), dtype=torch.long)
        lab = torch.full((len(batch), n), -100, dtype=torch.long)
        for i, b in enumerate(batch):
            k = len(b["input_ids"])
            ids[i, :k] = torch.tensor(b["input_ids"], dtype=torch.long)
            att[i, :k] = 1
            lab[i, :k] = torch.tensor(b["labels"], dtype=torch.long)
        return {"input_ids": ids, "attention_mask": att, "labels": lab}


def load_model(name, dtype):
    from transformers import AutoModelForCausalLM

    try:  # transformers ≥ 4.56: dtype=, 이전 버전: torch_dtype=
        return AutoModelForCausalLM.from_pretrained(name, dtype=dtype)
    except TypeError:
        return AutoModelForCausalLM.from_pretrained(name, torch_dtype=dtype)


def supported_kwargs(cls, kwargs):
    params = inspect.signature(cls.__init__).parameters
    dropped = [k for k in kwargs if k not in params]
    if dropped:
        print(f"[warn] {cls.__name__} 가 지원하지 않는 인자 생략: {dropped}")
    return {k: v for k, v in kwargs.items() if k in params}


def quick_check(model, tok, rows, n, end):
    """valid 일부를 탐욕 디코딩해 JSON 완전 일치율을 바로 본다 (정식 평가는 eval_en.py)."""
    import torch

    rows = rows[:n]
    tok.padding_side = "left"
    prompts = [render_prompt(tok, r["messages"][:-1]) for r in rows]
    enc = tok(prompts, return_tensors="pt", padding=True, add_special_tokens=False).to(model.device)
    end_id = tok.convert_tokens_to_ids(end)
    model.eval()
    with torch.no_grad():
        out = model.generate(**enc, max_new_tokens=96, do_sample=False, eos_token_id=end_id, pad_token_id=tok.pad_token_id)
    texts = tok.batch_decode(out[:, enc["input_ids"].shape[1]:], skip_special_tokens=True)

    def canon(t):
        try:
            return json.dumps(json.loads(t.strip()), ensure_ascii=False, sort_keys=True)
        except Exception:
            return None

    ok = sum(1 for r, t in zip(rows, texts) if canon(t) is not None and canon(t) == canon(r["messages"][-1]["content"]))
    print(f"[quick-check] valid {len(rows)}개 JSON 완전 일치 {ok}/{len(rows)}")
    for r, t in list(zip(rows, texts))[:5]:
        print("  " + r["messages"][1]["content"] + "  →  " + t.strip())
    tok.padding_side = "right"


def main():
    args = parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    import torch
    from datasets import Dataset
    from peft import LoraConfig, get_peft_model
    from transformers import AutoTokenizer, Trainer, TrainingArguments, set_seed

    set_seed(args.seed)
    bf16 = not args.no_bf16 and torch.cuda.is_available() and torch.cuda.is_bf16_supported()
    if not torch.cuda.is_available():
        print("[warn] CUDA 없음 — CPU 학습은 매우 느리다 (동작 확인용으로만 --max-steps 를 쓰자)")
    dtype = torch.bfloat16 if bf16 else torch.float32

    tok = AutoTokenizer.from_pretrained(args.model)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    tok.padding_side = "right"
    end = end_token(tok)

    t0 = time.time()
    data = {}
    for split, path, limit in (("train", args.train, args.limit_train), ("valid", args.valid, 0)):
        rows = read_jsonl(path, limit)
        enc = [encode(tok, r, args.max_len, end) for r in rows]
        kept = [e for e in enc if e is not None]
        lens = [len(e["input_ids"]) for e in kept]
        ans = [sum(1 for x in e["labels"] if x != -100) for e in kept]
        print(f"[data] {split}: {len(kept)}/{len(rows)} (max_len {args.max_len} 초과로 제외 {len(rows) - len(kept)}) | "
              f"토큰 평균 {sum(lens) / len(lens):.1f} 최대 {max(lens)} | 정답 토큰 평균 {sum(ans) / len(ans):.1f}")
        data[split] = Dataset.from_list(kept)
    print(f"[data] 토큰화 {time.time() - t0:.1f}s")

    model = load_model(args.model, dtype)
    model.config.use_cache = False
    lcfg = LoraConfig(r=args.lora_r, lora_alpha=args.lora_alpha, lora_dropout=args.lora_dropout, bias="none",
                      target_modules=[m.strip() for m in args.target_modules.split(",") if m.strip()],
                      task_type="CAUSAL_LM")
    model = get_peft_model(model, lcfg)
    model.print_trainable_parameters()

    world = max(1, torch.cuda.device_count()) if torch.cuda.is_available() else 1
    steps_per_epoch = math.ceil(len(data["train"]) / (args.batch * args.grad_accum * world))
    total_steps = args.max_steps if args.max_steps > 0 else math.ceil(steps_per_epoch * args.epochs)
    warmup = int(round(args.warmup_ratio * total_steps))
    print(f"[train] {steps_per_epoch} steps/epoch × {args.epochs} epochs ≈ {total_steps} steps, warmup {warmup}, bf16={bf16}")

    tdir = Path(args.out).parent / "trainer"
    targs = TrainingArguments(**supported_kwargs(TrainingArguments, dict(
        output_dir=str(tdir),
        per_device_train_batch_size=args.batch,
        per_device_eval_batch_size=args.batch,
        gradient_accumulation_steps=args.grad_accum,
        num_train_epochs=args.epochs,
        max_steps=args.max_steps,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=warmup,
        weight_decay=args.weight_decay,
        bf16=bf16,
        logging_steps=args.logging_steps,
        eval_strategy="epoch" if args.max_steps <= 0 else "steps",
        eval_steps=None if args.max_steps <= 0 else max(1, args.max_steps // 2),
        save_strategy="no",
        report_to="none",
        seed=args.seed,
        dataloader_num_workers=args.num_workers,
        remove_unused_columns=False,
        label_names=["labels"],  # PeftModel 은 기반 모델 인자를 가려서 자동 추론이 안 될 수 있다 → eval_loss 누락 방지
        gradient_checkpointing=args.grad_ckpt,
        gradient_checkpointing_kwargs={"use_reentrant": False} if args.grad_ckpt else None,
    )))
    trainer = Trainer(model=model, args=targs, train_dataset=data["train"], eval_dataset=data["valid"],
                      data_collator=Collator(tok.pad_token_id))
    t1 = time.time()
    trainer.train()
    train_sec = time.time() - t1
    metrics = trainer.evaluate()
    ev = metrics.get("eval_loss")
    print(f"[train] {train_sec / 60:.1f} min, 최종 valid loss {ev:.4f}" if ev is not None else f"[train] {train_sec / 60:.1f} min, {metrics}")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(out))
    tok.save_pretrained(str(out))
    log = {"args": vars(args), "train_seconds": train_sec, "final_eval": metrics,
           "log_history": trainer.state.log_history,
           "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu"}
    (out / "train_log.json").write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[save] 어댑터 → {out}")

    if args.quick_check > 0:
        model.config.use_cache = True
        quick_check(model, tok, read_jsonl(args.valid, args.quick_check), args.quick_check, end)

    if args.merge:
        from peft import PeftModel

        del trainer, model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        base = load_model(args.model, torch.bfloat16)
        merged = PeftModel.from_pretrained(base, str(out)).merge_and_unload()
        mdir = Path(args.merged_out)
        mdir.mkdir(parents=True, exist_ok=True)
        merged.save_pretrained(str(mdir), safe_serialization=True)
        tok.save_pretrained(str(mdir))
        print(f"[save] 병합 가중치(bf16) → {mdir}  (GGUF 변환용)")


if __name__ == "__main__":
    main()
