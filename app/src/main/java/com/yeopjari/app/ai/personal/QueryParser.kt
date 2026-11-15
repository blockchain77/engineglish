package com.yeopjari.app.ai.personal

/** 되묻기(확인·선택지)에 대한 사용자의 대답 */
sealed interface Reply {
    data object Yes : Reply
    data object No : Reply
    /** "아니, 본점"처럼 부정 뒤에 다른 이름을 바로 말한 경우 — [rest]는 들린 그대로(띄어쓰기 유지) */
    data class NoAnd(val rest: String) : Reply
    /** 0부터 시작하는 선택지 번호 — 화면보다 큰 번호("4번")도 그대로 돌려준다 (범위 안내는 받는 쪽에서) */
    data class Pick(val index: Int) : Reply
    data object Cancel : Reply
    data object None : Reply
}

/** 대답도 명령도 아닌 말 — 되묻기를 지운 채 새 명령으로 넘기지 않고 질문을 지킨다 */
enum class Aside {
    /** "뭐라고?", "다시 말해 줘", "잘 못 들었어" → 같은 질문을 다시 */
    REPEAT,
    /** "잠깐만", "기다려 봐" → 질문은 그대로 두고 기다린다 */
    HOLD,
    /** "어…", "음", "글쎄" → 머뭇거림: 질문은 그대로, 마이크를 한 번 더 연다 */
    FILLER,
}

/**
 * 장소 명령 해석기 — STT SDK가 준 문장에서 '어디로'와 '목적지/경유지'를 뽑는다.
 *
 * 목표 구현은 차량 명령 도메인 LoRA로 미세조정한 온디바이스 소형 언어모델(Qwen3 계열)이며,
 * 이 규칙 기반 구현은 같은 출력 형식(PlaceQuery)을 내는 대체물이다.
 */
object QueryParser {
    /** 말머리 군말 — 뒤에 띄어쓰기·쉼표가 있을 때만 뗀다 ('어린이집'·'그랜드'·'어마 집'의 첫 글자를 자르지 않게) */
    private val lead = Regex("""^(옆자리야|옆자리|음+|어+|저기요?|그+|있잖아)(?:[\s,.…~!]+|$)""")
    // '좀'·'잠깐'은 앞에 띄어쓰기가 있을 때만 꼬리로 본다 ('현대백화좀'처럼 이름 끝 소리가 '좀'으로 들린 경우를 자르지 않게)
    // "들렀다 가 줘"·"들러 주세요"·"들렀다 갈게"처럼 부탁·약속 꼴도 경유지다 (목적지 꼬리 '가 줘'가 먼저 잡혀 '성심당 들렀다'가 목적지가 되지 않게)
    private val waypointTail = Regex(
        """\s*(에|을|를)?\s*((?<=\s|^)잠깐\s*|(?<=\s|^)좀\s*)?(""" +
            """(?:들렀다가?|들러서)\s?가\s?(?:줘|주세요|줄래요?|주라|요)|(?:들렀다가?|들러서)\s?(?:갈게요?|갈께요?|갈래요?|갑시다)""" +
            """|들러\s?(?:줘|주세요|줄래요?|주라)""" +
            """|들렀다\s?가자|들렀다가\s?가자|들렀다\s?갈래|들렀다\s?가|들러서\s?가자|들러\s?가자|들르자""" +
            """|경유해\s?(?:줘|주세요)|경유해서\s?가자|경유)\s*[.!?~]*$""",
    )
    private val destTail = Regex(
        """\s*(으로|로|에|까지|쪽으로)?\s*((?<=\s|^)좀\s*)?(가자|가\s?줘|가\s?주세요|가\s?줄래|갈래|가고\s?싶어|안내해\s?줘|안내해\s?주세요|안내해|안내\s?시작|길\s?안내해\s?줘|찾아\s?줘|데려다\s?줘|가\s?볼까)\s*[.!?~]*$""",
    )
    private val companions = Regex("""^.*?(이랑|랑|하고)\s+""")
    private val onTheWay = Regex("""^.*가는\s?길에\s*""")
    private val habitWords = listOf("늘 가던", "자주 가던", "자주 가는", "맨날 가는", "맨날 가던", "평소 가는", "평소 가던", "항상 가는", "늘 가는", "단골", "가던")
    private val nearbyWords = listOf("근처", "가까운", "주변", "근방")
    private val trailingParticles = listOf("으로", "로", "에", "까지", "을", "를")

    /** 기존 의도(집으로·회사로·휴게소·충전소)가 처리하는 말 — 장소 말 전체가 이 말일 때 */
    private val excluded = setOf("집", "우리집", "회사", "휴게소", "다음휴게소", "충전소", "급속충전소", "졸음쉼터", "쉼터", "주유소", "화장실")

