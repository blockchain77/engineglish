package com.cartutor.engineglish.ui.english

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cartutor.engineglish.english.core.ActionResult
import com.cartutor.engineglish.english.core.AmbientPulse
import com.cartutor.engineglish.english.core.CoachPhase
import com.cartutor.engineglish.english.core.DemoControl
import com.cartutor.engineglish.english.core.DriveStatus
import com.cartutor.engineglish.english.core.EnglishCoach
import com.cartutor.engineglish.english.core.EnglishNavigator
import com.cartutor.engineglish.english.core.MicTester
import com.cartutor.engineglish.english.core.EnglishRoute
import com.cartutor.engineglish.english.core.EnglishUiState
import com.cartutor.engineglish.english.core.GearPosition
import com.cartutor.engineglish.english.core.Kst
import com.cartutor.engineglish.english.core.ScreenMode
import com.cartutor.engineglish.english.core.SignalOrigin
import com.cartutor.engineglish.english.core.ToastUi
import com.cartutor.engineglish.ui.theme.Y
import kotlinx.coroutines.delay

/*
 * 엔진글리시 — UI 진입점.
 * 화면은 코치의 EnglishUiState 하나만 보고 그리며, 동작은 EnglishCoach 함수로만 한다.
 * 실제 화면 = 상태 + 사용자가 고른 경로 (명세 §8.1). 주행 중에는 '주행 학습'만 조작할 수 있다.
 * 그 위에 뜨는 것: 주차 대시보드(주행 학습 뒤 P 단 · 전체 화면) · 퇴근길 학습 질문 카드 · 세션 요약 · 시연 패널.
 */

/** 실제로 보이는 화면 */
internal enum class Screen { SPLASH, ONBOARDING, PLACEMENT, DRIVE, LESSON, HOME, REVIEW, REPORT, SETTINGS }

private data class RailItem(val route: EnglishRoute, val icon: ImageVector, val label: String)

private val RAIL = listOf(
    RailItem(EnglishRoute.HOME, Icons.Rounded.Home, "홈"),
    RailItem(EnglishRoute.DRIVE, Icons.Rounded.DirectionsCar, "주행 학습"),
    RailItem(EnglishRoute.LESSON, Icons.Rounded.School, "학습"),
    RailItem(EnglishRoute.REVIEW, Icons.Rounded.History, "복습"),
    RailItem(EnglishRoute.REPORT, Icons.Rounded.Insights, "리포트"),
    RailItem(EnglishRoute.SETTINGS, Icons.Rounded.Settings, "설정"),
)

/** 화면 이동 · 안내를 화면들에 넘기는 묶음 */
internal class AppActions(
    val go: (EnglishRoute) -> Unit,
    val notice: (String) -> Unit,
) {
    /** 코치 결과가 실패면 이유를 짧게 띄운다 */
    fun result(r: ActionResult) {
        if (!r.ok && r.messageKo.isNotBlank()) notice(r.messageKo)
    }
}

private data class Notice(val id: Long, val text: String)

/**
 * 엔진글리시 앱 화면 전체.
 * @param demo 시연 제어 (디버그 빌드 또는 ADB 켜짐일 때만 통합이 넘긴다). null 이면 시연 패널 없음
 */
