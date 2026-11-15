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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Psychology
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.yeopjari.app.core.model.Gear
import com.yeopjari.app.ui.components.AiOrb
import com.yeopjari.app.ui.components.Dot
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.screens.AgentPanel
import com.yeopjari.app.ui.screens.CareScreen
import com.yeopjari.app.ui.screens.CarScreen
import com.yeopjari.app.ui.screens.ChargeScreen
import com.yeopjari.app.ui.screens.DemoPanel
import com.yeopjari.app.ui.screens.HomeScreen
import com.yeopjari.app.ui.screens.PersonalScreen
import com.yeopjari.app.ui.screens.PetShowcase
import com.yeopjari.app.ui.screens.RoutinesScreen
import com.yeopjari.app.ui.screens.SafetyScreen
import com.yeopjari.app.ui.screens.SettingsScreen
import com.yeopjari.app.ui.theme.Y
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private data class NavItem(val screen: Screen, val icon: ImageVector, val label: String)

private val navItems = listOf(
    NavItem(Screen.PERSONAL, Icons.Rounded.Psychology, "맞춤 AI"),
    NavItem(Screen.HOME, Icons.Rounded.Home, "홈"),
    NavItem(Screen.CHARGE, Icons.Rounded.EvStation, "충전"),
    NavItem(Screen.CARE, Icons.Rounded.Spa, "케어"),
    NavItem(Screen.SAFETY, Icons.Rounded.Shield, "안전"),
    NavItem(Screen.CAR, Icons.Rounded.DirectionsCar, "차량"),
    NavItem(Screen.ROUTINES, Icons.Rounded.AutoMode, "루틴"),
    NavItem(Screen.SETTINGS, Icons.Rounded.Tune, "AI 설정"),
)

/**
 * 화면 폭 정보 — Pleos 폼팩터: 반쪽 세로 창(628~840dp) ~ 전체 가로(1360~2560dp)
 *
 * @param restricted 주의분산 제한 중 (AAOS CarUxRestrictions 또는 주행)
 * @param driving 주행 화면 유지 — 제한 중이거나, D단에서 선 지 5초가 안 됐을 때 (P단·충전이면 바로 정차 화면).
 *   신호 대기마다 화면이 뒤집히지 않게 하는 앱 전체에 하나뿐인 상태다.
 * @param drivingHud '맞춤 AI' 화면이 주행 HUD를 띄우는 중 ([driving] && 화면 = 맞춤 AI) —
 *   떠 있는 오브·자막 겹침을 숨기고, 마이크는 대화 패널을 열지 않고 듣기만 한다
 */
data class Layout(
    val expanded: Boolean,
    val wide: Boolean,
    val restricted: Boolean,
    val driving: Boolean = restricted,
    val drivingHud: Boolean = false,
)

/** D단에서 이만큼 서 있어야 정차 화면으로 바꾼다 (신호 대기마다 화면이 뒤집히지 않게) */
private const val STOP_TO_PARKED_MS = 5_000L

