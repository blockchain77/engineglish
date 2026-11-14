package com.yeopjari.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Work
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.Screen
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.core.model.Moment
import com.yeopjari.app.core.model.MomentType
import com.yeopjari.app.core.model.Seat
import com.yeopjari.app.core.model.Telltale
import com.yeopjari.app.core.model.Urgency
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.AiOrb
import com.yeopjari.app.ui.components.BigButton
import com.yeopjari.app.ui.components.CarTopView
import com.yeopjari.app.ui.components.Eyebrow
import com.yeopjari.app.ui.components.GlassCard
import com.yeopjari.app.ui.components.IconBadge
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.SocRing
import com.yeopjari.app.ui.components.StatTile
import com.yeopjari.app.ui.components.SuggestionChip
import com.yeopjari.app.ui.components.TierBadge
import com.yeopjari.app.ui.theme.Y
import kotlin.math.roundToInt

fun momentVisual(type: MomentType): Pair<ImageVector, Color> = when (type) {
    MomentType.COMMUTE_BRIEFING -> Icons.Rounded.Work to Y.Blue
    MomentType.CHARGE_NEEDED -> Icons.Rounded.EvStation to Y.Amber
    MomentType.CHARGING_SESSION -> Icons.Rounded.EvStation to Y.Ev
    MomentType.FATIGUE -> Icons.Rounded.Bedtime to Y.Rest
    MomentType.FAMILY_BREAK -> Icons.Rounded.FamilyRestroom to Y.Kids
    MomentType.PET_CARE -> Icons.Rounded.Pets to Y.Pet
    MomentType.VEHICLE_ISSUE -> Icons.Rounded.Build to Y.Amber
    MomentType.TRAFFIC -> Icons.Rounded.Traffic to Y.Amber
    MomentType.REST -> Icons.Rounded.Coffee to Y.Rest
    MomentType.ROUTINE -> Icons.Rounded.AcUnit to Y.Cyan
    MomentType.PARKING -> Icons.Rounded.LocalParking to Y.Cyan
    MomentType.REAR_SEAT -> Icons.Rounded.ChildCare to Y.Red
    MomentType.WEATHER -> Icons.Rounded.WbSunny to Y.Kids
}

@Composable
fun HomeScreen(store: YeopjariStore, layout: Layout) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val moments by store.moments.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    val parking by store.parking.collectAsStateWithLifecycle()
    val hero = moments.firstOrNull()
    val pad = if (layout.expanded) 28.dp else 18.dp

    if (layout.restricted) {
        // ── 주행 중 글랜스 모드: 한 장의 카드 + 큰 버튼 3개 ──
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(if (layout.expanded) 1.6f else 1f).fillMaxHeight()) {
                    if (hero != null) MomentGlance(hero, store) else DrivingCalm(store, layout)
                }
                if (layout.expanded) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        GlanceTile("도착까지", if (ctx.route.active) "${ctx.route.remainingMin}분" else "—", ctx.route.destination?.name ?: "안내 없음", Y.Blue, Modifier.weight(1f))
                        val arr = ctx.energy.arrivalSoc?.roundToInt()
                        GlanceTile("배터리", "${ctx.vehicle.socPercent.roundToInt()}%", arr?.let { "도착 예상 ${it.coerceAtLeast(0)}%" } ?: "${ctx.vehicle.rangeKm}km 주행 가능", if ((arr ?: 50) < 10) Y.Amber else Y.Ev, Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("말하기", Modifier.weight(1f), icon = Icons.Rounded.Mic) { store.startVoice() }
                BigButton("쉬어 가기", Modifier.weight(1f), icon = Icons.Rounded.Coffee, primary = false) { store.send("다음 휴게소 들렀다 가자") }
                BigButton("집으로", Modifier.weight(1f), icon = Icons.Rounded.Home, primary = false) { store.send("집으로 가자") }
            }
        }
        return
    }

    val scroll = rememberScrollState()
    if (layout.expanded) {
        Row(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.35f).fillMaxHeight().verticalScroll(scroll).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (hero != null) MomentHero(hero, store, glance = false) else Greeting(store, layout)
                if (moments.size > 1) {
                    Eyebrow("옆자리가 챙기고 있는 것 · ${moments.size - 1}")
                    moments.drop(1).take(4).forEach { MomentRow(it, store) }
                }
                parking?.let { ParkingCard(it.note, it.clockLabel, it.placeLabel) }
                QuickAsk(store)
                TodayCard(store)
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                VehicleCard(store)
                RouteCard(store)
                AiStatusCard(store)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(pad).padding(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (hero != null) MomentHero(hero, store, glance = false) else Greeting(store, layout)
            moments.drop(1).take(3).forEach { MomentRow(it, store) }
            parking?.let { ParkingCard(it.note, it.clockLabel, it.placeLabel) }
            VehicleCard(store)
            RouteCard(store)
            QuickAsk(store)
            AiStatusCard(store)
        }
    }
}

