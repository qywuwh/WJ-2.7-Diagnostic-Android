package org.wjdiagnostic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

@SuppressLint("MissingPermission")
class DiagnosticController(private val context: Context, private val scope: CoroutineScope) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    val status = MutableStateFlow("Адаптер не подключён")
    val mode = MutableStateFlow("Classic")
    val devices = MutableStateFlow<List<AdapterInfo>>(emptyList())
    val console = MutableStateFlow("")
    val diagnostic = MutableStateFlow<JeepDiagnostic?>(null)
    val elm = MutableStateFlow<Elm327?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())
    val selected = MutableStateFlow(JeepModule.ECM)
    val busy = MutableStateFlow(false)
    val polling = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val lastError = MutableStateFlow("")
    val ble = BleTransport(context, adapter)
    private var transport: BluetoothTransport? = null
    private var liveJob: Job? = null
    private var keepaliveJob: Job? = null
    private var lastAddress: String? = null
    private var logJob: Job? = null
    private var recordKeys = emptyList<String>()
    private val recordRows = mutableListOf<String>()

    init {
        scope.launch { ble.discovered.collect { if (mode.value == "BLE") devices.value = it } }
    }

    fun setMode(value: String) { disconnect(); mode.value = value; devices.value = emptyList() }
    fun scan() = run {
        if (!adapter.isEnabled) error("Включите Bluetooth")
        if (mode.value == "Classic") {
            devices.value = adapter.bondedDevices.map { AdapterInfo(it.name ?: "Без имени", it.address, null, true) }
            if (devices.value.isEmpty()) error("Нет сопряжённых устройств. Сопрягите AutoHelfer в настройках Bluetooth")
        } else {
            ble.scan()
            status.value = "Поиск BLE устройств"
        }
    }

    fun connect(device: AdapterInfo) = run {
        disconnect()
        status.value = "Подключение к ${device.name}…"
        val t: BluetoothTransport = if (device.classic) ClassicTransport(adapter, scope) else ble
        t.connect(device.address)
        transport = t
        lastAddress = device.address
        val e = Elm327(t)
        elm.value = e
        logJob?.cancel()
        logJob = scope.launch { e.log.collect { log.value = it } }
        val identity = e.identify()
        console.value = "ATI → $identity"
        diagnostic.value = JeepDiagnostic(e)
        status.value = "ELM327 обнаружен: $identity"
        val intent = Intent(context, DiagnosticService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun disconnect() {
        liveJob?.cancel(); liveJob = null
        keepaliveJob?.cancel(); keepaliveJob = null
        polling.value = false
        transport?.disconnect(); transport = null
        elm.value = null; diagnostic.value = null
        context.stopService(Intent(context, DiagnosticService::class.java))
        status.value = "Адаптер не подключён"
    }

    fun retry() { val d = devices.value.firstOrNull { it.address == lastAddress }; if (d != null) connect(d) }

    fun send(command: String) = run {
        val e = elm.value ?: error("Сначала подключите адаптер")
        console.value = "$command → ${e.command(command)}"
    }

    fun chooseModule(module: JeepModule) = run {
        stopLive()
        keepaliveJob?.cancel(); keepaliveJob = null
        diagnostic.value?.select(module) ?: error("Сначала подключите адаптер")
        selected.value = module
        status.value = if (module.kline) "${module.title}: связь есть" else "${module.title}: протокол выбран, нажмите сканирование"
        restartKeepalive(module)
    }

    private fun restartKeepalive(module: JeepModule) {
        keepaliveJob?.cancel()
        if (module.kline) keepaliveJob = scope.launch {
            while (isActive) {
                delay(2_000)
                if (!polling.value) runCatching { elm.value?.command("81", 2_000) }
            }
        }
    }

    fun scanModules() = run {
        stopLive()
        keepaliveJob?.cancel(); keepaliveJob = null
        val d = diagnostic.value ?: error("Сначала подключите адаптер")
        d.scanAll()
        status.value = "Сканирование завершено"
    }

    fun readDtc() = run {
        stopLive()
        keepaliveJob?.cancel(); keepaliveJob = null
        val list = diagnostic.value?.readFaults(selected.value) ?: error("Сначала подключите адаптер")
        status.value = "${selected.value.title}: ${list.size} ошибок"
        restartKeepalive(selected.value)
    }

    fun clearDtc() = run {
        stopLive()
        keepaliveJob?.cancel(); keepaliveJob = null
        val list = diagnostic.value?.clearFaults(selected.value) ?: error("Сначала подключите адаптер")
        status.value = "После стирания: ${list.size} ошибок"
        restartKeepalive(selected.value)
    }

    fun startLive() {
        if (liveJob?.isActive == true) return
        val d = diagnostic.value
        if (d == null) { lastError.value = "Сначала подключите адаптер"; return }
        polling.value = true
        liveJob = scope.launch {
            while (isActive) {
                try { d.pollLive(selected.value) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastError.value = e.message ?: "Ошибка Live Data"
                    break
                }
                delay(300)
                if (recording.value) {
                    val values = d.live.value
                    recordRows += (listOf(System.currentTimeMillis().toString()) + recordKeys.map { k -> values[k]?.value?.toString() ?: "" }).joinToString(",")
                }
            }
            polling.value = false
        }
    }
    fun stopLive() { liveJob?.cancel(); liveJob = null; polling.value = false }

    fun chooseGatt(tx: String?, rx: String?) { ble.select(tx, rx) }
    fun clearLog() { elm.value?.clearLog(); log.value = emptyList() }
    fun startRecording(keys: Set<String>) {
        recordKeys = keys.sorted()
        recordRows.clear()
        recordRows += (listOf("time_ms") + recordKeys).joinToString(",")
        recording.value = true
        startLive()
    }
    fun stopRecording(): String {
        recording.value = false
        return recordRows.joinToString("\n")
    }

    private fun run(block: suspend () -> Unit) {
        scope.launch {
            busy.value = true
            lastError.value = ""
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError.value = e.message ?: "Неизвестная ошибка"
                status.value = lastError.value
            } finally { busy.value = false }
        }
    }
}
