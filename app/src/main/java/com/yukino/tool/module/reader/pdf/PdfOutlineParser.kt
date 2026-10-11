package com.yukino.tool.module.reader.pdf

// 纯 Kotlin PDF 书签(outline/大纲树)解析器: 不依赖任何渲染引擎, 按 PDF 规范直接解析文件字节。
// 覆盖: 经典 xref 表 / 交叉引用流(PDF 1.5+, FlateDecode) / 对象流(压缩对象) / 增量更新 /Prev 链 /
// 混合引用 /XRefStm;损坏的 xref 兜底全文扫描 "N G obj" 重建对象索引。
// 加密(/Encrypt)或结构性解析失败 → 返回空表(调用方降级为页码目录, 不影响阅读)。
object PdfOutlineParser {

    // level: 0 = 顶级书签, 子书签递增(目录树缩进用);page: 0 基页码
    class Item(val title: String, val page: Int, val level: Int)

    fun parse(data: ByteArray, pageCount: Int): List<Item> = try {
        Parser(data).parseRoot(pageCount)
    } catch (e: Exception) {
        android.util.Log.d("PdfOutlineParser", "outline 解析失败: ${e.message}")
        emptyList()
    }

    // ==================== 对象模型 ====================

    private sealed class PObj
    private class PNum(val v: Double) : PObj()
    private class PRef(val num: Long) : PObj()
    private class PName(val v: String) : PObj()
    private class PStr(val bytes: ByteArray) : PObj() {
        // UTF-16BE(BOM 头)优先, 其余按 PDFDocEncoding(与 latin-1 近似)解码
        fun text(): String = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        } else {
            // PDFDocEncoding: 0x80+ 区段与 latin-1 大体同值, ascii 区一致, 足够书签标题使用
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private class PArr(val items: List<PObj>) : PObj()
    private class PDict(val map: Map<String, PObj>) : PObj() {
        operator fun get(key: String): PObj? = map[key]
    }

    // ==================== 主解析流程 ====================

    private class Parser(private val d: ByteArray) {
        // objNum → "N G obj" 起始偏移(直接对象);objNum → (对象流号, 流内下标)(压缩对象)
        private val offsets = HashMap<Long, Long>()
        private val inStreams = HashMap<Long, Pair<Long, Int>>()
        // 对象流号 → 解压后的字节;对象流号 → 流内 objNum → 已解析对象
        private val streamData = HashMap<Long, ByteArray>()
        private val streamObjs = HashMap<Long, MutableMap<Long, PObj>>()
        private val pageIndexOf = HashMap<Long, Int>()   // 页对象 objNum → 页码

        fun parseRoot(pageCount: Int): List<Item> {
            val trailer = loadXref(startxrefOffset())
            val rootRef = trailer?.let { it["Root"] as? PRef }
                ?: run { scanAllObjects(); findCatalog() }
                ?: return emptyList()
            val catalog = derefRef(rootRef) as? PDict ?: return emptyList()
            if (catalog["Encrypt"] != null) return emptyList()   // 加密文档不解
            mapPages(catalog)
            val outlinesRef = catalog["Outlines"] as? PRef ?: return emptyList()
            val items = ArrayList<Item>()
            walk(derefRef(outlinesRef) as? PDict, 0, items, HashSet(), 0)
            return items.filter { it.page >= 0 && (pageCount <= 0 || it.page < pageCount) }
        }

        // ---------- xref 加载 ----------

        private fun startxrefOffset(): Long {
            // 文件尾部向前找 "startxref"
            val from = (d.size - 2048).coerceAtLeast(0)
            val key = "startxref".toByteArray(Charsets.US_ASCII)
            var i = d.size - key.size
            while (i >= from) {
                if (matchAt(key, i)) {
                    var p = skipWs(i + key.size)
                    var v = 0L
                    while (p < d.size && d[p].toInt() in 0x30..0x39) { v = v * 10 + (d[p] - 0x30); p++ }
                    return v
                }
                i--
            }
            return -1L
        }

        // 返回 trailer 字典(经典 xref)或 xref 流字典;失败 null
        private fun loadXref(off: Long, depth: Int = 0): PDict? {
            if (off <= 0 || off >= d.size || depth > 32) return null
            var p = skipWs(off.toInt())
            if (matchAt("xref".toByteArray(), p)) {
                return loadClassicXref(p + 4, depth)
            }
            // 交叉引用流: "N G obj <<…>> stream"
            val r = Reader(d, p)
            r.skipToken(); r.skipToken()   // num gen
            if (!r.expect("obj")) return null
            val dict = r.readValue() as? PDict ?: return null
            decodeXrefStream(dict, r)
            // 增量更新 /Prev 链(前代 xref 流);先到先得,旧条目不会覆盖新条目
            (deref(dict["Prev"]) as? PNum)?.v?.toLong()?.let { loadXref(it, depth + 1) }
            return dict
        }

        private fun loadClassicXref(p0: Int, depth: Int): PDict? {
            var p = skipWs(p0)
            var trailer: PDict? = null
            while (p < d.size) {
                if (matchAt("trailer".toByteArray(), p)) {
                    trailer = Reader(d, skipWs(p + 7)).readValue() as? PDict
                    break
                }
                var r = Reader(d, p)
                val start = (r.readValue() as? PNum)?.v?.toLong() ?: break
                val count = (r.readValue() as? PNum)?.v?.toLong() ?: break
                p = skipWs(r.pos)
                for (i in 0 until count) {
                    r = Reader(d, p)
                    val off = (r.readValue() as? PNum)?.v?.toLong() ?: break
                    r.readValue()   // gen
                    r.ws()
                    val type = if (r.pos < d.size) d[r.pos].toInt() else 0
                    if (type == 'n'.code && off > 0 && off < d.size) {
                        val num = start + i
                        if (num > 0) offsets.putIfAbsent(num, off)
                    } else if (type == 'f'.code) {
                        offsets.putIfAbsent(start + i, -1L)   // free 槽占位,防 fallback 误扫
                    }
                    p = skipWs(r.pos + 1)
                }
            }
            trailer ?: return null
            // 增量更新 /Prev 链与混合 /XRefStm: 先到先得(新条目优先)
            (deref(trailer["XRefStm"]) as? PNum)?.v?.toLong()?.let { loadXref(it, depth + 1) }
            (deref(trailer["Prev"]) as? PNum)?.v?.toLong()?.let { loadXref(it, depth + 1) }
            return trailer
        }

        private fun decodeXrefStream(dict: PDict, r: Reader) {
            val data = streamBytes(dict, r) ?: return
            val w = (deref(dict["W"]) as? PArr)?.items?.map { (it as? PNum)?.v?.toInt() ?: 0 } ?: return
            if (w.size < 3) return
            val index = (deref(dict["Index"]) as? PArr)?.items
                ?.map { (it as? PNum)?.v?.toLong() ?: 0L }
                ?: listOf(0L, (deref(dict["Size"]) as? PNum)?.v?.toLong() ?: 0L)
            val w2 = w[1]; val w3 = w[2]
            val entryLen = (if (w[0] > 0) w[0] else 1) + w2 + w3
            var pos = 0
            for (gi in index.indices step 2) {
                val start = index[gi]; val count = index[gi + 1]
                for (i in 0 until count) {
                    if (pos + entryLen > data.size) break
                    fun field(from: Int, len: Int): Long {
                        var v = 0L
                        for (k in 0 until len) v = (v shl 8) or (data[pos + from + k].toLong() and 0xFF)
                        return v
                    }
                    val type = if (w[0] == 0) 1L else field(0, w[0])
                    when (type) {
                        1L -> if (w2 > 0) offsets.putIfAbsent(start + i, field(w[0], w2))
                        2L -> if (w2 > 0 && w3 > 0) inStreams.putIfAbsent(start + i, Pair(field(w[0], w2), field(w[0] + w2, w3).toInt()))
                    }
                    pos += entryLen
                }
            }
        }

        // 兜底: xref 全坏时扫描全部 "N G obj" 重建直接对象索引
        private fun scanAllObjects() {
            val key = " obj".toByteArray(Charsets.US_ASCII)
            var i = 0
            while (true) {
                i = indexOf(key, i)
                if (i < 0) break
                // "obj" 前须是 "N G "
                var p = skipWsBack(i - 1)
                val genEnd = p + 1
                p = skipWsBack(p - 1)
                val numEnd = p + 1
                val num = parseUint(p - 19, numEnd)   // 对象号最多 7 位,取 20 字节窗口
                val gen = parseUint(p + 1, genEnd)
                if (num != null && gen != null && num > 0 && offsets.putIfAbsent(num, (numEnd + 1).toLong()) == null) {
                    // 占位,真值上面已放
                }
                i += key.size
            }
        }

        private fun parseUint(from0: Int, end: Int): Long? {
            var p = from0.coerceAtLeast(0)
            while (p < end && !isDigit(p)) p++
            if (p >= end) return null
            var v = 0L
            while (p < end && isDigit(p)) { v = v * 10 + (d[p] - 0x30); p++ }
            return v
        }

        // ---------- 对象获取 ----------

        private val fetchCache = HashMap<Long, PObj>()

        private fun fetch(num: Long): PObj? {
            if (num <= 0) return null
            fetchCache[num]?.let { return it }
            val obj = loadObj(num) ?: return null
            fetchCache[num] = obj
            return obj
        }

        private fun loadObj(num: Long): PObj? {
            val off = offsets[num]
            if (off != null && off > 0) {
                val r = Reader(d, off.toInt())
                r.skipToken(); r.skipToken()
                if (!r.expect("obj")) return null
                return r.readValue()
            }
            val loc = inStreams[num] ?: return null
            val objs = streamObjs.getOrPut(loc.first) { loadObjectStream(loc.first) ?: return null }
            return objs[num]
        }
        private fun loadObjectStream(num: Long): MutableMap<Long, PObj>? {
            streamObjs[num]?.let { return it }
            val off = offsets[num] ?: return null
            val r = Reader(d, off.toInt())
            r.skipToken(); r.skipToken()
            if (!r.expect("obj")) return null
            val dict = r.readValue() as? PDict ?: return null
            val data = streamData.getOrPut(num) { streamBytes(dict, r) ?: return null }
            val n = (deref(dict["N"]) as? PNum)?.v?.toInt() ?: return null
            val first = (deref(dict["First"]) as? PNum)?.v?.toInt() ?: return null
            val out = HashMap<Long, PObj>(n)
            val r2 = Reader(data, 0)
            var pos = 0
            val heads = ArrayList<Pair<Long, Int>>(n)
            for (i in 0 until n) {
                r2.pos = pos
                val objNum = (r2.readValue() as? PNum)?.v?.toLong() ?: break
                val rel = (r2.readValue() as? PNum)?.v?.toInt() ?: break
                heads += objNum to first + rel
                pos = r2.pos
            }
            for ((objNum, abs) in heads) {
                r2.pos = abs
                out[objNum] = r2.readValue()
            }
            return out
        }

        // ---------- 页树映射 ----------

        private fun mapPages(catalog: PDict) {
            val root = derefRef(catalog["Pages"] as? PRef ?: return) as? PDict ?: return
            walkPages(root, HashSet())
        }

        private fun walkPages(node: PDict, visited: MutableSet<PDict>) {
            if (!visited.add(node) || pageIndexOf.size > 100000) return
            if ((deref(node["Type"]) as? PName)?.v == "Page") return   // 由父层登记
            val kids = (deref(node["Kids"]) as? PArr)?.items ?: return
            for (k in kids) {
                val ref = k as? PRef ?: continue
                val kid = derefRef(ref) as? PDict ?: continue
                if ((deref(kid["Type"]) as? PName)?.v == "Page") {
                    if (pageIndexOf.put(ref.num, pageIndexOf.size) == null) { /* 登记页码 */ }
                } else {
                    walkPages(kid, visited)
                }
            }
        }

        // ---------- outline 树 ----------

        private fun walk(
            node: PDict?,
            level: Int,
            out: MutableList<Item>,
            visited: MutableSet<Any>,
            guard: Int
        ) {
            if (node == null || guard > 10000) return
            var cur = derefRef(node["First"] as? PRef)
            var n = 0
            while (cur is PDict && n++ < 100000) {
                if (!visited.add(cur)) break   // 身份去重(环保护);每条链从父节点进入,共享同一集合会误杀兄弟链 → 用有序重置
                val title = (cur["Title"] as? PStr)?.text()?.trim().orEmpty()
                val page = destPage(cur)
                if (title.isNotEmpty() && page >= 0) out += Item(title, page, level)
                walk(derefRef(cur["First"] as? PRef) as? PDict, level + 1, out, visited, guard + 1)
                cur = derefRef(cur["Next"] as? PRef)
            }
        }

        // 节点目标页码: /Dest 或 /A{/S /GoTo /D};目标可为 [页引用 /XYZ …] 或页码整数;命名目标不支持(-1)
        private fun destPage(item: PDict): Int {
            val dest = (deref(item["Dest"]) as? PArr)
                ?: ((deref(item["A"]) as? PDict)?.let { a ->
                    if ((deref(a["S"]) as? PName)?.v == "GoTo") deref(a["D"]) as? PArr else null
                })
                ?: return -1
            return when (val first = dest.items.firstOrNull()) {
                is PRef -> pageIndexOf[first.num] ?: -1
                is PNum -> first.v.toInt()
                else -> -1
            }
        }

        // ---------- 小工具 ----------

        // 兜底路径: 全量扫描后找 /Type /Catalog 对象
        private fun findCatalog(): PRef? {
            for ((num, off) in offsets) {
                if (off <= 0) continue
                val r = Reader(d, off.toInt())
                r.skipToken(); r.skipToken()
                if (!r.expect("obj")) continue
                val dict = r.readValue() as? PDict ?: continue
                if ((deref(dict["Type"]) as? PName)?.v == "Catalog") return PRef(num)
            }
            return null
        }

        private fun deref(o: PObj?): PObj? = if (o is PRef) derefRef(o) else o
        private fun derefRef(ref: PRef?): PObj? = ref?.let { fetch(it.num) }

        private fun streamBytes(dict: PDict, r: Reader): ByteArray? {
            var len = (deref(dict["Length"]) as? PNum)?.v?.toInt()
            if (len == null) {
                val lenRef = dict["Length"] as? PRef ?: return null
                len = (fetch(lenRef.num) as? PNum)?.v?.toInt() ?: return null
            }
            // 跳到 "stream" 关键字
            var p = r.pos
            val key = "stream".toByteArray()
            var found = -1
            while (p + key.size <= d.size && p < r.pos + 4096) {
                if (matchAt(key, p)) { found = p; break }
                p++
            }
            if (found < 0) return null
            var s = found + key.size
            if (d[s] == '\r'.code.toByte()) s++
            if (d[s] == '\n'.code.toByte()) s++
            val end = (s + len).coerceAtMost(d.size)
            val raw = d.copyOfRange(s, end)
            val filter = deref(dict["Filter"])
            val isFlate = when (filter) {
                is PName -> filter.v == "FlateDecode"
                is PArr -> filter.items.any { (it as? PName)?.v == "FlateDecode" }
                else -> false
            }
            if (!isFlate) return raw
            return runCatching {
                val inf = java.util.zip.Inflater()
                inf.setInput(raw)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break } else out.write(buf, 0, n)
                }
                inf.end()
                out.toByteArray()
            }.getOrNull()
        }

