package com.yeopjari.app.ai.personal

import com.yeopjari.app.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 개인화 장소 이해 평가 — "옆자리 엔진이 말로 부른 장소를 더 잘 알아듣는가?"를 숫자로 확인한다.
 *
 * 엔진이 한 번도 보지 못한 28일치 여정(같은 가상 운전자)을 시간 순서대로 다시 달리며,
 * 방문마다 운전자가 그 장소를 세 가지 말투로 불렀다고 보고 네 가지 방법의 답을 비교한다.
 * 결과는 표준 출력과 docs/eval/personal_eval.md 에 함께 쓴다.
 */
class PersonalEvalTest {

    @Test
    fun asrNoise_isDeterministicAndKeepsValidHangul() {
        assertEquals(AsrNoise.perturb("성심당", Random(5)), AsrNoise.perturb("성심당", Random(5)))
        for (seed in 0 until 300) for (name in listOf("성심당", "스타벅스", "엄마 집", "도윤이 유치원", "올림픽공원", "파리바게뜨", "본가")) {
            val out = AsrNoise.perturb(name, Random(seed.toLong()))
            assertNotEquals("소리가 실제로 달라져야 함: $name → $out", QueryParser.norm(name), QueryParser.norm(out))
            assertTrue("완성형 한글 음절과 띄어쓰기만: $out", out.all { it == ' ' || it.code in 0xAC00..0xD7A3 })
        }
    }

    @Test
    fun evaluatePersonalPlaceUnderstanding() {
        val main = PersonalEval.run(PersonalEval.TEST_SEED)
        val runs = listOf(main) + PersonalEval.EXTRA_SEEDS.map { PersonalEval.run(it) }
        val md = PersonalEvalReport.render(main, runs)
        println(md)
        val file = PersonalEvalReport.write(md)
        println("평가 보고서 → ${file.absolutePath}")

        // 정상성 확인만 한다 — 실패하면 숫자를 맞추려고 데이터를 고치지 말고 그대로 보고한다.
        val b = main.overall(EvalMethod.B)
        val c = main.overall(EvalMethod.C)
        val d = main.overall(EvalMethod.D)
        assertTrue("질의가 너무 적음: ${main.overallCount}", main.overallCount >= 60)
        assertTrue("C Top-1 ${c.top1}/${c.n} ≥ B Top-1 ${b.top1}/${b.n}", c.top1 >= b.top1)
        assertTrue("D 잘못된 자동 실행 ${d.wrongAuto}/${d.n} ≤ 5%", d.wrongAuto <= 0.05 * d.n)
        val (endC, endD) = main.endBrains.getValue(EvalForm.NAME)
        val ood = PersonalEval.outOfDictionary(endC, endD)
        for (m in listOf(EvalMethod.C, EvalMethod.D)) {
            val falseStarts = ood.count { it.outcomes.getValue(m).tier == Tier.AUTO }
            assertTrue("$m: 사전에 없는 곳으로 바로 출발 $falseStarts/${ood.size} ≤ 5%", falseStarts <= 0.05 * ood.size)
        }
    }
}

// ───────────────────────── 평가 데이터 구조 ─────────────────────────

internal enum class EvalForm(val code: String, val label: String, val inOverall: Boolean) {
    NAME("(a)", "이름·브랜드를 그대로", true),
    NOISY("(b)", "음성인식 오류가 섞인 이름", true),
    CATEGORY("(c)", "범주어 (빵집·카페·마트…)", true),
    NOISY_REPEAT("(b′)", "같은 오인식이 반복될 때", false),
}

internal enum class EvalMethod(val code: String, val label: String) {
    A("A", "일반 검색"),
    B("B", "발음 보정만"),
    C("C", "발음+개인 기록 (옆자리)"),
    D("D", "C + 차 안 학습"),
}

/** 순위 한 칸 — value: A=거리 km, B=발음 유사도, C·D=확률 · tags: 엔진이 붙인 근거 (C·D) */
internal data class EvalRanked(val id: String, val value: Double, val tags: List<String> = emptyList(), val phon: Double = value)

internal data class EvalOutcome(val ranked: List<EvalRanked>, val tier: Tier) {
    fun rankOf(id: String): Int = ranked.indexOfFirst { it.id == id }

    companion object {
        val NONE = EvalOutcome(emptyList(), Tier.NONE)
    }
}

/** 질의 대상 방문 하나와 그때의 상황·말 */
internal data class EvalTrip(
    /** 평가 기간 전체 방문(집·회사 포함, 시간순) 안에서의 순번 */
    val index: Int,
    val visit: Visit,
    val s: Situation,
    /** 토요일 대전 본가 가는 길 (경부고속도로 위, 목적지 = 본가) */
    val onHighway: Boolean,
    /** 출발 위치: 직전 방문 장소 id 또는 "highway" */
    val fromId: String,
    val name: String,
    val noisy: String,
    val noisyRepeat: String,
    val category: String?,
) {
    /** 평가 기간 몇 주차인지 (1~4) */
    val week: Int get() = (visit.epochDay - PersonaFactory.BASE_DAY - 1) / 7 + 1

    fun spoken(f: EvalForm): String? = when (f) {
        EvalForm.NAME -> name
        EvalForm.NOISY -> noisy
        EvalForm.NOISY_REPEAT -> noisyRepeat
        EvalForm.CATEGORY -> category
    }
}

internal data class EvalRecord(
    val trip: EvalTrip,
    val form: EvalForm,
    val spoken: String,
    val utterance: String,
    val q: PlaceQuery?,
    val outcomes: Map<EvalMethod, EvalOutcome>,
) {
    val truth: String get() = trip.visit.placeId

    /** 문장 해석기가 의도한 장소 말을 그대로 뽑았는지 */
    val parseOk: Boolean
        get() = q != null && if (form == EvalForm.CATEGORY) q.category != null else QueryParser.norm(q.text) == QueryParser.norm(spoken)

    fun hit(m: EvalMethod): Boolean = outcomes.getValue(m).rankOf(truth) == 0
}

internal class EvalTally {
    var n = 0
    var top1 = 0
    var top3 = 0
    var auto = 0
    var wrongAuto = 0
    var ask = 0
    var none = 0

    /** 맞는 곳에 닿기까지 운전자가 더 말해야 하는 횟수 (앱의 대화 흐름 그대로) */
    var turns0 = 0
    var turns1 = 0
    var turns2 = 0
    var fail = 0

    fun add(o: EvalOutcome, truth: String) {
        n++
        val r = o.rankOf(truth)
        if (r == 0) top1++
        if (r in 0..2) top3++
        when (o.tier) {
            Tier.AUTO -> { auto++; if (r != 0) wrongAuto++ }
            Tier.CONFIRM, Tier.CHOOSE -> ask++
            Tier.NONE -> none++
        }
        when {
            o.tier == Tier.AUTO && r == 0 -> turns0++ // 바로 출발
            o.tier == Tier.CONFIRM && r == 0 -> turns1++ // "응"
            o.tier == Tier.CHOOSE && r in 0..2 -> turns1++ // "n번"
            (o.tier == Tier.AUTO || o.tier == Tier.CONFIRM) && r in 1..3 -> turns2++ // "아니야" → 나머지 3곳 중 "n번"
            else -> fail++ // 화면에 없음 → 다시 말해야 함
        }
    }
}

internal class EvalRun(
    val seed: Int,
    /** 평가 기간 전체 방문 (집·회사 포함, 시간순) */
    val visits: List<Visit>,
    val trips: List<EvalTrip>,
    val records: List<EvalRecord>,
    /** 형태별 재생이 끝난 뒤 D의 가중치 */
    val dWeights: Map<EvalForm, DoubleArray>,
    val dLearnEvents: Map<EvalForm, Int>,
    /** 형태별 재생이 끝난 뒤의 (C, D) 엔진 */
    val endBrains: Map<EvalForm, Pair<PersonalBrain, PersonalBrain>>,
) {
    fun tally(m: EvalMethod, pred: (EvalRecord) -> Boolean): EvalTally =
        EvalTally().also { t -> records.filter(pred).forEach { t.add(it.outcomes.getValue(m), it.truth) } }

    fun overall(m: EvalMethod): EvalTally = tally(m) { it.form.inOverall }
    fun byForm(m: EvalMethod, f: EvalForm): EvalTally = tally(m) { it.form == f }
    val overallCount: Int get() = records.count { it.form.inOverall }
}

// ───────────────────────── 평가 실행 ─────────────────────────

internal object PersonalEval {
    const val TEST_SEED = 99
    val EXTRA_SEEDS = listOf(100, 101, 102, 103)

    /** sim/DriveSimulator P_DAEJEON 위치 — 경부고속도로, 대전 본가 북쪽 약 17km */
    const val HIGHWAY_LAT = 36.482
    const val HIGHWAY_LNG = 127.347

    /** 집·회사는 전용 의도('집으로'·'회사로')가 처리하므로 질의에서 뺀다 */
    private val dedicated = setOf("home", "work")

    /** 사람들이 실제로 부르는 이름 (브랜드·줄임말·별칭) */
    val spokenNames: Map<String, List<String>> = mapOf(
        "kinder" to listOf("도윤이 유치원", "해솔유치원"),
        "sb_pangyo" to listOf("스타벅스"), "sb_jamsil" to listOf("스타벅스"), "sb_gangnam" to listOf("스타벅스"),
        "ssd_station" to listOf("성심당"), "ssd_main" to listOf("성심당"), "ssd_dcc" to listOf("성심당"),
        "church_dh" to listOf("대흥동 성당"),
        "parents" to listOf("엄마 집", "본가", "부모님 댁"),
        "pb_lake" to listOf("파리바게뜨"),
        "hd_pangyo" to listOf("현대백화점"), "hd_coex" to listOf("현대백화점"),
        "hd_outlet" to listOf("현대아울렛"),
        "starfield" to listOf("스타필드"),
        "lotte_mart" to listOf("롯데마트"),
        "homeplus" to listOf("홈플러스"),
        "pedi" to listOf("소아과", "애들 병원"),
        "asan" to listOf("아산병원"),
        "olympic" to listOf("올림픽공원"),
    )

    val pois: List<Poi> get() = PersonaFactory.pois

    fun place(id: String): Place = PersonaFactory.poiById(id)!!.place
    fun nameOf(id: String): String = when (id) {
        "highway" -> "경부고속도로 위"
        else -> PersonaFactory.poiById(id)?.spoken ?: id
    }

    /** 엔진이 본 적 없는 미래 28일 (기준일+1 … 기준일+28), 시간순 */
    fun testVisits(seed: Int): List<Visit> =
        PersonaFactory.visits(baseDay = PersonaFactory.BASE_DAY + 29, days = 28, seed = seed)
            .sortedWith(compareBy<Visit>({ it.epochDay }, { it.minute }))

