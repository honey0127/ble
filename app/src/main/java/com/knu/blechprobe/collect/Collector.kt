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
import com.knu.blechprobe.model.AutoChecks
import com.knu.blechprobe.model.InvalidReason
import com.knu.blechprobe.model.ManualChecks
import com.knu.blechprobe.model.Mode
import com.knu.blechprobe.model.blockers
import com.knu.blechprobe.model.RunEvent
import com.knu.blechprobe.model.RunType
import com.knu.blechprobe.model.Sample
import com.knu.blechprobe.model.StatRow
import com.knu.blechprobe.model.TagForm
import com.knu.blechprobe.model.UiState
import com.knu.blechprobe.parse.BeaconParser
import com.knu.blechprobe.parse.CH_ALL_LABEL
import com.knu.blechprobe.parse.TagFilter
import com.knu.blechprobe.parse.toHex
import com.knu.blechprobe.source.SampleListener
import com.knu.blechprobe.source.SampleSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

private const val START_WINDOW_MS = 30_000L
const val START_LIMIT = 4                      // 시스템 한도 5. 여유를 두고 4에서 막는다
private const val TICK_MS = 10_000L            // 이벤트 로그에 누적 rows·상태를 다시 적는 주기
private const val KEY_RAW = -1                 // stats 키. BEACON 은 channel_id
private const val KEY_TAG = -2

/**
 * 스캔 → 파싱 → CSV. 실시간 SampleSource 이기도 하다.
 * 측정 1회 = 파일 3개, 같은 stamp: 데이터(raw_/beacon_/tag_) + events_ + meta_<stamp>.json
 *
 * [중요] 스캔은 측정(런)마다 한 번 시작해 끝까지 유지한다. 런 도중 재시작하지 않는다.
 *        30초에 startScan 5회를 넘기면 시스템이 그 스캔을 시작하지 않는다. 에러 콜백 없이
 *        로그("App ... is scanning too frequently")만 남는다. 공식 문서가 아니라 AOSP 소스
 *        (Bluetooth AppScanStats: NUM_SCAN_DURATIONS_KEPT=5, EXCESSIVE_SCANNING_PERIOD_MS=30 s,
 *        Android 7+)의 규칙이다 [AOSP 소스]. 제조사가 바꿨을 수 있다. 앱은 4회에서 미리 막는다
 */
