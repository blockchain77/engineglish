package com.yeopjari.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RampLeft
import androidx.compose.material.icons.rounded.RampRight
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.Clarify
import com.yeopjari.app.ClarifyStage
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.ai.personal.Candidate
import com.yeopjari.app.ai.personal.Hangul
import com.yeopjari.app.ai.personal.Resolution
import com.yeopjari.app.ai.personal.StopMode
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.core.model.AgentPhase
import com.yeopjari.app.core.model.RouteState
import com.yeopjari.app.ui.theme.Y
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ───────────────────────── 색 ─────────────────────────

/** HUD 투사 색 — 실제 앞유리 HUD처럼 청록빛 단색 + 빛 번짐 */
private val HudColor = Color(0xFF8CF5E6)
private val HudDim = HudColor.copy(alpha = 0.68f)
private val HudFaint = HudColor.copy(alpha = 0.42f)

// 장면 (해 질 녘 · 밤 고속도로)
private val SkyTop = Color(0xFF03060E)
private val SkyMid = Color(0xFF0A1230)
private val SkyHorizon = Color(0xFF253157)
private val CityGlow = Color(0xFFFF9D5C)
private val SkylineColor = Color(0xFF0A0F1D)
private val GroundFar = Color(0xFF0D1222)
private val GroundNear = Color(0xFF05070C)
private val RoadFar = Color(0xFF181E2B)
private val RoadNear = Color(0xFF212836)
private val Cabin = Color(0xFF020306)

/** 지평선 높이 (위에서부터 비율) */
private const val HORIZON = 0.42f

/** 대시보드 윗선 — 아래 15%는 실내 */
private const val DASH_LINE = 0.85f

/** 계기판 후드(HUD 투사기 자리)가 대시보드 위로 솟은 높이 */
private const val HOOD = 0.035f

/** 주행 중 HUD 글자의 하한 — 이보다 작아질 보조 글줄은 줄이지 않고 아예 뺀다 */
private const val MIN_DRIVING_SP = 20f

/** 멈춰 그릴 때의 차선 점선 위치 (0..1) */
private const val STATIC_LANE_PHASE = 0.35f

/** 시연 영상용 도로 움직임 — 점선 한 주기가 화면에서 몇 m로 보일지 (96km/h에서 약 1.1초에 한 칸) */
private const val LANE_PERIOD_M = 30f

// ───────────────────────── HUD ─────────────────────────

/**
 * 앞유리 HUD 시뮬레이터.
 *
 * Pleos Connect에는 HUD 공개 API가 없어서, 운전자가 앞유리 너머로 보게 될 장면을 앱 안에 그리고
 * 그 위에 HUD로 투사될 내용(속도 · 길 안내 · 옆자리의 되묻기)을 얹는다.
 * 되묻기는 HUD 안에서 끝난다 — 확인은 "응 / 아니야", 선택은 최대 3곳을 번호로.
 *
 * 장면은 늘 멈춰 있다(차선 점선·앞차는 고정된 그림). 점선이 다가오는 움직임은
 * 시연 디렉터의 "HUD 도로 움직임" 스위치(기본 꺼짐)를 켜고 시뮬레이터 데이터로 달릴 때만 —
 * 실차(Pleos SDK) 데이터로 달릴 때는 스위치가 켜져 있어도 멈춘 그림이다 (운전 중 주변 시야에 움직임을 만들지 않는다).
 *
 * @param compact 높이 ~300dp 정도의 작은 자리 (글자·여백 축소)
 * @param driving 주행 화면 — 20sp 아래로 내려갈 보조 글줄은 빼고, 근거 태그는 1개만, 시뮬레이터 설명 문구 숨김
 */
