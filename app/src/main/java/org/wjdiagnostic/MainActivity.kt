package org.wjdiagnostic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var controller: DiagnosticController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = DiagnosticController(this, lifecycleScope)
        setContent { MaterialTheme { App(controller) } }
    }
    override fun onDestroy() { controller.disconnect(); super.onDestroy() }
}

@Composable
private fun App(c: DiagnosticController) {
    val context = LocalContext.current
    val status by c.status.collectAsState()
    val error by c.lastError.collectAsState()
    val busy by c.busy.collectAsState()
    val selected by c.selected.collectAsState()
    var csvToSave by remember { mutableStateOf("") }
    val diagnostic by c.diagnostic.collectAsState()
    val elm by c.elm.collectAsState()
    var page by remember { mutableStateOf("Связь") }
    var permissionMessage by remember { mutableStateOf("") }
    val permissions = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionMessage = if (result.values.all { it }) "" else "Разрешите Bluetooth и геолокацию для поиска адаптера. Координаты не собираются."
    }
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(c.log.value.joinToString("\n").toByteArray())
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { out -> out.write(csvToSave.toByteArray()) }
    }
    LaunchedEffect(Unit) { permissionLauncher.launch(permissions) }

    Scaffold(topBar = { Text("WJ 2.7 Diagnostic", Modifier.padding(18.dp), fontWeight = FontWeight.Bold) },
        bottomBar = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Связь", "Блоки", "Данные", "Ошибки", "Консоль", "Диагностика").forEach { name ->
                    TextButton(onClick = { page = name }, modifier = Modifier.weight(1f)) {
                        Text(name.take(5), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            Text("Jeep WJ/WG 2.7 CRD", style = MaterialTheme.typography.titleMedium)
            Text(status, color = if (error.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (permissionMessage.isNotEmpty()) {
                Text(permissionMessage, color = MaterialTheme.colorScheme.error)
                Button(onClick = { permissionLauncher.launch(permissions) }) { Text("Запросить разрешения") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            when (page) {
                "Связь" -> ConnectionPage(c)
                "Блоки" -> ModulePage(c)
                "Данные" -> LivePage(c, diagnostic, selected, onExportCsv = { data -> csvToSave = data; exportCsv.launch("WJ-trip.csv") })
                "Ошибки" -> FaultPage(c, diagnostic, selected)
                "Консоль" -> ConsolePage(c, elm)
                else -> DeveloperPage(c, elm, onExport = { exportLog.launch("WJ-Diagnostic.log") })
            }
        }
    }
}

@Composable
private fun ConnectionPage(c: DiagnosticController) {
    val mode by c.mode.collectAsState()
    val devices by c.devices.collectAsState()
    val ready by c.elm.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Выберите тип адаптера", style = MaterialTheme.typography.titleMedium)
        Row { listOf("Classic", "BLE").forEach { m ->
            FilterChip(selected = mode == m, onClick = { c.setMode(m) }, label = { Text(m) }, modifier = Modifier.padding(end = 8.dp))
        } }
        Text(if (mode == "Classic") "Classic: сначала сопрягите адаптер в настройках телефона" else "BLE: адаптер появится после сканирования")
        Row {
            Button(onClick = { c.scan() }) { Text("Поиск адаптеров") }
            Spacer(Modifier.width(8.dp))
            if (ready != null) Button(onClick = { c.disconnect() }) { Text("Отключить") }
        }
        devices.forEach { d ->
            OutlinedCard(onClick = { c.connect(d) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(d.name, fontWeight = FontWeight.Bold)
                    Text("${d.address}  ${d.rssi?.let { "$it dBm" } ?: "сопряжён"}")
                }
            }
        }
    }
}

@Composable
private fun ModulePage(c: DiagnosticController) {
    val selected by c.selected.collectAsState()
    val d by c.diagnostic.collectAsState()
    val states = d?.modules?.collectAsState()?.value ?: emptyMap()
    Column {
        Button(onClick = { c.scanModules() }, enabled = d != null) { Text("Сканировать все блоки") }
        Text("Проверка идёт по одному блоку; это может занять несколько минут.")
        LazyColumn { items(JeepModule.entries) { m ->
            OutlinedCard(onClick = { c.chooseModule(m) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${if (selected == m) "● " else "○ "}${m.title}")
                    Text(states[m] ?: "Не проверен", style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
    }
}

@Composable
private fun LivePage(c: DiagnosticController, d: JeepDiagnostic?, selected: JeepModule, onExportCsv: (String) -> Unit) {
    val live = d?.live?.collectAsState()?.value ?: emptyMap()
    val polling by c.polling.collectAsState()
    val recording by c.recording.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("favorites", 0) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("keys", emptySet())!!.toSet()) }
    var onlyFavorite by remember { mutableStateOf(false) }
    var graphKey by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateMapOf<String, List<Pair<Long, Double>>>() }
    LaunchedEffect(live) {
        val now = System.currentTimeMillis()
        live.forEach { (k, v) -> history[k] = ((history[k] ?: emptyList()) + (now to v.value)).filter { now - it.first <= 300_000 } }
    }
    Column {
        Text("${selected.title} — реальные ответы ECU", style = MaterialTheme.typography.titleMedium)
        Row {
            Button(onClick = { if (polling) c.stopLive() else c.startLive() }, enabled = d != null) { Text(if (polling) "Стоп" else "Начать Live Data") }
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = onlyFavorite, onClick = { onlyFavorite = !onlyFavorite }, label = { Text("Мои параметры") })
        }
        Row {
            if (!recording) OutlinedButton(onClick = { c.startRecording(if (favorites.isEmpty()) live.keys else favorites) }, enabled = d != null) { Text("Начать запись") }
            else OutlinedButton(onClick = { onExportCsv(c.stopRecording()) }) { Text("Остановить и сохранить CSV") }
        }
        if (graphKey != null) {
            val k = graphKey!!
            var seconds by remember { mutableIntStateOf(30) }
            Row { listOf(30, 60, 300).forEach { n -> TextButton(onClick = { seconds = n }) { Text(if (n == 300) "5 мин" else "$n с") } } }
            Graph(history[k]?.filter { System.currentTimeMillis() - it.first <= seconds * 1000 } ?: emptyList())
            Button(onClick = { graphKey = null }) { Text("Назад к параметрам") }
        } else LazyColumn {
            items(live.values.filter { !onlyFavorite || it.key in favorites }.sortedBy { it.name }) { v ->
                OutlinedCard(onClick = { graphKey = v.key }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(v.name); Text("%.1f %s".format(Locale.US, v.value, v.unit), fontWeight = FontWeight.Bold) }
                        Checkbox(checked = v.key in favorites, onCheckedChange = {
                            favorites = if (it) favorites + v.key else favorites - v.key
                            prefs.edit().putStringSet("keys", favorites).apply()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Graph(points: List<Pair<Long, Double>>) {
    if (points.size < 2) { Text("Накопление данных…"); return }
    val min = points.minOf { it.second }; val max = points.maxOf { it.second }
    Canvas(Modifier.fillMaxWidth().height(180.dp).padding(12.dp)) {
        val first = points.first().first; val span = (points.last().first - first).coerceAtLeast(1).toFloat()
        val range = (max - min).coerceAtLeast(1.0)
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = size.width * (p.first - first).toFloat() / span
            val y = size.height * (1f - ((p.second - min) / range).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFF008577), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
    }
    Text("Диапазон %.1f–%.1f".format(Locale.US, min, max))
}

@Composable
private fun FaultPage(c: DiagnosticController, d: JeepDiagnostic?, selected: JeepModule) {
    val faults = d?.faults?.collectAsState()?.value?.filter { it.module == selected } ?: emptyList()
    var confirm by remember { mutableStateOf(false) }
    Column {
        Text(selected.title, style = MaterialTheme.typography.titleMedium)
        Row {
            Button(onClick = { c.readDtc() }, enabled = d != null) { Text("Читать ошибки") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { confirm = true }, enabled = d != null && selected.kline) { Text("Стереть ошибки") }
        }
        Text("Пустой список до чтения не означает отсутствие ошибок.")
        LazyColumn { items(faults) { fault ->
            Column(Modifier.padding(8.dp)) {
                Text(fault.code, fontWeight = FontWeight.Bold)
                Text(fault.description)
                Text(fault.module.title)
            }
        } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Стереть ошибки ${selected.title}?") },
        text = { Text("Команда будет отправлена в блок, затем ошибки будут прочитаны снова.") },
        confirmButton = { TextButton(onClick = { confirm = false; c.clearDtc() }) { Text("Стереть") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
}

@Composable
private fun ConsolePage(c: DiagnosticController, e: Elm327?) {
    var command by remember { mutableStateOf("") }
    val response by c.console.collectAsState()
    Column {
        Text("ELM327 Console", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Команда, например ATI") }, singleLine = true)
        Button(onClick = { c.send(command); command = "" }, enabled = e != null && command.isNotBlank()) { Text("Отправить") }
        Text(response)
        Text("Не отправляйте неизвестные команды записи.")
    }
}

@Composable
private fun DeveloperPage(c: DiagnosticController, e: Elm327?, onExport: () -> Unit) {
    val log by c.log.collectAsState()
    val gatt by c.ble.gattInfo.collectAsState()
    val notify by c.ble.notifyEnabled.collectAsState()
    Column {
        Row {
            Button(onClick = onExport) { Text("Сохранить лог") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { c.clearLog() }) { Text("Очистить") }
        }
        Text("BLE: MTU ${c.ble.mtu}, Notify ${if (notify) "ON" else "OFF"}")
        Text("TX: ${c.ble.chosenTx ?: "не выбран"}  RX: ${c.ble.chosenRx ?: "не выбран"}")
        LazyColumn {
            items(gatt) { item ->
                Column(Modifier.padding(4.dp)) {
                    Text("${item.service} / ${item.characteristic}", style = MaterialTheme.typography.bodySmall)
                    Text(item.properties, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (item.properties.contains("Write")) TextButton(onClick = { c.chooseGatt(item.characteristic, null) }) { Text("Выбрать TX") }
                        if (item.properties.contains("Notify") || item.properties.contains("Indicate")) TextButton(onClick = { c.chooseGatt(null, item.characteristic) }) { Text("Выбрать RX") }
                    }
                }
            }
            items(log.takeLast(200)) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
