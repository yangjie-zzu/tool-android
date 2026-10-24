package com.yukino.tool.module.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/*
 * 扫描封装: 结果按 MAC 去重(重复出现更新 rssi), 按 serviceUuids 有序输出。
 * 过滤在本地做(不用系统 ScanFilter): uuid 过滤匹配广播服务 UUID, name 过滤为包含匹配。
 */
@SuppressLint("MissingPermission") // 调用方先经 BlePermissions.ensurePermissions
class BleScanner(private val adapter: BluetoothAdapter) {

    /**
     * @param filterUuid 非空时只保留广播声明了该服务 UUID 的设备
     * @param filterName 非空时只保留名称包含该串的设备
     * @param collectAll false 时命中过滤条件的第一个设备后自动停止并结束流(供预设匹配)
     */
    fun scan(
        filterUuid: String = "",
        filterName: String = "",
        collectAll: Boolean = true
    ): Flow<List<DiscoveredDevice>> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("蓝牙扫描器不可用(蓝牙未开启或设备不支持)"))
            return@callbackFlow
        }
        val found = LinkedHashMap<String, DiscoveredDevice>()
        val uuidNorm = normalizeUuid(filterUuid)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids
                    ?.map { normalizeUuid(it.uuid.toString()) } ?: emptyList()
                val name = result.scanRecord?.deviceName ?: result.device?.name
                if (uuidNorm.isNotEmpty() && uuids.none { it == uuidNorm }) return
                if (filterName.isNotEmpty() && (name ?: "").contains(filterName, ignoreCase = true).not()) return
                val dev = DiscoveredDevice(result.device.address, name, result.rssi, uuids)
                found[dev.mac] = dev
                trySend(found.values.toList())
                if (!collectAll) {
                    stopScanner(scanner, this)
                    close()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("扫描失败 errorCode=$errorCode"))
            }
        }
        scanner.startScan(callback)
        awaitClose { stopScanner(scanner, callback) }
    }

    private fun stopScanner(scanner: android.bluetooth.le.BluetoothLeScanner, callback: ScanCallback) {
        runCatching { scanner.stopScan(callback) }
    }
}
