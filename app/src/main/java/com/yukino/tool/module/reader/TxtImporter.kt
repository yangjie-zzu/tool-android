package com.yukino.tool.module.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.universalchardet.UniversalDetector

// 导入: SAF uri → 编码检测 → 规范化(CRLF/BOM) → UTF-8 转存缓存 → 章节索引 → books.json
// 之后所有读取只碰缓存文件,原始 uri 仅留作缓存被清时的重新转存
object TxtImporter {

    private const val MAX_BYTES = 100L * 1024 * 1024
    private const val DETECT_BUF = 8 * 1024

    sealed interface ImportResult {
        data class Success(val book: ReaderBook) : ImportResult
        data class Failed(val reason: String) : ImportResult
    }

    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        runCatching { doImport(context, uri) }.getOrElse {
            ImportResult.Failed(it.message ?: "导入失败")
        }
    }

    private suspend fun doImport(context: Context, uri: Uri): ImportResult {
        val resolver = context.contentResolver
        // 保住 uri 重访权(缓存被清后重新转存用)
        runCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        // 1. 读字节 + 边读边探测编码
        val (data, encodingName) = resolver.openInputStream(uri)?.use { input ->
            val detector = UniversalDetector(null)
            val buf = ByteArray(DETECT_BUF)
            val out = ArrayList<ByteArray>()
            var total = 0L
            var detected: String? = null
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return ImportResult.Failed("文件超过 100MB 上限")
                out.add(buf.copyOf(n))
                if (detected == null) {
                    detector.handleData(buf, 0, n)
                    detected = detector.detectedCharset
                    if (detected != null) detector.dataEnd()
                }
            }
            detector.dataEnd()
            detector.reset()
            Pair(mergeChunks(out, total), detected ?: "UTF-8")
        } ?: return ImportResult.Failed("无法读取文件")

        // 2. 解码 + 规范化: 剥 BOM、CRLF/CR → LF(偏移与章节索引都基于规范化后的文本)
        val charset = runCatching { Charset.forName(encodingName) }.getOrElse { Charsets.UTF_8 }
        val text = String(data, charset)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (text.isBlank()) return ImportResult.Failed("文件为空或无法解码")

        val title = resolveTitle(context, uri)
        val now = System.currentTimeMillis()

        // 3. 去重: 同 sourceUri 视为同一本书,刷新缓存/章节,进度保留
        val books = ReaderStore.loadBooks(context)
        val existing = books.firstOrNull { it.sourceUri == uri.toString() }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val cacheFile = cacheFile(context, id).apply { parentFile?.mkdirs() }
        cacheFile.writeText(text)

        // 章节切分是纯 CPU 活(逐行正则),挪到 Default,不占 IO 工作线程
        val chapters = withContext(Dispatchers.Default) { ChapterSplitter.split(text, title) }
        val book = ReaderBook(
            id = id,
            title = title,
            sourceUri = uri.toString(),
            cachePath = cacheFile.absolutePath,
            encoding = encodingName,
            totalChars = text.length.toLong(),
            chapters = chapters,
            addedAt = existing?.addedAt ?: now,
            lastReadAt = existing?.lastReadAt ?: now,
            progress = existing?.progress ?: Progress(),
            fileSize = cacheFile.length()
        )
        books.removeAll { it.id == id }
        books.add(book)
        ReaderStore.saveBooks(context, books)
        return ImportResult.Success(book)
    }

    fun cacheFile(context: Context, bookId: String): File =
        File(File(context.cacheDir, "reader/books"), "$bookId.txt")

    // 缓存被系统清理后,用 sourceUri 重新转存(重新做编码检测),进度保留
    suspend fun ensureCache(context: Context, book: ReaderBook): ReaderBook = withContext(Dispatchers.IO) {
        val f = File(book.cachePath)
        if (f.exists() && f.length() > 0) return@withContext book
        val fresh = cacheFile(context, book.id)
        if (fresh.exists() && fresh.length() > 0 && fresh.absolutePath == book.cachePath) {
            return@withContext book
        }
        val result = import(context, Uri.parse(book.sourceUri))
        if (result is ImportResult.Success) result.book else book // 重转存失败则原样返回,由调用方报错
    }

    private fun mergeChunks(chunks: List<ByteArray>, total: Long): ByteArray {
        val all = ByteArray(total.toInt())
        var pos = 0
        chunks.forEach { c -> c.copyInto(all, pos); pos += c.size }
        return all
    }

    private fun resolveTitle(context: Context, uri: Uri): String {
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
