# -*- coding: utf-8 -*-
"""
test 세트 평가 — (a) 기반 모델 + few-shot 프롬프트, (b) 기반 모델 + LoRA (병합 후 추론).

    python ml/eval_nlu.py                          # → ml/out/eval.md, ml/out/eval_predictions.jsonl
    python ml/eval_nlu.py --skip-base --limit 100  # LoRA 만 빠르게
    python ml/eval_nlu.py --merged ml/out/merged   # 병합 가중치를 바로 평가

지표 (모두 탐욕 디코딩):
  valid JSON   출력 전체가 JSON 객체 하나인 비율
  의도 정확도   intent 일치
  완전 일치     키·값 전체 일치 (None/False 생략, 22.0==22)
  장소 EM/F1    정답이 NAV_PLACE 인 예제에서 place 문자열 완전 일치 / 글자 단위 F1(공백 무시)
  stop 정확도   NAV_PLACE 의 destination|waypoint 일치
  지연          배치 1, 예제당 생성 시간 (GPU 기준 — 차량 지연은 llama.cpp 로 따로 잰다)
깨끗한 발화와 음성인식 오류 발화, 학습 틀과 평가 전용 틀을 나눠서도 보고한다.
시연 문장(nlu_schema.DEMO_EXAMPLES)은 학습에 포함된 문장이라 지표에서 빼고 별도 표로만 싣는다.
"""
import argparse
import json
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from nlu_schema import DEMO_EXAMPLES, INTENTS, canonical, chat_messages, fewshot_messages, parse_output  # noqa: E402
from train_lora import end_token, load_model, read_jsonl, render_prompt  # noqa: E402


def parse_args():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="Qwen/Qwen3-0.6B")
    ap.add_argument("--adapter", default=str(HERE / "out" / "lora"))
    ap.add_argument("--merged", default="", help="병합 가중치 폴더 (주면 --adapter 대신 사용, 기반 모델 평가는 생략)")
    ap.add_argument("--test", default=str(HERE / "data" / "test.jsonl"))
    ap.add_argument("--out-md", default=str(HERE / "out" / "eval.md"))
    ap.add_argument("--out-pred", default=str(HERE / "out" / "eval_predictions.jsonl"))
    ap.add_argument("--batch-size", type=int, default=32)
    ap.add_argument("--max-new-tokens", type=int, default=96)
    ap.add_argument("--latency-samples", type=int, default=50, help="배치 1 지연 측정 예제 수")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--skip-base", action="store_true")
    ap.add_argument("--skip-lora", action="store_true")
    return ap.parse_args()


def char_f1(pred, gold):
    p = [c for c in (pred or "") if not c.isspace()]
    g = [c for c in (gold or "") if not c.isspace()]
    if not p or not g:
        return float(p == g)
    k = sum((Counter(p) & Counter(g)).values())
    if k == 0:
        return 0.0
    pr, rc = k / len(p), k / len(g)
    return 2 * pr * rc / (pr + rc)


class Generator:
    def __init__(self, model, tok, max_new_tokens):
        import torch
        from transformers import GenerationConfig

        self.torch = torch
        self.model = model.eval()
        self.tok = tok
        ids = [tok.convert_tokens_to_ids(end_token(tok))]
        if tok.eos_token_id is not None and tok.eos_token_id not in ids:
            ids.append(tok.eos_token_id)
        # 기반 모델의 샘플링 기본값(temperature 등)을 물려받지 않도록 새 설정으로 탐욕 디코딩
        self.gc = GenerationConfig(max_new_tokens=max_new_tokens, do_sample=False, eos_token_id=ids,
                                   pad_token_id=tok.pad_token_id)

    def __call__(self, prompts):
        self.tok.padding_side = "left"
        enc = self.tok(prompts, return_tensors="pt", padding=True, add_special_tokens=False).to(self.model.device)
        with self.torch.no_grad():
            out = self.model.generate(**enc, generation_config=self.gc)
        new = out[:, enc["input_ids"].shape[1]:]
        texts = self.tok.batch_decode(new, skip_special_tokens=True)
        n_tok = [int((row != self.tok.pad_token_id).sum()) for row in new]
        return texts, n_tok

    def sync(self):
        if self.torch.cuda.is_available():
            self.torch.cuda.synchronize()


def run_model(name, gen, prompt_fn, utts, bs, lat_n):
    prompts = [render_prompt(gen.tok, prompt_fn(u)) for u in utts]
    t0 = time.time()
    texts, ntoks = [], []
    for i in range(0, len(prompts), bs):
        t, n = gen(prompts[i:i + bs])
        texts += t
        ntoks += n
        print(f"\r[{name}] {min(i + bs, len(prompts))}/{len(prompts)}", end="", flush=True)
    gen.sync()
    batched_ms = (time.time() - t0) * 1000 / max(1, len(prompts))
    print()
    lat = []
    for p in prompts[:lat_n]:
        gen.sync()
        t1 = time.time()
        gen([p])
        gen.sync()
        lat.append((time.time() - t1) * 1000)
    lat.sort()
    timing = {"batched_ms_per_ex": batched_ms,
              "bs1_mean_ms": sum(lat) / len(lat) if lat else None,
              "bs1_p95_ms": lat[min(len(lat) - 1, int(len(lat) * 0.95))] if lat else None,
              "mean_new_tokens": sum(ntoks) / max(1, len(ntoks))}
    return texts, timing


