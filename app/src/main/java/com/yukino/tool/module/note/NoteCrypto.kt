package com.yukino.tool.module.note

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.yukino.tool.TAG
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * ============================ 加密方案总览(写给不熟悉密码学的自己) ============================
 *
 * 名词:
 *   主密钥 (DK)   由主密码经 PBKDF2 派生的 256 位 AES 密钥，直接用于加解密敏感字段，从不明文落盘。
 *   主密码路:     用户输入主密码 + 文件里的 salt → PBKDF2(12万轮，故意算得慢以对抗暴力猜解) → 现场得到 DK。
 *                文件里另存一段"DK加密的固定校验值"(check)，解得开=密码正确，解不开=密码错。
 *   指纹路:       Keystore 硬件密钥把 DK 封存一份(bioKey+bioIv)。
 *                setUserAuthenticationRequired(true)+validity=-1 ⇒ 每次解封都必须现场过指纹，
 *                认证成功后系统才放行 cipher.doFinal——指纹因此在当前设备上代替主密码。
 *
 *   AES/GCM: 对称加密算法+认证标签。每次加密用随机 IV(不保密，和密文一起存)；
 *            解密时若数据被篡改或密钥不对会直接抛异常(自带完整性校验)。
 *
 * v3 存储布局(密文就地存放):
 *   敏感字段的 value 直接存"Base64(IV)|Base64(密文)"，不再有独立的enc段。
 *   好处: 密文跟着字段走——改字段名/删除字段自动带上/丢掉对应密文；
 *         只改明文内容时完全不碰任何密文，也就不需要主密钥。
 *   不变量: 内存中的 ui.entries 里 secret 字段的 value 永远是密文(或空串)，
 *          明文只出现在"解密缓存"(secrets)和编辑器输入框里。
 *
 * 按需验证:
 *   - 平时列表/预览只读明文内容，完全不需要任何凭证。
 *   - 查看/复制/保存加密内容 时才验证(指纹或主密码)拿到 DK → 解密/加密对应字段。
 *   - 指纹已启用时优先指纹；点"使用密码"负按钮或未启用指纹时验证主密码；首次保存未设置则现场设置。
 *
 * 旧格式自动迁移(首次使用旧数据时各输一次主密码):
 *   v1: 随机VK加密数据+独立enc段 → DK解开旧VK，数据逐字段改密文落盘。
 *   v2: DK加密+独立enc段      → 解开enc段，数据逐字段改密文落盘(有指纹时拿DK不用输密码)。
 *
 * 指纹的一个重要限制(容易踩坑):
 *   Keystore 认证绑定密钥的 cipher.init(ENCRYPT_MODE) 可以随便调，
 *   但 cipher.doFinal() 必须在 BiometricPrompt 认证成功之后执行，
 *   否则抛 UserNotAuthenticatedException。BiometricPrompt.CryptoObject(cipher) 的作用
 *   就是把"认证"和"这次 doFinal"绑定在一起，认证成功后系统才放行。
 * ============================================================================================
 */

// 单个字段。secret=加密存储(value里是密文"Base64(IV)|Base64(密文)")；totp=2FA密钥字段(必须secret)；
// title=作为条目标题；preview=在列表中预览
@Serializable
data class NoteField(
    val key: String = "",
    val value: String = "",
    val secret: Boolean = false,
    val totp: Boolean = false,
    val title: Boolean = false,
    val preview: Boolean = false
)

// 一条记忆 = 字段列表。名称/账号/密码/备注只是新增时的"预填充模板"，字段完全可自定义
@Serializable
data class NoteEntry(val id: Long = System.currentTimeMillis(), val fields: List<NoteField> = emptyList())

