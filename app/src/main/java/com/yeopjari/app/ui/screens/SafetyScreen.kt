package com.yeopjari.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.ai.context.LoadLevel
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.BigButton
import com.yeopjari.app.ui.components.Eyebrow
import com.yeopjari.app.ui.components.FactorBar
import com.yeopjari.app.ui.components.GlassCard
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.ProgressRing
import com.yeopjari.app.ui.components.SectionTitle
import com.yeopjari.app.ui.theme.Y

@Composable
fun SafetyScreen(store: YeopjariStore, layout: Layout) {
    val pad = if (layout.expanded) 28.dp else 18.dp
    val body: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FatigueCard(store)
            LadderCard(store, layout)
        }
    }
    val side: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            InterruptionCard(store)
            RearSeatCard(store)
        }
    }
    if (layout.expanded) {
        Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 8.dp)) {
            ScreenHeader("졸음·주의 케어", "말을 걸어도 되는 순간을 고르고, 졸음은 단계별로 케어해요", Icons.Rounded.Shield, Y.Rest)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                body(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp))
                side(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ScreenHeader("졸음·주의 케어", "단계별 졸음 케어 · 인터럽션 관리", Icons.Rounded.Shield, Y.Rest)
            body(Modifier.fillMaxWidth())
            side(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FatigueCard(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val f = ctx.fatigue
    val color = when (f.level) { 0 -> Y.Ev; 1 -> Y.Kids; 2 -> Y.Amber; else -> Y.Red }
    GlassCard(Modifier.fillMaxWidth(), accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(f.score / 100f, 180.dp, color, 16.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${f.score}", style = MaterialTheme.typography.displayMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
                    Text(f.levelLabel, style = MaterialTheme.typography.titleMedium, color = color)
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("졸음 위험 요인 (설명 가능한 점수)")
                Spacer(Modifier.height(8.dp))
                if (f.factors.isEmpty()) Text("지금은 위험 요인이 없어요.", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
                f.factors.take(4).forEach { FactorBar(it.label, it.points.toFloat(), 55f, color, "+${it.points}") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("입력: 연속 주행 시간(Odometer) · 시간대 · 차속/도로 유형(CarProperty·NaviHelper) · 공조 상태(HVAC). 향후 조향 패턴 기반 온디바이스 분류 모델로 고도화.", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
    }
}

@Composable
private fun LadderCard(store: YeopjariStore, layout: Layout) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val lv = ctx.fatigue.level
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("단계별 케어 — 경고음 대신 행동을")
        Step(1, "환기 + 온도 낮추기", "외기 유입·풍량 5단·−2°C (HVAC 제어)", Icons.Rounded.Air, lv >= 1)
        Step(2, "대화로 깨우기", "관심사 기반 짧은 질문 · 클라우드 LLM 생성", Icons.Rounded.Forum, lv >= 2)
        Step(3, "쉼터 경유 + 쉼 모드", "가까운 졸음쉼터 경유지 추가 (NaviHelper) · 15분 타이머", Icons.Rounded.LocalCafe, lv >= 3)
        Spacer(Modifier.height(10.dp))
        Text("경고만 울리는 졸음 알림은 실제 휴식으로 이어지지 않는다는 연구에 따라, 구체적인 '다음 행동'을 제안해요.", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
        if (!layout.restricted) {
            Spacer(Modifier.height(14.dp))
            BigButton("졸음 시나리오로 시연", primary = false, color = Y.Rest) { store.playScenario(com.yeopjari.app.sim.ScenarioId.DROWSY) }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, sub: String, icon: ImageVector, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(20.dp)).background(if (active) Y.Rest.copy(alpha = 0.14f) else Y.Bg3).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (active) Y.Rest else Y.Bg4), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.titleMedium, color = if (active) Y.Bg0 else Y.Text2)
        }
        Spacer(Modifier.width(16.dp))
        Icon(icon, null, tint = if (active) Y.Rest else Y.Text3, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Y.Text1)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Y.Text2)
        }
        if (active) Pill("작동", color = Y.Rest, bg = Y.Rest.copy(alpha = 0.16f))
    }
}

@Composable
private fun InterruptionCard(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val deferred by store.deferredCount.collectAsStateWithLifecycle()
    val c = when (ctx.load.level) { LoadLevel.LOW -> Y.Ev; LoadLevel.MEDIUM -> Y.Blue; LoadLevel.HIGH -> Y.Amber }
    GlassCard(Modifier.fillMaxWidth(), accent = c) {
        SectionTitle("인터럽션 매니저")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("운전 부하 ${ctx.load.level.label}", style = MaterialTheme.typography.headlineMedium, color = c)
                Text(ctx.load.reason, style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NotificationsPaused, null, tint = if (deferred > 0) Y.Amber else Y.Text3, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
                Text("$deferred", style = MaterialTheme.typography.headlineMedium, color = Y.Text1)
            }
        }
        Spacer(Modifier.height(12.dp))
        LoadBar("여유", "고속도로 차로 유지 · 정차", Y.Ev, ctx.load.level == LoadLevel.LOW)
        LoadBar("보통", "시내 주행", Y.Blue, ctx.load.level == LoadLevel.MEDIUM)
        LoadBar("집중 필요", "1km 이내 방향 전환 (TBT) — 비긴급 음성 보류", Y.Amber, ctx.load.level == LoadLevel.HIGH)
        Spacer(Modifier.height(8.dp))
        Text("안전 관련 알림(뒷좌석·펫·졸음 3단계)은 보류하지 않아요.", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
    }
}

@Composable
private fun LoadBar(label: String, sub: String, color: Color, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(if (active) color else Y.Bg4))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (active) Y.Text1 else Y.Text3, modifier = Modifier.width(96.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = if (active) Y.Text2 else Y.Text3)
    }
}

@Composable
private fun RearSeatCard(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ChildCare, null, tint = Y.Red, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("뒷좌석 확인", style = MaterialTheme.typography.titleLarge, color = Y.Text1)
                Text(if (ctx.flags.rearOpenedAtStart) "출발 시 뒷문 개폐 감지 — 도착 후 하차 확인 대기" else "출발 시 뒷문 개폐 없음", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Door SDK 개폐 이력 + Seat 탑승 감지로 동작하는 보조 리마인더예요 (인증된 안전장치를 대체하지 않아요).", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
    }
}
