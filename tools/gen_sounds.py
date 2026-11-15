#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""옆자리 영어 효과음 생성기 — 직접 합성한 소리만 쓴다 (외부 음원·샘플 없음).

    python tools/gen_sounds.py            # app/src/main/res/raw/sfx_*.wav 를 다시 만든다
    python tools/gen_sounds.py --out DIR  # 다른 폴더에 만든다

소리 만드는 법: 마림바·실로폰 같은 '막대 타악기' 음색을 사인파 덧셈으로 흉내 낸다.
  기음 + 막대의 비정수배 배음(2.76배 · 3.98배 · 9.2배) · 빠른 어택 · 배음마다 다른 지수 감쇠.
  44.1 kHz · 모노 · 16 bit · 피크 정규화(-6 dBFS 안팎) · 앞뒤 10 ms 페이드 · 클리핑 없음.
  끝 꼬리(마지막 120 ms)는 한 번 더 부드럽게 줄여 뚝 끊기지 않게 한다.
표준 라이브러리만 쓴다 (numpy 불필요). 결과는 실행할 때마다 똑같다 (난수 없음).
"""
import argparse
import math
import os
import struct
import wave

SR = 44100
FADE_SEC = 0.010
TAIL_SEC = 0.120

# (주파수 배수, 상대 크기, 감쇠 시간 배수)
PARTIALS = (
    (1.00, 1.00, 1.00),  # 기음
    (2.76, 0.09, 0.42),  # 막대 2차 진동 — 맑은 종소리 느낌
    (3.98, 0.24, 0.30),  # 마림바의 조율된 4배음 (살짝 낮게)
    (9.20, 0.06, 0.09),  # 채가 닿는 순간의 '톡'
)

_SEMITONE = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}


def hz(name):
    """'E6' · 'C#7' → 주파수 (A4 = 440 Hz, 평균율)"""
    pitch, octave = name[:-1], int(name[-1])
    midi = 12 * (octave + 1) + _SEMITONE[pitch]
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


def strike(buf, at, note, amp=1.0, tau=0.22, bright=1.0, attack=0.004):
    """막대를 한 번 친 소리를 buf 의 at 초 위치에 더한다.

    tau = 기음이 1/e 로 줄어드는 시간(초), bright = 배음 크기(0 이면 순음에 가까움)
    """
    freq = hz(note) if isinstance(note, str) else float(note)
    start = int(at * SR)
    for mult, rel, decay in PARTIALS:
        f = freq * mult
        if f > 15000.0:
            continue
        a = amp * rel
        if mult > 1.0:
            # 높은 음일수록 배음을 줄여 귀를 찌르지 않게
            a *= bright * min(1.0, (900.0 / freq) ** 0.6)
        if a <= 0.0:
            continue
        td = tau * decay
        length = min(len(buf) - start, int(td * 8.0 * SR))
        w = 2.0 * math.pi * f / SR
        k = math.exp(-1.0 / (td * SR))
        n_att = max(1, int(attack * SR))
        env = 1.0
        for i in range(length):
            e = env
            if i < n_att:
                e *= 0.5 - 0.5 * math.cos(math.pi * i / n_att)
            buf[start + i] += a * e * math.sin(w * i)
            env *= k


def room(buf, taps=((0.029, 0.15), (0.043, 0.10), (0.067, 0.065), (0.097, 0.04))):
    """짧은 초기 반사 몇 개 — 소리가 마른 느낌이 나지 않게 (잔향기 아님)"""
    out = list(buf)
    n = len(buf)
    for delay, gain in taps:
        d = int(delay * SR)
        for i in range(n - d):
            out[i + d] += buf[i] * gain
    return out


def finish(buf, peak_db):
    """DC 제거 → 꼬리 줄이기 → 피크 정규화 → 앞뒤 10 ms 페이드 → 16 bit 정수"""
    n = len(buf)
    mean = sum(buf) / n
    buf = [v - mean for v in buf]
    tail = min(int(TAIL_SEC * SR), n // 3)
    for i in range(n - tail, n):
        buf[i] *= 0.5 + 0.5 * math.cos(math.pi * (i - (n - tail)) / tail)
    peak = max(abs(v) for v in buf) or 1.0
    gain = (10.0 ** (peak_db / 20.0)) / peak
    fade = int(FADE_SEC * SR)
    out = []
    for i, v in enumerate(buf):
        g = gain
        if i < fade:
            g *= 0.5 - 0.5 * math.cos(math.pi * i / fade)
        if i >= n - fade:
            g *= 0.5 - 0.5 * math.cos(math.pi * (n - 1 - i) / fade)
        out.append(max(-32767, min(32767, int(round(v * g * 32767.0)))))
    return out


def blank(seconds):
    return [0.0] * int(seconds * SR)


# ───────────────────────── 효과음 ─────────────────────────

def sfx_correct():
    """정답 — 밝게 올라가는 두 음 (솔→도 느낌의 완전4도: E6 → A6). 0.55초"""
    b = blank(0.55)
    strike(b, 0.000, "E6", amp=0.80, tau=0.15)
    strike(b, 0.085, "A6", amp=1.00, tau=0.19)
    strike(b, 0.085, "A5", amp=0.22, tau=0.17, bright=0.4)  # 한 옥타브 아래로 살짝 받쳐 따뜻하게
    return room(b), -6.0


def sfx_partial():
    """거의 맞음 — 올라가지도 내려가지도 않는 같은 음 두 번 (B5). 부드럽고 작게. 0.42초"""
    b = blank(0.42)
    strike(b, 0.000, "B5", amp=1.00, tau=0.12, bright=0.7)
    strike(b, 0.105, "B5", amp=0.72, tau=0.15, bright=0.6)
    strike(b, 0.000, "B4", amp=0.25, tau=0.12, bright=0.3)
    return room(b), -9.0


def sfx_wrong():
    """다시 — 낮고 둥근 두 음이 살짝 내려간다 (G4 → E4). 거슬리지 않게 배음을 거의 뺀다. 0.38초"""
    b = blank(0.38)
    strike(b, 0.000, "G4", amp=1.00, tau=0.10, bright=0.22, attack=0.008)
    strike(b, 0.110, "E4", amp=0.92, tau=0.13, bright=0.18, attack=0.008)
    return room(b, taps=((0.031, 0.10), (0.053, 0.06))), -10.0


def sfx_streak():
    """연속 정답 — 라장조 아르페지오가 빠르게 올라간다 (A5 C#6 E6 A6 + 반짝). 0.85초"""
    b = blank(0.85)
    for i, (n, a) in enumerate((("A5", 0.70), ("C#6", 0.78), ("E6", 0.86), ("A6", 1.00))):
        strike(b, 0.065 * i, n, amp=a, tau=0.13 if i < 3 else 0.24)
    strike(b, 0.195, "A5", amp=0.25, tau=0.22, bright=0.4)
    strike(b, 0.300, "E7", amp=0.20, tau=0.16, bright=0.5)
    return room(b), -6.0


def sfx_levelup():
    """레벨 업 — 레장조로 뛰어오른 뒤 화음으로 울린다 (D5 A5 D6 F#6 A6 → D·F#·A). 1.5초"""
    b = blank(1.50)
    run = (("D5", 0.62), ("A5", 0.70), ("D6", 0.80), ("F#6", 0.88), ("A6", 0.96))
    for i, (n, a) in enumerate(run):
        strike(b, 0.085 * i, n, amp=a, tau=0.14)
    chord_at = 0.085 * len(run) + 0.045
    for j, (n, a) in enumerate((("D5", 0.42), ("A5", 0.46), ("D6", 0.70), ("F#6", 0.74), ("A6", 0.62), ("D7", 0.34))):
        strike(b, chord_at + 0.012 * j, n, amp=a, tau=0.40, bright=0.8)
    strike(b, chord_at + 0.26, "A7", amp=0.12, tau=0.14, bright=0.4)
    return room(b), -6.0


def sfx_session_complete():
    """학습 완료 — 도 미 솔 도 아르페지오 뒤에 부드러운 마지막 화음 (C5 E5 G5 C6 → C·G·C·E). 2.3초"""
    b = blank(2.30)
    for i, (n, a) in enumerate((("C5", 0.74), ("E5", 0.80), ("G5", 0.88), ("C6", 1.00))):
        strike(b, 0.150 * i, n, amp=a, tau=0.24)
    chord_at = 0.150 * 4 + 0.13
    for j, (n, a) in enumerate((("C5", 0.50), ("G5", 0.52), ("C6", 0.66), ("E6", 0.72))):
        strike(b, chord_at + 0.022 * j, n, amp=a, tau=0.52, bright=0.75)
    strike(b, chord_at + 0.30, "G6", amp=0.24, tau=0.30, bright=0.5)
    strike(b, chord_at + 0.42, "C7", amp=0.16, tau=0.26, bright=0.4)
    return room(b), -6.0


SOUNDS = (
    ("sfx_correct", sfx_correct),
    ("sfx_partial", sfx_partial),
    ("sfx_wrong", sfx_wrong),
    ("sfx_streak", sfx_streak),
    ("sfx_levelup", sfx_levelup),
    ("sfx_session_complete", sfx_session_complete),
)


def write_wav(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(struct.pack("<%dh" % len(samples), *samples))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.join(here, os.pardir, "app", "src", "main", "res", "raw")
    ap = argparse.ArgumentParser(description="옆자리 영어 효과음(WAV) 합성")
    ap.add_argument("--out", default=default_out, help="WAV 를 쓸 폴더 (기본: app/src/main/res/raw)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for name, make in SOUNDS:
        raw, peak_db = make()
        pcm = finish(raw, peak_db)
        write_wav(os.path.join(args.out, name + ".wav"), pcm)
        peak = max(abs(v) for v in pcm) / 32767.0
        rms = math.sqrt(sum(v * v for v in pcm) / len(pcm)) / 32767.0
        print("%-22s %.2fs  peak %6.2f dBFS  rms %6.2f dBFS" % (
            name + ".wav", len(pcm) / SR, 20 * math.log10(peak), 20 * math.log10(max(rms, 1e-9))))


if __name__ == "__main__":
    main()