// 磁盘文件结构(v3，所有二进制都 Base64 后存 JSON):
//   salt         : PBKDF2 盐(随机16字节)
//   checkIv+check: DK加密的固定校验值，用于识别主密码对错
//   bioIv+bioKey : DK被Keystore指纹密钥封存后的密文
//   plain        : 全部条目。加密字段的value是"Base64(IV)|Base64(密文)"，非加密字段是明文
//   以下为v1/v2遗留字段，仅在自动迁移时读取:
//   encIv+enc    : (v2)整段敏感值JSON被DK加密
//   pwdIv+vkByPwd: (v1)VK被DK封存
//   vkByBio      : (v1)VK被Keystore指纹密钥封存
@Serializable
private data class MemFile(
    val version: Int = 3,
    val salt: String? = null,
    val checkIv: String? = null,
    val check: String? = null,
    val bioIv: String? = null,
    val bioKey: String? = null,
    val plain: String = "[]",
    val encIv: String? = null,
    val enc: String? = null,
    val pwdIv: String? = null,
    val vkByPwd: String? = null,
    val vkByBio: String? = null
)

object NoteCrypto {

    private const val FILE_NAME = "note.json"
    private const val KS_ALIAS = "note_bio_key"   // Keystore 里指纹路密钥的别名
    private const val GCM_TAG_BITS = 128            // GCM 认证标签长度
    private const val PBKDF2_ROUNDS = 120_000       // 派生轮数: 越大越慢越抗暴力破解
    private val CHECK_MAGIC = "NoteDK-v2-check".toByteArray()   // 主密码校验内容
    private const val FIELD_SEP = '|'               // 字段密文的IV与密文分隔符

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private fun readMeta(context: Context): MemFile? = runCatching {
        json.decodeFromString<MemFile>(file(context).readText())
    }.getOrNull()

    private fun write(context: Context, meta: MemFile) {
        file(context).writeText(json.encodeToString(meta))
    }

    fun isInitialized(context: Context): Boolean = file(context).exists()

    // 主密码是否已设置(校验值齐备，或旧格式待迁移)
    fun masterReady(context: Context): Boolean {
        val meta = readMeta(context) ?: return false
        return meta.vkByPwd != null || meta.check != null
    }

    // 是否为v1旧格式(VK方案，需迁移)
    fun isLegacy(context: Context): Boolean = readMeta(context)?.vkByPwd != null

    // 是否为v2格式(独立enc段，需拆入字段)
    fun isV2(context: Context): Boolean {
        val meta = readMeta(context) ?: return false
        return meta.check != null && meta.enc != null
    }

    // 指纹是否已启用(封存过DK且Keystore密钥可用)
    fun biometricEnabled(context: Context): Boolean {
        val meta = readMeta(context) ?: return false
        return meta.bioKey != null && bioDecryptCipher(context) != null
    }

    // ==================== 主密码路 ====================

