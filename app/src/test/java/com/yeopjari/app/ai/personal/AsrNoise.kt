package com.yeopjari.app.ai.personal

import java.util.Random

/**
 * 음성인식 오류 흉내 — 장소 이름을 '비슷한 소리'로 틀리게 만든다 (같은 Random 상태 → 같은 결과).
 *
 * 자모 단위 규칙만 쓴다:
 *  - 모음 혼동: ㅐ↔ㅔ, ㅓ↔ㅗ, ㅡ↔ㅜ, ㅕ↔ㅛ, ㅙ↔ㅚ↔ㅞ
 *  - 받침 탈락(특히 ㅇ: 성 → 서) · 받침 추가(주로 ㅇ: 타 → 탕)
 *  - ㅡ 끼어듦: 받침이 '으+받침' 음절로 떨어져 나감 (당 → 다응, 심 → 시음)
 *  - 예사·된·거센소리 바꿈: ㄱ↔ㄲ↔ㅋ, ㄷ↔ㄸ↔ㅌ, ㅂ↔ㅃ↔ㅍ, ㅈ↔ㅉ↔ㅊ, ㅅ↔ㅆ
 *  - 띄어쓰기 끼어듦 (소리 비교·검색 정규화에는 영향 없음)
 * 질의마다 소리 오류 1개(60%) 또는 2개(40%)를 넣고, 결과는 항상 올바른 한글 음절(가~힣)로 다시 조립한다.
 *
 * 실제 차량 STT의 오류 통계를 흉내 낸 것이 아니라 '소리가 비슷한 쪽으로 틀린다'는 가정만 반영한 규칙이다.
 */
internal object AsrNoise {
    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3
    private const val CHO_SILENT = 11 // 초성 ㅇ
    private const val JUNG_EU = 18 // ㅡ
    private const val JONG_NG = 21 // 받침 ㅇ
    private const val JONG_N = 4 // 받침 ㄴ
    private const val JONG_M = 16 // 받침 ㅁ

    private val vowelSwap: Map<Int, IntArray> = mapOf(
        1 to intArrayOf(5), 5 to intArrayOf(1), // ㅐ ↔ ㅔ
        4 to intArrayOf(8), 8 to intArrayOf(4), // ㅓ ↔ ㅗ
        18 to intArrayOf(13), 13 to intArrayOf(18), // ㅡ ↔ ㅜ
        6 to intArrayOf(12), 12 to intArrayOf(6), // ㅕ ↔ ㅛ
        10 to intArrayOf(11, 15), 11 to intArrayOf(10, 15), 15 to intArrayOf(10, 11), // ㅙ ㅚ ㅞ
    )

    private val choGroups: List<IntArray> = listOf(
        intArrayOf(0, 1, 15), // ㄱ ㄲ ㅋ
        intArrayOf(3, 4, 16), // ㄷ ㄸ ㅌ
        intArrayOf(7, 8, 17), // ㅂ ㅃ ㅍ
        intArrayOf(12, 13, 14), // ㅈ ㅉ ㅊ
        intArrayOf(9, 10), // ㅅ ㅆ
    )

    /** ㅡ 끼어듦으로 떨어져 나가기 쉬운 받침: ㅇ ㄴ ㅁ ㄹ */
    private val splittable = setOf(JONG_NG, JONG_N, JONG_M, 8)

    enum class Op(val weight: Double) { VOWEL(0.30), JONG_DROP(0.20), JONG_ADD(0.12), EU_INSERT(0.18), TENSE(0.20) }

    private class Tok(val raw: Char?, var cho: Int = 0, var jung: Int = 0, var jong: Int = 0) {
        val syllable: Boolean get() = raw == null
    }

    private fun tokenize(s: String): MutableList<Tok> = s.map { ch ->
        val c = ch.code
        if (c in BASE..LAST) {
            val i = c - BASE
            Tok(null, i / 588, (i % 588) / 28, i % 28)
        } else Tok(ch)
    }.toMutableList()

    private fun compose(t: List<Tok>): String = buildString {
        for (k in t) append(k.raw ?: (BASE + (k.cho * 21 + k.jung) * 28 + k.jong).toChar())
    }

