package com.knu.blechprobe.collect

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.Locale

/* 이벤트 CSV 헤더. 가운데 screen_on..bt 는 PhoneState.row() 가 같은 순서로 채운다 */
internal const val EVENT_HEADER =
    "rx_wall_ms,rx_elapsed_ms,ts_nanos,event,value,rows," +
        "screen_on,activity,importance,power_save,doze,plugged,batt_pct,batt_temp_c," +
        "thermal,headroom,bt," +
        "detail,tag"

internal fun bit(v: Boolean) = if (v) "1" else "0"

/**
 * 이벤트 CSV 의 모든 행에 붙는 "그 순간의 폰 상태".
 * 방송(broadcast)을 놓치거나 늦게 받아도 다음 tick 이 현재 값을 직접 읽어 다시 적는다.
 * (Android 14+ 는 cached 상태 앱에 SCREEN_ON 같은 방송을 미뤘다가 준다)
 */
internal class PhoneState(private val ctx: Context) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * EVENT_HEADER 의 screen_on..bt 순서로 채운 CSV 조각.
     * headroom 은 초당 1회보다 자주 부르면 NaN 이 나올 수 있어 tick 에서만 읽는다.
     */
    fun row(activity: String, withHeadroom: Boolean): String {
        // sticky 방송이라 수신기 없이 현재 값만 읽힌다 (공식 문서의 배터리 상태 읽기 방식)
        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val plugged = batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        // 시스템이 보는 이 프로세스의 중요도. 100=foreground, 125=foreground service, 400=cached
        val proc = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.currentThermalStatus.toString()
        } else ""
        val headroom = if (withHeadroom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val h = pm.getThermalHeadroom(0)
            if (h.isNaN()) "" else String.format(Locale.US, "%.3f", h)
        } else ""

        return listOf(
            bit(pm.isInteractive),
            activity,
            proc.importance.toString(),
            powerSave(),
            doze(),
            plugName(plugged),
            if (level >= 0 && scale > 0) (100 * level / scale).toString() else "",
            if (temp != Int.MIN_VALUE) String.format(Locale.US, "%.1f", temp / 10.0) else "",
            thermal,
            headroom,
            btName(adapter?.state),
        ).joinToString(",")
    }

    fun powerSave(): String = bit(pm.isPowerSaveMode)

    fun doze(): String = when {
        pm.isDeviceIdleMode -> "deep"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && pm.isDeviceLightIdleMode -> "light"
        else -> "off"
    }

    /** 앱별 배터리 '제한 없음' 이면 1 (README 6장 3번이 실제로 적용됐는지) */
    fun battOptExempt(): String = bit(pm.isIgnoringBatteryOptimizations(ctx.packageName))

    private fun plugName(p: Int) = when (p) {
        -1 -> ""
        0 -> "none"
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "other$p"                   // 8 = dock (API 33+)
    }
}

internal fun btName(state: Int?) = when (state) {
    BluetoothAdapter.STATE_ON -> "on"
    BluetoothAdapter.STATE_OFF -> "off"
    BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
    BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
    null -> "none"
    else -> "unknown$state"
}
