package com.yukino.tool.module.reader

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.yukino.tool.module.reader.common.BookFormat
import com.yukino.tool.module.reader.common.ImportResult
import com.yukino.tool.module.reader.common.Progress
import com.yukino.tool.module.reader.common.ReaderBook
import com.yukino.tool.module.reader.common.ReaderGroup
import com.yukino.tool.module.reader.epub.EpubImporter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 重新授权差异项: label 如"路径"/"提供方"/"文档类型"/"文件大小（不同）"
data class ReauthDiffItem(val label: String, val old: String, val new: String)

// 重新授权比对结果: matched=完全一致可直接换源;forced=忽略差异的强制换源结果;
// diffs=差异清单(仅不同项, 大小始终含);forceAllowed=类型相同才可强制授权
data class ReauthCheck(
    val matched: ReaderBook?,
    val forced: ReaderBook,
    val diffs: List<ReauthDiffItem>,
    val forceAllowed: Boolean
)

// 登记式导入: 只写一行元数据(文件名/格式/大小/组), 内容转码/解压与章节解析
// 全部推迟到首次打开(TxtImporter/EpubImporter.ensureReady)。
// 同 sourceUri 去重: 重复登记返回已有书目, 进度/组不变。
// 格式分流: txt 系→TXT, html 系/图片→WEB(WebView 渲染), epub/pdf 原生, 其余拒绝。
object BookRegistrar {

    // TXT 阅读器直读的纯文本扩展名
    private val TXT_EXTS = setOf("txt", "md", "markdown", "log", "text", "out")

    // WebView 能直接渲染的扩展名(html 系 + 图片)
    private val WEB_EXTS = setOf(
        "html", "htm", "xhtml", "mht", "mhtml", "svg",
        "jpg", "jpeg", "png", "gif", "webp", "bmp"
    )

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    // 按扩展名定格式;不支持返回 null
    fun formatOf(name: String): String? = when (val ext = extOf(name)) {
        "epub" -> BookFormat.EPUB
        "pdf" -> BookFormat.PDF
        in TXT_EXTS -> BookFormat.TXT
        in WEB_EXTS -> BookFormat.WEB
        else -> {
            if (ext.isEmpty()) BookFormat.TXT else null
        }
    }

    // 单本登记(uri 可为文件选择器文档或"打开方式/分享"带入的流)
    suspend fun register(context: Context, uri: Uri, groupId: String?): ImportResult =
        withContext(Dispatchers.IO) {
            runCatching { doRegister(context, uri, groupId) }.getOrElse {
                ImportResult.Failed("导入失败: ${it.message ?: "未知错误"}")
            }
        }

