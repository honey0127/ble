package com.knu.blechprobe.parse

import android.bluetooth.le.ScanRecord

/*
 * 우리 ESP32 비콘 페이로드 (Manufacturer Specific Data, company id 0xFFFF)
 *   [0] proto_ver  [1] beacon_id  [2] channel_id  [3..6] seq(LE32)
 *   [7..10] tx_uptime_ms(LE32)  [11] tx_power_dbm(int8)
 */
const val COMPANY_ID = 0xFFFF      // Manufacturer Specific Data company id
const val PROTO_VER = 1            // 페이로드 스키마 버전
const val PAYLOAD_LEN = 12         // proto_ver..tx_power_dbm
const val CH_ALL_LABEL = 0         // ALL_CONTROL 모드 표식 (실제 RF 채널 아님)

data class BeaconPayload(
    val beaconId: Int, val channelId: Int, val seq: Long, val uptimeMs: Long, val txPowerDbm: Int,
)

object BeaconParser {
    /** 우리 비콘이 아니면 null */
    fun parse(record: ScanRecord?): BeaconPayload? {
        val mfg = record?.getManufacturerSpecificData(COMPANY_ID) ?: return null
        if (mfg.size < PAYLOAD_LEN) return null
        if ((mfg[0].toInt() and 0xFF) != PROTO_VER) return null
        return BeaconPayload(
            beaconId = mfg[1].toInt() and 0xFF,
            channelId = mfg[2].toInt() and 0xFF,
            seq = le32(mfg, 3),
            uptimeMs = le32(mfg, 7),
            txPowerDbm = mfg[11].toInt(),                 // int8
        )
    }

    private fun le32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF)) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)
}
