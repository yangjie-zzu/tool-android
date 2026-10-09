package com.yukino.tool.module.reader.epub

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yukino.tool.module.reader.common.BlockCache
import com.yukino.tool.module.reader.common.ResolvedTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 位图调试面板: 只读展示当前渲染键命中的全部 WEBVIEW 块位图。
// 数据口径 = collectWebBlockSpecs 同一条遍历(章文件逐段 blockHtml 重算当前键),
// 磁盘 wblocks/ 里有对应 PNG 才列出——旧键/已失效位图一律不显示,也不做任何清理。
// 键未命中磁盘的块只计入"未渲染"汇总(渲染失败/黑名单,正文走兜底自绘)。
// 列表条目直接整幅大图展示, 点击条目跳转到该块所在章首页; 大图按屏宽降采样,
// 异步解码(IO 线程)+ LruCache 缓存, 滑动过程中主线程零解码阻塞。

// 单个块位图条目: 章序号 + 键 + 落盘信息 + 几何/内存状态
class BlockBitmapEntry(
    val chapterIndex: Int,
    val key: String,
    val file: File,
    val fileSize: Long,
    val bitmapW: Int,
    val bitmapH: Int,
    val hasGeom: Boolean,   // 几何 JSON 存在且 ok:1(选择可用)
    val noText: Boolean,    // 几何 JSON ok:2(块内无文本,纯图/装饰块,非失败)
    val inMemory: Boolean   // BlockCache.mem 命中(本会话渲染或已查过)
)

// 快照: 当前键块总数 / 已渲染条目 / 未渲染键数(渲染失败,无位图)
class BlockBitmapSnapshot(
    val totalBlocks: Int,
    val entries: List<BlockBitmapEntry>,
    val missingKeys: Int
)

// 全书当前键位图快照(章文件遍历 + 磁盘核对;IO 线程调用——章文档读取是磁盘 JSON)。
// 键计算与 ReaderPage.collectWebBlockSpecs 完全同参,保证口径一致
fun collectBlockBitmapSnapshot(
    content: EpubBookContent,
    typo: ResolvedTypography
): BlockBitmapSnapshot {
    val root = content.webBlockRoot()
    val entries = ArrayList<BlockBitmapEntry>()
    var total = 0
    var missing = 0
    for (i in 0 until content.chapterCount) {
        val doc = content.chapterDoc(i)
        for (p in doc.paragraphs) {
            val html = p.blockHtml ?: continue
            total++
            val shell = p.ancestorShell ?: ""
            val hash = BlockCache.contentHashOf(html, shell, p.blockDocDir, doc.cssHrefs, doc.cssInline)
            val key = BlockCache.keyOf(typo.fontPx, typo.textWidth, typo.lineSpacingPercent, typo.bookLineHeight, hash)
            val f = BlockCache.fileOf(root, key)
            if (!f.isFile || f.length() == 0L) { missing++; continue }
            val bounds = runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
            }.getOrNull()
            if (bounds == null) { missing++; continue }
            val jf = BlockCache.geomFileOf(root, key)
            val raw = if (jf.isFile) runCatching { jf.readText() }.getOrNull() else null
            val geomOk = raw?.let { r -> runCatching { BlockCache.parseGeomJson(r) }.getOrNull() != null } == true
            val noText = !geomOk && raw != null && raw.contains("\"ok\":2")
            entries += BlockBitmapEntry(
                chapterIndex = i,
                key = key,
                file = f,
                fileSize = f.length(),
                bitmapW = bounds.first,
                bitmapH = bounds.second,
                hasGeom = geomOk,
                noText = noText,
                inMemory = BlockCache.inMemory(key)
            )
        }
    }
    return BlockBitmapSnapshot(total, entries, missing)
}

// 大图解码缓存: key = path+lastModified, 容量按字节计; 驱逐交给 LruCache, 不手动 recycle
private val bitmapCache = object : LruCache<String, android.graphics.Bitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
}

// 按目标显示宽度降采样解码(inSampleSize 2 的幂采样; 整块位图可达数万像素,必须降采样)
private fun decodeForWidth(path: String, targetW: Int): android.graphics.Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    var sample = 1
    while (opts.outWidth / (sample * 2) >= targetW) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockBitmapDebugSheet(
    content: EpubBookContent,
    typo: ResolvedTypography,
    onDismiss: () -> Unit,
    onJumpToChapter: (Int) -> Unit
) {
    var snapshot by remember { mutableStateOf<BlockBitmapSnapshot?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(content, typo) {
        snapshot = withContext(Dispatchers.IO) { collectBlockBitmapSnapshot(content, typo) }
    }
    val density = LocalDensity.current
    val screenWpx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "位图调试(当前键)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            snapshot?.let { snap ->
                Text(
                    "块 ${snap.totalBlocks} · 已渲染 ${snap.entries.size} · 未渲染 ${snap.missingKeys}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                placeholder = { Text("按章号/键前缀过滤") },
                colors = TextFieldDefaults.colors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            val f = filter.trim()
            val shown = snapshot?.entries?.filter {
                f.isEmpty() || it.key.startsWith(f, ignoreCase = true) ||
                    it.chapterIndex.toString() == f
            } ?: emptyList()
            LazyColumn(
                modifier = Modifier.heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(shown, key = { it.key }) { e ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onJumpToChapter(e.chapterIndex) }
                    ) {
                        val cacheKey = "${e.file.absolutePath}#${e.file.lastModified()}"
                        val bmp by produceState<android.graphics.Bitmap?>(
                            initialValue = bitmapCache.get(cacheKey)?.takeIf { !it.isRecycled },
                            cacheKey
                        ) {
                            if (value == null) {
                                value = withContext(Dispatchers.IO) {
                                    decodeForWidth(e.file.absolutePath, screenWpx)?.also {
                                        bitmapCache.put(cacheKey, it)
                                    }
                                }
                            }
                        }
                        val b = bmp
                        // 解码完成前用快照原始宽高占位(居中转圈), 条目高度稳定不跳动
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .aspectRatio(e.bitmapW.toFloat() / e.bitmapH.toFloat()),
                            contentAlignment = Alignment.Center
                        ) {
                            if (b == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Image(
                                    bitmap = b.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "章 ${e.chapterIndex} · ${e.key.take(8)} · ${e.bitmapW}×${e.bitmapH} · " +
                                formatSize(e.fileSize) + " · " +
                                (if (e.inMemory) "内存" else "磁盘") + " · " +
                                (if (e.hasGeom) "几何可用" else if (e.noText) "无文字" else "无几何"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
