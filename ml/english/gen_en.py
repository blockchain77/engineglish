# -*- coding: utf-8 -*-
"""
옆자리 영어 — '학습자 응답 이해' 모델 학습 데이터(합성) 생성.

차 안 STT(영어)가 돌려준 학습자 발화 + 문항 → JSON 한 줄
  {"act": "answer|repeat|slower|skip|hint|meaning|pause|resume|stop|noise",
   "verdict": "correct|partial|wrong|none", "match": 정답번호(-1=없음),
   "errors": [{"t": 유형, "e": 기대 단어, "h": 들린 단어}]}

유형: pron_rl pron_fp pron_vb pron_th pron_shs final_drop article tense plural
      preposition word_order missing extra
  · 발음 오류는 '한국어 화자의 대표 혼동이 영어 음성인식에서 다른 실제 단어로 바뀐 경우'만 만든다
    (right→light, file→pile, very→berry, think→sink …). 음성인식은 실제 단어만 내기 때문이다.
  · 시험(test)은 학습에 없는 문항(unseen)과 학습 문항의 새 변형(seen)으로 나눈다.

    python gen_en.py --out data          # train/valid/test.jsonl
"""
import argparse
import json
import random
import re
from pathlib import Path

SYSTEM = "옆자리 영어 응답 판정기. 문항과 영어 음성인식 결과를 보고 JSON 한 줄만 출력한다."

