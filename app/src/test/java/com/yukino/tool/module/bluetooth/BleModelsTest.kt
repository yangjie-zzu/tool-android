package com.yukino.tool.module.bluetooth

import com.yukino.tool.module.bluetooth.ble.DeviceMatch
import com.yukino.tool.module.bluetooth.ble.ListenParams
import com.yukino.tool.module.bluetooth.ble.PresetCodec
import com.yukino.tool.module.bluetooth.ble.PresetTemplates
import com.yukino.tool.module.bluetooth.ble.ReadParams
import com.yukino.tool.module.bluetooth.ble.WriteCommand
import com.yukino.tool.module.bluetooth.ble.WriteParams
import com.yukino.tool.module.bluetooth.ble.normalizeUuid
import com.yukino.tool.module.bluetooth.ble.parseHex
import com.yukino.tool.module.bluetooth.ble.toAscii
import com.yukino.tool.module.bluetooth.ble.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleModelsTest {

    // —— UUID 规范化 ——

    @Test
    fun `标准16位UUID规范为4位短码`() {
        assertEquals("FFE0", normalizeUuid("0000ffe0-0000-1000-8000-00805f9b34fb"))
        assertEquals("FFF2", normalizeUuid("0000FFF2-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun `短码直接保留并大写`() {
        assertEquals("FFE0", normalizeUuid("ffe0"))
        assertEquals("FFF0", normalizeUuid(" Fff0 "))
    }

    @Test
    fun `非标准128位UUID保留完整大写`() {
        assertEquals("AABBCCDD-1122-3344-5566-778899AABBCC", normalizeUuid("aabbccdd-1122-3344-5566-778899aabbcc"))
    }

    // —— HEX 解析 ——

    @Test
    fun `紧凑HEX解析`() {
        assertArrayEquals(byteArrayOf(0x43, 0x02, 0x01, 0x01), parseHex("43020101"))
    }

    @Test
    fun `带冒号分隔HEX解析`() {
        assertArrayEquals(byteArrayOf(0x43, 0x02, 0x01, 0x02), parseHex("43:02:01:02"))
    }

    @Test
    fun `混合分隔与小写0x前缀解析`() {
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), parseHex("0xab cd"))
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), parseHex("01-02,03"))
    }

    @Test
    fun `空串解析为空数组`() {
        assertArrayEquals(ByteArray(0), parseHex(""))
    }

    @Test
    fun `奇数位返回null`() {
        assertNull(parseHex("4302010"))
    }

    @Test
    fun `非法字符返回null`() {
        assertNull(parseHex("43GH"))
    }

    // —— HEX/ASCII 输出 ——

    @Test
    fun `toHex大写无分隔`() {
        assertEquals("4302AB", toHex(byteArrayOf(0x43, 0x02, 0xAB.toByte())))
    }

    @Test
    fun `toAscii可打印保留不可打印圆点`() {
        assertEquals("A·B", toAscii(byteArrayOf(0x41, 0x00, 0x42)))
    }

    // —— 预设 params JSON 往返 ——

    @Test
    fun `WriteParams往返编码解码`() {
        val p = WriteParams(
            match = DeviceMatch(matchType = "uuid", serviceUuid = "FFE0", nameContains = "light"),
            svc = "FFF0", chr = "FFF2",
            commands = listOf(WriteCommand("43020101", 100), WriteCommand("43020102", 0)),
            writeWithResp = true, btOffAfter = true
        )
        val json = PresetCodec.encode(p)
        assertTrue(json.contains("\"kind\":\"write\""))
        val back = PresetCodec.decode(PresetTemplates.WRITE, json)
        assertEquals(p, back)
    }

    @Test
    fun `ReadParams往返编码解码`() {
        val p = ReadParams(DeviceMatch(matchType = "mac", mac = "AA:BB:CC:DD:EE:FF"), "180F", "2A19", 3, 500)
        val back = PresetCodec.decode(PresetTemplates.READ, PresetCodec.encode(p))
        assertEquals(p, back)
    }

    @Test
    fun `ListenParams往返编码解码`() {
        val p = ListenParams(DeviceMatch(), "FFF0", "FFF1", 0L)
        val back = PresetCodec.decode(PresetTemplates.LISTEN, PresetCodec.encode(p))
        assertEquals(p, back)
    }

    @Test
    fun `解码容忍未知字段与缺省字段`() {
        val json = """{"kind":"write","match":{"matchType":"uuid"},"extra":1}"""
        val p = PresetCodec.decode(PresetTemplates.WRITE, json) as WriteParams
        assertEquals("", p.svc)
        assertEquals(emptyList<WriteCommand>(), p.commands)
        assertTrue(p.writeWithResp)
    }
}
