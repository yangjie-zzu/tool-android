package com.yukino.tool.module.note

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

/*
 * 备忘录备份(方案见 docs/note-backup-plan.md):
 *  - 备份 = 标准 ZIP(密码可选,任何解压软件可开)内含明文 JSON(全部条目含secret字段明文),
 *    写入用户选定的 SAF 目录。密码现场输入不存储,备份文件不依赖主密码。
 *  - 变动数 X、备份次数 N 都是实时计算: 本机只存"底版指纹表"(备份时刻每条目的内容哈希,
 *    对密文计算)、"变动时间戳"和"备份记录列表",打开弹窗时现算。
 *  - 恢复 = 纯导入追加: 备份条目全部加到列表末尾(id冲突换新id),不替换不去重不动现有数据。
 */

// 备份 ZIP 内的 JSON: 全部字段均为明文
@Serializable
data class NoteBackupFile(
    val format: String = "note-backup-v1",
    val exportedAt: Long = 0,
    val entries: List<NoteEntry> = emptyList()
)

// 一条备份记录(时间+文件名),展示于提醒弹窗
@Serializable
data class NoteBackupLogItem(val time: Long, val fileName: String)

// 提醒条/弹窗展示用的实时状态快照
internal data class BackupStatusUi(
    val pending: Int,        // X: 与底版的差异条数(新增+删除+修改)
    val count: Int,          // N: 自最近一次变动以来的成功备份次数
    val lastChange: Long,    // 最近一次数据变动时间(关闭提醒的比对基准)
    val log: List<NoteBackupLogItem>
)

object NoteBackup {

    private const val PREFS = "note_backup"
    private const val KEY_DIR = "dir_uri"
    private const val KEY_LAST_CHANGE = "last_change_at"
    private const val KEY_LOG = "log"
    private const val BASELINE_NAME = "note_backup_baseline.json"
    private const val LOG_LIMIT = 200   // 备份记录条数上限,防无限增长

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    sealed interface Outcome {
        data class Success(val fileName: String, val uri: Uri) : Outcome
        object Cancelled : Outcome
        object NoData : Outcome
        data class Failed(val message: String) : Outcome
    }

    // ---------- 备份目录(SAF) ----------

    fun dirUri(context: Context): Uri? =
        prefs(context).getString(KEY_DIR, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun saveDir(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_DIR, uri.toString()).apply()
    }

    fun dirDisplayName(context: Context): String? {
        val dir = dirUri(context) ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            dir, DocumentsContract.getTreeDocumentId(dir)
        )
        return queryDisplayName(context, docUri) ?: dir.lastPathSegment
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    // ---------- 变动时间戳 / 备份记录 ----------

