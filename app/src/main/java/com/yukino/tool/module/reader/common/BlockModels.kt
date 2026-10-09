package com.yukino.tool.module.reader.common

import java.io.File

// WEBVIEW 块渲染的共享数据与缓存读取(混合渲染,docs/epub-hybrid-render-boundary.md)。
// common 层: 排版(BookPager)/物化/选择(SelectionGeometry)消费;epub 渲染器(WebViewBlockRenderer)写入。
// 放 common 是因为行模型与选择几何在 common/reader,不能反向依赖 epub 包。

// 块字符几何表: 字符序列与块投影文本逐字符一致(提取期与 WebView 采集脚本共用同一条
// 扁平化规则,对拍单测钉死);rects 为每字符 [x,y,w,h]×4,坐标系 = 块位图像素坐标。
// geom 为 null = 采集失败/超长 → 块上选择不可用(内容仍可经投影轴搜索/TTS/复制)
class BlockGeom(val chars: String, val rects: FloatArray, val contentW: Int, val contentH: Int) {
    val charCount: Int get() = rects.size / 4
}

// 渲染产物: 位图文件 + 像素尺寸 + 几何表
class CachedBlock(val file: File, val width: Int, val height: Int, val geom: BlockGeom?)

// 块行布局信息(断行期产物,ChapterLines.webBlocks 值): 位图文件 + 显示尺寸(版心坐标,
// 按版心宽等比,超高钳到一页内)。file 为 null = 位图缺失(渲染失败/被清理) →
// 该段不做块行,投影文本按普通段落自绘(兜底,绝不白屏)
class WebBlockInfo(
    val key: String,
    val file: String?,
    val width: Int,
    val height: Int,
    val geom: BlockGeom?,
    val bitmapW: Int = 0   // 位图像素宽(几何表坐标 → 显示坐标的换算基准)
)

// 块缓存键与磁盘读取。键 = 渲染器版本|字号|版心宽|行距(值+是否覆盖书内)|块内容 hash
// (失效条件与整章快照同语义: 字号、版式(版心宽)、行距注入、渲染器版本)
object BlockCache {
    // v2: 采集加布局稳定性环。v3: 强制全部声明字体 FontFace.load() 后再量。
    // v4: 两遍采集(隔 2×rAF)矩形序列一致才上报(布局定格自校验)。
    // v5: 等待全部 CSS background-image;时序诊断日志。
    // v6: 几何改为 settle 阶段经 evaluateJavascript 页内终采。
    // v7: 先 draw(强制渲染器落地最终布局)后收割几何;加像素抽检门(字符矩形内
    // 无字形墨迹即弃用几何写 ok:0)——显示永远正确,选择宁缺毋错。
    // v8: draw 白板重试(离屏帧产出节流,首绘可能空白,250ms×3);主线程销毁 WebView。
    // v9: 修 cropBottomBlank 别名回收(无裁剪时返回原图,被 recycle 后压缩必失败静默丢盘);
    // 像素门越界字符不再计入有效采样(横向溢出块视口外字符本就不可见)
    // v10: 内容宽超视口时 zoom 等比缩小(fitZoom 与高度上限同机制,双向)——修复
    // 定宽内容(学籍表/人物介绍聚合块等)横向溢出被视口裁切的缺陷
    // v11: 放弃注入缩放,视口直接撑到内容实际宽(桥上报),完整截取后由显示层按位图
    // 比例缩放到版心——零布局干预,断行/定位与浏览器原样;超高仍以 transform:scale
    // 缩到位图上限(绘制级,不触发重排)
    // v12: 拦截器请求路径 percent-decode 后再映射文件(中文/空格文件名图片 404 → OBJ 破图)
    // v13: 量宽并取 documentElement.scrollWidth(body.scrollWidth 不计视口包含块的
    // 绝对定位/out-of-flow 溢出,正文聚合位图右缘被视口裁切);补齐 v12/v13 漏递增
    // v14: 采集就绪改确定性静止屏障(资源终态+MutationObserver 布局静止,单次采集),
    // 替代尺寸稳定投票+双遍全等投票+6s 超时的概率式方案;几何 rs 改扁平整数数组,
    // 上限 5000→20000;纯图块上报 ok:2(无文字,非失败)
    const val RENDERER_VERSION = 14
    const val DIR_NAME = "wblocks"

    fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun keyOf(
        fontPx: Float,
        textWidth: Int,
        lineSpacingPercent: Int,
        bookLineHeight: Boolean,
        contentHash: String
    ): String =
        md5("v$RENDERER_VERSION|${"%.1f".format(fontPx)}|$textWidth|$lineSpacingPercent|$bookLineHeight|$contentHash")

    // 块内容 hash(渲染器与排版层共用同一条拼接规则)
    fun contentHashOf(
        html: String, shell: String, docDir: String,
        cssHrefs: List<String>, cssInline: List<String>,
        extra: String = ""
    ): String = md5(
        html + "\u0000" + shell + "\u0000" + docDir + "\u0000" +
            cssHrefs.joinToString("\u0001") + "\u0000" + cssInline.joinToString("\u0001") +
            if (extra.isEmpty()) "" else "\u0000$extra"
    )

    fun fileOf(chapterDir: File, key: String): File = File(File(chapterDir, DIR_NAME), "$key.png")

    fun geomFileOf(chapterDir: File, key: String): File = File(File(chapterDir, DIR_NAME), "$key.json")

    // 进程内结果表: 键控命中免磁盘 IO(断行与物化高频查;渲染器渲染完写入)
    private val mem = java.util.concurrent.ConcurrentHashMap<String, CachedBlock>()

    fun remember(key: String, cb: CachedBlock) { mem[key] = cb }

    fun inMemory(key: String): Boolean = mem.containsKey(key)

    // 查块: 内存 → 磁盘(PNG+JSON 双在才算完整)。null = 未渲染/失败(调用方走兜底自绘)
    fun lookup(chapterDir: File, key: String): CachedBlock? {
        mem[key]?.let { return it }
        val f = fileOf(chapterDir, key)
        if (!f.isFile || f.length() == 0L) return null
        val bounds = runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }.getOrNull() ?: return null
        val jf = geomFileOf(chapterDir, key)
        val geom = if (jf.isFile) runCatching { parseGeomJson(jf.readText()) }.getOrNull() else null
        val cb = CachedBlock(f, bounds.first, bounds.second, geom)
        mem[key] = cb
        return cb
    }

    // 几何 JSON: {"ok":1,"w":内容宽,"h":内容高,"cs":"字符序列","rs":...}(像素坐标)。
    // rs 两代格式兼容: 旧=嵌套数组 [[x,y,w,h],...],新(v14 渲染器起)=扁平整数数组
    // [x,y,w,h,...](体积省 40%+)。ok:2 = 块内无文本(纯图/装饰块,非失败),
    // 与 ok:0 采集失败同样解析为 null(选择退化,行为不变)
    fun parseGeomJson(json: String): BlockGeom? {
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
                .let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return null
        fun prim(key: String) =
            (root[key] as? kotlinx.serialization.json.JsonPrimitive)
        val ok = prim("ok")?.content?.toIntOrNull() ?: return null
        if (ok != 1) return null
        val w = prim("w")?.content?.toIntOrNull() ?: return null
        val h = prim("h")?.content?.toIntOrNull() ?: return null
        val cs = prim("cs")?.content ?: return null
        val rs = root["rs"] as? kotlinx.serialization.json.JsonArray ?: return null
        val rects: FloatArray
        if (rs.isEmpty()) {
            if (cs.isNotEmpty()) return null   // 序列与矩形数必须一致(错位即弃用,选择走兜底)
            rects = FloatArray(0)
        } else if (rs[0] is kotlinx.serialization.json.JsonPrimitive) {
            // 新格式: 扁平 [x,y,w,h,...]
            if (rs.size % 4 != 0 || rs.size / 4 != cs.length) return null
            rects = FloatArray(rs.size) { i ->
                (rs[i] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
                    ?: return null
            }
        } else {
            // 旧格式: 嵌套 [[x,y,w,h],...]
            if (rs.size != cs.length) return null
            rects = FloatArray(rs.size * 4)
            rs.forEachIndexed { i, e ->
                val r = e as? kotlinx.serialization.json.JsonArray ?: return null
                if (r.size < 4) return null
                for (k in 0 until 4) {
                    rects[i * 4 + k] = (r[k] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content?.toFloatOrNull() ?: return null
                }
            }
        }
        return BlockGeom(cs, rects, w, h)
    }
}
