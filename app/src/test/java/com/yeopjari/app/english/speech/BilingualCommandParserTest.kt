package com.yeopjari.app.english.speech

import com.yeopjari.app.english.core.LearnerCommand
import com.yeopjari.app.english.core.LearnerCommand.DONT_KNOW
import com.yeopjari.app.english.core.LearnerCommand.END_SESSION
import com.yeopjari.app.english.core.LearnerCommand.HINT
import com.yeopjari.app.english.core.LearnerCommand.MEANING
import com.yeopjari.app.english.core.LearnerCommand.NO
import com.yeopjari.app.english.core.LearnerCommand.PAUSE
import com.yeopjari.app.english.core.LearnerCommand.REPEAT
import com.yeopjari.app.english.core.LearnerCommand.RESUME
import com.yeopjari.app.english.core.LearnerCommand.SKIP
import com.yeopjari.app.english.core.LearnerCommand.SLOWER
import com.yeopjari.app.english.core.LearnerCommand.YES
import com.yeopjari.app.english.core.ListenContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilingualCommandParserTest {

    private val parser = BilingualCommandParser(EnglishNormalizer)

    private fun answer(text: String) = parser.parse(text, ListenContext.ANSWER)
    private fun confirm(text: String) = parser.parse(text, ListenContext.CONFIRM)

    private fun check(expected: LearnerCommand?, vararg texts: String, ctx: ListenContext = ListenContext.ANSWER) {
        for (t in texts) assertEquals("'$t'", expected, parser.parse(t, ctx))
    }

    @Test fun englishCommands() {
        check(REPEAT, "repeat", "Repeat, please.", "Can you repeat that?", "Could you say that again?", "One more time, please", "again", "Pardon?", "What?")
        check(SLOWER, "slower", "Slowly, please", "Could you speak more slowly?", "slow down")
        check(SKIP, "skip", "Next.", "pass", "Next one, please", "Um, next", "Okay, next", "skip this one")
        check(HINT, "hint", "Give me a hint.", "Hint please")
        check(MEANING, "meaning", "What does it mean?", "What's the meaning?", "in Korean", "translate")
        check(PAUSE, "pause", "stop", "Wait.", "Hold on", "hang on", "Wait a second")
        check(RESUME, "resume", "continue", "Keep going", "Let's continue", "go on", "I'm ready")
        check(DONT_KNOW, "I don't know", "I dont know", "no idea", "I have no idea", "I'm not sure", "I forgot")
        check(END_SESSION, "That's enough", "quit", "I'm done", "Let's stop here", "end session")
    }

    @Test fun wholeUtteranceRuleKeepsAnswers() {
        check(
            null,
            "The next meeting", "next week", "Can we push the meeting to tomorrow?", "I would like to continue the meeting",
            "I'm running late", "Let's call it a day.", "Please turn right here.", "", "   ",
        )
    }

    @Test fun koreanCommands() {
        check(REPEAT, "다시", "다시 말해 주세요", "한 번 더", "뭐라고요?", "못 들었어요")
        check(SLOWER, "천천히", "천천히 말해 줘", "다시 천천히")
        check(SKIP, "넘겨", "다음 문제", "패스", "건너뛰어", "스킵")
        check(HINT, "힌트", "힌트 줘")
        check(MEANING, "뜻", "무슨 뜻이에요?", "뜻이 뭐야", "한국어로 해 줘")
        check(PAUSE, "잠깐", "잠깐만요", "멈춰", "잠시만")
        check(RESUME, "계속", "계속해", "이어서 해 줘")
        check(DONT_KNOW, "모르겠어요", "몰라", "기억이 안 나요")
        check(END_SESSION, "그만", "그만할래", "오늘은 여기까지", "종료")
    }

    @Test fun longestKoreanPhraseWins() {
        assertEquals(RESUME, answer("다시 시작")) // '다시'(REPEAT) 보다 긴 '다시 시작'
        assertEquals(SLOWER, answer("다시 천천히"))
    }

    @Test fun koreanNegationIsIgnored() {
        assertNull(answer("넘기지 마"))
    }

    @Test fun mixedKoreanAndEnglish() {
        assertEquals(SKIP, answer("skip 해줘"))
    }

    @Test fun romanizedKoreanFromEnglishStt() {
        check(REPEAT, "dashi", "Da si", "Dah she.", "dasi")
        check(END_SESSION, "geuman", "Goo man")
        check(RESUME, "gye sok", "kyesok")
        check(PAUSE, "jamkkan", "jam kan")
        check(SKIP, "neomgyeo", "num gyo")
        check(SLOWER, "chun chun hee")
        check(DONT_KNOW, "molla")
    }

    @Test fun yesNoOnlyInConfirm() {
        check(null, "yes", "no", "okay", "sure", "네", "아니요", "ne", "ani")
        check(YES, "yes", "Yeah, sure", "sure why not", "Okay!", "No problem", "uh-huh", "네", "네, 좋아요", "응", "좋아요", "ne", "joa", "Of course", ctx = ListenContext.CONFIRM)
        check(NO, "no", "No thanks", "maybe later", "nope", "Not now", "아니요", "괜찮아요", "다음에 할게요", "ani", "아니 괜찮아", ctx = ListenContext.CONFIRM)
    }

    @Test fun confirmPrefersLeadingYesNo() {
        assertEquals(YES, confirm("네, 다시 해 주세요"))
        assertEquals(NO, confirm("아니, 다시"))
    }

    @Test fun otherCommandsStillWorkInConfirm() {
        assertEquals(END_SESSION, confirm("stop session"))
        assertEquals(END_SESSION, confirm("그만"))
        assertEquals(REPEAT, confirm("repeat"))
    }

    @Test fun defaultContextIsAnswer() {
        assertEquals(SKIP, parser.parse("next"))
        assertNull(parser.parse("yes"))
    }
}
