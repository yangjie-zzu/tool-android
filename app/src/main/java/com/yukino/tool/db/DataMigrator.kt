package com.yukino.tool.db

import android.content.Context
import android.util.Log
import com.yukino.tool.TAG
import com.yukino.tool.module.reader.ChapterIndex
import com.yukino.tool.module.reader.PageSpec
import com.yukino.tool.module.reader.Progress
import com.yukino.tool.module.reader.ReaderSettings
import com.yukino.tool.module.reader.ReaderStore
import com.yukino.tool.module.note.NoteCrypto
import com.yukino.tool.module.scan.ScanItem
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * 旧数据 → SQLite 一次性迁移引擎。
 *
 * 触发: 升级安装后首次启动, MainActivity 检测到"迁移标记不全 且 存在旧数据痕迹"时
 *       强制进入迁移页(不可跳过); 全新安装(无任何旧数据痕迹)由 markFreshInstall 静默补标记。
 * 过程: web → scan → reader → note 顺序迁移, 每模块独立事务、独立标记、失败互不影响;
 *       note 是唯一可能需要主密码的(v1/v2旧加密格式), 走 NeedPassword 状态由页面收集密码。
 * 幂等: 迁移成功才写标记、才删旧数据; 失败保留旧存储, 重试只重跑 FAILED 模块。
 */
object DataMigrator {

    enum class Module(val label: String) { WEB("浏览历史"), SCAN("扫码记录"), READER("书架"), NOTE("备忘录") }
    enum class Phase { PENDING, RUNNING, DONE, SKIPPED, FAILED }

    data class ModuleState(
        val module: Module,
        val phase: Phase = Phase.PENDING,
        val processed: Int = 0,
        val total: Int = 0,
        val message: String = ""
    )

    data class MigrationState(
        val modules: List<ModuleState> = Module.entries.map { ModuleState(it) },
        val needPassword: Boolean = false,     // note 等待输入主密码
        val passwordError: String? = null,     // 上次密码错误信息
        val logs: List<String> = emptyList(),
        val finished: Boolean = false,
        val allSuccess: Boolean = false
    ) {
        // 总进度: 各模块条数加权(空模块按1兜底)
        val fraction: Float
            get() {
                var done = 0f
                var total = 0f
                modules.forEach { m ->
                    val t = if (m.total > 0) m.total else 1
                    val d = when (m.phase) {
                        Phase.DONE, Phase.SKIPPED -> t.toFloat()
                        Phase.RUNNING -> m.processed.toFloat()
                        Phase.PENDING, Phase.FAILED -> 0f
                    }
                    done += d
                    total += t
                }
                return if (total == 0f) 1f else done / total
            }

        val failedModules: List<Module> get() = modules.filter { it.phase == Phase.FAILED }.map { it.module }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MigrationState())
    val state: StateFlow<MigrationState> = _state

    private var running = false
    private var passwordInput: CompletableDeferred<String?>? = null

    // ---------- 触发判定 ----------

    private fun flagged(context: Context, module: Module): Boolean {
        AppDb.get(context).rawQuery(
            "SELECT 1 FROM migration_flag WHERE module = ?", arrayOf(module.name)
        ).use { return it.moveToFirst() }
    }

    private fun markDone(context: Context, module: Module) {
        AppDb.get(context).execSQL(
            "INSERT OR REPLACE INTO migration_flag(module, done_at) VALUES(?,?)",
            arrayOf(module.name, System.currentTimeMillis())
        )
    }

    // 旧数据痕迹(与标记无关, 用于区分"全新安装"和"升级用户")
    private fun hasLegacyData(context: Context): Boolean {
        val spWeb = context.getSharedPreferences("web_history", Context.MODE_PRIVATE).contains("items")
        val spScan = context.getSharedPreferences("scan_history", Context.MODE_PRIVATE).contains("items")
        val readerDir = File(context.filesDir, "reader")
        val readerFiles = readerDir.listFiles()?.any {
            it.name == "books.json" || it.name == "settings.json" || (it.name.endsWith(".json") && it.parentFile.name == "specs")
        } == true
        val noteFile = File(context.filesDir, "note.json").exists()
        return spWeb || spScan || readerFiles || noteFile
    }

