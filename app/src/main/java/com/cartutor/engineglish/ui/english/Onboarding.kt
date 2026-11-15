package com.cartutor.engineglish.ui.english

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cartutor.engineglish.english.core.DailyGoal
import com.cartutor.engineglish.english.core.EnglishCoach
import com.cartutor.engineglish.english.core.EnglishRoute
import com.cartutor.engineglish.english.core.EnglishUiState
import com.cartutor.engineglish.english.core.LearnThresholds
import com.cartutor.engineglish.english.core.Level
import com.cartutor.engineglish.english.core.PlacementResult
import com.cartutor.engineglish.english.core.PlacementUi
import com.cartutor.engineglish.english.core.ScreenMode
import com.cartutor.engineglish.english.core.Track
import com.cartutor.engineglish.ui.theme.Y
import com.cartutor.engineglish.ui.theme.YType

/*
 * 첫 실행 — 환영 → 트랙 → 하루 목표 → 시작 레벨(레벨 테스트 또는 직접 고르기) → 레벨 테스트 → 결과.
 * 모두 정차 중에만 (주행 중이면 잠금 안내).
 */

private enum class OnbStep { WELCOME, TRACK, GOAL, LEVEL }

@Composable
internal fun OnboardingScreen(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    if (state.screenMode == ScreenMode.DRIVE) {
        LockedPanel(
            title = "정차 후 시작할 수 있어요",
            body = "처음 설정은 차를 세운 뒤에 해 주세요. 주행 중에는 화면을 보지 않아도 돼요.",
        )
        return
    }
    val existing = state.profile
    var step by rememberSaveable { mutableStateOf(if (existing != null) OnbStep.LEVEL else OnbStep.WELCOME) }
    var track by rememberSaveable { mutableStateOf(existing?.track) }
    var goal by rememberSaveable { mutableStateOf(existing?.goal ?: DailyGoal.STEADY) }

    fun complete(): Boolean {
        val t = track ?: return false
        if (state.profile == null) coach.completeOnboarding(t, goal)
        // 시작 뒤에는 홈에서 (기록을 지우고 다시 시작한 경우에도 설정 화면으로 돌아가지 않게)
        a.go(EnglishRoute.HOME)
        return true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1300.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 64.dp else 32.dp, vertical = 32.dp)) {
            // 위: 이전 · 단계 점
            Row(Modifier.fillMaxWidth().heightIn(min = TouchMin), verticalAlignment = Alignment.CenterVertically) {
                if (step != OnbStep.WELCOME && !(existing != null && step == OnbStep.LEVEL)) {
                    BigButton(
                        "이전",
                        onClick = { step = OnbStep.entries[(step.ordinal - 1).coerceAtLeast(0)] },
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        tone = Tone.GHOST,
                    )
                }
                Spacer(Modifier.weight(1f))
                StepDots(step.ordinal, OnbStep.entries.size)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
                    label = "onboarding",
                ) { s ->
                    when (s) {
                        OnbStep.WELCOME -> WelcomeStep(wide)
                        OnbStep.TRACK -> TrackStep(track, wide) { track = it }
                        OnbStep.GOAL -> GoalStep(goal, wide) { goal = it }
                        OnbStep.LEVEL -> LevelStep(
                            wide = wide,
                            onPlacement = { if (complete()) coach.startPlacement() },
                            onLevel = { lv -> if (complete()) coach.skipPlacement(lv) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = Y.Text3, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "학습 기록은 이 차 안에만 암호화해 저장돼요 · 앱 안에서 채점해요 (서버 전송 없음)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Y.Text3,
                    modifier = Modifier.weight(1f),
                )
                when (step) {
                    OnbStep.WELCOME -> BigButton("시작하기", { step = OnbStep.TRACK }, icon = Icons.AutoMirrored.Rounded.ArrowForward, modifier = Modifier.widthIn(min = 280.dp))
                    OnbStep.TRACK -> BigButton("다음", { step = OnbStep.GOAL }, enabled = track != null, icon = Icons.AutoMirrored.Rounded.ArrowForward, modifier = Modifier.widthIn(min = 240.dp))
                    OnbStep.GOAL -> BigButton("다음", { step = OnbStep.LEVEL }, icon = Icons.AutoMirrored.Rounded.ArrowForward, modifier = Modifier.widthIn(min = 240.dp))
                    OnbStep.LEVEL -> Unit
                }
            }
        }
    }
}

