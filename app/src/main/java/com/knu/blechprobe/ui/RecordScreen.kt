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
import com.knu.blechprobe.model.RunType
import com.knu.blechprobe.model.StatRow
import com.knu.blechprobe.model.UiState
import java.util.Locale
import kotlin.math.ceil

/** 기록 대상 패킷이 이만큼 안 오면 '끊김'으로 빨갛게 표시한다 [추론 — 태그 광고 주기 확인 뒤 조정] */
private const val STALE_S = 5.0

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
    var form by remember { mutableStateOf(collector.form) }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            ui = collector.snapshot()
            collector.consumeNotice().takeIf { it.isNotEmpty() }?.let { notice = it }
            // 타이머를 부드럽게 보이려고 0.25초마다 갱신
            kotlinx.coroutines.delay(250)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BLE Channel Probe", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("① 측정", fontSize = 13.sp, color = Subtle)

        /* 런 진행 중에는 큰 타이머를 맨 위에 둔다 — 멀리서 보여야 한다 */
        ui.runType?.let { rt -> if (ui.running) RunPanel(rt, ui.runClockSec ?: 0.0) }

        /* 모드 */
        ChipRow(Mode.entries, mode, !ui.running, { it.name }) { m ->
            mode = m; collector.mode = m
        }
        Text(
            when (mode) {
                Mode.RAW -> "주변 아무 기기 — A3(10분 연속) 확인용. 스캔 설정은 9/22 과 같다(legacy)"
                Mode.BEACON -> "우리 ESP32 비콘 — M4 확인용. 스캔 설정은 TAG 와 같다(확장 광고 포함, PHY 1M)"
                Mode.TAG -> "SmartTag2 (서비스 데이터 FD5A/FD59) — 본 측정. 확장 광고 포함, PHY 1M"
            },
            fontSize = 11.sp, color = Muted,
        )

        /* 조건 */
        if (mode == Mode.TAG) {
            TagFormSection(form, enabled = !ui.running) { f -> form = f; collector.updateForm(f) }
        } else {
            OutlinedTextField(
                value = tag,
                onValueChange = { tag = it; collector.tag = it },
                label = { Text("실험 조건 tag  예: 3m_사람0명_ch37") },
                singleLine = true,
                enabled = !ui.running,
                modifier = Modifier.fillMaxWidth()
            )
        }

        /* 시작 / 정지 */
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!permGranted) { onRequestPerms(); return@Button }
                    if (mode != Mode.TAG && tag.isBlank()) {
                        notice = "tag 를 먼저 입력하세요. 나중에 CSV 를 구분할 수 없습니다."; return@Button
                    }
                    collector.mode = mode
                    if (mode != Mode.TAG) collector.tag = tag
                    notice = ""
                    if (!collector.start()) notice = collector.consumeNotice()
                },
                enabled = !ui.running,
                modifier = Modifier.weight(1f)
            ) { Text(if (mode == Mode.TAG) "런 시작 (${form.runType.countdownS}초 뒤 기록)" else "시작") }

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

        /* 끝난 런 표시 — 런이 끝난 뒤에만 폰을 만진다 */
        if (ui.canFlag) FlagPanel(ui.lastFlag) { valid, reason ->
            notice = if (collector.flagLastRun(valid, reason))
                "표시함: ${if (valid) "유효" else "무효"}" else "표시하지 못했습니다."
        }

        Text(
            "스캔 시작 여유: ${collector.startBudget()} / $START_LIMIT  " +
                    "(30초에 5회 제한 — 재시작 버튼을 습관적으로 누르지 말 것)",
            fontSize = 11.sp, color = Muted
        )

        HorizontalDivider()

        /* 점검 표시 — 기록 대상 패킷 기준 */
        Checks(ui)

        /* 요약 */
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("경과", String.format(Locale.US, "%.1f s", ui.elapsedSec))
            Stat("rows", ui.totalRows.toString())
            Stat("events", ui.eventRows.toString())
            Stat("파일", ui.fileName.take(22))
        }
        if (ui.cond.isNotEmpty()) {
            Text("tag/cond  ${ui.cond}", fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
        }

        HorizontalDivider()

        /* 채널별 표 */
        Text("채널별 현황", fontWeight = FontWeight.SemiBold)
        Header()
        ui.rows.forEach { RowLine(it) }
        if (ui.rows.isEmpty()) {
            Text("아직 수신된 패킷이 없습니다.", fontSize = 12.sp, color = Muted)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "rows=수신 행 수 · pkt/s=rows÷경과초(조건 비교는 이 값) · " +
                    "seqObs%=고유seq÷seq구간(연속성) · dup=rows÷고유seq(진단값)\n" +
                    "ALL* = ALL_CONTROL 모드 표식이며 실제 RF 채널 번호가 아님\n" +
                    "back = seq 역행 횟수. 0이 아니면 T2 확인 필요\n" +
                    "events = 상태 이벤트 로그 행 수. 10초마다 최소 1씩 늘어야 정상",
            fontSize = 11.sp, color = Muted, lineHeight = 15.sp
        )
    }
}

