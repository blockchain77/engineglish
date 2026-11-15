package com.cartutor.engineglish.english.speech

import com.cartutor.engineglish.english.core.CommandParser
import com.cartutor.engineglish.english.core.LearnerCommand
import com.cartutor.engineglish.english.core.ListenContext
import com.cartutor.engineglish.english.core.TextNormalizer

/**
 * 영어·한국어 음성 명령 (명세 §5.3).
 *
 * - 영어: 정규화한 발화 '전체'가 명령 문구와 같을 때만 (앞의 please / can you / could you, 뒤의 please 는 뺀다).
 *   "next" → SKIP, "the next meeting" → 답.
 * - 한국어: 한글이 있으면 문구 포함 여부로 (띄어쓰기 무시), 가장 긴 문구가 이긴다 ("다시 시작" → RESUME).
 *   "넘기지 마"처럼 바로 뒤에 부정이 붙으면 무시한다.
 * - 영어 STT 가 한국어 명령을 로마자로 받아 적은 경우("dashi", "da si" → 다시)도 받아 준다.
 * - YES/NO 는 CONFIRM 문맥에서만. CONFIRM 에서는 첫머리의 예/아니요("네, 다시…")가 우선한다.
 *
 * 주의(코치): "next"·"wait"·"again" 같은 낱말이 그 문항의 정답일 수도 있다.
 * 명령으로 보기 전에 judge.judge(item, text).verdict == CORRECT 인지 먼저 확인하면 정답을 명령으로 오해하지 않는다.
 */
class BilingualCommandParser(private val normalizer: TextNormalizer) : CommandParser {

    private val englishPhrases: Map<String, LearnerCommand> = buildMap {
        for ((cmd, phrases) in EN_PHRASES) {
            for (p in phrases) {
                val key = normalizer.normalize(p)
                if (key.isNotEmpty() && key !in this) put(key, cmd)
            }
        }
    }

