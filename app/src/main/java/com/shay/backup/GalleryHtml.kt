package com.shay.backup

import android.text.format.Formatter
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a single self-contained HTML gallery (no external CSS/JS/fonts).
 * Inherits the design language from the aether-dashboard "Xi Design System".
 *
 * Recipient features:
 *  - Tap a tile → opens / downloads that one item.
 *  - Tap "Select" in the header → enter multi-select mode.
 *      In select mode, tapping a tile toggles its selection. The sticky bottom
 *      bar shows the count and a "Download N" button that triggers each as a
 *      same-origin <a download> click sequentially with a small delay.
 */
object GalleryHtml {

    data class Entry(
        val displayName: String,
        val blobName: String,
        val sizeBytes: Long,
        val mimeType: String
    )

    fun render(
        context: Context,
        shareId: String,
        entries: List<Entry>,
        readSasQs: String,
        accountUrl: String,
        shareContainer: String,
        expiryMs: Long
    ): String {
        val expiryStr = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(expiryMs))
        val baseUrl = "${accountUrl.trimEnd('/')}/$shareContainer"

        val tiles = entries.mapIndexed { idx, e ->
            val href = "$baseUrl/${encodePath(e.blobName)}$readSasQs"
            val size = Formatter.formatShortFileSize(context, e.sizeBytes)
            val safeName = htmlEscape(e.displayName)
            val safeHref = htmlEscape(href)
            val mime = e.mimeType.lowercase()
            val isVideo = mime.startsWith("video/")
            val isImage = mime.startsWith("image/")
            val preview = when {
                isVideo -> {
                    val src = htmlEscape("$href#t=0.1")
                    """<video class="media" src="$src" preload="metadata" muted playsinline></video>
                       <div class="play-badge" aria-hidden="true"><span></span></div>""".trimIndent()
                }
                isImage -> """<img class="media" src="$safeHref" alt="$safeName" loading="lazy">"""
                else    -> """<div class="file-glyph">📄</div>"""
            }
            val tileClass = if (isVideo) "tile is-video" else "tile"
            """
            <div class="$tileClass" data-idx="$idx" data-href="$safeHref" data-name="$safeName">
              <a class="open" href="$safeHref" target="_blank" rel="noopener noreferrer" download title="$safeName">
                <div class="thumb">
                  $preview
                  <div class="check-overlay" aria-hidden="true"></div>
                </div>
                <div class="meta">
                  <div class="name">$safeName</div>
                  <div class="size">$size</div>
                </div>
              </a>
            </div>
            """.trimIndent()
        }.joinToString("\n")

