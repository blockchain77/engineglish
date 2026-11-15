package com.cartutor.engineglish.ui.english

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cartutor.engineglish.ui.theme.Y
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/*
 * 엔진글리시 친구 '부릉이' — 주차 대시보드에서 "오늘도 수고했어요"를 전하는 작은 자동차 캐릭터.
 * 직접 그린 캐릭터다 (Compose Canvas 도형만 사용, 외부 이미지·폰트·캐릭터 없음).
 *
 * 쓰는 법:
 *     Mascot(Modifier.size(240.dp), mood = MascotMood.CELEBRATE)   // 통통 튀어나와 손을 흔들고 색종이가 터진다
 *     Mascot(Modifier.size(120.dp), mood = MascotMood.IDLE)        // 가만히 숨 쉬고 가끔 눈을 깜빡인다
 *     ConfettiOverlay(Modifier.fillMaxSize(), playKey = sessionId)   // 화면 전체에 색종이 3.6초
 *
 * 정차(P단) 화면 전용 — 주행 중 화면에는 올리지 않는다 (주행 중 장식 애니메이션 금지).
 * 모든 움직임은 '시작 후 지난 시간(초)'만으로 계산한다 → 상태가 없고, 그리기 단계에서만 다시 그린다.
 */

internal enum class MascotMood {
    /** 통통 뛰며 등장 → 두 팔 번쩍 · 웃는 눈 · 색종이 → 이후엔 잔잔하게 반복 */
    CELEBRATE,

    /** 살짝 숨 쉬듯 움직이고 가끔 깜빡 · 처음에 한 번 손 인사 */
    IDLE,
}

/**
 * @param playKey 값이 바뀌면 처음부터 다시 재생한다 (새 세션 결과가 뜰 때)
 */
@Composable
internal fun Mascot(
    modifier: Modifier = Modifier,
    mood: MascotMood = MascotMood.CELEBRATE,
    playKey: Any? = Unit,
) {
    val time = rememberElapsedSeconds(playKey to mood)
    Canvas(modifier.semantics { contentDescription = "엔진글리시 친구 부릉이" }) {
        val t = time.value
        // 200 × 200 단위의 도안을 가운데에 맞춰 그린다
        val u = size.minDimension / UNITS
        withTransform({
            translate((size.width - UNITS * u) / 2f, (size.height - UNITS * u) / 2f)
            scale(u, u, pivot = Offset.Zero)
        }) {
            drawBuddy(t, mood)
        }
    }
}

/** 화면 전체에 내리는 색종이 — playKey 가 바뀔 때마다 한 번 (약 3.6초) 재생하고 멈춘다 */
@Composable
internal fun ConfettiOverlay(modifier: Modifier = Modifier, playKey: Any? = Unit, enabled: Boolean = true) {
    if (!enabled) return
    var running by remember(playKey) { mutableStateOf(true) }
    val time = remember(playKey) { mutableFloatStateOf(0f) }
    LaunchedEffect(playKey) {
        val start = withFrameNanos { it }
        while (time.floatValue < RAIN_SECONDS) {
            withFrameNanos { now -> time.floatValue = (now - start) / 1_000_000_000f }
        }
        running = false
    }
    if (!running) return
    Canvas(modifier) {
        val t = time.floatValue
        val k = size.minDimension / 800f
        // 위에서 흩날리며 내려오는 색종이
        for (i in 0 until RAIN_COUNT) {
            val delay = rnd(i, 11) * 1.1f
            val tau = t - delay
            if (tau <= 0f) continue
            val life = 2.1f + rnd(i, 12) * 0.5f
            if (tau >= life) continue
            val x0 = rnd(i, 13) * size.width
            val fall = (260f + 300f * rnd(i, 14)) * k
            val sway = (22f + 30f * rnd(i, 15)) * k
            val x = x0 + sway * sin(tau * (2.2f + 2f * rnd(i, 16)) + i)
            val y = -30f * k + fall * tau + 60f * k * tau * tau
            val alpha = ((life - tau) / 0.5f).coerceIn(0f, 1f) * (tau / 0.15f).coerceIn(0f, 1f)
            drawPiece(i, Offset(x, y), (9f + 9f * rnd(i, 17)) * k, tau, alpha)
        }
    }
}