    // 是否需要强制迁移: 标记不全 且 确有旧数据
    fun needsMigration(context: Context): Boolean {
        val incomplete = Module.entries.any { !flagged(context, it) }
        return incomplete && hasLegacyData(context)
    }

    // 全新安装(或旧数据本为空): 静默补齐标记, 不进迁移页
    fun markFreshInstall(context: Context) {
        scope.launch {
            Module.entries.forEach { if (!flagged(context, it)) markDone(context, it) }
        }
    }

    // ---------- 迁移执行 ----------

    fun start(context: Context) = startInternal(context, Module.entries)

    // 只重跑失败项(成功/跳过的不动)
    fun retryFailed(context: Context) {
        val failed = _state.value.failedModules
        if (failed.isNotEmpty()) startInternal(context, failed)
    }

    // 跳过失败项: 标记为已完成, 失败模块保持旧存储读写(等价停留在旧版本), 旧数据不删
    fun skipFailed(context: Context) {
        scope.launch {
            _state.value.failedModules.forEach { markDone(context, it) }
            _state.value = _state.value.copy(finished = true, allSuccess = true)
        }
    }

    // 页面提交主密码(note v1/v2迁移用); null=用户放弃
    fun submitPassword(password: String?) {
        passwordInput?.complete(password)
    }

    private fun startInternal(context: Context, modules: List<Module>) {
        if (running) return
        running = true
        val app = context.applicationContext
        _state.value = MigrationState(
            modules = Module.entries.map { m ->
                val prev = _state.value.modules.firstOrNull { it.module == m }
                if (m in modules) ModuleState(m) else prev ?: ModuleState(m)
            }
        )
        scope.launch {
            modules.forEach { module ->
                update { s, m ->
                    s.copy(modules = s.modules.map { if (it.module == module) m(it.copy(phase = Phase.RUNNING, processed = 0, message = "")) else it })
                }
                try {
                    when (module) {
                        Module.WEB -> migrateWeb(app)
                        Module.SCAN -> migrateScan(app)
                        Module.READER -> migrateReader(app)
                        Module.NOTE -> migrateNote(app)
                    }
                    update { s, m ->
                        s.copy(modules = s.modules.map {
                            if (it.module == module && it.phase == Phase.RUNNING) m(it.copy(phase = Phase.DONE)) else it
                        })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "迁移[$module]失败", e)
                    update { s, m ->
                        s.copy(modules = s.modules.map {
                            if (it.module == module) m(it.copy(phase = Phase.FAILED, message = e.message ?: "未知错误")) else it
                        })
                    }
                }
            }
            val failed = _state.value.failedModules.isEmpty()
            update { s, m -> s.copy(finished = true, allSuccess = failed) }
            running = false
        }
    }

    private fun update(transform: (MigrationState, (ModuleState) -> ModuleState) -> MigrationState) {
        _state.value = transform(_state.value) { it }
    }