    // 数据变动点调用(保存条目/修改主密码/恢复导入完成): N 由此自然归零
    fun markChanged(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHANGE, System.currentTimeMillis()).apply()
    }

    fun lastChangeAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHANGE, 0L)

    fun log(context: Context): List<NoteBackupLogItem> = runCatching {
        json.decodeFromString<List<NoteBackupLogItem>>(
            prefs(context).getString(KEY_LOG, "[]") ?: "[]"
        )
    }.getOrDefault(emptyList())

    // N = 备份记录里晚于最近变动时间的条数(实时计算,不存数字)
    fun backupCountSinceChange(context: Context): Int {
        val lc = lastChangeAt(context)
        return log(context).count { it.time > lc }
    }

    private fun appendLog(context: Context, item: NoteBackupLogItem) {
        val all = (log(context) + item).takeLast(LOG_LIMIT)
        prefs(context).edit().putString(KEY_LOG, json.encodeToString(all)).apply()
    }

    // ---------- 底版指纹 / 变动数 X ----------

    // 对"条目在列表中的位置 + 整条内容(密文态)"取哈希;改一个字/挪位置哈希即变
    private fun fingerprints(entries: List<NoteEntry>): Map<Long, String> {
        val md = MessageDigest.getInstance("SHA-256")
        return entries.mapIndexed { idx, e ->
            val digest = md.digest("$idx|${json.encodeToString(e)}".toByteArray())
            e.id to digest.joinToString("") { "%02x".format(it) }
        }.toMap()
    }

    private fun readBaseline(context: Context): Map<Long, String>? = runCatching {
        val f = File(context.filesDir, BASELINE_NAME)
        if (!f.exists()) null
        else json.decodeFromString<Map<Long, String>>(f.readText())
    }.getOrNull()

    private fun writeBaseline(context: Context, map: Map<Long, String>) {
        File(context.filesDir, BASELINE_NAME).writeText(json.encodeToString(map))
    }

    // X: 从未备份过=全部条数;否则=新增+删除+内容变化条数
    fun pendingChanges(context: Context): Int = runCatching {
        val entries = NoteCrypto.readPlain(context)
        val base = readBaseline(context) ?: return entries.size
        val cur = fingerprints(entries)
        var x = 0
        cur.forEach { (id, h) -> if (base[id] != h) x++ }
        base.forEach { (id, _) -> if (id !in cur) x++ }
        x
    }.getOrDefault(0)

    // 实时状态快照(提醒条与弹窗共用)
    internal fun status(context: Context): BackupStatusUi = BackupStatusUi(
        pending = pendingChanges(context),
        count = backupCountSinceChange(context),
        lastChange = lastChangeAt(context),
        log = log(context).asReversed()   // 最新在前
    )

    // ---------- 备份 ----------

    /*
     * 执行备份(主密钥由调用方在主线程上下文取好传入,认证弹窗不能在IO线程弹)。
     * 成功: 写入SAF目录+刷新底版+追加记录,返回文件名与uri(供分享)。
     */
    suspend fun backup(context: Context, zipPassword: String?, key: ByteArray): Outcome {
        val dir = dirUri(context) ?: return Outcome.Failed("未配置备份目录")
        val entries = withContext(Dispatchers.IO) { NoteCrypto.readPlain(context) }
        if (entries.isEmpty()) return Outcome.NoData
        return withContext(Dispatchers.IO) {
            runCatching {
                val secrets = NoteCrypto.readSecrets(context, key)
                val plain = entries.map { e ->
                    e.copy(fields = e.fields.map { f ->
                        if (f.secret) f.copy(value = secrets["${e.id}:${f.key}"] ?: "") else f
                    })
                }
                // 文件名自带用途说明: 未来在电脑/网盘里看到也能认出这是可导入本应用恢复的备份
                val name = "备忘录备份_" +
                    SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) +
                    "_可导入个人工具集恢复"
                val workDir = File(context.cacheDir, "note_backup").apply {
                    mkdirs(); listFiles()?.forEach { it.delete() }
                }
                val inner = File(workDir, "$name.json").apply {
                    writeText(json.encodeToString(NoteBackupFile(exportedAt = System.currentTimeMillis(), entries = plain)))
                }
                val zip = File(workDir, "$name.zip")
                val zf = ZipFile(zip)
                if (!zipPassword.isNullOrEmpty()) zf.setPassword(zipPassword.toCharArray())
                zf.addFile(inner, ZipParameters().apply {
                    compressionMethod = CompressionMethod.DEFLATE
                    if (!zipPassword.isNullOrEmpty()) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                    fileNameInZip = "$name.json"
                })
                inner.delete()
                // 写入SAF目录(同名时提供方自动改名,实际文件名以查询结果为准)
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    dir, DocumentsContract.getTreeDocumentId(dir)
                )
                val docUri = DocumentsContract.createDocument(
                    context.contentResolver, parent, "application/zip", name
                ) ?: error("在备份目录创建文件失败")
                context.contentResolver.openOutputStream(docUri)?.use { out ->
                    zip.inputStream().use { it.copyTo(out) }
                } ?: error("打开备份文件写入流失败")
                val realName = queryDisplayName(context, docUri) ?: "$name.zip"
                zip.delete()
                writeBaseline(context, fingerprints(entries))
                appendLog(context, NoteBackupLogItem(System.currentTimeMillis(), realName))
                Outcome.Success(realName, docUri)
            }.getOrElse { Outcome.Failed(it.message ?: "备份失败") }
        }
    }

    // ---------- 恢复(纯导入) ----------

    // 读取备份ZIP并解出明文JSON。ZIP密码错误抛"ZIP密码错误",非备份文件抛相应消息
    suspend fun readBackup(context: Context, uri: Uri, zipPassword: String?): NoteBackupFile =
        withContext(Dispatchers.IO) {
            val workDir = File(context.cacheDir, "note_restore").apply {
                mkdirs(); listFiles()?.forEach { it.delete() }
            }
            val zip = File(workDir, "backup.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                zip.outputStream().use { input.copyTo(it) }
            } ?: error("无法读取所选文件")
            val zf = ZipFile(zip)
            if (zf.isEncrypted) zf.setPassword((zipPassword ?: "").toCharArray())
            val header = zf.fileHeaders.firstOrNull() ?: error("备份文件是空的")
            try {
                zf.extractFile(
                    header, workDir.absolutePath, "backup.json",
                    null as net.lingala.zip4j.model.UnzipParameters?
                )
                json.decodeFromString<NoteBackupFile>(File(workDir, "backup.json").readText())
            } catch (e: net.lingala.zip4j.exception.ZipException) {
                if ((e.message ?: "").contains("password", ignoreCase = true)) error("ZIP密码错误")
                else error("文件损坏或不是备忘录备份")
            } catch (e: Exception) {
                error("不是备忘录备份文件")
            }
        }

    /*
     * 导入: 备份条目全部追加到列表末尾;secret字段明文用当前主密钥重新加密;
     * 条目id与现有冲突时换新id(必能导入)。不动现有条目/主密码/指纹。返回导入条数。
     */
    suspend fun importBackup(context: Context, backup: NoteBackupFile, key: ByteArray): Int =
        withContext(Dispatchers.IO) {
            val existing = NoteCrypto.readPlain(context)
            val used = existing.mapTo(mutableSetOf()) { it.id }
            var next = System.currentTimeMillis()
            val imported = backup.entries.map { e ->
                var id = e.id
                if (id in used) {
                    do { id = next++ } while (id in used)
                }
                used.add(id)
                e.copy(id = id, fields = e.fields.map { f ->
                    if (f.secret && f.value.isNotEmpty()) f.copy(value = NoteCrypto.encryptField(key, f.value)) else f
                })
            }
            NoteCrypto.save(context, existing + imported)
            markChanged(context)
            imported.size
        }
}

