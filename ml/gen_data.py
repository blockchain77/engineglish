# -*- coding: utf-8 -*-
"""
옆자리 온디바이스 명령 해석 모델용 합성 데이터 생성기 (결정적: 같은 seed → 같은 파일).

    python ml/gen_data.py                 # ml/data/{train,valid,test}.jsonl
    python ml/gen_data.py --train 6000 --valid 600 --test 600 --seed 7

한 줄 = {"messages":[system, user(STT 문장), assistant(JSON 한 줄)], "meta":{...}}
meta 는 평가 분석용(의도, 오류 종류, 틀 id, 평가 전용 틀 여부, 원래 장소 이름)이며 학습 입력에는 쓰지 않는다.

설계
  - 의도별 틀 × 공용 표현 묶음 × 말머리(옆자리야/음…) × 존댓말/반말 × 띄어쓰기·문장부호 변형
  - NAV_PLACE 의 약 35%: 장소 구간에 자모 단위 음성인식 오류 (asr_noise.noisy_place) — 정답 place 도 '들린 그대로'
  - 나머지 의도의 약 10%: 명령 문장(슬롯 제외)에 가벼운 자모 오류 1개
  - test: 학습에 없는 장소 이름만 쓰고, 절반은 학습에 없는 틀로 만든다 (누출 검사 포함)
"""
import argparse
import json
import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from asr_noise import josa, light_noise, noisy_place, similarity
from lexicon import CITIES, COMPANIONS, KID_CALL, KID_DEST, ON_THE_WAY, PETS, PURPOSE, PlacePool
from nlu_schema import DEMO_EXAMPLES, INTENTS, SYSTEM_PROMPT, to_json_line
from templates import CAT, FILLERS, FRAMES, INTENT_WEIGHTS, MACROS, NAV_FRAMES

ALT = re.compile(r"\{([^{}]*)\}")
MACRO = re.compile(r"\[(GO|VIA|HAB|NEAR)\]")
SLOT = re.compile(r"<([A-Z]+)(?::([가-힣]+))?>")
P_SLOT = re.compile(r"<P(?::[가-힣]+)?>")

P_PLACE_NOISE = 0.35   # NAV_PLACE 중 장소 구간 오류 비율
P_LIGHT_NOISE = 0.10   # 다른 의도 중 가벼운 오류 비율
P_FILLER = 0.20
P_HELD_TEMPLATE = 0.5  # test 에서 평가 전용 틀을 쓰는 비율

SINO = "영일이삼사오육칠팔구"


def sino(n):
    """정수 → 한자어 수사 (22 → 이십이, 100 → 백)."""
    if n == 100:
        return "백"
    tens, ones = divmod(n, 10)
    s = ""
    if tens:
        s += ("" if tens == 1 else SINO[tens]) + "십"
    if ones:
        s += SINO[ones]
    return s or "영"


def wchoice(rng, pairs):
    total = sum(w for _, w in pairs)
    x = rng.random() * total
    for v, w in pairs:
        x -= w
        if x <= 0:
            return v
    return pairs[-1][0]


TEMPS = list(zip(range(16, 31), [1, 1, 2, 3, 4, 5, 6, 6, 5, 4, 3, 2, 1, 1, 1]))


def render_temp(rng):
    whole = wchoice(rng, TEMPS)
    half = whole < 30 and rng.random() < 0.12
    r = rng.random()
    if half:
        text = f"{whole}.5도" if r < 0.75 else f"{sino(whole)} 점 오 도"
        return text, whole + 0.5
    if r < 0.68:
        text = f"{whole}도"
    elif r < 0.73:
        text = f"{whole} 도"
    elif r < 0.93:
        text = f"{sino(whole)} 도"
    else:
        text = f"{sino(whole)}도"
    return text, whole


PERCENTS = [(50, 1), (60, 2), (70, 3), (75, 1), (80, 6), (85, 2), (90, 5), (95, 1), (100, 2)]


