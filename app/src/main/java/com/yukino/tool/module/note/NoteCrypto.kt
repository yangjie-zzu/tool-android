package com.yukino.tool.module.note

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.yukino.tool.TAG
import com.yukino.tool.db.AppDb
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val LEGACY_FILE = "note.json"   // 旧版数据文件名(仅迁移时读取)

/*
 * ============================ 加密方案总览(写给不熟悉密码学的自己) ============================
 *
 * 名词:
 *   主密钥 (DK)   由主密码经 PBKDF2 派生的 256 位 AES 密钥，直接用于加解密敏感字段，从不明文落盘。
 *   主密码路:     用户输入主密码 + note_meta 里的 salt → PBKDF2(12万轮，故意算得慢以对抗暴力猜解) → 现场得到 DK。
 *                另存一段"DK加密的固定校验值"(check)，解得开=密码正确，解不开=密码错。
 *   指纹路:       Keystore 硬件密钥把 DK 封存一份(bioKey+bioIv)。
 *                setUserAuthenticationRequired(true)+validity=-1 ⇒ 每次解封都必须现场过指纹，
 *                认证成功后系统才放行 cipher.doFinal——指纹因此在当前设备上代替主密码。
 *
 *   AES/GCM: 对称加密算法+认证标签。每次加密用随机 IV(不保密，和密文一起存)；
 *            解密时若数据被篡改或密钥不对会直接抛异常(自带完整性校验)。
 *
 * 存储布局(数据在 SQLite: note_meta/note_entry/note_field 三张表):
 *   note_meta   一行一个 k/v: salt、checkIv+check、bioIv+bioKey(二进制 Base64 存文本)
 *   note_entry  条目(ord 列保持列表顺序)
 *   note_field  字段。敏感字段的 value 直接存"Base64(IV)|Base64(密文)"，明文字段存原文。
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
 * 指纹的一个重要限制(容易踩坑):
 *   Keystore 认证绑定密钥的 cipher.init(ENCRYPT_MODE) 可以随便调，
 *   但 cipher.doFinal() 必须在 BiometricPrompt 认证成功之后执行，
 *   否则抛 UserNotAuthenticatedException。BiometricPrompt.CryptoObject(cipher) 的作用
 *   就是把"认证"和"这次 doFinal"绑定在一起，认证成功后系统才放行。
 * ============================================================================================
 */

// 单个字段。secret=加密存储(value里是密文"Base64(IV)|Base64(密文)")；totp=2FA密钥字段(必须secret)；
// title=作为条目标题；preview=在列表中预览
@kotlinx.serialization.Serializable
data class NoteField(
    val key: String = "",
    val value: String = "",
    val secret: Boolean = false,
    val totp: Boolean = false,
    val title: Boolean = false,
    val preview: Boolean = false
)

// 一条记忆 = 字段列表。名称/账号/密码/备注只是新增时的"预填充模板"，字段完全可自定义
@kotlinx.serialization.Serializable
data class NoteEntry(val id: Long = System.currentTimeMillis(), val fields: List<NoteField> = emptyList())

// 加密元数据(SQLite note_meta 表的内存映射, 全部为非机密的加密材料: 盐/IV/密文)
private data class MemFile(
    val salt: String? = null,
    val checkIv: String? = null,
    val check: String? = null,
    val bioIv: String? = null,
    val bioKey: String? = null
)

object NoteCrypto {

    private const val KS_ALIAS = "note_bio_key"   // Keystore 里指纹路密钥的别名
    private const val GCM_TAG_BITS = 128            // GCM 认证标签长度
    private const val PBKDF2_ROUNDS = 120_000       // 派生轮数: 越大越慢越抗暴力破解
    private val CHECK_MAGIC = "NoteDK-v2-check".toByteArray()   // 主密码校验内容
    private const val FIELD_SEP = '|'               // 字段密文的IV与密文分隔符

    private val random = SecureRandom()

