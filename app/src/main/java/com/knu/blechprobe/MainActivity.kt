package com.knu.blechprobe

/* ============================================================================
 * MainActivity.kt — Phase 0 BLE 수집 앱 (단일 파일)
 *
 * 목적: ESP32 비콘이 payload 에 적어 보낸 채널 번호와 RSSI 를 CSV 로 남긴다.
 *       측위·삼변측량·모델은 들어 있지 않다. Phase 0 범위는 "수집이 되는가" 까지.
 *
 * 모드
 *   RAW    — 주변 아무 BLE 기기나 기록. 보드가 오기 전 권한/쓰로틀링/CSV 경로 검증용
 *   BEACON — company id 0xFFFF, proto_ver 1 페이로드만 파싱해 기록
 *
 * [중요] 스캔은 한 번 시작해 계속 유지한다. 모드 전환은 스캔을 재시작하지 않는다.
 *        30초에 startScan 5회를 넘기면 시스템이 조용히 결과를 끊기 때문이다.
 *        (Android 공식 문서에 명시된 제한. 앱은 4회에서 미리 막는다)
 *
 * 지표 정의 — README 와 analyze.py 에 맞춘다
 *   rows     수신 행 수 (같은 seq 중복 포함)
 *   pkt/s    rows ÷ 경과초.  가정 없는 직접 측정값. 조건 비교는 이 값으로
 *   seqObs%  고유 seq ÷ (max−min+1).  연속성 지표. 100% 를 넘을 수 없다
 *   dup      rows ÷ 고유 seq.  타이밍 모델 진단값
 * ========================================================================== */

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.sqrt

/* ------------------------------ 상수 ------------------------------ */

private const val COMPANY_ID = 0xFFFF      // Manufacturer Specific Data company id
private const val PROTO_VER = 1            // 페이로드 스키마 버전
private const val PAYLOAD_LEN = 12         // proto_ver..tx_power_dbm
private const val CH_ALL_LABEL = 0         // ALL_CONTROL 모드 표식 (실제 RF 채널 아님)

private const val START_WINDOW_MS = 30_000L
private const val START_LIMIT = 4          // 시스템 한도 5. 여유를 두고 4에서 막는다

enum class Mode { RAW, BEACON }

/* --------------------------- 채널별 통계 --------------------------- */

private class ChStat {
    var rows = 0L
    var sum = 0.0
    var sumSq = 0.0
    var min = Int.MAX_VALUE
    var max = Int.MIN_VALUE
    var lastSeq = -1L
    var minSeq = -1L
    var maxSeq = -1L
    var backward = 0L                      // seq 역행 횟수 (T2 진단)
    val uniqSeq = HashSet<Long>()

    fun add(rssi: Int, seq: Long) {
        rows++
        sum += rssi
        sumSq += rssi.toDouble() * rssi
        if (rssi < min) min = rssi
        if (rssi > max) max = rssi
        if (seq >= 0) {
            if (lastSeq >= 0 && seq < lastSeq) backward++
            lastSeq = seq
            if (minSeq < 0 || seq < minSeq) minSeq = seq
            if (maxSeq < 0 || seq > maxSeq) maxSeq = seq
            uniqSeq.add(seq)
        }
    }

    val mean get() = if (rows > 0) sum / rows else 0.0
    val sd: Double
        get() {
            if (rows < 2) return 0.0
            val v = sumSq / rows - mean * mean
            return if (v > 0) sqrt(v) else 0.0
        }
    val span get() = if (minSeq < 0) 0L else maxSeq - minSeq + 1
    val seqObs get() = if (span > 0) 100.0 * uniqSeq.size / span else 0.0
    val dup get() = if (uniqSeq.isNotEmpty()) rows.toDouble() / uniqSeq.size else 0.0
}

/* 화면에 뿌릴 한 줄 (불변 스냅샷) */
data class StatRow(
    val label: String, val rows: Long, val mean: Double, val sd: Double,
    val pktPerSec: Double, val seqObs: Double, val dup: Double,
    val lastSeq: Long, val backward: Long, val min: Int, val max: Int,
)

