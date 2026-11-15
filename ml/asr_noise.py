# -*- coding: utf-8 -*-
"""
자모 단위 음성인식(ASR) 오류 주입 + 앱의 발음 유사도(Hangul.kt) 파이썬 이식.

차량 음성인식은 글자가 아니라 '소리'로 틀린다 (성심당 → 서심다응).
여기서 만드는 오류는 앱의 Hangul.kt 가 '싸게' 매기는 혼동과 같은 묶음을 쓴다:
  - 모음: ㅐ/ㅔ, ㅒ/ㅖ, ㅙ/ㅞ/ㅚ, ㅢ/ㅣ, ㅢ/ㅡ, ㅓ/ㅗ, ㅡ/ㅜ, ㅕ/ㅛ, ㅝ/ㅓ, ㅘ/ㅏ, ㅟ/ㅣ, ㅏ/ㅑ, ㅜ/ㅠ, ㅐ/ㅏ, ㅔ/ㅓ
  - 자음: ㄱ/ㄲ/ㅋ, ㄷ/ㄸ/ㅌ, ㅂ/ㅃ/ㅍ, ㅈ/ㅉ/ㅊ, ㅅ/ㅆ, ㄷ/ㅅ, ㄴ/ㄹ, ㄴ/ㅁ, ㅁ/ㅂ, ㅇ/ㄴ, ㅈ/ㅅ
  - 받침 탈락(성→서), 받침 분리(당→다응), 받침 교체(ㅇ/ㄴ/ㅁ, ㄱ/ㄷ/ㅂ)
  - 발음대로 적기: 연음(음악→으막), 비음화(국물→궁물), 유음화(신라→실라), 경음화(식당→식땅), 격음화(입학→이팍)
  - 띄어쓰기 오류, 긴 이름의 음절 탈락
주입 결과는 similarity(오류, 원래 이름) >= 0.6 일 때만 쓴다 — 앱의 개인화 순위 모델(PersonalBrain,
MIN_PHON=0.55)이 되돌릴 수 있는 범위의 오류만 학습시킨다는 뜻이다.
"""
import random

BASE, LAST = 0xAC00, 0xD7A3
CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
JONG = ["", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ",
        "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"]
# 받침 대표음 — Hangul.kt 의 JONG 표와 같다
JONG_REP = ["", "ㄱ", "ㄱ", "ㄱ", "ㄴ", "ㄴ", "ㄴ", "ㄷ", "ㄹ", "ㄱ", "ㅁ", "ㄹ", "ㄹ", "ㄹ",
            "ㅂ", "ㄹ", "ㅁ", "ㅂ", "ㅂ", "ㄷ", "ㄷ", "ㅇ", "ㄷ", "ㄷ", "ㄱ", "ㄷ", "ㅂ", "ㄷ"]

# Hangul.kt SIMILAR 와 같은 묶음·비용
SIMILAR = [
    ("ㅐㅔ", 0.2), ("ㅒㅖ", 0.2), ("ㅙㅞㅚ", 0.2), ("ㅢㅣ", 0.3), ("ㅢㅡ", 0.4),
    ("ㅓㅗ", 0.5), ("ㅡㅜ", 0.5), ("ㅕㅛ", 0.5), ("ㅝㅓ", 0.5), ("ㅘㅏ", 0.5), ("ㅟㅣ", 0.5),
    ("ㅏㅑ", 0.6), ("ㅜㅠ", 0.6), ("ㅐㅏ", 0.6), ("ㅔㅓ", 0.6),
    ("ㄱㄲㅋ", 0.4), ("ㄷㄸㅌ", 0.4), ("ㅂㅃㅍ", 0.4), ("ㅈㅉㅊ", 0.4), ("ㅅㅆ", 0.3),
    ("ㄷㅅ", 0.5), ("ㄴㄹ", 0.5), ("ㄴㅁ", 0.6), ("ㅁㅂ", 0.6), ("ㅇㄴ", 0.6), ("ㅈㅅ", 0.6),
]
SUB_COST = {}
for _group, _cost in SIMILAR:
    for _a in _group:
        for _b in _group:
            if _a != _b:
                SUB_COST[(_a, _b)] = min(SUB_COST.get((_a, _b), 1.0), _cost)


def _sub(a, b):
    return 0.0 if a == b else SUB_COST.get((a, b), 1.0)


def _indel(c):
    return 0.5 if c in "ㅡㅇㅎ" else 1.0


