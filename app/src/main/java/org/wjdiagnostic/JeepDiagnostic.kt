package org.wjdiagnostic

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

enum class JeepModule(val title: String, val address: Int, val kline: Boolean) {
    ECM("Двигатель ECM", 0x15, true), TCM("Коробка TCM", 0x20, true),
    ABS("ABS", 0x28, false), AIRBAG("Airbag", 0x58, false),
    BCM("Кузов BCM", 0x40, false), CLUSTER("Панель приборов", 0x60, false),
    HVAC("Климат", 0x98, false), SKIM("Иммобилайзер", 0xC0, false),
    DRIVER_DOOR("Дверь водителя", 0xA0, false), PASSENGER_DOOR("Дверь пассажира", 0xA1, false)
}
data class Fault(val module: JeepModule, val code: String, val description: String, val raw: String)
data class LiveValue(val key: String, val name: String, val value: Double, val unit: String)

/** Commands and block offsets are from takyonxxx/Jeep_Grand_Cherokee_WJ_2.7_CRD_OBD-II_Diagnostic (GPL-3.0). */
class JeepDiagnostic(private val elm: Elm327) {
    val active = MutableStateFlow<JeepModule?>(null)
    val faults = MutableStateFlow<List<Fault>>(emptyList())
    val live = MutableStateFlow<Map<String, LiveValue>>(emptyMap())
    val modules = MutableStateFlow<Map<JeepModule, String>>(emptyMap())