        private fun isDigit(i: Int): Boolean = i in d.indices && d[i].toInt() in 0x30..0x39
        private fun skipWs(i0: Int): Int {
            var i = i0
            while (i < d.size) {
                val c = d[i].toInt()
                if (c == '%'.code) { while (i < d.size && d[i] != '\n'.code.toByte() && d[i] != '\r'.code.toByte()) i++ }
                else if (c == 0 || c in 0x09..0x20) i++
                else break
            }
            return i
        }
        private fun skipWsBack(i0: Int): Int {
            var i = i0
            while (i >= 0 && (d[i].toInt() == 0 || d[i].toInt() in 0x09..0x20 || d[i].toInt() == '%'.code)) i--
            return i
        }
        private fun matchAt(key: ByteArray, at: Int): Boolean {
            if (at < 0 || at + key.size > d.size) return false
            for (i in key.indices) if (d[at + i] != key[i]) return false
            return true
        }
        private fun indexOf(key: ByteArray, from: Int): Int {
            var i = from.coerceAtLeast(0)
            val first = key[0]
            val limit = d.size - key.size
            while (i <= limit) {
                if (d[i] == first && matchAt(key, i)) return i
                i++
            }
            return -1
        }
    }

    // ==================== 对象记号读取器 ====================

    private class Reader(private val d: ByteArray, var pos: Int) {
        fun ws() {
            while (pos < d.size) {
                val c = d[pos].toInt()
                if (c == '%'.code) { while (pos < d.size && d[pos] != '\n'.code.toByte()) pos++ }
                else if (c == 0 || c in 0x09..0x20) pos++
                else break
            }
        }

        fun expect(word: String): Boolean {
            ws()
            val key = word.toByteArray(Charsets.US_ASCII)
            if (!match(key)) return false
            pos += key.size
            return true
        }

        private fun match(key: ByteArray): Boolean {
            if (pos + key.size > d.size) return false
            for (i in key.indices) if (d[pos + i] != key[i]) return false
            return true
        }

        fun skipToken() { ws(); while (pos < d.size && d[pos].toInt() > 0x20 && "![]<>(){}/%".indexOf(d[pos].toInt().toChar()) < 0) pos++ }

        fun readValue(): PObj {
            ws()
            if (pos >= d.size) return PName("")
            val c = d[pos].toInt()
            return when {
                c == '<'.code && pos + 1 < d.size && d[pos + 1] == '<'.code.toByte() -> readDict()
                c == '<'.code -> PStr(readHex())
                c == '('.code -> PStr(readLiteral())
                c == '['.code -> readArray()
                c == '/'.code -> readName()
                c == '+'.code || c == '-'.code || c in 0x30..0x39 || c == '.'.code -> readNumber()
                else -> {
                    val tok = token()
                    when (tok) {
                        "true" -> PNum(1.0)
                        "false", "null" -> PNum(0.0)
                        else -> PName(tok)
                    }
                }
            }
        }

        private fun token(): String {
            val s = pos
            while (pos < d.size && d[pos].toInt() > 0x20 && "![]<>(){}%/".indexOf(d[pos].toInt().toChar()) < 0) pos++
            return String(d, s, (pos - s).coerceAtLeast(0), Charsets.US_ASCII)
        }

        private fun readNumber(): PObj {
            val s = pos
            if (d[pos] == '+'.code.toByte() || d[pos] == '-'.code.toByte()) pos++
            var isInt = true
            while (pos < d.size) {
                val c = d[pos].toInt()
                if (c in 0x30..0x39) pos++
                else if (c == '.'.code) { isInt = false; pos++ }
                else break
            }
            val text = String(d, s, pos - s, Charsets.US_ASCII)
            val v = text.toDoubleOrNull() ?: 0.0
            // 引用 "N G R": 读到第二个整数后再探测 R
            if (isInt) {
                val save = pos
                ws()
                val s2 = pos
                var allDigit = pos < d.size
                while (pos < d.size && d[pos].toInt() in 0x30..0x39) pos++
                if (!allDigit || pos == s2) { pos = save; return PNum(v) }
                allDigit = pos > s2
                ws()
                if (pos < d.size && d[pos] == 'R'.code.toByte() &&
                    (pos + 1 >= d.size || d[pos + 1].toInt() < 0x21 || "#[]<>(){}%/".indexOf(d[pos + 1].toInt().toChar()) >= 0)
                ) {
                    pos++
                    return PRef(v.toLong())
                }
                pos = save
            }
            return PNum(v)
        }

        private fun readName(): PObj {
            pos++   // '/'
            val s = pos
            while (pos < d.size && d[pos].toInt() > 0x20 && "#/[]<>(){}".indexOf(d[pos].toInt().toChar()) < 0) pos++
            // #xx 十六进制转义
            val raw = String(d, s, pos - s, Charsets.US_ASCII)
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                if (raw[i] == '#' && i + 2 < raw.length + 1 && i + 2 < raw.length + 1) {
                    val hex = raw.substring(i + 1, (i + 3).coerceAtMost(raw.length))
                    if (hex.length == 2) {
                        val v = hex.toIntOrNull(16)
                        if (v != null) { sb.append(v.toChar()); i += 3; continue }
                    }
                }
                sb.append(raw[i]); i++
            }
            return PName(sb.toString())
        }

        private fun readArray(): PObj {
            pos++   // '['
            val items = ArrayList<PObj>()
            while (true) {
                ws()
                if (pos >= d.size) break
                if (d[pos] == ']'.code.toByte()) { pos++; break }
                val save = pos
                val v = readValue()
                if (pos == save) break
                items += v
            }
            return PArr(items)
        }

        private fun readDict(): PObj {
            pos += 2   // "<<"
            val map = HashMap<String, PObj>()
            while (true) {
                ws()
                if (pos >= d.size) break
                if (d[pos] == '>'.code.toByte() && pos + 1 < d.size && d[pos + 1] == '>'.code.toByte()) { pos += 2; break }
                if (d[pos] != '/'.code.toByte()) {
                    val save = pos
                    readValue()
                    if (pos == save) break
                    continue
                }
                val key = (readValue() as? PName)?.v ?: continue
                map[key] = readValue()
            }
            return PDict(map)
        }

        private fun readLiteral(): ByteArray {
            pos++   // '('
            val out = java.io.ByteArrayOutputStream()
            var depth = 1
            while (pos < d.size) {
                val b = d[pos]
                when (b.toInt()) {
                    '\\'.code -> {
                        pos++
                        if (pos >= d.size) break
                        when (val e = d[pos].toInt()) {
                            'n'.code -> out.write('\n'.code); 'r'.code -> out.write('\r'.code)
                            't'.code -> out.write('\t'.code); 'b'.code -> out.write('\b'.code)
                            'f'.code -> out.write(12); '('.code -> out.write('('.code)
                            ')'.code -> out.write(')'.code); '\\'.code -> out.write('\\'.code)
                            '\r'.code -> { if (pos + 1 < d.size && d[pos + 1] == '\n'.code.toByte()) pos++ }
                            in 0x30..0x37 -> {   // 八进制最多 3 位
                                var v = e - 0x30
                                var n = 1
                                while (n < 3 && pos + 1 < d.size && d[pos + 1].toInt() in 0x30..0x37) {
                                    pos++; n++; v = v * 8 + (d[pos].toInt() - 0x30)
                                }
                                out.write(v and 0xFF)
                            }
                            else -> out.write(e)
                        }
                        pos++
                    }
                    '('.code -> { depth++; out.write(b.toInt()); pos++ }
                    ')'.code -> {
                        depth--
                        if (depth == 0) { pos++; break }
                        out.write(b.toInt()); pos++
                    }
                    else -> { out.write(b.toInt()); pos++ }
                }
            }
            return out.toByteArray()
        }

        private fun readHex(): ByteArray {
            pos++   // '<'
            val sb = StringBuilder()
            while (pos < d.size) {
                val c = d[pos].toInt()
                if (c == '>'.code) { pos++; break }
                if (c in 0x30..0x39 || c in 'A'.code..'F'.code || c in 'a'.code..'f'.code) sb.append(c.toChar())
                pos++
            }
            if (sb.length % 2 == 1) sb.append('0')
            val out = ByteArray(sb.length / 2)
            for (i in out.indices) out[i] = sb.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            return out
        }
    }
}
