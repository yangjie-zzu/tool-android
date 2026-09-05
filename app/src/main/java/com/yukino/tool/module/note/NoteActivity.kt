package com.yukino.tool.module.note

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yukino.tool.ui.theme.ToolTheme
import com.yukino.tool.util.findActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// 继承 FragmentActivity: androidx.biometric 的 BiometricPrompt 只接受 FragmentActivity
class NoteActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ToolTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NoteApp()
                }
            }
        }
    }
}

// "复制"链接在 AnnotatedString 里的注解标记
private const val TAG_COPY = "copy"

private const val TAG = "Note"

// 密码对话框请求: 非空时弹对话框；用户确定/取消后清空并回调结果(取消为null)，挂起方借此继续
private class PasswordRequest(val title: String, val onResult: (String?) -> Unit)

// 设置主密码对话框请求: 回调(密码, 是否同时启用指纹)，取消为null
private class SetupRequest(val onResult: (Pair<String, Boolean>?) -> Unit)

// 指纹认证结果: 成功(带加密对象)或出错(code为BiometricPrompt错误码)
private sealed interface BioAuth {
    data class Success(val crypto: BiometricPrompt.CryptoObject?) : BioAuth
    data class Error(val code: Int) : BioAuth
}

/*
 * 界面状态。没有全局"锁定/解锁"，全部按需验证:
 *   - entries 只含明文段(标题/预览随时可看)
 *   - secrets 是验证通过后解密的敏感值缓存，键为"条目id:字段名"
 *   - revealed 记录列表卡片上哪些加密字段已展开明文
 * 退到后台时清空 secrets/revealed，回来后查看/复制要重新验证
 */
private data class NoteUi(
    val entries: List<NoteEntry> = emptyList(),
    val secrets: Map<String, String> = emptyMap(),
    val revealed: Set<String> = emptySet(),
    val editing: NoteEntry? = null,
    val editingIsNew: Boolean = false,
    val showSettings: Boolean = false
)

// 设备是否具备可用指纹(已录入且硬件支持)
private fun canBiometric(activity: FragmentActivity): Boolean = runCatching {
    BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
}.getOrDefault(false)

/*
 * 挂起式系统指纹对话框: 认证结束(成功/出错)才返回。
 * crypto 非空时把认证与该加密操作绑定(解封VK的cipher)——认证成功后系统才放行 cipher.doFinal；
 * negativeText 是负按钮文案(比如"使用密码")，用户点它时返回 Error(错误码13)。
 * 注意: 依赖认证的加密工作要在拿到认证过的 cipher 后立刻做(见 NoteCrypto 顶部说明)
 */
private suspend fun biometricAuth(
    activity: FragmentActivity,
    title: String,
    negativeText: String = "取消",
    crypto: BiometricPrompt.CryptoObject? = null
): BioAuth = suspendCancellableCoroutine { cont ->
    if (!canBiometric(activity)) {
        cont.resume(BioAuth.Error(-2))
        return@suspendCancellableCoroutine
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.i(TAG, "指纹回调: succeeded, crypto=${if (result.cryptoObject != null) "有" else "null"}")
                cont.resume(BioAuth.Success(result.cryptoObject))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "指纹回调: error code=$errorCode msg=$errString")
                cont.resume(BioAuth.Error(errorCode))
            }

            override fun onAuthenticationFailed() {
                Log.i(TAG, "指纹回调: failed(单次不匹配，继续)")
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setNegativeButtonText(negativeText)
        .build()
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
    if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
}

