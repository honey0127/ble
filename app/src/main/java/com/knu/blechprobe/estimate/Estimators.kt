package com.knu.blechprobe.estimate

import com.knu.blechprobe.model.Sample

/*
 * 추정기 자리 — 인터페이스만 있다. 구현은 Phase 0' GO(10/16) 이후.
 *
 * 규칙 (APP_DESIGN 3절)
 *  - 방법은 파이썬에서 평가하고, 최종으로 고른 하나만 여기로 옮긴다
 *  - 파라미터(A·n 등)는 파이썬이 model.json 으로 내보내고 앱은 읽기만 한다
 *  - 같은 런 3개를 앱에서 재생한 추정값이 파이썬 결과와 같은지 확인한다 (일치 검사)
 */

/** F1 거리 추정. 최근 샘플로 거리(m). 판단할 샘플이 모자라면 null */
interface DistanceEstimator {
    fun estimateM(recent: List<Sample>): Double?
}

/** 경로 A 채널 역산. 받은 시각이 스캔 창 시간표의 어느 채널 구간에 드는가. 모르면 null */
interface ChannelClassifier {
    fun channelOf(s: Sample, scanStartNanos: Long): Int?
}

/** F2 가림 감지. 모르면 null */
interface BlockageDetector {
    fun isBlocked(recent: List<Sample>): Boolean?
}

/** F3 보정. 가림으로 떨어진 신호를 감안한 거리(m) */
interface Corrector {
    fun correctM(rawM: Double, blocked: Boolean): Double
}