        val countLabel = if (entries.size == 1) "1 photo" else "${entries.size} photos"
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$countLabel · Shay Backup</title>
<style>
:root {
  --primary-700: #2847D6;
  --primary-600: #3B5AE8;
  --primary-100: #E8EDFF;
  --primary-50:  #F5F7FF;
  --warm-white:  #F5F9FF;
  --deep-brown:  #1E3A5F;
  --soft-brown:  #5A6B8A;
  --muted:       #8896B0;
  --border-light:#E2E8F0;
  --shadow:      rgba(31, 54, 199, 0.08);
  --shadow-md:   rgba(31, 54, 199, 0.16);
  --radius-md:   12px;
  --font-body:   'Segoe UI', -apple-system, BlinkMacSystemFont, sans-serif;
  --font-mono:   'JetBrains Mono', 'Cascadia Code', 'Fira Code', Consolas, monospace;
}
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
html { font-size: 14px; scroll-behavior: smooth; }
body {
  font-family: var(--font-body);
  background: linear-gradient(135deg, var(--warm-white) 0%, #FAFCFF 100%);
  color: var(--deep-brown);
  line-height: 1.6;
  min-height: 100vh;
  padding-bottom: 80px;          /* room for sticky action bar */
}
.header {
  position: sticky; top: 0; z-index: 10;
  display: flex; justify-content: space-between; align-items: center;
  padding: 0 28px;
  height: 56px;
  background: #FFFFFF;
  border-bottom: 1px solid var(--border-light);
  box-shadow: 0 1px 4px var(--shadow);
}
.brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
.brand .logo { font-size: 1.3rem; }
.brand h1 {
  font-size: 1.15rem; font-weight: 700;
  color: var(--primary-600);
  letter-spacing: -0.02em;
}
.brand .tagline {
  font-size: 0.75rem; color: var(--muted);
  border-left: 1px solid var(--border-light);
  padding-left: 12px;
}
.header-right { display: flex; align-items: center; gap: 16px; }
.meta { display: flex; gap: 20px; font-size: 0.75rem; color: var(--soft-brown); }
.meta .item { display: flex; flex-direction: column; align-items: flex-end; }
.meta .label {
  font-size: 0.65rem; text-transform: uppercase;
  letter-spacing: 0.08em; color: var(--muted);
}
.meta .value { font-family: var(--font-mono); font-weight: 600; color: var(--deep-brown); }

.btn {
  font-family: var(--font-body);
  font-size: 0.85rem; font-weight: 600;
  padding: 8px 16px;
  border-radius: 10px;
  border: 1px solid var(--border-light);
  background: #FFFFFF;
  color: var(--primary-700);
  cursor: pointer;
  transition: all 0.15s ease;
}
.btn:hover { background: var(--primary-50); border-color: var(--primary-200); }
.btn-primary { background: var(--primary-600); border-color: var(--primary-600); color: #FFFFFF; }
.btn-primary:hover:not(:disabled) { background: var(--primary-700); border-color: var(--primary-700); }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }

body.select-mode .btn-select { display: none; }
body:not(.select-mode) .action-bar { display: none; }

.action-bar {
  position: fixed; bottom: 0; left: 0; right: 0;
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px;
  padding: 12px 24px;
  background: rgba(255, 255, 255, 0.97);
  border-top: 1px solid var(--border-light);
  box-shadow: 0 -4px 16px var(--shadow);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  z-index: 20;
}
.action-bar .count {
  font-family: var(--font-mono);
  font-weight: 700;
  color: var(--deep-brown);
}
.action-bar .actions { display: flex; gap: 8px; }

.container { max-width: 1320px; margin: 0 auto; padding: 28px; }
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}
.tile {
  position: relative;
  background: #FFFFFF;
  border-radius: var(--radius-md);
  box-shadow: 0 2px 8px var(--shadow);
  overflow: hidden;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}
.tile:hover { box-shadow: 0 4px 16px var(--shadow-md); transform: translateY(-2px); }
.tile a.open { display: block; text-decoration: none; color: inherit; }
.thumb {
  width: 100%; aspect-ratio: 1 / 1;
  background: var(--primary-50);
  overflow: hidden;
  position: relative;
}
.thumb::after {
  content: '';
  position: absolute; inset: 0;
  background: linear-gradient(180deg, transparent 70%, rgba(0,0,0,0.04) 100%);
  pointer-events: none;
}
.thumb .media {
  width: 100%; height: 100%;
  object-fit: cover;
  display: block;
  background: #0c1424;
  pointer-events: none;
}
.thumb .file-glyph {
  width: 100%; height: 100%;
  display: flex; align-items: center; justify-content: center;
  font-size: 3rem;
  color: var(--muted);
  background: var(--primary-50);
}
.thumb .play-badge {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  pointer-events: none;
}
.thumb .play-badge span {
  width: 52px; height: 52px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  display: block; position: relative;
  box-shadow: 0 4px 14px rgba(0,0,0,0.35);
  transition: transform 0.2s ease, background 0.2s ease;
}
.thumb .play-badge span::after {
  content: ''; position: absolute;
  top: 50%; left: 54%;
  transform: translate(-50%, -50%);
  width: 0; height: 0;
  border-style: solid;
  border-width: 10px 0 10px 16px;
  border-color: transparent transparent transparent #FFFFFF;
}
.tile.is-video:hover .play-badge span {
  background: rgba(59, 90, 232, 0.85);
  transform: scale(1.06);
}

/* --- Selection state --- */
.thumb .check-overlay {
  position: absolute;
  top: 10px; right: 10px;
  width: 28px; height: 28px;
  border-radius: 50%;
  background: rgba(255,255,255,0.85);
  border: 2px solid rgba(0,0,0,0.18);
  z-index: 2;
  display: none;
  pointer-events: none;
}
body.select-mode .check-overlay { display: block; }
body.select-mode .tile a.open { pointer-events: none; }
body.select-mode .tile { cursor: pointer; }
.tile.selected .check-overlay {
  background: var(--primary-600);
  border-color: var(--primary-600);
}
.tile.selected .check-overlay::after {
  content: '';
  position: absolute;
  top: 6px; left: 9px;
  width: 6px; height: 11px;
  border: solid #FFFFFF;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}
.tile.selected {
  outline: 3px solid var(--primary-600);
  outline-offset: -3px;
}

.tile .meta {
  display: block; padding: 12px 14px; border-top: 1px solid var(--border-light);
}
.tile .name {
  font-size: 0.85rem; font-weight: 600;
  color: var(--deep-brown);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.tile .size {
  font-family: var(--font-mono);
  font-size: 0.7rem;
  color: var(--muted);
  margin-top: 2px;
}

.footer {
  text-align: center;
  padding: 28px 16px 32px;
  font-size: 0.75rem;
  color: var(--muted);
  border-top: 1px solid var(--border-light);
  margin-top: 40px;
  background: #FFFFFF;
}
.footer code {
  font-family: var(--font-mono);
  background: var(--primary-50);
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--primary-700);
}

@media (max-width: 600px) {
  .header { padding: 0 14px; }
  .brand h1 { font-size: 1rem; }
  .brand .tagline { display: none; }
  .meta { gap: 12px; font-size: 0.7rem; }
  .container { padding: 16px; }
  .grid { gap: 10px; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); }
  .tile .name { font-size: 0.78rem; }
  .action-bar { padding: 10px 14px; }
  .btn { padding: 7px 12px; font-size: 0.8rem; }
}
</style>
</head>
<body>
<header class="header">
  <div class="brand">
    <span class="logo">📸</span>
    <h1>Shay Backup</h1>
    <span class="tagline">Shared photos</span>
  </div>
  <div class="header-right">
    <div class="meta">
      <div class="item"><span class="label">Photos</span><span class="value">${entries.size}</span></div>
      <div class="item"><span class="label">Expires</span><span class="value">$expiryStr</span></div>
    </div>
    <button class="btn btn-select" id="btnSelect" type="button">Select</button>
  </div>
</header>
<main class="container">
  <div class="grid">
$tiles
  </div>
</main>

<div class="action-bar" role="region" aria-label="Selection actions">
  <span class="count" id="count">0 selected</span>
  <div class="actions">
    <button class="btn" id="btnSelectAll" type="button">Select all</button>
    <button class="btn" id="btnCancel" type="button">Cancel</button>
    <button class="btn btn-primary" id="btnDownload" type="button" disabled>Download</button>
  </div>
</div>

<footer class="footer">
  Shared via <strong>Shay Backup</strong> · <code>${htmlEscape(shareId)}</code>
</footer>

<script>
(function () {
  const body = document.body;
  const tiles = Array.from(document.querySelectorAll('.tile'));
  const btnSelect = document.getElementById('btnSelect');
  const btnSelectAll = document.getElementById('btnSelectAll');
  const btnCancel = document.getElementById('btnCancel');
  const btnDownload = document.getElementById('btnDownload');
  const count = document.getElementById('count');
  const selected = new Set();

  function refresh() {
    count.textContent = selected.size + ' selected';
    btnDownload.disabled = selected.size === 0;
    btnDownload.textContent = selected.size > 1 ? 'Download (' + selected.size + ')' : 'Download';
  }
  function clearAll() {
    selected.clear();
    tiles.forEach(t => t.classList.remove('selected'));
    refresh();
  }
  function enterMode() { body.classList.add('select-mode'); refresh(); }
  function exitMode() { body.classList.remove('select-mode'); clearAll(); }

  btnSelect.addEventListener('click', enterMode);
  btnCancel.addEventListener('click', exitMode);
  btnSelectAll.addEventListener('click', () => {
    tiles.forEach(t => { selected.add(t.dataset.idx); t.classList.add('selected'); });
    refresh();
  });

  tiles.forEach(t => {
    // Long-press on touch enters select mode
    let pressTimer = null;
    t.addEventListener('touchstart', () => {
      if (body.classList.contains('select-mode')) return;
      pressTimer = setTimeout(() => {
        enterMode();
        toggle(t);
      }, 450);
    }, { passive: true });
    const cancelPress = () => { if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; } };
    t.addEventListener('touchend', cancelPress);
    t.addEventListener('touchmove', cancelPress);

    t.addEventListener('click', e => {
      if (!body.classList.contains('select-mode')) return;
      e.preventDefault();
      e.stopPropagation();
      toggle(t);
    });
  });

  function toggle(t) {
    const id = t.dataset.idx;
    if (selected.has(id)) { selected.delete(id); t.classList.remove('selected'); }
    else { selected.add(id); t.classList.add('selected'); }
    refresh();
  }

  btnDownload.addEventListener('click', async () => {
    btnDownload.disabled = true;
    const snapshot = Array.from(selected);
    for (const id of snapshot) {
      const t = tiles.find(x => x.dataset.idx === id);
      if (!t) continue;
      const a = document.createElement('a');
      a.href = t.dataset.href;
      a.download = t.dataset.name;
      a.rel = 'noopener noreferrer';
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      // Spread out so the browser's download manager doesn't drop ones at the back of the queue.
      await new Promise(r => setTimeout(r, 300));
    }
    btnDownload.disabled = false;
  });
})();
</script>
</body>
</html>
""".trimIndent()
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun encodePath(s: String): String =
        s.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
}
