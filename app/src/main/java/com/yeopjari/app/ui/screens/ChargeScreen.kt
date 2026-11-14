package com.yeopjari.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.ai.context.EnergyModel
import com.yeopjari.app.core.model.Charger
import com.yeopjari.app.sim.Chargers
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.BigButton
import com.yeopjari.app.ui.components.Eyebrow
import com.yeopjari.app.ui.components.GlassCard
import com.yeopjari.app.ui.components.MiniLineChart
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.ProgressRing
import com.yeopjari.app.ui.components.SectionTitle
import com.yeopjari.app.ui.components.SocRing
import com.yeopjari.app.ui.components.StatTile
import com.yeopjari.app.ui.theme.Y
import kotlin.math.roundToInt

@Composable
fun ChargeScreen(store: YeopjariStore, layout: Layout) {
    val pad = if (layout.expanded) 28.dp else 18.dp
    if (layout.expanded) {
        Row(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ScreenHeader("충전 코파일럿", "실제 배터리 상태로 계획하고, 고장 난 충전기는 피하고, 과태료까지 챙겨요", Icons.Rounded.EvStation, Y.Ev)
                BatteryCard(store)
                SessionOrLimitCard(store, layout)
            }
            Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ForecastCard(store)
                ChargerList(store, layout)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ScreenHeader("충전 코파일럿", "실제 배터리로 계획 · 신뢰도 높은 충전소 · 과태료 알림", Icons.Rounded.EvStation, Y.Ev)
            BatteryCard(store)
            ForecastCard(store)
            SessionOrLimitCard(store, layout)
            ChargerList(store, layout)
        }
    }
}

@Composable
private fun BatteryCard(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val v = ctx.vehicle
    GlassCard(Modifier.fillMaxWidth(), accent = Y.Ev) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SocRing(v.socPercent, 200.dp, 18.dp, charging = v.isCharging) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${v.socPercent.roundToInt()}%", style = MaterialTheme.typography.displayMedium, color = Y.Text1, fontWeight = FontWeight.Bold)
                    Text(if (v.isCharging) "충전 중" else "SOC", style = MaterialTheme.typography.titleSmall, color = Y.Text3)
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("주행 가능", "${v.rangeKm}", "km", color = Y.Ev)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("유효 전비", "%.1f".format(ctx.energy.effectiveKmPerKwh), "km/kWh", modifier = Modifier.weight(1f))
                    StatTile("충전 한도", "${v.chargeLimitPercent}", "%", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ForecastCard(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val r = ctx.route
    val e = ctx.energy
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("도착 SOC 예측") {
            e.arrivalSoc?.let {
                val a = it.roundToInt()
                Pill(if (a < 10) "도착 ${a.coerceAtLeast(0)}% · 충전 필요" else "도착 $a% · 여유", color = if (a < 10) Y.Amber else Y.Ev, bg = (if (a < 10) Y.Amber else Y.Ev).copy(alpha = 0.12f))
            }
        }
        if (!r.active) {
            Text("목적지를 정하면 실제 배터리·외기온·공조 부하로 도착 SOC를 예측해요.", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
            Spacer(Modifier.height(16.dp))
            BigButton("강릉 가족여행 시나리오로 보기", primary = false, color = Y.Ev) { store.playScenario(com.yeopjari.app.sim.ScenarioId.ROADTRIP); store.go(com.yeopjari.app.Screen.CHARGE) }
            return@GlassCard
        }
        val noStop = e.curve.map { (it.km / r.remainingKm.coerceAtLeast(1f)) to it.soc }
        val stopKm = 118f.coerceAtMost(r.remainingKm * 0.7f)
        val withStop = EnergyModel.curveWithStop(ctx.vehicle, r, stopKm, 80f, e.effectiveKmPerKwh, "충전")
            .map { (it.km / r.remainingKm.coerceAtLeast(1f)) to it.soc }
        Text("충전 없이", style = MaterialTheme.typography.labelLarge, color = Y.Amber)
        MiniLineChart(noStop, Modifier.fillMaxWidth().height(120.dp), color = Y.Amber)
        Spacer(Modifier.height(14.dp))
        Text("횡성휴게소 E-pit 18분 충전 시", style = MaterialTheme.typography.labelLarge, color = Y.Ev)
        MiniLineChart(withStop, Modifier.fillMaxWidth().height(120.dp), color = Y.Ev, markers = listOf((stopKm / r.remainingKm) to "충전"))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            e.factors.take(4).forEach { (k, v) -> Pill("$k $v") }
        }
    }
}

@Composable
private fun SessionOrLimitCard(store: YeopjariStore, layout: Layout) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val v = ctx.vehicle
    if (v.isCharging) {
        val elapsed = v.chargeStartedSec?.let { (((ctx.clockSec - it) + 86400f) % 86400f / 60f) } ?: 0f
        val remainPct = (v.chargeLimitPercent - v.socPercent).coerceAtLeast(0f)
        val minutes = ((remainPct / 100f * v.batteryKwh) / v.chargePowerKw.coerceAtLeast(30f) * 60f).roundToInt()
        GlassCard(Modifier.fillMaxWidth(), accent = if (elapsed > 50) Y.Amber else Y.Ev) {
            SectionTitle("충전 세션")
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(elapsed / 60f, 150.dp, if (elapsed > 50) Y.Amber else Y.Ev) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(60 - elapsed).roundToInt().coerceAtLeast(0)}분", style = MaterialTheme.typography.headlineMedium, color = Y.Text1)
                        Text("과태료까지", style = MaterialTheme.typography.labelMedium, color = Y.Text3)
                    }
                }
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${v.chargePowerKw.roundToInt()} kW · ${minutes}분 뒤 ${v.chargeLimitPercent}%", style = MaterialTheme.typography.titleLarge, color = Y.Ev)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Gavel, null, tint = Y.Text3, modifier = Modifier.width(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("급속충전 1시간 초과 시 과태료 — 50분에 미리 알려 드려요", style = MaterialTheme.typography.bodySmall, color = Y.Text2)
                    }
                    if (!layout.restricted) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("쉼 모드", Modifier.weight(1f), primary = false) { store.startRestMode(15) }
                        BigButton("아이 퀴즈", Modifier.weight(1f), primary = false, color = Y.Kids) { store.openAgent(); store.send("아이들 심심해하는데 퀴즈 해줘") }
                    }
                }
            }
        }
    }
    if (layout.restricted) return // 설정 변경은 주행 중 제한 (NO_SETUP)
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("충전 한도 · EvBattery.setEvChargePercentLimit")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(80, 90, 100).forEach { p ->
                val sel = v.chargeLimitPercent == p
                Box(
                    Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(20.dp)).background(if (sel) Y.Ev.copy(alpha = 0.18f) else Y.Bg3)
                        .border(if (sel) 2.dp else 1.dp, if (sel) Y.Ev else Y.Stroke, RoundedCornerShape(20.dp)).clickable { store.setChargeLimit(p) },
                    contentAlignment = Alignment.Center,
                ) { Text("$p%", style = MaterialTheme.typography.headlineSmall, color = if (sel) Y.Ev else Y.Text1) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalParking, null, tint = Y.Cyan, modifier = Modifier.width(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("지하주차장 보호: 집·회사 주차장 진입 전 충전 한도가 90%를 넘으면 자동으로 90%로 맞출지 물어봐요.", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
        }
    }
}