    fun buildTrips(all: List<Visit>, seed: Int): List<EvalTrip> {
        val r = Random(seed.toLong())
        val parents = place("parents")
        val trips = ArrayList<EvalTrip>()
        for ((i, v) in all.withIndex()) {
            if (v.placeId in dedicated) continue
            val sameDay = all.withIndex().filter { it.value.epochDay == v.epochDay } // 이미 시간순
            val pos = sameDay.indexOfFirst { it.index == i }
            val prev = if (pos > 0) sameDay[pos - 1].value.placeId else "home"
            val towardParents = sameDay.any { it.index > i && it.value.placeId == "parents" }
            val minute = max(0, v.minute - 15)
            val s = if (towardParents) {
                Situation(v.epochDay, v.dow, minute, HIGHWAY_LAT, HIGHWAY_LNG, parents.lat, parents.lng)
            } else {
                val p = place(prev)
                Situation(v.epochDay, v.dow, minute, p.lat, p.lng)
            }
            val poi = PersonaFactory.poiById(v.placeId)!!
            val options = spokenNames[v.placeId] ?: listOf(poi.brand ?: poi.spoken)
            val name = options[r.nextInt(options.size)]
            val noisy = AsrNoise.perturb(name, r)
            // 같은 사람·같은 말 → 같은 오인식 (말마다 고정된 오류 하나)
            val repeat = AsrNoise.perturb(name, Random(seed * 1_000_003L + name.hashCode()))
            val category = poi.category.takeIf { it.words.isNotEmpty() }?.label
            trips += EvalTrip(i, v, s, towardParents, if (towardParents) "highway" else prev, name, noisy, repeat, category)
        }
        return trips
    }

    fun run(seed: Int): EvalRun {
        val all = testVisits(seed)
        val trips = buildTrips(all, seed)
        val records = ArrayList<EvalRecord>()
        val weights = LinkedHashMap<EvalForm, DoubleArray>()
        val learned = LinkedHashMap<EvalForm, Int>()
        // 말투마다 28일을 따로 다시 달린다 (여정 하나에 질의 하나 · 학습 하나 · 기록 추가 한 번)
        val brains = LinkedHashMap<EvalForm, Pair<PersonalBrain, PersonalBrain>>()
        for (form in EvalForm.entries) {
            val r = replay(all, trips, form) { it.spoken(form) }
            records += r.records
            weights[form] = r.dWeights
            learned[form] = r.learnEvents
            brains[form] = r.cBrain to r.dBrain
        }
        return EvalRun(seed, all, trips, records, weights, learned, brains)
    }

    class Replay(
        val records: List<EvalRecord>,
        val dWeights: DoubleArray,
        val learnEvents: Int,
        /** 28일을 다 달린 뒤의 C(기록만 쌓임)·D(기록 + 학습) 엔진 */
        val cBrain: PersonalBrain,
        val dBrain: PersonalBrain,
    )

    /** 이 운전자가 한 번도 간 적 없는(장소 사전에 없는) 흔한 목적지 */
    val OUT_OF_DICTIONARY = listOf(
        "이마트", "코스트코", "하나로마트", "신세계백화점", "갤러리아", "교보문고", "다이소", "올리브영",
        "맥도날드", "버거킹", "투썸플레이스", "이디야", "메가커피", "뚜레쥬르",
        "서울역", "강남역", "인천공항", "롯데월드", "에버랜드", "코엑스",
        "세브란스병원", "삼성서울병원", "어린이대공원", "한강공원", "석촌호수",
    )

    /** 기준값 확인용 — 위 목록·보고서 seed와 겹치지 않는 사전 밖 목적지 */
    val OUT_OF_DICTIONARY_HELD_OUT = listOf(
        "롯데리아", "써브웨이", "할리스", "폴바셋", "빕스", "아웃백", "이케아", "트레이더스", "농협", "우체국",
        "주민센터", "시청", "구청", "동물병원", "치과", "약국", "세차장", "키즈카페", "수영장", "도서관",
        "야구장", "잠실종합운동장", "가락시장", "광장시장", "남산타워", "여의도공원", "서울숲", "판교역", "수서역", "스타필드 고양",
    )

    /** 고치기 전의 바로 실행 규칙 (확률만 봄) — C·D 결과에서 반사실로 계산 */
    fun oldRuleAuto(o: EvalOutcome): Boolean {
        val p1 = o.ranked.getOrNull(0)?.value ?: return false
        val p2 = o.ranked.getOrNull(1)?.value ?: 0.0
        return p1 >= PersonalBrain.AUTO_PROB && p1 - p2 >= PersonalBrain.AUTO_MARGIN
    }

    class GateCheck(
        val seeds: List<Int>,
        /** 사전에 있는 곳: 옛 규칙의 바로 실행 / 그중 발음 미달로 확인이 된 것 / 그중 정답 / 옛 규칙의 잘못된 바로 실행 / 그중 막힌 것 */
        val oldAuto: Int, val demoted: Int, val demotedCorrect: Int, val oldWrongAuto: Int, val wrongBlocked: Int,
        /** 사전에 없는 곳 (C·D): 질의 수 / 옛 규칙의 바로 실행 / 그중 막힌 것 */
        val oodN: Int, val oodOldAuto: Int, val oodBlocked: Int,
    )

    /** 바로 실행 발음 기준([PersonalBrain.AUTO_MIN_PHON])을 보고서와 겹치지 않는 seed·장소로 확인한다. C는 학습이 없어 반사실 비교가 정확하다. */
    fun gateCheck(seeds: List<Int>): GateCheck {
        var oldAuto = 0
        var demoted = 0
        var demotedCorrect = 0
        var oldWrong = 0
        var wrongBlocked = 0
        var first: EvalRun? = null
        for (seed in seeds) {
            val run = run(seed)
            if (first == null) first = run
            for (r in run.records.filter { it.form.inOverall }) {
                val o = r.outcomes.getValue(EvalMethod.C)
                if (!oldRuleAuto(o)) continue
                oldAuto++
                val ok = o.rankOf(r.truth) == 0
                if (!ok) oldWrong++
                if (o.ranked.first().phon < PersonalBrain.AUTO_MIN_PHON) {
                    demoted++
                    if (ok) demotedCorrect++ else wrongBlocked++
                }
            }
        }
        val (c, d) = first!!.endBrains.getValue(EvalForm.NAME)
        val outs = outOfDictionary(c, d, OUT_OF_DICTIONARY_HELD_OUT).flatMap { listOf(it.outcomes.getValue(EvalMethod.C), it.outcomes.getValue(EvalMethod.D)) }
        val oodOld = outs.filter { oldRuleAuto(it) }
        return GateCheck(
            seeds, oldAuto, demoted, demotedCorrect, oldWrong, wrongBlocked,
            outs.size, oodOld.size, oodOld.count { it.ranked.first().phon < PersonalBrain.AUTO_MIN_PHON },
        )
    }

    class OodRow(val name: String, val situation: String, val outcomes: Map<EvalMethod, EvalOutcome>)

    /**
     * 사전에 없는 곳을 말했을 때 — 맞는 답은 '못 찾음'이다.
     * 비슷한 소리의 아는 곳으로 바로 출발하면 위험한 오작동, 되물으면 운전자가 “아니야”로 끊을 수 있다.
     * C·D는 평가 28일을 다 달린 뒤의 엔진을 쓴다 (D는 학습한 가중치·표현·선택 기억 포함).
     */
    fun outOfDictionary(c: PersonalBrain, d: PersonalBrain, names: List<String> = OUT_OF_DICTIONARY): List<OodRow> {
        val after = PersonaFactory.BASE_DAY + 29 // 평가 기간 다음 날 (일요일)
        val parents = place("parents")
        val work = place("work")
        val home = place("home")
        fun sit(day: Int, h: Int, m: Int, lat: Double, lng: Double, dLat: Double? = null, dLng: Double? = null) =
            Situation(day, java.time.LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value, h * 60 + m, lat, lng, dLat, dLng)
        val situations = listOf(
            "평일 08:31 출근길" to sit(after + 4, 8, 31, 37.4176, 127.1074, work.lat, work.lng),
            "평일 18:40 회사 앞" to sit(after + 4, 18, 40, work.lat, work.lng),
            "토 10:50 집 앞" to sit(after + 6, 10, 50, home.lat, home.lng),
            "토 10:04 고속도로" to sit(after + 6, 10, 4, HIGHWAY_LAT, HIGHWAY_LNG, parents.lat, parents.lng),
        )
        val sound = PersonalBrain(visits = emptyList())
        return names.flatMap { name ->
            val q = QueryParser.parse("$name 가자") ?: PlaceQuery(name)
            situations.map { (label, s) ->
                OodRow(
                    name, label,
                    mapOf(
                        EvalMethod.A to textSearch(q, s),
                        EvalMethod.B to soundSearch(sound, q, s),
                        EvalMethod.C to outcome(c.resolve(q, s)),
                        EvalMethod.D to outcome(d.resolve(q, s)),
                    ),
                )
            }
        }
    }

    /** 평가 기간을 시간순으로 한 번 달린다. 방법마다 새 엔진, 질의 대상 방문마다 [spokenOf]로 말을 정한다. */
    fun replay(all: List<Visit>, trips: List<EvalTrip>, form: EvalForm, spokenOf: (EvalTrip) -> String?): Replay {
        val tripAt = trips.associateBy { it.index }
        val records = ArrayList<EvalRecord>()
        val sound = PersonalBrain(visits = emptyList()) // B: 발음만 (기록·학습 없음)
        val c = PersonalBrain() // C: 90일 기록 + 기본 가중치, 학습 없음
        val d = PersonalBrain() // D: C + 고를 때마다 차 안 학습
        var events = 0
        for ((i, v) in all.withIndex()) {
            val trip = tripAt[i]
            val spoken = trip?.let(spokenOf)
            if (trip != null && spoken != null) {
                val utterance = if (trip.onHighway) "$spoken 들렀다 가자" else "$spoken 가자"
                val q = QueryParser.parse(utterance)
                val outcomes = if (q == null) {
                    EvalMethod.entries.associateWith { EvalOutcome.NONE }
                } else {
                    val rd = d.resolve(q, trip.s) // 학습 전 상태로 답한 뒤
                    if (learnLikeApp(d, rd, v.placeId)) events++ // 운전자의 확인·선택으로 학습
                    mapOf(
                        EvalMethod.A to textSearch(q, trip.s),
                        EvalMethod.B to soundSearch(sound, q, trip.s),
                        EvalMethod.C to outcome(c.resolve(q, trip.s)),
                        EvalMethod.D to outcome(rd),
                    )
                }
                records += EvalRecord(trip, form, spoken, utterance, q, outcomes)
            }
            // 차는 실제로 주차한 곳을 기록한다 (집·회사 포함) → 다음 날부터 자주·최근·시간대에 반영
            c.replaceVisits(c.visits + v)
            d.replaceVisits(d.visits + v)
        }
        return Replay(records, d.weights.copyOf(), events, c, d)
    }

