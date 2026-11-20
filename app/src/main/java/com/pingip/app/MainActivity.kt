package com.pingip.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_SHIZUKU = 2001
        private const val REQ_NOTIF = 2002
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvShizuku: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var btnToggle: Button
    private lateinit var btnSettings: Button
    private lateinit var btnShizukuPerm: Button
    private lateinit var btnClearLog: Button

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU) {
            refreshShizukuStatus()
            val msg = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                "Izin Shizuku disetujui!"
            } else {
                "Izin Shizuku ditolak."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshShizukuStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshShizukuStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvShizuku = findViewById(R.id.tvShizuku)
        tvSummary = findViewById(R.id.tvSummary)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        btnToggle = findViewById(R.id.btnToggle)
        btnSettings = findViewById(R.id.btnSettings)
        btnShizukuPerm = findViewById(R.id.btnShizukuPerm)
        btnClearLog = findViewById(R.id.btnClearLog)

        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        requestNotificationPermissionIfNeeded()

        btnToggle.setOnClickListener {
            if (PingStateStore.isRunning.value) {
                val stopIntent = Intent(this, PingService::class.java).apply {
                    action = PingService.ACTION_STOP
                }
                startService(stopIntent)
            } else {
                val startIntent = Intent(this, PingService::class.java).apply {
                    action = PingService.ACTION_START
                }
                ContextCompat.startForegroundService(this, startIntent)
            }
        }

        btnSettings.setOnClickListener { showSettingsDialog() }

        btnShizukuPerm.setOnClickListener { requestShizukuPermission() }

        btnClearLog.setOnClickListener { PingStateStore.clearLogs() }

        refreshConfigSummary()
        refreshShizukuStatus()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuStatus()
        refreshConfigSummary()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        collectJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        collectJob = uiScope.launch {
            launch {
                PingStateStore.isRunning.collectLatest { running ->
                    if (running) {
                        btnToggle.text = "STOP PINGIP"
                        btnToggle.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this@MainActivity, R.color.accent_red)
                        )
                    } else {
                        btnToggle.text = "MULAI PINGIP"
                        btnToggle.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this@MainActivity, R.color.accent_teal)
                        )
                    }
                }
            }
            launch {
                PingStateStore.statusText.collectLatest { status ->
                    tvStatus.text = "Status: $status"
                }
            }
            launch {
                PingStateStore.logs.collectLatest { list ->
                    tvLogs.text = if (list.isEmpty()) {
                        "Belum ada log. Pastikan Shizuku aktif & HTTP Custom konek, lalu tekan MULAI PINGIP."
                    } else {
                        list.joinToString("\n")
                    }
                    scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun refreshConfigSummary() {
        val c = PingConfig.load(this)
        val vpnLabel = c.vpnPkg.ifEmpty { "Semua VPN" }
        tvSummary.text =
            "Mode: ${c.execMode.uppercase()} | ON-OFF: ${c.airplaneDelaySec}s | Cooldown: ${c.cooldownSec}s\n" +
                "Ping: ${c.intervalSec}s | Timeout: ${c.timeoutSec}s (${c.maxFail}x) | Watcher: ${c.watcherIntervalSec}s | VPN: $vpnLabel"
    }

    private fun refreshShizukuStatus() {
        val cfg = PingConfig.load(this)
        if (cfg.execMode == PingConfig.MODE_ROOT) {
            tvShizuku.text = "Akses: Mode Root (su)"
            btnShizukuPerm.visibility = View.GONE
            return
        }

        val running = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

        if (!running) {
            tvShizuku.text = "Shizuku: Belum aktif (buka aplikasi Shizuku dulu)"
            btnShizukuPerm.visibility = View.GONE
        } else if (Shizuku.isPreV11()) {
            tvShizuku.text = "Shizuku: Versi terlalu lama"
            btnShizukuPerm.visibility = View.GONE
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            tvShizuku.text = "Shizuku: Aktif & Terhubung"
            btnShizukuPerm.visibility = View.GONE
        } else {
            tvShizuku.text = "Shizuku: Aktif, tapi belum diizinkan"
            btnShizukuPerm.visibility = View.VISIBLE
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQ_SHIZUKU)
            } else {
                Toast.makeText(this, "Hidupkan Shizuku dulu.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal meminta izin Shizuku: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val cfg = PingConfig.load(this)

        val rbShizuku = view.findViewById<RadioButton>(R.id.rbShizuku)
        val rbRoot = view.findViewById<RadioButton>(R.id.rbRoot)
        val etAirplane = view.findViewById<EditText>(R.id.etAirplaneDelay)
        val etCooldown = view.findViewById<EditText>(R.id.etCooldown)
        val etInterval = view.findViewById<EditText>(R.id.etInterval)
        val etWatcher = view.findViewById<EditText>(R.id.etWatcherInterval)
        val etShizukuRetry = view.findViewById<EditText>(R.id.etShizukuRetry)
        val etTimeout = view.findViewById<EditText>(R.id.etTimeout)
        val etMaxFail = view.findViewById<EditText>(R.id.etMaxFail)
        val etMaxLines = view.findViewById<EditText>(R.id.etMaxLines)
        val etTarget = view.findViewById<EditText>(R.id.etTarget)
        val etVpnPkg = view.findViewById<EditText>(R.id.etVpnPkg)

        if (cfg.execMode == PingConfig.MODE_ROOT) rbRoot.isChecked = true else rbShizuku.isChecked = true
        etAirplane.setText(cfg.airplaneDelaySec.toString())
        etCooldown.setText(cfg.cooldownSec.toString())
        etInterval.setText(cfg.intervalSec.toString())
        etWatcher.setText(cfg.watcherIntervalSec.toString())
        etShizukuRetry.setText(cfg.shizukuRetrySec.toString())
        etTimeout.setText(cfg.timeoutSec.toString())
        etMaxFail.setText(cfg.maxFail.toString())
        etMaxLines.setText(cfg.maxLines.toString())
        etTarget.setText(cfg.target)
        etVpnPkg.setText(cfg.vpnPkg)

        AlertDialog.Builder(this)
            .setTitle("Pengaturan PingIP v4.3")
            .setView(view)
            .setPositiveButton("Simpan") { _, _ ->
                val updated = PingConfig(
                    target = etTarget.text.toString().trim().ifEmpty { "dns.google" },
                    intervalSec = (etInterval.text.toString().toDoubleOrNull() ?: cfg.intervalSec).coerceAtLeast(0.0),
                    timeoutSec = (etTimeout.text.toString().toDoubleOrNull() ?: cfg.timeoutSec).coerceAtLeast(0.5),
                    maxFail = (etMaxFail.text.toString().toIntOrNull() ?: cfg.maxFail).coerceAtLeast(1),
                    airplaneDelaySec = (etAirplane.text.toString().toDoubleOrNull() ?: cfg.airplaneDelaySec).coerceAtLeast(0.0),
                    cooldownSec = (etCooldown.text.toString().toDoubleOrNull() ?: cfg.cooldownSec).coerceAtLeast(0.0),
                    watcherIntervalSec = (etWatcher.text.toString().toDoubleOrNull() ?: cfg.watcherIntervalSec).coerceAtLeast(0.2),
                    shizukuRetrySec = (etShizukuRetry.text.toString().toDoubleOrNull() ?: cfg.shizukuRetrySec).coerceAtLeast(0.5),
                    maxLines = (etMaxLines.text.toString().toIntOrNull() ?: cfg.maxLines).coerceAtLeast(20),
                    vpnPkg = etVpnPkg.text.toString().trim(),
                    execMode = if (rbRoot.isChecked) PingConfig.MODE_ROOT else PingConfig.MODE_SHIZUKU
                )
                PingConfig.save(this, updated)
                refreshConfigSummary()
                refreshShizukuStatus()

                if (PingStateStore.isRunning.value) {
                    val stopIntent = Intent(this, PingService::class.java).apply {
                        action = PingService.ACTION_STOP
                    }
                    startService(stopIntent)
                    btnToggle.postDelayed({
                        val startIntent = Intent(this, PingService::class.java).apply {
                            action = PingService.ACTION_START
                        }
                        ContextCompat.startForegroundService(this, startIntent)
                    }, 350)
                    Toast.makeText(this, "Disimpan & PingIP dijalankan ulang!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Pengaturan tersimpan.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
