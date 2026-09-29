package com.knu.blechprobe.collect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.knu.blechprobe.model.Mode
import com.knu.blechprobe.model.StatRow
import com.knu.blechprobe.model.UiState
import com.knu.blechprobe.parse.BeaconParser
import com.knu.blechprobe.parse.CH_ALL_LABEL
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale

private const val START_WINDOW_MS = 30_000L
const val START_LIMIT = 4                      // 시스템 한도 5. 여유를 두고 4에서 막는다
private const val TICK_MS = 10_000L            // 이벤트 로그에 누적 rows·상태를 다시 적는 주기

/**
 * 스캔 → 파싱 → CSV. 측정 1회에 파일 2개(데이터 + events_<stamp>.csv).
 *
 * [중요] 스캔은 한 번 시작해 계속 유지한다. 모드 전환은 스캔을 재시작하지 않는다.
 *        30초에 startScan 5회를 넘기면 시스템이 조용히 결과를 끊기 때문이다.
 *        (Android 공식 문서에 명시된 제한. 앱은 4회에서 미리 막는다)
 */
class Collector(private val ctx: Context) {

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val phone = PhoneState(ctx)
    private val main = Handler(Looper.getMainLooper())

    private val lock = Any()
    private val stats = LinkedHashMap<Int, ChStat>()     // BEACON: channel_id, RAW: 고정 -1
    private var data: CsvSink? = null
    private var events: CsvSink? = null
    private var file: File? = null
    private var eventFile: File? = null
    private var startedAtMs = 0L            // 파일명·rx_wall_ms 용 벽시계
    private var startedAtElapsed = 0L       // 경과·pkt/s 용 단조시계
    private var endedAtElapsed = 0L         // 정지 시각. 0이면 진행 중
    private var totalRows = 0L
    private var eventRows = 0L

    @Volatile var mode: Mode = Mode.RAW
    @Volatile var tag: String = ""
    @Volatile var running = false
        private set

    /** 마지막으로 알려진 Activity 생명주기. 이벤트 행마다 같이 적힌다 */
    @Volatile private var activityState = "-"

    private val startTimes = ArrayDeque<Long>()

