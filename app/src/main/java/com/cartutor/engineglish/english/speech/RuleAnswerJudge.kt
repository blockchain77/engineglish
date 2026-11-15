package com.cartutor.engineglish.english.speech

import com.cartutor.engineglish.english.core.AlignOp
import com.cartutor.engineglish.english.core.AnswerJudge
import com.cartutor.engineglish.english.core.JudgeResult
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.MinimalPair
import com.cartutor.engineglish.english.core.PronIssue
import com.cartutor.engineglish.english.core.PronunciationAnalyzer
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.SpeechThresholds
import com.cartutor.engineglish.english.core.TextNormalizer
import com.cartutor.engineglish.english.core.Verdict
import com.cartutor.engineglish.english.core.WordAlign
import kotlin.math.min

/**
 * 앱 안 규칙 채점기 (명세 §5.2) — 서버 전송 없음, 모델 없음.
 *
 * - 여러 허용 정답 중 가장 가까운 것과 단어 정렬(가중 Levenshtein)해 점수를 낸다.
 * - 자기 수정("…I mean, …")·다시 시작한 말은 마지막 시도를 채점한다.
 * - 키워드는 '고른 정답에 실제로 들어 있는 것'만 필수로 본다 (다른 표현의 정답이 불이익을 받지 않게).
 * - 한국어 화자 발음 문제(R/L 등)가 정답 단어를 바꿨으면 CORRECT 대신 PARTIAL + 발음 피드백.
 * - 문항의 오답 보기(distractors)에 더 가까운 문장은 WRONG (어순이 바뀐 문장 등).
 */
