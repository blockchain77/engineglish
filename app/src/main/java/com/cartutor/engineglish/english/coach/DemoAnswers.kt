package com.cartutor.engineglish.english.coach

import com.cartutor.engineglish.english.core.DemoAnswerKind
import com.cartutor.engineglish.english.core.LearningItem
import com.cartutor.engineglish.english.core.Skill

/**
 * 데모 패널·adb 용 답 만들기 (명세 §7 onAnswerText) — 현재 문항 기준.
 * CORRECT = 모범 답안, PARTIAL = 핵심어 하나 뺌, WRONG = 오답 보기, L1_ERROR = 한국어식 발음 치환 한 단어, DONT_KNOW = "I don't know".
 */
object DemoAnswers {
    const val NO_IDEA = "I have no idea about that."
    const val DONT_KNOW = "I don't know"

    private val STOP = setOf("a", "an", "the", "to", "is", "are", "am", "i", "you", "it", "we", "of", "in", "on", "at", "for", "and", "or", "be", "do")

    /** 한국어 화자가 자주 바꿔 말하는 소리 (앞의 것이 우선) */
    private val L1_RULES = listOf("th" to "s", "sh" to "s", "r" to "l", "f" to "p", "v" to "b", "z" to "j")

    fun make(item: LearningItem, kind: DemoAnswerKind): String = when (kind) {
        DemoAnswerKind.CORRECT -> item.modelAnswer.ifBlank { item.promptEn.orEmpty() }
        DemoAnswerKind.PARTIAL -> partial(item)
        DemoAnswerKind.WRONG -> wrong(item)
        DemoAnswerKind.L1_ERROR -> l1Error(item)
        DemoAnswerKind.DONT_KNOW -> DONT_KNOW
    }

    private fun core(token: String): String = token.lowercase().filter { it.isLetter() || it == '\'' }

    private fun isKeyword(word: String, keywords: List<String>): Boolean = keywords.any { k ->
        val kw = k.lowercase()
        word == kw || word == kw + "s" || word == kw + "es" || word == kw + "ed" || word == kw + "d" || word == kw + "ing"
    }

    private fun partial(item: LearningItem): String {
        val answer = item.modelAnswer
        val tokens = answer.split(' ').filter { it.isNotEmpty() }
        if (tokens.size <= 1) {
            val w = answer.trim().trimEnd('.', '!', '?')
            return when {
                w.isEmpty() -> NO_IDEA
                item.skill == Skill.MINIMAL_PAIR -> w.dropLast(1).ifEmpty { w }
                w.endsWith("s") -> w + "es"
                else -> w + "s"
            }
        }
        var drop = tokens.indexOfFirst { isKeyword(core(it), item.keywords) }
        if (drop < 0) drop = tokens.indexOfLast { core(it).isNotEmpty() && core(it) !in STOP }
        if (drop < 0) drop = tokens.lastIndex
        val kept = tokens.filterIndexed { i, _ -> i != drop }
        var text = kept.joinToString(" ")
        // 마지막 단어를 뺐다면 문장부호는 남긴다
        val lastPunct = tokens.last().takeLastWhile { it == '.' || it == '?' || it == '!' }
        if (drop == tokens.lastIndex && lastPunct.isNotEmpty() && !text.endsWith(lastPunct)) text += lastPunct
        return text
    }

    private fun wrong(item: LearningItem): String =
        item.distractors.firstOrNull { it.isNotBlank() } ?: NO_IDEA

    private fun l1Error(item: LearningItem): String {
        if (item.skill == Skill.MINIMAL_PAIR) {
            item.contrast?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val answer = item.modelAnswer.ifBlank { item.promptEn.orEmpty() }
        val tokens = answer.split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return NO_IDEA
        // 핵심어 → 나머지 내용어 순으로, 규칙이 걸리는 첫 단어 하나만 바꾼다
        val order = tokens.indices.sortedBy { i ->
            val w = core(tokens[i])
            when {
                isKeyword(w, item.keywords) -> 0
                w !in STOP -> 1
                else -> 2
            }
        }
        for (i in order) {
            val w = core(tokens[i])
            if (w.isEmpty() || w in STOP) continue
            for ((from, to) in L1_RULES) {
                if (w.contains(from)) {
                    tokens[i] = swapIn(tokens[i], from, to)
                    return tokens.joinToString(" ")
                }
            }
        }
        // 치환 규칙이 걸리는 단어가 없으면 끝소리 탈락 (check → chec) — 정답 그대로 돌려주면 '정답'이 되어 버린다
        for (i in order) {
            val w = core(tokens[i])
            if (w.length < 4 || w in STOP || w.last() !in FINAL_STOPS) continue
            val tail = tokens[i].takeLastWhile { !it.isLetter() }
            tokens[i] = tokens[i].dropLast(tail.length).dropLast(1) + tail
            return tokens.joinToString(" ")
        }
        return answer
    }

    /** 끝소리 탈락을 흉내 낼 자음 (s 는 복수형 탈락과 헷갈려 뺀다) */
    private const val FINAL_STOPS = "tdkpgbmn"

    /** 토큰 안의 from 을 모두 to 로 (첫 글자 대문자는 유지) */
    private fun swapIn(token: String, from: String, to: String): String {
        val lower = token.lowercase().replace(from, to)
        return if (token.firstOrNull()?.isUpperCase() == true) lower.replaceFirstChar { it.uppercaseChar() } else lower
    }
}