@Composable
fun NoteApp() {

    val context = LocalContext.current
    val activity = context.findActivity() as? FragmentActivity ?: return
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var ui by remember { mutableStateOf(NoteUi()) }

    // ===== 按需验证的对话框请求(非空时弹对应对话框，结果交给挂起方继续业务) =====
    var pwdRequest by remember { mutableStateOf<PasswordRequest?>(null) }
    var setupRequest by remember { mutableStateOf<SetupRequest?>(null) }
    // 指纹弹窗展示期间不清缓存(弹窗会让Activity暂停)
    var authenticating by remember { mutableStateOf(false) }

    val biometricUsable = remember { canBiometric(activity) }

    // 进入页面: 读明文段(无需任何验证)
    LaunchedEffect(Unit) {
        ui = ui.copy(entries = withContext(Dispatchers.IO) { NoteCrypto.readPlain(context) })
    }

    // 退到后台清空敏感缓存
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !authenticating) {
                ui = ui.copy(secrets = emptyMap(), revealed = emptySet())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    //系统返回键: 编辑→详情/列表，详情→列表，设置→列表
    BackHandler(enabled = ui.editing != null || ui.showSettings) {
        ui = when {
            ui.editing != null -> ui.copy(editing = null, editingIsNew = false)
            else -> ui.copy(showSettings = false)
        }
    }

    fun copyText(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
    }

    // 弹"输入主密码"对话框(挂起): 返回输入内容；用户取消返回null
    suspend fun askPassword(title: String): String? = suspendCancellableCoroutine { cont ->
        val request = PasswordRequest(title) { result ->
            if (cont.isActive) cont.resume(result)
        }
        pwdRequest = request
        cont.invokeOnCancellation { if (pwdRequest == request) pwdRequest = null }
    }

    // 弹"设置主密码"对话框(挂起): 返回(密码, 是否同时启用指纹)；用户取消返回null
    suspend fun askSetupMaster(): Pair<String, Boolean>? = suspendCancellableCoroutine { cont ->
        val request = SetupRequest { result ->
            if (cont.isActive) cont.resume(result)
        }
        setupRequest = request
        cont.invokeOnCancellation { if (setupRequest == request) setupRequest = null }
    }

    // 主密码验证(挂起): 现场派生主密钥DK，密码错误提示后可重试；取消返回null
    suspend fun requestKeyByPassword(title: String): ByteArray? {
        while (true) {
            val password = askPassword(title) ?: return null
            try {
                return withContext(Dispatchers.Default) { NoteCrypto.deriveWithPassword(context, password) }
            } catch (e: Exception) {
                Toast.makeText(context, "主密码错误", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 用已拿到的主密钥启用指纹: 弹指纹认证，成功后把它封存进Keystore密钥
    suspend fun enableBiometricWith(key: ByteArray) {
        val cipher = NoteCrypto.bioWrapCipher()
        if (cipher == null) {
            Toast.makeText(context, "指纹不可用", Toast.LENGTH_SHORT).show()
            return
        }
        authenticating = true
        try {
            when (val result = biometricAuth(activity, "启用指纹", crypto = BiometricPrompt.CryptoObject(cipher))) {
                is BioAuth.Success -> {
                    val ok = try {
                        result.crypto?.cipher
                            ?.let { c -> withContext(Dispatchers.IO) { NoteCrypto.storeBioWrap(context, c, key) } }
                            ?: false
                    } catch (e: Exception) {
                        false
                    }
                    Toast.makeText(context, if (ok) "指纹已启用" else "启用失败", Toast.LENGTH_SHORT).show()
                }
                // 用户取消，不提示
                is BioAuth.Error -> {}
            }
        } finally {
            authenticating = false
        }
    }

    // 常规取钥(不含迁移): 指纹优先，负按钮/未启用指纹退回主密码
    suspend fun obtainKeyNormal(reason: String): ByteArray? {
        val cipher = NoteCrypto.bioDecryptCipher(context)
        if (NoteCrypto.biometricEnabled(context) && canBiometric(activity) && cipher != null) {
            Log.i(TAG, "obtainKey[$reason]: 走指纹路径")
            authenticating = true
            try {
                when (val result = biometricAuth(activity, reason, negativeText = "使用密码", crypto = BiometricPrompt.CryptoObject(cipher))) {
                    is BioAuth.Success -> {
                        val c = result.crypto?.cipher
                        if (c == null) {
                            // 部分biometric库版本在API 30+有"认证成功但CryptoObject为null"的bug，这里转主密码兜底，不静默无响应
                            Log.w(TAG, "obtainKey[$reason]: 认证成功但cryptoObject为null，转主密码")
                            Toast.makeText(context, "指纹通道异常，请使用主密码", Toast.LENGTH_SHORT).show()
                        } else {
                            return try {
                                val key = withContext(Dispatchers.Default) { NoteCrypto.unwrapWithBio(context, c) }
                                Log.i(TAG, "obtainKey[$reason]: 指纹解封主密钥成功(${key.size}字节)")
                                key
                            } catch (e: Exception) {
                                Log.e(TAG, "obtainKey[$reason]: 解封主密钥失败", e)
                                Toast.makeText(context, "解密失败: ${e.message}", Toast.LENGTH_LONG).show()
                                null
                            }
                        }
                    }
                    // 用户点了负按钮("使用密码")→ 继续走主密码；其他错误(取消/失败过多)到此为止
                    is BioAuth.Error -> {
                        Log.w(TAG, "obtainKey[$reason]: Error分支 code=${result.code}")
                        if (result.code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) return null
                    }
                }
            } finally {
                authenticating = false
            }
        } else {
            Log.i(TAG, "obtainKey[$reason]: 走主密码路径(biometricEnabled=${NoteCrypto.biometricEnabled(context)}, canBio=${canBiometric(activity)}, cipher!=null=${cipher != null})")
        }
        return requestKeyByPassword(reason)
    }

    /*
     * 拿主密钥DK的统一入口(挂起，按需验证):
     * v1旧格式 → 输一次主密码迁移(解开封存的旧VK，数据逐字段改密文)；
     * v2旧格式 → 正常验证拿DK后，把独立enc段拆成逐字段密文(失败下次重试)；
     * v3 → 直接走常规取钥(指纹优先，主密码兜底)。
     * 返回null = 用户取消或验证失败(提示已在内部给出)。
     */
    suspend fun obtainKey(reason: String): ByteArray? {
        if (NoteCrypto.isLegacy(context)) {
            // v1迁移: 旧格式没有校验值，只能"派生→解封旧VK"来验证密码，错误可重试
            Log.i(TAG, "obtainKey[$reason]: v1旧格式，进入迁移流程")
            while (true) {
                val password = askPassword("迁移加密格式，验证主密码") ?: return null
                try {
                    var hadBio = false
                    val dk = withContext(Dispatchers.Default) {
                        val derived = NoteCrypto.deriveLegacy(context, password)
                        hadBio = NoteCrypto.migrateLegacy(context, derived)   // 密码错在这里抛"主密码错误"
                        Log.i(TAG, "obtainKey[$reason]: v1→v3迁移完成, 原指纹启用=$hadBio")
                        derived
                    }
                    // 原来启用过指纹: 引导现场认证一次，把DK重新封存进Keystore，指纹继续可用
                    if (hadBio) enableBiometricWith(dk)
                    return dk
                } catch (e: Exception) {
                    Log.w(TAG, "obtainKey[$reason]: 迁移失败: ${e.message}")
                    Toast.makeText(
                        context,
                        if (e.message == "主密码错误") "主密码错误" else "迁移失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        val key = obtainKeyNormal(reason) ?: return null
        if (NoteCrypto.isV2(context)) {
            try {
                withContext(Dispatchers.IO) { NoteCrypto.migrateV2(context, key) }
                Log.i(TAG, "obtainKey[$reason]: v2→v3迁移完成(加密值拆入字段)")
            } catch (e: Exception) {
                Log.e(TAG, "obtainKey[$reason]: v2→v3迁移失败(下次重试)", e)
            }
        }
        return key
    }

    // 解密全部敏感值并写入缓存(挂起)。返回null = 用户取消或解密失败(提示已在内部给出)
    suspend fun unlockSecrets(reason: String): Map<String, String>? {
        val key = obtainKey(reason) ?: return null
        return try {
            val secrets = withContext(Dispatchers.IO) { NoteCrypto.readSecrets(context, key) }
            Log.i(TAG, "unlockSecrets[$reason]: 解密成功，${secrets.size}项")
            secrets
        } catch (e: Exception) {
            Log.e(TAG, "unlockSecrets[$reason]: 解密失败", e)
            Toast.makeText(context, "解密失败: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    // 首次设置主密码(挂起): 可勾选同时启用指纹。成功返回新生成的主密钥，取消返回null
    suspend fun setupMasterFlow(): ByteArray? {
        val input = askSetupMaster() ?: return null
        val (password, useBio) = input
        return try {
            val key = withContext(Dispatchers.Default) { NoteCrypto.setupMaster(context, password) }
            if (useBio) enableBiometricWith(key)
            key
        } catch (e: Exception) {
            Toast.makeText(context, "设置失败: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    /*
     * 保存(新增/修改/删除共用)。
     * v3不变量: ui.entries里secret字段的value永远是密文。编辑器返回的条目里secret值是用户输入的明文，
     * 这里统一处理:
     *   secretTouched=false(没动加密内容) → 被编辑条目的secret字段恢复成改动前的密文，
     *       只写plain、不碰任何密文，也就无需验证主密码；
     *   secretTouched=true → 验证(指纹/主密码)拿DK，以磁盘上解密的全量旧值为基准合并本次修改，
     *       再逐字段重新加密(encryptEntries)。只依赖内存缓存会在缓存被清空后(退后台/重启)
     *       保存时把其他条目的密文对应的明文丢掉，所以必须以磁盘为准。
     *   加密值留空保存 = 清空该值(不再有"留空沿用旧值"的约定)。
     */
    fun persist(nextEntriesIn: List<NoteEntry>, modifiedEntryId: Long, entrySecrets: Map<String, String>, secretTouched: Boolean) {
        scope.launch {
            // 未动加密内容: 被编辑条目的secret字段按字段名恢复改动前的密文
            val prevEntry = ui.entries.firstOrNull { it.id == modifiedEntryId }
            val nextEntries = if (secretTouched || prevEntry == null) nextEntriesIn else nextEntriesIn.map { e ->
                if (e.id != modifiedEntryId) e else e.copy(fields = e.fields.map { f ->
                    if (!f.secret) f
                    else f.copy(value = prevEntry.fields.firstOrNull { it.secret && it.key == f.key }?.value ?: "")
                })
            }
            if (!secretTouched) {
                runCatching {
                    withContext(Dispatchers.IO) { NoteCrypto.save(context, nextEntries) }
                }.onSuccess {
                    ui = ui.copy(entries = nextEntries, editing = null, editingIsNew = false)
                }.onFailure { e ->
                    Log.e(TAG, "persist: 保存失败", e)
                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val key = if (NoteCrypto.masterReady(context)) {
                // 指纹已启用时优先用指纹解锁主密钥(在当前设备上代替主密码)；
                // 点"使用密码"负按钮、或未启用指纹时退回主密码验证
                obtainKey("保存修改")
            } else {
                // 未设置过主密码: 现场设置，设置成功直接用新主密钥保存
                setupMasterFlow()
            } ?: return@launch
            try {
                val merged = withContext(Dispatchers.IO) {
                    /*
                     * 其他条目沿用磁盘旧值；本次编辑条目:
                     *   编辑器给过的值 → 以编辑结果为准(值为空=清空)；
                     *   锁定状态(从未解密，编辑器里只是占位符) → 沿用磁盘旧值，不可能被有意清空。
                     */
                    val disk = NoteCrypto.readSecrets(context, key)
                    val prefix = "$modifiedEntryId:"
                    val others = disk.filterKeys { !it.startsWith(prefix) }
                    val untouched = disk.filterKeys { it.startsWith(prefix) && ui.secrets[it] == null }
                    others + untouched + entrySecrets
                }
                val finalEntries = withContext(Dispatchers.IO) { NoteCrypto.encryptEntries(nextEntries, merged, key) }
                withContext(Dispatchers.IO) { NoteCrypto.save(context, finalEntries) }
                ui = ui.copy(entries = finalEntries, secrets = merged, editing = null, editingIsNew = false)
            } catch (e: Exception) {
                Log.e(TAG, "persist: 保存失败", e)
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 启用指纹: 需要主密钥(按需验证获取)，再弹指纹认证把它封存进Keystore密钥
    fun enableBiometric() {
        scope.launch {
            val key = obtainKey("启用指纹") ?: return@launch
            enableBiometricWith(key)
        }
    }

    // ---------- 对话框渲染 ----------

    // 主密码验证对话框: 结果交给挂起中的requestKeyByPassword
    pwdRequest?.let { req ->
        PasswordDialog(
            title = req.title,
            onConfirm = { password ->
                if (pwdRequest == req) {
                    pwdRequest = null
                    req.onResult(password)
                }
            },
            onDismiss = {
                if (pwdRequest == req) {
                    pwdRequest = null
                    req.onResult(null)
                }
            }
        )
    }

    // 首次设置主密码对话框: 结果交给挂起中的setupMasterFlow
    setupRequest?.let { req ->
        SetupMasterDialog(
            biometricUsable = biometricUsable,
            onConfirm = { password, useBio ->
                if (setupRequest == req) {
                    setupRequest = null
                    req.onResult(password to useBio)
                }
            },
            onDismiss = {
                if (setupRequest == req) {
                    setupRequest = null
                    req.onResult(null)
                }
            }
        )
    }

    // ---------- 页面路由 ----------

    if (ui.showSettings) {
        SettingsScreen(
            onSetupMaster = { scope.launch { setupMasterFlow() } },
            onEnableBiometric = {
                if (NoteCrypto.masterReady(context)) {
                    enableBiometric()
                } else {
                    Toast.makeText(context, "请先设置主密码", Toast.LENGTH_SHORT).show()
                }
            },
            onDisableBiometric = {
                NoteCrypto.disableBiometric(context)
                Toast.makeText(context, "指纹已停用", Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

    ui.editing?.let { editing ->
        EditorScreen(
            entry = editing,
            isNew = ui.editingIsNew,
            secrets = ui.secrets,
            onUnlock = { reason ->
                unlockSecrets(reason).also { s -> if (s != null) ui = ui.copy(secrets = s) }
            },
            onSave = { entry, secrets, secretTouched ->
                val list = ui.entries.toMutableList()
                val index = list.indexOfFirst { it.id == entry.id }
                if (index >= 0) list[index] = entry else list.add(entry)
                persist(list, entry.id, secrets, secretTouched)
            },
            onDelete = { entry ->
                val list = ui.entries.filterNot { it.id == entry.id }
                // 删除含加密字段的条目会改动加密段，需要验证主密码；纯明文条目直接删
                persist(list, entry.id, emptyMap(), entry.fields.any { it.secret })
            },
            onCancel = { ui = ui.copy(editing = null, editingIsNew = false) }
        )
        return
    }

    ListScreen(
        entries = ui.entries,
        secrets = ui.secrets,
        revealed = ui.revealed,
        onCopy = { entry, field ->
            val cacheKey = "${entry.id}:${field.key}"
            val cached = ui.secrets[cacheKey]
            when {
                !field.secret -> copyText(field.key, field.value)
                //明文已展开在屏幕上 → 直接复制，不再重复认证
                cached != null && cacheKey in ui.revealed -> copyText(field.key, cached)
                //其余情况每次都要认证
                else -> scope.launch {
                    val secrets = unlockSecrets("复制 ${field.key}") ?: return@launch
                    val value = secrets[cacheKey]
                    if (value == null) {
                        Toast.makeText(context, "「${field.key}」没有加密值，请编辑后重新录入", Toast.LENGTH_LONG).show()
                    } else {
                        ui = ui.copy(secrets = secrets)
                        copyText(field.key, value)
                    }
                }
            }
        },
        onReveal = { entry, field ->
            val cacheKey = "${entry.id}:${field.key}"
            when {
                //已展开 → 收起(隐藏不需要认证)
                cacheKey in ui.revealed -> ui = ui.copy(revealed = ui.revealed - cacheKey)
                //每次显示都要重新认证(即使缓存里有值也不跳过)
                else -> scope.launch {
                    val secrets = unlockSecrets("查看 ${field.key}") ?: return@launch
                    if (secrets[cacheKey] == null) {
                        Toast.makeText(context, "「${field.key}」没有加密值，请编辑后重新录入", Toast.LENGTH_LONG).show()
                    } else {
                        ui = ui.copy(secrets = secrets, revealed = ui.revealed + cacheKey)
                    }
                }
            }
        },
        onEdit = { entry -> ui = ui.copy(editing = entry, editingIsNew = false) },
        onAdd = { ui = ui.copy(editing = newTemplateEntry(), editingIsNew = true) },
        onSettings = { ui = ui.copy(showSettings = true) }
    )
}

//统一输入框: 边框颜色更明显；password=true时用密码掩码；placeholder为占位提示
@Composable
private fun NoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(text = placeholder, fontSize = 12.sp) }
        } else null,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
    )
}

// 新增时的预填充模板(字段可改可删，加密/标题/预览随意调)
private fun newTemplateEntry() = NoteEntry(
    fields = listOf(
        NoteField(key = "网站/APP", value = "", title = true, preview = true),
        NoteField(key = "账号", value = "", preview = true),
        NoteField(key = "密码", value = "", secret = true),
        NoteField(key = "备注", value = "")
    )
)

@Composable
private fun VaultTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    //标题居中，动作按钮靠右
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(start = 15.dp, end = 5.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions()
        }
    }
}

// 列表: 每条记录一张卡片，字段的 显示/复制/隐藏 和编辑入口都在卡片上，无需中间页
@Composable
private fun ListScreen(
    entries: List<NoteEntry>,
    secrets: Map<String, String>,
    revealed: Set<String>,
    onCopy: (NoteEntry, NoteField) -> Unit,
    onReveal: (NoteEntry, NoteField) -> Unit,
    onEdit: (NoteEntry) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val copyColor = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxSize()) {
        VaultTopBar("备忘录", actions = {
            IconButton(onClick = onSettings) {
                Icon(imageVector = Icons.Rounded.Settings, contentDescription = "设置", tint = Color.White)
            }
        })
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "暂无记录，点下方新增按钮添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            //字段行: 加密字段 显示/隐藏 需认证；复制在明文可见时直接可用
                            entry.fields.forEach { field ->
                                val cacheKey = "${entry.id}:${field.key}"
                                val secretValue = secrets[cacheKey]
                                val isRevealed = cacheKey in revealed && secretValue != null
                                val display = when {
                                    !field.secret -> field.value
                                    isRevealed -> secretValue
                                    else -> "••••••"
                                }
                                //空的明文字段不显示行
                                if (!field.secret && display.isBlank()) return@forEach
                                Text(
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(fontSize = 13.sp, color = labelColor)) {
                                            append("${field.key}  ")
                                        }
                                        if (display.isNotBlank()) {
                                            withStyle(SpanStyle(fontSize = 14.sp)) { append(display) }
                                            append("  ")
                                        }
                                        withLink(
                                            LinkAnnotation.Clickable(
                                                tag = TAG_COPY,
                                                styles = TextLinkStyles(
                                                    style = SpanStyle(fontSize = 13.sp, color = copyColor, fontWeight = FontWeight.Medium)
                                                ),
                                                linkInteractionListener = { onCopy(entry, field) }
                                            )
                                        ) { append("复制") }
                                        if (field.secret) {
                                            append("  ")
                                            withLink(
                                                LinkAnnotation.Clickable(
                                                    tag = "toggle",
                                                    styles = TextLinkStyles(
                                                        style = SpanStyle(fontSize = 13.sp, color = copyColor, fontWeight = FontWeight.Medium)
                                                    ),
                                                    linkInteractionListener = { onReveal(entry, field) }
                                                )
                                            ) { append(if (isRevealed) "隐藏" else "显示") }
                                        }
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            //编辑入口放在卡片底部靠右
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { onEdit(entry) },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "编辑", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        //新增按钮放在底部，便于单手操作
        Button(
            onClick = onAdd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp)
        ) {
            Text(text = "新增")
        }
    }
}


/*
 * 编辑器: 字段完全自定义。加密字段的值要明文编辑需先点击输入框通过认证解密；
 * 加密值留空保存 = 清空该值。
 */
@Composable
private fun EditorScreen(
    entry: NoteEntry,
    isNew: Boolean,
    secrets: Map<String, String>,
    onUnlock: suspend (String) -> Map<String, String>?,
    onSave: (NoteEntry, Map<String, String>, Boolean) -> Unit,
    onDelete: ((NoteEntry) -> Unit)?,
    onCancel: () -> Unit
) {
    // 初始值: 明文字段用原值；加密字段用缓存(验证过)否则空串
    var fields by remember {
        mutableStateOf(
            entry.fields.map { field ->
                field.copy(value = if (field.secret) secrets["${entry.id}:${field.key}"] ?: "" else field.value)
            }
        )
    }

    // 加密内容是否被改动过(输入加密值/改加密字段名/切换加密开关/删除加密字段)。
    // 只有改动了加密内容，保存才需要验证主密码重写加密段；没动过则明文段和加密段各自独立保存
    var secretTouched by remember { mutableStateOf(false) }

    // 加密值输入框的"待验证遮罩"是否被用户取消过(取消后允许直接盲输新值，不再弹认证)
    var lockDismissed by remember { mutableStateOf(false) }

    // 正在配置的字段索引(非空时弹字段配置对话框)
    var configIndex by remember { mutableStateOf<Int?>(null) }

    // 待删除的字段索引(非空时弹删除确认对话框)
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun submit() {
        val clean = fields.filter { it.key.isNotBlank() || it.value.isNotBlank() }
        val newSecrets = mutableMapOf<String, String>()
        clean.forEach { field ->
            // 值为空 = 清空该加密值(不写入)
            if (field.secret && field.value.isNotBlank()) newSecrets["${entry.id}:${field.key}"] = field.value
        }
        onSave(entry.copy(fields = clean), newSecrets, secretTouched)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        VaultTopBar(if (isNew) "新增" else "编辑")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    //padding放在verticalScroll之后: 间距属于滚动内容，会跟着滑走而不是固定在视口上
                    .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            fields.forEachIndexed { index, field ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                    //阴影让卡片在深色背景上有立体感
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        //顶部留白要容纳悬浮在输入框边框上的label(上凸约8dp)
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        //值输入框(label用字段名)。加密值未解密时显示占位符(虚假值，纯展示不可编辑)，
                        //盖一层点击层: 点击弹认证，解密成功预填全部加密字段明文；
                        //取消认证则移除遮罩，允许直接盲输新值
                        val hasStored = entry.fields
                            .firstOrNull { it.secret && it.key == field.key }?.value?.isNotEmpty() == true
                        val locked = field.secret && !lockDismissed && hasStored &&
                            secrets["${entry.id}:${field.key}"] == null
                        Box(modifier = Modifier.fillMaxWidth()) {
                            FieldValueInput(
                                label = field.key.ifBlank { "值" },
                                value = if (locked) "••••••••" else field.value,
                                onValueChange = {
                                    if (field.secret) secretTouched = true
                                    fields = fields.toMutableList().also { list ->
                                        list[index] = field.copy(value = it)
                                    }
                                },
                                onLabelClick = { configIndex = index },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (locked) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .pointerInput(Unit) {
                                            detectTapGestures {
                                                scope.launch {
                                                    val s = onUnlock("编辑 ${field.key}")
                                                    if (s == null) {
                                                        lockDismissed = true
                                                    } else {
                                                        fields = fields.map { f ->
                                                            if (f.secret) f.copy(value = s["${entry.id}:${f.key}"] ?: f.value) else f
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                )
                            }
                        }
                        //第三行: 配置入口(点击弹配置)靠左，删除贴齐右缘
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { configIndex = index }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "字段配置",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                val flags = listOfNotNull(
                                    "加密".takeIf { field.secret },
                                    "标题".takeIf { field.title },
                                    "预览".takeIf { field.preview }
                                )
                                Text(
                                    text = flags.joinToString(" · ").ifEmpty { "无配置" },
                                    style = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 14.sp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "删除字段",
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { deleteIndex = index }
                                    .padding(2.dp)
                                    .size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            //添加字段块: 浅色背景与列表背景区分，点击添加
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.background)
                    .clickable {
                        fields = fields + NoteField()
                        //等新字段布局完成后滚动到底部
                        scope.launch {
                            delay(80)
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+ 添加字段",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }
        }
        //字段配置弹窗: 改名/加密/标题/预览开关在这里
        configIndex?.let { idx ->
            fields.getOrNull(idx)?.let { cfgField ->
                FieldConfigDialog(
                    field = cfgField,
                    onChange = { new ->
                        if (new.secret != cfgField.secret) secretTouched = true
                        fields = fields.toMutableList().also { it[idx] = new }
                    },
                    onRename = { newName ->
                        if (newName != cfgField.key) {
                            if (cfgField.secret) secretTouched = true
                            fields = fields.toMutableList().also { it[idx] = cfgField.copy(key = newName) }
                        }
                    },
                    onDismiss = { configIndex = null }
                )
            }
        }
        //删除字段确认弹窗
        deleteIndex?.let { idx ->
            fields.getOrNull(idx)?.let { delField ->
                AlertDialog(
                    onDismissRequest = { deleteIndex = null },
                    title = { Text(text = "删除字段") },
                    text = { Text(text = "确定删除字段「${delField.key.ifBlank { "未命名字段" }}」吗？") },
                    confirmButton = {
                        TextButton(onClick = {
                            if (delField.secret) secretTouched = true
                            fields = fields.filterIndexed { i, _ -> i != idx }
                            deleteIndex = null
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteIndex = null }) { Text("取消") }
                    }
                )
            }
        }
        //底部操作区: 浅色底板，顶部一条分隔线与滚动区区分
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            //顶部分隔线(贯通全宽)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 15.dp, end = 15.dp, bottom = 12.dp)
            ) {
                if (!isNew && onDelete != null) {
                    TextButton(onClick = { onDelete(entry) }) {
                        Text(text = "删除此记录", color = MaterialTheme.colorScheme.error)
                    }
                }
                //保存/取消放在底部，便于操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { submit() }, modifier = Modifier.weight(2f)) { Text("保存") }
                }
            }
        }
    }
}

/*
 * 带可点击label的值输入框: label(字段名+编辑图标)悬浮在边框上，整体可点击(用于打开字段配置弹窗)。
 * 外观仿 M3 OutlinedTextField: 圆角边框、聚焦变色、label悬浮并遮住边框线。
 */
@Composable
private fun FieldValueInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onLabelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    //配色对齐M3规范: 未聚焦边框=outline、聚焦=primary；label未聚焦=onSurfaceVariant、聚焦=primary
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val labelColor = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .border(1.dp, borderColor, MaterialTheme.shapes.small)
                .padding(top = 10.dp),
            decorationBox = { inner ->
                Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) { inner() }
            }
        )
        //label: 悬浮在边框上遮住边框线(固定18dp高度并上移半高，与边框线垂直居中)，整体可点击
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 12.dp, y = (-8).dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onLabelClick)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 14.sp),
                color = labelColor
            )
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "编辑字段配置",
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(14.dp),
                tint = labelColor
            )
        }
    }
}

//字段配置弹窗: 改名/加密/标题/预览开关在这里
@Composable
private fun FieldConfigDialog(
    field: NoteField,
    onChange: (NoteField) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(field.key) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "字段配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NoteTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "字段名",
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "加密=查看/复制需验证；标题=列表标题；预览=列表中显示",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConfigSwitchRow(label = "加密存储(查看/复制需验证)", checked = field.secret) {
                    onChange(field.copy(secret = it))
                }
                ConfigSwitchRow(label = "作为条目标题", checked = field.title) {
                    onChange(field.copy(title = it))
                }
                ConfigSwitchRow(label = "在列表中预览", checked = field.preview) {
                    onChange(field.copy(preview = it))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onRename(name)
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ConfigSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            NoteTextField(
                value = password,
                onValueChange = { password = it },
                label = "主密码",
                modifier = Modifier.fillMaxWidth(),
                password = true
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SetupMasterDialog(
    biometricUsable: Boolean,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var useBio by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "设置主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "保存需要主密码，忘记将无法恢复加密内容。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NoteTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "主密码(至少4位)",
                    modifier = Modifier.fillMaxWidth(),
                    password = true
                )
                NoteTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = "确认主密码",
                    modifier = Modifier.fillMaxWidth(),
                    password = true
                )
                if (biometricUsable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "同时启用指纹(用于查看/复制)", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = useBio, onCheckedChange = { useBio = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    password.length < 4 -> Toast.makeText(context, "主密码至少4位", Toast.LENGTH_SHORT).show()
                    password != confirm -> Toast.makeText(context, "两次输入不一致", Toast.LENGTH_SHORT).show()
                    else -> onConfirm(password, useBio && biometricUsable)
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// 设置页: 主密码/指纹的按需管理
@Composable
private fun SettingsScreen(
    onSetupMaster: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit
) {
    val context = LocalContext.current
    val masterReady = NoteCrypto.masterReady(context)
    val bioEnabled = NoteCrypto.biometricEnabled(context)
    Column(modifier = Modifier.fillMaxSize()) {
        VaultTopBar("设置")
        Column(
            modifier = Modifier
                .padding(15.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "主密码: ${if (masterReady) "已设置" else "未设置(首次保存时设置)"}",
                    modifier = Modifier.weight(1f)
                )
                if (!masterReady) {
                    TextButton(onClick = onSetupMaster) { Text(text = "设置") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "指纹: ${if (bioEnabled) "已启用(查看/复制时验证)" else if (masterReady) "未启用" else "需先设置主密码"}",
                    modifier = Modifier.weight(1f)
                )
                if (masterReady && !bioEnabled) {
                    TextButton(onClick = onEnableBiometric) { Text(text = "启用") }
                }
                if (bioEnabled) {
                    TextButton(onClick = onDisableBiometric) { Text(text = "停用") }
                }
            }
        }
    }
}