data class UiState(
    val running: Boolean = false,
    val mode: Mode = Mode.RAW,
    val elapsedSec: Double = 0.0,
    val totalRows: Long = 0,
    val fileName: String = "-",
    val rows: List<StatRow> = emptyList(),
    val notice: String = "",
)

/* ------------------------------ 수집기 ------------------------------ */

class Collector(private val ctx: Context) {

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null

    private val lock = Any()
    private val stats = LinkedHashMap<Int, ChStat>()     // BEACON: channel_id, RAW: 고정 -1
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var startedAtMs = 0L
    private var pending = 0
    private var totalRows = 0L

    @Volatile var mode: Mode = Mode.RAW
    @Volatile var tag: String = ""
    @Volatile var running = false
        private set

    private val startTimes = ArrayDeque<Long>()

    val fileName: String get() = file?.name ?: "-"

    /** 남은 startScan 여유 횟수. 0이면 지금 시작하면 안 된다. */
    fun startBudget(): Int {
        val now = SystemClock.elapsedRealtime()
        while (startTimes.isNotEmpty() && now - startTimes.first() > START_WINDOW_MS) {
            startTimes.pollFirst()
        }
        return (START_LIMIT - startTimes.size).coerceAtLeast(0)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            running = false
            postNotice("스캔 실패 (errorCode=$errorCode). 블루투스를 껐다 켜고 다시 시도하세요.")
        }
    }

    @Volatile private var notice: String = ""
    private fun postNotice(m: String) { notice = m }
    fun consumeNotice(): String { val n = notice; notice = ""; return n }

    /* ---------------- 시작 / 정지 ---------------- */

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (adapter == null) { postNotice("이 기기에서 블루투스 어댑터를 찾을 수 없습니다."); return false }
        if (!adapter.isEnabled) { postNotice("블루투스가 꺼져 있습니다. 켜고 다시 시작하세요."); return false }
        if (startBudget() <= 0) {
            postNotice("쓰로틀링 보호 — 30초에 스캔 시작 5회 제한입니다. 잠시 기다렸다 시작하세요.")
            return false
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { postNotice("BluetoothLeScanner 를 얻지 못했습니다."); return false }

        synchronized(lock) {
            stats.clear(); totalRows = 0; pending = 0
            startedAtMs = System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(startedAtMs)
            val prefix = if (mode == Mode.BEACON) "beacon" else "raw"
            val dir = File(ctx.getExternalFilesDir(null), "ble_logs").apply { mkdirs() }
            file = File(dir, "${prefix}_$stamp.csv")
            writer = BufferedWriter(FileWriter(file!!))
            writer!!.write(
                if (mode == Mode.BEACON)
                    "rx_wall_ms,rx_elapsed_ms,beacon_id,channel_id,seq,rssi,tx_uptime_ms,tx_power_dbm,tag\n"
                else
                    "rx_wall_ms,rx_elapsed_ms,address,rssi,name,tag\n"
            )
        }

        // 필터 없이 스캔한다. 모드는 기록 대상만 바꾼다 (재시작 금지)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setLegacy(true) }
            .build()

        return try {
            scanner!!.startScan(null, settings, callback)
            startTimes.addLast(SystemClock.elapsedRealtime())
            running = true
            true
        } catch (e: SecurityException) {
            postNotice("권한이 없습니다: ${e.message}")
            closeWriter()
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        try { scanner?.stopScan(callback) } catch (_: SecurityException) {}
        closeWriter()
    }

    private fun closeWriter() {
        synchronized(lock) {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            writer = null
        }
    }

    /* ---------------- 수신 처리 ---------------- */

    private fun handle(result: ScanResult) {
        if (!running) return
        val wall = System.currentTimeMillis()
        val elapsed = wall - startedAtMs
        val rssi = result.rssi
        val record = result.scanRecord

        if (mode == Mode.BEACON) {
            val mfg = record?.getManufacturerSpecificData(COMPANY_ID) ?: return
            if (mfg.size < PAYLOAD_LEN) return
            if ((mfg[0].toInt() and 0xFF) != PROTO_VER) return

            val beaconId = mfg[1].toInt() and 0xFF
            val channelId = mfg[2].toInt() and 0xFF
            val seq = le32(mfg, 3)
            val uptime = le32(mfg, 7)
            val txPower = mfg[11].toInt()                 // int8

            synchronized(lock) {
                stats.getOrPut(channelId) { ChStat() }.add(rssi, seq)
                totalRows++
                writer?.write("$wall,$elapsed,$beaconId,$channelId,$seq,$rssi,$uptime,$txPower,${csv(tag)}\n")
                maybeFlush()
            }
        } else {
            val addr = result.device?.address ?: "??"
            val name = try { record?.deviceName ?: "" } catch (_: SecurityException) { "" }
            synchronized(lock) {
                stats.getOrPut(-1) { ChStat() }.add(rssi, -1)
                totalRows++
                writer?.write("$wall,$elapsed,$addr,$rssi,${csv(name)},${csv(tag)}\n")
                maybeFlush()
            }
        }
    }

    private fun maybeFlush() {
        pending++
        if (pending >= 200) { pending = 0; try { writer?.flush() } catch (_: Exception) {} }
    }

    private fun le32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF)) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /* ---------------- 화면용 스냅샷 ---------------- */

    fun snapshot(): UiState = synchronized(lock) {
        val sec = if (startedAtMs == 0L) 0.0 else (System.currentTimeMillis() - startedAtMs) / 1000.0
        val list = stats.entries.sortedBy { it.key }.map { (ch, s) ->
            StatRow(
                label = when {
                    ch == -1 -> "RAW"
                    ch == CH_ALL_LABEL -> "ALL*"          // 실제 RF 채널이 아님
                    else -> ch.toString()
                },
                rows = s.rows, mean = s.mean, sd = s.sd,
                pktPerSec = if (sec > 0) s.rows / sec else 0.0,
                seqObs = s.seqObs, dup = s.dup, lastSeq = s.lastSeq,
                backward = s.backward, min = if (s.rows > 0) s.min else 0,
                max = if (s.rows > 0) s.max else 0,
            )
        }
        UiState(
            running = running, mode = mode, elapsedSec = sec, totalRows = totalRows,
            fileName = fileName, rows = list,
        )
    }
}

