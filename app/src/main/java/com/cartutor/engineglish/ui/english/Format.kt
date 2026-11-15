package com.cartutor.engineglish.ui.english

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.HearingDisabled
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.cartutor.engineglish.english.core.CommuteSlot
import com.cartutor.engineglish.english.core.DailyGoal
import com.cartutor.engineglish.english.core.Kst
import com.cartutor.engineglish.english.core.LoadLevel
import com.cartutor.engineglish.english.core.PlanKind
import com.cartutor.engineglish.english.core.ProgressEvent
import com.cartutor.engineglish.english.core.PulseKind
import com.cartutor.engineglish.english.core.RoadKind
import com.cartutor.engineglish.english.core.Skill
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.english.core.Verdict
import com.cartutor.engineglish.ui.theme.Y
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/*
 * 엔진글리시 UI — 색 · 아이콘 · 한국어 문구 도우미 (화면들이 같은 말과 색을 쓰도록).
 */

// ───────────────────────── 운전 부하 ─────────────────────────

internal fun loadColor(level: LoadLevel): Color = when (level) {
    LoadLevel.PARKED -> Y.LoadParked
    LoadLevel.RELAXED -> Y.LoadRelaxed
    LoadLevel.NORMAL -> Y.LoadNormal
    LoadLevel.FOCUS -> Y.LoadFocus
    LoadLevel.CRITICAL -> Y.LoadCritical
}

internal fun loadIcon(level: LoadLevel): ImageVector = when (level) {
    LoadLevel.PARKED -> Icons.Rounded.LocalParking
    LoadLevel.RELAXED -> Icons.Rounded.SelfImprovement
    LoadLevel.NORMAL -> Icons.Rounded.DirectionsCar
    LoadLevel.FOCUS -> Icons.Rounded.Visibility
    LoadLevel.CRITICAL -> Icons.Rounded.Warning
}

/** 단계별로 앱이 하는 일 (설정 · 안내 화면) */
internal fun loadMeaningKo(level: LoadLevel): String = when (level) {
    LoadLevel.PARKED -> "화면 선택지 · 레벨 테스트 · 긴 답변까지 모두"
    LoadLevel.RELAXED -> "토익 스피킹처럼 긴 답도 말로 연습"
    LoadLevel.NORMAL -> "짧은 표현 · 따라 말하기 · 단어 떠올리기"
    LoadLevel.FOCUS -> "새 질문 없이 조용히 기다려요"
    LoadLevel.CRITICAL -> "말하기와 듣기를 바로 멈춰요"
}

/** 단계에 들어가는 대표 상황 */
internal fun loadExampleKo(level: LoadLevel): String = when (level) {
    LoadLevel.PARKED -> "P단 · 충전 중"
    LoadLevel.RELAXED -> "크루즈 · 고속 직진 구간"
    LoadLevel.NORMAL -> "시내 주행 · 정체 · 신호 대기"
    LoadLevel.FOCUS -> "방향지시등 · 교차로 300m · 보호구역"
    LoadLevel.CRITICAL -> "급제동 · 과속 · 내비 음성 안내"
}

// ───────────────────────── 채점 ─────────────────────────

internal fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.CORRECT -> Y.Correct
    Verdict.PARTIAL -> Y.Retry
    Verdict.WRONG -> Y.Miss
    Verdict.NO_ANSWER -> Y.Text2
}

internal fun verdictIcon(v: Verdict): ImageVector = when (v) {
    Verdict.CORRECT -> Icons.Rounded.CheckCircle
    Verdict.PARTIAL, Verdict.WRONG -> Icons.Rounded.Replay
    Verdict.NO_ANSWER -> Icons.Rounded.HearingDisabled
}

internal fun pulseColor(kind: PulseKind): Color = when (kind) {
    PulseKind.GREEN -> Y.Correct
    PulseKind.AMBER -> Y.Retry
}

// ───────────────────────── 커리큘럼 ─────────────────────────

internal fun trackIcon(t: Track): ImageVector = when (t) {
    Track.BUSINESS -> Icons.Rounded.BusinessCenter
    Track.DAILY -> Icons.Rounded.Forum
    Track.TRAVEL -> Icons.Rounded.Flight
    Track.TOEIC_SPEAKING -> Icons.Rounded.RecordVoiceOver
}

internal fun trackBlurbKo(t: Track): String = when (t) {
    Track.BUSINESS -> "회의 · 메일 · 전화에서 바로 쓰는 표현"
    Track.DAILY -> "약속, 날씨, 길 위의 대화처럼 매일 쓰는 말"
    Track.TRAVEL -> "공항 · 호텔 · 식당에서 막히지 않는 영어"
    Track.TOEIC_SPEAKING -> "질문을 듣고 30~45초 동안 답하는 연습"
}

internal fun goalBlurbKo(g: DailyGoal): String = when (g) {
    DailyGoal.LIGHT -> "출근길 한 번이면 채워요"
    DailyGoal.STEADY -> "출퇴근길을 모두 쓰면 딱 맞아요"
    DailyGoal.INTENSIVE -> "긴 출퇴근길을 꽉 채워요"
}

