package com.yeopjari.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.core.model.Telltale
import com.yeopjari.app.sim.ScenarioId
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.Eyebrow
import com.yeopjari.app.ui.components.Toggle
import com.yeopjari.app.ui.theme.Y
import kotlin.math.roundToInt

/** 시연 디렉터 — PT 심사에서 시나리오를 즉시 재현하기 위한 패널 (Virtual Console 보완) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoPanel(store: YeopjariStore, layout: Layout) {
    val scenario by store.sim.scenario.collectAsStateWithLifecycle()
    val ctx by store.context.collectAsStateWithLifecycle()
    val flags by store.sim.flags.collectAsStateWithLifecycle()
    val src by store.sourceMode.collectAsStateWithLifecycle()
    val scale by store.sim.timeScale.collectAsStateWithLifecycle()
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null) { store.demoPanel.value = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 1500.dp).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Y.Bg1)
                .border(1.dp, Y.Stroke, RoundedCornerShape(32.dp)).clickable(remember { MutableInteractionSource() }, null) {}
                .verticalScroll(rememberScrollState()).padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Science, null, tint = Y.Violet, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("시연 디렉터", style = MaterialTheme.typography.headlineMedium, color = Y.Text1)
                    Text("시나리오를 고르면 차량 신호·경로·시간이 재현돼요. Pleos 에뮬레이터의 Virtual Console과 함께 쓸 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
                }
                Box(Modifier.size(60.dp).clip(CircleShape).background(Y.Bg3).clickable { store.demoPanel.value = false }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "닫기", tint = Y.Text1)
                }
            }
            Spacer(Modifier.height(22.dp))
            Eyebrow("시나리오")
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), maxItemsInEachRow = if (layout.expanded) 3 else 1) {
                ScenarioId.entries.forEach { s ->
                    val sel = s == scenario
                    Row(
                        Modifier.weight(1f).heightIn(min = 96.dp).clip(RoundedCornerShape(22.dp)).background(if (sel) Y.Violet.copy(alpha = 0.16f) else Y.Bg2)
                            .border(if (sel) 2.dp else 1.dp, if (sel) Y.Violet else Y.Stroke, RoundedCornerShape(22.dp)).clickable { store.playScenario(s) }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(if (sel) Y.Violet else Y.Bg4), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = if (sel) Y.Bg0 else Y.Text1)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, color = Y.Text1)
                            Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = Y.Text2)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Eyebrow("수동 조작 (Virtual Console 보완)")
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Gear.entries.filter { it != Gear.UNKNOWN && it != Gear.N }.forEach { g -> Ctl("기어 ${g.label}", ctx.vehicle.gear == g) { store.sim.manualGear(g) } }
                Ctl("속도 0", false) { store.sim.manualTargetSpeed(0f) }
                Ctl("속도 50", false) { store.sim.manualTargetSpeed(50f) }
                Ctl("속도 100", false) { store.sim.manualTargetSpeed(100f) }
                Ctl("SOC 15%", false) { store.sim.manualSoc(15f) }
                Ctl("SOC 60%", false) { store.sim.manualSoc(60f) }
                Ctl("운전석 문 ${if (ctx.vehicle.doors.driver) "닫기" else "열기"}", ctx.vehicle.doors.driver) { store.sim.manualDriverDoor(!ctx.vehicle.doors.driver) }
                Ctl("연속주행 +30분", false) { store.sim.manualDriveMinutes(ctx.vehicle.continuousDriveMin + 30) }
                Ctl("TPMS 경고등", Telltale.TPMS in ctx.vehicle.telltales) { store.sim.manualTelltale(Telltale.TPMS, Telltale.TPMS !in ctx.vehicle.telltales) }
                Ctl("시간 배속 ${scale.roundToInt()}x", false) { store.sim.setTimeScale(if (scale >= 40f) 5f else scale * 2) }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Flag("네트워크 연결", flags.online) { store.sim.setOnline(it) }
                Flag("반려견 동승", flags.petOnBoard) { store.sim.setPetOnBoard(it) }
                Flag("아이 동승", flags.kidsOnBoard) { store.sim.setKidsOnBoard(it) }
                Flag("Pleos SDK 데이터", src == Source.PLEOS_SDK) { store.useSource(if (it) Source.PLEOS_SDK else Source.SIMULATOR) }
            }
        }
    }
}

@Composable
private fun Ctl(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Y.Cyan.copy(alpha = 0.18f) else Y.Bg3)
            .border(1.dp, if (on) Y.Cyan else Y.Stroke, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) Y.Cyan else Y.Text1) }
}

@Composable
private fun Flag(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Y.Text2)
        Spacer(Modifier.width(10.dp))
        Toggle(on, onChange)
    }
}