# ── 문항 씨앗: (type, track, prompt, answers, keywords) ───────────────────────────
# type: build(한→영 문장) / repeat(따라 말하기) / vocab(뜻→단어) / qa(토익 스피킹형 답변)
B = "build"; R = "repeat"; V = "vocab"; Q = "qa"
SEED = [
    # 비즈니스
    (B, "BUSINESS", "'회의를 미루다'를 영어로", ["push back the meeting", "postpone the meeting"]),
    (B, "BUSINESS", "'제가 다시 연락드릴게요'를 영어로", ["I'll get back to you", "I will get back to you"]),
    (B, "BUSINESS", "'일정이 좀 늦어지고 있어요'를 영어로", ["we are running behind schedule", "we're running behind schedule"]),
    (B, "BUSINESS", "'이 보고서를 금요일까지 보내 주세요'를 영어로", ["please send this report by Friday", "please send the report by Friday"]),
    (B, "BUSINESS", "'회의록을 공유해 드릴게요'를 영어로", ["I'll share the meeting notes", "I will share the meeting minutes"]),
    (B, "BUSINESS", "'예산을 다시 검토해야 해요'를 영어로", ["we need to review the budget again", "we have to review the budget again"]),
    (B, "BUSINESS", "'고객이 마감일을 앞당기고 싶어 해요'를 영어로", ["the client wants to move up the deadline", "the client wants to bring forward the deadline"]),
    (B, "BUSINESS", "'제 화면이 보이시나요'를 영어로", ["can you see my screen", "are you able to see my screen"]),
    (B, "BUSINESS", "'잠깐 음소거해 주시겠어요'를 영어로", ["could you mute yourself for a moment", "could you please mute for a moment"]),
    (B, "BUSINESS", "'그 부분은 제가 확인해 볼게요'를 영어로", ["let me check on that", "I'll look into that"]),
    (B, "BUSINESS", "'다음 주 화요일은 어떠세요'를 영어로", ["how about next Tuesday", "does next Tuesday work for you"]),
    (B, "BUSINESS", "'계약서에 서명했어요'를 영어로", ["we signed the contract", "I signed the contract"]),
    (B, "BUSINESS", "'파일을 첨부했어요'를 영어로", ["I attached the file", "I've attached the file"]),
    (B, "BUSINESS", "'이 결정에 찬성해요'를 영어로", ["I agree with this decision", "I'm in favor of this decision"]),
    (B, "BUSINESS", "'우선순위를 정합시다'를 영어로", ["let's set our priorities", "let's prioritize"]),
    (B, "BUSINESS", "'가격을 조금 낮춰 주실 수 있나요'를 영어로", ["could you lower the price a little", "can you bring the price down a bit"]),
    (B, "BUSINESS", "'발표 자료를 준비하고 있어요'를 영어로", ["I'm preparing the presentation", "I am working on the presentation slides"]),
    (B, "BUSINESS", "'출장 일정이 확정됐어요'를 영어로", ["my business trip schedule is confirmed", "the business trip is confirmed"]),
    # 일상
    (B, "DAILY", "'차가 막혀서 늦을 것 같아'를 영어로", ["I'm stuck in traffic so I'll be late", "I'm stuck in traffic and I'll be late"]),
    (B, "DAILY", "'오늘 비가 엄청 와'를 영어로", ["it's pouring today", "it's raining really hard today"]),
    (B, "DAILY", "'저녁 같이 먹을래'를 영어로", ["do you want to have dinner together", "would you like to grab dinner"]),
    (B, "DAILY", "'주말에 뭐 했어'를 영어로", ["what did you do on the weekend", "what did you do over the weekend"]),
    (B, "DAILY", "'요즘 운동을 시작했어'를 영어로", ["I started working out these days", "I've started exercising lately"]),
    (B, "DAILY", "'아침을 거르면 안 돼'를 영어로", ["you shouldn't skip breakfast", "you should not skip breakfast"]),
    (B, "DAILY", "'커피 한 잔 할래'를 영어로", ["do you want a cup of coffee", "would you like some coffee"]),
    (B, "DAILY", "'오늘 정말 피곤해'를 영어로", ["I'm really tired today", "I am so tired today"]),
    (B, "DAILY", "'불 좀 꺼 줄래'를 영어로", ["can you turn off the light", "could you turn the light off"]),
    (B, "DAILY", "'창문 좀 열어도 될까'를 영어로", ["can I open the window", "do you mind if I open the window"]),
    (B, "DAILY", "'길이 너무 미끄러워'를 영어로", ["the road is really slippery", "the roads are very slippery"]),
    (B, "DAILY", "'주차할 자리가 없어'를 영어로", ["there's no place to park", "I can't find a parking spot"]),
    (B, "DAILY", "'오른쪽으로 꺾으면 돼'를 영어로", ["you can turn right", "just turn right"]),
    (B, "DAILY", "'생일 축하해'를 영어로", ["happy birthday", "happy birthday to you"]),
    (B, "DAILY", "'내일 일찍 일어나야 해'를 영어로", ["I have to get up early tomorrow", "I need to wake up early tomorrow"]),
    (B, "DAILY", "'이 노래 정말 좋다'를 영어로", ["I really love this song", "this song is really good"]),
    # 여행
    (B, "TRAVEL", "'창가 자리로 주세요'를 영어로", ["a window seat please", "can I have a window seat"]),
    (B, "TRAVEL", "'체크인은 몇 시인가요'를 영어로", ["what time is check-in", "when is check-in"]),
    (B, "TRAVEL", "'이 근처에 약국이 있나요'를 영어로", ["is there a pharmacy near here", "is there a pharmacy nearby"]),
    (B, "TRAVEL", "'짐을 맡길 수 있을까요'를 영어로", ["can I leave my luggage here", "could you keep my bags"]),
    (B, "TRAVEL", "'공항까지 얼마나 걸리나요'를 영어로", ["how long does it take to the airport", "how long does it take to get to the airport"]),
    (B, "TRAVEL", "'계산서 주세요'를 영어로", ["check please", "can I get the bill please"]),
    (B, "TRAVEL", "'방을 하나 예약했어요'를 영어로", ["I booked a room", "I have a reservation for a room"]),
    (B, "TRAVEL", "'렌터카를 반납하러 왔어요'를 영어로", ["I'm here to return the rental car", "I'd like to return my rental car"]),
    (B, "TRAVEL", "'기름을 가득 채워 주세요'를 영어로", ["fill it up please", "please fill up the tank"]),
    (B, "TRAVEL", "'길을 잃었어요'를 영어로", ["I'm lost", "I got lost"]),
    (B, "TRAVEL", "'이 버스가 시내로 가나요'를 영어로", ["does this bus go downtown", "does this bus go to the city center"]),
    (B, "TRAVEL", "'환불받을 수 있나요'를 영어로", ["can I get a refund", "is it possible to get a refund"]),
    (B, "TRAVEL", "'사진 좀 찍어 주시겠어요'를 영어로", ["could you take a picture of us", "can you take a photo for me"]),
    (B, "TRAVEL", "'비행기가 지연됐어요'를 영어로", ["my flight was delayed", "the flight has been delayed"]),
    (B, "TRAVEL", "'추천 메뉴가 뭔가요'를 영어로", ["what do you recommend", "what's your recommendation"]),
    (B, "TRAVEL", "'와이파이 비밀번호가 뭐예요'를 영어로", ["what's the wifi password", "what is the password for the wifi"]),
    # 따라 말하기 (발음 혼동 단어를 일부러 섞는다)
    (R, "DAILY", "Turn right at the next light.", ["turn right at the next light"]),
    (R, "DAILY", "I read the file on the road.", ["I read the file on the road"]),
    (R, "DAILY", "The coffee is very hot.", ["the coffee is very hot"]),
    (R, "DAILY", "I think it's three o'clock.", ["I think it's three o'clock"]),
    (R, "DAILY", "She bought a new sheet of glass.", ["she bought a new sheet of glass"]),
    (R, "TRAVEL", "The flight leaves in five minutes.", ["the flight leaves in five minutes"]),
    (R, "TRAVEL", "Please fill out this form.", ["please fill out this form"]),
    (R, "TRAVEL", "Is breakfast included in the price?", ["is breakfast included in the price"]),
    (R, "BUSINESS", "Let's collect the feedback first.", ["let's collect the feedback first"]),
    (R, "BUSINESS", "We need a faster process.", ["we need a faster process"]),
    (R, "BUSINESS", "Thank you for your valuable time.", ["thank you for your valuable time"]),
    (R, "BUSINESS", "The vote will be held on Thursday.", ["the vote will be held on Thursday"]),
    (R, "BUSINESS", "I'll send the full report later.", ["I'll send the full report later"]),
    (R, "DAILY", "Let's play a game after lunch.", ["let's play a game after lunch"]),
    (R, "DAILY", "I feel fine, thanks.", ["I feel fine thanks"]),
    (R, "TRAVEL", "Where is the bus stop?", ["where is the bus stop"]),
    (R, "BUSINESS", "Could you correct the price list?", ["could you correct the price list"]),
    (R, "DAILY", "The road was long and wrong.", ["the road was long and wrong"]),
    (R, "TRAVEL", "I'd like a room with a view.", ["I'd like a room with a view"]),
    (R, "DAILY", "We walked through the park.", ["we walked through the park"]),
    # 단어 (뜻 → 영어 단어)
    (V, "BUSINESS", "'마감일'", ["deadline"]), (V, "BUSINESS", "'예산'", ["budget"]),
    (V, "BUSINESS", "'계약서'", ["contract"]), (V, "BUSINESS", "'회의록'", ["minutes", "meeting minutes"]),
    (V, "BUSINESS", "'협상하다'", ["negotiate"]), (V, "BUSINESS", "'승인하다'", ["approve"]),
    (V, "BUSINESS", "'수익'", ["revenue", "profit"]), (V, "BUSINESS", "'고객'", ["client", "customer"]),
    (V, "DAILY", "'오른쪽'", ["right"]), (V, "DAILY", "'빛, 신호등'", ["light"]),
    (V, "DAILY", "'파일'", ["file"]), (V, "DAILY", "'아주, 매우'", ["very"]),
    (V, "DAILY", "'생각하다'", ["think"]), (V, "DAILY", "'잔디'", ["grass"]),
    (V, "DAILY", "'모으다'", ["collect", "gather"]), (V, "DAILY", "'맞는, 정확한'", ["correct", "right"]),
    (V, "DAILY", "'투표'", ["vote"]), (V, "DAILY", "'좋은, 괜찮은'", ["fine"]),
    (V, "TRAVEL", "'여권'", ["passport"]), (V, "TRAVEL", "'수하물'", ["baggage", "luggage"]),
    (V, "TRAVEL", "'환불'", ["refund"]), (V, "TRAVEL", "'왕복의'", ["round trip", "round-trip"]),
    (V, "TRAVEL", "'통로 쪽 자리'", ["aisle seat"]), (V, "TRAVEL", "'환전'", ["currency exchange", "exchange"]),
    (V, "TRAVEL", "'비행'", ["flight"]), (V, "TRAVEL", "'요금'", ["fare", "fee"]),
    (V, "DAILY", "'세 개, 셋'", ["three"]), (V, "DAILY", "'두꺼운'", ["thick"]),
    (V, "DAILY", "'바닷가, 해변'", ["beach"]), (V, "DAILY", "'배, 선박'", ["ship"]),
    # 토익 스피킹형 (질문 + 핵심어)
    (Q, "TOEIC_SPEAKING", "Describe a typical day at your work.", ["meeting|email|office|work|project|lunch|team"]),
    (Q, "TOEIC_SPEAKING", "What do you usually do on weekends?", ["weekend|relax|family|friends|movie|exercise|sleep|hiking"]),
    (Q, "TOEIC_SPEAKING", "How do you usually get to work?", ["drive|car|bus|subway|commute|walk|train"]),
    (Q, "TOEIC_SPEAKING", "Do you prefer working at home or at the office? Why?", ["home|office|prefer|because|commute|focus|colleagues"]),
    (Q, "TOEIC_SPEAKING", "What is your favorite season and why?", ["spring|summer|fall|autumn|winter|because|weather"]),
    (Q, "TOEIC_SPEAKING", "Tell me about a restaurant you like.", ["restaurant|food|delicious|menu|price|service|because"]),
    (Q, "TOEIC_SPEAKING", "What would you do if your flight was canceled?", ["airline|rebook|hotel|refund|call|next flight|counter"]),
    (Q, "TOEIC_SPEAKING", "Why is it important to learn English?", ["important|job|travel|communicate|business|because|people"]),
    (Q, "BUSINESS", "Introduce your company briefly.", ["company|product|service|customers|employees|we"]),
    (Q, "BUSINESS", "How do you handle a tight deadline?", ["deadline|priority|plan|team|overtime|schedule|focus"]),
    (Q, "TRAVEL", "Describe the best trip you have taken.", ["trip|traveled|visited|beautiful|food|with|because"]),
    (Q, "DAILY", "What do you do to stay healthy?", ["exercise|eat|sleep|walk|gym|healthy|vegetables"]),
]

