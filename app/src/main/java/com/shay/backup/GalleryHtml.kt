package com.shay.backup

import android.text.format.Formatter
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a single self-contained HTML gallery (no external CSS/JS/fonts).
 * Inherits the design language from the aether-dashboard "Xi Design System".
 */
object GalleryHtml {

    data class Entry(
        val displayName: String,
        val blobName: String,    // path inside the share container
        val sizeBytes: Long,
        val mimeType: String
    )

    fun render(
        context: Context,
        shareId: String,
        entries: List<Entry>,
        readSasQs: String,        // includes leading "?"
        accountUrl: String,
        shareContainer: String,
        expiryMs: Long
    ): String {
        val expiryStr = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(expiryMs))
        val baseUrl = "${accountUrl.trimEnd('/')}/$shareContainer"
        val tiles = entries.joinToString("\n") { e ->
            val href = "$baseUrl/${encodePath(e.blobName)}$readSasQs"
            val size = Formatter.formatShortFileSize(context, e.sizeBytes)
            val safeName = htmlEscape(e.displayName)
            """
            <div class="tile">
              <a href="${htmlEscape(href)}" target="_blank" rel="noopener noreferrer" download>
                <div class="thumb"><img src="${htmlEscape(href)}" alt="$safeName" loading="lazy"></div>
                <div class="meta">
                  <div class="name">$safeName</div>
                  <div class="size">$size</div>
                </div>
              </a>
            </div>
            """.trimIndent()
        }

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
.meta { display: flex; gap: 20px; font-size: 0.75rem; color: var(--soft-brown); }
.meta .item { display: flex; flex-direction: column; align-items: flex-end; }
.meta .label {
  font-size: 0.65rem; text-transform: uppercase;
  letter-spacing: 0.08em; color: var(--muted);
}
.meta .value { font-family: var(--font-mono); font-weight: 600; color: var(--deep-brown); }
.container { max-width: 1320px; margin: 0 auto; padding: 28px; }
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}
.tile {
  background: #FFFFFF;
  border-radius: var(--radius-md);
  box-shadow: 0 2px 8px var(--shadow);
  overflow: hidden;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}
.tile:hover { box-shadow: 0 4px 16px var(--shadow-md); transform: translateY(-2px); }
.tile a { display: block; text-decoration: none; color: inherit; }
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
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.meta-row {
  padding: 12px 14px;
  border-top: 1px solid var(--border-light);
}
.tile .meta {
  /* override since outer .meta lays out children differently */
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
  .header { padding: 0 16px; }
  .brand h1 { font-size: 1rem; }
  .brand .tagline { display: none; }
  .container { padding: 16px; }
  .grid { gap: 10px; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); }
  .tile .name { font-size: 0.78rem; }
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
  <div class="meta">
    <div class="item"><span class="label">Photos</span><span class="value">${entries.size}</span></div>
    <div class="item"><span class="label">Expires</span><span class="value">$expiryStr</span></div>
  </div>
</header>
<main class="container">
  <div class="grid">
$tiles
  </div>
</main>
<footer class="footer">
  Shared via <strong>Shay Backup</strong> · <code>${htmlEscape(shareId)}</code>
</footer>
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
