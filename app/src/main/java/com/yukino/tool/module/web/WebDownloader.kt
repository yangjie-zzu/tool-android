package com.yukino.tool.module.web

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.yukino.tool.TAG
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.core.isNotEmpty
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

//web下载器: 与webview解耦, 通知点击中断续传时进程/webview可能已不存在
object WebDownloader {

    private const val PREFS = "dl_resume"
    //每个下载的通知开关(默认关): url->bool, 确认弹窗与下载页面都能改
    private const val NOTIFY_PREFS = "web_download_notify"
    private val activeJobs = ConcurrentHashMap<String, Job>()
    //用户主动暂停/取消请求: 协程被cancel后由catch收尾分支消费, 区别于网络中断
    private val pausedRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cancelRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()

    //下载任务状态: 下载页面数据源(StateFlow驱动UI, 状态迁移落库, 进度只驻内存)
    enum class Status { RUNNING, PAUSED, INTERRUPTED, FAILED, DONE, CANCELED }

    data class DownloadState(
        val url: String,
        val name: String,
        val status: Status,
        val offset: Long = 0,
        val total: Long = -1,
        val speedBps: Long = 0,
        val mime: String? = null,
        val fileUri: String? = null,
        val error: String? = null,
        val notifyId: Int = 0
    )

    private val _states = MutableStateFlow<List<DownloadState>>(emptyList())
    val states: kotlinx.coroutines.flow.StateFlow<List<DownloadState>> = _states

    //打开下载页面触发器: 确认下载时自增, WebBrowser观察它弹页(纯对象传递免改层层参数)
    val openTick = androidx.compose.runtime.mutableIntStateOf(0)

    //状态迁移: 更新内存列表(最新在前)并落库; 加锁避免并发下载互相覆盖
    private fun updateState(context: Context, s: DownloadState) {
        synchronized(_states) {
            _states.value = listOf(s) + _states.value.filterNot { it.url == s.url }
        }
        DownloadStore.upsert(
            context,
            DownloadRecord(
                url = s.url, name = s.name, status = s.status.name,
                offset = s.offset, total = s.total, mime = s.mime,
                fileUri = s.fileUri, error = s.error
            )
        )
    }

    //进度更新: 高频(每秒), 只动内存不落库
    private fun updateProgress(url: String, offset: Long, total: Long, speedBps: Long) {
        synchronized(_states) {
            _states.value = _states.value.map {
                if (it.url == url) it.copy(offset = offset, total = total, speedBps = speedBps) else it
            }
        }
    }

    //移除任务条目(用户取消): 内存+库
    private fun removeState(context: Context, url: String) {
        synchronized(_states) {
            _states.value = _states.value.filterNot { it.url == url }
        }
        DownloadStore.remove(context, url)
    }

    //进程重启后从库恢复下载列表(浏览器启动时调一次); 下载中是瞬态, 重启遗留显示为已中断(断点快照仍在可继续)
    @Volatile
    private var persistedLoaded = false

    fun loadPersisted(context: Context) {
        if (persistedLoaded) return
        synchronized(this) {
            if (persistedLoaded) return
            persistedLoaded = true
            val records = DownloadStore.load(context)
            _states.value = records.map { r ->
                if (r.status == Status.RUNNING.name) {
                    val fixed = r.copy(status = Status.INTERRUPTED.name)
                    DownloadStore.upsert(context, fixed)
                    fixed
                } else {
                    r
                }
            }.map {
                DownloadState(
                    url = it.url, name = it.name, status = Status.valueOf(it.status),
                    offset = it.offset, total = it.total, mime = it.mime,
                    fileUri = it.fileUri, error = it.error
                )
            }
        }
    }

    data class Task(
        val url: String,
        val userAgent: String,
        val referer: String?,
        val cookie: String?,
        val contentDisposition: String?,
        val mimetype: String?
    )

    //任务通知id: 毫秒时间戳生成, 不随进程重启重置, 避免新任务顶掉通知栏残留的旧任务通知
    private fun newNotifyId(): Int = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()

