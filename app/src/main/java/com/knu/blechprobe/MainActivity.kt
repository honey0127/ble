package com.knu.blechprobe

/* ============================================================================
 * MainActivity.kt — Phase 0 BLE 수집 앱의 진입점
 *
 * 목적: BLE 광고를 받아 RSSI·시각·원본을 CSV 로 남긴다. 측위·보정은 들어 있지 않다.
 *
 * 패키지 (APP_DESIGN 3절)
 *   collect  Collector(스캔·기록), PhoneState(폰 상태), CsvSink(파일 쓰기), ViewModel
 *   parse    BeaconParser(우리 ESP32 비콘)
 *   model    Mode, 화면 스냅샷
 *   ui       RecordScreen(① 측정 화면)
 *
 * 측정기(Collector)는 ViewModel 에 둔다. 다크모드·글꼴 크기 변경으로 Activity 가
 * 다시 만들어져도 측정이 끊기지 않는다. Activity 가 정말 끝날 때만 정지한다.
 * ========================================================================== */

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.knu.blechprobe.collect.Collector
import com.knu.blechprobe.collect.CollectorViewModel
import com.knu.blechprobe.collect.bit
import com.knu.blechprobe.ui.RecordScreen
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var collector: Collector
    private var permGranted by mutableStateOf(false)

    private val perms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,   // API 30 이하는 필수 + 위치 서비스 ON
            )

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            permGranted = res.values.all { it }
            if (!permGranted) {
                Toast.makeText(this, "권한이 거부되면 스캔할 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }

    private fun hasPerms() = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 화면이 꺼지면 필터 없는 스캔이 멈출 수 있다. Phase 0 은 화면을 켠 채 측정한다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 설정 변경으로 다시 만들어진 Activity 도 같은 측정기를 받는다
        collector = ViewModelProvider(this)[CollectorViewModel::class.java].collector
        permGranted = hasPerms()

        // 무엇이 재생성을 일으켰는지 가를 수 있게 다크모드·글꼴 배율을 같이 남긴다
        val cfg = resources.configuration
        val night = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        collector.onActivity(
            "onCreate",
            "recreated=${bit(savedInstanceState != null)} night=${bit(night)} " +
                "fontScale=${String.format(Locale.US, "%.2f", cfg.fontScale)}"
        )

        setContent {
            MaterialTheme {
                RecordScreen(
                    collector, permGranted,
                    onRequestPerms = { requestPerms.launch(perms) },
                    onShare = ::shareZip,
                )
            }
        }
    }

    /** 내보낸 zip 을 공유 시트로 (드라이브·메일·PC 전송 등). 받는 앱에 읽기 권한만 잠깐 준다 */
    private fun shareZip(zip: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            clipData = ClipData.newRawUri(zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "측정 내보내기 — ${zip.name}"))
    }

    override fun onStart() {
        super.onStart()
        collector.onActivity("onStart")
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 권한을 켜고 돌아왔을 때 버튼이 계속 막혀 있지 않게 한다
        permGranted = hasPerms()
        collector.onActivity("onResume")
    }

    override fun onPause() {
        collector.onActivity("onPause")
        super.onPause()
    }

    override fun onStop() {
        collector.onActivity("onStop")
        super.onStop()
    }

    override fun onDestroy() {
        // 여기서 정지하지 않는다. 정지는 CollectorViewModel.onCleared 가 맡는다.
        // 설정 변경(isChangingConfigurations)으로 다시 만들어질 때는 측정이 그대로 이어진다.
        collector.onActivity(
            "onDestroy",
            "finishing=${bit(isFinishing)} changingConfig=${bit(isChangingConfigurations)}"
        )
        super.onDestroy()
    }
}
