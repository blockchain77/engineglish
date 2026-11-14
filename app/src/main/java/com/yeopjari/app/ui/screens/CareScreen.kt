package com.yeopjari.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.BigButton
import com.yeopjari.app.ui.components.Eyebrow
import com.yeopjari.app.ui.components.GlassCard
import com.yeopjari.app.ui.components.IconBadge
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.ProgressRing
import com.yeopjari.app.ui.components.SectionTitle
import com.yeopjari.app.ui.components.StatTile
import com.yeopjari.app.ui.components.Stepper
import com.yeopjari.app.ui.theme.Y
import kotlin.math.roundToInt

@Composable
fun CareScreen(store: YeopjariStore, layout: Layout, onShowcase: () -> Unit) {
    val pet by store.pet.collectAsStateWithLifecycle()
    val rest by store.rest.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(if (rest.active && !pet.active) 1 else 0) }
    val pad = if (layout.expanded) 28.dp else 18.dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pad, vertical = 8.dp).padding(bottom = if (layout.expanded) 24.dp else 110.dp)) {
        ScreenHeader("머무는 차 · 케어", "차에 머무는 순간을 지켜요 — 반려동물, 휴식, 아이", Icons.Rounded.Spa, Y.Rest)
        if (layout.restricted) { RestrictedNotice("케어 모드는 정차 중에만 시작할 수 있어요. 아이 퀴즈는 음성으로 계속돼요."); Spacer(Modifier.height(18.dp)) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Y.Bg2).padding(6.dp)) {
            listOf(Triple("펫 케어", Icons.Rounded.Pets, Y.Pet), Triple("쉼 모드", Icons.Rounded.Bedtime, Y.Rest), Triple("아이 동승", Icons.Rounded.ChildCare, Y.Kids)).forEachIndexed { i, (t, ic, c) ->
                Row(
                    Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(20.dp)).background(if (tab == i) Y.Bg4 else Color.Transparent).clickable { tab = i },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(ic, null, tint = if (tab == i) c else Y.Text3, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(t, style = MaterialTheme.typography.titleMedium, color = if (tab == i) Y.Text1 else Y.Text3)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        when (tab) {
            0 -> { PetTab(store, layout, onShowcase); Spacer(Modifier.height(20.dp)); PetGuide(layout) }
            1 -> RestTab(store, layout)
            else -> KidsTab(store, layout)
        }
    }
}

@Composable
private fun PetTab(store: YeopjariStore, layout: Layout, onShowcase: () -> Unit) {
    val pet by store.pet.collectAsStateWithLifecycle()
    val ctx by store.context.collectAsStateWithLifecycle()
    val v = ctx.vehicle
    if (!pet.active) {
        GlassCard(Modifier.fillMaxWidth(), accent = Y.Pet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Pets, Y.Pet, 72.dp)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("${ctx.flags.petName}를 잠깐 차에 두고 내려야 할 때", style = MaterialTheme.typography.headlineSmall, color = Y.Text1)
                    Text("실내 온도를 유지하고, 지나가는 사람에게 '반려동물이 안전하게 있어요'를 보여 줘요. 배터리 하한선 아래로는 내려가지 않아요.", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("외기온", "${v.outsideTempC.roundToInt()}", "°C", modifier = Modifier.weight(1f))
                StatTile("실내", "${v.cabinTempC.roundToInt()}", "°C", modifier = Modifier.weight(1f))
                StatTile("배터리", "${v.socPercent.roundToInt()}", "%", color = Y.Ev, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            BigButton(if (layout.restricted) "정차 후 시작할 수 있어요" else "펫 케어 모드 시작 · 22°C", Modifier.fillMaxWidth(), icon = Icons.Rounded.Pets, color = Y.Pet, enabled = !layout.restricted) { store.startPetMode(22f) }
            Spacer(Modifier.height(12.dp))
            Text("Gleo에게 \"글레오야, 옆자리 펫 케어 켜줘\"라고 말해도 시작돼요 (App Schema: START_CARE_MODE).", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
        }
        return
    }
    val elapsed = (((ctx.clockSec - pet.startedClockSec) + 86400f) % 86400f / 60f).roundToInt()
    GlassCard(Modifier.fillMaxWidth(), accent = Y.Pet) {
        SectionTitle("펫 케어 작동 중") { Pill("${elapsed}분째", color = Y.Pet, bg = Y.Pet.copy(alpha = 0.14f)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${ctx.flags.petName}가 편안하게 있어요", style = MaterialTheme.typography.headlineMedium, color = Y.Text1)
                Spacer(Modifier.height(8.dp))
                Text("실내 ${"%.1f".format(v.cabinTempC)}°C · 목표 ${pet.targetC.roundToInt()}°C · 배터리 ${v.socPercent.roundToInt()}% (하한 ${pet.minSoc}%)", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
                Spacer(Modifier.height(18.dp))
                Stepper("${pet.targetC.roundToInt()}°", onMinus = { store.startPetMode((pet.targetC - 1).coerceAtLeast(18f)) }, onPlus = { store.startPetMode((pet.targetC + 1).coerceAtMost(26f)) })
            }
            ProgressRing(((v.cabinTempC - v.outsideTempC) / (pet.targetC - v.outsideTempC).let { if (it == 0f) 1f else it }).coerceIn(0f, 1f), 170.dp, Y.Pet) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${v.cabinTempC.roundToInt()}°", style = MaterialTheme.typography.displayMedium, color = Y.Text1)
                    Text("실내", style = MaterialTheme.typography.labelMedium, color = Y.Text3)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("외부 안내 화면", Modifier.weight(1f), icon = Icons.Rounded.Fullscreen, color = Y.Pet, onClick = onShowcase)
            BigButton("종료", Modifier.weight(1f), primary = false) { store.stopPetMode() }
        }
        Spacer(Modifier.height(12.dp))
        Text("보호자 휴대폰으로 실내 온도·배터리를 5분마다 알려 드려요 (서버 푸시, 향후 연동).", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
    }
}

@Composable
private fun RestTab(store: YeopjariStore, layout: Layout) {
    val rest by store.rest.collectAsStateWithLifecycle()
    val ctx by store.context.collectAsStateWithLifecycle()
    if (!rest.active) {
        GlassCard(Modifier.fillMaxWidth(), accent = Y.Rest) {
            Text("15분이면 충분해요", style = MaterialTheme.typography.headlineMedium, color = Y.Text1)
            Spacer(Modifier.height(8.dp))
            Text("졸음쉼터나 충전 중에 시트를 눕히고 쉬세요. 공조는 조용하게, 끝나면 시원한 바람과 함께 부드럽게 깨워 드려요.", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(10, 15, 20).forEach { m -> BigButton("${m}분", Modifier.weight(1f), primary = m == 15, color = Y.Rest, enabled = !layout.restricted) { store.startRestMode(m) } }
            }
        }
        return
    }
    val elapsedMin = (((ctx.clockSec - rest.startedClockSec) + 86400f) % 86400f / 60f)
    val progress = (elapsedMin / rest.minutes).coerceIn(0f, 1f)
    val t = rememberInfiniteTransition(label = "breath")
    val breath by t.animateFloat(0.82f, 1.08f, infiniteRepeatable(tween(4000), RepeatMode.Reverse), label = "b")
    GlassCard(Modifier.fillMaxWidth(), accent = Y.Rest) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(200.dp).scale(breath).clip(CircleShape).background(Brush.radialGradient(listOf(Y.Rest.copy(alpha = 0.55f), Y.Rest.copy(alpha = 0.05f)))))
                ProgressRing(progress, 240.dp, Y.Rest, 10.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(rest.minutes - elapsedMin).roundToInt().coerceAtLeast(0)}분", style = MaterialTheme.typography.displayMedium, color = Y.Text1)
                        Text(if (breath > 0.95f) "들이쉬고" else "내쉬고", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("쉼 모드", style = MaterialTheme.typography.headlineMedium, color = Y.Text1)
                Text("실내 22°C · 풍량 2단 · 알림 무음", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
                Text("끝나기 1분 전 외기 유입과 함께 깨워 드려요", style = MaterialTheme.typography.bodyMedium, color = Y.Text3)
                BigButton("종료", primary = false) { store.stopRestMode() }
            }
        }
    }
}

@Composable
private fun KidsTab(store: YeopjariStore, layout: Layout) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val kids by store.kids.collectAsStateWithLifecycle()
    val r = ctx.route
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassCard(Modifier.weight(1f), accent = Y.Kids) {
            Eyebrow("\"아직 멀었어?\" 에 대한 대답", Y.Kids)
            Spacer(Modifier.height(12.dp))
            val episodes = (r.remainingMin / 12).coerceAtLeast(1)
            Text(if (r.active) "만화 ${episodes}편 보면 도착!" else "어디로 갈까요?", style = MaterialTheme.typography.displaySmall, color = Y.Text1)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(28.dp).clip(CircleShape).background(Y.Bg4)) {
                Box(Modifier.fillMaxWidth(r.progress.coerceAtLeast(0.04f)).height(28.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Y.Kids, Y.Pet))))
            }
            Spacer(Modifier.height(10.dp))
            Text("${(r.progress * 100).roundToInt()}% 왔어요 · ${r.destination?.name ?: ""}", style = MaterialTheme.typography.titleMedium, color = Y.Text2)
        }
        if (layout.expanded) GlassCard(Modifier.weight(1f)) {
            Eyebrow("아이 맞춤 · ${ctx.profile.kids.joinToString { "${it.first}(${it.second})" }}")
            Spacer(Modifier.height(12.dp))
            Text("목적지와 연결된 퀴즈·오디오 이야기를 만들어요. 운전자는 음성만 듣고, 화면 조작은 정차 중 또는 동승석에서만 가능해요.", style = MaterialTheme.typography.bodyLarge, color = Y.Text2)
        }
    }
    Spacer(Modifier.height(20.dp))
    GlassCard(Modifier.fillMaxWidth(), accent = Y.Kids) {
        SectionTitle("바다 동물 퀴즈") { Pill("점수 ${kids.score}", color = Y.Kids, bg = Y.Kids.copy(alpha = 0.12f)) }
        Text("다리가 8개이고 먹물을 쏘는 바다 동물은?", style = MaterialTheme.typography.headlineSmall, color = Y.Text1)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("문어", "오징어", "꽃게").forEach { c -> BigButton(c, Modifier.weight(1f), primary = false, color = Y.Kids) { store.answerQuiz(c) } }
        }
        kids.lastAnswerCorrect?.let { Text(if (it) "정답이에요! 🎉" else "아쉬워요, 정답은 문어!", style = MaterialTheme.typography.titleLarge, color = if (it) Y.Ev else Y.Amber, modifier = Modifier.padding(top = 14.dp)) }
        Spacer(Modifier.height(10.dp))
        BigButton("AI가 새 퀴즈 만들기", primary = false, color = Y.Kids) { store.openAgent(); store.send("아이들 심심해하는데 퀴즈 해줘") }
    }
}

