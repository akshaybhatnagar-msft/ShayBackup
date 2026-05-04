package com.shay.backup

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shay.backup.databinding.ItemBackupBinding
import com.shay.backup.databinding.ItemBackupTileBinding
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
    private val scope: CoroutineScope,
    private val onItemClick: (BackupItem) -> Unit = {},
    private val onItemLongClick: (BackupItem) -> Unit = {},
    private val onSelectionChanged: () -> Unit = {}
) : ListAdapter<BackupItem, RecyclerView.ViewHolder>(DIFF) {

    val selectedKeys: LinkedHashSet<String> = LinkedHashSet()
    var selectionMode: Boolean = false
        private set

    fun selectedItems(): List<BackupItem> {
        val byKey = currentList.associateBy { it.key }
        return selectedKeys.mapNotNull { byKey[it] }
    }

    fun startSelectionWith(item: BackupItem) {
        selectionMode = true
        selectedKeys.add(item.key)
        onSelectionChanged()
        @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
    }

    fun toggleSelection(item: BackupItem) {
        if (!selectionMode) return
        if (!selectedKeys.add(item.key)) selectedKeys.remove(item.key)
        if (selectedKeys.isEmpty()) {
            selectionMode = false
        }
        onSelectionChanged()
        notifyItemChanged(currentList.indexOfFirst { it.key == item.key })
        if (!selectionMode) {
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }
    }

    fun clearSelection() {
        if (selectedKeys.isEmpty() && !selectionMode) return
        selectedKeys.clear()
        selectionMode = false
        onSelectionChanged()
        @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
    }

    enum class ViewMode { LIST, TILE }

    var mode: ViewMode = ViewMode.TILE
        set(value) {
            if (field != value) {
                field = value
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }
        }

    private val thumbCache = LruCache<String, Bitmap>(96)
    private val resolver: ContentResolver = context.contentResolver
    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    override fun getItemViewType(position: Int): Int =
        if (mode == ViewMode.LIST) TYPE_LIST else TYPE_TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LIST) {
            ListVH(ItemBackupBinding.inflate(inflater, parent, false))
        } else {
            TileVH(ItemBackupTileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ListVH -> holder.bind(item)
            is TileVH -> holder.bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is ListVH -> holder.thumbJob?.cancel()
            is TileVH -> holder.thumbJob?.cancel()
        }
        super.onViewRecycled(holder)
    }

    // ── List row ────────────────────────────────────────────────────────────

    inner class ListVH(val binding: ItemBackupBinding) : RecyclerView.ViewHolder(binding.root) {
        var thumbJob: Job? = null

        fun bind(item: BackupItem) {
            val b = binding
            b.root.setOnClickListener {
                if (selectionMode) toggleSelection(item) else onItemClick(item)
            }
            b.root.setOnLongClickListener {
                if (!selectionMode) startSelectionWith(item) else toggleSelection(item)
                true
            }
            b.root.alpha = if (selectionMode && selectedKeys.contains(item.key)) 0.55f else 1f
            b.tvName.text = item.fileName
            b.tvMeta.text = "${Formatter.formatShortFileSize(context, item.size)} · " +
                    dateFmt.format(Date(item.modifiedMs))

            val (label, color) = labelAndColorFor(item.status)
            b.tvStatus.text = label
            b.tvStatus.setTextColor(ContextCompat.getColor(context, color))

            thumbJob?.cancel()
            b.ivThumb.setImageDrawable(null)
            b.ivIconOverlay.visibility = View.VISIBLE
            b.ivIconOverlay.setImageResource(iconFor(item.category))
            b.ivThumb.tag = item.key

            if (item.category != MediaScanner.Category.DOWNLOADS) {
                val cached = thumbCache[item.key]
                if (cached != null) {
                    b.ivThumb.setImageBitmap(cached)
                    b.ivIconOverlay.visibility = View.GONE
                } else {
                    thumbJob = scope.launch {
                        val bmp = loadThumb(item.uri, listSize)
                        if (b.ivThumb.tag == item.key && bmp != null) {
                            thumbCache.put(item.key, bmp)
                            b.ivThumb.setImageBitmap(bmp)
                            b.ivIconOverlay.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    // ── Tile ────────────────────────────────────────────────────────────────

    inner class TileVH(val binding: ItemBackupTileBinding) : RecyclerView.ViewHolder(binding.root) {
        var thumbJob: Job? = null

        fun bind(item: BackupItem) {
            val b = binding
            b.root.setOnClickListener {
                if (selectionMode) toggleSelection(item) else onItemClick(item)
            }
            b.root.setOnLongClickListener {
                if (!selectionMode) startSelectionWith(item) else toggleSelection(item)
                true
            }
            val isSelected = selectionMode && selectedKeys.contains(item.key)
            b.root.alpha = if (isSelected) 0.55f else 1f

            // Status dot in corner
            val dotColor = ContextCompat.getColor(context, dotColorFor(item.status))
            (b.statusDot.background as? GradientDrawable)?.setColor(dotColor)
                ?: run { b.statusDot.background?.setColorFilter(dotColor, PorterDuff.Mode.SRC_IN) }

            // Reset
            thumbJob?.cancel()
            b.ivThumb.setImageDrawable(null)
            b.ivIconOverlay.visibility = View.VISIBLE
            b.ivIconOverlay.setImageResource(iconFor(item.category))
            b.ivThumb.tag = item.key

            if (item.category != MediaScanner.Category.DOWNLOADS) {
                val cached = thumbCache[item.key]
                if (cached != null) {
                    b.ivThumb.setImageBitmap(cached)
                    b.ivIconOverlay.visibility = View.GONE
                } else {
                    thumbJob = scope.launch {
                        val bmp = loadThumb(item.uri, tileSize)
                        if (b.ivThumb.tag == item.key && bmp != null) {
                            thumbCache.put(item.key, bmp)
                            b.ivThumb.setImageBitmap(bmp)
                            b.ivIconOverlay.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun loadThumb(uri: Uri, size: Size): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { resolver.loadThumbnail(uri, size, null) }.getOrNull()
    }

    private fun iconFor(category: MediaScanner.Category): Int = when (category) {
        MediaScanner.Category.IMAGES    -> R.drawable.ic_image
        MediaScanner.Category.VIDEOS    -> R.drawable.ic_video
        MediaScanner.Category.DOWNLOADS -> R.drawable.ic_file
    }

    private fun labelAndColorFor(status: BackupStatus): Pair<String, Int> = when (status) {
        BackupStatus.DONE      -> "● Backed up"  to R.color.status_done
        BackupStatus.PENDING   -> "● Pending"    to R.color.status_pending
        BackupStatus.FAILED    -> "● Failed"     to R.color.status_failed
        BackupStatus.UPLOADING -> "● Uploading…" to R.color.status_uploading
    }

    private fun dotColorFor(status: BackupStatus): Int = when (status) {
        BackupStatus.DONE      -> R.color.status_done
        BackupStatus.PENDING   -> R.color.status_pending
        BackupStatus.FAILED    -> R.color.status_failed
        BackupStatus.UPLOADING -> R.color.status_uploading
    }

    companion object {
        private const val TYPE_LIST = 0
        private const val TYPE_TILE = 1

        private val listSize = Size(160, 160)
        private val tileSize = Size(360, 360)

        private val DIFF = object : DiffUtil.ItemCallback<BackupItem>() {
            override fun areItemsTheSame(a: BackupItem, b: BackupItem) = a.key == b.key
            override fun areContentsTheSame(a: BackupItem, b: BackupItem) = a == b
        }
    }
}
