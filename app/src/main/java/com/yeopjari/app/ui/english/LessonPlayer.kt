package com.yeopjari.app.ui.english

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeopjari.app.english.core.AlignOp
import com.yeopjari.app.english.core.CoachPhase
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.FeedbackUi
import com.yeopjari.app.english.core.FluencyRating
import com.yeopjari.app.english.core.LearnerCommand
import com.yeopjari.app.english.core.PromptUi
import com.yeopjari.app.english.core.PronIssue
import com.yeopjari.app.english.core.SessionUi
import com.yeopjari.app.english.core.Verdict
import com.yeopjari.app.english.core.WordAlign
import com.yeopjari.app.ui.theme.Y
import com.yeopjari.app.ui.theme.YType

/*
 * 레슨 플레이어 (정차 중 세션) — 같은 코치 흐름에 화면 보기 · 해설 · 단어 정렬 · 발음 팁 · 유창성을 더한다.
 * 에뮬레이터처럼 영어 음성 인식이 안 될 때를 위해 '입력해서 답하기'도 둔다 (STT 결과와 같은 길로 채점).
 */

private val ANSWERING = setOf(CoachPhase.PROMPTING, CoachPhase.LISTENING)

@Composable
internal fun LessonPlayer(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    val session = state.session ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1250.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp)) {
                Column(Modifier.weight(1.3f).fillMaxSize().verticalScroll(rememberScrollState())) {
                    LessonHeader(session, coach)
                    Spacer(Modifier.height(20.dp))
                    LessonMain(state, coach, wide = true)
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                    LessonSide(state, coach, a)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                LessonHeader(session, coach)
                Spacer(Modifier.height(18.dp))
                LessonMain(state, coach, wide = false)
                Spacer(Modifier.height(20.dp))
                LessonSide(state, coach, a)
            }
        }
    }
}

@Composable
private fun LessonHeader(s: SessionUi, coach: EnglishCoach) {
    val total = s.total.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Eyebrow("${s.slot.ko} · ${s.track.ko} · ${s.mode.ko}", color = Y.Accent)
            Spacer(Modifier.height(4.dp))
            Text(s.titleKo, style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        Text("${s.index.coerceAtMost(total)} / $total", style = YType.MetricSmall, color = Y.Text1)
        Spacer(Modifier.width(16.dp))
        BigButton("끝내기", { coach.stopSession() }, icon = Icons.Rounded.Flag, tone = Tone.GHOST)
    }
    Spacer(Modifier.height(12.dp))
    Meter(s.answered.toFloat() / total, height = 10.dp)
    Spacer(Modifier.height(8.dp))
    ChipFlow {
        Chip("정답 ${s.correct}", color = Y.Correct)
        if (s.partial > 0) Chip("거의 맞음 ${s.partial}", color = Y.Retry)
        Chip("새 표현 ${s.newDone}", color = Y.Text2)
        Chip("복습 ${s.reviewDone}", color = Y.Text2)
        if (s.xpGained > 0) Chip("+${s.xpGained} XP", color = Y.Accent)
    }
}

@Composable
private fun LessonMain(state: EnglishUiState, coach: EnglishCoach, wide: Boolean) {
    val p = state.prompt
    when {
        state.phase == CoachPhase.PAUSED -> CalmCard(Icons.Rounded.Pause, "잠깐 멈췄어요", "준비되면 ‘계속’을 누르세요") {
            BigButton("계속", { coach.resume() }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.fillMaxWidth())
        }
        state.phase == CoachPhase.HOLD -> CalmCard(Icons.Rounded.Visibility, "운전에 집중하세요", state.load.reasons.joinToString(" · ") { it.textKo })
        state.phase == CoachPhase.BRIEFING -> {
            val (head, parts) = splitPlanTitle(state.session?.titleKo ?: "")
            CalmCard(Icons.Rounded.PlayArrow, head.ifBlank { "세션을 시작해요" }, parts.joinToString(" · "))
        }
        state.phase == CoachPhase.CONFIRM -> CalmCard(Icons.Rounded.QuestionAnswer, p?.displayKo ?: "계속할까요?", "말로 답하거나 아래 버튼을 누르세요") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton("네", { coach.onCommand(LearnerCommand.YES) }, icon = Icons.Rounded.Check, modifier = Modifier.weight(1f))
                BigButton("아니요", { coach.onCommand(LearnerCommand.NO) }, icon = Icons.Rounded.Close, tone = Tone.SECONDARY, modifier = Modifier.weight(1f))
            }
        }
        p != null -> QuestionCard(state, p, coach, wide)
        else -> CalmCard(Icons.Rounded.PlayArrow, "다음 문제를 준비하고 있어요", "")
    }
}

