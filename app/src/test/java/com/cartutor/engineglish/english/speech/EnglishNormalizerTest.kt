package com.cartutor.engineglish.english.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishNormalizerTest {

    private fun n(s: String) = EnglishNormalizer.normalize(s)

    @Test fun lowercasesAndStripsPunctuation() {
        assertEquals("hello world", n("Hello, World!"))
        assertEquals("can we push the meeting to tomorrow", n("Can we push the meeting to tomorrow?"))
        assertEquals("", n("   "))
        assertEquals("", n("?!..."))
    }

    @Test fun expandsContractions() {
        assertEquals("i am running late", n("I'm running late."))
        assertEquals("i am running late", n("I’m running late.")) // 굽은 따옴표
        assertEquals("i can not go", n("I can't go"))
        assertEquals("i can not go", n("I cannot go"))
        assertEquals("it will not work", n("It won't work"))
        assertEquals("it is pouring", n("It's pouring."))
        assertEquals("let us call it a day", n("Let's call it a day."))
        assertEquals("i would like a coffee", n("I'd like a coffee"))
        assertEquals("they are here", n("They're here"))
        assertEquals("she does not know", n("She doesn't know"))
        assertEquals("we have been there", n("We've been there"))
        assertEquals("you will see", n("You'll see"))
        assertEquals("what is the meaning", n("What's the meaning?"))
    }

    @Test fun contractionsMatchBothWays() {
        assertEquals(n("I am not sure"), n("I'm not sure"))
        assertEquals(n("I do not know"), n("I don't know"))
        assertEquals(n("I do not know"), n("I dont know")) // STT 가 아포스트로피를 뺀 꼴
        assertEquals(n("I am going to be late"), n("I'm gonna be late"))
        assertEquals(n("it is pouring"), n("its pouring"))
        assertEquals(n("let us go"), n("lets go"))
    }

    @Test fun possessiveDropsApostropheButKeepsOclock() {
        assertEquals("my bosss car", n("My boss's car"))
        assertEquals("johns car", n("John's car"))
        assertEquals("at ten o'clock", n("at 10:00"))
        assertEquals(n("at ten o'clock"), n("at ten o clock"))
        assertEquals(n("at ten o'clock"), n("at 10 oclock"))
    }

    @Test fun numbersBecomeWords() {
        assertEquals("i have three cats", n("I have 3 cats"))
        assertEquals("one hundred twenty five", n("125"))
        assertEquals(n("125"), n("a hundred and twenty-five"))
        assertEquals(n("one hundred and five"), n("105"))
        assertEquals("one thousand", n("1,000"))
        assertEquals("two thousand twenty six", n("2026"))
        assertEquals("three point five", n("3.5"))
        assertEquals("ten thirty", n("10:30"))
        assertEquals("seven oh five", n("7:05"))
        assertEquals("ten am", n("10 a.m."))
        assertEquals("ten thirty pm", n("10:30 p.m."))
        assertEquals("the second floor", n("the 2nd floor"))
        assertEquals("the twenty first century", n("the 21st century"))
        assertEquals("the third time", n("the 3rd time"))
        assertEquals("twenty percent", n("20%"))
        assertEquals(n("twenty percent"), n("20 per cent"))
        assertEquals("it costs five dollars", n("It costs $5"))
        assertEquals("one dollar", n("$1"))
        assertEquals("zero seven", n("07"))
        assertEquals(n("thirty minutes"), n("30 minutes"))
    }

    @Test fun removesFillers() {
        assertEquals("i think it is good", n("Um, I think, uh, it's good"))
        assertEquals("i would like a coffee", n("Well, I'd like a coffee"))
        assertEquals("can we go", n("So, can we go?"))
        assertEquals("i think", n("Umm... uhh... hmm, I think"))
        assertEquals("", n("um uh"))
        assertEquals("", n("hmm"))
    }

    @Test fun keepsMeaningfulLike() {
        assertEquals("i would like a window seat", n("I would like a window seat."))
        assertEquals("it looks like rain", n("It looks like rain."))
        assertEquals("i feel like walking", n("I feel like walking"))
        assertEquals("i was like tired", n("Like, I was like like tired"))
        assertEquals("like", n("like")) // 단어 하나뿐이면 지우지 않는다 (VOCAB 정답일 수 있음)
        assertEquals("well", n("Well."))
    }

    @Test fun removesFillerYouKnowButKeepsRealKnow() {
        assertEquals("it was really hard", n("It was, you know, really hard"))
        assertEquals("it was great", n("It was great, you know"))
        assertEquals("do you know the way", n("Do you know the way?"))
        assertEquals("you know what i mean", n("You know what I mean?"))
        assertEquals("if you know the answer", n("If you know the answer"))
    }

    @Test fun unifiesBritishSpellingAndVariants() {
        assertEquals(n("My favorite color"), n("My favourite colour"))
        assertEquals("traveling", n("travelling"))
        assertEquals(n("organize"), n("organise"))
        assertEquals("email", n("e-mail"))
        assertEquals(n("all right"), n("alright"))
        assertEquals("okay", n("OK"))
        assertEquals(n("every day"), n("everyday"))
        assertEquals(n("toward the center"), n("towards the centre"))
    }

    @Test fun isIdempotent() {
        val samples = listOf(
            "I'm running late, you know.", "Um, can we push the 10:30 meeting to tomorrow?", "It's 20% off — $5!",
            "Let's touch base next week.", "I'd like 2 tickets for the 3rd show.", "Well, so, like, I dunno",
        )
        for (s in samples) {
            val once = n(s)
            assertEquals(s, once, n(once))
        }
    }

    @Test fun tokensSplitNormalizedText() {
        assertEquals(listOf("i", "am", "running", "late"), EnglishNormalizer.tokens("I'm running late."))
        assertTrue(EnglishNormalizer.tokens("").isEmpty())
    }
}
