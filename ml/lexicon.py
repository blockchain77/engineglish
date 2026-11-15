# -*- coding: utf-8 -*-
"""
장소 사전 — 학습(train/valid)과 평가(test)가 서로 다른 장소 이름을 쓴다.

test 전용 이름은 학습 데이터의 어느 문장에도 나오지 않는다(gen_data.py 가 검사).
상호·지명은 사람들이 실제로 말하는 이름일 뿐이며, 영업 정보·제휴와는 무관하다.
"""
import random

from nlu_schema import CATEGORY_WORDS

# ── 체인·브랜드 (지점명과 조합된다) ────────────────────────────────────────────
CHAINS = {
    "train": [
        "스타벅스", "투썸플레이스", "이디야", "메가커피", "빽다방", "컴포즈커피", "폴바셋", "블루보틀", "파스쿠찌", "탐앤탐스",
        "성심당", "파리바게뜨", "이성당", "삼송빵집", "태극당", "런던베이글뮤지엄",
        "이마트", "홈플러스", "롯데마트", "코스트코", "하나로마트", "노브랜드",
        "현대백화점", "롯데백화점", "신세계백화점", "스타필드", "롯데월드몰", "현대프리미엄아울렛", "롯데프리미엄아울렛",
        "맥도날드", "버거킹", "롯데리아", "맘스터치", "서브웨이", "교촌치킨", "본죽", "김밥천국", "명륜진사갈비",
        "다이소", "올리브영", "이케아", "블루핸즈", "IFC몰",
    ],
    "test": [
        "할리스", "커피빈", "엔제리너스", "뚜레쥬르", "근대골목단팥빵", "트레이더스", "갤러리아백화점", "AK플라자",
        "쉐이크쉑", "한솥도시락", "고봉민김밥", "무인양품", "모던하우스", "신세계사이먼아울렛",
    ],
}
BRANCHES = [
    "판교", "잠실", "강남", "강남역", "대전역", "성수", "홍대", "여의도", "광교", "동탄", "수원역", "송도", "일산", "분당",
    "서현", "목동", "신촌", "명동", "광화문", "센텀시티", "해운대", "부산역", "동대구역", "유성", "둔산", "청주", "천안",
    "세종", "하남", "용인", "평택", "잠실새내역", "테크노밸리", "DCC", "은행동", "무역센터", "스페이스원", "제타플렉스",
]

# ── 명소·시설 ─────────────────────────────────────────────────────────────────
LANDMARKS = {
    "train": [
        "롯데월드타워", "남산타워", "경복궁", "광화문광장", "코엑스", "여의도 한강공원", "올림픽공원", "서울숲", "해운대해수욕장",
        "광안리", "경포해변", "정동진", "전주 한옥마을", "대전역", "서울역", "수원역", "판교역", "잠실역", "인천공항", "김포공항",
        "에버랜드", "롯데월드", "서울대공원", "국립중앙박물관", "예술의전당", "고척스카이돔", "잠실야구장", "남이섬", "속초 중앙시장",
        "대흥동 성당", "서울아산병원", "삼성서울병원", "세브란스병원", "분당서울대병원", "판교 테크노밸리", "카이스트",
        "엑스포과학공원", "63빌딩", "청계천", "양재 시민의숲",
    ],
    "test": [
        "동대문디자인플라자", "북촌 한옥마을", "강릉 안목해변", "오송역", "대구 동성로", "광명역", "이대목동병원", "아쿠아플라넷",
        "한국민속촌", "수원 화성", "오죽헌", "청남대", "서대전역", "용산역", "김해공항", "보라매공원",
    ],
}

# ── 개인 별칭 (사람마다 다른 말 — 개인 사전이 실제 장소로 바꾼다) ─────────────────
ALIASES = {
    "train": [
        "엄마 집", "할머니 댁", "본가", "처가", "시댁", "친정", "부모님 댁", "큰집", "친구 집", "회사 앞 카페", "집 앞 편의점",
        "애들 학원", "태권도장", "피아노 학원", "도윤이 유치원", "단골 미용실", "동네 헬스장", "아내 회사", "장모님 댁",
        "삼촌 집", "지훈이네", "처갓집", "애들 병원", "주말농장",
    ],
    "test": [
        "외할머니 댁", "이모네", "고모 집", "남편 회사", "민지네 집", "수영장", "영어 학원", "동생 집", "시어머니 댁",
        "학교 앞 문구점", "사촌 형 집",
    ],
}

# ── 이름 있는 휴게소 (일반 '휴게소'는 ADD_REST_STOP, 이름을 대면 장소 명령) ──────────
REST_AREAS = {
    "train": ["안성휴게소", "덕평자연휴게소", "망향휴게소", "횡성휴게소", "여주휴게소", "평창휴게소", "문막휴게소"],
    "test": ["행담도휴게소", "천안삼거리휴게소", "금강휴게소", "이천휴게소"],
}