    // ---------- note_meta 表读写(元数据只有几行, 整读整写) ----------

    private fun readMeta(context: Context): MemFile? {
        val rows = mutableMapOf<String, String>()
        AppDb.get(context).rawQuery("SELECT k, v FROM note_meta", null).use { c ->
            while (c.moveToNext()) rows[c.getString(0)] = c.getString(1)
        }
        if (rows.isEmpty()) return null
        return MemFile(
            salt = rows["salt"],
            checkIv = rows["checkIv"],
            check = rows["check"],
            bioIv = rows["bioIv"],
            bioKey = rows["bioKey"]
        )
    }

    private fun write(context: Context, meta: MemFile) {
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM note_meta")
            val st = db.compileStatement("INSERT INTO note_meta(k, v) VALUES(?,?)")
            fun put(k: String, v: String?) {
                if (v == null) return
                st.bindString(1, k)
                st.bindString(2, v)
                st.executeInsert()
            }
            put("salt", meta.salt)
            put("checkIv", meta.checkIv)
            put("check", meta.check)
            put("bioIv", meta.bioIv)
            put("bioKey", meta.bioKey)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- note_entry/note_field 表读写(全量读/全量覆写, 与调用方内存模型一致) ----------

    // 读全部条目(加密字段的value是密文，调用方不能把secret字段的value直接展示/落盘为明文)
    fun readPlain(context: Context): List<NoteEntry> {
        val fields = LinkedHashMap<Long, MutableList<NoteField>>()
        AppDb.get(context).rawQuery(
            "SELECT entry_id, idx, key, value, secret, totp, is_title, preview FROM note_field ORDER BY entry_id, idx",
            null
        ).use { c ->
            while (c.moveToNext()) {
                fields.getOrPut(c.getLong(0)) { mutableListOf() }.add(
                    NoteField(
                        key = c.getString(2),
                        value = c.getString(3),
                        secret = c.getInt(4) != 0,
                        totp = c.getInt(5) != 0,
                        title = c.getInt(6) != 0,
                        preview = c.getInt(7) != 0
                    )
                )
            }
        }
        val entries = mutableListOf<NoteEntry>()
        AppDb.get(context).rawQuery("SELECT id FROM note_entry ORDER BY ord", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                entries.add(NoteEntry(id = id, fields = fields[id] ?: emptyList()))
            }
        }
        return entries
    }

