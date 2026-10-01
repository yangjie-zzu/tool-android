package com.yukino.tool.module.reader

import android.content.Context
import android.net.Uri
import com.yukino.tool.module.reader.common.ReaderBook
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.universalchardet.UniversalDetector

// TXT 懒初始化: 登记态的书(ready=false)在首次打开时执行
//   内容落盘(编码检测 → 规范化 CRLF/BOM → UTF-8 转存缓存) + 章节解析 + 回填落库。
// 缓存被系统清理时同一函数重新转存(进度保留)。
object TxtImporter {

    private const val MAX_BYTES = 100L * 1024 * 1024
    private const val DETECT_BUF = 8 * 1024

    fun cacheFile(context: Context, bookId: String): File =
        File(File(context.cacheDir, "reader/books"), "$bookId.txt")

    // 初始化到可读状态。返回更新后的书;失败抛 BookInitException(由 BookContents 收敛为文案)
    suspend fun ensureReady(
        context: Context,
        book: ReaderBook,
        onStage: (String) -> Unit = {}
    ): ReaderBook = withContext(Dispatchers.IO) {
        val cache = File(book.cachePath)
        val cacheOk = cache.exists() && cache.length() > 0
        if (book.ready && cacheOk) return@withContext book
        if (!cacheOk) {
            onStage("准备内容中…")
            transcode(context, Uri.parse(book.sourceUri), cache)
        }
        onStage("解析章节中…")
        val text = withContext(Dispatchers.Default) { cache.readText() }
        val chapters = withContext(Dispatchers.Default) { ChapterSplitter.split(text, book.title) }
        val updated = book.copy(
            chapters = chapters,
            totalChars = text.length.toLong(),
            ready = true
        )
        ReaderStore.upsertBook(context, updated)
        updated
    }

    // 读源文件: 边读边探测编码,转存规范化后的 UTF-8 文本,返回检测到的编码名
    private fun transcode(context: Context, uri: Uri, dest: File): String {
        val resolver = context.contentResolver
        dest.parentFile?.mkdirs()
        resolver.openInputStream(uri)?.use { input ->
            val detector = UniversalDetector(null)
            val buf = ByteArray(DETECT_BUF)
            val out = ArrayList<ByteArray>()
            var total = 0L
            var detected: String? = null
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw BookInitException("文件超过 100MB 上限")
                out.add(buf.copyOf(n))
                if (detected == null) {
                    detector.handleData(buf, 0, n)
                    detected = detector.detectedCharset
                    if (detected != null) detector.dataEnd()
                }
            }
            detector.dataEnd()
            detector.reset()

            val charset = runCatching { Charset.forName(detected ?: "UTF-8") }.getOrElse { Charsets.UTF_8 }
            val all = mergeChunks(out, total)
            val text = String(all, charset)
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            if (text.isBlank()) throw BookInitException("文件为空或无法解码")
            dest.writeText(text)
            return detected ?: "UTF-8"
        } ?: throw BookInitException("无法读取文件")
    }

    private fun mergeChunks(chunks: List<ByteArray>, total: Long): ByteArray {
        val all = ByteArray(total.toInt())
        var pos = 0
        chunks.forEach { c -> c.copyInto(all, pos); pos += c.size }
        return all
    }
}
