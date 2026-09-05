package com.yukino.tool.module.compress

import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.yukino.tool.TAG
import com.yukino.tool.components.text
import com.yukino.tool.util.FileNameCompare
import com.yukino.tool.util.OnTimer
import com.yukino.tool.util.ValueRef
import com.yukino.tool.util.rememberCurrentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.util.Date
import kotlin.math.log10
import kotlin.math.pow

class CompressItem(
    val path: String? = null,
    val isFolder: Boolean? = null,
    val size: Long? = null,
    val comment: String? = null,
    val createTime: Date? = null,
    val modifiedTime: Date? = null
)

class CompressFileInfo(
    val name: String? = null,
    val ext: String? = null,
    val size: Long? = null,
    val count: Int? = null,
    val items: List<CompressItem>? = null
)

fun displayName(name: String? = null, ext: String? = null): String {
    return "${name.text()}${if (ext == null) "" else "."}${ext.text()}"
}

fun openInArchive(
    parcelFileDescriptor: ParcelFileDescriptor,
    password: String? = null
): IInArchive {
    val fileDescriptor = parcelFileDescriptor.fileDescriptor
    val inStream = object : IInStream {
        override fun close() {
            parcelFileDescriptor.close()
        }

        override fun read(data: ByteArray?): Int {
            return android.system.Os.read(
                fileDescriptor,
                data,
                0,
                data?.size ?: 0
            )
        }

        override fun seek(offset: Long, seekOrigin: Int): Long {
            return android.system.Os.lseek(
                fileDescriptor,
                offset,
                seekOrigin
            )
        }

    }
    val inArchive = if (password.isNullOrEmpty()) {
        SevenZip.openInArchive(null, inStream)
    } else {
        SevenZip.openInArchive(null, inStream, password)
    }
    return inArchive
}

fun isPasswordError(message: String): Boolean {
    val lowercase = message.lowercase()
    return lowercase.contains("password") || lowercase.contains("encrypted") || lowercase.contains("加密")
}

// extract抛出的外层异常只有"Error extracting all items"，密码错误信息在cause链里
fun isPasswordError(error: Throwable): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (isPasswordError(cause.message ?: "")) {
            return true
        }
        cause = cause.cause
    }
    return false
}

