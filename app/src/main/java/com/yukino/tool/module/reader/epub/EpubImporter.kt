package com.yukino.tool.module.reader.epub

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yukino.tool.module.reader.ReaderStore
import com.yukino.tool.module.reader.common.BookFormat
import com.yukino.tool.module.reader.common.ChapterIndex
import com.yukino.tool.module.reader.common.ImportResult
import com.yukino.tool.module.reader.common.Progress
import com.yukino.tool.module.reader.common.ReaderBook
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile

// EPUB 导入主流程(独立于 TxtImporter,产物同为 ReaderBook+章节偏移表):
//   uri → 临时文件 → zip4j 解压(加密/DRM 拒绝)→ container.xml → OPF(spine/manifest)
//   → 目录(ncx 或 nav)→ 逐 spine 文档提取纯文本 → 每章一个 UTF-8 文本文件
//   → 章区间在全书偏移轴上连续拼接(章间一个虚拟换行偏移)
// 导入后所有读取只碰章节文件,原始 uri 留作缓存被清时重新导入。
class EpubFormatException(msg: String) : Exception(msg)

class EpubDrmException : Exception("不支持加密(DRM)的 EPUB 书籍")

object EpubImporter {

    private const val MAX_BYTES = 200L * 1024 * 1024   // 解压前源文件上限
    private const val MAX_TITLE_LEN = 60

