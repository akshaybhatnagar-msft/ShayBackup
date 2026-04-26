package com.shay.backup

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import com.shay.backup.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val config by lazy { ConfigStore(this) }
    private lateinit var itemsAdapter: BackupItemsAdapter

    private var allItems: List<BackupItem> = emptyList()
    private var currentTab: Tab = Tab.ALL
    private var currentlyUploadingKey: String? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshScan() }
    private val mediaObserver = object : ContentObserver(refreshHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshHandler.removeCallbacks(refreshRunnable)
            refreshHandler.postDelayed(refreshRunnable, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.any { it }) refreshScan()
        else Toast.makeText(this, R.string.perms_required, Toast.LENGTH_LONG).show()
    }

    enum class Tab { ALL, PENDING, DONE, FAILED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        itemsAdapter = BackupItemsAdapter(
            context = this,
            scope = lifecycleScope,
            onItemClick = ::openItem,
            onItemLongClick = ::onItemLongPress
        )
        binding.rvItems.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = itemsAdapter
            setHasFixedSize(true)
        }

        binding.btnBackupNow.setOnClickListener {
            try {
                if (!ensurePermissions()) return@setOnClickListener
                if (!config.isConfigured) {
                    Toast.makeText(this, R.string.not_configured, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return@setOnClickListener
                }
                BackupWorker.runOnce(this)
            } catch (t: Throwable) {
                Toast.makeText(this, "Couldn't start: ${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnGrantPerms.setOnClickListener { ensurePermissions() }

        binding.tabs.check(R.id.tabAll)
        binding.tabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentTab = when (checkedId) {
                R.id.tabPending -> Tab.PENDING
                R.id.tabDone    -> Tab.DONE
                R.id.tabFailed  -> Tab.FAILED
                else            -> Tab.ALL
            }
            applyFilter()
        }

        BackupWorker.observeOneShot(this).observe(this) { infos -> handleWorkInfos(infos) }
    }

    override fun onResume() {
        super.onResume()
        ensurePermissions()
        refreshScan()
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, mediaObserver)
        }
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(mediaObserver)
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (config.backupImages && !granted(Manifest.permission.READ_MEDIA_IMAGES))
                needed += Manifest.permission.READ_MEDIA_IMAGES
            if (config.backupVideos && !granted(Manifest.permission.READ_MEDIA_VIDEO))
                needed += Manifest.permission.READ_MEDIA_VIDEO
            if (!granted(Manifest.permission.POST_NOTIFICATIONS))
                needed += Manifest.permission.POST_NOTIFICATIONS
        } else if (!granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        binding.btnGrantPerms.visibility = if (needed.isNotEmpty()) View.VISIBLE else View.GONE
        if (needed.isEmpty()) return true
        permissionLauncher.launch(needed.toTypedArray())
        return false
    }

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // ── Data load ───────────────────────────────────────────────────────────

    private fun refreshScan() {
        if (!hasReadPermission()) return
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                BackupItemsRepository.collect(this@MainActivity, config, currentlyUploadingKey)
            }
            allItems = items
            renderHeader(items, runningTotal = null, runningDone = null)
            applyFilter()
        }
    }

    private fun hasReadPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun applyFilter() {
        val filtered = when (currentTab) {
            Tab.ALL     -> allItems
            Tab.PENDING -> allItems.filter { it.status == BackupStatus.PENDING || it.status == BackupStatus.UPLOADING }
            Tab.DONE    -> allItems.filter { it.status == BackupStatus.DONE }
            Tab.FAILED  -> allItems.filter { it.status == BackupStatus.FAILED }
        }
        if (filtered.isEmpty()) {
            binding.rvItems.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(when (currentTab) {
                Tab.ALL     -> R.string.empty_all
                Tab.PENDING -> R.string.empty_pending
                Tab.DONE    -> R.string.empty_done
                Tab.FAILED  -> R.string.empty_failed
            })
        } else {
            binding.rvItems.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            itemsAdapter.submitList(filtered)
        }
    }

    private fun renderHeader(items: List<BackupItem>, runningTotal: Int?, runningDone: Int?) {
        val stats = BackupItemsRepository.statsOf(items)
        binding.tvTarget.text = if (config.isConfigured)
            getString(R.string.configured_label, hostOf(config.accountUrl), config.container)
        else getString(R.string.not_configured)

        val running = runningTotal != null && runningDone != null
        binding.tvHeadline.text = if (running) {
            getString(R.string.headline_running, runningDone, runningTotal)
        } else {
            getString(R.string.headline_idle, stats.done, stats.total)
        }
        val maxVal = (if (running) runningTotal else stats.total) ?: 0
        val progVal = (if (running) runningDone else stats.done) ?: 0
        if (running && maxVal == 0) {
            // Total not yet known — show indeterminate spinner.
            binding.progress.isIndeterminate = true
        } else {
            binding.progress.isIndeterminate = false
            binding.progress.max = maxOf(maxVal, 1)
            binding.progress.progress = progVal.coerceIn(0, maxOf(maxVal, 1))
        }

        val bytesDone = Formatter.formatShortFileSize(this, stats.bytesDone)
        val bytesPending = Formatter.formatShortFileSize(this, stats.bytesPending)
        binding.tvStatsBytes.text = if (stats.failed > 0) {
            getString(R.string.stats_bytes, bytesDone, bytesPending) + " · " +
                    getString(R.string.stats_failed_count, stats.failed)
        } else {
            getString(R.string.stats_bytes, bytesDone, bytesPending)
        }

        binding.btnBackupNow.isEnabled = !running && config.isConfigured
    }

    private fun handleWorkInfos(infos: List<WorkInfo>?) {
        val info = infos?.firstOrNull()
        try { handleWorkInfo(info) } catch (t: Throwable) {
            Toast.makeText(this, "UI error: ${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleWorkInfo(info: WorkInfo?) {
        when (info?.state) {
            WorkInfo.State.RUNNING -> {
                val data = info.progress
                val done = data.getInt(BackupWorker.KEY_DONE, 0)
                val total = data.getInt(BackupWorker.KEY_TOTAL, 0)
                val current = data.getString(BackupWorker.KEY_CURRENT).orEmpty()
                currentlyUploadingKey = null  // we don't get the key directly; just the filename
                renderHeader(allItems, total, done)
                if (current.isNotBlank()) {
                    binding.tvCurrent.visibility = View.VISIBLE
                    binding.tvCurrent.text = "↑ $current"
                } else {
                    binding.tvCurrent.visibility = View.GONE
                }
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                binding.tvCurrent.visibility = View.GONE
                refreshScan()
            }
            else -> { /* enqueued/blocked: leave UI alone */ }
        }
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/')

    // ── Open / share ────────────────────────────────────────────────────────

    private fun openItem(item: BackupItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onItemLongPress(item: BackupItem) {
        val labels = mutableListOf<String>().apply {
            add(getString(R.string.action_open))
            if (item.status == BackupStatus.DONE) {
                add(getString(R.string.action_copy_link))
                add(getString(R.string.action_share_link))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    getString(R.string.action_open)       -> openItem(item)
                    getString(R.string.action_copy_link)  -> withShareUrl(item) { copyLink(it) }
                    getString(R.string.action_share_link) -> withShareUrl(item) { shareLink(it, item.fileName) }
                }
            }
            .show()
    }

    private fun withShareUrl(item: BackupItem, action: (String) -> Unit) {
        if (!config.isConfigured) return
        val blobName = BackupEngine.blobName(item.category, item.fileName, item.modifiedMs)
        val url = AzureBlobClient.blobUrl(
            config.accountUrl, config.container, blobName, config.sasToken
        )
        if (config.shareScopeWarned) {
            action(url)
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.share_warning_title)
                .setMessage(R.string.share_warning_body)
                .setPositiveButton(R.string.continue_btn) { _, _ ->
                    config.shareScopeWarned = true
                    action(url)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun copyLink(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Backup link", url))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLink(url: String, fileName: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, getString(R.string.action_share_link)))
    }
}
