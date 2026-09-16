package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File
import java.io.ByteArrayInputStream
import android.provider.Settings
import kotlinx.coroutines.launch
import com.example.data.WebSettingsManager
import com.example.data.AdsDnsManager
import com.example.data.DnsProvider
import com.example.data.OfflinePage
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.WebViewModel

enum class AppTab {
    BROWSER, BOOKMARKS, HISTORY, DOWNLOADS, SETTINGS
}

@Composable
fun NavigationBarLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            letterSpacing = (-0.3).sp,
            fontWeight = FontWeight.Medium
        )
    )
}

class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: WebSettingsManager
    private val webViewModel: WebViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = WebSettingsManager(this)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(settingsManager = settingsManager, viewModel = webViewModel)
            }
        }
    }
}

fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } else {
        @Suppress("DEPRECATION")
        val networkInfo = connectivityManager.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return networkInfo.isConnected
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(settingsManager: WebSettingsManager, viewModel: WebViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(AppTab.BROWSER) }
    val tabBackStack = remember { androidx.compose.runtime.mutableStateListOf<AppTab>() }
    val navigateToTab = { targetTab: AppTab ->
        if (currentTab != targetTab) {
            tabBackStack.remove(targetTab)
            tabBackStack.add(currentTab)
            currentTab = targetTab
        }
    }

    var isDistractionFreeMode by remember { mutableStateOf(settingsManager.isDistractionFreeMode) }
    var isScrolledDown by remember { mutableStateOf(false) }

    // State for WebView tracking
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var scrollProgress by remember { mutableFloatStateOf(0f) }
    var hasError by remember { mutableStateOf(false) }
    var errorDescription by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

    // Keep track of current webpage URL and title to enable bookmarking
    var currentUrl by remember { mutableStateOf(if (settingsManager.startUrl.startsWith("about:") || settingsManager.startUrl.startsWith("data:")) "" else settingsManager.startUrl) }
    val isCurrentPageActive = currentUrl.isNotBlank() && !currentUrl.startsWith("about:") && !currentUrl.startsWith("data:")
    var currentTitle by remember { mutableStateOf("Web Reader") }
    var showOpenUrlDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var isBrowserMenuExpanded by remember { mutableStateOf(false) }

    // Search and filter states
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var findCurrentMatch by remember { mutableStateOf(0) }
    var findTotalMatches by remember { mutableStateOf(0) }

    // Keep WebView instance remembered so that its page state is preserved
    // when switching tabs or rotating screen
    val webView = remember {
        ObservableWebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = settingsManager.isJsEnabled
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isForceDarkAllowed = settingsManager.isForceDarkAllowed
                @Suppress("DEPRECATION")
                settings.forceDark = if (settingsManager.isForceDarkAllowed) {
                    WebSettings.FORCE_DARK_AUTO
                } else {
                    WebSettings.FORCE_DARK_OFF
                }
            }
            onScrollChangedCallback = { scrollY, oldScrollY ->
                val currentUrl = url
                if (!currentUrl.isNullOrBlank()) {
                    viewModel.saveScrollPosition(currentUrl, scrollY)
                }
            }
            onScrollDirectionChanged = { isDown ->
                if (isDown) {
                    if (!isScrolledDown) isScrolledDown = true
                } else {
                    if (isScrolledDown) isScrolledDown = false
                }
            }
            onScrollProgressChangedCallback = { p ->
                scrollProgress = p
            }

            class OfflineCaptureBridge(private val onHtmlReceived: (String) -> Unit) {
                @JavascriptInterface
                fun processHtml(html: String) {
                    onHtmlReceived(html)
                }
            }
            addJavascriptInterface(
                OfflineCaptureBridge { rawHtml ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        viewModel.saveOfflinePage(
                            title = currentTitle.ifBlank { currentUrl },
                            url = currentUrl,
                            htmlContent = rawHtml,
                            onComplete = { success, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                },
                "OfflineCaptureBridge"
            )
        }
    }

    val clearWebViewCacheAndCookies: (Boolean) -> Unit = { reloadAfter ->
        try {
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
            webView.clearSslPreferences()
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies {
                cookieManager.flush()
            }
            WebStorage.getInstance().deleteAllData()
            try {
                context.cacheDir.deleteRecursively()
            } catch (_: Exception) {}

            Toast.makeText(context, "Cache and cookies cleared successfully", Toast.LENGTH_SHORT).show()
            if (reloadAfter && currentUrl.isNotBlank()) {
                hasError = false
                webView.loadUrl(currentUrl)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Cleared data: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val clearBrowser: () -> Unit = {
        currentUrl = ""
        currentTitle = ""
        settingsManager.startUrl = ""
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearFormData()
            webView.clearSslPreferences()
            webView.clearCache(true)
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies {
                cookieManager.flush()
            }
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        hasError = false
        errorDescription = ""
        isScrolledDown = false
        scrollProgress = 0f
        isSearchActive = false
        searchQuery = ""
        if (currentTab != AppTab.BROWSER) {
            navigateToTab(AppTab.BROWSER)
        }
        Toast.makeText(context, "Browser cleared. Ready for new link.", Toast.LENGTH_SHORT).show()
    }

    val loadWebsiteUrl: (String) -> Unit = { rawUrl ->
        val trimmed = rawUrl.trim()
        if (trimmed.isNotBlank()) {
            val target = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                "https://$trimmed"
            } else {
                trimmed
            }
            currentUrl = target
            settingsManager.startUrl = target
            hasError = false
            if (currentTab != AppTab.BROWSER) {
                navigateToTab(AppTab.BROWSER)
            }
            webView.loadUrl(target)
        }
    }

    val loadOfflinePageIntoBrowser: (OfflinePage) -> Unit = { page ->
        val htmlContent = viewModel.loadOfflineHtml(page)
        currentTitle = page.title
        currentUrl = page.url
        hasError = false
        if (currentTab != AppTab.BROWSER) {
            navigateToTab(AppTab.BROWSER)
        }
        webView.loadDataWithBaseURL(page.url, htmlContent, "text/html", "UTF-8", null)
        Toast.makeText(context, "Loaded offline page: ${page.title}", Toast.LENGTH_SHORT).show()
    }

    val saveCurrentPageForOffline: () -> Unit = {
        if (currentUrl.isBlank() || currentUrl.startsWith("about:") || currentUrl.startsWith("data:")) {
            Toast.makeText(context, "No active webpage to save", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Saving page for offline reading...", Toast.LENGTH_SHORT).show()
            val script = """
                (function() {
                    try {
                        var html = document.documentElement.outerHTML;
                        if (window.OfflineCaptureBridge) {
                            window.OfflineCaptureBridge.processHtml(html);
                            return "BRIDGE_OK";
                        }
                        return html;
                    } catch (e) {
                        return "";
                    }
                })()
            """.trimIndent()
            webView.evaluateJavascript(script) { result ->
                if (result != null && result != "\"BRIDGE_OK\"") {
                    val cleaned = if (result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
                        try {
                            org.json.JSONTokener(result).nextValue() as? String ?: result
                        } catch (_: Exception) {
                            result.substring(1, result.length - 1)
                                .replace("\\\"", "\"")
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                                .replace("\\t", "\t")
                                .replace("\\\\", "\\")
                        }
                    } else {
                        result
                    }
                    if (cleaned.isNotBlank()) {
                        viewModel.saveOfflinePage(
                            title = currentTitle.ifBlank { currentUrl },
                            url = currentUrl,
                            htmlContent = cleaned,
                            onComplete = { success, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // Set WebView clients once
    LaunchedEffect(webView) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val clickedUri = Uri.parse(url)

                // Intercept custom intents to prevent crashes
                if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:") || url.startsWith("intent:")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, clickedUri)
                        view.context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "No app available to handle this link", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }

                // Allow all web URLs (http / https) to load directly inside the reader WebView
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }

                try {
                    val intent = Intent(Intent.ACTION_VIEW, clickedUri)
                    view.context.startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null) {
                    val reqUrl = request.url.toString()
                    val provider = DnsProvider.fromId(settingsManager.dnsProvider)
                    if (settingsManager.isInAppAdBlockingEnabled && AdsDnsManager.shouldBlockUrl(reqUrl, provider, settingsManager.customDnsHost)) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
                hasError = false
                scrollProgress = 0f
                if (url != null && !url.startsWith("about:") && !url.startsWith("data:") && url.isNotBlank()) {
                    currentUrl = url
                } else {
                    currentUrl = ""
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
                isRefreshing = false
                progress = 100
                if (url != null && !url.startsWith("about:") && !url.startsWith("data:") && url.isNotBlank()) {
                    currentUrl = url
                    val title = view?.title ?: ""
                    if (title.isNotEmpty()) {
                        currentTitle = title
                    }
                    (view as? ObservableWebView)?.updateScrollProgress()
                    view?.postDelayed({
                        (view as? ObservableWebView)?.updateScrollProgress()
                    }, 300)

                    // Apply cosmetic ad-hiding rules if active provider blocks ads
                    val provider = DnsProvider.fromId(settingsManager.dnsProvider)
                    if (settingsManager.isInAppAdBlockingEnabled && provider.blocksAds) {
                        view?.evaluateJavascript(AdsDnsManager.getAdHidingScript(), null)
                    }
                    val pageUrl = url
                    viewModel.addHistory(pageUrl, if (title.isNotEmpty()) title else pageUrl)
                    scope.launch {
                        val pos = viewModel.getScrollPosition(pageUrl)
                        if (pos != null) {
                            view?.post {
                                view.scrollTo(0, pos.scrollY)
                            }
                            // Also try via JavaScript in case content didn't finish laying out
                            view?.evaluateJavascript("window.scrollTo(0, ${pos.scrollY});", null)
                        }
                    }
                } else {
                    currentUrl = ""
                    currentTitle = ""
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        error?.description?.toString() ?: "Network Connection Error"
                    } else {
                        "Network connection failed."
                    }
                    hasError = true
                    errorDescription = desc
                    isLoading = false
                    isRefreshing = false
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (failingUrl == view?.url) {
                    hasError = true
                    errorDescription = description ?: "Network connection failed."
                    isLoading = false
                    isRefreshing = false
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val didCrash = detail?.didCrash() ?: false
                android.util.Log.w("WebReader", "WebView render process gone (crashed: $didCrash). Recovering gracefully.")
                hasError = true
                errorDescription = if (didCrash) {
                    "The webpage rendering process was restarted by the system. Tap retry or clear cache to reload."
                } else {
                    "The webpage rendering process was reclaimed by the system to save memory. Tap retry to reload."
                }
                isLoading = false
                isRefreshing = false
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progress = newProgress
            }
        }
    }

    // Initial page load or retry trigger
    val triggerLoad = remember { mutableStateOf(0) }
    LaunchedEffect(triggerLoad.value) {
        if (currentUrl.isNotBlank()) {
            if (isNetworkAvailable(context)) {
                hasError = false
                webView.loadUrl(currentUrl)
            } else {
                hasError = true
                errorDescription = "No internet connection detected."
            }
        }
    }

    // Reset search when tab changes
    LaunchedEffect(currentTab) {
        isSearchActive = false
        searchQuery = ""
        webView.clearMatches()
        findCurrentMatch = 0
        findTotalMatches = 0
    }

    // Set Find Listener on the webView
    LaunchedEffect(webView) {
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            findCurrentMatch = activeMatchOrdinal
            findTotalMatches = numberOfMatches
        }
    }

    // Handle System Back Button
    BackHandler(enabled = (currentTab == AppTab.BROWSER && webView.canGoBack()) || tabBackStack.isNotEmpty()) {
        if (currentTab == AppTab.BROWSER && webView.canGoBack()) {
            webView.goBack()
        } else if (tabBackStack.isNotEmpty()) {
            val lastTab = tabBackStack.removeAt(tabBackStack.size - 1)
            currentTab = lastTab
        }
    }

    // Reset scrolled down state when switching tabs or when distraction-free mode is turned off
    LaunchedEffect(currentTab, isDistractionFreeMode) {
        if (!isDistractionFreeMode || currentTab != AppTab.BROWSER) {
            isScrolledDown = false
        }
    }

    val showBars = remember(currentTab, isDistractionFreeMode, isScrolledDown) {
        if (currentTab != AppTab.BROWSER) {
            true
        } else if (isDistractionFreeMode) {
            !isScrolledDown
        } else {
            true
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.animation.AnimatedVisibility(
                visible = showBars,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                Column {
                    if (isSearchActive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    isSearchActive = false
                                    searchQuery = ""
                                    if (currentTab == AppTab.BROWSER) {
                                        webView.clearMatches()
                                        findCurrentMatch = 0
                                        findTotalMatches = 0
                                    }
                                },
                                modifier = Modifier.testTag("close_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            TextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    if (currentTab == AppTab.BROWSER) {
                                        if (it.isNotEmpty()) {
                                            webView.findAllAsync(it)
                                        } else {
                                            webView.clearMatches()
                                            findCurrentMatch = 0
                                            findTotalMatches = 0
                                        }
                                    }
                                },
                                placeholder = {
                                    Text(
                                        when (currentTab) {
                                            AppTab.BROWSER -> "Search on page..."
                                            AppTab.BOOKMARKS -> "Filter bookmarks..."
                                            AppTab.HISTORY -> "Filter history..."
                                            AppTab.DOWNLOADS -> "Filter downloads..."
                                            else -> "Search..."
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("search_input_field"),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                singleLine = true
                            )

                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                        if (currentTab == AppTab.BROWSER) {
                                            webView.clearMatches()
                                            findCurrentMatch = 0
                                            findTotalMatches = 0
                                        }
                                    },
                                    modifier = Modifier.testTag("clear_search_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // If on browser tab and search is active, show the find on page navigation buttons
                        if (currentTab == AppTab.BROWSER && searchQuery.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (findTotalMatches > 0) {
                                        "${findCurrentMatch + 1} of $findTotalMatches matches"
                                    } else {
                                        "No matches found"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { webView.findNext(false) },
                                        enabled = findTotalMatches > 0,
                                        modifier = Modifier.testTag("find_prev_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = "Previous match"
                                        )
                                    }
                                    IconButton(
                                        onClick = { webView.findNext(true) },
                                        enabled = findTotalMatches > 0,
                                        modifier = Modifier.testTag("find_next_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Next match"
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Simple Material 3 Top App Bar with custom URL navigation icon
                        TopAppBar(
                            modifier = Modifier.testTag("main_top_app_bar"),
                            title = {
                                Surface(
                                    onClick = { showOpenUrlDialog = true },
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .testTag("top_bar_url_surface")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Language,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = when {
                                                currentTab != AppTab.BROWSER -> when (currentTab) {
                                                    AppTab.BOOKMARKS -> "Bookmarks"
                                                    AppTab.HISTORY -> "History"
                                                    AppTab.DOWNLOADS -> "Downloads"
                                                    AppTab.SETTINGS -> "Settings"
                                                    else -> "Web Reader"
                                                }
                                                isCurrentPageActive -> currentUrl.removePrefix("https://").removePrefix("http://")
                                                else -> "Enter URL to navigate..."
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (currentTab == AppTab.BROWSER && isCurrentPageActive) {
                                            IconButton(
                                                onClick = { clearBrowser() },
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .testTag("top_bar_clear_browser_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Clear Browser",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            navigationIcon = {
                                if (currentTab == AppTab.BROWSER && webView.canGoBack()) {
                                    IconButton(
                                        onClick = { webView.goBack() },
                                        modifier = Modifier.testTag("nav_back_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            if (currentTab != AppTab.BROWSER) {
                                                navigateToTab(AppTab.BROWSER)
                                            } else if (isCurrentPageActive) {
                                                clearBrowser()
                                            }
                                        },
                                        modifier = Modifier.testTag("app_logo_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoStories,
                                            contentDescription = "Web Reader",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (currentTab == AppTab.BROWSER && isCurrentPageActive) {
                                    IconButton(
                                        onClick = { clearBrowser() },
                                        modifier = Modifier.testTag("browser_clear_action_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear Browser",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (currentTab == AppTab.BROWSER) {
                                    // Dedicated Distraction-Free Mode toggle button directly in Top App Bar
                                    IconButton(
                                        onClick = {
                                            isDistractionFreeMode = !isDistractionFreeMode
                                            settingsManager.isDistractionFreeMode = isDistractionFreeMode
                                            isScrolledDown = false
                                            Toast.makeText(
                                                context,
                                                if (isDistractionFreeMode) "Distraction-Free Mode ON (Scroll down hides bars)" else "Distraction-Free Mode OFF",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        modifier = Modifier.testTag("distraction_free_top_bar_toggle")
                                    ) {
                                        Icon(
                                            imageVector = if (isDistractionFreeMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                            contentDescription = if (isDistractionFreeMode) "Exit Distraction-Free Mode" else "Enter Distraction-Free Mode",
                                            tint = if (isDistractionFreeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Dedicated icon that opens text input dialog to navigate to custom URLs
                                IconButton(
                                    onClick = { showOpenUrlDialog = true },
                                    modifier = Modifier.testTag("open_url_dialog_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddLink,
                                        contentDescription = "Enter Website URL",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                IconButton(
                                    onClick = { isSearchActive = true },
                                    modifier = Modifier.testTag("search_trigger_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (currentTab == AppTab.BROWSER) {
                                    Box {
                                        IconButton(
                                            onClick = { isBrowserMenuExpanded = true },
                                            modifier = Modifier.testTag("browser_more_menu_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "More Options",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = isBrowserMenuExpanded,
                                            onDismissRequest = { isBrowserMenuExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Enter Website URL") },
                                                onClick = {
                                                    isBrowserMenuExpanded = false
                                                    showOpenUrlDialog = true
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.AddLink,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                },
                                                modifier = Modifier.testTag("menu_open_url_dialog")
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Refresh Page") },
                                                onClick = {
                                                    isBrowserMenuExpanded = false
                                                    if (currentUrl.isNotBlank()) {
                                                        hasError = false
                                                        webView.reload()
                                                    }
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null
                                                    )
                                                },
                                                modifier = Modifier.testTag("menu_refresh_page")
                                            )

                                            DropdownMenuItem(
                                                text = { Text(if (isDistractionFreeMode) "Exit Distraction-Free Mode" else "Enter Distraction-Free Mode") },
                                                onClick = {
                                                    isBrowserMenuExpanded = false
                                                    isDistractionFreeMode = !isDistractionFreeMode
                                                    settingsManager.isDistractionFreeMode = isDistractionFreeMode
                                                    isScrolledDown = false
                                                    Toast.makeText(
                                                        context,
                                                        if (isDistractionFreeMode) "Distraction-Free Mode ON (Scroll down hides bars)" else "Distraction-Free Mode OFF",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = if (isDistractionFreeMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                                        contentDescription = null,
                                                        tint = if (isDistractionFreeMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                },
                                                modifier = Modifier.testTag("distraction_free_toggle_button")
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Save for Offline") },
                                                onClick = {
                                                    isBrowserMenuExpanded = false
                                                    saveCurrentPageForOffline()
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.SaveAlt,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                },
                                                modifier = Modifier.testTag("save_for_offline_button")
                                            )

                                            DropdownMenuItem(
                                                text = {
                                                    val provider = DnsProvider.fromId(settingsManager.dnsProvider)
                                                    Text("Ads & DNS: ${if (provider.blocksAds && settingsManager.isInAppAdBlockingEnabled) "AdBlock ON" else "Standard"}")
                                                },
                                                onClick = {
                                                    isBrowserMenuExpanded = false
                                                    navigateToTab(AppTab.SETTINGS)
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Security,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                },
                                                modifier = Modifier.testTag("menu_dns_settings")
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Clear Browser") },
                                                onClick = {
                                                    isBrowserMenuExpanded = false
                                                    clearBrowser()
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                modifier = Modifier.testTag("menu_clear_browser")
                                            )

                                            DropdownMenuItem(
                                                text = { Text("Clear Cache & Cookies") },
                                                onClick = {
                                                    isBrowserMenuExpanded = false
                                                    showClearDataDialog = true
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.CleaningServices,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                },
                                                modifier = Modifier.testTag("menu_clear_cache_cookies")
                                            )

                                            if (currentUrl.isNotBlank()) {
                                                DropdownMenuItem(
                                                    text = { Text("Open in External Browser") },
                                                    onClick = {
                                                        isBrowserMenuExpanded = false
                                                        try {
                                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                                            context.startActivity(intent)
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Cannot open external browser", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Default.OpenInBrowser,
                                                            contentDescription = null
                                                        )
                                                    },
                                                    modifier = Modifier.testTag("menu_open_external")
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                    // A very subtle line separator
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    },
        bottomBar = {
            androidx.compose.animation.AnimatedVisibility(
                visible = showBars,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                NavigationBar(
                    modifier = Modifier.testTag("bottom_nav_bar"),
                    tonalElevation = 8.dp
                ) {
                NavigationBarItem(
                    selected = currentTab == AppTab.BROWSER,
                    onClick = {
                        if (currentTab == AppTab.BROWSER && isCurrentPageActive) {
                            clearBrowser()
                        } else {
                            navigateToTab(AppTab.BROWSER)
                        }
                    },
                    label = { NavigationBarLabel("Browser") },
                    icon = { Icon(Icons.Default.Language, contentDescription = "Web Browser") },
                    modifier = Modifier.testTag("nav_browser_tab")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.BOOKMARKS,
                    onClick = { navigateToTab(AppTab.BOOKMARKS) },
                    label = { NavigationBarLabel("Bookmarks") },
                    icon = { Icon(Icons.Default.Bookmark, contentDescription = "Bookmarks") },
                    modifier = Modifier.testTag("nav_bookmarks_tab")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.HISTORY,
                    onClick = { navigateToTab(AppTab.HISTORY) },
                    label = { NavigationBarLabel("History") },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    modifier = Modifier.testTag("nav_history_tab")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.DOWNLOADS,
                    onClick = { navigateToTab(AppTab.DOWNLOADS) },
                    label = { NavigationBarLabel("Downloads") },
                    icon = { Icon(Icons.Default.Download, contentDescription = "Downloads") },
                    modifier = Modifier.testTag("nav_downloads_tab")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.SETTINGS,
                    onClick = { navigateToTab(AppTab.SETTINGS) },
                    label = { NavigationBarLabel("Settings") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    modifier = Modifier.testTag("nav_settings_tab")
                )
            }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .fillMaxSize()
        ) {
            when (currentTab) {
                AppTab.BROWSER -> {
                    BrowserScreen(
                        webView = webView,
                        isLoading = isLoading,
                        progress = progress,
                        scrollProgress = scrollProgress,
                        hasError = hasError,
                        errorDescription = errorDescription,
                        isRefreshing = isRefreshing,
                        currentUrl = currentUrl,
                        currentTitle = currentTitle,
                        viewModel = viewModel,
                        onRefresh = {
                            isRefreshing = true
                            if (isNetworkAvailable(context)) {
                                webView.reload()
                            } else {
                                hasError = true
                                errorDescription = "No internet connection detected."
                                isRefreshing = false
                            }
                        },
                        onRetry = {
                            triggerLoad.value += 1
                        },
                        onLoadUrl = loadWebsiteUrl,
                        onOpenUrlDialog = { showOpenUrlDialog = true },
                        onClearCacheAndCookies = { showClearDataDialog = true },
                        onSaveForOffline = { saveCurrentPageForOffline() },
                        showNavigationElements = showBars,
                        isDistractionFreeMode = isDistractionFreeMode,
                        onToggleDistractionFreeMode = {
                            isDistractionFreeMode = !isDistractionFreeMode
                            settingsManager.isDistractionFreeMode = isDistractionFreeMode
                            isScrolledDown = false
                            Toast.makeText(
                                context,
                                if (isDistractionFreeMode) "Distraction-Free Mode ON (Scroll down hides bars)" else "Distraction-Free Mode OFF",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onClearBrowser = clearBrowser
                    )
                }
                AppTab.BOOKMARKS -> {
                    BookmarksScreen(
                        viewModel = viewModel,
                        searchQuery = searchQuery,
                        onNavigateToUrl = { url ->
                            loadWebsiteUrl(url)
                        }
                    )
                }
                AppTab.HISTORY -> {
                    HistoryScreen(
                        viewModel = viewModel,
                        searchQuery = searchQuery,
                        onNavigateToUrl = { url ->
                            loadWebsiteUrl(url)
                        }
                    )
                }
                AppTab.DOWNLOADS -> {
                    DownloadsScreen(
                        viewModel = viewModel,
                        searchQuery = searchQuery,
                        isDistractionFreeMode = isDistractionFreeMode,
                        onOpenInBrowser = { page ->
                            loadOfflinePageIntoBrowser(page)
                        }
                    )
                }
                AppTab.SETTINGS -> {
                    SettingsScreen(
                        settingsManager = settingsManager,
                        onApplyChanges = { newUrl, newJs, newForceDark, newDistractionFree, newDns, newCustomDns, newAdBlock ->
                            settingsManager.startUrl = newUrl
                            settingsManager.isJsEnabled = newJs
                            settingsManager.isForceDarkAllowed = newForceDark
                            settingsManager.isDistractionFreeMode = newDistractionFree
                            settingsManager.dnsProvider = newDns
                            settingsManager.customDnsHost = newCustomDns
                            settingsManager.isInAppAdBlockingEnabled = newAdBlock
                            isDistractionFreeMode = newDistractionFree
                            webView.settings.javaScriptEnabled = newJs
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                webView.isForceDarkAllowed = newForceDark
                                @Suppress("DEPRECATION")
                                webView.settings.forceDark = if (newForceDark) {
                                    WebSettings.FORCE_DARK_AUTO
                                } else {
                                    WebSettings.FORCE_DARK_OFF
                                }
                            }
                            if (newUrl.isNotBlank()) {
                                loadWebsiteUrl(newUrl)
                            } else {
                                currentUrl = ""
                                webView.loadUrl("about:blank")
                            }
                            navigateToTab(AppTab.BROWSER)
                            Toast.makeText(context, "Settings saved and applied!", Toast.LENGTH_SHORT).show()
                        },
                        onClearCacheAndCookies = { showClearDataDialog = true },
                        onClearBrowserToHome = { clearBrowser() }
                    )
                }
            }
        }
    }

    if (showOpenUrlDialog) {
        var dialogUrl by remember {
            mutableStateOf(if (currentUrl.startsWith("about:") || currentUrl.startsWith("data:")) "" else currentUrl)
        }
        AlertDialog(
            onDismissRequest = { showOpenUrlDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            ),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .imePadding(),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Navigate to Custom URL", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Enter or paste any website link to navigate beyond the start page:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = dialogUrl,
                        onValueChange = { dialogUrl = it },
                        label = { Text("Website Link / URL") },
                        placeholder = { Text("https://example.com or domain.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (dialogUrl.isNotBlank()) {
                                    loadWebsiteUrl(dialogUrl)
                                    showOpenUrlDialog = false
                                }
                            }
                        ),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (dialogUrl.isNotEmpty()) {
                                    IconButton(onClick = { dialogUrl = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clip = clipboard?.primaryClip
                                        if (clip != null && clip.itemCount > 0) {
                                            val text = clip.getItemAt(0)?.text?.toString() ?: ""
                                            if (text.isNotBlank()) {
                                                dialogUrl = text.trim()
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste from clipboard",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_url_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Text(
                        text = "Quick navigation suggestions:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "Wikipedia" to "https://en.wikipedia.org",
                            "BBC News" to "https://www.bbc.com/news",
                            "Hacker News" to "https://news.ycombinator.com"
                        ).forEach { (name, target) ->
                            SuggestionChip(
                                onClick = {
                                    dialogUrl = target
                                    loadWebsiteUrl(target)
                                    showOpenUrlDialog = false
                                },
                                label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("quick_url_$name")
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogUrl.isNotBlank()) {
                            loadWebsiteUrl(dialogUrl)
                            showOpenUrlDialog = false
                        }
                    },
                    enabled = dialogUrl.isNotBlank(),
                    modifier = Modifier.testTag("dialog_load_url_button")
                ) {
                    Text("Navigate")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showOpenUrlDialog = false },
                    modifier = Modifier.testTag("dialog_cancel_url_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.CleaningServices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Clear Browser & Data",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Choose whether to reset the browser to the Web & Comic Reader home stage or clear cache and cookies to reload the current page."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDataDialog = false
                        clearWebViewCacheAndCookies(false)
                        clearBrowser()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("confirm_clear_to_home_button")
                ) {
                    Text("Clear & Return Home", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                showClearDataDialog = false
                                clearWebViewCacheAndCookies(true)
                            },
                            modifier = Modifier.testTag("confirm_clear_cache_button")
                        ) {
                            Text("Clear & Reload")
                        }
                    }
                    TextButton(
                        onClick = { showClearDataDialog = false },
                        modifier = Modifier.testTag("cancel_clear_cache_button")
                    ) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    webView: WebView,
    isLoading: Boolean,
    progress: Int,
    scrollProgress: Float,
    hasError: Boolean,
    errorDescription: String,
    isRefreshing: Boolean,
    currentUrl: String,
    currentTitle: String,
    viewModel: WebViewModel,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadUrl: (String) -> Unit,
    onOpenUrlDialog: () -> Unit,
    onClearCacheAndCookies: () -> Unit = {},
    onSaveForOffline: () -> Unit = {},
    showNavigationElements: Boolean = true,
    isDistractionFreeMode: Boolean = false,
    onToggleDistractionFreeMode: () -> Unit = {},
    onClearBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val isBookmarked by viewModel.isBookmarked(currentUrl).collectAsState(initial = false)

    // Download state variables
    var showDownloadDialog by remember { mutableStateOf(false) }
    var detectedImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var chapterTitleInput by remember { mutableStateOf("") }
    var isDownloadingChapter by remember { mutableStateOf(false) }
    var downloadProgressText by remember { mutableStateOf("") }

    val isPageActive = currentUrl.isNotBlank() && !currentUrl.startsWith("about:") && !currentUrl.startsWith("data:")

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isPageActive) {
            UrlEntryLandingScreen(
                onLoadUrl = onLoadUrl,
                viewModel = viewModel
            )
        } else if (hasError) {
            OfflineErrorScreen(
                errorDescription = errorDescription,
                onRetry = onRetry,
                onEnterNewUrl = onOpenUrlDialog,
                onClearCacheAndRetry = onClearCacheAndCookies,
                onClearBrowser = onClearBrowser,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pull_to_refresh_box")
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browser_webview")
                )
            }

            // Visual Reading Scroll Progress Bar at the top of the screen
            val animatedScrollProgress by animateFloatAsState(
                targetValue = scrollProgress.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 100, easing = LinearOutSlowInEasing),
                label = "readingScrollProgress"
            )

            // Progress bar at the top of the screen tracking the user's scroll percentage
            LinearProgressIndicator(
                progress = { animatedScrollProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.TopCenter)
                    .testTag("reading_scroll_progress_bar"),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            )

            // Webpage network loading indicator (shown during active page request)
            if (isLoading && progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                        .testTag("loading_progress_bar"),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = Color.Transparent
                )
            }

            // Interactive scroll percentage badge at the top right of the reading canvas
            val scrollPercentage = (animatedScrollProgress * 100).toInt().coerceIn(0, 100)
            AnimatedVisibility(
                visible = scrollPercentage > 0,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp)
            ) {
                Surface(
                    onClick = {
                        webView.scrollTo(0, 0)
                        webView.evaluateJavascript("window.scrollTo({top: 0, behavior: 'smooth'});", null)
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 4.dp,
                    shadowElevation = 3.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.testTag("reading_percentage_pill")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (scrollPercentage >= 99) Icons.Default.CheckCircle else Icons.Default.VerticalAlignTop,
                            contentDescription = "Scroll to top",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (scrollPercentage >= 99) "100% (Finished)" else "$scrollPercentage% read",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("reading_percentage_text")
                        )
                    }
                }
            }

            // Stacked Floating Action Buttons (Save for Offline, Download & Bookmark)
            androidx.compose.animation.AnimatedVisibility(
                visible = showNavigationElements && isPageActive,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    // Save for Offline FAB
                    FloatingActionButton(
                        onClick = { onSaveForOffline() },
                        modifier = Modifier.testTag("save_for_offline_fab"),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.SaveAlt,
                            contentDescription = "Save for Offline"
                        )
                    }

                    // Download Chapter FAB
                    FloatingActionButton(
                        onClick = {
                            val script = """
                                (function() {
                                    var imgs = document.getElementsByTagName('img');
                                    var urls = [];
                                    for (var i = 0; i < imgs.length; i++) {
                                        var src = imgs[i].src || imgs[i].getAttribute('data-src') || imgs[i].getAttribute('data-lazy-src') || imgs[i].getAttribute('data-original');
                                        if (src && (src.startsWith('http') || src.startsWith('data:image/'))) {
                                            var width = imgs[i].naturalWidth || imgs[i].width || 0;
                                            var height = imgs[i].naturalHeight || imgs[i].height || 0;
                                            if ((width === 0 || width > 150) && (height === 0 || height > 150)) {
                                                if (urls.indexOf(src) === -1) {
                                                    urls.push(src);
                                                }
                                            }
                                        }
                                    }
                                    return JSON.stringify(urls);
                                })()
                            """.trimIndent()

                            webView.evaluateJavascript(script) { result ->
                                try {
                                    val cleanedResult = if (result != null && result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
                                        val tokener = org.json.JSONTokener(result)
                                        val value = tokener.nextValue()
                                        if (value is String) value else result
                                    } else {
                                        result ?: "[]"
                                    }
                                    val jsonArray = org.json.JSONArray(cleanedResult)
                                    val urls = mutableListOf<String>()
                                    for (i in 0 until jsonArray.length()) {
                                        urls.add(jsonArray.getString(i))
                                    }
                                    detectedImages = urls
                                    
                                    var defaultTitle = currentTitle
                                        .replace(" - Read Free Online", "")
                                        .trim()
                                    if (defaultTitle.isEmpty()) {
                                        defaultTitle = "Chapter"
                                    }
                                    chapterTitleInput = defaultTitle
                                    showDownloadDialog = true
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to scan page images: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.testTag("download_chapter_fab"),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download Chapter"
                        )
                    }

                    // Bookmark Floating Action Button
                    FloatingActionButton(
                        onClick = {
                            viewModel.toggleBookmark(currentUrl, currentTitle)
                            val message = if (isBookmarked) "Bookmark removed" else "Page bookmarked"
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("bookmark_fab"),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark page"
                        )
                    }

                    // Clear Browser Floating Action Button (Quick Exit to Home Stage)
                    FloatingActionButton(
                        onClick = { onClearBrowser() },
                        modifier = Modifier.testTag("clear_browser_fab"),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear Browser and Return Home"
                        )
                    }
                }
            }

            // Distraction-Free floating quick-restore indicator chip
            androidx.compose.animation.AnimatedVisibility(
                visible = isDistractionFreeMode && !showNavigationElements,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it },
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 12.dp)
            ) {
                Surface(
                    onClick = onToggleDistractionFreeMode,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    tonalElevation = 4.dp,
                    shadowElevation = 3.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("distraction_free_floating_indicator")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "Exit Distraction-Free Mode",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Distraction-Free",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    // Elegant download dialog overlay
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloadingChapter) showDownloadDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            ),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .imePadding(),
            title = { Text("Download Chapter") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isDownloadingChapter) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = downloadProgressText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Please keep the app open while downloading.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            text = "Save this chapter for offline reading in your Downloads gallery.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        OutlinedTextField(
                            value = chapterTitleInput,
                            onValueChange = { chapterTitleInput = it },
                            label = { Text("Chapter Title") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("download_title_input"),
                            singleLine = true
                        )
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Detected ${detectedImages.size} pages on this page",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!isDownloadingChapter) {
                    Button(
                        onClick = {
                            val title = chapterTitleInput.trim().ifEmpty { "Untitled Chapter" }
                            isDownloadingChapter = true
                            downloadProgressText = "Starting download..."
                            viewModel.downloadChapter(
                                title = title,
                                sourceUrl = currentUrl,
                                imageUrlList = detectedImages,
                                onProgress = { progressText ->
                                    downloadProgressText = progressText
                                },
                                onComplete = { success, message ->
                                    isDownloadingChapter = false
                                    showDownloadDialog = false
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        modifier = Modifier.testTag("dialog_download_button")
                    ) {
                        Text("Download")
                    }
                }
            },
            dismissButton = {
                if (!isDownloadingChapter) {
                    TextButton(
                        onClick = { showDownloadDialog = false },
                        modifier = Modifier.testTag("dialog_cancel_button")
                    ) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
fun UrlEntryLandingScreen(
    onLoadUrl: (String) -> Unit,
    viewModel: WebViewModel
) {
    val context = LocalContext.current
    var urlText by remember { mutableStateOf("") }
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Web & Comic Reader",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Paste or enter any website link into the box below to start reading with smooth scrolling, offline chapters, and distraction-free mode.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Website Link") },
                    placeholder = { Text("https://example.com/chapter-1") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (urlText.isNotEmpty()) {
                                IconButton(onClick = { urlText = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear input")
                                }
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = clipboard?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0)?.text?.toString() ?: ""
                                        if (text.isNotBlank()) {
                                            urlText = text.trim()
                                            Toast.makeText(context, "Link pasted!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.testTag("paste_url_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (urlText.isNotBlank()) {
                                onLoadUrl(urlText)
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("main_url_input_box"),
                    shape = RoundedCornerShape(12.dp)
                )

                Button(
                    onClick = {
                        if (urlText.isNotBlank()) {
                            onLoadUrl(urlText)
                        }
                    },
                    enabled = urlText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_reading_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Reading", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Quick shortcuts from Bookmarks or History if available
        val recentShortcuts = remember(bookmarks, history) {
            val combined = (bookmarks.map { it.url to it.title } + history.map { it.url to it.title })
                .distinctBy { it.first }
                .take(3)
            combined
        }

        if (recentShortcuts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(28.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Or continue reading:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                recentShortcuts.forEach { (url, title) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        onClick = { onLoadUrl(url) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Launch,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (title.isNotBlank()) title else url,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OfflineErrorScreen(
    errorDescription: String,
    onRetry: () -> Unit,
    onEnterNewUrl: () -> Unit = {},
    onClearCacheAndRetry: (() -> Unit)? = null,
    onClearBrowser: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.web_error_illustration),
            contentDescription = "Offline Connection Illustration",
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Oops! Connection Lost",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = errorDescription,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("retry_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try Again", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onEnterNewUrl,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("change_url_button")
            ) {
                Icon(Icons.Default.Link, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Change Link", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (onClearCacheAndRetry != null) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onClearCacheAndRetry,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("error_clear_cache_button"),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CleaningServices,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Cache & Retry", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (onClearBrowser != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onClearBrowser,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("error_clear_browser_button"),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear Browser & Return Home", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun BookmarksScreen(
    viewModel: WebViewModel,
    searchQuery: String,
    onNavigateToUrl: (String) -> Unit
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val filteredBookmarks = remember(bookmarks, searchQuery) {
        if (searchQuery.isBlank()) {
            bookmarks
        } else {
            bookmarks.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.url.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Text(
            text = "Saved Bookmarks",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Quick access to your bookmarked pages and websites",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (bookmarks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No bookmarks saved yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap the bookmark button on any page while browsing to save it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else if (filteredBookmarks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No matching bookmarks",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No bookmarks match your search query.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredBookmarks, key = { it.url }) { bookmark ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bookmark_card_${bookmark.url.hashCode()}"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        onClick = { onNavigateToUrl(bookmark.url) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoStories,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column {
                                    Text(
                                        text = bookmark.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = bookmark.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                        maxLines = 1
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onNavigateToUrl(bookmark.url) },
                                    modifier = Modifier.testTag("open_bookmark_${bookmark.url.hashCode()}")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Launch,
                                        contentDescription = "Open bookmark",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.removeBookmark(bookmark.url) },
                                    modifier = Modifier.testTag("delete_bookmark_${bookmark.url.hashCode()}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete bookmark",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settingsManager: WebSettingsManager,
    onApplyChanges: (
        url: String,
        js: Boolean,
        forceDark: Boolean,
        distractionFree: Boolean,
        dnsProviderId: String,
        customDnsHost: String,
        inAppAdBlock: Boolean
    ) -> Unit,
    onClearCacheAndCookies: () -> Unit = {},
    onClearBrowserToHome: () -> Unit = {}
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf(settingsManager.startUrl) }
    var jsToggle by remember { mutableStateOf(settingsManager.isJsEnabled) }
    var forceDarkToggle by remember { mutableStateOf(settingsManager.isForceDarkAllowed) }
    var distractionFreeToggle by remember { mutableStateOf(settingsManager.isDistractionFreeMode) }
    var selectedDnsId by remember { mutableStateOf(settingsManager.dnsProvider) }
    var customDnsHostInput by remember { mutableStateOf(settingsManager.customDnsHost) }
    var inAppAdBlockToggle by remember { mutableStateOf(settingsManager.isInAppAdBlockingEnabled) }
    var isDnsDropdownExpanded by remember { mutableStateOf(false) }

    val selectedProvider = remember(selectedDnsId) { DnsProvider.fromId(selectedDnsId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "App Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Configure your wrapper app preferences",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "WebView Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Start URL") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (urlInput.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        urlInput = ""
                                        Toast.makeText(context, "Link removed from Start URL", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("clear_start_url_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove Start URL link",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = clipboard?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0)?.text?.toString() ?: ""
                                        if (text.isNotBlank()) {
                                            urlInput = text.trim()
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("paste_start_url_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste URL",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("url_input_field"),
                    shape = RoundedCornerShape(12.dp)
                )

                if (urlInput.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Start URL is configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = {
                                urlInput = ""
                                Toast.makeText(context, "Link removed from Start URL", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("one_click_remove_url_button"),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Remove link",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Leave empty to open the link launcher on start",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable JavaScript",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Required for interactive comic viewers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = jsToggle,
                        onCheckedChange = { jsToggle = it },
                        modifier = Modifier.testTag("js_toggle_switch")
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Force Dark Theme (WebView)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Auto-darken the viewer content to match your system preference using forceDarkAllowed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = forceDarkToggle,
                        onCheckedChange = { forceDarkToggle = it },
                        modifier = Modifier.testTag("force_dark_toggle_switch")
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Distraction-Free Mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Automatically hides top and bottom navigation bars while scrolling down to provide an immersive comic reader",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = distractionFreeToggle,
                        onCheckedChange = { distractionFreeToggle = it },
                        modifier = Modifier.testTag("distraction_free_toggle_switch")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Ads & DNS Protection Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ads_dns_settings_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Ads & DNS Protection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Select your preferred DNS resolver & ad blocking",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // In-App Ad Blocker switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "In-App Ad & Tracker Blocking",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Intercept and drop ads, popups, and intrusive tracker scripts in the browser",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = inAppAdBlockToggle,
                        onCheckedChange = { inAppAdBlockToggle = it },
                        modifier = Modifier.testTag("in_app_ad_block_switch")
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // DNS Provider Selector
                Text(
                    text = "DNS Provider Preference",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap to choose your preferred DNS server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedCard(
                        onClick = { isDnsDropdownExpanded = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dns_provider_selector"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedProvider.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = selectedProvider.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select DNS Provider",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = isDnsDropdownExpanded,
                        onDismissRequest = { isDnsDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        DnsProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = provider.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (provider.id == selectedDnsId) FontWeight.Bold else FontWeight.Normal,
                                                color = if (provider.id == selectedDnsId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (provider.blocksAds) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "Blocks Ads",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = provider.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }
                                },
                                onClick = {
                                    selectedDnsId = provider.id
                                    isDnsDropdownExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (provider.id == selectedDnsId) Icons.Default.CheckCircle else Icons.Default.Dns,
                                        contentDescription = null,
                                        tint = if (provider.id == selectedDnsId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            )
                        }
                    }
                }

                // Custom DNS input if CUSTOM is selected
                if (selectedProvider == DnsProvider.CUSTOM) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customDnsHostInput,
                        onValueChange = { customDnsHostInput = it },
                        label = { Text("Custom DNS Host / DoH Domain") },
                        placeholder = { Text("e.g. dns.my-adblocker.net or pi.hole") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_dns_input"),
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            Icon(Icons.Default.Dns, contentDescription = null)
                        },
                        trailingIcon = {
                            if (customDnsHostInput.isNotEmpty()) {
                                IconButton(onClick = { customDnsHostInput = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                }

                // DNS Details & Private DNS Configuration Guide
                if (selectedProvider.hostName.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Private DNS Hostname",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = selectedProvider.hostName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clip = ClipData.newPlainText("DNS Hostname", selectedProvider.hostName)
                                        clipboard?.setPrimaryClip(clip)
                                        Toast.makeText(context, "Copied '${selectedProvider.hostName}' for Private DNS", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("copy_dns_host_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy")
                                }
                            }

                            if (selectedProvider.ipAddress.isNotEmpty()) {
                                Text(
                                    text = "IP: ${selectedProvider.ipAddress}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Open Android Settings > Network > Private DNS", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("open_system_dns_button"),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Android System Private DNS Settings", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Privacy & Troubleshooting",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Clear WebView cache, cookies, and local website data to ensure privacy, free up storage, and fix website loading or display issues.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onClearBrowserToHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_clear_browser_to_home_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Browser & Reset to Home", fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onClearCacheAndCookies,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_clear_cache_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Cache & Cookies", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                onApplyChanges(
                    urlInput,
                    jsToggle,
                    forceDarkToggle,
                    distractionFreeToggle,
                    selectedDnsId,
                    customDnsHostInput,
                    inAppAdBlockToggle
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("save_settings_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Save & Apply Changes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HistoryScreen(
    viewModel: WebViewModel,
    searchQuery: String,
    onNavigateToUrl: (String) -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filteredHistory = remember(history, searchQuery) {
        if (searchQuery.isBlank()) {
            history
        } else {
            history.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.url.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Browsing History",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Revisit your previously viewed pages",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            if (history.isNotEmpty()) {
                IconButton(
                    onClick = {
                        viewModel.clearHistory()
                        Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear all history",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (history.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No browsing history yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Pages you visit will be automatically saved here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else if (filteredHistory.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No matching history",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No history items match your search query.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredHistory, key = { it.url }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("history_card_${item.url.hashCode()}"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        onClick = { onNavigateToUrl(item.url) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                        maxLines = 1
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onNavigateToUrl(item.url) },
                                    modifier = Modifier.testTag("open_history_${item.url.hashCode()}")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Launch,
                                        contentDescription = "Revisit page",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                 IconButton(
                                    onClick = { viewModel.removeHistory(item.url) },
                                    modifier = Modifier.testTag("delete_history_${item.url.hashCode()}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete history item",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun formatOfflineFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: WebViewModel,
    searchQuery: String,
    isDistractionFreeMode: Boolean,
    onOpenInBrowser: (OfflinePage) -> Unit = {}
) {
    val chapters by viewModel.downloadedChapters.collectAsStateWithLifecycle()
    val offlinePages by viewModel.offlinePages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    var selectedTab by remember { mutableStateOf(0) } // 0: Offline Pages, 1: Comic Chapters
    var activeChapterForReading by remember { mutableStateOf<com.example.data.DownloadedChapter?>(null) }
    var activeOfflinePageForReading by remember { mutableStateOf<OfflinePage?>(null) }
    var chapterToDelete by remember { mutableStateOf<com.example.data.DownloadedChapter?>(null) }
    var offlinePageToDelete by remember { mutableStateOf<OfflinePage?>(null) }

    val filteredChapters = remember(chapters, searchQuery) {
        if (searchQuery.isBlank()) {
            chapters
        } else {
            chapters.filter {
                it.title.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredOfflinePages = remember(offlinePages, searchQuery) {
        if (searchQuery.isBlank()) {
            offlinePages
        } else {
            offlinePages.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.url.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Intercept back button when reader is open
    BackHandler(enabled = activeChapterForReading != null || activeOfflinePageForReading != null) {
        if (activeOfflinePageForReading != null) {
            activeOfflinePageForReading = null
        } else if (activeChapterForReading != null) {
            activeChapterForReading = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp)
        ) {
            Text(
                text = "Downloads & Offline",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Read your saved HTML web pages and chapters completely offline",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Tabs: Saved Pages vs Comic Chapters
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("downloads_tab_row")
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Saved Pages (${offlinePages.size})") },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.OfflinePin,
                            contentDescription = "Saved Pages",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.testTag("tab_saved_pages")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Comic Chapters (${chapters.size})") },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "Comic Chapters",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.testTag("tab_comic_chapters")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 0) {
                // Offline HTML Pages
                if (offlinePages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.OfflinePin,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No saved offline pages yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap the 'Save for Offline' button while browsing any website to save its HTML content to local storage for reading without an internet connection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                } else if (filteredOfflinePages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No matching offline pages",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No saved offline pages match your search query.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredOfflinePages, key = { it.id }) { page ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("offline_page_card_${page.id}"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                onClick = { activeOfflinePageForReading = page }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.OfflinePin,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column {
                                            Text(
                                                text = page.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = page.url,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "Offline HTML",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = formatOfflineFileSize(page.fileSize),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { activeOfflinePageForReading = page },
                                            modifier = Modifier.testTag("read_offline_page_${page.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ChromeReaderMode,
                                                contentDescription = "Read offline",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = { onOpenInBrowser(page) },
                                            modifier = Modifier.testTag("open_offline_in_browser_${page.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInBrowser,
                                                contentDescription = "Open in browser",
                                                tint = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                        IconButton(
                                            onClick = { offlinePageToDelete = page },
                                            modifier = Modifier.testTag("delete_offline_page_${page.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete offline page",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Comic Chapters
                if (chapters.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No offline chapters yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap the download button at the bottom right corner while reading a comic chapter to save it here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                } else if (filteredChapters.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No matching chapters",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No downloaded chapters match your search query.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredChapters, key = { it.id }) { chapter ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("download_card_${chapter.id}"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                onClick = { activeChapterForReading = chapter }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Live comic thumbnail from downloaded directory
                                        val firstImageName = remember(chapter) {
                                            chapter.imageFiles.split(",").firstOrNull() ?: ""
                                        }
                                        val thumbnailFile = remember(chapter, firstImageName) {
                                            File(File(context.filesDir, "downloads/${chapter.localDirName}"), firstImageName)
                                        }

                                        AsyncImage(
                                            model = thumbnailFile,
                                            contentDescription = "Thumbnail for ${chapter.title}",
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentScale = ContentScale.Crop
                                        )

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Column {
                                            Text(
                                                text = chapter.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "${chapter.pageCount} Pages • Saved offline",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { activeChapterForReading = chapter },
                                            modifier = Modifier.testTag("read_download_${chapter.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                                contentDescription = "Read offline",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = { chapterToDelete = chapter },
                                            modifier = Modifier.testTag("delete_download_${chapter.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete download",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Overlay Offline HTML Page Reader as a full-screen cover
        if (activeOfflinePageForReading != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                OfflineHtmlReader(
                    page = activeOfflinePageForReading!!,
                    viewModel = viewModel,
                    isDistractionFreeMode = isDistractionFreeMode,
                    onClose = { activeOfflinePageForReading = null },
                    onOpenInBrowser = { page ->
                        activeOfflinePageForReading = null
                        onOpenInBrowser(page)
                    }
                )
            }
        }

        // Overlay Offline Comic Reader as a full-screen cover
        if (activeChapterForReading != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                OfflineChapterReader(
                    chapter = activeChapterForReading!!,
                    viewModel = viewModel,
                    isDistractionFreeMode = isDistractionFreeMode,
                    onClose = { activeChapterForReading = null }
                )
            }
        }
    }

    // Delete Comic Confirmation Dialog
    if (chapterToDelete != null) {
        AlertDialog(
            onDismissRequest = { chapterToDelete = null },
            title = { Text("Delete Download") },
            text = {
                Text("Are you sure you want to delete '${chapterToDelete!!.title}' and all of its cached pages? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteChapter(chapterToDelete!!)
                        Toast.makeText(context, "Chapter deleted", Toast.LENGTH_SHORT).show()
                        chapterToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("dialog_confirm_delete_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { chapterToDelete = null },
                    modifier = Modifier.testTag("dialog_cancel_delete_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Offline HTML Page Confirmation Dialog
    if (offlinePageToDelete != null) {
        AlertDialog(
            onDismissRequest = { offlinePageToDelete = null },
            title = { Text("Delete Offline Page") },
            text = {
                Text("Are you sure you want to delete '${offlinePageToDelete!!.title}' from offline storage? The local HTML content will be deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val page = offlinePageToDelete!!
                        viewModel.deleteOfflinePage(page) {
                            Toast.makeText(context, "Page removed from offline storage", Toast.LENGTH_SHORT).show()
                        }
                        offlinePageToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("dialog_confirm_delete_offline_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { offlinePageToDelete = null },
                    modifier = Modifier.testTag("dialog_cancel_delete_offline_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun OfflineHtmlReader(
    page: OfflinePage,
    viewModel: WebViewModel,
    isDistractionFreeMode: Boolean,
    onClose: () -> Unit,
    onOpenInBrowser: (OfflinePage) -> Unit
) {
    val context = LocalContext.current
    val htmlContent = remember(page) { viewModel.loadOfflineHtml(page) }
    var textZoomPercent by remember { mutableStateOf(100) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var offlineScrollProgress by remember { mutableFloatStateOf(0f) }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Reader Header Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("offline_reader_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close reader",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = page.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Offline Mode",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = formatOfflineFileSize(page.fileSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            textZoomPercent = if (textZoomPercent >= 150) 100 else textZoomPercent + 25
                        },
                        modifier = Modifier.testTag("offline_reader_zoom_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatSize,
                            contentDescription = "Adjust font size ($textZoomPercent%)",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = { onOpenInBrowser(page) },
                        modifier = Modifier.testTag("offline_reader_open_browser_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = "Open in Main Browser",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.testTag("offline_reader_info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Page info",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Visual reading scroll progress bar for offline reader
        val animatedOfflineProgress by animateFloatAsState(
            targetValue = offlineScrollProgress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 100, easing = LinearOutSlowInEasing),
            label = "offlineScrollProgress"
        )
        LinearProgressIndicator(
            progress = { animatedOfflineProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.5.dp)
                .testTag("offline_scroll_progress_bar"),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )

        // Offline Web Reader View
        AndroidView(
            factory = { ctx ->
                ObservableWebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    onScrollProgressChangedCallback = { p ->
                        offlineScrollProgress = p
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            return true
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            (view as? ObservableWebView)?.updateScrollProgress()
                        }
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        settings.forceDark = WebSettings.FORCE_DARK_AUTO
                    }
                    loadDataWithBaseURL(page.url, htmlContent, "text/html", "UTF-8", null)
                }
            },
            update = { wv ->
                wv.settings.textZoom = textZoomPercent
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("offline_webview")
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Offline Page Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Title: ${page.title}", fontWeight = FontWeight.SemiBold)
                    Text("Original URL: ${page.url}", style = MaterialTheme.typography.bodySmall)
                    Text("Size on disk: ${formatOfflineFileSize(page.fileSize)}")
                    Text("Local file: ${page.localFileName}", style = MaterialTheme.typography.labelSmall)
                    Text("Reading without internet connection enabled.", color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}


@Composable
fun OfflineChapterReader(
    chapter: com.example.data.DownloadedChapter,
    viewModel: WebViewModel,
    isDistractionFreeMode: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val pages = remember(chapter) { chapter.imageFiles.split(",") }
    val chapterDir = remember(chapter) { File(context.filesDir, "downloads/${chapter.localDirName}") }
    
    val listState = rememberLazyListState()

    var isOverlayVisible by remember { mutableStateOf(true) }
    var previousIndex by remember { mutableStateOf(0) }
    var previousOffset by remember { mutableStateOf(0) }

    val readingProgress = remember(pages) {
        derivedStateOf {
            if (pages.isEmpty()) 0f
            else {
                val visibleIndex = listState.firstVisibleItemIndex
                val totalPages = pages.size
                if (visibleIndex == 0) {
                    0f
                } else if (visibleIndex >= totalPages + 1) {
                    1f
                } else {
                    val pageIndex = visibleIndex - 1
                    val firstVisibleItemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == visibleIndex }
                    val itemSize = firstVisibleItemInfo?.size ?: 1
                    val offsetFraction = (listState.firstVisibleItemScrollOffset.toFloat() / itemSize.toFloat()).coerceIn(0f, 1f)
                    ((pageIndex.toFloat() + offsetFraction) / totalPages.toFloat()).coerceIn(0f, 1f)
                }
            }
        }
    }

    // Load saved scroll position on start
    LaunchedEffect(chapter) {
        val saved = viewModel.getScrollPosition("offline_${chapter.id}")
        if (saved != null) {
            try {
                listState.scrollToItem(saved.scrollIndex, saved.scrollY)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Auto-save scroll position when scrolling and track scroll direction
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val currentIndex = listState.firstVisibleItemIndex
        val currentOffset = listState.firstVisibleItemScrollOffset

        viewModel.saveScrollPosition(
            id = "offline_${chapter.id}",
            scrollY = currentOffset,
            scrollIndex = currentIndex
        )

        if (isDistractionFreeMode) {
            if (currentIndex > previousIndex || (currentIndex == previousIndex && currentOffset > previousOffset)) {
                // Scrolling down
                if (currentIndex > 0 || currentOffset > 50) {
                    if (isOverlayVisible) isOverlayVisible = false
                }
            } else if (currentIndex < previousIndex || (currentIndex == previousIndex && currentOffset < previousOffset)) {
                // Scrolling up
                if (!isOverlayVisible) isOverlayVisible = true
            }
            if (currentIndex == 0 && currentOffset <= 5) {
                if (!isOverlayVisible) isOverlayVisible = true
            }
        } else {
            if (!isOverlayVisible) isOverlayVisible = true
        }

        previousIndex = currentIndex
        previousOffset = currentOffset
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                isOverlayVisible = !isOverlayVisible
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(64.dp))
            }

            itemsIndexed(pages) { index, pageFile ->
                val file = File(chapterDir, pageFile)
                AsyncImage(
                    model = file,
                    contentDescription = "Page ${index + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(bottom = 8.dp),
                    contentScale = ContentScale.FillWidth
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "End of Chapter",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Back to Gallery")
                    }
                }
            }
        }

        // Top Overlay Bar
        androidx.compose.animation.AnimatedVisibility(
            visible = isOverlayVisible,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = chapter.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 240.dp)
                        )
                        Text(
                            text = "${pages.size} pages • Offline Reader",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "Offline Mode",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        // Bottom Overlay Bar showing progress & pages
        androidx.compose.animation.AnimatedVisibility(
            visible = isOverlayVisible,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
                    .testTag("bottom_overlay_bar")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentPage = if (listState.firstVisibleItemIndex == 0) 1 
                                      else (listState.firstVisibleItemIndex).coerceAtMost(pages.size)
                    Text(
                        text = "Page $currentPage of ${pages.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("chapter_page_text")
                    )
                    
                    Text(
                        text = "${(readingProgress.value * 100).toInt()}% read",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("chapter_progress_text")
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { readingProgress.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .testTag("chapter_progress_bar"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}

class ObservableWebView(context: Context) : WebView(context), NestedScrollingChild3 {
    var onScrollChangedCallback: ((Int, Int) -> Unit)? = null
    var onScrollProgressChangedCallback: ((Float) -> Unit)? = null
    var onScrollDirectionChanged: ((isDown: Boolean) -> Unit)? = null

    private val childHelper = NestedScrollingChildHelper(this).apply {
        isNestedScrollingEnabled = true
    }

    private var lastY = 0f
    private val scrollConsumed = IntArray(2)
    private val scrollOffset = IntArray(2)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = false
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.rawY
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
                handled = super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val curY = event.rawY
                val deltaY = (lastY - curY).toInt()
                lastY = curY

                // Detect user scrolling direction for distraction-free auto-hide
                if (deltaY > 12 && scrollY > 40) {
                    onScrollDirectionChanged?.invoke(true)
                } else if (deltaY < -12 || scrollY <= 15) {
                    onScrollDirectionChanged?.invoke(false)
                }

                // If scrolled to top and pulling down, or moving up while in nested scroll
                if (dispatchNestedPreScroll(0, deltaY, scrollConsumed, scrollOffset, ViewCompat.TYPE_TOUCH)) {
                    val unconsumedY = deltaY - scrollConsumed[1]
                    if (unconsumedY != 0) {
                        dispatchNestedScroll(0, scrollConsumed[1], 0, unconsumedY, scrollOffset, ViewCompat.TYPE_TOUCH)
                    }
                    handled = true
                } else {
                    handled = super.onTouchEvent(event)
                    val unconsumedY = deltaY
                    dispatchNestedScroll(0, 0, 0, unconsumedY, scrollOffset, ViewCompat.TYPE_TOUCH)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopNestedScroll(ViewCompat.TYPE_TOUCH)
                handled = super.onTouchEvent(event)
            }
            else -> {
                handled = super.onTouchEvent(event)
            }
        }
        return handled
    }

    // NestedScrollingChild3 implementation delegates to childHelper
    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray
    ) {
        childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed)
    }

    override fun startNestedScroll(axes: Int, type: Int): Boolean {
        return childHelper.startNestedScroll(axes, type)
    }

    override fun stopNestedScroll(type: Int) {
        childHelper.stopNestedScroll(type)
    }

    override fun hasNestedScrollingParent(type: Int): Boolean {
        return childHelper.hasNestedScrollingParent(type)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type)
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean {
        return childHelper.isNestedScrollingEnabled
    }

    override fun startNestedScroll(axes: Int): Boolean {
        return startNestedScroll(axes, ViewCompat.TYPE_TOUCH)
    }

    override fun stopNestedScroll() {
        stopNestedScroll(ViewCompat.TYPE_TOUCH)
    }

    override fun hasNestedScrollingParent(): Boolean {
        return hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ): Boolean {
        return dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, ViewCompat.TYPE_TOUCH)
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?
    ): Boolean {
        return dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, ViewCompat.TYPE_TOUCH)
    }

    override fun dispatchNestedFling(
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean
    ): Boolean {
        return childHelper.dispatchNestedFling(velocityX, velocityY, consumed)
    }

    override fun dispatchNestedPreFling(
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        return childHelper.dispatchNestedPreFling(velocityX, velocityY)
    }

    fun computeScrollProgress(): Float {
        val range = computeVerticalScrollRange() - computeVerticalScrollExtent()
        if (range <= 0) return 0f
        val offset = computeVerticalScrollOffset()
        return (offset.toFloat() / range.toFloat()).coerceIn(0f, 1f)
    }

    fun updateScrollProgress() {
        val progress = computeScrollProgress()
        onScrollProgressChangedCallback?.invoke(progress)
    }

    public override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedCallback?.invoke(t, oldt)
        val delta = t - oldt
        if (delta > 8 && t > 40) {
            onScrollDirectionChanged?.invoke(true)
        } else if (delta < -8 || t <= 15) {
            onScrollDirectionChanged?.invoke(false)
        }
        val progress = computeScrollProgress()
        onScrollProgressChangedCallback?.invoke(progress)
    }
}