class RuleAnswerJudge(
    private val normalizer: TextNormalizer,
    private val pronunciation: PronunciationAnalyzer,
) : AnswerJudge {

    override fun judge(item: LearningItem, heard: String): JudgeResult {
        val all = normalizer.tokens(heard)
        // 한글 군말("음", "어")은 버린다. 한글만 있으면 영어로 다시 말해 달라고 한다.
        val tokens = all.filterNot { SpeechText.hasHangul(it) }
        val normalized = tokens.joinToString(" ")
        if (tokens.isEmpty()) {
            val fb = if (all.isNotEmpty()) JudgeFeedback.SPEAK_ENGLISH else JudgeFeedback.NO_ANSWER
            return noAnswer(heard, normalized, fb)
        }
        return when (item.skill) {
            Skill.VOCAB -> judgeVocab(item, heard, normalized, tokens)
            Skill.EXPRESSION, Skill.SENTENCE_BUILD ->
                judgeSentence(item, heard, normalized, tokens, item.answers, SpeechThresholds.CORRECT_SCORE)
            Skill.LISTEN_REPEAT -> {
                // 따라 말하기도 핵심어("touch base"의 touch)가 빠지면 PARTIAL
                val targets = (item.answers + listOfNotNull(item.promptEn)).distinct()
                judgeSentence(item, heard, normalized, tokens, targets, SpeechThresholds.LISTEN_REPEAT_CORRECT_SCORE)
            }
            Skill.TOEIC_QA -> judgeToeic(item, heard, normalized, tokens)
            Skill.MINIMAL_PAIR -> judgeMinimalPair(item, heard, normalized, tokens)
        }
    }

    override fun judgeChoice(item: LearningItem, choice: String): JudgeResult {
        val tokens = normalizer.tokens(choice)
        val norm = tokens.joinToString(" ")
        fun key(t: List<String>) = if (item.skill == Skill.VOCAB) stripLead(t) else t
        val idx = item.answers.indexOfFirst { key(normalizer.tokens(it)) == key(tokens) }
        return if (idx >= 0 && tokens.isNotEmpty()) {
            val answerTokens = normalizer.tokens(item.answers[idx])
            JudgeResult(
                verdict = Verdict.CORRECT, score = 100, heard = choice, normalized = norm, matchedAnswer = item.answers[idx],
                alignment = WordAligner.align(answerTokens, tokens).ops, feedbackKo = JudgeFeedback.PERFECT,
            )
        } else {
            val model = normalizer.tokens(item.modelAnswer)
            val al = WordAligner.align(model, tokens)
            JudgeResult(
                verdict = Verdict.WRONG, score = 0, heard = choice, normalized = norm, matchedAnswer = item.modelAnswer.ifBlank { null },
                alignment = al.ops, feedbackKo = JudgeFeedback.WRONG,
            )
        }
    }

    // ───────── 문장형 (EXPRESSION · SENTENCE_BUILD · LISTEN_REPEAT) ─────────

    private class Match(
        val attempt: List<String>,
        val start: Int,
        val answerIndex: Int,
        val answerRaw: String,
        val answerTokens: List<String>,
        val alignment: WordAligner.Result,
    )

    private fun judgeSentence(
        item: LearningItem, heard: String, normalized: String, tokens: List<String>,
        answers: List<String>, correctAt: Int,
    ): JudgeResult {
        val best = bestMatch(tokens, answers) ?: return wrongWithoutAnswers(heard, normalized)
        val keywords = keywordTokens(item)
        val effective = keywords.filter { containsSeq(best.answerTokens, it) }
        val missing = effective.filterNot { containsSeq(best.attempt, it) }
        val hit = effective.size - missing.size
        val raw = best.alignment.score
        var score = raw
        var verdict = when {
            score >= correctAt && missing.isEmpty() -> Verdict.CORRECT
            score >= SpeechThresholds.PARTIAL_SCORE -> Verdict.PARTIAL
            effective.isNotEmpty() && hit * 2 >= effective.size -> Verdict.PARTIAL
            else -> Verdict.WRONG
        }
        if (missing.isNotEmpty()) score = min(score, correctAt - 1)
        val pron = pronunciation.analyze(item, best.alignment.ops)
        if (verdict == Verdict.CORRECT && pron.any { it.confidence >= STRONG_PRON }) {
            verdict = Verdict.PARTIAL
            score = min(score, correctAt - 1)
        }
        // 발화 전체가 오답 보기(그럴듯한 틀린 문장)에 더 가까우면 틀린 것 — 어순·뜻이 바뀐 문장을 잡는다
        // (자기 수정·다시 시작 후보는 쓰지 않는다: 오답 보기를 '앞말 버리기'로 정답처럼 만들지 않게)
        if (verdict != Verdict.WRONG && item.distractors.isNotEmpty()) {
            val trap = fullScore(tokens, item.distractors)
            if (trap >= DISTRACTOR_MATCH_SCORE && trap > fullScore(tokens, answers)) {
                verdict = Verdict.WRONG
                score = min(score, WRONG_SCORE_CAP)
            }
        }
        val missingWords = missing.map { it.joinToString(" ") }
        // 엉뚱한 문장끼리의 치환에서 나온 추정은 발음 프로필을 어지럽히므로 WRONG 에는 싣지 않는다
        val issues = if (verdict == Verdict.WRONG) emptyList() else pron
        return JudgeResult(
            verdict = verdict, score = score, heard = heard, normalized = normalized, matchedAnswer = best.answerRaw,
            alignment = best.alignment.ops, missingKeywords = missingWords, pronIssues = issues,
            feedbackKo = JudgeFeedback.sentence(verdict, score, missingWords, issues, best.alignment.ops),
        )
    }

    private fun bestMatch(tokens: List<String>, answers: List<String>): Match? {
        val base = SelfCorrection.attempts(tokens)
        var best: Match? = null
        for ((ai, raw) in answers.withIndex()) {
            val at = normalizer.tokens(raw)
            if (at.isEmpty()) continue
            val attempts = (base + SelfCorrection.answerRestarts(tokens, at.first())).distinctBy { it.start to it.end }
            for (a in attempts) {
                val m = Match(a.tokens, a.start, ai, raw, at, WordAligner.align(at, a.tokens))
                val b = best
                if (b == null || better(m, b)) best = m
            }
        }
        return best
    }

    /** 발화 전체(자기 수정 후보 없이)와 가장 잘 맞는 점수 */
    private fun fullScore(tokens: List<String>, candidates: List<String>): Int =
        candidates.maxOfOrNull { c ->
            val ct = normalizer.tokens(c)
            if (ct.isEmpty()) 0 else WordAligner.align(ct, tokens).score
        } ?: 0

    private fun better(a: Match, b: Match): Boolean = when {
        a.alignment.score != b.alignment.score -> a.alignment.score > b.alignment.score
        a.start != b.start -> a.start > b.start
        else -> a.answerIndex < b.answerIndex
    }

    // ───────── VOCAB ─────────

    private fun judgeVocab(item: LearningItem, heard: String, normalized: String, tokens: List<String>): JudgeResult {
        val answers = item.answers.mapNotNull { raw -> stripLead(normalizer.tokens(raw)).takeIf { it.isNotEmpty() }?.let { raw to it } }
        if (answers.isEmpty()) return wrongWithoutAnswers(heard, normalized)
        // 정답 안에 들어 있지 않은 오답 보기 ("traffic" 이 정답이어도 "traffic light" 는 오답)
        val traps = item.distractors.map { stripLead(normalizer.tokens(it)) }
            .filter { d -> d.isNotEmpty() && answers.none { (_, ans) -> indexOfSeq(ans, d) >= 0 } }
        var best: JudgeResult? = null
        var bestStart = -1
        for (attempt in SelfCorrection.attempts(tokens)) {
            val a = stripLead(attempt.tokens)
            val trapped = traps.any { indexOfSeq(a, it) >= 0 }
            for ((raw, ans) in answers) {
                val r = vocabOne(item, heard, normalized, a, raw, ans, trapped)
                val b = best
                val better = b == null || rank(r) > rank(b) ||
                    (rank(r) == rank(b) && r.score > b.score) ||
                    (rank(r) == rank(b) && r.score == b.score && attempt.start > bestStart)
                if (better) {
                    best = r
                    bestStart = attempt.start
                }
            }
        }
        return best ?: wrongWithoutAnswers(heard, normalized)
    }

    private fun vocabOne(
        item: LearningItem, heard: String, normalized: String, a: List<String>, raw: String, ans: List<String>, trapped: Boolean,
    ): JudgeResult {
        val al = WordAligner.align(ans, a)
        fun result(v: Verdict, score: Int, fb: String, pron: List<PronIssue> = emptyList()) = JudgeResult(
            verdict = v, score = score, heard = heard, normalized = normalized, matchedAnswer = raw,
            alignment = al.ops, pronIssues = pron, feedbackKo = fb,
            missingKeywords = if (v == Verdict.CORRECT) emptyList() else listOf(ans.joinToString(" ")),
        )
        if (a.isEmpty()) return result(Verdict.WRONG, 0, JudgeFeedback.WRONG)
        if (a.size == ans.size && a.indices.all { Homophones.same(a[it], ans[it]) }) {
            return result(Verdict.CORRECT, 100, JudgeFeedback.PERFECT)
        }
        if (trapped) return result(Verdict.WRONG, min(al.score, WRONG_SCORE_CAP), JudgeFeedback.WRONG, pronunciation.analyze(item, al.ops))
        val at = indexOfSeq(a, ans)
        if (at >= 0) {
            // a/an/the/to 는 덧붙은 말로 세지 않는다 ("it's a traffic jam")
            val extras = (a.subList(0, at) + a.subList(at + ans.size, a.size)).filterNot { it in LEAD }
            val score = 100 - 5 * extras.size
            // "it is postpone", "I think postpone", 말 더듬기 "post… postpone" 은 정답. 내용어가 붙으면 다른 말일 수 있다
            val harmless = extras.all { x -> x in FRAME_WORDS || (x.length >= 2 && ans.any { it.length > x.length && it.startsWith(x) }) }
            return if (extras.size <= VOCAB_MAX_EXTRA_WORDS && harmless) result(Verdict.CORRECT, score, JudgeFeedback.correct(score))
            else result(Verdict.PARTIAL, SpeechThresholds.VOCAB_PARTIAL_SCORE, JudgeFeedback.partial(JudgeFeedback.SAY_JUST_THE_WORD))
        }
        if (a.size == ans.size && a.indices.all { Morph.matches(a[it], ans[it]) }) {
            return result(Verdict.PARTIAL, SpeechThresholds.VOCAB_PARTIAL_SCORE, JudgeFeedback.partial(JudgeFeedback.form(ans.joinToString(" "))))
        }
        val pron = pronunciation.analyze(item, al.ops)
        if (pron.isNotEmpty() && a.size == ans.size && subsExplained(al.ops, pron)) {
            return result(Verdict.PARTIAL, SpeechThresholds.VOCAB_PARTIAL_SCORE, JudgeFeedback.partial(PronFeedback.spokenKo(pron.first()) + "."), pron)
        }
        return result(Verdict.WRONG, min(al.score, WRONG_SCORE_CAP), JudgeFeedback.WRONG)
    }

    /** 모든 틀린 칸이 발음 문제나 형태 변화로 설명되나 (VOCAB 부분 점수 조건) */
    private fun subsExplained(ops: List<WordAlign>, pron: List<PronIssue>): Boolean = ops.all { op ->
        when (op.op) {
            AlignOp.MATCH -> true
            AlignOp.SUB -> pron.any { it.expectedWord == op.expected } ||
                (op.expected != null && op.heard != null && Morph.variant(op.expected, op.heard))
            else -> false
        }
    }

    // ───────── TOEIC_QA ─────────

    private fun judgeToeic(item: LearningItem, heard: String, normalized: String, tokens: List<String>): JudgeResult {
        if (tokens.size < SpeechThresholds.TOEIC_MIN_ANSWER_WORDS) return noAnswer(heard, normalized, JudgeFeedback.TOO_SHORT)
        val keywords = keywordTokens(item)
        val missing = keywords.filterNot { containsSeq(tokens, it) }
        val k = if (keywords.isEmpty()) 1.0 else (keywords.size - missing.size).toDouble() / keywords.size
        val l = if (item.minWords <= 0) 1.0 else min(1.0, tokens.size.toDouble() / item.minWords)
        val notes = ToeicGrammar.check(tokens)
        val g = (1.0 - 0.2 * notes.size).coerceAtLeast(0.0)
        val score = Math.round(100.0 * (0.45 * k + 0.35 * l + 0.2 * g)).toInt().coerceIn(0, 100)
        val verdict = when {
            score >= SpeechThresholds.TOEIC_CORRECT_SCORE -> Verdict.CORRECT
            score >= SpeechThresholds.TOEIC_PARTIAL_SCORE -> Verdict.PARTIAL
            else -> Verdict.WRONG
        }
        val missingWords = missing.map { it.joinToString(" ") }
        val fb = when (verdict) {
            Verdict.CORRECT -> if (score >= 95) "완벽한 답변이에요!" else "좋은 답변이에요!"
            Verdict.PARTIAL -> when {
                missingWords.isNotEmpty() -> "괜찮아요. ${SpeechText.quote(missingWords.first())} 같은 말을 넣어 보세요."
                l < 1.0 -> "괜찮아요. 조금 더 길게 말해 보세요."
                notes.isNotEmpty() -> "괜찮아요. 문법을 한 군데 다듬어 봐요."
                else -> "괜찮아요. 조금만 더 다듬어 봐요."
            }
            else -> JudgeFeedback.WRONG
        }
        return JudgeResult(
            verdict = verdict, score = score, heard = heard, normalized = normalized, matchedAnswer = item.modelAnswer.ifBlank { null },
            alignment = emptyList(), missingKeywords = missingWords, pronIssues = emptyList(),
            feedbackKo = fb, grammarNotesKo = notes,
        )
    }

    // ───────── MINIMAL_PAIR ─────────

    private fun judgeMinimalPair(item: LearningItem, heard: String, normalized: String, tokens: List<String>): JudgeResult {
        val targetRaw = item.promptEn?.takeIf { it.isNotBlank() } ?: item.modelAnswer
        val targets = (listOf(targetRaw) + item.answers).map { normalizer.tokens(it) }.filter { it.isNotEmpty() }.distinct()
        if (targets.isEmpty()) return wrongWithoutAnswers(heard, normalized)
        val target = targets.first()
        val contrast = item.contrast?.let { normalizer.tokens(it) }?.takeIf { it.isNotEmpty() }
        val lastTarget = targets.maxOf { lastIndexOfSeq(tokens, it, homophones = true, skipNegated = false) }
        val lastContrast = if (contrast == null) -1 else lastIndexOfSeq(tokens, contrast, homophones = false, skipNegated = true)
        val al = WordAligner.align(target, tokens)
        val targetWord = target.joinToString(" ")
        return when {
            lastTarget >= 0 && lastTarget >= lastContrast -> JudgeResult(
                verdict = Verdict.CORRECT, score = 100, heard = heard, normalized = normalized, matchedAnswer = targetRaw,
                alignment = al.ops, feedbackKo = JudgeFeedback.PERFECT,
            )
            lastContrast >= 0 -> {
                val contrastWord = contrast?.joinToString(" ").orEmpty()
                val type = item.pronFocus ?: pronunciation.analyze(item, listOf(WordAlign(targetWord, contrastWord, AlignOp.SUB))).firstOrNull()?.type
                val issue = type?.let {
                    PronIssue(it, targetWord, contrastWord, MINIMAL_PAIR_CONFIDENCE, pronunciation.tip(it), MinimalPair(targetWord, contrastWord))
                }
                JudgeResult(
                    verdict = Verdict.WRONG, score = 0, heard = heard, normalized = normalized, matchedAnswer = targetRaw,
                    alignment = al.ops, missingKeywords = listOf(targetWord), pronIssues = listOfNotNull(issue),
                    feedbackKo = if (issue != null) "아쉬워요. " + PronFeedback.spokenKo(issue) + "." else JudgeFeedback.WRONG,
                )
            }
            else -> {
                // 짧게 말했을 때만 발음 추정 (문장으로 엉뚱하게 답하면 그냥 WRONG)
                val pron = if (tokens.size <= target.size + 1) pronunciation.analyze(item, al.ops) else emptyList()
                val similar = pron.isNotEmpty() || al.score >= SpeechThresholds.PARTIAL_SCORE
                if (similar) {
                    val score = min(al.score, SpeechThresholds.VOCAB_PARTIAL_SCORE)
                    JudgeResult(
                        verdict = Verdict.PARTIAL, score = score, heard = heard, normalized = normalized, matchedAnswer = targetRaw,
                        alignment = al.ops, missingKeywords = listOf(targetWord), pronIssues = pron,
                        feedbackKo = JudgeFeedback.partial(pron.firstOrNull()?.let { PronFeedback.spokenKo(it) + "." }),
                    )
                } else {
                    JudgeResult(
                        verdict = Verdict.WRONG, score = min(al.score, WRONG_SCORE_CAP), heard = heard, normalized = normalized,
                        matchedAnswer = targetRaw, alignment = al.ops, missingKeywords = listOf(targetWord), feedbackKo = JudgeFeedback.WRONG,
                    )
                }
            }
        }
    }

    // ───────── 공용 ─────────

    private fun keywordTokens(item: LearningItem): List<List<String>> =
        item.keywords.map { normalizer.tokens(it) }.filter { it.isNotEmpty() }.distinct()

    private fun noAnswer(heard: String, normalized: String, fb: String) = JudgeResult(
        verdict = Verdict.NO_ANSWER, score = 0, heard = heard, normalized = normalized, matchedAnswer = null, feedbackKo = fb,
    )

    /** 콘텐츠에 정답이 없을 때 (검증기가 걸러야 하지만 앱이 죽으면 안 된다) */
    private fun wrongWithoutAnswers(heard: String, normalized: String) = JudgeResult(
        verdict = Verdict.WRONG, score = 0, heard = heard, normalized = normalized, matchedAnswer = null, feedbackKo = JudgeFeedback.WRONG,
    )

    private fun rank(r: JudgeResult): Int = when (r.verdict) {
        Verdict.CORRECT -> 3
        Verdict.PARTIAL -> 2
        Verdict.WRONG -> 1
        Verdict.NO_ANSWER -> 0
    }

    companion object {
        /** 이 확신 이상의 발음 문제가 있으면 CORRECT → PARTIAL */
        const val STRONG_PRON = 0.8f
        const val MINIMAL_PAIR_CONFIDENCE = 0.9f
        const val VOCAB_MAX_EXTRA_WORDS = 2
        const val WRONG_SCORE_CAP = 40
        /** 오답 보기와 이 점수 이상 맞고, 정답보다 더 가까우면 WRONG */
        const val DISTRACTOR_MATCH_SCORE = 80
        private val LEAD = setOf("a", "an", "the", "to")

        /** VOCAB 정답 앞뒤에 붙어도 되는 틀 말 ("it is …", "I think …", "… in English") */
        private val FRAME_WORDS = setOf(
            "it", "is", "a", "an", "the", "i", "think", "that", "this", "my", "answer", "word", "be", "to", "am", "are",
            "was", "called", "in", "english", "means", "mean", "guess", "maybe", "probably", "okay", "yes", "yeah", "oh",
            "just", "say", "we", "you",
        )

        /** VOCAB: 앞의 a/an/the/to 는 빼고 본다 ("to postpone" = "postpone") */
        internal fun stripLead(t: List<String>): List<String> {
            var i = 0
            while (i < t.size - 1 && t[i] in LEAD) i++
            return if (i == 0) t else t.subList(i, t.size).toList()
        }

        internal fun indexOfSeq(t: List<String>, seq: List<String>): Int {
            if (seq.isEmpty() || t.size < seq.size) return -1
            for (i in 0..t.size - seq.size) if (seq.indices.all { Homophones.same(t[i + it], seq[it]) }) return i
            return -1
        }

        /** 키워드 포함 (연속, -s/-ed/-ing 허용) */
        internal fun containsSeq(t: List<String>, kw: List<String>): Boolean {
            if (kw.isEmpty() || t.size < kw.size) return false
            for (i in 0..t.size - kw.size) if (kw.indices.all { Morph.matches(t[i + it], kw[it]) }) return true
            return false
        }

        /** 마지막으로 나온 위치. skipNegated = "not light" 처럼 부정한 것은 셈하지 않음 */
        internal fun lastIndexOfSeq(t: List<String>, seq: List<String>, homophones: Boolean, skipNegated: Boolean): Int {
            if (seq.isEmpty() || t.size < seq.size) return -1
            for (i in t.size - seq.size downTo 0) {
                val hit = seq.indices.all { k -> if (homophones) Homophones.same(t[i + k], seq[k]) else t[i + k] == seq[k] }
                if (!hit) continue
                if (skipNegated && i > 0 && (t[i - 1] == "not" || t[i - 1] == "no")) continue
                return i
            }
            return -1
        }
    }
}

