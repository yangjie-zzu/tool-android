package com.yukino.tool.module.bluetooth.ble

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

/*
 * 模板执行器: 每种模板一条固定流程(扫描匹配→连接→发现→模板操作→断开),
 * 逐步发 ExecEvent 供执行面板展示。参数在执行前已可被用户临时修改, 此处只认参数。
 */
class PresetExecutor(
    private val context: Context,
    private val permissions: BlePermissions
) {
    private val events = MutableStateFlow<List<ExecEvent>>(emptyList())
    private val running = MutableStateFlow(false)
    private val runFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private val listenHold = java.util.concurrent.atomic.AtomicBoolean(false)
    private var client: BleClient? = null

    fun events(): Flow<List<ExecEvent>> = events

    fun isRunning(): Flow<Boolean> = running

    private fun step(text: String) {
        events.value = events.value + ExecEvent.Step(text)
    }

    private suspend fun resolveDevice(match: DeviceMatch): DiscoveredDevice {
        val adapter = permissions.adapter() ?: throw BleOpException("设备不支持蓝牙")
        step("扫描设备中…")
        val scanner = BleScanner(adapter)
        val flow = if (match.matchType == "mac") {
            scanner.scan(collectAll = true)
        } else {
            scanner.scan(filterUuid = match.serviceUuid, filterName = match.nameContains, collectAll = false)
        }
        return withTimeout(15000) {
            flow.first { list ->
                if (match.matchType == "mac") {
                    list.any { it.mac.equals(match.mac.trim(), ignoreCase = true) }
                } else {
                    list.isNotEmpty()
                }
            }.let { list ->
                if (match.matchType == "mac")
                    list.first { it.mac.equals(match.mac.trim(), ignoreCase = true) }
                else
                    list.first()
            }
        }.also { step("已找到设备: ${it.name ?: "(无名)"} ${it.mac}") }
    }

    private suspend fun connectTo(dev: DiscoveredDevice): BleClient {
        @Suppress("DEPRECATION")
        val device = permissions.adapter()!!.getRemoteDevice(dev.mac)
        step("连接中…")
        val client = BleClient(context)
        client.connect(device)
        this.client = client
        step("已连接, 服务发现完成")
        return client
    }

    /** 执行一条预设; 异常统一转 ExecEvent.Done(false) */
    suspend fun execute(name: String, template: String, params: PresetParams) {
        if (!runFlag.compareAndSet(false, true)) return
        listenHold.set(false)
        events.value = listOf(ExecEvent.Step("开始执行: $name"))
        try {
            if (!permissions.ensurePermissions()) throw BleOpException("蓝牙权限未授予")
            if (!permissions.ensureEnabled()) throw BleOpException("蓝牙未开启")
            when (template) {
                PresetTemplates.WRITE -> doWrite(params as WriteParams)
                PresetTemplates.READ -> doRead(params as ReadParams)
                PresetTemplates.LISTEN -> doListen(params as ListenParams)
                else -> throw BleOpException("未知模板: $template")
            }
        } catch (e: Exception) {
            events.value = events.value + ExecEvent.Done(false, e.message ?: "执行失败")
        } finally {
            // listen 模板执行返回后保持连接收数据, 直到 stop() 释放
            if (!listenHold.get()) {
                client?.disconnect(); client = null
                runFlag.set(false)
            }
            running.value = runFlag.get()
        }
    }

    private suspend fun doWrite(p: WriteParams) {
        if (p.commands.isEmpty()) throw BleOpException("指令列表为空")
        val client = connectTo(resolveDevice(p.match))
        for ((i, cmd) in p.commands.withIndex()) {
            val bytes = parseHex(cmd.hex) ?: throw BleOpException("第 ${i + 1} 条指令 HEX 格式错误: ${cmd.hex}")
            step("写入 ${i + 1}/${p.commands.size}: ${toHex(bytes)}")
            val status = client.write(p.svc, p.chr, bytes, p.writeWithResp)
            if (status != 0) throw BleOpException("第 ${i + 1} 条写入被拒绝 status=$status")
            if (cmd.delayMs > 0) delay(cmd.delayMs.toLong())
        }
        events.value = events.value + ExecEvent.Done(true, "执行完成")
        if (p.btOffAfter) {
            step("关闭蓝牙")
            permissions.disableBluetooth()
        }
    }

    private suspend fun doRead(p: ReadParams) {
        val client = connectTo(resolveDevice(p.match))
        val n = p.repeat.coerceAtLeast(1)
        for (i in 1..n) {
            step("读取 $i/$n")
            val value = client.read(p.svc, p.chr)
            events.value = events.value + ExecEvent.Data(toHex(value), toAscii(value))
            if (i < n && p.intervalMs > 0) delay(p.intervalMs.toLong())
        }
        events.value = events.value + ExecEvent.Done(true, "读取完成")
    }

    private suspend fun doListen(p: ListenParams) {
        val client = connectTo(resolveDevice(p.match))
        step("订阅中…")
        client.subscribe(p.svc, p.chr) { bytes ->
            events.value = events.value + ExecEvent.Data(toHex(bytes), toAscii(bytes))
        }
        listenHold.set(true)
        events.value = events.value + ExecEvent.Done(true, "订阅已开启, 等待数据")
    }

    /** 监听模板手动停止: 断开连接即结束 */
    fun stop() {
        if (listenHold.compareAndSet(true, false)) {
            client?.disconnect()
            client = null
            events.value = events.value + ExecEvent.Done(true, "已手动停止")
        }
        runFlag.set(false)
        running.value = false
    }
}