@Composable
private fun GlanceTile(label: String, value: String, sub: String, color: Color, modifier: Modifier) {
    GlassCard(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Y.Text3)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.displayLarge, color = color)
        Text(sub, style = MaterialTheme.typography.titleMedium, color = Y.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DrivingCalm(store: YeopjariStore, layout: Layout) {
    val phase by store.phase.collectAsStateWithLifecycle()
    val ctx by store.context.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxSize(), padding = 32.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiOrb(phase, 132.dp, still = true)
            Spacer(Modifier.width(24.dp))
            Column {
                Text("운전에만 집중하세요", style = MaterialTheme.typography.displaySmall, color = Y.Text1)
                Text("필요한 순간엔 제가 먼저 말씀드릴게요.", style = MaterialTheme.typography.titleLarge, color = Y.Text2)
            }
        }
        Spacer(Modifier.weight(1f))
        Eyebrow("옆자리가 지켜보는 중")
        Spacer(Modifier.height(14.dp))
        val e = ctx.energy.arrivalSoc
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Watch("졸음 케어", "${ctx.fatigue.levelLabel} · 연속 ${ctx.vehicle.continuousDriveMin}분", Icons.Rounded.Bedtime, Y.Rest, Modifier.weight(1f))
            Watch("배터리", e?.let { "도착 예상 ${it.roundToInt().coerceAtLeast(0)}%" } ?: "${ctx.vehicle.socPercent.roundToInt()}% · 이상 없음", Icons.Rounded.EvStation, Y.Ev, Modifier.weight(1f))
            Watch("알림 관리", "운전 부하 ${ctx.load.level.label}", Icons.Rounded.Traffic, Y.Blue, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Watch(title: String, sub: String, icon: ImageVector, color: Color, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(Y.Bg3).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, color, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Y.Text1)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Y.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 주행 중 글랜스 카드 — 큰 수치 1개 + 제목 + 한 문장 + 버튼 2개 (1~2초 인지) */
@Composable
fun MomentGlance(m: Moment, store: YeopjariStore) {
    val (icon, color) = momentVisual(m.type)
    val critical = m.urgency == Urgency.CRITICAL
    GlassCard(Modifier.fillMaxSize(), accent = color, padding = 32.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 64.dp)
            Spacer(Modifier.width(16.dp))
            Text(m.type.label, style = MaterialTheme.typography.headlineSmall, color = color)
            Spacer(Modifier.width(14.dp))
            TierBadge(m.tier)
            Spacer(Modifier.weight(1f))
            if (critical) Pill("지금 확인", color = Y.Red, bg = Y.Red.copy(alpha = 0.14f))
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.metric != null) {
                Column {
                    Text(m.metric, style = MaterialTheme.typography.displayLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(112f, androidx.compose.ui.unit.TextUnitType.Sp)), color = color, fontWeight = FontWeight.Bold)
                    m.metricLabel?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = Y.Text3) }
                }
                Spacer(Modifier.width(36.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.displaySmall, color = Y.Text1)
                Spacer(Modifier.height(12.dp))
                Text(m.body.substringBefore(". ").trimEnd('.') + ".", style = MaterialTheme.typography.titleLarge, color = Y.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            m.actions.take(2).forEachIndexed { i, a ->
                BigButton(a.label, Modifier.weight(1f).height(92.dp), primary = i == 0, color = if (critical) Y.Red else color) { store.onMomentAction(m, a.id) }
            }
        }
    }
}

