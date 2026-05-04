package com.shay.backup

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shay.backup.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val config by lazy { ConfigStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        // Load existing values
        if (config.isConfigured) {
            binding.etSasUrl.setText("${config.accountUrl}/${config.container}${config.sasToken}")
        }
        binding.swImages.isChecked = config.backupImages
        binding.swVideos.isChecked = config.backupVideos
        binding.swDownloads.isChecked = config.backupDownloads
        binding.swSchedule.isChecked = config.schedulingEnabled
        binding.swWifi.isChecked = config.wifiOnly
        binding.swCharging.isChecked = config.onlyWhenCharging
        binding.sliderInterval.value = config.intervalHours.toFloat()
        updateIntervalLabel(config.intervalHours)

        binding.btnSave.setOnClickListener { save() }
        binding.btnTest.setOnClickListener { test() }
        binding.btnRebuildHistory.setOnClickListener { rebuildHistory() }
        binding.btnSaveKey.setOnClickListener { saveAccountKey() }
        binding.btnTestSign.setOnClickListener { testSigning() }
        binding.btnClearKey.setOnClickListener { clearAccountKey() }
        // Show a placeholder so the user knows a key is set without revealing it.
        if (config.accountKey.isNotBlank()) {
            binding.etConnString.setText("AccountKey=•••••••••• (set)")
        }

        binding.swImages.setOnCheckedChangeListener   { _, c -> config.backupImages    = c }
        binding.swVideos.setOnCheckedChangeListener   { _, c -> config.backupVideos    = c }
        binding.swDownloads.setOnCheckedChangeListener{ _, c -> config.backupDownloads = c }
        binding.swSchedule.setOnCheckedChangeListener { _, c ->
            config.schedulingEnabled = c
            if (c) BackupWorker.schedulePeriodic(this, config) else BackupWorker.cancelPeriodic(this)
        }
        binding.swWifi.setOnCheckedChangeListener     { _, c ->
            config.wifiOnly = c
            if (config.schedulingEnabled) BackupWorker.schedulePeriodic(this, config)
        }
        binding.swCharging.setOnCheckedChangeListener { _, c ->
            config.onlyWhenCharging = c
            if (config.schedulingEnabled) BackupWorker.schedulePeriodic(this, config)
        }
        binding.sliderInterval.addOnChangeListener { _, v, _ ->
            val hours = v.toInt().coerceAtLeast(1)
            config.intervalHours = hours
            updateIntervalLabel(hours)
            if (config.schedulingEnabled) BackupWorker.schedulePeriodic(this, config)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun updateIntervalLabel(hours: Int) {
        binding.tvIntervalLabel.text = getString(R.string.schedule_interval, hours)
    }

    /** Returns true if the URL parsed and was saved. */
    private fun save(): Boolean {
        val raw = binding.etSasUrl.text?.toString().orEmpty()
        val parsed = SasUrl.parse(raw)
        if (parsed == null) {
            binding.sasUrlLayout.error = getString(R.string.azure_sas_url_invalid)
            return false
        }
        binding.sasUrlLayout.error = null
        config.accountUrl = parsed.accountUrl
        config.container = parsed.container
        config.sasToken = parsed.sasToken
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        return true
    }

    // ── Sharing auth (account key) ──────────────────────────────────────────

    private fun saveAccountKey() {
        val raw = binding.etConnString.text?.toString().orEmpty()
        if (raw.startsWith("AccountKey=••")) {
            // Placeholder — user didn't change it.
            Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show()
            return
        }
        val parsed = StorageConnectionString.parse(raw)
        if (parsed == null) {
            binding.connStringLayout.error = getString(R.string.conn_string_invalid)
            return
        }
        // Sanity-check the account name matches the existing SAS URL, if set.
        val sasAccount = config.accountName
        if (sasAccount.isNotBlank() && !sasAccount.equals(parsed.accountName, ignoreCase = true)) {
            binding.connStringLayout.error = getString(
                R.string.account_name_mismatch, parsed.accountName, sasAccount
            )
            return
        }
        binding.connStringLayout.error = null
        config.accountKey = parsed.accountKey
        binding.etConnString.setText("AccountKey=•••••••••• (set)")
        Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show()
    }

    private fun clearAccountKey() {
        config.accountKey = ""
        binding.etConnString.setText("")
        Toast.makeText(this, R.string.key_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun testSigning() {
        if (!config.canSignSas) {
            Toast.makeText(this, R.string.test_signing_no_key, Toast.LENGTH_LONG).show()
            return
        }
        binding.btnTestSign.isEnabled = false
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val sas = SasSigner.containerReadSas(
                        accountName = config.accountName,
                        accountKeyBase64 = config.accountKey,
                        containerName = config.container,
                        startMs = now - 5 * 60_000L,
                        expiryMs = now + 5 * 60_000L,
                        includeList = true
                    )
                    val url = URL(
                        "${config.accountUrl}/${config.container}" +
                            "?restype=container&comp=list&" + sas.removePrefix("?")
                    )
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        setRequestProperty("x-ms-version", "2020-12-06")
                    }
                    try { conn.responseCode } finally { conn.disconnect() }
                }
            }
            binding.btnTestSign.isEnabled = true
            outcome.fold(
                onSuccess = { code ->
                    val msg = if (code in 200..299)
                        getString(R.string.test_signing_ok, code)
                    else
                        getString(R.string.test_fail, "HTTP $code")
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.test_fail, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun rebuildHistory() {
        if (!config.isConfigured) {
            Toast.makeText(this, R.string.not_configured, Toast.LENGTH_LONG).show()
            return
        }
        binding.btnRebuildHistory.isEnabled = false
        binding.btnRebuildHistory.text = getString(R.string.rebuild_running)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { BackupEngine.reconcileFromCloud(this@SettingsActivity, config) }
            }
            binding.btnRebuildHistory.isEnabled = true
            binding.btnRebuildHistory.text = getString(R.string.action_rebuild_history)
            outcome.fold(
                onSuccess = { count ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.rebuild_done, count),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.rebuild_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun test() {
        if (!save()) return
        binding.btnTest.isEnabled = false
        binding.btnTest.text = getString(R.string.testing)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { AzureBlobClient.ping(config.accountUrl, config.container, config.sasToken) }
            }
            binding.btnTest.isEnabled = true
            binding.btnTest.text = getString(R.string.test_connection)
            outcome.fold(
                onSuccess = { code ->
                    val msg = if (code in 200..299) getString(R.string.test_ok, code)
                              else getString(R.string.test_fail, "HTTP $code")
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.test_fail, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
}
