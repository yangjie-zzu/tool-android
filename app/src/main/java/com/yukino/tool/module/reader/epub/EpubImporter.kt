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

    // 装饰章快照位图(离屏 WebView 渲染原书 xhtml 落盘的 WebP): 书目录下按代目子目录命名。
    // v2 = 自适应 CSS(border-box+max-width)与 JS 就绪探测的渲染语义, 旧代随渲染语义变更废弃
    private const val DECO_DIR = "deco_v2"

    // data URI 图片落盘子目录(相对解压根)
    private const val DATAURI_DIR = "datauri"

    fun decoFileOf(chapterDir: File, index: Int): File =
        File(File(chapterDir, DECO_DIR), "ch_%04d.webp".format(index))

    fun decoFile(context: Context, bookId: String, index: Int): File =
        decoFileOf(chapterDir(context, bookId), index)

    // data URI 图片落盘: base64 解码写入解压根 datauri/(文件名 = 载荷 MD5,幂等),
    // 返回相对解压根 ref;不认识的 mime/解码失败/写盘失败返回空串(该图忽略)
    private fun dataUriSinkOf(root: File): HtmlTextExtractor.DataUriSink {
        val dir = File(root, DATAURI_DIR)
        return HtmlTextExtractor.DataUriSink { mime, payload ->
            val ext = when (mime.substringBefore(';')) {
                "image/png" -> "png"
                "image/jpeg", "image/jpg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/svg+xml" -> "svg"
                else -> return@DataUriSink ""
            }
            val bytes = runCatching { android.util.Base64.decode(payload, android.util.Base64.DEFAULT) }
                .getOrNull() ?: return@DataUriSink ""
            runCatching {
                dir.mkdirs()
                val name = md5Hex(payload) + "." + ext
                val f = File(dir, name)
                if (!f.exists() || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
                "$DATAURI_DIR/$name"
            }.getOrDefault("")
        }
    }

    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // 装饰章判定: 章首段带装饰盒(boxStyle)的章为 CSS 排版页(扉页/封面等),
    // 阅读器排版引擎只做近似,这类章由 WebView 按原书样式呈现
    fun isDecorativeChapter(context: Context, bookId: String, index: Int): Boolean {
        val cf = chapterFile(context, bookId, index)
        if (!cf.exists()) return false
        val rr = runCatching { ChapterFileCodec.read(cf) }.getOrNull() ?: return false
        return rr.paragraphs.firstOrNull()?.boxStyle != null
    }

    // 反查装饰章的源 xhtml 文档与解压根目录(WebView 加载与 css 相对引用都用):
    // 遍历 OPF spine 的 xhtml 文档,以章首段文本与文档内容匹配(h2 拆章/多文档均适用)。
    // 非装饰章返回 null
    fun findDecorativeSourceDoc(
        context: Context,
        bookId: String,
        index: Int
    ): Pair<File, File>? {
        val cf = chapterFile(context, bookId, index)
        if (!cf.exists()) return null
        val rr = runCatching { ChapterFileCodec.read(cf) }.getOrNull() ?: return null
        if (rr.paragraphs.firstOrNull()?.boxStyle == null) return null
        val head = rr.paragraphs.firstNotNullOfOrNull { p ->
            p.text.takeIf { it.isNotBlank() }
        }?.take(24) ?: return null
        val outDir = chapterDir(context, bookId)   // 解压根: META-INF/OEBPS 所在
        val opfFile = runCatching {
            File(outDir, parseContainerXml(File(outDir, "META-INF/container.xml").readText()))
        }.getOrNull() ?: return null
        val opfDir = opfFile.path.substringBeforeLast('/', "")
        val pkg = runCatching { parseOpf(opfFile.readText(), opfDir) }.getOrNull() ?: return null
        for (ref in pkg.spine) {
            val item = pkg.items[ref.idref] ?: continue
            if (!ref.linear) continue
            if (item.mediaType.isNotBlank() && item.mediaType != MEDIA_TYPE_XHTML &&
                item.mediaType != "text/html" && !item.href.endsWith(".xhtml", true) &&
                !item.href.endsWith(".html", true) && !item.href.endsWith(".htm", true)
            ) continue
            // resolveHref 的结果规范化为绝对路径(相对路径基于进程 cwd 解析)
            val f = File(resolveHref(opfDir, percentDecode(stripFragment(item.href).first))).absoluteFile
            if (!f.exists()) continue
            val hit = runCatching {
                val docText = HtmlTextExtractor.extract(f, f.parent ?: "")
                    .paragraphs.joinToString("\n") { it.text }
                docText.contains(head)
            }.getOrDefault(false)
            if (hit) return f to outDir
        }
        return null
    }

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
            val first = chapterFile(context, book.id, 0)
            // 封面章剔除(封面去重)对存量书生效: 历史导入的首章仍为封面文档章时,
            // 走升级路径重建剔除(含进度迁移与装饰快照失效); 一次生效, 之后不再命中
            val coverLingers = runCatching {
                coverChapterLingers(first, book.coverPath, dir)
            }.getOrDefault(false)
            val tableLingers = runCatching {
                tableTitleLingers(context, book.id, book.chapters)
            }.getOrDefault(false)
            if (!ChapterFileCodec.needsUpgrade(first) && !coverLingers && !tableLingers) return@withContext book
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
            // 章序列可能变化(如封面文档去重使章号前移), 按章号缓存的装饰快照随之失效
            File(dir, "deco").deleteRecursively()
            File(dir, DECO_DIR).deleteRecursively()
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

                // 4. 目录: NCX(EPUB2)优先,缺则 Nav Doc(EPUB3);路径 → (标题, 锚点fragment)。
                // 同文档多条目录取首条(一期同约定),fragment 作章 anchorId(点击目录落锚点段落)
                onStage("解析章节中…")
                val tocMap = HashMap<String, Pair<String, String?>>()
                val ncxHref = pkg.ncxId?.let { pkg.items[it]?.href }
                    ?: pkg.items.values.firstOrNull { it.mediaType == MEDIA_TYPE_NCX }?.href
                if (ncxHref != null) {
                    val ncxFile = File(outDir, resolveHref(opfDir, percentDecode(stripFragment(ncxHref).first)))
                    if (ncxFile.exists()) {
                        parseNcx(ncxFile.readText(), dirOf(ncxFile, outDir))
                            .forEach { e -> tocMap.putIfAbsent(e.path, e.title to e.fragment) }
                    }
                }
                if (tocMap.isEmpty()) {
                    val navItem = pkg.items.values.firstOrNull { it.hasProperty("nav") }
                    if (navItem != null) {
                        val navFile = File(outDir, resolveHref(opfDir, percentDecode(stripFragment(navItem.href).first)))
                        if (navFile.exists()) {
                            parseNav(navFile.readText(), dirOf(navFile, outDir))
                                .forEach { e -> tocMap.putIfAbsent(e.path, e.title to e.fragment) }
                        }
                    }
                }

                // 5. 逐 spine 文档提取正文(非线性文档与资源文档跳过;宽容缺档)
                val chapters = ArrayList<ChapterIndex>()
                var offset = 0L
                // 封面文档去重: spine 里的整图封面页(转制书惯例)与元数据封面(COVER 页画的
                // 就是同一文件)同源——再登记为章会在书首出现连续两页同一封面,跳过不建章
                val coverAbs = pkg.coverHref?.let {
                    File(outDir, resolveHref(opfDir, percentDecode(stripFragment(it).first))).absoluteFile
                }
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
                    val extracted = HtmlTextExtractor.extract(
                        docFile, key.substringBeforeLast('/', ""), dataUriSinkOf(outDir)
                    )
                    val paragraphs = extracted.paragraphs
                    if (paragraphs.isEmpty()) continue
                    if (isCoverDoc(paragraphs, coverAbs, outDir, docFile)) continue

                    // 目录键与 NCX/Nav 条目同一约定: 相对 zip 根的解码路径
                    val fallbackTitle = docFile.nameWithoutExtension.ifBlank { "未命名" }
                    val toc = tocMap[key]
                    val title = (toc?.first ?: HtmlTextExtractor.firstHeading(docFile) ?: fallbackTitle)
                        .take(MAX_TITLE_LEN)
                    val paras = dedupeLeadingTitle(paragraphs, title)
                    // 纯图片页的目录标题常是文件名(如 "0.jpg"),显示为"插图"
                    val displayTitle = if (paras.all { it.isImage } &&
                        Regex("\\.(jpe?g|png|gif|webp)\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(title)
                    ) "插图" else title

                    // 四期: h2 小节二次拆章——文档内 h2 段落起点切分,首小节用目录名(level 0),
                    // 后续小节用 h2 文本(level 1,anchor = h2 id);无 h2 维持单章
                    for (section in splitSections(paras)) {
                        val f = chapterOutFile(chapters.size)
                        f.parentFile?.mkdirs()
                        // 脚注表按文档全量随每小节落盘(noteId 全文档唯一,角标可能跨小节)
                        ChapterFileCodec.write(f, section.paras, extracted.footnotes, extracted.fonts)
                        // 投影长度 = 各段 text 之和 + 段间换行(与 bodyText joinToString 同构)
                        val bodyLen = section.paras.sumOf { it.text.length.toLong() } + (section.paras.size - 1)
                        val secTitle = (section.h2Text ?: displayTitle).take(MAX_TITLE_LEN)
                        chapters += ChapterIndex(
                            secTitle, offset,
                            anchorId = section.h2Anchor ?: (if (section.first) toc?.second else null),
                            level = if (section.first) 0 else 1
                        )
                        offset += bodyLen + 1L   // 章间一个虚拟换行偏移(章区间连续拼接)
                    }
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
    // 版式层的 stripLeadingTitle 只认"整行等于章名",这里放宽到互相包含的短标题;
    // 另有章首装饰表格场景(数字圈"1"+章名+装饰图的降级段): 去掉序号前缀后与章名一致
    // 也视为重复剥掉——不剥则与合成章名形成双标题, 且表格行高被装饰图格需求撑出大段空白
    private fun dedupeLeadingTitle(paragraphs: List<Paragraph>, title: String): List<Paragraph> {
        val t = title.trim()
        if (t.isEmpty() || paragraphs.isEmpty()) return paragraphs
        val first = paragraphs.first().text.trim()
        if (first == t || (first.length <= 30 && (t.contains(first) || first.contains(t)))) {
            return paragraphs.drop(1)
        }
        // 章首装饰表格(数字圈"1"+章名+装饰图的降级段, 投影为单个 U+FFFC):
        // 各 cell 文本拼接去序号后与章名一致 → 视为重复剥掉(否则双标题+行高空白)
        if (paragraphs.first().isTable && first.length <= 2) {
            val cells = paragraphs.first().table?.cells?.joinToString("") { it.text } ?: ""
            val norm = stripOrdinal(cells)
            if (norm.isNotEmpty() && norm.length <= 40 && norm == stripOrdinal(t)) {
                return paragraphs.drop(1)
            }
        }
        return paragraphs
    }

    // 去掉首部序号修饰(阿拉伯数字/带圈数字/点号/空格)用于重复比较
    private fun stripOrdinal(s: String): String =
        s.trim().trimStart('0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩', ' ', '　', '.', '、')

    // 四期: h2 小节切分。heading==2 且非首段的段落为小节起点;
    // 首小节 first=true(用目录名/目录锚点),后续小节 h2Text/h2Anchor 取自其 h2 段
    internal class Section(
        val paras: List<Paragraph>,
        val h2Text: String?,
        val h2Anchor: String?,
        val first: Boolean
    )

    internal fun splitSections(paras: List<Paragraph>): List<Section> {
        val bounds = ArrayList<Int>()
        for ((i, p) in paras.withIndex()) if (p.heading == 2 && i > 0) bounds += i
        if (bounds.isEmpty()) return listOf(Section(paras, null, null, first = true))
        val raw = ArrayList<List<Paragraph>>()
        var start = 0
        for (b in bounds) {
            raw += paras.subList(start, b)
            start = b
        }
        raw += paras.subList(start, paras.size)
        return raw.mapIndexed { k, list ->
            if (list.isEmpty()) return@mapIndexed null
            val firstPara = list.first()
            if (k > 0 && firstPara.heading == 2) {
                Section(list, firstPara.text, firstPara.anchor, first = false)
            } else {
                Section(list, null, null, first = k == 0)
            }
        }.filterNotNull()
    }

    // 解压目录内按文件名(不含目录)查找,首匹配
    private fun findFileRecursively(dir: File, name: String): File? {
        if (!dir.exists()) return null
        dir.walkTopDown().forEach { f -> if (f.isFile && f.name.equals(name, ignoreCase = true)) return f }
        return null
    }

    // 章首装饰表格残留检测(存量书升级触发): 任一章的首段为表格降级段(投影 U+FFFC)
    // 且 cell 文本去序号后与该章章名一致 → 该书导入早于装饰表格剥除, 打开时升级重建
    // (双标题与大段空白随重建消失)。IO 线程调用
    private fun tableTitleLingers(
        context: Context,
        bookId: String,
        chapters: List<com.yukino.tool.module.reader.common.ChapterIndex>
    ): Boolean {
        for ((i, ch) in chapters.withIndex()) {
            if (ch.title.isBlank()) continue
            val rr = runCatching { ChapterFileCodec.read(chapterFile(context, bookId, i)) }
                .getOrNull() ?: continue
            val p0 = rr.paragraphs.firstOrNull() ?: continue
            if (!p0.isTable) continue
            val cells = p0.table?.cells?.joinToString("") { it.text } ?: continue
            val norm = stripOrdinal(cells)
            if (norm.isNotEmpty() && norm == stripOrdinal(ch.title)) return true
        }
        return false
    }

    // 封面章残留检测(存量书升级触发): 首章为纯图片文档且其图与本书元数据封面同一文件
    // → 该书导入早于封面去重, 打开时升级剔除。章文件 img 为相对书目录根的路径, 与 coverPath 同基准
    private fun coverChapterLingers(first: File, coverPath: String?, dir: File): Boolean {
        if (coverPath == null) return false
        val rr = ChapterFileCodec.read(first)
        return isCoverDoc(rr.paragraphs, File(coverPath), dir, dir)
    }

    // 封面文档判定(纯函数,单测覆盖): 文档提取结果全部为图片段、且其中存在与元数据封面
    // 同一文件的段 → 该文档是书内封面页(应用 COVER 页已呈现同一图),跳过不登记为章。
    // 元数据封面未探测到(coverFile null)时不判定——文档可能是唯一封面呈现,保留。
    // 图片段路径按解压根与文档目录两种基准解析;路径不等时以内容等价兜底(升级搬运会在
    // 书目录根留一份封面副本, 与解压目录内原图为同一张图的两份文件)
    internal fun isCoverDoc(
        paragraphs: List<Paragraph>,
        coverFile: File?,
        docRoot: File,
        docFile: File
    ): Boolean {
        if (coverFile == null) return false
        if (paragraphs.isEmpty() || !paragraphs.all { it.isImage }) return false
        return paragraphs.any { p ->
            val ref = p.imageRef ?: return@any false
            runCatching {
                val img = File(docRoot, ref)
                img.canonicalFile == coverFile.canonicalFile ||
                    File(docFile.parent, ref).canonicalFile == coverFile.canonicalFile ||
                    sameContent(img, coverFile)
            }.getOrDefault(false)
        }
    }

    // 内容等价: 大小相等且逐字节一致(封面图几 MB 级, 仅在首章检测时跑一次)
    private fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length() || a.length() == 0L) return false
        val bufA = ByteArray(8192)
        val bufB = ByteArray(8192)
        a.inputStream().use { ia ->
            b.inputStream().use { ib ->
                while (true) {
                    val na = ia.read(bufA)
                    val nb = ib.read(bufB)
                    if (na != nb) return false
                    if (na <= 0) return true
                    if (!bufA.copyOf(na).contentEquals(bufB.copyOf(nb))) return false
                }
                true
            }
        }
    }
}
