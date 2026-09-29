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
)
