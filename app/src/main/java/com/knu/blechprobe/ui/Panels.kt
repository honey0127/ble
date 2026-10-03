package com.knu.blechprobe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knu.blechprobe.model.AutoChecks
import com.knu.blechprobe.model.InvalidReason
import com.knu.blechprobe.model.ManualChecks

private val Ok = Color(0xFF15803D)
private val Caution = Color(0xFFB45309)

/** 한 줄: 표시 · 이름 · 값 */
@Composable private fun CheckLine(mark: String, color: Color, name: String, value: String) =
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = Subtle)
    }

/**
 * 시작 전 점검. 앱이 읽는 것 + 사람이 확인하는 것.
 * 사람 가림 런은 blockers 가 하나라도 있으면 시작 버튼이 막힌다 (알림음 = 가림 시각의 정답).
 */
@Composable
internal fun PreflightSection(
    auto: AutoChecks, manual: ManualChecks, blockers: List<String>, personRun: Boolean,
    onManual: (ManualChecks) -> Unit, onTestBeep: () -> Unit, onBeepHeard: () -> Unit,
) = Section("시작 전 점검") {
    Label("앱이 읽은 값")
    CheckLine(if (auto.powerSave) "✗" else "✓", if (auto.powerSave) Warn else Ok, "절전 모드", if (auto.powerSave) "켜짐" else "꺼짐")
    CheckLine(if (auto.battOptExempt) "✓" else "✗", if (auto.battOptExempt) Ok else Warn,
        "배터리 최적화 제외 (제한 없음)", if (auto.battOptExempt) "제외됨" else "적용 중")
    val charging = auto.plugged !in listOf("안 됨", "모름")
    CheckLine(if (charging) "✓" else "!", if (charging) Ok else Caution, "충전", auto.plugged)
    CheckLine(if (auto.alarmVolume > 0) "✓" else "✗", if (auto.alarmVolume > 0) Ok else Warn,
        "알람 음량", "${auto.alarmVolume} / ${auto.alarmMax}")
    val dndMark = when { auto.totalSilence -> "✗"; auto.dndFilter == 1 -> "✓"; else -> "!" }
    CheckLine(dndMark, when (dndMark) { "✗" -> Warn; "✓" -> Ok; else -> Caution }, "방해 금지", auto.dndLabel)
    CheckLine("·", Subtle, "Wi-Fi (기록만)", when (auto.wifiOn) { true -> "켜짐"; false -> "꺼짐"; null -> "모름" })

    Label("사람이 확인")
    Toggle("SmartThings 앱 종료", manual.smartThingsClosed) { onManual(manual.copy(smartThingsClosed = it)) }
    Toggle("워치·버즈 등 블루투스 연결 끊기", manual.wearablesOff) { onManual(manual.copy(wearablesOff = it)) }
    Toggle("태그 일반 모드 (절전 모드 아님)", manual.tagNormalMode) { onManual(manual.copy(tagNormalMode = it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTestBeep) { Text("알림음 시험") }
        val heard = manual.beepHeardAtVolume != null && manual.beepHeardAtVolume == auto.alarmVolume
        FilterChip(selected = heard, onClick = onBeepHeard, label = { Text(if (heard) "들렸음 ✓" else "들렸으면 누르기") })
    }

    if (personRun && blockers.isNotEmpty()) {
        Text("사람 가림 런을 시작할 수 없음: ${blockers.joinToString(", ")}", color = Warn, fontSize = 12.sp)
    } else if (personRun) {
        Text("사람 가림 런 시작 가능", color = Ok, fontSize = 12.sp)
    }
}

@Composable private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    FilterChip(selected = on, onClick = { onChange(!on) }, label = { Text((if (on) "✓ " else "") + label) })

/**
 * 끝난 런 표시. 무효는 절차 사유 칩을 하나 이상 골라야 누를 수 있다.
 * 한 번 표시하면 바꿀 수 없고, 표시하기 전에는 결과(pkt/s·RSSI)가 가려져 있다.
 */
@Composable
internal fun FlagPanel(lastFlag: String?, onFlag: (Boolean, List<InvalidReason>) -> Unit) {
    var picked by remember { mutableStateOf(emptySet<InvalidReason>()) }
    Section(if (lastFlag == null) "방금 런 표시 — 결과는 표시한 뒤 보인다" else "방금 런: ${if (lastFlag == "valid") "유효" else "무효"} (표시 완료)") {
        if (lastFlag != null) return@Section
        Text("무효라면 절차 사유를 고른다 (여러 개 가능)", fontSize = 12.sp, color = Subtle)
        InvalidReason.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { r ->
                    FilterChip(
                        selected = r in picked,
                        onClick = { picked = if (r in picked) picked - r else picked + r },
                        label = { Text(r.label, fontSize = 12.sp) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onFlag(true, emptyList()) }, enabled = picked.isEmpty(), modifier = Modifier.weight(1f)) {
                Text("유효")
            }
            OutlinedButton(onClick = { onFlag(false, picked.toList()) }, enabled = picked.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("무효")
            }
        }
    }
}

/** 하루치 측정을 zip(+files.txt sha256)으로 묶어 공유 시트로 보낸다 */
@Composable
internal fun ExportSection(days: List<Pair<String, Int>>, busy: Boolean, onExport: (String) -> Unit) =
    Section("측정 내보내기") {
        Text("앱을 지우면 측정 파일도 지워진다. 그날 측정이 끝나면 내보낸다. zip 안 files.txt 에 파일별 sha256",
            fontSize = 12.sp, color = Subtle)
        if (days.isEmpty()) Text("측정 파일이 아직 없다", fontSize = 12.sp, color = Muted)
        days.take(4).forEach { (day, n) ->
            OutlinedButton(onClick = { onExport(day) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("${day.substring(0, 4)}-${day.substring(4, 6)}-${day.substring(6)}  ·  파일 ${n}개  내보내기")
            }
        }
        if (busy) Text("묶는 중…", fontSize = 12.sp, color = Subtle)
    }
