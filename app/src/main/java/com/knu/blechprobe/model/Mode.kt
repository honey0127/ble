package com.knu.blechprobe.model

/**
 * RAW    주변 아무 BLE 기기. A3(10분 연속 수집) 확인용
 * BEACON 우리 ESP32 비콘 (company id 0xFFFF). M4(채널 역산) 확인용
 * TAG    SmartTag2 (서비스 데이터 FD5A/FD59). 본 측정
 */
enum class Mode { RAW, BEACON, TAG }
