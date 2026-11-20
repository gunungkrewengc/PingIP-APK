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
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "ERR:TIMEOUT"
            }
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "ERR:${e.message}"
        }
    }
}
