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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PingService : Service() {

    companion object {
        const val ACTION_START = "com.pingip.app.ACTION_START"
        const val ACTION_STOP = "com.pingip.app.ACTION_STOP"
        const val ACTION_PAUSE_TOGGLE = "com.pingip.app.ACTION_PAUSE_TOGGLE"
        const val ACTION_MANUAL_AIRPLANE = "com.pingip.app.ACTION_MANUAL_AIRPLANE"
        private const val CHANNEL_ID = "pingip_monitor_channel"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pingExecutor = ThreadPoolExecutor(
        0,
        6,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue()
    ) { r ->
        Thread(r, "PingIP-Net").apply { isDaemon = true }
    }
    private val rootShell = RootShell()

    private var mainJob: Job? = null
    private var watcherJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var shellService: IShellService? = null

    @Volatile
    private var dataOk: Boolean = false

    @Volatile
    private var wifiConnected: Boolean = false

    @Volatile
    private var vpnIfaceUp: Boolean = false

    @Volatile
    private var vpnOk: Boolean = false

    @Volatile
    private var watcherReady: Boolean = false

    @Volatile
    private var manualPaused: Boolean = false

    @Volatile
    private var useShellDataFallback: Boolean = false

    @Volatile
    private var lastNotifUpdateMs: Long = 0L

    private val airplaneBusy = AtomicBoolean(false)

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
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_TOGGLE -> {
                if (PingStateStore.isRunning.value) {
                    val cfg = PingConfig.load(this)
                    manualPaused = !manualPaused
                    PingStateStore.setPaused(manualPaused)
                    if (manualPaused) {
                        log("[${now()}] JEDA MANUAL (via notifikasi)", cfg.maxLines)
                        updateStatus("JEDA MANUAL", forceNotif = true)
                    } else {
                        log("[${now()}] LANJUT MANUAL", cfg.maxLines)
                        updateStatus("Melanjutkan...", forceNotif = true)
                    }
                }
                return START_STICKY
            }
            ACTION_MANUAL_AIRPLANE -> {
                if (PingStateStore.isRunning.value) {
                    triggerManualAirplane()
                }
                return START_STICKY
            }
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
        pingExecutor.shutdownNow()
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
        manualPaused = false
        useShellDataFallback = false
        rootShell.close()
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (_: Exception) {
        }
        shellService = null
        watcherReady = false
        releaseWakeLock()
        PingStateStore.setRunning(false)
        PingStateStore.setPaused(false)
        PingStateStore.setStatus("Berhenti")
        requestTileRefresh()
    }

    private fun triggerManualAirplane() {
        if (!airplaneBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val cfg = PingConfig.load(this@PingService)
                val t = PingStateStore.recordToggle(now())
                log("[${now()}] Reset IP Manual ON/OFF (${cfg.airplaneDelaySec}s) (#$t)", cfg.maxLines)
                updateStatus("Reset IP Manual (#$t)", forceNotif = true)
                val out = runAirplaneToggle(cfg)
                if (out.startsWith("ERR:")) {
                    log("[${now()}] [!] Gagal Reset IP Manual ($out)", cfg.maxLines)
                }
            } finally {
                airplaneBusy.set(false)
            }
        }
    }

    private fun startMonitoring() {
        val cfg = PingConfig.load(this)
        manualPaused = false
        useShellDataFallback = false
        PingStateStore.setRunning(true)
        PingStateStore.setPaused(false)
        PingStateStore.resetStats(SystemClock.elapsedRealtime())
        PingStateStore.clearLogs()
        requestTileRefresh()

        val antiLoopInfo = if (cfg.antiLoopLimit > 0) "${cfg.antiLoopLimit}x->${cfg.antiLoopCooldownSec}s" else "OFF"
        log(
            "=== PingIP v4.5 (${cfg.execMode.uppercase()} | ${cfg.pingMode.uppercase()} | ON-OFF: ${cfg.airplaneDelaySec}s | CD: ${cfg.cooldownSec}s | Ping: ${cfg.intervalSec}s | AntiLoop: $antiLoopInfo) ===",
            cfg.maxLines
        )

        mainJob = scope.launch {
            var pauseReason = ""
            var failCount = 0
            var consecutiveToggles = 0

            // 1. Buka 1 sesi Shizuku atau 1 sesi Persistent Root Shell di awal
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
                        updateStatus("JEDA: Menunggu Shizuku", forceNotif = true)
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
                if (!rootShell.openAndVerify(18000L)) {
                    log("[${now()}] [!] Akses Root (su) ditolak atau tidak tersedia.", cfg.maxLines)
                    updateStatus("Gagal: Root tidak tersedia", forceNotif = true)
                    stopMonitoring()
                    return@launch
                }
                log("[${now()}] Root Persistent Shell aktif (1 sesi su)! Memulai pemantauan...", cfg.maxLines)
            }

            // 2. Jalankan watcher native Android (0 panggilan shell saat stabil)
            startNativeWatcher(cfg)
            var waitInit = 0
            while (isActive && !watcherReady && waitInit < 25) {
                delay(80)
                waitInit++
            }

            // 3. Loop utama
            while (isActive) {
                if (manualPaused) {
                    failCount = 0
                    pauseReason = "MANUAL"
                    delay(400)
                    continue
                }

                if (cfg.pauseOnWifi && wifiConnected) {
                    if (pauseReason != "WIFI_ON") {
                        log("[${now()}] JEDA: Terhubung ke Wi-Fi (menunggu pindah ke Data Seluler...)", cfg.maxLines)
                        updateStatus("JEDA: Mode Wi-Fi", forceNotif = true)
                        pauseReason = "WIFI_ON"
                    }
                    failCount = 0
                    delay(500)
                    continue
                }

                if (!dataOk) {
                    if (pauseReason != "DATA_OFF") {
                        log("[${now()}] JEDA: Data seluler mati (menunggu dihidupkan...)", cfg.maxLines)
                        updateStatus("JEDA: Data seluler mati", forceNotif = true)
                        pauseReason = "DATA_OFF"
                    }
                    failCount = 0
                    delay(500)
                    continue
                }

                if (!vpnOk) {
                    if (pauseReason != "VPN_OFF") {
                        val vName = if (cfg.vpnPkg.isBlank()) "VPN" else "VPN HTTP Custom"
                        log("[${now()}] JEDA: $vName belum konek (menunggu...)", cfg.maxLines)
                        updateStatus("JEDA: Menunggu $vName", forceNotif = true)
                        pauseReason = "VPN_OFF"
                    }
                    failCount = 0
                    delay(500)
                    continue
                }

                if (pauseReason.isNotEmpty()) {
                    log("[${now()}] LANJUT: Data seluler & VPN aktif!", cfg.maxLines)
                    updateStatus("Aktif memantau (${cfg.target})", forceNotif = true)
                    pauseReason = ""
                    failCount = 0
                }

                val latency = checkConnectionWithFallback(cfg)
                if (latency != null) {
                    failCount = 0
                    if (consecutiveToggles > 0) {
                        consecutiveToggles = 0
                    }
                    if (!cfg.compactLog) {
                        log("[${now()}] OK ($latency)", cfg.maxLines)
                    }
                    updateStatus("Aktif - OK ($latency)", forceNotif = false)
                } else {
                    failCount++
                    log("[${now()}] TIMEOUT ($failCount/${cfg.maxFail})", cfg.maxLines)
                    updateStatus("TIMEOUT ($failCount/${cfg.maxFail})", forceNotif = true)

                    if (failCount >= cfg.maxFail) {
                        // Pengaman real-time anti-bablas (cek native + verifikasi shell persisten sebelum Mode Pesawat)
                        val liveNet = readNativeNetFlags()
                        if (cfg.pauseOnWifi && liveNet.first) {
                            log("[${now()}] JEDA: Wi-Fi aktif (Mode Pesawat dibatalkan)", cfg.maxLines)
                            updateStatus("JEDA: Mode Wi-Fi", forceNotif = true)
                            pauseReason = "WIFI_ON"
                            failCount = 0
                            delay(500)
                            continue
                        }

                        val liveNativeData = readMobileDataNative()
                        val liveData = if (liveNativeData == false) {
                            false
                        } else {
                            val shellData = checkMobileDataViaShell(cfg.execMode)
                            if (!shellData) {
                                useShellDataFallback = true
                                dataOk = false
                            }
                            shellData
                        }
                        if (!liveData) {
                            log("[${now()}] JEDA: Data seluler mati (Mode Pesawat dibatalkan)", cfg.maxLines)
                            updateStatus("JEDA: Data seluler mati", forceNotif = true)
                            pauseReason = "DATA_OFF"
                            failCount = 0
                            delay(500)
                            continue
                        }

                        if (!liveNet.second) {
                            log("[${now()}] JEDA: VPN belum konek (Mode Pesawat dibatalkan)", cfg.maxLines)
                            updateStatus("JEDA: Menunggu VPN", forceNotif = true)
                            pauseReason = "VPN_OFF"
                            failCount = 0
                            delay(500)
                            continue
                        }

                        if (airplaneBusy.compareAndSet(false, true)) {
                            try {
                                val tCount = PingStateStore.recordToggle(now())
                                consecutiveToggles++
                                log("[${now()}] Mode Pesawat ON/OFF (${cfg.airplaneDelaySec}s) (#$tCount)", cfg.maxLines)
                                updateStatus("Mode Pesawat ON/OFF (#$tCount)", forceNotif = true)

                                val out = runAirplaneToggle(cfg)
                                if (out.startsWith("ERR:")) {
                                    log("[${now()}] [!] Gagal toggle mode pesawat ($out)", cfg.maxLines)
                                }
                            } finally {
                                airplaneBusy.set(false)
                            }
                        }

                        failCount = 0
                        val useBackoff = cfg.antiLoopLimit > 0 && consecutiveToggles >= cfg.antiLoopLimit
                        val waitSec = if (useBackoff) cfg.antiLoopCooldownSec else cfg.cooldownSec
                        if (useBackoff) {
                            log(
                                "[${now()}] ANTI-LOOP: ${consecutiveToggles}x toggle tanpa OK, jeda ${waitSec}s",
                                cfg.maxLines
                            )
                            updateStatus("Anti-Loop - Jeda ${waitSec}s", forceNotif = true)
                        }
                        if (waitSec > 0.0) {
                            delay((waitSec * 1000).toLong())
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

    private fun startNativeWatcher(cfg: PingConfig) {
        watcherJob?.cancel()
        val wMs = (cfg.watcherIntervalSec.coerceAtLeast(0.2) * 1000).toLong()
        val vpnPkg = cfg.vpnPkg.trim()

        watcherJob = scope.launch {
            var cachedPkgOk = false
            var lastVerifyMs = 0L
            var lastShellDataMs = 0L
            var lastShellDataVal = true

            while (isActive) {
                val curData = if (!useShellDataFallback) {
                    val nativeVal = readMobileDataNative()
                    if (nativeVal != null) {
                        nativeVal
                    } else {
                        useShellDataFallback = true
                        lastShellDataMs = SystemClock.elapsedRealtime()
                        lastShellDataVal = checkMobileDataViaShell(cfg.execMode)
                        lastShellDataVal
                    }
                } else {
                    val nowMs = SystemClock.elapsedRealtime()
                    if (lastShellDataMs == 0L || nowMs - lastShellDataMs >= 1000L) {
                        lastShellDataMs = nowMs
                        lastShellDataVal = checkMobileDataViaShell(cfg.execMode)
                    }
                    lastShellDataVal
                }

                val netFlags = readNativeNetFlags()
                val curWifi = netFlags.first
                val curVpnIface = netFlags.second

                dataOk = curData
                wifiConnected = curWifi
                vpnIfaceUp = curVpnIface

                if (!curVpnIface) {
                    cachedPkgOk = false
                    lastVerifyMs = 0L
                    vpnOk = false
                } else if (vpnPkg.isEmpty()) {
                    vpnOk = true
                } else if (cachedPkgOk) {
                    vpnOk = true
                } else {
                    val nowMs = SystemClock.elapsedRealtime()
                    if (lastVerifyMs == 0L || nowMs - lastVerifyMs >= 3000L) {
                        lastVerifyMs = nowMs
                        cachedPkgOk = verifyVpnPackageViaShell(cfg.execMode, vpnPkg)
                    }
                    vpnOk = cachedPkgOk
                }

                watcherReady = true
                delay(wMs)
            }
        }
    }

    private fun safeGlobalSetting(key: String): String? {
        return try {
            Settings.Global.getString(contentResolver, key)?.trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun readMobileDataNative(): Boolean? {
        try {
            val baseTm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val defSub = SubscriptionManager.getDefaultDataSubscriptionId()
            val tm = if (defSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                baseTm.createForSubscriptionId(defSub)
            } else {
                baseTm
            }
            return tm.isDataEnabled
        } catch (_: Throwable) {
        }

        val defSub = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Throwable) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        if (defSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            val v = safeGlobalSetting("mobile_data$defSub")
            if (v == "0" || v == "1") return v == "1"
        }

        val subGlobal = safeGlobalSetting("multi_sim_data_call")
        if (!subGlobal.isNullOrEmpty() && subGlobal.all { it.isDigit() }) {
            val v = safeGlobalSetting("mobile_data$subGlobal")
            if (v == "0" || v == "1") return v == "1"
        }

        val globalMd = safeGlobalSetting("mobile_data")
        if (globalMd == "0" || globalMd == "1") return globalMd == "1"

        return null
    }

    private fun checkMobileDataViaShell(execMode: String): Boolean {
        val defSub = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Throwable) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val defSubStr = if (defSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) defSub.toString() else ""
        val d = "$"
        val cmd = """
            DEF_SUB='$defSubStr'
            res=""
            if [ -n "${d}DEF_SUB" ]; then
                md_def=${d}(settings get global "mobile_data${d}DEF_SUB" 2>/dev/null | tr -d '\r\n')
                if [ "${d}md_def" = '0' ] || [ "${d}md_def" = '1' ]; then
                    res="${d}md_def"
                fi
            fi

            if [ -z "${d}res" ]; then
                sub=${d}(settings get global multi_sim_data_call 2>/dev/null | tr -d '\r\n')
                case "${d}sub" in
                    [0-9]*)
                        md_sub=${d}(settings get global "mobile_data${d}sub" 2>/dev/null | tr -d '\r\n')
                        if [ "${d}md_sub" = '0' ] || [ "${d}md_sub" = '1' ]; then
                            res="${d}md_sub"
                        fi
                        ;;
                esac
            fi

            if [ -z "${d}res" ]; then
                md=${d}(settings get global mobile_data 2>/dev/null | tr -d '\r\n')
                if [ "${d}md" = '0' ]; then
                    res="0"
                else
                    ma=${d}(settings list global 2>/dev/null | grep -E '^mobile_data[0-9]+=')
                    if [ -n "${d}ma" ]; then
                        echo "${d}ma" | grep -q '=1${d}' && res="1" || res="0"
                    else
                        [ "${d}md" = '1' ] && res="1" || res="0"
                    fi
                fi
            fi
            echo "DATA_STATE:${d}res"
        """.trimIndent()
        val out = execShell(execMode, cmd)
        val line = out.lines().lastOrNull { it.startsWith("DATA_STATE:") }?.trim()
        return line != "DATA_STATE:0"
    }

    @Suppress("DEPRECATION")
    private fun readNativeNetFlags(): Pair<Boolean, Boolean> {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var hasWifi = false
            var hasVpn = false
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    hasVpn = true
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    hasWifi = true
                }
            }
            Pair(hasWifi, hasVpn)
        } catch (_: Exception) {
            Pair(false, false)
        }
    }

    private fun verifyVpnPackageViaShell(execMode: String, vpnPkg: String): Boolean {
        val safePkg = vpnPkg.replace("'", "")
        val d = "$"
        val cmd = """
            VPN_PKG='$safePkg'
            matched=0
            vman=${d}(dumpsys vpn_management 2>/dev/null)
            if [ -n "${d}vman" ]; then
                if echo "${d}vman" | grep -q "${d}VPN_PKG"; then
                    matched=1
                elif ! echo "${d}vman" | grep -qE 'mPackage=|Package:'; then
                    dumpsys activity services "${d}VPN_PKG" 2>/dev/null | grep -qE 'isForeground=true|VpnService|TunnelVpn' && matched=1
                fi
            else
                if dumpsys connectivity 2>/dev/null | grep -iE "vpn|${d}VPN_PKG" | grep -q "${d}VPN_PKG"; then
                    matched=1
                elif dumpsys activity services "${d}VPN_PKG" 2>/dev/null | grep -qE 'isForeground=true|VpnService|TunnelVpn'; then
                    matched=1
                fi
            fi
            echo "VPN_MATCH:${d}matched"
        """.trimIndent()
        val out = execShell(execMode, cmd)
        return out.lines().any { it.trim() == "VPN_MATCH:1" }
    }

    private fun runAirplaneToggle(cfg: PingConfig): String {
        val apCmd = if (cfg.airplaneDelaySec <= 0.0) {
            "cmd connectivity airplane-mode enable; cmd connectivity airplane-mode disable"
        } else {
            "cmd connectivity airplane-mode enable; sleep ${cfg.airplaneDelaySec}; cmd connectivity airplane-mode disable"
        }
        return execShell(cfg.execMode, apCmd)
    }

    private fun checkConnectionWithFallback(cfg: PingConfig): String? {
        val primary = checkConnectionHard(cfg.target, cfg.timeoutSec, cfg.pingMode)
        if (primary != null) return primary
        if (cfg.fallbackTarget.isNotBlank()) {
            val fb = checkConnectionHard(cfg.fallbackTarget, cfg.timeoutSec, cfg.pingMode)
            if (fb != null) return "$fb*"
        }
        return null
    }

    private fun checkConnectionHard(target: String, timeoutSec: Double, pingMode: String): String? {
        val timeoutMs = (timeoutSec.coerceAtLeast(0.5) * 1000).toLong()
        val hardTimeoutMs = timeoutMs + 250L
        val activeSocket = AtomicReference<Socket?>(null)
        val activeConn = AtomicReference<HttpURLConnection?>(null)

        val future = try {
            pingExecutor.submit<String?> {
                val startNs = System.nanoTime()
                if (pingMode == PingConfig.PING_TCP) {
                    val clean = target.removePrefix("https://").removePrefix("http://").substringBefore("/")
                    val parts = clean.split(":")
                    val host = parts[0].trim()
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 443
                    val sock = Socket()
                    activeSocket.set(sock)
                    try {
                        sock.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                        String.format(Locale.US, "%.2fs", elapsedSec)
                    } finally {
                        try { sock.close() } catch (_: Exception) {}
                        activeSocket.set(null)
                    }
                } else {
                    val urlStr = if (target.startsWith("http://") || target.startsWith("https://")) {
                        target
                    } else {
                        "https://$target"
                    }
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                            requestMethod = "HEAD"
                            connectTimeout = timeoutMs.toInt()
                            readTimeout = timeoutMs.toInt()
                            instanceFollowRedirects = false
                            useCaches = false
                        }
                        activeConn.set(conn)
                        val code = conn.responseCode
                        if (code in 200..399) {
                            val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                            String.format(Locale.US, "%.2fs", elapsedSec)
                        } else {
                            null
                        }
                    } finally {
                        try { conn?.disconnect() } catch (_: Exception) {}
                        activeConn.set(null)
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }

        return try {
            future.get(hardTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            try { activeSocket.getAndSet(null)?.close() } catch (_: Exception) {}
            try { activeConn.getAndSet(null)?.disconnect() } catch (_: Exception) {}
            null
        }
    }

    private fun execShell(execMode: String, command: String): String {
        return if (execMode == PingConfig.MODE_ROOT) {
            rootShell.exec(command, 12000L)
        } else {
            try {
                if (shellService == null) {
                    tryRebindShizukuBlocking()
                }
                shellService?.exec(command) ?: "ERR:SHIZUKU_UNBOUND"
            } catch (e: Exception) {
                shellService = null
                "ERR:${e.message}"
            }
        }
    }

    private fun tryRebindShizukuBlocking() {
        try {
            if (Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                var waited = 0
                while (shellService == null && waited < 15) {
                    Thread.sleep(100)
                    waited++
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun log(msg: String, maxLines: Int) {
        PingStateStore.appendLog(msg, maxLines)
    }

    private fun updateStatus(status: String, forceNotif: Boolean) {
        PingStateStore.setStatus(status)
        val nowMs = SystemClock.elapsedRealtime()
        if (forceNotif || nowMs - lastNotifUpdateMs >= 2000L) {
            lastNotifUpdateMs = nowMs
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(status))
            if (forceNotif) {
                requestTileRefresh()
            }
        }
    }

    private fun requestTileRefresh() {
        try {
            TileService.requestListeningState(this, ComponentName(this, PingTileService::class.java))
        } catch (_: Exception) {
        }
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

        val pauseIntent = Intent(this, PingService::class.java).apply {
            action = ACTION_PAUSE_TOGGLE
        }
        val pausePi = PendingIntent.getService(
            this, 2, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resetIntent = Intent(this, PingService::class.java).apply {
            action = ACTION_MANUAL_AIRPLANE
        }
        val resetPi = PendingIntent.getService(
            this, 3, resetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stats = PingStateStore.stats.value
        val title = if (stats.toggleCount > 0) "PingIP v4.5 • Toggle: ${stats.toggleCount}x" else "PingIP v4.5"
        val pauseLabel = if (manualPaused) "Lanjut" else "Jeda"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, pauseLabel, pausePi)
            .addAction(0, "Reset IP", resetPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }
}