def score(utts, golds, metas, texts):
    out = []
    for u, gold, meta, t in zip(utts, golds, metas, texts):
        pred, strict = parse_output(t)
        rec = {"utt": u, "gold": gold, "raw": t, "pred": pred, "meta": meta, "valid": strict,
               "intent": bool(pred) and pred.get("intent") == gold["intent"],
               "exact": canonical(pred) == canonical(gold),
               "route": bool(pred) and pred.get("route") == gold.get("route")}
        if gold["intent"] == "NAV_PLACE":
            pp = pred.get("place") if isinstance(pred, dict) and isinstance(pred.get("place"), str) else ""
            rec["place_em"] = pp == gold["place"]
            rec["place_f1"] = char_f1(pp, gold["place"])
            rec["stop"] = bool(pred) and pred.get("stop") == gold["stop"]
        out.append(rec)
    return out


def summarize(recs):
    nav = [r for r in recs if "place_em" in r]

    def rate(xs, key):
        return sum(1 for x in xs if x[key]) / len(xs) if xs else None

    return {"n": len(recs), "n_nav": len(nav), "valid": rate(recs, "valid"), "intent": rate(recs, "intent"),
            "exact": rate(recs, "exact"), "route": rate(recs, "route"), "place_em": rate(nav, "place_em"),
            "place_f1": (sum(r["place_f1"] for r in nav) / len(nav)) if nav else None, "stop": rate(nav, "stop")}


def pct(x):
    return "–" if x is None else f"{x * 100:.1f}%"


def ms(x):
    return "–" if x is None else f"{x:.0f}"


def subsets(recs):
    return [("전체", recs),
            ("깨끗한 발화", [r for r in recs if r["meta"]["noise"] == "none"]),
            ("음성인식 오류 발화", [r for r in recs if r["meta"]["noise"] != "none"]),
            ("└ 장소 구간 오류", [r for r in recs if r["meta"]["noise"] == "place"]),
            ("학습에 있던 틀", [r for r in recs if not r["meta"]["held_tpl"]]),
            ("평가 전용 틀", [r for r in recs if r["meta"]["held_tpl"]])]