class Collector(private val ctx: Context) : SampleSource {

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val phone = PhoneState(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val formStore = FormStore(ctx)
    private val preflight = Preflight(ctx)
    private val listeners = CopyOnWriteArrayList<SampleListener>()

    private val lock = Any()
    private val stats = LinkedHashMap<Int, ChStat>()
    private var data: CsvSink? = null
    private var events: CsvSink? = null
    private var file: File? = null
    private var eventFile: File? = null
    private var metaFile: File? = null
    private var meta: JSONObject? = null
    private var startedAtMs = 0L            // 파일명·rx_wall_ms 용 벽시계
    private var startedAtElapsed = 0L       // 경과·pkt/s 용 단조시계
    private var endedAtElapsed = 0L         // 정지 시각. 0이면 진행 중
    private var totalRows = 0L
    private var eventRows = 0L
    private var scanSeq = 0
    private var lastRowElapsed = -1L        // 마지막 기록 행의 rx_elapsed_ms
    private var lastRssi: Int? = null
    private var lastLegacy: Boolean? = null

    /* 런 진행 (TAG 모드). 알림음·자동 종료 예약은 runToken 으로 한꺼번에 취소한다 */
    private val runToken = Any()
    private var runType: RunType? = null
    private var runForm: TagForm? = null
    private var beeper: Beeper? = null

    /* 끝난 런 — 유효/무효 표시를 받으려고 파일을 기억해 둔다 */
    private var flagTarget: File? = null
    private var flagValue: String? = null

    @Volatile var mode: Mode = Mode.RAW
    /** RAW·BEACON 은 자유 입력, TAG 는 시작할 때 조건 코드로 덮어쓴다 */
    @Volatile var tag: String = ""
    @Volatile var running = false
        private set

    /** RAW 를 TAG 와 같은 스캔 설정(확장 광고 포함)으로 받을지. 앱을 켤 때마다 꺼진 상태로 시작한다 */
    @Volatile var rawExtended = false

    /** 시작 전 점검 중 사람이 확인하는 것. 앱을 다시 켜면 처음부터 다시 확인한다 */
    @Volatile var manual = ManualChecks()

    /** 알림음 시험 — 사람 가림 런 전에 실제로 들리는지 확인한다 */
    fun testBeep() {
        if (running) return
        Beeper().apply { play(Beeper.Pattern.TWO); releaseLater() }
    }

    /** '들렸다' — 그때의 알람 음량을 기억한다. 음량이 바뀌면 다시 시험해야 한다 */
    fun confirmBeep() { manual = manual.copy(beepHeardAtVolume = preflight.read().alarmVolume) }

    /** TAG 조건 입력. 바꿀 때마다 저장해서 앱을 다시 켜도 남는다 */
    @Volatile var form: TagForm = formStore.load()
        private set

    fun updateForm(f: TagForm) { form = f; formStore.save(f) }

    /** 마지막으로 알려진 Activity 생명주기. 이벤트 행마다 같이 적힌다 */
    @Volatile private var activityState = "-"

    private val startTimes = ArrayDeque<Long>()

    val fileName: String get() = file?.name ?: "-"
    val eventFileName: String get() = eventFile?.name ?: "-"

    override fun addListener(l: SampleListener) { listeners.add(l) }
    override fun removeListener(l: SampleListener) { listeners.remove(l) }

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
        val m = mode
        val f = form
        if (m == Mode.TAG) {
            val miss = f.missing()
            if (miss.isNotEmpty()) { postNotice("먼저 채우세요: ${miss.joinToString(", ")}"); return false }
            tag = f.condCode()
        }
        val auto = preflight.read()
        val manualNow = manual
        val block = blockers(if (m == Mode.TAG) f.runType else null, auto, manualNow)
        if (block.isNotEmpty()) {
            postNotice("사람 가림 런을 시작할 수 없습니다: ${block.joinToString(", ")}"); return false
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { postNotice("BluetoothLeScanner 를 얻지 못했습니다."); return false }

        val cfg = ScanConfig.forMode(m, rawExtended)
        val stamp: String
        synchronized(lock) {
            stats.clear(); totalRows = 0; eventRows = 0
            lastRowElapsed = -1L; lastRssi = null; lastLegacy = null
            startedAtMs = System.currentTimeMillis()
            startedAtElapsed = SystemClock.elapsedRealtime()
            endedAtElapsed = 0L
            stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(startedAtMs)
            val dir = File(ctx.getExternalFilesDir(null), "ble_logs").apply { mkdirs() }
            file = File(dir, "${SampleCsv.prefix(m)}_$stamp.csv")
            data = CsvSink(file!!, SampleCsv.header(m), flushEveryRow = false)
            eventFile = File(dir, "events_$stamp.csv")
            events = CsvSink(eventFile!!, EVENT_HEADER, flushEveryRow = true)
            metaFile = File(dir, "meta_$stamp.json")
            flagTarget = null; flagValue = null
        }
        runType = if (m == Mode.TAG) f.runType else null
        runForm = if (m == Mode.TAG) f else null

        // 필터 없이 스캔한다. 모드는 기록 대상만 바꾼다
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setLegacy(cfg.legacy)
                    cfg.phy?.let { setPhy(it) }
                }
            }
            .build()

        val preNs = SystemClock.elapsedRealtimeNanos()
        try {
            scanner!!.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            postNotice("권한이 없습니다: ${e.message}")
            closeSinks()
            return false
        }
        val postNs = SystemClock.elapsedRealtimeNanos()
        startTimes.addLast(SystemClock.elapsedRealtime())
        scanSeq = 1
        running = true

