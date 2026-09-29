package com.knu.blechprobe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knu.blechprobe.model.Block
import com.knu.blechprobe.model.BlockPos
import com.knu.blechprobe.model.DAYS
import com.knu.blechprobe.model.DISTANCES_M
import com.knu.blechprobe.model.TagForm
import java.util.Locale

/**
 * TAG 조건 입력. 선택형으로 받아 조건 코드를 자동으로 만든다.
 * 런 종류는 가림 종류가 정한다(따로 고르면 둘이 어긋날 수 있다).
 */
@Composable
internal fun TagFormSection(form: TagForm, enabled: Boolean, onChange: (TagForm) -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("거리 (수평)")
        ChipRow(DISTANCES_M, form.distanceM, enabled, { "$it m" }) { onChange(form.copy(distanceM = it)) }

        Label("가림")
        val blocks = Block.entries
        ChipRow(blocks.take(3), form.block, enabled, { it.label }) { onChange(form.copy(block = it)) }
        ChipRow(blocks.drop(3), form.block, enabled, { it.label }) { onChange(form.copy(block = it)) }

        if (form.block != Block.NONE) {
            Label("가림 위치")
            ChipRow(BlockPos.entries, form.pos, enabled, { it.label }) { onChange(form.copy(pos = it)) }
        }
        if (form.block == Block.WALL) {
            Field("벽 재질 (예: 석고보드)", form.wallMaterial, enabled) { onChange(form.copy(wallMaterial = it)) }
        }
        if (form.block == Block.EQUIP) {
            Field("장비 종류 (예: 철제 캐비닛)", form.equipment, enabled) { onChange(form.copy(equipment = it)) }
        }

        Label("날")
        ChipRow(DAYS, form.day, enabled, { "${it}일차" }) { onChange(form.copy(day = it)) }

        Label("배치 번호")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onChange(form.copy(placement = (form.placement - 1).coerceAtLeast(1))) },
                enabled = enabled) { Text("−") }
            Text(form.placement.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { onChange(form.copy(placement = (form.placement + 1).coerceAtMost(99))) },
                enabled = enabled) { Text("+") }
        }

        Field("사진 파일명 (선택)", form.photo, enabled) { onChange(form.copy(photo = it)) }

        /* 자주 안 바뀌는 값 */
        var open by remember { mutableStateOf(form.missing().isNotEmpty()) }
        TextButton(onClick = { open = !open }) {
            Text((if (open) "▾" else "▸") + " 고정 설정 — 높이·방향·태그 상태")
        }
        if (open) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("폰 높이 cm", form.phoneHeightCm, enabled, Modifier.weight(1f), number = true) {
                    onChange(form.copy(phoneHeightCm = it))
                }
                Field("태그 높이 cm", form.tagHeightCm, enabled, Modifier.weight(1f), number = true) {
                    onChange(form.copy(tagHeightCm = it))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("폰 방향", form.phoneOrient, enabled, Modifier.weight(1f)) { onChange(form.copy(phoneOrient = it)) }
                Field("태그 방향", form.tagOrient, enabled, Modifier.weight(1f)) { onChange(form.copy(tagOrient = it)) }
            }
            Field("조작자 위치", form.operatorPos, enabled) { onChange(form.copy(operatorPos = it)) }
            Label("태그 상태")
            ChipRow(listOf(true, false), form.registered, enabled, { if (it) "등록됨" else "미등록" }) {
                onChange(form.copy(registered = it))
            }
            ChipRow(listOf(false, true), form.smartThingsConnected, enabled,
                { if (it) "SmartThings 연결됨" else "SmartThings 끊김" }) {
                onChange(form.copy(smartThingsConnected = it))
            }
        }

        /* 만들어질 것 */
        Text("조건 코드  ${form.condCode()}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(
            "런: ${form.runType.label} · 정답 직선거리 " +
                (form.directDistanceM()?.let { String.format(Locale.US, "%.3f m", it) } ?: "— (높이 입력 필요)"),
            fontSize = 12.sp, color = Subtle,
        )
        val miss = form.missing()
        if (miss.isNotEmpty()) Text("시작 전에 채울 것: ${miss.joinToString(", ")}", fontSize = 12.sp, color = Warn)
    }

@Composable
private fun Field(
    label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false, onValue: (String) -> Unit,
) = OutlinedTextField(
    value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true, enabled = enabled,
    keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    modifier = modifier,
)
