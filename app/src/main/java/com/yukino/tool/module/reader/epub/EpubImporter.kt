package com.yukino.tool.module.reader.epub

import android.content.Context
import android.net.Uri
import com.yukino.tool.module.reader.BookInitException
import com.yukino.tool.module.reader.ReaderStore
import com.yukino.tool.module.reader.common.ChapterIndex
import com.yukino.tool.module.reader.common.ReaderBook
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile

// EPUB 懒初始化: 登记态的书(ready=false)在首次打开时执行
//   解压校验(DRM 拒绝) → 目录/OPF 解析 → 逐 spine 提取纯文本 → 每章一个 UTF-8 文本
//   → 章区间在全书偏移轴上连续拼接(章间一个虚拟换行偏移) → 回填落库。
// 章节文件被系统清理时同一函数重新导出(进度保留)。
class EpubFormatException(msg: String) : Exception(msg)

class EpubDrmException : Exception("不支持加密(DRM)的 EPUB 书籍")

object EpubImporter {

    private const val MAX_BYTES = 200L * 1024 * 1024   // 解压前源文件上限
    private const val MAX_TITLE_LEN = 60

    fun chapterDir(context: Context, bookId: String): File =
        File(File(context.cacheDir, "reader/epub"), bookId)

    fun chapterFile(context: Context, bookId: String, index: Int): File =
        File(File(chapterDir(context, bookId), "chapters"), "ch_%04d.txt".format(index))

    // 初始化到可读状态。返回更新后的书;失败抛 BookInitException(由 BookContents 收敛为文案)
    suspend fun ensureReady(
        context: Context,
        book: ReaderBook,
        onStage: (String) -> Unit = {}
    ): ReaderBook = withContext(Dispatchers.IO) {
        val dir = chapterDir(context, book.id)
        val n = book.chapters.size
        val filesOk = n > 0 &&
            chapterFile(context, book.id, 0).let { it.exists() && it.length() > 0 } &&
            chapterFile(context, book.id, n - 1).let { it.exists() && it.length() > 0 }
        if (book.ready && filesOk) return@withContext book
        if (!filesOk) {
            onStage("准备内容中…")
            dir.deleteRecursively()
            dir.mkdirs()
            doInit(context, book, dir, onStage)
        } else {
            onStage("解析章节中…")
            book
        }
    }

    private suspend fun doInit(context: Context, book: ReaderBook, dir: File, onStage: (String) -> Unit): ReaderBook =
        withContext(Dispatchers.Default) {
            val resolver = context.contentResolver

            // 1. 源文件落临时文件(zip4j 需要随机访问;同时校验大小)
            val temp = File(context.cacheDir, "reader/epub_src_${System.currentTimeMillis()}.zip")
            temp.parentFile?.mkdirs()
            try {
                resolver.openInputStream(Uri.parse(book.sourceUri))?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
                } ?: throw BookInitException("无法读取文件")
                if (temp.length() > MAX_BYTES) throw EpubFormatException("文件超过 200MB 上限")
                if (temp.length() == 0L) throw EpubFormatException("文件为空")

                // 2. 解压(加密 zip 直接拒绝;encryption.xml = DRM)
                val zip = ZipFile(temp)
                if (zip.isEncrypted) throw EpubDrmException()
                zip.extractAll(dir.absolutePath)
                val encryption = findFileRecursively(dir, "encryption.xml")
                if (encryption != null) throw EpubDrmException()

                // 3. container.xml → OPF
                val container = findFileRecursively(dir, "container.xml")
                    ?: throw EpubFormatException("缺少 META-INF/container.xml,不是有效的 EPUB")
                val opfPath = parseContainerXml(container.readText())
                val opfFile = File(dir, opfPath)
                if (!opfFile.exists()) throw EpubFormatException("OPF 文件缺失: $opfPath")
                val opfDir = opfPath.substringBeforeLast('/', "")
                val pkg = parseOpf(opfFile.readText(), opfDir)

                // 4. 目录: NCX(EPUB2)优先,缺则 Nav Doc(EPUB3);路径 → 标题
                onStage("解析章节中…")
                val tocMap = HashMap<String, String>()
                val ncxHref = pkg.ncxId?.let { pkg.items[it]?.href }
                    ?: pkg.items.values.firstOrNull { it.mediaType == MEDIA_TYPE_NCX }?.href
                if (ncxHref != null) {
                    val ncxFile = File(dir, resolveHref(opfDir, percentDecode(stripFragment(ncxHref).first)))
                    if (ncxFile.exists()) {
                        parseNcx(ncxFile.readText(), dirOf(ncxFile, dir))
                            .forEach { e -> tocMap.putIfAbsent(e.path, e.title) }
                    }
                }
                if (tocMap.isEmpty()) {
                    val navItem = pkg.items.values.firstOrNull { it.hasProperty("nav") }
                    if (navItem != null) {
                        val navFile = File(dir, resolveHref(opfDir, percentDecode(stripFragment(navItem.href).first)))
                        if (navFile.exists()) {
                            parseNav(navFile.readText(), dirOf(navFile, dir))
                                .forEach { e -> tocMap.putIfAbsent(e.path, e.title) }
                        }
                    }
                }

                // 5. 逐 spine 文档提取正文(非线性文档与资源文档跳过;宽容缺档)
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
                    val title = (tocMap[key] ?: HtmlTextExtractor.firstHeading(docFile) ?: fallbackTitle)
                        .take(MAX_TITLE_LEN)
                    val body = dedupeLeadingTitle(paragraphs, title).joinToString("\n")

                    val f = chapterFile(context, book.id, chapters.size)
                    f.parentFile?.mkdirs()
                    f.writeText(body)
                    chapters += ChapterIndex(title, offset)
                    offset += body.length + 1L   // 章间一个虚拟换行偏移(章区间连续拼接)
                }
                if (chapters.isEmpty()) throw EpubFormatException("书中没有可读的文本章节")

                val updated = book.copy(
                    title = pkg.title?.take(MAX_TITLE_LEN) ?: book.title,
                    author = pkg.author ?: book.author,
                    cachePath = dir.absolutePath,
                    encoding = "UTF-8",
                    totalChars = offset - 1,
                    chapters = chapters,
                    fileSize = if (book.fileSize > 0) book.fileSize else temp.length(),
                    ready = true
                )
                ReaderStore.upsertBook(context, updated)
                updated
            } finally {
                temp.delete()
            }
        }

    // 文件所在目录相对解压根的路径(""/"OEBPS"/"OEBPS/txt"),作为 NCX/Nav href 的解析基准
    private fun dirOf(f: File, root: File): String =
        f.parentFile?.toRelativeString(root)?.replace('\\', '/')?.let { if (it == ".") "" else it } ?: ""

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
}