/** 시작 후 지난 시간(초). 프레임마다 값이 바뀌므로 그리기 람다 안에서만 읽을 것 */
@Composable
private fun rememberElapsedSeconds(key: Any?): State<Float> {
    val t = remember(key) { mutableFloatStateOf(0f) }
    LaunchedEffect(key) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t.floatValue = (now - start) / 1_000_000_000f }
    }
    return t
}

// ───────────────────────── 도안 ─────────────────────────

private const val UNITS = 200f
private const val RAIN_SECONDS = 3.6f
private const val RAIN_COUNT = 90
private const val TAU = (2.0 * PI).toFloat()

private val BodyTop = Color(0xFF8DEEFF)
private val BodyMid = Color(0xFF5EE6FF)
private val BodyLow = Color(0xFF2BB4E2)
private val BodyEdge = Color(0xFF1C93BF)
private val Ink = Color(0xFF062230)
private val Glass = Color(0xFF0E2B3C)
private val GlassLow = Color(0xFF184A63)
private val Cheek = Color(0xFFFF8FB1)
private val Tongue = Color(0xFFFF7A9A)
private val StarGold = Color(0xFFFFD45E)
private val Tyre = Color(0xFF111821)
private val TyreEdge = Color(0xFF3A475A)

private val ConfettiColors = listOf(
    Color(0xFF5EE6FF), Color(0xFF2EE66B), Color(0xFFFFD45E), Color(0xFFFF8FB1), Color(0xFFB57CFF), Color(0xFFFFB020),
)

/** 한 순간의 자세 */
private class Pose(
    /** 등장 크기 0 → 1 (살짝 넘쳤다가 돌아온다) */
    val pop: Float,
    /** 바닥에서 뜬 높이 */
    val lift: Float,
    val scaleX: Float,
    val scaleY: Float,
    val tiltDeg: Float,
    /** 눈 감김 0..1 */
    val blink: Float,
    /** 웃는 눈 (∩ ∩) */
    val happy: Boolean,
    /** 오른팔 · 왼팔 올림 0..1 */
    val raiseRight: Float,
    val raiseLeft: Float,
    /** 흔드는 각도 (도) */
    val waveDeg: Float,
    val antennaSway: Float,
    /** 입 벌림 0..1 */
    val mouthOpen: Float,
)

/** a..b 구간에서 1, 양 끝 edge 초 동안 부드럽게 오르내린다 */
private fun window(t: Float, a: Float, b: Float, edge: Float = 0.18f): Float {
    if (t <= a || t >= b) return 0f
    val up = ((t - a) / edge).coerceIn(0f, 1f)
    val down = ((b - t) / edge).coerceIn(0f, 1f)
    val s = minOf(up, down)
    return s * s * (3f - 2f * s)
}

private fun poseAt(t: Float, mood: MascotMood): Pose {
    // 등장: 0.9초 동안 통 하고 튀어나온다
    val pop = if (t < 0.9f) (1f - exp(-6f * t) * cos(11f * t)).coerceAtLeast(0f) else 1f
    val celebrate = mood == MascotMood.CELEBRATE

    // 폴짝 (축하: 처음에 네 번, 그 뒤로는 6초마다 한 번 작게 — 그때 색종이도 조금 터진다)
    var lift = 0f
    var contact = 0f
    var hopping = false
    if (celebrate) {
        val h = t - 0.6f
        if (h in 0f..2.4f) {
            val phase = (h % 0.6f) / 0.6f
            val s = sin(PI.toFloat() * phase)
            val edge = minOf(h / 0.1f, (2.4f - h) / 0.1f, 1f)
            lift = s * 24f
            contact = (1f - s).let { it * it * it } * edge
            hopping = true
        } else if (h > 2.4f) {
            val c = (h - 2.4f) % 6f
            if (c in 3.9f..4.5f) {
                val s = sin(PI.toFloat() * (c - 3.9f) / 0.6f)
                val edge = minOf((c - 3.9f) / 0.1f, (4.5f - c) / 0.1f, 1f)
                lift = s * 12f
                contact = (1f - s).let { it * it * it } * 0.6f * edge
                hopping = true
            }
        }
    }
    // 숨 쉬듯 잔잔하게
    val breathe = sin(TAU * t / 2.4f)
    val scaleY = 1f + 0.014f * breathe - 0.09f * contact + 0.04f * (lift / 24f)
    val scaleX = 1f - 0.010f * breathe + 0.07f * contact
    val tilt = if (hopping) 4f * sin(TAU * t / 1.2f) else 1.2f * sin(TAU * t / 3.6f)

    // 깜빡임: 3.4초마다 0.14초
    val b = (t + 1.3f) % 3.4f
    val blink = if (b < 0.14f) sin(PI.toFloat() * b / 0.14f) else 0f

    // 손 인사
    val raiseRight = if (celebrate) {
        maxOf(window(t, 0.7f, 3.4f), window((t - 3.4f).mod(6f), 4.3f, 5.9f))
    } else {
        maxOf(window(t, 0.6f, 2.1f), window((t - 2.1f).mod(9f), 7.4f, 8.8f))
    }
    val raiseLeft = if (celebrate) window(t, 0.6f, 3.0f) else 0f
    val happy = if (celebrate) (t < 3.3f || hopping || raiseRight > 0.5f) else raiseRight > 0.5f
    return Pose(
        pop = pop,
        lift = lift,
        scaleX = scaleX,
        scaleY = scaleY,
        tiltDeg = tilt,
        blink = blink,
        happy = happy,
        raiseRight = raiseRight,
        raiseLeft = raiseLeft,
        waveDeg = 20f * sin(TAU * 2.6f * t),
        antennaSway = (if (hopping) 9f else 4f) * sin(TAU * 1.3f * t),
        mouthOpen = if (celebrate) maxOf(window(t, 0.5f, 3.2f), if (hopping) 1f else 0f) else 0f,
    )
}

