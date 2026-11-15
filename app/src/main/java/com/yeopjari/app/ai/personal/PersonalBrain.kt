package com.yeopjari.app.ai.personal

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 개인화 보정 엔진 — 음성인식이 준 텍스트를 '이 사람이 지금 말했을 법한 장소'로 바꾼다.
 *
 *     P(장소 | 들린 말, 나, 지금) ∝ exp( w · [발음, 자주, 시간대, 거리, 최근, 선호] )
 *
 *  - 발음: 들린 말과 장소 이름의 '소리' 비교 ([Hangul]) — 이게 없으면 무슨 말을 해도 자주 가는 곳으로 끌려간다
 *  - 자주·시간대·거리·최근·선호: 차 안에만 저장된 방문 기록에서 계산 (서버로 보내지 않음)
 *  - 가중치 w (숫자 6개): 사용자가 고를 때마다 차 안에서 바로 갱신 (조건부 로짓의 경사 상승 1스텝)
 *
 * 방문 기록은 모델 가중치에 외우게 하지 않고 '수첩'(DB)으로 둔다 — 새 장소가 바로 반영되고,
 * 사용자가 지우라고 하면 그 자리에서 지울 수 있으며, 없는 장소를 지어내지 않는다.
 */
class PersonalBrain(
    pois: List<Poi> = PersonaFactory.pois,
    visits: List<Visit> = PersonaFactory.visits(),
) {
    var pois: List<Poi> = pois
        private set
    var visits: List<Visit> = visits
        private set

    var weights: DoubleArray = DEFAULT_WEIGHTS.copyOf()
    var prevWeights: DoubleArray = DEFAULT_WEIGHTS.copyOf()

    /** 들린 표현(공백 제거) → 브랜드 또는 장소 id. 예: "서심다응" → "성심당" */
    val aliases: LinkedHashMap<String, String> = LinkedHashMap()

    /** 들린 표현 → (그 말로 실제 고른 장소 id → 횟수) — 요일 구분 없는 전체 횟수 (요일을 모르는 옛 기록 포함) */
    val choiceMemory: LinkedHashMap<String, LinkedHashMap<String, Int>> = LinkedHashMap()

    /**
     * 들린 표현 → (장소 id → [평일 횟수, 주말 횟수]).
     * 같은 말도 평일과 주말에 가리키는 곳이 다를 수 있어서(평일 '스타벅스' = 판교, 주말 = 잠실) 나눠 센다.
     */
    val choiceByDay: LinkedHashMap<String, LinkedHashMap<String, IntArray>> = LinkedHashMap()

    val favorites: MutableSet<String> = linkedSetOf()
    var learnCount: Int = 0

    fun replaceVisits(v: List<Visit>) { visits = v }

    /** 실차 데이터(내비 최근 목적지·즐겨찾기·도착)로 장소 사전을 넓힐 때 */
    fun replacePois(p: List<Poi>) { pois = p }

    /**
     * 이 말([spoken])로 [placeId]를 고른 기억을 한 번 덜어 낸다 ("아니야"로 거절했을 때). 바뀐 게 있으면 true.
     * [dow]를 주면 그 요일 종류(평일·주말)의 횟수에서 먼저 덜어 낸다.
     */
    fun forgetChoice(spoken: String, placeId: String, dow: Int? = null): Boolean {
        val key = norm(spoken)
        val m = choiceMemory[key] ?: return false
        val c = m[placeId] ?: return false
        if (c <= 1) m.remove(placeId) else m[placeId] = c - 1
        if (m.isEmpty()) choiceMemory.remove(key)
        choiceByDay[key]?.let { byDay ->
            byDay[placeId]?.let { a ->
                val t = dow?.let(::dayType)
                val i = when {
                    t != null && a[t] > 0 -> t
                    a[0] >= a[1] -> 0
                    else -> 1
                }
                if (a[i] > 0) a[i]--
                if (a[0] == 0 && a[1] == 0) byDay.remove(placeId)
            }
            if (byDay.isEmpty()) choiceByDay.remove(key)
        }
        return true
    }

    /** 학습한 것만 초기화 (방문 기록은 유지) */
    fun resetLearning() {
        weights = DEFAULT_WEIGHTS.copyOf()
        prevWeights = DEFAULT_WEIGHTS.copyOf()
        aliases.clear()
        choiceMemory.clear()
        choiceByDay.clear()
        learnCount = 0
    }

    /**
     * 같은 말로 고른 기록에서 이 곳의 몫 (0~1) — '선호' 특징.
     *  - 지금과 같은 요일 종류(평일·주말)에 고른 기록이 있으면 그 안에서의 몫
     *  - 없으면 전체 기록에서의 몫의 절반 (평일에 고른 곳이 주말 질문까지 끌고 가지 않게)
     */
    fun choiceShare(spoken: String, placeId: String, dow: Int): Double {
        val key = norm(spoken)
        val t = dayType(dow)
        val byDay = choiceByDay[key]
        val sameTotal = byDay?.values?.sumOf { it[t] } ?: 0
        if (sameTotal > 0) return (byDay?.get(placeId)?.get(t) ?: 0).toDouble() / sameTotal
        val all = choiceMemory[key] ?: return 0.0
        val total = all.values.sum()
        return if (total > 0) 0.5 * (all[placeId] ?: 0) / total else 0.0
    }

    /** 이 말로 [target](브랜드 또는 장소 id)에 해당하는 곳을 고른 횟수 */
    private fun choiceCount(key: String, target: String): Int =
        choiceMemory[key]?.entries?.sumOf { (id, n) -> if (id == target || pois.firstOrNull { it.place.id == id }?.brand == target) n else 0 } ?: 0

    /**
     * 이 선택을 '표현·선택 기억'에 남겨도 되는지.
     *  - 고른 곳의 발음이 들린 말과 충분히 같아야 한다 (바로 실행 기준과 같음) — "아니, 병원"처럼 다른 곳을 새로 말한 건 오인식이 아니다
     *  - 들린 말이 이미 어떤 장소의 정확한 이름이면 기억하지 않는다 — '스타벅스'를 병원으로 바꿔 알아듣는 일이 없게
     */
    private fun memorable(text: String, chosen: Candidate): Boolean =
        chosen.f.phon >= AUTO_MIN_PHON && pois.none { p -> soundsExact(text, p) }

    private class Stat(val n: Int, val lastDay: Int?, val timeAff: Double, val habitLabel: String?)

    private class Scored(
        val poi: Poi, val f: Features, val score: Double, val stat: Stat,
        val km: Double?, val onRoute: Boolean, val remembered: Int, val kmDirect: Double?,
    )

    fun phonetic(q: PlaceQuery, poi: Poi): Double {
        q.category?.let { return if (poi.category == it) 1.0 else 0.0 }
        aliases[norm(q.text)]?.let { t -> if (t == poi.place.id || t == poi.brand) return 1.0 }
        if (isShort(q.text)) return shortSimilarity(q.text, poi)
        var best = 0.0
        for (name in poi.names) best = max(best, Hangul.similarity(q.text, name))
        return best
    }

    /**
     * 두 음절 이하의 짧은 말 — 이름 안의 아무 부분(부분 정렬)이 아니라 이름의 단어·별칭·브랜드 전체와 비교한다.
     * '빨리'가 '스타벅스 판교테크노밸리점'의 '밸리'에 붙어 0.8이 되던 문제 (리뷰 D6). 글자 그대로 단어의 앞머리·끝이면
     * 0.9 ('판교' ↔ '판교테크노밸리점'), 한 음절이면 0.75 — 한 음절로는 바로 실행하지 않는다.
     */
    private fun shortSimilarity(text: String, poi: Poi): Double {
        val nq = norm(text)
        var best = 0.0
        for (k in shortKeys(poi)) {
            if (k == nq) return 1.0
            if (k.startsWith(nq) || k.endsWith(nq)) best = max(best, if (units(nq) >= 2) 0.9 else 0.75)
            best = max(best, Hangul.wholeSimilarity(nq, k))
        }
        return best
    }

    /** 짧은 말과 비교할 부르는 말: 이름·별칭·브랜드 전체, 그 단어들, 끝의 '점'을 뗀 단어 */
    private fun shortKeys(poi: Poi): Set<String> {
        val keys = LinkedHashSet<String>()
        for (name in poi.names) {
            keys += norm(name)
            for (w in name.split(' ', '(', ')', '·')) {
                val k = norm(w)
                if (k.isEmpty()) continue
                keys += k
                if (k.length >= 2 && k.endsWith("점")) keys += k.dropLast(1)
            }
        }
        keys.remove("")
        return keys
    }

    /** 들린 말이 이 장소의 이름을 그대로 말한 것인지 (짧은 말이면 이름의 단어를 그대로) */
    private fun soundsExact(text: String, poi: Poi): Boolean {
        if (!isShort(text)) return poi.names.any { Hangul.similarity(text, it) >= EXACT_PHON }
        val nq = norm(text)
        return shortKeys(poi).any { k -> k == nq || k.startsWith(nq) || k.endsWith(nq) || Hangul.wholeSimilarity(nq, k) >= EXACT_PHON }
    }

    fun resolve(q: PlaceQuery, s: Situation, w: DoubleArray = weights): Resolution {
        val window = visits.filter { it.epochDay < s.epochDay && it.epochDay >= s.epochDay - 90 }
        val byPlace = window.groupBy { it.placeId }
        val maxN = max(1, byPlace.values.maxOfOrNull { it.size } ?: 1)
        val key = norm(q.text)
        val memory = choiceMemory[key]

        val pre = pois.map { it to phonetic(q, it) }
            .filter { it.second >= MIN_PHON }
            .sortedByDescending { it.second }
            .take(8)
        if (pre.isEmpty()) return Resolution(q, emptyList(), Tier.NONE, null, weights = w.toList(), situation = s)

        val scored = pre.map { (poi, ph) ->
            val st = stat(byPlace[poi.place.id].orEmpty(), s)
            var dKm: Double? = null
            var kmDirect: Double? = null
            var onRoute = false
            if (s.lat != null && s.lng != null) {
                val direct = haversineKm(s.lat, s.lng, poi.place.lat, poi.place.lng)
                dKm = direct
                kmDirect = direct
                if (s.destLat != null && s.destLng != null) {
                    val seg = kmToSegment(poi.place.lat, poi.place.lng, s.lat, s.lng, s.destLat, s.destLng)
                    if (seg < direct) { dKm = seg; onRoute = seg < 4.0 }
                }
            }
            val freqRaw = ln(1.0 + st.n) / ln(1.0 + maxN)
            val freq = if (q.habit) min(1.0, freqRaw * 1.5) else freqRaw
            val dist = dKm?.let { exp(-it / if (q.nearby) 5.0 else 12.0) } ?: 0.4
            val recent = st.lastDay?.let { exp(-(s.epochDay - it) / 21.0) } ?: 0.0
            val remembered = memory?.get(poi.place.id) ?: 0
            // 선호 = 같은 말로 고른 기록에서의 몫(평일·주말 구분) · 즐겨찾기는 0.7
            val share = if (remembered > 0) choiceShare(q.text, poi.place.id, s.dow) else 0.0
            val pref = max(share, if (poi.place.id in favorites) 0.7 else 0.0)
            val f = Features(ph, freq, st.timeAff, dist, recent, pref)
            Scored(poi, f, dot(w, f), st, dKm, onRoute, remembered, kmDirect)
        }
        val probs = softmax(scored.map { it.score })
        val cands = scored.zip(probs)
            .map { (sc, p) ->
                Candidate(
                    sc.poi, sc.f, sc.score, p, tags(sc), sc.km, sc.onRoute,
                    kmDirect = sc.kmDirect, habitual = sc.stat.n >= 4 || sc.remembered > 0,
                )
            }
            .sortedByDescending { it.prob }

        val p1 = cands[0].prob
        val p2 = cands.getOrNull(1)?.prob ?: 0.0
        val tier = when {
            // 확률은 후보끼리의 상대값이라, 소리가 약하게만 비슷한 후보가 하나 남으면 1.0이 된다
            // (예: '다이소' → 성심당 본점). 바로 실행은 들린 말이 이름과 충분히 같은 소리일 때만.
            p1 >= AUTO_PROB && p1 - p2 >= AUTO_MARGIN && cands[0].f.phon >= AUTO_MIN_PHON -> Tier.AUTO
            p1 >= CONFIRM_PROB -> Tier.CONFIRM
            else -> Tier.CHOOSE
        }
        val top = cands[0]
        val aliasHit = aliases.containsKey(key)
        val exact = soundsExact(q.text, top.poi)
        val corrected = if (q.category == null && !exact) (top.poi.brand ?: top.poi.spoken) else null
        return Resolution(q, cands, tier, corrected, aliasHit, weights = w.toList(), situation = s)
    }

    /**
     * 사용자가 [chosenId]를 골랐을 때 차 안에서 바로 배운다.
     *  1) 순위 가중치: 고른 후보의 특징 쪽으로 w 이동 (w ← w + lr·(x_고른 − Σp·x))
     *  2) 선택 기억: 같은 말로 고른 장소를 평일·주말로 나눠 센다 (다음엔 '선호' = 고른 몫)
     *  3) 표현 기억: 발음이 달랐다면 "들린 말 → 브랜드"를 기억 (다음엔 발음 유사도 1.0)
     * 2)·3)은 오인식을 바로잡은 선택일 때만 남긴다 ([memorable]). 이미 기억한 표현을 다른 곳으로 바꾸는 것은
     * 그 말로 새 곳을 고른 횟수가 옛 곳보다 많아졌을 때뿐이다 (한 번의 실수로 기억이 뒤집히지 않게).
     */
    fun learn(res: Resolution, chosenId: String, lr: Double = 0.6): LearnOutcome? {
        val idx = res.candidates.indexOfFirst { it.poi.place.id == chosenId }
        if (idx < 0) return null
        val before = weights.copyOf()
        val expected = DoubleArray(N_FEATURES)
        for (c in res.candidates) {
            val x = c.f.vec()
            for (i in 0 until N_FEATURES) expected[i] += c.prob * x[i]
        }
        val chosen = res.candidates[idx]
        val x = chosen.f.vec()
        weights = DoubleArray(N_FEATURES) { i ->
            (weights[i] + lr * (x[i] - expected[i])).coerceIn(if (i == 0) 3.0 else 0.2, 9.0)
        }
        prevWeights = before

        var alias: Pair<String, String>? = null
        val key = norm(res.query.text)
        if (res.query.category == null && key.isNotEmpty() && memorable(res.query.text, chosen)) {
            val id = chosen.poi.place.id
            val m = choiceMemory.getOrPut(key) { LinkedHashMap() }
            m[id] = (m[id] ?: 0) + 1
            res.situation?.let { s -> choiceByDay.getOrPut(key) { LinkedHashMap() }.getOrPut(id) { IntArray(2) }[dayType(s.dow)]++ }
            val target = chosen.poi.brand ?: id
            val old = aliases[key]
            if (old != target && (old == null || choiceCount(key, target) > choiceCount(key, old))) {
                aliases[key] = target
                alias = res.query.text to (chosen.poi.brand ?: chosen.poi.spoken)
            }
        }
        learnCount++
        return LearnOutcome(before, weights.copyOf(), alias, chosen)
    }

    // ───────────────────────── 특징 계산 ─────────────────────────

    private fun stat(vs: List<Visit>, s: Situation): Stat {
        if (vs.isEmpty()) return Stat(0, null, 0.05, null)
        val weekendNow = s.dow >= 6
        var k = 0.0
        for (v in vs) {
            val same = (v.dow >= 6) == weekendNow
            var dm = abs(v.minute - s.minute).toDouble()
            if (dm > 720) dm = 1440 - dm
            val z = dm / 75.0
            k += (if (same) 1.0 else 0.25) * exp(-0.5 * z * z)
        }
        val aff = ((k + 0.05) / (vs.size + 1)).coerceIn(0.0, 1.0)
        return Stat(vs.size, vs.maxOf { it.epochDay }, aff, habitLabel(vs))
    }

    private fun habitLabel(vs: List<Visit>): String? {
        if (vs.size < 2) return null
        val sat = vs.count { it.dow == 6 } * 2 > vs.size
        val weekend = vs.count { it.dow >= 6 } * 2 > vs.size
        val hours = vs.map { it.minute / 60 }.sorted()
        val slot = when (hours[hours.size / 2]) {
            in 5..8 -> "아침"; in 9..10 -> "오전"; in 11..13 -> "점심"; in 14..17 -> "오후"; in 18..21 -> "저녁"; else -> "밤"
        }
        val day = when { sat -> "토요일"; weekend -> "주말"; else -> "평일" }
        return "$day $slot"
    }

    /**
     * 화면·HUD용 짧은 근거 — 운전 중 한눈에 판단에 도움이 되는 순서:
     * 지난번 선택 · 즐겨찾기 → 가는 길 → "주로 토요일 오전" → 방문 횟수 → 거리(직선, km).
     */
    private fun tags(sc: Scored): List<String> = buildList {
        if (sc.remembered > 0) add("지난번 선택")
        if (sc.poi.place.id in favorites) add("즐겨찾기")
        if (sc.onRoute) add("가는 길")
        if (sc.stat.habitLabel != null && sc.f.time >= 0.45) add("주로 ${sc.stat.habitLabel}")
        when {
            sc.stat.n >= 4 -> add("자주 감 ${sc.stat.n}회")
            sc.stat.n > 0 -> add("${sc.stat.n}회 방문")
            else -> add("방문 기록 없음")
        }
        // 보여 주는 거리는 현재 위치에서의 직선거리 (순위에 쓴 경로 우회 거리와 혼동하지 않게)
        (sc.kmDirect ?: sc.km)?.let { add(if (it < 10) "%.1fkm".format(java.util.Locale.ROOT, it) else "${it.roundToInt()}km") }
    }

    companion object {
        const val N_FEATURES = 6
        val FEATURE_LABELS = listOf("발음", "자주", "시간대", "거리", "최근", "선호")
        /** 초기 가중치 — 발음이 가장 크고, 개인 기록은 그 위에서 순위를 가른다 */
        val DEFAULT_WEIGHTS = doubleArrayOf(6.0, 1.6, 1.4, 1.0, 1.0, 2.0)
        const val MIN_PHON = 0.55
        const val AUTO_PROB = 0.85
        const val AUTO_MARGIN = 0.5
        /** 바로 실행에 필요한 최소 발음 유사도 (그 아래는 확률이 높아도 한 번 확인) */
        const val AUTO_MIN_PHON = 0.8
        const val CONFIRM_PROB = 0.5
        /** 이 이상이면 들린 말이 그 이름을 그대로 말한 것으로 본다 */
        const val EXACT_PHON = 0.97

        fun norm(s: String): String = QueryParser.norm(s)

        /** 글자 수 (한글 음절·영문자·숫자) */
        fun units(s: String): Int = s.count { it.isLetterOrDigit() }

        /** 두 음절 이하 — 소리를 이름의 단어 전체와만 비교하는 짧은 말 */
        fun isShort(text: String): Boolean = units(norm(text)) in 1..2

        /** 0 = 평일(월~금), 1 = 주말(토·일) */
        fun dayType(dow: Int): Int = if (dow >= 6) 1 else 0

        fun dot(w: DoubleArray, f: Features): Double {
            val x = f.vec()
            var s = 0.0
            for (i in 0 until N_FEATURES) s += w[i] * x[i]
            return s
        }

        fun softmax(scores: List<Double>): List<Double> {
            val m = scores.max()
            val e = scores.map { exp(it - m) }
            val sum = e.sum()
            return e.map { it / sum }
        }

        fun haversineKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val dLat = Math.toRadians(bLat - aLat)
            val dLng = Math.toRadians(bLng - aLng)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * 6371.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        /** 점 P에서 선분 A→B(현재 위치→목적지)까지의 거리 km (평면 근사) */
        fun kmToSegment(pLat: Double, pLng: Double, aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val kx = 111.32 * cos(Math.toRadians(aLat))
            val ky = 110.57
            val bx = (bLng - aLng) * kx
            val by = (bLat - aLat) * ky
            val px = (pLng - aLng) * kx
            val py = (pLat - aLat) * ky
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            return sqrt(dx * dx + dy * dy)
        }
    }
}