    /**
     * 다른 의도의 말 — 장소 말 안 어디에 들어 있어도 장소 명령이 아니다
     * ("졸려서 휴게소 들렀다 가자"는 졸음·휴게소 의도, "에어컨 켜고 집으로 가자"는 공조 의도).
     */
    private val otherIntentWords = listOf(
        "휴게소", "쉼터", "화장실", "충전소", "졸려", "졸음", "피곤", "에어컨", "히터", "추워", "더워",
        // "쉬었다 가자"·"잠깐 쉬어 가자"는 휴게소 의도 (장소 이름 '쉬었다'로 읽지 않는다)
        "쉬었다", "쉬어", "쉬자", "쉬고", "휴식",
    )

    /** 경유 동사가 장소 말 가운데·끝에 남은 경우 ("스타벅스 들렀다 회사로 가자" → 여정 계획) */
    private val stopVerbs = listOf("들렀", "들러", "들르", "경유")

    /**
     * 장소 말 앞에 붙는 부사·대답 낱말 — 앞에 있으면 떼고("그냥 스타벅스 가자" → 스타벅스),
     * 장소 말이 이것뿐이면 장소 명령이 아니다("빨리 가자", "그냥 가자", "거기로 가자").
     */
    private val adverbWords = setOf(
        "그냥", "빨리", "얼른", "어서", "천천히", "다시", "이제", "일단", "계속", "그대로", "이대로", "같이", "좀",
        "지금", "바로", "한번", "먼저", "우선", "잠깐", "잠시", "얼른요", "빨리요",
        // 대답 낱말 ("응 성심당 가자", "아니 스타벅스 가자")
        "응", "웅", "네", "예", "그래", "좋아", "오케이", "아니", "아니야",
    )
    /** 가리키는 말 — 이것만으로는 어느 장소인지 알 수 없다 (되묻는 중이면 [reply]가 '네'로 받는다) */
    private val pointerWords = setOf(
        "거기", "여기", "저기", "그곳", "이곳", "저곳", "그쪽", "이쪽", "저쪽", "그데", "거기다",
        // "아니 다른 데로 가자" → '다른 데'도 장소가 아니다
        "다른", "딴", "데", "곳",
    )

    fun norm(s: String): String = s.replace(Regex("[\\s.,!?~·]"), "").lowercase()

    fun parse(utterance: String): PlaceQuery? {
        var u = lead.replace(utterance.trim(), "")
        var mode = StopMode.DESTINATION
        val wp = waypointTail.find(u)
        val tail = if (wp != null) {
            mode = StopMode.WAYPOINT
            wp
        } else destTail.find(u) ?: return null
        u = u.substring(0, tail.range.first)
        // 꼬리가 떼어 간 조사 ('그대로 가자'의 '로') — 부사인지 볼 때 다시 붙여 본다
        val particle = tail.groupValues[1]
        u = onTheWay.replace(u, "")
        u = companions.replace(u, "")
        val habit = habitWords.any { u.contains(it) }
        habitWords.forEach { u = u.replace(it, " ") }
        val nearby = nearbyWords.any { u.contains(it) }
        nearbyWords.forEach { u = u.replace(it, " ") }
        u = u.replace(Regex("\\s+"), " ").trim()
        u = dropLeadingAdverbs(u, particle) ?: return null
        for (p in trailingParticles) if (u.length > p.length + 1 && u.endsWith(p)) { u = u.dropLast(p.length).trim(); break }
        if (u.isBlank()) return null
        val n = norm(u)
        if (n in excluded || n.length > 24) return null
        if (!isSinglePlace(u)) return null
        val category = Category.entries.firstOrNull { c -> c.words.any { norm(it) == n } }
        return PlaceQuery(u, mode, category, habit, nearby, utterance)
    }

    /**
     * 앞에 붙은 부사·대답 낱말을 뗀다. 장소 말이 부사·가리키는 말뿐이면 null
     * ("빨리 가자", "그냥 가자", "거기로 가자" — '빨리'가 '…밸리점'에 붙어 엉뚱한 곳을 묻지 않게).
     */
    private fun dropLeadingAdverbs(u: String, particle: String = ""): String? {
        val tokens = u.split(' ').filter { it.isNotEmpty() }
        val bare = { t: String -> t.trim(',', '.', '…', '~', '!', '?') }
        val nonPlace = { t: String -> t in adverbWords || t in pointerWords }
        val lastWithParticle = tokens.lastOrNull()?.let { bare(it) + particle }
        if (tokens.isNotEmpty() && tokens.dropLast(1).all { nonPlace(bare(it)) } &&
            (nonPlace(bare(tokens.last())) || (lastWithParticle != null && nonPlace(lastWithParticle)))
        ) return null
        val first = tokens.indexOfFirst { bare(it) !in adverbWords }
        return if (first <= 0) u else tokens.drop(first).joinToString(" ")
    }