/** 하루 목표 짧은 이름 ("가볍게") */
internal fun goalShortKo(g: DailyGoal): String = g.ko.substringBefore(" ·").trim()

internal fun skillIcon(s: Skill): ImageVector = when (s) {
    Skill.VOCAB -> Icons.Rounded.Abc
    Skill.EXPRESSION -> Icons.Rounded.ChatBubble
    Skill.SENTENCE_BUILD -> Icons.Rounded.Translate
    Skill.LISTEN_REPEAT -> Icons.Rounded.Hearing
    Skill.TOEIC_QA -> Icons.Rounded.RecordVoiceOver
    Skill.MINIMAL_PAIR -> Icons.Rounded.GraphicEq
}

internal fun kindColor(kind: PlanKind): Color = when (kind) {
    PlanKind.NEW -> Y.Accent
    PlanKind.REVIEW -> Y.LoadParked
    PlanKind.MISTAKE -> Y.Retry
    PlanKind.HOOK -> Y.LoadRelaxed
    PlanKind.DRILL -> Y.Periwinkle
}

internal fun slotIcon(slot: CommuteSlot?): ImageVector = when (slot) {
    CommuteSlot.MORNING -> Icons.Rounded.WbSunny
    CommuteSlot.EVENING -> Icons.Rounded.NightsStay
    CommuteSlot.OTHER, null -> Icons.Rounded.DirectionsCar
}

internal fun roadColor(r: RoadKind): Color = when (r) {
    RoadKind.LOCAL, RoadKind.CITY -> Color(0xFF7E8BA3)
    RoadKind.RIVERSIDE -> Color(0xFF4DA3FF)
    RoadKind.TUNNEL -> Color(0xFF9C8CFF)
    RoadKind.HIGHWAY -> Y.LoadRelaxed
    RoadKind.EXPRESSWAY_EXIT -> Y.LoadFocus
    RoadKind.SCHOOL_ZONE -> Color(0xFFFFD45E)
    RoadKind.JAM -> Y.LoadCritical
}

/** 진행 이벤트 한 줄 */
internal fun eventKo(e: ProgressEvent): String = when (e) {
    is ProgressEvent.CheckpointPassed -> "유닛 ${e.unit.unit} 통과 · 정확도 ${percentKo(e.accuracy)}"
    is ProgressEvent.UnitUnlocked -> "새 유닛 열림 · 유닛 ${e.unit.unit}"
    is ProgressEvent.LevelUp -> "레벨 업! 이제 ${e.to.ko}이에요"
    is ProgressEvent.StreakExtended -> "${e.days}일 연속 학습"
    is ProgressEvent.DailyGoalReached -> "오늘 목표 ${e.goal}문장 달성"
}

// ───────────────────────── 숫자 · 시간 ─────────────────────────

internal fun minutesKo(min: Int): String = when {
    min < 60 -> "${min}분"
    min % 60 == 0 -> "${min / 60}시간"
    else -> "${min / 60}시간 ${min % 60}분"
}

internal fun secondsKo(ms: Long): String {
    val s = ms / 1000f
    return if (s >= 10f) "${s.roundToInt()}초" else "%.1f초".format(s)
}

internal fun percentKo(f: Float): String = "${(f * 100f).roundToInt()}%"

internal fun bytesKo(b: Long): String = when {
    b < 1024 -> "${b}B"
    b < 1024 * 1024 -> "%.1fKB".format(b / 1024f)
    else -> "%.1fMB".format(b / 1_048_576f)
}

internal fun dayOfWeekKo(epochDay: Long): String = when (LocalDate.ofEpochDay(epochDay).dayOfWeek) {
    DayOfWeek.MONDAY -> "월"
    DayOfWeek.TUESDAY -> "화"
    DayOfWeek.WEDNESDAY -> "수"
    DayOfWeek.THURSDAY -> "목"
    DayOfWeek.FRIDAY -> "금"
    DayOfWeek.SATURDAY -> "토"
    DayOfWeek.SUNDAY -> "일"
    null -> ""
}

/** "11월 16일 오전 7:52" */
internal fun dateTimeKo(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(Kst.zone)
    return "${d.monthValue}월 ${d.dayOfMonth}일 ${Kst.clockLabel(ms)}"
}

/** "출근길 32분 → 새 표현 6개 + 복습 10개" → ("출근길 32분", ["새 표현 6개", "복습 10개"]) */
internal fun splitPlanTitle(title: String): Pair<String, List<String>> {
    val i = title.indexOf('→')
    if (i < 0) return title.trim() to emptyList()
    val head = title.substring(0, i).trim()
    val parts = title.substring(i + 1).split('+').map { it.trim() }.filter { it.isNotEmpty() }
    return head to parts
}

/** 짧은 영어 문장 비교용 (보기 강조) */
internal fun looseEquals(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    fun n(s: String) = s.lowercase().replace('’', '\'').filter { it.isLetterOrDigit() || it == ' ' || it == '\'' }.trim()
    return n(a) == n(b)
}
