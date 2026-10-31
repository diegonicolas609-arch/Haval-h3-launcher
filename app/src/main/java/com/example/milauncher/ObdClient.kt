package com.example.milauncher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.UUID
import kotlin.concurrent.thread

/** Cliente mínimo para adaptadores OBD2 Bluetooth ELM327 (perfil serie SPP). */
class ObdClient {
    @Volatile var rpm = 0
    @Volatile var speed = -1
    @Volatile var coolant = -1
    @Volatile var connected = false
    @Volatile var status = "OBD2 desconectado"
    @Volatile private var running = false

    val busy get() = running

    private var socket: BluetoothSocket? = null
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun connect(dev: BluetoothDevice) {
        disconnect()
        running = true
        thread {
            try {
                status = "Conectando a ${dev.name ?: dev.address}…"
                val s = dev.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                s.connect()
                socket = s
                listOf("ATZ", "ATE0", "ATL0", "ATSP0").forEach { send(it) }
                send("0100") // fuerza la detección del protocolo
                connected = true
                status = "OBD2 conectado: ${dev.name ?: dev.address}"
                var n = 0
                while (running) {
                    pid(send("010C"), 0x0C)?.let { if (it.size >= 2) rpm = (it[0] * 256 + it[1]) / 4 }
                    pid(send("010D"), 0x0D)?.let { if (it.isNotEmpty()) speed = it[0] }
                    if (n % 5 == 0) pid(send("0105"), 0x05)?.let { if (it.isNotEmpty()) coolant = it[0] - 40 }
                    n++
                }
            } catch (e: Exception) {
                status = "Error OBD2: ${e.message}"
            } finally {
                connected = false; running = false
                try { socket?.close() } catch (_: Exception) {}
                if (!status.startsWith("Error")) status = "OBD2 desconectado"
            }
        }
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }

    /** Lee códigos de falla (modo 03). Ejecutar fuera del hilo principal. */
    fun readDtcs(): List<String> {
        val resp = send("03")
        val out = mutableListOf<String>()
        for (line in resp.split("\n")) {
            val b = hexBytes(line)
            val i = b.indexOf(0x43)
            if (i < 0) continue
            var d = b.drop(i + 1)
            if (d.size % 2 == 1) d = d.drop(1) // en CAN el primer byte es el conteo
            for (k in 0 until d.size - 1 step 2) {
                val a = d[k]; val c = d[k + 1]
                if (a == 0 && c == 0) continue
                val code = "${"PCBU"[a shr 6]}${(a shr 4) and 3}${(a and 15).toString(16)}${c.toString(16).padStart(2, '0')}"
                out.add(code.uppercase())
            }
        }
        return out.distinct()
    }

    /** Borra fallas y apaga la luz Check Engine (modo 04). */
    fun clearDtcs() { send("04") }

    // ---- bajo nivel ----
    private fun send(cmd: String): String = synchronized(lock) {
        val s = socket ?: return ""
        val out = s.outputStream; val inp = s.inputStream
        out.write((cmd + "\r").toByteArray()); out.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val c = inp.read()
                if (c == '>'.code) break
                sb.append(c.toChar())
            } else Thread.sleep(10)
        }
        sb.toString().replace("\r", "\n").trim()
    }

    private fun hexBytes(s: String) = s.split(Regex("\\s+"))
        .filter { it.matches(Regex("[0-9A-Fa-f]{2}")) }.map { it.toInt(16) }

    private fun pid(resp: String, pid: Int): List<Int>? {
        val b = hexBytes(resp.replace("\n", " "))
        for (i in 0 until b.size - 1) if (b[i] == 0x41 && b[i + 1] == pid) return b.drop(i + 2)
        return null
    }
}
