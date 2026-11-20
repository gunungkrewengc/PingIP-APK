package com.pingip.app

import android.content.Context

data class PingConfig(
    val target: String = "dns.google",
    val intervalSec: Double = 0.5,
    val timeoutSec: Double = 3.0,
    val maxFail: Int = 2,
    val airplaneDelaySec: Double = 1.0,
    val cooldownSec: Double = 4.0,
    val watcherIntervalSec: Double = 0.5,
    val shizukuRetrySec: Double = 2.0,
    val maxLines: Int = 100,
    val vpnPkg: String = "xyz.easypro.httpcustom",
    val execMode: String = MODE_SHIZUKU
) {
    companion object {
        const val MODE_SHIZUKU = "shizuku"
        const val MODE_ROOT = "root"
        private const val PREF_NAME = "pingip_prefs"

        fun load(context: Context): PingConfig {
            val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return PingConfig(
                target = p.getString("target", "dns.google") ?: "dns.google",
                intervalSec = (p.getString("intervalSec", "0.5")?.toDoubleOrNull() ?: 0.5).coerceAtLeast(0.0),
                timeoutSec = (p.getString("timeoutSec", "3.0")?.toDoubleOrNull() ?: 3.0).coerceAtLeast(0.5),
                maxFail = (p.getInt("maxFail", 2)).coerceAtLeast(1),
                airplaneDelaySec = (p.getString("airplaneDelaySec", "1.0")?.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.0),
                cooldownSec = (p.getString("cooldownSec", "4.0")?.toDoubleOrNull() ?: 4.0).coerceAtLeast(0.0),
                watcherIntervalSec = (p.getString("watcherIntervalSec", "0.5")?.toDoubleOrNull() ?: 0.5).coerceAtLeast(0.2),
                shizukuRetrySec = (p.getString("shizukuRetrySec", "2.0")?.toDoubleOrNull() ?: 2.0).coerceAtLeast(0.5),
                maxLines = (p.getInt("maxLines", 100)).coerceAtLeast(20),
                vpnPkg = p.getString("vpnPkg", "xyz.easypro.httpcustom") ?: "xyz.easypro.httpcustom",
                execMode = p.getString("execMode", MODE_SHIZUKU) ?: MODE_SHIZUKU
            )
        }

        fun save(context: Context, cfg: PingConfig) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString("target", cfg.target.trim().ifEmpty { "dns.google" })
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
                .apply()
        }
    }
}
