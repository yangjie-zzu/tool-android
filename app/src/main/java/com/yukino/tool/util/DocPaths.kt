package com.yukino.tool.util

import android.net.Uri
import android.os.Environment

// SAF 文档 uri → 人类可读绝对路径(仅显示用)。文档 id 形如 "卷id:路径":
// 冒号后以 /storage/ 开头视为已是完整绝对路径直接使用(实测文件管理器提供方
// com.android.fileexplorer.documents 的 docId 即 "primary:/storage/emulated/0/..."
// 形态, 拼卷根会重复前缀);否则卷根拼接(primary → /storage/emulated/0,
// 其他卷 → /storage/<卷id>);两种结果都压缩连续斜杠。
object DocPaths {

    // 文档 id 取最后一个路径段(前一段为 "document");单文件 uri 与文件夹子项
    // (.../tree/<root>/document/<id>)两种形态均适用;非文档结构返回 null
    fun documentIdOf(uri: Uri): String? {
        val segs = uri.pathSegments ?: return null
        if (segs.size >= 2 && segs[segs.size - 2] == "document") return segs.last()
        return null
    }

    // "卷id:路径" → 绝对路径;非该结构 docId 原样返回
    @Suppress("DEPRECATION")
    fun absolutePathOfDocId(docId: String): String {
        val idx = docId.indexOf(':')
        if (idx <= 0 || idx == docId.length - 1) return docId
        val sub = docId.substring(idx + 1)
        if (sub.startsWith("/storage/")) {
            return sub.replace(Regex("/{2,}"), "/")
        }
        val root = if (docId.substring(0, idx) == "primary") {
            Environment.getExternalStorageDirectory().absolutePath
        } else "/storage/${docId.substring(0, idx)}"
        return "$root/$sub".replace(Regex("/{2,}"), "/")
    }

    // uri → 绝对路径;解析不出文档 id 或非 "卷:路径" 结构时返回 uri 原串兜底
    fun documentAbsolutePath(uri: Uri): String {
        val docId = documentIdOf(uri) ?: return uri.toString()
        val idx = docId.indexOf(':')
        if (idx <= 0 || idx == docId.length - 1) return uri.toString()
        return absolutePathOfDocId(docId)
    }
}