def write_report(path, results, args, env):
    L = ["# 옆자리 온디바이스 명령 해석 — 평가 결과\n",
         f"- 생성: {datetime.now():%Y-%m-%d %H:%M} · 기반 모델 `{args.model}` · 어댑터 `{args.merged or args.adapter}` · "
         f"test `{Path(args.test).name}`",
         f"- 환경: {env}",
         "- 탐욕 디코딩. test 의 장소 이름은 학습에 한 번도 나오지 않았고, 절반은 학습에 없던 문장 틀이다.",
         "- 지연은 GPU·transformers 기준이다. 차량 목표(< 300 ms)는 GGUF Q4_K_M + llama.cpp 로 따로 잰다(README).\n",
         "## 요약\n",
         "| 모델 | n | valid JSON | 의도 정확도 | JSON 완전 일치 | 장소 EM | 장소 글자 F1 | stop 정확도 | route 정확도 | 지연 bs1 평균(ms) | p95(ms) | 배치(ms/예제) | 출력 토큰 |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, res in results.items():
        s, t = summarize(res["recs"]), res["timing"]
        L.append(f"| {name} | {s['n']} | {pct(s['valid'])} | {pct(s['intent'])} | {pct(s['exact'])} | {pct(s['place_em'])} | "
                 f"{pct(s['place_f1'])} | {pct(s['stop'])} | {pct(s['route'])} | {ms(t['bs1_mean_ms'])} | "
                 f"{ms(t['bs1_p95_ms'])} | {ms(t['batched_ms_per_ex'])} | {t['mean_new_tokens']:.1f} |")
    L += ["\n## 깨끗한 발화 vs 음성인식 오류 발화 · 학습 틀 vs 평가 전용 틀\n",
          "| 모델 | 구분 | n | valid JSON | 의도 정확도 | 완전 일치 | 장소 EM (NAV n) | 장소 글자 F1 | stop 정확도 |",
          "|---|---|---|---|---|---|---|---|---|"]
    for name, res in results.items():
        for label, sub in subsets(res["recs"]):
            if sub:
                s = summarize(sub)
                L.append(f"| {name} | {label} | {s['n']} | {pct(s['valid'])} | {pct(s['intent'])} | {pct(s['exact'])} | "
                         f"{pct(s['place_em'])} ({s['n_nav']}) | {pct(s['place_f1'])} | {pct(s['stop'])} |")

    last = list(results)[-1]
    recs = results[last]["recs"]
    L += [f"\n## 의도별 — {last}\n", "| 의도 | n | 의도 정확도 | 완전 일치 | 가장 많이 헷갈린 의도 |", "|---|---|---|---|---|"]
    by = defaultdict(list)
    for r in recs:
        by[r["gold"]["intent"]].append(r)
    for it in INTENTS:
        xs = by.get(it, [])
        if xs:
            wrong = Counter((r["pred"] or {}).get("intent", "(파싱 실패)") for r in xs if not r["intent"])
            top = ", ".join(f"{k} {v}" for k, v in wrong.most_common(2)) or "–"
            L.append(f"| {it} | {len(xs)} | {pct(sum(r['intent'] for r in xs) / len(xs))} | "
                     f"{pct(sum(r['exact'] for r in xs) / len(xs))} | {top} |")

    demo = results[last]["demo"]
    if demo:
        L += [f"\n## 시연 문장 — {last} (학습 데이터에 포함된 문장, 지표 제외)\n", "| 발화 | 출력 | 일치 |", "|---|---|---|"]
        for r in demo:
            L.append(f"| {r['utt']} | `{r['raw'].strip()}` | {'O' if r['exact'] else 'X'} |")

    errs = [r for r in recs if not r["exact"]][:25]
    if errs:
        L += [f"\n## 오답 예시 — {last} (최대 25개)\n", "| 발화 | 정답 | 출력 | 오류 종류 |", "|---|---|---|---|"]
        for r in errs:
            g = json.dumps(r["gold"], ensure_ascii=False, separators=(",", ":"))
            raw = r["raw"].strip().replace("|", "\\|")[:160]
            L.append(f"| {r['utt']} | `{g}` | `{raw}` | {r['meta']['noise']} |")
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("\n".join(L) + "\n", encoding="utf-8")


def main():
    args = parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    import torch
    import transformers
    from transformers import AutoTokenizer

    rows = read_jsonl(args.test, args.limit)
    utts = [r["messages"][1]["content"] for r in rows]
    golds = [json.loads(r["messages"][2]["content"]) for r in rows]
    metas = [r["meta"] for r in rows]
    demo_utts = [u for u, _ in DEMO_EXAMPLES]
    demo_golds = [d for _, d in DEMO_EXAMPLES]
    demo_metas = [{"noise": "demo", "held_tpl": False} for _ in DEMO_EXAMPLES]

    cuda = torch.cuda.is_available()
    dtype = torch.bfloat16 if cuda and torch.cuda.is_bf16_supported() else (torch.float16 if cuda else torch.float32)
    device = "cuda" if cuda else "cpu"
    env = (f"{torch.cuda.get_device_name(0) if cuda else 'CPU'} · torch {torch.__version__} · "
           f"transformers {transformers.__version__} · {str(dtype).replace('torch.', '')}")
    print(f"[eval] test {len(rows)}개 · {env}")

    tok = AutoTokenizer.from_pretrained(args.merged or args.model)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    results = {}

    def evaluate(name, model, prompt_fn):
        gen = Generator(model.to(device), tok, args.max_new_tokens)
        texts, timing = run_model(name, gen, prompt_fn, utts, args.batch_size, args.latency_samples)
        dtexts, _ = run_model(name + " · 시연", gen, prompt_fn, demo_utts, args.batch_size, 0)
        res = {"recs": score(utts, golds, metas, texts), "timing": timing,
               "demo": score(demo_utts, demo_golds, demo_metas, dtexts)}
        s = summarize(res["recs"])
        print(f"[{name}] valid {pct(s['valid'])} · 의도 {pct(s['intent'])} · 완전 일치 {pct(s['exact'])} · "
              f"장소 EM {pct(s['place_em'])} · stop {pct(s['stop'])} · bs1 {ms(timing['bs1_mean_ms'])} ms")
        results[name] = res

    base = None
    if not args.skip_base and not args.merged:
        base = load_model(args.model, dtype)
        evaluate("기반 모델 few-shot", base, fewshot_messages)
    if not args.skip_lora:
        if args.merged:
            model = load_model(args.merged, dtype)
        else:
            from peft import PeftModel

            base = base if base is not None else load_model(args.model, dtype)
            model = PeftModel.from_pretrained(base, args.adapter).merge_and_unload()
        evaluate("기반 모델 + LoRA", model, chat_messages)
    if not results:
        print("[eval] 평가할 모델이 없다 (--skip-base 와 --skip-lora 를 함께 줬는지 확인)")
        return

    write_report(args.out_md, results, args, env)
    Path(args.out_pred).parent.mkdir(parents=True, exist_ok=True)
    with open(args.out_pred, "w", encoding="utf-8") as f:
        for name, res in results.items():
            for kind in ("recs", "demo"):
                for r in res[kind]:
                    f.write(json.dumps({"model": name, "utt": r["utt"], "gold": r["gold"], "raw": r["raw"],
                                        "exact": r["exact"], "noise": r["meta"]["noise"],
                                        "held_tpl": r["meta"]["held_tpl"]}, ensure_ascii=False) + "\n")
    print(f"[eval] → {args.out_md}\n[eval] → {args.out_pred}")


if __name__ == "__main__":
    main()