private fun DrawScope.drawBuddy(t: Float, mood: MascotMood) {
    val p = poseAt(t, mood)

    // 바닥 빛 (어두운 화면이라 그림자 대신 은은한 불빛) — 뛰어오르면 작아진다
    val glow = p.pop * (1f - p.lift / 70f)
    if (glow > 0f) {
        val c = Offset(100f, 181f)
        val rx = 66f * glow
        scale(1f, 0.17f, pivot = c) {
            drawCircle(Brush.radialGradient(listOf(Y.Accent.copy(alpha = 0.42f), Color.Transparent), center = c, radius = rx), rx, c)
        }
    }

    // 반짝이는 별 (늘 잔잔하게)
    if (p.pop > 0.6f) drawTwinkles(t, mood)

    // 축하: 등장할 때 몸 뒤에서 색종이가 터지고, 그 뒤로는 6초마다 작게
    if (mood == MascotMood.CELEBRATE) {
        drawBurst(t - 0.35f, count = 26, seed = 1, power = 190f)
        val loop = t - 4f
        if (loop > 0f) drawBurst(loop % 6f - 3f, count = 12, seed = 2 + (loop / 6f).toInt() % 3, power = 140f)
    }

    if (p.pop <= 0f) return
    withTransform({
        translate(0f, -p.lift)
        rotate(p.tiltDeg, pivot = Offset(100f, 170f))
        scale(p.scaleX * p.pop, p.scaleY * p.pop, pivot = Offset(100f, 176f))
    }) {
        drawArms(p)
        drawWheels()
        drawAntenna(p, t)
        drawBody()
        drawFace(p)
    }
}

/** 팔 (사이드미러 자리에 달린 작은 손) — 몸보다 먼저 그려 어깨가 몸 뒤에 숨는다 */
private fun DrawScope.drawArms(p: Pose) {
    fun arm(shoulder: Offset, mirror: Float, raise: Float, wave: Float) {
        // 내린 자세 58° (아래쪽) → 올린 자세 −52° (위쪽) + 흔들기
        val deg = 58f + (-52f - 58f + wave) * raise
        val rad = deg * PI.toFloat() / 180f
        val hand = Offset(shoulder.x + mirror * 31f * cos(rad), shoulder.y + 31f * sin(rad))
        drawLine(BodyEdge, shoulder, hand, strokeWidth = 11f, cap = StrokeCap.Round)
        drawLine(BodyLow, shoulder, hand, strokeWidth = 7.5f, cap = StrokeCap.Round)
        drawCircle(BodyEdge, 10.5f, hand)
        drawCircle(BodyTop, 8.5f, hand)
    }
    arm(Offset(163f, 122f), 1f, p.raiseRight, p.waveDeg)
    arm(Offset(37f, 122f), -1f, p.raiseLeft, -p.waveDeg * 0.5f)
}

private fun DrawScope.drawWheels() {
    for (x in floatArrayOf(47f, 125f)) {
        drawRoundRect(Tyre, Offset(x, 150f), Size(28f, 28f), CornerRadius(9f))
        drawRoundRect(TyreEdge, Offset(x, 150f), Size(28f, 28f), CornerRadius(9f), style = Stroke(2f))
    }
}

