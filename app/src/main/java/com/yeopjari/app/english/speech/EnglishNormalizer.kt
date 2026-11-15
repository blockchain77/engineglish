package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.TextNormalizer
import java.util.Locale

/**
 * 영어 문장 정규화 (명세 §5.1) — 정답과 들린 문장에 똑같이 적용한다 (멱등).
 *
 * 소문자 → 따옴표 통일 → 기호(a.m., %, $, &) → 축약형 풀기(i'm → i am, can't → can not)
 * → 숫자 → 단어(10:30 → ten thirty, 2nd → second, 1,000 → one thousand) → 문장부호 제거(단어 안 ' 는 유지)
 * → 붙여 쓴 축약형(im, dont)·구어(gonna)·영국식 철자(colour) 통일 → 군말 제거
 * (um/uh/er/hmm, 군말 "you know", 문장 첫 well/so/like, "like like" — "would like"·"looks like" 는 그대로).
 */
object EnglishNormalizer : TextNormalizer {

    override fun normalize(text: String): String = tokens(text).joinToString(" ")

    override fun tokens(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        var s = unifyQuotes(text.lowercase(Locale.ROOT))
        s = expandSymbols(s)
        s = expandContractions(s)
        s = NumberWords.expand(s)
        var t = split(s)
        t = mapTokens(t)
        t = mergeSequences(t)
        return removeFillers(t)
    }

    // ───────── 1. 따옴표 · 기호 ─────────