@Composable
fun HudView(store: YeopjariStore, modifier: Modifier = Modifier, compact: Boolean = false, driving: Boolean = false) {
    // DriveContext는 0.5초 틱마다(시계·SOC 포함) 새로 나온다 — HUD에 보이는 값만 골라 바뀔 때만 다시 그린다
    val drive by remember(store) {
        store.context.map { HudDrive.of(it.vehicle.speedKph, it.route) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(HudDrive.of(store.context.value.vehicle.speedKph, store.context.value.route))
    val roadMotion by remember(store) {
        store.sim.flags.map { it.hudRoadMotion }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(store.sim.flags.value.hudRoadMotion)
    // 시연 영상용 움직임은 시뮬레이터 데이터일 때만 — 스위치는 시나리오가 바뀌어도 남아 있으므로,
    // 켜 둔 채 실차 데이터로 바꿔 달려도 센터 화면의 점선이 움직이지 않게 데이터 소스로 한 번 더 막는다
    val source by store.sourceMode.collectAsStateWithLifecycle()
    val clarify by store.clarify.collectAsStateWithLifecycle()
    val flash by store.hudFlash.collectAsStateWithLifecycle()
    val phase by store.phase.collectAsStateWithLifecycle()
    val partial by store.partialTranscript.collectAsStateWithLifecycle()

    val listening = phase == AgentPhase.LISTENING
    val c = clarify
    val f = flash
    val ai: HudAi = when {
        c != null -> HudAi.Ask(c)
        listening -> HudAi.Listen
        f != null -> HudAi.Flash(f)
        phase == AgentPhase.ROUTING || phase == AgentPhase.THINKING -> HudAi.Think
        else -> HudAi.Idle
    }

    BoxWithConstraints(modifier.clip(RoundedCornerShape(if (compact) 22.dp else 28.dp)).background(SkyTop)) {
        // 낮은 HUD에서는 설명 문구를 알약 옆으로 붙이고 위 여백을 알약 높이만큼만 둔다
        val short = maxHeight < 400.dp
        val tightTop = compact || driving || short
        val spec = HudSpec(
            k = hudScale(maxWidth.value, maxHeight.value, compact, driving),
            driving = driving,
            compact = compact,
            textWidth = maxWidth * 0.82f,
        )
        val rowWidth = min(maxWidth.value * 0.82f, 1100f * spec.k).dp
        WindshieldScene(
            speedKph = drive.speed.toFloat(),
            animate = drive.moving && roadMotion && source == Source.SIMULATOR,
            showCarAhead = drive.moving,
            modifier = Modifier.fillMaxSize(),
        )

        // ── 투사 영역: AI 층(위) + 주행 정보 줄(아래). 계기판 후드 바로 위에 뜬다 ──
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    top = if (tightTop) 48.dp else 66.dp,
                    bottom = maxHeight * (1f - DASH_LINE + HOOD) + 4.dp,
                    start = maxWidth * 0.09f,
                    end = maxWidth * 0.09f,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                AnimatedContent(
                    targetState = ai,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) using SizeTransform(clip = false) },
                    contentAlignment = Alignment.BottomCenter,
                    label = "hud-ai",
                    contentKey = { it.key },
                ) { s ->
                    when (s) {
                        is HudAi.Ask -> AskLayer(s.c, spec, listening, partial)
                        HudAi.Listen -> ListenLayer(partial, spec)
                        is HudAi.Flash -> FlashLayer(s.text, spec)
                        HudAi.Think -> HudText("알아듣는 중…", 24f, spec, color = HudDim, weight = FontWeight.Medium)
                        HudAi.Idle -> if (spec.shows(16f)) HudText("옆자리", 16f, spec, color = HudFaint, letterSpacing = 4f)
                    }
                }
            }
            Spacer(Modifier.height(spec.dp(12f)))
            HudBottomRow(drive, spec, Modifier.width(rowWidth))
        }

        SimulatorLabel(
            inline = compact || short,
            showNote = !driving,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp),
        )
    }
}

/** HUD에 지금 띄울 AI 층 */
private sealed interface HudAi {
    val key: Any

    data class Ask(val c: Clarify) : HudAi {
        override val key: Any get() = c.id
    }

    data object Listen : HudAi {
        override val key: Any get() = "listen"
    }

    data class Flash(val text: String) : HudAi {
        override val key: Any get() = "flash:$text"
    }

    data object Think : HudAi {
        override val key: Any get() = "think"
    }

    data object Idle : HudAi {
        override val key: Any get() = "idle"
    }
}

/**
 * HUD 글자 규칙.
 * @param k 글자 배율 (기준 화면에서 1.0)
 * @param textWidth AI 층이 쓸 수 있는 폭 — 긴 이름을 한 줄에 둘지 두 줄로 나눌지 정할 때
 */
@Immutable
private data class HudSpec(val k: Float, val driving: Boolean, val compact: Boolean, val textWidth: Dp) {
    /** 주행 중에는 20sp 아래로 내려갈 보조 글줄을 줄이지 않고 뺀다 */
    fun shows(base: Float): Boolean = !driving || base * k >= MIN_DRIVING_SP

    /** 실제 글자 크기(sp) — 주행 중에는 20sp 아래로 내리지 않는다 */
    fun sp(base: Float): Float = if (driving) max(base * k, MIN_DRIVING_SP) else base * k

    fun dp(base: Float): Dp = (base * k).dp

    /** 근거 태그 수 — 주행 중 · 좁은 화면은 1개, 넓은 정차 화면은 2개 */
    val tags: Int get() = if (driving || compact) 1 else 2
}

/** 글자 배율 — 기준: 높이 470dp · 폭 1080dp에서 1.0. 주행 중에는 작게 줄이지 않는다(좁은 화면도 0.9 이상) */
private fun hudScale(w: Float, h: Float, compact: Boolean, driving: Boolean): Float = when {
    compact && driving -> min(h / 470f, w / 640f).coerceIn(0.9f, 1.2f)
    compact -> min(h / 440f, w / 760f).coerceIn(0.55f, 0.82f)
    driving -> min(h / 470f, w / 1080f).coerceIn(0.9f, 1.55f)
    else -> min(h / 470f, w / 1080f).coerceIn(0.72f, 1.55f)
}

/**
 * HUD가 보는 주행 정보 — 화면에 보이는 값(반올림·짧게 줄인 글)만 담아서,
 * 값이 그대로면 시뮬레이터 틱이 와도 다시 그리지 않는다.
 */
@Immutable
private data class HudDrive(
    val speed: Int,
    val moving: Boolean,
    val guided: Boolean,
    /** 다음 안내 원문 (회전 아이콘 고르기용) */
    val guidance: String,
    /** "300m" */
    val turn: String?,
    val dest: String?,
    val waypoint: String?,
    val remainingMin: Int,
    val remainingKm: String,
) {
    companion object {
        fun of(speedKph: Float, r: RouteState) = HudDrive(
            speed = speedKph.roundToInt(),
            moving = speedKph > 1f,
            guided = r.active && r.destination != null,
            guidance = r.nextGuidance,
            turn = r.nextTurnMeters?.let(::meters),
            dest = r.destination?.name?.let(::shortName),
            waypoint = r.waypoints.lastOrNull()?.let { w ->
                shortName(w.name) + if (r.waypoints.size > 1) " 외 ${r.waypoints.size - 1}곳" else ""
            },
            remainingMin = r.remainingMin,
            remainingKm = kmLabel(r.remainingKm.toDouble()),
        )
    }
}