def render_percent(rng):
    v = wchoice(rng, PERCENTS)
    form = wchoice(rng, [("a", 35), ("b", 20), ("c", 5), ("d", 15), ("e", 12), ("f", 10), ("g", 3)])
    text = {"a": f"{v}%", "b": f"{v}퍼센트", "c": f"{v} 퍼센트", "d": f"{v}프로",
            "e": f"{sino(v)} 퍼센트", "f": f"{sino(v)} 프로", "g": f"{sino(v)}프로"}[form]
    return text, v


FLOORS = ["지하 1층", "지하 2층", "지하 3층", "지하 4층", "지하 5층", "B1", "B2", "B3", "B4", "B1층", "B2층", "B3층",
          "지상 1층", "2층", "3층", "4층", "5층", "옥상"]


def render_memo(rng):
    f = rng.choice(FLOORS)
    latin = rng.random() < 0.8
    z = rng.choice("ABCDEFGH") if latin else rng.choice("가나다라마바")
    n = rng.randint(1, 40)
    zone = f"{z}구역" if latin else f"{z} 구역"
    p = rng.random()
    if p < 0.25:
        return f"{f} {zone}"
    if p < 0.35:
        return f"{f} {z} 구역"
    if p < 0.6:
        return f"{f} {zone} {n}번"
    if p < 0.75 and latin:
        return f"{f} {z}{n} 기둥"
    if p < 0.88:
        return f"{f} {n}번 기둥"
    return f"{f} {z}{n}" if latin else f"{f} {zone} {n}번"


