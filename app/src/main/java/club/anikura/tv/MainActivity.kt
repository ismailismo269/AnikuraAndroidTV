package club.anikura.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var searchOverlay: View? = null
    private var searchInput: EditText? = null
    private val homeUrl = "https://anikura.club/"
    private val logTag = "AnikuraTV"

    private var lastBackAtHome = 0L
    private var lastBackHandledAt = 0L

    /** Lets the injected script write to logcat: `adb logcat -s AnikuraTV` */
    private inner class Bridge {
        @JavascriptInterface
        fun log(msg: String) { Log.d(logTag, "js: $msg") }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TEMPORARY: enable Chrome DevTools remote inspection while debugging the WebView.
        WebView.setWebContentsDebuggingEnabled(true)
        enterImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                // A TV remote press is not a "user gesture" for the media policy, so the
                // player could never autostart (or be toggled by media keys) with `true`.
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false
                allowContentAccess = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "AnikuraTvBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    (webView.parent as? FrameLayout)?.addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                    webView.visibility = View.GONE
                    enterImmersiveMode()
                }

                override fun onHideCustomView() {
                    hideCustomView()
                }

                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    val msg: String = "console[" + m.messageLevel().toString() + "] " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")"
                    Log.d(logTag, msg)
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return false
                    val navMsg: String = "nav -> " + url.toString()
                    Log.d(logTag, navMsg)
                    // Keep website navigation inside the app. Non-http(s) schemes are not launched.
                    return url.scheme !in listOf("http", "https")
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    val startMsg: String = "pageStarted " + (url ?: "null")
                    Log.d(logTag, startMsg)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val finishMsg: String = "pageFinished " + (url ?: "null")
                    Log.d(logTag, finishMsg)
                    // NOTE: history is intentionally NOT cleared here (the old clearHistory()
                    // broke the site's own back/close handling).
                    injectTvNavigation()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Toast.makeText(this@MainActivity, "Pagina laden mislukt. Controleer je verbinding en probeer opnieuw.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(rootLayout)
        addSearchShortcut()
        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(homeUrl)
    }

    private fun openSearch() {
        if (searchOverlay != null || !::rootLayout.isInitialized) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(Color.rgb(15, 15, 15))
            isFocusableInTouchMode = true
        }

        val heading = TextView(this).apply {
            text = "Search anime"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

        val input = EditText(this).apply {
            hint = "Search anime..."
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            textSize = 18f
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(35, 35, 35))
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }

        val hint = TextView(this).apply {
            text = "Type to search, then press OK/Search. Back closes search."
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        }

        val results = TextView(this).apply {
            text = "Search results will open on Anikura."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(25, 25, 25))
                setStroke(dp(2), Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
            }
        }

        val close = TextView(this).apply {
            text = "✕  Close search"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(35, 35, 35))
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { closeSearch() }
        }

        val searchButton = TextView(this).apply {
            text = "Search"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(45, 45, 45))
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { submitSearch(input.text.toString()) }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                submitSearch(input.text.toString())
                true
            } else false
        }

        panel.addView(heading, LinearLayout.LayoutParams(-1, dp(42)))
        panel.addView(input, LinearLayout.LayoutParams(-1, dp(54)))
        panel.addView(searchButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        panel.addView(hint, LinearLayout.LayoutParams(-1, -2))
        panel.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(close, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        val frameParams = FrameLayout.LayoutParams(-1, -1)
        panel.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                closeSearch()
                true
            } else false
        }

        rootLayout.addView(panel, frameParams)
        searchOverlay = panel
        searchInput = input
        input.requestFocus()
    }

    private fun submitSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchInput?.error = "Enter an anime title"
            return
        }
        // Use the site's own search UI when possible; never invent a search endpoint.
        val safe = org.json.JSONObject.quote(q)
        val js = """
            (function(q) {
              const inputs = Array.from(document.querySelectorAll('input[type="search"],input[placeholder*="search" i],input[aria-label*="search" i],input[name*="search" i]'));
              const input = inputs.find(el => el.offsetWidth > 0 && el.offsetHeight > 0);
              if (input) {
                input.focus();
                const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                setter.call(input, q);
                input.dispatchEvent(new Event('input', {bubbles:true}));
                input.dispatchEvent(new Event('change', {bubbles:true}));
                const form = input.closest('form');
                if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); return true; }
                const button = input.parentElement && input.parentElement.querySelector('button,[role="button"]');
                if (button) { button.click(); return true; }
                input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));
                return true;
              }
              return false;
            })($safe)
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            if (result != "true") {
                searchInput?.error = "Anikura's search field was not found on this page"
            } else {
                closeSearch()
            }
        }
    }

    private fun closeSearch() {
        val overlay = searchOverlay ?: return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(overlay.windowToken, 0)
        rootLayout.removeView(overlay)
        searchOverlay = null
        searchInput = null
        webView.requestFocus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun addSearchShortcut() {
        if (!::rootLayout.isInitialized || rootLayout.findViewWithTag<View>("anikura-native-search") != null) return
        val button = TextView(this).apply {
            tag = "anikura-native-search"
            text = "⌕  Search"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = "Open anime search"
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 18))
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { openSearch() }
        }
        rootLayout.addView(button, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(14)
            marginEnd = dp(18)
        })
    }

    private fun hideCustomView() {
        val view = customView ?: return
        val parent = webView.parent as? FrameLayout
        parent?.removeView(view)
        customView = null
        val callback = customViewCallback
        customViewCallback = null
        webView.visibility = View.VISIBLE
        enterImmersiveMode()
        callback?.onCustomViewHidden()
        webView.requestFocus()
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private val tvScript: String by lazy {
        assets.open("tv-nav.js").bufferedReader().use { it.readText() }
    }

    private fun injectTvNavigation() {
        webView.evaluateJavascript(tvScript, null)
    }

    /** Back priority: native fullscreen video -> open modal -> HTML fullscreen -> catalog -> exit app. */
    private fun handleBack() {
        if (customView != null) {
            hideCustomView()
            return
        }
        webView.evaluateJavascript(
            "(window.__anikuraTv ? window.__anikuraTv.back() : 'no-script')"
        ) { raw ->
            val result = raw?.trim('"') ?: "null"
            Log.d(logTag, "back -> " + result)
            if (result == "at-home" || result == "no-script" || result == "null") {
                // Already on the catalog: require a second Back within 2 s to leave the app.
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAtHome < 2000) finish()
                else {
                    lastBackAtHome = now
                    Toast.makeText(this, "Druk nogmaals op Terug om af te sluiten", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // Consume DOWN, repeats and UP so the Activity's default onBackPressed()/finish()
                // and WebView's own history navigation never run.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBackHandledAt > 350) {   // debounce double-delivered Back
                        lastBackHandledAt = now
                        handleBack()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && ::webView.isInitialized) {
                    val mode = when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
                        else -> "toggle"
                    }
                    webView.evaluateJavascript(
                        "(window.__anikuraTv ? window.__anikuraTv.togglePlay('$mode') : 'no-script')"
                    ) { r -> Log.d(logTag, "media $mode -> " + (r ?: "null")) }
                }
                return true // never forwarded as Back or Enter
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Back is routed through dispatchKeyEvent; this covers gesture/system back")
    override fun onBackPressed() {
        handleBack()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        enterImmersiveMode()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }
}