    // 导入。失败收敛为 ImportResult.Failed,不向调用方抛异常
    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        runCatching { doImport(context, uri) }.getOrElse {
            when (it) {
                is EpubFormatException, is EpubDrmException -> ImportResult.Failed(it.message ?: "EPUB 解析失败")
                else -> ImportResult.Failed("EPUB 导入失败: ${it.message ?: "未知错误"}")
            }
        }
    }

    // 内容缓存完整性检查 + 缺失时重新导入(进度保留,与 TxtImporter.ensureCache 同约定)。
    // 返回最新书目(null = 重导失败,由调用方提示)
    suspend fun ensureCache(context: Context, book: ReaderBook): ReaderBook? = withContext(Dispatchers.IO) {
        val n = book.chapters.size
        if (n > 0 && chapterFile(context, book.id, 0).let { it.exists() && it.length() > 0 } &&
            chapterFile(context, book.id, n - 1).let { it.exists() && it.length() > 0 }
        ) return@withContext book
        when (val r = import(context, Uri.parse(book.sourceUri))) {
            is ImportResult.Success -> r.book
            is ImportResult.Failed -> null
        }
    }

    fun chapterDir(context: Context, bookId: String): File =
        File(File(context.cacheDir, "reader/epub"), bookId)

    fun chapterFile(context: Context, bookId: String, index: Int): File =
        File(File(chapterDir(context, bookId), "chapters"), "ch_%04d.txt".format(index))

    private fun doImport(context: Context, uri: Uri): ImportResult.Success {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        // 1. 源文件落临时文件(zip4j 需要随机访问;同时校验大小)
        val temp = File(context.cacheDir, "reader/epub_src_${System.currentTimeMillis()}.zip")
        temp.parentFile?.mkdirs()
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            } ?: throw EpubFormatException("无法读取文件")
            if (temp.length() > MAX_BYTES) throw EpubFormatException("文件超过 200MB 上限")
            if (temp.length() == 0L) throw EpubFormatException("文件为空")

            // 2. 书目去重: 同 sourceUri 视为同一本书,刷新章节,进度保留
            val books = ReaderStore.loadBooks(context)
            val existing = books.firstOrNull { it.sourceUri == uri.toString() }
            val id = existing?.id ?: UUID.randomUUID().toString()
            val dir = chapterDir(context, id)
            dir.deleteRecursively()
            dir.mkdirs()

            // 3. 解压(加密 zip 直接拒绝;encryption.xml = DRM)
            val zip = ZipFile(temp)
            if (zip.isEncrypted) throw EpubDrmException()
            zip.extractAll(dir.absolutePath)
            val encryption = findFileRecursively(dir, "encryption.xml")
            if (encryption != null) throw EpubDrmException()

            // 4. container.xml → OPF
            val container = findFileRecursively(dir, "container.xml")
                ?: throw EpubFormatException("缺少 META-INF/container.xml,不是有效的 EPUB")
            val opfPath = parseContainerXml(container.readText())
            val opfFile = File(dir, opfPath)
            if (!opfFile.exists()) throw EpubFormatException("OPF 文件缺失: $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val pkg = parseOpf(opfFile.readText(), opfDir)

            // 5. 目录: NCX(EPUB2)优先,缺则 Nav Doc(EPUB3);路径 → 标题
            val tocMap = HashMap<String, String>()
            val ncxHref = pkg.ncxId?.let { pkg.items[it]?.href }
                ?: pkg.items.values.firstOrNull { it.mediaType == MEDIA_TYPE_NCX }?.href
            if (ncxHref != null) {
                val ncxFile = File(dir, resolveHref(opfDir, percentDecode(stripFragment(ncxHref).first)))
                if (ncxFile.exists()) {
                    parseNcx(ncxFile.readText(), ncxFile.parentFile?.toRelativeString(dir)?.let { if (it == "." ) "" else it } ?: "")
                        .forEach { e -> tocMap.putIfAbsent(e.path, e.title) }
                }
            }
            if (tocMap.isEmpty()) {
                val navItem = pkg.items.values.firstOrNull { it.hasProperty("nav") }
                if (navItem != null) {
                    val navFile = File(dir, resolveHref(opfDir, percentDecode(stripFragment(navItem.href).first)))
                    if (navFile.exists()) {
                        parseNav(navFile.readText(), navFile.parentFile?.toRelativeString(dir)?.let { if (it == ".") "" else it } ?: "")
                            .forEach { e -> tocMap.putIfAbsent(e.path, e.title) }
                    }
                }
            }

            // 6. 逐 spine 文档提取正文(非线性文档与资源文档跳过;宽容缺档)
            val chapters = ArrayList<ChapterIndex>()
            var offset = 0L
            for (ref in pkg.spine) {
                val item = pkg.items[ref.idref] ?: continue
                if (!ref.linear) continue
                if (item.mediaType.isNotBlank() && item.mediaType != MEDIA_TYPE_XHTML &&
                    item.mediaType != "text/html" && !item.href.endsWith(".xhtml", true) &&
                    !item.href.endsWith(".html", true) && !item.href.endsWith(".htm", true)
                ) continue
                val docFile = File(dir, resolveHref(opfDir, percentDecode(stripFragment(item.href).first)))
                if (!docFile.exists()) continue
                val paragraphs = HtmlTextExtractor.extract(docFile)
                if (paragraphs.isEmpty()) continue

                // 目录键与 NCX/Nav 条目同一约定: 相对 zip 根的解码路径
                val key = resolveHref(opfDir, percentDecode(stripFragment(item.href).first))
                val fallbackTitle = docFile.nameWithoutExtension.ifBlank { "未命名" }
                val title = (tocMap[key] ?: fallbackTitle).take(MAX_TITLE_LEN)
                val body = dedupeLeadingTitle(paragraphs, title).joinToString("\n")

                val f = chapterFile(context, id, chapters.size)
                f.parentFile?.mkdirs()
                f.writeText(body)
                chapters += ChapterIndex(title, offset)
                offset += body.length + 1L   // 章间一个虚拟换行偏移(章区间连续拼接)
            }
            if (chapters.isEmpty()) throw EpubFormatException("书中没有可读的文本章节")

            val title = pkg.title ?: resolveFileName(context, uri)
            val now = System.currentTimeMillis()
            val book = ReaderBook(
                id = id,
                title = title,
                sourceUri = uri.toString(),
                cachePath = dir.absolutePath,
                encoding = "UTF-8",
                totalChars = offset - 1,
                chapters = chapters,
                addedAt = existing?.addedAt ?: now,
                lastReadAt = existing?.lastReadAt ?: now,
                progress = existing?.progress ?: Progress(),
                fileSize = temp.length(),
                format = BookFormat.EPUB,
                author = pkg.author,
                coverPath = null
            )
            books.removeAll { it.id == id }
            books.add(book)
            ReaderStore.saveBooks(context, books)
            return ImportResult.Success(book)
        } finally {
            temp.delete()
        }
    }

    // 正文首段与章名相同的剥掉(EPUB 正文常自带 <h1> 标题,与合成大标题/页眉重复)。
    // 版式层的 stripLeadingTitle 只认"整行等于章名",这里放宽到互相包含的短标题
    private fun dedupeLeadingTitle(paragraphs: List<String>, title: String): List<String> {
        val t = title.trim()
        if (t.isEmpty() || paragraphs.isEmpty()) return paragraphs
        val first = paragraphs.first().trim()
        if (first == t || (first.length <= 30 && (t.contains(first) || first.contains(t)))) {
            return paragraphs.drop(1)
        }
        return paragraphs
    }

    // 解压目录内按文件名(不含目录)查找,首匹配
    private fun findFileRecursively(dir: File, name: String): File? {
        if (!dir.exists()) return null
        dir.walkTopDown().forEach { f -> if (f.isFile && f.name.equals(name, ignoreCase = true)) return f }
        return null
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        }.getOrNull() ?: uri.lastPathSegment ?: "未命名"
        return name.substringBeforeLast('.').ifBlank { "未命名" }
    }
}