    // 文件夹登记: 目录结构映射为组结构(所选文件夹=一个组, 子文件夹=嵌套子组),
    // 递归扫描 txt/epub;同名同级组复用(重复导入同一文件夹不产生重复组)。
    // 返回 (新增组数, 登记书数);失败抛异常
    suspend fun registerFolder(
        context: Context,
        treeUri: Uri,
        parentGroupId: String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        runCatching {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootName = queryName(context, documentUri(treeUri, rootDocId)) ?: "导入文件夹"
            val files = ArrayList<Pair<String, String>>()   // (docId, 相对路径名)
            walk(context, treeUri, rootDocId, "") { docId, relName -> files += docId to relName }
            if (files.isEmpty()) return@runCatching ImportResult.Failed("文件夹里没有可导入的书籍")

            // 目录路径逐级建组: 所选文件夹本身成为一个组(组名=文件夹名),
            // 子目录为嵌套子组;每级按"同名同级复用"匹配已有组(重复导入不产生重复组)
            val now = System.currentTimeMillis()
            val groups = ArrayList<ReaderGroup>()
            val existing = ReaderStore.loadGroups(context)
            val rootUri = treeUri.toString()
            existing.firstOrNull { it.sourceUri == rootUri }?.let {
                return@runCatching ImportResult.Failed("该文件夹已导入过（分组「${it.name}」）")
            }
            val rootGroup = ReaderGroup(UUID.randomUUID().toString(), rootName, parentGroupId, now, rootUri)
                .also { groups += it }
            fun groupFor(path: List<String>): String {
                var id = rootGroup.id
                for ((depth, name) in path.withIndex()) {
                    val hit = groups.firstOrNull { it.parentId == id && it.name == name }
                        ?: existing.firstOrNull { it.parentId == id && it.name == name }
                    id = hit?.id ?: ReaderGroup(UUID.randomUUID().toString(), name, id, now + depth + 1)
                        .also { groups += it }.id
                }
                return id
            }

            val books = ArrayList<ReaderBook>()
            val booksAll = ReaderStore.loadBooks(context)
            for ((docId, relName) in files) {
                val name = relName.substringAfterLast('/')
                val format = formatOf(name) ?: continue
                val docUri = documentUri(treeUri, docId).toString()
                if (booksAll.any { it.sourceUri == docUri }) continue   // 已登记,进度保留
                val path = relName.substringBeforeLast('/', "").split('/').filter { it.isNotBlank() }
                val size = querySize(context, documentUri(treeUri, docId))
                val id = UUID.randomUUID().toString()
                // WEB 书 WebView 只能加载本地文件: 登记时即转存, 无懒初始化
                val webCopy = if (format == BookFormat.WEB) {
                    copyToInbox(context, id, documentUri(treeUri, docId), name) ?: continue
                } else null
                books += ReaderBook(
                    id = id,
                    title = name.substringBeforeLast('.').ifBlank { "未命名" },
                    sourceUri = webCopy ?: docUri,
                    cachePath = webCopy?.let { Uri.parse(it).path ?: it }
                        ?: cachePathFor(context, id, format),
                    encoding = "",
                    totalChars = 0,
                    chapters = emptyList(),
                    addedAt = now,
                    lastReadAt = now,
                    fileSize = size,
                    format = format,
                    groupId = groupFor(path),
                    ready = webCopy != null
                )
                onProgress(books.size, files.size)
            }
            ReaderStore.upsertGroups(context, groups)
            ReaderStore.upsertBooks(context, books)
            ImportResult.FolderImported(groups.size, books.size)
        }.getOrElse {
            ImportResult.Failed("文件夹导入失败: ${it.message ?: "未知错误"}")
        }
    }

    // 重新授权比对: 阅读页打开遇 Permission Denial 后, 用户重选文件时调用。
    // 对新 uri 重新持久化授权(先于匹配校验);完全一致(authority+文档 id)返回 matched
    // 直接换源;否则返回逐项差异清单供弹窗展示, forced 为忽略差异的强制换源结果。
    // forceAllowed=文档类型(格式类)相同——类型不同强换后阅读器按原格式解析必乱, 禁止。
    fun reauthorizeCheck(context: Context, book: ReaderBook, picked: Uri): ReauthCheck {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                picked, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val oldUri = Uri.parse(book.sourceUri)
        val oldId = documentIdOf(oldUri)
        val newId = documentIdOf(picked)
        val diffs = ArrayList<ReauthDiffItem>()
        // 路径: 文档 id 还原绝对路径;任一侧解析不出则标注
        if (oldId == null || newId == null) {
            diffs += ReauthDiffItem(
                "路径",
                oldId?.let { absPathOfDocId(it) } ?: "无法解析文档路径",
                newId?.let { absPathOfDocId(it) } ?: "无法解析文档路径"
            )
        } else if (oldId != newId) {
            diffs += ReauthDiffItem("路径", absPathOfDocId(oldId), absPathOfDocId(newId))
        }
        // 提供方
        if (oldUri.authority != picked.authority) {
            diffs += ReauthDiffItem("提供方", oldUri.authority ?: "?", picked.authority ?: "?")
        }
        // 文档类型(按格式分流判定, txt/md 同属 TXT 类)
        val newName = queryName(context, picked) ?: ""
        val newFormat = formatOf(newName)
        val typeSame = newFormat != null && newFormat == book.format
        if (!typeSame) {
            diffs += ReauthDiffItem(
                "文档类型",
                book.format,
                newFormat ?: "不支持(.${extOf(newName).ifEmpty { "?" }})"
            )
        }
        // 文件大小: 始终显示对比(相同≠同一文件, 仅参考);登记大小 0 视为未知
        val newSize = querySize(context, picked)
        diffs += ReauthDiffItem(
            "文件大小" + if (newSize == book.fileSize) "（相同）" else "（不同）",
            sizeText(book.fileSize),
            sizeText(newSize)
        )
        val matched =
            if (oldUri.authority == picked.authority && oldId != null && oldId == newId) {
                book.copy(sourceUri = picked.toString())
            } else null
        return ReauthCheck(matched, book.copy(sourceUri = picked.toString()), diffs, typeSame)
    }

