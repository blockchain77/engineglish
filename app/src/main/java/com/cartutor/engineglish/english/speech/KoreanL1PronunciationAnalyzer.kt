package com.cartutor.engineglish.english.speech

import com.cartutor.engineglish.english.core.AlignOp
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.MinimalPair
import com.cartutor.engineglish.english.core.PronIssue
import com.cartutor.engineglish.english.core.PronIssueType
import com.cartutor.engineglish.english.core.PronunciationAnalyzer
import com.cartutor.engineglish.english.core.WordAlign

/**
 * 한국어 화자(L1) 발음 문제 추정 (명세 §5.4).
 *
 * 정렬의 치환(SUB) 쌍 — "right" 를 말하려다 "light" 로 들린 경우 — 을 발음 규칙으로 설명할 수 있으면 문제로 본다.
 * 1) 단어를 간이 발음 키로 바꾼다 (wr→r, ph→f, ck→k, 부드러운 c/g, 묵음 e, 끝 -y/-ee → i, 겹자음 하나로).
 * 2) 규칙의 두 소리(R↔L, F↔P, V↔B, TH↔S/T, TH↔D, Z↔J, SH↔S)를 같은 기호로 합쳤을 때 두 키가 같고,
 *    실제로 한쪽 소리가 다른 쪽으로 바뀌었으면 그 유형 (모음까지 같으면 0.8, 모음 차이만 남으면 0.7).
 * 3) 들린 말이 정답 단어의 끝 자음(들)만 빠진 꼴이면 끝소리 탈락 (0.6). 복수 -s 탈락은 문법 실수로 보고 뺀다.
 * STT 오인식일 수도 있으므로 피드백은 "…로 들렸어요" 말투로 한다.
 */
class KoreanL1PronunciationAnalyzer : PronunciationAnalyzer {

    override fun analyze(item: LearningItem, alignment: List<WordAlign>): List<PronIssue> {
        val found = ArrayList<Pair<Int, PronIssue>>()
        val seen = HashSet<String>()
        alignment.forEachIndexed { idx, op ->
            if (op.op != AlignOp.SUB) return@forEachIndexed
            val e = op.expected ?: return@forEachIndexed
            val h = op.heard ?: return@forEachIndexed
            if (e in seen) return@forEachIndexed
            val issue = detect(e, h, item.pronFocus) ?: return@forEachIndexed
            seen += e
            found += idx to issue
        }
        return found
            .sortedWith(compareByDescending<Pair<Int, PronIssue>> { it.second.confidence }.thenBy { it.first })
            .take(MAX_ISSUES)
            .map { it.second }
    }

    /** 단어 하나 쌍 판단. [prefer] = 문항이 겨냥한 유형 (점수가 같을 때 우선) */
    fun detect(expected: String, heard: String, prefer: PronIssueType? = null): PronIssue? {
        val a = expected.lowercase().filter { it in 'a'..'z' }
        val b = heard.lowercase().filter { it in 'a'..'z' }
        if (a.isEmpty() || b.isEmpty() || a == b || Homophones.same(a, b)) return null
        val pa = Phonetic.key(a)
        val pb = Phonetic.key(b)
        // 소리 개수는 겹자음을 줄이기 전 키로 센다 (tomorrow 의 rr 두 개 → tomolrow)
        val ra = Phonetic.key(a, collapse = false)
        val rb = Phonetic.key(b, collapse = false)
        var bestType: PronIssueType? = null
        var bestConf = 0f
        for (rule in SWAP_RULES) {
            val conf = rule.match(pa, pb, ra, rb) ?: continue
            val better = conf > bestConf || (conf == bestConf && rule.type == prefer && bestType != prefer)
            if (better) {
                bestConf = conf
                bestType = rule.type
            }
        }
        val type = bestType
        if (type != null) return PronIssue(type, a, b, bestConf, tip(type), MinimalPair(a, b))
        val drop = finalDrop(a, b) ?: return null
        return PronIssue(PronIssueType.FINAL_CONSONANT_DROP, a, b, drop, tip(PronIssueType.FINAL_CONSONANT_DROP), MinimalPair(a, b))
    }

    override fun tip(type: PronIssueType): String = TIPS.getValue(type)

    override fun pairs(type: PronIssueType): List<MinimalPair> = PAIRS.getValue(type)

    private fun finalDrop(a: String, b: String): Float? {
        if (a.length < 3 || b.length < 2 || !a.startsWith(b) || b in SHORT_FUNCTION_WORDS) return null
        val tail = a.substring(b.length)
        if (tail.isEmpty() || tail.length > 3) return null
        if (tail == "ed" || (tail == "d" && a.endsWith("ed"))) return DROP_CONFIDENCE
        if (tail == "s" || tail == "es") return null
        // y·w 는 모음 소리 (warranty → warrant 는 끝소리 탈락이 아님)
        return if (tail.all { it !in VOWELS && it != 'y' && it != 'w' }) DROP_CONFIDENCE else null
    }