/**
 * 런 진행 표시. 조작자·돕는 사람이 멀리서 볼 수 있게 크게.
 * t = 런 시계(초). 카운트다운 중에는 음수.
 */
@Composable
private fun RunPanel(rt: RunType, t: Double) {
    val inS = rt.blockInS
    val outS = rt.blockOutS
    val (big, bg) = when {
        t < 0 -> "물러나세요" to Color(0xFFE2E8F0)
        inS != null && outS != null && t >= inS && t < outS -> "가림 중 — 그대로" to Color(0xFFFDE68A)
        inS != null && outS != null && t >= outS -> "나가 있으세요" to Color(0xFFDCFCE7)
        else -> "기록 중 — 가림 없음" to Color(0xFFDCFCE7)
    }
    val clock = if (t < 0) "−${ceil(-t).toInt()}" else "${t.toInt()} / ${rt.durationS} s"
    val next = when {
        t < 0 -> "알림음 1번 = 기록 시작"
        inS != null && t < inS -> "다음: ${inS} s 알림음 2번 → 들어오세요 (${ceil(inS - t).toInt()}초 뒤)"
        outS != null && t < outS -> "다음: ${outS} s 알림음 3번 → 나가세요 (${ceil(outS - t).toInt()}초 뒤)"
        else -> "다음: ${rt.durationS} s 긴 알림음 → 자동 종료 (${ceil(rt.durationS - t).toInt()}초 뒤)"
    }
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(rt.label, fontSize = 12.sp, color = Subtle)
        Text(big, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(clock, fontSize = 48.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Ink)
        Text(next, fontSize = 12.sp, color = Subtle)
        Text("측정 중에는 폰을 만지지 마세요", fontSize = 11.sp, color = Subtle)
    }
}

/** 점검 표시: 태그가 끊기면 '마지막 수신'이 빨개진다 */
@Composable
private fun Checks(ui: UiState) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Stat("pkt/s", String.format(Locale.US, "%.1f", ui.pktPerSec))
    Stat("마지막 RSSI", ui.lastRssi?.toString() ?: "-")
    Stat("광고", when (ui.lastLegacy) { true -> "legacy"; false -> "확장"; null -> "-" })
    val since = ui.sinceLastSec
    Stat(
        "마지막 수신",
        since?.let { String.format(Locale.US, "%.1f s 전", it) } ?: "-",
        if (ui.running && since != null && since > STALE_S) Warn else Ink,
    )
}

/** 끝난 런에 유효/무효 표시. 무효면 이유를 적는다 (예: 사람이 늦게 움직임) */
@Composable
private fun FlagPanel(lastFlag: String?, onFlag: (Boolean, String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "방금 런 표시" + (lastFlag?.let { " — 현재: ${if (it == "valid") "유효" else "무효"}" } ?: " — 아직 안 함"),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = reason, onValueChange = { reason = it }, singleLine = true,
            label = { Text("이유 (무효일 때)  예: 사람이 5초 늦게 들어옴") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onFlag(true, reason) }, modifier = Modifier.weight(1f)) { Text("유효") }
            OutlinedButton(onClick = { onFlag(false, reason) }, modifier = Modifier.weight(1f)) { Text("무효") }
        }
    }
}

@Composable private fun Header() = Row(Modifier.fillMaxWidth()) {
    listOf("ch" to 0.9f, "rows" to 1.2f, "pkt/s" to 1.1f, "mean" to 1.2f,
        "sd" to 1.0f, "seqObs" to 1.2f, "dup" to 0.9f, "lastSeq" to 1.3f, "back" to 0.8f)
        .forEach { (t, w) ->
            Text(t, Modifier.weight(w), fontSize = 11.sp,
                color = Subtle, fontWeight = FontWeight.SemiBold)
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