@Composable
fun EnglishApp(
    coach: EnglishCoach,
    navigator: EnglishNavigator,
    demo: DemoControl?,
    modifier: Modifier = Modifier,
    micTest: MicTester? = null,
) {
    val state by coach.state.collectAsStateWithLifecycle()
    val route by navigator.route.collectAsStateWithLifecycle()

    var notice by remember { mutableStateOf<Notice?>(null) }
    var noticeSeq by remember { mutableLongStateOf(0L) }
    /** 정차 중 세션에서 사용자가 다른 화면을 고른 세션 id — 그 세션 동안은 학습 화면으로 끌고 가지 않는다 */
    var leftLessonFor by remember { mutableStateOf<String?>(null) }
    var demoOpen by remember { mutableStateOf(false) }
    /** 축하 애니메이션을 이미 본 세션 id — 같은 결과를 다시 열 때는 차분하게 보여 준다 */
    var celebratedSession by remember { mutableStateOf<String?>(null) }

    val driving = state.screenMode == ScreenMode.DRIVE
    val parkedSession = !driving && state.phase.inSession && state.session != null
    val screen = when {
        !state.ready -> Screen.SPLASH
        state.profile == null || state.phase == CoachPhase.ONBOARDING -> Screen.ONBOARDING
        state.phase == CoachPhase.PLACEMENT -> Screen.PLACEMENT
        driving -> Screen.DRIVE
        parkedSession && leftLessonFor != state.session?.planId -> Screen.LESSON
        else -> screenOf(route)
    }

    val showNotice: (String) -> Unit = { text ->
        noticeSeq += 1
        notice = Notice(noticeSeq, text)
    }
    val go: (EnglishRoute) -> Unit = { r ->
        when {
            driving && !r.drivingAllowed -> showNotice(LOCKED_KO)
            else -> {
                if (parkedSession && r != EnglishRoute.LESSON) leftLessonFor = state.session?.planId
                if (r == EnglishRoute.LESSON) leftLessonFor = null
                navigator.go(r)
            }
        }
    }
    // 위 람다들은 매번 새로 만들어지므로, 화면에는 늘 최신 것을 부르는 안정된 껍데기를 넘긴다
    val goLatest = rememberLatest(go)
    val noticeLatest = rememberLatest(showNotice)
    val stableActions = remember { AppActions({ goLatest.value(it) }, { noticeLatest.value(it) }) }

    // 시연 제어: 통합이 넘겼고(디버그·ADB) 코치도 허용할 때만. 실차(Pleos) 주행 중에는 숨긴다
    val demoCtl: DemoControl? = demo?.takeIf { state.demoEnabled && !(driving && state.drive.origin == SignalOrigin.PLEOS) }

    Box(modifier.fillMaxSize().background(Y.Bg0)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showNav = screen != Screen.SPLASH && screen != Screen.ONBOARDING && screen != Screen.PLACEMENT
            val railCompact = maxWidth < 1100.dp || driving
            Row(Modifier.fillMaxSize()) {
                if (showNav) {
                    NavRail(
                        selected = railRouteOf(screen),
                        driving = driving,
                        compact = railCompact,
                        reviewBadge = if (driving) 0 else state.progress.dueReviews,
                        showDemo = demoCtl != null,
                        onDemo = { demoOpen = true },
                        onSelect = { stableActions.go(it) },
                    )
                }
                // 레일이 없는 화면(온보딩·레벨 테스트)에서는 오른쪽 위 시연 버튼 자리를 비워 둔다
                val fabRoom = if (demoCtl != null && !showNav && screen != Screen.SPLASH) TouchMin + 36.dp else 0.dp
                Column(Modifier.weight(1f).fillMaxHeight().padding(end = fabRoom)) {
                    if (showNav && screen != Screen.DRIVE) StatusBar(state)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Crossfade(targetState = screen, animationSpec = tween(220), label = "screen") { s ->
                            ScreenContent(s, state, coach, stableActions, micTest)
                        }
                    }
                }
            }
        }

        // 정답/다시 — 화면 가장자리 1초 빛 (실차는 앰비언트 라이트도 함께)
        PulseGlow(state.pulse)

        // 주차 대시보드 — 주행 학습을 마치고 P 단에 넣으면 전체 화면으로 (홈의 '지난 학습 결과'로도 다시 연다)
        val dash = state.dashboard
        val showDashboard = state.showParkedDashboard && dash != null && !driving

        // 세션 요약 (정차 화면 위에) — 대시보드가 뜰 때는 대시보드가 대신한다
        val summary = state.summary
        if (state.phase == CoachPhase.SUMMARY && summary != null && !driving && screen != Screen.DRIVE && !showDashboard) {
            SummarySheet(
                summary = summary,
                onClose = { coach.dismissSummary() },
                onReport = {
                    coach.dismissSummary()
                    stableActions.go(EnglishRoute.REPORT)
                },
            )
        }

        if (dash != null && showDashboard) {
            // 방금 끝난 세션이면 축하(캐릭터가 뛰고 색종이), 이미 본 결과를 다시 열면 차분하게
            val celebrate = dash.sessionId != celebratedSession
            DisposableEffect(dash.sessionId) {
                onDispose { celebratedSession = dash.sessionId }
            }
            ParkedDashboardScreen(
                ui = remember(dash, state.profile) { dashboardUiOf(dash, state) },
                onReviewMistakes = {
                    val r = coach.reviewMistakes()
                    stableActions.result(r)
                    if (r.ok) navigator.go(EnglishRoute.LESSON)
                },
                onHome = {
                    coach.dismissDashboard()
                    navigator.go(EnglishRoute.HOME)
                },
                celebrate = celebrate,
            )
        }

        // 퇴근길 학습 질문 — 주행 화면이든 홈이든 그 위에 뜬다 (말로 답하거나 큰 버튼 두 개)
        val ask = state.eveningAsk
        EveningAskCard(
            visible = ask.visible && !showDashboard,
            onYes = { coach.answerEveningAsk(true) },
            onNo = { coach.answerEveningAsk(false) },
            question = ask.questionKo,
            yesLabel = ask.yesLabelKo,
            noLabel = ask.noLabelKo,
            listening = ask.listening,
            deadlineMs = ask.deadlineMs,
            smoothRing = !driving,
        )

        // 온보딩·레벨 테스트처럼 레일이 없는 화면의 시연 버튼
        val noRail = screen == Screen.SPLASH || screen == Screen.ONBOARDING || screen == Screen.PLACEMENT
        if (demoCtl != null && noRail && !demoOpen) {
            DemoFab(Modifier.align(Alignment.TopEnd).padding(16.dp)) { demoOpen = true }
        }
        if (demoCtl != null && demoOpen) {
            DemoPanel(state, coach, demoCtl, navigator, onClose = { demoOpen = false })
        }

        ToastHost(state.toast, notice, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
    }
}