@Composable
private fun CalmCard(icon: ImageVector, title: String, body: String, actions: (@Composable ColumnScope.() -> Unit)? = null) {
    Panel(Modifier.fillMaxWidth(), padding = 36.dp) {
        IconBadge(icon, Y.Accent, size = 72.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
        if (body.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.titleMedium, color = Y.Text2)
        }
        if (actions != null) {
            Spacer(Modifier.height(24.dp))
            actions()
        }
    }
}

@Composable
private fun QuestionCard(state: EnglishUiState, p: PromptUi, coach: EnglishCoach, wide: Boolean) {
    // 이 문항의 채점 결과 — 피드백 단계에서만 보기 색으로 보여 준다
    val fb = state.feedback?.takeIf { it.itemId == p.itemId && state.phase == CoachPhase.FEEDBACK }
    Panel(Modifier.fillMaxWidth(), accent = kindColor(p.kind), padding = 32.dp) {
        ChipFlow {
            Chip(p.titleKo, color = kindColor(p.kind), icon = skillIcon(p.skill), large = true)
            Chip(p.skill.ko, color = Y.Text2)
            if (p.attempt >= 2) Chip("한 번 더", color = Y.Retry)
            val hint = state.hud.hint
            if (p.hintShown && !hint.isNullOrBlank()) Chip("힌트 · $hint", color = Y.Accent, icon = Icons.Rounded.Lightbulb)
        }
        Spacer(Modifier.height(22.dp))
        Text(p.displayKo, style = MaterialTheme.typography.displaySmall, color = Y.Text1, fontWeight = FontWeight.Bold)
        p.displayEn?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = YType.English, color = Y.Accent)
        }
        Spacer(Modifier.height(26.dp))
        if (p.choices.isNotEmpty()) {
            val correctIndex = if (fb != null) p.choices.indexOfFirst { looseEquals(it, fb.modelAnswer) }.takeIf { it >= 0 } else null
            val wrongIndex = if (fb != null && fb.verdict != Verdict.CORRECT) {
                p.choices.indexOfFirst { looseEquals(it, fb.heard) }.takeIf { it >= 0 && it != correctIndex }
            } else {
                null
            }
            ChoiceGrid(
                choices = p.choices,
                wide = wide,
                correctIndex = correctIndex,
                wrongIndex = wrongIndex,
                enabled = state.phase in ANSWERING,
                onChoose = { coach.chooseOption(it) },
            )
            Spacer(Modifier.height(18.dp))
            Text(
                if (state.phase in ANSWERING) "보기를 누르거나 영어로 말해 보세요" else " ",
                style = MaterialTheme.typography.bodyMedium,
                color = Y.Text3,
            )
        }
        when {
            fb != null -> ResultLine(fb)
            state.phase == CoachPhase.LISTENING -> ListeningIndicator(state.listening, animate = true, showPartial = true, k = 0.8f)
            state.phase == CoachPhase.JUDGING -> Text("확인하고 있어요", style = MaterialTheme.typography.titleLarge, color = Y.Text2)
            else -> Unit
        }
        if (state.phase in ANSWERING) {
            Spacer(Modifier.height(20.dp))
            TypeAnswer(p.itemId) { coach.onAnswerText(it) }
        }
    }
}

/** 결과 한 줄 (자세한 내용은 옆 패널) */
@Composable
private fun ResultLine(f: FeedbackUi) {
    val c = verdictColor(f.verdict)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(verdictIcon(f.verdict), contentDescription = f.verdict.ko, tint = c, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(14.dp))
        Text(f.headlineKo, style = MaterialTheme.typography.headlineMedium, color = c, fontWeight = FontWeight.Bold)
        if (f.xp > 0) {
            Spacer(Modifier.width(12.dp))
            Chip("+${f.xp} XP", color = Y.Accent, large = true)
        }
    }
}