@Composable
private fun StepDots(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            Box(
                Modifier
                    .height(10.dp)
                    .width(if (i == index) 36.dp else 10.dp)
                    .clip(CircleShape)
                    .background(if (i <= index) Y.Accent else Y.Bg4),
            )
        }
    }
}

@Composable
private fun WelcomeStep(wide: Boolean) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState())) {
            Eyebrow("엔진글리시 · Engineglish", color = Y.Accent)
            Spacer(Modifier.height(16.dp))
            Text("출퇴근길이\n영어 시간이 돼요", style = MaterialTheme.typography.displayLarge, color = Y.Text1)
            Spacer(Modifier.height(16.dp))
            Text(
                "운전이 바쁠 땐 조용히 기다리고, 여유로울 때만 짧게 물어봐요.",
                style = MaterialTheme.typography.headlineSmall,
                color = Y.Text2,
            )
            Spacer(Modifier.height(32.dp))
            FeatureRow(Icons.Rounded.Speed, "운전 부하에 맞춰 알아서 쉬어요", "방향지시등 · 교차로 · 급제동 때는 바로 멈추고, 여유가 생기면 아까 그 문제부터 이어서 해요.")
            FeatureRow(Icons.Rounded.WbSunny, "출근길엔 새 표현, 퇴근길엔 맞춤 복습", "남은 운전 시간을 보고 오늘 할 양을 정해요. 퇴근길에는 할지 먼저 물어봐요.")
            FeatureRow(Icons.Rounded.Hearing, "말로 답하고, 발음까지 짚어 줘요", "R/L, F/P처럼 한국어 화자가 헷갈리는 소리를 골라 연습해요.")
        }
        if (wide) {
            Spacer(Modifier.width(48.dp))
            DrivePreviewCard(Modifier.weight(0.9f))
        }
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        IconBadge(icon, Y.Accent, size = 60.dp)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
        }
    }
}