/** 채점 피드백 문장 (TTS 로 읽는 짧은 한국어 한 문장 — 조사 실수가 없도록 ‘…’ 부분이 / ‘…’라고 형태만 쓴다) */
internal object JudgeFeedback {
    const val NO_ANSWER = "잘 안 들렸어요."
    const val SPEAK_ENGLISH = "영어로 답해 주세요."
    const val TOO_SHORT = "조금 더 길게, 한 문장 이상 말해 보세요."
    const val CORRECT = "정확해요!"
    const val PERFECT = "완벽해요!"
    const val WRONG = "아쉬워요. 이렇게 말해요."
    const val PARTIAL = "거의 맞았어요."
    const val SAY_JUST_THE_WORD = "정답 단어만 짧게 말해 보세요."

    fun correct(score: Int): String = if (score >= 95) PERFECT else CORRECT

    fun partial(detail: String?): String = if (detail.isNullOrBlank()) PARTIAL else "$PARTIAL $detail"

    fun form(word: String): String = "${SpeechText.quote(word)} 형태로 말해 보세요."

    fun missing(words: List<String>): String = words.take(2).joinToString(", ") { SpeechText.quote(it) } + " 부분이 빠졌어요."

    fun sentence(verdict: Verdict, score: Int, missing: List<String>, pron: List<PronIssue>, ops: List<WordAlign>): String = when (verdict) {
        Verdict.CORRECT -> correct(score)
        Verdict.WRONG -> WRONG
        Verdict.NO_ANSWER -> NO_ANSWER
        Verdict.PARTIAL -> partial(
            pron.firstOrNull()?.let { PronFeedback.spokenKo(it) + "." }
                ?: missing.takeIf { it.isNotEmpty() }?.let { missing(it) }
                ?: firstDiff(ops)
        )
    }

