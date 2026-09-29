package com.knu.blechprobe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Muted = Color(0xFF94A3B8)
internal val Subtle = Color(0xFF64748B)
internal val Ink = Color(0xFF0F172A)
internal val Warn = Color(0xFFDC2626)

@Composable internal fun Stat(k: String, v: String, color: Color = Ink) = Column {
    Text(k, fontSize = 11.sp, color = Muted)
    Text(v, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
}

@Composable internal fun Notice(text: String, bg: Color) =
    Box(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(text, fontSize = 12.sp, color = Ink)
    }

@Composable internal fun Label(text: String) = Text(text, fontSize = 12.sp, color = Subtle, fontWeight = FontWeight.SemiBold)

/** 칩 한 줄. 선택지가 많으면 여러 번 부른다 (FlowRow 없이) */
@Composable internal fun <T> ChipRow(
    items: List<T>, selected: T, enabled: Boolean, label: (T) -> String, onPick: (T) -> Unit,
) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    items.forEach { v ->
        FilterChip(selected = v == selected, onClick = { onPick(v) }, enabled = enabled, label = { Text(label(v)) })
    }
}