/** 오늘 옆자리가 한 일 — 선제 제안의 가치를 수치로 */
@Composable
private fun TodayCard(store: YeopjariStore) {
    val routines by store.routines.collectAsStateWithLifecycle()
    val gleo by store.gleoCalls.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth()) {
        Eyebrow("이번 주 옆자리가 챙긴 것")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("선제 제안", "23", "회", color = Y.Cyan, modifier = Modifier.weight(1f))
            StatTile("루틴 실행", "${routines.sumOf { it.runsThisMonth }}", "회", color = Y.Periwinkle, modifier = Modifier.weight(1f))
            StatTile("터치 대신 음성", "41", "회", color = Y.Ev, modifier = Modifier.weight(1f))
            StatTile("Gleo 연동", "${gleo + 6}", "회", color = Y.Violet, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Text("“글레오야, 옆자리로 강릉 가는 충전 계획 세워줘” — 차량 기본 AI에서도 옆자리를 바로 부를 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = Y.Text3)
    }
}

/** 하이브리드 AI 상태 */
@Composable
private fun AiStatusCard(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val s by store.settings.collectAsStateWithLifecycle()
    val src by store.sourceMode.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth(), onClick = { store.go(Screen.SETTINGS) }) {
        Eyebrow("옆자리 AI 상태")
        Spacer(Modifier.height(12.dp))
        StatusLine("온디바이스 SLM", "의도 분석·단순 명령 · 네트워크 불필요", Y.TierEdge, true)
        StatusLine("클라우드 추론 LLM", if (!ctx.flags.online) "오프라인 — 온디바이스 대체 응답" else if (s.cloudEnabled) "복합 계획·진단·루틴 생성" else "사용 안 함", Y.TierCloud, ctx.flags.online && s.cloudEnabled)
        StatusLine("음성 (Pleos STT/TTS)", if (store.speechIn.available) "온디바이스 · 연결됨" else "이 기기에서 미지원", Y.Cyan, store.speechIn.available)
        StatusLine("차량 데이터", if (src == com.yeopjari.app.core.gateway.Source.PLEOS_SDK) "Pleos SDK 실시간" else "시나리오 시뮬레이터", Y.Ev, true)
    }
}

@Composable
private fun StatusLine(title: String, sub: String, color: Color, on: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) color else Y.Bg4))
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Y.Text1, modifier = Modifier.width(200.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Y.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 히어로 모먼트 카드 — 지금 가장 중요한 한 가지 */
@Composable
fun MomentHero(m: Moment, store: YeopjariStore, glance: Boolean) {
    val (icon, color) = momentVisual(m.type)
    val critical = m.urgency == Urgency.CRITICAL
    GlassCard(Modifier.fillMaxWidth().then(if (glance) Modifier.fillMaxHeight() else Modifier), accent = color, padding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.type.label, style = MaterialTheme.typography.labelLarge, color = color)
                    Spacer(Modifier.width(10.dp))
                    if (critical) Pill("지금 확인", color = Y.Red, bg = Y.Red.copy(alpha = 0.14f))
                }
                Spacer(Modifier.height(4.dp))
                TierBadge(m.tier)
            }
            if (m.metric != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(m.metric, style = MaterialTheme.typography.displayMedium, color = color)
                    m.metricLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Y.Text3) }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(m.title, style = if (glance) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge, color = Y.Text1)
        if (!glance || m.body.length < 70) {
            Spacer(Modifier.height(10.dp))
            Text(m.body, style = MaterialTheme.typography.bodyLarge, color = Y.Text2, maxLines = if (glance) 2 else 5, overflow = TextOverflow.Ellipsis)
        }
        if (glance) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            m.actions.take(if (glance) 2 else 3).forEachIndexed { i, a ->
                BigButton(a.label, Modifier.weight(1f), primary = i == 0, color = if (critical) Y.Red else color) { store.onMomentAction(m, a.id) }
            }
        }
    }
}