    /** 오류 세기 실험: 이름마다 소리 오류를 정확히 k개 넣어 (b)를 다시 잰다 (k=0은 (a)와 같다) */
    fun noiseCurve(seed: Int, levels: List<Int>): Map<Int, Replay> {
        val all = testVisits(seed)
        val trips = buildTrips(all, seed)
        return levels.associateWith { k ->
            replay(all, trips, EvalForm.NOISY) { t -> AsrNoise.perturbExactly(t.name, k, Random(seed * 7919L + k * 104_729L + t.index)) }
        }
    }

    /** 경로 안내 중이면 경로(현재 위치→목적지)까지의 거리, 아니면 직선거리 — 엔진의 '거리' 특징과 같은 정의 */
    fun km(s: Situation, poi: Poi): Double {
        val lat = s.lat ?: return 0.0
        val lng = s.lng ?: return 0.0
        val direct = PersonalBrain.haversineKm(lat, lng, poi.place.lat, poi.place.lng)
        val dLat = s.destLat ?: return direct
        val dLng = s.destLng ?: return direct
        return min(direct, PersonalBrain.kmToSegment(poi.place.lat, poi.place.lng, lat, lng, dLat, dLng))
    }

    /** A 일반 검색: 이름·별칭·브랜드에 들린 말이 글자 그대로 들어 있는 곳, 가까운 순. 결과가 하나면 바로 실행. */
    fun textSearch(q: PlaceQuery, s: Situation): EvalOutcome {
        val nq = QueryParser.norm(q.text)
        if (nq.isEmpty()) return EvalOutcome.NONE
        val hits = pois.filter { p -> p.names.any { QueryParser.norm(it).contains(nq) } }
        if (hits.isEmpty()) return EvalOutcome.NONE
        val ranked = hits.map { EvalRanked(it.place.id, km(s, it)) }.sortedBy { it.value }
        return EvalOutcome(ranked, if (hits.size == 1) Tier.AUTO else Tier.CHOOSE)
    }

    /** B 발음 보정만: 엔진의 발음 특징(범주어면 범주 일치)만으로 순위, 같으면 가까운 순. 1위가 혼자면 바로 실행. */
    fun soundSearch(sound: PersonalBrain, q: PlaceQuery, s: Situation): EvalOutcome {
        val scored = pois.map { it to sound.phonetic(q, it) }.filter { it.second >= PersonalBrain.MIN_PHON }
        if (scored.isEmpty()) return EvalOutcome.NONE
        val sorted = scored.sortedWith(compareByDescending<Pair<Poi, Double>> { it.second }.thenBy { km(s, it.first) })
        val unique = sorted.size == 1 || sorted[0].second - sorted[1].second > 1e-9
        return EvalOutcome(sorted.map { EvalRanked(it.first.place.id, it.second) }, if (unique) Tier.AUTO else Tier.CHOOSE)
    }

    fun outcome(r: Resolution): EvalOutcome = EvalOutcome(r.candidates.map { EvalRanked(it.poi.place.id, it.prob, it.tags, it.f.phon) }, r.tier)

    /**
     * 앱(YeopjariStore)의 대화 흐름 그대로 학습한다.
     *  - 바로 실행 + 맞음 → 10초 동안 '아니야'가 없으면 조용히 학습 (lr 0.3)
     *  - 확인 "응" / 선택지 "n번" / 바로 실행·확인 뒤 "아니야" → 나머지 3곳 중 "n번" → 학습 (lr 0.6)
     *  - 바로 실행·확인을 "아니야"로 거절하면 그 1위는 이 말의 선택 기억에서 한 번 덜어 낸다 (PersonalService.forget)
     *  - 정답이 화면에 뜬 후보에 없으면 운전자는 다시 말해야 하므로 이번 결과로는 학습하지 않는다
     */
    fun learnLikeApp(brain: PersonalBrain, res: Resolution, truth: String): Boolean {
        val r = res.candidates.indexOfFirst { it.poi.place.id == truth }
        if ((res.tier == Tier.AUTO || res.tier == Tier.CONFIRM) && r != 0) {
            res.top?.let { brain.forgetChoice(res.query.text, it.poi.place.id, res.situation?.dow) }
        }
        val lr = when {
            r < 0 -> return false
            res.tier == Tier.AUTO && r == 0 -> 0.3
            (res.tier == Tier.AUTO || res.tier == Tier.CONFIRM) && r <= 3 -> 0.6
            res.tier == Tier.CHOOSE && r <= 2 -> 0.6
            else -> return false
        }
        return brain.learn(res, truth, lr) != null
    }

    class MemoryDemo(
        /** 평일 출근길에 "스타버스"(스타벅스 오인식)로 판교점을 고른 횟수 */
        val weekdayPicks: Int,
        /** 그 뒤 토요일 10:50 집 앞: 판교점의 선호 특징 · 1위 · 1위 확률 */
        val satPrefPangyo: Double, val satTop: String, val satTopProb: Double,
        /** 토요일에 잠실새내역점을 한 번 고른 뒤: 토요일 1위 · 평일 출근길 1위 */
        val satTopAfter: String, val weekdayTopAfter: String,
        /** 같은 말로 판교 2번·강남 1번 고른 평일: 선호 특징 */
        val sharePangyo: Double, val shareGangnam: Double,
    )

    /** 선택 기억이 평일·주말을 나눠 '고른 몫'으로 쓰이는지 — 시연 좌표로 계산 */
    fun memoryDemo(): MemoryDemo {
        val base = PersonaFactory.BASE_DAY
        fun sit(day: Int, h: Int, m: Int, lat: Double, lng: Double, dLat: Double? = null, dLng: Double? = null) =
            Situation(day, java.time.LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value, h * 60 + m, lat, lng, dLat, dLng)
        val work = place("work")
        val commute = { d: Int -> sit(base - d, 8, 31, 37.4176, 127.1074, work.lat, work.lng) } // 목·수·화 출근길
        val saturday = sit(base, 10, 50, 37.5133, 127.1001)
        val q = PlaceQuery("스타버스")
        val brain = PersonalBrain()
        val picks = 3
        for (d in 2..(1 + picks)) brain.learn(brain.resolve(q, commute(d)), "sb_pangyo")
        val sat = brain.resolve(q, saturday)
        val pangyo = sat.candidates.first { it.poi.place.id == "sb_pangyo" }
        brain.learn(sat, "sb_jamsil")
        val satAfter = brain.resolve(q, saturday)
        val weekdayAfter = brain.resolve(q, commute(2))
        val mixed = PersonalBrain()
        for ((d, id) in listOf(2 to "sb_pangyo", 3 to "sb_pangyo", 4 to "sb_gangnam")) mixed.learn(mixed.resolve(q, commute(d)), id)
        val m = mixed.resolve(q, commute(2))
        fun pref(r: Resolution, id: String) = r.candidates.first { it.poi.place.id == id }.f.pref
        return MemoryDemo(
            picks, pangyo.f.pref, sat.top!!.poi.place.id, sat.top!!.prob,
            satAfter.top!!.poi.place.id, weekdayAfter.top!!.poi.place.id,
            pref(m, "sb_pangyo"), pref(m, "sb_gangnam"),
        )
    }
}

// ───────────────────────── 보고서 ─────────────────────────

internal object PersonalEvalReport {
    private val L = Locale.ROOT
    private val dowKo = arrayOf("", "월", "화", "수", "목", "금", "토", "일")
    private val mainForms = listOf(EvalForm.NAME, EvalForm.NOISY, EvalForm.CATEGORY)

    fun pct(k: Int, n: Int): String = if (n == 0) "–" else String.format(L, "%.1f%%", 100.0 * k / n)
    private fun pct1(x: Double): String = String.format(L, "%.1f%%", 100.0 * x)
    private fun hhmm(m: Int): String = String.format(L, "%02d:%02d", m / 60, m % 60)
    private fun f2(x: Double): String = String.format(L, "%.2f", x)
    private fun pValue(p: Double): String = if (p < 0.001) "< 0.001" else String.format(L, "%.3f", p)

