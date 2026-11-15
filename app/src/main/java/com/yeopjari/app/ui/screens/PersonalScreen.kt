package com.yeopjari.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Work
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.Clarify
import com.yeopjari.app.ClarifyStage
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.ai.personal.Candidate
import com.yeopjari.app.ai.personal.LearnOutcome
import com.yeopjari.app.ai.personal.LedgerEntry
import com.yeopjari.app.ai.personal.PersonalBrain
import com.yeopjari.app.ai.personal.Resolution
import com.yeopjari.app.ai.personal.StopMode
import com.yeopjari.app.ai.personal.Tier
import com.yeopjari.app.core.model.AgentPhase
import com.yeopjari.app.sim.ScenarioId
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.BigButton
import com.yeopjari.app.ui.components.GlassCard
import com.yeopjari.app.ui.components.GradientText
import com.yeopjari.app.ui.components.HudView
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.SectionTitle
import com.yeopjari.app.ui.components.SuggestionChip
import com.yeopjari.app.ui.components.hudDistanceLabel
import com.yeopjari.app.ui.components.isHudDistanceTag
import com.yeopjari.app.ui.theme.Y
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SUBTITLE = "잘못 들려도 내 기록으로 바로잡고, 헷갈릴 때만 HUD로 물어봐요"
private const val FORMULA = "점수 = Σ 가중치 × 특징 → 확률(softmax)"
private val TRY_PHRASES = listOf("서심다응 가자", "스타벅스 가자", "빵집 들렀다 가자", "엄마 집 가자", "현대백화점 가자")

/** 가중치 막대의 끝 (PersonalBrain.learn 이 9.0으로 제한한다) */
private const val W_MAX = 9.0

/** 판단 근거 패널에서 후보 목록과 특징 분해를 나란히 둘 최소 폭 — 좁으면 위아래로 */
private val SIDE_BY_SIDE_MIN = 620.dp

private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

private class PersonalScene(val id: ScenarioId, val label: String, val icon: ImageVector)

private val SCENES by lazy {
    listOf(
        PersonalScene(ScenarioId.P_DAEJEON, "토요일 · 대전 가는 길", Icons.Rounded.BakeryDining),
        PersonalScene(ScenarioId.P_WEEKDAY, "평일 아침 · 판교", Icons.Rounded.Work),
        PersonalScene(ScenarioId.P_WEEKEND, "토요일 오전 · 잠실", Icons.Rounded.LocalCafe),
    )
}

/**
 * 나를 아는 옆자리 — 음성인식이 잘못 들은 장소 이름을 이 운전자의 기록으로 바로잡고,
 * 확신이 낮을 때만 HUD로 되묻는 개인화 보정의 대표 화면.
 *
 *  - 주행 중: 앞유리 HUD 시뮬레이터 + 큰 버튼만 (긴 글 · 스크롤 · 4개 이상 선택지 없음)
 *  - 정차 중 · 넓은 화면: HUD + 판단 근거 · 차 안 학습 · 개인정보 흐름을 한 화면에
 *  - 좁은 화면: 같은 내용을 세로로
 */
@Composable
fun PersonalScreen(store: YeopjariStore, layout: Layout) {
    val lastClarify = rememberLastClarify(store)
    // 주행 화면 여부는 셸이 앱 전체에 하나로 정한 값(제한 중 + 정차 뒤 5초 유지)을 그대로 쓴다 —
    // 떠 있는 오브 · 자막 · 패널 열기가 같은 값을 보므로, 주행 HUD가 떠 있는 동안 오브는 늘 숨어 있다.
    // (화면 조건이 붙은 layout.drivingHud 대신 layout.driving: 다른 화면으로 넘어가며 흐려지는 동안 정차 화면으로 바뀌어 번쩍이지 않게)
    when {
        layout.driving -> DrivingHud(store, layout)
        layout.expanded -> ParkedWide(store, lastClarify)
        else -> ParkedCompact(store, lastClarify)
    }
}

/**
 * 마지막으로 진행된 되묻기 — 대화가 끝난 뒤에도 판단 근거 패널이 HUD와 같은 번호 · '거절함'을 보여 줄 수 있게.
 */
@Composable
private fun rememberLastClarify(store: YeopjariStore): Clarify? {
    var last by remember(store) { mutableStateOf(store.clarify.value) }
    LaunchedEffect(store) {
        store.clarify.collect { if (it != null) last = it }
    }
    return last
}

// ───────────────────────── A. 주행 중 ─────────────────────────