class Renderer:
    def __init__(self, split, rng):
        self.split = split
        self.rng = rng
        self.pool = PlacePool(split)

    # ── 틀 → 문장 조각 ─────────────────────────────────────────────────────────
    def expand(self, text, held):
        rng = self.rng
        flags = {"habit": "[HAB]" in text, "nearby": "[NEAR]" in text}

        def macro(m):
            pool = MACROS[m.group(1)]["train"] + (MACROS[m.group(1)]["test"] if held else [])
            return rng.choice(pool)

        text = MACRO.sub(macro, text)
        while True:
            m = ALT.search(text)
            if not m:
                break
            text = text[: m.start()] + rng.choice(m.group(1).split("|")) + text[m.end():]
        return text, flags

    def render(self, intent, frame, held, want_place_noise=False, want_light_noise=False):
        """→ (발화, 정답 dict, meta dict) 또는 None (다시 뽑기)"""
        rng = self.rng
        if intent == "NAV_PLACE":
            fid, text, stop, kinds = frame
            extra = {}
        else:
            fid, text = frame[0], frame[1]
            extra = frame[2] if len(frame) > 2 else {}
            stop, kinds = None, None
        text, flags = self.expand(text, held)
        label = {"intent": intent}
        meta = {"intent": intent, "tpl": fid, "held_tpl": held, "noise": "none"}

        place = None
        if P_SLOT.search(text):
            if want_place_noise:
                kinds = tuple(k for k in (kinds or ("chain", "landmark", "alias", "synth", "rest")) if k != "cat")
                if not kinds:
                    return None
            span, src, cat = self.pool.sample(rng, kinds)
            place = {"clean": span, "src": src, "cat": cat, "value": span}
            if want_place_noise and src != "cat":
                noisy = noisy_place(span, rng)
                if noisy is None:
                    return None
                place["value"] = noisy
                meta["noise"] = "place"
                meta["clean"] = span
            meta["src"] = src

        used_city = []
        segs = []  # (텍스트, 슬롯이면 True)
        pos = 0
        for m in SLOT.finditer(text):
            if m.start() > pos:
                segs.append([text[pos:m.start()], False])
            name, j = m.group(1), m.group(2)
            if name == "P":
                v = place["value"]
                label["place"] = v
            elif name == "T":
                v, val = render_temp(rng)
                if intent == "HVAC_SET_TEMP":
                    label["temp"] = val
            elif name == "N":
                v, val = render_percent(rng)
                if intent == "CHARGE_LIMIT":
                    label["percent"] = val
            elif name == "M":
                v = render_memo(rng)
                if intent == "PARKING_MEMO":
                    label["note"] = v
            elif name == "PURPOSE":
                v = rng.choice(PURPOSE[place["cat"]])
            elif name == "C":
                v = rng.choice(COMPANIONS)
            elif name == "X":
                v = rng.choice(ON_THE_WAY)
            elif name == "Q":
                v = self.pool.mention(rng)
            elif name == "CITY":
                v = rng.choice([c for c in CITIES if c not in used_city])
                used_city.append(v)
            elif name == "PET":
                v = rng.choice(PETS)
            elif name == "KID":
                v = rng.choice(KID_CALL)
            elif name == "KDEST":
                v = rng.choice(KID_DEST)
            else:
                raise ValueError(f"unknown slot {name} in {fid}")
            segs.append([v, True])
            if j:
                segs.append([josa(v, j), False])
            pos = m.end()
        if pos < len(text):
            segs.append([text[pos:], False])

        if intent == "NAV_PLACE":
            label["stop"] = "destination" if stop == "D" else "waypoint"
            if place["cat"]:
                label["category"] = place["cat"]
            if flags["habit"]:
                label["habit"] = True
            if flags["nearby"]:
                label["nearby"] = True
        label["route"] = extra.get("route", INTENTS[intent][1])

        # 가벼운 자모 오류 — 슬롯이 아닌 조각에만
        if want_light_noise:
            idx = [i for i, (t, is_slot) in enumerate(segs) if not is_slot and re.search("[가-힣]", t)]
            rng.shuffle(idx)
            for i in idx:
                r = light_noise(segs[i][0], rng)
                if r is not None:
                    segs[i][0] = r
                    meta["noise"] = "light"
                    break
            if meta["noise"] != "light":
                return None  # 오류를 넣을 자리가 없던 틀 → 다른 틀로 다시
        # 띄어쓰기 변형 (STT 출력마다 다르다) — 슬롯 내부는 건드리지 않는다
        if rng.random() < 0.12:
            idx = [(i, k) for i, (t, is_slot) in enumerate(segs) if not is_slot for k, ch in enumerate(t) if ch == " "]
            if idx:
                i, k = rng.choice(idx)
                segs[i][0] = segs[i][0][:k] + segs[i][0][k + 1:]

        utt = "".join(t for t, _ in segs)
        if rng.random() < P_FILLER:
            utt = rng.choice(FILLERS) + " " + utt
        utt = " ".join(utt.split())
        # 문장부호 변형: 물음표를 떼거나 마침표·느낌표를 붙인다
        if utt.endswith("?") and rng.random() < 0.25:
            utt = utt[:-1]
        elif not utt.endswith(("?", "!", ".", "…")):
            r = rng.random()
            if r < 0.12:
                utt += "."
            elif r < 0.15:
                utt += "!"
        return utt, label, meta


def quotas(n_total):
    w = sum(INTENT_WEIGHTS.values())
    raw = {k: v * n_total / w for k, v in INTENT_WEIGHTS.items()}
    q = {k: int(v) for k, v in raw.items()}
    rest = n_total - sum(q.values())
    for k in sorted(raw, key=lambda k: raw[k] - q[k], reverse=True)[:rest]:
        q[k] += 1
    return q


def frames_for(intent, held):
    if intent == "NAV_PLACE":
        return NAV_FRAMES["test" if held else "train"]
    return FRAMES[intent]["test" if held else "train"]