@Composable
private fun SimulatorLabel(inline: Boolean, showNote: Boolean, modifier: Modifier) {
    val pill: @Composable () -> Unit = {
        Pill("앞유리 HUD 시뮬레이터", color = Y.Text1, bg = Color(0xB3000000), icon = Icons.Rounded.Science)
    }
    if (!showNote) {
        // 주행 중에는 작은 알약만 (설명 문구는 정차 화면에서)
        Box(modifier) { pill() }
        return
    }
    val note: @Composable () -> Unit = {
        Text(
            "실차 HUD API 미공개 → 앱 안에서 재현",
            style = MaterialTheme.typography.labelSmall,
            color = Y.Text2.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (inline) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            pill()
            Spacer(Modifier.width(10.dp))
            note()
        }
    } else {
        Column(modifier) {
            pill()
            Box(Modifier.padding(start = 10.dp, top = 5.dp)) { note() }
        }
    }
}

// ───────────────────────── AI 층 ─────────────────────────

/**
 * 되묻기 층. 듣는 중이면 힌트 줄 · 부제를 '듣는 중' 줄로 바꿔 끼운다
 * (줄을 덧붙이지 않는다 — 좁은 HUD에서 맨 아래 줄이 잘리지 않게).
 * 마이크 아이콘은 실제로 듣고 있을 때만 보인다.
 */
@Composable
private fun AskLayer(c: Clarify, s: HudSpec, listening: Boolean, partial: String) {
    val top = c.options.firstOrNull() ?: return
    val waypoint = c.res.query.mode == StopMode.WAYPOINT
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (c.stage) {
            ClarifyStage.CONFIRM -> {
                HeardLine(c.res, s)
                val name = top.poi.spoken
                val tail = if (waypoint) "에 들를까요?" else "${Hangul.ro(name)} 갈까요?"
                FitOrSplit("$name$tail", hudStyle(40f, s, weight = FontWeight.Bold), s) {
                    HudText(name, 40f, s, weight = FontWeight.Bold, maxLines = 2, align = TextAlign.Center)
                    HudText(tail, 30f, s)
                }
                if (s.shows(18f)) {
                    reasonOf(top, s.tags)?.let {
                        Spacer(Modifier.height(s.dp(4f)))
                        HudText(it, 18f, s, color = HudDim, weight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(s.dp(12f)))
                if (listening) ListeningLine(partial, s) else SayHints(listOf("응", "아니야"), s)
            }

            ClarifyStage.CHOOSE -> ChooseLayer(c, s, waypoint, listening, partial)

            ClarifyStage.UNDO -> {
                HeardLine(c.res, s)
                val name = top.poi.spoken
                // '늘 가던 곳'은 정말 단골일 때만 (방문 기록이 없거나 한두 번이면 '바로 안내')
                val label = when {
                    waypoint && top.habitual -> "늘 들르던 곳"
                    waypoint -> "경유지 추가"
                    top.habitual -> "늘 가던 곳"
                    else -> "바로 안내"
                }
                FitOrSplit("$label · $name", hudStyle(36f, s, weight = FontWeight.Bold), s) {
                    HudText(label, 24f, s, color = HudDim, weight = FontWeight.Medium)
                    HudText(name, 36f, s, weight = FontWeight.Bold, maxLines = 2, align = TextAlign.Center)
                }
                Spacer(Modifier.height(s.dp(10f)))
                if (listening) {
                    ListeningLine(partial, s)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val lead = when {
                            !top.habitual -> "다른 곳이면"
                            waypoint -> "경유지 추가 · 다른 곳이면"
                            else -> "안내 시작 · 다른 곳이면"
                        }
                        HudText(lead, 20f, s, color = HudDim, weight = FontWeight.Medium)
                        Spacer(Modifier.width(s.dp(10f)))
                        HintChip("아니야", s)
                    }
                }
            }
        }
    }
}

/** "들린 말 ‘서심다응’ → 성심당" — 발음 보정이 있었을 때만 */
@Composable
private fun HeardLine(res: Resolution, s: HudSpec) {
    val fixed = res.correctedTo ?: return
    if (!s.shows(18f)) return
    HudText(
        "들린 말 ‘${res.query.text}’ → $fixed" + if (res.aliasHit && !s.driving) " · 기억한 표현" else "",
        18f, s, color = HudDim, weight = FontWeight.Medium,
    )
    Spacer(Modifier.height(s.dp(4f)))
}

