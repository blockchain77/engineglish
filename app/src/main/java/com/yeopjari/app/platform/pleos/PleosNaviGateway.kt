package com.yeopjari.app.platform.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
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
import com.yeopjari.app.core.model.GeoPoint
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.english.core.CameraAhead
import com.yeopjari.app.english.core.CautionAhead
import com.yeopjari.app.english.core.Maneuver
import com.yeopjari.app.english.drive.NaviMapping
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
import kotlin.math.roundToInt

/**
 * Pleos NaviHelper SDK 연동 — 내비게이션을 새로 만들지 않고 차량 기본 내비를 '오케스트레이션'한다.
 *  - 목적지/경유지 설정 (requestRoute · addWaypoint, 경유지 최대 2개)
 *  - 잔여 거리/시간 (onDestinationInfo), 다음 안내 지점 (onTBTInfo → Maneuver), 단속 카메라 (onCameraAlert),
 *    주의 구간 (onCautionPointInfo), 도로 제한 속도, 안내 상태 (onNavigationStatus), 현재 위치 (onCurrentLocationInfo)
 *    → 옆자리 영어의 운전 부하 신호
 *  - 도착 이벤트 (onDestinationArrived)
 *
 * @param personalPlaceSync true 면 예전 기능용으로 최근 목적지·즐겨찾기를 읽고 도착 SOC(requestTrip)를 묻는다.
 *        옆자리 영어(DriveModule)는 false — 개인 장소를 읽지 않는다.
 * 공개 API 만 쓴다 (getInternalApi 호출 없음). 목적지 이름·주소·위치는 로그에 남기지 않는다.
 */
