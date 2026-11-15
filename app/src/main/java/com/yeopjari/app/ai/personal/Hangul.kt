package com.yeopjari.app.ai.personal

import kotlin.math.min

/**
 * 한국어 '소리' 단위 비교 — 음성인식 오류 보정의 첫 단계.
 *
 * 음성인식은 글자가 아니라 비슷한 '소리'로 틀린다(성심당 → 서심다응).
 * 그래서 음절을 초성·중성·종성 자모로 풀고 받침은 실제 발음되는 대표음으로 바꾼 뒤,
 * 소리가 비슷한 자모끼리는 바꿔도 비용을 적게 매기는 편집 거리로 비교한다.
 * 장소 이름 앞뒤에 남는 부분(지점명 등)은 비용 없이 건너뛴다(부분 정렬).
 *
 * 네트워크·모델 없이 수 밀리초 안에 끝나는 순수 계산이라 차 안에서 항상 동작한다.
 */
object Hangul {
    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"

    /** 종성 → 대표음 (받침 발음 규칙: ㄲㅋ→ㄱ, ㅅㅆㅈㅊㅌㅎ→ㄷ, ㅍ→ㅂ, 겹받침은 대표음 하나) */
    private val JONG = arrayOf(
        "", "ㄱ", "ㄱ", "ㄱ", "ㄴ", "ㄴ", "ㄴ", "ㄷ", "ㄹ", "ㄱ", "ㅁ", "ㄹ", "ㄹ", "ㄹ",
        "ㅂ", "ㄹ", "ㅁ", "ㅂ", "ㅂ", "ㄷ", "ㄷ", "ㅇ", "ㄷ", "ㄷ", "ㄱ", "ㄷ", "ㅂ", "ㄷ",
    )

    /** 소리가 비슷한 자모 묶음과 서로 바꿀 때의 비용 (기본 1.0) */
    private val SIMILAR: List<Pair<String, Double>> = listOf(
        "ㅐㅔ" to 0.2, "ㅒㅖ" to 0.2, "ㅙㅞㅚ" to 0.2, "ㅢㅣ" to 0.3, "ㅢㅡ" to 0.4,
        "ㅓㅗ" to 0.5, "ㅡㅜ" to 0.5, "ㅕㅛ" to 0.5, "ㅝㅓ" to 0.5, "ㅘㅏ" to 0.5, "ㅟㅣ" to 0.5,
        "ㅏㅑ" to 0.6, "ㅜㅠ" to 0.6, "ㅐㅏ" to 0.6, "ㅔㅓ" to 0.6,
        "ㄱㄲㅋ" to 0.4, "ㄷㄸㅌ" to 0.4, "ㅂㅃㅍ" to 0.4, "ㅈㅉㅊ" to 0.4, "ㅅㅆ" to 0.3,
        "ㄷㅅ" to 0.5, "ㄴㄹ" to 0.5, "ㄴㅁ" to 0.6, "ㅁㅂ" to 0.6, "ㅇㄴ" to 0.6, "ㅈㅅ" to 0.6,
    )

    private val subCost = HashMap<Long, Double>().apply {
        for ((group, cost) in SIMILAR) for (a in group) for (b in group) if (a != b) {
            val k = key(a, b)
            put(k, min(get(k) ?: 1.0, cost))
        }
    }

    private fun key(a: Char, b: Char): Long = (a.code.toLong() shl 16) or b.code.toLong()
    private fun sub(a: Char, b: Char): Double = if (a == b) 0.0 else subCost[key(a, b)] ?: 1.0

    /** 잘 끼어들거나 빠지는 소리(ㅡ 모음 삽입, 받침 ㅇ·ㅎ 탈락)는 삽입·삭제 비용이 작다 */
    private fun indel(c: Char): Double = when (c) { 'ㅡ', 'ㅇ', 'ㅎ' -> 0.5; else -> 1.0 }

