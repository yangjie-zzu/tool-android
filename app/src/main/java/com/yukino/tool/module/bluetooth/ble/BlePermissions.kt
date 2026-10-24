package com.yukino.tool.module.bluetooth.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.yukino.tool.util.IntentLauncher
import com.yukino.tool.util.PermissionRequester
import kotlinx.coroutines.delay

/*
 * 权限与开关收拢: 旧实现在每个回调里散落 checkSelfPermission, 现统一在操作前 ensure 一次。
 * 保证顺序: 权限 → 蓝牙已开启; 不满足时返回 false 并给出原因文案。
 */
class BlePermissions(
    val appContext: Context,
    private val permissions: PermissionRequester,
    private val intents: IntentLauncher
) {
    private val context: Context get() = appContext
    /** 申请扫描+连接所需全部权限(12 以下另需定位), 全部通过返回 true */
    suspend fun ensurePermissions(): Boolean {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        for (p in need) {
            if (!permissions.request(p)) return false
        }
        return true
    }

    fun adapter(): android.bluetooth.BluetoothAdapter? =
        context.getSystemService<BluetoothManager>()?.adapter

    /** 权限就绪后调用: 蓝牙未开则弹系统开启对话框, 开启是异步的, 轮询最多 6s */
    suspend fun ensureEnabled(): Boolean {
        val adapter = adapter() ?: return false
        if (adapter.isEnabled) return true
        intents.send(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        repeat(30) {
            if (adapter.isEnabled) return true
            delay(200)
        }
        return adapter.isEnabled
    }

    /** 关蓝牙: 系统已废弃第三方 disable, 失败跳系统蓝牙设置由用户手动关 */
    fun disableBluetooth() {
        val adapter = adapter() ?: return
        if (!adapter.isEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED || !adapter.disable()
            ) {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            adapter.disable()
        }
    }
}