    /** 두 소리 맞바꿈 규칙. a = 목표 소리, b = 한국어 화자가 대신 내기 쉬운 소리 */
    private class SwapRule(val type: PronIssueType, val a: Set<Char>, val b: Set<Char>) {
        /** x, y = 발음 키, rawX, rawY = 겹자음을 줄이기 전 키 (소리 개수용) */
        fun match(x: String, y: String, rawX: String, rawY: String): Float? {
            if (x == y || !swapped(rawX, rawY)) return null
            val kx = merge(x)
            val ky = merge(y)
            if (kx == ky) return STRICT_CONFIDENCE
            if (Phonetic.loose(kx) == Phonetic.loose(ky)) return LOOSE_CONFIDENCE
            return null
        }

        /** 두 소리를 '#' 하나로 (이어진 '#' 도 하나로: tomorrow 의 rr → r, tomolrow 의 lr → #) */
        private fun merge(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                val m = if (c in a || c in b) '#' else c
                if (m == '#' && sb.isNotEmpty() && sb[sb.length - 1] == '#') continue
                sb.append(m)
            }
            return sb.toString()
        }

        /**
         * 한쪽의 a 소리가 다른 쪽의 b 소리로 (또는 반대로) 바뀌었나.
         * da = 목표 소리 a 가 줄어든 수(반드시 변해야 함), db = b 가 늘어난 수(같은 방향이거나 그대로).
         * cats → cat 처럼 b 만 빠진 것은 바꿈이 아니다.
         */
        private fun swapped(x: String, y: String): Boolean {
            val da = x.count { it in a } - y.count { it in a }
            val db = y.count { it in b } - x.count { it in b }
            return (da > 0 && db >= 0) || (da < 0 && db <= 0)
        }
    }

    companion object {
        const val STRICT_CONFIDENCE = 0.8f
        const val LOOSE_CONFIDENCE = 0.7f
        const val DROP_CONFIDENCE = 0.6f
        const val MAX_ISSUES = 2
        private const val VOWELS = "aeiou"
        private val SHORT_FUNCTION_WORDS = setOf("a", "an", "the", "to", "i", "in", "on", "at", "of", "and", "or", "it", "is", "as")

        // Phonetic.key 의 자리표시: T = th, S = sh, C = ch
        private val SWAP_RULES = listOf(
            SwapRule(PronIssueType.R_L, setOf('r'), setOf('l')),
            SwapRule(PronIssueType.F_P, setOf('f'), setOf('p')),
            SwapRule(PronIssueType.V_B, setOf('v'), setOf('b')),
            SwapRule(PronIssueType.TH_S, setOf('T'), setOf('s', 't')),
            SwapRule(PronIssueType.TH_D, setOf('T'), setOf('d')),
            SwapRule(PronIssueType.Z_J, setOf('z'), setOf('j')),
            SwapRule(PronIssueType.SH_S, setOf('S'), setOf('s')),
        )

        val TIPS: Map<PronIssueType, String> = mapOf(
            PronIssueType.R_L to "R은 혀끝이 어디에도 닿지 않게 살짝 말고, L은 혀끝을 윗니 뒤에 붙여요.",
            PronIssueType.F_P to "F는 윗니를 아랫입술에 살짝 대고 바람을 내보내요.",
            PronIssueType.V_B to "V는 윗니를 아랫입술에 대고 목을 울려요. 두 입술을 붙이지 않아요.",
            PronIssueType.TH_S to "TH는 혀끝을 윗니 사이로 살짝 내밀고 바람을 내보내요.",
            PronIssueType.TH_D to "they의 TH는 혀를 이 사이에 두고 목을 울려요.",
            PronIssueType.Z_J to "Z는 S 소리에 목 울림만 더해요. ‘지’처럼 혀를 붙이지 않아요.",
            PronIssueType.SH_S to "SH는 입술을 동그랗게 내밀고 ‘쉬’, S는 입꼬리를 당겨 ‘스’.",
            PronIssueType.FINAL_CONSONANT_DROP to "끝소리를 삼키지 말고 짧게라도 들리게 마무리해요.",
        )

        val PAIRS: Map<PronIssueType, List<MinimalPair>> = mapOf(
            PronIssueType.R_L to pairs("right" to "light", "road" to "load", "correct" to "collect", "pray" to "play", "rice" to "lice", "grass" to "glass", "fry" to "fly", "arrive" to "alive"),
            PronIssueType.F_P to pairs("fan" to "pan", "coffee" to "copy", "file" to "pile", "fast" to "past", "fool" to "pool", "fine" to "pine", "full" to "pull", "fork" to "pork"),
            PronIssueType.V_B to pairs("very" to "berry", "vote" to "boat", "vest" to "best", "van" to "ban", "vet" to "bet", "vat" to "bat", "vase" to "base", "vent" to "bent"),
            PronIssueType.TH_S to pairs("think" to "sink", "thank" to "sank", "mouth" to "mouse", "thick" to "sick", "thing" to "sing", "path" to "pass", "three" to "tree", "worth" to "worse"),
            PronIssueType.TH_D to pairs("they" to "day", "then" to "den", "there" to "dare", "though" to "dough", "father" to "fodder", "worthy" to "wordy", "lather" to "ladder"),
            PronIssueType.Z_J to pairs("zest" to "jest", "zealous" to "jealous", "zig" to "jig", "maze" to "mage", "buzz" to "budge"),
            PronIssueType.SH_S to pairs("she" to "see", "ship" to "sip", "shell" to "sell", "sheet" to "seat", "shoe" to "sue", "shin" to "sin", "shore" to "sore", "mesh" to "mess"),
            PronIssueType.FINAL_CONSONANT_DROP to pairs("book" to "boo", "worked" to "work", "card" to "car", "seat" to "sea", "tent" to "ten", "fork" to "for", "helped" to "help", "wanted" to "want"),
        )

        private fun pairs(vararg p: Pair<String, String>): List<MinimalPair> = p.map { MinimalPair(it.first, it.second) }
    }
}

