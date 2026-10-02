package com.yukino.tool.module.reader.epub

import android.content.Context
import android.net.Uri
import com.yukino.tool.module.reader.BookInitException
import com.yukino.tool.module.reader.ReaderStore
import com.yukino.tool.module.reader.common.ChapterIndex
import com.yukino.tool.module.reader.common.Paragraph
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

    // 初始化到可读状态。返回更新后的书;失败抛 BookInitException(由 BookContents 收敛为文案)。
    // 二期升级: ready 且章文件在但为一期纯文本格式时,自动重新提取获得富文本/图片/封面,
    // 进度按"章级对齐+章内比例"迁移(见 migrateProgress);升级在临时目录完成,失败保持老格式可读
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
        if (book.ready && filesOk) {
            if (!ChapterFileCodec.isLegacyFormat(chapterFile(context, book.id, 0))) return@withContext book
            onStage("升级书籍内容中…")
            return@withContext upgrade(context, book, dir, onStage)
        }
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

    // 老书升级: 在 dir/upgrade_tmp 完成解压提取(不碰旧章文件),成功后原子替换 chapters/
    // 并把封面搬运到书目录根,进度迁移后统一落库;任何一步失败删临时目录返回原书(下次再试)。
    // 中途崩溃的最坏情况(章目录已换/未换)都有既有自愈路径: 章文件缺失触发 filesOk 完整重建
    private suspend fun upgrade(
        context: Context,
        book: ReaderBook,
        dir: File,
        onStage: (String) -> Unit
    ): ReaderBook = withContext(Dispatchers.IO) {
        val tmp = File(dir, "upgrade_tmp")
        runCatching {
            tmp.deleteRecursively()
            val updated = doInit(context, book, tmp, onStage, persist = false)

            // 进度迁移: 章级对齐 + 章内按新旧章长比例缩放(投影因列表前缀/图片占位变长)
            val migrated = migrateProgress(
                book.chapters, book.totalChars, book.progress.globalCharOffset,
                updated.chapters, updated.totalChars
            )
            val percent = if (updated.totalChars > 0) {
                (migrated.toDouble() / updated.totalChars).coerceIn(0.0, 1.0)
            } else 0.0

            // 封面在 tmp 解压内容里,搬运到书目录根(其余解压内容随后删除)
            val coverFixed = updated.coverPath?.let { cp ->
                val src = File(cp)
                if (src.exists() && src.absolutePath.startsWith(tmp.absolutePath)) {
                    val ext = src.extension
                    val dst = File(dir, if (ext.isNotBlank()) "cover.$ext" else "cover")
                    src.copyTo(dst, overwrite = true)
                    dst.absolutePath
                } else cp
            }
            val final = updated.copy(
                cachePath = dir.absolutePath,
                coverPath = coverFixed,
                progress = book.progress.copy(globalCharOffset = migrated, percent = percent)
            )

            // 替换章文件: 旧 chapters 让位(rename 到 bak,失败兜底直删)→ 新 chapters 就位
            val oldChapters = File(dir, "chapters")
            val bak = File(dir, "chapters_bak")
            bak.deleteRecursively()
            if (!oldChapters.renameTo(bak)) oldChapters.deleteRecursively()
            if (!File(tmp, "chapters").renameTo(oldChapters)) {
                throw BookInitException("升级写入失败")   // chapters 未就位: bak 还在,删 tmp 后下次重试
            }
            ReaderStore.upsertBook(context, final)
            tmp.deleteRecursively()
            bak.deleteRecursively()
            final
        }.getOrElse {
            tmp.deleteRecursively()
            book   // 保持老格式可读(旧章文件未动),下次打开再试升级
        }
    }

    // 旧全书偏移 → 新全书偏移(章区间因富文本投影变化): 旧章经标题映射定位新章,
    // 章内偏移按"旧章长 ∶ 新章长"等比缩放。纯函数,单测覆盖
    internal fun migrateProgress(
        oldChapters: List<ChapterIndex>,
        oldTotal: Long,
        oldOffset: Long,
        newChapters: List<ChapterIndex>,
        newTotal: Long
    ): Long {
        if (oldChapters.isEmpty() || newChapters.isEmpty()) return 0L
        if (oldOffset <= 0L || oldTotal <= 0L) return 0L
        var lo = 0
        var hi = oldChapters.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (oldChapters[mid].startChar <= oldOffset) lo = mid else hi = mid - 1
        }
        val oldIdx = lo
        val inOld = (oldOffset - oldChapters[oldIdx].startChar).coerceAtLeast(0L)
        val newIdx = mapChapters(oldChapters, newChapters)[oldIdx].coerceIn(0, newChapters.lastIndex)
        val oldLen = chapterLen(oldChapters, oldIdx, oldTotal)
        val newLen = chapterLen(newChapters, newIdx, newTotal)
        val inNew = if (oldLen > 0) (inOld.toDouble() / oldLen * newLen).toLong() else 0L
        return (newChapters[newIdx].startChar + inNew.coerceAtMost(newLen)).coerceIn(0L, newTotal)
    }

    // 旧→新章号映射(顺序保持的标题匹配): 二期升级只会增章(一期跳过的图片页登记为章),
    // 不会减章/重排,旧章序列是新章序列的子序列。双指针按标题顺序匹配;
    // 未匹配的旧章(标题被 NCX 改动等)在前后匹配锚点间线性插值,无锚点退回序号
    internal fun mapChapters(
        oldChapters: List<ChapterIndex>,
        newChapters: List<ChapterIndex>
    ): IntArray {
        val map = IntArray(oldChapters.size) { -1 }
        var j = 0
        for (i in oldChapters.indices) {
            var k = j
            while (k < newChapters.size) {
                if (newChapters[k].title == oldChapters[i].title) { map[i] = k; j = k + 1; break }
                k++
            }
        }
        var prevIdx = -1
        var prevMap = -1
        for (i in oldChapters.indices) {
            if (map[i] >= 0) {
                if (prevMap < 0 && map[i] > 0) {
                    // 首个锚点之前未匹配的旧章: 平铺到 [0, 首锚点) 区间
                    for (k in 0 until i) map[k] = map[i] * (k + 1) / i
                }
                prevIdx = i; prevMap = map[i]
                continue
            }
            var nextIdx = -1
            var nextMap = -1
            for (k in i + 1 until oldChapters.size) {
                if (map[k] >= 0) { nextIdx = k; nextMap = map[k]; break }
            }
            map[i] = when {
                prevMap >= 0 && nextMap >= 0 ->
                    prevMap + (nextMap - prevMap) * (i - prevIdx) / (nextIdx - prevIdx)
                prevMap >= 0 -> prevMap
                nextMap >= 0 -> nextMap
                else -> i.coerceAtMost(newChapters.lastIndex)
            }
        }
        return map
    }

    // 章正文长度(不含章间一个虚拟换行): 末章 start + len = total
    private fun chapterLen(chapters: List<ChapterIndex>, index: Int, total: Long): Long {
        val start = chapters[index].startChar
        val end = if (index + 1 < chapters.size) chapters[index + 1].startChar else total + 1
        return (end - start - 1).coerceAtLeast(0L)
    }

    // outDir: 解压与章文件输出目录(普通初始化=书目录;升级=临时目录,成功后由 upgrade 替换)。
    // persist=false 只返回结果不落库(升级路径统一在替换完成后落库,防中途态污染 DB)
    private suspend fun doInit(
        context: Context,
        book: ReaderBook,
        outDir: File,
        onStage: (String) -> Unit,
        persist: Boolean = true
    ): ReaderBook =
        withContext(Dispatchers.Default) {
            val resolver = context.contentResolver
            fun chapterOutFile(index: Int): File = File(File(outDir, "chapters"), "ch_%04d.txt".format(index))

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
                zip.extractAll(outDir.absolutePath)
                val encryption = findFileRecursively(outDir, "encryption.xml")
                if (encryption != null) throw EpubDrmException()

                // 3. container.xml → OPF
                val container = findFileRecursively(outDir, "container.xml")
                    ?: throw EpubFormatException("缺少 META-INF/container.xml,不是有效的 EPUB")
                val opfPath = parseContainerXml(container.readText())
                val opfFile = File(outDir, opfPath)
                if (!opfFile.exists()) throw EpubFormatException("OPF 文件缺失: $opfPath")
                val opfDir = opfPath.substringBeforeLast('/', "")
                val pkg = parseOpf(opfFile.readText(), opfDir)

                // 4. 目录: NCX(EPUB2)优先,缺则 Nav Doc(EPUB3);路径 → 标题
                onStage("解析章节中…")
                val tocMap = HashMap<String, String>()
                val ncxHref = pkg.ncxId?.let { pkg.items[it]?.href }
                    ?: pkg.items.values.firstOrNull { it.mediaType == MEDIA_TYPE_NCX }?.href
                if (ncxHref != null) {
                    val ncxFile = File(outDir, resolveHref(opfDir, percentDecode(stripFragment(ncxHref).first)))
                    if (ncxFile.exists()) {
                        parseNcx(ncxFile.readText(), dirOf(ncxFile, outDir))
                            .forEach { e -> tocMap.putIfAbsent(e.path, e.title) }
                    }
                }
                if (tocMap.isEmpty()) {
                    val navItem = pkg.items.values.firstOrNull { it.hasProperty("nav") }
                    if (navItem != null) {
                        val navFile = File(outDir, resolveHref(opfDir, percentDecode(stripFragment(navItem.href).first)))
                        if (navFile.exists()) {
                            parseNav(navFile.readText(), dirOf(navFile, outDir))
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
                    val docFile = File(outDir, resolveHref(opfDir, percentDecode(stripFragment(item.href).first)))
                    if (!docFile.exists()) continue
                    val key = resolveHref(opfDir, percentDecode(stripFragment(item.href).first))
                    val paragraphs = HtmlTextExtractor.extract(docFile, key.substringBeforeLast('/', ""))
                    if (paragraphs.isEmpty()) continue

                    // 目录键与 NCX/Nav 条目同一约定: 相对 zip 根的解码路径
                    val fallbackTitle = docFile.nameWithoutExtension.ifBlank { "未命名" }
                    val title = (tocMap[key] ?: HtmlTextExtractor.firstHeading(docFile) ?: fallbackTitle)
                        .take(MAX_TITLE_LEN)
                    val paras = dedupeLeadingTitle(paragraphs, title)
                    // 纯图片页的目录标题常是文件名(如 "0.jpg"),显示为"插图"
                    val displayTitle = if (paras.all { it.isImage } &&
                        Regex("\\.(jpe?g|png|gif|webp)\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(title)
                    ) "插图" else title

                    val f = chapterOutFile(chapters.size)
                    f.parentFile?.mkdirs()
                    ChapterFileCodec.write(f, paras)
                    // 投影长度 = 各段 text 之和 + 段间换行(与 bodyText joinToString 同构)
                    val bodyLen = paras.sumOf { it.text.length.toLong() } + (paras.size - 1)
                    chapters += ChapterIndex(displayTitle, offset)
                    offset += bodyLen + 1L   // 章间一个虚拟换行偏移(章区间连续拼接)
                }
                if (chapters.isEmpty()) throw EpubFormatException("书中没有可读的文本章节")

                // 封面: OPF 探测到的 href → 解压目录内文件(已在磁盘上,直接记录路径)
                val coverFile = pkg.coverHref?.let {
                    File(outDir, resolveHref(opfDir, percentDecode(stripFragment(it).first)))
                }?.takeIf { it.exists() && it.length() > 0 }

                val updated = book.copy(
                    title = pkg.title?.take(MAX_TITLE_LEN) ?: book.title,
                    author = pkg.author ?: book.author,
                    cachePath = outDir.absolutePath,
                    encoding = "UTF-8",
                    totalChars = offset - 1,
                    chapters = chapters,
                    fileSize = if (book.fileSize > 0) book.fileSize else temp.length(),
                    coverPath = coverFile?.absolutePath,
                    ready = true
                )
                if (persist) ReaderStore.upsertBook(context, updated)
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
    private fun dedupeLeadingTitle(paragraphs: List<Paragraph>, title: String): List<Paragraph> {
        val t = title.trim()
        if (t.isEmpty() || paragraphs.isEmpty()) return paragraphs
        val first = paragraphs.first().text.trim()
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