/** 펫 케어 안전 원칙 + 반려견 동반 장소 */
@Composable
private fun PetGuide(layout: Layout) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        GlassCard(Modifier.weight(1f)) {
            Eyebrow("펫 케어 안전 원칙")
            Spacer(Modifier.height(12.dp))
            listOf(
                "배터리 하한 20% — 도달 시 공조를 절전 단계로 낮추고 보호자에게 알림",
                "최대 60분 — 그 이후에는 반복 알림으로 복귀 유도",
                "문 잠김·창문 닫힘 확인 후 시작 (Door SDK)",
                "보조 기능이에요. 반려동물을 장시간 차에 두지 마세요",
            ).forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = Y.Text2, modifier = Modifier.padding(vertical = 3.dp)) }
        }
        if (layout.expanded) GlassCard(Modifier.weight(1f)) {
            Eyebrow("반려견 동반 장소 · 경로 근처")
            Spacer(Modifier.height(12.dp))
            listOf("덕평자연휴게소" to "반려견 놀이터 · 산책로", "횡성휴게소 (강릉방향)" to "야외 테라스 동반 가능", "경포해변 산책로" to "목줄 착용 시 동반 가능").forEach { (n, d) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Pets, null, tint = Y.Pet, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(n, style = MaterialTheme.typography.titleSmall, color = Y.Text1)
                        Text(d, style = MaterialTheme.typography.bodySmall, color = Y.Text3)
                    }
                }
            }
        }
    }
}