    /**
     * 뽑은 말이 '장소 하나'로 보이는지. 다른 의도의 말, 부정("…말고", "대신"),
     * 앞에 이어진 절("…고 ", "…서 ", "…는데 "), 경유 동사("…들렀다")가 섞여 있으면 장소 명령으로 보지 않는다.
     */
    private fun isSinglePlace(u: String): Boolean {
        val n = norm(u)
        if (otherIntentWords.any { n.contains(it) }) return false
        val tokens = u.split(Regex("\\s+")).map { it.trim(',', '.', '…', '~', '!', '?') }.filter { it.isNotEmpty() }
        if (tokens.any { it == "대신" || it == "대신에" || it.endsWith("말고") }) return false
        // 마지막 낱말까지 본다 — "성심당 들렀다 가 줘"의 '들렀다'가 장소 이름에 남지 않게
        if (tokens.any { t -> stopVerbs.any { t.startsWith(it) } }) return false
        for (i in 0 until tokens.size - 1) {
            // 뒤 낱말이 한 음절뿐이면 음성인식이 한 낱말 사이에 띄어쓰기를 넣은 것으로 본다 ('올림픽고 원')
            if (isClauseEnd(tokens[i]) && tokens[i + 1].count { it.isLetterOrDigit() } >= 2) return false
        }
        return true
    }

    /** '…고'가 이어 주는 말(켜고·먹고·들르고)로 쓰이는 한 음절 동사·형용사 줄기 — 이 줄기 + '고'가 낱말 전체일 때만 */
    private val goStems1 = setOf(
        "하", "켜", "꺼", "끄", "틀", "먹", "있", "없", "가", "오", "보", "주", "사", "타", "놓", "두", "받", "싣", "찾", "빼", "넣",
        "열", "닫", "쉬", "씻", "들", "자", "울", "웃", "놀", "달", "살", "팔", "읽", "쓰", "듣", "걷", "앉", "서", "입", "벗", "찍",
    )
    /** 두 음절 이상 동사 줄기 — 낱말이 이것 + '고'로 끝나면 이어 주는 말 ('애들 데리고', '불 켜주고') */
    private val goStems2 = listOf(
        "들르", "내리", "태우", "데리", "맡기", "끝내", "마치", "챙기", "기다리", "만나", "들어가", "나오", "올리", "낮추", "줄이",
        "높이", "다녀오", "갔다오", "해주", "켜주", "꺼주", "틀어주", "사주", "데려다주", "내려주", "태워주", "재우", "먹이", "입히",
        "씻기", "바꾸", "피우", "다니", "모시", "고치", "넘기", "보내",
    )

    /**
     * '…고'가 앞 절을 이어 주는 말인지 — 동사·형용사 줄기 뒤에서만 ('밥 먹고', '에어컨 켜고', '애들 데리고', '급하고', '늦었고').
     * '잠실고'·'휘문고'·'망고'·'중고'처럼 이름이 '고'로 끝나는 경우는 이어 주는 말이 아니다.
     */
    private fun isGoConnective(t: String): Boolean {
        if (t.length < 2 || t.last() != '고') return false
        val stem = t.dropLast(1)
        if (stem in goStems1 || goStems2.any { stem.endsWith(it) }) return true
        // …하고 (주차하고·충전하고·급하고) · …없고 (재미없고) · 과거 …았/었/했고 (늦었고·했고: 받침 ㅆ)
        if (stem.length >= 2 && stem.endsWith("하")) return true
        return stem.endsWith("없") || Hangul.jongOf(stem.last()) == 20
    }

    /**
     * 이어지는 절의 끝인지 (뒤에 띄어쓰기가 있는 낱말만 본다):
     *  - …고: 동사·형용사 줄기 뒤에서만 (켜고·먹고·들르고 / '잠실고'·'망고'·'중고'는 아님)
     *  - …서: 앞 음절에 받침이 없을 때만 (졸려서·피곤해서·있어서 / '경찰서'·'소방서'는 아님)
     *  - …ㄴ데 (급한데·막히는데·인데 / '롯데'는 아님)
     *  - …니까 · …지만 · …았/었다가 (들렀다가·갔다가 / '바다가'는 아님)
     */
    private fun isClauseEnd(t: String): Boolean {
        if (t.length < 2) return false
        val last = t.last()
        val prev = t[t.length - 2]
        return when {
            last == '고' -> isGoConnective(t)
            last == '서' -> Hangul.jongOf(prev) == 0
            last == '데' -> Hangul.jongOf(prev) == 4
            t.endsWith("니까") || t.endsWith("니깐") || t.endsWith("지만") -> t.length >= 3
            t.endsWith("다가") -> t.length >= 3 && Hangul.jongOf(t[t.length - 3]) == 20
            else -> false
        }
    }

