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

    const val ACTION_RESUME = "com.yukino.tool.web.action.RESUME_DOWNLOAD"
    const val EXTRA_URL = "url"

    private const val PREFS = "dl_resume"
    private val activeJobs = ConcurrentHashMap<String, Job>()

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

    fun download(context: Context, base: Task) {
        //同链接防并行: 已有任务在下载时忽略重复触发, 避免新任务覆盖/互踩未完成的下载
        if (activeJobs[base.url]?.isActive == true) {
            Log.i(TAG, "download: 同链接下载进行中, 忽略重复触发 ${base.url}")
            notifyBusy(context)
            return
        }
        val name = resolveFileName(base.url, base.contentDisposition, base.mimetype)
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
                .setContentTitle("准备下载").setContentText(name).setSmallIcon(android.R.drawable.stat_sys_download)
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
                notifySafely()
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
                        context.contentResolver.openOutputStream(uri, "r")?.use { }
                        pendingUri = uri
                        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                            if (c.moveToFirst()) offset = c.getLong(0)
                        }
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
                                                builder.setContentTitle("下载中: $name")
                                                    .setContentText(
                                                        "${formatBytes(offset)}" +
                                                            (if (totalLen > 0) "/${formatBytes(totalLen)}" else "") +
                                                            " · ${formatBytes(speedBps)}/s"
                                                    )
                                                    .setProgress(
                                                        if (totalLen > 0) totalLen.toInt() else 0,
                                                        offset.toInt(), totalLen <= 0
                                                    )
                                                notifySafely()
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
                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(doneUri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                //按处理器数量决定点击行为, 规避两类系统限制:
                //-隐式PendingIntent在新系统被拦截 -> 显式component
                //-多处理器时resolveActivity返回系统内部ResolverActivity, 第三方显式启动会被拒 -> 改用系统chooser
                val candidates = context.packageManager.queryIntentActivities(openIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    .filter { it.activityInfo.packageName != "android" }
                //APK直达安装器: APK的mime注册者众多(解压缩/网盘/办公软件都把apk当zip), 多候选会弹chooser无法直达;
                //从候选中挑包名含packageinstaller的系统安装器显式启动, 未授权"安装未知应用"则先跳设置引导(授权持久, 之后直达)
                val isApk = mime.equals("application/vnd.android.package-archive", ignoreCase = true)
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
                    else -> Intent.createChooser(openIntent, "打开 $finalName")
                }
                val pi = PendingIntent.getActivity(
                    context, notifyId, finalIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                Log.i(TAG, "download: 下载完成 $finalName")
                builder.setContentTitle("下载完成: $finalName").setContentText(null)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setProgress(0, 0, false)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                notifySafely()
            } catch (e: Exception) {
                Log.i(TAG, "download: 失败收尾 offset=$offset serverSupportsRange=$serverSupportsRange totalLen=$totalLen")
                if (serverSupportsRange && offset > 0) {
                    //服务端支持续传: 保留未完成下载, 点击中断通知即可续传
                    builder.setContentTitle("下载中断: $name").setContentText("点击通知续传")
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentIntent(resumePendingIntent(context, notifyId, base.url))
                        .setAutoCancel(true)
                    notifySafely()
                    Log.e(TAG, "download: 中断(已保留断点) $name offset=$offset", e)
                } else {
                    //无法续传: 清理半截文件与断点索引, 下次从头下载
                    resumePrefs.edit().remove(base.url).apply()
                    pendingUri?.let { context.contentResolver.delete(it, null, null) }
                    targetFile?.delete()
                    builder.setContentTitle("下载失败: $name").setContentText(e.message).setSmallIcon(android.R.drawable.stat_notify_error).setAutoCancel(true)
                    notifySafely()
                    Log.e(TAG, "download: ", e)
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

    //断点快照: pending uri + 会话信息(ua/referer/cookie/响应头文件名与类型), 续传与通知点击恢复都用它
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

    private fun resumePendingIntent(context: Context, notifyId: Int, url: String): PendingIntent {
        val intent = Intent(context, DownloadResumeReceiver::class.java).apply {
            action = ACTION_RESUME
            putExtra(EXTRA_URL, url)
        }
        return PendingIntent.getBroadcast(
            context, notifyId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    //字节数人类可读: 1.5MB / 16.2MB / 230KB
    private fun formatBytes(bytes: Long): String {
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

//下载中断通知点击: 按url取断点快照恢复下载
class DownloadResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(WebDownloader.EXTRA_URL) ?: return
        WebDownloader.resume(context, url)
    }
}