/** 입력해서 답하기 — 정차 중 · 영어 음성 인식이 안 될 때 */
@Composable
private fun TypeAnswer(itemId: String, onSubmit: (String) -> Unit) {
    var open by rememberSaveable(itemId) { mutableStateOf(false) }
    var text by rememberSaveable(itemId) { mutableStateOf("") }
    if (!open) {
        Row(
            Modifier
                .heightIn(min = TouchMin)
                .clip(RoundedCornerShape(20.dp))
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Keyboard, contentDescription = null, tint = Y.Text2, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("입력해서 답하기", style = MaterialTheme.typography.titleSmall, color = Y.Text2)
        }
        return
    }
    val submit = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSubmit(t)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 26.sp, color = Y.Text1),
            cursorBrush = SolidColor(Y.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TouchMin)
                .clip(RoundedCornerShape(20.dp))
                .background(Y.Bg1)
                .border(1.dp, Y.Stroke, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("영어로 입력해 보세요", style = TextStyle(fontSize = 24.sp, color = Y.Text3))
                    inner()
                }
            },
        )
        Spacer(Modifier.width(14.dp))
        BigButton("보내기", { submit() }, icon = Icons.AutoMirrored.Rounded.Send, enabled = text.isNotBlank())
    }
}

// ───────────────────────── 옆 패널 ─────────────────────────

@Composable
private fun LessonSide(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    val p = state.prompt
    val fb = state.feedback?.takeIf { p == null || it.itemId == p.itemId }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (fb != null && state.phase !in ANSWERING) FeedbackDetail(fb, coach, a)
        val expl = p?.explanationKo
        if (expl != null && fb != null && state.phase == CoachPhase.FEEDBACK) {
            Panel(Modifier.fillMaxWidth(), padding = 24.dp) {
                Eyebrow("해설")
                Spacer(Modifier.height(8.dp))
                Text(expl, style = MaterialTheme.typography.bodyLarge, color = Y.Text1)
            }
        }
        Controls(state, coach, a)
        Panel(Modifier.fillMaxWidth(), padding = 22.dp) {
            Eyebrow("핸들 버튼")
            Spacer(Modifier.height(8.dp))
            Text("다음 곡 = 넘기기 · 이전 곡 = 다시 · 재생/일시정지 = 잠깐/계속", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
            Spacer(Modifier.height(6.dp))
            Text("말로도 돼요: ‘다시’, ‘천천히’, ‘힌트’, ‘뜻’, ‘넘겨’, ‘잠깐’, ‘계속’", style = MaterialTheme.typography.bodyMedium, color = Y.Text3)
        }
    }
}

@Composable
private fun Controls(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    val p = state.prompt
    val has = p != null && state.phase != CoachPhase.PAUSED
    val paused = state.phase == CoachPhase.PAUSED
    Panel(Modifier.fillMaxWidth(), padding = 20.dp) {
        Eyebrow("조작")
        Spacer(Modifier.height(12.dp))
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> ControlTile(Icons.Rounded.Replay, "다시 듣기", has, m) { coach.onCommand(LearnerCommand.REPEAT) } },
            { m -> ControlTile(Icons.Rounded.SlowMotionVideo, "천천히", has, m) { coach.onCommand(LearnerCommand.SLOWER) } },
            { m -> ControlTile(Icons.Rounded.Lightbulb, "힌트", has && p.hintShown.not(), m) { coach.onCommand(LearnerCommand.HINT) } },
            { m -> ControlTile(Icons.Rounded.Translate, "뜻", has, m) { coach.onCommand(LearnerCommand.MEANING) } },
            { m -> ControlTile(Icons.Rounded.SkipNext, "넘기기", has, m) { coach.onCommand(LearnerCommand.SKIP) } },
            { m -> ControlTile(Icons.Rounded.BookmarkAdd, "저장", p != null || state.feedback != null, m) { a.result(coach.saveExpression(null)) } },
            { m ->
                if (paused) ControlTile(Icons.Rounded.PlayArrow, "계속", true, m) { coach.resume() }
                else ControlTile(Icons.Rounded.Pause, "일시정지", state.phase.inSession, m) { coach.pause() }
            },
            { m -> ControlTile(Icons.Rounded.Flag, "끝내기", true, m) { coach.stopSession() } },
        )
        tiles.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ControlTile(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(Y.Bg3)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) Y.Text1 else Y.Text3, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) Y.Text1 else Y.Text3, maxLines = 1)
    }
}