@Composable
private fun DrivingHud(store: YeopjariStore, layout: Layout) {
    val clarify by store.clarify.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    val side = if (layout.expanded) 24.dp else 16.dp
    Column(
        // 아래 여백은 넓이와 상관없이 22dp — 셸이 주행 HUD가 떠 있는 동안(정차 뒤 5초 유지 포함) 떠 있는 오브를
        // 숨기므로 오브 자리(124dp)를 비워 둘 필요가 없다. 그만큼 HUD가 커진다
        Modifier.fillMaxSize().padding(start = side, end = side, top = 4.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        HudView(store, Modifier.weight(1f).fillMaxWidth(), compact = !layout.expanded, driving = true)
        DrivingActions(store, clarify, phase == AgentPhase.LISTENING, layout.expanded)
    }
}

/**
 * 주행 중 버튼 — 모두 76dp 이상, 선택지는 최대 3개.
 * 되묻는 중에도 마이크(76×76dp)를 늘 같이 둔다 — 화면을 내려다보지 않고 말로 대답할 수 있게.
 */
@Composable
private fun DrivingActions(store: YeopjariStore, c: Clarify?, listening: Boolean, wide: Boolean) {
    val toggleMic: () -> Unit = { if (listening) store.stopVoice() else store.startVoice(openPanel = false) }
    if (c == null) {
        BigButton(if (listening) "듣는 중…" else "말하기", Modifier.fillMaxWidth(), icon = Icons.Rounded.Mic, onClick = toggleMic)
        return
    }
    when (c.stage) {
        ClarifyStage.CONFIRM -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val waypoint = c.res.query.mode == StopMode.WAYPOINT
            BigButton(if (waypoint) "응, 들르자" else "응, 가자", Modifier.weight(1f), icon = Icons.Rounded.Check) { store.send("응") }
            BigButton("아니야", Modifier.weight(1f), icon = Icons.Rounded.Close, primary = false) { store.send("아니야") }
            MicButton(listening, toggleMic)
        }

        ClarifyStage.CHOOSE -> {
            val opts = c.options.take(3)
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    opts.forEachIndexed { i, o -> ChoiceButton(store, c, i, o, Modifier.weight(1f)) }
                    MicButton(listening, toggleMic)
                }
            } else {
                // 선택지 세 줄 옆에 마이크 — 높이를 더 쓰지 않는다
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        opts.forEachIndexed { i, o -> ChoiceButton(store, c, i, o, Modifier.fillMaxWidth()) }
                    }
                    MicButton(listening, toggleMic)
                }
            }
        }

        ClarifyStage.UNDO -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton("아니야, 다른 곳", Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.Undo, primary = false) {
                store.send("아니야")
            }
            MicButton(listening, toggleMic)
        }
    }
}

/** HUD와 같은 번호의 선택 버튼 — 지금 되묻기의 결과(id)에 묶어, 지난 화면의 늦은 터치가 다른 결과로 학습되지 않게 */
@Composable
private fun ChoiceButton(store: YeopjariStore, c: Clarify, i: Int, o: Candidate, modifier: Modifier) {
    BigButton("${i + 1} · ${o.poi.spoken}", modifier, primary = i == 0) {
        store.pickPlace(o.poi.place.id, resolutionId = c.res.id)
    }
}

/** 76×76dp 마이크 — 깜빡임·맥동 없이 듣는 중인지만 색으로 */
@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .size(76.dp)
            .clip(shape)
            .background(if (listening) Y.Cyan else Y.Bg3)
            .border(1.dp, if (listening) Y.Cyan else Y.Stroke, shape)
            .clickable(onClickLabel = if (listening) "듣기 멈추기" else "말로 대답하기", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Mic,
            contentDescription = if (listening) "듣는 중" else "말로 대답하기",
            tint = if (listening) Y.Bg0 else Y.Text1,
            modifier = Modifier.size(32.dp),
        )
    }
}

// ───────────────────────── B. 정차 중 · 넓은 화면 ─────────────────────────

@Composable
private fun ParkedWide(store: YeopjariStore, lastClarify: Clarify?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hudHeight = (maxHeight * 0.4f).coerceIn(320.dp, 470.dp)
        Column(
            Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 2.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(store, wide = true)
            HudView(store, Modifier.fillMaxWidth().height(hudHeight))
            TryRow(store)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // 판단 근거(후보 이름 · 확률)가 이 화면의 주인공 — 폭을 더 준다
                WhyPanel(store, Modifier.weight(1.5f).fillMaxHeight(), scroll = true, lastClarify = lastClarify)
                LearnPanel(store, Modifier.weight(1f).fillMaxHeight(), scroll = true)
                PrivacyPanel(store, Modifier.weight(1f).fillMaxHeight(), scroll = true)
            }
        }
    }
}

