package com.yeopjari.app.platform.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import com.yeopjari.app.core.gateway.LocationGateway
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.model.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos FusedLocation SDK — GNSS + 차량 추측항법 융합 위치.
 * 위치 갱신이 끊기면(지하주차장 진입) 주차 위치 음성 메모 모먼트를 띄우는 근거로 쓴다.
 * 권한(pleos.car.permission.FUSED_LOCATION)은 dangerous 등급이므로 MainActivity 에서 런타임 요청 후 start() 한다.
 */
class PleosLocationGateway(private val context: Context) : LocationGateway {
    override val source = Source.PLEOS_SDK
    private val _location = MutableStateFlow<GeoPoint?>(null)
    override val location: StateFlow<GeoPoint?> = _location.asStateFlow()
    private val _lastFixMillis = MutableStateFlow(0L)
    val lastFixMillis: StateFlow<Long> = _lastFixMillis.asStateFlow()

    private var fused: FusedLocation? = null
    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            _location.value = GeoPoint(location.latitude, location.longitude, location.heading)
            _lastFixMillis.value = System.currentTimeMillis()
        }
        override fun onError(exception: Exception) { Log.d(TAG, "location error: ${exception.javaClass.simpleName}") }
    }

    fun start() {
        if (fused != null) return
        runCatching {
            val f = FusedLocation(context)
            if (!f.isSupported()) return
            f.initialize()
            f.registerFusedLocationCallback(listener)
            fused = f
        }.onFailure { Log.i(TAG, "FusedLocation 사용 불가", it) }
    }

    fun stop() {
        runCatching { fused?.unregisterFusedLocationCallback(listener); fused?.release() }
        fused = null
    }

    companion object { private const val TAG = "PleosLocation" }
}
