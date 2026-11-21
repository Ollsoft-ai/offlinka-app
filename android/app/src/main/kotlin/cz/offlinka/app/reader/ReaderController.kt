package cz.offlinka.app.reader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cz.offlinka.app.drive.Shelf
import cz.offlinka.ui.screens.FindState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the WebView currently shows. */
data class PageInfo(
    val url: String? = null,
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** 0..100 while loading, null when idle. */
    val progress: Int? = null,
    /** The main frame answered 404: do not record or offer saving. */
    val notFound: Boolean = false,
    /** Bumped every time a page finishes, so observers can record history once per visit. */
    val visit: Int = 0,
    /** The article's own h1 scrolled away: the top bar shows the title instead of the book. */
    val titleInBar: Boolean = false,
)

sealed interface ReaderEvent {
    data class External(val url: String) : ReaderEvent
    data object CannotOpenFile : ReaderEvent
}

/**
 * Owns the reader's single WebView for the lifetime of the activity, so its back/forward history
 * survives moving between screens. Everything the page loads comes from [server].
 */
class ReaderController(
    private val context: Context,
    private val server: ContentServer,
    private val shelf: Shelf,
    private val scope: CoroutineScope,
    /** Lives in the app graph, so view models keep following the page after the activity is recreated. */
    private val _page: MutableStateFlow<PageInfo> = MutableStateFlow(PageInfo()),
    /** Keeps the WebView's back/forward list across activity recreation in memory (never in the parcelled bundle). */
    private val retained: RetainedReaderState = RetainedReaderState(),
) {
    private val recovery = PageRecovery()
    private var fontScale = context.resources.configuration.fontScale
    val page: StateFlow<PageInfo> = _page.asStateFlow()

    private val _find = MutableStateFlow<FindState?>(null)
    val find: StateFlow<FindState?> = _find.asStateFlow()

    private val _events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()

    private var clearHistoryOnLoad = false
    private var web: WebView? = null
    private var pendingState: Bundle? = null

    val webView: WebView
        get() = web ?: create().also { web = it }

    /** Follows the shelf; cancelled in [destroy] so a finished activity is never kept alive by it. */
    internal val stateJob: Job = scope.launch {
        // When the drive's books open (again), apply a deferred restore; reload a page shown while its book
        // was not served yet as soon as that book is there (also when it opens later than the first ones).
        shelf.state.collect { s ->
            val r = s as? cz.offlinka.app.drive.ShelfState.Ready ?: return@collect
            val w = web ?: return@collect
            val pending = pendingState
            val url = w.url
            if (pending != null) {
                pendingState = null
                w.restoreState(pending)
            } else if (recovery.shouldReload(r.byId.keys) ||
                (url != null && server.wasUnavailable(url) && bookOf(url) in r.byId.keys)
            ) {
                w.reload()
            }
        }
    }

    /** Book of a reader URL, or null for other pages. */
    private fun bookOf(url: String?): String? = url?.let { ReaderUrls.parse(it) }?.bookId

    private fun served(bookId: String?): Boolean = bookId != null && shelf.servedBooks().containsKey(bookId)

    /** Loads [url]. [fresh] starts a new reading session: back then leaves the reader instead of going to older pages. */
    fun load(url: String, fresh: Boolean) {
        val w = webView
        if (w.url == url) {
            if (fresh) w.clearHistory()
            return
        }
        clearHistoryOnLoad = fresh
        _page.value = _page.value.copy(url = url, title = "", progress = 0, notFound = false)
        w.loadUrl(url)
    }

    fun hasPage(): Boolean = web?.url != null || pendingState != null || retained.bundle != null

    fun back() { web?.takeIf { it.canGoBack() }?.goBack() }

    fun forward() { web?.takeIf { it.canGoForward() }?.goForward() }

    private var zoom = 100
    private var dark = false

    fun setTextZoom(percent: Int) {
        zoom = percent
        applyLook()
    }

    fun setDark(dark: Boolean) {
        this.dark = dark
        applyLook()
    }

    /** MediaWiki pages scale through the reader stylesheet; everything else through WebView text zoom. */
    private fun isWikiPage(url: String?): Boolean {
        val t = url?.let { ReaderUrls.parse(it) } ?: return false
        return shelf.ready?.byId?.get(t.bookId)?.isWiki == true
    }

    /** The system font scale changed (Settings → Display): the reader follows it. */
    fun setFontScale(scale: Float) {
        fontScale = scale
        applyLook()
    }

    private fun applyLook(url: String? = web?.url) {
        val w = web ?: return
        val wiki = isWikiPage(url)
        w.settings.textZoom = if (wiki) 100 else ReaderZoom.textZoom(zoom, fontScale)
        if (wiki) w.evaluateJavascript(HtmlInjector.lookScript(ReaderLook(dark, ReaderZoom.cssScale(zoom, fontScale))), null)
    }

    /** Headings of the article for the "Obsah" sheet: (anchor id, text, level 2..3). */
    fun contents(onResult: (List<Heading>) -> Unit) {
        val w = web ?: return onResult(emptyList())
        w.evaluateJavascript(CONTENTS_SCRIPT) { json -> onResult(Heading.parse(json)) }
    }

    fun scrollTo(anchor: String) {
        val id = anchor.replace("\\", "\\\\").replace("'", "\\'")
        web?.evaluateJavascript("(function(){var e=document.getElementById('$id');if(e)e.scrollIntoView();})()", null)
    }

    fun openFind() {
        _find.value = FindState()
    }

    fun findQuery(q: String) {
        _find.value = FindState(query = q)
        val w = web ?: return
        if (q.isEmpty()) w.clearMatches() else w.findAllAsync(q)
    }

    fun findNext(forward: Boolean) {
        web?.findNext(forward)
    }

    fun closeFind() {
        web?.clearMatches()
        _find.value = null
    }

    /**
     * Stops JavaScript timers (games, maps) and media while the app is in the background. pauseTimers()
     * acts on every WebView of the process, so the paused state is process-wide ([timersPaused]): a WebView
     * created later by a recreated activity resumes them.
     */
    fun pause() {
        web?.let {
            it.onPause()
            it.pauseTimers()
            timersPaused = true
        }
    }

    fun resume() {
        web?.let {
            it.resumeTimers()
            timersPaused = false
            it.onResume()
        }
    }

    /**
     * The connect cover hides the reader: TalkBack must not read the WebView (a classic View the Compose
     * semantics cannot clear) underneath.
     */
    fun setCovered(covered: Boolean) {
        this.covered = covered
        web?.importantForAccessibility =
            if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private var covered = false

    /** Last time the user touched the page; downloads and phone/mail links need a fresh touch. */
    @Volatile private var lastTouch = 0L

    /**
     * Activity is going away. The back/forward list stays in memory ([retained]); the parcelled bundle
     * only gets the current URL, so a long history never hits TransactionTooLargeException.
     */
    fun saveState(out: Bundle) {
        val w = web ?: return
        retained.bundle = Bundle().also { w.saveState(it) }
        w.url?.let { out.putString(URL_KEY, it) }
    }

    /** New activity: restore the in-memory history, else (process was killed) reopen the last URL. */
    fun restoreState(saved: Bundle?) {
        val kept = retained.bundle
        retained.bundle = null
        if (kept != null) {
            pendingState = kept
        } else {
            saved?.getString(URL_KEY)?.let { url -> pendingUrl = url }
        }
    }

    private var pendingUrl: String? = null
    private var lastAutoReload = 0L

    /** Takes the WebView out of its old parent so a new screen can host it. */
    fun detachedWebView(): WebView = webView.also { (it.parent as? ViewGroup)?.removeView(it) }

    fun destroy() {
        stateJob.cancel()
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        web = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val w = WebView(context)
        w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.settings.apply {
            javaScriptEnabled = true // maps and games need it
            domStorageEnabled = true // game scores, map viewer state
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        noAlgorithmicDarkening(w)
        w.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        w.webViewClient = Client()
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _page.value = _page.value.copy(progress = if (newProgress >= 100) null else newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank() && !title.startsWith("https://")) _page.value = _page.value.copy(title = title)
            }
        }
        w.setFindListener { active, count, done ->
            val f = _find.value ?: return@setFindListener
            _find.value = f.copy(current = if (count == 0) 0 else active + 1, total = count, done = done)
        }
        // A page may not hand files to other apps on its own: only right after the user touched it.
        w.setDownloadListener { url, _, _, mime, _ -> if (LinkPolicy.userInitiated(lastTouch, SystemClock.uptimeMillis())) openFile(url, mime) }
        @Suppress("ClickableViewAccessibility")
        w.setOnTouchListener { _, _ ->
            lastTouch = SystemClock.uptimeMillis()
            false
        }
        if (timersPaused) {
            // The previous activity paused the process' timers; this WebView would otherwise start frozen.
            w.resumeTimers()
            timersPaused = false
        }
        if (covered) w.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(w, "offlinkaApp", setOf(ReaderUrls.ORIGIN)) { _, message, _, isMainFrame, _ ->
                    if (isMainFrame) _page.value = _page.value.copy(titleInBar = message.data == "1")
                }
            }
        }
        interceptServiceWorkers()
        // Restore only with the books open; otherwise every restored page would be a 503 (see init).
        if (shelf.ready != null) {
            pendingState?.let { w.restoreState(it) }
            pendingState = null
        }
        pendingUrl?.let { url ->
            pendingUrl = null
            if (pendingState == null) {
                recovery.onPageStarted(bookOf(url), served(bookOf(url)))
                w.loadUrl(url)
            }
        }
        return w
    }

    /**
     * All books share one origin, so a service worker registered by any page would answer pages of every
     * book. None of our books needs one: every service-worker fetch (its script included) is refused, which
     * also makes registering fail; pages additionally get a script that unregisters old ones.
     */
    private fun interceptServiceWorkers() {
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
            ) {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse {
                        val s = server.serveServiceWorker(request.url.toString())
                        return WebResourceResponse(s.mime, s.charset, s.status, s.reason, s.headers, s.body)
                    }
                })
            }
        }
    }

    /** Our pages bring their own dark styles; never let the WebView invert maps or games. */
    private fun noAlgorithmicDarkening(w: WebView) {
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, false)
            }
        }
    }

    private fun updateNav(view: WebView) {
        _page.value = _page.value.copy(url = view.url, canGoBack = view.canGoBack(), canGoForward = view.canGoForward())
    }

    private inner class Client : WebViewClientCompat() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val s = server.serve(request.url.toString(), request.method, request.requestHeaders ?: emptyMap(), request.isForMainFrame)
            return WebResourceResponse(s.mime, s.charset, s.status, s.reason, s.headers, s.body)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (ReaderUrls.isOurs(url)) return false
            when (request.url.scheme?.lowercase()) {
                // Other apps open only when the user tapped the link, never from a page's own script.
                "tel" -> return if (request.hasGesture()) startSystem(Intent(Intent.ACTION_DIAL, request.url)) else true
                "mailto", "sms", "smsto" -> return if (request.hasGesture()) startSystem(Intent(Intent.ACTION_SENDTO, request.url)) else true
                "javascript", "about", "data", "blob" -> return false
            }
            val wiki = ReaderUrls.wikimediaTarget(url)
            if (wiki != null) {
                scope.launch {
                    val target = withContext(Dispatchers.IO) { resolveOffline(wiki.first, wiki.second) }
                    if (target != null) view.loadUrl(target) else _events.tryEmit(ReaderEvent.External(url))
                }
                return true
            }
            _events.tryEmit(ReaderEvent.External(url))
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            recovery.onPageStarted(bookOf(url), served(bookOf(url)))
            view.settings.textZoom = if (isWikiPage(url)) 100 else ReaderZoom.textZoom(zoom, fontScale)
            _page.value = _page.value.copy(url = url, notFound = false, progress = 0, titleInBar = false)
            updateNav(view)
            if (_find.value != null) closeFind()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (clearHistoryOnLoad && url != null && ReaderUrls.isOurs(url)) {
                clearHistoryOnLoad = false
                view.clearHistory()
            }
            updateNav(view)
            // Answered "come back later" but the book is served by now (it opened while the page loaded): again.
            if (url != null && server.wasUnavailable(url) && served(bookOf(url)) && System.currentTimeMillis() - lastAutoReload > 5_000) {
                lastAutoReload = System.currentTimeMillis()
                view.postDelayed({ view.reload() }, 300)
            }
            // A redirect stub replaces itself at once: no history row for it.
            val stub = url != null && server.wasRedirect(url)
            _page.value = _page.value.copy(
                title = view.title?.takeUnless { it.isBlank() || it.startsWith("https://") } ?: _page.value.title,
                progress = null,
                visit = if (stub) _page.value.visit else _page.value.visit + 1,
            )
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            updateNav(view)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (!request.isForMainFrame) return
            val book = bookOf(request.url.toString())
            recovery.onMainFrameStatus(book, errorResponse.statusCode)
            // A 503 while the book is served (a rescan swapped it under the request): try once more.
            if (errorResponse.statusCode == 503 && served(book) && System.currentTimeMillis() - lastAutoReload > 5_000) {
                lastAutoReload = System.currentTimeMillis()
                recovery.shouldReload(setOfNotNull(book))
                view.postDelayed({ view.reload() }, 300)
            }
            if (errorResponse.statusCode == 404 || errorResponse.statusCode == 503) _page.value = _page.value.copy(notFound = true)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
            Log.i(TAG, "load error ${request.url}")
        }
    }

    /** First of [books] that has [title]; the offline URL of it. */
    private fun resolveOffline(books: List<String>, title: String): String? {
        val served = shelf.servedBooks()
        for (id in books) {
            val book = served[id] ?: continue
            val found = runCatching { book.content.get(title) }.getOrNull() ?: continue
            return ReaderUrls.article(id, found.path)
        }
        return null
    }

    private fun startSystem(intent: Intent): Boolean {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            _events.tryEmit(ReaderEvent.CannotOpenFile)
        }
        return true
    }

    /** PDFs and other files the WebView cannot show: copied to the app cache (never the drive) and handed to a viewer. */
    private fun openFile(url: String, mime: String?) {
        val target = ReaderUrls.parse(url) ?: return
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val book = shelf.servedBooks()[target.bookId] ?: return@runCatching null
                    val res = book.content.get(target.path)
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, target.path.substringAfterLast('/').ifBlank { "soubor" }).apply { writeBytes(res.data) }
                }.getOrNull()
            }
            if (file == null) {
                _events.tryEmit(ReaderEvent.CannotOpenFile)
                return@launch
            }
            val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: ContentServer.mimeOf(file.name) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                _events.tryEmit(ReaderEvent.CannotOpenFile)
            }
        }
    }

    companion object {
        private const val TAG = "OfflinkaReader"

        /** pauseTimers() was called and not undone: process-wide, like the WebView timers themselves. */
        @Volatile
        internal var timersPaused = false

        private const val URL_KEY = "offlinka.reader.url"

        /** Collects h2/h3 of the article (giving each an id if it has none) as JSON. */
        private const val CONTENTS_SCRIPT = "(function(){var out=[];var n=0;" +
            "document.querySelectorAll('h2,h3').forEach(function(h){var t=(h.textContent||'').trim();if(!t)return;" +
            "if(!h.id)h.id='ofl-h-'+(n++);out.push({id:h.id,text:t.substring(0,120),level:h.tagName=='H2'?2:3});});" +
            "return JSON.stringify(out);})()"
    }
}
