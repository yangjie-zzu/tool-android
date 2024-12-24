package com.yukino.tool.home

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yukino.tool.components.MenuCard
import com.yukino.tool.util.currentActivity
import com.yukino.tool.web.WebActivity

@Composable
fun Home() {

    val activity = currentActivity()

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()

        ) {
            Text(text = "个人工具集", modifier = Modifier.align(Alignment.Center))
        }
        LazyVerticalGrid(columns = GridCells.Fixed(3), content = {
            item {
                MenuCard(
                    title = { Text(text = "去广告web", maxLines = 1) },
                    onClick = {
                        activity.startActivity(Intent(activity, WebActivity::class.java).also { it.flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    }
                ) {
                    Icon(imageVector = Icons.Rounded.Send, contentDescription = "浏览器")
                }
            }
            item {
                MenuCard(onClick = { /*TODO*/ }, title = { Text(text = "解压缩") }) {
                    Icon(imageVector = Icons.Rounded.List, contentDescription = "解压缩")
                }
            }
            item {
                MenuCard(onClick = { /*TODO*/ }, title = { Text(text = "记事本") }) {
                    Icon(imageVector = Icons.Rounded.DateRange, contentDescription = "记事本")
                }
            }
        })
    }
}