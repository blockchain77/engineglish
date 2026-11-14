package com.yeopjari.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.Screen
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.ai.context.LoadLevel
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.model.AgentPhase
import com.yeopjari.app.ui.components.AiOrb
import com.yeopjari.app.ui.components.Dot
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.screens.AgentPanel
import com.yeopjari.app.ui.screens.CareScreen
import com.yeopjari.app.ui.screens.CarScreen
import com.yeopjari.app.ui.screens.ChargeScreen
import com.yeopjari.app.ui.screens.DemoPanel
import com.yeopjari.app.ui.screens.HomeScreen
import com.yeopjari.app.ui.screens.PetShowcase
import com.yeopjari.app.ui.screens.RoutinesScreen
import com.yeopjari.app.ui.screens.SafetyScreen
import com.yeopjari.app.ui.screens.SettingsScreen
import com.yeopjari.app.ui.theme.Y
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class NavItem(val screen: Screen, val icon: ImageVector, val label: String)

private val navItems = listOf(
    NavItem(Screen.HOME, Icons.Rounded.Home, "홈"),
    NavItem(Screen.CHARGE, Icons.Rounded.EvStation, "충전"),
    NavItem(Screen.CARE, Icons.Rounded.Spa, "케어"),
    NavItem(Screen.SAFETY, Icons.Rounded.Shield, "안전"),
    NavItem(Screen.CAR, Icons.Rounded.DirectionsCar, "차량"),
    NavItem(Screen.ROUTINES, Icons.Rounded.AutoMode, "루틴"),
    NavItem(Screen.SETTINGS, Icons.Rounded.Tune, "AI 설정"),
)

/** 화면 폭 정보 — Pleos 폼팩터: 반쪽 세로 창(628~840dp) ~ 전체 가로(1360~2560dp) */
data class Layout(val expanded: Boolean, val wide: Boolean, val restricted: Boolean)

@Composable
fun YeopjariAppUi(store: YeopjariStore) {
    val screen by store.screen.collectAsStateWithLifecycle()
    val restricted by store.restricted.collectAsStateWithLifecycle()
    val overlay by store.agentOverlay.collectAsStateWithLifecycle()
    val demo by store.demoPanel.collectAsStateWithLifecycle()
    val pet by store.pet.collectAsStateWithLifecycle()
    val toast by store.toast.collectAsStateWithLifecycle()
    val caption by store.caption.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    var showcase = remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(toast) { if (toast != null) { delay(2600); store.clearToast() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Y.Bg0)) {
        val expanded = maxWidth >= 1000.dp
        val layout = Layout(expanded = expanded, wide = maxWidth >= 1700.dp, restricted = restricted)

        val content: @Composable () -> Unit = {
            Crossfade(screen, label = "screen") { s ->
                when (s) {
                    Screen.HOME, Screen.AGENT -> HomeScreen(store, layout)
                    Screen.CHARGE -> ChargeScreen(store, layout)
                    Screen.CARE -> CareScreen(store, layout, onShowcase = { showcase.value = true })
                    Screen.SAFETY -> SafetyScreen(store, layout)
                    Screen.CAR -> CarScreen(store, layout)
                    Screen.ROUTINES -> RoutinesScreen(store, layout)
                    Screen.SETTINGS -> SettingsScreen(store, layout)
                }
            }
        }

        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                NavRail(store, screen, phase, restricted)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(store, compact = false)
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(store, compact = true)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content()
                    Box(Modifier.align(Alignment.BottomEnd).padding(20.dp).size(96.dp).clip(CircleShape).clickable { store.startVoice() }, contentAlignment = Alignment.Center) {
                        AiOrb(phase, 96.dp, still = restricted)
                    }
                }
                BottomBar(store, screen)
            }
        }

        // 음성 응답 자막 (에이전트 패널이 닫혀 있을 때)
        AnimatedVisibility(
            visible = caption != null && !overlay && !expanded,
            enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (expanded) 74.dp else 70.dp, start = 24.dp, end = 24.dp),
        ) {
            Row(
                Modifier.widthIn(max = 980.dp).clip(RoundedCornerShape(26.dp)).background(Y.Bg3.copy(alpha = 0.96f)).border(1.dp, Y.Stroke, RoundedCornerShape(26.dp))
                    .clickable { store.openAgent() }.padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AiOrb(phase, 52.dp, still = restricted)
                Spacer(Modifier.width(14.dp))
                Text(caption ?: "", style = MaterialTheme.typography.titleMedium, color = Y.Text1, maxLines = 2)
            }
        }

        // 옆자리 AI 패널
        AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) { store.closeAgent() })
        }
        AnimatedVisibility(
            visible = overlay,
            enter = if (expanded) slideInHorizontally { it } else slideInVertically { it },
            exit = if (expanded) slideOutHorizontally { it } else slideOutVertically { it },
            modifier = Modifier.align(if (expanded) Alignment.CenterEnd else Alignment.BottomCenter),
        ) {
            Box(if (expanded) Modifier.fillMaxHeight().width(if (layout.wide) 820.dp else 640.dp) else Modifier.fillMaxSize()) {
                AgentPanel(store, layout)
            }
        }

        // 데모 패널 (시나리오 디렉터)
        AnimatedVisibility(visible = demo, enter = fadeIn(), exit = fadeOut()) {
            DemoPanel(store, layout)
        }

        // 펫 케어 외부 표시 화면
        if (pet.active && showcase.value) PetShowcase(store) { showcase.value = false }

        AnimatedVisibility(
            visible = toast != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (expanded) 36.dp else 130.dp),
        ) {
            Text(
                toast ?: "", style = MaterialTheme.typography.titleSmall, color = Y.Bg0,
                modifier = Modifier.clip(CircleShape).background(Y.Text1).padding(horizontal = 26.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun NavRail(store: YeopjariStore, current: Screen, phase: AgentPhase, restricted: Boolean) {
    Column(
        Modifier.fillMaxHeight().width(118.dp).background(Y.Bg1).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(100.dp).clip(CircleShape).clickable { store.startVoice() }, contentAlignment = Alignment.Center) {
            AiOrb(phase, 100.dp, still = restricted)
        }
        Text("옆자리", style = MaterialTheme.typography.labelLarge, color = Y.Text2)
        Spacer(Modifier.height(20.dp))
        navItems.forEach { item ->
            RailItem(item, current == item.screen) { store.go(item.screen) }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Y.Bg3).clickable { store.demoPanel.value = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Science, "데모", tint = Y.Violet, modifier = Modifier.size(30.dp)) }
        Text("시연", style = MaterialTheme.typography.labelSmall, color = Y.Text3, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RailItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.padding(vertical = 5.dp).width(96.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) Y.Bg3 else Color.Transparent).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(item.icon, item.label, tint = if (selected) Y.Cyan else Y.Text3, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(4.dp))
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = if (selected) Y.Text1 else Y.Text3)
    }
}

