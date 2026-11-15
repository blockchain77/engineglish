package com.cartutor.engineglish.english.drive

import com.cartutor.engineglish.english.core.CameraKind
import com.cartutor.engineglish.english.core.CommuteMap
import com.cartutor.engineglish.english.core.CommutePlace
import com.cartutor.engineglish.english.core.CommuteRoute
import com.cartutor.engineglish.english.core.LatLng
import com.cartutor.engineglish.english.core.ManeuverType
import com.cartutor.engineglish.english.core.RoadKind
import com.cartutor.engineglish.english.core.RouteSegment
import com.cartutor.engineglish.english.core.SegmentEvent
import com.cartutor.engineglish.english.core.SegmentEventKind
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 출퇴근 경로 하나를 '달릴 수 있게' 펼친 것 — 구간 순서·방향, 절대 위치(m)의 안내 지점·카메라·구역, 남은 시간 계산.
 *
 * 방향: 경로가 구간을 끝점→시작점으로 지나면 reversed. 그때 구간 이벤트는 거울처럼 뒤집는다
 * (위치 = 길이 − 위치, 좌↔우, 진출↔진입). 도착(ARRIVE)은 구간 이벤트가 아니라 경로 끝에 자동으로 붙는다.
 *
 * 남은 시간: 구간 평소 속도(정체 구역 8km/h, 어린이 보호구역 28km/h)로 10m 단위 적분 → 명목 시간(32분)에 맞춰 보정.
 */
