package com.pingip.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        System.exit(0)
    }

    override fun exec(command: String?): String {
        if (command.isNullOrBlank()) return ""
        var process: Process? = null
        return try {
            val proc = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = proc
            val sb = StringBuilder()
            val readerThread = Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            if (sb.length < 8192) {
                                sb.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }.apply {
                isDaemon = true
                start()
            }
            val finished = proc.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return "ERR:TIMEOUT"
            }
            readerThread.join(1000)
            sb.toString().trim()
        } catch (t: Throwable) {
            "ERR:${t.message ?: "exec"}"
        } finally {
            try { process?.destroy() } catch (_: Throwable) {}
        }
    }
}
