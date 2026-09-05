package com.yukino.tool.module.note

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yukino.tool.ui.theme.ToolTheme
import com.yukino.tool.util.findActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "Note"

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

// 指纹认证结果: 成功(带加密对象)或出错(code为BiometricPrompt错误码)
private sealed interface BioAuth {
    data class Success(val crypto: BiometricPrompt.CryptoObject?) : BioAuth
    data class Error(val code: Int) : BioAuth
}

// 设备是否具备可用指纹(已录入且硬件支持)
private fun canBiometric(activity: FragmentActivity): Boolean = runCatching {
    BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
}.getOrDefault(false)

/*
 * 挂起式系统指纹对话框: 认证结束(成功/出错)才返回。
 * crypto 非空时把认证与该加密操作绑定——认证成功后系统才放行 cipher.doFinal；
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

    // 2FA验证码的秒级时钟(列表里展开的动态码每秒刷新)
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowSeconds = System.currentTimeMillis() / 1000
        }
    }

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

    //系统返回键: 编辑→列表，设置→列表
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
        SettingsPage(
            entries = ui.entries,
            onUnlock = { reason -> unlockSecrets(reason) },
            onSetupMaster = { scope.launch { setupMasterFlow() } },
            onChangeMasterPassword = { oldPw, newPw ->
                scope.launch {
                    try {
                        val (newDk, hadBio) = withContext(Dispatchers.Default) {
                            NoteCrypto.changeMasterPassword(context, oldPw, newPw)
                        }
                        Toast.makeText(context, "主密码已修改", Toast.LENGTH_SHORT).show()
                        // 指纹封存随旧密钥失效: 原启用过则自动引导重新认证封存新主密钥
                        if (hadBio) enableBiometricWith(newDk)
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "修改失败", Toast.LENGTH_LONG).show()
                    }
                }
            },
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
        EditorPage(
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
                // 只有真的存了加密值的条目，删除才需要验证(要从加密段摘除数据)；
                // 字段有但值为空 → 加密段里本来就没有它的数据，和纯明文一样直接删
                persist(list, entry.id, emptyMap(), entry.fields.any { it.secret && it.value.isNotBlank() })
            },
            onCancel = { ui = ui.copy(editing = null, editingIsNew = false) }
        )
        return
    }

    ListPage(
        entries = ui.entries,
        secrets = ui.secrets,
        revealed = ui.revealed,
        nowSeconds = nowSeconds,
        onCopy = { entry, field ->
            val cacheKey = "${entry.id}:${field.key}"
            val cached = ui.secrets[cacheKey]
            when {
                !field.secret -> copyText(field.key, field.value)
                //2FA字段复制的是当前动态码而不是密钥本身
                field.totp && cached != null -> Totp.code(cached, nowSeconds)?.let { copyText(field.key, it) }
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
                        if (field.totp) {
                            Totp.code(value, nowSeconds)?.let { copyText(field.key, it) }
                        } else {
                            copyText(field.key, value)
                        }
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
