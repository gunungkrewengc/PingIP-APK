package com.pingip.app

import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Keep
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String?): String {
        if (command.isNullOrBlank()) return ""
        return try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val outBuf = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(2048)
                        var n: Int
                        while (br.read(buf).also { n = it } != -1) {
                            if (outBuf.length < 32768) {
                                outBuf.append(buf, 0, n)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            readerThread.start()
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(500)
                return "ERR:TIMEOUT"
            }
            readerThread.join(1000)
            outBuf.toString()
        } catch (e: Exception) {
            "ERR:${e.message}"
        }
    }
}