    private fun documentIdOf(uri: Uri): String? {
        val segs = uri.pathSegments
        if (segs.size >= 2 && segs[segs.size - 2] == "document") return segs.last()
        return null
    }

    // 源文件绝对路径(授权失效时悬浮展示, 指引用户去选择器里定位原文件):
    // 文档 id 形如 "primary:Download/x.txt"(内部存储)或 "0000-0000:Book/x.epub"(SD 卷),
    // primary 卷 → Environment 外存根路径(/storage/emulated/0), 其他卷 → /storage/<卷id>;
    // 非文档结构 uri(其他 provider)返回原串兜底。
    @Suppress("DEPRECATION")
    fun sourceAbsolutePath(uri: Uri): String {
        val docId = documentIdOf(uri) ?: return uri.toString()
        val idx = docId.indexOf(':')
        if (idx <= 0 || idx == docId.length - 1) return uri.toString()
        return absPathOfDocId(docId)
    }

    // 文档 id "卷id:路径" → 绝对路径(卷根拼接), 结果压缩连续斜杠
    // (部分提供方冒号后带前导/等冗余分隔符时避免出现 //);非 "卷:路径" 结构原样返回
    @Suppress("DEPRECATION")
    private fun absPathOfDocId(docId: String): String {
        val idx = docId.indexOf(':')
        if (idx <= 0 || idx == docId.length - 1) return docId
        val root = if (docId.substring(0, idx) == "primary") {
            Environment.getExternalStorageDirectory().absolutePath
        } else "/storage/${docId.substring(0, idx)}"
        return "$root/${docId.substring(idx + 1)}".replace(Regex("/{2,}"), "/")
    }

    // 弹窗展示用大小文本;0/负数视为未知(导入时 querySize 失败落 0)
    internal fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun doRegister(context: Context, uri: Uri, groupId: String?): ImportResult {
        // 持久化授权失败(系统"打开方式/分享"进来的 grant 不带 persistable flag)时,
        // 授权随进程死亡失效, 懒初始化将报 Permission Denial——此类 uri 登记时立即
        // 把源文件转存到应用缓存, sourceUri 指向本地副本(删除书时按 id 前缀一并清理)
        val persistable = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess

        val books = ReaderStore.loadBooks(context)
        val existing = books.firstOrNull { it.sourceUri == uri.toString() }
        if (existing != null) return ImportResult.Success(existing)   // 重复登记: 原样返回(自动打开)

        val name = queryName(context, uri) ?: uri.lastPathSegment ?: "未命名"
        val format = formatOf(name)
            ?: return ImportResult.Failed("不支持的格式: .${extOf(name).ifEmpty { "?" }}")
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        // WEB 书 WebView 无法加载 content:// uri, 一律转存本地副本
        val effectiveUri: String
        val cachePath: String
        if (format == BookFormat.WEB) {
            val copied = copyToInbox(context, id, uri, name)
                ?: return ImportResult.Failed("无法读取文件(转存失败)")
            effectiveUri = copied
            // copyToInbox 返回 file:// uri 字符串; cachePath 需要纯文件路径
            cachePath = Uri.parse(copied).path ?: copied
        } else {
            effectiveUri = if (persistable) uri.toString() else {
                copyToInbox(context, id, uri, name)
                    ?: return ImportResult.Failed("无法读取文件(授权不可持久且转存失败)")
            }
            cachePath = cachePathFor(context, id, format)
        }
        val book = ReaderBook(
            id = id,
            title = name.substringBeforeLast('.').ifBlank { "未命名" },
            sourceUri = effectiveUri,
            cachePath = cachePath,
            encoding = "",
            totalChars = 0,
            chapters = emptyList(),
            addedAt = now,
            lastReadAt = now,
            fileSize = querySize(context, uri),
            format = format,
            groupId = groupId,
            ready = format != BookFormat.WEB   // WEB 内容已转存本地即就绪, 无懒初始化
        )
        ReaderStore.upsertBook(context, book)
        return ImportResult.Success(book)
    }

