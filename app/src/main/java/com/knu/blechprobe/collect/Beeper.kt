package com.knu.blechprobe.collect

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 런 알림음. 측정 중에는 아무도 폰을 만지지 않으므로 소리로 전환 시점을 알린다.
 * 알람 음량(STREAM_ALARM)을 쓴다 — 무음 모드에서도 대개 울린다. 알람 볼륨을 올려 둘 것.
 *
 *   ONE   카운트다운 끝 (런 시계 0 s)      ▬
 *   TWO   들어오세요 (30 s)                ▬ ▬
 *   THREE 나가세요 (90 s)                  ▬ ▬ ▬
 *   END   끝 (120 s)                       ▬▬▬▬▬
 */
internal class Beeper {
    enum class Pattern(val onMs: List<Int>) {
        ONE(listOf(400)), TWO(listOf(250, 250)), THREE(listOf(250, 250, 250)), END(listOf(1200)),
    }

    private val main = Handler(Looper.getMainLooper())
    private val token = Any()
    private var tone: ToneGenerator? =
        try { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) } catch (_: RuntimeException) { null }

    /** 첫 소리는 즉시 난다. 이벤트 기록 시각 = 첫 소리 시작 시각 */
    fun play(p: Pattern) {
        // postDelayed(r, token, ms) 는 API 28+ 라 minSdk 26 에서 쓸 수 없다. postAtTime 은 API 1
        var at = SystemClock.uptimeMillis()
        for (ms in p.onMs) {
            main.postAtTime({ tone?.startTone(ToneGenerator.TONE_DTMF_0, ms) }, token, at)
            at += ms + 150L
        }
    }

    /** 마지막 소리가 끝난 뒤 풀어 준다 */
    fun releaseLater() {
        main.postDelayed({
            main.removeCallbacksAndMessages(token)
            tone?.release(); tone = null
        }, 1_500L)
    }
}