# ── 한국어 화자 발음 혼동 → 영어 STT 가 내는 실제 단어 ─────────────────────────────
CONF = {}
def _pairs(t, pairs, both=True):
    for a, b in pairs:
        CONF.setdefault(a, []).append((b, t))
        if both:
            CONF.setdefault(b, []).append((a, t))
_pairs("pron_rl", [("right", "light"), ("read", "lead"), ("rice", "lice"), ("road", "load"), ("rock", "lock"),
                   ("rate", "late"), ("grass", "glass"), ("pray", "play"), ("fry", "fly"), ("correct", "collect"),
                   ("arrive", "alive"), ("wrong", "long"), ("red", "led"), ("crowd", "cloud"), ("free", "flee"),
                   ("room", "loom"), ("price", "plice"), ("bring", "bling")])
_pairs("pron_fp", [("fan", "pan"), ("file", "pile"), ("fine", "pine"), ("full", "pull"), ("feel", "peel"),
                   ("fork", "pork"), ("fast", "past"), ("coffee", "copy"), ("fair", "pair"), ("face", "pace"),
                   ("fool", "pool"), ("four", "pour"), ("fill", "pill"), ("first", "pierced"), ("form", "porm"),
                   ("five", "pipe"), ("faster", "pastor"), ("flight", "plight"), ("feedback", "peedback")], both=False)
