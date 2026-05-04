package com.shay.backup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shay.backup.databinding.ActivitySharesBinding
import com.shay.backup.databinding.ItemShareBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SharesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySharesBinding
    private val config by lazy { ConfigStore(this) }
    private lateinit var adapter: SharesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySharesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = SharesAdapter(
            onCopy = ::copyLink,
            onOpen = ::openInBrowser,
            onDelete = ::confirmDelete
        )
        binding.rvShares.layoutManager = LinearLayoutManager(this)
        binding.rvShares.adapter = adapter

        load()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        if (!config.canSignSas) {
            binding.tvSubtitle.text = getString(R.string.share_no_key)
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(R.string.share_no_key)
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.tvSubtitle.text = getString(R.string.loading_galleries)

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val sas = SasSigner.accountSas(
                        accountName = config.accountName,
                        accountKeyBase64 = config.accountKey,
                        permissions = "rl",      // read + list — we only need to enumerate here
                        services = "b",
                        resourceTypes = "sc",    // service + container
                        startMs = now - 5 * 60_000L,
                        expiryMs = now + 10 * 60_000L
                    )
                    AzureAdmin.listShareContainers(config.accountUrl, sas)
                }
            }
            binding.progress.visibility = View.GONE
            outcome.fold(
                onSuccess = { rows ->
                    if (rows.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.tvEmpty.text = getString(R.string.no_galleries)
                        binding.tvSubtitle.text = getString(R.string.galleries_count, 0)
                    } else {
                        binding.tvEmpty.visibility = View.GONE
                        binding.tvSubtitle.text = getString(R.string.galleries_count, rows.size)
                        adapter.submitList(rows.sortedByDescending { it.lastModified ?: "" })
                    }
                },
                onFailure = { e ->
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.text = getString(
                        R.string.galleries_failed, e.message ?: e.javaClass.simpleName
                    )
                }
            )
        }
    }

    private fun mintReadSas(container: String, days: Int = 7): String {
        val now = System.currentTimeMillis()
        return SasSigner.containerReadSas(
            accountName = config.accountName,
            accountKeyBase64 = config.accountKey,
            containerName = container,
            startMs = now - 5 * 60_000L,
            expiryMs = now + days * 24L * 60 * 60 * 1000L,
            includeList = false
        )
    }

    private fun galleryUrlFor(container: String): String =
        "${config.accountUrl.trimEnd('/')}/$container/index.html${mintReadSas(container)}"

    private fun copyLink(row: AzureAdmin.ContainerSummary) {
        val url = galleryUrlFor(row.name)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Gallery link", url))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openInBrowser(row: AzureAdmin.ContainerSummary) {
        val url = galleryUrlFor(row.name)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser to open the gallery", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(row: AzureAdmin.ContainerSummary) {
        val shortId = row.name.removePrefix("share-")
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_gallery_confirm, shortId))
            .setPositiveButton(R.string.delete) { _, _ -> doDelete(row) }
            .setNegativeButton(R.string.cancel_btn, null)
            .show()
    }

    private fun doDelete(row: AzureAdmin.ContainerSummary) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val sas = SasSigner.accountSas(
                        accountName = config.accountName,
                        accountKeyBase64 = config.accountKey,
                        permissions = "d",
                        services = "b",
                        resourceTypes = "c",
                        startMs = now - 5 * 60_000L,
                        expiryMs = now + 5 * 60_000L
                    )
                    AzureAdmin.deleteContainer(config.accountUrl, row.name, sas)
                }
            }
            outcome.fold(
                onSuccess = { code ->
                    if (code in 200..299) {
                        Toast.makeText(this@SharesActivity, R.string.gallery_deleted, Toast.LENGTH_SHORT).show()
                        load()
                    } else {
                        Toast.makeText(
                            this@SharesActivity,
                            getString(R.string.gallery_delete_failed, code),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@SharesActivity,
                        getString(R.string.share_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
}

private class SharesAdapter(
    private val onCopy: (AzureAdmin.ContainerSummary) -> Unit,
    private val onOpen: (AzureAdmin.ContainerSummary) -> Unit,
    private val onDelete: (AzureAdmin.ContainerSummary) -> Unit
) : ListAdapter<AzureAdmin.ContainerSummary, SharesAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemShareBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemShareBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        val b = holder.binding
        b.tvName.text = row.name
        val ctx = holder.itemView.context
        b.tvCreated.text = ctx.getString(R.string.created_label) + " · " + (row.lastModified ?: "—")
        b.btnOpen.setOnClickListener { onOpen(row) }
        b.btnCopy.setOnClickListener { onCopy(row) }
        b.btnDelete.setOnClickListener { onDelete(row) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AzureAdmin.ContainerSummary>() {
            override fun areItemsTheSame(a: AzureAdmin.ContainerSummary, b: AzureAdmin.ContainerSummary) =
                a.name == b.name
            override fun areContentsTheSame(a: AzureAdmin.ContainerSummary, b: AzureAdmin.ContainerSummary) =
                a == b
        }
    }
}
