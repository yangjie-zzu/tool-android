package com.yukino.tool.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

@Composable
fun Title(
    title: String?
) {
    Text(title.text(), fontWeight = FontWeight.Bold)
}