package com.yukino.tool.module.note

import android.net.Uri
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * TOTP动态验证码(RFC 6238): 6位数字、30秒周期、HMAC-SHA1，与Google Authenticator等标准验证器兼容。
 * 密钥为Base32编码，支持直接粘贴 otpauth://totp/...?secret=XXX 链接自动提取。
 */
object Totp {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // Base32解码，含非法字符返回null
    private fun base32Decode(input: String): ByteArray? {
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>(input.length * 5 / 8)
        for (c in input) {
            val v = BASE32_ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /*
     * 从用户输入规整出密钥: 支持 纯Base32 / otpauth://totp/...?secret=XXX 链接。
     * 返回大写、去空格和=的Base32；无法解析返回null。
     */
    fun normalizeSecret(input: String): String? {
        val s = input.trim()
        val raw = if (s.startsWith("otpauth://", ignoreCase = true)) {
            runCatching { Uri.parse(s).getQueryParameter("secret") }.getOrNull() ?: return null
        } else {
            s
        }
        val clean = raw.uppercase().filter { it != ' ' && it != '=' && it != '-' }
        if (clean.isEmpty() || clean.any { BASE32_ALPHABET.indexOf(it) < 0 }) return null
        return clean
    }

    // 生成指定时刻的TOTP码(默认当前时间)；密钥非法返回null
    fun code(base32Secret: String, epochSeconds: Long = System.currentTimeMillis() / 1000): String? {
        val secret = base32Decode(base32Secret) ?: return null
        val counter = epochSeconds / 30
        val msg = ByteArray(8)
        for (i in 0..7) msg[i] = (counter shr ((7 - i) * 8)).toByte()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        //动态截断(RFC 4226)
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }
}