@Composable
private fun MomentRow(m: Moment, store: YeopjariStore) {
    val (icon, color) = momentVisual(m.type)
    GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 52.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.titleLarge, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.body, style = MaterialTheme.typography.bodyMedium, color = Y.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            m.actions.firstOrNull()?.let { a ->
                BigButton(a.label, primary = false) { store.onMomentAction(m, a.id) }
            }
        }
    }
}

@Composable
private fun Greeting(store: YeopjariStore, layout: Layout) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth(), accent = Y.Periwinkle, padding = 30.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiOrb(phase, if (layout.expanded) 150.dp else 110.dp)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(greet(ctx.hour) + ", ${ctx.profile.name}님", style = MaterialTheme.typography.headlineLarge, color = Y.Text1)
                Spacer(Modifier.height(6.dp))
                Text("지금은 챙길 일이 없어요. 운전대 밖의 일은 옆자리에게 맡겨 주세요.", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton("말하기", Modifier.weight(1f), icon = Icons.Rounded.Mic) { store.startVoice() }
            BigButton("회사로", Modifier.weight(1f), icon = Icons.Rounded.Work, primary = false) { store.send("회사로 가자") }
            BigButton("시연 시나리오", Modifier.weight(1f), icon = Icons.Rounded.Flag, primary = false, color = Y.Violet) { store.demoPanel.value = true }
        }
    }
}

private fun greet(h: Int) = when (h) { in 5..10 -> "좋은 아침이에요"; in 11..16 -> "좋은 오후예요"; in 17..21 -> "편안한 저녁이에요"; else -> "늦은 시간이네요" }

@Composable
private fun ParkingCard(note: String, clock: String, place: String) {
    GlassCard(Modifier.fillMaxWidth(), accent = Y.Cyan, padding = 22.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.LocalParking, Y.Cyan, 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("주차 위치 메모", style = MaterialTheme.typography.labelLarge, color = Y.Cyan)
                Text(note, style = MaterialTheme.typography.headlineSmall, color = Y.Text1)
                Text("$place · $clock 저장 · 휴대폰으로 전송됨", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAsk(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val suggestions = buildList {
        if (ctx.route.active) add("도착하면 배터리 몇 % 남아?") else add("오늘 브리핑 해줘")
        add("아이들이랑 강릉 가는데 충전이랑 점심까지 계획 세워줘")
        add("매일 아침 8시에 출발하면 회사로 안내하고 21도로 맞춰줘")
        add("오토홀드 어떻게 켜?")
    }
    Column {
        Eyebrow("옆자리에게 물어보기")
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            suggestions.forEach { s -> SuggestionChip(s) { store.openAgent(); store.send(s) } }
        }
    }
}

@Composable
private fun VehicleCard(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val v = ctx.vehicle
    GlassCard(Modifier.fillMaxWidth(), onClick = { store.go(Screen.CHARGE) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SocRing(v.socPercent, 168.dp, charging = v.isCharging) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${v.socPercent.roundToInt()}%", style = MaterialTheme.typography.displaySmall, color = Y.Text1, fontWeight = FontWeight.Bold)
                    Text("${v.rangeKm} km", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
                }
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (v.isCharging) "충전 중 · ${v.chargePowerKw.roundToInt()} kW" else "주행 가능 거리 ${v.rangeKm} km", style = MaterialTheme.typography.titleLarge, color = if (v.isCharging) Y.Ev else Y.Text1)
                Text("충전 한도 ${v.chargeLimitPercent}% · 전비 ${"%.1f".format(ctx.energy.effectiveKmPerKwh)} km/kWh", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (v.hvac.power) "실내 ${v.cabinTempC.roundToInt()}° → ${"%.1f".format(v.hvac.setTempC)}°" else "실내 ${v.cabinTempC.roundToInt()}° · 공조 꺼짐", icon = Icons.Rounded.Thermostat)
                    if (!v.hvac.recirculation) Pill("외기", icon = Icons.Rounded.AcUnit)
                }
            }
            CarTopView(
                listOf(v.doors.driver, v.doors.passenger, v.doors.rearLeft, v.doors.rearRight, v.doors.trunk),
                listOf(Seat.DRIVER in v.occupants, Seat.PASSENGER in v.occupants, Seat.REAR_LEFT in v.occupants, Seat.REAR_RIGHT in v.occupants),
                Modifier.size(110.dp, 160.dp), tpms = Telltale.TPMS in v.telltales,
            )
        }
    }
}