    // ───────────────────────── 되묻기 대답 ─────────────────────────

    /** 대답 앞의 군말 (띄어쓰기 앞에서만): "음 2번", "어 아니야" — '네'는 '네 번째'의 번호일 수 있어 떼지 않는다 */
    private val fillerLead = Regex("""^(어+|음+|아+|저기요?|그+)[\s,.…~!]+""")
    /** 맞장구까지 포함한 말머리: "응 아니야", "네 본점" — 부정·이름 대답을 찾을 때만 뗀다 */
    private val replyLead = Regex("""^(어+|음+|아+|응|네|예|그래|맞아|좋아|저기요?|그+)[\s,.…~!]+""")

    private const val YES_WORD = "(응|웅|네에|네|넵|넹|예에|예스|예|그래요|그래|그러자|그러죠|그럽시다|그럼요|그럼|맞아요|맞아|맞습니다|맞지|맞네|" +
        "좋아요|좋아|좋습니다|좋지요|좋지|좋네|좋다|오케이|오키|ok|okay|yes|yeah|yep|ㅇㅇ|ㅇㅋ|그렇지|당연하지|당연|물론이죠|물론이지|물론|콜|고고)"
    /** 가리키는 말 — 확인 단계에서 "그거", "거기로"는 제안한 곳을 고른다는 뜻 */
    private const val DEMO = "(그걸로|그거로|그것으로|그거|그것|그걸|거기로|거기|그리로|그곳으로|그곳|그쪽으로|그쪽|저기로|그대로|이대로)"
    /**
     * 경유 동사 꼬리 ("들르자", "들러 줘", "들렀다 가자", "들러서 가 줘", "들를게요", "경유해 줘") — 경유지 질문("…에 들를까요?")에 대한 '네'.
     * HUD 버튼이 "응, 들르자"라서 말로도 그대로 대답한다. '들렀다'만으로는 넣지 않는다 (지난 일을 말하는 것일 수 있다).
     */
    private const val STOP = "(?:들렀다가?|들러서)(?:가자|가줘|가주세요|가주라|가줄래|가요|갈게|갈께|갈래|갑시다|가)" +
        "|들르자|들릅시다|들를게요|들를게|들를께|들를래|들러줘|들러주세요|들러줄래|들러주라|들러요|들러|경유해줘|경유해주세요|경유해|경유하자|경유"
    /** 경유 동사 앞의 부사 ("잠깐 들렀다 가자", "일단 들르자") — 경유 동사 앞에서만 받는다 ('그냥 가자'는 '네'가 아니다) */
    private const val STOP_ADV = "(?:잠깐|잠시|일단|먼저|우선|그냥|얼른|빨리|좀)"
    /** 대답 뒤에 붙는 동사 꼬리 ("네 가 주세요", "그렇게 해 줘", "출발", "가 보자", "응 들르자") */
    private const val GO = "(가자|가줘|가주세요|가주라|가보자|가봅시다|가보죠|가요|갑시다|가시죠|가죠|갈게|갈께|갈래|가|안내해줘|안내해주세요|안내해|안내시작|안내|" +
        "출발해줘|출발해주세요|출발해|출발하자|출발|시작해줘|시작해|시작|그렇게해줘|그렇게해주세요|그렇게하자|그렇게해|그렇게|해줘|해주세요|부탁해요|부탁해|부탁합니다|부탁|렛츠고|" +
        STOP + ")"
    /** 맞장구 뒤의 인사 ("응 고마워") — 인사만으로는 대답이 아니다 */
    private const val THANKS = "(고마워요|고마워|고맙습니다|고맙다|감사합니다|감사해요|감사|땡큐|thankyou|thanks)"
    private val yes = Regex(
        """^(어|음)?(?:$YES_WORD(요)?)+$DEMO?$GO*(요)?$THANKS?$|^(어|음)?$DEMO(요|이요|야)?$GO*(요)?$THANKS?$|^(어|음)?$GO+(요)?$THANKS?$""" +
            """|^(어|음)?(?:$YES_WORD(요)?)*$DEMO?$STOP_ADV+(?:$STOP)(요)?$THANKS?$""",
    )
    /** 말 끝의 경유 동사 꼬리 ("응 들르자", "2번 들러 줘") */
    private val stopEnd = Regex("""(?:$STOP)(요)?$THANKS?$""")
    /** 경유 동사 + 꼬리뿐인 말 ("들렀다 가자", "들러 줘") */
    private val stopOnly = Regex("""^(?:$STOP)(요)?$THANKS?$""")
    /** 경유 동사로 시작하는 낱말 ('들릅시다'·'들를게'까지) */
    private val stopStems = listOf("들렀", "들러", "들르", "들릅", "들를", "경유")

