package com.yeopjari.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.YeopjariStore
import com.yeopjari.app.core.gateway.Source
import com.yeopjari.app.ui.Layout
import com.yeopjari.app.ui.components.Dot
import com.yeopjari.app.ui.components.Eyebrow
import com.yeopjari.app.ui.components.GlassCard
import com.yeopjari.app.ui.components.Pill
import com.yeopjari.app.ui.components.SectionTitle
import com.yeopjari.app.ui.components.Toggle
import com.yeopjari.app.ui.theme.Y

@Composable
fun SettingsScreen(store: YeopjariStore, layout: Layout) {
    val pad = if (layout.expanded) 28.dp else 18.dp
    if (layout.expanded) {
        Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 8.dp)) {
            ScreenHeader("AI 설정 · 아키텍처", "온디바이스 SLM + 자체 추론 LLM 하이브리드 — 무엇이 어디서 처리되는지 투명하게", Icons.Rounded.Tune, Y.Periwinkle)
            PipelineCard(store)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    EngineCard(store); PrefsCard(store); PrivacyCard()
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    GleoCard(store); ToolsCard(store); SourceCard(store)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ScreenHeader("AI 설정 · 아키텍처", "하이브리드 AI를 투명하게", Icons.Rounded.Tune, Y.Periwinkle)
            PipelineCard(store); EngineCard(store); PrefsCard(store); GleoCard(store); ToolsCard(store); PrivacyCard(); SourceCard(store)
        }
    }
}

@Composable
private fun PipelineCard(store: YeopjariStore) {
    GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Eyebrow("요청 처리 흐름")
        Spacer(Modifier.height(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Node("음성 입력", "Pleos STT SDK\n온디바이스", Icons.Rounded.Mic, Y.Cyan)
            Arrow()
            Node("안전 정책", "규칙 엔진\n주행 중 차단", Icons.Rounded.Shield, Y.Amber)
            Arrow()
            Node("의도·라우팅", "Edge SLM\n< 300ms", Icons.Rounded.Bolt, Y.TierEdge)
            Arrow()
            Node("복합 추론", "Reasoner LLM\nTool 계획", Icons.Rounded.Cloud, Y.TierCloud)
            Arrow()
            Node("차량 실행", "Vehicle · Navi\nSDK Tool", Icons.Rounded.Build, Y.Ev)
            Arrow()
            Node("음성 응답", "Pleos TTS SDK", Icons.Rounded.RecordVoiceOver, Y.Violet)
        }
        Spacer(Modifier.height(12.dp))
        Text("단순 명령(공조·목적지·상태 질의)은 차량 안에서 끝나고, 다중 제약 계획·진단·루틴 생성만 클라우드로 갑니다. 네트워크가 끊기면 온디바이스가 대체 응답합니다. Gleo(차량 기본 AI)는 App Schema로 옆자리 기능을 직접 호출합니다.", style = MaterialTheme.typography.bodySmall, color = Y.Text2)
    }
}

@Composable
private fun Node(title: String, sub: String, icon: ImageVector, color: Color) {
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(22.dp)).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(22.dp)).padding(16.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Y.Text1)
        Text(sub, style = MaterialTheme.typography.labelMedium, color = Y.Text2)
    }
}

@Composable
private fun Arrow() = Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Y.Text3, modifier = Modifier.padding(horizontal = 8.dp).size(26.dp))

@Composable
private fun EngineCard(store: YeopjariStore) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("AI 엔진 슬롯")
        EngineRow("온디바이스 SLM", "차량 도메인 특화 한국어 1~3B · 4bit 양자화 · NPU", store.intentEngine.modelName, Y.TierEdge, Icons.Rounded.Bolt)
        Spacer(Modifier.height(12.dp))
        EngineRow("추론 LLM (자체 서버)", "차량 도메인 SFT + Tool-use · 컨텍스트 최소화 입력", store.reasoner.modelName, Y.TierCloud, Icons.Rounded.Cloud)
        Spacer(Modifier.height(12.dp))
        EngineRow("음성 (STT/TTS)", "Pleos SpeechToText · TextToSpeech SDK (ON_DEVICE)", if (store.speechIn.available) "연결됨" else "이 기기에서 사용 불가 → 텍스트/추천 문장", Y.Cyan, Icons.Rounded.GraphicEq)
        Spacer(Modifier.height(14.dp))
        Text("※ 공모전 1차 단계: AI 모델은 미탑재 상태이며, 동일 인터페이스의 규칙/시나리오 대체 구현으로 전체 UX와 Tool 실행 경로를 검증합니다. 모델 교체 시 앱 코드는 변경되지 않습니다.", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
    }
}