    //该下载是否发通知: 默认关, 确认弹窗/下载页面按url单独开启
    fun isNotifyEnabled(context: Context, url: String): Boolean =
        context.getSharedPreferences(NOTIFY_PREFS, Context.MODE_PRIVATE).getBoolean(url, false)

    fun setNotifyEnabled(context: Context, url: String, enabled: Boolean) {
        context.getSharedPreferences(NOTIFY_PREFS, Context.MODE_PRIVATE).edit().putBoolean(url, enabled).apply()
    }

    fun download(context: Context, base: Task) {
        //同链接防并行: 已有任务在下载时忽略重复触发, 避免新任务覆盖/互踩未完成的下载
        if (activeJobs[base.url]?.isActive == true) {
            Log.i(TAG, "download: 同链接下载进行中, 忽略重复触发 ${base.url}")
            notifyBusy(context)
            return
        }
        val name = resolveFileName(base.url, base.contentDisposition, base.mimetype)
        //清理上次残留的暂停/取消标志(任务已结束后点按钮会遗留)
        pausedRequests.remove(base.url)
        cancelRequests.remove(base.url)
        //入库与打开用mime: 回调值缺失或太泛时按最终扩展名反推
        val mime = resolveMime(base.mimetype, name)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel("download", "download", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notifyId = newNotifyId()
        val resumePrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val job = CoroutineScope(Dispatchers.IO).launch {
            val builder = NotificationCompat.Builder(context, "download")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                //通知只在下载完成/失败时发(点击进下载页面); 过程状态在下载管理页面实时看
                .setContentIntent(downloadsPendingIntent(context))
            //通知权限: 异步请求不阻塞下载, 被拒时通知静默(下载照常)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                CoroutineScope(Dispatchers.Main).launch {
                    permissionRequester.request(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            fun notifySafely() {
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    notificationManager.notify(notifyId, builder.build())
                }
            }
            //API29+的MediaStore记录(不可续传失败时删除pending脏记录)与API28-的半截文件(不可续传失败时删除)
            var pendingUri: Uri? = null
            var targetFile: File? = null
            var offset = 0L            //已落盘字节(续传起点): 跨任务复用同名未完成下载
            var totalLen = -1L         //全文件总长, 未知为-1
            var serverSupportsRange = false
            try {
                //获取输出流: append=true续传追加, false首次/服务端不支持Range时截断重写
                fun openOutput(append: Boolean): OutputStream {
                    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val uri = pendingUri ?: run {
                            val values = ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, name)
                                put(MediaStore.Downloads.MIME_TYPE, mime)
                                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/tool")
                                put(MediaStore.Downloads.IS_PENDING, 1)
                            }
                            val created = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                ?: throw IOException("无法创建下载记录")
                            pendingUri = created
                            //创建即登记断点索引: 即使进程被杀, 点击中断通知也能续传
                            saveSnapshot(resumePrefs, base, created)
                            created
                        }
                        context.contentResolver.openOutputStream(uri, if (append) "wa" else "w")
                            ?: throw IOException("无法打开输出流")
                    } else {
                        val file = targetFile ?: run {
                            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "tool")
                            if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建下载目录")
                            var target = File(dir, name)
                            var seq = 1
                            while (target.exists()) {
                                val dot = name.lastIndexOf('.')
                                target = if (dot > 0) File(dir, "${name.substring(0, dot)}(${seq})${name.substring(dot)}")
                                else File(dir, "${name}(${seq})")
                                seq++
                            }
                            targetFile = target
                            target
                        }
                        FileOutputStream(file, append)
                    }
                }