@Composable
private fun ChooseLayer(c: Clarify, s: HudSpec, waypoint: Boolean, listening: Boolean, partial: String) {
    Column(Modifier.width(IntrinsicSize.Max)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudText(if (waypoint) "어디에 들를까요?" else "어디로 갈까요?", 28f, s, weight = FontWeight.Bold)
            if (listening) {
                // 부제 자리에 듣기 상태 (줄을 덧붙이지 않는다)
                Spacer(Modifier.width(s.dp(16f)))
                ListeningLine(partial, s)
            } else if (s.shows(20f)) {
                HudText(" · 번호로 말해 주세요", 20f, s, color = HudDim, weight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(s.dp(10f)))
        // 주의분산 가이드: 선택지는 최대 3개
        c.options.take(3).forEachIndexed { i, o ->
            if (i > 0) Spacer(Modifier.height(s.dp(8f)))
            ChoiceRow(i + 1, o, s)
        }
    }
}

@Composable
private fun ChoiceRow(n: Int, o: Candidate, s: HudSpec) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(s.dp(40f))
                .background(HudColor.copy(alpha = 0.10f), CircleShape)
                .border(s.dp(2f), HudColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) { HudText("$n", 22f, s, weight = FontWeight.Bold) }
        Spacer(Modifier.width(s.dp(14f)))
        // 주행 중에는 긴 이름을 '…'로 자르지 않고 두 줄까지 (그만큼 근거 태그는 줄였다)
        HudText(
            o.poi.spoken, 28f, s, weight = FontWeight.Bold,
            maxLines = if (s.driving) 2 else 1,
            modifier = Modifier.weight(1f),
        )
        if (s.shows(17f)) {
            reasonOf(o, s.tags)?.let {
                Spacer(Modifier.width(s.dp(16f)))
                HudText(it, 17f, s, color = HudDim, weight = FontWeight.Medium, modifier = Modifier.widthIn(max = s.dp(300f)))
            }
        }
        // 보여 주는 거리는 지금 위치에서의 직선거리 (순위에 쓴 '경로에서 벗어난 거리'가 아니다)
        val km = kmLabel(o.kmDirect ?: o.km)
        if (km.isNotEmpty()) {
            Spacer(Modifier.width(s.dp(16f)))
            HudText(km, 22f, s)
        }
    }
}

/** 말로 대답할 수 있는 말 — 터치가 없는 HUD라 누르는 칩이 아니라 '힌트'다 */
@Composable
private fun SayHints(words: List<String>, s: HudSpec) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.dp(12f))) {
        words.forEach { HintChip(it, s) }
    }
}

@Composable
private fun HintChip(text: String, s: HudSpec) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .background(HudColor.copy(alpha = 0.08f), shape)
            .border((1.5f * s.k).dp, HudColor.copy(alpha = 0.75f), shape)
            .padding(horizontal = s.dp(20f), vertical = s.dp(5f)),
    ) { HudText(text, 22f, s, weight = FontWeight.Bold) }
}

/** 되묻는 중 듣기 상태 — 힌트 줄 · 부제 자리에 들어간다 */
@Composable
private fun ListeningLine(partial: String, s: HudSpec) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HudIcon(Icons.Rounded.Mic, 24f, s)
        Spacer(Modifier.width(s.dp(8f)))
        HudText(if (partial.isBlank()) "듣는 중…" else "“$partial”", 22f, s, weight = FontWeight.Bold)
    }
}

@Composable
private fun ListenLayer(partial: String, s: HudSpec) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudIcon(Icons.Rounded.Mic, 34f, s)
            Spacer(Modifier.width(s.dp(10f)))
            HudText("듣는 중…", 32f, s, weight = FontWeight.Bold)
        }
        if (partial.isNotBlank()) {
            Spacer(Modifier.height(s.dp(6f)))
            HudText("“$partial”", 24f, s, color = HudDim, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FlashLayer(text: String, s: HudSpec) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HudIcon(Icons.Rounded.AutoAwesome, 32f, s)
        Spacer(Modifier.width(s.dp(12f)))
        HudText(text, 30f, s, weight = FontWeight.Bold, maxLines = 2)
    }
}

/**
 * 한 줄에 들어가면 [oneLine] 그대로, 넘치면 [split]의 두 줄로 — 긴 장소 이름이 '…'로 잘리지 않게
 * (예: "현대프리미엄아울렛 스페이스원" / "으로 갈까요?").
 */
@Composable
private fun FitOrSplit(oneLine: String, style: TextStyle, s: HudSpec, split: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    val limit = with(LocalDensity.current) { s.textWidth.roundToPx() }
    val fits = remember(oneLine, style, limit) {
        measurer.measure(oneLine, style = style, maxLines = 1, softWrap = false).size.width <= limit
    }
    if (fits) {
        Text(oneLine, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { split() }
    }
}

// ───────────────────────── 주행 정보 줄 ─────────────────────────

@Composable
private fun HudBottomRow(d: HudDrive, s: HudSpec, modifier: Modifier) {
    val speed: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.Bottom) {
            HudText("${d.speed}", 56f, s, weight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(s.dp(6f)))
            HudText("km/h", 18f, s, color = HudDim, modifier = Modifier.alignByBaseline())
        }
    }
    if (!d.guided) {
        // 안내 중이 아니면 속도만 가운데에
        Row(modifier, horizontalArrangement = Arrangement.Center) { speed() }
        return
    }
    // 양옆에 같은 폭을 줘서 회전 안내가 늘 도로 한가운데(소실점 아래)에 오게
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomStart) { speed() }
        TurnBlock(d, s)
        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomEnd) { DestinationBlock(d, s) }
    }
}

@Composable
private fun TurnBlock(d: HudDrive, s: HudSpec) {
    val guide = shortGuidance(d.guidance)
    Row(verticalAlignment = Alignment.CenterVertically) {
        HudIcon(turnIcon(d.guidance), 50f, s)
        Spacer(Modifier.width(s.dp(12f)))
        Column {
            d.turn?.let { HudText(it, 34f, s, weight = FontWeight.Bold) }
            if (guide.isNotBlank() && s.shows(17f)) {
                HudText(guide, 17f, s, color = HudDim, weight = FontWeight.Medium, modifier = Modifier.widthIn(max = s.dp(320f)))
            }
        }
    }
}