def build_split(split, n_total, seed, forbid=frozenset()):
    rng = random.Random(f"{seed}:{split}")
    R = Renderer(split, rng)
    seen = set()
    rows = []
    shortfall = Counter()
    def exact_flags(n, p):
        """정확히 round(n·p)개만 True — 오류 비율·평가 전용 틀 비율을 의도마다 정확히 맞춘다."""
        k = round(n * p)
        f = [True] * k + [False] * (n - k)
        rng.shuffle(f)
        return f

    for intent, n in quotas(n_total).items():
        noise_flags = exact_flags(n, P_PLACE_NOISE if intent == "NAV_PLACE" else P_LIGHT_NOISE)
        held_flags = exact_flags(n, P_HELD_TEMPLATE if split == "test" else 0.0)
        for i in range(n):
            ok = False
            # 오류·평가 전용 틀 여부는 예제마다 한 번만 정한다 (중복으로 다시 뽑을 때 바꾸면 비율이 한쪽으로 쏠린다)
            held = held_flags[i]
            want_pn = intent == "NAV_PLACE" and noise_flags[i]
            want_ln = intent != "NAV_PLACE" and noise_flags[i]
            frames = frames_for(intent, held)
            for _attempt in range(300):
                frame = rng.choice(frames)
                if want_pn and intent == "NAV_PLACE" and frame[3] == CAT:
                    continue
                out = R.render(intent, frame, held, want_pn, want_ln)
                if out is None:
                    continue
                utt, label, meta = out
                if utt in seen or utt in forbid:
                    continue
                seen.add(utt)
                rows.append(make_row(utt, label, meta))
                ok = True
                break
            if not ok:
                shortfall[intent] += 1
    if split == "train":
        for utt, label in DEMO_EXAMPLES:
            if utt not in seen:
                seen.add(utt)
                rows.append(make_row(utt, label, {"intent": label["intent"], "tpl": "demo", "held_tpl": False,
                                                  "noise": "place" if utt.find("서심다응") >= 0 else "none"}))
    rng.shuffle(rows)
    return rows, shortfall


def make_row(utt, label, meta):
    return {"messages": [{"role": "system", "content": SYSTEM_PROMPT},
                         {"role": "user", "content": utt},
                         {"role": "assistant", "content": to_json_line(label)}],
            "meta": meta}