        logEvent(
            "session_start", m.name,
            "file=${file?.name} maker=${Build.MANUFACTURER} model=${Build.MODEL} " +
                "sdk=${Build.VERSION.SDK_INT} scan=${cfg.describe()} " +
                "batt_opt_exempt=${phone.battOptExempt()}"
        )
        // 채널 역산(M4)의 기준 시각. startScan 호출 직전·직후
        logEvent("scan_start", scanSeq.toString(), "pre_ns=$preNs post_ns=$postNs")

        meta = RunMeta.build(
            ctx, stamp, m, tag, runForm, runType, cfg,
            file!!.name, eventFile!!.name, adapter,
        ).also {
            it.put("preflight", Preflight.toJson(auto, manualNow))
            RunMeta.write(metaFile!!, it)
        }

        registerMonitors()
        runType?.let { scheduleRun(it) }
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop(reason: String = "user") = finish(reason)

    /** 정상 정지·자동 종료·스캔 실패·ViewModel 정리가 같은 경로로 끝나게 한다. 두 번 불려도 안전하다. */
    @SuppressLint("MissingPermission")
    private fun finish(reason: String) {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(runToken)
        beeper?.releaseLater(); beeper = null
        unregisterMonitors()
        try { scanner?.stopScan(callback) } catch (_: SecurityException) {}
        synchronized(lock) { if (endedAtElapsed == 0L) endedAtElapsed = SystemClock.elapsedRealtime() }
        logEvent("run_end", reason)
        logEvent("session_stop", reason)

        meta?.let { j ->
            val r = j.getJSONObject("result")
            synchronized(lock) {
                r.put("end", reason)
                r.put("rows", totalRows)
                r.put("event_rows", eventRows)
                r.put("duration_s", (endedAtElapsed - startedAtElapsed) / 1000.0)
            }
            metaFile?.let { RunMeta.write(it, j) }
        }
        synchronized(lock) { flagTarget = eventFile }
        closeSinks()
    }

    private fun closeSinks() {
        synchronized(lock) {
            data?.close(); data = null
            events?.close(); events = null
        }
    }

    /**
     * 끝난 런에 유효/무효를 표시한다. 런이 끝난 뒤에만 폰을 만진다는 규칙 때문에 여기서 받는다.
     * events 에 run_flag 행을 덧붙이고 meta 의 result.flag 를 고친다.
     * 한 번 표시하면 바꿀 수 없다 — 표시 전에는 화면에서 결과(pkt/s·RSSI)를 가리므로,
     * 결과를 보고 표시를 바꾸는 일이 없게 하려는 것이다.
     */
    fun flagLastRun(valid: Boolean, reasons: List<InvalidReason>): Boolean {
        if (running) return false
        val target = synchronized(lock) { if (flagValue != null) null else flagTarget } ?: return false
        if (!valid && reasons.isEmpty()) return false          // 무효는 절차 사유를 골라야 한다
        val value = if (valid) "valid" else "invalid"
        val codes = if (valid) emptyList() else reasons.map { it.code }
        val reason = codes.joinToString("|")
        val (e, line) = eventLine("run_flag", value, reason, withHeadroom = false)
        try { FileWriter(target, true).use { it.write(line + "\n") } } catch (_: IOException) { return false }
        synchronized(lock) { eventRows++; flagValue = value }
        for (l in listeners) l.onEvent(e)
        meta?.let { j ->
            j.getJSONObject("result").put("flag", value).put("flag_reason", reason)
                .put("flag_reasons", JSONArray(codes))
            metaFile?.let { RunMeta.write(it, j) }
        }
        return true
    }

    /* ---------------- 런 진행 (TAG) ---------------- */

