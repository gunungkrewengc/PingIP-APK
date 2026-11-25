package com.pingip.app

import android.content.Context

data class PingConfig(
    val execMode: String = MODE_ROOT,
    val postToggleMode: String = POST_IP_CHANGE,
    val pingMethod: String = PING_HTTP_204,
    val pauseOnWifi: Boolean = true,
    val compactLog: Boolean = false,
    val airplaneDelaySec: Double = 0.0,
    val ipStabilizeSec: Double = 1.5,
    val ipWaitMaxSec: Double = 15.0,
    val cooldownSec: Double = 8.0,
    val intervalSec: Double = 1.0,
    val timeoutSec: Double = 1.0,
    val failThreshold: Int = 2,
    val antiLoopLimit: Int = 10,
    val antiLoopCooldownSec: Double = 30.0,
    val watcherSec: Double = 0.5,
    val shizukuRetrySec: Double = 2.0,
    val maxLogLines: Int = 100,
    val targetPrimary: String = "cp.cloudflare.com/generate_204",
    val targetBackup: String = "",
    val vpnPackageFilter: String = "xyz.easypro.httpcustom"
) {
    companion object {
        const val PREFS = "pingip_v4_prefs"
        const val MODE_ROOT = "root"
        const val MODE_SHIZUKU = "shizuku"
        const val MODE_ASSISTANT = "assistant"

        const val POST_IP_CHANGE = "ip_change"
        const val POST_COOLDOWN = "cooldown"

        const val PING_HTTP_204 = "http_204"
        const val PING_HTTPS_HEAD = "https_head"
        const val PING_TCP_443 = "tcp_443"

        fun load(context: Context): PingConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return PingConfig(
                execMode = p.getString("exec_mode", MODE_ROOT) ?: MODE_ROOT,
                postToggleMode = p.getString("post_toggle_mode", POST_IP_CHANGE) ?: POST_IP_CHANGE,
                pingMethod = p.getString("ping_method", PING_HTTP_204) ?: PING_HTTP_204,
                pauseOnWifi = p.getBoolean("pause_on_wifi", true),
                compactLog = p.getBoolean("compact_log", false),
                airplaneDelaySec = p.getFloat("airplane_delay_sec", 0.0f).toDouble().coerceIn(0.0, 30.0),
                ipStabilizeSec = p.getFloat("ip_stabilize_sec", 1.5f).toDouble().coerceIn(0.0, 30.0),
                ipWaitMaxSec = p.getFloat("ip_wait_max_sec", 15.0f).toDouble().coerceIn(2.0, 120.0),
                cooldownSec = p.getFloat("cooldown_sec", 8.0f).toDouble().coerceIn(0.5, 120.0),
                intervalSec = p.getFloat("interval_sec", 1.0f).toDouble().coerceIn(0.2, 60.0),
                timeoutSec = p.getFloat("timeout_sec", 1.0f).toDouble().coerceIn(0.3, 15.0),
                failThreshold = p.getInt("fail_threshold", 2).coerceIn(1, 20),
                antiLoopLimit = p.getInt("anti_loop_limit", 10).coerceIn(0, 100),
                antiLoopCooldownSec = p.getFloat("anti_loop_cooldown_sec", 30.0f).toDouble().coerceIn(2.0, 300.0),
                watcherSec = p.getFloat("watcher_sec", 0.5f).toDouble().coerceIn(0.2, 10.0),
                shizukuRetrySec = p.getFloat("shizuku_retry_sec", 2.0f).toDouble().coerceIn(0.5, 15.0),
                maxLogLines = p.getInt("max_log_lines", 100).coerceIn(20, 500),
                targetPrimary = (p.getString("target_primary", "cp.cloudflare.com/generate_204")
                    ?: "cp.cloudflare.com/generate_204").trim().ifEmpty { "cp.cloudflare.com/generate_204" },
                targetBackup = (p.getString("target_backup", "") ?: "").trim(),
                vpnPackageFilter = (p.getString("vpn_package_filter", "xyz.easypro.httpcustom")
                    ?: "xyz.easypro.httpcustom").trim()
            )
        }

        fun save(context: Context, cfg: PingConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("exec_mode", cfg.execMode)
                .putString("post_toggle_mode", cfg.postToggleMode)
                .putString("ping_method", cfg.pingMethod)
                .putBoolean("pause_on_wifi", cfg.pauseOnWifi)
                .putBoolean("compact_log", cfg.compactLog)
                .putFloat("airplane_delay_sec", cfg.airplaneDelaySec.coerceIn(0.0, 30.0).toFloat())
                .putFloat("ip_stabilize_sec", cfg.ipStabilizeSec.coerceIn(0.0, 30.0).toFloat())
                .putFloat("ip_wait_max_sec", cfg.ipWaitMaxSec.coerceIn(2.0, 120.0).toFloat())
                .putFloat("cooldown_sec", cfg.cooldownSec.coerceIn(0.5, 120.0).toFloat())
                .putFloat("interval_sec", cfg.intervalSec.coerceIn(0.2, 60.0).toFloat())
                .putFloat("timeout_sec", cfg.timeoutSec.coerceIn(0.3, 15.0).toFloat())
                .putInt("fail_threshold", cfg.failThreshold.coerceIn(1, 20))
                .putInt("anti_loop_limit", cfg.antiLoopLimit.coerceIn(0, 100))
                .putFloat("anti_loop_cooldown_sec", cfg.antiLoopCooldownSec.coerceIn(2.0, 300.0).toFloat())
                .putFloat("watcher_sec", cfg.watcherSec.coerceIn(0.2, 10.0).toFloat())
                .putFloat("shizuku_retry_sec", cfg.shizukuRetrySec.coerceIn(0.5, 15.0).toFloat())
                .putInt("max_log_lines", cfg.maxLogLines.coerceIn(20, 500))
                .putString("target_primary", cfg.targetPrimary.trim().ifEmpty { "cp.cloudflare.com/generate_204" })
                .putString("target_backup", cfg.targetBackup.trim())
                .putString("vpn_package_filter", cfg.vpnPackageFilter.trim())
                .apply()
        }
    }
}