_pairs("pron_vb", [("very", "berry"), ("vote", "boat"), ("van", "ban"), ("vest", "best"), ("vase", "base"),
                   ("curve", "curb"), ("vet", "bet"), ("valuable", "baluable"), ("view", "bew"), ("leaves", "leabs")], both=False)
_pairs("pron_th", [("think", "sink"), ("thick", "sick"), ("thank", "sank"), ("mouth", "mouse"), ("path", "pass"),
                   ("bath", "bass"), ("three", "tree"), ("through", "true"), ("thought", "taught"), ("they", "day"),
                   ("then", "den"), ("though", "dough"), ("there", "dare"), ("those", "doze"), ("thursday", "sursday"),
                   ("the", "da"), ("this", "dis"), ("with", "wis")], both=False)
_pairs("pron_shs", [("she", "see"), ("ship", "sip"), ("sheet", "seat"), ("short", "sort"), ("shock", "sock"),
                    ("shave", "save"), ("sheep", "seep"), ("shop", "sop")], both=False)
_pairs("final_drop", [("card", "car"), ("plant", "plan"), ("seat", "sea"), ("hold", "hole"), ("find", "fine"),
                      ("cold", "coal"), ("guest", "guess"), ("send", "sen"), ("first", "firs"), ("next", "neck"),
                      ("left", "lef"), ("last", "lass")], both=False)

