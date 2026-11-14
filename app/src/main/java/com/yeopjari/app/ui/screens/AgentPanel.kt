package com.yeopjari.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.core.model.AgentCard
import com.yeopjari.app.core.model.AgentPhase
import com.yeopjari.app.core.model.AgentTurn
import com.yeopjari.app.core.model.PlaceKind
import com.yeopjari.app.core.model.Speaker
import com.yeopjari.app.core.model.ToolStatus
import com.yeopjari.app.core.model.WarningLevel
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.AiOrb
import com.yeopjari.app.ui.components.BigButton
import com.yeopjari.app.ui.components.Dot
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.SuggestionChip
import com.yeopjari.app.ui.components.TierBadge
import com.yeopjari.app.ui.components.rememberPhaseLabel
import com.yeopjari.app.ui.theme.Y

@Composable
fun AgentPanel(store: YeopjariStore, layout: Layout) {
    val turns by store.turns.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    val detail by store.phaseDetail.collectAsStateWithLifecycle()
    val partial by store.partialTranscript.collectAsStateWithLifecycle()
    val ctx by store.context.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

    Column(
        Modifier.fillMaxSize().background(Y.Bg1)
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(112.dp).clip(CircleShape).clickable { if (phase == AgentPhase.LISTENING) store.stopVoice() else store.startVoice() }, contentAlignment = Alignment.Center) {
                AiOrb(phase, 112.dp, still = layout.restricted)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(rememberPhaseLabel(phase), style = MaterialTheme.typography.headlineMedium, color = Y.Text1)
                Text(
                    when {
                        partial.isNotBlank() -> "“$partial”"
                        detail != null -> detail!!
                        else -> "차량 상태 · 경로 · 동승자를 함께 고려해요"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = if (detail != null) Y.Periwinkle else Y.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Y.Bg3).clickable { store.closeAgent() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "닫기", tint = Y.Text1, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val shown = if (layout.restricted) turns.takeLast(3) else turns
            items(shown, key = { it.id }) { t -> TurnView(t, store, layout) }
        }

        Spacer(Modifier.height(14.dp))
        // Suggestions (context aware)
        val suggestions = buildList {
            if (ctx.vehicle.driveMode.restricted) {
                add("졸려"); add("다음 휴게소 들렀다 가자"); add("도착하면 배터리 몇 % 남아?"); add("유튜브 틀어줘")
            } else {
                add("아이들이랑 강릉 가는데 충전이랑 쉬는 거 같이 계획 세워줘")
                add("타이어 경고등 왜 켜진 거야?")
                add("퇴근할 때 비 오면 집까지 예상 지연 알려주는 루틴 만들어줘")
                add("강아지 두고 잠깐 내릴게")
                add("오늘 브리핑 해줘")
                add("V2L 어떻게 써?")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            suggestions.forEach { s -> SuggestionChip(s, Modifier.widthIn(max = 420.dp)) { store.send(s) } }
        }
        Spacer(Modifier.height(14.dp))
        // Input row
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!layout.restricted) {
                var text by remember { mutableStateOf("") }
                Box(
                    Modifier.weight(1f).heightIn(min = 72.dp).clip(RoundedCornerShape(24.dp)).background(Y.Bg3).border(1.dp, Y.Stroke, RoundedCornerShape(24.dp)).padding(horizontal = 22.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text("정차 중에는 입력도 가능해요", style = MaterialTheme.typography.bodyLarge, color = Y.Text3)
                    BasicTextField(
                        text, { text = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Y.Text1), cursorBrush = SolidColor(Y.Cyan),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { store.send(text); text = "" }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(72.dp).clip(CircleShape).background(Y.Bg3).clickable { store.send(text); text = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "보내기", tint = Y.Text1, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(12.dp))
            } else Spacer(Modifier.weight(1f))
            BigButton(if (phase == AgentPhase.LISTENING) "듣는 중…" else "말하기", icon = Icons.Rounded.Mic, modifier = if (layout.restricted) Modifier.fillMaxWidth() else Modifier) {
                if (phase == AgentPhase.LISTENING) store.stopVoice() else store.startVoice()
            }
        }
    }
}

@Composable
private fun TurnView(t: AgentTurn, store: YeopjariStore, layout: Layout) {
    when (t.speaker) {
        Speaker.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                t.text, style = MaterialTheme.typography.bodyLarge, color = Y.Bg0,
                modifier = Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp)).background(Y.Cyan).padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
        Speaker.SYSTEM -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Pill(t.text, color = if (t.text.startsWith("Gleo")) Y.Violet else Y.Ev, bg = (if (t.text.startsWith("Gleo")) Y.Violet else Y.Ev).copy(alpha = 0.12f))
        }
        Speaker.AGENT -> AgentBubble(t, store, layout)
    }
}

