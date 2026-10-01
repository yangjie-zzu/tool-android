package com.yukino.tool.module.reader

import android.content.Context
import com.yukino.tool.module.reader.common.BookContent
import com.yukino.tool.module.reader.common.BookFormat
import com.yukino.tool.module.reader.common.ReaderBook
import com.yukino.tool.module.reader.common.TxtBookContent
import com.yukino.tool.module.reader.epub.EpubBookContent
import com.yukino.tool.module.reader.epub.EpubDrmException
import com.yukino.tool.module.reader.epub.EpubFormatException
import com.yukino.tool.module.reader.epub.EpubImporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 打开书失败的用户文案(登记态初始化失败/缓存缺失重转存失败)
class BookInitException(message: String) : Exception(message)

// 内容加载分派: 按书格式进入各自主流程的懒初始化(登记态 → 内容落盘+章节解析),
// 之后汇合成统一的 BookContent, 阅读页只消费公共接口。
// onStage 上报初始化阶段文案(供 loading 展示);失败抛 BookInitException(带用户文案)
object BookContents {

    // 返回 (内容源, 初始化后的书目)。登记态书初始化后字段被回填(ready/chapters/书名等),
    // 调用方必须把刷新后的书同步回内存态, 否则后续进度落盘会用旧对象覆盖初始化结果
    suspend fun load(
        context: Context,
        book: ReaderBook,
        onStage: (String) -> Unit = {}
    ): Pair<BookContent, ReaderBook> = withContext(Dispatchers.IO) {
        try {
            when (book.format) {
                BookFormat.EPUB -> {
                    val refreshed = EpubImporter.ensureReady(context, book, onStage)
                    Pair(EpubBookContent(refreshed, EpubImporter.chapterDir(context, refreshed.id)), refreshed)
                }
                else -> {
                    val refreshed = TxtImporter.ensureReady(context, book, onStage)
                    Pair(TxtBookContent(refreshed, File(refreshed.cachePath).readText()), refreshed)
                }
            }
        } catch (e: BookInitException) {
            throw e
        } catch (e: EpubDrmException) {
            throw BookInitException(e.message ?: "不支持加密书籍")
        } catch (e: EpubFormatException) {
            throw BookInitException(e.message ?: "不是有效的 EPUB")
        } catch (e: Exception) {
            throw BookInitException("打开失败: ${e.message ?: "未知错误"}")
        }
    }
}
