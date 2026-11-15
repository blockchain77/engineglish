package com.yeopjari.app.ui.english

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.WbIncandescent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yeopjari.app.english.core.CommuteSlot
import com.yeopjari.app.english.core.DailyGoal
import com.yeopjari.app.english.core.EnglishCoach
import com.yeopjari.app.english.core.EnglishUiState
import com.yeopjari.app.english.core.EveningStudy
import com.yeopjari.app.english.core.LearnerSettings
import com.yeopjari.app.english.core.LoadLevel
import com.yeopjari.app.english.core.ScreenMode
import com.yeopjari.app.english.core.SlotMode
import com.yeopjari.app.english.core.Track
import com.yeopjari.app.ui.theme.Y
import kotlin.math.abs

/*
 * 설정 — 트랙 · 목표 · 출퇴근 시간대별 학습 방식 · 출근길 자동 시작 · 퇴근길 학습(물어보기) · 효과음 · 주행 중 피드백 · 차량 연동 · 개인정보.
 */

private val RATES = listOf(0.85f, 1.0f, 1.1f)

@Composable
internal fun SettingsScreen(state: EnglishUiState, coach: EnglishCoach, a: AppActions) {
    if (state.screenMode == ScreenMode.DRIVE) {
        LockedPanel()
        return
    }
    var confirmErase by remember { mutableStateOf(false) }
    val st = state.settings
    val set: ((LearnerSettings) -> LearnerSettings) -> Unit = { coach.updateSettings(it) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoCol = maxWidth >= 1300.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
                .padding(top = 8.dp, bottom = 40.dp),
        ) {
            ScreenHeader("설정", subtitle = "학습 방식과 주행 중 알림 방법을 정해요")
            val left: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    LearningSection(state, coach)
                    CommuteSection(st, set)
                }
            }
            val right: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    FeedbackSection(st, set)
                    LoadRulesSection()
                    VehicleSection(state, set)
                    PrivacySection(state) { confirmErase = true }
                }
            }
            if (twoCol) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    left(Modifier.weight(1f))
                    right(Modifier.weight(1f))
                }
            } else {
                left(Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                right(Modifier.fillMaxWidth())
            }
        }
    }
    if (confirmErase) {
        ConfirmSheet(
            title = "학습 기록을 모두 지울까요?",
            body = "진도, 복습 일정, 발음 기록, 저장한 표현이 이 차에서 완전히 지워지고 처음 화면으로 돌아가요. 되돌릴 수 없어요.",
            confirmLabel = "모두 지우기",
            danger = true,
            onConfirm = {
                confirmErase = false
                coach.eraseAllData()
            },
            onDismiss = { confirmErase = false },
        )
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.fillMaxWidth(), padding = 26.dp) {
        SectionTitle(title, subtitle = subtitle)
        content()
    }
}

