package com.cartutor.engineglish.ui.english

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartutor.engineglish.ui.theme.Y
import kotlinx.coroutines.delay
import kotlin.math.ceil

/*
 * 퇴근길 학습 질문 카드 — 퇴근길이 시작되면 바로 수업을 시작하지 않고 먼저 묻는다.
 * "퇴근길에도 영어 복습할까요?" + 큰 버튼 두 개 (할게요 / 오늘은 쉴게요) + 남은 시간 고리.
 * 말로 답해도 되고(코치가 듣는다), 버튼을 눌러도 된다. 답이 없으면 코치가 카드를 내린다.
 *
 * 주행 중에도 뜨는 카드라 주행 화면 규칙을 따른다: 질문 글자 34sp 이상 · 버튼 2개(104dp) · 목록·스크롤 없음.
 * 남은 시간 고리는 주행 중에는 1초에 한 칸씩만 줄어든다 (매끄러운 움직임은 정차 중에만).
 */

/**
 * @param visible 코치가 묻는 동안만 true
 * @param listening 대답을 듣는 중 — 고리 안에 마이크를 띄운다
 * @param deadlineMs 대답 마감 시각 (epoch ms). 질문을 읽어 주는 동안에는 null → 고리가 가득 찬 채로 있다
 * @param smoothRing 정차 중이면 true (고리가 매끄럽게 줄어든다)
 */
@Composable
internal fun EveningAskCard(
    visible: Boolean,
    onYes: () -> Unit,
    onNo: () -> Unit,
    modifier: Modifier = Modifier,
    question: String = "퇴근길에도 영어 복습할까요?",
    yesLabel: String = "할게요",
    noLabel: String = "오늘은 쉴게요",
    listening: Boolean = false,
    deadlineMs: Long? = null,
    smoothRing: Boolean = false,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Y.Dim)
                // 카드 밖을 눌러도 닫히지 않는다 — 아래 화면 버튼이 잘못 눌리지 않게 터치만 막는다
                .clickable(interactionSource = null, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Panel(
                Modifier.padding(28.dp).widthIn(max = 1240.dp).fillMaxWidth(),
                accent = Y.Accent,
                padding = 34.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CountdownRing(deadlineMs, listening, smoothRing, Modifier.size(128.dp))
                    Spacer(Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Eyebrow("퇴근길 학습", color = Y.Accent)
                        Spacer(Modifier.height(6.dp))
                        FitText(question, maxSp = 54f, maxLines = 2, align = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "‘응’ 또는 ‘아니’라고 말해도 돼요",
                            style = MaterialTheme.typography.titleLarge,
                            color = Y.Text2,
                        )
                    }
                }
                Spacer(Modifier.height(30.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    DriveButton(yesLabel, Icons.Rounded.Check, onYes, Modifier.weight(1f), highlight = true, height = 104.dp)
                    DriveButton(noLabel, Icons.Rounded.Close, onNo, Modifier.weight(1f), height = 104.dp)
                }
            }
        }
    }
}

/** 남은 대답 시간 고리 — 가운데에는 남은 초 (듣는 중이면 마이크, 질문을 읽는 동안에는 달) */
@Composable
private fun CountdownRing(deadlineMs: Long?, listening: Boolean, smooth: Boolean, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val start = remember(deadlineMs) { System.currentTimeMillis() }
    LaunchedEffect(deadlineMs, smooth) {
        if (deadlineMs == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= deadlineMs) break
            // 주행 중: 다음 '초'가 바뀔 때만 다시 그린다
            delay(if (smooth) 50L else ((deadlineMs - now) % 1000L).coerceAtLeast(40L))
        }
    }
    val total = if (deadlineMs != null) (deadlineMs - start).coerceAtLeast(1L) else 1L
    val left = if (deadlineMs != null) (deadlineMs - now).coerceIn(0L, total) else total
    val secondsLeft = ceil(left / 1000.0).toInt()
    val frac = when {
        deadlineMs == null -> 1f
        smooth -> left.toFloat() / total
        // 주행 중에는 1초 단위로만
        else -> (secondsLeft * 1000f / total).coerceIn(0f, 1f)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 9.dp.toPx()
            val d = size.minDimension - s
            val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            drawArc(Y.Accent.copy(alpha = 0.18f), -90f, 360f, false, tl, Size(d, d), style = Stroke(s))
            if (frac > 0f) drawArc(Y.Accent, -90f, 360f * frac, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        when {
            deadlineMs != null && !listening ->
                Text("$secondsLeft", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Y.Text1)
            deadlineMs != null ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Mic, contentDescription = "듣는 중", tint = Y.Accent, modifier = Modifier.size(34.dp))
                    Text("$secondsLeft", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Y.Text1)
                }
            listening -> Icon(Icons.Rounded.Mic, contentDescription = "듣는 중", tint = Y.Accent, modifier = Modifier.size(50.dp))
            else -> Icon(Icons.Rounded.NightsStay, contentDescription = null, tint = Y.Accent, modifier = Modifier.size(50.dp))
        }
    }
}