@Composable
private fun RouteCard(store: YeopjariStore) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val r = ctx.route
    GlassCard(Modifier.fillMaxWidth()) {
        Eyebrow("길 안내 · NaviHelper")
        Spacer(Modifier.height(10.dp))
        if (!r.active) {
            Text("안내 중인 경로가 없어요", style = MaterialTheme.typography.titleLarge, color = Y.Text2)
            return@GlassCard
        }
        if (r.remainingKm < 0.5f && ctx.vehicle.speedKph < 1f) {
            Text("도착했어요 · ${r.destination?.name ?: "목적지"}", style = MaterialTheme.typography.headlineSmall, color = Y.Ev, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("배터리 ${ctx.vehicle.socPercent.roundToInt()}% · 다음 일정까지 충분해요", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
            return@GlassCard
        }
        Text(r.destination?.name ?: "목적지", style = MaterialTheme.typography.headlineMedium, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Y.Bg4)) {
            Box(Modifier.fillMaxWidth(r.progress.coerceAtLeast(0.02f)).height(12.dp).clip(CircleShape).background(Y.AiGradientH))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("남은 시간", "${r.remainingMin}", "분", modifier = Modifier.weight(1f))
            StatTile("남은 거리", "${r.remainingKm.roundToInt()}", "km", modifier = Modifier.weight(1f))
            val arr = ctx.energy.arrivalSoc?.roundToInt()
            StatTile("도착 SOC", arr?.let { "${it.coerceAtLeast(0)}" } ?: "-", "%", color = if ((arr ?: 50) < 10) Y.Amber else Y.Ev, modifier = Modifier.weight(1f))
        }
        if (r.waypoints.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("경유: " + r.waypoints.joinToString(" → ") { it.name }, style = MaterialTheme.typography.bodyMedium, color = Y.Cyan)
        }
        if (r.nextGuidance.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text((r.nextTurnMeters?.let { "${it}m · " } ?: "") + r.nextGuidance, style = MaterialTheme.typography.bodyMedium, color = Y.Text3)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipsRow(items: List<Pair<String, () -> Unit>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (t, a) -> SuggestionChip(t, onClick = a) }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String, icon: ImageVector, color: Color, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, color, 60.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Y.Text1)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Y.Text2, maxLines = 2)
        }
        trailing()
    }
}

@Composable
fun RestrictedNotice(text: String = "주행 중에는 핵심 정보만 보여 드려요. 정차하면 전체 화면이 열려요.") {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Y.Amber.copy(alpha = 0.12f)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bedtime, null, tint = Y.Amber, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Y.Amber)
    }
}
