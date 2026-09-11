@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yukino.tool.module.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val PRESET_COLORS = listOf(
    0xFFFFFFFF, 0xFFF6F1E7, 0xFFE8DCC0, 0xFFC7EDCC,
    0xFFD6EAF8, 0xFFFDEDEC, 0xFFE8E8E8, 0xFF9DA5AE,
    0xFF15171A, 0xFF2C3A2E, 0xFF4A3B28, 0xFF1A1A1A
)

// 设置面板: 全部改动即时生效(settings 即 Compose state,改完立刻重排重绘)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ThemeRow(settings, onChange)
            if (settings.theme == ReaderTheme.CUSTOM) {
                CustomColorRow("背景", settings.customBg ?: settings.effectiveBg) {
                    onChange(settings.copy(customBg = it))
                }
                CustomColorRow("前景", settings.customFg ?: settings.effectiveFg) {
                    onChange(settings.copy(customFg = it))
                }
            }
            FontSizeRow(settings, onChange)
            PercentSliderRow("行距", settings.lineSpacingPercent, Typography.SPACING_MIN, Typography.SPACING_MAX, 10, "%") {
                onChange(settings.copy(lineSpacingPercent = it))
            }
            PercentSliderRow("段距", settings.paragraphSpacingPercent, 0, 300, 50, "%") {
                onChange(settings.copy(paragraphSpacingPercent = it))
            }
            PercentSliderRow("边距", settings.marginDp, Typography.MARGIN_MIN, Typography.MARGIN_MAX, 4, "dp") {
                onChange(settings.copy(marginDp = it))
            }
            SwitchRow("首行缩进", settings.indent) { onChange(settings.copy(indent = it)) }
            SwitchRow("两端对齐", settings.justify) { onChange(settings.copy(justify = it)) }
            SwitchRow("屏幕常亮", settings.keepScreenOn) { onChange(settings.copy(keepScreenOn = it)) }
        }
    }
}

@Composable
private fun ThemeRow(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    Column {
        Text("主题", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReaderTheme.entries.forEach { theme ->
                val (bg, fg) = theme.colors
                val selected = settings.theme == theme
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(bg), CircleShape)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable { onChange(settings.copy(theme = theme)) },
                    contentAlignment = Alignment.Center
                ) {
                    if (theme == ReaderTheme.CUSTOM) {
                        Text("自", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Box(Modifier.size(14.dp).background(Color(fg), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomColorRow(label: String, current: Long, onPick: (Long) -> Unit) {
    var hexInput by remember(current) { mutableStateOf("%06X".format(current and 0xFFFFFF)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(16.dp).background(Color(current), CircleShape))
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hexInput,
                onValueChange = { input ->
                    hexInput = input
                    input.toLongOrNull(16)?.let { v ->
                        if (input.length == 6) onPick(0xFF000000L or v)
                    }
                },
                singleLine = true,
                modifier = Modifier.width(130.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                prefix = { Text("#", style = MaterialTheme.typography.bodySmall) }
            )
        }
        Spacer(Modifier.height(6.dp))
        // 两行 12 预设色
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESET_COLORS.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c ->
                        val selected = (current and 0xFFFFFF) == (c and 0xFFFFFF)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(c), CircleShape)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable { onPick(c) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FontSizeRow(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("字号", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = {
            val next = (settings.fontSizeDp - 1).coerceAtLeast(Typography.FONT_MIN)
            onChange(settings.copy(fontSizeDp = next))
        }) { Icon(Icons.Rounded.Remove, "减小字号") }
        Text(
            "${settings.fontSizeDp.roundToInt()}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(36.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
        IconButton(onClick = {
            val next = (settings.fontSizeDp + 1).coerceAtMost(Typography.FONT_MAX)
            onChange(settings.copy(fontSizeDp = next))
        }) { Icon(Icons.Rounded.Add, "增大字号") }
    }
}

@Composable
private fun PercentSliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String,
    onChange: (Int) -> Unit
) {
    Column {
        Text("$label  $value$unit", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val snapped = ((it - min) / step).roundToInt() * step + min
                onChange(snapped.coerceIn(min, max))
            },
            valueRange = min.toFloat()..max.toFloat()
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