    // PBKDF2 把"人能记住的密码"变成"能用于 AES 的 256 位密钥"。
    // API 26+ 支持 SHA256，更早版本退化为 SHA1(安全性略低但可用)。
    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ROUNDS, 256)
        val algorithm = if (android.os.Build.VERSION.SDK_INT >= 26) {
            "PBKDF2WithHmacSHA256"
        } else {
            "PBKDF2WithHmacSHA1"
        }
        return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded.let {
            SecretKeySpec(it, "AES")
        }
    }

    // AES-GCM 加密: 返回 (IV, 密文) 的 Base64 对。IV 每次随机，不保密但要一起存
    private fun gcmEncrypt(key: SecretKey, plainText: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return b64(cipher.iv) to b64(cipher.doFinal(plainText))
    }

    // AES-GCM 解密: 密码错误/数据被篡改都会在这里抛异常，调用方据此判断验证是否通过
    private fun gcmDecrypt(key: SecretKey, iv: String, encrypted: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, unb64(iv)))
        return cipher.doFinal(unb64(encrypted))
    }

    // 首次设置主密码: 生成 salt，派生 DK，写入校验值。返回 DK 供本次保存直接使用。
    // 全新设置会清掉一切旧封存(含可能残留的旧格式字段)
    fun setupMaster(context: Context, password: String): ByteArray {
        val meta = readMeta(context) ?: MemFile()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val dk = deriveKey(password, salt)
        val (checkIv, check) = gcmEncrypt(dk, CHECK_MAGIC)
        write(
            context, meta.copy(
                version = 3, salt = b64(salt), checkIv = checkIv, check = check,
                bioIv = null, bioKey = null, encIv = null, enc = null,
                pwdIv = null, vkByPwd = null, vkByBio = null
            )
        )
        return dk.encoded
    }

    // 主密码 → DK(现场派生)。密码错误(check 解不开)抛异常，UI 层据此提示重试
    fun deriveWithPassword(context: Context, password: String): ByteArray {
        val meta = readMeta(context) ?: error("记忆未初始化")
        val salt = meta.salt ?: error("未设置主密码")
        val checkIv = meta.checkIv ?: error("未设置主密码")
        val check = meta.check ?: error("未设置主密码")
        val dk = deriveKey(password, unb64(salt))
        try {
            if (!gcmDecrypt(dk, checkIv, check).contentEquals(CHECK_MAGIC)) error("主密码错误")
        } catch (e: Exception) {
            error("主密码错误")
        }
        return dk.encoded
    }

    /*
     * 修改主密码: 验证旧密码 → 新盐派生新DK → 全部加密值解密后用新DK重加密 → 换校验值。
     * 指纹封存的DK随之失效(封存的是旧DK)，需重新启用指纹(返回值供自动引导)。
     * 失败抛IllegalStateException(含提示信息)；兼容v1/v2遗留格式(先校验后整体转换)。
     */
    fun changeMasterPassword(context: Context, oldPassword: String, newPassword: String): Pair<ByteArray, Boolean> {
        if (newPassword.length < 4) error("新主密码至少4位")
        val meta = readMeta(context) ?: error("记忆未初始化")
        val salt = meta.salt ?: error("未设置主密码")
        val oldDk: SecretKey
        // 修改前是否启用指纹: v2/v3看bioKey，v1旧格式看vkByBio。改密后封存的是旧DK，必须引导重新认证
        val hadBio = meta.bioKey != null || meta.vkByBio != null
        if (meta.check != null && meta.checkIv != null) {
            // v3: 校验值验证旧密码
            oldDk = deriveKey(oldPassword, unb64(salt))
            try {
                if (!gcmDecrypt(oldDk, meta.checkIv, meta.check).contentEquals(CHECK_MAGIC)) error("x")
            } catch (e: Exception) {
                error("主密码错误")
            }
        } else if (meta.vkByPwd != null) {
            // v1/v2遗留: 旧密码只能靠解开封存的旧VK验证
            oldDk = deriveKey(oldPassword, unb64(salt))
            try {
                gcmDecrypt(oldDk, meta.pwdIv!!, meta.vkByPwd)
            } catch (e: Exception) {
                error("主密码错误")
            }
        } else {
            error("未设置主密码")
        }
        val newSalt = ByteArray(16).also { random.nextBytes(it) }
        val newDk = deriveKey(newPassword, newSalt)
        val (newCheckIv, newCheck) = gcmEncrypt(newDk, CHECK_MAGIC)
        // 全部加密值用旧DK解出、新DK重加密(兼容v2 enc段与v3字段密文)
        val secrets = readSecrets(context, oldDk.encoded)
        val newPlain = fillEncryptedValues(meta.plain, secrets, SecretKeySpec(newDk.encoded, "AES"))
        write(
            context, meta.copy(
                version = 3, salt = b64(newSalt), checkIv = newCheckIv, check = newCheck,
                plain = newPlain, encIv = null, enc = null,
                bioIv = null, bioKey = null, pwdIv = null, vkByPwd = null, vkByBio = null
            )
        )
        return newDk.encoded to hadBio
    }

    // v1旧格式专用: 只派生不校验(旧格式没有校验值)。密码对错由迁移时旧VK能否解开判定
    fun deriveLegacy(context: Context, password: String): ByteArray {
        val meta = readMeta(context) ?: error("记忆未初始化")
        val salt = meta.salt ?: error("数据损坏")
        return deriveKey(password, unb64(salt)).encoded
    }

    /*
     * v1→v3 自动迁移: 用 DK 解开旧封存的 VK → 解出全部敏感值 → 逐字段加密写入value。
     * 返回迁移前是否启用过指纹(上层据此引导重新封存DK)。
     * dk 来自用户输入的主密码；旧VK解不开即密码错误，抛异常。
     */
    fun migrateLegacy(context: Context, dk: ByteArray): Boolean {
        val meta = readMeta(context) ?: return false
        val vkByPwd = meta.vkByPwd ?: return false
        val aesKey = SecretKeySpec(dk, "AES")
        val vk = try {
            gcmDecrypt(aesKey, meta.pwdIv!!, vkByPwd)
        } catch (e: Exception) {
            error("主密码错误")
        }
        val secrets = if (meta.enc != null) {
            json.decodeFromString<Map<String, String>>(String(gcmDecrypt(SecretKeySpec(vk, "AES"), meta.encIv!!, meta.enc)))
        } else emptyMap()
        val (checkIv, check) = gcmEncrypt(aesKey, CHECK_MAGIC)
        val newPlain = fillEncryptedValues(meta.plain, secrets, aesKey)
        write(
            context, meta.copy(
                version = 3, checkIv = checkIv, check = check, plain = newPlain,
                encIv = null, enc = null, bioIv = null, bioKey = null,
                pwdIv = null, vkByPwd = null, vkByBio = null
            )
        )
        return meta.vkByBio != null
    }

    /*
     * v2→v3 自动迁移: 解开独立enc段 → 敏感值逐字段加密写入value。
     * DK可来自指纹或主密码；失败时文件保持v2，下次重试。
     */
    fun migrateV2(context: Context, dk: ByteArray) {
        val meta = readMeta(context) ?: return
        val enc = meta.enc ?: return
        val aesKey = SecretKeySpec(dk, "AES")
        val secrets = runCatching {
            json.decodeFromString<Map<String, String>>(String(gcmDecrypt(aesKey, meta.encIv!!, enc)))
        }.getOrDefault(emptyMap())
        val newPlain = fillEncryptedValues(meta.plain, secrets, aesKey)
        write(context, meta.copy(version = 3, plain = newPlain, encIv = null, enc = null))
    }

    // 把明文secrets逐字段加密写进plain的value里(v1/v2迁移共用)
    private fun fillEncryptedValues(plain: String, secrets: Map<String, String>, key: SecretKey): String {
        val entries = runCatching { json.decodeFromString<List<NoteEntry>>(plain) }.getOrDefault(emptyList())
        val newEntries = entries.map { e ->
            e.copy(fields = e.fields.map { f ->
                if (!f.secret) f
                else {
                    val v = secrets["${e.id}:${f.key}"]
                    f.copy(value = if (v != null) encryptFieldValue(key, v) else "")
                }
            })
        }
        return json.encodeToString(newEntries)
    }

    // 指纹认证成功后(认证过的cipher)解封DK
    fun unwrapWithBio(context: Context, cipher: Cipher): ByteArray {
        val meta = readMeta(context) ?: error("记忆未初始化")
        return cipher.doFinal(unb64(meta.bioKey ?: error("未启用指纹")))
    }

    // ==================== 指纹路 ====================

    // "解封用" cipher: 用 Keystore 密钥解密封存的 DK。init 不需要认证，
    // 但必须装进 BiometricPrompt.CryptoObject、认证成功后才允许 doFinal。
    fun bioDecryptCipher(context: Context): Cipher? {
        val meta = readMeta(context) ?: return null
        if (meta.bioKey == null || meta.bioIv == null) return null
        return runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(KS_ALIAS, null) as? SecretKey ?: return null
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, unb64(meta.bioIv)))
            }
        }.onFailure { Log.e(TAG, "bioDecryptCipher failed", it) }.getOrNull()
    }

    // 生成指纹路Keystore密钥(每次必须现场过指纹才能用于加解密)
    private fun generateBioKey(): SecretKey {
        @Suppress("DEPRECATION")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(-1)
                .build()
        )
        return generator.generateKey()
    }

    // "封存用" cipher: 启用指纹时把 DK 加密一份。同样 doFinal 要等指纹认证成功。
    // 旧密钥可能因指纹重录/生物信息变更被永久作废(init抛KeyPermanentlyInvalidatedException)，
    // 此时删掉重建——密钥只是封存容器，重建无数据损失，否则将永远无法启用指纹
    fun bioWrapCipher(): Cipher? {
        return runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            var key = ks.getKey(KS_ALIAS, null) as? SecretKey
            if (key == null) {
                key = generateBioKey()
            }
            try {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, key)
                }
            } catch (e: Exception) {
                Log.w(TAG, "bioWrapCipher: 现有密钥不可用(${e.javaClass.simpleName})，删除重建")
                ks.deleteEntry(KS_ALIAS)
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, generateBioKey())
                }
            }
        }.onFailure { Log.e(TAG, "bioWrapCipher failed", it) }.getOrNull()
    }

    // 指纹认证成功后(认证过的cipher)调用: 封存主密钥并落盘
    fun storeBioWrap(context: Context, cipher: Cipher, key: ByteArray): Boolean {
        val meta = readMeta(context) ?: return false
        return runCatching {
            val wrapped = b64(cipher.doFinal(key))
            write(context, meta.copy(bioIv = b64(cipher.iv), bioKey = wrapped))
            true
        }.getOrDefault(false)
    }

    // 停用指纹: 丢弃封存的DK即可(Keystore密钥保留无妨)
    fun disableBiometric(context: Context): Boolean {
        val meta = readMeta(context) ?: return false
        return runCatching {
            write(context, meta.copy(bioIv = null, bioKey = null))
            true
        }.getOrDefault(false)
    }

    // ==================== 数据读写 ====================

    // 读全部条目(加密字段的value是密文，调用方不能把secret字段的value直接展示/落盘为明文)
    fun readPlain(context: Context): List<NoteEntry> {
        val meta = readMeta(context) ?: return emptyList()
        return runCatching { json.decodeFromString<List<NoteEntry>>(meta.plain) }.getOrDefault(emptyList())
    }

    /*
     * 用主密钥解密全部加密字段，键为"条目id:字段名"。
     * 兼容v2(独立enc段，迁移前)与v3(密文在字段value里)；单字段解密失败跳过该字段。
     */
    fun readSecrets(context: Context, key: ByteArray): Map<String, String> {
        val meta = readMeta(context) ?: return emptyMap()
        val aesKey = SecretKeySpec(key, "AES")
        if (meta.enc != null) {
            return runCatching {
                json.decodeFromString<Map<String, String>>(String(gcmDecrypt(aesKey, meta.encIv!!, meta.enc)))
            }.getOrDefault(emptyMap())
        }
        val entries = runCatching { json.decodeFromString<List<NoteEntry>>(meta.plain) }.getOrDefault(emptyList())
        val result = mutableMapOf<String, String>()
        entries.forEach { e ->
            e.fields.forEach { f ->
                if (f.secret && f.value.isNotEmpty()) {
                    decryptFieldValue(aesKey, f.value)?.let { result["${e.id}:${f.key}"] = it }
                }
            }
        }
        return result
    }

    /*
     * 用合并后的明文secrets重新加密条目的全部secret字段(保存专用)。
     * secrets里没有的键视为"该字段无值"，value写成空串。
     */
    fun encryptEntries(entries: List<NoteEntry>, secrets: Map<String, String>, key: ByteArray): List<NoteEntry> {
        val aesKey = SecretKeySpec(key, "AES")
        return entries.map { e ->
            e.copy(fields = e.fields.map { f ->
                if (!f.secret) f
                else {
                    val v = secrets["${e.id}:${f.key}"]
                    f.copy(value = if (v != null) encryptFieldValue(aesKey, v) else "")
                }
            })
        }
    }

    // 保存: entries的secret字段value已由encryptEntries写成密文(见persist里的不变量说明)
    fun save(context: Context, entries: List<NoteEntry>) {
        val meta = readMeta(context) ?: MemFile()
        write(context, meta.copy(plain = json.encodeToString(entries)))
    }

    // 字段值加解密(密文格式"Base64(IV)|Base64(密文)")
    private fun encryptFieldValue(key: SecretKey, value: String): String {
        val (iv, ct) = gcmEncrypt(key, value.toByteArray())
        return "$iv$FIELD_SEP$ct"
    }

    private fun decryptFieldValue(key: SecretKey, stored: String): String? {
        val iv = stored.substringBefore(FIELD_SEP)
        val ct = stored.substringAfter(FIELD_SEP, "")
        if (iv.isEmpty() || ct.isEmpty()) return null
        return runCatching { String(gcmDecrypt(key, iv, ct)) }.getOrNull()
    }
}