@Composable
fun CompressDetail(
    url: String?
) {

    Log.i(TAG, "url: $url")
    var fileInfo by remember {
        mutableStateOf<CompressFileInfo?>(null)
    }

    var errMsg by remember {
        mutableStateOf<String?>(null)
    }

    var password by remember {
        mutableStateOf("")
    }

    var needPassword by remember {
        mutableStateOf(false)
    }

    val activity = rememberCurrentActivity()
    LaunchedEffect(url, password, needPassword) {
        if (url == null) return@LaunchedEffect
        errMsg = null
        try {
            val cursor = activity.contentResolver.query(Uri.parse(url), null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayName =
                        it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    val sizeIndex = it.getColumnIndexOrThrow(OpenableColumns.SIZE)
                    val size = if (!it.isNull(sizeIndex)) it.getLong(sizeIndex) else null
                    val name = displayName?.substringBeforeLast('.')
                    val ext = displayName?.substringAfterLast('.')
                    fileInfo = CompressFileInfo(
                        name = name,
                        ext = ext,
                        size = size
                    )
                    withContext(Dispatchers.IO) {
                        activity.contentResolver.openFileDescriptor(Uri.parse(url), "r")?.use { parcelFileDescriptor ->
                            val inArchive = openInArchive(
                                parcelFileDescriptor,
                                password.takeIf { password.isNotEmpty() }
                            )
                            val count = inArchive.numberOfItems
                            val simpleInArchive = inArchive.simpleInterface
                            val compressItems = simpleInArchive.archiveItems.map { item ->
                                CompressItem(
                                    path = item.path,
                                    isFolder = item.isFolder,
                                    size = item.size,
                                    comment = item.comment,
                                    createTime = item.creationTime,
                                    modifiedTime = item.lastWriteTime
                                )
                            }.sortedWith({ a, b ->
                                val p1 = a.path ?: ""
                                val p2 = b.path ?: ""
                                if (p1.startsWith(p2)) {
                                    return@sortedWith 1
                                } else if (p2.startsWith(p1)) {
                                    return@sortedWith -1
                                } else {
                                    return@sortedWith FileNameCompare.compare(p1, p2)
                                }
                            })
                            withContext(Dispatchers.Main) {
                                fileInfo = CompressFileInfo(
                                    name = name,
                                    ext = ext,
                                    size = size,
                                    count = count,
                                    items = compressItems
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            Log.e(TAG, "parse failed", e)
            if (isPasswordError(e)) {
                if (password.isNotEmpty()) {
                    errMsg = "密码错误或无法解密"
                }
                needPassword = true
            } else {
                errMsg = e.toString()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Text("解压", fontWeight = FontWeight.Bold)
        }
        fileInfo?.let {
            Row {
                Text(
                    text = "${
                        displayName(
                            it.name,
                            it.ext
                        )
                    } 大小: ${readableByteLength(it.size).text()} 数量: ${
                        it.count?.toString().text()
                    }",
                    fontWeight = FontWeight.Bold
                )
            }
            errMsg?.let {
                Text("解析错误：${errMsg.text()}")
            }
            if (needPassword) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("压缩包密码") },
                    singleLine = true,
                    isError = password.isNotEmpty() && errMsg != null
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 3.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
                    items = it.items ?: listOf(),
                    key = { _, item -> item.path ?: "" }
                ) { _, item ->
                    Row {
                        Box(modifier = Modifier.weight(1f)) {
                            Text(text = buildAnnotatedString {
                                withStyle(
                                    if (item.isFolder == true) {
                                        SpanStyle(
                                            fontWeight = FontWeight.Thin,
                                            fontStyle = FontStyle.Italic
                                        )
                                    } else {
                                        SpanStyle()
                                    }
                                ) {
                                    append("${item.path.text()} ${item.comment.text()} ")
                                }
                            })
                        }
                        Box {
                            Text(" ${if (item.isFolder != true) readableByteLength(item.size) else ""}", fontWeight = FontWeight.Thin)
                        }
                    }
                }
            }
        }
        var isFinish by remember {
            mutableStateOf(false)
        }
        var isRunning by remember {
            mutableStateOf(false)
        }
        var isError by remember {
            mutableStateOf(false)
        }
        var extractResult by remember {
            mutableStateOf<String?>(null)
        }
        var extractResultIsError by remember {
            mutableStateOf(false)
        }
        val totalRef = remember {
            ValueRef(0L)
        }
        val completeRef = remember {
            ValueRef(0L)
        }
        var progressState by remember {
            mutableStateOf(0f)
        }
        if (!isRunning) {
            val downloadPath =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            extractResult?.let { result ->
                Text(
                    text = result,
                    color = if (extractResultIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color(0xFF2E7D32)
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Row {
                val coroutineScope = rememberCoroutineScope()
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                Log.i(TAG, "extract start, hasPassword=${password.isNotEmpty()}")
                                isRunning = true
                                isError = false
                                extractResult = null
                                activity.contentResolver.openFileDescriptor(Uri.parse(url), "r")?.use { parcelFileDescriptor ->
                                    val inArchive = openInArchive(
                                        parcelFileDescriptor,
                                        password.takeIf { password.isNotEmpty() }
                                    )
                                    val simpleArchive = inArchive.simpleInterface
                                    var dirPath = "${downloadPath}/tool"
                                    if (!checkSingle(simpleArchive.archiveItems, fileInfo?.name)) {
                                        dirPath += "/${fileInfo?.name}"
                                    }
                                    val dir = File(dirPath)
                                    if (!dir.exists()) {
                                        dir.mkdirs()
                                    }
                                    inArchive.extract(
                                        null,
                                        false,
                                        // 该fork批量extract不使用openInArchive时绑定的密码，需实现ICryptoGetTextPassword提供
                                        object : IArchiveExtractCallback, ICryptoGetTextPassword {
                                        override fun cryptoGetTextPassword(): String {
                                            return password
                                        }
                                        override fun setTotal(total: Long) {
                                            totalRef.value = total
                                        }

                                        override fun setCompleted(complete: Long) {
                                            completeRef.value = complete
                                        }

                                        override fun getStream(
                                            index: Int,
                                            extractAskMode: ExtractAskMode?
                                        ): ISequentialOutStream {
                                            val item = simpleArchive.getArchiveItem(index)
                                            val file = File(dir, item.path)
                                            val parent = file.parentFile
                                            if (parent != null) {
                                                if (!parent.exists()) {
                                                    parent.mkdirs()
                                                }
                                            }
                                            if (item.isFolder) {
                                                file.mkdirs()
                                                return ISequentialOutStream { 0 }
                                            } else {
                                                file.createNewFile()
                                                Log.i(TAG, "file: ${file.absolutePath}")
                                                val fileOutputStream = FileOutputStream(file)
                                                return ISequentialOutStream { data ->
                                                    fileOutputStream.write(data)
                                                    data?.size ?: 0
                                                }
                                            }
                                        }

                                        override fun prepareOperation(extractAskMode: ExtractAskMode?) {
                                        }

                                        override fun setOperationResult(extractOperationResult: ExtractOperationResult?) {
                                            // 该绑定对密码问题可能返回UNSUPPORTEDMETHOD/DATAERROR而非WRONG_PASSWORD
                                            val cryptoSuspect = extractOperationResult == ExtractOperationResult.WRONG_PASSWORD ||
                                                    extractOperationResult == ExtractOperationResult.UNSUPPORTEDMETHOD ||
                                                    extractOperationResult == ExtractOperationResult.DATAERROR
                                            if (extractOperationResult != null && extractOperationResult != ExtractOperationResult.OK) {
                                                if (cryptoSuspect && password.isNotEmpty()) {
                                                    throw SevenZipException("Wrong password")
                                                }
                                                if (cryptoSuspect && password.isEmpty()) {
                                                    throw SevenZipException("Encrypted or unsupported method")
                                                }
                                                throw SevenZipException(extractOperationResult.toString())
                                            }
                                        }
                                        },
                                        // 密码在openInArchive时已绑定，此处无需再传
                                    )
                                    inArchive.close()
                                }
                                Log.i(TAG, "extract done")
                                isFinish = true
                                extractResult = "解压完成"
                                extractResultIsError = false
                            } catch (e: Exception) {
                                isError = true
                                Log.e(TAG, "extract failed", e)
                                val isPassword = isPasswordError(e)
                                if (isPassword) {
                                    needPassword = true
                                }
                                extractResult = if (isPassword) {
                                    if (password.isEmpty()) "解压失败：该压缩包已加密，请输入密码" else "解压失败：密码错误"
                                } else {
                                    "解压失败"
                                }
                                extractResultIsError = true
                            } finally {
                                isRunning = false
                            }
                        }
                    }
                ) {
                    Text(
                        "${if (isFinish) "重新" else ""}解压至${
                            downloadPath.split('/').let { it[it.size - 1] }
                        }/tool/${fileInfo?.name}"
                    )
                }
            }
        } else {
            OnTimer {
                progressState = completeRef.value.toFloat() / totalRef.value
            }
            Row(modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = { progressState }
                )
            }
        }
    }
}

@Composable
fun SelectFile(
    navController: NavController
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row {
            Text("选择文件", fontWeight = FontWeight.Bold)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val coroutineScope = rememberCoroutineScope()
            val activity = rememberCurrentActivity()
            Button(
                onClick = {
                    coroutineScope.launch {
                        val uri = compressFilePicker.open(arrayOf("*/*"))
                        if (uri != null) {
                            activity.runOnUiThread {
                                navController.navigate(DecompressRoute(uri.toString()))
                            }
                        }
                    }
                }
            ) {
                Text("选择文件")
            }
        }
    }
}

fun readableByteLength(byteLength: Long?): String? {
    if (byteLength == null) return null
    if (byteLength <= 0) return "0"
    val units = arrayOf("B", "kB", "MB", "GB", "TB", "PB", "EB")
    val digitGroups = (log10(byteLength.toDouble()) / log10(1024.0)).toInt()
    return DecimalFormat("#,##0.##").format(byteLength / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}

fun checkSingle(archiveItems: Array<ISimpleInArchiveItem>, name: String?): Boolean {
    val firsts = archiveItems.map { item -> item.path.split('/').let { it[0] } }.distinct()
    if (firsts.size != 1) {
        return false
    }
    return firsts[0] == name
}