/** 채점 상세 — 들린 말 · 단어 정렬 · 모범 답안 · 발음 문제 · 유창성 */
@Composable
internal fun FeedbackDetail(f: FeedbackUi, coach: EnglishCoach, a: AppActions) {
    val c = verdictColor(f.verdict)
    Panel(Modifier.fillMaxWidth(), accent = c, padding = 26.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(verdictIcon(f.verdict), contentDescription = null, tint = c, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.headlineKo, style = MaterialTheme.typography.headlineSmall, color = c, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull("${f.score}점", f.grade?.let { "기억 정도 · ${it.ko}" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Y.Text2,
                )
            }
            if (f.xp > 0) Chip("+${f.xp} XP", color = Y.Accent)
        }
        if (f.heard.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Eyebrow("들린 말")
            Spacer(Modifier.height(6.dp))
            if (f.alignment.isNotEmpty()) AlignmentText(f.alignment)
            else Text("“${f.heard}”", style = MaterialTheme.typography.titleLarge, color = Y.Text1)
        }
        if (f.modelAnswer.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Eyebrow("모범 답안")
            Spacer(Modifier.height(6.dp))
            Text(f.modelAnswer, style = YType.English.copy(fontSize = 28.sp), color = Y.Accent)
        }
        if (f.pronIssues.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Eyebrow("발음")
            f.pronIssues.forEach { issue -> PronIssueRow(issue) { a.result(coach.startPronDrill(issue.type)) } }
        }
        f.fluency?.let { fl ->
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    fl.rating.ko,
                    color = when (fl.rating) {
                        FluencyRating.SMOOTH -> Y.Correct
                        FluencyRating.OK -> Y.Accent
                        FluencyRating.HESITANT -> Y.Retry
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text(fl.noteKo, style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
            }
        }
    }
}

/** 단어 정렬 — 초록 맞음 · 빨강 다르게 들림(괄호 안 = 들린 말) · 주황 밑줄 빠짐 · 회색 줄 덧붙음 */
@Composable
private fun AlignmentText(al: List<WordAlign>) {
    val text = buildAnnotatedString {
        al.forEachIndexed { i, w ->
            if (i > 0) append(" ")
            when (w.op) {
                AlignOp.MATCH -> withStyle(SpanStyle(color = Y.Correct)) { append(w.expected ?: w.heard.orEmpty()) }
                AlignOp.SUB -> {
                    withStyle(SpanStyle(color = Y.Miss, fontWeight = FontWeight.Bold)) { append(w.expected.orEmpty()) }
                    withStyle(SpanStyle(color = Y.Text3, fontSize = 18.sp)) { append("(${w.heard.orEmpty()})") }
                }
                AlignOp.DEL -> withStyle(SpanStyle(color = Y.Retry, textDecoration = TextDecoration.Underline)) { append(w.expected.orEmpty()) }
                AlignOp.INS -> withStyle(SpanStyle(color = Y.Text3, textDecoration = TextDecoration.LineThrough)) { append(w.heard.orEmpty()) }
            }
        }
    }
    Text(text, style = MaterialTheme.typography.titleLarge.copy(lineHeight = 36.sp))
    Spacer(Modifier.height(6.dp))
    Text("초록 맞음 · 빨강 다르게 들림 · 주황 밑줄 빠짐 · 회색 줄 덧붙음", style = MaterialTheme.typography.labelMedium, color = Y.Text3)
}

@Composable
internal fun PronIssueRow(issue: PronIssue, onDrill: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Rounded.GraphicEq, Y.Retry, size = 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(issue.type.ko, style = MaterialTheme.typography.titleMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
            Text("‘${issue.expectedWord}’가 ‘${issue.heardWord}’로 들렸어요", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
            if (issue.tipKo.isNotBlank()) Text(issue.tipKo, style = MaterialTheme.typography.bodySmall, color = Y.Text3)
            issue.drill?.let { d ->
                Text("${d.target} ↔ ${d.contrast}", style = MaterialTheme.typography.titleSmall, color = Y.Accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        BigButton("발음 연습", onDrill, tone = Tone.SECONDARY)
    }
}

