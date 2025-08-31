package com.yukino.tool.module.home

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yukino.tool.components.MenuCard
import com.yukino.tool.module.bluetooth.BluetoothActivity
import com.yukino.tool.module.compress.CompressActivity
import com.yukino.tool.util.rememberCurrentActivity
import com.yukino.tool.module.web.WebActivity
import kotlinx.coroutines.launch

@Composable
fun Home() {

    val activity = rememberCurrentActivity()
    val coroutineScope = rememberCoroutineScope()
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
                    title = { Text(text = "web") },
                    onClick = {
                        activity.startActivity(
                            Intent(
                                activity,
                                WebActivity::class.java
                            ).also { it.flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "浏览器"
                    )
                }
            }
            item {
                MenuCard(
                    onClick = {
                        activity.startActivity(
                            Intent(
                                activity,
                                CompressActivity::class.java
                            ).also {
                                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    },
                    title = { Text(text = "解压缩") }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.List,
                        contentDescription = "解压缩"
                    )
                }
            }
            item {
                MenuCard(
                    onClick = {
                        activity.startActivity(
                            Intent(
                                activity,
                                BluetoothActivity::class.java
                            ).also {
                                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    },
                    title = { Text(text = "蓝牙应用") }
                ) {
                    Icon(imageVector = Icons.Rounded.DateRange, contentDescription = "蓝牙应用")
                }
            }
        })
    }
}