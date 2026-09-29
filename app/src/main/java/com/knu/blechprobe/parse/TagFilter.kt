package com.knu.blechprobe.parse

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import com.knu.blechprobe.model.ServiceData

/**
 * SmartTag2 광고 골라내기 — 서비스 데이터 UUID 만 본다. 내용 해석(TagParser)은 B1 확인 뒤.
 * [미검증] SmartTag2 가 FD5A/FD59 서비스 데이터로 광고한다는 것은 B1 에서 확인할 가정이다.
 * 다르면 이 목록만 고치면 된다. 원본(svc_data_hex)은 그대로 남는다.
 */
val TAG_SERVICE_UUIDS: List<ParcelUuid> = listOf(0xFD5A, 0xFD59).map { uuid16(it) }

private fun uuid16(v: Int): ParcelUuid =
    ParcelUuid.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", v))

object TagFilter {
    /** 목록 순서대로 처음 찾은 서비스 데이터. 태그 광고가 아니면 null */
    fun find(record: ScanRecord?): ServiceData? {
        if (record == null) return null
        for (u in TAG_SERVICE_UUIDS) {
            val bytes = record.getServiceData(u) ?: continue
            return ServiceData(shortUuid(u), bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) })
        }
        return null
    }

    /** 0000fd5a-0000-1000-8000-00805f9b34fb → FD5A */
    private fun shortUuid(u: ParcelUuid): String {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb", ignoreCase = true) && s.startsWith("0000"))
            s.substring(4, 8).uppercase() else s
    }
}
