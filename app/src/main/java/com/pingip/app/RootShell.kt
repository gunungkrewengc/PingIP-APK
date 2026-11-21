package com.pingip.app

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sesi shell root (su) persisten tunggal:
 * - Membuka 1 proses `su` saja saat Mulai -> hanya 1 entri log di Magisk / KernelSU.
 * - Setiap perintah dibungkus subshell `( ... )` agar `exit` tidak mematikan sesi utama.
 * - Reader thread daemon terpisah + timeout keras mencegah deadlock pipe Linux.
 * - Otomatis membuka ulang sesi jika proses su dimatikan sistem.
 */
class RootShell {

    companion object {
        private const val EOF_SENTINEL = "__PINGIP_STREAM_EOF__"
        private const val MAX_OUTPUT_CHARS = 32768
    }

    private val lock = Any()
    private val seq = AtomicLong(0L)

    @Volatile
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var lineQueue: LinkedBlockingQueue<String>? = null

    fun openAndVerify(timeoutMs: Long = 15000L): Boolean {
        synchronized(lock) {
            if (!ensureAliveLocked()) return false
            val out = execInternalLocked("id", timeoutMs)
            if (out.contains("uid=0")) {
                return true
            }
            closeLocked()
            return false
        }
    }

    fun exec(command: String, timeoutMs: Long = 12000L): String {
        if (command.isBlank()) return ""
        synchronized(lock) {
            if (!ensureAliveLocked()) {
                return "ERR:SU_START_FAILED"
            }
            val first = execInternalLocked(command, timeoutMs)
            if (first == "ERR:EOF") {
                // Coba buka ulang sekali jika sesi su sebelumnya terputus
                if (ensureAliveLocked()) {
                    return execInternalLocked(command, timeoutMs)
                }
            }
            return first
        }
    }

    fun close() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun ensureAliveLocked(): Boolean {
        val cur = process
        if (cur != null && cur.isAlive && writer != null && lineQueue != null) {
            return true
        }
        closeLocked()
        return try {
            val pb = ProcessBuilder("su")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val q = LinkedBlockingQueue<String>(512)
            val w = BufferedWriter(OutputStreamWriter(proc.outputStream))

            val readerThread = Thread({
                try {
                    proc.inputStream.bufferedReader().use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            val item = line ?: ""
                            if (!q.offer(item)) {
                                q.poll()
                                q.offer(item)
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (!q.offer(EOF_SENTINEL)) {
                        q.poll()
                        q.offer(EOF_SENTINEL)
                    }
                }
            }, "PingIP-SuReader").apply {
                isDaemon = true
            }
            readerThread.start()

            process = proc
            writer = w
            lineQueue = q
            true
        } catch (_: Exception) {
            closeLocked()
            false
        }
    }

    private fun execInternalLocked(command: String, timeoutMs: Long): String {
        val w = writer ?: return "ERR:NO_WRITER"
        val q = lineQueue ?: return "ERR:NO_QUEUE"
        q.clear()

        val token = seq.incrementAndGet()
        val marker = "__PINGIP_DONE_${token}_${System.nanoTime()}__"
        return try {
            w.write("(\n")
            w.write(command)
            w.write("\n)\n")
            w.write("echo '$marker'\n")
            w.flush()

            val sb = StringBuilder()
            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1000L))
            while (true) {
                val remMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                if (remMs <= 0L) {
                    closeLocked()
                    return "ERR:TIMEOUT"
                }
                val line = q.poll(remMs, TimeUnit.MILLISECONDS)
                if (line == null) {
                    closeLocked()
                    return "ERR:TIMEOUT"
                }
                if (line == marker) {
                    break
                }
                if (line == EOF_SENTINEL) {
                    closeLocked()
                    return "ERR:EOF"
                }
                if (sb.length < MAX_OUTPUT_CHARS) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            closeLocked()
            "ERR:${e.message}"
        }
    }

    private fun closeLocked() {
        try {
            writer?.apply {
                write("exit\n")
                flush()
                close()
            }
        } catch (_: Exception) {
        }
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
        writer = null
        process = null
        lineQueue?.clear()
        lineQueue = null
    }
}
