package com.knu.blechprobe.source

import com.knu.blechprobe.model.RunEvent
import com.knu.blechprobe.model.Sample

/*
 * 하나의 데이터 흐름 (APP_DESIGN 3절)
 *
 *   [실시간] Collector ──┐
 *                        ├─▶ Sample · RunEvent ─▶ (채널 분류기) ─▶ (거리·가림 추정기) ─▶ 화면
 *   [재생]  CsvReplaySource ┘                    GO(10/16) 이후 구현
 *
 * 실시간 소스는 Collector 다(실시간일 때만 CSV 로 저장한다).
 * CsvReplaySource(저장된 런 재생)는 GO 이후에 만든다. 발표장에서 실시간이 흔들려도
 * 같은 화면을 재생으로 보여주기 위한 것이라, 화면은 이 인터페이스만 보고 만든다.
 */
interface SampleListener {
    fun onSample(s: Sample) {}
    fun onEvent(e: RunEvent) {}
}

interface SampleSource {
    fun addListener(l: SampleListener)
    fun removeListener(l: SampleListener)
}