@Composable
private fun EngineRow(title: String, desc: String, impl: String, color: Color, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Y.Bg3).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Y.Text1)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Y.Text2)
            Text("현재: $impl", style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
private fun PrefsCard(store: YeopjariStore) {
    val s by store.settings.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("동작 설정")
        PrefRow("클라우드 추론 사용", "끄면 모든 요청을 차량 안에서만 처리") { Toggle(s.cloudEnabled) { v -> store.updateSettings { it.copy(cloudEnabled = v) } } }
        PrefRow("음성으로 답하기", "TTS SDK로 응답 읽기") { Toggle(s.voiceReplies) { v -> store.updateSettings { it.copy(voiceReplies = v) } } }
        PrefRow("선제 제안", if (s.proactiveLevel >= 2) "균형 — 필요한 순간 먼저 말 걸기" else "긴급만 — 안전·배터리 위주") {
            Toggle(s.proactiveLevel >= 2) { v -> store.updateSettings { it.copy(proactiveLevel = if (v) 2 else 1) } }
        }
    }
}

@Composable
private fun PrefRow(title: String, sub: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Y.Text1)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Y.Text2)
        }
        trailing()
    }
}

@Composable
private fun PrivacyCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("개인정보 최소화") { Icon(Icons.Rounded.Lock, null, tint = Y.Ev) }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Y.Bg3).padding(16.dp)) {
                Text("클라우드로 보내는 것", style = MaterialTheme.typography.labelLarge, color = Y.TierCloud)
                listOf("주행 상태", "배터리 %", "도로 유형·잔여 거리", "동승 구성(성인/아동)", "시간대").forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Y.Text1) }
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Y.Bg3).padding(16.dp)) {
                Text("차 밖으로 안 나가는 것", style = MaterialTheme.typography.labelLarge, color = Y.Ev)
                listOf("음성 원본 (STT 온보드)", "정밀 GPS 좌표", "실명·연락처", "차대번호(VIN)").forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Y.Text1) }
            }
        }
    }
}

@Composable
private fun GleoCard(store: YeopjariStore) {
    val calls by store.gleoCalls.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth(), accent = Y.Violet) {
        SectionTitle("Gleo Interface · App Schema") { Pill("호출 $calls 회", color = Y.Violet, bg = Y.Violet.copy(alpha = 0.12f)) }
        Text("“글레오야, 옆자리로 서심다응 가자” — 차량 기본 AI가 옆자리를 Function Calling으로 호출하면, 옆자리가 이 사람의 기록으로 장소 이름을 바로잡고 애매하면 HUD로 되묻습니다 (assets/schema.json).", style = MaterialTheme.typography.bodyMedium, color = Y.Text2)
        Spacer(Modifier.height(12.dp))
        listOf(
            Triple("GO_TO_MY_PLACE", "activity", "place, stop_type(destination|waypoint)"),
            Triple("PLAN_EV_TRIP", "activity", "destination, companions"),
            Triple("START_CARE_MODE", "service", "mode(pet|rest), temperature"),
        ).forEach { (n, c, p) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Y.Bg3).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace), color = Y.Violet, modifier = Modifier.weight(1f))
                Text("$c · $p", style = MaterialTheme.typography.bodySmall, color = Y.Text2)
            }
        }
    }
}

@Composable
private fun ToolsCard(store: YeopjariStore) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("AI가 쓸 수 있는 도구 (Function Calling)") { Icon(Icons.Rounded.Hub, null, tint = Y.Text3) }
        store.tools.specs.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), color = Y.Text1)
                    Text("${t.title} · ${t.sdk}", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
                }
                if (t.requiresConfirm) { Pill("확인 필요", color = Y.Amber, bg = Y.Amber.copy(alpha = 0.1f)); Spacer(Modifier.width(6.dp)) }
                if (!t.allowedWhileDriving) Pill("정차 전용", color = Y.Red, bg = Y.Red.copy(alpha = 0.1f))
            }
        }
    }
}

@Composable
private fun SourceCard(store: YeopjariStore) {
    val src by store.sourceMode.collectAsStateWithLifecycle()
    val connected by store.pleosConnected.collectAsStateWithLifecycle()
    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle("차량 데이터 소스")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (connected) Y.Ev else Y.Text3, 14.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (connected) "Pleos Vehicle/NaviHelper SDK 연결됨" else "Pleos SDK 미연결 (일반 기기)", style = MaterialTheme.typography.titleMedium, color = Y.Text1)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Source.PLEOS_SDK to "실차/에뮬레이터", Source.SIMULATOR to "시나리오 시뮬레이터").forEach { (s, l) ->
                val sel = s == src
                Box(
                    Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(20.dp)).background(if (sel) Y.Cyan.copy(alpha = 0.15f) else Y.Bg3)
                        .border(if (sel) 2.dp else 1.dp, if (sel) Y.Cyan else Y.Stroke, RoundedCornerShape(20.dp)).clickable { store.useSource(s) },
                    contentAlignment = Alignment.Center,
                ) { Text(l, style = MaterialTheme.typography.titleSmall, color = if (sel) Y.Cyan else Y.Text2) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("시뮬레이터 사용 중에도 AI의 공조·경유지 변경은 연결된 Pleos 차량(에뮬레이터)에 그대로 반영됩니다.", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
    }
}