    /** 번호 말 → 0부터 시작하는 번호 */
    private val ordinalNumber: Map<String, Int> = mapOf(
        "첫" to 0, "한" to 0, "하나" to 0, "일" to 0,
        "두" to 1, "둘" to 1, "이" to 1,
        "세" to 2, "셋" to 2, "삼" to 2,
        "네" to 3, "넷" to 3, "사" to 3,
        "다섯" to 4, "오" to 4,
    )
    /** 단독으로 말해도 번호로 볼 수 있는 말 ('네'는 대답 '네'라서 빠진다) */
    private val bareOrdinals: Map<String, Int> = mapOf(
        "하나" to 0, "첫째" to 0, "둘" to 1, "둘째" to 1, "셋" to 2, "셋째" to 2, "넷" to 3, "넷째" to 3, "다섯" to 4, "다섯째" to 4,
    )
    /** 번호 뒤 꼬리: "2번이요", "두 번째 거요", "첫 번째 걸로", "1번 안내해 줘", "2번 갈래", "3번으로 들러 줘" */
    private const val PICK_TAIL = "(거|것|꺼|곳|데|걸)?(으로|로)?(이에요|예요|이요|요)?" +
        "(가자|가줘|가주세요|가요|가|갈래|갈게|갈께|할게|할께|할래|해줘|해주세요|해|안내해줘|안내해주세요|안내해|들러줘|들르자|들러|선택할게|선택|고를게|부탁해요|부탁해|부탁합니다)*(요)?"
    private val ordinal = Regex("""^(첫|한|하나|일|두|둘|이|세|셋|삼|네|넷|사|다섯|오|[1-9])(번째|번|째)$PICK_TAIL$""")
    private val bareOrdinal = Regex("""^(하나|첫째|둘째|둘|셋째|셋|넷째|넷|다섯째|다섯|[1-9])$PICK_TAIL$""")

    /**
     * 부정: "아니야"·"아냐"·"노노"·"잘못 들었어"류, "거기 말고"·"다른 데(로)"류, "그거 아니야"·"거기 아니고"류.
     * 조사(으로·로·에)는 '말고·다른 데' 무리에만 붙인다 ('아니 에버랜드'의 '에'를 조사로 떼지 않게).
     */
    private val no = Regex(
        """^(?:(아니야|아니요|아니에요|아녜요|아닙니다|아니라고|아니거든요|아니거든|아니지|아니|아냐요|아냐|아뇨|아닌데요|아닌데|틀렸어요|틀렸어|틀렸|싫어|노노|no(?![a-z])""" +
            """|잘못(?:알아)?들(?:으셨|었)(?:어요|어|네요|네|습니다|다|나봐요|나봐|는데요|는데)?)""" +
            """|((?:거기|그거|그곳|그쪽|이거|여기)?말고|(?:다른|딴)(?:데|곳|거))(?:으로|로|에)?""" +
            """|(?:그거|그게|그건|거기|거긴|그곳|그쪽|이거|이게|이건|여기|저거|저게|저기)(?:는|은|가|이)?""" +
            """(?:아니야|아니요|아니에요|아녜요|아닙니다|아니라고|아니라|아니고|아니지|아니|아냐|아닌데요|아닌데))""",
    )
    private val cancel = Regex("""^(취소|그만|됐어|됐다|됐습니다|안갈래|안가|가지마|나중에|필요없어|멈춰|중지|스톱|stop)""")
    /** "아니" 뒤에 동사 꼬리만 남은 말 ("아니 다른 데로 가자", "아니 들렀다 가자", "아니 잠깐 들렀다 가자") → 그냥 '아니야' */
    private val goOnly = Regex("""^$GO*(요)?$|^$STOP_ADV+(?:$STOP)(요)?$""")
    /** "잘 못 들었어"(못 알아들음 → 다시 묻기)는 "잘못 들었어"(오인식 → 부정)와 띄어쓰기로만 갈린다 */
    private val spacedMishear = Regex("""(?:^|\s)잘\s+못\s*(?:알아)?들""")