# ── 처음 보는 상호를 그대로 옮겨 적는 능력을 기르기 위한 합성 상호 ─────────────────
_SYL = list("가나다라마바사아자하해달별솔담온빛숲늘봄결새윤은한도란미루소예린서진율채연수정호민지유보람슬")
_SUFFIX = [
    "빵공방", "베이커리", "커피", "식당", "국밥", "칼국수", "정육점", "꽃집", "미용실", "약국", "치과", "내과", "소아과",
    "안경원", "서점", "세탁소", "공방", "분식", "돈가스", "횟집", "반찬가게", "정비소", "세차장", "필라테스", "수학학원",
    "떡집", "냉면", "곱창", "한의원", "동물병원",
]


def _synthetic(split, n):
    r = random.Random(20261114)
    stems = []
    seen = set()
    while len(stems) < 400:
        st = r.choice(_SYL) + r.choice(_SYL) + (r.choice(_SYL) if r.random() < 0.25 else "")
        if st not in seen and len(set(st)) == len(st):
            seen.add(st)
            stems.append(st)
    names = [st + r.choice(_SUFFIX) for st in stems]
    names = [x if r.random() < 0.8 else x[: len(x) - len(x) // 3] + " " + x[len(x) - len(x) // 3:] for x in names]
    return names[:n] if split == "train" else names[300:300 + n]


SYNTH = {"train": _synthetic("train", 160), "test": _synthetic("test", 60)}

# 종류로 말할 때 앞에 붙는 목적 (빵 사러 빵집 들르자)
PURPOSE = {
    "BAKERY": ["빵 사러", "아침 빵 좀 사게", "케이크 사러"],
    "CAFE": ["커피 마시러", "커피 한잔하게", "커피 사러"],
    "MART": ["장 보러", "장 좀 보게", "우유 사러"],
    "DEPT": ["쇼핑하러", "선물 사러"],
    "KIDS": ["애들 데리러", "아이 데려다주러", "도윤이 데리러"],
    "HOSPITAL": ["진료 받으러", "애들 감기 때문에", "약 타러"],
    "PARK": ["산책하러", "애들 뛰어놀게", "바람 쐬러"],
}

COMPANIONS = ["애들", "엄마", "아빠", "여보", "와이프", "남편", "친구", "도윤이", "지유", "가족들", "부모님"]
# "X 가는 길에 P 들르자" 의 X (장소 슬롯이 아님)
ON_THE_WAY = ["집에", "집", "회사", "본가", "대전", "할머니 댁에", "학원", "공항", "강릉", "부산", "처가에", "캠핑장"]
CITIES = ["강릉", "부산", "속초", "여수", "경주", "전주", "대전", "남해", "양양", "가평", "춘천", "통영", "안동", "단양", "제천", "목포"]
PETS = ["강아지", "우리 강아지", "강아지 콩이", "반려견", "고양이", "우리 고양이", "멍멍이", "댕댕이", "우리 개"]
KID_CALL = ["아빠", "엄마", "아빠아", "엄마아"]
KID_DEST = ["할머니 집", "할머니네", "바다", "놀이공원", "캠핑장", "워터파크", "키즈카페"]


class PlacePool:
    """split 별 장소 표본기. sample() → (말한 장소 문자열, 출처, 종류 또는 None)"""

    WEIGHTS = [("chain", 0.30), ("landmark", 0.20), ("alias", 0.15), ("synth", 0.15), ("cat", 0.15), ("rest", 0.05)]

    def __init__(self, split):
        key = "test" if split == "test" else "train"
        self.key = key
        self.chains = CHAINS[key]
        self.landmarks = LANDMARKS[key]
        self.aliases = ALIASES[key]
        self.rest = REST_AREAS[key]
        self.synth = SYNTH[key]

    def names(self):
        """이 split 의 고유 이름 전체 (겹침 검사용, 종류 단어 제외)."""
        return list(self.chains) + list(self.landmarks) + list(self.aliases) + list(self.rest) + list(self.synth)

    def chain_mention(self, rng):
        b = rng.choice(self.chains)
        x = rng.random()
        if x < 0.5:
            return b
        if x < 0.75:
            br = rng.choice(BRANCHES + ["본"])
            return f"{b} {br}점"
        return f"{rng.choice(BRANCHES)} {b}"

    def sample(self, rng, kinds=None):
        items = [(k, w) for k, w in self.WEIGHTS if kinds is None or k in kinds]
        total = sum(w for _, w in items)
        x = rng.random() * total
        kind = items[-1][0]
        for k, w in items:
            x -= w
            if x <= 0:
                kind = k
                break
        if kind == "chain":
            return self.chain_mention(rng), kind, None
        if kind == "landmark":
            return rng.choice(self.landmarks), kind, None
        if kind == "alias":
            return rng.choice(self.aliases), kind, None
        if kind == "synth":
            return rng.choice(self.synth), kind, None
        if kind == "rest":
            return rng.choice(self.rest), kind, None
        cat = rng.choice(list(CATEGORY_WORDS))
        return rng.choice(CATEGORY_WORDS[cat]), "cat", cat

    def mention(self, rng):
        """다른 의도 문장 속 장소 언급(정답 슬롯 아님) — 같은 split 의 이름만 쓴다."""
        x = rng.random()
        if x < 0.55:
            return self.chain_mention(rng)
        if x < 0.85:
            return rng.choice(self.landmarks)
        return rng.choice(self.synth)
