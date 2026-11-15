# -*- coding: utf-8 -*-
"""
옆자리 온디바이스 명령 해석 모델의 출력 계약 (gen_data / train_lora / eval_nlu 공용).

앱 쪽 대응:
  app/src/main/java/com/yeopjari/app/ai/nlu/OnDeviceIntentEngine.kt   (Intent enum, NluResult)
  app/src/main/java/com/yeopjari/app/ai/personal/PersonalModels.kt    (PlaceQuery, StopMode, Category)

모델 출력 = JSON 한 줄. 해당 없는 키는 쓰지 않는다. 키 순서는 KEY_ORDER로 고정한다.
  {"intent":"NAV_PLACE","place":"서심다응","stop":"destination","route":"edge"}
"""
import json
import re

# Kotlin enum Intent 와 같은 순서·이름. 값 = (한국어 라벨, 기본 처리 위치)
INTENTS = {
    "NAV_PLACE": ("장소 안내 (개인화 보정)", "edge"),
    "HVAC_SET_TEMP": ("온도 설정", "edge"),
    "HVAC_WARMER": ("따뜻하게", "edge"),
    "HVAC_COOLER": ("시원하게", "edge"),
    "HVAC_FRESH_AIR": ("환기", "edge"),
    "NAV_HOME": ("집으로 안내", "edge"),
    "NAV_WORK": ("회사로 안내", "edge"),
    "ADD_REST_STOP": ("휴게소 경유", "edge"),
    "FIND_CHARGER": ("충전소 찾기", "edge"),
    "CHARGE_STATUS": ("배터리 상태", "edge"),
    "ETA_QUERY": ("도착 시간", "edge"),
    "KIDS_ETA": ("아이 질문: 언제 도착", "edge"),
    "PET_MODE_ON": ("펫 케어 켜기", "edge"),
    "PET_MODE_OFF": ("펫 케어 끄기", "edge"),
    "REST_MODE": ("쉼 모드", "edge"),
    "VEHICLE_CHECK": ("차량 점검", "edge"),
    "DROWSY": ("졸음 호소", "edge"),
    "VIDEO_REQUEST": ("영상 재생", "edge"),
    "PARKING_MEMO": ("주차 위치 메모", "edge"),
    "CHARGE_LIMIT": ("충전 한도 설정", "edge"),
    "KIDS_GAME": ("아이 퀴즈", "cloud"),
    "DIAGNOSE": ("증상 진단", "cloud"),
    "MANUAL_QA": ("차량 기능 질문", "cloud"),
    "ROUTINE_CREATE": ("루틴 만들기", "cloud"),
    "BRIEFING": ("브리핑", "cloud"),
    "TRIP_PLAN": ("여정 계획", "cloud"),
    "OPEN_TALK": ("자유 대화", "cloud"),
}

# 말로 부르는 장소 종류 — PersonalModels.kt Category.words 와 같은 단어
# (장소 자리에 이 단어만 단독으로 왔을 때만 category 를 붙인다. "회사 앞 카페"는 이름으로 본다)
CATEGORY_WORDS = {
    "BAKERY": ["빵집", "베이커리", "제과점", "빵가게"],
    "CAFE": ["카페", "커피숍", "커피집"],
    "MART": ["마트", "대형마트", "슈퍼"],
    "DEPT": ["백화점", "아울렛"],
    "KIDS": ["유치원", "어린이집"],
    "HOSPITAL": ["병원", "소아과", "소아청소년과"],
    "PARK": ["공원"],
}

KEY_ORDER = ["intent", "place", "stop", "category", "habit", "nearby", "temp", "percent", "note", "route"]
STOPS = ("destination", "waypoint")
ROUTES = ("edge", "cloud")

# 학습·평가·차량 추론이 모두 같은 문장을 쓴다 (짧을수록 차량에서 빠르다).
SYSTEM_PROMPT = (
    "너는 차량 음성비서 '옆자리'의 명령 해석기다. 음성인식 결과 문장을 JSON 한 줄로 바꿔라. "
    "장소 이름은 들린 그대로 쓰고 고치지 마라."
)


def to_json_line(d):
    """KEY_ORDER 순서의 압축 JSON 한 줄 (토큰 수 최소화)."""
    out = {}
    for k in KEY_ORDER:
        if k in d and d[k] is not None and d[k] is not False:
            out[k] = d[k]
    extra = [k for k in d if k not in KEY_ORDER]
    if extra:
        raise ValueError(f"unknown keys: {extra}")
    return json.dumps(out, ensure_ascii=False, separators=(",", ":"))


def canonical(d):
    """비교용 정규화: None/False 제거, 22.0 → 22, 키 집합만 비교."""
    if not isinstance(d, dict):
        return None
    out = {}
    for k, v in d.items():
        if v is None or v is False:
            continue
        if isinstance(v, float) and v.is_integer():
            v = int(v)
        if k in ("temp", "percent") and isinstance(v, str):
            try:
                f = float(v)
                v = int(f) if f.is_integer() else f
            except ValueError:
                pass
        out[k] = v
    return out


_THINK = re.compile(r"<think>.*?</think>", re.S)
_OBJ = re.compile(r"\{[^{}]*\}", re.S)