// ───────────────────────── C. 좁은 화면 ─────────────────────────

@Composable
private fun ParkedCompact(store: YeopjariStore, lastClarify: Clarify?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header(store, wide = false)
        HudView(store, Modifier.fillMaxWidth().height(320.dp), compact = true)
        TryRow(store)
        WhyPanel(store, Modifier.fillMaxWidth(), scroll = false, lastClarify = lastClarify)
        LearnPanel(store, Modifier.fillMaxWidth(), scroll = false)
        PrivacyPanel(store, Modifier.fillMaxWidth(), scroll = false)
    }
}

// ───────────────────────── 머리말 · 상황 · 말해 보기 ─────────────────────────

@Composable
private fun Header(store: YeopjariStore, wide: Boolean) {
    val active by store.sim.scenario.collectAsStateWithLifecycle()
    val titles: @Composable () -> Unit = {
        Column {
            GradientText("나를 아는 옆자리", MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(2.dp))
            Text(SUBTITLE, style = MaterialTheme.typography.bodySmall, color = Y.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { titles() }
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SCENES.forEach { s -> SceneChip(s, active == s.id) { store.playScenario(s.id) } }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            titles()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SCENES.forEach { s -> SceneChip(s, active == s.id) { store.playScenario(s.id) } }
            }
        }
    }
}

@Composable
private fun SceneChip(s: PersonalScene, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(if (active) Y.Cyan.copy(alpha = 0.14f) else Y.Bg3)
            .border(if (active) 1.5.dp else 1.dp, if (active) Y.Cyan else Y.Stroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(s.icon, null, tint = if (active) Y.Cyan else Y.Text3, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(s.label, style = MaterialTheme.typography.titleSmall, color = if (active) Y.Text1 else Y.Text2, maxLines = 1)
    }
}

/** 눌러서 말해 보기 — 되묻는 중이면 그 단계에 맞는 대답으로 바뀐다 */
@Composable
private fun TryRow(store: YeopjariStore) {
    val clarify by store.clarify.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    val c = clarify
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (c == null) "말해 보기" else "대답하기",
            style = MaterialTheme.typography.labelLarge,
            color = if (c == null) Y.Text3 else Y.Cyan,
        )
        Spacer(Modifier.width(14.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (c == null) {
                TRY_PHRASES.forEach { p -> SuggestionChip("“$p”") { store.send(p) } }
            } else {
                replyChips(c).forEach { r -> ReplyChip(r) { store.send(r) } }
            }
        }
        Spacer(Modifier.width(14.dp))
        val listening = phase == AgentPhase.LISTENING
        BigButton(if (listening) "듣는 중…" else "말하기", icon = Icons.Rounded.Mic) {
            if (listening) store.stopVoice() else store.startVoice(openPanel = false)
        }
    }
}

@Composable
private fun ReplyChip(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .heightIn(min = 60.dp)
            .clip(shape)
            .background(Y.Cyan.copy(alpha = 0.10f))
            .border(1.5.dp, Y.Cyan.copy(alpha = 0.7f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleSmall, color = Y.Text1, maxLines = 1) }
}

/** 되묻기 단계별 대답 예 — 확인: 응/아니야, 선택: 번호 · 지점 이름, 되돌리기: 아니야 */
private fun replyChips(c: Clarify): List<String> = when (c.stage) {
    ClarifyStage.CONFIRM -> buildList {
        add("응")
        add("아니야")
        c.res.candidates.drop(1).firstNotNullOfOrNull(::branchOf)?.let { add("아니, $it") }
    }
    ClarifyStage.CHOOSE -> buildList {
        c.options.take(3).indices.forEach { add("${it + 1}번") }
        c.options.take(3).mapNotNull(::branchOf).distinct().take(2).forEach { add(it) }
        add("아니야")
    }
    ClarifyStage.UNDO -> listOf("아니야", "응, 맞아")
}

/** 브랜드 뒤의 지점 이름 — "성심당 본점" → "본점" (이름 일부로 고르는 대답) */
private fun branchOf(c: Candidate): String? {
    val brand = c.poi.brand ?: return null
    val name = c.poi.spoken
    if (!name.startsWith(brand)) return null
    return name.removePrefix(brand).trim().takeIf { it.isNotEmpty() && it.length <= 8 }
}

// ───────────────────────── 패널 공통 ─────────────────────────