@Composable
private fun AgentBubble(t: AgentTurn, store: YeopjariStore, layout: Layout) {
    var showReason by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp, 26.dp, 26.dp, 26.dp)).background(Y.Bg2).border(1.dp, Y.StrokeSoft, RoundedCornerShape(6.dp, 26.dp, 26.dp, 26.dp))
            .padding(20.dp).animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            t.tier?.let { TierBadge(it, t.latencyMs) }
            Spacer(Modifier.weight(1f))
            if (t.reasoning.isNotEmpty() && !layout.restricted) {
                Row(
                    Modifier.clip(CircleShape).clickable { showReason = !showReason }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Psychology, null, tint = Y.Text3, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (showReason) "추론 접기" else "추론 ${t.reasoning.size}단계", style = MaterialTheme.typography.labelMedium, color = Y.Text3)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(t.text, style = MaterialTheme.typography.bodyLarge, color = Y.Text1, maxLines = if (layout.restricted) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)

        AnimatedVisibility(showReason) {
            Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Y.Bg1).padding(16.dp)) {
                t.reasoning.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Y.Periwinkle, modifier = Modifier.width(26.dp))
                        Text(s, style = MaterialTheme.typography.bodySmall, color = Y.Text2)
                    }
                }
            }
        }
        if (t.toolCalls.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                t.toolCalls.forEach { c ->
                    val (icon, color) = when (c.status) {
                        ToolStatus.DONE -> Icons.Rounded.CheckCircle to Y.Ev
                        ToolStatus.PENDING_CONFIRM -> Icons.Rounded.Schedule to Y.Amber
                        ToolStatus.BLOCKED -> Icons.Rounded.Block to Y.Red
                        ToolStatus.RUNNING -> Icons.Rounded.RadioButtonUnchecked to Y.Blue
                    }
                    Pill("${c.name}${if (c.resultSummary.isNotBlank()) " · ${c.resultSummary}" else ""}", color = color, bg = color.copy(alpha = 0.10f), icon = icon)
                }
            }
        }
        t.cards.filter { !layout.restricted || it is AgentCard.Confirm || it is AgentCard.Game }.forEach { card ->
            Spacer(Modifier.height(14.dp))
            CardView(card, store, layout)
        }
        if (t.privacyNote != null && !layout.restricted) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, null, tint = Y.Text3, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.privacyNote, style = MaterialTheme.typography.labelSmall, color = Y.Text3)
            }
        }
    }
}

