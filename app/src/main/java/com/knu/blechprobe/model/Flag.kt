package com.knu.blechprobe.model

/**
 * 무효 사유 — 결과가 아니라 **절차** 사유만 고를 수 있다 (APP_DESIGN 6.2a의 3, DESIGN 0절).
 * "잘 안 나온 런을 뺀 것 아닌가"라는 질문에 답하려고, 무효는 사유 하나를 골라야 하고
 * 표시하기 전에는 화면에서 pkt/s·RSSI 를 가린다. 한 번 표시하면 바꿀 수 없다.
 */
enum class FlagReason(val code: String, val label: String) {
    MOVE_TIMING("move_timing", "사람 이동이 알림음과 어긋남"),
    INTRUDER("intruder", "계획 밖 사람·물체 통과"),
    TOUCH("touch", "폰·태그 접촉·이동"),
    BEEP("beep", "알림음 문제"),
    APP_ERROR("app_error", "앱 오류"),
    OTHER("other", "기타 (메모 필수)");

    val needsMemo: Boolean get() = this == OTHER
}

/** 사람이 확인하는 시작 전 점검. 배치가 바뀌면(배치 번호·날) 처음부터 다시 확인한다 */
data class ManualChecks(
    val smartThingsClosed: Boolean = false,
    val wearablesOff: Boolean = false,
    val tagNormalMode: Boolean = false,
    /** 알림음 시험을 '들렸다'고 확인한 순간의 알람 음량. 음량이 바뀌면 다시 확인해야 한다 */
    val beepHeardAtVolume: Int? = null,
)

/** 앱이 읽는 시작 전 점검 */
data class AutoChecks(
    val powerSave: Boolean,
    val battOptExempt: Boolean,
    val plugged: String,
    val alarmVolume: Int,
    val alarmMax: Int,
    /** NotificationManager.INTERRUPTION_FILTER_* (1 꺼짐, 2 중요, 3 완전 무음, 4 알람만, 0 모름) */
    val dndFilter: Int,
    val wifiOn: Boolean?,
) {
    val dndLabel: String
        get() = when (dndFilter) {
            1 -> "꺼짐"; 2 -> "중요 알림만"; 3 -> "완전 무음"; 4 -> "알람만"; else -> "모름"
        }

    /** 완전 무음 = 전화 외 모든 소리를 끈다 [공식 INTERRUPTION_FILTER_NONE] → 알림음이 안 난다 */
    val totalSilence: Boolean get() = dndFilter == 3
}

/** 사람 가림 런을 막는 이유. 알림음이 곧 가림 시각의 정답이라 소리가 안 나면 그 런은 정답이 없다 */
fun blockers(rt: RunType?, auto: AutoChecks, manual: ManualChecks): List<String> {
    if (rt?.blockInS == null) return emptyList()
    return buildList {
        if (auto.alarmVolume <= 0) add("알람 음량 0")
        if (auto.totalSilence) add("방해 금지가 완전 무음")
        if (manual.beepHeardAtVolume == null) add("알림음 시험 미확인")
        else if (manual.beepHeardAtVolume != auto.alarmVolume) add("알람 음량이 바뀜 — 알림음 다시 시험")
    }
}
