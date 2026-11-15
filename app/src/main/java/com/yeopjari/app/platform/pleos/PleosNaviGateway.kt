package com.yeopjari.app.platform.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.yeopjari.app.core.gateway.NaviGateway
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.RouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pleos NaviHelper SDK 연동 — 내비게이션을 새로 만들지 않고 차량 기본 내비를 '오케스트레이션'한다.
 *  - 목적지/경유지 설정 (requestRoute · addWaypoint, 경유지 최대 2개)
 *  - 잔여 거리/시간 (onDestinationInfo), 다음 회전 (onTBTInfo), 도착 SOC 예측 (requestTrip → onTripInfo)
 *  - 개인 장소 사전의 실차 원천: 최근 목적지(getRecentDestinationInfo) · 즐겨찾기(getBookmarkInfo)
 *    · 도착 이벤트(onDestinationArrived → 방문 기록). 이 값들은 차 안 암호화 저장소에만 들어간다.
 */
class PleosNaviGateway(
    private val context: Context,
    private val scope: CoroutineScope,
) : NaviGateway {

    override val source = Source.PLEOS_SDK
    private val _route = MutableStateFlow(RouteState())
    override val route: StateFlow<RouteState> = _route.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _recent = MutableStateFlow<List<Place>>(emptyList())
    /** 차량 내비의 최근 목적지 */
    val recentDestinations: StateFlow<List<Place>> = _recent.asStateFlow()
    private val _bookmarks = MutableStateFlow<List<Place>>(emptyList())
    /** 차량 내비의 즐겨찾기 (집·회사 포함) */
    val bookmarks: StateFlow<List<Place>> = _bookmarks.asStateFlow()
    private val _arrivals = MutableSharedFlow<Place>(extraBufferCapacity = 8)
    /** 목적지 도착 — 개인화 엔진의 방문 기록이 된다 */
    val arrivals: SharedFlow<Place> = _arrivals.asSharedFlow()

    private var helper: NaviHelper? = null
    private var pollJob: Job? = null
    private var lastTripPoi: String? = null

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            // 안내 중이 아니면 빈 목적지 정보가 온다 → 경로 없음으로 처리
            if (info.remainDistance <= 0 && info.name.isBlank()) { _route.value = RouteState(); return }
            val place = Place(info.poiId.ifBlank { "navi_dest" }, info.name, PlaceKind.DESTINATION, info.latitude, info.longitude, info.address)
            val km = info.remainDistance / 1000f
            _route.update {
                it.copy(
                    active = true, destination = place,
                    totalKm = if (it.destination?.id != place.id || it.totalKm < km) km else it.totalKm,
                    remainingKm = km, remainingMin = info.remainTime / 60,
                    isHighway = km > 60f && it.isHighway,
                )
            }
            if (lastTripPoi != place.id) {
                lastTripPoi = place.id
                runCatching { helper?.requestTrip(TripRequest(place.lat, place.lng, place.id, place.name, "0")) }
            }
        }

        override fun onTBTInfo(list: List<TBTInfo>) {
            val next = list.firstOrNull() ?: return
            _route.update { it.copy(nextGuidance = next.description, nextTurnMeters = next.distance.toInt()) }
        }

        override fun onTripInfo(info: TripInfo) {
            _route.update { it.copy(naviEstimatedSoc = info.estimatedSocAtDest) }
        }

        override fun onWayPointInfo(list: List<WaypointInfo>) {
            _route.update { r ->
                r.copy(waypoints = list.map { Place("wp_${it.name}", it.name, PlaceKind.REST_AREA, it.latitude, it.longitude) })
            }
        }

        override fun onRouteCancelled() { _route.value = RouteState() }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            val dest = _route.value.destination
            _route.value = RouteState()
            val name = info.name.orEmpty()
            if (name.isBlank()) return
            val place = dest?.takeIf { it.name == name }
                ?: Place("arrived_$name", name, PlaceKind.DESTINATION, dest?.lat ?: 0.0, dest?.lng ?: 0.0, info.address.orEmpty())
            _arrivals.tryEmit(place)
        }

        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) {
            _recent.value = list.filter { it.name.orEmpty().isNotBlank() }.map {
                Place(it.poiId.orEmpty().ifBlank { "recent_${it.name}" }, it.name.orEmpty(), PlaceKind.DESTINATION, it.latitude, it.longitude, it.address.orEmpty())
            }
        }

        override fun onBookmarkInfo(list: List<BookmarkInfo>) {
            _bookmarks.value = list.filter { it.name.orEmpty().isNotBlank() }.map {
                val type = it.category?.name.orEmpty().uppercase()
                val kind = when {
                    "HOME" in type -> PlaceKind.HOME
                    "OFFICE" in type || "WORK" in type || "COMPANY" in type -> PlaceKind.WORK
                    else -> PlaceKind.DESTINATION
                }
                Place(it.poiId.orEmpty().ifBlank { "bookmark_${it.name}" }, it.name.orEmpty(), kind, it.latitude, it.longitude, it.address.orEmpty())
            }
        }

        override fun onBookMarkChanged() { runCatching { helper?.getBookmarkInfo() } }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) { _route.update { it.copy(speedLimitKph = speedLimit.takeIf { v -> v > 0 }) } }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "NaviHelper error: $errorCode") }
    }

    fun start() {
        if (helper != null) return
        runCatching {
            val h = NaviHelper(context)
            helper = h
            h.initialize(
                {
                    _connected.value = true
                    h.addListener(listener)
                    pollJob = scope.launch {
                        var tick = 0
                        while (isActive) {
                            runCatching { h.getDestinationInfo(); h.getTBTInfo(); h.getWaypointInfo() }
                            // 개인 장소 사전 원천은 자주 바뀌지 않으므로 1분마다
                            if (tick % 12 == 0) runCatching { h.getRecentDestinationInfo(); h.getBookmarkInfo() }
                            tick++
                            delay(5_000)
                        }
                    }
                },
                { t -> Log.i(TAG, "NaviHelper 연결 실패 → 시뮬레이터 경로 사용", t) },
            )
        }.onFailure { Log.i(TAG, "NaviHelper SDK 사용 불가", it) }
    }

    fun stop() {
        pollJob?.cancel()
        runCatching { helper?.removeListener(listener); helper?.release() }
        helper = null
    }

    override suspend fun setDestination(place: Place): Result<Unit> = runCatching {
        val h = helper ?: error("NaviHelper 미연결")
        h.requestRoute(RouteInfo(place.lat, place.lng, null, null, place.name, place.id, place.address, "0", RouteOption.RECOMMENDED, "", false))
    }

    override suspend fun addWaypoint(place: Place): Result<Unit> = runCatching {
        val h = helper ?: error("NaviHelper 미연결")
        val index = if (_route.value.waypoints.isEmpty()) WaypointIndex.FIRST else WaypointIndex.SECOND
        h.addWaypoint(RequestWaypointInfo(place.lat, place.lng, place.name, index, place.id, place.address, "0", ""))
    }

    override suspend fun cancelGuidance(): Result<Unit> = runCatching { helper?.cancelRoute() ?: error("NaviHelper 미연결") }

    companion object { private const val TAG = "PleosNavi" }
}
