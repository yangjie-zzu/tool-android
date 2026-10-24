package com.yukino.tool.module.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.yukino.tool.TAG
import com.yukino.tool.components.Title
import com.yukino.tool.util.IntentLauncher
import com.yukino.tool.util.PermissionRequester
import com.yukino.tool.util.ValueRef
import com.yukino.tool.util.rememberCurrentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.bluetooth.le.ScanResult
import kotlin.coroutines.resume
import java.util.UUID

lateinit var enableBluetoothLauncher: IntentLauncher

lateinit var permissionRequester: PermissionRequester

class BluetoothActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableBluetoothLauncher = IntentLauncher(this)
        permissionRequester = PermissionRequester(this)
        setContent {
            BluetoothView()
        }
    }
}

@Composable
fun BluetoothView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
    ) {
        Row {
            Title("蓝牙设备")
        }
        Column(modifier = Modifier.weight(1f)) {
            val currentActivity = rememberCurrentActivity()
            val coroutineScope = rememberCoroutineScope()
            val deviceRef = remember {
                ValueRef<BluetoothDevice?>(null)
            }

            fun disableBluetooth() {
                coroutineScope.launch {
                    val bluetoothManager = currentActivity.getSystemService<BluetoothManager>()
                    val adapter = bluetoothManager?.adapter
                    if (currentActivity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                        || adapter?.disable() != true) {
                        currentActivity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                }
            }

            suspend fun closeLed(device: BluetoothDevice) {
                Toast.makeText(currentActivity, "关灯中", Toast.LENGTH_SHORT).show()
                if (currentActivity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    val gatt = connectLedGatt(device, currentActivity)
                    if (gatt != null) {
                        try {
                            val getCharacteristic = { gatt.getService(UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"))?.getCharacteristic(UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")) }
                            gatt.writeCharacteristic(getCharacteristic()?.also { it.value = byteArrayOf(67, 2, 1, 1) })
                            delay(100L)
                            gatt.writeCharacteristic(getCharacteristic()?.also { it.value = byteArrayOf(67, 2, 1, 2) })
                            delay(100L)
                            Toast.makeText(currentActivity, "已关灯", Toast.LENGTH_SHORT).show()
                            disableBluetooth()
                        } finally {
                            gatt.disconnect()
                            gatt.close()
                        }
                    } else {
                        Toast.makeText(currentActivity, "连接失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    coroutineScope.launch {
                        val bluetoothManager = currentActivity.getSystemService<BluetoothManager>()
                        val adapter = bluetoothManager?.adapter
                        if (adapter != null) {
                            if (!adapter.isEnabled) {
                                deviceRef.value = null
                                if (permissionRequester.request(Manifest.permission.ACCESS_FINE_LOCATION) && permissionRequester.request(Manifest.permission.BLUETOOTH_CONNECT)) {
                                    val result = enableBluetoothLauncher.send(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    Log.i(TAG, "result $result")
                                    Toast.makeText(currentActivity, "蓝牙启动", Toast.LENGTH_SHORT).show()
                                } else {
                                    Log.i(TAG, "permissionRequester 失败")
                                }
                            }
                            if (adapter.isEnabled) {
                                if (currentActivity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || permissionRequester.request(Manifest.permission.BLUETOOTH_SCAN)) {
                                    val bluetoothLeScanner = adapter.bluetoothLeScanner
                                    deviceRef.value.let { device ->
                                        if (device != null) {
                                            closeLed(device)
                                        } else {
                                            Toast.makeText(currentActivity, "蓝牙扫描中", Toast.LENGTH_SHORT).show()
                                            bluetoothLeScanner?.let { scanner ->
                                                val device = scanForLedDevice(scanner)
                                                deviceRef.value = device
                                                closeLed(device)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            ) {
                Text("关灯")
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    disableBluetooth()
                }
            ) {
                Text("关闭蓝牙")
            }
        }
    }
}

// Gatt 连接+服务发现: 成功返回已发现服务的 gatt, 失败返回 null; 取消/协程结束时自动断开
private suspend fun connectLedGatt(device: BluetoothDevice, activity: Activity): BluetoothGatt? {
    return suspendCancellableCoroutine { cont ->
        val gatt = device.connectGatt(activity, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt?.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && cont.isActive) {
                    cont.resume(null)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)
                Log.i(TAG, "services: ${gatt?.services}")
                if (cont.isActive) {
                    cont.resume(if (status == BluetoothGatt.GATT_SUCCESS) gatt else null)
                }
            }
        })
        cont.invokeOnCancellation {
            gatt.disconnect()
            gatt.close()
        }
    }
}

// LE 扫描: 等待广播 FFE0 服务的目标设备出现, 命中即停扫; 取消时也停扫
private suspend fun scanForLedDevice(scanner: android.bluetooth.le.BluetoothLeScanner): BluetoothDevice {
    return suspendCancellableCoroutine { cont ->
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                Log.i(TAG, "onScanResult: $result")
                if ("0000FFE0-0000-1000-8000-00805F9B34FB" == result.scanRecord?.serviceUuids?.get(0)?.uuid?.toString()?.uppercase()) {
                    if (cont.isActive) {
                        cont.resume(result.device)
                    }
                    runCatching { scanner.stopScan(this) }
                }
            }
        }
        scanner.startScan(callback)
        cont.invokeOnCancellation {
            runCatching { scanner.stopScan(callback) }
        }
    }
}