@Composable
private fun DestinationBlock(d: HudDrive, s: HudSpec) {
    val dest = d.dest ?: return
    Column(horizontalAlignment = Alignment.End) {
        if (s.shows(16f)) {
            d.waypoint?.let {
                HudText("경유 · $it", 16f, s, color = HudDim, weight = FontWeight.Medium, modifier = Modifier.widthIn(max = s.dp(300f)))
            }
        }
        if (s.shows(19f)) {
            HudText(dest, 19f, s, color = HudDim, weight = FontWeight.Medium, modifier = Modifier.widthIn(max = s.dp(300f)))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            HudText("${d.remainingMin}분", 32f, s, weight = FontWeight.Bold, modifier = Modifier.alignByBaseline())
            if (s.shows(18f)) {
                Spacer(Modifier.width(s.dp(6f)))
                HudText(d.remainingKm, 18f, s, color = HudDim, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

// ───────────────────────── HUD 글자 · 아이콘 ─────────────────────────

/** HUD 글자 스타일 — 단색 + 같은 색 번짐(blur 16)으로 투사된 빛처럼 보이게 */
private fun hudStyle(
    size: Float,
    s: HudSpec,
    color: Color = HudColor,
    weight: FontWeight = FontWeight.SemiBold,
    letterSpacing: Float = 0f,
    align: TextAlign? = null,
): TextStyle {
    val px = s.sp(size)
    return TextStyle(
        color = color,
        fontSize = px.sp,
        lineHeight = (px * 1.2f).sp,
        fontWeight = weight,
        letterSpacing = letterSpacing.sp,
        textAlign = align ?: TextAlign.Unspecified,
        shadow = Shadow(color = color.copy(alpha = color.alpha * 0.85f), offset = Offset.Zero, blurRadius = 16f * s.k),
    )
}

@Composable
private fun HudText(
    text: String,
    size: Float,
    s: HudSpec,
    modifier: Modifier = Modifier,
    color: Color = HudColor,
    weight: FontWeight = FontWeight.SemiBold,
    letterSpacing: Float = 0f,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = hudStyle(size, s, color, weight, letterSpacing, align),
    )
}

@Composable
private fun HudIcon(icon: ImageVector, sizeDp: Float, s: HudSpec, tint: Color = HudColor) {
    Icon(
        icon, contentDescription = null, tint = tint,
        modifier = Modifier
            .size(s.dp(sizeDp))
            .drawWithCache {
                // 번짐 붓은 크기가 바뀔 때만 만든다
                val r = size.minDimension * 0.8f
                val c = Offset(size.width / 2f, size.height / 2f)
                val glow = Brush.radialGradient(listOf(tint.copy(alpha = 0.28f), Color.Transparent), center = c, radius = r)
                onDrawBehind { drawCircle(glow, radius = r, center = c) }
            },
    )
}

// ───────────────────────── 글 다듬기 ─────────────────────────

/** "1.7km" · "12km" — 거리 태그 (엔진 문구가 바뀌어도 숫자+km 꼴이면 거리로 본다) */
private val KM_TAG = Regex("""^\s*\d+(?:[.,]\d+)?\s*km\s*$""", RegexOption.IGNORE_CASE)

/** 거리를 담은 근거 태그인지 — 거리는 따로(직선거리로) 보여 주므로 근거 줄에서는 뺀다 */
internal fun isHudDistanceTag(t: String): Boolean = KM_TAG.matches(t) || t.trimEnd().endsWith("km", ignoreCase = true)

/** '방문 기록 없음'처럼 고를 이유가 아닌 태그 (문구가 조금 바뀌어도 걸러지게) */
private fun isNoRecordTag(t: String): Boolean = "없음" in t || "기록 없" in t

private val HABIT_WORDS = listOf("주로", "단골", "아침", "오전", "점심", "오후", "저녁", "밤")

/**
 * 후보의 근거 태그 n개 — 운전 중 한눈에 판단에 도움이 되는 순서로 고른다
 * (지난번 선택 · 즐겨찾기 → 가는 길 → 시간대 습관 → 방문 횟수).
 * 거리는 따로 보여 주고, '방문 기록 없음'은 고를 이유가 아니라서 뺀다.
 * 태그 문구는 엔진이 정하므로 정확한 문자열이 아니라 핵심 낱말로 가린다.
 */
private fun reasonOf(c: Candidate, n: Int): String? {
    if (n <= 0) return null
    fun rank(t: String) = when {
        "지난번" in t || "즐겨찾기" in t -> 0
        "가는 길" in t -> 1
        HABIT_WORDS.any { it in t } -> 2
        else -> 3
    }
    return c.tags
        .filterNot { isHudDistanceTag(it) || isNoRecordTag(it) }
        .sortedBy(::rank)
        .take(n)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
}

/** "5.4km" · "18km" (없으면 빈 글) */
private fun kmLabel(km: Double?): String = when {
    km == null -> ""
    km < 10 -> "%.1fkm".format(km)
    else -> "${km.roundToInt()}km"
}

/** HUD와 같은 꼴의 거리 글 — 판단 근거 패널에서도 같은 숫자로 보이게 */
internal fun hudDistanceLabel(km: Double?): String = kmLabel(km)

private fun meters(m: Int): String = if (m >= 1000) "%.1fkm".format(m / 1000f) else "${(m / 10) * 10}m"

private fun shortName(name: String): String = name.substringBefore(" (").trim()

private val leadingDistance = Regex("""^\s*\d+(?:\.\d+)?\s*(?:m|km)\s*앞\s*""")

/** "300m 앞 우회전" → "우회전" (거리는 큰 숫자로 따로 보여 준다) */
private fun shortGuidance(g: String): String = g.replace(leadingDistance, "").trim()

private fun turnIcon(g: String): ImageVector = when {
    "유턴" in g -> Icons.Rounded.UTurnLeft
    "좌회전" in g -> Icons.Rounded.TurnLeft
    "우회전" in g -> Icons.Rounded.TurnRight
    "IC" in g || "JC" in g || "나들목" in g || "출구" in g ->
        if ("왼쪽" in g || "좌측" in g) Icons.Rounded.RampLeft else Icons.Rounded.RampRight
    "왼쪽" in g || "좌측" in g -> Icons.Rounded.TurnSlightLeft
    "오른쪽" in g || "우측" in g -> Icons.Rounded.TurnSlightRight
    else -> Icons.Rounded.Straight
}

// ───────────────────────── 앞유리 너머 장면 ─────────────────────────

/**
 * 앞유리 너머 장면. 하늘·도로·앞차·안개·실내는 크기가 바뀔 때만 한 번 그려 두고(GraphicsLayer 캐시),
 * 차선 점선만 따로 그린다.
 *
 * 기본은 정지 그림 — 점선은 고정된 위치에 그려 움직이지 않는다(운전 중 주변 시야의 움직임 = 주의분산).
 * [animate]는 시연 영상용 스위치를 켜고 시뮬레이터 데이터로 달릴 때만 참이고, 그때는 프레임마다 지금 속도만큼 점선을 당긴다
 * (속도가 바뀌면 다음 프레임부터 바로 반영 — 위상이 이어져 튀지 않는다).
 */
@Composable
private fun WindshieldScene(speedKph: Float, animate: Boolean, showCarAhead: Boolean, modifier: Modifier) {
    val lane = remember { mutableFloatStateOf(STATIC_LANE_PHASE) }
    val speed by rememberUpdatedState(speedKph)
    if (animate) {
        LaunchedEffect(Unit) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                    last = now
                    lane.floatValue = (lane.floatValue + dt * speed / 3.6f / LANE_PERIOD_M) % 1f
                }
            }
        }
    }
    Spacer(
        modifier
            .graphicsLayer { }
            .drawWithCache {
                val g = HudGeo(size.width, size.height)
                val back = obtainGraphicsLayer().apply { record { drawBackdrop(g) } }
                // 앞차·먼 안개는 크기에만 달린 그림 — 붓(그라디언트)도 여기서 한 번만 만든다
                val mid = obtainGraphicsLayer().apply {
                    record {
                        if (showCarAhead) drawCarAhead(g)
                        drawDistanceHaze(g)
                    }
                }
                val front = obtainGraphicsLayer().apply { record { drawCabin(g) } }
                val dashes = Path()
                onDrawBehind {
                    drawLayer(back)
                    drawLaneDashes(g, dashes, lane.floatValue)
                    drawLayer(mid)
                    drawLayer(front)
                }
            },
    )
}

/**
 * 원근 계산. 깊이 z(화면 맨 아래 = 1)에 있는 노면의 화면 y = 지평선 + depth / z,
 * 가로 위치 u(도로 반폭 = 1, 3차로)의 x = 가운데 + u · halfB / z.
 */
private class HudGeo(val w: Float, val h: Float) {
    val hy = h * HORIZON
    val cx = w * 0.5f
    val depth = h - hy
    val dashTop = h * DASH_LINE
    val halfB = max(w * 0.52f, depth * 2.2f)
    fun y(z: Float) = hy + depth / z
    fun x(u: Float, z: Float) = cx + u * halfB / z

    /** 깊이 z에서 1m가 몇 px인지 (도로 반폭 u = 1 ≈ 5.4m) */
    fun m(z: Float) = halfB / 5.4f / z
}

private val LANE_U = floatArrayOf(-1f / 3f, 1f / 3f)

/** 결정적 의사난수 0..1 — 별·건물 배치가 늘 같도록 */
private fun hash(i: Int, salt: Int): Float {
    val v = sin(i * 12.9898 + salt * 78.233) * 43758.5453
    return (v - floor(v)).toFloat()
}

/** 노면 위 띠 (차선) — 깊이 zFar..zNear */
private fun stripe(g: HudGeo, u: Float, lw: Float, zFar: Float, zNear: Float) = Path().apply {
    moveTo(g.x(u - lw, zFar), g.y(zFar))
    lineTo(g.x(u + lw, zFar), g.y(zFar))
    lineTo(g.x(u + lw, zNear), g.y(zNear))
    lineTo(g.x(u - lw, zNear), g.y(zNear))
    close()
}

/** 도로 옆에 선 벽 (중앙분리대·가드레일) — 높이 lo..hi m, 소실점으로 모이는 띠 */
private fun wall(g: HudGeo, u: Float, lo: Float, hi: Float): Path {
    val zf = 120f
    val zn = 0.6f
    return Path().apply {
        moveTo(g.x(u, zf), g.y(zf) - hi * g.m(zf))
        lineTo(g.x(u, zn), g.y(zn) - hi * g.m(zn))
        lineTo(g.x(u, zn), g.y(zn) - lo * g.m(zn))
        lineTo(g.x(u, zf), g.y(zf) - lo * g.m(zf))
        close()
    }
}

private fun DrawScope.drawBackdrop(g: HudGeo) {
    val w = g.w
    val h = g.h
    val hy = g.hy
    val px = 1.dp.toPx()

    // 하늘 — 위는 짙은 남색, 지평선 쪽이 조금 밝다
    drawRect(Brush.verticalGradient(0f to SkyTop, 0.55f to SkyMid, 1f to SkyHorizon, startY = 0f, endY = hy), size = Size(w, hy + 1f))
    for (i in 0 until 36) {
        drawCircle(
            Color.White.copy(alpha = 0.10f + 0.32f * hash(i, 3)),
            radius = px * (0.5f + 0.8f * hash(i, 4)),
            center = Offset(hash(i, 1) * w, hash(i, 2) * hy * 0.7f),
        )
    }

    // 땅
    drawRect(Brush.verticalGradient(listOf(GroundFar, GroundNear), startY = hy, endY = h), topLeft = Offset(0f, hy), size = Size(w, h - hy))

    // 지평선의 도시 불빛 (넓은 타원 번짐)
    val r = h * 0.5f
    val c = Offset(g.cx, hy)
    scale(scaleX = max(1.2f, w / h), scaleY = 0.5f, pivot = c) {
        drawCircle(
            Brush.radialGradient(0f to CityGlow.copy(alpha = 0.32f), 0.4f to CityGlow.copy(alpha = 0.12f), 1f to Color.Transparent, center = c, radius = r),
            radius = r, center = c,
        )
    }

    // 스카이라인 실루엣 + 창문 불빛 (소실점 근처는 낮게)
    var x = 0f
    var i = 0
    while (x < w) {
        val bw = h * (0.03f + 0.06f * hash(i, 5))
        val edge = abs(x + bw / 2f - g.cx) / (w / 2f)
        val bh = h * (0.010f + 0.045f * hash(i, 6)) * (0.45f + 0.55f * edge)
        drawRect(SkylineColor, topLeft = Offset(x, hy - bh), size = Size(bw + 1f, bh + 1f))
        if (hash(i, 7) > 0.4f) {
            val n = 1 + (hash(i, 8) * 4f).toInt()
            for (j in 0 until n) {
                val s = i * 7 + j
                drawCircle(
                    Color(0xFFFFC98A).copy(alpha = 0.30f + 0.45f * hash(s, 9)),
                    radius = px * 0.8f,
                    center = Offset(x + bw * (0.2f + 0.6f * hash(s, 10)), hy - bh * (0.2f + 0.65f * hash(s, 11))),
                )
            }
        }
        x += bw
        i++
    }
    // 지평선 위 옅은 안개 띠
    val band = h * 0.035f
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to SkyHorizon.copy(alpha = 0.55f), startY = hy - band, endY = hy),
        topLeft = Offset(0f, hy - band), size = Size(w, band),
    )

    // 중앙분리대(왼쪽) · 가드레일(오른쪽)
    drawPath(wall(g, -1.2f, 0f, 0.85f), Brush.verticalGradient(listOf(Color(0xFF1C2230), Color(0xFF2A3142)), startY = hy, endY = h))
    drawPath(wall(g, 1.28f, 0.5f, 0.78f), Color(0xFF7D879C).copy(alpha = 0.26f))

    // 도로 (갓길 포함)
    val road = Path().apply {
        moveTo(g.cx, hy)
        lineTo(g.x(1.16f, 1f), h)
        lineTo(g.x(-1.16f, 1f), h)
        close()
    }
    drawPath(road, Brush.verticalGradient(listOf(RoadFar, RoadNear), startY = hy, endY = h))
    // 전조등이 비추는 앞 노면
    clipPath(road) {
        val hr = g.depth * 0.8f
        val bottom = Offset(g.cx, h)
        scale(scaleX = 2.4f, scaleY = 1f, pivot = bottom) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF0CF).copy(alpha = 0.11f), Color.Transparent), center = bottom, radius = hr), radius = hr, center = bottom)
        }
    }
    // 바깥 실선 (흰색)
    drawPath(stripe(g, -1f, 0.016f, 90f, 1f), Color.White.copy(alpha = 0.62f))
    drawPath(stripe(g, 1f, 0.016f, 90f, 1f), Color.White.copy(alpha = 0.62f))
}