private fun DrawScope.drawAntenna(p: Pose, t: Float) {
    val base = Offset(100f, 58f)
    val tip = Offset(100f + p.antennaSway, 31f)
    val stem = Path().apply {
        moveTo(base.x, base.y)
        quadraticTo(100f + p.antennaSway * 0.3f, 44f, tip.x, tip.y)
    }
    drawPath(stem, BodyEdge, style = Stroke(3.2f, cap = StrokeCap.Round))
    drawCircle(StarGold.copy(alpha = 0.28f), 13f, tip)
    drawPath(starPath(tip, 9.5f, 4.4f, 5, rotationDeg = -90f + p.antennaSway * 2f + 8f * sin(t * 1.7f)), StarGold)
}

private fun DrawScope.drawBody() {
    val topLeft = Offset(30f, 54f)
    val bodySize = Size(140f, 112f)
    val r = CornerRadius(50f)
    drawRoundRect(
        Brush.verticalGradient(listOf(BodyTop, BodyMid, BodyLow), startY = topLeft.y, endY = topLeft.y + bodySize.height),
        topLeft, bodySize, r,
    )
    drawRoundRect(BodyEdge.copy(alpha = 0.55f), topLeft, bodySize, r, style = Stroke(2.2f))
    // 앞유리
    drawRoundRect(
        Brush.verticalGradient(listOf(Glass, GlassLow), startY = 64f, endY = 100f),
        Offset(53f, 64f), Size(94f, 36f), CornerRadius(17f),
    )
    // 유리에 비친 빛 두 줄
    drawLine(Color.White.copy(alpha = 0.30f), Offset(66f, 94f), Offset(80f, 70f), strokeWidth = 5f, cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.18f), Offset(78f, 94f), Offset(89f, 76f), strokeWidth = 3f, cap = StrokeCap.Round)
    // 지붕 위 둥근 윤기
    drawOval(Color.White.copy(alpha = 0.22f), Offset(52f, 57f), Size(46f, 5f))
    // 범퍼 아래 살짝 어두운 띠
    drawRoundRect(BodyEdge.copy(alpha = 0.28f), Offset(48f, 153f), Size(104f, 7f), CornerRadius(3.5f))
}

private fun DrawScope.drawFace(p: Pose) {
    val eyeY = 124f
    for (cx in floatArrayOf(74f, 126f)) {
        if (p.happy) {
            // 웃는 눈
            val arc = Path().apply {
                moveTo(cx - 11f, eyeY + 4f)
                quadraticTo(cx, eyeY - 13f, cx + 11f, eyeY + 4f)
            }
            drawPath(arc, Ink, style = Stroke(5f, cap = StrokeCap.Round))
        } else {
            val open = 1f - 0.9f * p.blink
            scale(1f, open, pivot = Offset(cx, eyeY)) {
                drawCircle(Color.White, 13.5f, Offset(cx, eyeY))
                drawCircle(Ink, 8.2f, Offset(cx + 1f, eyeY + 1f))
                drawCircle(Color.White, 3.2f, Offset(cx - 2.4f, eyeY - 2.6f))
                drawCircle(Color.White.copy(alpha = 0.8f), 1.5f, Offset(cx + 3.6f, eyeY + 3.4f))
            }
        }
    }
    // 볼
    drawOval(Cheek.copy(alpha = 0.78f), Offset(43f, 138f), Size(17f, 10f))
    drawOval(Cheek.copy(alpha = 0.78f), Offset(140f, 138f), Size(17f, 10f))
    // 입
    val m = p.mouthOpen
    if (m > 0.05f) {
        // 활짝 웃는 입 (반달) + 혀
        val w = 11f + 3f * m
        val h = 4f + 9f * m
        val mouth = Path().apply {
            moveTo(100f - w, 137f)
            lineTo(100f + w, 137f)
            cubicTo(100f + w, 137f + h * 1.35f, 100f - w, 137f + h * 1.35f, 100f - w, 137f)
            close()
        }
        drawPath(mouth, Ink)
        if (m > 0.4f) drawOval(Tongue, Offset(100f - w * 0.5f, 137f + h * 0.52f), Size(w, h * 0.45f))
    } else {
        val smile = Path().apply {
            moveTo(89f, 137f)
            quadraticTo(100f, 149f, 111f, 137f)
        }
        drawPath(smile, Ink, style = Stroke(4.5f, cap = StrokeCap.Round))
    }
}

