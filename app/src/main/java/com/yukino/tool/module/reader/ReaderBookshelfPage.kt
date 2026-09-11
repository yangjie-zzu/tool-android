package com.yukino.tool.module.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 书架: 按第一次导入时间倒序(最新导入置顶),显示进度百分比;删除时连带清理缓存
@Composable
fun ReaderBookshelfPage(
    books: List<ReaderBook>?,
    importing: Boolean,
    onOpen: (ReaderBook) -> Unit,
    onDelete: (ReaderBook) -> Unit,
    onImport: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "书架",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
            HorizontalDivider()
            val sorted = books.orEmpty().sortedByDescending { it.addedAt }
            if (sorted.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (importing) "正在导入..." else "书架空空，点击右下角导入 TXT",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sorted, key = { it.id }) { book ->
                        BookItem(book = book, onOpen = onOpen, onDelete = onDelete)
                        HorizontalDivider()
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onImport,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("导入 TXT")
        }

        if (importing) {
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 120.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(book) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
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
            val percent = (book.progress.percent * 100).toInt()
            val subtitle = buildString {
                if (book.fileSize > 0) {
                    append(
                        when {
                            book.fileSize >= 1 shl 20 -> "%.1f MB".format(book.fileSize / 1048576.0)
                            book.fileSize >= 1024 -> "${book.fileSize / 1024} KB"
                            else -> "$book.fileSize B"
                        } + " · "
                    )
                }
                append("${book.chapters.size} 章 · ")
                append(if (percent > 0) "已读 $percent%" else "未读")
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