    /**
     * 시작 → countdownS 초 카운트다운(조작자가 물러난다) → 런 시계 0
     * → (사람 가림) 30 s 들어오세요 · 90 s 나가세요 → 120 s 자동 종료.
     * 가림 시각은 버튼이 아니라 알림음 시각으로 기록한다 — 폰을 만지면 그것 자체가 가림이 된다.
     */
    private fun scheduleRun(rt: RunType) {
        val b = Beeper().also { beeper = it }
        val base = SystemClock.uptimeMillis()
        val people = runForm?.block?.people ?: 0
        val pos = runForm?.pos?.code ?: ""
        // postDelayed(r, token, ms) 는 API 28+. postAtTime(r, token, t) 는 API 1
        fun at(runClockS: Int, action: () -> Unit) =
            main.postAtTime({ if (running) action() }, runToken, base + (rt.countdownS + runClockS) * 1000L)

        at(0) {
            b.play(Beeper.Pattern.ONE)
            logEvent("timer", rt.countdownS.toString(), "run_clock_s=0 countdown_end")
        }
        rt.blockInS?.let { s ->
            at(s) {
                b.play(Beeper.Pattern.TWO)
                logEvent("timer", s.toString(), "run_clock_s=$s")
                logEvent("block_in_planned", people.toString(), "pos=$pos")
            }
        }
        rt.blockOutS?.let { s ->
            at(s) {
                b.play(Beeper.Pattern.THREE)
                logEvent("timer", s.toString(), "run_clock_s=$s")
                logEvent("block_out_planned", people.toString(), "pos=$pos")
            }
        }
        at(rt.durationS) {
            b.play(Beeper.Pattern.END)
            logEvent("timer", rt.durationS.toString(), "run_clock_s=${rt.durationS}")
            finish("auto")
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

    /** 이벤트 한 건과 그 CSV 행(개행 없음). rx_elapsed_ms 는 이 런의 시작 기준 */
    private fun eventLine(event: String, value: String, detail: String, withHeadroom: Boolean): Pair<RunEvent, String> {
        val wall = System.currentTimeMillis()
        val nanos = SystemClock.elapsedRealtimeNanos()      // 데이터 CSV 의 ts_nanos 와 같은 시계
        val state = phone.row(activityState, withHeadroom)  // 시스템 서비스 호출이라 잠금 밖에서 읽는다
        return synchronized(lock) {
            val elapsed = nanos / 1_000_000 - startedAtElapsed
            RunEvent(wall, elapsed, nanos, event, value, detail) to
                "$wall,$elapsed,$nanos,$event,${csv(value)},$totalRows,$state,${csv(detail)},${csv(tag)}"
        }
    }

    /** events_<stamp>.csv 에 한 행. 측정 중이 아니면(파일이 없으면) 아무것도 하지 않는다 */
    private fun logEvent(event: String, value: String = "", detail: String = "", withHeadroom: Boolean = false) {
        if (events == null) return
        val (e, line) = eventLine(event, value, detail, withHeadroom)
        synchronized(lock) {
            val sink = events ?: return
            sink.write(line)
            eventRows++
        }
        for (l in listeners) l.onEvent(e)
    }

    /* ---------------- 수신 처리 ---------------- */

    private fun handle(result: ScanResult) {
        if (!running) return
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
        val m = mode
        val record = result.scanRecord
        // 모드별로 기록 대상이 아니면 여기서 버린다
        val beacon = if (m == Mode.BEACON) (BeaconParser.parse(record) ?: return) else null
        val svc = if (m == Mode.TAG) (TagFilter.find(record) ?: return) else null
        val name = if (m == Mode.RAW) (try { record?.deviceName ?: "" } catch (_: SecurityException) { "" }) else ""
        val ids = if (m == Mode.RAW) TagFilter.summarize(record) else Triple("", "", "")

        val s = Sample(
            rxWallMs = wall,
            rxElapsedMs = elapsed,
            // 블루투스 서비스가 결과를 만들 때 넣는 호스트 시각(elapsedRealtimeNanos) [문헌: AOSP 12L GattService,
            // Android 16 미확인]. 이벤트 CSV 의 ts_nanos 와 같은 시계라 그대로 맞댈 수 있다
            tsNanos = result.timestampNanos,
            address = result.device?.address ?: "??",
            rssi = result.rssi,
            isLegacy = result.isLegacy,
            primaryPhy = result.primaryPhy,
            secondaryPhy = result.secondaryPhy,
            advSid = result.advertisingSid,
            scanSeq = scanSeq,
            name = name,
            beacon = beacon,
            svc = svc,
            svcDataUuids = ids.first,
            svcUuids = ids.second,
            mfgIds = ids.third,
            advHex = if (m == Mode.RAW) toHex(record?.bytes) else "",
        )
        synchronized(lock) {
            val key = when (m) {
                Mode.BEACON -> beacon!!.channelId
                Mode.RAW -> KEY_RAW
                Mode.TAG -> KEY_TAG
            }
            stats.getOrPut(key) { ChStat() }.add(s.rssi, beacon?.seq ?: -1)
            totalRows++
            lastRowElapsed = elapsed; lastRssi = s.rssi; lastLegacy = s.isLegacy
            data?.write(SampleCsv.row(m, s, tag))
        }
        for (l in listeners) l.onSample(s)
    }

    /* ---------------- 화면용 스냅샷 ---------------- */

    /* ---------------- 하루치 내보내기 ---------------- */

    fun exportDays(): List<Pair<String, Int>> = Exporter.days(ctx)

    /** 그날 파일을 zip 으로 묶는다. 디스크·해시 작업이라 별도 스레드에서 하고 결과는 메인으로 */
    fun exportDay(day: String, onDone: (File?, String) -> Unit) {
        if (running) { onDone(null, "측정 중에는 내보내지 않습니다 (쓰는 중인 파일이 있다)"); return }
        Thread {
            val r = try {
                Exporter.exportDay(ctx, day, RunMeta.appVersion(ctx)) to ""
            } catch (e: Exception) { null to "내보내기 실패: ${e.message}" }
            main.post { onDone(r.first, r.second) }
        }.start()
    }

    /* ---------------- 화면용 스냅샷 ---------------- */

    private var autoCache: AutoChecks? = null
    private var autoAt = 0L

    /** 시작 전 점검(앱이 읽는 값). 화면이 0.25초마다 부르므로 1초 동안 재사용한다 */
    private fun autoChecks(): AutoChecks {
        val now = SystemClock.elapsedRealtime()
        val c = autoCache
        if (c != null && now - autoAt < 1_000L) return c
        return preflight.read().also { autoCache = it; autoAt = now }
    }

    fun snapshot(): UiState {
        val auto = if (running) null else autoChecks()   // 측정 중에는 읽지 않는다
        val man = manual
        val rtNext = if (mode == Mode.TAG) form.runType else null
        return synchronized(lock) { snapshotLocked(auto, man, rtNext) }
    }

    private fun snapshotLocked(auto: AutoChecks?, man: ManualChecks, rtNext: RunType?): UiState {
        // 정지 후에는 시간이 더 흐르지 않아야 한다. 안 그러면 pkt/s 가 계속 내려간다
        val endE = if (endedAtElapsed > 0L) endedAtElapsed else SystemClock.elapsedRealtime()
        val sec = if (startedAtElapsed == 0L) 0.0 else (endE - startedAtElapsed) / 1000.0
        val list = stats.entries.sortedBy { it.key }.map { (ch, s) ->
            StatRow(
                label = when {
                    ch == KEY_RAW -> "RAW"
                    ch == KEY_TAG -> "TAG"
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
        val rt = runType
        return UiState(
            running = running, mode = mode, elapsedSec = sec, totalRows = totalRows,
            eventRows = eventRows, fileName = fileName, rows = list,
            cond = tag,
            runType = rt,
            runClockSec = if (rt != null && startedAtElapsed > 0L) sec - rt.countdownS else null,
            pktPerSec = if (sec > 0) totalRows / sec else 0.0,
            lastRssi = lastRssi,
            lastLegacy = lastLegacy,
            // 아직 한 번도 못 받았으면 시작부터 센다
            sinceLastSec = if (startedAtElapsed == 0L) null
                else (endE - startedAtElapsed - (if (lastRowElapsed >= 0) lastRowElapsed else 0L)) / 1000.0,
            canFlag = !running && flagTarget != null,
            lastFlag = flagValue,
            // 결과를 보고 런을 빼는 일이 없게, 측정 중과 표시 전에는 pkt/s·RSSI 를 가린다
            blind = running || (flagTarget != null && flagValue == null),
            auto = auto,
            manual = man,
            blockers = if (auto == null) emptyList() else blockers(rtNext, auto, man),
        )
    }
}
