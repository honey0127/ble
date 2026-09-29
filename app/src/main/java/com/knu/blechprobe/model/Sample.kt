package com.knu.blechprobe.model

import com.knu.blechprobe.parse.BeaconPayload

/** 서비스 데이터 원본. 내용 해석(TagParser)은 B1 확인 뒤 */
data class ServiceData(val uuid: String, val hex: String)

/**
 * 받은 패킷 1개. 실시간 수집과 (GO 이후) 기록 재생이 같은 모양을 쓴다.
 * @param tsNanos ScanResult.getTimestampNanos(). 공식 문서: "부팅 후, 스캔 레코드가 관측된 시각".
 *                컨트롤러 수신 시각인지 호스트 처리 시각인지는 문서에 없다 [미검증]
 * @param scanSeq 런 안에서 몇 번째 스캔 세션인가 (1부터). 재시작 뒤 채널 위상 기준
 */
data class Sample(
    val rxWallMs: Long,
    val rxElapsedMs: Long,
    val tsNanos: Long,
    val address: String,
    val rssi: Int,
    val isLegacy: Boolean,
    val primaryPhy: Int,
    val secondaryPhy: Int,
    val advSid: Int,
    val scanSeq: Int,
    val name: String = "",
    val beacon: BeaconPayload? = null,
    val svc: ServiceData? = null,
    /** RAW 만: 서비스 데이터 UUID · 광고 서비스 UUID · 제조사 ID ('|' 로 이음). B1 찾기용 */
    val svcDataUuids: String = "",
    val svcUuids: String = "",
    val mfgIds: String = "",
)

/** events_<stamp>.csv 한 행의 핵심. 상태 스냅샷 열은 CSV 에만 있다 */
data class RunEvent(
    val rxWallMs: Long,
    val rxElapsedMs: Long,
    val tsNanos: Long,
    val event: String,
    val value: String,
    val detail: String,
)
