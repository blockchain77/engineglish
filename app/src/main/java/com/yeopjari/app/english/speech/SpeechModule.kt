package com.yeopjari.app.english.speech

import android.content.Context
import com.yeopjari.app.english.core.AnswerJudge
import com.yeopjari.app.english.core.AudioEnvironment
import com.yeopjari.app.english.core.Clock
import com.yeopjari.app.english.core.CommandParser
import com.yeopjari.app.english.core.FluencyMeter
import com.yeopjari.app.english.core.PronunciationAnalyzer
import com.yeopjari.app.english.core.SpeechPort
import com.yeopjari.app.english.core.TextNormalizer
import kotlinx.coroutines.CoroutineScope

/**
 * 말하기 모듈 묶음 (명세 §2.1) — 통합(AppContainer)이 한 번 만든다.
 * 채점·명령·발음·유창성은 모두 앱 안 규칙 엔진이다 (서버 전송 없음, AI 모델 없음).
 */
class SpeechModule(context: Context, scope: CoroutineScope, clock: Clock) {
    private val androidAudio = AndroidAudioEnvironment(context, scope, clock)
    private val pleosPort = PleosSpeechPort(context, scope, clock, androidAudio)

    val audio: AudioEnvironment = androidAudio
    val port: SpeechPort = pleosPort
    val normalizer: TextNormalizer = EnglishNormalizer
    val pronunciation: PronunciationAnalyzer = KoreanL1PronunciationAnalyzer()
    val judge: AnswerJudge = RuleAnswerJudge(normalizer, pronunciation)
    val commands: CommandParser = BilingualCommandParser(normalizer)
    val fluency: FluencyMeter = EpdFluencyMeter()

    /** LearnerSettings.englishRate (0.75~1.1) — Android TTS 영어 음성에만 적용 */
    fun setEnglishRate(rate: Float) = pleosPort.setEnglishRate(rate)

    /** true = 영어 보통 속도도 Android TTS 먼저 (Pleos 영어 음성이 어색할 때 통합에서 끌 수 있다) */
    fun preferAndroidEnglishVoice(prefer: Boolean) {
        pleosPort.preferPleosEnglish = !prefer
    }

    fun release() {
        pleosPort.release()
        androidAudio.release()
    }
}
