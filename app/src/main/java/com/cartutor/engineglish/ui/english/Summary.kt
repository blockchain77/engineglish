package com.cartutor.engineglish.ui.english

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cartutor.engineglish.english.core.ProgressEvent
import com.cartutor.engineglish.english.core.SessionSummaryUi
import com.cartutor.engineglish.ui.theme.Y
import com.cartutor.engineglish.ui.theme.YType

/*
 * 세션 요약 시트 (정차 화면 위). 운전 집중으로 쉰 문제는 틀린 걸로 치지 않았다는 걸 분명히 알려 준다.
 */

@Composable
internal fun SummarySheet(summary: SessionSummaryUi, onClose: () -> Unit, onReport: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Y.Dim)
            .clickable(interactionSource = null, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Panel(
            Modifier
                .padding(28.dp)
                .widthIn(max = 1120.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            accent = Y.Correct,
            padding = 36.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Flag, Y.Correct, size = 76.dp)
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(summary.titleKo, style = MaterialTheme.typography.displaySmall, color = Y.Text1, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${minutesKo(summary.minutes)} 동안 ${summary.answered}문장을 연습했어요",
                        style = MaterialTheme.typography.titleLarge,
                        color = Y.Text2,
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> StatTile("정답", "${summary.correct}", m, color = Y.Correct) },
                { m -> StatTile("거의 맞음", "${summary.partial}", m, color = Y.Retry) },
                { m -> StatTile("새 표현", "${summary.newLearned}", m) },
                { m -> StatTile("복습", "${summary.reviewed}", m) },
                { m -> StatTile("넘김", "${summary.skipped}", m, color = Y.Text2) },
                { m -> StatTile("XP", "+${summary.xpGained}", m, color = Y.Accent) },
            )
            CardGrid(cols = 6, cards = tiles, spacing = 12.dp)
            if (summary.interrupted > 0) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Visibility, contentDescription = null, tint = Y.LoadFocus, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "운전에 집중하느라 ${summary.interrupted}번 쉬었어요. 쉬는 동안 끊긴 문제는 틀린 걸로 치지 않았어요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Y.Text1,
                    )
                }
            }
            if (summary.events.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                ChipFlow {
                    summary.events.take(4).forEach { e ->
                        Chip(
                            eventKo(e),
                            color = when (e) {
                                is ProgressEvent.LevelUp -> Y.Accent
                                is ProgressEvent.CheckpointPassed, is ProgressEvent.UnitUnlocked -> Y.Correct
                                is ProgressEvent.StreakExtended -> Y.Retry
                                is ProgressEvent.DailyGoalReached -> Y.LoadRelaxed
                            },
                            large = true,
                        )
                    }
                }
            }
            summary.bestSentence?.let { best ->
                Spacer(Modifier.height(24.dp))
                Eyebrow("오늘의 한 문장")
                Spacer(Modifier.height(8.dp))
                Text(best, style = YType.English, color = Y.Accent)
            }
            if (summary.pronTips.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Eyebrow("발음 한 가지")
                summary.pronTips.take(2).forEach { t ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Y.Retry, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("${t.type.ko} · ‘${t.expectedWord}’", style = MaterialTheme.typography.titleMedium, color = Y.Text1, fontWeight = FontWeight.SemiBold)
                            if (t.tipKo.isNotBlank()) Text(t.tipKo, style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
                        }
                    }
                }
            }
            summary.fluency?.let { f ->
                if (f.samples > 0) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        if (f.avgWpm > 0) "평균 ${secondsKo(f.avgLatencyMs)} 만에 답을 시작했고, 1분에 ${f.avgWpm}단어를 말했어요"
                        else "평균 ${secondsKo(f.avgLatencyMs)} 만에 답을 시작했어요",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Y.Text2,
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton("리포트 보기", onReport, Modifier.weight(1f), icon = Icons.Rounded.Insights, tone = Tone.SECONDARY)
                BigButton("확인", onClose, Modifier.weight(1f), icon = Icons.Rounded.Check)
            }
        }
    }
}