    private fun unifyQuotes(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    '’', '‘', '‛', '`', '´', '′', '＇' -> '\''
                    '“', '”', '„', '″' -> '"'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private val AM_PM = Regex("(?<![\\p{L}])([ap])\\.\\s?m\\.?")
    private val CURRENCY = Regex("([\$£€₩])\\s?(\\d[\\d,]*(?:\\.\\d+)?)")
    private val PERCENT = Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s?%")

    private fun expandSymbols(input: String): String {
        var s = AM_PM.replace(input) { m -> m.groupValues[1] + "m" }
        s = CURRENCY.replace(s) { m ->
            val amount = m.groupValues[2]
            val one = amount == "1"
            val name = when (m.groupValues[1]) {
                "\$" -> if (one) "dollar" else "dollars"
                "£" -> if (one) "pound" else "pounds"
                "€" -> if (one) "euro" else "euros"
                else -> "won"
            }
            " $amount $name "
        }
        s = PERCENT.replace(s) { m -> " " + m.groupValues[1] + " percent " }
        return s.replace("&", " and ").replace("+", " plus ").replace("@", " at ")
    }

    // ───────── 2. 축약형 ─────────

    private val APOS_WORD = Regex("\\p{L}+(?:'\\p{L}+)+")
    private val EXACT_APOS = mapOf(
        "y'all" to "you all", "ma'am" to "madam", "o'clock" to "o'clock", "c'mon" to "come on", "y'know" to "you know",
    )

    /** 's 를 is 로 푸는 주어 (그 외 's 는 소유격 → 아포스트로피만 뺀다) */
    private val S_IS = setOf(
        "it", "that", "what", "there", "here", "he", "she", "who", "where", "how", "when", "why", "this",
        "everyone", "everybody", "someone", "somebody", "nobody", "nothing", "something", "everything",
    )

    private fun expandContractions(s: String): String = APOS_WORD.replace(s) { m -> expandOne(m.value) }

    private fun expandOne(w: String): String {
        EXACT_APOS[w]?.let { return it }
        return when {
            w.endsWith("n't") -> when (val b = w.dropLast(3)) {
                "ca" -> "can not"
                "wo" -> "will not"
                "sha" -> "shall not"
                "ai" -> "is not"
                else -> "$b not"
            }
            w.endsWith("'re") -> w.dropLast(3) + " are"
            w.endsWith("'ve") -> w.dropLast(3) + " have"
            w.endsWith("'ll") -> w.dropLast(3) + " will"
            w.endsWith("'d") -> w.dropLast(2) + " would"
            w.endsWith("'m") -> w.dropLast(2) + " am"
            w.endsWith("'s") -> {
                val b = w.dropLast(2)
                when {
                    b == "let" -> "let us"
                    b in S_IS -> "$b is"
                    else -> b + "s"
                }
            }
            else -> w
        }
    }

    // ───────── 3. 문장부호 제거 · 토큰 ─────────

    private fun split(s: String): List<String> {
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == '\'' && i > 0 && i < s.length - 1 && s[i - 1].isLetter() && s[i + 1].isLetter() -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        return sb.split(' ').filter { it.isNotEmpty() }
    }

    // ───────── 4. 낱말 치환 (붙여 쓴 축약형 · 구어 · 영국식 철자) ─────────

    private val SPELLING: Map<String, String> = mapOf(
        "colour" to "color", "colours" to "colors", "favourite" to "favorite", "favourites" to "favorites",
        "flavour" to "flavor", "honour" to "honor", "humour" to "humor", "labour" to "labor",
        "neighbour" to "neighbor", "neighbours" to "neighbors", "neighbourhood" to "neighborhood",
        "behaviour" to "behavior", "harbour" to "harbor", "rumour" to "rumor",
        "centre" to "center", "centres" to "centers", "theatre" to "theater", "metre" to "meter", "metres" to "meters",
        "litre" to "liter", "litres" to "liters", "kilometre" to "kilometer", "kilometres" to "kilometers",
        "organise" to "organize", "organised" to "organized", "organising" to "organizing", "organisation" to "organization",
        "realise" to "realize", "realised" to "realized", "recognise" to "recognize", "recognised" to "recognized",
        "apologise" to "apologize", "apologised" to "apologized", "apologising" to "apologizing",
        "prioritise" to "prioritize", "summarise" to "summarize", "analyse" to "analyze", "analysed" to "analyzed",
        "travelling" to "traveling", "travelled" to "traveled", "traveller" to "traveler", "travellers" to "travelers",
        "cancelled" to "canceled", "cancelling" to "canceling", "labelled" to "labeled", "modelling" to "modeling",
        "programme" to "program", "programmes" to "programs", "licence" to "license", "defence" to "defense",
        "offence" to "offense", "practise" to "practice", "practised" to "practiced", "practising" to "practicing",
        "grey" to "gray", "cheque" to "check", "catalogue" to "catalog", "dialogue" to "dialog",
        "tyre" to "tire", "tyres" to "tires", "aeroplane" to "airplane", "jewellery" to "jewelry",
        "enrol" to "enroll", "fulfil" to "fulfill", "judgement" to "judgment", "ageing" to "aging",
        "pyjamas" to "pajamas", "towards" to "toward", "afterwards" to "afterward", "amongst" to "among",
        "whilst" to "while", "learnt" to "learned", "spelt" to "spelled", "dreamt" to "dreamed", "burnt" to "burned",
        "mum" to "mom", "storey" to "story", "plough" to "plow", "mould" to "mold",
    )

    private val TOKEN_MAP: Map<String, List<String>> = buildMap {
        fun m(k: String, v: String) { put(k, v.split(' ')) }
        // 아포스트로피 없이 받아 적은 축약형 (STT)
        m("im", "i am"); m("ive", "i have"); m("youre", "you are"); m("youve", "you have"); m("youll", "you will")
        m("youd", "you would"); m("theyre", "they are"); m("theyve", "they have"); m("theyll", "they will")
        m("weve", "we have"); m("dont", "do not"); m("doesnt", "does not"); m("didnt", "did not")
        m("cant", "can not"); m("wont", "will not"); m("isnt", "is not"); m("arent", "are not")
        m("wasnt", "was not"); m("werent", "were not"); m("havent", "have not"); m("hasnt", "has not")
        m("hadnt", "had not"); m("couldnt", "could not"); m("wouldnt", "would not"); m("shouldnt", "should not")
        m("mustnt", "must not"); m("thats", "that is"); m("whats", "what is"); m("theres", "there is")
        m("heres", "here is"); m("wheres", "where is"); m("whos", "who is"); m("hows", "how is")
        m("itll", "it will"); m("thatll", "that will"); m("shes", "she is"); m("hes", "he is"); m("its", "it is")
        // 구어 · 붙여 쓰기
        m("cannot", "can not"); m("gonna", "going to"); m("wanna", "want to"); m("gotta", "got to")
        m("kinda", "kind of"); m("sorta", "sort of"); m("lemme", "let me"); m("gimme", "give me")
        m("dunno", "do not know"); m("outta", "out of"); m("oclock", "o'clock"); m("ok", "okay")
        m("alright", "all right"); m("everyday", "every day")
        for ((k, v) in SPELLING) put(k, listOf(v))
    }

    private fun mapTokens(t: List<String>): List<String> {
        val out = ArrayList<String>(t.size + 4)
        t.forEachIndexed { i, w ->
            if (i == 0 && w == "lets") {
                out += "let"; out += "us"
            } else {
                val mapped = TOKEN_MAP[w]
                if (mapped != null) out.addAll(mapped) else out += w
            }
        }
        return out
    }

    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion",
    )
    private val SCALE_WORDS = setOf("hundred", "thousand", "million", "billion")

