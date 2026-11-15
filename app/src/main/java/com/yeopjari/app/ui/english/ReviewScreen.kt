package com.yeopjari.app.ui.english

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TaskAlt
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.ExpressionUi
import com.yeopjari.app.english.core.PronIssueType
import com.yeopjari.app.english.core.PronTipUi
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.ui.theme.Y
import com.yeopjari.app.ui.theme.YType

/*
 * 복습 — 지금 복습할 표현 수 · 오늘 틀린 표현 · 곧 복습할 표현 · 저장한 표현 · 발음 짝 연습.
 */

private enum class ReviewTab(val ko: String) { MISTAKES("오늘 틀린 표현"), DUE("곧 복습할 표현"), SAVED("저장한 표현") }

@Composable
internal fun ReviewScreen(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    if (state.screenMode == ScreenMode.DRIVE) {
        LockedPanel()
        return
    }
    val lib = state.library
    var tab by rememberSaveable {
        mutableStateOf(if (lib.mistakesToday.isEmpty() && lib.dueSoon.isNotEmpty()) ReviewTab.DUE else ReviewTab.MISTAKES)
    }
    val list: List<ExpressionUi> = when (tab) {
        ReviewTab.MISTAKES -> lib.mistakesToday
        ReviewTab.DUE -> lib.dueSoon
        ReviewTab.SAVED -> lib.saved
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1200.dp
        val drillCols = if (wide) 2 else 1
        // 발음 드릴: 자주 헷갈린 유형 먼저, 나머지 유형도 모두 연습 가능
        val tips = lib.pronTips
        val rest = PronIssueType.entries.filter { t -> tips.none { it.type == t } }
        val drills: List<Pair<PronIssueType, PronTipUi?>> = tips.map { it.type to it } + rest.map { it to null }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeader("복습", subtitle = "잊기 직전에 다시 떠올려야 오래 남아요") }
            item { ReviewHero(state, coach, a) }
            item {
                Spacer(Modifier.height(12.dp))
                Segmented(
                    options = ReviewTab.entries,
                    selected = tab,
                    label = { t ->
                        val n = when (t) {
                            ReviewTab.MISTAKES -> lib.mistakesToday.size
                            ReviewTab.DUE -> lib.dueSoon.size
                            ReviewTab.SAVED -> lib.saved.size
                        }
                        "${t.ko} $n"
                    },
                    onSelect = { tab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
            }
            if (list.isEmpty()) {
                item {
                    when (tab) {
                        ReviewTab.MISTAKES -> EmptyNote(Icons.Rounded.TaskAlt, "오늘은 틀린 표현이 없어요", body = "틀리거나 거의 맞힌 표현은 퇴근길 맞춤 복습에 먼저 나와요.")
                        ReviewTab.DUE -> EmptyNote(Icons.Rounded.EventRepeat, "복습 예정인 표현이 없어요", body = "새 표현을 배우면 간격 반복 일정이 잡혀요.")
                        ReviewTab.SAVED -> EmptyNote(Icons.Rounded.Bookmark, "아직 저장한 표현이 없어요", body = "학습 중 ‘저장’을 누르거나, 운전 중 Gleo에게 ‘이 표현 저장해 줘’라고 말해 보세요.")
                    }
                }
            } else {
                itemsIndexed(list, key = { i, e -> "${tab.name}:$i:${e.itemId ?: e.textEn}" }) { _, e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Y.Bg2)
                            .padding(horizontal = 24.dp),
                    ) {
                        ExpressionRow(
                            e,
                            tagColor = when (tab) {
                                ReviewTab.MISTAKES -> Y.Retry
                                ReviewTab.DUE -> Y.LoadParked
                                ReviewTab.SAVED -> Y.Accent
                            },
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
                SectionTitle("발음 짝 연습", subtitle = "한국어 화자가 헷갈리기 쉬운 소리 짝을 6개씩 연습해요")
            }
            items(drills.chunked(drillCols)) { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { (type, tip) ->
                        DrillCard(type, tip, Modifier.weight(1f).fillMaxHeight()) { a.result(coach.startPronDrill(type)) }
                    }
                    repeat(drillCols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ReviewHero(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    val due = state.progress.dueReviews
    val mistakes = state.library.mistakesToday.size
    Panel(Modifier.fillMaxWidth(), accent = Y.LoadParked, padding = 30.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.History, Y.LoadParked, size = 72.dp)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("지금 복습할 표현")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$due", style = YType.Hero, color = Y.Text1)
                    Spacer(Modifier.width(6.dp))
                    Text("개", style = MaterialTheme.typography.titleLarge, color = Y.Text3, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(
                    if (mistakes > 0) "오늘 틀린 표현 ${mistakes}개부터 다시 해요" else "간격 반복으로 잊기 직전에 다시 물어봐요",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Y.Text2,
                )
            }
            Spacer(Modifier.width(20.dp))
            BigButton(
                "복습 시작",
                { a.result(coach.startReview()) },
                icon = Icons.Rounded.PlayArrow,
                enabled = due > 0 || mistakes > 0,
                minHeight = 88.dp,
            )
        }
    }
}

@Composable
private fun DrillCard(type: PronIssueType, tip: PronTipUi?, modifier: Modifier, onStart: () -> Unit) {
    Panel(modifier, padding = 24.dp, accent = if (tip != null) Y.Retry else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.GraphicEq, if (tip != null) Y.Retry else Y.Periwinkle, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(type.ko, style = MaterialTheme.typography.titleLarge, color = Y.Text1, fontWeight = FontWeight.Bold)
                Text(
                    if (tip != null && tip.count > 0) "${tip.count}번 헷갈렸어요" else type.example,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tip != null) Y.Retry else Y.Text2,
                )
            }
            Spacer(Modifier.width(12.dp))
            BigButton("연습", onStart, tone = Tone.SECONDARY)
        }
        if (tip != null) {
            if (tip.tipKo.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(tip.tipKo, style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
            }
            if (tip.pairs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ChipFlow(spacing = 8.dp) {
                    tip.pairs.take(3).forEach { Chip("${it.target} ↔ ${it.contrast}", color = Y.Text1, bg = Y.Bg3) }
                }
            }
        }
    }
}