    private fun log(message: String) {
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date()) + "  " + message
        Log.i(TAG, "迁移: $message")
        _state.value = _state.value.copy(logs = (_state.value.logs + line).takeLast(200))
    }

    private fun progress(module: Module, processed: Int, total: Int, message: String = "") {
        _state.value = _state.value.copy(modules = _state.value.modules.map {
            if (it.module == module) it.copy(processed = processed, total = total, message = message) else it
        })
    }

    // ---------- 各模块迁移 ----------

    // 旧版浏览历史条目(icon是Base64字符串)
    @Serializable
    private data class LegacyWebHistoryItem(
        val url: String,
        val title: String = "",
        val time: Long,
        val icon: String? = null
    )

    private fun migrateWeb(context: Context) {
        val sp = context.getSharedPreferences("web_history", Context.MODE_PRIVATE)
        val raw = sp.getString("items", null)
        if (raw == null) {
            log("浏览历史: 无旧数据")
            markDone(context, Module.WEB)
            progress(Module.WEB, 1, 1, "无旧数据")
            _state.value = _state.value.copy(modules = _state.value.modules.map {
                if (it.module == Module.WEB) it.copy(phase = Phase.SKIPPED) else it
            })
            return
        }
        val items = runCatching {
            json.decodeFromString<List<LegacyWebHistoryItem>>(raw)
        }.getOrElse { error("旧浏览历史解析失败: ${it.message}") }
        log("浏览历史: 解析到 ${items.size} 条")
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            items.forEachIndexed { i, item ->
                val st = db.compileStatement("INSERT OR REPLACE INTO web_history(url, title, time, icon) VALUES(?,?,?,?)")
                st.bindString(1, item.url)
                st.bindString(2, item.title)
                st.bindLong(3, item.time)
                val iconBytes = item.icon?.let { b64 -> runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull() }
                if (iconBytes != null) st.bindBlob(4, iconBytes) else st.bindNull(4)
                st.executeInsert()
                progress(Module.WEB, i + 1, items.size)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        sp.edit().remove("items").apply()
        markDone(context, Module.WEB)
        log("浏览历史: 迁移完成(${items.size}条), 旧存储已清除")
    }

    private fun migrateScan(context: Context) {
        val sp = context.getSharedPreferences("scan_history", Context.MODE_PRIVATE)
        val raw = sp.getString("items", null)
        if (raw == null) {
            log("扫码记录: 无旧数据")
            markDone(context, Module.SCAN)
            _state.value = _state.value.copy(modules = _state.value.modules.map {
                if (it.module == Module.SCAN) it.copy(phase = Phase.SKIPPED) else it
            })
            return
        }
        val items = runCatching {
            json.decodeFromString<List<ScanItem>>(raw)
        }.getOrElse { error("旧扫码记录解析失败: ${it.message}") }
        log("扫码记录: 解析到 ${items.size} 条")
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            items.forEachIndexed { i, item ->
                val st = db.compileStatement("INSERT OR IGNORE INTO scan_history(content, time) VALUES(?,?)")
                st.bindString(1, item.content)
                st.bindLong(2, item.time)
                st.executeInsert()
                progress(Module.SCAN, i + 1, items.size)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        sp.edit().remove("items").apply()
        markDone(context, Module.SCAN)
        log("扫码记录: 迁移完成(${items.size}条), 旧存储已清除")
    }

    @Serializable
    private data class LegacyReaderBook(
        val id: String,
        val title: String,
        val sourceUri: String,
        val cachePath: String,
        val encoding: String,
        val totalChars: Long,
        val chapters: List<ChapterIndex> = emptyList(),
        val addedAt: Long,
        val lastReadAt: Long,
        val progress: Progress = Progress(),
        val fileSize: Long = 0L
    )

    private fun migrateReader(context: Context) {
        val dir = File(context.filesDir, "reader")
        val booksFile = File(dir, "books.json")
        val settingsFile = File(dir, "settings.json")
        if (!booksFile.exists() && !settingsFile.exists()) {
            log("书架: 无旧数据")
            markDone(context, Module.READER)
            _state.value = _state.value.copy(modules = _state.value.modules.map {
                if (it.module == Module.READER) it.copy(phase = Phase.SKIPPED) else it
            })
            return
        }
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            // 书架
            if (booksFile.exists()) {
                val books = runCatching {
                    json.decodeFromString<List<LegacyReaderBook>>(booksFile.readText())
                }.getOrElse { error("旧书架解析失败: ${it.message}") }
                log("书架: 解析到 ${books.size} 本")
                books.forEachIndexed { i, b ->
                    val st = db.compileStatement(
                        "INSERT INTO reader_book(id, title, source_uri, cache_path, encoding, total_chars, " +
                            "chapters, added_at, last_read_at, progress_offset, progress_percent, file_size) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                    )
                    st.bindString(1, b.id)
                    st.bindString(2, b.title)
                    st.bindString(3, b.sourceUri)
                    st.bindString(4, b.cachePath)
                    st.bindString(5, b.encoding)
                    st.bindLong(6, b.totalChars)
                    st.bindString(7, json.encodeToString(b.chapters))
                    st.bindLong(8, b.addedAt)
                    st.bindLong(9, b.lastReadAt)
                    st.bindLong(10, b.progress.globalCharOffset)
                    st.bindDouble(11, b.progress.percent)
                    st.bindLong(12, b.fileSize)
                    st.executeInsert()
                    progress(Module.READER, i + 1, books.size, b.title)
                }
            }
            // 设置
            if (settingsFile.exists()) {
                val settings = runCatching {
                    json.decodeFromString<ReaderSettings>(settingsFile.readText())
                }.getOrElse { error("旧阅读设置解析失败: ${it.message}") }
                ReaderStore.saveSettings(context, settings)
                log("阅读设置: 已迁移")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // 分页缓存(书架行已在库, 可逐个搬; 失败只丢缓存)
        val specsDir = File(dir, "specs")
        var specsMoved = 0
        specsDir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
            runCatching {
                @Serializable
                data class SpecsCache(val typoKey: Int, val totalChars: Long, val specs: List<PageSpec>)
                val c = json.decodeFromString<SpecsCache>(f.readText())
                ReaderStore.saveSpecs(context, f.nameWithoutExtension, c.typoKey, c.totalChars, c.specs)
                f.delete()
                specsMoved++
            }
        }
        // 旧json清除(正文文本缓存保留, cache_path 还指着)
        booksFile.delete()
        settingsFile.delete()
        markDone(context, Module.READER)
        log("书架: 迁移完成, 分页缓存 $specsMoved 项, 旧json已清除")
    }

    private suspend fun migrateNote(context: Context) {
        val noteFile = File(context.filesDir, "note.json")
        if (!noteFile.exists()) {
            log("备忘录: 无旧数据")
            markDone(context, Module.NOTE)
            _state.value = _state.value.copy(modules = _state.value.modules.map {
                if (it.module == Module.NOTE) it.copy(phase = Phase.SKIPPED) else it
            })
            return
        }
        // v3(密文已在字段里)可无密码静默导入
        val silent = NoteCrypto.importV3File(context)
        if (silent != null) {
            log("备忘录: 静默导入完成($silent 条), 旧文件已删除")
            markDone(context, Module.NOTE)
            return
        }
        // v1/v2 旧加密格式: 需要主密码
        while (true) {
            log("备忘录: 旧加密格式, 等待输入主密码")
            _state.value = _state.value.copy(needPassword = true, passwordError = null)
            val password = CompletableDeferred<String?>()
            passwordInput = password
            val input = runCatching { password.await() }.getOrNull()
            passwordInput = null
            _state.value = _state.value.copy(needPassword = false)
            if (input == null) {
                // 用户放弃: 该模块保持旧存储, 页面上可重试或跳过
                log("备忘录: 跳过(未输入主密码, 旧数据保留)")
                _state.value = _state.value.copy(modules = _state.value.modules.map {
                    if (it.module == Module.NOTE) it.copy(phase = Phase.FAILED, message = "未验证主密码, 旧数据保留") else it
                })
                return
            }
            try {
                val count = NoteCrypto.importLegacyFile(context, input)
                log("备忘录: 导入完成($count 条), 旧文件已删除")
                markDone(context, Module.NOTE)
                _state.value = _state.value.copy(passwordError = null)
                return
            } catch (e: Exception) {
                _state.value = _state.value.copy(passwordError = e.message ?: "导入失败")
                log("备忘录: 导入失败(${e.message})")
            }
        }
    }
}
