package com.shop.babysharma

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.util.TypedValue
import android.webkit.WebViewClient.ERROR_CONNECT
import android.webkit.WebViewClient.ERROR_HOST_LOOKUP
import android.webkit.WebViewClient.ERROR_IO
import android.webkit.WebViewClient.ERROR_TIMEOUT
import android.webkit.WebViewClient.ERROR_UNKNOWN

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Loading overlay
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingText: TextView

    // Network monitoring
    private var netCallbackRegistered = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    // Step 1: App URL
    private val APP_URL = "https://www.wattsahead.co/resources/floating-whatsapp-button-free"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Safe-area root (so we can add top/bottom gaps for status/nav bars)
        val root = findViewById<View>(R.id.root)

        // Apply system window insets and a small extra gap (8dp) top/bottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
                        or WindowInsetsCompat.Type.navigationBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )

            val extraPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
            ).toInt() // 8dp extra gap

            v.updatePadding(
                left = bars.left,
                top = bars.top + extraPx,
                right = bars.right,
                bottom = bars.bottom + extraPx
            )

            WindowInsetsCompat.CONSUMED
        }

        web = findViewById(R.id.web)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingText = findViewById(R.id.loadingText)

        // --- WebView settings
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true              // allow responsive viewport
            loadWithOverviewMode = true         // scale to fit screen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            // mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // if needed
        }

        // Adaptive scaling (keeps content readable on tablets & phones)
        applyAdaptiveScaling()

        // --- Cookies
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        }

        // Keep WebView’s network state in sync at startup
        web.setNetworkAvailable(isOnline())

        // --- Handle navigation & special links
        web.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // Show loading overlay for navigation starts (internal loads)
                showLoading()
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Hide overlay when page finished
                hideLoading()
                super.onPageFinished(view, url)
            }

            // Android M (23)+ — with request + error object
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // Only react to main-frame network errors
                if (request.isForMainFrame && isNetworkError(error.errorCode)) {
                    if (!isOnline()) {
                        showOffline()
                    } else {
                        // transient subresource issue: ignore but hide loader
                        hideLoading()
                    }
                } else {
                    // For other errors, just hide loader
                    hideLoading()
                }
            }

            // Legacy (pre-M) — assume main-frame
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (isNetworkError(errorCode) && !isOnline()) {
                    showOffline()
                } else {
                    hideLoading()
                }
            }

            // Don’t take the whole app offline for subresource HTTP errors
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                // Hide loader for http errors
                if (request.isForMainFrame) hideLoading()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()

                if (url.startsWith("app://open-settings")) {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    return true
                }

                if (url.startsWith("tel:")) {
                    // Show a short loading state while launching the dialer (optional)
                    showLoading(message = "Opening dialer…")
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                    hideLoading()
                    return true
                }

                if (url.startsWith("whatsapp://") ||
                    url.contains("wa.me") ||
                    url.contains("api.whatsapp.com")) {
                    // Show small loading state while opening WhatsApp
                    showLoading(message = "Opening WhatsApp…")
                    openWhatsApp(url)
                    hideLoading()
                    return true
                }

                if (url.contains("mail.google.com") ||
                    url.contains("accounts.google.com") ||
                    url.contains("google.com/gsi")) {
                    // external browser for Google auth
                    showLoading(message = "Opening browser…")
                    openInBrowser(url)
                    hideLoading()
                    return true
                }

                // For normal links let WebView handle them; onPageStarted will show loader
                return false
            }

            // For API 21–23 devices that call the deprecated overload
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val fakeReq = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse(url)
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders(): Map<String, String> = emptyMap()
                }
                return shouldOverrideUrlLoading(view, fakeReq)
            }
        }

        // --- File uploads (onShowFileChooser)
        fileChooserLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                filePathCallback?.onReceiveValue(uris)
                filePathCallback = null
            }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallbackParam: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePathCallbackParam

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }
        }

        // First load
        if (isOnline()) {
            web.loadUrl(APP_URL)
        } else {
            showOffline()
        }
    }

    /** Show the loading overlay. Optional message parameter. */
    private fun showLoading(message: String? = null) {
        runOnUiThread {
            loadingText.text = message ?: "Loading…"
            if (loadingOverlay.visibility != View.VISIBLE) loadingOverlay.visibility = View.VISIBLE
        }
    }

    /** Hide the loading overlay. */
    private fun hideLoading() {
        runOnUiThread {
            if (loadingOverlay.visibility != View.GONE) loadingOverlay.visibility = View.GONE
        }
    }

    /**
     * Make the WebView scale/text size adapt to screen width (phones & tablets).
     * Uses:
     *  - screen width in dp to pick a reasonable textZoom
     *  - initialScale percentage so the page isn't tiny on large screens
     */
    private fun applyAdaptiveScaling() {
        val metrics = resources.displayMetrics
        val screenDp = metrics.widthPixels / metrics.density // width in dp

        val textZoom = when {
            screenDp < 320f -> 85
            screenDp < 360f -> 95
            screenDp < 480f -> 100
            screenDp < 720f -> 105
            else -> 110
        }
        web.settings.textZoom = textZoom

        val baselineDp = 360f
        val scalePercent = ((screenDp / baselineDp) * 100f).toInt().coerceIn(50, 200)
        web.setInitialScale(scalePercent)
    }

    private fun isOnline(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showOffline() {
        hideLoading()
        web.loadUrl("file:///android_asset/offline.html")
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { /* ignore */ }
    }

    private fun openWhatsApp(url: String) {
        try {
            val uri = if (url.contains("wa.me")) {
                val phone = Uri.parse(url).pathSegments.joinToString("").filter { it.isDigit() || it == '+' }
                Uri.parse("whatsapp://send?phone=$phone")
            } else {
                Uri.parse(url)
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp")))
            } catch (_: Exception) { /* ignore */ }
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun isNetworkError(code: Int): Boolean {
        return when (code) {
            ERROR_HOST_LOOKUP,
            ERROR_CONNECT,
            ERROR_TIMEOUT,
            ERROR_UNKNOWN,
            ERROR_IO -> true
            else -> false
        }
    }

    override fun onStart() {
        super.onStart()
        if (!netCallbackRegistered) {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    runOnUiThread {
                        web.setNetworkAvailable(true)
                        if (web.url?.startsWith("file:///android_asset/offline.html") == true) {
                            web.loadUrl(APP_URL)
                        }
                    }
                }

                override fun onLost(network: android.net.Network) {
                    runOnUiThread { web.setNetworkAvailable(false) }
                }
            }
            cm.registerNetworkCallback(req, netCallback!!)
            netCallbackRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (netCallbackRegistered) {
            try {
                netCallback?.let { cm.unregisterNetworkCallback(it) }
            } catch (_: Exception) { /* ignore */ }
            netCallback = null
            netCallbackRegistered = false
        }
    }
}
