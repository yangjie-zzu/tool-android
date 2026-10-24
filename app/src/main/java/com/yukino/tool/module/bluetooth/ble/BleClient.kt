package com.yukino.tool.module.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

/*
 * GATT 客户端封装: 单连接单操作(预设/调试页都是顺序操作, 不做并发)。
 * 每个操作挂起等待对应回调, 带 8s 超时; 权限由调用方先经 BlePermissions 保证。
 */
const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805F9B34FB"
private const val OP_TIMEOUT_MS = 8000L

class BleClient(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private var waiter: CompletableDeferred<Any?>? = null
    private var dataListener: ((ByteArray) -> Unit)? = null

    private fun <T> newWaiter(): CompletableDeferred<T> {
        val d = CompletableDeferred<T>()
        @Suppress("UNCHECKED_CAST")
        waiter = d as CompletableDeferred<Any?>
        return d
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): ServiceTree {
        disconnect() // 兜底: 复用前先释放旧连接
        val connected = newWaiter<Boolean>()
        val discovered = newWaiter<Int>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    (waiter as? CompletableDeferred<Boolean>)?.complete(false)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                discovered.complete(status)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                (waiter as? CompletableDeferred<Int>)?.complete(status)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                (waiter as? CompletableDeferred<Pair<Int, ByteArray?>>)?.complete(status to characteristic.value)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                (waiter as? CompletableDeferred<Int>)?.complete(status)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                dataListener?.invoke(characteristic.value ?: ByteArray(0))
            }
        }
        gatt = device.connectGatt(context, false, callback)
        if (!withTimeout(OP_TIMEOUT_MS) { connected.await() }) {
            throw BleOpException("连接超时")
        }
        gatt?.discoverServices()
        val status = withTimeout(OP_TIMEOUT_MS) { discovered.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleOpException("服务发现失败 status=$status")
        val tree = gatt?.services?.map { svc ->
            ServiceNode(
                uuid = normalizeUuid(svc.uuid.toString()),
                characteristics = svc.characteristics.map { chr ->
                    CharacteristicNode(normalizeUuid(chr.uuid.toString()), propsOf(chr.properties))
                }
            )
        }?.let { ServiceTree(it) } ?: throw BleOpException("未发现任何服务")
        return tree
    }

    private fun propsOf(p: Int): Set<String> = buildSet {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResp")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }

    /** @return GATT status, 0=成功 */
    @SuppressLint("MissingPermission")
    suspend fun write(svc: String, chr: String, bytes: ByteArray, withResp: Boolean): Int {
        val g = gatt ?: throw BleOpException("未连接")
        val characteristic = findCharacteristic(svc, chr) ?: throw BleOpException("特征不存在 $svc/$chr")
        val done = newWaiter<Int>()
        characteristic.writeType = if (withResp)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = bytes
        if (!g.writeCharacteristic(characteristic)) throw BleOpException("写入请求发送失败")
        return withTimeout(OP_TIMEOUT_MS) { done.await() }
    }

    /** @return 读到的字节, 设备返回错误时抛异常 */
    @SuppressLint("MissingPermission")
    suspend fun read(svc: String, chr: String): ByteArray {
        val g = gatt ?: throw BleOpException("未连接")
        val characteristic = findCharacteristic(svc, chr) ?: throw BleOpException("特征不存在 $svc/$chr")
        val done = newWaiter<Pair<Int, ByteArray?>>()
        if (!g.readCharacteristic(characteristic)) throw BleOpException("读取请求发送失败")
        val (status, value) = withTimeout(OP_TIMEOUT_MS) { done.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleOpException("读取被拒绝 status=$status")
        return value ?: ByteArray(0)
    }

    /** 开启 notify/indicate, 等订阅生效(CCCD 写成功)后返回 */
    @SuppressLint("MissingPermission")
    suspend fun subscribe(svc: String, chr: String, onPacket: (ByteArray) -> Unit): Boolean {
        val g = gatt ?: throw BleOpException("未连接")
        val characteristic = findCharacteristic(svc, chr) ?: throw BleOpException("特征不存在 $svc/$chr")
        val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val done = newWaiter<Int>()
        gatt?.setCharacteristicNotification(characteristic, true)
        val desc = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
            ?: throw BleOpException("特征无 CCCD 描述符, 无法订阅")
        desc.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(desc)) throw BleOpException("订阅请求发送失败")
        val status = withTimeout(OP_TIMEOUT_MS) { done.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleOpException("订阅失败 status=$status")
        dataListener = onPacket
        return indicate
    }

    @SuppressLint("MissingPermission")
    fun stopListen() {
        dataListener = null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        waiter = null
        dataListener = null
    }

    private fun findCharacteristic(svc: String, chr: String): BluetoothGattCharacteristic? =
        gatt?.getService(UUID.fromString(expandUuid(svc)))
            ?.getCharacteristic(UUID.fromString(expandUuid(chr)))

    companion object {
        /** 4 位短码 → 完整 128 位 UUID */
        fun expandUuid(raw: String): String {
            val s = normalizeUuid(raw)
            return if (s.length == 4) "0000$s-0000-1000-8000-00805F9B34FB" else s
        }
    }
}

class BleOpException(message: String) : Exception(message)