def phonemes(s):
    """문자열 → 발음 자모열 (Hangul.kt phonemes 와 동일: 초성 ㅇ 생략, 받침은 대표음, 공백·부호 무시)."""
    out = []
    for ch in s:
        c = ord(ch)
        if BASE <= c <= LAST:
            i = c - BASE
            cho = CHO[i // 588]
            if cho != "ㅇ":
                out.append(cho)
            out.append(JUNG[(i % 588) // 28])
            out.append(JONG_REP[i % 28])
        elif ch.isalnum():
            out.append(ch.lower())
    return "".join(out)


def _infix_distance(q, t):
    m, n = len(q), len(t)
    if m == 0:
        return 0.0
    prev = [0.0] * (n + 1)  # 이름 어디서 시작해도 무료
    for i in range(1, m + 1):
        qi = q[i - 1]
        cur = [0.0] * (n + 1)
        cur[0] = prev[0] + _indel(qi)
        for j in range(1, n + 1):
            cur[j] = min(prev[j - 1] + _sub(qi, t[j - 1]), prev[j] + _indel(qi), cur[j - 1] + _indel(t[j - 1]))
        prev = cur
    return min(prev)  # 이름 어디서 끝나도 무료


def similarity(spoken, name):
    """0~1 발음 유사도 (Hangul.kt similarity 이식)."""
    q, t = phonemes(spoken), phonemes(name)
    if not q or not t:
        return 0.0
    return max(0.0, min(1.0, 1.0 - _infix_distance(q, t) / len(q)))


# ── 자모 분해/조합 ─────────────────────────────────────────────────────────────
def dec(ch):
    c = ord(ch)
    if BASE <= c <= LAST:
        i = c - BASE
        return [i // 588, (i % 588) // 28, i % 28]
    return None


def com(cho, jung, jong):
    return chr(BASE + cho * 588 + jung * 28 + jong)


def _confusions(jamo, pool):
    """jamo 와 혼동되는 (다른 자모, 가중치) 목록. 비용이 낮을수록 자주 일어난다."""
    out = {}
    for group, cost in SIMILAR:
        if jamo in group and all(c in pool for c in group):
            for other in group:
                if other != jamo:
                    out[other] = max(out.get(other, 0.0), 1.1 - cost)
    return list(out.items())


def _wchoice(rng, items):
    total = sum(w for _, w in items)
    x = rng.random() * total
    for v, w in items:
        x -= w
        if x <= 0:
            return v
    return items[-1][0]


_SINGLE_CODA_TO_CHO = {1: 0, 2: 1, 4: 2, 7: 3, 8: 5, 16: 6, 17: 7, 19: 9, 20: 10, 22: 12, 23: 14, 24: 15, 25: 16, 26: 17}
_SPLIT_CODA = {21: "응", 4: "은", 16: "음", 8: "을"}  # ㅇ ㄴ ㅁ ㄹ
_NASAL_CODAS = [21, 4, 16]          # ㅇ ㄴ ㅁ
_STOP_CODAS = [1, 7, 17]            # ㄱ ㄷ ㅂ
_TENSE = {0: 1, 3: 4, 7: 8, 9: 10, 12: 13}  # ㄱ→ㄲ ㄷ→ㄸ ㅂ→ㅃ ㅅ→ㅆ ㅈ→ㅉ
_ASP = {0: 15, 3: 16, 12: 14, 7: 17}       # ㄱ→ㅋ ㄷ→ㅌ ㅈ→ㅊ ㅂ→ㅍ


def _hangul_positions(s, protect=()):
    return [i for i, ch in enumerate(s) if dec(ch) is not None and ch not in protect]


def op_vowel(s, rng, protect=()):
    pos = [i for i in _hangul_positions(s, protect) if _confusions(JUNG[dec(s[i])[1]], JUNG)]
    if not pos:
        return None
    i = rng.choice(pos)
    cho, jung, jong = dec(s[i])
    new = _wchoice(rng, _confusions(JUNG[jung], JUNG))
    return s[:i] + com(cho, JUNG.index(new), jong) + s[i + 1:]


def op_onset(s, rng, protect=()):
    pos = []
    for i in _hangul_positions(s, protect):
        c = CHO[dec(s[i])[0]]
        if c == "ㅎ" or (c != "ㅇ" and _confusions(c, CHO.replace("ㅇ", ""))):
            pos.append(i)
    if not pos:
        return None
    i = rng.choice(pos)
    cho, jung, jong = dec(s[i])
    c = CHO[cho]
    if c == "ㅎ":
        new = "ㅇ"  # ㅎ 약화 (전화 → 저놔)
    else:
        new = _wchoice(rng, _confusions(c, CHO.replace("ㅇ", "")))
    return s[:i] + com(CHO.index(new), jung, jong) + s[i + 1:]


def op_coda_drop(s, rng, protect=()):
    pos = [i for i in _hangul_positions(s, protect) if dec(s[i])[2] != 0]
    if not pos:
        return None
    # 울림소리 받침(ㅇ ㄴ ㄹ ㅁ)이 더 잘 빠진다
    weighted = [(i, 3.0 if dec(s[i])[2] in (21, 4, 8, 16) else 1.0) for i in pos]
    i = _wchoice(rng, weighted)
    cho, jung, _ = dec(s[i])
    return s[:i] + com(cho, jung, 0) + s[i + 1:]


def op_coda_split(s, rng, protect=()):
    """받침을 따로 떼어 '으' 음절로 (당 → 다응). 단어 끝 음절에서 더 잘 일어난다."""
    pos = [i for i in _hangul_positions(s, protect) if dec(s[i])[2] in _SPLIT_CODA]
    if not pos:
        return None
    weighted = [(i, 3.0 if (i + 1 >= len(s) or dec(s[i + 1]) is None) else 1.0) for i in pos]
    i = _wchoice(rng, weighted)
    cho, jung, jong = dec(s[i])
    return s[:i] + com(cho, jung, 0) + _SPLIT_CODA[jong] + s[i + 1:]


def op_coda_swap(s, rng, protect=()):
    pos = [i for i in _hangul_positions(s, protect) if dec(s[i])[2] in _NASAL_CODAS + _STOP_CODAS + [8]]
    if not pos:
        return None
    i = rng.choice(pos)
    cho, jung, jong = dec(s[i])
    if jong in _NASAL_CODAS:
        new = rng.choice([c for c in _NASAL_CODAS if c != jong])
    elif jong in _STOP_CODAS:
        new = rng.choice([c for c in _STOP_CODAS if c != jong])
    else:  # ㄹ ↔ ㄴ
        new = 4
    return s[:i] + com(cho, jung, new) + s[i + 1:]


def op_phonological(s, rng, protect=()):
    """음절 경계의 발음 규칙대로 적기 (연음·비음화·유음화·경음화·격음화)."""
    cands = []
    for i in range(len(s) - 1):
        a, b = dec(s[i]), dec(s[i + 1])
        if a is None or b is None or s[i] in protect or s[i + 1] in protect:
            continue
        ca, va, ja = a
        cb, vb, jb = b
        rep = JONG_REP[ja]
        here = []
        if ja in _SINGLE_CODA_TO_CHO and cb == 11:  # 연음: 받침 + 초성 ㅇ (음악 → 으막)
            here.append(com(ca, va, 0) + com(_SINGLE_CODA_TO_CHO[ja], vb, jb))
        elif ja == 27 and cb == 11:  # ㅎ 받침 + 모음 → ㅎ 탈락 (좋아 → 조아)
            here.append(com(ca, va, 0) + s[i + 1])
        if rep in ("ㄱ", "ㄷ", "ㅂ") and CHO[cb] in ("ㄴ", "ㅁ"):  # 비음화 (국물 → 궁물)
            here.append(com(ca, va, {"ㄱ": 21, "ㄷ": 4, "ㅂ": 16}[rep]) + s[i + 1])
        if ja == 4 and cb == 5:  # 유음화 ㄴ+ㄹ (신라 → 실라)
            here.append(com(ca, va, 8) + s[i + 1])
        if ja == 8 and cb == 2:  # 유음화 ㄹ+ㄴ (설날 → 설랄)
            here.append(s[i] + com(5, vb, jb))
        if rep in ("ㄱ", "ㄷ", "ㅂ") and cb in _TENSE and ja != 27:  # 경음화 (식당 → 식땅)
            here.append(s[i] + com(_TENSE[cb], vb, jb))
        if ja == 27 and cb in (0, 3, 12):  # 격음화 ㅎ+ㄱㄷㅈ (좋다 → 조타)
            here.append(com(ca, va, 0) + com(_ASP[cb], vb, jb))
        if cb == 18 and ja in (1, 7, 17, 22):  # 격음화 ㄱㄷㅂㅈ+ㅎ (입학 → 이팍)
            asp = {1: 15, 7: 16, 17: 17, 22: 14}[ja]
            here.append(com(ca, va, 0) + com(asp, vb, jb))
        cands.extend((i, c) for c in here)
    if not cands:
        return None
    i, rep2 = rng.choice(cands)
    return s[:i] + rep2 + s[i + 2:]


def op_space(s, rng, protect=()):
    if " " in s and rng.random() < 0.7:
        idx = [i for i, ch in enumerate(s) if ch == " "]
        i = rng.choice(idx)
        return s[:i] + s[i + 1:]
    pos = [i for i in range(1, len(s)) if dec(s[i - 1]) is not None and dec(s[i]) is not None]
    if len(pos) < 2:
        return None
    i = rng.choice(pos)
    return s[:i] + " " + s[i:]


def op_syll_drop(s, rng, protect=()):
    pos = _hangul_positions(s, protect)
    if len(pos) < 4:
        return None
    i = rng.choice(pos[1:])
    return s[:i] + s[i + 1:]


PLACE_OPS = [
    (op_vowel, 0.22), (op_onset, 0.20), (op_coda_drop, 0.14), (op_coda_split, 0.10),
    (op_coda_swap, 0.08), (op_phonological, 0.14), (op_space, 0.08), (op_syll_drop, 0.04),
]
LIGHT_OPS = [(op_vowel, 0.35), (op_onset, 0.30), (op_coda_drop, 0.20), (op_phonological, 0.15)]


def is_common(s):
    """모든 한글 음절이 KS X 1001 상용 2,350자인가 — 음성인식기는 언어모델 때문에 '퍈·싀' 같은 희귀 음절을 거의 내지 않는다."""
    for ch in s:
        if dec(ch) is not None:
            try:
                # 상용 음절은 2바이트, 나머지는 KS X 1001 부속서 3의 8바이트 조합 시퀀스로 인코딩된다
                if len(ch.encode("euc-kr")) != 2:
                    return False
            except UnicodeEncodeError:
                return False
    return True


def noisy_place(name, rng, min_sim=0.6, tries=16):
    """장소 이름에 1~2개의 소리 오류를 넣는다. 되돌릴 수 없을 만큼 망가지면 다시 뽑고, 끝내 못 만들면 None."""
    for _ in range(tries):
        s = name
        n_ops = 1 if rng.random() < 0.6 else 2
        for _ in range(n_ops):
            op = _wchoice(rng, PLACE_OPS)
            r = op(s, rng)
            if r is not None:
                s = r
        s = " ".join(s.split())
        if s and s != name and is_common(s) and similarity(s, name) >= min_sim:
            return s
    return None


# 뜻(켜기/끄기, 올림/내림, 부정)을 바꿀 수 있는 음절은 가벼운 오류에서 건드리지 않는다
PROTECT = set("켜꺼끄켠끈안못더덜춥추덥올내려말끝멈")


def light_noise(text, rng):
    """명령 문장(슬롯 제외 부분)에 자모 오류 1개."""
    for _ in range(8):
        op = _wchoice(rng, LIGHT_OPS)
        r = op(text, rng, PROTECT)
        if r is not None and r != text and is_common(r):
            return r
    return None


# ── 조사 (받침 유무) ───────────────────────────────────────────────────────────
_DIGIT_BATCHIM = {"0": (True, False), "1": (True, True), "2": (False, False), "3": (True, False), "4": (False, False),
                  "5": (False, False), "6": (True, False), "7": (True, True), "8": (True, True), "9": (False, False)}
_LATIN_BATCHIM = {"l": (True, True), "r": (True, True), "m": (True, False), "n": (True, False)}


def batchim(word):
    """(받침 있음, ㄹ 받침) — 숫자·영문은 읽는 소리 기준, %는 '퍼센트'."""
    w = word.strip()
    while w and not (w[-1].isalnum() or w[-1] == "%"):
        w = w[:-1]
    if not w:
        return (False, False)
    ch = w[-1]
    if ch == "%":
        return (False, False)
    d = dec(ch)
    if d is not None:
        return (d[2] != 0, d[2] == 8)
    if ch.isdigit():
        return _DIGIT_BATCHIM[ch]
    return _LATIN_BATCHIM.get(ch.lower(), (False, False))


JOSA = {  # (받침 있을 때, 없을 때)
    "로": ("으로", "로"), "을": ("을", "를"), "이": ("이", "가"), "은": ("은", "는"),
    "랑": ("이랑", "랑"), "라고": ("이라고", "라고"), "야": ("이야", "야"), "과": ("과", "와"),
}


def josa(word, kind):
    has, rieul = batchim(word)
    if kind == "로" and rieul:
        return "로"
    a, b = JOSA[kind]
    return a if has else b


if __name__ == "__main__":
    r = random.Random(0)
    print("similarity(서심다응, 성심당 대전역점) =", round(similarity("서심다응", "성심당 대전역점"), 3))
    for nm in ["성심당", "스타벅스 판교점", "파리바게뜨", "올림픽공원", "엄마 집", "덕평자연휴게소"]:
        print(nm, "→", [noisy_place(nm, r) for _ in range(4)])