@Composable
fun YeopjariAppUi(store: YeopjariStore) {
    val screen by store.screen.collectAsStateWithLifecycle()
    val restricted by store.restricted.collectAsStateWithLifecycle()
    val overlay by store.agentOverlay.collectAsStateWithLifecycle()
    val demo by store.demoPanel.collectAsStateWithLifecycle()
    val source by store.sourceMode.collectAsStateWithLifecycle()
    val pet by store.pet.collectAsStateWithLifecycle()
    val toast by store.toast.collectAsStateWithLifecycle()
    val caption by store.caption.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    var showcase = remember { androidx.compose.runtime.mutableStateOf(false) }

    // 앱 전체가 같은 '주행 화면' 상태를 본다 (HUD 화면 고르기 · 오브 · 자막 · 패널 열기가 서로 어긋나지 않게)
    val driving = rememberDrivingLayout(store, restricted)
    // 주행 중 '맞춤 AI' 화면은 HUD가 질문·자막을 대신한다 — 대화 패널로 HUD를 덮지 않고 듣기만
    val hudDriving = driving && screen == Screen.PERSONAL
    // 주행 HUD로 바뀌면 열려 있던 대화 패널을 닫는다 (정차 중에 연 패널이 출발 뒤에도 HUD를 가리지 않게)
    LaunchedEffect(hudDriving) { if (hudDriving) store.closeAgent() }
    // 실차(Pleos SDK) 주행 중에는 시연 디렉터(스크롤 · 76dp 미만 버튼)를 띄우지 않는다 — 열려 있던 것도 닫는다
    // (숨기기만 하면 신호 대기에서 제한이 풀릴 때 다시 튀어나온다)
    val demoBlocked = restricted && source == Source.PLEOS_SDK
    LaunchedEffect(demoBlocked, demo) { if (demoBlocked && demo) store.demoPanel.value = false }

    LaunchedEffect(toast) { if (toast != null) { delay(2600); store.clearToast() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Y.Bg0)) {
        val expanded = maxWidth >= 1000.dp
        val layout = Layout(
            expanded = expanded, wide = maxWidth >= 1700.dp, restricted = restricted,
            driving = driving, drivingHud = hudDriving,
        )

        val content: @Composable () -> Unit = {
            Crossfade(screen, label = "screen") { s ->
                when (s) {
                    Screen.PERSONAL -> PersonalScreen(store, layout)
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
                NavRail(store, screen, phase, restricted, hudDriving)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(store, compact = false, hudDriving = hudDriving)
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(store, compact = true, hudDriving = hudDriving)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content()
                    // 주행 HUD가 떠 있는 동안(정차 뒤 5초 유지 포함)은 떠 있는 오브를 숨긴다 —
                    // HUD 아래 버튼 줄에 마이크가 있고, 주행 HUD는 이 자리까지 쓴다
                    if (!hudDriving) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(20.dp).size(96.dp).clip(CircleShape)
                                .clickable { store.startVoice(openPanel = !hudDriving) },
                            contentAlignment = Alignment.Center,
                        ) {
                            AiOrb(phase, 96.dp, still = restricted)
                        }
                    }
                }
                BottomBar(store, screen)
            }
        }

        // 음성 응답 자막 (에이전트 패널이 닫혀 있을 때) — 주행 중 HUD 화면에서는 HUD가 질문을 보여 주므로 덮지 않는다
        AnimatedVisibility(
            visible = caption != null && !overlay && !expanded && !hudDriving,
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

        // 데모 패널 (시나리오 디렉터) — 실차 주행 중에는 띄우지 않는다
        AnimatedVisibility(visible = demo && !demoBlocked, enter = fadeIn(), exit = fadeOut()) {
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

/**
 * 주행 화면 유지(이력 현상). 신호 대기처럼 D단에서 잠깐 설 때마다 정차 화면(스크롤 패널 · 떠 있는 오브)으로
 * 뒤집혔다가 출발하면 다시 돌아오지 않게 — P단이 되거나(충전 포함) 5초 넘게 서 있을 때만 정차 화면으로 바꾼다.
 * 다시 움직이면(제한 상태가 되면) 곧바로 주행 화면.
 *
 * 앱 전체에 하나만 둔다 — 화면마다 따로 세면 HUD는 주행 화면인데 오브·자막·패널은 정차 기준으로 움직인다.
 */
@Composable
private fun rememberDrivingLayout(store: YeopjariStore, restricted: Boolean): Boolean {
    val parked by remember(store) {
        store.context.map { it.vehicle.gear == Gear.P || it.vehicle.isCharging }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(store.context.value.vehicle.let { it.gear == Gear.P || it.isCharging })
    var driving by remember { mutableStateOf(restricted) }
    LaunchedEffect(restricted, parked) {
        when {
            restricted -> driving = true
            !driving -> Unit
            parked -> driving = false
            else -> {
                delay(STOP_TO_PARKED_MS)
                driving = false
            }
        }
    }
    return driving || restricted
}

@Composable
private fun NavRail(store: YeopjariStore, current: Screen, phase: AgentPhase, restricted: Boolean, hudDriving: Boolean) {
    Column(
        Modifier.fillMaxHeight().width(118.dp).background(Y.Bg1).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 주행 HUD 화면에서는(정차 뒤 5초 유지 포함) 대화 패널을 열지 않고 듣기만 (패널이 HUD 질문·선택지를 가리지 않게)
        Box(Modifier.size(100.dp).clip(CircleShape).clickable { store.startVoice(openPanel = !hudDriving) }, contentAlignment = Alignment.Center) {
            AiOrb(phase, 100.dp, still = restricted)
        }
        Text("옆자리", style = MaterialTheme.typography.labelLarge, color = Y.Text2)
        Spacer(Modifier.height(20.dp))
        navItems.forEach { item ->
            RailItem(item, current == item.screen) { store.go(item.screen) }
        }
        Spacer(Modifier.weight(1f))
        // 시연 버튼은 76dp. 실차(Pleos SDK) 주행 중에는 숨기고, 시뮬레이터 주행 중에는 시나리오를 바꿀 수 있게 둔다
        val source by store.sourceMode.collectAsStateWithLifecycle()
        if (!(restricted && source == Source.PLEOS_SDK)) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(18.dp)).background(Y.Bg3).clickable { store.demoPanel.value = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Science, "데모", tint = Y.Violet, modifier = Modifier.size(30.dp)) }
            Text("시연", style = MaterialTheme.typography.labelSmall, color = Y.Text3, modifier = Modifier.padding(top = 4.dp))
        }
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
            // 탭 높이 76dp 이상 (주행 중 터치 대상 기준)
            Column(
                Modifier.weight(1f).heightIn(min = 76.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) Y.Bg3 else Color.Transparent)
                    .clickable { store.go(item.screen) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(item.icon, item.label, tint = if (sel) Y.Cyan else Y.Text3, modifier = Modifier.size(30.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = if (sel) Y.Text1 else Y.Text3, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TopBar(store: YeopjariStore, compact: Boolean, hudDriving: Boolean) {
    val ctx by store.context.collectAsStateWithLifecycle()
    val restricted by store.restricted.collectAsStateWithLifecycle()
    val source by store.sourceMode.collectAsStateWithLifecycle()
    val deferred by store.deferredCount.collectAsStateWithLifecycle()
    val v = ctx.vehicle
    Row(
        // 위아래 여백 대신 최소 높이로 — 좁은 화면은 76dp 시연 버튼이, 넓은 화면은 음성 자막 알약이 들어가도
        // 상태바 높이가 그대로라서 아래 화면(주행 HUD 포함)이 말할 때마다 커졌다 작아지지 않는다
        Modifier.fillMaxWidth()
            .then(if (compact) Modifier.heightIn(min = 76.dp).padding(horizontal = 18.dp) else Modifier.heightIn(min = 70.dp).padding(horizontal = 28.dp)),
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
            // 음성 응답 자막 — 상태바 안에 표시해 화면 콘텐츠를 가리지 않음.
            // 주행 중·주행 HUD 화면에서는 누를 수 없음 (작은 터치 대상이 되지 않게, 대화 패널이 HUD를 덮지 않게)
            // 위아래 여백 5dp: 글줄 26sp + 10dp = 36dp로 시연(데이터 소스) 알약보다 높지 않게
            Spacer(Modifier.width(14.dp))
            Row(
                Modifier.weight(1f).clip(CircleShape).background(Y.Periwinkle.copy(alpha = 0.14f))
                    .then(if (restricted || hudDriving) Modifier else Modifier.clickable { store.openAgent() })
                    .padding(horizontal = 16.dp, vertical = 5.dp),
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
            // 데이터 소스 표시 — 정차 중에만 눌러서 시연 패널을 연다
            Row(
                Modifier.clip(CircleShape).background(Y.Bg2)
                    .then(if (restricted) Modifier else Modifier.clickable { store.demoPanel.value = true })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(if (source == Source.PLEOS_SDK) Y.Ev else Y.Violet)
                Spacer(Modifier.width(8.dp))
                Text(if (source == Source.PLEOS_SDK) "Pleos SDK 실시간" else "시뮬레이터", style = MaterialTheme.typography.labelMedium, color = Y.Text2)
            }
            Spacer(Modifier.width(16.dp))
        } else if (!(restricted && source == Source.PLEOS_SDK)) {
            // 좁은 화면의 시연 버튼 — 누르는 범위는 76dp (보이는 원은 44dp). 실차 주행 중에는 숨김
            Box(Modifier.size(76.dp).clip(CircleShape).clickable { store.demoPanel.value = true }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Y.Bg2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Science, "데모", tint = Y.Violet, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Text("${v.outsideTempC.roundToInt()}°", style = MaterialTheme.typography.titleLarge, color = Y.Text2)
        Spacer(Modifier.width(16.dp))
        Icon(Icons.Rounded.EvStation, null, tint = if (v.socPercent < 20) Y.Red else Y.Ev, modifier = Modifier.size(26.dp))
        Text("${v.socPercent.roundToInt()}%", style = MaterialTheme.typography.titleLarge, color = Y.Text1)
    }
}