/* ------------------------------ Activity ------------------------------ */

class MainActivity : ComponentActivity() {

    private lateinit var collector: Collector
    private var permGranted by mutableStateOf(false)

    private val perms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,   // API 30 이하는 필수 + 위치 서비스 ON
            )

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            permGranted = res.values.all { it }
            if (!permGranted) {
                Toast.makeText(this, "권한이 거부되면 스캔할 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }

    private fun hasPerms() = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 화면이 꺼지면 필터 없는 스캔이 멈출 수 있다. Phase 0 은 화면을 켠 채 측정한다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        collector = Collector(applicationContext)
        permGranted = hasPerms()

        setContent { MaterialTheme { Screen() } }
    }

    override fun onDestroy() {
        collector.stop()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        var ui by remember { mutableStateOf(UiState()) }
        var tag by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(Mode.RAW) }
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
                        if (!permGranted) { requestPerms.launch(perms); return@Button }
                        if (tag.isBlank()) { notice = "tag 를 먼저 입력하세요. 나중에 CSV 를 구분할 수 없습니다."; return@Button }
                        collector.mode = mode
                        collector.tag = tag
                        if (!collector.start()) notice = collector.consumeNotice()
                    },
                    enabled = !ui.running,
                    modifier = Modifier.weight(1f)
                ) { Text("시작") }

                OutlinedButton(
                    onClick = { collector.stop(); notice = "저장됨: ${collector.fileName}" },
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
                        "back = seq 역행 횟수. 0이 아니면 T2 확인 필요",
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

    @Composable privatwlrme fun Header() = Row(Modifier.fillMaxWidth()) {
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

    /* 표 한 칸. 로컬 함수로 두면 @Composable 호출이 막혀서 멤버로 뺐다 */
    @Composable private fun RowScope.Cell(t: String, w: Float, bold: Boolean = false) =
        Text(t, Modifier.weight(w), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}