    /** 되묻기에 대한 대답 해석 */
    fun reply(utterance: String): Reply {
        val raw = utterance.trim()
        val raw0 = fillerLead.replace(raw, "")
        val n = norm(raw0)
        if (n.isEmpty()) return Reply.None
        if (cancel.containsMatchIn(n)) return Reply.Cancel
        ordinal.matchEntire(n)?.let { m ->
            val w = m.groupValues[1]
            return Reply.Pick(w.toIntOrNull()?.minus(1) ?: ordinalNumber.getValue(w))
        }
        bareOrdinal.matchEntire(n)?.let { m ->
            val w = m.groupValues[1]
            return Reply.Pick(w.toIntOrNull()?.minus(1) ?: bareOrdinals.getValue(w))
        }
        if (spacedMishear.containsMatchIn(raw0)) return Reply.None // [aside]가 '다시 묻기'로 받는다
        // "응 아니야"처럼 맞장구 뒤에 부정이 오면 부정으로 본다
        val noRaw = if (no.containsMatchIn(n)) raw0 else replyLead.replace(raw, "")
        val nNo = norm(noRaw)
        no.find(nNo)?.let { m ->
            var cut = m.range.last + 1
            // "아니 아니 …", "아니야 거기 말고 …", "아니 다른 데로 …" → 앞의 군더더기를 걷어 낸다
            while (true) {
                val again = no.find(nNo.substring(cut)) ?: break
                cut += again.range.last + 1
            }
            val rest = nNo.substring(cut)
            if (cancel.containsMatchIn(rest)) return Reply.Cancel // "아니 됐어"
            if (goOnly.matches(rest)) return Reply.No // "아니 다른 데로 가자"
            if (rest.length < 2) return Reply.No
            // 뒤의 말은 들린 그대로 돌려준다 ("아니 대흥동 성당 가자" → '대흥동 성당 가자' — 새 명령으로 다시 해석할 수 있게)
            return Reply.NoAnd(rawAfter(noRaw, cut).trim().trimStart(',', '.', '…', '~', '!', '?').trim().ifEmpty { rest })
        }
        if (yes.matches(n)) return Reply.Yes
        return Reply.None
    }

    /**
     * 대답이 '들러 가자'는 뜻인지 — 경유 동사로 끝나는 대답("응 들르자", "들렀다 가자", "2번 들러 줘")이나
     * 경유지 장소 명령("본점 들렀다 가자", "성심당 들렀다 가 줘"). 목적지를 묻던 중이면 경유지로 바꿔 듣는 데 쓴다.
     */
    fun asksStop(utterance: String): Boolean =
        stopEnd.containsMatchIn(norm(utterance)) || parse(utterance)?.mode == StopMode.WAYPOINT

    /**
     * 장소 없이 경유 동사만 말한 짧은 말인지 — "들렀다 가자", "응 들렀다 가자", "아니 들렀다 가자", "거기 들러서 가자".
     * 경유 동사 앞이 대답·부사·가리키는 말뿐이고 뒤가 동사 꼬리뿐일 때. 의도 엔진이 이런 대답을 여정 계획으로 보내지 않게 한다
     * ("스타벅스 들렀다 회사로 가자"·"다음 휴게소 들렀다 가자"처럼 앞에 장소가 있거나 뒤에 다른 말이 이어지면 아니다).
     */
    fun bareStopVerb(utterance: String): Boolean {
        if (stopStems.none { norm(utterance).contains(it) }) return false
        // 대답 문법 그대로 '네'·'아니야'로 읽히면 장소가 없다 ("응 들렀다 가자", "거기 들르자", "아니 들렀다 가자")
        val r = reply(utterance)
        if (r == Reply.Yes || r == Reply.No) return true
        val tokens = lead.replace(utterance.trim(), "").split(Regex("\\s+"))
            .map { it.trim(',', '.', '…', '~', '!', '?') }.filter { it.isNotEmpty() }
        val i = tokens.indexOfFirst { t -> stopStems.any { t.startsWith(it) } }
        if (i < 0) return false
        val nonPlace = { t: String ->
            val bare = trailingParticles.firstOrNull { p -> t.length > p.length && t.endsWith(p) }?.let { t.dropLast(it.length) }
            t in adverbWords || t in pointerWords || (bare != null && (bare in adverbWords || bare in pointerWords))
        }
        return tokens.take(i).all(nonPlace) && stopOnly.matches(norm(tokens.drop(i).joinToString("")))
    }

