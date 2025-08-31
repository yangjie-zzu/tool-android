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

            fun closeLed(device: BluetoothDevice) {
                Toast.makeText(currentActivity, "关灯中", Toast.LENGTH_SHORT).show()
                if (currentActivity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    device.connectGatt(currentActivity, false, object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(
                            gatt: BluetoothGatt?,
                            status: Int,
                            newState: Int
                        ) {
                            super.onConnectionStateChange(
                                gatt,
                                status,
                                newState
                            )
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                if (currentActivity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                    gatt?.discoverServices()
                                }
                            }
                        }

                        override fun onServicesDiscovered(
                            gatt: BluetoothGatt?,
                            status: Int
                        ) {
                            super.onServicesDiscovered(gatt, status)
                            val services = gatt?.services
                            Log.i(TAG, "services: $services")
                            val getCharacteristic = { gatt?.getService(UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"))?.getCharacteristic(UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")) }
                            coroutineScope.launch {
                                if (currentActivity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                    gatt?.writeCharacteristic(getCharacteristic()?.also { it.value = byteArrayOf(67,2,1,1) })
                                    delay(100L)
                                    gatt?.writeCharacteristic(getCharacteristic()?.also { it.value = byteArrayOf(67,2,1,2) })
                                    delay(100L)
                                    Toast.makeText(currentActivity, "已关灯", Toast.LENGTH_SHORT).show()
                                    disableBluetooth()
                                }
                            }
                        }

                    })
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
                                            bluetoothLeScanner?.startScan(object : ScanCallback() {
                                                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                                                    super.onScanResult(callbackType, result)
                                                    Log.i(TAG, "onScanResult: $result")
                                                    if ("0000FFE0-0000-1000-8000-00805F9B34FB" == result.scanRecord?.serviceUuids?.get(0)?.uuid?.toString()?.uppercase()) {
                                                        if (currentActivity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                                                            bluetoothLeScanner.stopScan(this)
                                                        }
                                                        deviceRef.value = result.device
                                                        closeLed(result.device)
                                                    }
                                                }
                                            })
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