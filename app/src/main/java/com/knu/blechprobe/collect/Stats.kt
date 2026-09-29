package com.knu.blechprobe.collect

import kotlin.math.sqrt

/* 채널별 통계 */
internal class ChStat {
    var rows = 0L
    var sum = 0.0
    var sumSq = 0.0
    var min = Int.MAX_VALUE
    var max = Int.MIN_VALUE
    var lastSeq = -1L
    var minSeq = -1L
    var maxSeq = -1L
    var backward = 0L                      // seq 역행 횟수 (T2 진단)
    val uniqSeq = HashSet<Long>()

    fun add(rssi: Int, seq: Long) {
        rows++
        sum += rssi
        sumSq += rssi.toDouble() * rssi
        if (rssi < min) min = rssi
        if (rssi > max) max = rssi
        if (seq >= 0) {
            if (lastSeq >= 0 && seq < lastSeq) backward++
            lastSeq = seq
            if (minSeq < 0 || seq < minSeq) minSeq = seq
            if (maxSeq < 0 || seq > maxSeq) maxSeq = seq
            uniqSeq.add(seq)
        }
    }

    val mean get() = if (rows > 0) sum / rows else 0.0
    val sd: Double
        get() {
            if (rows < 2) return 0.0
            val v = sumSq / rows - mean * mean
            return if (v > 0) sqrt(v) else 0.0
        }
    val span get() = if (minSeq < 0) 0L else maxSeq - minSeq + 1
    val seqObs get() = if (span > 0) 100.0 * uniqSeq.size / span else 0.0
    val dup get() = if (uniqSeq.isNotEmpty()) rows.toDouble() / uniqSeq.size else 0.0
}
