package com.knu.blechprobe.collect

import android.bluetooth.BluetoothDevice
import com.knu.blechprobe.model.Mode

/**
 * 모드별 스캔 설정. 공통: LOW_LATENCY, ALL_MATCHES, report delay 0, 필터 없음.
 *
 *  RAW     기본 legacy=true — 9/22 A3 측정과 같은 설정이어야 재측정 결과를 비교할 수 있다.
 *          '확장 포함'을 켜면 TAG 와 같은 설정 — 태그 도착 날 B1(다른 UUID?)과 B9(확장 광고?)를
 *          가르려면 확장 광고도 받아 봐야 한다. 이 런은 9/22 와 비교하지 않는다
 *  TAG     legacy=false, PHY 1M — setLegacy 기본값 true 는 레거시 광고만 돌려주므로
 *          SmartTag2 가 확장 광고를 쓰면 아예 안 보인다(B9). setPhy 는 legacy=false 일 때만
 *          쓰이고 기본값은 문서에 없다. ALL_SUPPORTED 면 Coded PHY 도 스캔해 스캔 창 시간표가
 *          바뀔 수 있어 [추론] 1M 으로 고정한다. legacy=false 도 레거시 광고는 그대로 받는다
 *  BEACON  TAG 와 같게 — M4(채널 역산) 결과를 TAG 측정에 옮기려면 스캔 설정이 같아야 한다
 */
internal data class ScanConfig(val legacy: Boolean, val phy: Int?) {
    val phyName: String
        get() = when (phy) {
            null -> "default"
            BluetoothDevice.PHY_LE_1M -> "LE_1M"
            BluetoothDevice.PHY_LE_CODED -> "LE_CODED"
            else -> "other$phy"
        }

    /** session_start detail 에 들어가는 요약. RAW 는 9/27 과 같은 문자열 */
    fun describe() = "LOW_LATENCY/" + (if (legacy) "legacy" else "ext+legacy/phy=$phyName") + "/no_filter"

    companion object {
        private val TAG_LIKE = ScanConfig(legacy = false, phy = BluetoothDevice.PHY_LE_1M)

        fun forMode(m: Mode, rawExtended: Boolean = false) =
            if (m == Mode.RAW && !rawExtended) ScanConfig(legacy = true, phy = null) else TAG_LIKE
    }
}
