package com.yukino.tool.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MenuCard(
    onClick: () -> Unit,
    title: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable {
                onClick()
            }
            .fillMaxWidth()
            .padding(15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content()
        title()
    }
}
