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
        // Keeps confirm() and alert() working.
        webView.webChromeClient = WebChromeClient()
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

    private val btPermissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

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
    }
}