    override fun parse(text: String, context: ListenContext): LearnerCommand? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val confirm = context == ListenContext.CONFIRM
        if (SpeechText.hasHangul(raw)) {
            korean(raw, confirm)?.let { return it }
            val latin = raw.filterNot { SpeechText.isHangul(it) }
            return if (latin.any { it.isLetter() }) english(latin, confirm) else null
        }
        return english(raw, confirm)
    }

    // ───────── 영어 ─────────

    private fun english(text: String, confirm: Boolean): LearnerCommand? {
        if (confirm && UH_HUH.containsMatchIn(text.lowercase())) return LearnerCommand.YES
        val t = stripPolite(normalizer.tokens(text))
        if (t.isEmpty()) return null
        englishPhrases[t.joinToString(" ")]?.let { cmd -> if (allowed(cmd, confirm)) return cmd }
        ROMANIZED[t.joinToString("")]?.let { cmd -> if (allowed(cmd, confirm)) return cmd }
        if (confirm) {
            when (t.first()) {
                in YES_FIRST -> return LearnerCommand.YES
                in NO_FIRST -> return LearnerCommand.NO
            }
        }
        return null
    }

    private fun stripPolite(input: List<String>): List<String> {
        var t = input
        while (true) {
            t = when {
                t.size > 1 && t[0] in LEAD_SINGLE -> t.drop(1)
                t.size > 2 && t[0] in LEAD_MODAL && t[1] == "you" -> t.drop(2)
                t.size > 2 && t[0] == "all" && t[1] == "right" -> t.drop(2)
                else -> break
            }
        }
        while (t.size > 1 && t.last() == "please") t = t.dropLast(1)
        if (t.size > 2 && t[t.size - 2] == "for" && t.last() == "me") t = t.dropLast(2)
        return t
    }

    // ───────── 한국어 ─────────

    private class Hit(val cmd: LearnerCommand, val length: Int, val position: Int)

    private fun korean(raw: String, confirm: Boolean): LearnerCommand? {
        val compact = raw.filter { it.isLetterOrDigit() }
        val words = raw.split(WORD_SPLIT).map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        val hits = ArrayList<Hit>()
        for ((cmd, phrases) in KO_PHRASES) {
            if (!allowed(cmd, confirm)) continue
            for (p in phrases) {
                var from = 0
                while (true) {
                    val at = compact.indexOf(p, from)
                    if (at < 0) break
                    if (!negatedAfter(compact, at + p.length)) hits += Hit(cmd, p.length, at)
                    from = at + 1
                }
            }
        }
        if (confirm) {
            // 한 글자 대답 ("네", "응", "예")은 낱말 단위로만
            for ((cmd, forms) in KO_WHOLE_WORDS) {
                val idx = words.indexOfFirst { w -> w in forms || w.removeSuffix("요") in forms }
                if (idx >= 0) hits += Hit(cmd, 1, compact.indexOf(words[idx]).coerceAtLeast(0))
            }
            // 첫머리의 예/아니요가 우선 ("네, 다시 해 주세요" → YES)
            hits.filter { it.position == 0 && (it.cmd == LearnerCommand.YES || it.cmd == LearnerCommand.NO) }
                .maxByOrNull { it.length }?.let { return it.cmd }
        }
        return hits.maxWithOrNull(compareBy<Hit>({ it.length }, { it.position }))?.cmd
    }

    private fun negatedAfter(compact: String, end: Int): Boolean {
        val rest = compact.substring(end.coerceAtMost(compact.length))
        return rest.startsWith("지마") || rest.startsWith("지말") || rest.startsWith("말고")
    }

    private fun allowed(cmd: LearnerCommand, confirm: Boolean): Boolean =
        confirm || (cmd != LearnerCommand.YES && cmd != LearnerCommand.NO)

    companion object {
        private val UH_HUH = Regex("^\\s*uh[- ]?huh\\b")
        private val WORD_SPLIT = Regex("[\\s,.!?~…]+")
        private val LEAD_SINGLE = setOf("please", "hey", "oh", "okay", "now")
        private val LEAD_MODAL = setOf("can", "could", "would", "will")
        private val YES_FIRST = setOf("yes", "yeah", "yep", "yup", "sure", "okay", "absolutely", "definitely", "ya")
        private val NO_FIRST = setOf("no", "nope", "nah")

        /** 영어 문구 — 실행 때 정규화해서 비교한다 ("I don't know" → "i do not know") */
        val EN_PHRASES: Map<LearnerCommand, List<String>> = linkedMapOf(
            LearnerCommand.REPEAT to listOf(
                "repeat", "again", "one more time", "say again", "say it again", "say that again", "repeat that",
                "repeat it", "repeat the question", "come again", "pardon", "pardon me", "what", "what did you say",
                "sorry what", "once more", "one more",
            ),
            LearnerCommand.SLOWER to listOf(
                "slower", "slowly", "slow down", "more slowly", "speak slowly", "speak more slowly", "say it slowly",
                "say it more slowly", "a bit slower", "a little slower", "slow", "too fast",
            ),
            LearnerCommand.SKIP to listOf(
                "skip", "next", "pass", "next one", "skip it", "skip this", "skip this one", "next question",
                "move on", "let's move on", "go next",
            ),
            LearnerCommand.HINT to listOf("hint", "give me a hint", "a hint", "clue", "give me a clue", "any hint"),
            LearnerCommand.MEANING to listOf(
                "meaning", "what does it mean", "what does that mean", "what does this mean", "what's the meaning",
                "what is the meaning", "what's the meaning of that", "translate", "translation", "in korean",
                "say it in korean", "what is it in korean", "the meaning",
            ),
            LearnerCommand.PAUSE to listOf(
                "pause", "stop", "wait", "hold on", "hang on", "wait a moment", "wait a second", "wait a minute",
                "just a moment", "just a second", "one moment", "one second", "hold on a second", "stop for a moment",
            ),
            LearnerCommand.RESUME to listOf(
                "resume", "continue", "go on", "keep going", "go ahead", "let's continue", "carry on", "i'm ready",
                "ready", "i'm back", "start again", "let's keep going",
            ),
            LearnerCommand.DONT_KNOW to listOf(
                "i don't know", "don't know", "no idea", "i have no idea", "i forgot", "i forget", "not sure",
                "i'm not sure", "i don't remember", "i can't remember", "no clue", "i have no clue", "i give up",
                "give up", "i don't know the answer",
            ),
            LearnerCommand.END_SESSION to listOf(
                "end session", "stop session", "end the session", "stop the session", "that's enough",
                "that's enough for today", "quit", "i'm done", "we're done", "let's stop here", "let's stop",
                "that's all", "that's all for today", "end lesson", "stop lesson", "i want to stop",
            ),
            LearnerCommand.YES to listOf(
                "yes", "yeah", "yep", "yup", "sure", "okay", "of course", "sounds good", "let's do it", "yes please",
                "sure thing", "why not", "go for it", "all right", "absolutely", "definitely", "no problem", "ya",
                "i'd love to", "good",
            ),
            LearnerCommand.NO to listOf(
                "no", "nope", "not now", "no thanks", "no thank you", "maybe later", "not really", "nah", "later",
                "next time", "i'm good", "not today",
            ),
        )

        /** 한국어 문구 (띄어쓰기 없이 비교) */
        val KO_PHRASES: Map<LearnerCommand, List<String>> = linkedMapOf(
            LearnerCommand.REPEAT to listOf("다시", "한번더", "다시말해", "뭐라고", "못들었", "다시한번", "다시들려"),
            LearnerCommand.SLOWER to listOf("천천히", "느리게", "천천이"),
            LearnerCommand.SKIP to listOf("넘겨", "넘어가", "다음", "패스", "건너뛰", "스킵", "넘기"),
            LearnerCommand.HINT to listOf("힌트"),
            LearnerCommand.MEANING to listOf("뜻", "무슨뜻", "의미", "해석", "한국어로", "번역"),
            LearnerCommand.PAUSE to listOf("잠깐", "멈춰", "정지", "잠시만", "스톱", "기다려"),
            LearnerCommand.RESUME to listOf("계속", "다시시작", "이어서", "재개"),
            LearnerCommand.DONT_KNOW to listOf("모르겠", "몰라", "기억이안나", "생각이안나", "기억안나", "생각안나"),
            LearnerCommand.END_SESSION to listOf("그만", "끝내", "종료", "여기까지", "끝낼래", "마칠게", "마치자"),
            LearnerCommand.YES to listOf("좋아", "좋습니다", "그래", "그럼요", "물론", "당연하", "알겠", "오케이", "예스", "할게", "더하자", "더할래"),
            LearnerCommand.NO to listOf("아니", "괜찮아", "괜찮습니다", "싫어", "다음에", "됐어", "안할래", "안해", "나중에"),
        )

        /** 한 글자 대답 — 낱말 전체가 이 꼴일 때만 (CONFIRM) */
        val KO_WHOLE_WORDS: Map<LearnerCommand, Set<String>> = mapOf(
            LearnerCommand.YES to setOf("네", "네네", "넵", "넹", "네에", "응", "응응", "웅", "엉", "예", "예예", "옙", "콜"),
            LearnerCommand.NO to setOf("노", "노노"),
        )

        /** 영어 STT 가 한국어 명령을 로마자로 받아 적은 꼴 (공백 없이) */
        val ROMANIZED: Map<String, LearnerCommand> = buildMap {
            fun all(cmd: LearnerCommand, vararg forms: String) { for (f in forms) put(f, cmd) }
            all(
                LearnerCommand.REPEAT, "dasi", "dashi", "dassi", "dasshi", "dahshi", "dahsi", "dashe", "dahshe",
                "dasee", "dashee", "tashi", "tasi", "dashy", "hanbeonde", "hanbunde", "hanbundeo",
            )
            all(LearnerCommand.SLOWER, "cheoncheonhi", "cheoncheoni", "chunchunhi", "chunchunhee", "chunchunhe", "chonchonhi", "chonchonhee", "neurige", "nurige")
            all(LearnerCommand.SKIP, "neomgyeo", "neomgyo", "numgyeo", "numgyo", "nomgyo", "nomgyeo", "neomeoga", "numuga", "daeum", "daum", "paeseu")
            all(LearnerCommand.HINT, "hinteu", "hintu")
            all(LearnerCommand.MEANING, "tteut", "ddeut", "museuntteut", "musuntteut", "uimi", "euimi")
            all(LearnerCommand.PAUSE, "jamkkan", "jamkan", "jamggan", "chamkan", "jamkkanman", "jamkanman", "jamsiman", "meomchwo", "momchwo", "jeongji")
            all(LearnerCommand.RESUME, "gyesok", "kyesok", "gyesog", "kyesog", "gyesokhae", "kyesokhae", "ieoseo")
            all(LearnerCommand.DONT_KNOW, "moreugesseo", "moreugeseo", "moreugesseoyo", "morugesso", "morugesseo", "molla", "mollayo")
            all(LearnerCommand.END_SESSION, "geuman", "geumanhae", "guman", "gooman", "kuman", "keuman", "geumanhaja", "kkeutnae", "jongnyo")
            all(LearnerCommand.YES, "ne", "neh", "nae", "eung", "ung", "joa", "joah", "joayo", "choa", "geurae", "kurae", "gurae")
            all(LearnerCommand.NO, "ani", "aniyo", "aniya", "anni", "annie", "gwaenchana", "gwenchana", "kwenchana", "sireo", "shireo", "daeume")
        }
    }
}