/** 명세의 다른 이름 — EnglishApp 과 같다 (내비게이터·시연 제어를 생략하면 기본값) */
@Composable
fun EnglishAppRoot(
    coach: EnglishCoach,
    navigator: EnglishNavigator = remember { EnglishNavigator() },
    demo: DemoControl? = null,
    modifier: Modifier = Modifier,
) {
    EnglishApp(coach, navigator, demo, modifier)
}

@Composable
private fun ScreenContent(s: Screen, state: EnglishUiState, coach: EnglishCoach, a: AppActions, micTest: MicTester?) {
    when (s) {
        Screen.SPLASH -> Splash()
        Screen.ONBOARDING -> OnboardingScreen(state, coach, a)
        Screen.PLACEMENT -> PlacementScreen(state, coach, a)
        Screen.DRIVE -> DriveScreen(state, coach, a.notice)
        Screen.LESSON -> LearnScreen(state, coach, a)
        Screen.HOME -> HomeScreen(state, coach, a)
        Screen.REVIEW -> ReviewScreen(state, coach, a)
        Screen.REPORT -> ReportScreen(state, coach, a)
        Screen.SETTINGS -> SettingsScreen(state, coach, a, micTest)
    }
}

private fun screenOf(r: EnglishRoute): Screen = when (r) {
    EnglishRoute.HOME -> Screen.HOME
    EnglishRoute.DRIVE -> Screen.DRIVE
    EnglishRoute.LESSON -> Screen.LESSON
    EnglishRoute.REVIEW -> Screen.REVIEW
    EnglishRoute.REPORT -> Screen.REPORT
    EnglishRoute.SETTINGS -> Screen.SETTINGS
    EnglishRoute.ONBOARDING, EnglishRoute.PLACEMENT -> Screen.HOME
}

private fun railRouteOf(s: Screen): EnglishRoute? = when (s) {
    Screen.HOME -> EnglishRoute.HOME
    Screen.DRIVE -> EnglishRoute.DRIVE
    Screen.LESSON -> EnglishRoute.LESSON
    Screen.REVIEW -> EnglishRoute.REVIEW
    Screen.REPORT -> EnglishRoute.REPORT
    Screen.SETTINGS -> EnglishRoute.SETTINGS
    Screen.SPLASH, Screen.ONBOARDING, Screen.PLACEMENT -> null
}