    /** [norm]이 남기는 글자를 [count]개 지난 뒤의 원문 (띄어쓰기·대소문자 그대로) */
    private fun rawAfter(raw: String, count: Int): String {
        var seen = 0
        var i = 0
        while (i < raw.length && seen < count) {
            val c = raw[i]
            if (!(c.isWhitespace() || c in ".,!?~·")) seen++
            i++
        }
        return raw.substring(i)
    }

    // ───────────────────────── 대답도 명령도 아닌 말 ─────────────────────────

    private val repeatAsk = Regex(
        """^(?:뭐라고(?:요|\s*했어요?|\s*하셨어요|\s*하셨죠|\s*했지)?|뭐요?|무슨\s*말이야""" +
            """|다시(?:\s*한\s*번)?(?:\s*(?:말해|말씀해|얘기해|이야기해)\s*(?:줘요|줘|줄래요|줄래|주세요|주실래요|봐요|봐)?)?""" +
            """|한\s*번\s*더(?:\s*(?:말해|말씀해|얘기해)\s*(?:줘요|줘|줄래요|줄래|주세요|주실래요)?)?""" +
            """|잘\s+못\s*(?:알아)?들었(?:어요|어|습니다|는데요|는데)?|못\s*(?:알아)?들었(?:어요|어|는데요|는데)?|(?:잘\s*)?안\s*들려(?:요)?)$""",
    )
    private val holdAsk = Regex(
        """^(?:잠깐(?:만요|만)?|잠시(?:만요|만)?|(?:잠깐\s*)?기다려(?:\s*봐)?(?:요)?|생각\s*좀\s*(?:해\s*)?볼게(?:요)?|생각\s*중(?:이야)?)$""",
    )
    private val fillerOnly = Regex("""^(?:어|음|아|으|흠|에|엄|흐음|으음|저|그|저기|저기요|있잖아|글쎄|글쎄요|뭐지|어디보자|그러니까|뭐더라)+$""")

    /**
     * 되묻는 중 대답([reply])도 명령도 아닌 말의 종류 — 다시 묻기("뭐라고?") · 기다리기("잠깐만") · 머뭇거림("어…").
     * 해당하지 않으면 null (동승자 대화 등).
     */
    fun aside(utterance: String): Aside? {
        val t = fillerLead.replace(utterance.trim(), "").trim().trim('.', ',', '!', '?', '~', '…', ' ')
        if (t.isEmpty()) return if (norm(utterance).replace("…", "").isEmpty()) null else Aside.FILLER
        if (repeatAsk.matches(t)) return Aside.REPEAT
        if (holdAsk.matches(t)) return Aside.HOLD
        if (fillerOnly.matches(norm(utterance).replace("…", ""))) return Aside.FILLER
        return null
    }

    // ───────────────────────── 이름 일부로 고르기 ─────────────────────────

    /** 끝에 붙는 군말: "역에 있는 거", "판교 쪽", "본점 그거" */
    private val optionFiller = Regex("""(\s*(에\s*)?(있는|있던)\s*(거|것|꺼|곳|데)?|\s+(거|것|꺼|곳|데|쪽|그거)|쪽)$""")
    private val optionEnding = Regex("""(이에요|예요|이요|요)$""")
    private val optionParticle = Regex("""(으로|로)$""")

    /** 가리키는 말 — 이름 일부가 아니다 (확인 단계에서는 [reply]가 '네'로 받는다) */
    private val demonstrative = Regex("""^(그|이|저|요)(거|것|걸|곳|쪽|기|데)(로|으로)?$|^(거기|여기|저기)(로)?$""")
    /** 장소 이름 일부로 볼 수 없는 군말·딴말 */
    private val fillerWords = setOf(
        "다시", "이전", "아까", "뭐", "뭐라고", "뭐야", "몰라", "글쎄", "잠깐", "잠시만", "음", "어", "아", "그", "저",
        "응", "네", "예", "좀", "빨리", "천천히", "모르겠어", "뭐였지", "어디", "어디야", "여기", "저기", "거기",
    )

    /**
     * "본점", "역에 있는 거", "DCC점으로 가자"처럼 후보 이름 일부를 말했을 때 가장 그럴듯한 후보.
     * 이름 전체 안의 아무 부분이 아니라 '지점 이름 단어 · 별칭 · 이름 전체'와 비교한다
     * (군말 '그거요'·'빨리'·'노래'가 '판교테크노밸리' 같은 긴 이름의 일부에 붙지 않게).
     */
    fun matchOption(utterance: String, options: List<Candidate>): Candidate? {
        val n = norm(optionCore(utterance))
        if (n.isEmpty() || n in fillerWords || demonstrative.matches(n)) return null
        // "판교점"은 '판교테크노밸리점'의 앞머리 '판교' + 지점 꼬리 '점'
        val forms = listOfNotNull(n, n.dropLast(1).takeIf { n.length >= 2 && n.endsWith("점") })
        val scored = options.map { c -> c to (optionKeys(c).maxOfOrNull { k -> forms.maxOf { f -> optionScore(f, k) } } ?: 0.0) }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0.0
        return if (best.second >= OPTION_MIN && best.second - second >= OPTION_MARGIN) best.first else null
    }

