package com.shay.backup

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tiny Azure Monitor / Application Insights client.
 *
 * No SDK — POSTs envelopes directly to {ingestionEndpoint}v2.1/track. The connection
 * string is baked in at build time from BuildConfig.AI_CONN_STR (GitHub Actions secret).
 * If the string is blank, every method becomes a no-op so local debug builds don't
 * accidentally talk to the cloud.
 *
 * Privacy: only error class/message/stack and aggregated counts are ever sent.
 * Filenames, addresses, and SAS tokens stay on the device.
 */
object AppInsights {

    private var iKey: String = ""
    private var trackUrl: String = ""
    private var enabled: Boolean = false
    private var roleInstance: String = ""
    private var versionName: String = ""

    private val queue = LinkedBlockingQueue<JSONObject>(500)
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun init(context: Context) {
        val conn = BuildConfig.AI_CONN_STR
        if (conn.isBlank()) return

        val parts = conn.split(';')
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }
            .toMap()
        iKey = parts["InstrumentationKey"].orEmpty()
        val ingest = parts["IngestionEndpoint"]?.trimEnd('/').orEmpty()
        if (iKey.isEmpty() || ingest.isEmpty()) return
        trackUrl = "$ingest/v2.1/track"
        roleInstance = stableInstance(context)
        versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) { "" }
        enabled = true

        startWorker()
    }

    fun trackTrace(message: String, props: Map<String, String> = emptyMap(), severity: Int = 1) {
        if (!enabled) return
        offer(buildTraceEnvelope(message, props, severity))
    }

    fun trackException(t: Throwable, props: Map<String, String> = emptyMap()) {
        if (!enabled) return
        offer(buildExceptionEnvelope(t, props))
    }

    /** Best-effort drain on app shutdown / worker exit. */
    fun flushBlocking(timeoutMs: Long = 2_000L) {
        if (!enabled) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun offer(env: JSONObject) {
        if (!queue.offer(env)) {
            // queue full — drop newest to favour older. v0.1 behaviour.
        }
    }

    private fun startWorker() {
        thread(name = "AppInsightsWorker", isDaemon = true) {
            val batch = ArrayList<JSONObject>(20)
            while (true) {
                try {
                    val first = queue.poll(5, TimeUnit.SECONDS) ?: continue
                    batch.clear()
                    batch += first
                    queue.drainTo(batch, 19)
                    runCatching { post(batch) }
                } catch (_: InterruptedException) {
                    return@thread
                } catch (_: Exception) {
                    // never let telemetry crash the app
                }
            }
        }
    }

    private fun post(envelopes: List<JSONObject>) {
        if (envelopes.isEmpty()) return
        val body = JSONArray().apply { envelopes.forEach { put(it) } }.toString().toByteArray()
        val conn = (URL(trackUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            // Drain body to free socket; ignore status.
            try { conn.inputStream.use { it.readBytes() } }
            catch (_: Exception) { try { conn.errorStream?.use { it.readBytes() } } catch (_: Exception) {} }
        } finally {
            conn.disconnect()
        }
    }

    private fun stableInstance(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences("shay_backup_ai", Context.MODE_PRIVATE)
        return prefs.getString("instance_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("instance_id", it).apply()
        }
    }

    private fun envelope(name: String, baseType: String, baseData: JSONObject): JSONObject {
        val tags = JSONObject().apply {
            put("ai.cloud.role", "ShayBackup")
            put("ai.cloud.roleInstance", roleInstance)
            put("ai.application.ver", versionName)
            put("ai.device.osVersion", "Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            put("ai.device.model", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        return JSONObject().apply {
            put("name", "Microsoft.ApplicationInsights.${iKey.replace("-", "")}.$name")
            put("time", isoFmt.format(Date()))
            put("iKey", iKey)
            put("tags", tags)
            put("data", JSONObject().apply {
                put("baseType", baseType)
                put("baseData", baseData)
            })
        }
    }

    private fun buildTraceEnvelope(
        message: String,
        props: Map<String, String>,
        severity: Int
    ): JSONObject {
        val baseData = JSONObject().apply {
            put("ver", 2)
            put("message", message)
            put("severityLevel", severity)
            if (props.isNotEmpty()) put("properties", JSONObject(props as Map<*, *>))
        }
        return envelope("Message", "MessageData", baseData)
    }

    private fun buildExceptionEnvelope(t: Throwable, props: Map<String, String>): JSONObject {
        val parsedStack = JSONArray()
        t.stackTrace.take(50).forEachIndexed { idx, ste ->
            parsedStack.put(JSONObject().apply {
                put("level", idx)
                put("method", "${ste.className}.${ste.methodName}")
                put("assembly", ste.className.substringBeforeLast('.', ""))
                put("fileName", ste.fileName.orEmpty())
                put("line", ste.lineNumber.coerceAtLeast(0))
            })
        }
        val exceptions = JSONArray().put(
            JSONObject().apply {
                put("id", 1)
                put("typeName", t.javaClass.name)
                put("message", t.message.orEmpty().take(2000))
                put("hasFullStack", true)
                put("parsedStack", parsedStack)
            }
        )
        val baseData = JSONObject().apply {
            put("ver", 2)
            put("exceptions", exceptions)
            put("severityLevel", 3)
            if (props.isNotEmpty()) put("properties", JSONObject(props as Map<*, *>))
        }
        return envelope("Exception", "ExceptionData", baseData)
    }
}
