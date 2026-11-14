package com.yeopjari.app.platform.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yeopjari.app.YeopjariApp
import com.yeopjari.app.core.model.DriveMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo Interface (App Schema) 연동.
 *
 *  사용자 ─"글레오야, 옆자리로 강릉 가는 충전 계획 세워줘"─▶ Gleo AI (차량 온디바이스/서버 LLM)
 *      ─ Function Calling: PLAN_EV_TRIP(destination="강릉") ─▶ Intent(action, ACTION_PARAM) ─▶ 옆자리
 *      ◀─ Callback 브로드캐스트(APP_ACTION_RESPONSE: success/asking/fail) ── 옆자리 (7초 이내)
 *
 * 즉, '기본 음성 인식·명령'은 차량 AI(Gleo)가 처리하고, 복잡한 추론이 필요한 순간에만
 * 옆자리의 도메인 에이전트가 호출되는 구조.
 */
object GleoBridge {
    const val PLAN_EV_TRIP = "PLAN_EV_TRIP"
    const val START_CARE_MODE = "START_CARE_MODE"
    const val DIAGNOSE_VEHICLE = "DIAGNOSE_VEHICLE"
    val ACTIONS = setOf(PLAN_EV_TRIP, START_CARE_MODE, DIAGNOSE_VEHICLE)

    private const val EXTRA_PARAM = "ACTION_PARAM"
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    data class Action(val name: String, val params: JSONObject)

    /** Intent → Gleo Action. 문서 예시의 이중 따옴표 감싸기·개별 extra 방식 모두 허용 */
    fun parse(intent: Intent?): Action? {
        val name = intent?.action ?: return null
        if (name !in ACTIONS) return null
        val raw = intent.getStringExtra(EXTRA_PARAM)?.trim()
        val json = raw?.let {
            val unwrapped = if (it.length >= 2 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1).replace("\\\"", "\"") else it
            runCatching { JSONObject(unwrapped) }.getOrNull()
        } ?: JSONObject().apply {
            intent.extras?.keySet()?.forEach { k -> if (k != EXTRA_PARAM) put(k, intent.extras?.get(k)?.toString()) }
        }
        return Action(name, json)
    }

    /** Gleo 로 결과 콜백 — status: success | asking | fail, mode: voice | beep | silent */
    fun respond(context: Context, functionName: String, status: String, message: String, data: JSONArray? = null, mode: String = "voice") {
        runCatching {
            val body = JSONObject().apply {
                put("functionName", functionName)
                put("status", status)
                put("message", message)
                put("mode", mode)
                data?.let { put("data", it.toString()) }
            }
            context.sendBroadcast(Intent(RESPONSE_ACTION).apply {
                putExtra("packageName", context.packageName)
                putExtra("response", body.toString())
            })
        }.onFailure { Log.e("GleoBridge", "respond failed", it) }
    }

    /** 사람이 읽을 수 있는 요약 (대화창 표시용) */
    fun describe(a: Action): String {
        val args = a.params.keys().asSequence().joinToString(", ") { "$it=${a.params.opt(it)}" }
        return "${a.name}($args)"
    }
}

/** component = "service" 액션 수신기 — UI 없이 즉시 처리 후 콜백 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = GleoBridge.parse(intent) ?: return
        val store = (context.applicationContext as YeopjariApp).container.store
        store.logGleo(action)
        when (action.name) {
            GleoBridge.START_CARE_MODE -> {
                val mode = action.params.optString("mode", "pet")
                val temp = action.params.optDouble("temperature", 22.0).toFloat().coerceIn(16f, 30f)
                if (store.context.value.vehicle.driveMode == DriveMode.DRIVING) {
                    GleoBridge.respond(context, action.name, "fail", "Care mode can only start after the vehicle is parked.")
                    return
                }
                if (mode == "rest") store.startRestMode(15) else store.startPetMode(temp)
                GleoBridge.respond(
                    context, action.name, "success",
                    if (mode == "rest") "Rest mode started for 15 minutes." else "Pet care mode started. The cabin will stay at ${temp.toInt()} degrees Celsius.",
                    JSONArray().put(JSONObject().put("mode", mode).put("temperature", temp)),
                )
            }
            else -> GleoBridge.respond(context, action.name, "fail", "This action is handled on screen.")
        }
    }
}
