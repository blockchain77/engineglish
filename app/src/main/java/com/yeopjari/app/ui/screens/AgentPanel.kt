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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.ai.context.DriveContext
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

/** 지금 누를 수 있는 선택지 카드 — 진행 중인 되묻기와 같은 보정 결과의 가장 최근 카드 하나 */
private data class ActiveCard(val turnId: Long, val card: AgentCard.PlaceChoices)

private fun activeCardOf(turns: List<AgentTurn>, pendingResolutionId: Long?): ActiveCard? {
    pendingResolutionId ?: return null
    for (t in turns.asReversed()) {
        val card = t.cards.filterIsInstance<AgentCard.PlaceChoices>().lastOrNull { it.resolutionId == pendingResolutionId }
        if (card != null) return ActiveCard(t.id, card)
    }
    return null
}

/** 주행 중 추천 — 스크롤 없이 3개만 */
private fun drivingSuggestions(ctx: DriveContext): List<String> = listOf(
    "서심다응 가자",
    if (ctx.route.isHighway) "다음 휴게소 들렀다 가자" else "빵집 들렀다 가자",
    when {
        ctx.fatigue.level > 0 -> "졸려"
        ctx.route.active -> "도착하면 배터리 몇 % 남아?"
        else -> "유튜브 틀어줘"
    },
)

@Composable
fun AgentPanel(store: YeopjariStore, layout: Layout) {
    val turns by store.turns.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    val detail by store.phaseDetail.collectAsStateWithLifecycle()
    val partial by store.partialTranscript.collectAsStateWithLifecycle()
    val ctx by store.context.collectAsStateWithLifecycle()
    val clarify by store.clarify.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 진행 중인 되묻기의 카드만 누를 수 있다 (지난 질문의 카드를 눌러 다시 학습·안내되지 않게)
    val active = activeCardOf(turns, clarify?.res?.id)

    LaunchedEffect(turns.size, layout.restricted) { if (!layout.restricted && turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

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
            Box(Modifier.size(76.dp).clip(CircleShape).background(Y.Bg3).clickable { store.closeAgent() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "닫기", tint = Y.Text1, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        if (layout.restricted) {
            // 주행 중: 스크롤 목록 없이 최근 대화 두 개(넘치면 위쪽이 잘림) + 지금 답할 선택지 카드 하나(최대 3곳 · 76dp)
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomStart).wrapContentHeight(Alignment.Bottom, unbounded = true),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    turns.takeLast(2).forEach { t -> TurnView(t, store, layout, active = null) }
                }
            }
            active?.let { a ->
                Spacer(Modifier.height(14.dp))
                CardView(a.card, store, layout, placeActive = true)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(turns, key = { it.id }) { t -> TurnView(t, store, layout, active) }
            }
        }

        Spacer(Modifier.height(14.dp))
        // Suggestions (context aware)
        if (layout.restricted) {
            // 주행 중: 되묻는 중이면 선택지 카드만 (선택지는 최대 3개), 아니면 스크롤 없는 추천 3개
            if (clarify == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    drivingSuggestions(ctx).forEach { s -> SuggestionChip(s, Modifier.weight(1f).heightIn(min = 76.dp)) { store.send(s) } }
                }
                Spacer(Modifier.height(14.dp))
            }
        } else {
            val suggestions = listOf(
                "서심다응 가자", "스타벅스 가자", "엄마 집 가자",
                "아이들이랑 강릉 가는데 충전이랑 쉬는 거 같이 계획 세워줘",
                "타이어 경고등 왜 켜진 거야?",
                "퇴근할 때 비 오면 집까지 예상 지연 알려주는 루틴 만들어줘",
                "강아지 두고 잠깐 내릴게",
                "오늘 브리핑 해줘",
                "V2L 어떻게 써?",
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestions.forEach { s -> SuggestionChip(s, Modifier.widthIn(max = 420.dp)) { store.send(s) } }
            }
            Spacer(Modifier.height(14.dp))
        }
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
private fun TurnView(t: AgentTurn, store: YeopjariStore, layout: Layout, active: ActiveCard?) {
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
        Speaker.AGENT -> AgentBubble(t, store, layout, active)
    }
}

@Composable
private fun AgentBubble(t: AgentTurn, store: YeopjariStore, layout: Layout, active: ActiveCard?) {
    var showReason by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp, 26.dp, 26.dp, 26.dp)).background(Y.Bg2).border(1.dp, Y.StrokeSoft, RoundedCornerShape(6.dp, 26.dp, 26.dp, 26.dp))
            .padding(20.dp).then(if (layout.restricted) Modifier else Modifier.animateContentSize()),
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
            // 주행 중에는 스크롤되는 줄 없이 첫 결과 하나만
            val calls = if (layout.restricted) t.toolCalls.take(1) else t.toolCalls
            Row(if (layout.restricted) Modifier else Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                calls.forEach { c ->
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
        // 주행 중 선택지 카드는 대화 밖에 진행 중인 것 하나만 따로 그린다
        t.cards.filter { !layout.restricted || it is AgentCard.Confirm || it is AgentCard.Game }.forEach { card ->
            Spacer(Modifier.height(14.dp))
            val placeActive = card is AgentCard.PlaceChoices && active != null && active.turnId == t.id && active.card.resolutionId == card.resolutionId
            CardView(card, store, layout, placeActive = placeActive)
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

/** @param placeActive 선택지 카드가 지금 진행 중인 되묻기의 것인지 — 아니면 흐리게, 누를 수 없게 */
@Composable
private fun CardView(card: AgentCard, store: YeopjariStore, layout: Layout, placeActive: Boolean = false) {
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
        // 번호는 카드에 보이는 순서 그대로 — "2번"도 이 목록 기준 (선택 단계 카드는 HUD와 같은 목록)
        is AgentCard.PlaceChoices -> Column(inner.alpha(if (placeActive) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("들린 말 ‘${card.heard}’", style = MaterialTheme.typography.titleSmall, color = Y.Text2, modifier = Modifier.weight(1f, false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                card.corrected?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("→ $it", style = MaterialTheme.typography.titleSmall, color = Y.Cyan, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Pill(card.stageLabel, color = Y.Periwinkle, bg = Y.Periwinkle.copy(alpha = 0.12f))
            }
            card.options.take(3).forEachIndexed { i, o ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(18.dp)).background(Y.Bg2)
                        .then(if (placeActive) Modifier.clickable { store.pickPlace(o.placeId, card.resolutionId) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val lead = i == 0 && placeActive
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (lead) Y.Cyan else Y.Bg4), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = if (lead) Y.Bg0 else Y.Text1)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.name, style = MaterialTheme.typography.titleMedium, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!layout.restricted) Text(o.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Y.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${o.percent}%", style = MaterialTheme.typography.titleMedium, color = if (lead) Y.Cyan else Y.Text2)
                }
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
