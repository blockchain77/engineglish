package com.yeopjari.app.ui.english

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.LearnThresholds
import com.yeopjari.app.english.core.Level
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.english.core.SessionOrigin
import com.yeopjari.app.english.core.SessionRequest
import com.yeopjari.app.english.core.Track
import com.yeopjari.app.english.core.UnitProgress
import com.yeopjari.app.ui.theme.Y

/*
 * 학습 — 트랙 × 레벨 × 유닛 (잠금 · 진행). 정차 중 세션이 진행 중이면 레슨 플레이어.
 */

@Composable
internal fun LearnScreen(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    if (state.screenMode == ScreenMode.DRIVE) {
        LockedPanel()
        return
    }
    if (state.phase.inSession && state.session != null) {
        LessonPlayer(state, coach, a)
        return
    }
    Curriculum(state, coach, a)
}

@Composable
private fun Curriculum(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    val profile = state.profile
    val pr = state.progress
    var switchTo by remember { mutableStateOf<Track?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = when {
            maxWidth >= 1650.dp -> 4
            maxWidth >= 1150.dp -> 3
            maxWidth >= 760.dp -> 2
            else -> 1
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            ScreenHeader(
                "학습",
                subtitle = "유닛마다 ${LearnThresholds.UNIT_SIZE}문장 · 80% 넘게 맞히면 다음 유닛이 열려요",
            )
            // 트랙
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Track.entries.forEach { t ->
                    TrackTab(t, selected = profile?.track == t, modifier = Modifier.weight(1f)) {
                        if (profile != null && profile.track != t) switchTo = t
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            // 레벨 길
            profile?.let { LevelPath(it.level, pr.levelProgress) }
            Spacer(Modifier.height(30.dp))
            val units = pr.units
            val passed = units.count { it.passed }
            SectionTitle(
                listOfNotNull(profile?.track?.ko, profile?.level?.ko).joinToString(" · ") + " 유닛",
                subtitle = if (units.isNotEmpty()) "${units.size}개 중 ${passed}개 통과 · 이 레벨에서 유닛 ${LearnThresholds.UNITS_FOR_LEVEL_UP}개를 통과하면 레벨 업" else null,
            )
            if (units.isEmpty()) {
                EmptyNote(Icons.AutoMirrored.Rounded.MenuBook, "이 레벨의 유닛을 불러오고 있어요")
            } else {
                val currentKey = pr.currentUnit?.key
                CardGrid(
                    cols = cols,
                    cards = units.map { u ->
                        { m: Modifier ->
                            UnitCard(
                                u = u,
                                current = u.key == currentKey,
                                modifier = m,
                                onStart = { a.result(coach.startSession(SessionRequest(track = profile?.track, origin = SessionOrigin.USER))) },
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.height(32.dp))
            SectionTitle("더 연습하기")
            val topPron = state.library.pronTips.firstOrNull()
            CardGrid(
                cols = if (cols >= 2) 2 else 1,
                cards = listOf(
                    { m ->
                        PracticeCard(
                            icon = Icons.Rounded.GraphicEq,
                            color = Y.Periwinkle,
                            title = "발음 짝 연습",
                            body = topPron?.let { "자주 헷갈린 ${it.type.ko}부터 연습해요" } ?: "R/L, F/P처럼 헷갈리는 소리 짝을 연습해요",
                            action = "연습 시작",
                            modifier = m,
                        ) { a.result(coach.startPronDrill(topPron?.type ?: PronIssueType.R_L)) }
                    },
                    { m ->
                        PracticeCard(
                            icon = Icons.Rounded.History,
                            color = Y.LoadParked,
                            title = "복습",
                            body = if (pr.dueReviews > 0) "지금 복습할 표현이 ${pr.dueReviews}개 있어요" else "틀린 표현과 잊을 때가 된 표현을 다시 연습해요",
                            action = "복습 시작",
                            modifier = m,
                        ) { a.result(coach.startReview()) }
                    },
                ),
            )
        }
    }
    switchTo?.let { t ->
        ConfirmSheet(
            title = "${t.ko} 트랙으로 바꿀까요?",
            body = "지금까지의 기록은 그대로 남아요. 바꾼 트랙의 유닛부터 이어서 해요.",
            confirmLabel = "바꾸기",
            onConfirm = {
                coach.changeTrack(t)
                switchTo = null
            },
            onDismiss = { switchTo = null },
        )
    }
}

@Composable
private fun TrackTab(t: Track, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .heightIn(min = 84.dp)
            .clip(shape)
            .background(if (selected) Y.AccentSoft else Y.Bg2)
            .border(if (selected) 2.dp else 1.dp, if (selected) Y.Accent else Y.StrokeSoft, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(trackIcon(t), contentDescription = null, tint = if (selected) Y.Accent else Y.Text2, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(12.dp))
        Text(t.ko, style = MaterialTheme.typography.titleMedium, color = if (selected) Y.Text1 else Y.Text2, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** 초급 → 중급 → 고급 길 (지나온 레벨 · 지금 레벨 · 잠긴 레벨) */
@Composable
private fun LevelPath(current: Level, levelProgress: Float) {
    Panel(Modifier.fillMaxWidth(), padding = 26.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Level.entries.forEachIndexed { i, lv ->
                if (i > 0) {
                    Box(
                        Modifier.weight(0.6f).height(4.dp).clip(CircleShape)
                            .background(if (lv.ordinal <= current.ordinal) Y.Accent else Y.Bg4),
                    )
                }
                val done = lv.ordinal < current.ordinal
                val now = lv == current
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    done -> Y.Accent
                                    now -> Y.AccentSoft
                                    else -> Y.Bg3
                                },
                            )
                            .border(2.dp, if (now) Y.Accent else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            done -> Icon(Icons.Rounded.Check, contentDescription = null, tint = Y.OnAccent, modifier = Modifier.size(30.dp))
                            now -> Text("${i + 1}", style = MaterialTheme.typography.titleLarge, color = Y.Accent, fontWeight = FontWeight.Bold)
                            else -> Icon(Icons.Rounded.Lock, contentDescription = null, tint = Y.Text3, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(lv.ko, style = MaterialTheme.typography.titleLarge, color = if (now) Y.Text1 else if (done) Y.Text2 else Y.Text3, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                done -> "지나온 레벨"
                                now -> "레벨 업까지 ${percentKo(levelProgress)}"
                                else -> "레벨 업하면 열려요"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (now) Y.Accent else Y.Text3,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnitCard(u: UnitProgress, current: Boolean, modifier: Modifier, onStart: () -> Unit) {
    val locked = !u.unlocked
    Panel(modifier.alpha(if (locked) 0.55f else 1f), accent = if (current) Y.Accent else null, padding = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("유닛 ${u.key.unit}", color = if (current) Y.Accent else Y.Text3)
            Spacer(Modifier.weight(1f))
            when {
                u.passed -> Chip("통과", color = Y.Correct, icon = Icons.Rounded.CheckCircle)
                locked -> Chip("잠김", color = Y.Text3, icon = Icons.Rounded.Lock)
                current -> Chip("학습 중", color = Y.Accent)
                else -> Unit
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(u.titleKo, style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        Meter(if (u.total > 0) u.answered.toFloat() / u.total else 0f, color = if (u.passed) Y.Correct else Y.Accent)
        Spacer(Modifier.height(8.dp))
        Row {
            Text("${u.answered} / ${u.total}문장", style = MaterialTheme.typography.bodySmall, color = Y.Text2)
            Spacer(Modifier.weight(1f))
            if (u.answered > 0) Text("정확도 ${percentKo(u.accuracy)}", style = MaterialTheme.typography.bodySmall, color = Y.Text2)
        }
        Spacer(Modifier.weight(1f, fill = false))
        when {
            current -> {
                Spacer(Modifier.height(18.dp))
                BigButton("이어서 학습", onStart, icon = Icons.Rounded.PlayArrow, modifier = Modifier.fillMaxWidth())
            }
            locked -> {
                Spacer(Modifier.height(14.dp))
                Text("앞 유닛을 80% 넘게 맞히면 열려요", style = MaterialTheme.typography.bodySmall, color = Y.Text3, textAlign = TextAlign.Start)
            }
            else -> Unit
        }
    }
}

@Composable
private fun PracticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    title: String,
    body: String,
    action: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Panel(modifier, padding = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
        Spacer(Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(18.dp))
        BigButton(action, onClick, tone = Tone.SECONDARY, modifier = Modifier.fillMaxWidth())
    }
}