/** 매번 바뀌는 람다를 안정된 껍데기 안에서 최신 것으로 부르기 위한 홀더 */
@Composable
private fun <T> rememberLatest(value: T): Holder<T> {
    val h = remember { Holder(value) }
    h.value = value
    return h
}

private class Holder<T>(var value: T)

// ───────────────────────── 왼쪽 레일 ─────────────────────────

@Composable
private fun NavRail(
    selected: EnglishRoute?,
    driving: Boolean,
    compact: Boolean,
    reviewBadge: Int,
    showDemo: Boolean,
    onDemo: () -> Unit,
    onSelect: (EnglishRoute) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxHeight().width(if (compact) 108.dp else 132.dp).background(Y.Bg1)) {
        // 낮은 화면(720dp 안팎)에서는 로고를 빼고 칸 높이를 76dp 로 맞춘다
        val short = maxHeight < 860.dp
        Column(
            Modifier.fillMaxSize().padding(vertical = if (short) 8.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!short) {
                BrandMark(compact)
                Spacer(Modifier.height(20.dp))
            }
            RAIL.forEach { item ->
                val locked = driving && !item.route.drivingAllowed
                RailButton(
                    item = item,
                    selected = selected == item.route,
                    locked = locked,
                    badge = if (item.route == EnglishRoute.REVIEW) reviewBadge else 0,
                    width = if (compact) 96.dp else 116.dp,
                    minHeight = if (short) TouchMin else 86.dp,
                    onClick = { onSelect(item.route) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (driving && !short) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = Y.LoadFocus, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text("주행 중", style = MaterialTheme.typography.labelMedium, color = Y.LoadFocus)
                Spacer(Modifier.height(12.dp))
            }
            if (showDemo) {
                DemoFab(onClick = onDemo)
            }
        }
    }
}

@Composable
private fun BrandMark(compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Y.AccentSoft).border(1.5.dp, Y.Accent, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("EN", style = MaterialTheme.typography.titleLarge, color = Y.Accent, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (compact) "엔진" else "엔진글리시",
            style = MaterialTheme.typography.labelLarge,
            color = Y.Text2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RailButton(
    item: RailItem,
    selected: Boolean,
    locked: Boolean,
    badge: Int,
    width: androidx.compose.ui.unit.Dp,
    minHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val tint = when {
        selected -> Y.Accent
        locked -> Y.Text3
        else -> Y.Text2
    }
    Column(
        Modifier
            .padding(vertical = 3.dp)
            .width(width)
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) Y.AccentSoft else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(
                item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(34.dp).alpha(if (locked) 0.45f else 1f),
            )
            if (locked) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 4.dp).size(20.dp).clip(CircleShape).background(Y.Bg1),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = Y.LoadFocus, modifier = Modifier.size(14.dp))
                }
            } else if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp).heightIn(min = 22.dp).widthIn(min = 22.dp)
                        .clip(CircleShape).background(Y.Retry).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (badge > 99) "99+" else "$badge", style = MaterialTheme.typography.labelSmall, color = Y.Bg0, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Y.Text1 else tint,
            maxLines = 1,
            modifier = Modifier.alpha(if (locked) 0.6f else 1f),
        )
    }
}

@Composable
private fun DemoFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .size(TouchMin + 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Y.Bg3)
            .border(1.dp, Y.Violet.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Science, contentDescription = "시연 패널", tint = Y.Violet, modifier = Modifier.size(30.dp))
        Text("시연", style = MaterialTheme.typography.labelMedium, color = Y.Violet)
    }
}

// ───────────────────────── 위 상태 줄 (정차 화면) ─────────────────────────