@Composable
private fun CardView(card: AgentCard, store: YeopjariStore, layout: Layout) {
    val inner = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Y.Bg3).padding(18.dp)
    when (card) {
        is AgentCard.RoutePlan -> Column(inner) {
            Text(card.title, style = MaterialTheme.typography.titleMedium, color = Y.Text1)
            Text(card.note + (card.arrivalSoc?.let { " · 도착 SOC $it%" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Y.Text2)
            Spacer(Modifier.height(12.dp))
            card.stops.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(30.dp)) {
                        Dot(when (s.kind) { PlaceKind.CHARGER -> Y.Ev; PlaceKind.REST_AREA -> Y.Kids; else -> Y.Cyan }, 14.dp)
                        if (i < card.stops.lastIndex) Box(Modifier.width(2.dp).height(44.dp).background(Y.Stroke))
                    }
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(s.label, style = MaterialTheme.typography.titleSmall, color = Y.Text1, modifier = Modifier.weight(1f))
                            Text(s.etaClock, style = MaterialTheme.typography.labelLarge, color = Y.Text2)
                        }
                        if (s.detail.isNotBlank()) Text(s.detail, style = MaterialTheme.typography.bodySmall, color = Y.Text3, maxLines = 2)
                    }
                }
            }
        }
        is AgentCard.ChargerOptions -> Column(inner, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            card.chargers.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.place.name, style = MaterialTheme.typography.titleSmall, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                            if (c.place.id == card.recommendedId) { Spacer(Modifier.width(8.dp)); Pill("추천", color = Y.Ev, bg = Y.Ev.copy(alpha = 0.12f), icon = Icons.Rounded.Star) }
                        }
                        Text("${c.maxKw}kW · ${c.available}/${c.total}대 · ${c.memberPricePerKwh}원/kWh · 신뢰도 ${c.reliability}", style = MaterialTheme.typography.bodySmall, color = Y.Text2)
                    }
                    Text("${c.kmFromStart.toInt()}km", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
                }
            }
        }
        is AgentCard.VehicleCheck -> Column(inner, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.items.forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (i.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (i.ok) Y.Ev else Y.Amber, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(i.label, style = MaterialTheme.typography.bodyMedium, color = Y.Text2, modifier = Modifier.weight(1f))
                    Text(i.value, style = MaterialTheme.typography.titleSmall, color = Y.Text1)
                }
            }
        }
        is AgentCard.Diagnosis -> Column(inner) {
            val c = when (card.severity) { WarningLevel.INFO -> Y.Blue; WarningLevel.CAUTION -> Y.Amber; WarningLevel.CRITICAL -> Y.Red }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, tint = c, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(card.title, style = MaterialTheme.typography.titleMedium, color = Y.Text1)
            }
            Spacer(Modifier.height(6.dp))
            Text("근거: " + card.explanation, style = MaterialTheme.typography.bodySmall, color = Y.Text2)
            Spacer(Modifier.height(10.dp))
            card.actions.forEachIndexed { i, a -> Text("${i + 1}. $a", style = MaterialTheme.typography.bodyMedium, color = Y.Text1, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        is AgentCard.RoutineDraft -> Column(inner) {
            Text("루틴 초안", style = MaterialTheme.typography.labelLarge, color = Y.Cyan)
            Spacer(Modifier.height(8.dp))
            Text("조건  " + card.routine.triggers.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Y.Text1)
            Text("동작  " + card.routine.actions.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Y.Text1)
        }
        is AgentCard.Briefing -> Column(inner, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.lines.forEach { (k, v) ->
                Row {
                    Text(k, style = MaterialTheme.typography.labelLarge, color = Y.Text3, modifier = Modifier.width(72.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, color = Y.Text1)
                }
            }
        }
        is AgentCard.Confirm -> Column(inner) {
            Text(card.question, style = MaterialTheme.typography.titleMedium, color = Y.Text1)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(card.confirmLabel, Modifier.weight(1f)) { store.confirm(card.actionId) }
                BigButton("괜찮아요", Modifier.weight(1f), primary = false) { store.decline() }
            }
        }
        is AgentCard.Game -> {
            val kids by store.kids.collectAsStateWithLifecycle()
            Column(inner) {
                Text(card.title, style = MaterialTheme.typography.labelLarge, color = Y.Kids)
                Text(card.prompt, style = MaterialTheme.typography.titleLarge, color = Y.Text1)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    card.choices.forEach { c -> BigButton(c, Modifier.weight(1f), primary = false, color = Y.Kids) { store.answerQuiz(c) } }
                }
                kids.lastAnswerCorrect?.let { Text(if (it) "정답! 점수 ${kids.score}" else "다시 해 볼까요?", style = MaterialTheme.typography.titleMedium, color = if (it) Y.Ev else Y.Amber, modifier = Modifier.padding(top = 10.dp)) }
                Text("※ 아이 화면/음성 전용 · 운전자는 음성만 듣습니다", style = MaterialTheme.typography.labelSmall, color = Y.Text3, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
