package com.yeopjari.app.english.coach

import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.ProgressEvent
import com.yeopjari.app.english.core.SessionPlan
import com.yeopjari.app.english.core.SessionRequest
import com.yeopjari.app.english.core.SlotMode
import com.yeopjari.app.english.core.Verdict

/** 코치가 말하고 보여 주는 한국어 문구 — 해요체, 운전 중에 듣기 좋게 짧게 */
internal object Say {
    const val RESUME_HELD = "아까 그 문제 다시 할게요."
    const val NEXT_INSTEAD = "다음 문제로 갈게요."
    const val CONTINUE = "이어서 할게요."
    const val AGAIN = "다시 할게요."
    const val SILENCE_RETRY = "다시 들려드릴게요."
    const val ONE_MORE = "한 번 더 말해 볼까요?"
    const val REVEAL = "이렇게 말해요."
    const val SKIPPED = "넘어갈게요."
    const val HINT = "힌트!"
    const val PAUSED = "잠깐 멈출게요. 재생 버튼을 누르면 이어서 할게요."
    /** 도착 1분 전 — 더 묻지 않고 주행 학습을 끝낸다 */
    const val ARRIVING_SOON = "곧 도착해요. 오늘 학습은 여기까지 할게요."
    const val EVENING_ASK = "퇴근길에도 영어 복습할까요?"
    const val EVENING_NO = "알겠어요. 오늘은 쉴게요."
    const val EVENING_HINT = "‘할게요’ 또는 ‘쉴게요’라고 답해 주세요"
    const val EXTEND_Q = "준비한 문장을 다 했어요. 복습 몇 개 더 할까요?"
    const val EXTEND_YES = "좋아요. 복습 몇 개 더 할게요."
    const val EXTEND_NO = "알겠어요. 오늘은 여기까지 할게요."
    const val AIRPORT_Q = "공항 가시는 길이네요. 오늘은 여행 영어로 바꿔 볼까요?"
    const val TRAVEL_YES = "좋아요. 여행 영어로 바꿀게요."
    const val KEEP_TRACK = "알겠어요. 하던 대로 할게요."
    const val REVIEW_FIRST = "복습할 표현부터 할게요."
    const val CONFIRM_HINT = "‘네’ 또는 ‘아니요’로 답해 주세요."

    // 토스트 · 결과 메시지
    const val PARKED_ONLY = "정차 후 이용할 수 있어요"
    const val NEED_PROFILE = "먼저 정차 중에 시작하기를 완료해 주세요."
    const val NOT_ACCEPTING = "지금은 답을 받지 않아요"
    const val SAVED = "표현을 저장했어요"
    const val NOTHING_TO_SAVE = "저장할 표현이 없어요"
    const val ERASED = "학습 기록을 모두 지웠어요"
    const val NOTHING_TO_REVIEW = "지금 복습할 표현이 없어요"
    const val SESSION_RUNNING = "학습 세션을 마친 뒤에 할 수 있어요"
    const val NO_ITEMS = "지금 낼 수 있는 문제가 없어요"
    const val CHOOSE_TRACK_FIRST = "먼저 학습 목표를 골라 주세요"
    const val HOLD_LINE = "잠시 운전에 집중할게요"
    const val ARRIVING_TOO_SOON = "곧 도착해요. 도착한 뒤에 시작해 주세요"
    const val NO_DASHBOARD = "아직 학습 결과가 없어요"

    /** 세션 시작 안내 (명세 §7 Session start) */
    fun briefing(plan: SessionPlan, request: SessionRequest): String {
        val n = plan.newCount
        val m = plan.reviewCount
        request.pronDrill?.let { return "${it.ko} 발음 연습 ${plan.items.size}개를 시작할게요." }
        if (request.itemIds.isNotEmpty()) return "틀린 표현 ${plan.items.size}개를 다시 해 볼게요."
        if (request.reviewOnly) return "복습 ${maxOf(m, plan.items.size)}개를 시작할게요."
        val min = plan.routeMinutes
        if (min == null) {
            return if (m == 0) "오늘의 세션, 새 표현 ${n}개로 시작할게요."
            else if (n == 0) "오늘의 세션, 복습 ${m}개로 시작할게요."
            else "오늘의 세션, 새 표현 ${n}개와 복습 ${m}개로 시작할게요."
        }
        val head = "${plan.slot.ko} ${min}분,"
        if (plan.mode == SlotMode.REVIEW) {
            return if (n == 0) "$head 오늘 틀린 표현부터 복습 ${m}개 할게요."
            else "$head 오늘 틀린 표현부터 복습 ${m}개, 새 표현 ${n}개 할게요."
        }
        return when {
            m == 0 -> "$head 새 표현 ${n}개로 시작할게요."
            n == 0 -> "$head 복습 ${m}개로 시작할게요."
            else -> "$head 새 표현 ${n}개와 복습 ${m}개로 시작할게요."
        }
    }

