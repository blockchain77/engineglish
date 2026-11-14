package com.yeopjari.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.core.model.Seat
import com.yeopjari.app.core.model.Telltale
import com.yeopjari.app.core.model.WarningLevel
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.BigButton
import com.yeopjari.app.ui.components.CarTopView
import com.yeopjari.app.ui.components.Eyebrow
import com.yeopjari.app.ui.components.GlassCard
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.SectionTitle
import com.yeopjari.app.ui.components.StatTile
import com.yeopjari.app.ui.theme.Y
import kotlin.math.roundToInt

@Composable
fun CarScreen(store: YeopjariStore, layout: Layout) {
    val pad = if (layout.expanded) 28.dp else 18.dp
    if (layout.expanded) {
        Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 8.dp)) {
            ScreenHeader("차량 닥터 · 여정 기록", "경고등과 증상을 실시간 차량 데이터로 풀어 설명해요", Icons.Rounded.DirectionsCar, Y.Blue)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    StatusCard(store, layout)
                    AskCard(store, layout)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    JournalCard(store, layout)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ScreenHeader("차량 닥터 · 여정 기록", "경고등을 쉬운 말로", Icons.Rounded.DirectionsCar, Y.Blue)
            StatusCard(store, layout)
            AskCard(store, layout)
            JournalCard(store, layout)
        }
    }
}

@Composable
private fun StatusCard(store: YeopjariStore, layout: Layout) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val v = ctx.vehicle
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("실시간 상태") { Pill(v.model, color = Y.Text3) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CarTopView(
                listOf(v.doors.driver, v.doors.passenger, v.doors.rearLeft, v.doors.rearRight, v.doors.trunk),
                listOf(Seat.DRIVER in v.occupants, Seat.PASSENGER in v.occupants, Seat.REAR_LEFT in v.occupants, Seat.REAR_RIGHT in v.occupants),
                Modifier.size(170.dp, 250.dp), tpms = Telltale.TPMS in v.telltales,
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("누적 주행", "%,d".format(v.odometerKm), "km", modifier = Modifier.weight(1f))
                    StatTile("탑승", "${v.occupants.size}", "명", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("실내", "${v.cabinTempC.roundToInt()}", "°C", modifier = Modifier.weight(1f))
                    StatTile("연속 주행", "${v.continuousDriveMin}", "분", modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Eyebrow("경고등 · Safety.getTelltalesInfo")
        Spacer(Modifier.height(10.dp))
        val all = Telltale.entries
        all.forEach { t ->
            val on = t in v.telltales
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Y.Amber.copy(alpha = 0.12f) else Y.Bg3).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (on) Icons.Rounded.Warning else Icons.Rounded.CheckCircle, null, tint = if (on) (if (t.level == WarningLevel.CRITICAL) Y.Red else Y.Amber) else Y.Ev, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(t.label, style = MaterialTheme.typography.bodyLarge, color = if (on) Y.Text1 else Y.Text2, modifier = Modifier.weight(1f))
                if (on && !layout.restricted) BigButton("왜 켜졌나요?", primary = false, color = Y.Amber) { store.openAgent(); store.send("${t.label} 경고등 왜 켜진 거야?") }
                else Text(if (on) "점등" else "정상", style = MaterialTheme.typography.labelLarge, color = if (on) Y.Amber else Y.Text3)
            }
        }
    }
}

@Composable
private fun AskCard(store: YeopjariStore, layout: Layout) {
    GlassCard(Modifier.fillMaxWidth(), accent = Y.Periwinkle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.HelpOutline, null, tint = Y.Periwinkle, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text("말로 묻는 매뉴얼", style = MaterialTheme.typography.titleLarge, color = Y.Text1)
        }
        Spacer(Modifier.height(10.dp))
        Text("차종 매뉴얼 RAG + 실시간 차량 데이터. 주행 중엔 핵심만 음성으로, 정차하면 단계별 화면으로 안내해요.", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
        Spacer(Modifier.height(14.dp))
        ChipsRow(listOf(
            "오토홀드 어떻게 켜?" to { store.openAgent(); store.send("오토홀드 어떻게 켜?") },
            "회생제동 단계 바꾸는 법" to { store.openAgent(); store.send("회생제동 어떻게 써?") },
            "차 상태 점검해줘" to { store.openAgent(); store.send("차 상태 점검해줘") },
            "브레이크에서 끼익 소리가 나" to { store.openAgent(); store.send("브레이크에서 끼익 소리가 이상해") },
        ))
    }
}

@Composable
private fun JournalCard(store: YeopjariStore, layout: Layout) {
    val trips by store.trips.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("여정 기록 · AI 요약")
        trips.take(if (layout.restricted) 2 else 4).forEachIndexed { i, t ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Y.Bg3).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleLarge, color = Y.Text1)
                        Text("${t.dateLabel} · ${t.km.roundToInt()}km · ${t.minutes}분 · ${"%.1f".format(t.efficiency)}km/kWh", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${t.safetyScore}", style = MaterialTheme.typography.headlineMedium, color = if (t.safetyScore >= 90) Y.Ev else Y.Amber)
                        Text("안전 점수", style = MaterialTheme.typography.labelSmall, color = Y.Text3)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Y.Periwinkle, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t.aiSummary, style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { t.tags.forEach { Pill(it) } }
            }
            if (i < trips.lastIndex) Spacer(Modifier.height(12.dp))
        }
    }
}