    private fun applicable(op: Op, t: List<Tok>): List<Int> = t.indices.filter { i ->
        val k = t[i]
        k.syllable && when (op) {
            Op.VOWEL -> k.jung in vowelSwap
            Op.JONG_DROP -> k.jong != 0
            Op.JONG_ADD -> k.jong == 0
            Op.EU_INSERT -> k.jong in splittable
            Op.TENSE -> choGroups.any { g -> k.cho in g }
        }
    }

    private fun pickOp(r: Random): Op {
        val total = Op.entries.sumOf { it.weight }
        var x = r.nextDouble() * total
        for (op in Op.entries) {
            x -= op.weight
            if (x < 0) return op
        }
        return Op.TENSE
    }

    private fun pickPosition(op: Op, idx: List<Int>, t: List<Tok>, r: Random): Int {
        if (op != Op.JONG_DROP) return idx[r.nextInt(idx.size)]
        // 받침 탈락은 ㅇ 받침에서 가장 흔하다 (가중치 3:1)
        val w = idx.map { if (t[it].jong == JONG_NG) 3.0 else 1.0 }
        var x = r.nextDouble() * w.sum()
        for ((k, i) in idx.withIndex()) {
            x -= w[k]
            if (x < 0) return i
        }
        return idx.last()
    }

    private fun apply(op: Op, t: MutableList<Tok>, i: Int, r: Random) {
        val k = t[i]
        when (op) {
            Op.VOWEL -> vowelSwap.getValue(k.jung).let { alt -> k.jung = alt[r.nextInt(alt.size)] }
            Op.JONG_DROP -> k.jong = 0
            Op.JONG_ADD -> k.jong = when (r.nextInt(10)) { in 0..6 -> JONG_NG; 7, 8 -> JONG_N; else -> JONG_M }
            Op.EU_INSERT -> {
                val j = k.jong
                k.jong = 0
                t.add(i + 1, Tok(null, CHO_SILENT, JUNG_EU, j))
            }
            Op.TENSE -> {
                val alt = choGroups.first { g -> k.cho in g }.filter { it != k.cho }
                k.cho = alt[r.nextInt(alt.size)]
            }
        }
    }

    private fun insertSpace(t: MutableList<Tok>, r: Random) {
        val gaps = (0 until t.size - 1).filter { t[it].syllable && t[it + 1].syllable }
        if (gaps.isNotEmpty()) t.add(gaps[r.nextInt(gaps.size)] + 1, Tok(' '))
    }

    /** 오류 세기 실험용 — 소리 오류를 정확히 [ops]개 넣는다 (띄어쓰기 끼어듦 없음). */
    fun perturbExactly(s: String, ops: Int, r: Random): String {
        if (ops <= 0) return s
        repeat(30) {
            val t = tokenize(s)
            var done = 0
            var guard = 0
            while (done < ops && guard++ < 80) {
                val op = pickOp(r)
                val idx = applicable(op, t)
                if (idx.isEmpty()) continue
                apply(op, t, pickPosition(op, idx, t, r), r)
                done++
            }
            val out = compose(t)
            if (QueryParser.norm(out) != QueryParser.norm(s)) return out
        }
        return s
    }

    /** [s]를 소리 오류 1~2개가 섞인 말로 바꾼다. 소리가 실제로 달라질 때까지 다시 뽑는다. */
    fun perturb(s: String, r: Random): String {
        repeat(30) {
            val t = tokenize(s)
            val ops = if (r.nextDouble() < 0.6) 1 else 2
            var done = 0
            var guard = 0
            while (done < ops && guard++ < 40) {
                val op = pickOp(r)
                val idx = applicable(op, t)
                if (idx.isEmpty()) continue
                apply(op, t, pickPosition(op, idx, t, r), r)
                done++
            }
            if (r.nextDouble() < 0.25) insertSpace(t, r)
            val out = compose(t)
            if (QueryParser.norm(out) != QueryParser.norm(s)) return out
        }
        return s
    }
}