@Composable
private fun StatusBar(state: EnglishUiState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomy = maxWidth >= 1150.dp
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 28.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClockText()
            Spacer(Modifier.width(18.dp))
            DriveStateChip(state.drive)
            Spacer(Modifier.width(12.dp))
            val d = state.drive
            if (d.charging) {
                Chip("충전 중" + (d.socPercent?.let { " · $it%" } ?: ""), color = Y.Ev, icon = Icons.Rounded.EvStation)
                Spacer(Modifier.width(12.dp))
            }
            // 정차 화면에서는 부하가 늘 '정차'라 기어 칩과 겹친다 → 부하 알약은 주행 화면에서만
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Chip(d.origin.ko, color = if (d.origin == SignalOrigin.PLEOS) Y.Ev else Y.Violet)
            if (roomy) {
                Spacer(Modifier.width(12.dp))
                Chip("차 안에만 암호화 저장", color = Y.Text2, icon = Icons.Rounded.Lock)
            }
        }
    }
}

@Composable
private fun ClockText() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L + 50L)
        }
    }
    Text(Kst.clockLabel(now), style = MaterialTheme.typography.headlineSmall, color = Y.Text1, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DriveStateChip(d: DriveStatus) {
    val text = when {
        d.charging -> "${d.gear.label} · 충전 중"
        d.gear == GearPosition.P -> "P · 정차 중"
        else -> "${d.gear.label} · ${d.speedKph}km/h"
    }
    Chip(text, color = Y.Text1, bg = Y.Bg3, icon = Icons.Rounded.DirectionsCar)
}

// ───────────────────────── 스플래시 ─────────────────────────

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(112.dp).clip(RoundedCornerShape(34.dp)).background(Y.AccentSoft).border(2.dp, Y.Accent, RoundedCornerShape(34.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("EN", style = MaterialTheme.typography.displayMedium, color = Y.Accent, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(24.dp))
            Text("엔진글리시", style = MaterialTheme.typography.displaySmall, color = Y.Text1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("학습 기록을 불러오고 있어요", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
        }
    }
}

// ───────────────────────── 정답 빛 · 알림 ─────────────────────────

/** 펄스 id 가 바뀌면 화면 가장자리를 1초 동안 초록/주황으로 (글자 뒤가 아닌 가장자리만) */
@Composable
private fun PulseGlow(pulse: AmbientPulse?) {
    val anim = remember { Animatable(0f) }
    val firstId = remember { pulse?.id }
    var color by remember { mutableStateOf(Y.Correct) }
    LaunchedEffect(pulse?.id) {
        val p = pulse ?: return@LaunchedEffect
        if (p.id == firstId) return@LaunchedEffect
        color = pulseColor(p.kind)
        anim.snapTo(0f)
        anim.animateTo(1f, tween(200))
        anim.animateTo(0f, tween(800))
    }
    Box(
        Modifier.fillMaxSize().drawBehind {
            val a = anim.value
            if (a <= 0f) return@drawBehind
            val e = 64.dp.toPx()
            val c = color.copy(alpha = 0.6f * a)
            drawRect(Brush.verticalGradient(listOf(c, Color.Transparent), startY = 0f, endY = e), size = Size(size.width, e))
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, c), startY = size.height - e, endY = size.height),
                topLeft = Offset(0f, size.height - e), size = Size(size.width, e),
            )
            drawRect(Brush.horizontalGradient(listOf(c, Color.Transparent), startX = 0f, endX = e), size = Size(e, size.height))
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, c), startX = size.width - e, endX = size.width),
                topLeft = Offset(size.width - e, 0f), size = Size(e, size.height),
            )
        },
    )
}

/** 코치 토스트와 화면 안내 중 가장 최근 것 하나 */
@Composable
private fun ToastHost(toast: ToastUi?, local: Notice?, modifier: Modifier) {
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(toast?.id) {
        val t = toast ?: return@LaunchedEffect
        val key = "c${t.id}"
        shown = key to t.textKo
        delay(2800)
        if (shown?.first == key) shown = null
    }
    LaunchedEffect(local?.id) {
        val n = local ?: return@LaunchedEffect
        val key = "l${n.id}"
        shown = key to n.text
        delay(2600)
        if (shown?.first == key) shown = null
    }
    val text = rememberLastWhileCurrent(shown != null, shown?.second ?: "")
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 },
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = Y.Bg0,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .widthIn(max = 900.dp)
                .clip(CircleShape)
                .background(Y.Text1)
                .padding(horizontal = 30.dp, vertical = 16.dp),
        )
    }
}