/** 차로 사이 점선 — phase 0→1 동안 한 칸만큼 운전자 쪽으로 다가온다 */
private fun DrawScope.drawLaneDashes(g: HudGeo, path: Path, phase: Float) {
    path.reset()
    val period = 1.6f
    val len = 0.65f
    val lw = 0.014f
    val zMin = 0.5f
    for (u in LANE_U) {
        var n = 0
        while (n < 80) {
            val z0 = zMin + (n - phase) * period
            n++
            val ze = z0 + len
            if (ze <= zMin) continue
            val zs = max(z0, zMin)
            val yNear = g.y(zs)
            val yFar = g.y(ze)
            if (yNear - yFar < 0.6f) break // 이보다 먼 점선은 1px도 안 된다
            path.moveTo(g.x(u - lw, ze), yFar)
            path.lineTo(g.x(u + lw, ze), yFar)
            path.lineTo(g.x(u + lw, zs), yNear)
            path.lineTo(g.x(u - lw, zs), yNear)
            path.close()
        }
    }
    drawPath(path, Color.White.copy(alpha = 0.68f))
}

/** 앞서 가는 차 (주행 중에만) — 같은 속도로 달리므로 화면에서는 제자리 */
private fun DrawScope.drawCarAhead(g: HudGeo) {
    val z = 8f
    val m = g.m(z)
    val cw = 1.85f * m
    val ch = 1.4f * m
    val cx = g.x(2f / 3f, z)
    val yb = g.y(z)
    drawRoundRect(Color(0xFF06080D), topLeft = Offset(cx - cw / 2f, yb - ch), size = Size(cw, ch), cornerRadius = CornerRadius(cw * 0.14f))
    val ly = yb - 0.85f * m
    val lr = max(1.2f, 0.11f * m)
    for (s in floatArrayOf(-1f, 1f)) {
        val p = Offset(cx + s * cw * 0.36f, ly)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFF3B3B).copy(alpha = 0.5f), Color.Transparent), center = p, radius = lr * 5f), radius = lr * 5f, center = p)
        drawCircle(Color(0xFFFF6A6A), radius = lr, center = p)
    }
}

