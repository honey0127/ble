package com.knu.blechprobe.collect

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import com.knu.blechprobe.model.Block
import com.knu.blechprobe.model.Mode
import com.knu.blechprobe.model.RunType
import com.knu.blechprobe.model.TagForm
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * meta_<stamp>.json — 런 1개의 정답과 조건. 시작할 때 쓰고, 끝날 때와 유효/무효 표시 때 다시 쓴다.
 * 모르는 값은 null 로 둔다(빈 문자열과 구분).
 */
internal object RunMeta {
    private val NULL = JSONObject.NULL

    fun build(
        ctx: Context, stamp: String, mode: Mode, cond: String, form: TagForm?, runType: RunType?,
        scan: ScanConfig, dataFile: String, eventFile: String, adapter: BluetoothAdapter?,
        tagNormalConfirmed: Boolean = false,
    ): JSONObject {
        val j = JSONObject()
        j.put("schema", 1)
        j.put("stamp", stamp)
        j.put("mode", mode.name)
        j.put("cond", cond)
        j.put("files", JSONObject().put("data", dataFile).put("events", eventFile))

        if (form != null) {
            j.put("truth", JSONObject()
                .put("horizontal_m", form.distanceM)
                .put("phone_height_m", form.phoneHeightM() ?: NULL)
                .put("tag_height_m", form.tagHeightM() ?: NULL)
                .put("direct_m", form.directDistanceM()?.let { Math.round(it * 1000) / 1000.0 } ?: NULL))
            j.put("condition", JSONObject()
                .put("distance_m", form.distanceM)
                .put("block", form.block.code)
                .put("block_people", form.block.people)
                .put("block_position", if (form.block == Block.NONE) NULL else form.pos.code)
                .put("wall_material", if (form.block == Block.WALL) form.wallMaterial else NULL)
                .put("equipment", if (form.block == Block.EQUIP) form.equipment else NULL)
                .put("day", form.day)
                .put("placement", form.placement)
                .put("operator_position", form.operatorPos))
            j.put("orientation", JSONObject().put("phone", form.phoneOrient).put("tag", form.tagOrient))
            j.put("tag_state", JSONObject()
                .put("registered", form.registered)
                .put("smartthings_connected", form.smartThingsConnected)
                // 고정값이 아니라 시작 전 점검에서 사람이 확인한 값. 확인 안 했으면 "unconfirmed"
                .put("battery_mode", if (tagNormalConfirmed) "normal" else "unconfirmed"))
            j.put("photo", form.photo)
        } else {
            j.put("truth", NULL)
            j.put("condition", NULL)
        }

        j.put("run", if (runType == null) JSONObject().put("type", "MANUAL") else JSONObject()
            .put("type", runType.name)
            .put("countdown_s", runType.countdownS)
            .put("duration_s", runType.durationS)
            .put("block_in_s", runType.blockInS ?: NULL)
            .put("block_out_s", runType.blockOutS ?: NULL))

        j.put("scan", JSONObject()
            .put("scan_mode", "LOW_LATENCY")
            .put("callback_type", "ALL_MATCHES")
            .put("report_delay_ms", 0)
            .put("legacy", scan.legacy)
            .put("phy", scan.phyName)
            .put("filter", "none")
            .put("raw_extended", mode == Mode.RAW && !scan.legacy)
            .put("le_extended_adv_supported", cap { adapter?.isLeExtendedAdvertisingSupported })
            .put("le_coded_phy_supported", cap { adapter?.isLeCodedPhySupported }))

        j.put("device", JSONObject()
            .put("maker", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("build", Build.DISPLAY)
            .put("fingerprint", Build.FINGERPRINT))
        j.put("app", appInfo(ctx))

        j.put("result", JSONObject()
            .put("end", NULL).put("rows", NULL).put("event_rows", NULL).put("duration_s", NULL)
            .put("flag", NULL).put("flag_reason", NULL).put("flag_memo", NULL))
        return j
    }

    fun write(file: File, j: JSONObject) {
        try { file.writeText(j.toString(2)) } catch (_: IOException) {}
    }

    private fun cap(f: () -> Boolean?): Any = try { f() ?: NULL } catch (_: SecurityException) { NULL }

    /** 내보내기 files.txt 에 적는 앱 버전 */
    fun appVersion(ctx: Context): String {
        val a = appInfo(ctx)
        return "${a.optString("version_name", "?")}(${a.optLong("version_code", -1)})"
    }

    @Suppress("DEPRECATION")
    private fun appInfo(ctx: Context): JSONObject {
        val o = JSONObject()
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            o.put("version_name", info.versionName ?: NULL)
            o.put("version_code", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong())
        } catch (_: Exception) {}
        return o
    }
}
