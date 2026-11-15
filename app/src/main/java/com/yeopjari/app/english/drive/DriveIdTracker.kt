package com.yeopjari.app.english.drive

import com.yeopjari.app.english.core.GearPosition
import com.yeopjari.app.english.core.Ids

/**
 * 주행 id (명세 §4.1 driveId) — P 에서 60초 이상 서 있다가 출발하면 새 주행, 데모 시나리오 시작도 새 주행.
 * 잠깐 P(60초 미만)는 같은 주행으로 본다. 정차 중에는 마지막 주행 id 를 유지한다. UNKNOWN 기어는 무시.
 */
class DriveIdTracker(private val minParkMs: Long = MIN_PARK_MS) {
    var driveId: String? = null
        private set
    private var parkedSinceMs: Long? = null

    /** 기어 갱신. 새 주행 id 를 발급했으면 true */
    fun update(gear: GearPosition, nowMs: Long): Boolean {
        when (gear) {
            GearPosition.UNKNOWN -> return false
            GearPosition.P -> {
                if (parkedSinceMs == null) parkedSinceMs = nowMs
                return false
            }
            else -> {
                val parked = parkedSinceMs
                parkedSinceMs = null
                if (driveId == null || (parked != null && nowMs - parked >= minParkMs)) {
                    driveId = Ids.next("drive", nowMs)
                    return true
                }
                return false
            }
        }
    }

    /** 데모 시나리오 시작 등 — 바로 새 주행 */
    fun forceNew(nowMs: Long, parked: Boolean): String {
        val id = Ids.next("drive", nowMs)
        driveId = id
        parkedSinceMs = if (parked) nowMs else null
        return id
    }

    fun reset() {
        driveId = null
        parkedSinceMs = null
    }

    companion object {
        const val MIN_PARK_MS = 60_000L
    }
}