    /** 두 낱말 묶음 통일: o clock, e mail, per cent, a hundred, hundred and five … */
    private fun mergeSequences(t: List<String>): List<String> {
        val out = ArrayList<String>(t.size)
        var i = 0
        while (i < t.size) {
            val w = t[i]
            val n = t.getOrNull(i + 1)
            when {
                w == "o" && n == "clock" -> { out += "o'clock"; i += 2 }
                w == "e" && (n == "mail" || n == "mails") -> { out += "e$n"; i += 2 }
                w == "per" && n == "cent" -> { out += "percent"; i += 2 }
                w == "web" && n == "site" -> { out += "website"; i += 2 }
                w == "any" && n == "more" -> { out += "anymore"; i += 2 }
                w == "a" && (n == "hundred" || n == "thousand" || n == "million") -> { out += "one"; i += 1 }
                w == "and" && (out.lastOrNull() ?: "") in SCALE_WORDS && n != null && n in NUMBER_WORDS -> i += 1
                else -> { out += w; i += 1 }
            }
        }
        return out
    }

    // ───────── 5. 군말 ─────────

    private val FILLER = Regex("^(u+m+|u+h+m*|e+r+m*|a+h+|h+m+|m{2,}|e+h+)$")
    private val LEADING_DISCOURSE = setOf("well", "so", "like")

    /** 이 말 뒤의 you know 는 진짜 '알다' (do you know, if you know …) */
    private val KNOW_PREV = setOf(
        "do", "does", "did", "not", "if", "as", "would", "will", "should", "could", "might", "may", "can",
        "what", "how", "who", "where", "when", "why", "whether", "that", "let", "me", "to", "and", "but", "or",
        "wish", "hope", "i", "we", "they", "now", "already", "probably", "maybe", "never", "always",
    )

    /** you know 뒤에 목적어가 오면 진짜 '알다' (you know what / you know the way …) */
    private val KNOW_OBJECT = setOf(
        "what", "how", "who", "where", "when", "why", "whether", "that", "if", "the", "a", "an", "about",
        "him", "her", "them", "it", "me", "us", "my", "your", "his", "their", "our", "this", "these", "those",
        "everyone", "everybody", "anyone", "anybody", "someone", "somebody", "each", "all", "any", "some",
        "english", "korean", "nothing", "something", "everything", "more", "much", "many", "too", "better", "best",
    )

    private fun removeFillers(input: List<String>): List<String> {
        var t = input
        repeat(4) {
            val before = t
            t = t.filterNot { FILLER.matches(it) }
            t = removeYouKnow(t)
            t = collapseLike(t)
            while (t.size > 1 && t[0] in LEADING_DISCOURSE) t = t.drop(1)
            if (t == before) return t
        }
        return t
    }

