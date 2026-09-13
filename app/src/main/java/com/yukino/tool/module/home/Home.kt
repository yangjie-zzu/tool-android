package com.yukino.tool.module.home

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yukino.tool.BuildConfig
import com.yukino.tool.R
import com.yukino.tool.components.MenuCard
import com.yukino.tool.module.bluetooth.BluetoothActivity
import com.yukino.tool.module.compress.CompressActivity
import com.yukino.tool.module.ip.IpActivity
import com.yukino.tool.module.note.NoteActivity
import com.yukino.tool.module.reader.ReaderActivity
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
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(text = "个人工具集")
                Text(
                    text = stringResource(R.string.build_time).let { "v${BuildConfig.VERSION_NAME} · 构建于 $it" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            item {
                MenuCard(
                    onClick = {
                        activity.startActivity(
                            Intent(
                                activity,
                                IpActivity::class.java
                            ).also {
                                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    },
                    title = { Text(text = "IP信息") }
                ) {
                    Icon(imageVector = Icons.Rounded.Info, contentDescription = "IP信息")
                }
            }
            item {
                MenuCard(
                    onClick = {
                        activity.startActivity(
                            Intent(
                                activity,
                                NoteActivity::class.java
                            ).also {
                                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    },
                    title = { Text(text = "备忘录") }
                ) {
                    Icon(imageVector = Icons.Rounded.Person, contentDescription = "备忘录")
                }
            }
            item {
                MenuCard(
                    onClick = {
                        activity.startActivity(
                            Intent(
                                activity,
                                ReaderActivity::class.java
                            ).also {
                                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    },
                    title = { Text(text = "阅读") }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = "阅读"
                    )
                }
            }
        })
    }
}