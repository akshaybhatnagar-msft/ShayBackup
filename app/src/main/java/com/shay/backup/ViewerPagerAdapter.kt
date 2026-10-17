package com.shay.backup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.recyclerview.widget.RecyclerView
import com.shay.backup.databinding.ItemViewerPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One page per item for [ViewerActivity]'s ViewPager2.
 *
 *  - image/*   decoded with ImageDecoder (EXIF-corrected, downsampled to screen size)
 *  - video/*   poster frame + play button; inline VideoView once tapped
 *  - anything  file glyph + hint to use "Open in another app"
 *
 * The list is shared with the activity and never re-submitted, so favorite
 * toggles don't rebind (and re-decode) the page.
 */
class ViewerPagerAdapter(
    private val items: List<BackupItem>,
    private val scope: CoroutineScope,
    private val onSingleTap: () -> Unit,
    private val onDoubleTap: () -> Unit
) : RecyclerView.Adapter<ViewerPagerAdapter.PageVH>() {

    private var playingVH: PageVH? = null

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val b = ItemViewerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageVH(b)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: PageVH) {
        holder.loadJob?.cancel()
        if (playingVH === holder) playingVH = null
        holder.resetVideo()
        super.onViewRecycled(holder)
    }

    /** Stops any inline video. Called on page change, pause, and destroy. */
    fun stopPlayback() {
        playingVH?.resetVideo()
        playingVH = null
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class PageVH(val b: ItemViewerPageBinding) : RecyclerView.ViewHolder(b.root) {
        var loadJob: Job? = null
        private val ctx: Context get() = b.root.context
        private var videoUri: Uri? = null

        private val detector = GestureDetector(b.root.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onSingleTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTap(); return true }
        })

        init {
            // ViewPager2 still intercepts horizontal drags for swiping; we only see taps.
            b.root.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
            b.btnPlay.setOnClickListener { startVideo() }
        }

        fun bind(item: BackupItem) {
            loadJob?.cancel()
            resetVideo()
            videoUri = null
            b.ivImage.setImageDrawable(null)
            b.ivImage.visibility = View.VISIBLE
            b.progress.visibility = View.VISIBLE
            b.hintBox.visibility = View.GONE
            b.btnPlay.visibility = View.GONE

            val mime = item.mimeType.lowercase()
            when {
                mime.startsWith("video/") || item.category == MediaScanner.Category.VIDEOS -> bindVideo(item)
                mime.startsWith("image/") || item.category == MediaScanner.Category.IMAGES -> bindImage(item)
                else -> bindFile()
            }
        }

        private fun bindImage(item: BackupItem) {
            val maxDim = targetDim()
            loadJob = scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { decodeImage(item.uri, maxDim) }.getOrNull()
                        ?: runCatching {
                            ctx.contentResolver.loadThumbnail(item.uri, Size(maxDim, maxDim), null)
                        }.getOrNull()
                }
                b.progress.visibility = View.GONE
                if (bmp != null) {
                    b.ivImage.setImageBitmap(bmp)
                } else {
                    showHint(R.string.viewer_load_failed, R.drawable.ic_image)
                }
            }
        }

        private fun bindVideo(item: BackupItem) {
            videoUri = item.uri
            val maxDim = targetDim()
            loadJob = scope.launch {
                val poster = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.loadThumbnail(item.uri, Size(maxDim, maxDim), null)
                    }.getOrNull()
                }
                b.progress.visibility = View.GONE
                if (poster != null) b.ivImage.setImageBitmap(poster)
                b.btnPlay.visibility = View.VISIBLE
            }
        }

        private fun bindFile() {
            b.progress.visibility = View.GONE
            showHint(R.string.viewer_preview_unavailable, R.drawable.ic_file)
        }

        private fun showHint(textRes: Int, iconRes: Int) {
            b.ivGlyph.setImageResource(iconRes)
            b.tvHint.setText(textRes)
            b.hintBox.visibility = View.VISIBLE
        }

        private fun startVideo() {
            val uri = videoUri ?: return
            stopPlayback()                      // one inline player at a time
            playingVH = this
            b.btnPlay.visibility = View.GONE
            b.progress.visibility = View.VISIBLE
            b.video.visibility = View.VISIBLE
            b.video.setMediaController(MediaController(ctx).apply { setAnchorView(b.video) })
            b.video.setOnPreparedListener { mp ->
                b.progress.visibility = View.GONE
                b.ivImage.visibility = View.INVISIBLE
                mp.start()
            }
            b.video.setOnCompletionListener {
                resetVideo()
                if (playingVH === this) playingVH = null
            }
            b.video.setOnErrorListener { _, _, _ ->
                resetVideo()
                showHint(R.string.viewer_load_failed, R.drawable.ic_video)
                true
            }
            b.video.setVideoURI(uri)
        }

        fun resetVideo() {
            b.video.setOnPreparedListener(null)
            b.video.setOnCompletionListener(null)
            b.video.setOnErrorListener(null)
            b.video.stopPlayback()
            b.video.setMediaController(null)
            b.video.visibility = View.GONE
            b.progress.visibility = View.GONE
            b.ivImage.visibility = View.VISIBLE
            if (videoUri != null) b.btnPlay.visibility = View.VISIBLE
        }

        private fun targetDim(): Int {
            val dm = ctx.resources.displayMetrics
            return maxOf(dm.widthPixels, dm.heightPixels).coerceIn(1080, 2560)
        }

        private fun decodeImage(uri: Uri, maxDim: Int): Bitmap {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > maxDim) {
                    val scale = maxDim.toFloat() / longest
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        }
    }
}