/** 간이 발음 키 — 철자 차이를 줄여 소리 바꿈만 남긴다 (T = th, S = sh, C = ch) */
internal object Phonetic {
    private const val VOWELS = "aeiou"
    private val VOWEL_RUN = Regex("[aeiouy]+")

    /** [collapse] = 겹자음·겹모음을 하나로 줄일지 (소리 개수를 셀 때는 false) */
    fun key(word: String, collapse: Boolean = true): String {
        var w = word
        if (w.startsWith("wr") || w.startsWith("wl")) w = w.substring(1)
        if (w.startsWith("kn")) w = "n" + w.substring(2)
        if (w.startsWith("wh")) w = "w" + w.substring(2)
        w = w.replace("ph", "f").replace("ck", "k").replace("dg", "j").replace("q", "k").replace("x", "ks")
        w = w.replace("th", "T").replace("sh", "S").replace("ch", "C")
        // 부드러운 c/g (city, page)
        val sb = StringBuilder(w.length)
        for (i in w.indices) {
            val c = w[i]
            val n = w.getOrNull(i + 1)
            sb.append(
                when {
                    c == 'c' && n != null && n in "eiy" -> 's'
                    c == 'c' -> 'k'
                    c == 'g' && i > 0 && n != null && n in "eiy" -> 'j'
                    else -> c
                }
            )
        }
        w = sb.toString()
        // 끝소리: -ee/-ey/-ie → i, 자음+y → i, 짧은 she/the → i, 묵음 e 제거
        w = when {
            w.length > 2 && (w.endsWith("ee") || w.endsWith("ey") || w.endsWith("ie")) -> w.dropLast(2) + "i"
            w.length > 2 && w.endsWith("y") && w[w.length - 2] !in VOWELS -> w.dropLast(1) + "i"
            w.length in 2..3 && w.endsWith("e") && w[w.length - 2] !in VOWELS -> w.dropLast(1) + "i"
            w.length >= 4 && w.endsWith("e") && w[w.length - 2] !in VOWELS && w.dropLast(2).any { it in VOWELS } -> w.dropLast(1)
            else -> w
        }
        if (!collapse) return w
        // 겹자음·겹모음 하나로
        val out = StringBuilder(w.length)
        for (c in w) if (out.isEmpty() || out[out.length - 1] != c) out.append(c)
        return out.toString()
    }

    /** 모음 차이를 무시한 키 */
    fun loose(k: String): String = VOWEL_RUN.replace(k, "V")
}

/** 발음 문제 → 짧은 한국어 문장 (TTS 로 읽어도 자연스럽게: 알파벳 이름 + ‘로’) */
object PronFeedback {
    /** "R 발음이 L로 들렸어요" (마침표 없음) */
    fun spokenKo(issue: PronIssue): String {
        val e = issue.expectedWord.lowercase()
        val h = issue.heardWord.lowercase()
        fun more(x: String, y: String, part: String) = x.split(part).size > y.split(part).size
        val (from, to) = when (issue.type) {
            PronIssueType.R_L -> if (more(e, h, "r")) "R" to "L" else "L" to "R"
            PronIssueType.F_P -> if (more(e, h, "f") || more(e, h, "ph")) "F" to "P" else "P" to "F"
            PronIssueType.V_B -> if (more(e, h, "v")) "V" to "B" else "B" to "V"
            PronIssueType.TH_S -> if (more(e, h, "th")) "TH" to (if (more(h, e, "s")) "S" else "T") else (if (more(e, h, "s")) "S" else "T") to "TH"
            PronIssueType.TH_D -> if (more(e, h, "th")) "TH" to "D" else "D" to "TH"
            PronIssueType.Z_J -> if (more(e, h, "z")) "Z" to "J" else "J" to "Z"
            PronIssueType.SH_S -> if (more(e, h, "sh")) "SH" to "S" else "S" to "SH"
            PronIssueType.FINAL_CONSONANT_DROP -> return "끝소리가 약하게 들렸어요"
        }
        return "$from 발음이 ${to}로 들렸어요"
    }

    /** 화면용: "R 발음이 L로 들렸어요 (right → light)" */
    fun describeKo(issue: PronIssue): String = spokenKo(issue) + " (${issue.expectedWord} → ${issue.heardWord})"
}
