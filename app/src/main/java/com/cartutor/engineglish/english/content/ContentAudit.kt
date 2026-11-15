package com.cartutor.engineglish.english.content

import com.cartutor.engineglish.english.core.ContentSource
import com.cartutor.engineglish.english.core.HookTrigger
import com.cartutor.engineglish.english.core.LearnThresholds
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.Track

/**
 * 콘텐츠 품질 점검 — 명세 §11 스키마 + 운전 부하 정책(§4.3 단어 수) + 분량·구성 규칙.
 * 단위 테스트와 디버그 점검에서 쓴다. 문제가 없으면 빈 목록 (문장은 영어 코드 + 문항 id 뿐, 개인 정보 없음).
 */
object ContentAudit {

    /** 트랙·레벨마다 유닛 수 (10문항 × 3 = 30 ≥ 24) */
    const val MIN_UNITS_PER_LEVEL = 3
    const val UNIT_SIZE = LearnThresholds.UNIT_SIZE
    const val MIN_NORMAL_PER_UNIT = 6
    /** 운전 중 TTS 로 읽는 한국어 지시문 최대 길이 */
    const val DRIVE_PROMPT_MAX = 40
    const val PARKED_PROMPT_MAX = 80
    const val EXPLANATION_MAX = 80
    /** 정책 표의 maxAnswerWords (NORMAL 12 · RELAXED 20) */
    const val NORMAL_MAX_WORDS = 12
    const val RELAXED_MAX_WORDS = 20
    const val HOOK_MIN_ITEMS = 3
    const val PRON_FILE_MIN_PER_TYPE = 5
    /** 발음 드릴 세션 한 번 = 6문항 */
    const val PRON_DRILL_SESSION = 6
    const val PLACEMENT_GENERAL_MIN = 8
    const val PLACEMENT_TRACK_MIN = 4
    const val PLACEMENT_POOL_MIN = 12
    const val TODAY_MIN = 2
    const val QUIZ_MIN = 5

    val TOEIC_MIN_WORDS: Map<Level, Int> = mapOf(Level.BEGINNER to 15, Level.INTERMEDIATE to 25, Level.ADVANCED to 35)
    val TOEIC_LISTEN_SEC: Map<Level, Int> = mapOf(Level.BEGINNER to 30, Level.INTERMEDIATE to 40, Level.ADVANCED to 45)

    private val ID_RE = Regex("^[a-z0-9]+(\\.[a-z0-9_]+)+$")
    private val KEYWORD_RE = Regex("^[a-z]+(-[a-z]+)*$")
    private val HANGUL_RE = Regex("[가-힣]")
    private val PERSONAL_RE = Regex("[\\w.+-]+@[\\w-]+\\.\\w+|\\d{2,4}[- ]\\d{3,4}[- ]\\d{4}|https?://|www\\.")
    private val WORD_RE = Regex("[a-z0-9']+")
    private val CHOICE_SKILLS = setOf(Skill.VOCAB, Skill.EXPRESSION, Skill.SENTENCE_BUILD)
    private val ENGLISH_PROMPT_SKILLS = setOf(Skill.LISTEN_REPEAT, Skill.TOEIC_QA, Skill.MINIMAL_PAIR)

    /** 채점기 정규화와 같은 축약형 풀이 (명세 §5.1) — 키워드가 답에 있는지 볼 때 쓴다 */
    private val CONTRACTIONS = mapOf(
        "i'm" to "i am", "you're" to "you are", "it's" to "it is", "that's" to "that is", "let's" to "let us",
        "can't" to "can not", "won't" to "will not", "don't" to "do not", "doesn't" to "does not", "didn't" to "did not",
        "isn't" to "is not", "aren't" to "are not", "i'd" to "i would", "i'll" to "i will", "we'll" to "we will",
        "i've" to "i have", "we're" to "we are", "they're" to "they are", "couldn't" to "could not",
        "wouldn't" to "would not", "shouldn't" to "should not", "what's" to "what is", "there's" to "there is",
        "how's" to "how is",
    )

