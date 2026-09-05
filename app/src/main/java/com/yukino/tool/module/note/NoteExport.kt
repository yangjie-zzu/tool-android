package com.yukino.tool.module.note

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 备忘录导出: 打包成AES-256加密的ZIP并分享。
 *  - 加密备份: 原样打包数据文件(note.json)，数据仍由主密钥加密，恢复需导回本应用；
 *  - 明文导出: 解密后的全部条目生成可读TXT(含密码和2FA密钥)，用于查看，请妥善保管。
 */
object NoteExport {

    private fun exportDir(context: Context): File =
        File(context.cacheDir, "export").apply { mkdirs() }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "备忘录备份" }

    // 加密备份: 原样打包数据文件(无需认证，数据本身仍是密文)
    fun exportEncryptedBackupZip(context: Context, name: String, password: String): File {
        val dataFile = File(context.filesDir, "note.json")
        if (!dataFile.exists()) error("没有可导出的数据")
        return zipWithPassword(
            context, name, password,
            "${safeFileName(name)}.json", dataFile.readText()
        )
    }

    // 明文导出: 解密后的全部条目生成可读TXT(secrets为"条目id:字段名"→明文值)
    fun buildPlaintextText(entries: List<NoteEntry>, secrets: Map<String, String>): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val sb = StringBuilder("备忘录明文导出  $time\n")
        entries.forEachIndexed { i, e ->
            val title = e.fields.firstOrNull { it.title }?.value?.ifBlank { null } ?: "未命名"
            sb.append("\n【${i + 1}】$title\n")
            e.fields.forEach { f ->
                val v = if (f.secret) secrets["${e.id}:${f.key}"] ?: "" else f.value
                if (v.isNotBlank()) sb.append("${f.key}: $v\n")
            }
        }
        return sb.toString()
    }

    fun exportPlaintextZip(context: Context, name: String, password: String, text: String): File =
        zipWithPassword(context, name, password, "${safeFileName(name)}.txt", text)

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

    // 唤起系统分享
    fun share(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileProvider", zip
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, zip.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享备忘录备份"))
    }
}
