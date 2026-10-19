package net.fp10.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * `window.FP10Native` in the page. The page uses it as a Web MIDI stand-in (see nativeMIDIAccess()
 * in index.html). Calls arrive on the WebView's JavaBridge thread; replies go back through
 * `window.__fp10` on the main thread. Times are System.nanoTime() nanoseconds.
 */
class Bridge(private val context: Context, private val webView: WebView, private val connect: () -> Unit) {
    lateinit var link: PianoLink
    private val main = Handler(Looper.getMainLooper())
    private val midiBatch = StringBuilder()
    private var midiFlushPosted = false

    private fun js(code: String) {
        main.post { webView.evaluateJavascript("window.__fp10 && window.__fp10.$code", null) }
    }

    // --- Native -> page -----------------------------------------------------------------------
    /** Raw byte chunks (the page frames them), batched per main-thread turn. */
    fun forwardMidi(data: ByteArray, offset: Int, count: Int, timestampNs: Long) {
        if (count <= 0) return
        val bytes = (offset until offset + count).joinToString(",") { (data[it].toInt() and 0xFF).toString() }
        synchronized(midiBatch) {
            if (midiBatch.isNotEmpty()) midiBatch.append(',')
            midiBatch.append("[[").append(bytes).append("],").append(timestampNs).append(']')
            if (midiFlushPosted) return
            midiFlushPosted = true
        }
        main.post {
            val batch = synchronized(midiBatch) {
                midiFlushPosted = false
                midiBatch.toString().also { midiBatch.setLength(0) }
            }
            webView.evaluateJavascript("window.__fp10 && window.__fp10.midi([$batch])", null)
        }
    }

    fun state(connected: Boolean, name: String?, message: String) =
        js("state($connected,${JSONObject.quote(name ?: "")},${JSONObject.quote(message)})")

    // --- Page -> native -----------------------------------------------------------------------
    @JavascriptInterface fun nowNs(): Double = System.nanoTime().toDouble()

    @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME

    /** Connects (or reports the current connection); answers through state(). */
    @JavascriptInterface fun connect() { main.post(connect) }

    @JavascriptInterface
    fun status(): String = JSONObject()
        .put("connected", link.isConnected)
        .put("name", link.connectedName ?: JSONObject.NULL)
        .toString()

    @JavascriptInterface
    fun send(csv: String): Boolean {
        val parts = csv.split(',')
        val bytes = ByteArray(parts.size) { parts[it].trim().toInt().toByte() }
        return link.send(bytes)
    }

    /** Saves a file to Download/FP-10. Returns where it went, or "error: …". */
    @JavascriptInterface
    fun saveFile(name: String, mime: String, base64: String): String = try {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("could not create $safe")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(Base64.decode(base64, Base64.DEFAULT)) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        // MediaStore renames on a clash ("x (1).mid"), so report the name it kept.
        val saved = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: safe
        "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$saved"
    } catch (e: Exception) {
        Log.e("FP10", "saveFile $name", e)
        "error: ${e.message}"
    }

    companion object {
        private const val FOLDER = "FP-10"
    }
}
