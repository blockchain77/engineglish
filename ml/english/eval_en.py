# -*- coding: utf-8 -*-
"""
옆자리 영어 '학습자 응답 이해' 평가.

    python eval_en.py --model Qwen/Qwen3-4B --adapter out/q4B/lora --test data/test.jsonl --out out/q4B
    python eval_en.py --baseline --test data/test.jsonl --out out/baseline      # 규칙 기반 비교군

지표: JSON 유효율, 행동(act) 정확도, 판정(verdict) 정확도(정답 시도만), 완전 일치(act·verdict·match·오류 유형),
      오류 유형 micro P/R/F1, 학습 문항(seen)/처음 보는 문항(unseen)·사례 유형별, 예제당 지연(배치).
"""
import argparse
import json
import re
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))


def read(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def parse(text):
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        d = json.loads(m.group(0))
        return d if isinstance(d, dict) else None
    except Exception:
        return None


def etypes(d):
    return Counter(e.get("t") for e in (d.get("errors") or []) if isinstance(e, dict))


# ── 규칙 기반 비교군 (앱의 1차 판정기와 같은 발상) ────────────────────────────────
def baseline_predict(user):
    from gen_en import COMMANDS, CONF, norm
    m = re.search(r"인식: (.*)$", user, re.S)
    hyp = norm(m.group(1) if m else "")
    if user.startswith("문항(qa"):
        kws = re.search(r"핵심어: (.*)\n", user).group(1).split(", ")
        if not hyp or len(hyp.split()) <= 1:
            return {"act": "noise", "verdict": "none", "match": -1, "errors": []}
        for act, ph in COMMANDS.items():
            if hyp in {norm(p) for p in ph}:
                return {"act": act, "verdict": "none", "match": -1, "errors": []}
        hit = any(k in hyp for k in kws)
        n = len(hyp.split())
        v = "correct" if hit and n >= 8 else ("partial" if hit else "wrong")
        return {"act": "answer", "verdict": v, "match": 0 if v != "wrong" else -1, "errors": []}
    answers = [norm(a) for a in re.search(r"정답: (.*)\n", user).group(1).split(" | ")]
    for act, ph in COMMANDS.items():
        if hyp in {norm(p) for p in ph}:
            return {"act": act, "verdict": "none", "match": -1, "errors": []}
    if not hyp or (len(hyp.split()) <= 1 and hyp not in answers and len(answers[0].split()) > 1):
        return {"act": "noise", "verdict": "none", "match": -1, "errors": []}
    fill = {"um", "uh", "well", "like", "hmm", "so"}
    hw = [w for w in hyp.split() if w not in fill]
    best = (-1.0, -1, None)
    for i, a in enumerate(answers):
        aw = a.split()
        # 토큰 편집 거리 (정렬)
        n, mm = len(aw), len(hw)
        dp = [[0] * (mm + 1) for _ in range(n + 1)]
        for x in range(n + 1):
            dp[x][0] = x
        for y in range(mm + 1):
            dp[0][y] = y
        for x in range(1, n + 1):
            for y in range(1, mm + 1):
                dp[x][y] = min(dp[x - 1][y] + 1, dp[x][y - 1] + 1, dp[x - 1][y - 1] + (aw[x - 1] != hw[y - 1]))
        sim = 1 - dp[n][mm] / max(n, mm, 1)
        if sim > best[0]:
            best = (sim, i, (aw, hw, dp))
    sim, i, (aw, hw, dp) = best
    errs = []
    x, y = len(aw), len(hw)
    while x > 0 and y > 0:
        if aw[x - 1] == hw[y - 1]:
            x -= 1; y -= 1
        elif dp[x][y] == dp[x - 1][y - 1] + 1:
            e, h = aw[x - 1], hw[y - 1]
            t = next((tt for hh, tt in CONF.get(e, []) if hh == h), None)
            errs.append({"t": t or "substitution", "e": e, "h": h}); x -= 1; y -= 1
        elif dp[x][y] == dp[x - 1][y] + 1:
            errs.append({"t": "article" if aw[x - 1] in {"a", "an", "the"} else "missing", "e": aw[x - 1], "h": ""}); x -= 1
        else:
            y -= 1
    if sim >= 0.999:
        return {"act": "answer", "verdict": "correct", "match": i, "errors": []}
    if sim >= 0.6:
        return {"act": "answer", "verdict": "partial", "match": i, "errors": [e for e in errs if e["t"] != "substitution"]}
    return {"act": "answer", "verdict": "wrong", "match": -1, "errors": []}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="Qwen/Qwen3-4B")
    ap.add_argument("--adapter", default="")
    ap.add_argument("--test", default=str(HERE / "data" / "test.jsonl"))
    ap.add_argument("--out", default=str(HERE / "out" / "eval"))
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--baseline", action="store_true")
    a = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    rows = read(a.test)
    if a.limit:
        rows = rows[:a.limit]
    t0 = time.time()
    preds = []
    if a.baseline:
        preds = [baseline_predict(r["messages"][1]["content"]) for r in rows]
        texts = [json.dumps(p, ensure_ascii=False) for p in preds]
        gen_sec = time.time() - t0
    else:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        tok = AutoTokenizer.from_pretrained(a.adapter or a.model)
        tok.padding_side = "left"
        if tok.pad_token is None:
            tok.pad_token = tok.eos_token
        try:
            model = AutoModelForCausalLM.from_pretrained(a.model, dtype=torch.bfloat16, device_map="cuda")
        except TypeError:
            model = AutoModelForCausalLM.from_pretrained(a.model, torch_dtype=torch.bfloat16, device_map="cuda")
        if a.adapter:
            from peft import PeftModel
            model = PeftModel.from_pretrained(model, a.adapter)
            model = model.merge_and_unload()
        model.eval()
        end = "<|im_end|>" if "<|im_end|>" in tok.get_vocab() else tok.eos_token
        end_id = tok.convert_tokens_to_ids(end)

        def prompt(r):
            kw = dict(tokenize=False, add_generation_prompt=True)
            try:
                return tok.apply_chat_template(r["messages"][:-1], enable_thinking=False, **kw)
            except TypeError:
                return tok.apply_chat_template(r["messages"][:-1], **kw)

        texts = []
        t0 = time.time()
        for i in range(0, len(rows), a.batch):
            chunk = rows[i:i + a.batch]
            enc = tok([prompt(r) for r in chunk], return_tensors="pt", padding=True, add_special_tokens=False).to("cuda")
            with torch.no_grad():
                o = model.generate(**enc, max_new_tokens=96, do_sample=False, eos_token_id=end_id, pad_token_id=tok.pad_token_id)
            texts += tok.batch_decode(o[:, enc["input_ids"].shape[1]:], skip_special_tokens=True)
            print(f"  {min(i + a.batch, len(rows))}/{len(rows)}", flush=True)
        gen_sec = time.time() - t0
        preds = [parse(t) for t in texts]
        # 단일 예제 지연 (배치 1) 10개
        lat = []
        for r in rows[:10]:
            enc = tok([prompt(r)], return_tensors="pt", add_special_tokens=False).to("cuda")
            torch.cuda.synchronize(); s = time.time()
            with torch.no_grad():
                model.generate(**enc, max_new_tokens=96, do_sample=False, eos_token_id=end_id, pad_token_id=tok.pad_token_id)
            torch.cuda.synchronize(); lat.append((time.time() - s) * 1000)

    tot = Counter(); by = defaultdict(Counter)
    tp = fp = fn = 0
    conf = Counter()
    for r, p in zip(rows, preds):
        g = json.loads(r["messages"][-1]["content"])
        m = r.get("meta", {})
        keys = ["all", "split:" + m.get("split", "?"), "cat:" + m.get("cat", "?"), "type:" + m.get("type", "?")]
        ok_json = p is not None
        p = p or {}
        act_ok = p.get("act") == g["act"]
        ver_ok = (p.get("verdict") == g["verdict"]) if g["act"] == "answer" else None
        full = act_ok and p.get("verdict") == g["verdict"] and p.get("match") == g["match"] and etypes(p) == etypes(g)
        ge, pe = etypes(g), etypes(p)
        tp += sum((ge & pe).values()); fp += sum((pe - ge).values()); fn += sum((ge - pe).values())
        if g["act"] == "answer":
            conf[(g["verdict"], p.get("verdict", "?"))] += 1
        for k in keys:
            c = by[k]
            c["n"] += 1; c["json"] += ok_json; c["act"] += act_ok; c["full"] += full
            if ver_ok is not None:
                c["ans"] += 1; c["ver"] += ver_ok
    prec = tp / max(tp + fp, 1); rec = tp / max(tp + fn, 1); f1 = 2 * prec * rec / max(prec + rec, 1e-9)
    name = "baseline(rule)" if a.baseline else (Path(a.adapter).parent.name if a.adapter else a.model)
    res = {"name": name, "n": len(rows), "gen_seconds": gen_sec,
           "per_example_ms_batched": gen_sec * 1000 / max(len(rows), 1),
           "latency_ms_batch1_avg": (sum(lat) / len(lat)) if not a.baseline else 0.0,
           "error_type_p": prec, "error_type_r": rec, "error_type_f1": f1, "groups": {}}
    for k, c in sorted(by.items()):
        res["groups"][k] = {"n": c["n"], "json": c["json"] / c["n"], "act": c["act"] / c["n"],
                            "verdict": (c["ver"] / c["ans"]) if c["ans"] else None, "full": c["full"] / c["n"]}
    res["verdict_confusion"] = {f"{g}->{p}": n for (g, p), n in sorted(conf.items())}
    od = Path(a.out); od.mkdir(parents=True, exist_ok=True)
    (od / "metrics.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    with open(od / "predictions.jsonl", "w", encoding="utf-8") as f:
        for r, t in zip(rows, texts):
            f.write(json.dumps({"user": r["messages"][1]["content"], "gold": r["messages"][-1]["content"], "pred": t},
                               ensure_ascii=False) + "\n")
    g = res["groups"]
    def fmt(k):
        x = g.get(k)
        if not x:
            return f"{k}: -"
        v = f"{x['verdict']*100:.1f}" if x["verdict"] is not None else "-"
        return f"{k}: n={x['n']} json={x['json']*100:.1f} act={x['act']*100:.1f} verdict={v} full={x['full']*100:.1f}"
    print(f"== {name}")
    for k in ["all", "split:seen", "split:unseen"]:
        print("  " + fmt(k))
    print(f"  error types P/R/F1 = {prec*100:.1f}/{rec*100:.1f}/{f1*100:.1f}")
    print(f"  per-example (batched) {res['per_example_ms_batched']:.1f} ms, batch-1 avg {res['latency_ms_batch1_avg']:.0f} ms")
    for k in sorted(g):
        if k.startswith("cat:"):
            print("  " + fmt(k))


if __name__ == "__main__":
    main()
