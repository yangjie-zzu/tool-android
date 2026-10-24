package com.yukino.tool.module.bluetooth.ble

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * 蓝牙模块数据模型:
 *   扫描结果 DiscoveredDevice / 服务树 ServiceTree / 执行事件 ExecEvent 为内存模型,
 *   预设 Preset(template+params) 持久化到 ble_preset 表, params 按 template 三选一序列化为 JSON。
 * UUID 统一规范化: 去掉 0000 前缀与 -....-后缀, 保留 4 位短码大写; 非 16 位 UUID 保留完整大写。
 */

data class DiscoveredDevice(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<String>,
    val connectable: Boolean = true   // 广播为可连接类型; 纯广播设备(beacon等)为 false
)

data class CharacteristicNode(
    val uuid: String,
    val properties: Set<String>   // read/write/writeNoResp/notify/indicate
)

data class ServiceNode(
    val uuid: String,
    val characteristics: List<CharacteristicNode>
)

data class ServiceTree(val services: List<ServiceNode>) {
    fun findCharacteristic(svc: String, chr: String): CharacteristicNode? =
        services.find { it.uuid == normalizeUuid(svc) }
            ?.characteristics?.find { it.uuid == normalizeUuid(chr) }
}

sealed class ExecEvent {
    data class Step(val text: String) : ExecEvent()
    data class Data(val hex: String, val ascii: String) : ExecEvent()
    data class Done(val ok: Boolean, val message: String) : ExecEvent()
}

// —— 预设 ——

object PresetTemplates {
    const val WRITE = "write"    // 发指令(控制类)
    const val READ = "read"      // 读数据(查询类)
    const val LISTEN = "listen"  // 监听数据(接收类)
}

@Serializable
data class DeviceMatch(
    val matchType: String = "uuid",     // mac | uuid
    val mac: String = "",
    val serviceUuid: String = "",       // matchType=uuid: 广播含此 UUID
    val nameContains: String = ""       // matchType=uuid: 名称需包含(可空)
)

@Serializable
data class WriteCommand(val hex: String, val delayMs: Int = 0)

@Serializable
sealed interface PresetParams

@Serializable
@SerialName("write")
data class WriteParams(
    val match: DeviceMatch = DeviceMatch(),
    val svc: String = "",
    val chr: String = "",
    val commands: List<WriteCommand> = emptyList(),
    val writeWithResp: Boolean = true,
    val btOffAfter: Boolean = false
) : PresetParams

@Serializable
@SerialName("read")
data class ReadParams(
    val match: DeviceMatch = DeviceMatch(),
    val svc: String = "",
    val chr: String = "",
    val repeat: Int = 1,
    val intervalMs: Int = 0
) : PresetParams

@Serializable
@SerialName("listen")
data class ListenParams(
    val match: DeviceMatch = DeviceMatch(),
    val svc: String = "",
    val chr: String = "",
    val durationMs: Long = 0            // 0=手动停
) : PresetParams

data class Preset(
    val id: Long,
    val name: String,
    val template: String,               // PresetTemplates.*
    val params: PresetParams,
    val createdAt: Long
)

object PresetCodec {
    val json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(params: PresetParams): String = json.encodeToString(params)

    fun decode(template: String, paramsJson: String): PresetParams {
        val ser = when (template) {
            PresetTemplates.WRITE -> WriteParams.serializer()
            PresetTemplates.READ -> ReadParams.serializer()
            PresetTemplates.LISTEN -> ListenParams.serializer()
            else -> throw IllegalArgumentException("未知模板: $template")
        }
        return json.decodeFromString(ser, paramsJson)
    }
}

// —— UUID/HEX 工具 ——

private val BASE_UUID_SUFFIX = "-0000-1000-8000-00805F9B34FB"

/** 各种写法(含 0000xxxx-...-8000-00805f9b34fb / 小写/带分隔) → 4 位短码大写; 非标准 UUID 保留完整大写 */
fun normalizeUuid(raw: String): String {
    val s = raw.trim().uppercase().replace(":", "").replace(" ", "")
    if (s.length == 36 && s.endsWith(BASE_UUID_SUFFIX) && s.startsWith("0000")) return s.substring(4, 8)
    return s
}

/** 宽松解析十六进制: 接受 43:02:01:01 / 43 02 01 01 / 0x43.. 等写法, 奇数位返回 null */
fun parseHex(raw: String): ByteArray? {
    val cleaned = raw.trim()
        .replace("0x", " ").replace("0X", " ")
        .replace(":", " ").replace("-", " ").replace(",", " ")
        .split(' ', '\t', '\n').filter { it.isNotEmpty() }
    val hex = cleaned.joinToString("").uppercase()
    if (hex.length % 2 != 0 || hex.any { !it.isDigit() && it !in 'A'..'F' }) return null
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

fun toAscii(bytes: ByteArray): String = buildString {
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        append(if (c in 0x20..0x7E) c.toChar() else '·')
    }
}