/** 펫 케어 외부 안내 화면 — 창밖에서도 보이도록 크게 */
@Composable
fun PetShowcase(store: YeopjariStore, onClose: () -> Unit) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val pet by store.pet.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF071B1E), Color(0xFF04090D)))).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
            Icon(Icons.Rounded.Pets, null, tint = Y.Pet, modifier = Modifier.size(120.dp))
            Spacer(Modifier.height(24.dp))
            Text("반려견이 안전하게 쉬고 있어요", style = MaterialTheme.typography.displayLarge, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Text("My dog is safe and comfortable", style = MaterialTheme.typography.headlineMedium, color = Y.Text2)
            Spacer(Modifier.height(40.dp))
            Text("${"%.1f".format(ctx.vehicle.cabinTempC)}°C", style = MaterialTheme.typography.displayLarge.copy(fontSize = 140.sp), color = Y.Ev, fontWeight = FontWeight.Bold)
            Text("실내 온도 유지 중 · 목표 ${pet.targetC.roundToInt()}°C", style = MaterialTheme.typography.headlineSmall, color = Y.Text2)
            Spacer(Modifier.height(30.dp))
            Text("보호자가 곧 돌아옵니다. 걱정해 주셔서 감사해요.", style = MaterialTheme.typography.headlineSmall, color = Y.Text1)
            Spacer(Modifier.height(30.dp))
            Text("화면을 누르면 돌아가요", style = MaterialTheme.typography.labelLarge, color = Y.Text3)
        }
    }
}