    private fun removeYouKnow(t: List<String>): List<String> {
        if (t.size < 2) return t
        val out = ArrayList<String>(t.size)
        var i = 0
        while (i < t.size) {
            if (t[i] == "you" && i + 1 < t.size && t[i + 1] == "know") {
                val prev = out.lastOrNull()
                val next = t.getOrNull(i + 2)
                val drop = when {
                    next != null && next in KNOW_OBJECT -> false
                    prev == null -> next != null
                    else -> prev !in KNOW_PREV
                }
                if (drop) {
                    i += 2
                    continue
                }
            }
            out += t[i]
            i++
        }
        return out
    }

    private fun collapseLike(t: List<String>): List<String> {
        val out = ArrayList<String>(t.size)
        for (w in t) {
            if (w == "like" && out.lastOrNull() == "like") continue
            out += w
        }
        return out
    }
}

/** 숫자 → 영어 낱말 (0 ~ 999,999,999,999 · 시각 · 서수 · 소수) */
internal object NumberWords {
    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ORDINAL_EXCEPTIONS = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
        "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
    )

    fun cardinal(n: Long): String = when {
        n < 0 -> "minus " + cardinal(-n)
        n < 20 -> ONES[n.toInt()]
        n < 100 -> TENS[(n / 10).toInt()] + (if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else "")
        n < 1_000 -> ONES[(n / 100).toInt()] + " hundred" + (if (n % 100 != 0L) " " + cardinal(n % 100) else "")
        n < 1_000_000 -> scale(n, 1_000L, "thousand")
        n < 1_000_000_000 -> scale(n, 1_000_000L, "million")
        else -> scale(n, 1_000_000_000L, "billion")
    }

    private fun scale(n: Long, unit: Long, name: String): String =
        cardinal(n / unit) + " " + name + (if (n % unit != 0L) " " + cardinal(n % unit) else "")

    fun ordinal(n: Long): String {
        val words = cardinal(n).split(' ')
        val last = words.last()
        val ord = ORDINAL_EXCEPTIONS[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return (words.dropLast(1) + ord).joinToString(" ")
    }

    fun digits(s: String): String = s.map { ONES[it - '0'] }.joinToString(" ")

    fun integerWords(s: String): String = when {
        s.length > 1 && s[0] == '0' -> digits(s)
        s.length > 12 -> digits(s)
        else -> cardinal(s.toLong())
    }

    private fun time(hour: Int, mm: String, ampm: String?): String {
        val h = cardinal(hour.toLong())
        val body = when {
            mm == "00" -> if (ampm != null) h else "$h o'clock"
            mm[0] == '0' -> "$h oh " + ONES[mm[1] - '0']
            else -> h + " " + cardinal(mm.toLong())
        }
        return if (ampm != null) "$body $ampm" else body
    }

    private val TIME = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])(\\s*(am|pm)(?!\\p{L}))?")
    private val THOUSANDS = Regex("(?<![\\d,])\\d{1,3}(?:,\\d{3})+(?![\\d,])")
    private val DECIMAL = Regex("(?<![\\d.])(\\d+)\\.(\\d+)(?![\\d.])")
    private val ORDINAL = Regex("(?<![\\p{L}\\d])(\\d+)(st|nd|rd|th)(?![\\p{L}\\d])")
    private val INTEGER = Regex("\\d+")

    fun expand(text: String): String {
        if (text.none { it.isDigit() }) return text
        var s = TIME.replace(text) { m ->
            val ampm = m.groupValues[4].ifEmpty { null }
            " " + time(m.groupValues[1].toInt(), m.groupValues[2], ampm) + " "
        }
        s = THOUSANDS.replace(s) { m -> m.value.replace(",", "") }
        s = DECIMAL.replace(s) { m -> " " + integerWords(m.groupValues[1]) + " point " + digits(m.groupValues[2]) + " " }
        s = ORDINAL.replace(s) { m ->
            val d = m.groupValues[1]
            " " + (if (d.length > 12) digits(d) else ordinal(d.toLong())) + " "
        }
        s = INTEGER.replace(s) { m -> " " + integerWords(m.value) + " " }
        return s
    }
}
