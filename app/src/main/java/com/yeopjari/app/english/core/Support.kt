package com.yeopjari.app.english.core

import java.time.Instant
import java.time.ZoneId

/*
 * 옆자리 영어 — 작은 공용 구현 (순수 Kotlin).
 */

/** 실제 시계 */
object WallClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** 한국 시간(KST) 기준 날짜·시각 계산 — 스트릭·일일 통계·출퇴근 시간대 */
object Kst {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")

    /** epoch ms → KST epochDay */
    fun epochDay(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    /** epoch ms → KST 하루 중 분 (0..1439) */
    fun minuteOfDay(ms: Long): Int {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return t.hour * 60 + t.minute
    }

    /** "오전 7:52" */
    fun clockLabel(ms: Long): String {
        val m = minuteOfDay(ms)
        val h = m / 60
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$ampm $h12:${(m % 60).toString().padStart(2, '0')}"
    }
}

fun Clock.epochDay(): Long = Kst.epochDay(nowMs())
fun Clock.minuteOfDay(): Int = Kst.minuteOfDay(nowMs())

/** 테스트·대체용 메모리 저장소 (암호화 아님) */
class InMemorySecureStore : SecureStore {
    private val files = LinkedHashMap<String, ByteArray>()
    override val encrypted: Boolean = false

    @Synchronized override fun write(name: String, bytes: ByteArray): Boolean { files[name] = bytes.copyOf(); return true }
    @Synchronized override fun read(name: String): ByteArray? = files[name]?.copyOf()
    @Synchronized override fun delete(name: String) { files.remove(name) }
    @Synchronized override fun size(name: String): Long = files[name]?.size?.toLong() ?: 0L
    @Synchronized fun names(): List<String> = files.keys.toList()
}

/** 짧은 고유 id ("s-1h2k3j-7") — 세션·주행·제안 카드 */
object Ids {
    private var seq = 0L
    @Synchronized fun next(prefix: String, nowMs: Long): String = "$prefix-${nowMs.toString(36)}-${(seq++).toString(36)}"
}
