package org.wjdiagnostic

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Serial ELM command gate. Every caller waits for '>' before another command can be written. */
class Elm327(private val transport: BluetoothTransport) {
    val log = MutableStateFlow<List<String>>(emptyList())
    private val lock = Mutex()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun record(message: String) {
        log.value = (log.value + "[${stamp.format(Date())}] $message").takeLast(1000)
    }
    fun clearLog() { log.value = emptyList() }

    suspend fun command(text: String, timeoutMs: Long = 5_000): String = lock.withLock {
        val cmd = text.trim().uppercase(Locale.US)
        require(cmd.isNotEmpty() && cmd.length <= 128 && cmd.all { it.code in 32..126 }) { "Неверная команда" }
        while (transport.incoming.tryReceive().isSuccess) { /* stale unsolicited bytes */ }
        record("TX: $cmd")
        transport.write("$cmd\r".toByteArray(StandardCharsets.US_ASCII))
        val raw = try {
            withTimeout(timeoutMs) {
                val buffer = StringBuilder()
                while (true) {
                    val fragment = transport.incoming.receive()
                    if (fragment.isEmpty()) error("Bluetooth connection lost")
                    record("RAW RX: " + fragment.joinToString(" ") { "%02X".format(it) })
                    buffer.append(String(fragment, StandardCharsets.ISO_8859_1))
                    if ('>' in buffer) break
                    if (buffer.length > 32_768) error("ELM response too long")
                }
                buffer.toString()
            }
        } catch (e: TimeoutCancellationException) {
            record("TIMEOUT: $cmd"); throw IllegalStateException("Timeout waiting for response: $cmd", e)
        }
        val lines = raw.replace('>', ' ').replace('\n', '\r').split('\r')
            .map { it.trim() }.filter { it.isNotEmpty() && !it.equals(cmd, true) && it != "ü" }
        val result = lines.joinToString("\r")
        record("RX: $result")
        result
    }

    suspend fun identify(): String {
        val response = command("ATI", 7_000)
        if (response.isBlank() || response == "?" || response.contains("NO DATA")) error("ELM327 не ответил на ATI: $response")
        return response
    }
}
