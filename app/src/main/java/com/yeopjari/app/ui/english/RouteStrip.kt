package com.yeopjari.app.ui.english

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yeopjari.app.english.core.RouteStripItem
import com.yeopjari.app.ui.theme.Y

/*
 * 경로 띠 — 출퇴근 지도의 이름 있는 구간들. 지나온 구간 · 지금 구간(차 표시) · 남은 구간.
 */

/**
 * @param showAllNames 정차 화면 — 구간 이름을 모두 아래에 (넓을 때만)
 */
@Composable
internal fun RouteStrip(
    items: List<RouteStripItem>,
    modifier: Modifier = Modifier,
    showAllNames: Boolean = false,
    barHeight: Dp = 14.dp,
) {
    if (items.isEmpty()) return
    val currentIndex = items.indexOfFirst { it.current }
    val current = items.getOrNull(currentIndex)
    val next = if (currentIndex >= 0) items.getOrNull(currentIndex + 1) else null
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(barHeight + 18.dp)) {
            val n = items.size
            val gap = 6.dp.toPx()
            val segW = (size.width - gap * (n - 1)) / n
            val bh = barHeight.toPx()
            val top = size.height - bh - 4.dp.toPx()
            val r = CornerRadius(bh / 2f)
            items.forEachIndexed { i, it ->
                val x = i * (segW + gap)
                val color = when {
                    it.current -> Y.Accent
                    it.passed -> Y.Accent.copy(alpha = 0.32f)
                    else -> roadColor(it.road).copy(alpha = 0.38f)
                }
                drawRoundRect(color, Offset(x, top), Size(segW, bh), r)
                if (it.current) {
                    // 지금 위치 표시
                    val c = Offset(x + segW / 2f, top + bh / 2f)
                    drawCircle(Color.White, bh * 0.95f, c)
                    drawCircle(Y.Accent, bh * 0.95f, c, style = Stroke(3.dp.toPx()))
                }
            }
        }
        if (showAllNames) {
            Row(Modifier.fillMaxWidth()) {
                items.forEachIndexed { i, it ->
                    if (i > 0) Spacer(Modifier.width(6.dp))
                    Text(
                        it.nameKo,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.current) Y.Accent else Y.Text3,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else if (current != null) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("지금", style = MaterialTheme.typography.labelLarge, color = Y.Text3)
                Spacer(Modifier.width(8.dp))
                Text(
                    current.nameKo,
                    style = MaterialTheme.typography.titleMedium,
                    color = Y.Text1,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (next != null) {
                    Spacer(Modifier.weight(1f))
                    Text("다음", style = MaterialTheme.typography.labelLarge, color = Y.Text3)
                    Spacer(Modifier.width(8.dp))
                    Text(next.nameKo, style = MaterialTheme.typography.titleSmall, color = Y.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
