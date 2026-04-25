package com.shay.backup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import com.shay.backup.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val config by lazy { ConfigStore(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            updateState(running = false)
        } else {
            Toast.makeText(this, R.string.perms_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBackupNow.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            if (!config.isConfigured) {
                Toast.makeText(this, R.string.not_configured, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            BackupWorker.runOnce(this)
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnGrantPerms.setOnClickListener { ensurePermissions() }

        BackupWorker.observeOneShot(this).observe(this) { infos ->
            updateState(running = BackupWorker.isRunning(infos), infos = infos)
        }
    }

    override fun onResume() {
        super.onResume()
        ensurePermissions()
        updateState(running = false)
    }

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!granted(Manifest.permission.READ_MEDIA_IMAGES)) needed += Manifest.permission.READ_MEDIA_IMAGES
            if (!granted(Manifest.permission.READ_MEDIA_VIDEO))  needed += Manifest.permission.READ_MEDIA_VIDEO
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) needed += Manifest.permission.POST_NOTIFICATIONS
        } else {
            if (!granted(Manifest.permission.READ_EXTERNAL_STORAGE)) needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        binding.btnGrantPerms.visibility = if (needed.isNotEmpty()) View.VISIBLE else View.GONE
        if (needed.isEmpty()) return true
        permissionLauncher.launch(needed.toTypedArray())
        return false
    }

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun updateState(running: Boolean, infos: List<WorkInfo>? = null) {
        binding.tvTarget.text =
            if (config.isConfigured)
                getString(R.string.configured_label, hostOf(config.accountUrl), config.container)
            else
                getString(R.string.not_configured)

        binding.tvStatus.text = when {
            running -> getString(R.string.status_running)
            config.lastRunMs > 0 -> {
                val rel = DateUtils.getRelativeTimeSpanString(
                    config.lastRunMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
                if (config.lastResult.startsWith("Failed"))
                    getString(R.string.status_failed, config.lastResult.removePrefix("Failed: "))
                else
                    "${getString(R.string.status_done, rel)}\n${config.lastResult}"
            }
            else -> getString(R.string.status_never)
        }
        binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
        if (running) binding.progressBar.isIndeterminate = true
        binding.btnBackupNow.isEnabled = !running && config.isConfigured
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/')
}
