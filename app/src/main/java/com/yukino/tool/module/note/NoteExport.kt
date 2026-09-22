package com.yukino.tool.module.note

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.yukino.tool.db.AppDb
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 备忘录备份结构(加密ZIP内的JSON): 加密元数据+全部条目, secret字段的value仍是密文
@Serializable
data class NoteCryptoBackup(
    val salt: String? = null,
    val checkIv: String? = null,
    val check: String? = null,
    val bioIv: String? = null,
    val bioKey: String? = null,
    val entries: List<NoteEntry> = emptyList()
) {
    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        internal fun metaOf(context: Context, k: String): String? {
            AppDb.get(context).rawQuery("SELECT v FROM note_meta WHERE k = ?", arrayOf(k)).use { c ->
                return if (c.moveToFirst()) c.getString(0) else null
            }
        }

        internal fun parse(text: String): NoteCryptoBackup = json.decodeFromString(text)
    }
}

/*
 * 备忘录导出:
 *  - 加密备份: 打包成AES-256加密的ZIP并分享,导出库内全部数据(元数据+条目,密文原样)，
 *    数据仍由主密钥加密，恢复需导回本应用；
 *  - 明文导出: 解密后的全部条目生成可读Markdown(.md)文件直接分享(含密码和2FA密钥)，请妥善保管。
 */
object NoteExport {

    // 导出模式
    const val MODE_BACKUP = 0       // 备份: 加密ZIP(库内数据导出的JSON)
    const val MODE_ENCRYPTED_MD = 1 // 加密导出: 加密ZIP(内含明文Markdown)
    const val MODE_PLAIN_MD = 2     // 明文导出: Markdown文件直接分享

    private fun exportDir(context: Context): File =
        File(context.cacheDir, "export").apply { mkdirs() }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "备忘录备份" }

    // 加密备份: 导出库内全部数据(加密元数据+条目, secret字段仍是密文)为JSON打包(无需认证)
    fun exportEncryptedBackupZip(context: Context, name: String, password: String): File {
        val dump = NoteCryptoBackup(
            salt = NoteCryptoBackup.metaOf(context, "salt"),
            checkIv = NoteCryptoBackup.metaOf(context, "checkIv"),
            check = NoteCryptoBackup.metaOf(context, "check"),
            bioIv = NoteCryptoBackup.metaOf(context, "bioIv"),
            bioKey = NoteCryptoBackup.metaOf(context, "bioKey"),
            entries = NoteCrypto.readPlain(context)
        )
        if (dump.salt == null) error("没有可导出的数据")
        return zipWithPassword(
            context, name, password,
            "${safeFileName(name)}.json", NoteCryptoBackup.json.encodeToString(dump)
        )
    }

    // 明文导出: 解密后的全部条目生成Markdown(secrets为"条目id:字段名"→明文值)
    fun buildPlaintextMarkdown(entries: List<NoteEntry>, secrets: Map<String, String>): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val sb = StringBuilder("# 备忘录导出\n\n> 导出时间：$time\n")
        entries.forEachIndexed { i, e ->
            val title = e.fields.firstOrNull { it.title }?.value?.ifBlank { null } ?: "未命名"
            sb.append("\n## ${i + 1}. $title\n\n")
            e.fields.forEach { f ->
                val v = if (f.secret) secrets["${e.id}:${f.key}"] ?: "" else f.value
                if (v.isNotBlank()) {
                    // 多行值缩进续行,保持列表项完整
                    sb.append("- **${f.key}**：").append(v.replace("\n", "\n  ")).append('\n')
                }
            }
        }
        return sb.toString()
    }

    // 明文导出: 生成.md文件(不经ZIP,明文本身即为交付物)
    fun exportPlaintextMd(context: Context, name: String, markdown: String): File {
        val exportDir = exportDir(context)
        exportDir.listFiles()?.forEach { it.delete() }   // 清理历史导出
        return File(exportDir, "${safeFileName(name)}.md").apply { writeText(markdown) }
    }

    // 加密导出: 明文Markdown打入AES-256加密ZIP
    fun exportEncryptedMdZip(context: Context, name: String, password: String, markdown: String): File =
        zipWithPassword(context, name, password, "${safeFileName(name)}.md", markdown)

    // 打包: 内容以AES-256加密写入ZIP(需密码解压)
    private fun zipWithPassword(
        context: Context,
        name: String,
        password: String,
        fileName: String,
        content: String
    ): File {
        val exportDir = exportDir(context)
        exportDir.listFiles()?.forEach { it.delete() }   // 清理历史导出
        val inner = File(exportDir, fileName).apply { writeText(content) }
        val zip = File(exportDir, "${safeFileName(name)}.zip")
        ZipFile(zip).apply {
            setPassword(password.toCharArray())
            val params = ZipParameters().apply {
                compressionMethod = CompressionMethod.DEFLATE
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = fileName
            }
            addFile(inner, params)
        }
        inner.delete()
        return zip
    }

    // 唤起系统分享(mime按扩展名: md→text/markdown, zip→application/zip)
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileProvider", file
        )
        val mime = if (file.extension.equals("md", ignoreCase = true)) "text/markdown" else "application/zip"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享备忘录备份"))
    }
}
