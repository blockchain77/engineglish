package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.AlignOp
import com.yeopjari.app.english.core.WordAlign
import kotlin.math.abs
import kotlin.math.max

/*
 * 말하기 모듈 공용 도우미 (순수 Kotlin, JVM 테스트 대상).
 * 한글 판별 · 단어 정렬(가중 Levenshtein) · 형태 변화(-s/-ed/-ing) · 동음이의어 · 자기 수정(I mean…) 후보.
 */

internal object SpeechText {
    fun isHangul(c: Char): Boolean = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
    fun hasHangul(s: String): Boolean = s.any { isHangul(it) }

    /** 화면·음성 피드백용 따옴표 ‘word’ */
    fun quote(w: String): String = "‘$w’"

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 글자 유사도 0..1 */
    fun similarity(a: String, b: String): Double {
        val m = max(a.length, b.length)
        return if (m == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / m
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }
}

/** STT 가 자주 바꿔 쓰는 동음이의어 — 정렬에서 같은 단어로 본다 */
internal object Homophones {
    private val GROUPS: List<Set<String>> = listOf(
        setOf("right", "write", "rite"), setOf("two", "to", "too"), setOf("there", "their"), setOf("for", "four"),
        setOf("by", "buy", "bye"), setOf("one", "won"), setOf("sea", "see"), setOf("hear", "here"),
        setOf("new", "knew"), setOf("meet", "meat"), setOf("week", "weak"), setOf("whole", "hole"), setOf("plane", "plain"),
        setOf("wait", "weight"), setOf("road", "rode"), setOf("flour", "flower"), setOf("pair", "pear"), setOf("son", "sun"),
        setOf("tail", "tale"), setOf("mail", "male"), setOf("sale", "sail"), setOf("hour", "our"), setOf("night", "knight"),
        setOf("break", "brake"), setOf("fare", "fair"), setOf("way", "weigh"), setOf("piece", "peace"),
        setOf("cent", "sent", "scent"), setOf("ate", "eight"), setOf("be", "bee"), setOf("dear", "deer"),
    )
    private val index: Map<String, Int> = buildMap {
        GROUPS.forEachIndexed { i, g -> g.forEach { w -> put(w, i) } }
    }

    fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val ia = index[a] ?: return false
        return ia == index[b]
    }
}

/** 형태 변화 (-s, -es, -ed, -ing, -ies) — 키워드 일치·부분 점수용 */
internal object Morph {
    private fun doubled(s: String): Boolean = s.length >= 2 && s[s.length - 1] == s[s.length - 2] && s.last() !in "aeiou"

    fun stems(w: String): Set<String> {
        val out = HashSet<String>()
        out += w
        fun add(s: String) { if (s.length >= 3) out += s }
        if (w.endsWith("ies")) add(w.dropLast(3) + "y")
        if (w.endsWith("es")) add(w.dropLast(2))
        if (w.endsWith("s") && !w.endsWith("ss")) add(w.dropLast(1))
        if (w.endsWith("ied")) add(w.dropLast(3) + "y")
        if (w.endsWith("ed")) {
            val b = w.dropLast(2)
            add(b); add(b + "e")
            if (doubled(b)) add(b.dropLast(1))
        }
        if (w.endsWith("ed")) add(w.dropLast(1)) // liked → like (card → car 는 아님)
        if (w.endsWith("ing")) {
            val b = w.dropLast(3)
            add(b); add(b + "e")
            if (doubled(b)) add(b.dropLast(1))
        }
        return out
    }

    /** 같은 낱말의 다른 형태인가 (meeting ↔ meetings, push ↔ pushed) */
    fun variant(a: String, b: String): Boolean {
        if (a == b) return false
        val sa = stems(a)
        return stems(b).any { it in sa }
    }

    /** 키워드 일치 — 같거나, 동음이의어거나, 형태 변화 */
    fun matches(token: String, keyword: String): Boolean =
        token == keyword || Homophones.same(token, keyword) || variant(token, keyword)
}

/** 단어 단위 가중 Levenshtein 정렬 (명세 §5.2) — 기능어 a/an/the/to/please 는 0.5 */
internal object WordAligner {
    private const val EPS = 1e-9
    private val FUNCTION_WORDS = setOf("a", "an", "the", "to", "please")

    fun weight(w: String): Double = if (w in FUNCTION_WORDS) 0.5 else 1.0

    class Result(val ops: List<WordAlign>, val errors: Double, val expectedWeight: Double) {
        val score: Int get() = scoreOf(errors, expectedWeight)
    }

    fun scoreOf(errors: Double, expectedWeight: Double): Int =
        if (expectedWeight <= 0.0) 0
        else Math.round(100.0 * (1.0 - errors / expectedWeight)).toInt().coerceIn(0, 100)

    /** 치환 비용. 같은 말·동음이의어 0, 형태 변화 절반, 글자가 비슷할수록 조금 싸게 (정렬이 비슷한 말끼리 짝짓도록) */
    fun subCost(e: String, h: String): Double {
        if (Homophones.same(e, h)) return 0.0
        val base = max(weight(e), weight(h))
        if (Morph.variant(e, h)) return base * 0.5
        return base * (1.0 - 0.15 * SpeechText.similarity(e, h))
    }

