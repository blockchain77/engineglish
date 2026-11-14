package com.yeopjari.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Mic
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
import com.yeopjari.app.core.model.Routine
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.BigButton
import com.yeopjari.app.ui.components.Eyebrow
import com.yeopjari.app.ui.components.GlassCard
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.Toggle
import com.yeopjari.app.ui.theme.Y

@Composable
fun RoutinesScreen(store: YeopjariStore, layout: Layout) {
    val routines by store.routines.collectAsStateWithLifecycle()
    val pad = if (layout.expanded) 28.dp else 18.dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pad, vertical = 8.dp).padding(bottom = if (layout.expanded) 24.dp else 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ScreenHeader("루틴", "차량 이벤트(기어·시간·위치·충전)가 조건이 되는 자동화 — 말로 만들 수 있어요", Icons.Rounded.AutoMode, Y.Cyan)
        if (layout.restricted) RestrictedNotice("루틴 편집은 정차 중에 할 수 있어요. 실행은 주행 중에도 자동으로 돼요.")
        GlassCard(Modifier.fillMaxWidth(), accent = Y.Periwinkle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Y.Periwinkle, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("말로 루틴 만들기", style = MaterialTheme.typography.titleLarge, color = Y.Text1)
                    Text("“평일 아침 8시에 출발하면 회사로 안내하고 21도로 맞춰줘” → 조건·동작으로 구조화", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("예시로 만들어 보기", Modifier.weight(1f), icon = Icons.Rounded.AutoAwesome, color = Y.Periwinkle, enabled = !layout.restricted) {
                    store.openAgent(); store.send("평일 아침 8시에 출발하면 회사로 안내하고 21도로 맞춰주는 루틴 만들어줘")
                }
                BigButton("말하기", Modifier.weight(1f), icon = Icons.Rounded.Mic, primary = false, enabled = !layout.restricted) { store.startVoice() }
            }
        }
        Eyebrow("내 루틴 · ${routines.size}")
        routines.forEach { r -> RoutineRow(r, layout) { store.toggleRoutine(r.id) } }
    }
}

@Composable
private fun RoutineRow(r: Routine, layout: Layout, onToggle: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = 22.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = MaterialTheme.typography.titleLarge, color = if (r.enabled) Y.Text1 else Y.Text3)
                    if (r.createdByAi) { Spacer(Modifier.width(10.dp)); Pill("AI 생성", color = Y.Periwinkle, bg = Y.Periwinkle.copy(alpha = 0.12f)) }
                    if (r.runsThisMonth > 0) { Spacer(Modifier.width(8.dp)); Pill("이번 달 ${r.runsThisMonth}회") }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Y.Bg3).padding(14.dp)) {
                        Text("조건", style = MaterialTheme.typography.labelMedium, color = Y.Text3)
                        r.triggers.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Y.Text1) }
                    }
                    Box(Modifier.padding(horizontal = 10.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Y.Text3) }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Y.Bg3).padding(14.dp)) {
                        Text("동작", style = MaterialTheme.typography.labelMedium, color = Y.Text3)
                        r.actions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Y.Cyan) }
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            if (!layout.restricted) Toggle(r.enabled) { onToggle() }
        }
    }
}