                //跨任务续传: 从应用侧断点索引恢复上次未完成的pending记录(不依赖MediaStore查询),
                //复用同一记录续写, 新下载不再覆盖/另起副本; 记录失效则回退全新下载
                resumePrefs.getString(base.url, null)?.let { json ->
                    try {
                        val o = org.json.JSONObject(json)
                        val uri = Uri.parse(o.getString("pendingUri"))
                        //断点=文件实际字节数: 用fd seek到尾取真实大小;
                        //不能查MediaStore的SIZE列——IS_PENDING记录该列恒为0
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            offset = android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_END)
                        }
                        if (offset < 0) offset = 0
                        pendingUri = uri
                    } catch (e: Exception) {
                        Log.i(TAG, "download: 断点记录失效, 全新下载 ${base.url}")
                        resumePrefs.edit().remove(base.url).apply()
                        pendingUri = null
                        offset = 0
                    }
                }
                if (offset > 0) {
                    Log.i(TAG, "download: 复用未完成下载 $name offset=$offset")
                }
                //任务登记进下载列表(全新/续传都含断点起点)
                updateState(
                    context,
                    DownloadState(
                        url = base.url, name = name, status = Status.RUNNING,
                        offset = offset, total = -1, mime = mime, notifyId = notifyId
                    )
                )

                var attempts = 0
                HttpClient {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 10000
                    }
                }.use { httpClient ->
                    while (true) {
                        attempts++
                        try {
                            httpClient.prepareGet(base.url) {
                                headers {
                                    if (base.userAgent.isNotBlank()) append(HttpHeaders.UserAgent, base.userAgent)
                                    base.cookie?.let { append(HttpHeaders.Cookie, it) }
                                    //ktor的HttpHeaders未定义Referer常量
                                    base.referer?.let { append("Referer", it) }
                                    if (offset > 0) append(HttpHeaders.Range, "bytes=$offset-")
                                }
                                timeout {
                                    connectTimeoutMillis = 15000
                                    requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                                }
                            }.execute { res ->
                                if (offset > 0) {
                                    if (res.status.value == 206) {
                                        serverSupportsRange = true
                                    } else {
                                        //服务器无视Range返回全量200: 弃续传从头重写
                                        offset = 0
                                    }
                                }
                                if (res.headers[HttpHeaders.AcceptRanges]?.equals("bytes", true) == true) {
                                    serverSupportsRange = true
                                }
                                val contentLen = res.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
                                totalLen = if (contentLen > 0) offset + contentLen else -1L
                                var lastNotifyTime = System.currentTimeMillis()
                                var lastNotifyBytes = offset
                                var written = 0L
                                openOutput(append = offset > 0).use { out ->
                                    val channel = res.bodyAsChannel()
                                    while (!channel.isClosedForRead) {
                                        //读空闲超时: 15秒无数据到达即判定连接死亡进入续传重试,
                                        //否则网络中断时read无限挂起, 任务永久卡死且拦截后续重新下载
                                        val packet = withTimeout(15000) {
                                            channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                                        }
                                        while (packet.isNotEmpty) {
                                            val bytes = packet.readBytes()
                                            out.write(bytes)
                                            written += bytes.size
                                            //offset实时累加: 连接RST等异常路径抛出时也保留最新断点
                                            offset += bytes.size
                                            //进度通知节流为每秒一次, 同点计算速度与已下载/总大小
                                            val now = System.currentTimeMillis()
                                            if (now - lastNotifyTime > 1000) {
                                                val dtMs = (now - lastNotifyTime).coerceAtLeast(1)
                                                val speedBps = (offset - lastNotifyBytes) * 1000 / dtMs
                                                lastNotifyTime = now
                                                lastNotifyBytes = offset
                                                //过程不发通知, 进度只更新下载页面
                                                updateProgress(base.url, offset, totalLen, speedBps)
                                            }
                                        }
                                    }
                                }
                                //提前EOF校验: 正常FIN关闭但字节数不足时ktor不抛异常、流静默结束, 须显式判定为中断走续传
                                if (contentLen > 0 && written < contentLen) {
                                    throw IOException("连接提前关闭: 期望${contentLen}字节 实收${written}字节")
                                }
                            }
                            break //流正常结束=下载完成
                        } catch (e: Exception) {
                            //用户暂停/取消: 不做自动续传重试, 直接抛给外层收尾分支处理
                            if (cancelRequests.contains(base.url) || pausedRequests.contains(base.url)) throw e
                            //连接中断且服务端支持Range: 从已落盘位置续传重试
                            if (offset > 0 && serverSupportsRange && attempts < 3) {
                                Log.i(TAG, "download: 中断续传(第${attempts}次) $name offset=$offset")
                                continue
                            }
                            throw e
                        }
                    }
                }
                //流结束=下载完成; 清除断点索引; API29+解除pending并查最终名(重名被系统加了序号)
                resumePrefs.edit().remove(base.url).apply()
                var finalName = name
                pendingUri?.let { uri ->
                    val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    context.contentResolver.update(uri, values, null, null)
                    context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) finalName = c.getString(0) ?: finalName
                    }
                }
                val doneUri = pendingUri ?: FileProvider.getUriForFile(
                    context, context.applicationContext.packageName + ".fileProvider", targetFile!!
                )
                //本地完整性复核: 按文件实际大小复核(预期=总长, 未知时须大于0), 不符按失败处理
                var actualSize = -1L
                runCatching {
                    context.contentResolver.openFileDescriptor(doneUri, "r")?.use { pfd ->
                        actualSize = android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_END)
                    }
                }
                val expectedSize = if (totalLen > 0) totalLen else offset
                if (actualSize < 0 || actualSize != expectedSize) {
                    throw IOException("完整性校验失败: 实际${actualSize}字节 预期${expectedSize}字节")
                }
                Log.i(TAG, "download: 下载完成 $finalName")
                //完成态落列表(打开入口在下载页面条目上)
                updateState(
                    context,
                    DownloadState(
                        url = base.url, name = finalName, status = Status.DONE,
                        offset = if (totalLen > 0) totalLen else offset, total = totalLen,
                        mime = mime, fileUri = doneUri.toString(), notifyId = notifyId
                    )
                )
                builder.setContentTitle("下载完成: $finalName").setContentText("点击查看下载")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setProgress(0, 0, false)
                    .setAutoCancel(true)
                //通知默认关: 仅用户在确认弹窗/下载页面单独开启才发
                if (isNotifyEnabled(context, base.url)) notifySafely()
            } catch (e: Exception) {
                Log.i(TAG, "download: 失败收尾 offset=$offset serverSupportsRange=$serverSupportsRange totalLen=$totalLen")
                when {
                    //用户取消: 保留断点与半截文件(可继续/可重新下载/可删除), 条目标记已取消
                    cancelRequests.remove(base.url) -> {
                        updateState(
                            context,
                            DownloadState(
                                url = base.url, name = name, status = Status.CANCELED,
                                offset = offset, total = totalLen, mime = mime, notifyId = notifyId
                            )
                        )
                        Log.i(TAG, "download: 用户取消 $name offset=$offset")
                    }
                    //用户暂停: 保留断点(无通知, 状态在下载页面)
                    pausedRequests.remove(base.url) -> {
                        updateState(
                            context,
                            DownloadState(
                                url = base.url, name = name, status = Status.PAUSED,
                                offset = offset, total = totalLen, mime = mime, notifyId = notifyId
                            )
                        )
                        Log.i(TAG, "download: 用户暂停 $name offset=$offset")
                    }
                    serverSupportsRange && offset > 0 -> {
                        //服务端支持续传: 保留未完成下载, 下载页面可继续(无通知)
                        updateState(
                            context,
                            DownloadState(
                                url = base.url, name = name, status = Status.INTERRUPTED,
                                offset = offset, total = totalLen, mime = mime, notifyId = notifyId
                            )
                        )
                        Log.e(TAG, "download: 中断(已保留断点) $name offset=$offset", e)
                    }
                    else -> {
                        //无法续传: 清理半截文件与断点索引, 下次从头下载
                        resumePrefs.edit().remove(base.url).apply()
                        pendingUri?.let { context.contentResolver.delete(it, null, null) }
                        targetFile?.delete()
                        builder.setContentTitle("下载失败: $name").setContentText(e.message).setSmallIcon(android.R.drawable.stat_notify_error).setAutoCancel(true)
                        if (isNotifyEnabled(context, base.url)) notifySafely()
                        updateState(
                            context,
                            DownloadState(
                                url = base.url, name = name, status = Status.FAILED,
                                offset = offset, total = totalLen, mime = mime,
                                error = e.message, notifyId = notifyId
                            )
                        )
                        Log.e(TAG, "download: ", e)
                    }
                }
            }
        }
        activeJobs[base.url] = job
        job.invokeOnCompletion { activeJobs.remove(base.url) }
    }

    //从中断通知/外部入口恢复下载: 按url取断点快照重建任务
    fun resume(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(url, null) ?: run {
            Log.i(TAG, "resume: 无断点记录 $url")
            return
        }
        try {
            val o = org.json.JSONObject(json)
            download(
                context,
                Task(
                    url = url,
                    userAgent = o.optString("ua"),
                    referer = o.optString("referer").ifEmpty { null },
                    cookie = o.optString("cookie").ifEmpty { null },
                    contentDisposition = o.optString("cd").ifEmpty { null },
                    mimetype = o.optString("mime").ifEmpty { null }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "resume: ", e)
        }
    }

    //用户主动暂停: cancel下载协程, 断点与"已暂停"通知由catch收尾分支处理
    fun pause(url: String) {
        val job = activeJobs[url] ?: return
        if (!job.isActive) return
        pausedRequests.add(url)
        job.cancel()
    }

    //用户主动取消(下载页面): 任务在跑则cancel后由catch分支记CANCELED; 已结束(如暂停)直接置CANCELED
    //现场(断点+半截文件)都保留, 供继续/重新下载/删除
    fun cancelDownload(context: Context, url: String, notifyId: Int) {
        val job = activeJobs[url]
        if (job != null && job.isActive) {
            cancelRequests.add(url)
            job.cancel()
        } else {
            if (notifyId > 0) {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifyId)
            }
            synchronized(_states) {
                _states.value = _states.value.map {
                    if (it.url == url) it.copy(status = Status.CANCELED) else it
                }
            }
            DownloadStore.load(context).firstOrNull { it.url == url }?.let { r ->
                DownloadStore.upsert(context, r.copy(status = Status.CANCELED.name))
            }
        }
    }

    //重新下载(下载页面): 清断点快照与旧文件(半截/已完成都算), 从0全新开始; 通知开关沿用url已存设置不重置
    fun redownload(context: Context, url: String) {
        //同链接在跑时忽略(防重复点击)
        if (activeJobs[url]?.isActive == true) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(url, null)?.let { json ->
            runCatching {
                context.contentResolver.delete(
                    Uri.parse(org.json.JSONObject(json).getString("pendingUri")), null, null
                )
            }
        }
        prefs.edit().remove(url).apply()
        //已完成文件的旧产物: 按记录里的fileUri删, 避免重下后目录堆积(n)副本
        DownloadStore.load(context).firstOrNull { it.url == url }?.fileUri?.let { uriStr ->
            runCatching { context.contentResolver.delete(Uri.parse(uriStr), null, null) }
        }
        //从库里的记录恢复下载会话信息(ua/cookie等), 没有记录则仅带url下载
        DownloadStore.load(context).firstOrNull { it.url == url }?.let { r ->
            download(context, Task(url = url, userAgent = "", referer = null, cookie = null, contentDisposition = null, mimetype = r.mime))
        } ?: download(context, Task(url = url, userAgent = "", referer = null, cookie = null, contentDisposition = null, mimetype = null))
    }

    //删除记录(下载页面): 清断点与半截文件, 从列表与库中移除条目
    fun deleteRecord(context: Context, url: String, notifyId: Int) {
        if (activeJobs[url]?.isActive == true) return  //在跑的任务先取消再删
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(url, null)?.let { json ->
            runCatching {
                context.contentResolver.delete(
                    Uri.parse(org.json.JSONObject(json).getString("pendingUri")), null, null
                )
            }
        }
        prefs.edit().remove(url).apply()
        if (notifyId > 0) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifyId)
        }
        removeState(context, url)
    }

    //通知点击统一跳下载页面: 拉起WebActivity并带标记
    private fun downloadsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WebActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(WebActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    //打开已完成的下载(下载页面条目[打开]):
    //按处理器数量决定行为, 规避两类系统限制——隐式intent在新系统被拦截->显式component;
    //多处理器时resolveActivity返回系统内部ResolverActivity, 第三方显式启动会被拒->改用系统chooser
    fun openDownloaded(context: Context, state: DownloadState) {
        val uri = state.fileUri?.let(Uri::parse) ?: return
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, state.mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val candidates = context.packageManager.queryIntentActivities(openIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != "android" }
        //APK直达安装器: APK的mime注册者众多(解压缩/网盘/办公软件都把apk当zip), 多候选会弹chooser无法直达;
        //从候选中挑包名含packageinstaller的系统安装器显式启动, 未授权"安装未知应用"则先跳设置引导(授权持久, 之后直达)
        val isApk = state.mime?.equals("application/vnd.android.package-archive", ignoreCase = true) == true
        val installer = candidates.find { it.activityInfo.packageName.contains("packageinstaller", ignoreCase = true) }
        val finalIntent = when {
            isApk && installer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls() ->
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.applicationContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            isApk && installer != null -> openIntent.apply {
                setClassName(installer.activityInfo.packageName, installer.activityInfo.name)
            }
            candidates.size == 1 -> openIntent.apply {
                setClassName(candidates[0].activityInfo.packageName, candidates[0].activityInfo.name)
            }
            candidates.isEmpty() -> Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> Intent.createChooser(openIntent, "打开 ${state.name}")
        }
        runCatching { context.startActivity(finalIntent) }
            .onFailure { Log.e(TAG, "openDownloaded: ", it) }
    }

    //断点快照: pending uri + 会话信息(ua/referer/cookie/响应头文件名与类型), 续传与通知点击恢复都用它;
    //断点位置不入快照, 恢复时以文件实际大小(fd seek)为准
    private fun saveSnapshot(prefs: android.content.SharedPreferences, task: Task, pendingUri: Uri) {
        prefs.edit()
            .putString(
                task.url,
                org.json.JSONObject()
                    .put("pendingUri", pendingUri.toString())
                    .put("ua", task.userAgent)
                    .put("referer", task.referer ?: "")
                    .put("cookie", task.cookie ?: "")
                    .put("cd", task.contentDisposition ?: "")
                    .put("mime", task.mimetype ?: "")
                    .toString()
            )
            .apply()
    }

    //mimetype解析: 回调值缺失或octet-stream时按最终扩展名反推
    private fun resolveMime(mimetype: String?, name: String): String {
        mimetype?.takeIf { it.isNotBlank() && !"application/octet-stream".equals(it, true) }?.let { return it }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    //字节数人类可读: 1.5MB / 16.2MB / 230KB (下载页面进度也用)
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1fMB", mb)
        return String.format(java.util.Locale.US, "%.2fGB", mb / 1024)
    }

    //下载文件名解析: Content-Disposition优先(filename*支持UTF-8编码名), 其次URL尾段(解码), 兜底时间戳名;
    //清洗路径类非法字符; 无扩展名时按mimetype推断
    private fun resolveFileName(url: String, contentDisposition: String?, mimetype: String?): String {
        var name = ""
        if (!contentDisposition.isNullOrBlank()) {
            //RFC5987: filename*=UTF-8''%E4%B8%AD%E6%96%87.zip
            Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)?.let {
                name = runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrDefault("")
            }
            if (name.isBlank()) {
                Regex("filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))", RegexOption.IGNORE_CASE).find(contentDisposition)?.let {
                    name = (it.groupValues[1].ifBlank { it.groupValues[2] }).trim()
                }
            }
        }
        if (name.isBlank()) {
            val tail = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            name = runCatching { URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
        }
        if (name.isBlank()) name = "download_${System.currentTimeMillis()}"
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (name.isBlank()) name = "download_${System.currentTimeMillis()}"
        if (!name.contains('.')) {
            mimetype?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { mime ->
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.isNotBlank() }?.let { ext ->
                    name = "$name.$ext"
                }
            }
        }
        return name
    }

    private fun notifyBusy(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("download", "download", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(
                newNotifyId(),
                NotificationCompat.Builder(context, "download")
                    .setContentTitle("该链接正在下载中").setContentText("请等待当前下载完成")
                    .setSmallIcon(android.R.drawable.stat_sys_download).setAutoCancel(true).build()
            )
        }
    }
}