def write_jsonl(path, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n")


def print_stats(splits, shortfalls, out_dir):
    names = list(splits)
    print("=" * 72)
    print("split sizes: " + " | ".join(f"{s} {len(splits[s])}" for s in names))
    print("\nper-intent counts")
    print(f"{'intent':<16}" + "".join(f"{s:>7}" for s in names) + "  route")
    counts = {s: Counter(r["meta"]["intent"] for r in splits[s]) for s in names}
    for it in INTENTS:
        print(f"{it:<16}" + "".join(f"{counts[s][it]:>7}" for s in names) + f"  {INTENTS[it][1]}")
    cloud_complex = {s: sum(1 for r in splits[s] if r["meta"]["intent"] in ("ADD_REST_STOP", "FIND_CHARGER")
                            and '"route":"cloud"' in r["messages"][2]["content"]) for s in names}
    print("multi-constraint edge intents routed to cloud: " + ", ".join(f"{s} {cloud_complex[s]}" for s in names))

    print("\nASR noise")
    for s in names:
        rows = splits[s]
        nav = [r for r in rows if r["meta"]["intent"] == "NAV_PLACE"]
        other = [r for r in rows if r["meta"]["intent"] != "NAV_PLACE"]
        pn = sum(1 for r in nav if r["meta"]["noise"] == "place")
        ln = sum(1 for r in other if r["meta"]["noise"] == "light")
        tot = sum(1 for r in rows if r["meta"]["noise"] != "none")
        sims = [similarity(json.loads(r["messages"][2]["content"])["place"], r["meta"]["clean"])
                for r in nav if r["meta"]["noise"] == "place" and "clean" in r["meta"]]
        ms = sum(sims) / len(sims) if sims else 0.0
        print(f"  {s:<5}: NAV_PLACE place-noise {pn}/{len(nav)} = {pn / max(1, len(nav)):.1%} | "
              f"other light-noise {ln}/{len(other)} = {ln / max(1, len(other)):.1%} | "
              f"all noisy {tot / max(1, len(rows)):.1%} | phonetic sim(noisy, clean) mean {ms:.3f}"
              + (f" min {min(sims):.3f}" if sims else ""))

    # 누출 검사: test 전용 장소 이름이 학습 문장에 나오면 안 된다
    test_names = PlacePool("test").names()
    train_names = set(PlacePool("train").names())
    overlap = [n for n in test_names if n in train_names]
    train_text = "\n".join(r["messages"][1]["content"] for r in splits["train"] + splits["valid"])
    leaks = [n for n in test_names if n in train_text]
    tr_utts = set(r["messages"][1]["content"] for r in splits["train"])
    dup = sum(1 for r in splits["test"] if r["messages"][1]["content"] in tr_utts)
    held = sum(1 for r in splits["test"] if r["meta"]["held_tpl"])
    held_ids = sorted({r["meta"]["tpl"] for r in splits["test"] if r["meta"]["held_tpl"]})
    train_ids = {r["meta"]["tpl"] for r in splits["train"]}
    print("\nheld-out check (test)")
    print(f"  test-only place names: {len(test_names)} | shared with train lexicon: {len(overlap)} | "
          f"found inside train/valid utterances: {len(leaks)} {leaks[:5] if leaks else ''}")
    print(f"  test examples from test-only templates: {held}/{len(splits['test'])} = {held / max(1, len(splits['test'])):.1%} "
          f"({len(held_ids)} templates; used in train: {len([i for i in held_ids if i in train_ids])})")
    print(f"  test utterances identical to a train utterance: {dup}")
    for s in names:
        if shortfalls[s]:
            print(f"  shortfall {s}: {dict(shortfalls[s])}")

    print("\nlengths (chars)")
    for s in names:
        u = [len(r["messages"][1]["content"]) for r in splits[s]]
        a = [len(r["messages"][2]["content"]) for r in splits[s]]
        print(f"  {s:<5}: utterance avg {sum(u) / len(u):.1f} max {max(u)} | answer JSON avg {sum(a) / len(a):.1f} max {max(a)}")
    total = 0
    for s in names:
        p = out_dir / f"{s}.jsonl"
        total += p.stat().st_size
        print(f"  {p.as_posix()}  {p.stat().st_size / 1024:.0f} KB")
    print(f"  total {total / 1024 / 1024:.2f} MB")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "data"))
    ap.add_argument("--train", type=int, default=6000)
    ap.add_argument("--valid", type=int, default=600)
    ap.add_argument("--test", type=int, default=600)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--samples", type=int, default=8)
    args = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    splits, shortfalls = {}, {}
    splits["train"], shortfalls["train"] = build_split("train", args.train, args.seed)
    forbid = frozenset(r["messages"][1]["content"] for r in splits["train"])
    splits["valid"], shortfalls["valid"] = build_split("valid", args.valid, args.seed, forbid)
    forbid = forbid | frozenset(r["messages"][1]["content"] for r in splits["valid"])
    splits["test"], shortfalls["test"] = build_split("test", args.test, args.seed, forbid)
    for s, rows in splits.items():
        write_jsonl(out / f"{s}.jsonl", rows)
    print_stats(splits, shortfalls, out)

    print(f"\n{args.samples} random samples (train, seed {args.seed})")
    rng = random.Random(args.seed)
    for r in rng.sample(splits["train"], args.samples):
        m = r["meta"]
        print(f"  [{m['tpl']}{'/' + m['noise'] if m['noise'] != 'none' else ''}] {r['messages'][1]['content']}"
              f"\n      → {r['messages'][2]['content']}")


if __name__ == "__main__":
    main()