    fun align(expected: List<String>, heard: List<String>): Result {
        val n = expected.size
        val m = heard.size
        val cost = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) cost[i][0] = cost[i - 1][0] + weight(expected[i - 1])
        for (j in 1..m) cost[0][j] = cost[0][j - 1] + weight(heard[j - 1])
        for (i in 1..n) {
            for (j in 1..m) {
                val diag = cost[i - 1][j - 1] + subCost(expected[i - 1], heard[j - 1])
                val del = cost[i - 1][j] + weight(expected[i - 1])
                val ins = cost[i][j - 1] + weight(heard[j - 1])
                cost[i][j] = minOf(diag, del, ins)
            }
        }
        val ops = ArrayList<WordAlign>(n + m)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val e = expected[i - 1]
                val h = heard[j - 1]
                val sc = subCost(e, h)
                if (abs(cost[i][j] - (cost[i - 1][j - 1] + sc)) < EPS) {
                    ops += WordAlign(e, h, if (sc == 0.0) AlignOp.MATCH else AlignOp.SUB)
                    i--; j--
                    continue
                }
            }
            if (i > 0 && (j == 0 || abs(cost[i][j] - (cost[i - 1][j] + weight(expected[i - 1]))) < EPS)) {
                ops += WordAlign(expected[i - 1], null, AlignOp.DEL)
                i--
                continue
            }
            ops += WordAlign(null, heard[j - 1], AlignOp.INS)
            j--
        }
        ops.reverse()
        return Result(ops, cost[n][m], expected.sumOf { weight(it) })
    }
}

/**
 * 자기 수정 — "I'm running lately… I mean, I'm running late." 처럼 고쳐 말하면 마지막 시도를 채점한다.
 * 후보: 전체 발화, 마지막 수정 표지(I mean·sorry·wait…) 뒤, 문장 첫머리를 다시 시작한 곳(앞 5단어 이내) 뒤.
 * 채점기는 후보 중 가장 잘 맞는 것을 고르고, 같은 점수면 뒤쪽(마지막 시도)을 고른다.
 */
internal object SelfCorrection {
    data class Attempt(val tokens: List<String>, val start: Int, val end: Int)

    private const val MAX_FALSE_START = 5
    /** 정답 첫 낱말로 다시 시작한 경우 버릴 수 있는 앞말 (Can I, could we …) */
    private const val MAX_ANSWER_RESTART = 3

    private val MARKERS: List<List<String>> = listOf(
        listOf("let", "me", "say", "that", "again"), listOf("let", "me", "say", "it", "again"),
        listOf("let", "me", "try", "again"), listOf("let", "me", "start", "again"), listOf("let", "me", "start", "over"),
        listOf("scratch", "that"), listOf("or", "rather"), listOf("i", "mean"), listOf("i", "meant"),
        listOf("no", "wait"), listOf("no", "no"), listOf("sorry"), listOf("wait"), listOf("oops"), listOf("correction"),
    )

    fun attempts(t: List<String>): List<Attempt> {
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<Attempt>()
        out += Attempt(t, 0, t.size)
        val cuts = markerCuts(t)
        if (cuts.isNotEmpty()) {
            val (ms, me) = cuts.last()
            if (me < t.size) {
                out += Attempt(t.subList(me, t.size).toList(), me, t.size)
            } else {
                // 끝에 붙은 표지("…tomorrow, sorry") → 그 앞 구간
                val prevEnd = if (cuts.size >= 2) cuts[cuts.size - 2].second else 0
                if (ms > prevEnd) out += Attempt(t.subList(prevEnd, ms).toList(), prevEnd, ms)
            }
        }
        out += restartsAt(t, t.first(), MAX_FALSE_START)
        return out.distinctBy { it.start to it.end }
    }

    /** 정답 첫 낱말([word])로 다시 시작한 곳부터 (버리는 앞말은 3단어까지) */
    fun answerRestarts(t: List<String>, word: String): List<Attempt> = restartsAt(t, word, MAX_ANSWER_RESTART)

    /** 앞 [maxDrop] 단어 안에서 [word] 로 다시 시작한 마지막 위치부터 */
    private fun restartsAt(t: List<String>, word: String, maxDrop: Int): List<Attempt> {
        val limit = minOf(maxDrop, t.size - 1)
        for (i in limit downTo 1) {
            if (t[i] == word) return listOf(Attempt(t.subList(i, t.size).toList(), i, t.size))
        }
        return emptyList()
    }

    private fun markerCuts(t: List<String>): List<Pair<Int, Int>> {
        val cuts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < t.size) {
            val mk = MARKERS.firstOrNull { m -> i + m.size <= t.size && m.indices.all { k -> t[i + k] == m[k] } }
            if (mk != null) {
                cuts += i to (i + mk.size)
                i += mk.size
            } else {
                i++
            }
        }
        return cuts
    }
}
