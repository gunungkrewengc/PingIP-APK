package com.pingip.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.Locale

class MainActivity : Activity(), PingService.UiListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvMeta: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnAssistant: Button
    private lateinit var btnToggle: Button
    private lateinit var btnPause: Button
    private lateinit var btnResetIp: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val ok = grantResult == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (ok) "Izin Shizuku diberikan" else "Izin Shizuku ditolak", Toast.LENGTH_SHORT).show()
    }

    private val statsTicker = object : Runnable {
        override fun run() {
            refreshUi()
            if (PingService.isRunning) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { Shizuku.addRequestPermissionResultListener(shizukuPermListener) } catch (_: Throwable) {}
        if (!PingService.isRunning) {
            PingService.refreshStaticCellIp(this)
        }
        buildLayout()
        requestNotifPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        PingService.setUiListener(this)
        if (!PingService.isRunning) {
            PingService.refreshStaticCellIp(this)
        }
        refreshUi()
        handler.removeCallbacks(statsTicker)
        if (PingService.isRunning) handler.postDelayed(statsTicker, 1000L)
    }

    override fun onStop() {
        super.onStop()
        PingService.setUiListener(null)
        handler.removeCallbacks(statsTicker)
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermListener) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onStateUpdated() {
        runOnUiThread {
            refreshUi()
            handler.removeCallbacks(statsTicker)
            if (PingService.isRunning) handler.postDelayed(statsTicker, 1000L)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(colorHex: String, radiusDp: Int = 10, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun makeBtn(text: String, bgHex: String, textHex: String = "#FFFFFF"): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textHex))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setAllCaps(true)
            background = roundedBg(bgHex, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(42)
        }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1120"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "PingIP v4.7"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badge = TextView(this).apply {
            text = "ULTRA-SLIM"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg("#064E3B", 6)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        header.addView(title)
        header.addView(badge)
        root.addView(header)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1E293B", 12, "#334155")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        tvStatus = TextView(this).apply {
            text = "Status: Berhenti"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        tvStats = TextView(this).apply {
            text = "Aktif: 00:00:00 • Toggle: 0x • IP: -"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        tvMeta = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(tvStatus)
        card.addView(tvStats)
        card.addView(tvMeta)
        root.addView(card)

        btnBattery = makeBtn("NONAKTIFKAN PEMBATASAN BATERAI", "#F59E0B", "#0F172A").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { openBatteryOptimization() }
        }
        root.addView(btnBattery)

        btnAssistant = makeBtn("ATUR PINGIP SEBAGAI ASISTEN DIGITAL", "#38BDF8", "#0F172A").apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { openAssistantSettings() }
        }
        root.addView(btnAssistant)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        btnToggle = makeBtn("MULAI PINGIP", "#10B981", "#052E16").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { onClickStartStop() }
        }
        val btnSettings = makeBtn("ATUR DETIK", "#2563EB").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showSettingsDialog() }
        }
        row1.addView(btnToggle)
        row1.addView(btnSettings)
        root.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        btnPause = makeBtn("JEDA", "#334155").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                if (PingService.isRunning) {
                    startService(Intent(this@MainActivity, PingService::class.java).setAction(PingService.ACTION_TOGGLE_PAUSE))
                }
            }
        }
        btnResetIp = makeBtn("RESET IP", "#475569").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                if (PingService.isRunning) {
                    startService(Intent(this@MainActivity, PingService::class.java).setAction(PingService.ACTION_RESET_IP))
                } else {
                    Toast.makeText(this@MainActivity, "Mulai PingIP terlebih dahulu", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnCopy = makeBtn("SALIN", "#1E293B", "#CBD5E1").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PingIP Log", PingService.getLogsSnapshot()))
                Toast.makeText(this@MainActivity, "Log disalin", Toast.LENGTH_SHORT).show()
            }
        }
        val btnClear = makeBtn("HAPUS", "#1E293B", "#F87171").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            setOnClickListener { PingService.clearLogsInternal() }
        }
        row2.addView(btnPause)
        row2.addView(btnResetIp)
        row2.addView(btnCopy)
        row2.addView(btnClear)
        root.addView(row2)

        val logLabel = TextView(this).apply {
            text = "LOG REAL-TIME"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(6) }
        }
        root.addView(logLabel)

        scrollLog = ScrollView(this).apply {
            background = roundedBg("#0D1117", 10, "#1E293B")
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        tvLog = TextView(this).apply {
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        scrollLog.addView(tvLog)
        root.addView(scrollLog)

        setContentView(root)
    }

    private fun refreshUi() {
        val cfg = PingConfig.load(this)
        val running = PingService.isRunning

        tvStatus.text = "Status: ${PingService.currentStatus}"
        tvStatus.setTextColor(
            when {
                !running -> Color.parseColor("#94A3B8")
                PingService.currentStatus.contains("TIMEOUT") || PingService.currentStatus.contains("ERROR") -> Color.parseColor("#F87171")
                PingService.currentStatus.contains("JEDA") || PingService.currentStatus.contains("Cooldown") || PingService.currentStatus.contains("Menunggu") -> Color.parseColor("#FBBF24")
                else -> Color.parseColor("#34D399")
            }
        )

        val uptime = if (running && PingService.startTimeElapsed > 0L) {
            formatDuration((SystemClock.elapsedRealtime() - PingService.startTimeElapsed) / 1000L)
        } else "00:00:00"
        tvStats.text = "Aktif: $uptime • Toggle: ${PingService.toggleCount}x • IP: ${PingService.currentCellIp}"

        val modeStr = when (cfg.execMode) {
            PingConfig.MODE_SHIZUKU -> "SHIZUKU"
            PingConfig.MODE_ASSISTANT -> "ASISTEN"
            else -> "ROOT"
        }
        val methodStr = when (cfg.pingMethod) {
            PingConfig.PING_HTTP_204 -> "HTTP 204"
            PingConfig.PING_TCP_443 -> "TCP 443"
            else -> "HTTPS HEAD"
        }
        val postStr = if (cfg.postToggleMode == PingConfig.POST_IP_CHANGE) {
            "Tunggu IP (+${cfg.ipStabilizeSec}s)"
        } else {
            "Cooldown ${cfg.cooldownSec}s"
        }
        tvMeta.text = "$modeStr • $methodStr • ${cfg.targetPrimary} • $postStr"

        btnToggle.text = if (running) "STOP PINGIP" else "MULAI PINGIP"
        btnToggle.background = roundedBg(if (running) "#EF4444" else "#10B981", 10)
        btnToggle.setTextColor(Color.parseColor(if (running) "#FFFFFF" else "#052E16"))

        btnPause.text = if (PingService.isManuallyPaused) "LANJUT" else "JEDA"
        btnPause.alpha = if (running) 1.0f else 0.45f
        btnResetIp.alpha = if (running) 1.0f else 0.45f

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        btnBattery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE

        val needAssistantBtn = (cfg.execMode == PingConfig.MODE_ASSISTANT) && !PingAssistantService.isAssistantActive(this)
        btnAssistant.visibility = if (needAssistantBtn) View.VISIBLE else View.GONE

        val logs = PingService.getLogsSnapshot()
        if (tvLog.text.toString() != logs) {
            tvLog.text = logs
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun onClickStartStop() {
        if (PingService.isRunning) {
            startService(Intent(this, PingService::class.java).setAction(PingService.ACTION_STOP))
            return
        }
        val cfg = PingConfig.load(this)
        if (cfg.execMode == PingConfig.MODE_SHIZUKU) {
            try {
                if (!Shizuku.pingBinder()) {
                    Toast.makeText(this, "Layanan Shizuku belum aktif", Toast.LENGTH_SHORT).show()
                } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(1001)
                    return
                }
            } catch (_: Throwable) {
                Toast.makeText(this, "Gagal menghubungi Shizuku", Toast.LENGTH_SHORT).show()
            }
        } else if (cfg.execMode == PingConfig.MODE_ASSISTANT) {
            if (!PingAssistantService.isAssistantActive(this)) {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Asisten Digital Belum Aktif")
                    .setMessage("Untuk mode Non-Root, pilih PingIP sebagai Aplikasi Asisten Digital bawaan di Pengaturan HP.")
                    .setNegativeButton("NANTI", null)
                    .setPositiveButton("BUKA PENGATURAN") { _, _ -> openAssistantSettings() }
                    .show()
                return
            }
        }
        val intent = Intent(this, PingService::class.java).setAction(PingService.ACTION_START)
        startForegroundService(intent)
    }

    private fun showSettingsDialog() {
        val cfg = PingConfig.load(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111827"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        fun addSectionLabel(text: String) {
            box.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(4))
            })
        }

        fun makeEdit(label: String, value: String, hint: String, inputType: Int): EditText {
            box.addView(TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12.5f
                setPadding(0, dp(8), 0, dp(2))
            })
            val et = EditText(this).apply {
                setText(value)
                this.hint = hint
                this.inputType = inputType
                setTextColor(Color.parseColor("#F8FAFC"))
                setHintTextColor(Color.parseColor("#64748B"))
                textSize = 14f
                setSingleLine(true)
            }
            box.addView(et)
            return et
        }

        fun styleRadio(rb: RadioButton): RadioButton = rb.apply {
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 13.5f
        }

        addSectionLabel("Mode Eksekusi")
        val rgMode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbRoot = styleRadio(RadioButton(this).apply { id = View.generateViewId(); text = "Root (1 Sesi su - Tanpa Kedip)" })
        val rbShizuku = styleRadio(RadioButton(this).apply { id = View.generateViewId(); text = "Shizuku (Tanpa Root & Tanpa Kedip)" })
        val rbAssistant = styleRadio(RadioButton(this).apply { id = View.generateViewId(); text = "Non-Root (Asisten Digital)" })
        rgMode.addView(rbRoot)
        rgMode.addView(rbShizuku)
        rgMode.addView(rbAssistant)
        rgMode.check(
            when (cfg.execMode) {
                PingConfig.MODE_SHIZUKU -> rbShizuku.id
                PingConfig.MODE_ASSISTANT -> rbAssistant.id
                else -> rbRoot.id
            }
        )
        box.addView(rgMode)

        addSectionLabel("Setelah Mode Pesawat")
        val rgPost = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbIpChange = styleRadio(RadioButton(this).apply { id = View.generateViewId(); text = "Tunggu IP Baru (Dinamis + Stabilisasi)" })
        val rbCooldown = styleRadio(RadioButton(this).apply { id = View.generateViewId(); text = "Cooldown Tetap (Detik)" })
        rgPost.addView(rbIpChange)
        rgPost.addView(rbCooldown)
        rgPost.check(if (cfg.postToggleMode == PingConfig.POST_COOLDOWN) rbCooldown.id else rbIpChange.id)
        box.addView(rgPost)

        addSectionLabel("Metode Cek Ping (Keep-Alive Aktif)")
        val rgPing = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbHttp204 = styleRadio(RadioButton(this).apply { id = View.generateViewId(); text = "HTTP 204 (Tercepat, ms Kecil, Anti-OK Palsu)" })
        val rbHttps = styleRadio(RadioButton(this).apply { id = View.generateViewId(); text = "HTTPS HEAD (Akurat, Keep-Alive)" })
        val rbTcp = styleRadio(RadioButton(this).apply { id = View.generateViewId(); text = "TCP 443" })
        rgPing.addView(rbHttp204)
        rgPing.addView(rbHttps)
        rgPing.addView(rbTcp)
        rgPing.check(
            when (cfg.pingMethod) {
                PingConfig.PING_HTTP_204 -> rbHttp204.id
                PingConfig.PING_TCP_443 -> rbTcp.id
                else -> rbHttps.id
            }
        )
        box.addView(rgPing)

        val cbWifi = CheckBox(this).apply {
            text = "Auto-Jeda saat terhubung ke Wi-Fi"
            isChecked = cfg.pauseOnWifi
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 13.5f
            setPadding(0, dp(8), 0, dp(4))
        }
        val cbCompact = CheckBox(this).apply {
            text = "Mode Log Ringkas (hanya catat RTO/IP/Toggle)"
            isChecked = cfg.compactLog
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 13.5f
            setPadding(0, dp(4), 0, dp(4))
        }
        box.addView(cbWifi)
        box.addView(cbCompact)

        val numDec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val numInt = InputType.TYPE_CLASS_NUMBER
        val txt = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        val etAirplane = makeEdit("Jeda ON-OFF Mode Pesawat (detik)", cfg.airplaneDelaySec.toString(), "0.0", numDec)
        val etIpStab = makeEdit("Jeda Stabilisasi VPN setelah IP Baru (detik, min 0.0)", cfg.ipStabilizeSec.toString(), "1.5", numDec)
        val etIpMax = makeEdit("Batas Tunggu IP Baru Maksimal (detik)", cfg.ipWaitMaxSec.toString(), "15.0", numDec)
        val etCooldown = makeEdit("Cooldown Tetap setelah Mode Pesawat (detik)", cfg.cooldownSec.toString(), "8.0", numDec)
        val etInterval = makeEdit("Interval Cek Ping (detik)", cfg.intervalSec.toString(), "1.0", numDec)
        val etTimeout = makeEdit("Timeout Maksimal Ping (detik)", cfg.timeoutSec.toString(), "1.0", numDec)
        val etFail = makeEdit("Batas Gagal RTO (kali)", cfg.failThreshold.toString(), "2", numInt)
        val etAntiLimit = makeEdit("Anti-Loop: Batas Toggle Beruntun Tanpa OK (0 = nonaktif)", cfg.antiLoopLimit.toString(), "10", numInt)
        val etAntiCd = makeEdit("Anti-Loop: Cooldown Saat Melambat (detik)", cfg.antiLoopCooldownSec.toString(), "30.0", numDec)
        val etWatcher = makeEdit("Kecepatan Watcher Native (detik, min 0.2)", cfg.watcherSec.toString(), "0.5", numDec)
        val etShizukuRetry = makeEdit("Coba Ulang Shizuku/Asisten di Awal (detik, min 0.5)", cfg.shizukuRetrySec.toString(), "2.0", numDec)
        val etMaxLog = makeEdit("Batas Baris Log Layar", cfg.maxLogLines.toString(), "100", numInt)
        val etPrimary = makeEdit("Target Ping Utama", cfg.targetPrimary, "cp.cloudflare.com/generate_204 / 1.1.1.1", txt)
        val etBackup = makeEdit("Target Ping Cadangan (opsional, kosong = nonaktif)", cfg.targetBackup, "contoh: connectivitycheck.gstatic.com/generate_204", txt)
        val etVpnFilter = makeEdit("Filter Paket VPN (Root/Shizuku, kosong = semua VPN)", cfg.vpnPackageFilter, "xyz.easypro.httpcustom", txt)

        rgPing.setOnCheckedChangeListener { _, checkedId ->
            val cur = etPrimary.text.toString().trim()
            if (checkedId == rbHttp204.id) {
                if (cur.isEmpty() || cur.equals("dns.google", true) || cur == "1.1.1.1" || cur == "8.8.8.8") {
                    etPrimary.setText("cp.cloudflare.com/generate_204")
                }
            } else {
                if (cur.contains("generate_204", true) || cur.contains("cp.cloudflare.com", true) || cur.contains("gstatic.com", true)) {
                    etPrimary.setText("1.1.1.1")
                }
            }
        }

        scroll.addView(box)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pengaturan PingIP v4.7")
            .setView(scroll)
            .setNegativeButton("BATAL", null)
            .setPositiveButton("SIMPAN") { _, _ ->
                val chosenMode = when (rgMode.checkedRadioButtonId) {
                    rbShizuku.id -> PingConfig.MODE_SHIZUKU
                    rbAssistant.id -> PingConfig.MODE_ASSISTANT
                    else -> PingConfig.MODE_ROOT
                }
                val newCfg = PingConfig(
                    execMode = chosenMode,
                    postToggleMode = if (rgPost.checkedRadioButtonId == rbCooldown.id) PingConfig.POST_COOLDOWN else PingConfig.POST_IP_CHANGE,
                    pingMethod = when (rgPing.checkedRadioButtonId) {
                        rbHttp204.id -> PingConfig.PING_HTTP_204
                        rbTcp.id -> PingConfig.PING_TCP_443
                        else -> PingConfig.PING_HTTPS_HEAD
                    },
                    pauseOnWifi = cbWifi.isChecked,
                    compactLog = cbCompact.isChecked,
                    airplaneDelaySec = etAirplane.text.toString().toDoubleOrNull() ?: cfg.airplaneDelaySec,
                    ipStabilizeSec = etIpStab.text.toString().toDoubleOrNull() ?: cfg.ipStabilizeSec,
                    ipWaitMaxSec = etIpMax.text.toString().toDoubleOrNull() ?: cfg.ipWaitMaxSec,
                    cooldownSec = etCooldown.text.toString().toDoubleOrNull() ?: cfg.cooldownSec,
                    intervalSec = etInterval.text.toString().toDoubleOrNull() ?: cfg.intervalSec,
                    timeoutSec = etTimeout.text.toString().toDoubleOrNull() ?: cfg.timeoutSec,
                    failThreshold = etFail.text.toString().toIntOrNull() ?: cfg.failThreshold,
                    antiLoopLimit = etAntiLimit.text.toString().toIntOrNull() ?: cfg.antiLoopLimit,
                    antiLoopCooldownSec = etAntiCd.text.toString().toDoubleOrNull() ?: cfg.antiLoopCooldownSec,
                    watcherSec = etWatcher.text.toString().toDoubleOrNull() ?: cfg.watcherSec,
                    shizukuRetrySec = etShizukuRetry.text.toString().toDoubleOrNull() ?: cfg.shizukuRetrySec,
                    maxLogLines = etMaxLog.text.toString().toIntOrNull() ?: cfg.maxLogLines,
                    targetPrimary = etPrimary.text.toString().trim().ifEmpty { "cp.cloudflare.com/generate_204" },
                    targetBackup = etBackup.text.toString().trim(),
                    vpnPackageFilter = etVpnFilter.text.toString().trim()
                )
                PingConfig.save(this, newCfg)
                if (PingService.isRunning) {
                    startService(Intent(this, PingService::class.java).setAction(PingService.ACTION_RELOAD_CONFIG))
                    Toast.makeText(this, "Disimpan (Hot-Reload aktif)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                }
                refreshUi()
                if (chosenMode == PingConfig.MODE_ASSISTANT && !PingAssistantService.isAssistantActive(this)) {
                    openAssistantSettings()
                }
            }
            .show()
    }

    private fun openBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) {}
        }
    }

    private fun openAssistantSettings() {
        Toast.makeText(this, "Pilih 'PingIP' sebagai Aplikasi Asisten Digital", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (_: Throwable) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Throwable) {}
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
