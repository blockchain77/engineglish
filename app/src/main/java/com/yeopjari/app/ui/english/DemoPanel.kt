package com.yeopjari.app.ui.english

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.english.core.DemoAnswerKind
import com.yeopjari.app.english.core.DemoControl
import com.yeopjari.app.english.core.DemoScenario
import com.yeopjari.app.english.core.DemoSignal
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.EnglishNavigator
import com.yeopjari.app.english.core.EnglishRoute
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.MediaKey
import com.yeopjari.app.ui.theme.Y
import kotlin.math.abs

/*
 * 시연 패널 — 디버그 빌드 또는 ADB 켜짐일 때만 (통합이 demo 를 넘길 때만 보인다).
 * 시나리오 · 운전 신호 · 답 넣기 · 핸들 버튼 · 퇴근길 질문 · 주차 대시보드 · 배속 · 화면 이동. 실차(Pleos) 주행 중에는 열리지 않는다.
 */

/** 말로 하는 명령 (데모에서 그대로 넣어 본다) */
private val COMMAND_PHRASES = listOf("repeat", "slower", "skip", "hint", "meaning", "pause", "continue", "다시", "넘겨", "응", "아니", "not today")

private val SCALES = listOf(1f, 6f, 12f, 20f)

@Composable
internal fun DemoPanel(
    state: EnglishUiState,
    coach: EnglishCoach,
    demo: DemoControl,
    navigator: EnglishNavigator,
    onClose: () -> Unit,
) {
    val scenario by demo.scenario.collectAsStateWithLifecycle()
    val scale by demo.timeScale.collectAsStateWithLifecycle()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(interactionSource = null, indication = null, onClick = onClose),
    ) {
        // 화면 절반 · 560~680dp (좁은 화면이면 화면 폭)
        val panelWidth = (maxWidth * 0.5f).coerceIn(minOf(560.dp, maxWidth), 680.dp)
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(panelWidth)
                .background(Y.Bg1)
                .clickable(interactionSource = null, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("시연 패널", style = MaterialTheme.typography.headlineMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
                    Text("디버그 · ADB 전용 — 실제 사용자에게는 보이지 않아요", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
                }
                BigButton("닫기", onClose, icon = Icons.Rounded.Close, tone = Tone.GHOST)
            }
            Spacer(Modifier.height(16.dp))
            // 지금 상태
            Panel(Modifier.fillMaxWidth(), padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadChip(state.load, maxReasons = 2)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "시나리오 · ${scenario?.ko ?: "없음 (실차 신호 또는 집 앞 정차)"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Y.Text2,
                )
                Text(
                    "단계 · ${state.phase.ko}   속도 · ${state.drive.speedKph}km/h   기어 · ${state.drive.gear.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Y.Text2,
                )
                state.prompt?.let { Text("문항 · ${it.itemId} (${it.skill.ko})", style = MaterialTheme.typography.bodySmall, color = Y.Text3) }
            }

            DemoSection("시나리오")
            DemoGrid(DemoScenario.entries, cols = 2) { s ->
                DemoButton(s.ko, selected = scenario == s) { demo.play(s) }
            }
            Spacer(Modifier.height(10.dp))
            BigButton("시나리오 끝내기", { demo.stop() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Stop, tone = Tone.GHOST)

            DemoSection("배속 (경로 진행만 빨라지고, 부하 판단 시간은 실제 시간)")
            Segmented(SCALES, SCALES.minByOrNull { abs(it - scale) }, { "${it.toInt()}×" }, { demo.setTimeScale(it) }, Modifier.fillMaxWidth())

            DemoSection("운전 신호")
            DemoGrid(DemoSignal.entries, cols = 2) { sig ->
                DemoButton(sig.ko) { demo.inject(sig) }
            }

            DemoSection("답 넣기 (지금 문항 기준)")
            DemoGrid(DemoAnswerKind.entries, cols = 2) { k ->
                DemoButton(k.ko) { coach.injectDemoAnswer(k) }
            }
            Spacer(Modifier.height(10.dp))
            DemoGrid(COMMAND_PHRASES, cols = 3) { phrase ->
                DemoButton("“$phrase”") { coach.onAnswerText(phrase) }
            }
            Spacer(Modifier.height(10.dp))
            DemoTextInput { coach.onAnswerText(it) }

            DemoSection("핸들 버튼")
            DemoGrid(MediaKey.entries, cols = 3) { key ->
                DemoButton(key.ko) { coach.onMediaKey(key) }
            }

            DemoSection("퇴근길 질문 · 주차 대시보드")
            DemoGrid(listOf("할게요" to true, "오늘은 쉴게요" to false), cols = 2) { (label, yes) ->
                DemoButton("퇴근길 질문 · $label") { coach.answerEveningAsk(yes) }
            }
            Spacer(Modifier.height(10.dp))
            DemoButton("지난 학습 결과 (주차 대시보드)") {
                coach.showDashboard()
                onClose()
            }

            DemoSection("화면")
            val routes = EnglishRoute.entries.filter { it != EnglishRoute.ONBOARDING && it != EnglishRoute.PLACEMENT }
            DemoGrid(routes, cols = 3) { r ->
                DemoButton(r.ko) { navigator.go(r) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DemoSection(title: String) {
    Spacer(Modifier.height(22.dp))
    Eyebrow(title, color = Y.Violet)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun <T> DemoGrid(items: List<T>, cols: Int, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DemoButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TouchMin)
            .clip(shape)
            .background(if (selected) Y.Violet.copy(alpha = 0.22f) else Y.Bg3)
            .border(1.dp, if (selected) Y.Violet else Y.StrokeSoft, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (selected) Y.Text1 else Y.Text1.copy(alpha = 0.9f), maxLines = 2)
    }
}

@Composable
private fun DemoTextInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSend(t)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 22.sp, color = Y.Text1),
            cursorBrush = SolidColor(Y.Violet),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TouchMin)
                .clip(RoundedCornerShape(18.dp))
                .background(Y.Bg0)
                .border(1.dp, Y.Stroke, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("들린 말 직접 넣기 (예: Can we push the meeting?)", style = TextStyle(fontSize = 18.sp, color = Y.Text3))
                    inner()
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        BigButton("넣기", { send() }, icon = Icons.AutoMirrored.Rounded.Send)
    }
}