ARTICLES = {"a", "an", "the"}
TENSE = {"signed": "sign", "attached": "attach", "booked": "book", "started": "start", "walked": "walk",
         "delayed": "delay", "confirmed": "confirm", "bought": "buy", "did": "do", "was": "is", "got": "get",
         "leaves": "leave", "wants": "want", "does": "do", "is": "are", "has": "have"}
PLURAL = {"notes": "note", "minutes": "minute", "priorities": "priority", "bags": "bag", "roads": "road",
          "slides": "slide", "days": "day", "weekends": "weekend"}
PREP = {("by", "friday"): "until", ("at", "the"): "in", ("on", "the"): "in", ("to", "the"): "for",
        ("for", "your"): "to", ("of", "us"): "for", ("in", "the"): "at", ("on", "thursday"): "in", ("on", "weekends"): "in"}
FILLERS = ["um", "uh", "well", "like", "you know", "hmm", "so"]

COMMANDS = {
    "repeat": ["repeat", "repeat please", "say it again", "again please", "one more time", "can you repeat that",
               "sorry say that again", "pardon", "dashi", "da si", "the she", "han bun deo", "what was that"],
    "slower": ["slower please", "slow down please", "speak slowly", "can you say it slower", "too fast",
               "chun chun hee", "more slowly please"],
    "skip": ["skip", "skip this one", "next question", "pass", "next please", "I don't know skip", "move on",
             "nam gyeo", "just skip it"],
    "hint": ["hint", "give me a hint", "hint please", "can I get a hint", "a little hint", "hin teu"],
    "meaning": ["what does it mean", "meaning please", "what's the meaning", "translate it", "in korean please",
                "what does that word mean", "tteut", "the meaning"],
    "pause": ["pause", "wait", "hold on", "stop for a second", "jam can", "jam kkan", "wait a minute", "hang on"],
    "resume": ["continue", "resume", "go on", "let's continue", "keep going", "I'm ready", "gye sok"],
    "stop": ["stop the lesson", "end the session", "that's enough for today", "quit", "stop learning", "finish"],
}
NOISE = ["", "uh", "um", "hmm", "ok", "okay", "yeah", "ah ne", "eung", "in three hundred meters turn right",
         "turn left ahead", "you have arrived at your destination", "and now the weather for today",
         "traffic is heavy on the highway", "the next song is", "mom can we stop", "hello hello",
         "keep left", "speed camera ahead"]
GARBLE = ["hoe ui reul mi ru da", "ma gam il", "ye san", "an nyeong", "geu reon ga", "mol la yo", "jal mo reu get eo",
          "gong hang", "yak guk", "chang ga ja ri"]