    private suspend fun at(command: String) {
        val reply = elm.command(command, 7_000)
        if (reply.contains("ERROR") || reply == "?" || reply.contains("TIMEOUT")) error("$command: $reply")
    }
    private fun bytes(response: String): List<Int> = response.replace('\r', ' ').replace('\n', ' ')
        .split(Regex("\\s+")).mapNotNull { s -> s.takeIf { it.length == 2 && it.all { c -> c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f' } }?.toIntOrNull(16) }
    private fun hasToken(response: String, token: Int) = bytes(response).contains(token)
    private fun word(data: List<Int>, at: Int) = data[at] * 256 + data[at + 1]
    private fun put(key: String, name: String, value: Double, unit: String) {
        live.value = live.value + (key to LiveValue(key, name, value, unit))
    }

    suspend fun select(module: JeepModule) {
        if (active.value == module) return
        val old = active.value
        active.value = null
        if (old?.kline == true && module.kline) { runCatching { elm.command("82", 2_000) }; delay(600) }
        if (module.kline) {
            at("ATZ"); at("ATZ"); at("ATE1"); at("ATH1")
            val addr = "%02X".format(module.address)
            at("ATWM81${addr}F13E")
            if (module == JeepModule.TCM) at("ATWM81${addr}F13E")
            at("ATSH81${addr}F1"); at("ATSP5")
            val init = elm.command("ATFI", 7_000)
            if (init.contains("BUS INIT: ERROR")) error("K-Line BUS INIT: ERROR")
            val reply = elm.command("81", 4_000)
            if (!hasToken(reply, 0xC1) && !reply.contains("BUS INIT: OK")) error("${module.title} не отвечает: $reply")
        } else {
            val addr = "%02X".format(module.address)
            at("ATZ"); at("ATZ"); at("ATSP2"); at("ATIFR0"); at("ATH1")
            at("ATSH24${addr}22"); at("ATRA$addr")
        }
        active.value = module
        if (module.kline) modules.value = modules.value + (module to "Связь есть")
    }

    suspend fun probe(module: JeepModule): Boolean = try {
        select(module)
        if (module.kline) true else {
            val reply = elm.command("20 00 00 00", 4_000)
            val yes = !reply.contains("NO DATA") && reply.isNotBlank() && reply != "?"
            modules.value = modules.value + (module to if (yes) "Связь есть" else "Нет ответа")
            yes
        }
    } catch (e: Exception) {
        modules.value = modules.value + (module to "Нет ответа: ${e.message}")
        false
    }

    suspend fun scanAll() {
        for (m in JeepModule.entries) probe(m)
    }

    suspend fun readFaults(module: JeepModule): List<Fault> {
        select(module)
        if (!module.kline) return readJ1850Faults(module)
        val command = if (module == JeepModule.TCM) "18 02 FF 00" else "18 02 00 00"
        val response = elm.command(command, 8_000)
        if (response.contains("NO DATA") || response.contains("TIMEOUT")) error("${module.title}: $response")
        val b = bytes(response)
        val marker = b.indexOf(0x58)
        if (marker < 0 || marker + 1 >= b.size) error("Неверный ответ DTC: $response")
        val count = b[marker + 1]
        val list = (0 until count).mapNotNull { n ->
            val p = marker + 2 + n * 3
            if (p + 2 >= b.size) null else {
                val hi = b[p]; val lo = b[p + 1]
                val code = "${"PCBU"[hi shr 6]}${(hi shr 4) and 3}${hi and 15}${"%02X".format(lo)}"
                Fault(module, code, if (code == "P0702") "Transmission Control System Electrical" else "Описание не подтверждено", response)
            }
        }
        faults.value = faults.value.filterNot { it.module == module } + list
        modules.value = modules.value + (module to if (list.isEmpty()) "0 ошибок" else "${list.size} ошибок")
        return list
    }

    private suspend fun readJ1850Faults(module: JeepModule): List<Fault> {
        // WJdiag-Pro 0x2E/0x2F PID probes; only documented modules are decoded.
        val probes = when (module) {
            JeepModule.ABS -> listOf("2E 10", "2E 11", "2E 12", "2E 13", "2E 14", "2E 15", "2E 16", "2E 17",
                "2E 20", "2E 21", "2E 22", "2E 23", "2E 24", "2E 25", "2E 26", "2E 27", "2E 30")
            JeepModule.BCM -> listOf("2E 00", "2E 01", "2E 02", "2E 03", "2E 05", "2E 12")
            JeepModule.AIRBAG -> listOf(0x10,0x13,0x16,0x19,0x1C,0x1F,0x22,0x25,0x28,0x2B,0x2E,0x37,0x3A,0x3D,0x43,0x49,0x4C,0x4F,0x52,0x70,0x73,0x76,0x79,0x7C,0x7F,0x82,0x85,0x88,0x8B,0x8E,0x91,0x94,0x97,0xDC,0xDF,0xE2,0xE5,0xFA,0xFD).map { "2E %02X".format(it) } +
                listOf(0x00,0x03,0x06,0x18,0x1B,0x1E,0x21,0x24,0x27,0x2A,0x2D,0x30,0x33,0x36,0x39,0x3C).map { "2F %02X".format(it) }
            else -> error("DTC PID scan для ${module.title} пока не подтверждён")
        }
        val found = mutableListOf<Fault>()
        for (pid in probes) {
            val reply = elm.command("$pid 00", 4_000)
            if (reply.contains("NO DATA") || reply == "?") continue
            val b = bytes(reply)
            val idx = b.indices.firstOrNull { it >= 2 && it + 3 < b.size && b[it - 2] == 0x26 && b[it - 1] == module.address && b[it] == 0x62 } ?: continue
            val d0 = b[idx + 1]; val d1 = b[idx + 2]; val d2 = b[idx + 3]
            val raw = d0 * 256 + d1
            if (raw != 0 && raw != 0xFFFF && !(d1 == 0xFF && d2 == 0xFF))
                found += Fault(module, pid, "Код блока $pid", reply)
        }
        faults.value = faults.value.filterNot { it.module == module } + found
        modules.value = modules.value + (module to if (found.isEmpty()) "0 ошибок (${probes.size} PID)" else "${found.size} ошибок")
        return found
    }

    suspend fun clearFaults(module: JeepModule): List<Fault> {
        select(module)
        require(module.kline) { "Стирание ${module.title} пока не разрешено" }
        val response = elm.command("14 00 00", 8_000)
        var confirmed = hasToken(response, 0x54)
        if (!confirmed && module == JeepModule.TCM && response.contains("7F 14 78")) {
            delay(1_500)
            val deferred = elm.command("18 02 FF 00", 8_000)
            confirmed = hasToken(deferred, 0x54)
        }
        if (!confirmed) error("ECU не подтвердил стирание: $response")
        delay(500)
        return readFaults(module)
    }

    suspend fun pollLive(module: JeepModule) {
        select(module)
        require(module.kline) { "Live Data ${module.title} пока не реализованы" }
        val blocks = if (module == JeepModule.ECM) listOf(0x28, 0x12, 0x36, 0x16) else listOf(0x30, 0x31, 0x34)
        for (block in blocks) {
            val response = elm.command("21 %02X".format(block), 4_000)
            val b = bytes(response)
            val i = b.indices.firstOrNull { it + 1 < b.size && b[it] == 0x61 && b[it + 1] == block } ?: continue
            val d = b.drop(i + 2)
            if (module == JeepModule.ECM) when (block) {
                0x28 -> if (d.size >= 2) put("rpm", "Обороты", word(d, 0).toDouble(), "rpm")
                0x12 -> {
                    if (d.size >= 2) put("coolant", "Температура ОЖ", word(d, 0) / 10.0 - 273.1, "°C")
                    if (d.size >= 4) put("iat", "Температура воздуха", word(d, 2) / 10.0 - 273.1, "°C")
                    if (d.size >= 14) put("pedal", "Педаль газа", (word(d, 12) / 100.0).coerceIn(0.0, 100.0), "%")
                    if (d.size >= 18) put("boost", "Наддув фактический", word(d, 16) / 1000.0, "bar abs")
                    if (d.size >= 20) put("rail", "Давление рейки", word(d, 18) * 0.101, "bar")
                }
                0x36 -> {
                    if (d.size >= 8) put("maf", "Расход воздуха", word(d, 6) / 10.0, "mg/str")
                    if (d.size >= 10) put("boostTarget", "Наддув заданный", word(d, 8) / 1000.0, "bar abs")
                }
                0x16 -> if (d.size >= 4) put("voltage", "Напряжение ECU", word(d, 2) * 5.0 / 3072.0, "V")
            } else when (block) {
                0x30 -> {
                    if (d.size >= 6) put("tcmOutput", "Выходной вал", word(d, 4).toDouble(), "rpm")
                    if (d.size >= 12) put("tcmTemp", "Температура АКПП", d[11] - 50.0, "°C")
                    if (d.size >= 10) put("tcmGear", "Передача", d[9].toDouble(), "")
                }
                0x31 -> if (d.size >= 6) put("tcmInput", "Входной вал", word(d, 4).toDouble(), "rpm")
                0x34 -> if (d.size >= 8) put("tcmVoltage", "Напряжение соленоидов", word(d, 6) / 40.0, "V")
            }
        }
    }
}