    private const val OPTION_MIN = 0.8
    private const val OPTION_MARGIN = 0.08

    /** 대답 끝의 맞장구 — "성심당 맞아", "본점 그래", "본점이야" */
    private val trailingYes = Regex("""\s*(맞아요|맞아|맞지|맞네|맞습니다|그래요|그래|좋아요|좋아|이야|거야|이에요|예요)$""")

    /**
     * 대답이 이 후보를 부르는지 — 브랜드만 말해도 된다 ("성심당", "응 성심당", "성심당 맞아").
     * 확인·되돌리기 단계에서 제안한 곳의 이름을 다시 말하면 '네'로 받는 데 쓴다
     * (여러 지점 중 어느 곳인지는 [matchOption]이 먼저 가린다).
     */
    fun namesOption(utterance: String, cand: Candidate): Boolean {
        var t = optionCore(utterance)
        repeat(2) {
            t = trailingYes.replace(t, "").trim()
            t = optionEnding.replace(t, "").trim()
        }
        val n = norm(t)
        if (n.isEmpty() || n in fillerWords || demonstrative.matches(n)) return false
        val forms = listOfNotNull(n, n.dropLast(1).takeIf { n.length >= 2 && n.endsWith("점") })
        // 다른 후보와 견주지 않으므로 더 엄격하게: 한 음절 끝 일치('점'·'당')는 이름을 부른 것이 아니다
        return optionKeys(cand).any { k -> forms.any { f -> optionScore(f, k) >= NAMES_MIN } }
    }

    private const val NAMES_MIN = 0.9

    /** 대답에서 장소 말만 남긴다: 동사 꼬리·조사(parse) → 앞 군말 → 끝 군말·'요'·'으로' */
    private fun optionCore(utterance: String): String {
        var t = parse(utterance)?.text ?: utterance.trim()
        t = replyLead.replace(t, "")
        t = t.trim().trimEnd('.', ',', '!', '?', '~', '…').trim()
        repeat(2) {
            t = optionEnding.replace(t, "").trim()
            t = optionFiller.replace(t, "").trim()
            if (t.length > 2) t = optionParticle.replace(t, "").trim()
        }
        return t
    }

    /** 후보를 부를 수 있는 말들: 이름 전체 · 브랜드 · 별칭 · 이름의 단어(브랜드 제외) · 끝의 '점'을 뗀 단어 */
    private fun optionKeys(c: Candidate): Set<String> {
        val poi = c.poi
        val brand = poi.brand?.let(::norm)
        val keys = LinkedHashSet<String>()
        keys += norm(poi.spoken)
        brand?.let { keys += it }
        poi.aliases.forEach { keys += norm(it) }
        val words = (listOf(poi.spoken) + poi.aliases.filter { a -> brand != null && norm(a).startsWith(brand) }).flatMap { it.split(' ') }
        for (w in words) {
            val k = norm(w)
            if (k.isEmpty() || k == brand) continue
            keys += k
            if (k.length >= 2 && k.endsWith("점")) keys += k.dropLast(1)
        }
        keys.remove("")
        return keys
    }

    /**
     * 대답 n과 부르는 말 k의 일치 점수 (0~1):
     *  - 글자가 같으면 1, 글자 그대로 앞머리·끝부분이면 0.9 (한 음절이면 0.85: "역" ↔ "대전역", "판교" ↔ "판교테크노밸리")
     *  - 2음절 이상이면 소리 전체 비교, 3음절 이상이면 앞머리·끝부분 소리 비교도 (음성인식 오류 대비)
     */
    private fun optionScore(n: String, k: String): Double {
        if (n == k) return 1.0
        val units = n.count { it.isLetterOrDigit() }
        var s = 0.0
        if (k.startsWith(n) || k.endsWith(n)) s = if (units >= 2) 0.9 else 0.85
        if (units >= 2) {
            val whole = Hangul.wholeSimilarity(n, k)
            // 2음절 대답은 더 엄격하게 ('빨리' ↔ '밸리' 같은 우연한 소리 일치 방지)
            if (units >= 3 || whole >= 0.88) s = maxOf(s, whole)
        }
        if (units >= 3) s = maxOf(s, 0.95 * Hangul.edgeSimilarity(n, k))
        return s
    }
}