    /** 정렬에서 처음 어긋난 곳 하나 */
    fun firstDiff(ops: List<WordAlign>): String? {
        ops.firstOrNull { it.op == AlignOp.SUB }?.let { return "${SpeechText.quote(it.expected.orEmpty())} 대신 ${SpeechText.quote(it.heard.orEmpty())}라고 들렸어요." }
        ops.firstOrNull { it.op == AlignOp.DEL }?.let { return "${SpeechText.quote(it.expected.orEmpty())} 부분이 빠졌어요." }
        if (ops.any { it.op == AlignOp.INS }) return "필요 없는 말이 조금 들어갔어요."
        return null
    }
}

/**
 * 토익 스피킹 문법 휴리스틱 (명세 §5.2) — 규칙마다 한 줄 메모 (화면 표시용).
 * i am + 동사원형 · 3인칭 단수 -s 누락 · did + 과거형 · more + 비교급 · 주어 없이 동사로 시작.
 */
internal object ToeicGrammar {
    private val BASE_VERBS = setOf(
        "agree", "disagree", "go", "come", "want", "need", "think", "work", "drive", "live", "study", "eat", "make",
        "take", "get", "have", "know", "see", "watch", "play", "use", "try", "believe", "prefer", "enjoy", "finish",
        "start", "begin", "leave", "arrive", "visit", "buy", "read", "write", "listen", "speak", "talk", "meet", "help",
        "call", "wait", "stay", "walk", "run", "sleep", "cook", "travel", "commute", "spend", "learn", "teach", "practice",
        "find", "give", "keep", "bring", "send", "pay", "plan", "decide", "hope", "remember", "forget", "understand",
        "mean", "say", "tell", "ask", "look", "become", "change", "move", "carry", "choose", "share", "save", "check",
        "join", "prepare", "improve", "support", "depend", "happen", "recommend", "suggest", "rely", "focus", "relax",
        "love", "feel", "like",
    )

