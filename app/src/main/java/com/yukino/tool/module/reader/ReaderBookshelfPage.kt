package com.yukino.tool.module.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yukino.tool.module.reader.common.BookFormat
import com.yukino.tool.module.reader.common.ReaderBook
import com.yukino.tool.module.reader.common.ReaderGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 书架: 分组(可嵌套)在前 + 当前层书籍在后, 各自按添加时间倒序。
// 点分组进入下一层(顶栏返回上一层);删组不删书, 组内书籍回归未分组。
// 导入双入口: 文件夹(成组, 登记后留书架)/ 文件(登记后自动打开)。
@Composable
fun ReaderBookshelfPage(
    groups: List<ReaderGroup>,
    books: List<ReaderBook>,
    currentGroup: ReaderGroup?,
    importing: Boolean,
    onOpen: (ReaderBook) -> Unit,
    onDelete: (ReaderBook) -> Unit,
    onOpenGroup: (String) -> Unit,
    onBackToParent: () -> Unit,
    onDeleteGroup: (ReaderGroup) -> Unit,
    onDeleteGroupAll: (ReaderGroup) -> Unit,
    onImportFile: () -> Unit,
    onImportFolder: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<ReaderGroup?>(null) }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏: 组内显示返回 + 组名, 顶层显示"书架"
            if (currentGroup != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    IconButton(onClick = onBackToParent) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回上一层")
                    }
                    Text(
                        currentGroup.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    "书架",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
            HorizontalDivider()

            val sortedGroups = groups.sortedByDescending { it.addedAt }
            val sortedBooks = books.sortedByDescending { it.addedAt }
            if (sortedGroups.isEmpty() && sortedBooks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (importing) "正在导入..." else "书架空空，点击右下角导入书籍或文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sortedGroups, key = { "g:${it.id}" }) { group ->
                        GroupItem(
                            group = group,
                            onDelete = { deletingGroup = group },
                            onOpen = { onOpenGroup(group.id) }
                        )
                        HorizontalDivider()
                    }
                    items(sortedBooks, key = { it.id }) { book ->
                        BookItem(book = book, onOpen = onOpen, onDelete = onDelete)
                        HorizontalDivider()
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { menuOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("导入")
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("导入文件夹（作为分组）") }, onClick = {
                    menuOpen = false
                    onImportFolder()
                })
                DropdownMenuItem(text = { Text("导入文件（导入后打开）") }, onClick = {
                    menuOpen = false
                    onImportFile()
                })
            }
        }

        if (importing) {
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 120.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }

        deletingGroup?.let { group ->
            AlertDialog(
                onDismissRequest = { deletingGroup = null },
                title = { Text("删除分组「${group.name}」？") },
                text = { Text("仅删分组：组内书籍和子分组保留，移回未分组。\n删除全部：子分组和组内书籍（含缓存与进度）一并删除，不可恢复。") },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            onDeleteGroup(group)          // 仅删组
                            deletingGroup = null
                        }) { Text("仅删分组") }
                        TextButton(onClick = {
                            onDeleteGroupAll(group)       // 连书籍一起删
                            deletingGroup = null
                        }) { Text("删除全部") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletingGroup = null }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun GroupItem(
    group: ReaderGroup,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "删除分组",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BookItem(
    book: ReaderBook,
    onOpen: (ReaderBook) -> Unit,
    onDelete: (ReaderBook) -> Unit
) {
    val dateFmt = remember(book.addedAt) { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    // 封面缩略图: 后台解码 ~96px 小图(48dp 行首);SVG 封面栅格化;无封面/解码失败回退图标
    val cover by produceState<android.graphics.Bitmap?>(initialValue = null, book.coverPath) {
        val path = book.coverPath ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (com.yukino.tool.module.reader.common.SvgDecoder.isSvg(path)) {
                    com.yukino.tool.module.reader.common.SvgDecoder.decode(path, 96, 144)
                } else {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(path, opts)
                    var sample = 1
                    while (opts.outWidth / (sample * 2) >= 96) sample *= 2
                    opts.inSampleSize = sample
                    opts.inJustDecodeBounds = false
                    android.graphics.BitmapFactory.decodeFile(path, opts)
                }
            }.getOrNull()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(book) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (cover != null) {
            Image(
                bitmap = cover!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
            )
        } else {
            Icon(
                Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 状态显示: "打开"是未读→已读的分界线(未打开=未读), 进度只影响已读的百分比
            val percent = (book.progress.percent * 100).toInt()
            val subtitle = buildString {
                if (book.format == BookFormat.EPUB) append("EPUB · ")
                if (book.fileSize > 0) {
                    append(
                        when {
                            book.fileSize >= 1 shl 20 -> "%.1f MB".format(book.fileSize / 1048576.0)
                            book.fileSize >= 1024 -> "${book.fileSize / 1024} KB"
                            else -> "${book.fileSize} B"
                        } + " · "
                    )
                }
                append(if (book.ready) "已读 $percent%" else "未读")
                append(" · ${dateFmt.format(Date(book.lastReadAt))}")
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = { onDelete(book) }) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