// ---------- UI: 提醒条 / 备份弹窗 / 恢复相关对话框 ----------

// 数据变动提醒条(X>0时常驻所有备忘录页面底部,红色醒目,点击打开提醒弹窗)
@Composable
internal fun BackupBanner(pending: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "⚠ $pending 条变动未备份",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "去备份",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/*
 * 备份弹窗(提醒弹窗与设置·备份弹窗共用):
 *   showReminderInfo=true  提醒弹窗: 显示X/N/备份记录,负按钮"关闭提醒"(关闭逻辑由调用方实现)
 *   showReminderInfo=false 设置弹窗: 只有ZIP密码+备份,负按钮"取消"
 * 成功后切成功态: 文件名 + [分享] + 负按钮收尾。
 */
@Composable
internal fun BackupDialog(
    showReminderInfo: Boolean,
    pending: Int,
    count: Int,
    log: List<NoteBackupLogItem>,
    onBackup: suspend (String?) -> NoteBackup.Outcome,
    onShare: (Uri, String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf<NoteBackup.Outcome.Success?>(null) }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text(text = if (showReminderInfo) "备份提醒" else "备份") },
        text = {
            val done = success
            if (done == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showReminderInfo) {
                        Text(text = "待备份变动：$pending 条", fontSize = 14.sp)
                        Text(text = "本次变动后已备份：$count 次", fontSize = 14.sp)
                    }
                    NoteTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "ZIP密码(可留空=不加密)",
                        modifier = Modifier.fillMaxWidth(),
                        password = true
                    )
                    Text(
                        text = "ZIP密码app不保存，忘记将无法打开备份；留空则备份不加密。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showReminderInfo && log.isNotEmpty()) {
                        Text(
                            text = "备份记录",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(
                            modifier = Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            log.forEach {
                                Text(
                                    text = "${timeFmt.format(Date(it.time))}  ${it.fileName}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "✓ 已备份到备份目录", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "文件：${done.fileName}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            val done = success
            if (done == null) {
                TextButton(enabled = !working, onClick = {
                    scope.launch {
                        working = true
                        when (val r = onBackup(password.ifBlank { null })) {
                            is NoteBackup.Outcome.Success -> success = r
                            is NoteBackup.Outcome.Failed ->
                                android.widget.Toast.makeText(context, "备份失败: ${r.message}", android.widget.Toast.LENGTH_LONG).show()
                            NoteBackup.Outcome.NoData ->
                                android.widget.Toast.makeText(context, "没有可备份的数据", android.widget.Toast.LENGTH_SHORT).show()
                            NoteBackup.Outcome.Cancelled -> {}
                        }
                        working = false
                    }
                }) { Text(text = if (showReminderInfo) "立即备份" else "备份") }
            } else {
                TextButton(onClick = { onShare(done.uri, done.fileName) }) { Text(text = "分享") }
            }
        },
        dismissButton = {
            val done = success
            TextButton(enabled = !working, onClick = onClose) {
                Text(text = if (showReminderInfo) "关闭提醒" else if (done == null) "取消" else "完成")
            }
        }
    )
}

// ZIP密码输入(恢复加密备份用): 确定返回输入,取消返回null
internal class ZipPwdRequest(val onResult: (String?) -> Unit)

@Composable
internal fun ZipPwdDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "输入ZIP密码") },
        text = {
            NoteTextField(
                value = password,
                onValueChange = { password = it },
                label = "备份时设置的ZIP密码",
                modifier = Modifier.fillMaxWidth(),
                password = true
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text(text = "确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消恢复") } }
    )
}

// 恢复确认: 显示备份时间与条数,确定返回true
internal class RestoreConfirmRequest(val time: Long, val count: Int, val onResult: (Boolean) -> Unit)

@Composable
internal fun RestoreConfirmDialog(time: Long, count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "恢复备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "备份时间：${fmt.format(Date(time))}", fontSize = 14.sp)
                Text(text = "共 $count 条记录", fontSize = 14.sp)
                Text(
                    text = "将全部追加到列表末尾，不影响现有记录；同一备份重复恢复会出现重复条目。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(text = "导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } }
    )
}