    fun meaning(meaningKo: String): String {
        val m = meaningKo.trim()
        return "뜻은 ‘$m’${Josa.ieyo(m)}."
    }

    fun arrival(answered: Int, correct: Int): String =
        if (answered == 0) "도착했어요. 오늘도 수고했어요!"
        else "도착했어요. 오늘 ${answered}문제 중 ${correct}개 맞혔어요. 수고했어요!"

    fun ended(answered: Int, correct: Int): String =
        if (answered == 0) "오늘은 여기까지 할게요."
        else "오늘은 여기까지 할게요. ${answered}문제 중 ${correct}개 맞혔어요. 수고했어요!"

    /** 진행 이벤트 칭찬 한마디 (부하 ≤ NORMAL 일 때만 말한다) */
    fun praise(e: ProgressEvent): String? = when (e) {
        is ProgressEvent.LevelUp -> "레벨 업! 이제 ${e.to.ko}${Josa.ieyo(e.to.ko)}."
        is ProgressEvent.CheckpointPassed -> "유닛 통과! 다음 유닛이 열렸어요."
        is ProgressEvent.DailyGoalReached -> "오늘 목표 ${e.goal}문장을 채웠어요!"
        is ProgressEvent.StreakExtended -> "${e.days}일 연속 학습이에요!"
        is ProgressEvent.UnitUnlocked -> null
    }

    fun headline(v: Verdict): String = when (v) {
        Verdict.CORRECT -> "정답이에요!"
        Verdict.PARTIAL -> "거의 맞았어요"
        Verdict.WRONG, Verdict.NO_ANSWER -> "이렇게 말해요"
    }

    fun summaryTitle(slot: CommuteSlot): String =
        if (slot == CommuteSlot.OTHER) "오늘의 세션 완료" else "${slot.ko} 세션 완료"

    // ───────────── 주차 대시보드 ─────────────

    /** 대시보드의 큰 인사말 */
    fun dashboardHeadline(slot: CommuteSlot): String = when (slot) {
        CommuteSlot.EVENING -> "오늘 하루도 수고했어요!"
        else -> "오늘도 수고했어요!"
    }

    /** "출근길 12분 동안 8문장을 연습했어요" */
    fun dashboardSubtitle(slot: CommuteSlot, minutes: Int, answered: Int): String {
        val head = if (slot == CommuteSlot.OTHER) "" else "${slot.ko} "
        val time = if (minutes >= 1) "${minutes}분 동안 " else ""
        return "$head$time${answered}문장을 연습했어요"
    }

    /** 대시보드가 뜬 뒤 (징글이 끝날 무렵) 건네는 한마디 */
    fun dashboardLine(arrived: Boolean, answered: Int, correct: Int): String =
        if (arrived) arrival(answered, correct) else "오늘 ${answered}문제 중 ${correct}개 맞혔어요. 수고했어요!"

    /** 발음 약점 예시 — 영어 낱말 뒤에 조사를 붙이지 않는 꼴로 (‘right’ 대신 ‘light’처럼 들렸어요) */
    fun heardAs(expected: String, heard: String): String = "‘$expected’ 대신 ‘$heard’처럼 들렸어요"

    /** 레벨 진행 막대 옆 설명 — "중급까지" (최고 레벨이면 "고급 과정") */
    fun levelGoal(level: Level?): String {
        val next = level?.next
        return when {
            level == null -> ""
            next != null -> "${next.ko}까지"
            else -> "${level.ko} 과정"
        }
    }
}

/** 조사 고르기 (받침 유무) */
internal object Josa {
    fun hasBatchim(text: String): Boolean {
        val c = text.trim().lastOrNull { it.isLetterOrDigit() } ?: return false
        if (c in '가'..'힣') return (c.code - 0xAC00) % 28 != 0
        if (c.isDigit()) return c in "013678"
        return c.lowercaseChar() in "lmn"
    }

    /** 이에요 / 예요 */
    fun ieyo(text: String): String = if (hasBatchim(text)) "이에요" else "예요"
}
