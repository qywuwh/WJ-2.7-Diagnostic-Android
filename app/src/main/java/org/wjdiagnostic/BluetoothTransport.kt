package org.wjdiagnostic

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AdapterInfo(val name: String, val address: String, val rssi: Int?, val classic: Boolean)
data class GattInfo(val service: String, val characteristic: String, val properties: String)

interface BluetoothTransport {
    val incoming: Channel<ByteArray>
    suspend fun connect(address: String)
    suspend fun write(bytes: ByteArray)
    fun disconnect()
}

@SuppressLint("MissingPermission")
class ClassicTransport(private val adapter: BluetoothAdapter, private val scope: CoroutineScope) : BluetoothTransport {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var socket: BluetoothSocket? = null
    private var reader: Job? = null
    var sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override suspend fun connect(address: String) = withContext(Dispatchers.IO) {
        disconnect()
        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val s = device.createRfcommSocketToServiceRecord(sppUuid)
        try {
            s.connect()
            socket = s
            reader = scope.launch(Dispatchers.IO) { readLoop(s.inputStream) }
        } catch (e: Exception) {
            s.close()
            throw IllegalStateException("RFCOMM: ${e.message}", e)
        }
    }

    private suspend fun readLoop(input: InputStream) {
        val buffer = ByteArray(512)
        try {
            while (currentCoroutineContext().isActive) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) incoming.send(buffer.copyOf(n))
            }
        } catch (_: Exception) { }
        finally { incoming.trySend(byteArrayOf()) }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out: OutputStream = socket?.outputStream ?: error("Bluetooth Classic отключён")
        out.write(bytes)
        out.flush()
    }

    override fun disconnect() {
        reader?.cancel(); reader = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }
}

@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val adapter: BluetoothAdapter) : BluetoothTransport {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    val discovered = MutableStateFlow<List<AdapterInfo>>(emptyList())
    val gattInfo = MutableStateFlow<List<GattInfo>>(emptyList())
    val notifyEnabled = MutableStateFlow(false)
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private var connectWaiter: CancellableContinuation<Unit>? = null
    private var writeWaiter: CancellableContinuation<Unit>? = null
    var chosenTx: String? = null
    var chosenRx: String? = null
    var mtu = 23

    fun scan() {
        stopScan()
        discovered.value = emptyList()
        val scanner = adapter.bluetoothLeScanner ?: error("BLE scanner недоступен")
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = d.name ?: result.scanRecord?.deviceName ?: "Без имени"
                discovered.value = (discovered.value.filterNot { it.address == d.address } +
                    AdapterInfo(name, d.address, result.rssi, false)).sortedByDescending { it.rssi ?: -127 }
            }
            override fun onScanFailed(errorCode: Int) { incoming.trySend(byteArrayOf()) }
        }
        scanner.startScan(scanCallback)
    }

    fun stopScan() {
        scanCallback?.let { adapter.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    override suspend fun connect(address: String) {
        disconnect()
        stopScan()
        withTimeout(15_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                connectWaiter = cont
                gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callbacks)
                cont.invokeOnCancellation { disconnect() }
            }
        }
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectWaiter?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("BLE disconnect/status $status"))
                connectWaiter = null
                incoming.trySend(byteArrayOf())
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectWaiter?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("GATT discovery $status"))
                connectWaiter = null
                return
            }
            val all = g.services.flatMap { s -> s.characteristics.map { c -> s to c } }
            gattInfo.value = all.map { (s, c) ->
                val p = c.properties
                GattInfo(s.uuid.toString(), c.uuid.toString(), listOfNotNull(
                    "Write".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
                    "WriteNoResponse".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
                    "Notify".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
                    "Indicate".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
                    "Read".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                ).joinToString(", "))
            }
            val preference = context.getSharedPreferences("gatt", Context.MODE_PRIVATE)
            val key = g.device.address
            chosenTx = chosenTx ?: preference.getString("tx.$key", null)
            chosenRx = chosenRx ?: preference.getString("rx.$key", null)
            tx = all.map { it.second }.firstOrNull { it.uuid.toString() == chosenTx }
                ?: all.map { it.second }.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
            rx = all.map { it.second }.firstOrNull { it.uuid.toString() == chosenRx }
                ?: all.map { it.second }.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
            chosenTx = tx?.uuid?.toString(); chosenRx = rx?.uuid?.toString()
            if (tx == null || rx == null) {
                connectWaiter?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("GATT TX/RX не найдены"))
                connectWaiter = null
                return
            }
            g.setCharacteristicNotification(rx, true)
            val descriptor = rx?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (descriptor != null) {
                descriptor.value = if (rx!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(descriptor)
            } else {
                notifyEnabled.value = true
                connectWaiter?.takeIf { it.isActive }?.resume(Unit); connectWaiter = null
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            g.requestMtu(247)
            notifyEnabled.value = status == BluetoothGatt.GATT_SUCCESS
            val waiter = connectWaiter; connectWaiter = null
            if (waiter?.isActive == true) {
                if (notifyEnabled.value) waiter.resume(Unit)
                else waiter.resumeWithException(IllegalStateException("GATT Notify error $status"))
            }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { incoming.trySend(it.copyOf()) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val waiter = writeWaiter; writeWaiter = null
            if (waiter?.isActive == true) {
                if (status == BluetoothGatt.GATT_SUCCESS) waiter.resume(Unit)
                else waiter.resumeWithException(IllegalStateException("GATT write $status"))
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) mtu = value }
    }

    fun select(txUuid: String?, rxUuid: String?) {
        val g = gatt ?: return
        val all = g.services.flatMap { it.characteristics }
        if (txUuid != null) {
            tx = all.firstOrNull { it.uuid.toString() == txUuid && it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
            if (tx == null) error("GATT TX не поддерживает Write")
            chosenTx = txUuid
        }
        if (rxUuid != null) {
            val candidate = all.firstOrNull { it.uuid.toString() == rxUuid && it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                ?: error("GATT RX не поддерживает Notify/Indicate")
            rx?.let { g.setCharacteristicNotification(it, false) }
            rx = candidate
            chosenRx = rxUuid
            g.setCharacteristicNotification(candidate, true)
            candidate.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let { descriptor ->
                descriptor.value = if (candidate.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(descriptor)
            }
        }
        context.getSharedPreferences("gatt", Context.MODE_PRIVATE).edit()
            .putString("tx.${g.device.address}", chosenTx).putString("rx.${g.device.address}", chosenRx).apply()
    }

    override suspend fun write(bytes: ByteArray) {
        val g = gatt ?: error("BLE отключён")
        val c = tx ?: error("BLE TX не выбран")
        val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        c.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        for (chunk in bytes.toList().chunked((mtu - 3).coerceAtLeast(20))) {
            val data = chunk.toByteArray()
            if (withResponse) withTimeout(5_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    writeWaiter = cont
                    c.value = data
                    if (!g.writeCharacteristic(c)) {
                        writeWaiter = null
                        cont.resumeWithException(IllegalStateException("GATT write rejected"))
                    }
                }
            } else {
                c.value = data
                if (!g.writeCharacteristic(c)) error("GATT write rejected")
                delay(25)
            }
        }
    }

    override fun disconnect() {
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
        gatt = null; tx = null; rx = null; notifyEnabled.value = false
    }
}