    /** "i am X" 에서 X 가 이것이면 틀린 문장 (형용사로도 쓰는 말은 뺐다) */
    private val I_AM_BAD = BASE_VERBS - setOf("like", "open", "close", "clean", "free", "used")

    private val THIRD_SUBJECTS = setOf("he", "she", "it", "everyone", "everybody", "someone", "somebody", "nobody")
    private val ADVERBS = setOf(
        "always", "usually", "often", "sometimes", "never", "also", "really", "just", "still", "normally",
        "generally", "rarely", "actually", "seldom", "mostly", "only", "even",
    )

    /** 주어 앞에 이 말이 오면 의문문·사역 구문 (does she like, let it go) */
    private val THIRD_PREV_SKIP = setOf(
        "do", "does", "did", "can", "could", "will", "would", "shall", "should", "may", "might", "must",
        "let", "make", "makes", "made", "help", "helps", "see", "saw", "watch", "hear", "heard", "have", "has", "had", "to",
    )

    private val IRREGULAR_PAST = mapOf(
        "went" to "go", "saw" to "see", "ate" to "eat", "came" to "come", "took" to "take", "made" to "make", "got" to "get",
        "had" to "have", "bought" to "buy", "thought" to "think", "told" to "tell", "said" to "say", "drove" to "drive",
        "wrote" to "write", "spoke" to "speak", "met" to "meet", "left" to "leave", "found" to "find", "gave" to "give",
        "knew" to "know", "ran" to "run", "sat" to "sit", "slept" to "sleep", "felt" to "feel", "kept" to "keep",
        "brought" to "bring", "began" to "begin", "chose" to "choose", "forgot" to "forget", "understood" to "understand",
        "paid" to "pay", "sent" to "send", "spent" to "spend", "taught" to "teach", "caught" to "catch", "flew" to "fly",
        "grew" to "grow", "heard" to "hear", "lost" to "lose", "meant" to "mean", "rode" to "ride", "sold" to "sell",
        "stood" to "stand", "swam" to "swim", "threw" to "throw", "woke" to "wake", "wore" to "wear", "did" to "do",
    )
    private val ED_NOT_PAST = setOf(
        "need", "feed", "speed", "bleed", "breed", "seed", "proceed", "succeed", "exceed", "indeed", "shed", "bed",
        "red", "wed", "weed", "hundred", "sacred", "naked", "wicked",
    )
    private val COMPARATIVES = setOf(
        "better", "worse", "bigger", "smaller", "faster", "easier", "harder", "cheaper", "longer", "shorter", "higher",
        "lower", "larger", "nicer", "happier", "busier", "earlier", "later", "closer", "older", "younger", "stronger",
        "weaker", "cooler", "warmer", "quicker", "slower", "safer", "simpler", "cleaner", "healthier", "greater",
        "richer", "newer", "wider", "deeper", "heavier", "louder", "quieter", "smarter", "tougher", "calmer",
    )
    private val SUPERLATIVES = setOf(
        "best", "worst", "biggest", "smallest", "fastest", "easiest", "hardest", "cheapest", "longest", "shortest",
        "highest", "lowest", "largest", "nicest", "happiest", "busiest", "earliest", "latest", "closest", "oldest",
        "youngest", "strongest", "greatest", "newest", "safest", "simplest",
    )
    private val START_VERBS = setOf(
        "think", "go", "like", "want", "need", "drive", "take", "work", "live", "prefer", "enjoy", "believe", "agree",
        "have", "love", "spend", "get", "use", "feel", "try", "commute", "study", "plan",
    )
    private val PRONOUNS = setOf("i", "you", "he", "she", "it", "we", "they", "this", "that", "there")

