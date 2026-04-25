package com.shay.backup

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shay.backup.databinding.ItemBackupBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupItemsAdapter(
    private val context: Context,
    private val scope: CoroutineScope
) : ListAdapter<BackupItem, BackupItemsAdapter.VH>(DIFF) {

    private val thumbCache = LruCache<String, Bitmap>(64)
    private val resolver: ContentResolver = context.contentResolver
    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    inner class VH(val binding: ItemBackupBinding) : RecyclerView.ViewHolder(binding.root) {
        var thumbJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBackupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.tvName.text = item.fileName
        b.tvMeta.text = "${Formatter.formatShortFileSize(context, item.size)} · " +
                dateFmt.format(Date(item.modifiedMs))

        val (label, color) = when (item.status) {
            BackupStatus.DONE      -> "● Backed up"  to R.color.status_done
            BackupStatus.PENDING   -> "● Pending"    to R.color.status_pending
            BackupStatus.FAILED    -> "● Failed"     to R.color.status_failed
            BackupStatus.UPLOADING -> "● Uploading…" to R.color.status_uploading
        }
        b.tvStatus.text = label
        b.tvStatus.setTextColor(ContextCompat.getColor(context, color))

        // Reset thumb state
        holder.thumbJob?.cancel()
        b.ivThumb.setImageDrawable(null)
        b.ivIconOverlay.visibility = View.VISIBLE
        b.ivIconOverlay.setImageResource(iconFor(item.category))
        b.ivThumb.tag = item.key

        if (item.category != MediaScanner.Category.DOWNLOADS) {
            // Try a cached thumbnail first.
            val cached = thumbCache[item.key]
            if (cached != null) {
                b.ivThumb.setImageBitmap(cached)
                b.ivIconOverlay.visibility = View.GONE
            } else {
                holder.thumbJob = scope.launch {
                    val bmp = loadThumb(item.uri)
                    if (b.ivThumb.tag == item.key && bmp != null) {
                        thumbCache.put(item.key, bmp)
                        b.ivThumb.setImageBitmap(bmp)
                        b.ivIconOverlay.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.thumbJob?.cancel()
        super.onViewRecycled(holder)
    }

    private suspend fun loadThumb(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { resolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
    }

    private fun iconFor(category: MediaScanner.Category): Int = when (category) {
        MediaScanner.Category.IMAGES    -> R.drawable.ic_image
        MediaScanner.Category.VIDEOS    -> R.drawable.ic_video
        MediaScanner.Category.DOWNLOADS -> R.drawable.ic_file
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BackupItem>() {
            override fun areItemsTheSame(a: BackupItem, b: BackupItem) = a.key == b.key
            override fun areContentsTheSame(a: BackupItem, b: BackupItem) = a == b
        }
    }
}
