package com.yeopjari.app.platform.hub

import com.yeopjari.app.core.gateway.NaviGateway
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.gateway.VehicleGateway
import com.yeopjari.app.core.model.Place
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.core.model.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 데이터 소스 허브 — Pleos SDK(실차/에뮬레이터) 와 시나리오 시뮬레이터를 전환한다.
 *  - 읽기: 현재 선택된 소스의 스냅샷
 *  - 쓰기: 선택된 소스에 실행 + Pleos SDK 가 연결돼 있으면 실제 차량에도 미러링
 *          (시나리오 시연 중 AI가 바꾼 공조·경유지가 에뮬레이터 기본 내비/공조에도 그대로 반영됨)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleHub(
    scope: CoroutineScope,
    private val real: VehicleGateway,
    private val sim: VehicleGateway,
    private val mode: StateFlow<Source>,
    private val realConnected: StateFlow<Boolean>,
) : VehicleGateway {
    override val source: Source get() = mode.value
    override val snapshot: StateFlow<VehicleSnapshot> =
        mode.flatMapLatest { if (it == Source.PLEOS_SDK) real.snapshot else sim.snapshot }
            .stateIn(scope, SharingStarted.Eagerly, sim.snapshot.value)

    private suspend fun both(op: suspend (VehicleGateway) -> Result<Unit>): Result<Unit> {
        val primary = if (mode.value == Source.PLEOS_SDK) real else sim
        val r = op(primary)
        if (primary === sim && realConnected.value) op(real)
        return r
    }

    override suspend fun setHvacPower(on: Boolean) = both { it.setHvacPower(on) }
    override suspend fun setHvacTemperature(celsius: Float) = both { it.setHvacTemperature(celsius) }
    override suspend fun setFanSpeed(level: Int) = both { it.setFanSpeed(level) }
    override suspend fun setRecirculation(on: Boolean) = both { it.setRecirculation(on) }
    override suspend fun setChargeLimit(percent: Int) = both { it.setChargeLimit(percent) }
    override fun start() { sim.start(); real.start() }
    override fun stop() { sim.stop(); real.stop() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NaviHub(
    scope: CoroutineScope,
    private val real: NaviGateway,
    private val sim: NaviGateway,
    private val mode: StateFlow<Source>,
    private val realConnected: StateFlow<Boolean>,
) : NaviGateway {
    override val source: Source get() = mode.value
    override val route: StateFlow<RouteState> =
        mode.flatMapLatest { if (it == Source.PLEOS_SDK) real.route else sim.route }
            .stateIn(scope, SharingStarted.Eagerly, sim.route.value)

    private suspend fun both(op: suspend (NaviGateway) -> Result<Unit>): Result<Unit> {
        val primary = if (mode.value == Source.PLEOS_SDK) real else sim
        val r = op(primary)
        if (primary === sim && realConnected.value) op(real)
        return r
    }

    override suspend fun setDestination(place: Place) = both { it.setDestination(place) }
    override suspend fun addWaypoint(place: Place) = both { it.addWaypoint(place) }
    override suspend fun cancelGuidance() = both { it.cancelGuidance() }
}