    fun check(t: List<String>): List<String> {
        val notes = LinkedHashSet<String>()
        for (i in t.indices) {
            val w = t[i]
            val n = t.getOrNull(i + 1)
            // 1) i am + 동사원형 (i am agree)
            if (w == "i" && n == "am") {
                val v = t.getOrNull(i + 2)
                if (v != null && v in I_AM_BAD) notes += "be동사 뒤에 동사원형을 바로 쓰지 않아요: ‘I am $v’ → ‘I $v’"
            }
            // 2) 3인칭 단수 -s 누락 (she go)
            if (w in THIRD_SUBJECTS && (i == 0 || t[i - 1] !in THIRD_PREV_SKIP)) {
                var j = i + 1
                while (j < t.size && t[j] in ADVERBS) j++
                val v = t.getOrNull(j)
                if (v != null && v in BASE_VERBS) notes += "$w 뒤의 동사에는 -s를 붙여요: ‘$w $v’ → ‘$w ${thirdPerson(v)}’"
            }
            // 3) did + 과거형 (did went, did you went, did not went)
            if (w == "did") {
                var j = i + 1
                if (t.getOrNull(j) == "not") j++
                if ((t.getOrNull(j) ?: "") in PRONOUNS) j++
                val v = t.getOrNull(j)
                if (v != null && isPast(v)) notes += "did 뒤에는 동사원형을 써요: ‘did $v’ → ‘did ${IRREGULAR_PAST[v] ?: v.removeSuffix("ed")}’"
            }
            // 4) more + 비교급 (more better), most + 최상급 (most best)
            if (w == "more" && n != null && n in COMPARATIVES) notes += "비교급을 겹쳐 쓰지 않아요: ‘more $n’ → ‘$n’"
            if (w == "most" && n != null && n in SUPERLATIVES) notes += "최상급을 겹쳐 쓰지 않아요: ‘most $n’ → ‘the $n’"
        }
        // 5) 주어 없이 동사로 시작 (think it is good)
        val first = t.firstOrNull() ?: return notes.toList()
        if (first in START_VERBS) {
            notes += "문장을 주어로 시작해요: ‘$first …’ → ‘I $first …’"
        } else if ((first == "is" || first == "was") && (t.getOrNull(1) ?: "") !in PRONOUNS) {
            notes += "문장을 주어로 시작해요: ‘$first …’ → ‘It $first …’"
        }
        return notes.toList()
    }

    private fun isPast(v: String): Boolean =
        v in IRREGULAR_PAST && v != "did" || (v.length > 4 && v.endsWith("ed") && v !in ED_NOT_PAST)

    fun thirdPerson(v: String): String = when {
        v == "have" -> "has"
        v == "do" -> "does"
        v == "go" -> "goes"
        v.endsWith("y") && v.length > 1 && v[v.length - 2] !in "aeiou" -> v.dropLast(1) + "ies"
        v.endsWith("s") || v.endsWith("sh") || v.endsWith("ch") || v.endsWith("x") || v.endsWith("z") || v.endsWith("o") -> v + "es"
        else -> v + "s"
    }
}