def parse_output(text):
    """모델 출력 → (dict 또는 None, strict_ok).
    strict_ok: 출력 전체가 그대로 JSON 객체 하나였는가 (valid-JSON 비율 계산용)."""
    t = _THINK.sub("", text or "").strip()
    try:
        obj = json.loads(t)
        if isinstance(obj, dict):
            return obj, True
    except (json.JSONDecodeError, ValueError):
        pass
    for m in _OBJ.finditer(t):
        try:
            obj = json.loads(m.group(0))
            if isinstance(obj, dict):
                return obj, False
        except (json.JSONDecodeError, ValueError):
            continue
    return None, False


def chat_messages(utterance, system=SYSTEM_PROMPT):
    return [{"role": "system", "content": system}, {"role": "user", "content": utterance}]


# ── 기반 모델(미세조정 전) 비교용 few-shot 프롬프트 ─────────────────────────────
FEWSHOT_SYSTEM = (
    SYSTEM_PROMPT
    + "\n출력 키(해당 없으면 생략): intent, place(장소 이름·종류를 들린 그대로), stop(destination|waypoint), "
    "category(BAKERY|CAFE|MART|DEPT|KIDS|HOSPITAL|PARK, 장소 자리에 종류 단어만 왔을 때), habit(늘 가던·자주 가는), "
    "nearby(근처·가까운), temp(설정 온도 숫자), percent(충전 한도 숫자), note(주차 위치), route(edge|cloud).\n"
    "intent 목록: "
    + ", ".join(f"{k}({v[0]})" for k, v in INTENTS.items())
    + "\nroute: 여정 계획·진단·차량 기능 질문·루틴·브리핑·아이 퀴즈·자유 대화와 조건이 여러 개인 요청은 cloud, 나머지는 edge."
    "\n집·회사·휴게소·충전소 일반 명칭은 각각 NAV_HOME·NAV_WORK·ADD_REST_STOP·FIND_CHARGER로 처리한다."
)

FEWSHOT_EXAMPLES = [
    ("옆자리야 스타벅스 판교점으로 가 줘", {"intent": "NAV_PLACE", "place": "스타벅스 판교점", "stop": "destination", "route": "edge"}),
    ("집에 가는 길에 늘 가던 빵집 들렀다 가자", {"intent": "NAV_PLACE", "place": "빵집", "stop": "waypoint", "category": "BAKERY", "habit": True, "route": "edge"}),
    ("서심다응 가자", {"intent": "NAV_PLACE", "place": "서심다응", "stop": "destination", "route": "edge"}),
    ("집으로 가자", {"intent": "NAV_HOME", "route": "edge"}),
    ("에어컨 21도로 맞춰 줘", {"intent": "HVAC_SET_TEMP", "temp": 21, "route": "edge"}),
    ("충전 80%까지만 해 줘", {"intent": "CHARGE_LIMIT", "percent": 80, "route": "edge"}),
    ("지하 2층 C구역 15번에 주차했어", {"intent": "PARKING_MEMO", "note": "지하 2층 C구역 15번", "route": "edge"}),
    ("다음 휴게소 들렀다 가자", {"intent": "ADD_REST_STOP", "route": "edge"}),
    ("언제 도착해?", {"intent": "ETA_QUERY", "route": "edge"}),
    ("이번 주말 강릉 여행 코스 짜 줘", {"intent": "TRIP_PLAN", "route": "cloud"}),
    ("성심당 몇 시까지 해?", {"intent": "OPEN_TALK", "route": "cloud"}),
]


# 앱 시연 시나리오에서 실제로 말하는 문장 — 학습 데이터에 고정 포함, 평가 때 별도 확인
DEMO_EXAMPLES = [
    ("서심다응 가자", {"intent": "NAV_PLACE", "place": "서심다응", "stop": "destination", "route": "edge"}),
    ("스타벅스 가자", {"intent": "NAV_PLACE", "place": "스타벅스", "stop": "destination", "route": "edge"}),
    ("빵집 들렀다 가자", {"intent": "NAV_PLACE", "place": "빵집", "stop": "waypoint", "category": "BAKERY", "route": "edge"}),
    ("엄마 집 가자", {"intent": "NAV_PLACE", "place": "엄마 집", "stop": "destination", "route": "edge"}),
    ("옆자리야 서심다응 들렀다 가자", {"intent": "NAV_PLACE", "place": "서심다응", "stop": "waypoint", "route": "edge"}),
    ("집으로 가자", {"intent": "NAV_HOME", "route": "edge"}),
    ("회사로 가자", {"intent": "NAV_WORK", "route": "edge"}),
    ("다음 휴게소 들렀다 가자", {"intent": "ADD_REST_STOP", "route": "edge"}),
    ("차 상태 점검해줘", {"intent": "VEHICLE_CHECK", "route": "edge"}),
    ("오늘 브리핑 해줘", {"intent": "BRIEFING", "route": "cloud"}),
    ("아이들 심심해하는데 퀴즈 해줘", {"intent": "KIDS_GAME", "route": "cloud"}),
    ("졸려, 잠 좀 깨워줘", {"intent": "DROWSY", "route": "edge"}),
    ("옆자리 펫 케어 켜줘", {"intent": "PET_MODE_ON", "route": "edge"}),
    ("더워", {"intent": "HVAC_COOLER", "route": "edge"}),
    ("추워", {"intent": "HVAC_WARMER", "route": "edge"}),
]


def fewshot_messages(utterance):
    msgs = [{"role": "system", "content": FEWSHOT_SYSTEM}]
    for u, d in FEWSHOT_EXAMPLES:
        msgs.append({"role": "user", "content": u})
        msgs.append({"role": "assistant", "content": to_json_line(d)})
    msgs.append({"role": "user", "content": utterance})
    return msgs
