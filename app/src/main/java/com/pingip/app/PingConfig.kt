package com.pingip.app

import android.content.Context

data class PingConfig(
    val target: String = "1.1.1.1",
    val fallbackTarget: String = "",
    val pingMode: String = PING_HTTPS,
    val intervalSec: Double = 1.0,
    val timeoutSec: Double = 1.0,
    val maxFail: Int = 2,
    val airplaneDelaySec: Double = 0.5,
    val cooldownSec: Double = 8.0,
    val watcherIntervalSec: Double = 0.5,
    val shizukuRetrySec: Double = 2.0,
    val maxLines: Int = 100,
    val vpnPkg: String = "xyz.easypro.httpcustom",
    val execMode: String = MODE_SHIZUKU,
    val pauseOnWifi: Boolean = true,
    val compactLog: Boolean = false,
    val antiLoopLimit: Int = 5,
    val antiLoopCooldownSec: Double = 30.0
) {
    companion object {
        const val MODE_SHIZUKU = "shizuku"
        const val MODE_ROOT = "root"
        const val PING_HTTPS = "https"
        const val PING_TCP = "tcp"
        private const val PREF_NAME = "pingip_prefs"

        fun load(context: Context): PingConfig {
            val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return PingConfig(
                target = p.getString("target", "1.1.1.1") ?: "1.1.1.1",
                fallbackTarget = p.getString("fallbackTarget", "") ?: "",
                pingMode = p.getString("pingMode", PING_HTTPS) ?: PING_HTTPS,
                intervalSec = (p.getString("intervalSec", "1.0")?.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.0),
                timeoutSec = (p.getString("timeoutSec", "1.0")?.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.5),
                maxFail = (p.getInt("maxFail", 2)).coerceAtLeast(1),
                airplaneDelaySec = (p.getString("airplaneDelaySec", "0.5")?.toDoubleOrNull() ?: 0.5).coerceAtLeast(0.0),
                cooldownSec = (p.getString("cooldownSec", "8.0")?.toDoubleOrNull() ?: 8.0).coerceAtLeast(0.0),
                watcherIntervalSec = (p.getString("watcherIntervalSec", "0.5")?.toDoubleOrNull() ?: 0.5).coerceAtLeast(0.2),
                shizukuRetrySec = (p.getString("shizukuRetrySec", "2.0")?.toDoubleOrNull() ?: 2.0).coerceAtLeast(0.5),
                maxLines = (p.getInt("maxLines", 100)).coerceAtLeast(20),
                vpnPkg = p.getString("vpnPkg", "xyz.easypro.httpcustom") ?: "xyz.easypro.httpcustom",
                execMode = p.getString("execMode", MODE_SHIZUKU) ?: MODE_SHIZUKU,
                pauseOnWifi = p.getBoolean("pauseOnWifi", true),
                compactLog = p.getBoolean("compactLog", false),
                antiLoopLimit = (p.getInt("antiLoopLimit", 5)).coerceAtLeast(0),
                antiLoopCooldownSec = (p.getString("antiLoopCooldownSec", "30.0")?.toDoubleOrNull() ?: 30.0).coerceAtLeast(1.0)
            )
        }

        fun save(context: Context, cfg: PingConfig) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString("target", cfg.target.trim().ifEmpty { "1.1.1.1" })
                .putString("fallbackTarget", cfg.fallbackTarget.trim())
                .putString("pingMode", cfg.pingMode)
                .putString("intervalSec", cfg.intervalSec.coerceAtLeast(0.0).toString())
                .putString("timeoutSec", cfg.timeoutSec.coerceAtLeast(0.5).toString())
                .putInt("maxFail", cfg.maxFail.coerceAtLeast(1))
                .putString("airplaneDelaySec", cfg.airplaneDelaySec.coerceAtLeast(0.0).toString())
                .putString("cooldownSec", cfg.cooldownSec.coerceAtLeast(0.0).toString())
                .putString("watcherIntervalSec", cfg.watcherIntervalSec.coerceAtLeast(0.2).toString())
                .putString("shizukuRetrySec", cfg.shizukuRetrySec.coerceAtLeast(0.5).toString())
                .putInt("maxLines", cfg.maxLines.coerceAtLeast(20))
                .putString("vpnPkg", cfg.vpnPkg.trim())
                .putString("execMode", cfg.execMode)
                .putBoolean("pauseOnWifi", cfg.pauseOnWifi)
                .putBoolean("compactLog", cfg.compactLog)
                .putInt("antiLoopLimit", cfg.antiLoopLimit.coerceAtLeast(0))
                .putString("antiLoopCooldownSec", cfg.antiLoopCooldownSec.coerceAtLeast(1.0).toString())
                .apply()
        }
    }
}