/** 먼 노면은 안개에 녹아든다 (점선이 지평선에서 자연스럽게 사라지게) */
private fun DrawScope.drawDistanceHaze(g: HudGeo) {
    val hh = g.depth * 0.22f
    drawRect(
        Brush.verticalGradient(0f to SkyHorizon.copy(alpha = 0.8f), 1f to Color.Transparent, startY = g.hy, endY = g.hy + hh),
        topLeft = Offset(0f, g.hy), size = Size(g.w, hh),
    )
}

/** 차 안 — 지붕 테두리 · A필러 · 대시보드(가운데 계기판 후드) · 유리 반사 · 비네트 */
private fun DrawScope.drawCabin(g: HudGeo) {
    val w = g.w
    val h = g.h
    val top = g.dashTop
    val sw = 1.5.dp.toPx()

    // HUD 투사 영역 뒤를 살짝 어둡게 — 밝은 차선 위에서도 속도·안내 글자가 또렷하게
    val ry = h * 0.16f
    val sc = Offset(g.cx, h * 0.74f)
    scale(scaleX = w * 0.47f / ry, scaleY = 1f, pivot = sc) {
        drawCircle(
            Brush.radialGradient(0f to Color.Black.copy(alpha = 0.45f), 0.6f to Color.Black.copy(alpha = 0.22f), 1f to Color.Transparent, center = sc, radius = ry),
            radius = ry, center = sc,
        )
    }

    val roof = Path().apply {
        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h * 0.03f)
        quadraticTo(g.cx, h * 0.065f, 0f, h * 0.03f)
        close()
    }
    drawPath(roof, Cabin)

    val pTop = h * 0.2f
    val pBot = h * 0.05f
    val left = Path().apply { moveTo(0f, 0f); lineTo(pTop, 0f); lineTo(pBot, h); lineTo(0f, h); close() }
    val right = Path().apply { moveTo(w, 0f); lineTo(w - pTop, 0f); lineTo(w - pBot, h); lineTo(w, h); close() }
    drawPath(left, Brush.horizontalGradient(listOf(Cabin, Color(0xFF0A0D14)), startX = 0f, endX = pTop))
    drawPath(right, Brush.horizontalGradient(listOf(Color(0xFF0A0D14), Cabin), startX = w - pTop, endX = w))
    val rim = Color.White.copy(alpha = 0.06f)
    drawLine(rim, Offset(pTop, 0f), Offset(pBot, h), sw)
    drawLine(rim, Offset(w - pTop, 0f), Offset(w - pBot, h), sw)

    val bumpW = min(w * 0.22f, h * 0.7f)
    val bumpH = h * HOOD
    val dash = Path().apply {
        moveTo(0f, h)
        lineTo(0f, top - h * 0.02f)
        quadraticTo(w * 0.2f, top + h * 0.006f, g.cx - bumpW, top)
        cubicTo(g.cx - bumpW * 0.62f, top, g.cx - bumpW * 0.58f, top - bumpH, g.cx - bumpW * 0.3f, top - bumpH)
        lineTo(g.cx + bumpW * 0.3f, top - bumpH)
        cubicTo(g.cx + bumpW * 0.58f, top - bumpH, g.cx + bumpW * 0.62f, top, g.cx + bumpW, top)
        quadraticTo(w * 0.8f, top + h * 0.006f, w, top - h * 0.02f)
        lineTo(w, h)
        close()
    }
    drawPath(dash, Brush.verticalGradient(listOf(Color(0xFF0C1018), Cabin), startY = top - bumpH, endY = h))
    drawPath(dash, Color.White.copy(alpha = 0.07f), style = Stroke(width = sw))

    // 아주 옅은 유리 반사 두 줄
    val a = Offset(w * 0.12f, 0f)
    val b = Offset(w * 0.55f, h)
    drawRect(
        Brush.linearGradient(0f to Color.Transparent, 0.46f to Color.Transparent, 0.52f to Color.White.copy(alpha = 0.045f), 0.58f to Color.Transparent, 1f to Color.Transparent, start = a, end = b),
        size = Size(w, top),
    )
    drawRect(
        Brush.linearGradient(0f to Color.Transparent, 0.64f to Color.Transparent, 0.67f to Color.White.copy(alpha = 0.03f), 0.70f to Color.Transparent, 1f to Color.Transparent, start = a, end = b),
        size = Size(w, top),
    )
    drawRect(
        Brush.radialGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f), center = Offset(g.cx, h * 0.45f), radius = hypot(w, h) * 0.55f),
    )
}
