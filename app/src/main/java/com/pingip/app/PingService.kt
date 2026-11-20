package com.pingip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PingService : Service() {

    companion object {
        const val ACTION_START = "com.pingip.app.ACTION_START"
        const val ACTION_STOP = "com.pingip.app.ACTION_STOP"
        private const val CHANNEL_ID = "pingip_monitor_channel"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mainJob: Job? = null
    private var watcherJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var shellService: IShellService? = null

    @Volatile
    private var dataOk: Boolean = false

    @Volatile
    private var vpnOk: Boolean = false

    @Volatile
    private var watcherReady: Boolean = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                shellService = IShellService.Stub.asInterface(binder)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (PingStateStore.isRunning.value && mainJob?.isActive == true) {
            return START_STICKY
        }

        val notif = buildNotification("Memulai PingIP...")
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
        acquireWakeLock()
        startMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoringInternal()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingIP::MonitorLock").apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun stopMonitoring() {
        stopMonitoringInternal()
        stopSelf()
    }

    private fun stopMonitoringInternal() {
        mainJob?.cancel()
        watcherJob?.cancel()
        mainJob = null
        watcherJob = null
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (_: Exception) {
        }
        shellService = null
        watcherReady = false
        releaseWakeLock()
        PingStateStore.setRunning(false)
        PingStateStore.setStatus("Berhenti")
    }

    private fun startMonitoring() {
        val cfg = PingConfig.load(this)
        PingStateStore.setRunning(true)
        PingStateStore.clearLogs()
        log(
            "=== PingIP v4.3 APK (Mode: ${cfg.execMode.uppercase()} | ON-OFF: ${cfg.airplaneDelaySec}s | Cooldown: ${cfg.cooldownSec}s | Ping: ${cfg.intervalSec}s | Watcher: ${cfg.watcherIntervalSec}s) ===",
            cfg.maxLines
        )

        mainJob = scope.launch {
            var pauseReason = ""
            var failCount = 0
            var toggleCount = 0

            // 1. Tunggu & hubungkan Shizuku / Root di awal saja (mengikuti SHIZUKU_RETRY)
            if (cfg.execMode == PingConfig.MODE_SHIZUKU) {
                val retryMs = (cfg.shizukuRetrySec.coerceAtLeast(0.5) * 1000).toLong()
                var bindRequested = false
                while (isActive && shellService == null) {
                    val binderReady = try {
                        Shizuku.pingBinder() &&
                            !Shizuku.isPreV11() &&
                            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    } catch (_: Exception) {
                        false
                    }

                    if (binderReady) {
                        if (!bindRequested) {
                            try {
                                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                                bindRequested = true
                            } catch (_: Exception) {
                            }
                        }
                        delay(250)
                        if (shellService != null) break
                    } else {
                        bindRequested = false
                    }

                    if (pauseReason != "SHIZUKU_OFF") {
                        log("[${now()}] JEDA: Shizuku belum aktif / belum diizinkan (coba ulang tiap ${cfg.shizukuRetrySec}s...)", cfg.maxLines)
                        updateStatus("JEDA: Menunggu Shizuku")
                        pauseReason = "SHIZUKU_OFF"
                    }
                    delay(retryMs)
                }
                if (!isActive) return@launch
                if (pauseReason == "SHIZUKU_OFF") {
                    log("[${now()}] Shizuku terhubung! Memulai pemantauan...", cfg.maxLines)
                    pauseReason = ""
                }
            } else {
                val idOut = execShell(cfg.execMode, "id")
                if (!idOut.contains("uid=0")) {
                    log("[${now()}] [!] Akses Root (su) ditolak atau tidak tersedia.", cfg.maxLines)
                    updateStatus("Gagal: Root tidak tersedia")
                    stopMonitoring()
                    return@launch
                }
                log("[${now()}] Root (su) aktif! Memulai pemantauan...", cfg.maxLines)
            }

            // 2. Jalankan watcher latar belakang (mengikuti WATCHER_INTERVAL)
            startWatcher(cfg)
            var waitInit = 0
            while (isActive && !watcherReady && waitInit < 30) {
                delay(100)
                waitInit++
            }

            // 3. Loop utama (deteksi Shizuku hanya di awal, bebas false-positive saat jalan)
            while (isActive) {
                if (!dataOk) {
                    if (pauseReason != "DATA_OFF") {
                        log("[${now()}] JEDA: Data seluler mati (menunggu dihidupkan...)", cfg.maxLines)
                        updateStatus("JEDA: Data seluler mati")
                        pauseReason = "DATA_OFF"
                    }
                    failCount = 0
                    delay(500)
                    continue
                }

                if (!vpnOk) {
                    if (pauseReason != "VPN_OFF") {
                        log("[${now()}] JEDA: VPN HTTP Custom belum konek (menunggu...)", cfg.maxLines)
                        updateStatus("JEDA: Menunggu HTTP Custom")
                        pauseReason = "VPN_OFF"
                    }
                    failCount = 0
                    delay(500)
                    continue
                }

                if (pauseReason.isNotEmpty()) {
                    log("[${now()}] LANJUT: Data seluler & HTTP Custom aktif!", cfg.maxLines)
                    updateStatus("Aktif memantau (${cfg.target})")
                    pauseReason = ""
                    failCount = 0
                }

                val latency = checkConnection(cfg.target, cfg.timeoutSec)
                if (latency != null) {
                    failCount = 0
                    log("[${now()}] OK ($latency)", cfg.maxLines)
                    updateStatus("Aktif - OK ($latency)")
                } else {
                    failCount++
                    log("[${now()}] TIMEOUT ($failCount/${cfg.maxFail})", cfg.maxLines)
                    updateStatus("TIMEOUT ($failCount/${cfg.maxFail})")

                    if (failCount >= cfg.maxFail) {
                        // Pengaman real-time anti-bablas Mode Pesawat sebelum eksekusi
                        val guard = checkLiveGuard(cfg.execMode)
                        if (guard == "GUARD:DATA_OFF") {
                            log("[${now()}] JEDA: Data seluler mati (Mode Pesawat dibatalkan)", cfg.maxLines)
                            updateStatus("JEDA: Data seluler mati")
                            pauseReason = "DATA_OFF"
                            failCount = 0
                            delay(500)
                            continue
                        } else if (guard == "GUARD:VPN_OFF") {
                            log("[${now()}] JEDA: VPN belum konek (Mode Pesawat dibatalkan)", cfg.maxLines)
                            updateStatus("JEDA: Menunggu HTTP Custom")
                            pauseReason = "VPN_OFF"
                            failCount = 0
                            delay(500)
                            continue
                        }

                        toggleCount++
                        log("[${now()}] Mode Pesawat ON/OFF (${cfg.airplaneDelaySec}s) (#$toggleCount)", cfg.maxLines)
                        updateStatus("Mode Pesawat ON/OFF (#$toggleCount)")

                        val apCmd = if (cfg.airplaneDelaySec <= 0.0) {
                            "cmd connectivity airplane-mode enable; cmd connectivity airplane-mode disable"
                        } else {
                            "cmd connectivity airplane-mode enable; sleep ${cfg.airplaneDelaySec}; cmd connectivity airplane-mode disable"
                        }
                        val out = execShell(cfg.execMode, apCmd)
                        if (out.startsWith("ERR:")) {
                            log("[${now()}] [!] Gagal toggle mode pesawat ($out)", cfg.maxLines)
                        }

                        failCount = 0
                        if (cfg.cooldownSec > 0.0) {
                            delay((cfg.cooldownSec * 1000).toLong())
                        }
                        continue
                    }
                }

                if (cfg.intervalSec > 0.0) {
                    delay((cfg.intervalSec * 1000).toLong())
                }
            }
        }
    }

    private fun startWatcher(cfg: PingConfig) {
        watcherJob?.cancel()
        val safePkg = cfg.vpnPkg.replace("'", "")
        val d = "$"
        val watcherCmd = """
            VPN_PKG='$safePkg'
            sub=${d}(settings get global multi_sim_data_call 2>/dev/null | tr -d '\r\n')
            md_sub=""
            case "${d}sub" in
                [0-9]*) md_sub=${d}(settings get global "mobile_data${d}sub" 2>/dev/null | tr -d '\r\n') ;;
            esac
            if [ "${d}md_sub" = '0' ] || [ "${d}md_sub" = '1' ]; then
                data_ok="${d}md_sub"
            else
                md_all=${d}(settings list global 2>/dev/null | grep -E '^mobile_data[0-9]+=')
                if [ -n "${d}md_all" ]; then
                    echo "${d}md_all" | grep -q '=1${d}' && data_ok=1 || data_ok=0
                else
                    md=${d}(settings get global mobile_data 2>/dev/null | tr -d '\r\n')
                    [ "${d}md" = '1' ] && data_ok=1 || data_ok=0
                fi
            fi

            vpn_ok=0
            vpn_iface=0
            if ip link 2>/dev/null | grep -qE 'tun[0-9]+:.*[<,]UP[,>]'; then
                vpn_iface=1
            elif dumpsys connectivity 2>/dev/null | grep -qE 'type: VPN.*CONNECTED'; then
                vpn_iface=1
            fi

            if [ "${d}vpn_iface" = '1' ]; then
                if [ -z "${d}VPN_PKG" ]; then
                    vpn_ok=1
                else
                    vman=${d}(dumpsys vpn_management 2>/dev/null)
                    if [ -n "${d}vman" ]; then
                        if echo "${d}vman" | grep -q "${d}VPN_PKG"; then
                            vpn_ok=1
                        elif ! echo "${d}vman" | grep -qE 'mPackage=|Package:'; then
                            dumpsys activity services "${d}VPN_PKG" 2>/dev/null | grep -qE 'isForeground=true|VpnService|TunnelVpn' && vpn_ok=1
                        fi
                    else
                        if dumpsys connectivity 2>/dev/null | grep -iE "vpn|${d}VPN_PKG" | grep -q "${d}VPN_PKG"; then
                            vpn_ok=1
                        elif dumpsys activity services "${d}VPN_PKG" 2>/dev/null | grep -qE 'isForeground=true|VpnService|TunnelVpn'; then
                            vpn_ok=1
                        fi
                    fi
                fi
            fi
            echo "STATE:${d}data_ok:${d}vpn_ok"
        """.trimIndent()

        val wMs = (cfg.watcherIntervalSec.coerceAtLeast(0.2) * 1000).toLong()
        watcherJob = scope.launch {
            while (isActive) {
                val out = execShell(cfg.execMode, watcherCmd)
                val line = out.lines().lastOrNull { it.startsWith("STATE:") }
                if (line != null) {
                    val parts = line.split(":")
                    if (parts.size >= 3) {
                        dataOk = parts[1].trim() == "1"
                        vpnOk = parts[2].trim() == "1"
                        watcherReady = true
                    }
                }
                delay(wMs)
            }
        }
    }

    private fun checkLiveGuard(execMode: String): String {
        val d = "$"
        val guardCmd = """
            sub=${d}(settings get global multi_sim_data_call 2>/dev/null | tr -d '\r\n')
            ms=""
            case "${d}sub" in
                [0-9]*) ms=${d}(settings get global "mobile_data${d}sub" 2>/dev/null | tr -d '\r\n') ;;
            esac
            if [ "${d}ms" = '0' ] || [ "${d}ms" = '1' ]; then
                dok="${d}ms"
            else
                ma=${d}(settings list global 2>/dev/null | grep -E '^mobile_data[0-9]+=')
                if [ -n "${d}ma" ]; then
                    echo "${d}ma" | grep -q '=1${d}' && dok=1 || dok=0
                else
                    m=${d}(settings get global mobile_data 2>/dev/null | tr -d '\r\n')
                    [ "${d}m" = '1' ] && dok=1 || dok=0
                fi
            fi
            [ "${d}dok" != '1' ] && { echo "GUARD:DATA_OFF"; exit 0; }
            if ip link 2>/dev/null | grep -qE 'tun[0-9]+:.*[<,]UP[,>]' || dumpsys connectivity 2>/dev/null | grep -qE 'type: VPN.*CONNECTED'; then
                echo "GUARD:OK"
            else
                echo "GUARD:VPN_OFF"
            fi
        """.trimIndent()
        val out = execShell(execMode, guardCmd)
        return out.lines().lastOrNull { it.startsWith("GUARD:") }?.trim() ?: ""
    }

    private fun checkConnection(target: String, timeoutSec: Double): String? {
        val timeoutMs = (timeoutSec.coerceAtLeast(0.5) * 1000).toInt()
        val startNs = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://$target")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                useCaches = false
            }
            val code = conn.responseCode
            if (code in 200..399) {
                val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                String.format(Locale.US, "%.2fs", elapsedSec)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun execShell(execMode: String, command: String): String {
        return if (execMode == PingConfig.MODE_ROOT) {
            try {
                val pb = ProcessBuilder("su", "-c", command)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val ok = proc.waitFor(12, TimeUnit.SECONDS)
                if (!ok) {
                    proc.destroyForcibly()
                    "ERR:TIMEOUT"
                } else {
                    proc.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                "ERR:${e.message}"
            }
        } else {
            try {
                shellService?.exec(command) ?: "ERR:SHIZUKU_UNBOUND"
            } catch (e: Exception) {
                "ERR:${e.message}"
            }
        }
    }

    private fun now(): String = timeFmt.format(Date())

    private fun log(msg: String, maxLines: Int) {
        PingStateStore.appendLog(msg, maxLines)
    }

    private fun updateStatus(status: String) {
        PingStateStore.setStatus(status)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PingIP Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status pemantauan PingIP & Mode Pesawat"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("PingIP v4.3")
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }
}
