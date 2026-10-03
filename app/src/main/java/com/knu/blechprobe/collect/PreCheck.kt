package com.knu.blechprobe.collect

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import com.knu.blechprobe.model.AutoChecks
import com.knu.blechprobe.model.ManualChecks
import org.json.JSONObject

/** 시작 전 점검 중 앱이 읽을 수 있는 것. 권한 없이 읽힌다 (Wi-Fi 는 ACCESS_WIFI_STATE, 일반 권한) */
internal class PreCheck(private val ctx: Context) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun read(): AutoChecks {
        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = when (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1) {
            -1 -> "모름"; 0 -> "안 됨"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "기타"
        }
        return AutoChecks(
            powerSave = pm.isPowerSaveMode,
            battOptExempt = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            plugged = plugged,
            alarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
            alarmMax = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            dndFilter = nm.currentInterruptionFilter,
            wifiOn = try { wifi?.isWifiEnabled } catch (_: SecurityException) { null },
        )
    }

    companion object {
        /** meta_<stamp>.json 의 precheck — 그 런을 시작할 때의 점검 상태 */
        fun toJson(a: AutoChecks, m: ManualChecks): JSONObject = JSONObject()
            .put("power_save", a.powerSave)
            .put("batt_opt_exempt", a.battOptExempt)
            .put("plugged", a.plugged)
            .put("alarm_volume", a.alarmVolume)
            .put("alarm_max", a.alarmMax)
            .put("dnd", a.dndLabel)
            .put("wifi_on", a.wifiOn ?: JSONObject.NULL)
            .put("smartthings_closed", m.smartThingsClosed)
            .put("wearables_off", m.wearablesOff)
            .put("tag_normal_mode", m.tagNormalMode)
            .put("beep_heard", m.beepHeardAtVolume != null && m.beepHeardAtVolume == a.alarmVolume)
    }
}
