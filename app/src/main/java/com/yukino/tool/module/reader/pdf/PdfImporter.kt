package com.yukino.tool.module.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import com.yukino.tool.module.reader.BookInitException
import com.yukino.tool.module.reader.ReaderStore
import com.yukino.tool.module.reader.common.BookFormat
import com.yukino.tool.module.reader.common.ChapterIndex
import com.yukino.tool.module.reader.common.ReaderBook
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// PDF 懒初始化: 登记态的书(ready=false)首次打开时执行
//   源文件转存缓存 → 页数/封面 → 书签树解析(纯 Kotlin,失败降级为空目录) → 回填落库。
// 缓存被清理时同一函数重新转存(进度保留)。
// 坐标约定: chapters[].startChar 存 0 基页码;totalChars 存总页数(百分比分母)。
object PdfImporter {

    private const val MAX_BYTES = 300L * 1024 * 1024
    private const val COVER_WIDTH = 600   // 封面缩略图宽(px)

    fun cacheFile(context: Context, bookId: String): File =
        File(File(context.cacheDir, "reader/books"), "$bookId.pdf")

    fun coverFile(context: Context, bookId: String): File =
        File(File(context.cacheDir, "reader/books"), "$bookId.cover.png")

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
            copySource(context, Uri.parse(book.sourceUri), cache)
        }
        onStage("解析目录中…")
        val pageCount = try {
            openRenderer(cache).use { it.pageCount }
        } catch (e: SecurityException) {
            throw BookInitException("加密 PDF 暂不支持")
        } catch (e: Exception) {
            throw BookInitException("不是有效的 PDF")
        }
        val chapters = withContext(Dispatchers.Default) {
            PdfOutlineParser.parse(cache.readBytes(), pageCount)
                .map { ChapterIndex(it.title, it.page.toLong(), level = it.level) }
        }
        onStage("生成封面…")
        val cover = coverFile(context, book.id)
        runCatching { renderCover(cache, cover) }
        val updated = book.copy(
            chapters = chapters,
            totalChars = pageCount.toLong(),
            ready = true,
            coverPath = if (cover.exists()) cover.absolutePath else null
        )
        ReaderStore.upsertBook(context, updated)
        updated
    }

    fun openRenderer(file: File): PdfRenderer =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

    // 渲染某页为指定位图宽的页面位图(等比,白底——透明背景的 PDF 页不透出黑块)
    fun renderPage(renderer: PdfRenderer, index: Int, bitmapWidth: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val scale = bitmapWidth.toFloat() / page.width
            val w = bitmapWidth.coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    private fun renderCover(file: File, dest: File) {
        openRenderer(file).use { r ->
            if (r.pageCount <= 0) return
            val bmp = renderPage(r, 0, COVER_WIDTH)
            dest.parentFile?.mkdirs()
            dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
        }
    }

    private fun copySource(context: Context, uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BookInitException("无法读取文件")
        input.use { ins ->
            var total = 0L
            dest.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw BookInitException("文件超过 300MB 上限")
                    out.write(buf, 0, n)
                }
            }
            if (total == 0L) throw BookInitException("文件为空")
        }
    }
}
