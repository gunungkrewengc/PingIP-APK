package com.pingip.app

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PersistentRootShell {
    private val lock = Any()
    private val seqGen = AtomicInteger(0)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var queue = LinkedBlockingQueue<String>()
    private var readerThread: Thread? = null

    fun ensureOpen(): Boolean = synchronized(lock) {
        if (isProcessAlive()) return true
        closeLocked()
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            val q = LinkedBlockingQueue<String>()
            val rt = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            q.offer(line)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    q.offer("__EOF__")
                }
            }.apply {
                name = "PingIP-RootReader"
                isDaemon = true
                start()
            }
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            queue = q
            readerThread = rt
            val probe = execLocked("echo OK", 4000L)
            probe == "OK"
        } catch (_: Throwable) {
            closeLocked()
            false
        }
    }

    fun exec(command: String, timeoutMs: Long = 10_000L): String? = synchronized(lock) {
        if (!isProcessAlive() && !ensureOpen()) return null
        val res = execLocked(command, timeoutMs)
        if (res == null) {
            closeLocked()
        }
        res
    }

    private fun execLocked(command: String, timeoutMs: Long): String? {
        val w = writer ?: return null
        val q = queue
        q.clear()
        val marker = "__PINGIP_DONE_${seqGen.incrementAndGet()}__"
        return try {
            w.write(command)
            w.newLine()
            w.write("echo $marker")
            w.newLine()
            w.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val rem = deadline - System.currentTimeMillis()
                if (rem <= 0L) return null
                val line = q.poll(rem, TimeUnit.MILLISECONDS) ?: return null
                if (line == marker) break
                if (line == "__EOF__") return null
                if (sb.length < 8192) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
            }
            sb.toString().trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun isProcessAlive(): Boolean {
        val p = process ?: return false
        return try {
            p.isAlive
        } catch (_: Throwable) {
            false
        }
    }

    fun close() = synchronized(lock) {
        closeLocked()
    }

    private fun closeLocked() {
        try {
            writer?.apply {
                write("exit\n")
                flush()
                close()
            }
        } catch (_: Throwable) {}
        try { process?.destroy() } catch (_: Throwable) {}
        writer = null
        process = null
        readerThread = null
        queue.clear()
    }
}
