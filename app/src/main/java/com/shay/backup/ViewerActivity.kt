package com.shay.backup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.shay.backup.databinding.ActivityViewerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hands the (possibly thousands-long) visible item list to [ViewerActivity]
 * without squeezing it through an Intent extra. Set right before startActivity.
 * If the process is killed and recreated, the list is empty and the viewer
 * simply finishes back to MainActivity.
 */
object ViewerItems {
    @Volatile var items: List<BackupItem> = emptyList()
}

/**
 * Full-screen swipe viewer for photos, videos, and downloads.
 *
 * The heart in the toolbar (or a double-tap on the page) toggles favorite.
 * Favorites are written straight to [ConfigStore]; MainActivity re-scans on
 * resume, so the grid reflects them when you come back.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private val config by lazy { ConfigStore(this) }
    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    private var items: MutableList<BackupItem> = mutableListOf()
    private lateinit var pagerAdapter: ViewerPagerAdapter
    private var chromeVisible = true

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            pagerAdapter.stopPlayback()
            renderChrome(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        items = ViewerItems.items.toMutableList()
        if (items.isEmpty()) {
            finish()
            return
        }
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.lastIndex)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Keep the chrome clear of the status / navigation bars (we draw edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(binding.topChrome) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
        val bottomBasePad = binding.bottomChrome.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomChrome) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = bottomBasePad + bottom)
            insets
        }

        pagerAdapter = ViewerPagerAdapter(
            items = items,
            scope = lifecycleScope,
            onSingleTap = ::toggleChrome,
            onDoubleTap = { toggleFavorite(fromDoubleTap = true) }
        )
        binding.pager.adapter = pagerAdapter
        binding.pager.offscreenPageLimit = 1
        binding.pager.setCurrentItem(start, false)
        binding.pager.registerOnPageChangeCallback(pageCallback)
        renderChrome(start)
    }

    override fun onPause() {
        super.onPause()
        if (::pagerAdapter.isInitialized) pagerAdapter.stopPlayback()
    }

    override fun onDestroy() {
        if (::pagerAdapter.isInitialized) {
            binding.pager.unregisterOnPageChangeCallback(pageCallback)
            pagerAdapter.stopPlayback()
        }
        super.onDestroy()
    }

    private fun current(): BackupItem? = items.getOrNull(binding.pager.currentItem)

    private fun renderChrome(position: Int) {
        val item = items.getOrNull(position) ?: return
        supportActionBar?.title = getString(R.string.viewer_counter, position + 1, items.size)
        binding.tvTitle.text = item.fileName
        binding.tvMeta.text = StringBuilder()
            .append(Formatter.formatShortFileSize(this, item.size))
            .append(" · ").append(dateFmt.format(Date(item.createdMs)))
            .append(" · ").append(getString(statusLabelRes(item.status)))
            .toString()
        invalidateOptionsMenu()
    }

    private fun statusLabelRes(status: BackupStatus): Int = when (status) {
        BackupStatus.DONE      -> R.string.tab_done
        BackupStatus.PENDING   -> R.string.tab_pending
        BackupStatus.FAILED    -> R.string.tab_failed
        BackupStatus.UPLOADING -> R.string.status_running
    }

    // ── Menu ────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.viewer_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val fav = current()?.isFavorite == true
        menu.findItem(R.id.action_favorite)?.apply {
            setIcon(if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            setTitle(if (fav) R.string.action_remove_favorites else R.string.action_add_favorites)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_favorite -> { toggleFavorite(fromDoubleTap = false); true }
        R.id.action_open_external -> { openExternally(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Favorite ────────────────────────────────────────────────────────────

    private fun toggleFavorite(fromDoubleTap: Boolean) {
        val pos = binding.pager.currentItem
        val item = items.getOrNull(pos) ?: return
        val nowFav = !item.isFavorite
        if (nowFav) config.addFavorites(listOf(item.key)) else config.removeFavorites(listOf(item.key))
        items[pos] = item.copy(isFavorite = nowFav)
        invalidateOptionsMenu()
        if (fromDoubleTap) {
            burstHeart(nowFav)
        } else {
            Toast.makeText(
                this,
                if (nowFav) R.string.favorite_added_one else R.string.favorite_removed_one,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Big heart that pops in the middle of the screen, Instagram-style, on double-tap. */
    private fun burstHeart(filled: Boolean) {
        val v = binding.ivBurst
        v.animate().cancel()
        v.setImageResource(if (filled) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.scaleX = 0.6f
        v.scaleY = 0.6f
        v.animate().alpha(1f).scaleX(1.15f).scaleY(1.15f).setStartDelay(0).setDuration(160)
            .withEndAction {
                v.animate().alpha(0f).scaleX(1f).scaleY(1f).setStartDelay(350).setDuration(250)
                    .withEndAction { v.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    // ── Chrome / immersive ──────────────────────────────────────────────────

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        fade(binding.topChrome, chromeVisible)
        fade(binding.bottomChrome, chromeVisible)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (chromeVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun fade(v: View, show: Boolean) {
        v.animate().cancel()
        if (show) {
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(150).withEndAction(null).start()
        } else {
            v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.GONE }.start()
        }
    }

    // ── Open externally ─────────────────────────────────────────────────────

    private fun openExternally() {
        val item = current() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.viewer_no_app, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                getString(R.string.viewer_open_failed, e.message ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