    // 保存: entries的secret字段value已由encryptEntries写成密文(见顶部不变量说明)。全量覆写
    fun save(context: Context, entries: List<NoteEntry>) {
        val db = AppDb.get(context)
        db.beginTransaction()
        try {
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM note_entry") // 字段表级联删除
            entries.forEachIndexed { ord, e ->
                var st = db.compileStatement("INSERT INTO note_entry(id, ord) VALUES(?,?)")
                st.bindLong(1, e.id)
                st.bindLong(2, ord.toLong())
                st.executeInsert()
                e.fields.forEachIndexed { idx, f ->
                    st = db.compileStatement(
                        "INSERT INTO note_field(entry_id, idx, key, value, secret, totp, is_title, preview) VALUES(?,?,?,?,?,?,?,?)"
                    )
                    st.bindLong(1, e.id)
                    st.bindLong(2, idx.toLong())
                    st.bindString(3, f.key)
                    st.bindString(4, f.value)
                    st.bindLong(5, if (f.secret) 1 else 0)
                    st.bindLong(6, if (f.totp) 1 else 0)
                    st.bindLong(7, if (f.title) 1 else 0)
                    st.bindLong(8, if (f.preview) 1 else 0)
                    st.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ==================== 状态查询 ====================

    fun isInitialized(context: Context): Boolean = readMeta(context) != null

    // 主密码是否已设置(有校验值)
    fun masterReady(context: Context): Boolean = readMeta(context)?.check != null

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

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    // 首次设置主密码: 生成 salt，派生 DK，写入校验值。返回 DK 供本次保存直接使用。
    fun setupMaster(context: Context, password: String): ByteArray {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val dk = deriveKey(password, salt)
        val (checkIv, check) = gcmEncrypt(dk, CHECK_MAGIC)
        write(context, MemFile(salt = b64(salt), checkIv = checkIv, check = check))
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
     * 修改主密码: 验证旧密码 → 新盐派生新DK → 全部条目的加密字段解密后用新DK重加密 → 换校验值。
     * 指纹封存的DK随之失效(封存的是旧DK)，需重新启用指纹(返回值供自动引导)。
     * 失败抛IllegalStateException(含提示信息)。
     */
    fun changeMasterPassword(context: Context, oldPassword: String, newPassword: String): Pair<ByteArray, Boolean> {
        if (newPassword.length < 4) error("新主密码至少4位")
        val meta = readMeta(context) ?: error("记忆未初始化")
        val salt = meta.salt ?: error("未设置主密码")
        val checkIv = meta.checkIv ?: error("未设置主密码")
        val check = meta.check ?: error("未设置主密码")
        val hadBio = meta.bioKey != null
        val oldDk = deriveKey(oldPassword, unb64(salt))
        try {
            if (!gcmDecrypt(oldDk, checkIv, check).contentEquals(CHECK_MAGIC)) error("x")
        } catch (e: Exception) {
            error("主密码错误")
        }
        val newSalt = ByteArray(16).also { random.nextBytes(it) }
        val newDk = deriveKey(newPassword, newSalt)
        val (newCheckIv, newCheck) = gcmEncrypt(newDk, CHECK_MAGIC)
        // 全部加密字段用旧DK解出、新DK重加密(以磁盘上的密文为准, 不依赖内存缓存)
        val secrets = readSecrets(context, oldDk.encoded)
        val finalEntries = encryptEntries(readPlain(context), secrets, newDk.encoded)
        save(context, finalEntries)
        write(
            context, MemFile(
                salt = b64(newSalt), checkIv = newCheckIv, check = newCheck,
                bioIv = null, bioKey = null
            )
        )
        return newDk.encoded to hadBio
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

    /*
     * 用主密钥解密全部加密字段，键为"条目id:字段名"。
     * 单字段解密失败跳过该字段。
     */
    fun readSecrets(context: Context, key: ByteArray): Map<String, String> {
        val aesKey = SecretKeySpec(key, "AES")
        val result = mutableMapOf<String, String>()
        readPlain(context).forEach { e ->
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

    // ==================== 旧 note.json 一次性导入(仅 DataMigrator 调用) ====================

    private val legacyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun readLegacyFile(context: Context): LegacyMemFile? = runCatching {
        val f = java.io.File(context.filesDir, LEGACY_FILE)
        if (!f.exists()) return null
        legacyJson.decodeFromString<LegacyMemFile>(f.readText())
    }.getOrNull()

    private fun deleteLegacyFile(context: Context) {
        java.io.File(context.filesDir, LEGACY_FILE).delete()
    }

    /*
     * v3 旧文件静默导入(无需密码: plain 段本身是明文JSON, 密文在各字段value里)。
     * 不是v3(需密码)返回null; 无旧文件/已导入返回0; 成功返回条数并删除旧文件。
     */
    @Synchronized
    fun importV3File(context: Context): Int? {
        val meta = readLegacyFile(context) ?: return 0
        if (meta.plain == null || meta.enc != null || meta.vkByPwd != null) return null
        val entries = runCatching {
            legacyJson.decodeFromString<List<NoteEntry>>(meta.plain)
        }.getOrElse { error("旧数据解析失败") }
        write(
            context, MemFile(
                salt = meta.salt, checkIv = meta.checkIv, check = meta.check,
                bioIv = meta.bioIv, bioKey = meta.bioKey
            )
        )
        save(context, entries)
        deleteLegacyFile(context)
        return entries.size
    }

    /*
     * v1/v2 旧文件导入(需主密码换出DK):
     *   v1: DK解开封存的旧VK → VK解独立enc段; 旧格式无校验值, 现场补写; 封存的指纹VK作废(需重开指纹)
     *   v2: DK解独立enc段; 校验值/指纹封存原样沿用
     * 密码错误抛"主密码错误"; 成功返回条数并删除旧文件。
     */
    @Synchronized
    fun importLegacyFile(context: Context, password: String): Int {
        val meta = readLegacyFile(context) ?: error("没有旧数据")
        val salt = meta.salt ?: error("数据损坏")
        val dk = deriveKey(password, unb64(salt))
        if (meta.vkByPwd != null) {
            // v1: 密码对错靠旧VK能否解开判定
            val vk = try {
                gcmDecrypt(dk, meta.pwdIv ?: error("数据损坏"), meta.vkByPwd)
            } catch (e: Exception) {
                error("主密码错误")
            }
            val newCheck = gcmEncrypt(dk, CHECK_MAGIC)   // v1没有校验值, 补写
            val secrets = if (meta.enc != null) {
                decodeSecrets(vk, meta.encIv, meta.enc)
            } else emptyMap()
            val count = importEntries(context, meta, secrets, dk, MemFile(salt = salt, checkIv = newCheck.first, check = newCheck.second))
            deleteLegacyFile(context)
            return count
        }
        // v2: 有校验值, 先验证密码
        val checkIv = meta.checkIv ?: error("数据损坏")
        val check = meta.check ?: error("数据损坏")
        try {
            if (!gcmDecrypt(dk, checkIv, check).contentEquals(CHECK_MAGIC)) error("主密码错误")
        } catch (e: Exception) {
            error("主密码错误")
        }
        val secrets = decodeSecrets(dk.encoded, meta.encIv, meta.enc)
        val count = importEntries(
            context, meta, secrets, dk,
            MemFile(salt = salt, checkIv = checkIv, check = check, bioIv = meta.bioIv, bioKey = meta.bioKey)
        )
        deleteLegacyFile(context)
        return count
    }

    // 解开独立enc段(v1用VK, v2用DK), 得到"条目id:字段名"→明文值
    private fun decodeSecrets(rawKey: ByteArray, encIv: String?, enc: String?): Map<String, String> {
        if (enc == null) return emptyMap()
        return runCatching {
            legacyJson.decodeFromString<Map<String, String>>(
                String(gcmDecrypt(SecretKeySpec(rawKey, "AES"), encIv ?: error("数据损坏"), enc))
            )
        }.getOrElse { error("旧数据解析失败") }
    }

    // 把plain(条目JSON)里secret字段的空值按secrets用DK逐字段重加密后入库, 并写meta
    private fun importEntries(context: Context, meta: LegacyMemFile, secrets: Map<String, String>, dk: SecretKey, newMeta: MemFile): Int {
        val entries = runCatching {
            legacyJson.decodeFromString<List<NoteEntry>>(meta.plain ?: "[]")
        }.getOrElse { error("旧数据解析失败") }
        val filled = entries.map { e ->
            e.copy(fields = e.fields.map { f ->
                if (!f.secret) f
                else {
                    val v = secrets["${e.id}:${f.key}"]
                    f.copy(value = if (v != null) encryptFieldValue(dk, v) else "")
                }
            })
        }
        write(context, newMeta)
        save(context, filled)
        return filled.size
    }
}

// 旧版note.json磁盘结构(v1/v2/v3), 仅迁移时读取
@kotlinx.serialization.Serializable
private data class LegacyMemFile(
    val version: Int = 3,
    val salt: String? = null,
    val checkIv: String? = null,
    val check: String? = null,
    val bioIv: String? = null,
    val bioKey: String? = null,
    val plain: String? = null,
    val encIv: String? = null,
    val enc: String? = null,
    val pwdIv: String? = null,
    val vkByPwd: String? = null,
    val vkByBio: String? = null
)
