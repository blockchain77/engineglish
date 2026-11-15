package com.cartutor.engineglish.english.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.cartutor.engineglish.R
import com.cartutor.engineglish.english.core.SoundCue
import com.cartutor.engineglish.english.core.SoundPort
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

/*
 * 효과음 재생 — 정답 · 거의 맞음 · 다시 · 연속 정답 · 레벨 업 · 학습 완료.
 *
 * 소리는 모두 직접 합성한 WAV (tools/gen_sounds.py → res/raw/sfx_*.wav, 외부 음원 없음).
 * SoundPool 에 미리 올려 두고 지연 없이 튼다. 오디오 포커스를 요청하지 않으므로
 * 코치의 TTS(안내 음성)를 끊거나 줄이지 않고 그 위에 섞여 나온다.
 * 용도는 USAGE_ASSISTANCE_SONIFICATION — 차량 오디오에서 '시스템 알림음' 쪽으로 흘러 미디어 볼륨과 따로 논다.
 *
 * 언제 낼지는 코치가 정한다 (설정 '효과음' 켬 · 운전 부하가 '위험'이 아닐 때만).
 * 여기서도 한 번 더 막을 수 있게 [enabled] 와 [allowed] 를 둔다.
 */
class SoundPoolPlayer(
    context: Context,
    /** false 를 돌려주면 소리를 내지 않는다 (예: 운전 부하가 '위험'일 때). 기본은 늘 허용 */
    private val allowed: () -> Boolean = { true },
) : SoundPort {

    /** 설정 '효과음' — 꺼 두면 아무 소리도 내지 않는다 */
    @Volatile
    var enabled: Boolean = true

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val sampleIds = EnumMap<SoundCue, Int>(SoundCue::class.java)
    private val ready: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val lastPlayedMs = EnumMap<SoundCue, Long>(SoundCue::class.java)
    private val streams = ArrayDeque<Int>()

    @Volatile
    private var released = false

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) ready += sampleId }
        val app = context.applicationContext
        SoundCue.entries.forEach { cue -> sampleIds[cue] = pool.load(app, rawOf(cue), 1) }
    }

    override fun play(cue: SoundCue) {
        if (released || !enabled || !allowed()) return
        val id = sampleIds[cue] ?: return
        if (id !in ready) { // 아직 올리는 중 — 건너뛴다 (늦게 나오는 소리는 없느니만 못하다)
            Log.i(TAG, "cue ${cue.name} skipped (sample not loaded yet)")
            return
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            // 같은 소리가 연달아 겹쳐 울리지 않게
            val last = lastPlayedMs[cue]
            if (last != null && now - last < REPEAT_GUARD_MS) return
            lastPlayedMs[cue] = now
            val v = volumeOf(cue)
            val stream = pool.play(id, v, v, priorityOf(cue), 0, 1f)
            Log.i(TAG, "cue ${cue.name} ${if (stream != 0) "played" else "failed"}")
            if (stream != 0) {
                streams.addLast(stream)
                while (streams.size > MAX_STREAMS) streams.removeFirst()
            }
        }
    }

    /** 울리고 있는 효과음을 바로 멈춘다 (운전 부하가 '위험'으로 올라갔을 때) */
    fun stop() {
        if (released) return
        synchronized(this) {
            streams.forEach { pool.stop(it) }
            streams.clear()
        }
    }

    fun release() {
        if (released) return
        released = true
        pool.release()
    }

    private fun rawOf(cue: SoundCue): Int = when (cue) {
        SoundCue.CORRECT -> R.raw.sfx_correct
        SoundCue.PARTIAL -> R.raw.sfx_partial
        SoundCue.WRONG -> R.raw.sfx_wrong
        SoundCue.STREAK -> R.raw.sfx_streak
        SoundCue.LEVEL_UP -> R.raw.sfx_levelup
        SoundCue.SESSION_COMPLETE -> R.raw.sfx_session_complete
    }

    /** 파일마다 이미 크기를 맞춰 뒀다 (거의 맞음·다시는 더 작게). 여기서는 살짝만 다듬는다 */
    private fun volumeOf(cue: SoundCue): Float = when (cue) {
        SoundCue.CORRECT -> 0.90f
        SoundCue.PARTIAL -> 0.85f
        SoundCue.WRONG -> 0.85f
        SoundCue.STREAK -> 0.90f
        SoundCue.LEVEL_UP -> 0.95f
        SoundCue.SESSION_COMPLETE -> 1.00f
    }

    private fun priorityOf(cue: SoundCue): Int = when (cue) {
        SoundCue.LEVEL_UP, SoundCue.SESSION_COMPLETE -> 2
        else -> 1
    }

    private companion object {
        const val TAG = "EngineglishSound"
        const val MAX_STREAMS = 3
        const val REPEAT_GUARD_MS = 120L
    }
}