    /** 문자열 → 발음 자모열. 초성 ㅇ(소리 없음)은 버리고 공백·문장부호는 무시한다. */
    fun phonemes(s: String): String {
        val sb = StringBuilder(s.length * 3)
        for (ch in s) {
            val c = ch.code
            when {
                c in BASE..LAST -> {
                    val i = c - BASE
                    val cho = CHO[i / 588]
                    if (cho != 'ㅇ') sb.append(cho)
                    sb.append(JUNG[(i % 588) / 28])
                    sb.append(JONG[i % 28])
                }
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 질의 q 전체를 이름 t에 맞출 때의 최소 편집 비용.
     * [freeStart]·[freeEnd]: 이름 앞·뒤에 남는 부분을 비용 없이 건너뛸지.
     * 둘 다 true면 '이름 안의 연속 구간'(예: "성심당" ↔ "성심당 대전역점"), 둘 다 false면 전체 대 전체.
     */
    private fun alignDistance(q: String, t: String, freeStart: Boolean, freeEnd: Boolean): Double {
        val m = q.length
        val n = t.length
        var prev = DoubleArray(n + 1)
        if (!freeStart) for (j in 1..n) prev[j] = prev[j - 1] + indel(t[j - 1])
        var cur = DoubleArray(n + 1)
        for (i in 1..m) {
            cur[0] = prev[0] + indel(q[i - 1])
            for (j in 1..n) {
                val diag = prev[j - 1] + sub(q[i - 1], t[j - 1])
                val del = prev[j] + indel(q[i - 1])
                val ins = cur[j - 1] + indel(t[j - 1])
                cur[j] = minOf(diag, del, ins)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return if (freeEnd) prev.min() else prev[n]
    }

    /** 0~1 발음 유사도. 1 = 들린 말이 이름(의 일부)과 소리가 같음. */
    fun similarity(spoken: String, name: String): Double {
        val q = phonemes(spoken)
        val t = phonemes(name)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        val d = alignDistance(q, t, freeStart = true, freeEnd = true)
        return (1.0 - d / q.length).coerceIn(0.0, 1.0)
    }

    /**
     * 0~1 전체 대 전체 발음 유사도 — 이름 안의 일부와 맞추지 않는다.
     * 짧은 대답("본점")을 지점 이름의 단어와 비교할 때 쓴다 ('빨리'가 '…밸리'에 붙는 일 없게).
     */
    fun wholeSimilarity(a: String, b: String): Double {
        val q = phonemes(a)
        val t = phonemes(b)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        val d = alignDistance(q, t, freeStart = false, freeEnd = false)
        return (1.0 - d / maxOf(q.length, t.length)).coerceIn(0.0, 1.0)
    }

    /** 0~1 — 들린 말이 이름의 앞머리나 끝부분과 같은 소리인지 ("판교" ↔ "판교테크노밸리"). 가운데 일부는 보지 않는다. */
    fun edgeSimilarity(spoken: String, name: String): Double {
        val q = phonemes(spoken)
        val t = phonemes(name)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        val d = minOf(alignDistance(q, t, freeStart = false, freeEnd = true), alignDistance(q, t, freeStart = true, freeEnd = false))
        return (1.0 - d / q.length).coerceIn(0.0, 1.0)
    }

    /** 한 음절의 받침 번호 (0 = 받침 없음, 4 = ㄴ, 20 = ㅆ …). 한글 음절이 아니면 -1 */
    fun jongOf(ch: Char): Int = if (ch.code in BASE..LAST) (ch.code - BASE) % 28 else -1

    /** 마지막 글자에 받침이 있으면 '으로', 없거나 ㄹ 받침이면 '로' */
    fun ro(word: String): String {
        val last = word.trim().lastOrNull { it.code in BASE..LAST || it.isLetterOrDigit() } ?: return "로"
        if (last.code !in BASE..LAST) return "로"
        val jong = (last.code - BASE) % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }

    /** 마지막 글자에 받침이 있는지 (한글이 아니면 false) */
    fun hasBatchim(word: String): Boolean {
        val last = word.trim().lastOrNull { it.code in BASE..LAST || it.isLetterOrDigit() } ?: return false
        if (last.code !in BASE..LAST) return false
        return (last.code - BASE) % 28 != 0
    }

    /** 은/는 */
    fun eunNeun(word: String): String = if (hasBatchim(word)) "은" else "는"

    /** 이/가 */
    fun iGa(word: String): String = if (hasBatchim(word)) "이" else "가"

    /** 을/를 */
    fun eulReul(word: String): String = if (hasBatchim(word)) "을" else "를"

    /** 와/과 */
    fun waGwa(word: String): String = if (hasBatchim(word)) "과" else "와"

    /** 사람·반려동물 이름의 부르는 꼴 — 받침 있는 이름은 '하율이'처럼 '이'를 붙인다 (뒤에 가·를·와가 온다) */
    fun nameStem(name: String): String = if (hasBatchim(name)) "${name}이" else name

    /** 사람 이름 + 이/가 — 받침 있는 이름은 '하율이가'처럼 '이'를 붙인다 */
    fun nameSubject(name: String): String = "${nameStem(name)}가"

    /** 부를 때 붙이는 아/야 — '하율아', '콩이야' */
    fun aYa(name: String): String = if (hasBatchim(name)) "아" else "야"
}