class RoutePlan(
    val map: CommuteMap,
    val route: CommuteRoute,
    nominalSec: Int = CommuteMaps.NOMINAL_ROUTE_SEC,
) {
    class Leg(val segment: RouteSegment, val reversed: Boolean, val startM: Int, val events: List<SegmentEvent>) {
        val lengthM: Int get() = segment.lengthM
        val endM: Int get() = startM + segment.lengthM
        val from: LatLng get() = if (reversed) segment.end else segment.start
        val to: LatLng get() = if (reversed) segment.start else segment.end
    }

    data class PlannedManeuver(val atM: Int, val type: ManeuverType, val descriptionKo: String)
    data class PlannedCamera(val atM: Int, val kind: CameraKind, val limitKph: Int?, val textKo: String)
    data class PlannedZone(val kind: SegmentEventKind, val startM: Int, val endM: Int, val limitKph: Int?, val textKo: String)

    val legs: List<Leg>
    val totalM: Int
    val origin: CommutePlace? = map.place(route.fromPlaceId)
    val destination: CommutePlace? = map.place(route.toPlaceId)
    val maneuvers: List<PlannedManeuver>
    val cameras: List<PlannedCamera>
    val zones: List<PlannedZone>

    private val bucketCount: Int
    private val bucketSec: DoubleArray
    private val suffixSec: DoubleArray
    private val etaFactor: Double

    init {
        val segs = map.segmentsOf(route)
        val built = ArrayList<Leg>(segs.size)
        var cursor: LatLng? = origin?.point
        var start = 0
        for (seg in segs) {
            val c = cursor
            val reversed = c != null && MapMatcher.distanceM(c, seg.end) < MapMatcher.distanceM(c, seg.start)
            val events = if (reversed) seg.events.mapNotNull { mirror(it, seg.lengthM) } else seg.events.filterNot { isArrive(it) }
            built += Leg(seg, reversed, start, events.sortedBy { it.atM })
            start += seg.lengthM
            cursor = if (reversed) seg.start else seg.end
        }
        legs = built
        totalM = start

        val ms = ArrayList<PlannedManeuver>()
        val cams = ArrayList<PlannedCamera>()
        val zs = ArrayList<PlannedZone>()
        for (leg in legs) {
            for (e in leg.events) {
                val at = leg.startM + e.atM.coerceIn(0, leg.lengthM)
                when (e.kind) {
                    SegmentEventKind.MANEUVER -> {
                        val type = e.maneuver ?: continue
                        ms += PlannedManeuver(at, type, e.textKo.ifBlank { "${type.ko} · ${leg.segment.nameKo}" })
                    }
                    SegmentEventKind.SPEED_CAMERA -> {
                        val kind = if (leg.segment.road == RoadKind.SCHOOL_ZONE) CameraKind.SCHOOL_ZONE else CameraKind.SPEED_FIXED
                        cams += PlannedCamera(at, kind, e.limitKph ?: leg.segment.speedLimitKph, e.textKo)
                    }
                    else -> {
                        val end = (at + e.lengthM).coerceAtMost(leg.endM)
                        if (end > at) {
                            val limit = if (e.kind == SegmentEventKind.SCHOOL_ZONE) (e.limitKph ?: SCHOOL_ZONE_LIMIT_KPH) else e.limitKph
                            zs += PlannedZone(e.kind, at, end, limit, e.textKo)
                        }
                    }
                }
            }
        }
        ms += PlannedManeuver(totalM, ManeuverType.ARRIVE, "${destination?.nameKo ?: "목적지"} 도착")
        maneuvers = ms.sortedBy { it.atM }
        cameras = cams.sortedBy { it.atM }
        zones = zs.sortedBy { it.startM }

        // 남은 시간 적분표
        bucketCount = ceil(totalM / BUCKET_M).toInt().coerceAtLeast(1)
        bucketSec = DoubleArray(bucketCount)
        for (i in 0 until bucketCount) {
            val from = i * BUCKET_M
            val len = min(BUCKET_M, totalM - from).coerceAtLeast(0.0)
            val mid = from + len / 2
            bucketSec[i] = if (len <= 0.0) 0.0 else len / (plannedSpeedKph(mid) / 3.6)
        }
        suffixSec = DoubleArray(bucketCount + 1)
        for (i in bucketCount - 1 downTo 0) suffixSec[i] = suffixSec[i + 1] + bucketSec[i]
        etaFactor = if (suffixSec[0] > 0.0) nominalSec / suffixSec[0] else 1.0
    }

    fun legAt(posM: Double): Leg = legs.firstOrNull { posM < it.endM } ?: legs.last()

    fun legIndexAt(posM: Double): Int = legs.indexOfFirst { posM < it.endM }.let { if (it < 0) legs.lastIndex else it }

    fun zoneAt(posM: Double, kind: SegmentEventKind): PlannedZone? =
        zones.firstOrNull { it.kind == kind && posM >= it.startM && posM < it.endM }

    /** 아직 들어가지 않은 다음 구역 */
    fun nextZone(posM: Double, kind: SegmentEventKind): PlannedZone? =
        zones.firstOrNull { it.kind == kind && it.startM > posM }

    /** 아직 지나지 않은 다음 안내 지점 (도착 포함) */
    fun nextManeuver(posM: Double): PlannedManeuver? = maneuvers.firstOrNull { it.atM > posM }

    fun nextCamera(posM: Double): PlannedCamera? = cameras.firstOrNull { it.atM > posM }

    /** 지금 위치에서 도착까지 남은 시간(초, 명목 32분 기준으로 보정) */
    fun remainingSec(posM: Double): Int = (remainingExact(posM) * etaFactor).roundToInt()

    /** 남은 시간이 [sec] 초가 되는 위치 (도착 3분 전으로 건너뛰기 데모용) */
    fun posForRemainingSec(sec: Int): Double {
        val target = sec / etaFactor
        if (target >= suffixSec[0]) return 0.0
        if (target <= 0.0) return totalM.toDouble()
        var lo = 0.0
        var hi = totalM.toDouble()
        repeat(48) {
            val mid = (lo + hi) / 2
            if (remainingExact(mid) > target) lo = mid else hi = mid
        }
        return hi
    }

    fun locationAt(posM: Double): LatLng {
        val leg = legAt(posM)
        val t = ((posM - leg.startM) / leg.lengthM.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        if (t <= 0.0) return leg.from
        if (t >= 1.0) return leg.to
        return LatLng(leg.from.lat + (leg.to.lat - leg.from.lat) * t, leg.from.lng + (leg.to.lng - leg.from.lng) * t)
    }

    /** 이 위치의 제한 속도 (어린이 보호구역 안이면 30) */
    fun speedLimitAt(posM: Double): Int {
        val base = legAt(posM).segment.speedLimitKph
        val school = zoneAt(posM, SegmentEventKind.SCHOOL_ZONE) ?: return base
        return min(base, school.limitKph ?: SCHOOL_ZONE_LIMIT_KPH)
    }

    private fun remainingExact(posM: Double): Double {
        val p = posM.coerceIn(0.0, totalM.toDouble())
        val i = (p / BUCKET_M).toInt()
        if (i >= bucketCount) return 0.0
        val from = i * BUCKET_M
        val len = min(BUCKET_M, totalM - from)
        val fraction = if (len <= 0.0) 0.0 else ((from + len - p) / len).coerceIn(0.0, 1.0)
        return suffixSec[i + 1] + bucketSec[i] * fraction
    }

    private fun plannedSpeedKph(posM: Double): Double {
        var v = legAt(posM).segment.typicalSpeedKph.toDouble()
        if (zoneAt(posM, SegmentEventKind.SCHOOL_ZONE) != null) v = min(v, SCHOOL_ZONE_SPEED_KPH)
        if (zoneAt(posM, SegmentEventKind.JAM) != null) v = JAM_PLANNED_KPH
        return v.coerceAtLeast(1.0)
    }

    companion object {
        const val BUCKET_M = 10.0
        const val JAM_PLANNED_KPH = 8.0
        const val SCHOOL_ZONE_SPEED_KPH = 28.0
        const val SCHOOL_ZONE_LIMIT_KPH = 30

        private fun isArrive(e: SegmentEvent) = e.kind == SegmentEventKind.MANEUVER && e.maneuver == ManeuverType.ARRIVE

        /** 반대 방향으로 지날 때의 이벤트 */
        fun mirror(e: SegmentEvent, lengthM: Int): SegmentEvent? {
            if (isArrive(e)) return null
            val at = (lengthM - e.atM - e.lengthM).coerceIn(0, lengthM)
            return if (e.kind == SegmentEventKind.MANEUVER) {
                e.copy(atM = at, maneuver = e.maneuver?.let { mirrorManeuver(it) }, textKo = "")
            } else {
                e.copy(atM = at)
            }
        }

        fun mirrorManeuver(t: ManeuverType): ManeuverType = when (t) {
            ManeuverType.LEFT -> ManeuverType.RIGHT
            ManeuverType.RIGHT -> ManeuverType.LEFT
            ManeuverType.KEEP_LEFT -> ManeuverType.KEEP_RIGHT
            ManeuverType.KEEP_RIGHT -> ManeuverType.KEEP_LEFT
            ManeuverType.EXIT -> ManeuverType.ENTER
            ManeuverType.ENTER -> ManeuverType.EXIT
            else -> t
        }

        /** 지도의 모든 경로 */
        fun all(map: CommuteMap): Map<String, RoutePlan> = map.routes.associate { it.id to RoutePlan(map, it) }
    }
}

/** 위치 → 지도 구간·장소 맞추기 (평면 근사, 수 km 범위에서 충분) */
object MapMatcher {
    private const val M_PER_DEG_LAT = 110_540.0
    private const val M_PER_DEG_LNG_EQ = 111_320.0

    /** 실차 위치를 구간 선분에 맞출 때 허용 거리 */
    const val SEGMENT_MATCH_M = 150.0
    /** 목적지·출발지를 지도 장소로 볼 거리 */
    const val PLACE_MATCH_M = 300.0

    fun distanceM(a: LatLng, b: LatLng): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2.0))
        val dx = (b.lng - a.lng) * M_PER_DEG_LNG_EQ * k
        val dy = (b.lat - a.lat) * M_PER_DEG_LAT
        return sqrt(dx * dx + dy * dy)
    }

    fun distanceToSegmentM(p: LatLng, a: LatLng, b: LatLng): Double {
        val kx = M_PER_DEG_LNG_EQ * cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * kx
        val ay = (a.lat - p.lat) * M_PER_DEG_LAT
        val bx = (b.lng - p.lng) * kx
        val by = (b.lat - p.lat) * M_PER_DEG_LAT
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    /** 가장 가까운 구간 (maxM 안), 없으면 null */
    fun match(map: CommuteMap, p: LatLng, maxM: Double = SEGMENT_MATCH_M): RouteSegment? {
        var best: RouteSegment? = null
        var bestD = Double.MAX_VALUE
        for (s in map.segments) {
            val d = distanceToSegmentM(p, s.start, s.end)
            if (d <= maxM && d < bestD) {
                best = s
                bestD = d
            }
        }
        return best
    }

    fun nearestPlace(map: CommuteMap, p: LatLng, maxM: Double = PLACE_MATCH_M): CommutePlace? {
        var best: CommutePlace? = null
        var bestD = Double.MAX_VALUE
        for (pl in map.places) {
            val d = distanceM(p, pl.point)
            if (d <= maxM && d < bestD) {
                best = pl
                bestD = d
            }
        }
        return best
    }

    /** 좌표를 소수점 [decimals] 자리로 (위치는 화면·판단에만 쓰고 저장하지 않는다) */
    fun coarse(p: LatLng, decimals: Int = 4): LatLng {
        var f = 1.0
        repeat(decimals) { f *= 10.0 }
        return LatLng((p.lat * f).roundToInt() / f, (p.lng * f).roundToInt() / f)
    }
}