@Composable
private fun ChargerList(store: YeopjariStore, layout: Layout) {
    val ctx by store.context.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("경로상 충전소 비교") { Pill("예비 충전소 항상 확보", color = Y.Cyan, bg = Y.Cyan.copy(alpha = 0.1f)) }
        val list = Chargers.routeToGangneung.sortedByDescending { score(it) }
        list.take(if (layout.restricted) 2 else 3).forEachIndexed { i, c ->
            ChargerRow(c, recommended = i == 0, backup = i == 1, onAdd = { store.confirm("add_waypoint:${c.place.id}") })
            if (i < 2) Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("신뢰도 = 최근 충전 세션 경과 시간 + 30일 고장 제보 + 가용 비율. '마지막 사용이 며칠 전이면 고장'이라는 운전자 노하우를 지표로 만들었어요.", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
    }
}

private fun score(c: Charger) = c.reliability * 2 + c.maxKw / 10 - c.expectedQueue * 15 - c.memberPricePerKwh / 20

@Composable
private fun ChargerRow(c: Charger, recommended: Boolean, backup: Boolean, onAdd: () -> Unit) {
    val relColor = when { c.reliability >= 75 -> Y.Ev; c.reliability >= 45 -> Y.Amber; else -> Y.Red }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Y.Bg3)
            .then(if (recommended) Modifier.border(1.5.dp, Y.Ev.copy(alpha = 0.6f), RoundedCornerShape(22.dp)) else Modifier).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.place.name, style = MaterialTheme.typography.titleMedium, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(8.dp))
                if (recommended) Pill("추천", color = Y.Ev, bg = Y.Ev.copy(alpha = 0.12f), icon = Icons.Rounded.Verified)
                if (backup) Pill("예비", color = Y.Cyan, bg = Y.Cyan.copy(alpha = 0.12f))
            }
            Spacer(Modifier.height(6.dp))
            Text("${c.kmFromStart.toInt()}km 앞 · ${c.maxKw}kW · ${c.available}/${c.total}대 가용 · 대기 예상 ${c.expectedQueue}대", style = MaterialTheme.typography.bodySmall, color = Y.Text2)
            Text("멤버십 ${c.memberPricePerKwh}원/kWh · 마지막 충전 ${if (c.lastSessionMinAgo < 60) "${c.lastSessionMinAgo}분" else "${c.lastSessionMinAgo / 60}시간"} 전 · ${c.amenities.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = Y.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${c.reliability}", style = MaterialTheme.typography.headlineMedium, color = relColor)
            Text("신뢰도", style = MaterialTheme.typography.labelSmall, color = Y.Text3)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Y.Bg4).clickable(onClick = onAdd).padding(16.dp)) {
            Icon(Icons.Rounded.Add, "경유지 추가", tint = Y.Text1)
        }
    }
}
