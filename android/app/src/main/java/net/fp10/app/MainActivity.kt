package net.fp10.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Shows the FP-10 page in a WebView: the live GitHub Pages copy, so page updates need no new APK,
 * or the copy bundled in the APK when offline. The piano is opened natively (PianoLink) and reaches
 * the page through Bridge as a Web MIDI stand-in.
 */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var bridge: Bridge
    private lateinit var link: PianoLink
    private var usingBundled = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recording and playback must not stop when the screen would time out.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        // Keeps confirm() and alert() working, and lets <input type=file> open a score.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                // Android doesn't know the MusicXML types, so filtering by the page's accept list would hide them.
                val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return try {
                    startActivityForResult(Intent.createChooser(pick, "Open a score"), REQ_FILE); true
                } catch (e: Exception) {
                    fileCallback = null; false
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            // The bridge is only for our page: anything else opens in the browser.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host
                if (host == PAGE_HOST || host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !usingBundled) {
                    usingBundled = true
                    view.loadUrl(BUNDLED_URL)
                }
            }
        }
        webView.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bridge = Bridge(this, webView) { connectPiano() }
        link = PianoLink(this, object : PianoLink.Events {
            override fun onMidi(data: ByteArray, offset: Int, count: Int, timestampNs: Long) =
                bridge.forwardMidi(data, offset, count, timestampNs)

            override fun onState(connected: Boolean, name: String?, message: String) =
                bridge.state(connected, name, message)
        })
        bridge.link = link
        webView.addJavascriptInterface(bridge, "FP10Native")
        setContentView(webView)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.loadUrl(PAGE_URL)
    }

    override fun onDestroy() {
        link.release()
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val uri = data?.data
        fileCallback?.onReceiveValue(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private val btPermissions =arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private fun connectPiano() {
        val missing = btPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) link.connect() else requestPermissions(missing.toTypedArray(), REQ_BT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) link.connect()
        else bridge.state(false, null, "Bluetooth permission was refused. USB still works; for Bluetooth, allow \"Nearby devices\" for FP-10 in Android settings.")
    }

    companion object {
        private const val PAGE_HOST = "elyorkhakimov.github.io"
        private const val PAGE_URL = "https://$PAGE_HOST/fp10-tester/"
        private const val BUNDLED_URL = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/index.html"
        private const val REQ_BT = 1
        private const val REQ_FILE = 2
    }
}
