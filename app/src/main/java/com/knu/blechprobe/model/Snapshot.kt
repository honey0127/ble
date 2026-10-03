package com.knu.blechprobe.model

/* 화면에 뿌릴 한 줄 (불변 스냅샷) */
data class StatRow(
    val label: String, val rows: Long, val mean: Double, val sd: Double,
    val pktPerSec: Double, val seqObs: Double, val dup: Double,
    val lastSeq: Long, val backward: Long, val min: Int, val max: Int,
)

/* Collector.snapshot() 이 0.5초마다 만들어 화면에 넘기는 상태 */
data class UiState(
    val running: Boolean = false,
    val mode: Mode = Mode.RAW,
    val elapsedSec: Double = 0.0,
    val totalRows: Long = 0,
    val eventRows: Long = 0,
    val fileName: String = "-",
    val rows: List<StatRow> = emptyList(),
    val notice: String = "",
    /** TAG 는 조건 코드, 그 밖에는 자유 입력 tag */
    val cond: String = "",
    /** TAG 런일 때만. 런 시계 = 경과초 − 카운트다운 (카운트다운 중에는 음수) */
    val runType: RunType? = null,
    val runClockSec: Double? = null,
    /* 점검 표시 — 기록 대상 패킷 기준 */
    val pktPerSec: Double = 0.0,
    val lastRssi: Int? = null,
    val lastLegacy: Boolean? = null,
    val sinceLastSec: Double? = null,
    /* 끝난 런의 유효/무효 표시 */
    val canFlag: Boolean = false,
    val lastFlag: String? = null,
    /** 측정 중이거나 끝난 런을 아직 표시하지 않았으면 결과(pkt/s·RSSI)를 가린다 */
    val blind: Boolean = false,
    /* 시작 전 점검 — 측정 중에는 auto 가 null */
    val auto: AutoChecks? = null,
    val manual: ManualChecks = ManualChecks(),
    /** 지금 고른 런을 막는 이유 (사람 가림 런만) */
    val blockers: List<String> = emptyList(),
)
