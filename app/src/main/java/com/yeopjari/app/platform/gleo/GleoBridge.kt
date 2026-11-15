package com.yeopjari.app.platform.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yeopjari.app.YeopjariApp
import com.yeopjari.app.english.core.ActionResult
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.SessionOrigin
import com.yeopjari.app.english.core.SessionRequest
import com.yeopjari.app.english.core.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Gleo Interface (App Schema) 연동 — assets/schema.json 의 함수 3개.
 *
 *  "글레오야, 옆자리로 영어 공부 시작해 줘" ─▶ Gleo(차량 음성 비서) ─ START_ENGLISH_SESSION ─▶ 옆자리 영어
 *      ◀─ 콜백 브로드캐스트(APP_ACTION_RESPONSE: success / fail, 7초 이내)
 *
 *  - START_ENGLISH_SESSION(track?) · REVIEW_NOW() : component = activity (startActivity)
 *  - SAVE_EXPRESSION(text?)                         : component = service (sendBroadcast → [GleoActionReceiver])
 *
 * 앱이 직접 한국어로 안내하므로 Gleo 응답은 silent 모드로 보낸다. 사용자의 말이나 표현은 로그에 남기지 않는다.
 */
object GleoBridge {
    const val START_ENGLISH_SESSION = "START_ENGLISH_SESSION"
    const val REVIEW_NOW = "REVIEW_NOW"
    const val SAVE_EXPRESSION = "SAVE_EXPRESSION"
    val ACTIONS = setOf(START_ENGLISH_SESSION, REVIEW_NOW, SAVE_EXPRESSION)

    const val SUCCESS = "success"
    const val FAIL = "fail"

    private const val TAG = "GleoBridge"
    private const val EXTRA_PARAM = "ACTION_PARAM"
    /** 내보낸 리시버라 어떤 앱이든 보낼 수 있다 → 저장할 표현 길이를 제한한다 */
    private const val MAX_EXPRESSION_CHARS = 200
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    data class Action(val name: String, val params: JSONObject) {
        fun string(key: String): String? = params.optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Intent → Gleo 액션. ACTION_PARAM JSON(따옴표로 한 번 더 감싼 형태 포함)과 개별 extra 를 모두 받는다 */
    fun parse(intent: Intent?): Action? {
        val name = intent?.action ?: return null
        if (name !in ACTIONS) return null
        val raw = intent.getStringExtra(EXTRA_PARAM)?.trim()
        val json = raw?.let {
            val unwrapped = if (it.length >= 2 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1).replace("\\\"", "\"") else it
            runCatching { JSONObject(unwrapped) }.getOrNull()
        } ?: JSONObject().apply {
            intent.extras?.keySet()?.forEach { k ->
                if (k != EXTRA_PARAM) intent.extras?.getString(k)?.let { v -> put(k, v) }
            }
        }
        return Action(name, json)
    }

    /** 액션 실행 → 코치 결과. 메인 스레드에서 부른다 */
    fun execute(action: Action, coach: EnglishCoach): ActionResult = when (action.name) {
        START_ENGLISH_SESSION -> coach.startSession(SessionRequest(track = Track.parse(action.string("track")), origin = SessionOrigin.GLEO))
        REVIEW_NOW -> coach.reviewNow()
        SAVE_EXPRESSION -> coach.saveExpression(action.string("text")?.filterNot { it.isISOControl() }?.take(MAX_EXPRESSION_CHARS)?.trim()?.takeIf { it.isNotEmpty() })
        else -> ActionResult(false, "지원하지 않는 요청이에요.", "This request is not supported.")
    }

    /**
     * 앱이 막 켜진 경우(Gleo 가 앱을 처음 띄움) 학습 기록을 다 읽을 때까지 잠깐 기다렸다가 실행하고 콜백한다.
     * Gleo 콜백 제한(7초) 안에 끝나도록 최대 [READY_WAIT_MS] 만 기다린다.
     */
    fun dispatch(context: Context, action: Action, done: () -> Unit = {}) {
        val app = context.applicationContext
        val container = (app as YeopjariApp).container
        container.scope.launch {
            try {
                val ready = withTimeoutOrNull(READY_WAIT_MS) { container.coach.state.first { it.ready } } != null
                val result = if (ready) execute(action, container.coach)
                else ActionResult(false, "앱을 준비하고 있어요. 잠시 후 다시 말씀해 주세요.", "The app is still starting. Please try again in a moment.")
                respond(app, action.name, result)
            } finally {
                done()
            }
        }
    }

    private const val READY_WAIT_MS = 5_000L

    /** Gleo 로 결과 콜백 — status: success | fail, mode: voice | beep | silent */
    fun respond(context: Context, functionName: String, result: ActionResult, mode: String = "silent") {
        runCatching {
            val body = JSONObject().apply {
                put("functionName", functionName)
                put("status", if (result.ok) SUCCESS else FAIL)
                put("message", result.messageEn)
                put("mode", mode)
            }
            context.sendBroadcast(
                Intent(RESPONSE_ACTION).apply {
                    putExtra("packageName", context.packageName)
                    putExtra("response", body.toString())
                },
            )
        }.onFailure { Log.w(TAG, "respond failed: ${it.javaClass.simpleName}") }
    }
}

/** component = "service" 액션 수신기 — 화면을 띄우지 않고 표현을 저장한 뒤 콜백 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = GleoBridge.parse(intent) ?: return
        if (action.name != GleoBridge.SAVE_EXPRESSION) {
            GleoBridge.respond(context.applicationContext, action.name, ActionResult(false, "화면에서 처리하는 요청이에요.", "This action opens the app screen."))
            return
        }
        val pending = goAsync()
        GleoBridge.dispatch(context, action) { pending.finish() }
    }
}
