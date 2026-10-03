package com.knu.blechprobe.collect

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 하루치 측정 내보내기 — ble_logs 의 <yyyyMMdd>_ 파일을 zip 하나로 묶는다.
 * 앱을 지우면 앱 전용 저장소의 CSV 도 같이 지워지므로 폰 밖으로 빼 둔다.
 * zip 안 files.txt 에 파일명·크기·sha256 을 적는다 — 개발일/평가일을 나눌 때
 * "그날 그 파일"이라는 증거가 된다(나중에 파일이 바뀌면 sha256 이 달라진다).
 */
internal object Exporter {
    private val STAMP = Regex("""_(\d{8})_\d{6}\.(csv|json)$""")

    fun logDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "ble_logs")
    private fun exportDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }

    /** 측정이 있는 날짜(yyyyMMdd)와 파일 수, 최근 날짜부터 */
    fun days(ctx: Context): List<Pair<String, Int>> =
        (logDir(ctx).listFiles() ?: emptyArray())
            .mapNotNull { STAMP.find(it.name)?.groupValues?.get(1) }
            .groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.first }

    /** 그날 파일을 zip 으로. 메인 스레드에서 부르지 말 것 (sha256·압축) */
    fun exportDay(ctx: Context, day: String, appVersion: String): File {
        val files = (logDir(ctx).listFiles() ?: emptyArray())
            .filter { STAMP.find(it.name)?.groupValues?.get(1) == day }
            .sortedBy { it.name }
        require(files.isNotEmpty()) { "$day 측정 파일이 없습니다" }

        val now = Date()
        val out = File(exportDir(ctx),
            "ble_${day}_export_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)}.zip")
        val manifest = StringBuilder()
            .append("# BLE Channel Probe 측정 내보내기\n")
            .append("# day=$day exported_at=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(now)}\n")
            .append("# device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} app=$appVersion\n")
            .append("# name\tbytes\tsha256\n")

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val buf = ByteArray(64 * 1024)
            for (f in files) {
                val md = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(f.name).apply { time = f.lastModified() })
                FileInputStream(f).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        zip.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
                manifest.append(f.name).append('\t').append(f.length()).append('\t')
                    .append(md.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }).append('\n')
            }
            zip.putNextEntry(ZipEntry("files.txt"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out
    }
}