def norm(s):
    s = s.lower().replace("’", "'")
    s = re.sub(r"[^a-z0-9' -]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def asr_surface(words, rnd):
    """STT 출력처럼: 소문자/대문자, 구두점 일부, 숫자 표기."""
    s = " ".join(words)
    if rnd.random() < 0.3:
        s = s[:1].upper() + s[1:]
    if rnd.random() < 0.2:
        s += rnd.choice([".", "?", ""])
    if rnd.random() < 0.2:
        s = s.replace("three", "3").replace("five", "5").replace("four", "4")
    return s


def out(act, verdict="none", match=-1, errors=()):
    return json.dumps({"act": act, "verdict": verdict, "match": match,
                       "errors": [{"t": t, "e": e, "h": h} for t, e, h in errors]},
                      ensure_ascii=False, separators=(",", ":"))


def user_msg(item, hyp):
    typ, track, prompt, answers = item[:4]
    if typ == Q:
        kws = answers[0].replace("|", ", ")
        return f"문항(qa, {track}): {prompt}\n핵심어: {kws}\n인식: {hyp}"
    return f"문항({typ}, {track}): {prompt}\n정답: {' | '.join(answers)}\n인식: {hyp}"


def perturb_pron(words, rnd, k=1):
    idx = [i for i, w in enumerate(words) if w in CONF]
    rnd.shuffle(idx)
    errs = []
    w2 = list(words)
    for i in idx[:k]:
        h, t = rnd.choice(CONF[w2[i]])
        errs.append((t, w2[i], h))
        w2[i] = h
    return w2, errs


def perturb_gram(words, rnd):
    """문법 오류 1개. 가능한 종류 중 하나를 고른다."""
    opts = []
    for i, w in enumerate(words):
        if w in ARTICLES and i + 1 < len(words):
            opts.append(("article", i))
        if w in TENSE:
            opts.append(("tense", i))
        if w in PLURAL:
            opts.append(("plural", i))
        if i + 1 < len(words) and (w, words[i + 1]) in PREP:
            opts.append(("preposition", i))
    if len(words) >= 4 and words[0] in {"where", "what", "how", "can", "could", "do", "does", "is"}:
        opts.append(("word_order", 0))
    if not opts:
        return None, None
    kind, i = rnd.choice(opts)
    w2 = list(words)
    if kind == "article":
        e = w2.pop(i)
        return w2, [("article", e, "")]
    if kind == "tense":
        e = w2[i]; w2[i] = TENSE[e]
        return w2, [("tense", e, w2[i])]
    if kind == "plural":
        e = w2[i]; w2[i] = PLURAL[e]
        return w2, [("plural", e, w2[i])]
    if kind == "preposition":
        e = w2[i]; w2[i] = PREP[(w2[i], w2[i + 1])]
        return w2, [("preposition", e, w2[i])]
    # word_order: 의문문 도치 누락 (can you see → you can see)
    if len(w2) >= 3:
        w2[0], w2[1] = w2[1], w2[0]
        return w2, [("word_order", words[0] + " " + words[1], w2[0] + " " + w2[1])]
    return None, None


def content_words(words):
    return [i for i, w in enumerate(words) if w not in ARTICLES and len(w) > 2]


def gen_for_item(item, rnd, n):
    """한 문항에서 n 개 예제. 반환: (user, assistant, meta)"""
    typ, track, prompt, answers = item[:4]
    rows = []
    for _ in range(n):
        r = rnd.random()
        if typ == Q:
            rows.append(gen_qa(item, rnd))
            continue
        mi = rnd.randrange(len(answers))
        words = norm(answers[mi]).split()
        if r < 0.20:  # 정답 그대로 (표면형 변화)
            hyp = asr_surface(words, rnd); rows.append((hyp, out("answer", "correct", mi), "correct_exact"))
        elif r < 0.27:  # 군말 + 정답
            w2 = list(words); w2.insert(0, rnd.choice(FILLERS))
            if rnd.random() < 0.5 and len(w2) > 3:
                w2.insert(rnd.randrange(2, len(w2)), rnd.choice(["uh", "um"]))
            rows.append((asr_surface(w2, rnd), out("answer", "correct", mi), "correct_filler"))
        elif r < 0.32:  # 자기 수정: 틀렸다가 다시 맞게
            bad = list(words)
            ci = content_words(bad)
            if ci:
                bad = bad[:max(ci[0], 1)]
            hyp = " ".join(bad) + " " + rnd.choice(["sorry", "I mean", "no wait"]) + " " + " ".join(words)
            rows.append((asr_surface(hyp.split(), rnd), out("answer", "correct", mi), "correct_selfcorrect"))
        elif r < 0.50:  # 발음 오류 → partial
            k = 1 if rnd.random() < 0.75 else 2
            w2, errs = perturb_pron(words, rnd, k)
            if errs:
                v = "partial" if len(words) > 1 or typ == V else "wrong"
                rows.append((asr_surface(w2, rnd), out("answer", "partial", mi, errs), "pron"))
            else:
                w2, errs = perturb_gram(words, rnd)
                if errs:
                    rows.append((asr_surface(w2, rnd), out("answer", "partial", mi, errs), "gram"))
                else:
                    rows.append((asr_surface(words, rnd), out("answer", "correct", mi), "correct_exact"))
        elif r < 0.62:  # 문법 오류 1개 → partial
            w2, errs = perturb_gram(words, rnd)
            if errs:
                rows.append((asr_surface(w2, rnd), out("answer", "partial", mi, errs), "gram"))
            else:
                w2, errs = perturb_pron(words, rnd, 1)
                if errs:
                    rows.append((asr_surface(w2, rnd), out("answer", "partial", mi, errs), "pron"))
                else:
                    rows.append((asr_surface(words, rnd), out("answer", "correct", mi), "correct_exact"))
        elif r < 0.68:  # 단어 하나 빠짐 → partial (짧은 문장이면 wrong)
            ci = content_words(words)
            if len(words) >= 4 and ci:
                i = rnd.choice(ci); e = words[i]; w2 = words[:i] + words[i + 1:]
                rows.append((asr_surface(w2, rnd), out("answer", "partial", mi, [("missing", e, "")]), "missing"))
            else:
                rows.append((asr_surface(words, rnd), out("answer", "correct", mi), "correct_exact"))
        elif r < 0.74:  # 오류 여러 개 → wrong
            w2, e1 = perturb_pron(words, rnd, 2)
            w3, e2 = perturb_gram(w2, rnd)
            errs = (e1 or []) + (e2 or [])
            ci = content_words(w3 or w2)
            if len(errs) >= 2 or not ci:
                rows.append((asr_surface(w3 or w2, rnd), out("answer", "wrong", -1, errs), "multi"))
            else:
                i = rnd.choice(ci); base = (w3 or w2)
                e = base[i]; w4 = base[:i] + base[i + 1:]
                rows.append((asr_surface(w4, rnd), out("answer", "wrong", -1, errs + [("missing", e, "")]), "multi"))
        elif r < 0.80:  # 다른 문항의 답 (엉뚱한 답) → wrong
            other = rnd.choice([s for s in SEED if s[0] in (B, R, V) and s[2] != prompt])
            hyp = norm(rnd.choice(other[3]))
            rows.append((asr_surface(hyp.split(), rnd), out("answer", "wrong", -1), "unrelated"))
        elif r < 0.83:  # 한국어로 답해 영어 STT 가 깨짐 → wrong
            rows.append((rnd.choice(GARBLE), out("answer", "wrong", -1), "garble_ko"))
        elif r < 0.95:  # 명령
            act = rnd.choice(list(COMMANDS))
            rows.append((asr_surface(rnd.choice(COMMANDS[act]).split(), rnd), out(act), "cmd_" + act))
        else:  # 잡음·내비 음성·라디오
            rows.append((rnd.choice(NOISE), out("noise"), "noise"))
    return [(user_msg(item, h), a, {"cat": c, "type": typ, "track": track}) for h, a, c in rows]


def gen_qa(item, rnd):
    typ, track, prompt, answers = item[:4]
    kws = [k for k in answers[0].split("|")]
    frames = {
        "full": ["I usually {k1} and then I {k2} with my team", "on a typical day I check my {k1} and have a {k2}",
                 "I think {k1} is important because it helps me {k2} every day",
                 "personally I prefer {k1} because I can {k2} more easily",
                 "most of the time I {k1} and sometimes I {k2} on the way"],
        "short": ["{k1}", "{k1} maybe", "I {k1}"],
    }
    r = rnd.random()
    k1, k2 = rnd.sample(kws, 2) if len(kws) >= 2 else (kws[0], kws[0])
    if r < 0.30:
        s = rnd.choice(frames["full"]).format(k1=k1, k2=k2)
        return (asr_surface(s.split(), rnd), out("answer", "correct", 0), "qa_full")
    if r < 0.45:
        s = rnd.choice(frames["full"]).format(k1=k1, k2=k2)
        w2, errs = perturb_gram(s.split(), rnd)
        if not errs:
            w2, errs = perturb_pron(s.split(), rnd, 1)
        if errs:
            return (asr_surface(w2, rnd), out("answer", "partial", 0, errs), "qa_err")
        return (asr_surface(s.split(), rnd), out("answer", "correct", 0), "qa_full")
    if r < 0.60:
        s = rnd.choice(frames["short"]).format(k1=k1)
        return (asr_surface(s.split(), rnd), out("answer", "partial", 0, [("missing", "full sentence", "")]), "qa_short")
    if r < 0.72:
        other = rnd.choice([x for x in SEED if x[0] == Q and x[2] != prompt])
        ok = [k for k in other[3][0].split("|") if k not in kws]
        if len(ok) >= 2:
            s = rnd.choice(frames["full"]).format(k1=ok[0], k2=ok[1])
            return (asr_surface(s.split(), rnd), out("answer", "wrong", -1), "qa_offtopic")
        return (rnd.choice(GARBLE), out("answer", "wrong", -1), "garble_ko")
    if r < 0.88:
        act = rnd.choice(list(COMMANDS))
        return (asr_surface(rnd.choice(COMMANDS[act]).split(), rnd), out(act), "cmd_" + act)
    return (rnd.choice(NOISE), out("noise"), "noise")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--per-item", type=int, default=48)
    ap.add_argument("--holdout", type=float, default=0.2)
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    items = list(SEED)
    rnd.shuffle(items)
    # 유형별로 고르게 unseen 문항을 뗀다
    unseen = []
    for t in (B, R, V, Q):
        group = [x for x in items if x[0] == t]
        unseen += group[:max(2, int(len(group) * a.holdout))]
    seen = [x for x in items if x not in unseen]
    train, valid, test = [], [], []
    for it in seen:
        rows = gen_for_item(it, rnd, a.per_item)
        train += [(u, ans, dict(m, split="seen")) for u, ans, m in rows]
        rows = gen_for_item(it, random.Random(rnd.random()), 6)
        test += [(u, ans, dict(m, split="seen")) for u, ans, m in rows]
    for it in unseen:
        rows = gen_for_item(it, rnd, 16)
        test += [(u, ans, dict(m, split="unseen")) for u, ans, m in rows]
    # 중복 제거 (같은 입력이 train/test 에 함께 있으면 test 에서 뺀다)
    seen_inputs = {u for u, _, _ in train}
    test = [r for r in test if r[0] not in seen_inputs]
    rnd.shuffle(train)
    valid, train = train[:300], train[300:]
    od = Path(a.out); od.mkdir(parents=True, exist_ok=True)
    for name, rows in (("train", train), ("valid", valid), ("test", test)):
        with open(od / f"{name}.jsonl", "w", encoding="utf-8") as f:
            for u, ans, m in rows:
                f.write(json.dumps({"messages": [{"role": "system", "content": SYSTEM},
                                                 {"role": "user", "content": u},
                                                 {"role": "assistant", "content": ans}], "meta": m},
                                   ensure_ascii=False) + "\n")
    from collections import Counter
    print("items: seen", len(seen), "unseen", len(unseen))
    print("rows: train", len(train), "valid", len(valid), "test", len(test),
          "| test split", dict(Counter(m["split"] for _, _, m in test)))
    print("test categories", dict(Counter(m["cat"] for _, _, m in test).most_common()))


if __name__ == "__main__":
    main()