    /** 전체 점검 */
    fun run(source: JsonContentSource): List<String> {
        val out = ArrayList<String>()
        source.problems.forEach { out += "load $it" }
        source.items().forEach { checkItem(it, out) }
        checkUnits(source, out)
        checkHooks(source, out)
        checkPron(source, out)
        checkPlacement(source, out)
        checkToday(source, out)
        checkQuiz(source, out)
        return out
    }

    /** 유닛 문항 수 — 키 "BUSINESS/BEGINNER/VOCAB" (트랙 파일만) */
    fun counts(source: ContentSource): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (track in Track.entries) for (level in Level.entries) {
            val items = source.units(track, level).flatMap { source.unitItems(it.key) }
            for (skill in Skill.entries) {
                val n = items.count { it.skill == skill }
                if (n > 0) out["${track.name}/${level.name}/${skill.name}"] = n
            }
        }
        return out
    }

    // ───────────── 문항 하나 ─────────────

    fun checkItem(item: LearningItem, out: MutableList<String>) {
        fun bad(msg: String) {
            out += "${item.id}: $msg"
        }
        if (!ID_RE.matches(item.id)) bad("id format")
        val promptMax = if (item.maxLoad == LoadLevel.PARKED) PARKED_PROMPT_MAX else DRIVE_PROMPT_MAX
        if (item.promptKo.isBlank() || item.promptKo.length > promptMax) bad("promptKo length ${item.promptKo.length} > $promptMax")
        if (item.meaningKo.isBlank()) bad("meaningKo blank")
        item.explanationKo?.let { if (it.length > EXPLANATION_MAX) bad("explanationKo length ${it.length}") }

        val needsEn = item.skill in ENGLISH_PROMPT_SKILLS
        if (needsEn && item.promptEn.isNullOrBlank()) bad("promptEn required")
        if (!needsEn && item.promptEn != null) bad("promptEn must be null")

        val n = item.answers.size
        val answersOk = when (item.skill) {
            Skill.VOCAB, Skill.EXPRESSION, Skill.SENTENCE_BUILD -> n in 2..4
            Skill.LISTEN_REPEAT, Skill.TOEIC_QA -> n in 1..2
            Skill.MINIMAL_PAIR -> n == 1
        }
        if (!answersOk) bad("answers count $n")
        if (item.answers.any { it.isBlank() }) bad("blank answer")

        // 키워드: 소문자 원형, 모범 답안에 있어야 함. 표현·문장·따라 말하기는 모든 허용 답에 있어야 정답 판정이 일관된다
        val kwRange = if (item.skill == Skill.TOEIC_QA) 3..8 else 1..3
        if (item.keywords.size !in kwRange) bad("keywords count ${item.keywords.size}")
        val mustContain = when (item.skill) {
            Skill.EXPRESSION, Skill.SENTENCE_BUILD, Skill.LISTEN_REPEAT -> item.answers
            else -> item.answers.take(1)
        }
        for (k in item.keywords) {
            if (!KEYWORD_RE.matches(k)) bad("keyword format '$k'")
            for (a in mustContain) if (!keywordIn(k, a)) bad("keyword '$k' missing in '$a'")
        }

        // HUD 힌트: 모범 답안의 한 단어 (발음 짝은 없음)
        if (item.skill == Skill.MINIMAL_PAIR) {
            if (item.hintWord != null) bad("hintWord must be null")
        } else {
            val h = item.hintWord
            if (h == null || h.contains(' ') || h.lowercase() !in words(item.modelAnswer)) bad("hintWord '$h' not a word of answers[0]")
        }

        // 운전 부하: 모범 답안 단어 수가 정책의 maxAnswerWords 안이어야 그 단계에서 낼 수 있다
        val w = item.answerWords
        if (item.skill == Skill.TOEIC_QA && item.maxLoad == LoadLevel.NORMAL) bad("TOEIC_QA cannot be NORMAL")
        if (item.maxLoad == LoadLevel.NORMAL && w > NORMAL_MAX_WORDS) bad("NORMAL item has $w words")
        if (item.maxLoad == LoadLevel.RELAXED && w > RELAXED_MAX_WORDS) bad("RELAXED item has $w words")

        val needsDistractors = item.skill in CHOICE_SKILLS
        if (needsDistractors && item.distractors.size !in 2..3) bad("distractors count ${item.distractors.size}")
        if (!needsDistractors && item.distractors.isNotEmpty()) bad("unexpected distractors")
        val normalized = item.answers.map { norm(it) }.toSet()
        if (item.distractors.any { norm(it) in normalized }) bad("distractor equals an answer")

        if (item.skill == Skill.TOEIC_QA) {
            if (item.minWords != TOEIC_MIN_WORDS[item.level]) bad("minWords ${item.minWords}")
            if (item.listenSec != TOEIC_LISTEN_SEC[item.level]) bad("listenSec ${item.listenSec}")
        } else if (item.minWords != 0) {
            bad("minWords must be 0")
        }

        if (item.skill == Skill.MINIMAL_PAIR) {
            val c = item.contrast
            if (item.pronFocus == null || c.isNullOrBlank() || c.equals(item.modelAnswer, ignoreCase = true)) bad("minimal pair needs pronFocus and a different contrast")
            if (item.keywords != listOf(item.modelAnswer.lowercase())) bad("minimal pair keyword must be the target")
        } else if (item.pronFocus != null || item.contrast != null) {
            bad("pronFocus/contrast only for MINIMAL_PAIR")
        }

        val english = item.answers + item.distractors + item.keywords + listOfNotNull(item.promptEn, item.hintWord, item.contrast)
        if (english.any { HANGUL_RE.containsMatchIn(it) }) bad("Hangul in an English field")
        if (english.any { it != it.trim() || it.contains("  ") }) bad("spacing in an English field")
        val allText = english + listOfNotNull(item.promptKo, item.meaningKo, item.explanationKo)
        if (allText.any { PERSONAL_RE.containsMatchIn(it) }) bad("looks like personal data")
    }

    // ───────────── 유닛 구성 ─────────────

    private fun checkUnits(source: ContentSource, out: MutableList<String>) {
        for (track in Track.entries) for (level in Level.entries) {
            val where = "${track.name}/${level.name}"
            val units = source.units(track, level)
            if (units.size < MIN_UNITS_PER_LEVEL) out += "$where: ${units.size} units (< $MIN_UNITS_PER_LEVEL)"
            if (units.map { it.key.unit } != (1..units.size).toList()) out += "$where: unit numbers are not 1..n"
            val all = ArrayList<LearningItem>()
            for (u in units) {
                val uw = u.key.id
                val items = source.unitItems(u.key)
                all += items
                if (items.size != UNIT_SIZE || u.itemIds.size != UNIT_SIZE) out += "$uw: ${items.size} items (need $UNIT_SIZE)"
                if (u.titleKo.isBlank() || u.titleEn.isBlank()) out += "$uw: missing title"
                if (items.any { it.track != track || it.level != level || it.unit != u.key.unit }) out += "$uw: item key mismatch"
                if (source.unit(u.key) != u) out += "$uw: unit(key) lookup mismatch"
                val c = items.groupingBy { it.skill }.eachCount()
                if (!mixOk(track, c)) out += "$uw: skill mix $c"
                val normal = items.count { it.maxLoad == LoadLevel.NORMAL }
                if (normal < MIN_NORMAL_PER_UNIT) out += "$uw: only $normal NORMAL items"
            }
            val skills = all.map { it.skill }.toSet()
            for (s in requiredSkills(track)) if (s !in skills) out += "$where: no ${s.name}"
        }
    }

    /** 일반 트랙: VOCAB 3 · EXPRESSION 2 · SENTENCE_BUILD(+비즈니스 TOEIC_QA 최대 1) 3 · LISTEN_REPEAT 1 · MINIMAL_PAIR 1. 토익: 2·1·2·1 + TOEIC_QA 4 */
    fun mixOk(track: Track, c: Map<Skill, Int>): Boolean {
        fun n(s: Skill) = c[s] ?: 0
        return if (track == Track.TOEIC_SPEAKING) {
            n(Skill.VOCAB) == 2 && n(Skill.EXPRESSION) == 1 && n(Skill.SENTENCE_BUILD) == 2 &&
                n(Skill.LISTEN_REPEAT) == 1 && n(Skill.TOEIC_QA) == 4 && n(Skill.MINIMAL_PAIR) == 0
        } else {
            n(Skill.VOCAB) == 3 && n(Skill.EXPRESSION) == 2 && n(Skill.LISTEN_REPEAT) == 1 && n(Skill.MINIMAL_PAIR) == 1 &&
                n(Skill.SENTENCE_BUILD) + n(Skill.TOEIC_QA) == 3 && n(Skill.TOEIC_QA) <= (if (track == Track.BUSINESS) 1 else 0)
        }
    }

    fun requiredSkills(track: Track): Set<Skill> {
        val base = linkedSetOf(Skill.VOCAB, Skill.EXPRESSION, Skill.SENTENCE_BUILD, Skill.LISTEN_REPEAT)
        if (track == Track.TOEIC_SPEAKING) base += Skill.TOEIC_QA else base += Skill.MINIMAL_PAIR
        // 비즈니스는 유닛마다 토익식 질문 1개 (여유 구간 말하기용)
        if (track == Track.BUSINESS) base += Skill.TOEIC_QA
        return base
    }

    // ───────────── 훅 · 발음 · 레벨 테스트 · 오늘의 한 문장 · 퀴즈 ─────────────

    private fun checkHooks(source: ContentSource, out: MutableList<String>) {
        for (t in HookTrigger.entries) {
            val h = source.hook(t)
            if (h == null) {
                out += "hook ${t.name}: missing"
                continue
            }
            if (h.introKo.isBlank() || h.introKo.length > DRIVE_PROMPT_MAX) out += "hook ${t.name}: introKo length ${h.introKo.length}"
            if (h.items.size < HOOK_MIN_ITEMS) out += "hook ${t.name}: ${h.items.size} items (< $HOOK_MIN_ITEMS)"
            for (i in h.items) {
                if (i.contextHook != t) out += "${i.id}: contextHook ${i.contextHook} != ${t.name}"
                if (i.maxLoad != LoadLevel.NORMAL) out += "${i.id}: hook items must be NORMAL"
                if (i.unit != 0) out += "${i.id}: hook items have unit 0"
            }
        }
    }

    private fun checkPron(source: ContentSource, out: MutableList<String>) {
        for (type in PronIssueType.entries) {
            val drills = source.pronDrills(type)
            val fromFile = drills.count { it.id.startsWith("pron.") }
            if (fromFile < PRON_FILE_MIN_PER_TYPE) out += "pron ${type.name}: $fromFile drills in pronunciation.json (< $PRON_FILE_MIN_PER_TYPE)"
            if (drills.size < PRON_DRILL_SESSION) out += "pron ${type.name}: ${drills.size} drills (< $PRON_DRILL_SESSION)"
            if (drills.any { it.skill != Skill.MINIMAL_PAIR || it.pronFocus != type }) out += "pron ${type.name}: wrong item in drills"
            val pairs = drills.map { it.modelAnswer.lowercase() to it.contrast?.lowercase() }
            if (pairs.toSet().size != pairs.size) out += "pron ${type.name}: duplicate pair"
        }
    }

    private fun checkPlacement(source: JsonContentSource, out: MutableList<String>) {
        val pool = source.placementPool()
        for (level in Level.entries) {
            val general = pool.count { it.track == null && it.level == level }
            if (general < PLACEMENT_GENERAL_MIN) out += "placement general/${level.name}: $general (< $PLACEMENT_GENERAL_MIN)"
            for (track in Track.entries) {
                val own = pool.count { it.track == track && it.level == level }
                if (own < PLACEMENT_TRACK_MIN) out += "placement ${track.name}/${level.name}: $own (< $PLACEMENT_TRACK_MIN)"
                val combined = source.placement(track).count { it.level == level }
                if (combined < PLACEMENT_POOL_MIN) out += "placement pool ${track.name}/${level.name}: $combined (< $PLACEMENT_POOL_MIN)"
            }
        }
        for (p in pool) {
            if (p.choices.size !in 3..4) out += "${p.id}: ${p.choices.size} choices"
            if (p.answerIndex !in p.choices.indices) out += "${p.id}: answerIndex"
            if (p.choices.map { it.lowercase() }.toSet().size != p.choices.size) out += "${p.id}: duplicate choices"
            if (p.questionKo.length > PARKED_PROMPT_MAX) out += "${p.id}: questionKo too long"
            if (p.explanationKo.isNullOrBlank()) out += "${p.id}: explanationKo missing"
            if (p.skill == Skill.LISTEN_REPEAT && p.audioEn.isNullOrBlank()) out += "${p.id}: listening item needs audioEn"
            if (p.questionEn?.contains("___") == false && p.skill == Skill.SENTENCE_BUILD) out += "${p.id}: blank sentence needs ___"
        }
        val spread = pool.groupingBy { it.answerIndex }.eachCount()
        if (spread.size < 3) out += "placement: answers not spread over positions $spread"
    }

    private fun checkToday(source: JsonContentSource, out: MutableList<String>) {
        for (track in Track.entries) for (level in Level.entries) {
            val n = source.todaySentences(track, level).size
            if (n < TODAY_MIN) out += "today ${track.name}/${level.name}: $n (< $TODAY_MIN)"
        }
        for (i in source.todaySentences()) {
            if (i.skill != Skill.LISTEN_REPEAT || i.maxLoad != LoadLevel.NORMAL || ContentExtras.TODAY_TAG !in i.tags || i.unit != 0) {
                out += "${i.id}: today sentence must be a NORMAL LISTEN_REPEAT item (unit 0, tagged)"
            }
        }
    }

    private fun checkQuiz(source: ContentSource, out: MutableList<String>) {
        for (track in Track.entries) for (level in Level.entries) {
            val quiz = source.quizItems(track, level)
            if (quiz.size < QUIZ_MIN) out += "quiz ${track.name}/${level.name}: ${quiz.size} (< $QUIZ_MIN)"
            if (quiz.any { it.distractors.size < 2 || it.track != track || it.level != level }) out += "quiz ${track.name}/${level.name}: bad item"
        }
    }

    // ───────────── 단어 처리 ─────────────

    /** 소문자 단어 (아포스트로피 유지, 축약형 풀지 않음) */
    fun words(text: String): List<String> =
        WORD_RE.findAll(text.lowercase().replace('’', '\'')).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()

    /** 채점용 토큰 (축약형을 푼다) */
    fun tokens(text: String): List<String> = words(text).flatMap { w -> CONTRACTIONS[w]?.split(' ') ?: listOf(w) }

    fun norm(text: String): String = tokens(text).joinToString(" ")

    /** 키워드가 답에 있는가 — 채점기처럼 -s/-es/-ed/-d/-ing 변형과 e 탈락·y→ies·자음 겹침을 허용 */
    fun keywordIn(keyword: String, answer: String): Boolean = tokens(answer).any { matchesKeyword(keyword, it) }

    fun matchesKeyword(k: String, t: String): Boolean {
        if (t == k) return true
        if (t == k + "s" || t == k + "es" || t == k + "ed" || t == k + "d" || t == k + "ing") return true
        if (k.endsWith("e") && t == k.dropLast(1) + "ing") return true
        if (k.endsWith("y") && (t == k.dropLast(1) + "ies" || t == k.dropLast(1) + "ied")) return true
        val last = k.lastOrNull() ?: return false
        return t == k + last + "ed" || t == k + last + "ing"
    }
}