@Composable
private fun BottomBar(store: YeopjariStore, current: Screen) {
    Row(
        Modifier.fillMaxWidth().background(Y.Bg1).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        navItems.forEach { item ->
            val sel = current == item.screen
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (sel) Y.Bg3 else Color.Transparent)
                    .clickable { store.go(item.screen) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, item.label, tint = if (sel) Y.Cyan else Y.Text3, modifier = Modifier.size(30.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = if (sel) Y.Text1 else Y.Text3, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TopBar(store: YeopjariStore, compact: Boolean) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val restricted by store.restricted.collectAsStateWithLifecycle()
    val source by store.sourceMode.collectAsStateWithLifecycle()
    val deferred by store.deferredCount.collectAsStateWithLifecycle()
    val v = ctx.vehicle
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 18.dp else 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) {
            Text("옆자리", style = MaterialTheme.typography.headlineSmall.copy(brush = Y.AiGradientH), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
        }
        Text(ctx.clockLabel, style = MaterialTheme.typography.headlineSmall, color = Y.Text1)
        Spacer(Modifier.width(14.dp))
        val modeColor = if (restricted) Y.Amber else if (v.isCharging) Y.Ev else Y.Text2
        Pill(
            if (restricted) "${v.driveMode.label} · ${v.speedKph.roundToInt()}km/h · 간소화" else "${v.driveMode.label} · ${v.gear.label}",
            color = modeColor, bg = modeColor.copy(alpha = 0.12f),
        )
        val caption by store.caption.collectAsStateWithLifecycle()
        val overlay by store.agentOverlay.collectAsStateWithLifecycle()
        if (!compact && caption != null && !overlay) {
            // 음성 응답 자막 — 상태바 안에 표시해 화면 콘텐츠를 가리지 않음
            Spacer(Modifier.width(14.dp))
            Row(
                Modifier.weight(1f).clip(CircleShape).background(Y.Periwinkle.copy(alpha = 0.14f)).clickable { store.openAgent() }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Y.Periwinkle, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(caption ?: "", style = MaterialTheme.typography.titleSmall, color = Y.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(16.dp))
        } else {
            if (!compact) {
                Spacer(Modifier.width(10.dp))
                val lc = when (ctx.load.level) { LoadLevel.LOW -> Y.Ev; LoadLevel.MEDIUM -> Y.Blue; LoadLevel.HIGH -> Y.Amber }
                Pill("운전 부하 ${ctx.load.level.label}", color = lc, bg = lc.copy(alpha = 0.10f))
            }
            if (deferred > 0) {
                Spacer(Modifier.width(10.dp))
                Pill("알림 ${deferred}건 보류", color = Y.Amber, bg = Y.Amber.copy(alpha = 0.12f), icon = Icons.Rounded.NotificationsPaused)
            }
            if (!ctx.flags.online) {
                Spacer(Modifier.width(10.dp))
                Pill("오프라인", color = Y.Text2, icon = Icons.Rounded.WifiOff)
            }
            Spacer(Modifier.weight(1f))
        }
        if (!compact) {
            Row(
                Modifier.clip(CircleShape).background(Y.Bg2).clickable { store.demoPanel.value = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(if (source == Source.PLEOS_SDK) Y.Ev else Y.Violet)
                Spacer(Modifier.width(8.dp))
                Text(if (source == Source.PLEOS_SDK) "Pleos SDK 실시간" else "시뮬레이터", style = MaterialTheme.typography.labelMedium, color = Y.Text2)
            }
            Spacer(Modifier.width(16.dp))
        } else {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Y.Bg2).clickable { store.demoPanel.value = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Science, "데모", tint = Y.Violet, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Text("${v.outsideTempC.roundToInt()}°", style = MaterialTheme.typography.titleLarge, color = Y.Text2)
        Spacer(Modifier.width(16.dp))
        Icon(Icons.Rounded.EvStation, null, tint = if (v.socPercent < 20) Y.Red else Y.Ev, modifier = Modifier.size(26.dp))
        Text("${v.socPercent.roundToInt()}%", style = MaterialTheme.typography.titleLarge, color = Y.Text1)
    }
}