    /** Wilson 95% 신뢰구간 */
    fun wilson(k: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n == 0) return 0.0 to 0.0
        val p = k.toDouble() / n
        val d = 1 + z * z / n
        val c = (p + z * z / (2.0 * n)) / d
        val h = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d
        return max(0.0, c - h) to min(1.0, c + h)
    }

    /** 짝지은 비교의 정확 부호검정 (양측) — x: X만 맞힘, y: Y만 맞힘 */
    fun signTestP(x: Int, y: Int): Double {
        val n = x + y
        if (n == 0) return 1.0
        var cum = 0.0
        for (i in 0..min(x, y)) cum += exp(lnChoose(n, i) - n * ln(2.0))
        return min(1.0, 2 * cum)
    }

    private fun lnChoose(n: Int, k: Int): Double {
        var s = 0.0
        for (i in 1..k) s += ln((n - k + i).toDouble()) - ln(i.toDouble())
        return s
    }

    private fun methodLabel(m: EvalMethod) = "${m.code} ${m.label}"

    /** 마지막 글자 받침 유무로 조사 고르기 (Hangul.ro 와 같은 규칙) */
    private fun hasBatchim(word: String): Boolean {
        val last = word.trim().lastOrNull { it.code in 0xAC00..0xD7A3 } ?: return false
        return (last.code - 0xAC00) % 28 != 0
    }
    private fun eul(word: String) = if (hasBatchim(word)) "을" else "를"
    private fun iga(word: String) = if (hasBatchim(word)) "이" else "가"

    private fun metricsTable(rows: List<Pair<String, EvalTally>>): String = buildString {
        appendLine("| 방법 | Top-1 | Top-3 | 자동 실행 | 잘못된 자동 실행 | 되묻기 | 못 찾음 |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|")
        for ((label, t) in rows) {
            appendLine(
                "| $label | ${pct(t.top1, t.n)} | ${pct(t.top3, t.n)} | ${pct(t.auto, t.n)} | " +
                    "${pct(t.wrongAuto, t.n)} (${t.wrongAuto}건) | ${pct(t.ask, t.n)} | ${pct(t.none, t.n)} |",
            )
        }
    }

    private fun valueLabel(m: EvalMethod, v: Double): String = when (m) {
        EvalMethod.A -> String.format(L, "%.1fkm", v)
        EvalMethod.B -> String.format(L, "발음 %.0f%%", 100 * v)
        EvalMethod.C, EvalMethod.D -> String.format(L, "%.0f%%", 100 * v)
    }

    private fun tierLabel(o: EvalOutcome): String = o.tier.label

    private fun situationLabel(t: EvalTrip): String {
        val v = t.visit
        val where = if (t.onHighway) "경부고속도로 위 · 대전 본가로 안내 중" else "출발: ${PersonalEval.nameOf(t.fromId)}"
        return "${t.week}주차 ${dowKo[v.dow]}요일 ${hhmm(t.s.minute)} · $where"
    }

    private fun example(no: Int, r: EvalRecord): String = buildString {
        appendLine("**${"①②③④⑤⑥"[no]} ${situationLabel(r.trip)}** — 들린 말 “${r.utterance}” → 실제로 간 곳 **${PersonalEval.nameOf(r.truth)}**")
        appendLine()
        appendLine("| 방법 | 1위 | 2위 | 3위 | 엔진 행동 |")
        appendLine("|---|---|---|---|---|")
        for (m in EvalMethod.entries) {
            val o = r.outcomes.getValue(m)
            val cells = (0 until 3).map { k ->
                o.ranked.getOrNull(k)?.let { x ->
                    val s = "${PersonalEval.nameOf(x.id)} · ${valueLabel(m, x.value)}"
                    if (x.id == r.truth) "**$s**" else s
                } ?: "–"
            }
            appendLine("| ${methodLabel(m)} | ${cells.joinToString(" | ")} | ${tierLabel(o)} |")
        }
        appendLine()
    }

    fun render(main: EvalRun, runs: List<EvalRun>): String = buildString {
        val methods = EvalMethod.entries
        val ov = methods.associateWith { main.overall(it) }
        val n = main.overallCount
        val formTally = mainForms.associateWith { f -> methods.associateWith { main.byForm(it, f) } }
        val formCount = EvalForm.entries.associateWith { f -> main.records.count { it.form == f } }
        fun line(map: Map<EvalMethod, EvalTally>, pick: (EvalTally) -> Int) =
            methods.joinToString(" · ") { "${it.code} ${pct(pick(map.getValue(it)), map.getValue(it).n)}" }

        fun paired(x: EvalMethod, y: EvalMethod): Triple<Int, Int, Double> {
            var onlyX = 0
            var onlyY = 0
            for (r in main.records.filter { it.form.inOverall }) {
                val hx = r.hit(x)
                val hy = r.hit(y)
                if (hx && !hy) onlyX++ else if (hy && !hx) onlyY++
            }
            return Triple(onlyX, onlyY, signTestP(onlyX, onlyY))
        }
        val cb = paired(EvalMethod.C, EvalMethod.B)
        val dc = paired(EvalMethod.D, EvalMethod.C)
        val ba = paired(EvalMethod.B, EvalMethod.A)
        val scenarios = scenarioRows()
        val (endC, endD) = main.endBrains.getValue(EvalForm.NAME)
        val ood = PersonalEval.outOfDictionary(endC, endD)
        fun oodCount(m: EvalMethod, pred: (EvalOutcome) -> Boolean) = ood.count { pred(it.outcomes.getValue(m)) }

        appendLine("# 개인화 장소 이해 평가 — 옆자리")
        appendLine()
        appendLine("> 이 문서는 `app/src/test/java/com/yeopjari/app/ai/personal/PersonalEvalTest.kt`가 앱에 들어간 엔진 코드")
        appendLine("> (`Hangul` · `QueryParser` · `PersonalBrain`)를 JVM에서 그대로 돌려 자동으로 만든다. 손으로 고친 숫자는 없다.")
        appendLine("> 다시 만들기: `./gradlew :app:testDebugUnitTest --tests \"*PersonalEvalTest*\"` — seed가 고정되어 매번 같은 결과가 나온다.")
        appendLine()

        // ── 한눈에
        appendLine("## 한눈에")
        appendLine()
        appendLine("- 엔진이 본 적 없는 28일치 여정에서 집·회사를 뺀 방문 ${main.trips.size}건을 세 가지 말투로 불러 본 질의 **${n}개**.")
        appendLine("- **Top-1** (첫 후보가 실제로 간 곳): ${line(ov) { it.top1 }}")
        appendLine("- **음성인식 오류가 섞인 이름 (b)** Top-1: ${line(formTally.getValue(EvalForm.NOISY)) { it.top1 }}")
        appendLine("- **범주어 (c)** Top-1 (예: “빵집 들렀다 가자”): ${line(formTally.getValue(EvalForm.CATEGORY)) { it.top1 }}")
        appendLine("- **잘못된 자동 실행** (확인 없이 엉뚱한 곳으로 출발 — 운전 중 가장 위험한 실수): ${line(ov) { it.wrongAuto }}")
        appendLine("- **더 말할 필요 없이 바로 맞는 곳으로 출발**: ${line(ov) { it.turns0 }}")
        val oc = ov.getValue(EvalMethod.C)
        val od = ov.getValue(EvalMethod.D)
        appendLine(
            "- **차 안 학습(C → D)**: Top-1 ${pct(oc.top1, oc.n)} → ${pct(od.top1, od.n)} · 바로 실행 ${pct(oc.auto, oc.n)} → ${pct(od.auto, od.n)} · " +
                "되묻기 ${pct(oc.ask, oc.n)} → ${pct(od.ask, od.n)} · 잘못된 자동 실행 ${oc.wrongAuto}건 → ${od.wrongAuto}건",
        )
        appendLine(
            "- 같은 질의 짝 비교: C만 맞힌 ${cb.first}건 · B만 맞힌 ${cb.second}건 (부호검정 p ${pValue(cb.third)}), " +
                "D만 맞힌 ${dc.first}건 · C만 맞힌 ${dc.second}건 (p ${pValue(dc.third)})",
        )
        appendLine(
            "- **사전에 없는 곳을 말하면** (처음 가는 흔한 목적지 ${PersonalEval.OUT_OF_DICTIONARY.size}곳 × 상황 4개 = ${ood.size}개): 엉뚱한 아는 곳으로 바로 출발 " +
                methods.joinToString(" · ") { m -> "${m.code} ${pct(oodCount(m) { it.tier == Tier.AUTO }, ood.size)}" } +
                " (고치기 전 엔진이었다면 C ${pct(oodCount(EvalMethod.C) { PersonalEval.oldRuleAuto(it) }, ood.size)}) — 5장 참고",
        )
        appendLine("- 이번 평가에서 엔진 버그 2개를 찾아 고쳤다: 문장 해석기의 말머리 군말 제거, 약한 발음 일치로 바로 출발하던 판단. 코드 리뷰에서 나온 학습·대화 규칙 4가지(선택 기억을 평일·주말로 나눈 ‘고른 몫’으로, 오인식을 바로잡은 선택만 기억, 대답 해석, 섞인 명령 거르기)도 고친 뒤의 숫자다 (5장).")
        appendLine()

        // ── 1. 무엇을 쟀나
        appendLine("## 1. 무엇을 쟀나")
        appendLine()
        appendLine("### 평가 데이터")
        appendLine()
        val weekdays = main.visits.map { it.epochDay }.distinct().size
        val tripDow = main.trips.groupingBy { if (it.visit.dow >= 6) "주말" else "평일" }.eachCount()
        appendLine("- **엔진이 아는 것**: 가상 운전자 ‘서준’(잠실 거주 · 판교 출근 · 아이 둘 · 격주 토요일 대전 본가)의 기준일 이전 90일 방문 기록 — 앱 시연과 같은 기록 (`PersonaFactory.visits()`, seed 7)과 장소 사전 ${PersonalEval.pois.size}곳.")
        appendLine("- **평가 여정**: 기준일 다음 날부터 28일(4주, 방문이 있던 날 ${weekdays}일)의 새 방문 ${main.visits.size}건 (`PersonaFactory.visits(baseDay = 기준일+29, days = 28, seed = ${main.seed})`). 같은 생활 패턴에서 새로 뽑은 기록이며 엔진은 미리 보지 못한다.")
        appendLine("- 집·회사 방문 ${main.visits.size - main.trips.size}건은 전용 의도(‘집으로 가자’·‘회사로 가자’)가 처리하므로 질의하지 않는다. 다만 차는 실제로 주차한 곳을 모두 기록하므로 C·D의 방문 기록에는 매일 쌓인다.")
        appendLine("- **질의 대상 방문 ${main.trips.size}건** (평일 ${tripDow["평일"] ?: 0} · 주말 ${tripDow["주말"] ?: 0}): " +
            main.trips.groupingBy { it.visit.placeId }.eachCount().entries.sortedByDescending { it.value }
                .joinToString(" · ") { "${PersonalEval.nameOf(it.key)} ${it.value}" })
        appendLine("- **질의 순간**: 방문 15분 전. 차 위치는 그날 직전에 들른 곳(그날 첫 방문이면 집), 목적지 안내는 없음. " +
            "단, 토요일 대전 본가 가는 날의 성심당은 경부고속도로 위(시연 P_DAEJEON 위치)에서 대전 본가로 안내 받는 중에 “… 들렀다 가자”로 말한다.")
        appendLine("- 문장은 “<장소 말> 가자”(고속도로 경유는 “<장소 말> 들렀다 가자”)로 만들어 앱과 같은 `QueryParser.parse`를 거친다.")
        appendLine()
        appendLine("| 발화 형태 | 질의 수 | 만드는 법 |")
        appendLine("|---|---:|---|")
        appendLine("| (a) 이름·브랜드 그대로 | ${formCount[EvalForm.NAME]} | 사람들이 부르는 말: 스타벅스, 성심당, 파리바게뜨, 현대백화점, 롯데마트, 홈플러스, 올림픽공원, ‘도윤이 유치원’·‘해솔유치원’, ‘소아과’·‘애들 병원’, ‘엄마 집’·‘본가’·‘부모님 댁’ (여럿이면 seed로 하나) |")
        appendLine("| (b) 음성인식 오류 섞임 | ${formCount[EvalForm.NOISY]} | (a)에 자모 단위 소리 오류 1개(60%)·2개(40%): 모음 혼동 ㅐ/ㅔ·ㅓ/ㅗ·ㅡ/ㅜ·ㅕ/ㅛ, 받침 탈락·추가(특히 ㅇ), ㅡ 끼어듦(당→다응), 예사·된·거센소리 바꿈, 띄어쓰기 끼어듦 |")
        appendLine("| (c) 범주어 | ${formCount[EvalForm.CATEGORY]} | 장소의 범주어: 빵집·카페·마트·백화점·유치원·병원·공원 (대전 본가는 범주어가 없어 제외) |")
        appendLine("| (b′) 같은 오인식 반복 | ${formCount[EvalForm.NOISY_REPEAT]} | 민감도 분석용 — 같은 말은 늘 같은 오류로 들린다고 가정 (전체 집계에서 제외) |")
        appendLine()
        val noisySamples = main.trips.map { it.name to it.noisy }.distinct().take(12)
        appendLine("(b) 오류 예: " + noisySamples.joinToString(" · ") { "${it.first} → ${it.second}" })
        appendLine()
        appendLine("### 비교한 방법")
        appendLine()
        appendLine("| 방법 | 설명 | ‘바로 실행’ 기준 |")
        appendLine("|---|---|---|")
        appendLine("| A 일반 검색 | 장소 이름·별칭·브랜드에 들린 말이 글자 그대로(띄어쓰기 무시) 들어 있는 곳, 가까운 순. 글자가 안 맞으면 못 찾음 | 결과가 1곳뿐일 때 |")
        appendLine("| B 발음 보정만 | 엔진의 발음 유사도(`Hangul.similarity`, 이름 중 최댓값 · 범주어면 범주 일치)만, 같으면 가까운 순. 개인 기록 없음 | 1위 점수가 혼자일 때 |")
        appendLine("| C 발음+개인 기록 (옆자리) | `PersonalBrain.resolve` 기본 가중치 [${PersonalBrain.DEFAULT_WEIGHTS.joinToString(", ")}], 학습 없음 | 엔진 등급 (p1 ≥ ${PersonalBrain.AUTO_PROB}, p1−p2 ≥ ${PersonalBrain.AUTO_MARGIN}, 1위 발음 ≥ ${PersonalBrain.AUTO_MIN_PHON}) |")
        appendLine("| D C + 차 안 학습 | C와 같되 질의마다 앱의 대화 흐름대로 `learn` 호출: 바로 실행이 맞으면 lr 0.3(10초 무응답), “응”·“n번”·“아니야→n번”이면 lr 0.6, 정답이 화면 후보에 없으면 학습 없음. “아니야”로 거절한 1위는 그 말의 선택 기억에서 한 번 덜어 냄(`forgetChoice`) | 엔진 등급 |")
        appendLine()
        appendLine("- 거리는 엔진과 같은 정의(안내 중이면 경로까지의 거리, 아니면 직선거리)를 A·B의 동점 처리에도 쓴다. 후보 기준(발음 ≥ ${PersonalBrain.MIN_PHON})도 B·C·D가 같다.")
        appendLine("- C·D는 질의 뒤 그날 실제 방문을 기록에 더한다(`replaceVisits(visits + v)`). 방법마다, 말투마다 새 엔진으로 28일을 따로 다시 달린다 — 여정 하나에 질의 하나·학습 하나.")
        appendLine("- 지표: **Top-1** 첫 후보가 정답 · **Top-3** HUD 선택지(최대 3개) 안에 정답 · **자동 실행** 확인 없이 바로 안내 · **잘못된 자동 실행** 바로 안내했는데 틀림(전체 질의 대비) · **되묻기** 한 번 확인 또는 선택지 · **못 찾음** 후보 없음.")
        appendLine()

        // ── 2. 결과
        appendLine("## 2. 결과")
        appendLine()
        appendLine("### 전체 — (a)+(b)+(c) ${n}개 질의")
        appendLine()
        append(metricsTable(methods.map { methodLabel(it) to ov.getValue(it) }))
        appendLine()
        for (f in mainForms) {
            appendLine("### ${f.code} ${f.label} — ${formCount[f]}개")
            appendLine()
            append(metricsTable(methods.map { methodLabel(it) to formTally.getValue(f).getValue(it) }))
            appendLine()
        }

        appendLine("### 장소별 Top-1 — 차이가 어디서 나나 ((a)+(b)+(c))")
        appendLine()
        appendLine("이름이 하나뿐인 곳(유치원·본가 등)은 어느 방법이든 맞힌다. 차이는 지점이 여럿인 브랜드와 범주어에서 난다.")
        appendLine()
        appendLine("| 실제로 간 곳 | 질의 | A | B | C | D |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        val byPlace = main.records.filter { it.form.inOverall }.groupBy { it.truth }.entries.sortedByDescending { it.value.size }
        for ((id, rs) in byPlace) {
            appendLine("| ${PersonalEval.nameOf(id)} | ${rs.size} | " + methods.joinToString(" | ") { m -> pct(rs.count { it.hit(m) }, rs.size) } + " |")
        }
        appendLine()

        appendLine("### 운전자 부담 — 맞는 곳까지 더 말해야 하는 횟수 (전체)")
        appendLine()
        appendLine("앱의 실제 대화 흐름 기준: 바로 실행이 맞으면 0회, “응”/“n번”이면 1회, “아니야” → 나머지 3곳 중 “n번”이면 2회. 정답이 화면에 없으면 다시 말해야 한다.")
        appendLine()
        appendLine("| 방법 | 0회 · 바로 출발 | 1회 | 2회 | 화면에 없음 → 다시 말하기 |")
        appendLine("|---|---:|---:|---:|---:|")
        for (m in methods) {
            val t = ov.getValue(m)
            appendLine("| ${methodLabel(m)} | ${pct(t.turns0, t.n)} | ${pct(t.turns1, t.n)} | ${pct(t.turns2, t.n)} | ${pct(t.fail, t.n)} |")
        }
        appendLine()

        appendLine("### 통계적으로 믿을 만한가")
        appendLine()
        appendLine("- Top-1 95% 신뢰구간 (Wilson, n=${n}): " + methods.joinToString(" · ") { m ->
            val t = ov.getValue(m)
            val (lo, hi) = wilson(t.top1, t.n)
            "${m.code} ${pct(t.top1, t.n)} [${pct1(lo)}, ${pct1(hi)}]"
        })
        appendLine("- 같은 질의 짝 비교 (정확 부호검정, 양측): B vs A — B만 ${ba.first}건 · A만 ${ba.second}건 (p ${pValue(ba.third)}) · " +
            "C vs B — C만 ${cb.first}건 · B만 ${cb.second}건 (p ${pValue(cb.third)}) · D vs C — D만 ${dc.first}건 · C만 ${dc.second}건 (p ${pValue(dc.third)})")
        appendLine("- 주의: 질의 상당수가 같은 장소(유치원·판교 스타벅스)의 반복이고 말투 세 가지가 같은 방문을 공유해 서로 독립이 아니다. 신뢰구간과 p값은 실제보다 좁게(유리하게) 나오므로 참고용이다 — 아래 seed 반복이 더 정직한 확인이다.")
        appendLine()

        appendLine("### 기간별 — 차 안 학습이 쌓이면")
        appendLine()
        appendLine("| 방법 | 1~2주 Top-1 | 3~4주 Top-1 | 1~2주 자동 실행 | 3~4주 자동 실행 | 1~2주 잘못된 자동 | 3~4주 잘못된 자동 |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|")
        for (m in methods) {
            val h1 = main.tally(m) { it.form.inOverall && it.trip.week <= 2 }
            val h2 = main.tally(m) { it.form.inOverall && it.trip.week >= 3 }
            appendLine("| ${methodLabel(m)} | ${pct(h1.top1, h1.n)} | ${pct(h2.top1, h2.n)} | ${pct(h1.auto, h1.n)} | ${pct(h2.auto, h2.n)} | ${pct(h1.wrongAuto, h1.n)} | ${pct(h2.wrongAuto, h2.n)} |")
        }
        appendLine()
        appendLine("D가 28일 동안 학습한 횟수: " + mainForms.joinToString(" · ") { "${it.code} ${main.dLearnEvents[it]}회" } + ". 학습 뒤 가중치:")
        appendLine()
        appendLine("| | " + PersonalBrain.FEATURE_LABELS.joinToString(" | ") + " |")
        appendLine("|---|" + PersonalBrain.FEATURE_LABELS.joinToString("") { "---:|" })
        appendLine("| 초기값 | " + PersonalBrain.DEFAULT_WEIGHTS.joinToString(" | ") { f2(it) } + " |")
        for (f in EvalForm.entries) appendLine("| D 학습 후 ${f.code} | " + main.dWeights.getValue(f).joinToString(" | ") { f2(it) } + " |")
        appendLine()

        appendLine("### seed를 바꿔도 같은가 — 평가 여정·말 고르기·오류를 모두 새로 뽑아 반복")
        appendLine()
        appendLine("| 평가 seed | 질의 | A Top-1 | B Top-1 | C Top-1 | D Top-1 | C 잘못된 자동 | D 잘못된 자동 |")
        appendLine("|---:|---:|---:|---:|---:|---:|---:|---:|")
        val agg = methods.associateWith { 0.0 }.toMutableMap()
        var aggCw = 0.0
        var aggDw = 0.0
        for (run in runs) {
            val t = methods.associateWith { run.overall(it) }
            appendLine("| ${run.seed} | ${run.overallCount} | " + methods.joinToString(" | ") { pct(t.getValue(it).top1, t.getValue(it).n) } +
                " | ${pct(t.getValue(EvalMethod.C).wrongAuto, run.overallCount)} | ${pct(t.getValue(EvalMethod.D).wrongAuto, run.overallCount)} |")
            for (m in methods) agg[m] = agg.getValue(m) + t.getValue(m).top1.toDouble() / t.getValue(m).n
            aggCw += t.getValue(EvalMethod.C).wrongAuto.toDouble() / run.overallCount
            aggDw += t.getValue(EvalMethod.D).wrongAuto.toDouble() / run.overallCount
        }
        appendLine("| **평균** | | " + methods.joinToString(" | ") { "**${pct1(agg.getValue(it) / runs.size)}**" } +
            " | **${pct1(aggCw / runs.size)}** | **${pct1(aggDw / runs.size)}** |")
        appendLine()

        appendLine("### 민감도 — 같은 말이 늘 같은 오류로 들린다면 (b′)")
        appendLine()
        appendLine("실제 음성인식 오류는 같은 사람·같은 단어에서 반복되는 경우가 많다. (b)는 매번 새로 틀리게 해 ‘들린 말 → 장소’ 기억이 거의 다시 쓰이지 않는 보수적 조건이고, (b′)는 말마다 오류를 하나로 고정한 조건이다. 전체 집계에는 넣지 않았다.")
        appendLine()
        append(metricsTable(methods.map { methodLabel(it) to main.byForm(it, EvalForm.NOISY_REPEAT) }))
        appendLine()
        appendLine("(b′) 고정 오류: " + main.trips.map { it.name to it.noisyRepeat }.distinct().joinToString(" · ") { "${it.first} → ${it.second}" })
        appendLine()

        appendLine("### 오류가 많아지면 — 이름 하나에 소리 오류를 정확히 k개")
        appendLine()
        appendLine(
            "(b)의 오류 1~2개가 너무 쉬운지 확인하려고, 같은 ${main.trips.size}개 방문의 (a) 이름에 자모 단위 소리 오류를 k개씩 넣어 28일을 다시 달렸다. 발음 유사도가 후보 기준(${PersonalBrain.MIN_PHON}) 밑으로 떨어지면 ‘못 찾음’이 된다. " +
                "오류에 강하게 나오는 데에는 장소 사전이 ${PersonalEval.pois.size}곳뿐이라 비슷한 소리의 다른 후보가 적다는 점도 작용한다 — 같은 너그러움이 사전에 없는 곳을 아는 곳으로 바꿔 듣는 쪽으로도 작용한다(아래 ‘처음 가는 곳’).",
        )
        appendLine()
        appendLine("| 소리 오류 k | 예 | A Top-1 | B Top-1 | B 못 찾음 | C Top-1 | C Top-3 | C 못 찾음 | D Top-1 | D 잘못된 자동 |")
        appendLine("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|")
        for ((k, rep) in PersonalEval.noiseCurve(main.seed, listOf(0, 1, 2, 3, 4))) {
            fun t(m: EvalMethod) = EvalTally().also { tally -> rep.records.forEach { tally.add(it.outcomes.getValue(m), it.truth) } }
            val a = t(EvalMethod.A)
            val b = t(EvalMethod.B)
            val c = t(EvalMethod.C)
            val d = t(EvalMethod.D)
            val samples = listOf("성심당", "스타벅스", "해솔유치원").mapNotNull { nm -> rep.records.firstOrNull { it.trip.name == nm }?.let { "$nm → ${it.spoken}" } }
            appendLine(
                "| $k | ${samples.joinToString(", ")} | ${pct(a.top1, a.n)} | ${pct(b.top1, b.n)} | ${pct(b.none, b.n)} | ${pct(c.top1, c.n)} | ${pct(c.top3, c.n)} | ${pct(c.none, c.n)} | " +
                    "${pct(d.top1, d.n)} | ${pct(d.wrongAuto, d.n)} |",
            )
        }
        appendLine()

        appendLine("### 처음 가는 곳을 말하면 — 장소 사전에 없는 흔한 목적지 ${PersonalEval.OUT_OF_DICTIONARY.size}곳")
        appendLine()
        appendLine(
            "맞는 답은 ‘못 찾음’이다. 발음이 비슷한 아는 곳으로 **바로 출발**하면 위험한 오작동이고, **되묻기**면 운전자가 “아니야”로 끊을 수 있다. " +
                "${PersonalEval.OUT_OF_DICTIONARY.joinToString(", ")}를 시연과 같은 네 상황(평일 출근길·평일 저녁 회사 앞·토요일 집 앞·토요일 고속도로)에서 물었다(${ood.size}개). C·D는 평가 28일을 다 달린 뒤의 엔진이다.",
        )
        appendLine()
        appendLine("| 방법 | 못 찾음 (맞게 거절) | 되묻기 (아는 곳을 제안) | 바로 실행 (엉뚱한 곳으로 출발) | 고치기 전 규칙이었다면 바로 실행 |")
        appendLine("|---|---:|---:|---:|---:|")
        for (m in methods) {
            val outs = ood.map { it.outcomes.getValue(m) }
            val none = outs.count { it.tier == Tier.NONE }
            val auto = outs.count { it.tier == Tier.AUTO }
            val before = if (m == EvalMethod.C || m == EvalMethod.D) outs.count { PersonalEval.oldRuleAuto(it) }.let { "${pct(it, outs.size)} (${it}건)" } else "–"
            appendLine("| ${methodLabel(m)} | ${pct(none, outs.size)} | ${pct(outs.size - none - auto, outs.size)} | ${pct(auto, outs.size)} (${auto}건) | $before |")
        }
        appendLine()
        appendLine(
            "‘고치기 전 규칙’은 확률만 보던 바로 실행 조건(p1 ≥ ${PersonalBrain.AUTO_PROB}, p1−p2 ≥ ${PersonalBrain.AUTO_MARGIN})이다. 후보가 하나만 남으면 확률이 1.0이 되어 소리가 약하게만 비슷해도 바로 출발했다. " +
                "지금 엔진은 1위의 발음 유사도가 ${PersonalBrain.AUTO_MIN_PHON} 이상일 때만 바로 실행한다 (아래 ‘엔진 수정’).",
        )
        appendLine()
        val hijacked = ood.groupBy { it.name }.mapNotNull { (name, rows) ->
            val hits = rows.map { it.outcomes.getValue(EvalMethod.C) }.filter { it.tier != Tier.NONE }
            if (hits.isEmpty()) null else {
                val top = hits.groupingBy { it.ranked.first().id }.eachCount().maxByOrNull { it.value }!!.key
                val autos = rows.count { it.outcomes.getValue(EvalMethod.C).tier == Tier.AUTO }
                "$name → ${PersonalEval.nameOf(top)} (${hits.size}/${rows.size}" + (if (autos > 0) ", 바로 실행 $autos" else "") + ")"
            }
        }
        appendLine(
            if (hijacked.isEmpty()) "C는 ${PersonalEval.OUT_OF_DICTIONARY.size}곳 모두 ‘못 찾음’으로 답했다."
            else "C가 아는 곳으로 바꿔 들은 말 (상황 네 개 중 몇 번): " + hijacked.joinToString(" · "),
        )
        appendLine()
        appendLine(
            "지금 앱은 ‘못 찾음’이면 “제 기록에서 찾지 못했어요. 장소 이름을 한 번 더 말씀해 주시겠어요?”로 끝나고, 아는 곳을 제안받은 운전자가 “아니야”라고 해도 일반 내비 검색으로 넘기는 연결은 아직 없다. " +
                "처음 가는 곳을 말로 찾으려면 이 엔진 뒤에 내비 POI 검색(NaviHelper)을 이어 붙여야 한다.",
        )
        appendLine()

        val parseIssues = main.records.filter { it.form.inOverall && !it.parseOk }
        appendLine("### 문장 해석")
        appendLine()
        if (parseIssues.isEmpty()) {
            appendLine("(a)(b)(c) ${n}개 문장 모두 `QueryParser.parse`가 의도한 장소 말(범주어는 범주)을 그대로 뽑았다.")
        } else {
            appendLine("`QueryParser.parse`가 의도와 다르게 뽑은 문장 ${parseIssues.size}개 (네 방법 모두 같은 입력을 받으므로 공통 손실):")
            for (r in parseIssues.take(10)) appendLine("- “${r.utterance}” → ${r.q?.let { "‘${it.text}’ (범주 ${it.category?.label ?: "없음"})" } ?: "해석 실패"}")
        }
        appendLine()

        val misses = main.records.filter { it.form.inOverall && (!it.hit(EvalMethod.C) || !it.hit(EvalMethod.D)) }
        appendLine("### C 또는 D가 틀린 질의 — 전부 (${misses.size}개 / ${n}개)")
        appendLine()
        appendLine("| 형태 | 상황 | 들린 말 | 실제로 간 곳 | C 1위 · 행동 | D 1위 · 행동 · 엔진이 붙인 근거 |")
        appendLine("|---|---|---|---|---|---|")
        for (r in misses) {
            fun top(m: EvalMethod): String {
                val o = r.outcomes.getValue(m)
                val x = o.ranked.firstOrNull() ?: return "– · ${o.tier.label}"
                val s = "${PersonalEval.nameOf(x.id)} ${valueLabel(m, x.value)}"
                return (if (x.id == r.truth) "**$s**" else s) + " · ${o.tier.label}"
            }
            val dTags = r.outcomes.getValue(EvalMethod.D).ranked.firstOrNull()?.tags.orEmpty().take(3).joinToString(", ")
            appendLine("| ${r.form.code} | ${situationLabel(r.trip)} | “${r.spoken}” | ${PersonalEval.nameOf(r.truth)} | ${top(EvalMethod.C)} | ${top(EvalMethod.D)} · $dTags |")
        }
        appendLine()

        appendLine("### 시연 시나리오에서 같은 엔진이 내는 값")
        appendLine()
        appendLine("앱 시연(sim/DriveSimulator.kt)과 같은 시각·위치·목적지로 새 엔진에 물었다. 시연 화면의 퍼센트는 이 값이다.")
        appendLine()
        appendLine("| 시나리오 | 들린 말 | 1위 | 2위 | 엔진 행동 | 보정 |")
        appendLine("|---|---|---|---|---|---|")
        for ((label, said, res) in scenarios) {
            val c1 = res.candidates.getOrNull(0)
            val c2 = res.candidates.getOrNull(1)
            fun cell(c: Candidate?) = c?.let { String.format(L, "%s %.0f%%", it.poi.spoken, 100 * it.prob) } ?: "–"
            appendLine("| $label | “$said” | ${cell(c1)} | ${cell(c2)} | ${res.tier.label} | ${res.correctedTo ?: "–"} |")
        }
        appendLine()

        // ── 3. 예시
        appendLine("## 3. 예시 — 들린 말과 상위 3개 후보")
        appendLine()
        appendLine("A는 거리, B는 발음 유사도, C·D는 엔진 확률. 굵은 글씨가 실제로 간 곳.")
        appendLine()
        val picks = ArrayList<EvalRecord>()
        fun pick(pred: (EvalRecord) -> Boolean) { main.records.lastOrNull { it !in picks && pred(it) }?.let { picks += it } }
        fun pickFirst(pred: (EvalRecord) -> Boolean) { main.records.firstOrNull { it !in picks && pred(it) }?.let { picks += it } }
        pick { it.form == EvalForm.NOISY && it.trip.onHighway }
        pickFirst { it.form == EvalForm.NAME && it.truth == "sb_pangyo" }
        pickFirst { it.form == EvalForm.NAME && it.truth == "sb_jamsil" }
        pickFirst { it.form == EvalForm.CATEGORY && it.truth == "pb_lake" }
        pickFirst { it.form.inOverall && !it.hit(EvalMethod.C) && it.outcomes.getValue(EvalMethod.C).tier != Tier.NONE }
        picks.forEachIndexed { i, r -> append(example(i, r)) }
        val lastPick = picks.lastOrNull()
        if (lastPick != null && !lastPick.hit(EvalMethod.C)) {
            val cOut = lastPick.outcomes.getValue(EvalMethod.C)
            val rank = cOut.rankOf(lastPick.truth)
            val recovery = when {
                (cOut.tier == Tier.AUTO || cOut.tier == Tier.CONFIRM) && rank in 1..3 -> "“아니야” 한 번이면 정답이 HUD 선택지에 뜬다."
                cOut.tier == Tier.CHOOSE && rank in 0..2 -> "정답은 처음 띄운 HUD 선택지 안에 있다."
                else -> "정답이 HUD 선택지에 없어 다시 말해야 한다."
            }
            appendLine(
                "마지막 예시는 C가 틀린 경우다 — 평소 패턴에서 벗어난 방문은 개인 기록이 오히려 다른 곳으로 끌고 간다. " +
                    (if (cOut.tier == Tier.AUTO) "이때 엔진은 바로 출발했다(잘못된 자동 실행). " else "이때 엔진은 바로 출발하지 않고 ‘${cOut.tier.label}’${Hangul.ro(cOut.tier.label)} 되물었다. ") +
                    recovery,
            )
            appendLine()
        }

        // ── 4. 발견한 점
        appendLine("## 4. 발견한 점")
        appendLine()
        val fb = formTally.getValue(EvalForm.NOISY)
        val fa = formTally.getValue(EvalForm.NAME)
        val bTop3a = fa.getValue(EvalMethod.B).top3.toDouble() / max(1, fa.getValue(EvalMethod.B).n)
        val bTop3b = fb.getValue(EvalMethod.B).top3.toDouble() / max(1, fb.getValue(EvalMethod.B).n)
        appendLine(
            "- **발음 보정과 오류 섞인 이름**: B의 Top-3가 (a) ${pct1(bTop3a)} → (b) ${pct1(bTop3b)}로 " +
                (if (bTop3b >= bTop3a - 0.05) "거의 그대로다" else "떨어진다") +
                " (글자 일치 A는 (b)에서 Top-1 ${pct(fb.getValue(EvalMethod.A).top1, fb.getValue(EvalMethod.A).n)}). 발음만으로는 지점이 여럿인 브랜드(스타벅스·성심당·현대백화점)와 범주어에서 어느 곳인지 가리지 못해, 동점이면 가까운 곳을 고른다.",
        )
        appendLine(
            "- **개인 기록과 ‘어느 지점’**: 같은 질의에서 C만 맞힌 것 ${cb.first}건, B만 맞힌 것 ${cb.second}건" +
                (if (cb.first > cb.second) " — 시간대·경로·최근 방문이 같은 브랜드의 지점과 범주어의 대상을 가른다." else "."),
        )
        val autoUp = od.auto - oc.auto
        val top1Diff = od.top1 - oc.top1
        appendLine(
            "- **차 안 학습(C → D)**: 바로 실행 ${if (autoUp >= 0) "+" else ""}${autoUp}건, Top-1 ${if (top1Diff >= 0) "+" else ""}${top1Diff}건, 잘못된 자동 실행 ${oc.wrongAuto}건 → ${od.wrongAuto}건" +
                (if (autoUp > 0 && kotlin.math.abs(top1Diff) <= 2) " — 학습은 정확도보다 확신(확인 질문을 건너뛰는 비율)을 바꿨다." else "."),
        )
        val dOnlyMiss = main.records.filter { it.form.inOverall && it.hit(EvalMethod.C) && !it.hit(EvalMethod.D) }
        val prefPulled = dOnlyMiss.filter { r -> r.outcomes.getValue(EvalMethod.D).ranked.firstOrNull()?.tags?.contains("지난번 선택") == true }
        if (prefPulled.isNotEmpty()) {
            val ex = prefPulled.first()
            val pulledTo = PersonalEval.nameOf(ex.outcomes.getValue(EvalMethod.D).ranked.first().id)
            appendLine(
                "- **선택 기억이 끈 경우** (D만 틀린 ${dOnlyMiss.size}건 중 ${prefPulled.size}건): ‘${ex.spoken}’${Hangul.ro(ex.spoken)} $pulledTo${eul(pulledTo)} 고른 기억이 " +
                    "${dowKo[ex.trip.visit.dow]}요일 ${hhmm(ex.trip.s.minute)}의 같은 말도 그쪽으로 끌었다 (실제: ${PersonalEval.nameOf(ex.truth)}). " +
                    "선택 기억은 평일·주말로 나눈 ‘고른 몫’이지만, 같은 평일 안의 출근길·퇴근길처럼 시간대는 가르지 않는다.",
            )
        }
        val (_, dB) = main.endBrains.getValue(EvalForm.NOISY_REPEAT)
        val (_, dA) = main.endBrains.getValue(EvalForm.NAME)
        appendLine(
            "- **무엇을 기억했나** (28일 뒤 D): 이름을 그대로 말한 (a)에서 남은 선택 기억 ${dA.choiceMemory.size}개·표현 ${dA.aliases.size}개 — 정확한 이름(스타벅스·성심당…)은 이미 알아들으므로 기억하지 않는다. " +
                "같은 오인식이 반복되는 (b′)에서는 선택 기억 ${dB.choiceMemory.size}개·표현 ${dB.aliases.size}개" +
                (dB.aliases.entries.take(3).takeIf { it.isNotEmpty() }?.joinToString(", ", " (예: ", ")") { (k, v) -> "‘$k’ = ${PersonaFactory.poiById(v)?.spoken ?: v}" } ?: "") + ".",
        )
        val wrongAutoD = main.records.filter { it.form.inOverall && it.outcomes.getValue(EvalMethod.D).tier == Tier.AUTO && !it.hit(EvalMethod.D) }
        if (wrongAutoD.isNotEmpty()) {
            val w = wrongAutoD.first()
            val went = PersonalEval.nameOf(w.outcomes.getValue(EvalMethod.D).ranked.first().id)
            appendLine(
                "- **D의 잘못된 자동 실행 ${wrongAutoD.size}건**: 예) ${situationLabel(w.trip)}, “${w.spoken}” → $went${Hangul.ro(went)} 바로 출발 (실제: ${PersonalEval.nameOf(w.truth)}). " +
                    "앱은 바로 실행 뒤 10초 동안 “아니야”로 되돌리고 나머지 후보를 HUD에 띄우므로, 이 경우 운전자 부담은 2회 발화다.",
            )
        }
        val bakery = scenarios[2].third
        val far = bakery.candidates.firstOrNull { (it.km ?: 0.0) > 50 && it.prob >= 0.05 }
        if (far != null) {
            appendLine(
                "- **아주 먼 곳도 크게 깎이지 않는다**: 거리 특징이 0~1 범위라 아무리 멀어도 점수가 최대 ${f2(PersonalBrain.DEFAULT_WEIGHTS[3])}점(거리 가중치)만 줄어든다. " +
                    "시연 P_DAEJEON의 고속도로 위 “빵집 들렀다 가자”에서 ${String.format(L, "%.0f", far.km)}km 떨어진 ${far.poi.spoken}${iga(far.poi.spoken)} ${String.format(L, "%.0f%%", 100 * far.prob)} (평일 저녁 단골이라 ‘자주·최근’ 점수가 높다). " +
                    "1위는 맞고 행동이 ‘한 번 확인’이라 틀린 답은 아니지만, 경유지 질의에서 경로와 먼 곳을 거르면 확신이 오를 여지가 있다.",
            )
        }
        val destAsStop = main.records.firstOrNull { r ->
            r.form.inOverall && r.trip.onHighway && r.outcomes.getValue(EvalMethod.C).ranked.take(3).any { it.id == "parents" }
        }
        if (destAsStop != null) {
            val x = destAsStop.outcomes.getValue(EvalMethod.C).ranked.first { it.id == "parents" }
            appendLine(
                "- **지금 목적지가 경유지 후보로 나온다**: 대전 본가로 안내 중에 “${destAsStop.utterance}”라고 했는데 대전 본가가 ${String.format(L, "%.0f%%", 100 * x.value)}로 3위 안에 든다. " +
                    "경유지(‘들렀다 가자’) 질의에서는 현재 목적지를 후보에서 빼는 규칙을 넣을 수 있다 — 이번 평가에서는 엔진을 바꾸지 않았다.",
            )
        }
        appendLine()

        // ── 5. 고친 엔진 버그
        appendLine("## 5. 이번 평가에서 찾아 고친 엔진 버그")
        appendLine()
        appendLine(
            "1. **`QueryParser` 말머리 군말 제거**: ‘음·어·그’ 같은 군말을 단어 경계 없이 떼어 ‘어린이집 들렀다 가자’ → ‘린이집’, ‘그랜드 하얏트’ → ‘랜드 하얏트’, " +
                "‘어마 집’(엄마 집의 오인식) → ‘마 집’으로 잘렸다. 군말 뒤에 띄어쓰기·쉼표가 있을 때만 떼도록 정규식 한 줄을 고쳤다 (회귀 테스트 `parse_fillerIsStrippedOnlyAsSeparateWord`).",
        )
        val oodOld = ood.count { PersonalEval.oldRuleAuto(it.outcomes.getValue(EvalMethod.C)) }
        val oodNow = ood.count { it.outcomes.getValue(EvalMethod.C).tier == Tier.AUTO }
        val inDict = main.records.filter { it.form.inOverall }.map { it to it.outcomes.getValue(EvalMethod.C) }
        val inOld = inDict.count { PersonalEval.oldRuleAuto(it.second) }
        val inNow = inDict.count { it.second.tier == Tier.AUTO }
        appendLine(
            "2. **`PersonalBrain` 바로 실행 판단**: 확률은 후보끼리의 상대값이라, 사전에 없는 곳을 말해 소리가 약하게만 비슷한 후보 하나가 남으면 확률이 1.0이 되어 확인 없이 출발했다 " +
                "(‘다이소’ → 성심당 본점, ‘한강공원’ → 올림픽공원). 바로 실행에 ‘1위 발음 유사도 ≥ ${PersonalBrain.AUTO_MIN_PHON}’ 조건을 더했다 (`AUTO_MIN_PHON`, 회귀 테스트 `weakSoundAlike_neverStartsWithoutAsking`). " +
                "순위·확률은 그대로이고, 조건에 못 미치면 ‘한 번 확인’이 된다.",
        )
        appendLine(
            "   - 영향 (C, 같은 질의의 반사실 비교): 사전에 없는 곳 ${ood.size}개 중 바로 실행 ${oodOld}건 → ${oodNow}건 · 사전에 있는 곳 ${inDict.size}개 중 바로 실행 ${inOld}건 → ${inNow}건.",
        )
        val gate = PersonalEval.gateCheck((200..209).toList())
        val demotedNote = when {
            gate.demoted == 0 -> "기준 미달로 ‘한 번 확인’이 된 것은 없다"
            gate.demotedCorrect == gate.demoted -> "기준 미달로 ‘한 번 확인’이 된 것 ${gate.demoted}건(모두 정답 — 운전자가 “응” 한 번 더 말하는 비용)"
            else -> "기준 미달로 ‘한 번 확인’이 된 것 ${gate.demoted}건(그중 정답 ${gate.demotedCorrect}건)"
        }
        val wrongNote = if (gate.oldWrongAuto == 0) "옛 규칙에서도 잘못된 바로 실행은 0건" else
            "옛 규칙의 잘못된 바로 실행 ${gate.oldWrongAuto}건 중 막힌 것 ${gate.wrongBlocked}건" +
                (if (gate.wrongBlocked < gate.oldWrongAuto) " (남은 것은 발음은 정확한데 개인 기록이 다른 지점을 고른 경우)" else "")
        appendLine(
            "   - 기준값 ${PersonalBrain.AUTO_MIN_PHON}은 seed 99~103의 분포를 보고 정했고, 겹치지 않는 seed ${gate.seeds.first()}~${gate.seeds.last()}와 다른 사전 밖 장소 ${PersonalEval.OUT_OF_DICTIONARY_HELD_OUT.size}곳으로 확인했다: " +
                "사전에 있는 곳(C)에서 옛 규칙의 바로 실행 ${gate.oldAuto}건 중 $demotedNote, $wrongNote. " +
                "사전에 없는 곳 ${gate.oodN}개(C·D, seed ${gate.seeds.first()}의 28일 뒤 엔진)에서 옛 규칙의 바로 실행 ${gate.oodOldAuto}건 중 ${gate.oodBlocked}건이 막혔다.",
        )
        appendLine()
        appendLine("### 코드 리뷰에서 고친 학습·대화 규칙 (위 숫자는 모두 고친 뒤)")
        appendLine()
        val demo = PersonalEval.memoryDemo()
        appendLine(
            "3. **선택 기억 = 평일·주말로 나눈 ‘고른 몫’** (`PersonalBrain.choiceShare`): 전에는 같은 말로 한 번이라도 고른 곳의 ‘선호’가 1.0이라, 평일에 고른 곳이 주말의 같은 말까지 끌고 갔다(지난 평가의 D만 틀린 질의). " +
                "이제 선호 = 같은 요일 종류(평일·주말)에 그 말로 고른 기록 중 이 곳의 몫, 그 요일 종류의 기록이 없으면 전체 몫의 절반이다. " +
                "시연 좌표로 계산: 평일 출근길에 ‘스타버스’로 판교점을 ${demo.weekdayPicks}번 고른 뒤 토요일 10:50 집 앞에서 같은 말 → 판교점 선호 ${f2(demo.satPrefPangyo)}, " +
                "1위 ${PersonalEval.nameOf(demo.satTop)} ${pct1(demo.satTopProb)} · 토요일에 잠실새내역점을 한 번 고른 뒤 → 토요일 1위 ${PersonalEval.nameOf(demo.satTopAfter)}, 평일 출근길 1위 ${PersonalEval.nameOf(demo.weekdayTopAfter)} · " +
                "평일에 판교 2번·강남 1번이면 선호 ${f2(demo.sharePangyo)} / ${f2(demo.shareGangnam)}. 거절(“아니야”)하면 그 곳의 횟수를 한 번 덜어 낸다 (회귀 테스트 `choiceMemory_isShareSplitByWeekdayWeekend`).",
        )
        appendLine(
            "4. **오인식을 바로잡은 선택만 기억** (`PersonalBrain.learn`): 전에는 고른 곳이 무엇이든 ‘들린 말 → 장소’ 표현과 선택 기억을 남겨, 확인 단계의 “아니, 병원”이 ‘스타벅스 = 소아과’로 기억됐다. " +
                "이제 고른 곳의 발음 유사도가 ${PersonalBrain.AUTO_MIN_PHON} 이상이고 들린 말이 어떤 장소의 정확한 이름도 아닐 때만 남기며, 이미 기억한 표현은 그 말로 새 곳을 고른 횟수가 옛 곳보다 많아져야 바뀐다. " +
                "가중치 학습은 그대로 한다 (회귀 테스트 `learn_onlyMemorizesCorrectedMishearings`, `learn_aliasNeedsMoreVotesToChange`).",
        )
        appendLine(
            "5. **대답 해석** (`QueryParser.reply`·`matchOption`): “네 가 주세요”·“그렇게 해 줘”·“거기로 가자”·“1번 안내해 줘”·“네 번째”가 대답으로 읽히고(4번 이상은 번호 그대로 넘겨 범위 안내), 혼자 말한 “어”는 ‘네’가 아니다. " +
                "이름 일부로 고를 때는 긴 이름 안의 아무 부분이 아니라 지점 이름 단어·별칭과 비교해 ‘그거요’·‘빨리’·‘노래’가 엉뚱한 지점에 붙지 않는다 (회귀 테스트 `reply_*`, `matchOption_*`). " +
                "재검토에서 “아냐”·“그거 아니야”·“잘못 들었어”·“노노”(부정), “좋지”·“그러자”·“가 보자”·“응 고마워”(긍정), 제안한 곳의 이름(“성심당 맞아”)도 대답으로 읽고, " +
                "“뭐라고?”·“잠깐만”·“어…”(다시 묻기·기다리기·머뭇거림)는 질문을 지운 채 새 명령으로 넘기지 않는다 — 질문을 내리는 것은 진짜 새 명령뿐이다 (회귀 테스트 `DialogueParsingTest`, `DialogueStoreTest`).",
        )
        appendLine(
            "6. **섞인 명령 거르기** (`QueryParser.parse`, 의도 엔진): “졸려서 휴게소 들렀다 가자”, “에어컨 켜고 집으로 가자”, “스타벅스 말고 회사로 가자”처럼 다른 의도의 말·부정·앞 절(…고/…서/…는데)이 섞이면 장소 명령으로 보지 않는다. " +
                "재검토에서 ‘…고’는 동사 줄기 뒤(먹고·켜고·데리고)에서만 앞 절로 보고(‘잠실고 정문’·‘망고 식스’·‘중고 서점’은 장소), “빨리 가자”·“그냥 가자”처럼 부사뿐인 말은 장소 명령이 아니며(앞에 붙은 부사는 뗀다), " +
                "“성심당 들렀다 가 줘”·“들러 주세요” 같은 부탁 꼴은 경유지로 읽는다. 두 음절 이하의 짧은 말은 이름 안의 일부가 아니라 이름의 단어 전체와 소리를 비교한다(‘빨리’ ↔ ‘…밸리점’ 방지). " +
                "평가 문장(${n}개)의 해석 결과는 위 ‘문장 해석’ 그대로다 (회귀 테스트 `parse_rejectsMixedCommands`, `appIntentEngine_mixedCommandsGoToTheirOwnIntent`, `parse_d6_*`, `parse_d7_*`, `parse_d10_*`).",
        )
        appendLine()

        // ── 6. 한계
        appendLine("## 6. 한계 — 이 숫자가 말하지 않는 것")
        appendLine()
        appendLine("- **합성 페르소나**: 평가 여정은 학습 기록과 같은 생성기(같은 생활 패턴)에서 나왔다. 실제 사람은 덜 규칙적이고 새 장소에도 가므로, 개인 기록을 쓰는 C·D에 유리한 조건이다. 실제 주행 로그(NaviHelper 목적지 이력·주차 위치)로 다시 재야 한다.")
        appendLine("- **흉내 낸 음성인식 오류**: 자모 규칙으로 ‘비슷한 소리로 틀리기’만 흉내 냈다. Pleos STT의 실제 오류 분포(단어 통째 바뀜, 영어 브랜드 표기, 소음 속 음절 누락 등)와 다르며, 실제 녹음으로는 재지 않았다.")
        appendLine("- **한 사람, 장소 사전 ${PersonalEval.pois.size}곳**: 이미 아는 장소를 다시 찾아가는 경우만 정확도를 쟀다. 처음 가는 곳은 위 ‘처음 가는 곳’ 표처럼 거절·되묻기 비율만 봤고, 그 뒤 내비 검색으로 넘기는 연결은 앱에 아직 없다. 사전이 작아서 소리 오류에 강하게 나온 면도 있다 — 실제 POI DB를 뒤지면 비슷한 소리의 후보가 훨씬 많아 A·B는 더 헷갈린다.")
        appendLine("- **학습 신호는 이상적**: D는 운전자가 늘 맞는 곳을 확인·선택한다고 가정했다(앱 대화 흐름 그대로, 화면에 없는 후보는 학습하지 않음). 잘못 누른 선택이 섞이면 학습이 흔들릴 수 있다.")
        appendLine("- **작은 표본**: 질의 ${n}개라 신뢰구간이 넓다(위 표). 문장은 “<장소> 가자” 틀만 썼고, 차 위치는 직전 방문지로 단순화했다. A·B의 ‘바로 실행’은 결과가 하나뿐일 때로 정의했다(실제 내비 검색은 보통 목록을 보여 준다).")
        appendLine("- **A는 엄격한 기준선**: 오타 허용 없는 글자 일치로 정의했으므로 (b)에서 0%가 나오는 것은 정의상 당연하다. 상용 검색의 오타 보정·초성 검색은 이보다 낫다. 또 A는 이름 글자만 보므로 ‘빵집’·‘카페’처럼 이름에 없는 범주어는 못 찾는다 — 실제 내비의 ‘주변 빵집’ 검색은 B의 (c) 결과(범주 일치 + 가까운 순)에 가깝다.")
        appendLine("- **쉬운 질의가 많다**: 유치원처럼 이름이 하나뿐인 곳의 반복 방문이 질의의 큰 몫이라 모든 방법의 숫자를 함께 끌어올린다. 방법 사이의 차이는 장소별 표에서 보는 것이 정확하다.")
    }

    /** 시연 시나리오 (sim/DriveSimulator.kt P_DAEJEON · P_WEEKDAY · P_WEEKEND) 그대로 */
    private fun scenarioRows(): List<Triple<String, String, Resolution>> {
        val base = PersonaFactory.BASE_DAY
        val parents = PersonalEval.place("parents")
        fun sit(day: Int, h: Int, m: Int, lat: Double, lng: Double, dLat: Double? = null, dLng: Double? = null) =
            Situation(day, java.time.LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value, h * 60 + m, lat, lng, dLat, dLng)
        val daejeon = sit(base, 10, 4, PersonalEval.HIGHWAY_LAT, PersonalEval.HIGHWAY_LNG, parents.lat, parents.lng)
        val weekday = sit(base - 2, 8, 31, 37.4176, 127.1074, 37.4020, 127.1086)
        val weekend = sit(base, 10, 50, 37.5133, 127.1001)
        val learning = PersonalBrain()
        val first = learning.resolve(PlaceQuery("서심다응"), daejeon)
        learning.learn(first, "ssd_station")
        val again = learning.resolve(PlaceQuery("서심다응"), daejeon)
        return listOf(
            Triple("P_DAEJEON 토 10:04 고속도로 (처음)", "서심다응 가자", first),
            Triple("P_DAEJEON — “응” 한 번 뒤", "서심다응 가자", again),
            Triple("P_DAEJEON 토 10:04 고속도로", "빵집 들렀다 가자", PersonalBrain().resolve(PlaceQuery("빵집", StopMode.WAYPOINT, Category.BAKERY), daejeon)),
            Triple("P_WEEKDAY 목 08:31 출근길", "스타벅스 가자", PersonalBrain().resolve(PlaceQuery("스타벅스"), weekday)),
            Triple("P_WEEKEND 토 10:50 집 앞", "스타벅스 가자", PersonalBrain().resolve(PlaceQuery("스타벅스"), weekend)),
        )
    }

    fun write(md: String): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val root = generateSequence(start) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() } ?: start
        val out = File(root, "docs/eval/personal_eval.md")
        out.parentFile.mkdirs()
        out.writeText(md, Charsets.UTF_8)
        return out
    }
}
