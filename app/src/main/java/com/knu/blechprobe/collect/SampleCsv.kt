package com.knu.blechprobe.collect

import com.knu.blechprobe.model.Mode
import com.knu.blechprobe.model.Sample

/*
 * 모드별 데이터 CSV. 새 컬럼은 맨 끝에 붙인다 — 분석 스크립트는 이름으로 읽는다.
 * RAW·BEACON 형식은 9/27 과 같다.
 */
internal object SampleCsv {
    fun prefix(mode: Mode) = when (mode) {
        Mode.RAW -> "raw"
        Mode.BEACON -> "beacon"
        Mode.TAG -> "tag"
    }

    fun header(mode: Mode) = when (mode) {
        // 9/30: svc_data_uuids,svc_uuids,mfg_ids 를 끝에 붙임 (B1 찾기 — scripts/b1_find.py)
        Mode.RAW -> "rx_wall_ms,rx_elapsed_ms,address,rssi,name,tag,ts_nanos,svc_data_uuids,svc_uuids,mfg_ids"
        Mode.BEACON ->
            "rx_wall_ms,rx_elapsed_ms,beacon_id,channel_id,seq,rssi,tx_uptime_ms,tx_power_dbm,tag,ts_nanos,address"
        Mode.TAG ->
            "rx_wall_ms,rx_elapsed_ms,ts_nanos,address,rssi,is_legacy,primary_phy,secondary_phy,adv_sid," +
                "svc_uuid,svc_data_hex,scan_seq,cond"
    }

    /** @param tag RAW·BEACON 은 자유 입력 tag, TAG 는 조건 코드 */
    fun row(mode: Mode, s: Sample, tag: String): String = when (mode) {
        Mode.RAW -> "${s.rxWallMs},${s.rxElapsedMs},${s.address},${s.rssi},${csv(s.name)},${csv(tag)},${s.tsNanos}," +
            "${s.svcDataUuids},${s.svcUuids},${s.mfgIds}"
        Mode.BEACON -> {
            val b = s.beacon!!
            "${s.rxWallMs},${s.rxElapsedMs},${b.beaconId},${b.channelId},${b.seq},${s.rssi},${b.uptimeMs}," +
                "${b.txPowerDbm},${csv(tag)},${s.tsNanos},${s.address}"
        }
        Mode.TAG ->
            "${s.rxWallMs},${s.rxElapsedMs},${s.tsNanos},${s.address},${s.rssi},${bit(s.isLegacy)}," +
                "${s.primaryPhy},${s.secondaryPhy},${s.advSid},${s.svc?.uuid ?: ""},${s.svc?.hex ?: ""}," +
                "${s.scanSeq},${csv(tag)}"
    }
}
