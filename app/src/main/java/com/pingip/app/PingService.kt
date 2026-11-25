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
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PingService : Service() {

    interface UiListener {
        fun onStateUpdated()
    }

    companion object {
        const val ACTION_START = "com.pingip.app.START"
        const val ACTION_STOP = "com.pingip.app.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.pingip.app.TOGGLE_PAUSE"
        const val ACTION_RESET_IP = "com.pingip.app.RESET_IP"
        const val ACTION_RELOAD_CONFIG = "com.pingip.app.RELOAD_CONFIG"
        const val ACTION_CLEAR_LOG = "com.pingip.app.CLEAR_LOG"

        private const val CHANNEL_ID = "pingip_v47_channel"
        private const val NOTIF_ID = 4701
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        @Volatile var isRunning: Boolean = false
        @Volatile var isManuallyPaused: Boolean = false
        @Volatile var currentStatus: String = "Berhenti"
        @Volatile var currentCellIp: String = "-"
        @Volatile var cellLinkGeneration: Int = 0
        @Volatile var startTimeElapsed: Long = 0L
        @Volatile var toggleCount: Int = 0
        @Volatile var lastToggleTime: String = "-"

        private val logLock = Any()
        private val logBuffer = ArrayDeque<String>(128)
        @Volatile private var uiListener: UiListener? = null

        fun setUiListener(listener: UiListener?) {
            uiListener = listener
        }

        fun getLogsSnapshot(): String = synchronized(logLock) {
            if (logBuffer.isEmpty()) "Belum ada log. Tekan MULAI PINGIP."
            else logBuffer.joinToString("\n")
        }

        fun clearLogsInternal() {
            synchronized(logLock) { logBuffer.clear() }
            uiListener?.onStateUpdated()
        }

        @Suppress("DEPRECATION")
        fun refreshStaticCellIp(context: Context) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
                var found: String? = null
                for (net in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) {
                        val ip = extractBestIp(cm.getLinkProperties(net))
                        if (ip != null) found = ip
                    }
                }
                currentCellIp = found ?: "-"
            } catch (_: Throwable) {}
        }

        fun extractBestIp(lp: LinkProperties?): String? {
            if (lp == null) return null
            val addrs = lp.linkAddresses ?: return null
            for (la in addrs) {
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                    return a.hostAddress
                }
            }
            for (la in addrs) {
                val a = la.address
                if (a is Inet6Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                    return a.hostAddress?.substringBefore('%')
                }
            }
            return null
        }
    }

    @Volatile private var config: PingConfig = PingConfig()
    private val runningFlag = AtomicBoolean(false)
    private val manualResetRequested = AtomicBoolean(false)
    private val waitLock = Object()

    private var wakeLock: PowerManager.WakeLock? = null
    private var workerThread: Thread? = null

    private val pingExecutor = ThreadPoolExecutor(
        1, 6, 30L, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "PingIP-Net").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    private val rootShell = PersistentRootShell()
    @Volatile private var shizukuService: IShellService? = null
    @Volatile private var shizukuBound = false
    @Volatile private var shizukuArgs: Shizuku.UserServiceArgs? = null
    @Volatile private var shizukuLatch: CountDownLatch? = null

    private val shizukuConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuService = if (service != null && service.pingBinder()) {
                IShellService.Stub.asInterface(service)
            } else null
            shizukuBound = shizukuService != null
            shizukuLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
            shizukuBound = false
        }
    }

    private var cm: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val activeCaps = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val activeLinks = ConcurrentHashMap<Network, LinkProperties>()
    @Volatile private var isWifiActive = false
    @Volatile private var isVpnActive = false
    @Volatile private var vpnFilterChecked = false
    @Volatile private var vpnFilterMatched = true

    private var lastNotifText = ""
    private var lastNotifTimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        try {
            System.setProperty("http.keepAlive", "true")
            System.setProperty("http.maxConnections", "4")
        } catch (_: Throwable) {}
        createNotificationChannel()
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        registerNetWatcher()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllAndFinish()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                if (isRunning) {
                    isManuallyPaused = !isManuallyPaused
                    if (isManuallyPaused) {
                        addLog("JEDA - Dijeda manual oleh pengguna")
                        setStatus("JEDA - Manual", forceNotif = true)
                    } else {
                        addLog("LANJUT - Dilanjutkan manual")
                        setStatus("Aktif - Melanjutkan...", forceNotif = true)
                    }
                    wakeWorker()
                }
                return START_STICKY
            }
            ACTION_RESET_IP -> {
                if (isRunning) {
                    manualResetRequested.set(true)
                    wakeWorker()
                }
                return START_STICKY
            }
            ACTION_CLEAR_LOG -> {
                clearLogsInternal()
                return START_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                val oldCfg = config
                val newCfg = PingConfig.load(this)
                config = newCfg
                trimLogs(newCfg.maxLogLines)
                if (isRunning) {
                    if (oldCfg.execMode != newCfg.execMode) {
                        addLog("CONFIG: Ganti mode -> ${modeDisplayName(newCfg.execMode)}")
                        if (oldCfg.execMode == PingConfig.MODE_ROOT) rootShell.close()
                        if (oldCfg.execMode == PingConfig.MODE_SHIZUKU) unbindShizuku()
                    }
                    if (oldCfg.vpnPackageFilter != newCfg.vpnPackageFilter) {
                        vpnFilterChecked = false
                    }
                    val postDesc = if (newCfg.postToggleMode == PingConfig.POST_IP_CHANGE) {
                        "Tunggu IP (+${newCfg.ipStabilizeSec}s)"
                    } else {
                        "Cooldown ${newCfg.cooldownSec}s"
                    }
                    addLog("CONFIG: Hot-Reload (${methodDisplayName(newCfg.pingMethod)} | $postDesc)")
                    wakeWorker()
                    notifyUi()
                }
                return START_STICKY
            }
            else -> {
                config = PingConfig.load(this)
                if (!isRunning) {
                    startMonitoring()
                } else {
                    wakeWorker()
                }
                return START_STICKY
            }
        }
    }

    private fun startMonitoring() {
        isRunning = true
        isManuallyPaused = false
        toggleCount = 0
        lastToggleTime = "-"
        startTimeElapsed = SystemClock.elapsedRealtime()
        vpnFilterChecked = false
        runningFlag.set(true)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingIP::MonitorWakeLock").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Throwable) {}
        }

        val notif = buildNotification("Memulai PingIP v4.7...")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        PingTileService.requestSync(this)
        addLog("=== PingIP v4.7 Ultra-Slim Dimulai ===")
        addLog("Mode: ${modeDisplayName(config.execMode)} | Ping: ${methodDisplayName(config.pingMethod)} | IP Awal: $currentCellIp")
        setStatus("Menyiapkan layanan...", forceNotif = true)

        workerThread = Thread({ runMonitorLoop() }, "PingIP-Worker").apply {
            isDaemon = true
            start()
        }
    }

    private fun runMonitorLoop() {
        var failCount = 0
        var consecutiveToggles = 0
        var lastPauseReason: String? = null

        while (runningFlag.get()) {
            val cfg = config

            if (manualResetRequested.getAndSet(false)) {
                if (ensureShellReady(cfg)) {
                    val oldIp = currentCellIp
                    val startGen = cellLinkGeneration
                    addLog("[MANUAL] Mode Pesawat (${cfg.airplaneDelaySec}s) | IP Lama: $oldIp")
                    setStatus("Mode Pesawat (Manual)...", forceNotif = true)
                    val ok = executeAirplaneToggle(cfg)
                    failCount = 0
                    consecutiveToggles = 0
                    if (ok) {
                        waitAfterAirplaneToggle(cfg, oldIp, startGen, isAntiLoop = false, consecutiveToggles = 0)
                    }
                } else {
                    addLog("ERROR: Akses ${modeDisplayName(cfg.execMode)} tidak siap untuk Reset IP")
                }
                continue
            }

            if (isManuallyPaused) {
                lastPauseReason = "manual"
                failCount = 0
                setStatus("JEDA - Manual", forceNotif = false)
                interruptibleSleep((cfg.watcherSec * 1000.0).toLong())
                continue
            }

            if (cfg.pauseOnWifi && isWifiActive) {
                if (lastPauseReason != "wifi") {
                    lastPauseReason = "wifi"
                    failCount = 0
                    consecutiveToggles = 0
                    addLog("JEDA - Wi-Fi terhubung")
                    setStatus("JEDA - Wi-Fi aktif", forceNotif = true)
                }
                interruptibleSleep((cfg.watcherSec * 1000.0).toLong())
                continue
            }

            if (!isMobileDataEnabled(cfg)) {
                if (lastPauseReason != "data") {
                    lastPauseReason = "data"
                    failCount = 0
                    consecutiveToggles = 0
                    addLog("JEDA - Data Seluler mati")
                    setStatus("JEDA - Data mati", forceNotif = true)
                }
                interruptibleSleep((cfg.watcherSec * 1000.0).toLong())
                continue
            }

            if (cfg.vpnPackageFilter.isNotBlank()) {
                if (!isVpnActive) {
                    if (lastPauseReason != "vpn_off") {
                        lastPauseReason = "vpn_off"
                        failCount = 0
                        consecutiveToggles = 0
                        addLog("JEDA - VPN belum aktif")
                        setStatus("JEDA - Menunggu VPN", forceNotif = true)
                    }
                    interruptibleSleep((cfg.watcherSec * 1000.0).toLong())
                    continue
                }
                if (cfg.execMode == PingConfig.MODE_ASSISTANT) {
                    vpnFilterMatched = true
                    vpnFilterChecked = true
                } else if (!vpnFilterChecked) {
                    if (ensureShellReady(cfg)) {
                        vpnFilterMatched = checkVpnPackageMatches(cfg)
                        vpnFilterChecked = true
                    } else {
                        setStatus("Menunggu akses ${modeDisplayName(cfg.execMode)}...", forceNotif = true)
                        interruptibleSleep((cfg.shizukuRetrySec * 1000.0).toLong())
                        continue
                    }
                }
                if (!vpnFilterMatched) {
                    if (lastPauseReason != "vpn_mismatch") {
                        lastPauseReason = "vpn_mismatch"
                        failCount = 0
                        consecutiveToggles = 0
                        addLog("JEDA - VPN aktif bukan ${cfg.vpnPackageFilter}")
                        setStatus("JEDA - Filter VPN tidak cocok", forceNotif = true)
                    }
                    interruptibleSleep((cfg.watcherSec * 1000.0).toLong())
                    continue
                }
            }

            if (!ensureShellReady(cfg)) {
                val msg = when (cfg.execMode) {
                    PingConfig.MODE_SHIZUKU -> "Menunggu Shizuku aktif (${cfg.shizukuRetrySec}s)..."
                    PingConfig.MODE_ASSISTANT -> "PingIP belum dipilih sebagai Asisten Digital..."
                    else -> "Gagal akses Root (su), coba lagi..."
                }
                if (lastPauseReason != "shell") {
                    lastPauseReason = "shell"
                    addLog("WARN - $msg")
                    setStatus(msg, forceNotif = true)
                }
                interruptibleSleep((cfg.shizukuRetrySec * 1000.0).toLong())
                continue
            }

            if (lastPauseReason != null) {
                addLog("LANJUT - Pemantauan aktif (IP: $currentCellIp)")
                lastPauseReason = null
            }

            val timeoutMs = (cfg.timeoutSec * 1000.0).toLong().coerceAtLeast(300L)
            val latency = runPingWithBackup(cfg, timeoutMs)

            if (!runningFlag.get()) break

            if (latency >= 0L) {
                failCount = 0
                consecutiveToggles = 0
                setStatus("Aktif - OK (${latency}ms) • IP: $currentCellIp", forceNotif = false)
                if (!cfg.compactLog) {
                    addLog("OK (${latency}ms)")
                }
                interruptibleSleep((cfg.intervalSec * 1000.0).toLong())
            } else {
                failCount++
                addLog("TIMEOUT ($failCount/${cfg.failThreshold})")
                setStatus("TIMEOUT ($failCount/${cfg.failThreshold})", forceNotif = true)

                if (failCount >= cfg.failThreshold) {
                    val stillEligible = !isManuallyPaused &&
                        !(cfg.pauseOnWifi && isWifiActive) &&
                        isMobileDataEnabledNativeOnly() != false &&
                        (cfg.vpnPackageFilter.isBlank() || (isVpnActive && vpnFilterMatched))

                    if (!stillEligible) {
                        addLog("BATAL - Status jaringan berubah sebelum Mode Pesawat")
                        failCount = 0
                        continue
                    }

                    val oldIp = currentCellIp
                    val startGen = cellLinkGeneration
                    addLog("Mode Pesawat ON-OFF (${cfg.airplaneDelaySec}s) | IP Lama: $oldIp")
                    setStatus("Mode Pesawat (${cfg.airplaneDelaySec}s)...", forceNotif = true)
                    val ok = executeAirplaneToggle(cfg)
                    failCount = 0
                    consecutiveToggles++

                    if (ok) {
                        val useAntiLoop = cfg.antiLoopLimit > 0 && consecutiveToggles >= cfg.antiLoopLimit
                        waitAfterAirplaneToggle(cfg, oldIp, startGen, useAntiLoop, consecutiveToggles)
                    } else {
                        interruptibleSleep(1500L)
                    }
                } else {
                    interruptibleSleep((cfg.intervalSec * 1000.0).toLong())
                }
            }
        }
    }

    private fun waitAfterAirplaneToggle(
        cfg: PingConfig,
        oldIp: String,
        startGen: Int,
        isAntiLoop: Boolean,
        consecutiveToggles: Int
    ) {
        if (isAntiLoop) {
            val cdSec = cfg.antiLoopCooldownSec
            addLog("ANTI-LOOP (${consecutiveToggles}x tanpa OK) -> Cooldown ${cdSec}s")
            waitCooldown(cdSec, "Anti-Loop (${cdSec}s)")
            return
        }

        if (cfg.postToggleMode == PingConfig.POST_IP_CHANGE) {
            waitForNewIpAndStabilize(cfg, oldIp, startGen)
        } else {
            waitCooldown(cfg.cooldownSec, "Cooldown (${cfg.cooldownSec}s)")
        }
    }

    private fun waitForNewIpAndStabilize(cfg: PingConfig, oldIp: String, startGen: Int) {
        val maxWaitMs = (cfg.ipWaitMaxSec * 1000.0).toLong().coerceAtLeast(2000L)
        val tStart = SystemClock.elapsedRealtime()
        val deadline = tStart + maxWaitMs
        setStatus("Menunggu IP baru...", forceNotif = true)

        var detected = false
        while (runningFlag.get()) {
            if (isManuallyPaused || manualResetRequested.get()) return
            if (cfg.pauseOnWifi && isWifiActive) return
            if (isMobileDataEnabledNativeOnly() == false) return

            val now = SystemClock.elapsedRealtime()
            val curIp = currentCellIp
            val ipChanged = (curIp != "-" && curIp != oldIp)
            val linkReconnected = (curIp != "-" && cellLinkGeneration > startGen && (now - tStart) >= 350L)

            if (ipChanged || linkReconnected) {
                detected = true
                val secStr = String.format(Locale.US, "%.1f", (now - tStart) / 1000.0)
                if (curIp != oldIp && oldIp != "-") {
                    addLog("IP Baru: $oldIp -> $curIp (${secStr}s)")
                } else {
                    addLog("IP Aktif: $curIp (${secStr}s)")
                }
                break
            }

            val rem = deadline - now
            if (rem <= 0L) break
            interruptibleSleep(rem.coerceAtMost(200L))
        }

        if (!runningFlag.get() || isManuallyPaused || manualResetRequested.get()) return
        if (cfg.pauseOnWifi && isWifiActive) return
        if (isMobileDataEnabledNativeOnly() == false) return

        if (!detected) {
            addLog("WARN - Batas tunggu IP (${cfg.ipWaitMaxSec}s) habis, lanjut cek...")
            return
        }

        if (cfg.ipStabilizeSec > 0.001) {
            waitCooldown(cfg.ipStabilizeSec, "Stabilisasi VPN (${cfg.ipStabilizeSec}s)...")
        }
    }

    private fun waitCooldown(seconds: Double, label: String) {
        val totalMs = (seconds * 1000.0).toLong().coerceAtLeast(200L)
        val endAt = SystemClock.elapsedRealtime() + totalMs
        setStatus(label, forceNotif = true)
        while (runningFlag.get()) {
            if (isManuallyPaused || manualResetRequested.get()) return
            val cfg = config
            if (cfg.pauseOnWifi && isWifiActive) return
            if (isMobileDataEnabledNativeOnly() == false) return
            val rem = endAt - SystemClock.elapsedRealtime()
            if (rem <= 0L) return
            interruptibleSleep(rem.coerceAtMost(200L))
        }
    }

    private fun executeAirplaneToggle(cfg: PingConfig): Boolean {
        if (cfg.execMode == PingConfig.MODE_ASSISTANT) {
            val svc = PingAssistantService.instance
            if (svc == null) {
                addLog("ERROR - Asisten Digital belum aktif (setel di Pengaturan)")
                return false
            }
            val okOn = svc.triggerAirplaneMode(true)
            if (!okOn) {
                addLog("ERROR - Gagal memicu Asisten Mode Pesawat ON")
                return false
            }
            val holdMs = maxOf(350L, (cfg.airplaneDelaySec * 1000.0).toLong())
            interruptibleSleep(holdMs)
            val okOff = svc.triggerAirplaneMode(false)
            if (!okOff) {
                addLog("ERROR - Gagal memicu Asisten Mode Pesawat OFF")
                return false
            }
            toggleCount++
            lastToggleTime = nowStr()
            addLog("Mode Pesawat (Asisten) selesai (Toggle #${toggleCount})")
            notifyUi()
            return true
        }

        val cmd = if (cfg.airplaneDelaySec <= 0.001) {
            "(cmd connectivity airplane-mode enable || settings put global airplane_mode_on 1) >/dev/null 2>&1; " +
                "(cmd connectivity airplane-mode disable || settings put global airplane_mode_on 0) >/dev/null 2>&1; echo DONE"
        } else {
            val secStr = String.format(Locale.US, "%.2f", cfg.airplaneDelaySec)
            "(cmd connectivity airplane-mode enable || settings put global airplane_mode_on 1) >/dev/null 2>&1; " +
                "sleep $secStr; " +
                "(cmd connectivity airplane-mode disable || settings put global airplane_mode_on 0) >/dev/null 2>&1; echo DONE"
        }
        val out = execShell(cfg, cmd, 12_000L)
        return if (out != null && out.contains("DONE")) {
            toggleCount++
            lastToggleTime = nowStr()
            addLog("Mode Pesawat selesai (Toggle #${toggleCount})")
            notifyUi()
            true
        } else {
            addLog("ERROR - Gagal eksekusi Mode Pesawat (${out ?: "null"})")
            false
        }
    }

    private fun runPingWithBackup(cfg: PingConfig, timeoutMs: Long): Long {
        val primary = pingSingleWithHardTimeout(cfg.targetPrimary, cfg.pingMethod, timeoutMs)
        if (primary >= 0L) return primary
        if (cfg.targetBackup.isNotBlank() && runningFlag.get()) {
            return pingSingleWithHardTimeout(cfg.targetBackup, cfg.pingMethod, timeoutMs)
        }
        return -1L
    }

    private fun pingSingleWithHardTimeout(rawTarget: String, method: String, timeoutMs: Long): Long {
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val sockRef = AtomicReference<Socket?>(null)
        val future = try {
            pingExecutor.submit<Long> {
                val t0 = SystemClock.elapsedRealtime()
                when (method) {
                    PingConfig.PING_HTTP_204 -> doHttp204(rawTarget, timeoutMs.toInt(), connRef, t0)
                    PingConfig.PING_TCP_443 -> doTcpConnect(rawTarget, timeoutMs.toInt(), sockRef, t0)
                    else -> doHttpsHead(rawTarget, timeoutMs.toInt(), connRef, t0)
                }
            }
        } catch (_: Throwable) {
            return -1L
        }

        return try {
            future.get(timeoutMs + 80L, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            try { connRef.get()?.disconnect() } catch (_: Throwable) {}
            try { sockRef.get()?.close() } catch (_: Throwable) {}
            future.cancel(true)
            -1L
        } catch (_: Throwable) {
            try { connRef.get()?.disconnect() } catch (_: Throwable) {}
            try { sockRef.get()?.close() } catch (_: Throwable) {}
            future.cancel(true)
            -1L
        }
    }

    private fun doHttp204(rawTarget: String, timeoutMs: Int, connRef: AtomicReference<HttpURLConnection?>, t0: Long): Long {
        var clean = rawTarget.trim()
        clean = when {
            clean.startsWith("http://", ignoreCase = true) -> clean
            clean.startsWith("https://", ignoreCase = true) -> "http://" + clean.substring(8)
            clean.contains("/") -> "http://$clean"
            else -> "http://$clean/generate_204"
        }
        return executeKeepAliveHttp(clean, "GET", timeoutMs, connRef, t0) { code -> code == 204 }
    }

    private fun doHttpsHead(rawTarget: String, timeoutMs: Int, connRef: AtomicReference<HttpURLConnection?>, t0: Long): Long {
        val clean = rawTarget.trim().let {
            when {
                it.startsWith("https://", ignoreCase = true) -> it
                it.startsWith("http://", ignoreCase = true) -> "https://" + it.substring(7)
                else -> "https://$it"
            }
        }
        return executeKeepAliveHttp(clean, "HEAD", timeoutMs, connRef, t0) { code -> code in 200..399 }
    }

    private fun executeKeepAliveHttp(
        urlStr: String,
        httpMethod: String,
        timeoutMs: Int,
        connRef: AtomicReference<HttpURLConnection?>,
        t0: Long,
        isValidCode: (Int) -> Boolean
    ): Long {
        val url = try { URL(urlStr) } catch (_: Throwable) { return -1L }
        for (attempt in 0..1) {
            val elapsedBefore = SystemClock.elapsedRealtime() - t0
            val remMs = (timeoutMs - elapsedBefore).toInt()
            if (remMs < 150) return -1L

            var conn: HttpURLConnection? = null
            var keepAliveSafe = false
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = httpMethod
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = remMs
                    readTimeout = remMs
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("Cache-Control", "no-cache")
                }
                connRef.set(conn)
                val code = conn.responseCode
                val stream = try {
                    if (code in 200..399) conn.inputStream else conn.errorStream
                } catch (_: Throwable) { null }
                if (stream != null) {
                    stream.use { s ->
                        val buf = ByteArray(256)
                        while (s.read(buf) != -1) {
                            // Drain response stream for clean Keep-Alive reuse
                        }
                    }
                }
                if (isValidCode(code)) {
                    keepAliveSafe = true
                    return (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
                } else {
                    return -1L
                }
            } catch (_: Throwable) {
                try { conn?.disconnect() } catch (_: Throwable) {}
                val fastFail = (SystemClock.elapsedRealtime() - t0) < (timeoutMs / 2)
                if (attempt == 0 && fastFail && runningFlag.get() && !Thread.currentThread().isInterrupted) {
                    continue
                }
                return -1L
            } finally {
                if (!keepAliveSafe) {
                    try { conn?.disconnect() } catch (_: Throwable) {}
                }
                connRef.set(null)
            }
        }
        return -1L
    }

    private fun doTcpConnect(rawTarget: String, timeoutMs: Int, sockRef: AtomicReference<Socket?>, t0: Long): Long {
        var host = rawTarget.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
        var port = 443
        if (host.contains(":")) {
            val parts = host.split(":")
            host = parts[0]
            port = parts.getOrNull(1)?.toIntOrNull() ?: 443
        }
        val sock = Socket()
        sockRef.set(sock)
        return try {
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
        } catch (_: Throwable) {
            -1L
        } finally {
            try { sock.close() } catch (_: Throwable) {}
            sockRef.set(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerNetWatcher() {
        val c = cm ?: return
        try {
            for (net in c.allNetworks) {
                c.getNetworkCapabilities(net)?.let { activeCaps[net] = it }
                c.getLinkProperties(net)?.let { activeLinks[net] = it }
            }
            recomputeNetworkFlags()
        } catch (_: Throwable) {}

        val req = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    c.getNetworkCapabilities(network)?.let { activeCaps[network] = it }
                    c.getLinkProperties(network)?.let { activeLinks[network] = it }
                } catch (_: Throwable) {}
                recomputeNetworkFlags()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                activeCaps[network] = caps
                if (!activeLinks.containsKey(network)) {
                    try {
                        c.getLinkProperties(network)?.let { activeLinks[network] = it }
                    } catch (_: Throwable) {}
                }
                recomputeNetworkFlags()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                activeLinks[network] = linkProperties
                recomputeNetworkFlags()
            }

            override fun onLost(network: Network) {
                activeCaps.remove(network)
                activeLinks.remove(network)
                recomputeNetworkFlags()
            }
        }
        netCallback = cb
        try {
            c.registerNetworkCallback(req, cb)
        } catch (_: Throwable) {}
    }

    private fun recomputeNetworkFlags() {
        var wifi = false
        var vpn = false
        var foundCellIp: String? = null

        for ((net, caps) in activeCaps) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                val lp = activeLinks[net] ?: try { cm?.getLinkProperties(net) } catch (_: Throwable) { null }
                val ip = extractBestIp(lp)
                if (ip != null) foundCellIp = ip
            }
        }

        val prevWifi = isWifiActive
        val prevVpn = isVpnActive
        val prevCellIp = currentCellIp
        val newCellIp = foundCellIp ?: "-"

        isWifiActive = wifi
        isVpnActive = vpn
        if (!vpn || (!prevVpn && vpn)) {
            vpnFilterChecked = false
        }

        var stateChanged = (prevWifi != wifi || prevVpn != vpn)
        if (newCellIp != prevCellIp) {
            currentCellIp = newCellIp
            if (newCellIp != "-") {
                cellLinkGeneration++
            }
            stateChanged = true
            notifyUi()
        }

        if (stateChanged) {
            wakeWorker()
        }
    }

    private fun isMobileDataEnabledNativeOnly(): Boolean? {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                val subId = SubscriptionManager.getDefaultDataSubscriptionId()
                val activeTm = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    tm.createForSubscriptionId(subId)
                } else tm
                return activeTm.isDataEnabled
            }
        } catch (_: Throwable) {}

        try {
            val v = Settings.Global.getInt(contentResolver, "mobile_data", -1)
            if (v == 0) return false
            if (v == 1) return true
        } catch (_: Throwable) {}

        return null
    }

    private fun isMobileDataEnabled(cfg: PingConfig): Boolean {
        val nativeVal = isMobileDataEnabledNativeOnly()
        if (nativeVal != null) return nativeVal
        if (cfg.execMode == PingConfig.MODE_ASSISTANT) return true
        val out = execShell(cfg, "settings get global mobile_data", 2500L)?.trim()
        return out != "0"
    }

    private fun checkVpnPackageMatches(cfg: PingConfig): Boolean {
        if (cfg.execMode == PingConfig.MODE_ASSISTANT) return true
        val pkgs = cfg.vpnPackageFilter.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (pkgs.isEmpty()) return true
        val patterns = mutableListOf<String>()
        for (pkg in pkgs) {
            patterns.add(pkg.replace(".", "\\."))
            try {
                val uid = packageManager.getApplicationInfo(pkg, 0).uid
                patterns.add("OwnerUid: *${uid}\\b")
                patterns.add("uid[=: ]+${uid}\\b")
            } catch (_: Throwable) {}
        }
        val regex = patterns.joinToString("|")
        val cmd = "if (dumpsys connectivity 2>/dev/null | grep -E 'Transports:.*VPN|TRANSPORT_VPN|VpnConfig|VpnInfo' | grep -qE '$regex') || " +
            "(dumpsys vpn_management 2>/dev/null | grep -qE '$regex'); then echo MATCH; else echo NOMATCH; fi"
        val res = execShell(cfg, cmd, 4000L) ?: return true
        return res.contains("MATCH")
    }

    private fun ensureShellReady(cfg: PingConfig): Boolean {
        return when (cfg.execMode) {
            PingConfig.MODE_ASSISTANT -> PingAssistantService.instance != null
            PingConfig.MODE_SHIZUKU -> {
                val svc = shizukuService
                if (svc != null && svc.asBinder().pingBinder()) true
                else tryRebindShizukuBlocking()
            }
            else -> rootShell.ensureOpen()
        }
    }

    private fun tryRebindShizukuBlocking(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
            unbindShizuku()
            val latch = CountDownLatch(1)
            shizukuLatch = latch
            val args = Shizuku.UserServiceArgs(
                ComponentName(packageName, ShellUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shizuku_shell")
                .debuggable(false)
                .version(48)
            shizukuArgs = args
            Shizuku.bindUserService(args, shizukuConn)
            latch.await(3500L, TimeUnit.MILLISECONDS)
            val svc = shizukuService
            svc != null && svc.asBinder().pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun unbindShizuku() {
        try {
            val args = shizukuArgs
            if (args != null && shizukuBound) {
                Shizuku.unbindUserService(args, shizukuConn, true)
            }
        } catch (_: Throwable) {}
        shizukuService = null
        shizukuBound = false
    }

    private fun execShell(cfg: PingConfig, cmd: String, timeoutMs: Long): String? {
        return when (cfg.execMode) {
            PingConfig.MODE_ASSISTANT -> null
            PingConfig.MODE_SHIZUKU -> {
                if (!ensureShellReady(cfg)) return null
                try {
                    shizukuService?.exec(cmd)
                } catch (_: Throwable) {
                    shizukuService = null
                    if (tryRebindShizukuBlocking()) {
                        try { shizukuService?.exec(cmd) } catch (_: Throwable) { null }
                    } else null
                }
            }
            else -> rootShell.exec(cmd, timeoutMs)
        }
    }

    private fun interruptibleSleep(ms: Long) {
        if (ms <= 0L) return
        synchronized(waitLock) {
            try {
                waitLock.wait(ms)
            } catch (_: InterruptedException) {}
        }
    }

    private fun wakeWorker() {
        synchronized(waitLock) {
            waitLock.notifyAll()
        }
    }

    private fun nowStr(): String = LocalTime.now().format(TIME_FMT)

    private fun modeDisplayName(m: String): String = when (m) {
        PingConfig.MODE_SHIZUKU -> "SHIZUKU"
        PingConfig.MODE_ASSISTANT -> "ASISTEN"
        else -> "ROOT"
    }

    private fun methodDisplayName(m: String): String = when (m) {
        PingConfig.PING_HTTP_204 -> "HTTP 204"
        PingConfig.PING_TCP_443 -> "TCP 443"
        else -> "HTTPS HEAD"
    }

    private fun addLog(msg: String) {
        val line = "[${nowStr()}] $msg"
        synchronized(logLock) {
            logBuffer.addLast(line)
            val limit = config.maxLogLines
            while (logBuffer.size > limit) logBuffer.removeFirst()
        }
        notifyUi()
    }

    private fun trimLogs(limit: Int) {
        synchronized(logLock) {
            while (logBuffer.size > limit) logBuffer.removeFirst()
        }
    }

    private fun setStatus(status: String, forceNotif: Boolean) {
        currentStatus = status
        val now = SystemClock.elapsedRealtime()
        if (forceNotif || (status != lastNotifText && (now - lastNotifTimeMs >= 900L))) {
            lastNotifText = status
            lastNotifTimeMs = now
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(status))
        }
        notifyUi()
    }

    private fun notifyUi() {
        uiListener?.onStateUpdated()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "PingIP Layanan Aktif", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(statusText: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openPi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val pausePi = PendingIntent.getService(this, 2, Intent(this, PingService::class.java).setAction(ACTION_TOGGLE_PAUSE), flags)
        val resetPi = PendingIntent.getService(this, 3, Intent(this, PingService::class.java).setAction(ACTION_RESET_IP), flags)
        val stopPi = PendingIntent.getService(this, 4, Intent(this, PingService::class.java).setAction(ACTION_STOP), flags)

        val pauseLabel = if (isManuallyPaused) "Lanjut" else "Jeda"
        val noIcon: Icon? = null
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("PingIP v4.7 • Toggle: ${toggleCount}x")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(noIcon, pauseLabel, pausePi).build())
            .addAction(Notification.Action.Builder(noIcon, "Reset IP", resetPi).build())
            .addAction(Notification.Action.Builder(noIcon, "Stop", stopPi).build())
            .build()
    }

    private fun stopAllAndFinish() {
        runningFlag.set(false)
        isRunning = false
        isManuallyPaused = false
        currentStatus = "Berhenti"
        wakeWorker()
        addLog("=== PingIP Dihentikan ===")
        PingTileService.requestSync(this)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        runningFlag.set(false)
        isRunning = false
        wakeWorker()
        try {
            netCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        rootShell.close()
        unbindShizuku()
        try { pingExecutor.shutdownNow() } catch (_: Throwable) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        PingTileService.requestSync(this)
        notifyUi()
        super.onDestroy()
    }
}