// ───────────────────────── 별 · 색종이 ─────────────────────────

private val TWINKLES = arrayOf(
    // x, y, 크기, 위상
    floatArrayOf(20f, 46f, 9f, 0.0f),
    floatArrayOf(181f, 58f, 7f, 0.37f),
    floatArrayOf(38f, 18f, 6f, 0.71f),
    floatArrayOf(164f, 20f, 8f, 0.18f),
    floatArrayOf(10f, 104f, 5f, 0.55f),
    floatArrayOf(190f, 112f, 6f, 0.86f),
)

private fun DrawScope.drawTwinkles(t: Float, mood: MascotMood) {
    val n = if (mood == MascotMood.CELEBRATE) TWINKLES.size else 3
    for (i in 0 until n) {
        val s = TWINKLES[i]
        val a = abs(sin(PI.toFloat() * (t * 0.45f + s[3])))
        val color = when (i % 3) {
            0 -> StarGold
            1 -> Color.White
            else -> Cheek
        }
        drawPath(starPath(Offset(s[0], s[1]), s[2] * (0.45f + 0.55f * a), s[2] * 0.3f * (0.45f + 0.55f * a), 4, 0f), color.copy(alpha = 0.25f + 0.75f * a))
    }
}

/** 몸 뒤(100, 110)에서 위로 터지는 색종이. tau = 터진 뒤 지난 시간(초) */
private fun DrawScope.drawBurst(tau: Float, count: Int, seed: Int, power: Float) {
    if (tau <= 0f || tau > 2.8f) return
    val drag = (1f - exp(-1.7f * tau)) / 1.7f
    for (i in 0 until count) {
        val life = 1.7f + 0.9f * rnd(i, seed * 7 + 1)
        if (tau >= life) continue
        val ang = (-90f + (rnd(i, seed * 7 + 2) - 0.5f) * 150f) * PI.toFloat() / 180f
        val speed = power * (0.5f + 0.5f * rnd(i, seed * 7 + 3))
        val x = 100f + cos(ang) * speed * drag + 5f * sin(tau * (4f + 4f * rnd(i, seed * 7 + 4)) + i)
        val y = 112f + sin(ang) * speed * drag + 62f * tau * tau
        val alpha = ((life - tau) / 0.45f).coerceIn(0f, 1f)
        drawPiece(i + seed * 31, Offset(x, y), 4.5f + 4f * rnd(i, seed * 7 + 5), tau, alpha)
    }
}

/** 색종이 한 조각 — 네모(팔랑팔랑 뒤집힘) · 동그라미 · 별 */
private fun DrawScope.drawPiece(i: Int, at: Offset, sizePx: Float, tau: Float, alpha: Float) {
    if (alpha <= 0f) return
    val color = ConfettiColors[i.mod(ConfettiColors.size)].copy(alpha = alpha)
    when (i.mod(3)) {
        0 -> {
            val flip = abs(cos(tau * (5f + 5f * rnd(i, 21)) + i)).coerceAtLeast(0.15f)
            rotate(tau * (160f + 300f * rnd(i, 22)) + i * 40f, pivot = at) {
                drawRect(color, Offset(at.x - sizePx / 2f, at.y - sizePx * 0.3f * flip), Size(sizePx, sizePx * 0.6f * flip))
            }
        }
        1 -> drawCircle(color, sizePx * 0.36f, at)
        else -> drawPath(starPath(at, sizePx * 0.62f, sizePx * 0.26f, 5, tau * 220f + i * 30f), color)
    }
}

/** 별 모양 — points 개의 뾰족한 끝 */
private fun starPath(center: Offset, outer: Float, inner: Float, points: Int, rotationDeg: Float): Path {
    val path = Path()
    val step = PI.toFloat() / points
    val rot = rotationDeg * PI.toFloat() / 180f
    for (k in 0 until points * 2) {
        val r = if (k % 2 == 0) outer else inner
        val a = rot + k * step
        val x = center.x + r * cos(a)
        val y = center.y + r * sin(a)
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** 순서(i)와 소금(salt)으로 정해지는 0..1 값 — 난수 대신 써서 매번 같은 모양으로 터진다 */
private fun rnd(i: Int, salt: Int): Float {
    val x = sin(i * 12.9898f + salt * 78.233f) * 43758.547f
    return x - floor(x)
}