/** 주행 화면 맛보기 (그림 설명용 — 실제 데이터 아님) */
@Composable
private fun DrivePreviewCard(modifier: Modifier) {
    Panel(modifier, accent = Y.Accent, padding = 28.dp) {
        Eyebrow("주행 중 화면은 이렇게 보여요")
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("보통 · 시내 주행", color = Y.LoadNormal, large = true)
            Spacer(Modifier.width(10.dp))
            Chip("출근길 · 도착까지 28분", color = Y.Text1, bg = Y.Bg3)
        }
        Spacer(Modifier.height(22.dp))
        Text("‘회의를 내일로 미룰 수 있을까요?’를 영어로", style = MaterialTheme.typography.headlineMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF060A14)),
            contentAlignment = Alignment.Center,
        ) {
            Text("push", style = YType.English.copy(color = Y.Hud, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(8.dp))
        Text("앞유리 HUD에는 힌트 한 단어만 띄워요", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("집중 · 교차로 접근 120m", color = Y.LoadFocus)
            Spacer(Modifier.width(10.dp))
            Text("→ 잠시 멈췄다가 다시", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
        }
    }
}

@Composable
private fun TrackStep(selected: Track?, wide: Boolean, onSelect: (Track) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("어떤 영어가 필요하세요?", style = MaterialTheme.typography.displaySmall, color = Y.Text1, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("나중에 설정에서 언제든 바꿀 수 있어요.", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
        Spacer(Modifier.height(28.dp))
        val tracks = Track.entries
        val perRow = if (wide) 4 else 2
        tracks.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { t ->
                    ChoiceCard(
                        title = t.ko,
                        body = trackBlurbKo(t),
                        icon = trackIcon(t),
                        selected = selected == t,
                        onClick = { onSelect(t) },
                        modifier = Modifier.weight(1f).heightIn(min = 240.dp),
                    )
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GoalStep(selected: DailyGoal, wide: Boolean, onSelect: (DailyGoal) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("하루 목표를 정해 주세요", style = MaterialTheme.typography.displaySmall, color = Y.Text1, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("목표를 채우면 연속 학습 일수가 늘어나요.", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
        Spacer(Modifier.height(28.dp))
        val goals = DailyGoal.entries
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                goals.forEach { g -> GoalCard(g, selected == g, Modifier.weight(1f)) { onSelect(g) } }
            }
        } else {
            goals.forEach { g ->
                GoalCard(g, selected == g, Modifier.fillMaxWidth()) { onSelect(g) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun GoalCard(g: DailyGoal, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ChoiceCard(
        title = goalShortKo(g),
        body = "하루 ${g.items}문장 · ${goalBlurbKo(g)}",
        icon = when (g) {
            DailyGoal.LIGHT -> Icons.Rounded.Lightbulb
            DailyGoal.STEADY -> Icons.AutoMirrored.Rounded.TrendingUp
            DailyGoal.INTENSIVE -> Icons.Rounded.Speed
        },
        selected = selected,
        onClick = onClick,
        badge = if (g == DailyGoal.STEADY) "추천" else null,
        modifier = modifier.heightIn(min = 220.dp),
    )
}

@Composable
private fun LevelStep(wide: Boolean, onPlacement: () -> Unit, onLevel: (Level) -> Unit) {
    var manual by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("지금 실력에 맞춰 시작할게요", style = MaterialTheme.typography.displaySmall, color = Y.Text1, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("레벨 테스트는 정차 중에 화면의 선택지를 눌러 답해요.", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
        Spacer(Modifier.height(28.dp))
        val cards: @Composable (Modifier) -> Unit = { m ->
            ChoiceCard(
                title = "레벨 테스트 하기",
                body = "${LearnThresholds.PLACEMENT_ITEMS}문항 · 약 3분. 맞히면 어려워지고 틀리면 쉬워져요.",
                icon = Icons.Rounded.Quiz,
                selected = false,
                onClick = onPlacement,
                badge = "추천",
                modifier = m,
            )
        }
        val manualCard: @Composable (Modifier) -> Unit = { m ->
            ChoiceCard(
                title = "레벨 직접 고르기",
                body = "초급 · 중급 · 고급 중에서 바로 시작해요.",
                icon = Icons.Rounded.Tune,
                selected = manual,
                onClick = { manual = !manual },
                modifier = m,
            )
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                cards(Modifier.weight(1f).heightIn(min = 220.dp))
                manualCard(Modifier.weight(1f).heightIn(min = 220.dp))
            }
        } else {
            cards(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            manualCard(Modifier.fillMaxWidth())
        }
        AnimatedVisibility(visible = manual) {
            Column(Modifier.padding(top = 24.dp)) {
                Text("어느 레벨부터 할까요?", style = MaterialTheme.typography.titleLarge, color = Y.Text1)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Level.entries.forEach { lv ->
                        BigButton(
                            "${lv.ko}부터",
                            onClick = { onLevel(lv) },
                            tone = Tone.SECONDARY,
                            modifier = Modifier.weight(1f),
                            minHeight = 92.dp,
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────── 레벨 테스트 ─────────────────────────

@Composable
internal fun PlacementScreen(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    if (state.screenMode == ScreenMode.DRIVE) {
        LockedPanel(title = "정차 후 이어서 할 수 있어요", body = "레벨 테스트는 정차 중에만 할 수 있어요.")
        return
    }
    val p = state.placement
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1300.dp
        Box(Modifier.fillMaxSize().padding(horizontal = if (wide) 64.dp else 28.dp, vertical = 32.dp)) {
            when {
                p == null -> EmptyNote(Icons.Rounded.Quiz, "레벨 테스트를 준비하고 있어요", Modifier.align(Alignment.Center))
                p.result != null -> PlacementResultView(state, p.result, wide, coach)
                else -> PlacementQuestionView(p, wide, coach)
            }
        }
    }
}

@Composable
private fun PlacementQuestionView(p: PlacementUi, wide: Boolean, coach: EnglishCoach) {
    var pickLevel by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("레벨 테스트", style = MaterialTheme.typography.headlineMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Chip("지금 난이도 · ${p.currentLevel.ko}", color = Y.Accent)
            Spacer(Modifier.weight(1f))
            Text(
                "${(p.index + 1).coerceAtMost(p.total)} / ${p.total}",
                style = YType.MetricSmall,
                color = Y.Text1,
            )
        }
        Spacer(Modifier.height(14.dp))
        Meter(p.index.toFloat() / p.total.coerceAtLeast(1), height = 12.dp)
        Spacer(Modifier.height(32.dp))
        val item = p.item
        if (item == null) {
            EmptyNote(Icons.Rounded.Quiz, "다음 문항을 준비하고 있어요")
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(item.skill.ko, color = Y.Text2, icon = skillIcon(item.skill))
                    if (item.audioEn != null) {
                        Spacer(Modifier.width(10.dp))
                        Chip("소리로 들려드려요", color = Y.Accent, icon = Icons.Rounded.Hearing)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(item.questionKo, style = MaterialTheme.typography.headlineLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
                val en = item.questionEn
                if (en != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(en, style = YType.English.copy(fontSize = YType.English.fontSize * 1.1f), color = Y.Accent)
                }
                Spacer(Modifier.height(32.dp))
                val revealed = p.lastCorrect != null
                ChoiceGrid(
                    choices = item.choices,
                    wide = wide,
                    correctIndex = if (revealed) item.answerIndex else null,
                    wrongIndex = null,
                    enabled = !revealed,
                    onChoose = { coach.choosePlacementAnswer(it) },
                )
                Spacer(Modifier.height(20.dp))
                AnimatedVisibility(visible = revealed) {
                    val ok = p.lastCorrect == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Lightbulb,
                            contentDescription = null,
                            tint = if (ok) Y.Correct else Y.Retry,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (ok) "맞았어요" else "아쉬워요" + (item.explanationKo?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (ok) Y.Correct else Y.Retry,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("모를 땐 가장 그럴듯한 보기를 골라 주세요.", style = MaterialTheme.typography.bodyMedium, color = Y.Text3, modifier = Modifier.weight(1f))
            BigButton("레벨 직접 고르기", { pickLevel = true }, tone = Tone.GHOST, icon = Icons.Rounded.Tune)
        }
    }
    if (pickLevel) {
        LevelPickerSheet(
            title = "레벨을 직접 고를까요?",
            onPick = { lv ->
                pickLevel = false
                coach.skipPlacement(lv)
            },
            onDismiss = { pickLevel = false },
        )
    }
}

/** 보기 버튼 묶음 (레벨 테스트 · 정차 중 학습) */
@Composable
internal fun ChoiceGrid(
    choices: List<String>,
    wide: Boolean,
    correctIndex: Int?,
    wrongIndex: Int?,
    enabled: Boolean,
    onChoose: (Int) -> Unit,
) {
    val cols = if (wide && choices.size >= 4) 2 else 1
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        choices.withIndex().toList().chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (i, text) ->
                    AnswerChoice(
                        letter = ('A' + i).toString(),
                        text = text,
                        state = when (i) {
                            correctIndex -> ChoiceState.CORRECT
                            wrongIndex -> ChoiceState.WRONG
                            else -> if (enabled) ChoiceState.NORMAL else ChoiceState.DIMMED
                        },
                        enabled = enabled,
                        onClick = { onChoose(i) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal enum class ChoiceState { NORMAL, CORRECT, WRONG, DIMMED }

@Composable
internal fun AnswerChoice(
    letter: String,
    text: String,
    state: ChoiceState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val accent = when (state) {
        ChoiceState.CORRECT -> Y.Correct
        ChoiceState.WRONG -> Y.Miss
        else -> null
    }
    Row(
        modifier
            .heightIn(min = 96.dp)
            .clip(shape)
            .background(accent?.copy(alpha = 0.14f) ?: Y.Bg2)
            .border(if (accent != null) 2.dp else 1.dp, accent ?: Y.Stroke, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(accent?.copy(alpha = 0.25f) ?: Y.Bg3),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, style = MaterialTheme.typography.titleLarge, color = accent ?: Y.Text2, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall,
            color = if (state == ChoiceState.DIMMED) Y.Text3 else Y.Text1,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (state == ChoiceState.CORRECT) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = "정답", tint = Y.Correct, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun PlacementResultView(state: EnglishUiState, r: PlacementResult, wide: Boolean, coach: EnglishCoach) {
    var chosen by rememberSaveable { mutableStateOf(r.level) }
    val profile = state.profile
    val left: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Eyebrow("레벨 테스트 결과", color = Y.Accent)
            Spacer(Modifier.height(14.dp))
            Text("${r.level.ko}부터 시작할게요", style = MaterialTheme.typography.displayMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(r.summaryKo, style = MaterialTheme.typography.headlineSmall, color = Y.Text2)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatTile("정답", "${r.correct}/${r.total}", Modifier.weight(1f), icon = Icons.Rounded.CheckCircle, color = Y.Correct)
                StatTile("트랙", profile?.track?.ko ?: "-", Modifier.weight(1f), icon = profile?.track?.let { trackIcon(it) })
                StatTile("하루 목표", profile?.goal?.let { "${it.items}문장" } ?: "-", Modifier.weight(1f))
            }
            Spacer(Modifier.height(28.dp))
            Text("다른 레벨로 시작해도 돼요", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
            Spacer(Modifier.height(10.dp))
            Segmented(Level.entries, chosen, { it.ko }, { chosen = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            BigButton(
                if (chosen == r.level) "이 레벨로 시작하기" else "${chosen.ko}으로 시작하기",
                onClick = { if (chosen == r.level) coach.finishPlacement() else coach.skipPlacement(chosen) },
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 92.dp,
            )
        }
    }
    val right: @Composable (Modifier) -> Unit = { m ->
        Panel(m, padding = 28.dp) {
            Text("레벨별 결과", style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Level.entries.forEach { lv ->
                val (c, n) = r.perLevel[lv] ?: (0 to 0)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                    Text(lv.ko, style = MaterialTheme.typography.titleMedium, color = if (lv == r.level) Y.Accent else Y.Text1, modifier = Modifier.width(72.dp))
                    Box(Modifier.weight(1f)) {
                        Meter(if (n > 0) c.toFloat() / n else 0f, color = if (lv == r.level) Y.Accent else Y.Text3, height = 14.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(if (n > 0) "$c / $n" else "안 풀었어요", style = MaterialTheme.typography.titleSmall, color = Y.Text2, modifier = Modifier.width(104.dp), textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("맞힐수록 어려운 문제가 나와서, 정답 수보다 어디까지 올라갔는지가 중요해요.", style = MaterialTheme.typography.bodyMedium, color = Y.Text3)
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            left(Modifier.weight(1.1f).verticalScroll(rememberScrollState()))
            Spacer(Modifier.width(40.dp))
            right(Modifier.weight(0.9f))
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            left(Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            right(Modifier.fillMaxWidth())
        }
    }
}

/** 레벨 고르기 시트 */
@Composable
internal fun LevelPickerSheet(title: String, onPick: (Level) -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Y.Dim)
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Panel(
            Modifier.padding(24.dp).widthIn(max = 820.dp).clickable(interactionSource = null, indication = null) {},
            accent = Y.Accent,
            padding = 32.dp,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Level.entries.forEach { lv ->
                    BigButton("${lv.ko}부터", { onPick(lv) }, Modifier.weight(1f), tone = Tone.SECONDARY, minHeight = 92.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
            BigButton("취소", onDismiss, Modifier.fillMaxWidth(), tone = Tone.GHOST)
        }
    }
}
