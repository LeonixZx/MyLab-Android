package com.leoncybrlab.mylab

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.webkit.WebView.setWebContentsDebuggingEnabled
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.leoncybrlab.mylab.ui.theme.MyLabTheme
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import android.webkit.JavascriptInterface

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null  // Keep this private
    private var mInterstitialAd: InterstitialAd? = null
    private var mAdView: AdView? = null

    companion object {
        private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
        private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        var pendingContent: String? = null  // Add this if not already present
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            launchOpenFile()
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    pendingContent?.let { content ->
                        outputStream.write(content.toByteArray())
                        webView?.post {
                            webView?.evaluateJavascript(
                                "showNotification('History exported successfully!', 'success')",
                                null
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FileExport", "Failed to save file", e)
                webView?.post {
                    webView?.evaluateJavascript(
                        "showNotification('Error saving file: ${e.message?.replace("'", "\\'")}', 'error')",
                        null
                    )
                }
            }
        }
    }

    // Add this function
    fun showNotification(message: String, type: String) {
        webView?.post {
            webView?.evaluateJavascript(
                "showNotification('$message', '$type')",
                null
            )
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.d("FileImport", "Got URI: $uri")
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { inputStream ->
                    val content = inputStream.bufferedReader().use { reader -> reader.readText() }
                    Log.d("FileImport", "Successfully read file content, length: ${content.length}")
                    Log.d("FileImport", "Content: $content") // Log the content for debugging

                    // Properly wrap the content in quotes if needed
                    val jsonContent = if (!content.trim().startsWith("{")) {
                        "'$content'" // Wrap in quotes if it's not already JSON
                    } else {
                        content // Leave as is if it's already JSON
                    }

                    webView?.post {
                        webView?.evaluateJavascript(
                            """
                        console.log('Starting import of content...');
                        try {
                            let data = $jsonContent;
                            console.log('Parsed data:', data);
                            importHistoryContent(data);
                            showNotification('History imported successfully!', 'success');
                        } catch(e) {
                            console.error('Import failed:', e);
                            showNotification('Error importing file: ' + e.message, 'error');
                        }
                        """.trimIndent(),
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("FileImport", "Failed to read file", e)
                webView?.post {
                    webView?.evaluateJavascript(
                        "showNotification('Error reading file: ${e.message?.replace("'", "\\'")}', 'error')",
                        null
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}
        loadInterstitialAd()
        setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        setContent {
            MyLabTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            OptimizedCalculatorWebView(
                                this@MainActivity,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                            )?.also { webView = it }
                        }

                        AndroidView(
                            factory = { context ->
                                AdView(context).apply {
                                    mAdView = this
                                    setAdSize(AdSize.BANNER)
                                    adUnitId = TEST_BANNER_AD_UNIT_ID
                                    loadAd(AdRequest.Builder().build())
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                        )
                    }
                }
            }
        }
        showInterstitialAd()
    }

    private fun loadInterstitialAd() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            TEST_INTERSTITIAL_AD_UNIT_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.d("AdMob", "Interstitial ad failed to load: ${adError.message}")
                    mInterstitialAd = null
                }

                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    Log.d("AdMob", "Interstitial ad loaded successfully")
                    mInterstitialAd = interstitialAd
                    setupInterstitialAdCallbacks()
                }
            }
        )
    }

    private fun setupInterstitialAdCallbacks() {
        mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d("AdMob", "Interstitial ad dismissed")
                mInterstitialAd = null
                loadInterstitialAd()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.d("AdMob", "Interstitial ad failed to show: ${adError.message}")
                mInterstitialAd = null
            }

            override fun onAdShowedFullScreenContent() {
                Log.d("AdMob", "Interstitial ad showed successfully")
            }
        }
    }

    private fun showInterstitialAd() {
        if (mInterstitialAd != null) {
            mInterstitialAd?.show(this)
        } else {
            Log.d("AdMob", "Interstitial ad wasn't ready yet")
        }
    }

    override fun onDestroy() {
        showInterstitialAd()
        mAdView?.destroy()
        mAdView = null
        webView?.apply {
            clearHistory()
            clearCache(true)
            clearFormData()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    fun launchSaveFile() {
        pendingContent?.let {  // Remove the parameter entirely
            createDocumentLauncher.launch("mylab_history_${System.currentTimeMillis()}.json")
        }
    }

    fun launchPermissionRequest() {
        requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun launchOpenFile() {
        try {
            // Only use JSON MIME type since we're specifically looking for JSON files
            openDocumentLauncher.launch(arrayOf("application/json"))
            Log.d("FileImport", "Launched file picker for JSON files")
        } catch (e: Exception) {
            Log.e("FileImport", "Failed to launch file picker", e)
            webView?.evaluateJavascript(
                "showNotification('Error opening file picker', 'error')",
                null
            )
        }
    }
}

class FileInterface(private val mainActivity: MainActivity) {
    @JavascriptInterface
    fun saveFile(content: String) {
        // Using content parameter directly in function body
        Log.d("FileInterface", "saveFile called with content length: ${content.length}")
        // Then store it
        MainActivity.pendingContent = content
        // Launch save file operation
        mainActivity.launchSaveFile()
    }

    @JavascriptInterface
    fun openFile() {
        Log.d("FileInterface", "openFile called from JavaScript")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mainActivity.launchOpenFile()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (mainActivity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    mainActivity.launchPermissionRequest()
                } else {
                    mainActivity.launchOpenFile()
                }
            } else {
                mainActivity.launchOpenFile()
            }
        } catch (e: Exception) {
            Log.e("FileInterface", "Error in openFile", e)
            mainActivity.showNotification("Error: ${e.message?.replace("'", "\\'")}", "error")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OptimizedCalculatorWebView(activity: MainActivity, modifier: Modifier = Modifier): WebView? {
    var webView: WebView? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                clearHistory()
                clearCache(true)
                clearFormData()
                destroy()
            }
            webView = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webView = this
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("WebView", "Page finished loading")



                        evaluateJavascript("""
                            (function() {
                                // UTF-8 meta tag
                                if (!document.querySelector('meta[charset="UTF-8"]')) {
                                    const meta = document.createElement('meta');
                                    meta.setAttribute('charset', 'UTF-8');
                                    document.head.insertBefore(meta, document.head.firstChild);
                                }
                                
                                // Viewport meta tag
                                if (!document.querySelector('meta[name="viewport"]')) {
                                    const viewportMeta = document.createElement('meta');
                                    viewportMeta.name = 'viewport';
                                    viewportMeta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                                    document.head.appendChild(viewportMeta);
                                }

                                // Add math styles
                                if (!document.getElementById('math-styles')) {
                                    const style = document.createElement('style');
                                    style.id = 'math-styles';
                                    style.textContent = `
                                        .step-formula {
                                            font-family: monospace;
                                            font-size: 0.95rem;
                                            line-height: 1.5;
                                            letter-spacing: 0.02em;
                                        }
                                        .step-formula sub,
                                        .step-formula sup,
                                        .step-title sub,
                                        .step-title sup,
                                        .result-value sub,
                                        .result-value sup {
                                            font-size: 75%;
                                            line-height: 0;
                                            position: relative;
                                            vertical-align: baseline;
                                            font-family: monospace;
                                        }
                                        .step-formula sup,
                                        .step-title sup,
                                        .result-value sup {
                                            top: -0.5em;
                                        }
                                        .step-formula sub,
                                        .step-title sub,
                                        .result-value sub {
                                            bottom: -0.25em;
                                        }
                                        .step-formula .operator {
                                            margin: 0 0.2em;
                                        }
                                    `;
                                    document.head.appendChild(style);
                                }

                                function debounce(func, wait) {
                                    let timeout;
                                    return function executedFunction(...args) {
                                        const later = () => {
                                            clearTimeout(timeout);
                                            func(...args);
                                        };
                                        clearTimeout(timeout);
                                        timeout = setTimeout(later, wait);
                                    };
                                }

                                // Performance monitoring
                                const originalCalculateFunctions = {};
                                ['logReduction', 'dilution', 'cfu', 'media', 'ethanol', 'buffer', 'bufferPh'].forEach(calculator => {
                                    const funcName = 'calculate' + calculator.charAt(0).toUpperCase() + calculator.slice(1);
                                    if (window[funcName]) {
                                        originalCalculateFunctions[funcName] = window[funcName];
                                        window[funcName] = function(...args) {
                                            if (window.PerformanceMonitor) {
                                                window.PerformanceMonitor.onCalculationStart(calculator);
                                            }
                                            const startTime = performance.now();
                                            
                                            try {
                                                originalCalculateFunctions[funcName].apply(this, args);
                                                
                                                if (window.PerformanceMonitor) {
                                                    const duration = performance.now() - startTime;
                                                    window.PerformanceMonitor.onCalculationEnd(calculator, Math.round(duration));
                                                }
                                            } catch (error) {
                                                console.error('Calculation error in ' + calculator + ':', error);
                                                if (window.PerformanceMonitor) {
                                                    window.PerformanceMonitor.onCalculationEnd(calculator, -1);
                                                }
                                            }
                                        };
                                    }
                                });

                                const fixMathNotation = debounce(() => {
                                    const elements = document.querySelectorAll('.step-formula, .result-value, .result-note, .step-title');
                                    elements.forEach(el => {
                                        if (!el.dataset.mathFixed) {
                                            let html = el.innerHTML;
                                            
                                            // Fix dilution formula notation
                                            html = html.replace(/C(\d)V\1/g, 'C<sub>$1</sub>V<sub>$1</sub>');
                                            html = html.replace(/([CV])(\d)(?!\d)/g, '$1<sub>$2</sub>');
                                            html = html.replace(/([CV])₁/g, '$1<sub>1</sub>');
                                            html = html.replace(/([CV])₂/g, '$1<sub>2</sub>');
                                            html = html.replace(/([CV])′/g, '$1<sub>1</sub>');
                                            html = html.replace(/([CV])″/g, '$1<sub>2</sub>');
                                            
                                            // Fix log notation
                                            html = html.replace(/Log(?:₁₀|10|\d{2})/gi, 'Log<sub>10</sub>');
                                            html = html.replace(/log(?:₁₀|10|\d{2})/g, 'log<sub>10</sub>');
                                            
                                            // Fix N0 notation
                                            html = html.replace(/N[0₀]/g, 'N<sub>0</sub>');
                                            
                                            // Fix scientific notation
                                            html = html.replace(/(\d+\.?\d*)[eE]\+?(-?\d+)/g, function(match, b, e) {
                                                return Number(b).toFixed(2) + '×10<sup>' + e + '</sup>';
                                            });
                                            
                                            // Fix multiplication symbol
                                            html = html.replace(/\s*[xX]\s*/g, ' × ');
                                            
                                            // Fix equals sign spacing
                                            html = html.replace(/\s*=\s*/g, ' = ');
                                            
                                            // Fix spacing around arithmetic operators
                                            html = html.replace(/(\d)\s*([+\-×÷])\s*(\d)/g, '$1 $2 $3');
                                            
                                            if (html !== el.innerHTML) {
                                                el.innerHTML = html;
                                                el.dataset.mathFixed = 'true';
                                            }
                                        }
                                    });
                                }, 100);

                                // Clean up previous observer
                                if (window.mathObserver) {
                                    window.mathObserver.disconnect();
                                }

                                // Initialize mutation observer
                                window.mathObserver = new MutationObserver((mutations) => {
                                    const shouldFix = mutations.some(mutation => 
                                        mutation.addedNodes.length > 0 || 
                                        (mutation.type === 'attributes' && 
                                         (mutation.target.classList.contains('step-formula') ||
                                          mutation.target.classList.contains('result-value')))
                                    );
                                    if (shouldFix) {
                                        mutations.forEach(mutation => {
                                            if (mutation.target && mutation.target.dataset) {
                                                delete mutation.target.dataset.mathFixed;
                                            }
                                        });
                                        fixMathNotation();
                                    }
                                });

                                // Start observing
                                window.mathObserver.observe(document.body, {
                                    childList: true,
                                    subtree: true,
                                    attributes: true,
                                    attributeFilter: ['class']
                                });

                                // Initial fix
                                fixMathNotation();

                                // Add visibility change handler for cleanup
                                document.addEventListener('visibilitychange', () => {
                                    if (document.hidden) {
                                        if (window.mathObserver) {
                                            window.mathObserver.disconnect();
                                        }
                                        if (window.calculatorTimeouts) {
                                            window.calculatorTimeouts.forEach(clearTimeout);
                                            window.calculatorTimeouts = [];
                                        }
                                    }
                                });
                            })();
                        """.trimIndent(), null)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        Log.e("WebView Error", "Error: ${error?.description}")
                        super.onReceivedError(view, request, error)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.apply {
                            val level = when (messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                                ConsoleMessage.MessageLevel.WARNING -> "WARNING"
                                else -> "LOG"
                            }
                            Log.d("WebView Console", "$level - ${message()} -- From line ${lineNumber()} of ${sourceId()}")
                        }
                        return true
                    }
                }

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    // Performance optimizations
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    displayZoomControls = false
                    builtInZoomControls = false
                    defaultTextEncodingName = "UTF-8"
                    defaultFontSize = 16
                    minimumLogicalFontSize = 8

                    // Additional settings
                    setGeolocationEnabled(false)
                    mediaPlaybackRequiresUserGesture = true
                    blockNetworkImage = false
                    loadsImagesAutomatically = true

                    // Enable database
                    databaseEnabled = true

                    // Enable JavaScript optimizations
                    javaScriptCanOpenWindowsAutomatically = true
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onCalculationStart(calculatorType: String) {
                        Log.d("Calculator Performance", "Starting calculation: $calculatorType")
                    }

                    @JavascriptInterface
                    fun onCalculationEnd(calculatorType: String, duration: Long) {
                        Log.d("Calculator Performance", "Calculation completed: $calculatorType, Duration: ${duration}ms")
                    }
                }, "PerformanceMonitor")

                // Add the FileInterface
                addJavascriptInterface(FileInterface(activity), "AndroidFileHandler")

                setBackgroundColor(AndroidColor.TRANSPARENT)

                try {
                    loadDataWithBaseURL(
                        "file:///android_asset/",
                        loadHTMLFromAssets(context),
                        "text/html",
                        "UTF-8",
                        null
                    )
                } catch (e: Exception) {
                    Log.e("WebView Load Error", "Failed to load HTML: ${e.message}")
                    loadUrl("about:blank")
                }
            }
        }
    )

    return webView
}

private fun loadHTMLFromAssets(context: android.content.Context): String {
    return try {
        context.assets.open("index.html").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e("Asset Load Error", "Failed to load HTML from assets: ${e.message}")
        "<html><body><h1>Error loading calculator</h1><p>${e.message}</p></body></html>"
    }
}