@Composable
private fun Panel(
    title: String,
    modifier: Modifier,
    scroll: Boolean,
    accent: Color? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val sc = rememberScrollState()
    GlassCard(modifier, accent = accent, padding = 22.dp) {
        SectionTitle(title, trailing = trailing)
        Column(
            if (scroll) Modifier.weight(1f).fillMaxWidth().verticalScroll(sc) else Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

// ───────────────────────── (1) AI가 이렇게 골랐어요 ─────────────────────────

/** 판단 근거 패널의 후보 한 줄 */
private class WhyRow(
    val cand: Candidate,
    /** HUD와 같은 번호 — 되묻기 선택지가 아니었거나 거절한 후보는 번호 없음 */
    val number: Int?,
    /** 지금 HUD에 떠 있는 선택지 */
    val onHud: Boolean,
    val rejected: Boolean,
    /** 대화가 끝나고 실제로 고른 곳 */
    val chosen: Boolean,
    /** 강조할 줄 — 고른 곳, 없으면 HUD 첫 선택지, 없으면 1순위(거절하지 않았다면) */
    val lead: Boolean,
)

/**
 * 후보 줄 만들기. 번호는 HUD와 같은 출처(되묻기 선택지)에서 매긴다 —
 * 패널의 '2번'과 HUD의 '2번'이 다른 곳을 가리키면 운전자가 엉뚱한 곳을 고르게 된다.
 * 되묻기 없이 끝난 결과만 확률 순위로 번호를 매긴다.
 */
private fun whyRows(res: Resolution, pending: Clarify?, shown: Clarify?, learned: LearnOutcome?): List<WhyRow> {
    val optionIds = shown?.options?.map { it.poi.place.id }
    val rejectedId = shown?.rejectedId
    // LearnOutcome.chosen 은 학습한 결과의 후보 객체 그대로다 — 같은 객체일 때만 이 결과의 선택.
    // (대화용 사본 res.copy(tier = CONFIRM)도 후보 객체를 함께 쓰므로 같은 결과는 객체로 충분하다.
    //  장소·특징이 같다고 받아 주면, 같은 분 안에 다시 말했다가 취소한 결과까지 '선택함'으로 보인다)
    val chosenId = if (pending == null) {
        learned?.chosen?.let { ch -> res.candidates.firstOrNull { it === ch }?.poi?.place?.id }
    } else {
        null
    }
    val rows = buildList {
        addAll(res.candidates.take(4))
        res.candidates.drop(4)
            .filter { it.poi.place.id == chosenId || optionIds?.contains(it.poi.place.id) == true }
            .forEach { add(it) }
    }
    val leadId = chosenId
        ?: pending?.options?.firstOrNull()?.poi?.place?.id
        ?: res.candidates.firstOrNull()?.poi?.place?.id?.takeIf { it != rejectedId }
    return rows.map { cand ->
        val id = cand.poi.place.id
        val rejected = id == rejectedId
        val number = when {
            rejected -> null
            optionIds != null -> optionIds.indexOf(id).takeIf { it >= 0 }?.plus(1)
            else -> res.candidates.indexOf(cand) + 1
        }
        WhyRow(
            cand = cand,
            number = number,
            onHud = pending != null && optionIds?.contains(id) == true,
            rejected = rejected,
            chosen = id == chosenId,
            lead = id == leadId && !rejected,
        )
    }
}

/** 보이는 후보가 모두 같은 브랜드면 그 브랜드 (줄에는 지점 이름만) */
private fun sharedBrand(rows: List<WhyRow>): String? {
    if (rows.size < 2) return null
    val b = rows.first().cand.poi.brand ?: return null
    return b.takeIf {
        rows.all { r -> r.cand.poi.brand == b && r.cand.poi.spoken.startsWith(b) && r.cand.poi.spoken.length > b.length }
    }
}

@Composable
private fun WhyPanel(store: YeopjariStore, modifier: Modifier, scroll: Boolean, lastClarify: Clarify?) {
    val clarify by store.clarify.collectAsStateWithLifecycle()
    val ui by store.personalUi.collectAsStateWithLifecycle()
    val c = clarify
    // 대화가 끝나도 그 대화가 실제로 쓴 결과를 그대로 보여 준다 — 엔진 원본(ui.lastResolution)은 대화 정책으로
    // 바뀌기 전 값이라(예: 경유지 '바로 실행' → '한 번 확인' 사본, 같은 id) 답하자마자 단계 표시가 뒤집힌다.
    // 같은 id일 때만 마지막 되묻기의 결과를 쓰고, 되묻기 없이 끝난 새 결과(못 찾음 등)는 엔진 원본을 쓴다.
    val res = c?.res ?: lastClarify?.res?.takeIf { it.id == ui.lastResolution?.id } ?: ui.lastResolution
    Panel(
        "AI가 이렇게 골랐어요", modifier, scroll,
        accent = if (res != null) Y.Cyan else null,
        trailing = { if (res != null) TierPill(res.tier) },
    ) {
        if (res == null) {
            WhyEmpty()
        } else {
            // 대화가 끝났으면 마지막 되묻기(같은 결과일 때만)로 번호 · 거절을 그대로 보여 준다
            val shown = c ?: lastClarify?.takeIf { it.res.id == res.id }
            val rows = whyRows(res, c, shown, ui.lastLearn)
            HeardSummary(res)
            Spacer(Modifier.height(14.dp))
            // 나란히는 넓을 때만 — 좁은 칸을 다시 나누면 이름이 '성심당 …'으로 잘린다
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= SIDE_BY_SIDE_MIN) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1.1f)) { CandidateList(rows) }
                        Column(Modifier.weight(1f)) { FeatureBreakdown(res, ui.weights) }
                    }
                } else {
                    Column {
                        CandidateList(rows)
                        Spacer(Modifier.height(18.dp))
                        FeatureBreakdown(res, ui.weights)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhyEmpty() {
    Text("아직 고른 장소가 없어요", style = MaterialTheme.typography.titleMedium, color = Y.Text1)
    Spacer(Modifier.height(8.dp))
    Text(
        "위의 ‘서심다응 가자’를 눌러 보세요. 음성인식이 ‘성심당’을 잘못 들어도 발음 · 방문 기록 · 시간대 · 거리를 함께 따져 바로잡아요.",
        style = MaterialTheme.typography.bodyMedium, color = Y.Text2,
    )
    Spacer(Modifier.height(14.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PersonalBrain.FEATURE_LABELS.forEachIndexed { i, label ->
            Pill(label, color = featureColor(i), bg = featureColor(i).copy(alpha = 0.12f))
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(FORMULA, style = MaterialTheme.typography.labelMedium, color = Y.Text3)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeardSummary(res: Resolution) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Hearing, null, tint = Y.Text3, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("들린 말", style = MaterialTheme.typography.labelLarge, color = Y.Text3)
        Spacer(Modifier.width(12.dp))
        Text(
            "‘${res.query.text}’", style = MaterialTheme.typography.titleLarge, color = Y.Text1,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        res.correctedTo?.let { fixed ->
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Y.Cyan, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(fixed, style = MaterialTheme.typography.titleLarge, color = Y.Cyan, maxLines = 1)
        }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(if (res.query.mode == StopMode.WAYPOINT) "경유지로" else "목적지로")
        res.query.category?.let { Pill("종류로 말함 · ${it.label}", color = Y.Periwinkle, bg = Y.Periwinkle.copy(alpha = 0.12f)) }
        if (res.aliasHit) {
            Pill("기억한 표현", color = Y.Ev, bg = Y.Ev.copy(alpha = 0.12f))
        } else if (res.correctedTo != null) {
            Pill("발음 보정", color = Y.Cyan, bg = Y.Cyan.copy(alpha = 0.12f))
        }
        if (res.query.habit) Pill("‘늘 가던’", color = Y.Violet, bg = Y.Violet.copy(alpha = 0.12f))
        if (res.query.nearby) Pill("‘근처’", color = Y.Blue, bg = Y.Blue.copy(alpha = 0.12f))
        Pill("후보 ${res.candidates.size}곳")
    }
}

@Composable
private fun CandidateList(rows: List<WhyRow>) {
    if (rows.isEmpty()) {
        Text("발음이 비슷한 장소를 찾지 못했어요", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
        return
    }
    val brand = sharedBrand(rows)
    if (brand != null) {
        Text("$brand · 지점 ${rows.size}곳", style = MaterialTheme.typography.labelLarge, color = Y.Text2, maxLines = 1)
        Spacer(Modifier.height(8.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { CandidateRow(it, brand) }
    }
}

@Composable
private fun CandidateRow(r: WhyRow, brand: String?) {
    val c = r.cand
    val color = when {
        r.rejected -> Y.Text3
        r.chosen -> Y.Ev
        r.lead -> Y.Cyan
        else -> Y.Periwinkle
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (r.lead) Y.Bg3 else Color.Transparent)
            // HUD에 떠 있는 선택지는 테두리로 (따로 'HUD' 글자를 붙이지 않는다)
            .then(if (r.onHud) Modifier.border(1.dp, Y.Cyan.copy(alpha = 0.45f), shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowBadge(r)
            Spacer(Modifier.width(10.dp))
            val name = if (brand != null) c.poi.spoken.removePrefix(brand).trim() else c.poi.spoken
            Text(
                name, style = MaterialTheme.typography.titleSmall,
                color = if (r.rejected) Y.Text3 else Y.Text1,
                textDecoration = if (r.rejected) TextDecoration.LineThrough else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            when {
                r.chosen -> {
                    Spacer(Modifier.width(8.dp))
                    MiniTag("선택함", Y.Ev)
                }
                r.rejected -> {
                    Spacer(Modifier.width(8.dp))
                    MiniTag("거절함", Y.Red)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(pct(c.prob), style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(7.dp))
        Bar(c.prob.toFloat(), color, Modifier.fillMaxWidth().height(8.dp))
        val tags = panelTags(c)
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Y.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 번호 칸 — HUD 번호가 있으면 숫자, 거절한 후보는 ✕, 선택지가 아니었던 후보는 빈 원 */
@Composable
private fun RowBadge(r: WhyRow) {
    val filled = r.lead && r.number != null
    val fill = when {
        filled -> if (r.chosen) Y.Ev else Y.Cyan
        r.number != null -> Y.Bg4
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(fill)
            .then(if (r.number == null) Modifier.border(1.dp, Y.Stroke, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            r.rejected -> Icon(Icons.Rounded.Close, null, tint = Y.Text3, modifier = Modifier.size(16.dp))
            r.number != null -> Text("${r.number}", style = MaterialTheme.typography.labelLarge, color = if (filled) Y.Bg0 else Y.Text1)
        }
    }
}

@Composable
private fun MiniTag(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.13f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 패널에 보이는 근거. 거리는 HUD와 같은 '지금 위치에서의 직선거리'로 다시 쓰고,
 * 경로 위 후보는 순위에 쓴 '경로에서 벗어난 거리'를 따로 밝힌다 (맨숫자 하나로 섞어 보이지 않게).
 */
private fun panelTags(c: Candidate): List<String> = buildList {
    val base = c.tags.filterNot(::isHudDistanceTag)
    addAll(base)
    if (c.onRoute) {
        if (base.none { "가는 길" in it }) add("가는 길")
        val off = hudDistanceLabel(c.km)
        if (off.isNotEmpty()) add("경로에서 $off")
    }
    val direct = hudDistanceLabel(c.kmDirect ?: c.km)
    if (direct.isNotEmpty()) add(if (c.onRoute) "직선 $direct" else direct)
}

/**
 * 1위 후보의 특징 6개 (0~1)와 가중치.
 * 곱하는 가중치는 '이 결과를 계산할 때 쓴 값'(res.weights) — 학습 뒤의 새 가중치로 곱하면
 * 화면의 확률과 맞지 않는다. 그 뒤에 배운 값은 옆에 흐리게 '→1.7'로.
 */
@Composable
private fun FeatureBreakdown(res: Resolution, current: List<Double>) {
    val top = res.top ?: return
    val n = PersonalBrain.N_FEATURES
    val used = res.weights.takeIf { it.size >= n } ?: current
    val learnedSince = (0 until n).any { i ->
        val w = used.getOrElse(i) { PersonalBrain.DEFAULT_WEIGHTS[i] }
        abs(current.getOrElse(i) { w } - w) >= 0.05
    }
    Text("1위 ‘${top.poi.spoken}’의 근거", style = MaterialTheme.typography.labelLarge, color = Y.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(6.dp))
    val x = top.f.vec()
    PersonalBrain.FEATURE_LABELS.forEachIndexed { i, label ->
        val w = used.getOrElse(i) { PersonalBrain.DEFAULT_WEIGHTS[i] }
        val now = current.getOrElse(i) { w }
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Y.Text2, maxLines = 1, modifier = Modifier.width(54.dp))
            Bar(x[i].toFloat(), featureColor(i), Modifier.weight(1f).height(8.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "%.2f × %.1f".format(x[i], w), style = MaterialTheme.typography.labelMedium, color = Y.Text3,
                textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(92.dp),
            )
            if (learnedSince) {
                val d = now - w
                Text(
                    if (abs(d) >= 0.05) "→%.1f".format(now) else "",
                    style = MaterialTheme.typography.labelMedium, color = deltaColor(d).copy(alpha = 0.75f),
                    textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(48.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(FORMULA, style = MaterialTheme.typography.labelMedium, color = Y.Periwinkle)
    if (learnedSince) {
        Spacer(Modifier.height(4.dp))
        Text("→ 뒤 숫자는 이번 선택으로 새로 배운 가중치 (다음 판단부터 써요)", style = MaterialTheme.typography.labelSmall, color = Y.Text3)
    }
}

// ───────────────────────── (2) 차 안에서 배운 것 ─────────────────────────

@Composable
private fun LearnPanel(store: YeopjariStore, modifier: Modifier, scroll: Boolean) {
    val ui by store.personalUi.collectAsStateWithLifecycle()
    Panel(
        "차 안에서 배운 것", modifier, scroll,
        accent = if (ui.learnCount > 0) Y.Ev else null,
        trailing = { Pill("학습 ${ui.learnCount}회", color = Y.Ev, bg = Y.Ev.copy(alpha = 0.12f)) },
    ) {
        Text("순위 가중치 6개 · 흐린 막대 = 직전 값", style = MaterialTheme.typography.labelMedium, color = Y.Text3)
        Spacer(Modifier.height(4.dp))
        PersonalBrain.FEATURE_LABELS.forEachIndexed { i, label ->
            val w = ui.weights.getOrElse(i) { PersonalBrain.DEFAULT_WEIGHTS[i] }
            WeightRow(label, w, ui.prevWeights.getOrElse(i) { w }, featureColor(i))
        }
        Spacer(Modifier.height(14.dp))
        Text("기억한 표현", style = MaterialTheme.typography.labelLarge, color = Y.Text2)
        Spacer(Modifier.height(6.dp))
        if (ui.aliases.isEmpty()) {
            Text("아직 없어요 · 잘못 들린 이름을 한 번 바로잡아 주면 기억해요", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
        } else {
            ui.aliases.takeLast(4).forEach { (heard, name) -> AliasRow(heard, name) }
        }
        Spacer(Modifier.height(14.dp))
        SmallButton("학습 초기화", Icons.Rounded.RestartAlt) { store.resetPersonalLearning() }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Psychology, null, tint = Y.Text3, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "명령 해석: 지금은 규칙 기반 · 온디바이스 소형 모델(Qwen3-0.6B + LoRA)로 교체 예정",
                style = MaterialTheme.typography.labelMedium, color = Y.Text3,
            )
        }
    }
}

@Composable
private fun WeightRow(label: String, w: Double, prev: Double, color: Color) {
    val d = w - prev
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Y.Text2, maxLines = 1, modifier = Modifier.width(54.dp))
        Bar((w / W_MAX).toFloat(), color, Modifier.weight(1f).height(10.dp), ghost = (prev / W_MAX).toFloat())
        Spacer(Modifier.width(10.dp))
        Text("%.2f".format(w), style = MaterialTheme.typography.labelLarge, color = Y.Text1, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        Text(
            deltaLabel(d), style = MaterialTheme.typography.labelMedium, color = deltaColor(d),
            textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(62.dp),
        )
    }
}

@Composable
private fun AliasRow(heard: String, name: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‘$heard’", style = MaterialTheme.typography.titleSmall, color = Y.Text1, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Y.Text3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = Y.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────── (3) 개인정보는 차 안에 ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacyPanel(store: YeopjariStore, modifier: Modifier, scroll: Boolean) {
    val ui by store.personalUi.collectAsStateWithLifecycle()
    Panel(
        "개인정보는 차 안에", modifier, scroll,
        trailing = { Pill("온디바이스", color = Y.Ev, bg = Y.Ev.copy(alpha = 0.12f), icon = Icons.Rounded.Lock) },
    ) {
        InfoLine(Icons.Rounded.History, if (ui.erased) "방문 기록 0건 · 사용자 요청으로 지움" else "방문 기록 ${ui.visitCount}건 · 장소 ${ui.placeCount}곳")
        InfoLine(Icons.Rounded.Lock, "AES-256-GCM · Android Keystore", trailing = if (ui.encrypted) "암호화 저장" else "키 저장소 없음", ok = ui.encrypted)
        InfoLine(Icons.Rounded.Storage, "저장 파일 ${bytes(ui.vaultBytes)} · 이 차에서만 열림")

        Spacer(Modifier.height(12.dp))
        Text("서버로 보내는 것 · 이것뿐", style = MaterialTheme.typography.labelLarge, color = Y.Ev)
        Spacer(Modifier.height(8.dp))
        val box = RoundedCornerShape(16.dp)
        Column(
            Modifier.fillMaxWidth().clip(box).background(Y.Bg0).border(1.dp, Y.Ev.copy(alpha = 0.35f), box).padding(14.dp),
        ) {
            Text(prettyBody(ui.packet.body), style = MONO, color = Y.Text1)
            Spacer(Modifier.height(6.dp))
            Text("SHA-256 ${ui.packet.sha256.take(8)} · 서명 키 ${ui.packet.keyId}", style = MaterialTheme.typography.labelSmall, color = Y.Text3)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("변화량 전송(모의)", Icons.Rounded.CloudUpload, Y.Ev) { store.sendPersonalUpdate() }
            if (ui.erased) {
                SmallButton("가상 기록 복원", Icons.Rounded.RestoreFromTrash, Y.Cyan) { store.restorePersonalData() }
            } else {
                SmallButton("내 기록 지우기", Icons.Rounded.DeleteForever, Y.Red) { store.erasePersonalData() }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("가중치 변화량 6개 · 잡음 σ ${ui.packet.sigma} · ECDSA P-256 서명", style = MaterialTheme.typography.labelSmall, color = Y.Text3)

        Spacer(Modifier.height(18.dp))
        Text("보내지 않는 것", style = MaterialTheme.typography.labelLarge, color = Y.Text2)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("장소 이름", "좌표", "방문 시각", "음성").forEach { NoSendChip(it) }
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("학습 기록 사슬", style = MaterialTheme.typography.labelLarge, color = Y.Text2, modifier = Modifier.weight(1f))
            val ok = ui.ledgerValid
            val tone = if (ok) Y.Ev else Y.Red
            Pill(if (ok) "사슬 검증 ✓" else "사슬 검증 ✗", color = tone, bg = tone.copy(alpha = 0.12f))
        }
        Spacer(Modifier.height(6.dp))
        ui.ledger.takeLast(4).forEach { LedgerRow(it) }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, trailing: String? = null, ok: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Y.Text3, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Y.Text1, maxLines = 2, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = if (ok) Y.Ev else Y.Amber, maxLines = 1)
        }
    }
}

@Composable
private fun NoSendChip(text: String) {
    Row(
        Modifier.clip(CircleShape).background(Y.Bg3).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Block, null, tint = Y.Red.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Y.Text2)
    }
}

@Composable
private fun LedgerRow(e: LedgerEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text("#${e.seq}", style = MaterialTheme.typography.labelLarge, color = Y.Periwinkle, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text("${e.type} · ${e.summary}", style = MaterialTheme.typography.bodySmall, color = Y.Text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${e.hash.take(8)} ← ${e.prev.take(8)}", style = MONO.copy(fontSize = 12.sp, lineHeight = 16.sp), color = Y.Text3)
        }
    }
}

// ───────────────────────── 작은 부품 ─────────────────────────

/** 정차 중 패널용 보조 버튼 (주행 중에는 쓰지 않는다) */
@Composable
private fun SmallButton(label: String, icon: ImageVector, color: Color = Y.Text1, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(Y.Bg3)
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** 확률·특징 막대. ghost = 직전 값(흐리게) */
@Composable
private fun Bar(value: Float, color: Color, modifier: Modifier, ghost: Float? = null) {
    Box(modifier.clip(CircleShape).background(Y.Bg4)) {
        if (ghost != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(ghost.coerceIn(0f, 1f)).clip(CircleShape).background(Y.Text3.copy(alpha = 0.5f)))
        }
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0.015f, 1f)).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color))),
        )
    }
}

@Composable
private fun TierPill(t: Tier) {
    val c = when (t) {
        Tier.AUTO -> Y.Ev
        Tier.CONFIRM -> Y.Cyan
        Tier.CHOOSE -> Y.Amber
        Tier.NONE -> Y.Red
    }
    Pill(t.label, color = c, bg = c.copy(alpha = 0.13f))
}

private fun featureColor(i: Int): Color = when (i) {
    0 -> Y.Cyan
    1 -> Y.Periwinkle
    2 -> Y.Violet
    3 -> Y.Blue
    4 -> Y.Amber
    else -> Y.Ev
}

private fun pct(p: Double): String {
    val v = (p * 100).roundToInt()
    return if (v < 1) "<1%" else "$v%"
}

private fun deltaLabel(d: Double): String = when {
    abs(d) < 0.005 -> "±0"
    d > 0 -> "+%.2f".format(d)
    else -> "−%.2f".format(-d)
}

private fun deltaColor(d: Double): Color = when {
    abs(d) < 0.005 -> Y.Text3
    d > 0 -> Y.Ev
    else -> Y.Red
}

private fun bytes(n: Long): String = when {
    n <= 0L -> "0 B"
    n < 1024L -> "$n B"
    n < 1024L * 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}

/** 전송 본문을 키마다 줄을 바꿔 읽기 쉽게 (내용은 그대로, 쉼표 뒤에만 줄바꿈 허용) */
private fun prettyBody(body: String): String = body.replace(",\"", ",\n \"").replace(",", ",​")
