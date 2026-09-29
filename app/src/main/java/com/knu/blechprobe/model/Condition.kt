package com.knu.blechprobe.model

import java.util.Locale
import kotlin.math.sqrt

/* TAG 모드 런 1개의 조건. 자유 입력 대신 선택형으로 받아 조건 코드를 만든다 (오타 → T5 매칭 깨짐 방지) */

enum class Block(val code: String, val label: String, val people: Int) {
    NONE("none", "가림 없음", 0),
    P1("p1", "1명", 1), P2("p2", "2명", 2), P3("p3", "3명", 3),
    WALL("wall", "벽", 0), EQUIP("equip", "장비", 0),
}

enum class BlockPos(val code: String, val label: String) {
    TAG("tag", "태그 앞"), MID("mid", "중간"), PHONE("phone", "폰 앞"),
}

/**
 * 런 종류. 시각은 '런 시계' 기준 초 — 시작 버튼 뒤 countdownS 초가 지난 순간이 0 이다.
 * 카운트다운 구간도 기록하지만 채점에서는 뺀다.
 */
enum class RunType(
    val label: String,
    val countdownS: Int,
    val durationS: Int,
    val blockInS: Int?,
    val blockOutS: Int?,
) {
    CLEAR_120("무가림 120 s", 10, 120, null, null),
    PERSON_30_60_30("사람 가림 30-60-30", 10, 120, 30, 90),
    STATIC_120("정적 120 s (벽·장비)", 10, 120, null, null),
}

val DISTANCES_M = listOf(1, 3, 5)
val DAYS = listOf(1, 2, 3)

data class TagForm(
    val distanceM: Int = 1,
    val block: Block = Block.NONE,
    val pos: BlockPos = BlockPos.MID,
    val day: Int = 1,
    val placement: Int = 1,
    val wallMaterial: String = "",
    val equipment: String = "",
    val photo: String = "",
    // 고정 설정 — 런마다 잘 안 바뀐다
    val phoneHeightCm: String = "",
    val tagHeightCm: String = "",
    val phoneOrient: String = "",
    val tagOrient: String = "",
    val operatorPos: String = "",
    val registered: Boolean = true,
    val smartThingsConnected: Boolean = false,
) {
    /** 가림 종류가 런 종류를 정한다. 따로 고르게 하면 둘이 어긋날 수 있다 */
    val runType: RunType
        get() = when (block) {
            Block.NONE -> RunType.CLEAR_120
            Block.P1, Block.P2, Block.P3 -> RunType.PERSON_30_60_30
            Block.WALL, Block.EQUIP -> RunType.STATIC_120
        }

    /** 예: d3_p2-mid_day1_pl04, d1_none_day2_pl01 */
    fun condCode(): String {
        val b = if (block == Block.NONE) block.code else "${block.code}-${pos.code}"
        return String.format(Locale.US, "d%d_%s_day%d_pl%02d", distanceM, b, day, placement)
    }

    fun phoneHeightM(): Double? = phoneHeightCm.trim().toDoubleOrNull()?.div(100.0)
    fun tagHeightM(): Double? = tagHeightCm.trim().toDoubleOrNull()?.div(100.0)

    /** 정답 직선거리 = √(수평거리² + 높이차²). 높이를 모르면 null */
    fun directDistanceM(): Double? {
        val hp = phoneHeightM() ?: return null
        val ht = tagHeightM() ?: return null
        val dh = hp - ht
        return sqrt(distanceM.toDouble() * distanceM + dh * dh)
    }

    /** 시작 전에 채워야 하는데 빠진 것. 비어 있으면 시작해도 된다 */
    fun missing(): List<String> = buildList {
        if (phoneHeightM() == null) add("폰 높이(cm)")
        if (tagHeightM() == null) add("태그 높이(cm)")
        if (block == Block.WALL && wallMaterial.isBlank()) add("벽 재질")
        if (block == Block.EQUIP && equipment.isBlank()) add("장비 종류")
    }
}
