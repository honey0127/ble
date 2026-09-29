package com.knu.blechprobe.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knu.blechprobe.collect.Collector
import com.knu.blechprobe.collect.START_LIMIT
import com.knu.blechprobe.model.Mode
import com.knu.blechprobe.model.StatRow
import java.util.Locale

/*
 * ① 측정 화면.
 * 지표 정의 — README 와 analyze.py 에 맞춘다
 *   rows     수신 행 수 (같은 seq 중복 포함)
 *   pkt/s    rows ÷ 경과초.  가정 없는 직접 측정값. 조건 비교는 이 값으로
 *   seqObs%  고유 seq ÷ (max−min+1).  연속성 지표. 100% 를 넘을 수 없다
 *   dup      rows ÷ 고유 seq.  타이밍 모델 진단값
 */
@Composable
fun RecordScreen(collector: Collector, permGranted: Boolean, onRequestPerms: () -> Unit) {
    var ui by remember { mutableStateOf(collector.snapshot()) }
    // Activity 가 다시 만들어져도 입력값은 측정기(ViewModel 쪽)에서 되살린다
    var tag by remember { mutableStateOf(collector.tag) }
    var mode by remember { mutableStateOf(collector.mode) }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            ui = collector.snapshot()
            collector.consumeNotice().takeIf { it.isNotEmpty() }?.let { notice = it }
            kotlinx.coroutines.delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BLE Channel Probe", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Phase 0 — 채널 라벨 RSSI 수집", fontSize = 13.sp, color = Color(0xFF64748B))

        /* 모드 */
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = {
                        if (ui.running) {
                            notice = "측정 중에는 모드를 바꾸지 않습니다. 정지 후 바꾸세요."
                        } else {
                            mode = m; collector.mode = m
                        }
                    },
                    label = { Text(if (m == Mode.RAW) "RAW (아무 기기)" else "BEACON (우리 비콘)") }
                )
            }
        }

        /* 실험 조건 태그 */
        OutlinedTextField(
            value = tag,
            onValueChange = { tag = it; collector.tag = it },
            label = { Text("실험 조건 tag  예: 3m_사람0명_ch37") },
            singleLine = true,
            enabled = !ui.running,
            modifier = Modifier.fillMaxWidth()
        )

        /* 시작 / 정지 */
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!permGranted) { onRequestPerms(); return@Button }
                    if (tag.isBlank()) { notice = "tag 를 먼저 입력하세요. 나중에 CSV 를 구분할 수 없습니다."; return@Button }
                    collector.mode = mode
                    collector.tag = tag
                    if (!collector.start()) notice = collector.consumeNotice()
                },
                enabled = !ui.running,
                modifier = Modifier.weight(1f)
            ) { Text("시작") }

            OutlinedButton(
                onClick = {
                    collector.stop()
                    notice = "저장됨: ${collector.fileName} · ${collector.eventFileName}"
                },
                enabled = ui.running,
                modifier = Modifier.weight(1f)
            ) { Text("정지") }
        }

        if (!permGranted) {
            Notice("권한이 필요합니다. 시작을 누르면 요청합니다." +
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                        " (Android 11 이하는 위치 권한과 위치 서비스 ON 이 필요합니다)" else "",
                Color(0xFFFEF3C7))
        }
        if (notice.isNotEmpty()) Notice(notice, Color(0xFFFEF2F2))

        Text(
            "스캔 시작 여유: ${collector.startBudget()} / $START_LIMIT  " +
                    "(30초에 5회 제한 — 재시작 버튼을 습관적으로 누르지 말 것)",
            fontSize = 11.sp, color = Color(0xFF94A3B8)
        )

        HorizontalDivider()

        /* 요약 */
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("경과", String.format(Locale.US, "%.1f s", ui.elapsedSec))
            Stat("rows", ui.totalRows.toString())
            Stat("events", ui.eventRows.toString())
            Stat("파일", ui.fileName.take(22))
        }

        HorizontalDivider()

        /* 채널별 표 */
        Text("채널별 현황", fontWeight = FontWeight.SemiBold)
        Header()
        ui.rows.forEach { RowLine(it) }
        if (ui.rows.isEmpty()) {
            Text("아직 수신된 패킷이 없습니다.", fontSize = 12.sp, color = Color(0xFF94A3B8))
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "rows=수신 행 수 · pkt/s=rows÷경과초(조건 비교는 이 값) · " +
                    "seqObs%=고유seq÷seq구간(연속성) · dup=rows÷고유seq(진단값)\n" +
                    "ALL* = ALL_CONTROL 모드 표식이며 실제 RF 채널 번호가 아님\n" +
                    "back = seq 역행 횟수. 0이 아니면 T2 확인 필요\n" +
                    "events = 상태 이벤트 로그 행 수. 10초마다 최소 1씩 늘어야 정상",
            fontSize = 11.sp, color = Color(0xFF94A3B8), lineHeight = 15.sp
        )
    }
}

@Composable private fun Stat(k: String, v: String) = Column {
    Text(k, fontSize = 11.sp, color = Color(0xFF94A3B8))
    Text(v, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable private fun Notice(text: String, bg: Color) =
    Box(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(text, fontSize = 12.sp, color = Color(0xFF0F172A))
    }

@Composable private fun Header() = Row(Modifier.fillMaxWidth()) {
    listOf("ch" to 0.9f, "rows" to 1.2f, "pkt/s" to 1.1f, "mean" to 1.2f,
        "sd" to 1.0f, "seqObs" to 1.2f, "dup" to 0.9f, "lastSeq" to 1.3f, "back" to 0.8f)
        .forEach { (t, w) ->
            Text(t, Modifier.weight(w), fontSize = 11.sp,
                color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
        }
}

@Composable private fun RowLine(r: StatRow) = Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Cell(r.label, 0.9f, true)
    Cell(r.rows.toString(), 1.2f)
    Cell(String.format(Locale.US, "%.1f", r.pktPerSec), 1.1f)
    Cell(String.format(Locale.US, "%.1f", r.mean), 1.2f)
    Cell(String.format(Locale.US, "%.2f", r.sd), 1.0f)
    Cell(if (r.seqObs > 0) String.format(Locale.US, "%.0f%%", r.seqObs) else "-", 1.2f)
    Cell(if (r.dup > 0) String.format(Locale.US, "%.2f", r.dup) else "-", 0.9f)
    Cell(if (r.lastSeq >= 0) r.lastSeq.toString() else "-", 1.3f)
    Cell(r.backward.toString(), 0.8f)
}

/* 표 한 칸 */
@Composable private fun RowScope.Cell(t: String, w: Float, bold: Boolean = false) =
    Text(t, Modifier.weight(w), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