@Composable
private fun FieldLabel(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Y.Text1, fontWeight = FontWeight.SemiBold)
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Y.Text3)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LearningSection(state: EnglishUiState, coach: EnglishCoach) {
    val p = state.profile
    SettingsCard("학습", subtitle = p?.let { "지금 레벨 · ${it.track.ko} ${it.level.ko}" }) {
        FieldLabel("트랙")
        Segmented(Track.entries, p?.track, { it.ko }, { if (it != p?.track) coach.changeTrack(it) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(22.dp))
        FieldLabel("하루 목표", sub = "목표를 채우면 연속 학습 일수가 늘어나요")
        Segmented(DailyGoal.entries, p?.goal, { "${goalShortKo(it)}\n${it.items}문장" }, { if (it != p?.goal) coach.changeGoal(it) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("레벨 테스트 다시 하기", style = MaterialTheme.typography.titleMedium, color = Y.Text1, fontWeight = FontWeight.SemiBold)
                Text("12문항으로 지금 실력에 맞는 레벨을 다시 찾아요", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
            }
            Spacer(Modifier.width(14.dp))
            BigButton("테스트", { coach.startPlacement() }, icon = Icons.Rounded.Quiz, tone = Tone.SECONDARY)
        }
    }
}

@Composable
private fun CommuteSection(st: LearnerSettings, set: ((LearnerSettings) -> LearnerSettings) -> Unit) {
    SettingsCard("출퇴근 맞춤", subtitle = "세션 길이는 남은 운전 시간에 맞추고, 도착 1분 전에 학습을 마쳐요. 주차하면 오늘의 결과를 보여 드려요") {
        SlotModeRow(CommuteSlot.MORNING, st.morningMode) { m -> set { it.copy(morningMode = m) } }
        Spacer(Modifier.height(18.dp))
        SlotModeRow(CommuteSlot.EVENING, st.eveningMode) { m -> set { it.copy(eveningMode = m) } }
        Spacer(Modifier.height(18.dp))
        SlotModeRow(CommuteSlot.OTHER, st.otherMode) { m -> set { it.copy(otherMode = m) } }
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            "출근길에는 자동으로 시작",
            st.autoStartOnCommute,
            { v -> set { it.copy(autoStartOnCommute = v) } },
            subtitle = "출근길이 확실할 때만, 3초 넘게 달린 뒤 음성으로 안내하고 시작해요",
            icon = Icons.Rounded.AutoMode,
        )
        Spacer(Modifier.height(14.dp))
        FieldLabel("퇴근길 학습", sub = "‘물어보기’면 퇴근길에 “퇴근길에도 영어 복습할까요?” 하고 먼저 물어봐요. 말이나 버튼으로 답하면 돼요")
        Segmented(
            EveningStudy.entries,
            st.eveningStudy,
            { it.ko },
            { v -> set { it.copy(eveningStudy = v) } },
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SlotModeRow(slot: CommuteSlot, mode: SlotMode, onChange: (SlotMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(slotIcon(slot), contentDescription = null, tint = Y.Text2, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when (slot) {
                CommuteSlot.MORNING -> "출근길"
                CommuteSlot.EVENING -> "퇴근길"
                CommuteSlot.OTHER -> "그 밖의 주행"
            },
            style = MaterialTheme.typography.titleMedium,
            color = Y.Text1,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(10.dp))
    Segmented(SlotMode.entries, mode, { it.ko }, onChange, Modifier.fillMaxWidth())
}

@Composable
private fun FeedbackSection(st: LearnerSettings, set: ((LearnerSettings) -> LearnerSettings) -> Unit) {
    SettingsCard("효과음 · 주행 중 피드백") {
        ToggleRow(
            "효과음",
            st.soundEffects,
            { v -> set { it.copy(soundEffects = v) } },
            subtitle = "맞히면 맑은 실로폰 소리로 알려 줘요. 급제동처럼 운전이 급할 때는 울리지 않아요",
            icon = Icons.Rounded.MusicNote,
        )
        ToggleRow(
            "앰비언트 라이트로 정답 알림",
            st.ambientPulse,
            { v -> set { it.copy(ambientPulse = v) } },
            subtitle = "맞히면 초록, 아쉬우면 주황으로 1초 동안 빛나요 (부하가 ‘보통’ 이하일 때만)",
            icon = Icons.Rounded.WbIncandescent,
        )
        ToggleRow(
            "앞유리 HUD 힌트",
            st.hudHint,
            { v -> set { it.copy(hudHint = v) } },
            subtitle = "듣는 동안 힌트 한 단어만 띄워요",
            icon = Icons.Rounded.Lightbulb,
        )
        ToggleRow(
            "방금 그 상황 표현",
            st.contextHooks,
            { v -> set { it.copy(contextHooks = v) } },
            subtitle = "정체를 빠져나오거나 비가 오면, 여유가 생겼을 때 그 상황에 딱 맞는 표현을 알려 줘요",
            icon = Icons.Rounded.TipsAndUpdates,
        )
        Spacer(Modifier.height(16.dp))
        FieldLabel("영어 음성 속도", sub = "보조 음성 엔진으로 영어를 읽을 때 적용돼요. 말로 ‘천천히’라고 해도 돼요")
        val selected = RATES.minByOrNull { abs(it - st.englishRate) }
        Segmented(
            RATES,
            selected,
            { r ->
                when (r) {
                    0.85f -> "천천히"
                    1.0f -> "보통"
                    else -> "조금 빠르게"
                }
            },
            { r -> set { it.copy(englishRate = r) } },
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LoadRulesSection() {
    SettingsCard("운전 부하 기준", subtitle = "안전을 위해 기준은 바꿀 수 없어요. 부하가 내려가도 4초 동안 안정돼야 다시 말을 걸어요") {
        LoadLevel.entries.forEach { lv ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(loadIcon(lv), contentDescription = null, tint = loadColor(lv), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(lv.ko, style = MaterialTheme.typography.titleMedium, color = loadColor(lv), fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(loadMeaningKo(lv), style = MaterialTheme.typography.bodyMedium, color = Y.Text1)
                    Text(loadExampleKo(lv), style = MaterialTheme.typography.bodySmall, color = Y.Text3)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "쉬는 동안 끊긴 문제는 틀린 걸로 치지 않고, 여유가 생기면 ‘아까 그 문제 다시 할게요’로 이어서 해요.",
            style = MaterialTheme.typography.bodySmall,
            color = Y.Text3,
        )
    }
}

@Composable
private fun VehicleSection(state: EnglishUiState, set: ((LearnerSettings) -> LearnerSettings) -> Unit) {
    val sp = state.speech
    SettingsCard("차량 연동") {
        ToggleRow(
            "시뮬레이터 경로를 실제 내비에 반영",
            state.settings.mirrorSimRouteToCar,
            { v -> set { it.copy(mirrorSimRouteToCar = v) } },
            subtitle = "시연용 가상 경로를 차량 내비게이션에도 띄워요. 평소에는 꺼 두세요",
            icon = Icons.Rounded.Map,
        )
        Spacer(Modifier.height(12.dp))
        InfoRow("차량 신호", state.drive.origin.ko)
        InfoRow("음성 인식", if (sp.sttReady) "준비됨 (Pleos 음성 인식)" else "준비 중")
        InfoRow(
            "영어 인식",
            when (sp.sttEnglish) {
                true -> "지원"
                false -> "지원 안 함 · 화면 선택지나 입력으로 답해요"
                null -> "확인 전"
            },
        )
        if (sp.ttsKoEngine.isNotBlank()) InfoRow("한국어 음성", sp.ttsKoEngine)
        if (sp.ttsEnEngine.isNotBlank()) InfoRow("영어 음성", sp.ttsEnEngine)
        sp.lastErrorKo?.let { InfoRow("최근 오류", it, Y.Retry) }
    }
}

@Composable
private fun InfoRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = Y.Text1) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Y.Text3, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PrivacySection(state: EnglishUiState, onErase: () -> Unit) {
    val s = state.storage
    SettingsCard("개인정보") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Lock, Y.Accent, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("학습 기록은 이 차 안에만 암호화해 저장돼요", style = MaterialTheme.typography.titleMedium, color = Y.Text1, fontWeight = FontWeight.SemiBold)
                Text("답은 앱 안에서 채점하고, 서버로 보내는 학습 데이터는 없어요", style = MaterialTheme.typography.bodySmall, color = Y.Text3)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (s != null) {
            InfoRow("보호", if (s.encrypted) "AES-256-GCM · Android Keystore" else "기기 암호화 키를 쓸 수 없어 보호 수준이 낮아요", if (s.encrypted) Y.Text1 else Y.Retry)
            InfoRow("저장 용량", "${bytesKo(s.bytes)} · 파일 ${s.files.size}개")
            InfoRow("마지막 저장", s.lastSavedMs?.let { dateTimeKo(it) } ?: "아직 없음")
        }
        InfoRow("저장하는 것", "진도 · 복습 일정 · 발음 기록 · 출발 시각(분)")
        InfoRow("저장하지 않는 것", "목소리 녹음 · 위치 좌표 · 이름 같은 개인 정보")
        Spacer(Modifier.height(16.dp))
        BigButton("학습 기록 모두 지우기", onErase, icon = Icons.Rounded.DeleteForever, tone = Tone.DANGER, modifier = Modifier.fillMaxWidth())
    }
}