class PleosNaviGateway(
    private val context: Context,
    private val scope: CoroutineScope,
    private val personalPlaceSync: Boolean = false,
) : NaviGateway {

    override val source = Source.PLEOS_SDK
    private val _route = MutableStateFlow(RouteState())
    override val route: StateFlow<RouteState> = _route.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _recent = MutableStateFlow<List<Place>>(emptyList())
    /** 차량 내비의 최근 목적지 (personalPlaceSync 일 때만) */
    val recentDestinations: StateFlow<List<Place>> = _recent.asStateFlow()
    private val _bookmarks = MutableStateFlow<List<Place>>(emptyList())
    /** 차량 내비의 즐겨찾기 (personalPlaceSync 일 때만) */
    val bookmarks: StateFlow<List<Place>> = _bookmarks.asStateFlow()
    private val _arrivals = MutableSharedFlow<Place>(extraBufferCapacity = 8)
    /** 목적지 도착 (이름이 있을 때) */
    val arrivals: SharedFlow<Place> = _arrivals.asSharedFlow()

    private val _arrivedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** 목적지 도착 신호 (이름과 무관하게 매번) — DrivingSignals.arrived 5초 */
    val arrivedEvents: SharedFlow<Unit> = _arrivedEvents.asSharedFlow()

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    /** 내비가 아는 현재 위치 (FusedLocation 권한이 없을 때의 대체 원천) */
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    private val _navigationStatus = MutableStateFlow<String?>(null)
    /** NavigationStatus 이름 (INACTIVE · INITIALIZING · FREE_DRIVE · ROUTE_DRIVE) — 진단용 */
    val navigationStatus: StateFlow<String?> = _navigationStatus.asStateFlow()

    private var helper: NaviHelper? = null
    private var pollJob: Job? = null
    private var lastTripPoi: String? = null
    private var cameraExpiry: Job? = null
    private var cautionExpiry: Job? = null
    private var maneuverExpiry: Job? = null

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            // 안내 중이 아니면 빈 목적지 정보가 온다 → 경로 없음으로 처리 (제한 속도·카메라는 유지)
            if (info.remainDistance <= 0 && info.name.isBlank()) {
                _route.update { RouteState(speedLimitKph = it.speedLimitKph, camera = it.camera, caution = it.caution) }
                return
            }
            val place = Place(info.poiId.ifBlank { "navi_dest" }, info.name, PlaceKind.DESTINATION, info.latitude, info.longitude, info.address)
            val km = info.remainDistance / 1000f
            _route.update {
                it.copy(
                    active = true, destination = place,
                    totalKm = if (it.destination?.id != place.id || it.totalKm < km) km else it.totalKm,
                    remainingKm = km, remainingMin = info.remainTime / 60,
                    isHighway = km > 60f && it.isHighway,
                    remainingSec = info.remainTime.coerceAtLeast(0),
                    remainingM = info.remainDistance.coerceAtLeast(0),
                )
            }
            if (personalPlaceSync && lastTripPoi != place.id) {
                lastTripPoi = place.id
                runCatching { helper?.requestTrip(TripRequest(place.lat, place.lng, place.id, place.name, "0")) }
            }
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val next = tbtInfoList.firstOrNull()
            if (next == null) {
                _route.update { it.copy(maneuver = null, nextTurnMeters = null) }
                return
            }
            val meters = next.distance.roundToInt().coerceAtLeast(0)
            val maneuver = Maneuver(NaviMapping.maneuverType(next.type.name), meters, next.description)
            _route.update { it.copy(nextGuidance = next.description, nextTurnMeters = meters, maneuver = maneuver) }
            maneuverExpiry = expire(maneuverExpiry, MANEUVER_STALE_MS) { it.copy(maneuver = null) }
        }

        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            val nearest = cameraAlertList.filter { it.remainDistance >= 0 }.minByOrNull { it.remainDistance }
            val camera = nearest?.let { CameraAhead(NaviMapping.cameraKind(it.type.name), it.remainDistance, it.speedLimit.takeIf { l -> l > 0 }) }
            _route.update { it.copy(camera = camera) }
            if (camera != null) cameraExpiry = expire(cameraExpiry, ALERT_STALE_MS) { it.copy(camera = null) }
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            val nearest = cautionPointList.mapNotNull { p ->
                NaviMapping.cautionKind(p.type.name)?.let { kind -> CautionAhead(kind, p.remainDistance.coerceAtLeast(0), inside = p.remainDistance <= 0) }
            }.minByOrNull { it.distanceM }
            _route.update { it.copy(caution = nearest) }
            if (nearest != null) cautionExpiry = expire(cautionExpiry, ALERT_STALE_MS) { it.copy(caution = null) }
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            _navigationStatus.value = status.name
            // 경로 안내가 아니면 다음 안내 지점은 없다 (카메라 알림은 자유 주행에도 온다)
            if (status != NavigationStatus.ROUTE_DRIVE) _route.update { it.copy(maneuver = null) }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val info = currentLocationInfo
            if (info.latitude == 0.0 && info.longitude == 0.0) return
            _currentLocation.value = GeoPoint(info.latitude, info.longitude, info.heading.toFloat())
        }

        override fun onTripInfo(info: TripInfo) {
            _route.update { it.copy(naviEstimatedSoc = info.estimatedSocAtDest) }
        }

        override fun onWayPointInfo(list: List<WaypointInfo>) {
            _route.update { r ->
                r.copy(waypoints = list.map { Place("wp_${it.name}", it.name, PlaceKind.REST_AREA, it.latitude, it.longitude) })
            }
        }

        override fun onRouteCancelled() { _route.update { RouteState(speedLimitKph = it.speedLimitKph) } }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            val dest = _route.value.destination
            _route.update { RouteState(speedLimitKph = it.speedLimitKph) }
            _arrivedEvents.tryEmit(Unit)
            val name = info.name.orEmpty()
            if (name.isBlank()) return
            val place = dest?.takeIf { it.name == name }
                ?: Place("arrived_$name", name, PlaceKind.DESTINATION, dest?.lat ?: 0.0, dest?.lng ?: 0.0, info.address.orEmpty())
            _arrivals.tryEmit(place)
        }

        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) {
            if (!personalPlaceSync) return
            _recent.value = list.filter { it.name.orEmpty().isNotBlank() }.map {
                Place(it.poiId.orEmpty().ifBlank { "recent_${it.name}" }, it.name.orEmpty(), PlaceKind.DESTINATION, it.latitude, it.longitude, it.address.orEmpty())
            }
        }

        override fun onBookmarkInfo(list: List<BookmarkInfo>) {
            if (!personalPlaceSync) return
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

        override fun onBookMarkChanged() { if (personalPlaceSync) runCatching { helper?.getBookmarkInfo() } }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) { _route.update { it.copy(speedLimitKph = speedLimit.takeIf { v -> v > 0 }) } }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "NaviHelper error: $errorCode") }
    }

    /** 알림이 끊기면 [staleMs] 뒤 값을 지운다 (지나간 카메라가 계속 남지 않게) */
    private fun expire(previous: Job?, staleMs: Long, clear: (RouteState) -> RouteState): Job {
        previous?.cancel()
        return scope.launch {
            delay(staleMs)
            _route.update(clear)
        }
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
                            // 경로 안내 중에는 다음 안내 지점을 1초마다 (부하 판단), 아니면 5초마다
                            if (_route.value.active || tick % 5 == 0) runCatching { h.getTBTInfo() }
                            if (tick % 2 == 0) runCatching { h.getDestinationInfo() }
                            if (tick % 2 == 1) runCatching { h.getCurrentLocationInfo() }
                            if (tick % 5 == 0) runCatching { h.getWaypointInfo() }
                            // 개인 장소 사전 원천은 예전 기능에서만, 1분마다
                            if (personalPlaceSync && tick % 60 == 0) runCatching { h.getRecentDestinationInfo(); h.getBookmarkInfo() }
                            tick++
                            delay(1_000)
                        }
                    }
                },
                { t -> Log.i(TAG, "NaviHelper 연결 실패 → 시뮬레이터 경로 사용: ${t.javaClass.simpleName}") },
            )
        }.onFailure { Log.i(TAG, "NaviHelper SDK 사용 불가: ${it.javaClass.simpleName}") }
    }

    fun stop() {
        pollJob?.cancel()
        cameraExpiry?.cancel()
        cautionExpiry?.cancel()
        maneuverExpiry?.cancel()
        runCatching { helper?.removeListener(listener); helper?.release() }
        helper = null
        _connected.value = false
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

    companion object {
        private const val TAG = "PleosNavi"
        /** 카메라·주의 구간 알림이 이만큼 안 오면 지운다 */
        const val ALERT_STALE_MS = 10_000L
        /** 안내 지점 갱신이 이만큼 없으면 지운다 */
        const val MANEUVER_STALE_MS = 15_000L
    }
}