    val fileName: String get() = file?.name ?: "-"
    val eventFileName: String get() = eventFile?.name ?: "-"

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
            logEvent("scan_failed", errorCode.toString())
            // 여기서 파일을 닫지 않으면 버퍼에 남은 행이 CSV 에 안 들어간다
            finish("scan_failed")
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
            stats.clear(); totalRows = 0; eventRows = 0
            startedAtMs = System.currentTimeMillis()
            startedAtElapsed = SystemClock.elapsedRealtime()
            endedAtElapsed = 0L
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(startedAtMs)
            val prefix = if (mode == Mode.BEACON) "beacon" else "raw"
            val dir = File(ctx.getExternalFilesDir(null), "ble_logs").apply { mkdirs() }
            file = File(dir, "${prefix}_$stamp.csv")
            // 새 컬럼(ts_nanos, address)은 맨 끝에 붙인다. 분석 스크립트는 이름으로 읽어서 영향이 없다
            data = CsvSink(
                file!!,
                if (mode == Mode.BEACON)
                    "rx_wall_ms,rx_elapsed_ms,beacon_id,channel_id,seq,rssi,tx_uptime_ms,tx_power_dbm,tag,ts_nanos,address"
                else
                    "rx_wall_ms,rx_elapsed_ms,address,rssi,name,tag,ts_nanos",
                flushEveryRow = false,
            )
            eventFile = File(dir, "events_$stamp.csv")
            events = CsvSink(eventFile!!, EVENT_HEADER, flushEveryRow = true)
        }

        // 필터 없이 스캔한다. 모드는 기록 대상만 바꾼다 (재시작 금지)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setLegacy(true) }
            .build()

        try {
            scanner!!.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            postNotice("권한이 없습니다: ${e.message}")
            closeSinks()
            return false
        }
        startTimes.addLast(SystemClock.elapsedRealtime())
        running = true

        logEvent(
            "session_start", mode.name,
            "file=${file?.name} maker=${Build.MANUFACTURER} model=${Build.MODEL} " +
                "sdk=${Build.VERSION.SDK_INT} scan=LOW_LATENCY/legacy/no_filter " +
                "batt_opt_exempt=${phone.battOptExempt()}"
        )
        registerMonitors()
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop(reason: String = "user") = finish(reason)

    /** 정상 정지·스캔 실패·ViewModel 정리가 같은 경로로 끝나게 한다. 두 번 불려도 안전하다. */
    @SuppressLint("MissingPermission")
    private fun finish(reason: String) {
        if (!running) return
        running = false
        unregisterMonitors()
        try { scanner?.stopScan(callback) } catch (_: SecurityException) {}
        synchronized(lock) { if (endedAtElapsed == 0L) endedAtElapsed = SystemClock.elapsedRealtime() }
        logEvent("session_stop", reason)
        closeSinks()
    }

    private fun closeSinks() {
        synchronized(lock) {
            data?.close(); data = null
            events?.close(); events = null
        }
    }

    /* ---------------- 상태 이벤트 로그 ---------------- */

    /** Activity 생명주기를 남긴다. 측정 중이 아닐 때는 상태 값만 갱신된다 */
    fun onActivity(state: String, detail: String = "") {
        activityState = state
        logEvent("activity", state, detail)
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> logEvent("screen", "on")
                Intent.ACTION_SCREEN_OFF -> logEvent("screen", "off")
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> logEvent("power_save", phone.powerSave())
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
                PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED -> logEvent("doze", phone.doze())
                Intent.ACTION_POWER_CONNECTED -> logEvent("charging", "connected")
                Intent.ACTION_POWER_DISCONNECTED -> logEvent("charging", "disconnected")
                BluetoothAdapter.ACTION_STATE_CHANGED -> logEvent(
                    "bt", btName(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
                )
            }
        }
    }

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** 10초마다 누적 rows 와 상태를 다시 적는다. 간격이 10초보다 크게 벌어지면 그동안 앱이 멈춰 있었다는 뜻 */
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            logEvent("tick", withHeadroom = true)
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun registerMonitors() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
            }
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // EXPORTED 로 등록한다. 블루투스 상태 방송은 system UID 가 아닌 특권 앱이 보내므로
        // NOT_EXPORTED 수신기에는 오지 않는다 (developer.android.com 'Broadcasts overview').
        // 위 액션은 전부 보호된(protected) 시스템 방송이라 다른 앱이 흉내 낼 수 없다.
        ContextCompat.registerReceiver(ctx, stateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val l = PowerManager.OnThermalStatusChangedListener { s -> logEvent("thermal", s.toString()) }
            pm.addThermalStatusListener(l)      // 콜백은 메인 스레드로 온다
            thermalListener = l
        }
        main.postDelayed(tick, TICK_MS)
    }

    private fun unregisterMonitors() {
        main.removeCallbacks(tick)
        try { ctx.unregisterReceiver(stateReceiver) } catch (_: IllegalArgumentException) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { pm.removeThermalStatusListener(it) }
        }
        thermalListener = null
    }

    /** events_<stamp>.csv 에 한 행. 측정 중이 아니면(파일이 없으면) 아무것도 하지 않는다 */
    private fun logEvent(event: String, value: String = "", detail: String = "", withHeadroom: Boolean = false) {
        if (events == null) return
        val wall = System.currentTimeMillis()
        val nanos = SystemClock.elapsedRealtimeNanos()      // 데이터 CSV 의 ts_nanos 와 같은 시계
        val state = phone.row(activityState, withHeadroom)  // 시스템 서비스 호출이라 잠금 밖에서 읽는다
        synchronized(lock) {
            val sink = events ?: return
            val elapsed = nanos / 1_000_000 - startedAtElapsed
            sink.write("$wall,$elapsed,$nanos,$event,${csv(value)},$totalRows,$state,${csv(detail)},${csv(tag)}")
            eventRows++
        }
    }

    /* ---------------- 수신 처리 ---------------- */

    private fun handle(result: ScanResult) {
        if (!running) return
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
        // 콜백이 불린 시각이 아니라 컨트롤러가 패킷을 관측한 시각. 부팅 후 ns (전달 지연이 빠져 있다)
        val tsNanos = result.timestampNanos
        val rssi = result.rssi
        val record = result.scanRecord
        val addr = result.device?.address ?: "??"

        if (mode == Mode.BEACON) {
            val b = BeaconParser.parse(record) ?: return
            synchronized(lock) {
                stats.getOrPut(b.channelId) { ChStat() }.add(rssi, b.seq)
                totalRows++
                data?.write(
                    "$wall,$elapsed,${b.beaconId},${b.channelId},${b.seq},$rssi,${b.uptimeMs},${b.txPowerDbm}," +
                        "${csv(tag)},$tsNanos,$addr"
                )
            }
        } else {
            val name = try { record?.deviceName ?: "" } catch (_: SecurityException) { "" }
            synchronized(lock) {
                stats.getOrPut(-1) { ChStat() }.add(rssi, -1)
                totalRows++
                data?.write("$wall,$elapsed,$addr,$rssi,${csv(name)},${csv(tag)},$tsNanos")
            }
        }
    }

    /* ---------------- 화면용 스냅샷 ---------------- */

    fun snapshot(): UiState = synchronized(lock) {
        // 정지 후에는 시간이 더 흐르지 않아야 한다. 안 그러면 pkt/s 가 계속 내려간다
        val endE = if (endedAtElapsed > 0L) endedAtElapsed else SystemClock.elapsedRealtime()
        val sec = if (startedAtElapsed == 0L) 0.0 else (endE - startedAtElapsed) / 1000.0
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
            eventRows = eventRows, fileName = fileName, rows = list,
        )
    }
}
