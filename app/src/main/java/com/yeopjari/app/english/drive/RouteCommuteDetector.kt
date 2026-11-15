package com.yeopjari.app.english.drive

import com.yeopjari.app.english.core.CommuteContext
import com.yeopjari.app.english.core.CommuteDetector
import com.yeopjari.app.english.core.CommuteMap
import com.yeopjari.app.english.core.CommutePattern
import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.DestinationCategory
import com.yeopjari.app.english.core.DrivingSignals
import com.yeopjari.app.english.core.Kst
import kotlin.math.abs

/**
 * 출퇴근 판단 (명세 §4.6).
 * 1) 출발지·목적지가 지도 장소로 확인되면: 집→회사 = 출근, 회사→집 = 퇴근 (0.9)
 * 2) 목적지 종류만 알면: 회사 = 출근, 집 = 퇴근 (0.7)
 * 3) 아니면 시각 (KST 05:00–10:59 출근, 16:00–21:59 퇴근 0.4, 그 밖 OTHER 0.2).
 *    학습된 출발 시각이 3번 이상 있고 지금이 그 중앙값 ±45분이면 그 시간대 0.5.
 * 좌표는 저장하지 않는다 — learn 은 출발 '분'만 더한다.
 */
class RouteCommuteDetector(private val map: CommuteMap) : CommuteDetector {

    override fun detect(signals: DrivingSignals, pattern: CommutePattern, nowMs: Long): CommuteContext {
        val from = signals.originPlaceId
        val to = signals.destinationPlaceId
        val fromCat = map.place(from)?.category
        val toCat = map.place(to)?.category ?: signals.destinationCategory
        val remaining = signals.routeRemainingSec

        fun ctx(slot: CommuteSlot, confidence: Float) = CommuteContext(
            slot = slot,
            fromPlaceId = from,
            toPlaceId = to,
            routeId = map.routeBetween(from, to)?.id,
            routeRemainingSec = remaining,
            destinationCategory = toCat,
            confidence = confidence,
        )

        if (fromCat == DestinationCategory.HOME && toCat == DestinationCategory.OFFICE) return ctx(CommuteSlot.MORNING, 0.9f)
        if (fromCat == DestinationCategory.OFFICE && toCat == DestinationCategory.HOME) return ctx(CommuteSlot.EVENING, 0.9f)
        if (toCat == DestinationCategory.OFFICE) return ctx(CommuteSlot.MORNING, 0.7f)
        if (toCat == DestinationCategory.HOME) return ctx(CommuteSlot.EVENING, 0.7f)

        val minute = Kst.minuteOfDay(nowMs)
        learnedSlot(pattern, minute)?.let { return ctx(it, 0.5f) }
        return when (minute) {
            in MORNING_FROM..MORNING_TO -> ctx(CommuteSlot.MORNING, 0.4f)
            in EVENING_FROM..EVENING_TO -> ctx(CommuteSlot.EVENING, 0.4f)
            else -> ctx(CommuteSlot.OTHER, 0.2f)
        }
    }

    override fun learn(pattern: CommutePattern, context: CommuteContext, departedMs: Long): CommutePattern {
        val minute = Kst.minuteOfDay(departedMs)
        return when (context.slot) {
            CommuteSlot.MORNING -> pattern.copy(morningDepartures = (pattern.morningDepartures + minute).takeLast(KEEP), trips = pattern.trips + 1)
            CommuteSlot.EVENING -> pattern.copy(eveningDepartures = (pattern.eveningDepartures + minute).takeLast(KEEP), trips = pattern.trips + 1)
            CommuteSlot.OTHER -> pattern.copy(trips = pattern.trips + 1)
        }
    }

    /** 학습된 출발 시각과 가까우면 그 시간대 */
    private fun learnedSlot(pattern: CommutePattern, minute: Int): CommuteSlot? {
        val m = median(pattern.morningDepartures)
        val e = median(pattern.eveningDepartures)
        val nearMorning = m != null && pattern.morningDepartures.size >= MIN_LEARNED && abs(minute - m) <= NEAR_MIN
        val nearEvening = e != null && pattern.eveningDepartures.size >= MIN_LEARNED && abs(minute - e) <= NEAR_MIN
        return when {
            nearMorning && !nearEvening -> CommuteSlot.MORNING
            nearEvening && !nearMorning -> CommuteSlot.EVENING
            else -> null
        }
    }

    private fun median(xs: List<Int>): Int? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return s[s.size / 2]
    }

    companion object {
        const val MORNING_FROM = 5 * 60
        const val MORNING_TO = 10 * 60 + 59
        const val EVENING_FROM = 16 * 60
        const val EVENING_TO = 21 * 60 + 59
        const val KEEP = 10
        const val MIN_LEARNED = 3
        const val NEAR_MIN = 45
    }
}
