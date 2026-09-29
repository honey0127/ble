package com.knu.blechprobe.collect

import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException

private const val FLUSH_ROWS = 200
private const val FLUSH_INTERVAL_MS = 2_000L   // 수신이 드물어도 2초마다 디스크에 내린다

/**
 * CSV 파일 하나. 헤더를 쓰고, 정해진 규칙으로 디스크에 내린다.
 * @param flushEveryRow 드물게 쓰이는 파일(이벤트)은 매 행 내린다. 앱이 죽어도 직전 행까지 남는다
 * 스레드 안전하지 않다. 호출하는 쪽(Collector)이 잠금을 잡는다.
 */
internal class CsvSink(val file: File, header: String, private val flushEveryRow: Boolean) {
    private var writer: BufferedWriter? = BufferedWriter(FileWriter(file)).apply { write(header + "\n") }
    private var pending = 0
    private var lastFlushElapsed = SystemClock.elapsedRealtime()

    fun write(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            if (flushEveryRow) { w.flush(); return }
            pending++
            val now = SystemClock.elapsedRealtime()
            // 행 수만 보면 수신이 드문 구간에서 오래 안 써진다. 시간 조건을 같이 둔다
            if (pending >= FLUSH_ROWS || now - lastFlushElapsed >= FLUSH_INTERVAL_MS) {
                pending = 0
                lastFlushElapsed = now
                w.flush()
            }
        } catch (_: IOException) {}
    }

    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
    }
}

/** 쉼표·따옴표가 있으면 따옴표로 감싼다. 개행은 행이 쪼개지지 않게 공백으로 바꾼다 */
internal fun csv(s: String): String {
    val t = s.replace('\n', ' ').replace('\r', ' ')
    return if (t.contains(',') || t.contains('"')) "\"" + t.replace("\"", "\"\"") + "\"" else t
}