    // 导入文本: 正文包装为带阅读主题配色的 HTML, 落盘后按 WEB 格式登记并返回
    fun registerText(context: Context, title: String, content: String, groupId: String?): ImportResult {
        val name = title.ifBlank { "未命名文本" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val dst = File(File(context.cacheDir, "reader/books"), "$id.src.html")
        dst.parentFile?.mkdirs()
        // 正文不做任何转换也不包外壳, 原样落盘交给 WebView;
        // 编码统一由 WebBookReader 的 WebView 设置(defaultTextEncodingName=UTF-8)保证
        val html = content.trim()
        runCatching { dst.writeText(html) }.getOrElse {
            return ImportResult.Failed("写入文本失败: ${it.message}")
        }
        val uri = Uri.fromFile(dst)
        val book = ReaderBook(
            id = id,
            title = name,
            sourceUri = uri.toString(),
            cachePath = dst.absolutePath,
            encoding = "",
            totalChars = content.length.toLong(),
            chapters = emptyList(),
            addedAt = now,
            lastReadAt = now,
            fileSize = dst.length(),
            format = BookFormat.WEB,
            groupId = groupId,
            ready = true
        )
        ReaderStore.upsertBook(context, book)
        return ImportResult.Success(book)
    }

    // 不可持久化授权的源文件转存: reader/books/<id>.src.<ext>(与各格式缓存同目录,
    // 删书时按 id 前缀清理)。失败返回 null
    private fun copyToInbox(context: Context, id: String, uri: Uri, name: String): String? =
        runCatching {
            val ext = name.substringAfterLast('.', "bin").take(8)
            val dst = File(File(context.cacheDir, "reader/books"), "$id.src.$ext")
            dst.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (dst.length() == 0L) return null
            Uri.fromFile(dst).toString()
        }.getOrNull()

    // 登记态也预生成缓存路径: 懒初始化写入位置在登记时即定
    private fun cachePathFor(context: Context, id: String, format: String): String =
        when (format) {
            BookFormat.EPUB -> EpubImporter.chapterDir(context, id).absolutePath
            BookFormat.PDF -> com.yukino.tool.module.reader.pdf.PdfImporter.cacheFile(context, id).absolutePath
            else -> TxtImporter.cacheFile(context, id).absolutePath
        }

    private fun documentUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    // 递归收集目录树下的书籍文件, 回调 (docId, 相对路径名)
    private fun walk(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        relPrefix: String,
        onFile: (docId: String, relName: String) -> Unit
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val dirs = ArrayList<Pair<String, String>>()
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs += docId to name
                    } else if (formatOf(name) != null) {
                        onFile(docId, if (relPrefix.isEmpty()) name else "$relPrefix/$name")
                    }
                }
            }
        }
        for ((docId, name) in dirs) {
            walk(context, treeUri, docId, if (relPrefix.isEmpty()) name else "$relPrefix/$name", onFile)
        }
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
    }.getOrNull()

    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
    }.getOrDefault(0L)
}
