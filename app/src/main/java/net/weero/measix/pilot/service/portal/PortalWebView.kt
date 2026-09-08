package net.weero.measix.pilot.service.portal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.webkit.*
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.EnterpriseSynchronizationService

/** Immutable verified bytes. An unlisted request is denied locally and can never fall through to the network. */
internal class PortalAssets private constructor(private val files: Map<String, ByteArray>) {
    fun response(path: String): WebResourceResponse? = files[path]?.let { bytes ->
        val mime = when (if (path == "/portal/") "html" else path.substringAfterLast('.')) {
            "html" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
        WebResourceResponse(mime, "UTF-8", 200, "OK", HEADERS, ByteArrayInputStream(bytes))
    }

    companion object {
        private val HEADERS = mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src blob:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
        )
        suspend fun load(context: Context): PortalAssets = withContext(Dispatchers.IO) {
            val identity = context.assets.open("enterprise_portal/build-identity.json").use {
                Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject
            }
            if (identity["bridgeVersion"]?.jsonPrimitive?.int != PortalProtocol.VERSION ||
                identity["localReadVersion"]?.jsonPrimitive?.int != PortalProtocol.LOCAL_READ_VERSION ||
                identity["sourceKind"]?.jsonPrimitive?.content != "local" ||
                identity["origin"]?.jsonPrimitive?.content != PortalProtocol.LOCAL_ORIGIN) throw PortalFailure("source_unavailable")
            val assets = identity.getValue("assets").jsonObject
            if ("index.html" !in assets) throw PortalFailure("source_unavailable")
            val files = assets.map { (path, hash) ->
                if (path.startsWith('/') || '\\' in path || ':' in path || path.split('/').any { it in setOf("", ".", "..") }) {
                    throw PortalFailure("source_unavailable")
                }
                val bytes = context.assets.open("enterprise_portal/$path").use { it.readBytes() }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (digest != hash.jsonPrimitive.content) throw PortalFailure("source_unavailable")
                (if (path == "index.html") "/portal/" else "/portal/$path") to bytes
            }.toMap()
            PortalAssets(files)
        }
    }
}

/** A WebView is never reused for a second authorized document, including same-origin reloads. */
internal class PortalWebView private constructor(
    val view: WebView,
    val document: PortalDocument,
    private val assets: PortalAssets,
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val entryRequested = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var initialNavigation = true

    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> document.close(PortalCloseReason.DOCUMENT_REPLACED) }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Fragment changes stay in the approved document; no other navigation can replace it.
                val url = request.url
                if (request.isForMainFrame && url.buildUpon().fragment(null).build().toString() == PortalProtocol.LOCAL_ENTRY &&
                    url.fragment != null) return false
                if (request.isForMainFrame) document.close(PortalCloseReason.DOCUMENT_REPLACED)
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (!active.get()) return denied()
                val uri = request.url
                if (request.method != "GET" || uri.scheme != "https" || uri.encodedAuthority != "local.measix.invalid" ||
                    uri.query != null || uri.encodedPath != uri.path) return denied()
                if (request.isForMainFrame && (uri.path != "/portal/" || !entryRequested.compareAndSet(false, true))) {
                    revokeFromIo()
                    return denied()
                }
                if (!request.isForMainFrame && uri.path == "/portal/") return denied()
                return assets.response(uri.path.orEmpty()) ?: denied()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) document.close(PortalCloseReason.HOST_FAILURE)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                document.close(PortalCloseReason.HOST_FAILURE)
                return true
            }
        }
        WebViewCompat.addNavigationListener(view, { task ->
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
        }, object : NavigationListener {
            override fun onNavigationStarted(navigation: Navigation) {
                if (navigation.isSameDocument) return
                if (initialNavigation && navigation.url == PortalProtocol.LOCAL_ENTRY && !navigation.wasInitiatedByPage()) {
                    initialNavigation = false
                } else document.close(PortalCloseReason.DOCUMENT_REPLACED)
            }
            override fun onNavigationRedirected(navigation: Navigation) { document.close(PortalCloseReason.DOCUMENT_REPLACED) }
        })
        val origins = setOf(PortalProtocol.LOCAL_ORIGIN)
        WebViewCompat.addWebMessageListener(view, "MeasixHost", origins) { _, message, sourceOrigin, isMainFrame, originalReply ->
            if (active.get() && message.type == WebMessageCompat.TYPE_STRING) {
                message.data?.let { document.receive(it, sourceOrigin.toString(), isMainFrame, originalReply::postMessage) }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(view,
            "if(window===window.top){Object.defineProperty(window,'MeasixPortalDocument',{value:Object.freeze(${document.bootstrap}),writable:false,configurable:false});}", origins)
        view.loadUrl(PortalProtocol.LOCAL_ENTRY)
    }

    private fun revokeFromIo() {
        active.set(false)
        main.post { document.close(PortalCloseReason.DOCUMENT_REPLACED) }
    }

    override fun close() = document.close()

    private val viewTeardown by lazy {
        mutableListOf<() -> Unit>(
            { view.stopLoading() },
            { WebViewCompat.removeWebMessageListener(view, "MeasixHost") },
            { view.clearHistory() },
            { (view.parent as? android.view.ViewGroup)?.removeView(view) },
        )
    }
    private var browserCleanup: CompletableDeferred<Unit>? = null
    private var destroyed = false

    private fun destroy(): Deferred<Unit> {
        active.set(false)
        var failure = attemptTeardown(viewTeardown)
        // WebView methods cannot be retried after destroy, which also requires a detached view.
        if (viewTeardown.isEmpty() && !destroyed) {
            try { view.destroy(); destroyed = true }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (failure == null) failure = error else if (error !== failure) failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
        return browserCleanup?.takeUnless { it.isCancelled } ?: CompletableDeferred<Unit>().also { pending ->
            browserCleanup = pending
            try {
                WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), PortalProtocol.LOCAL_ORIGIN) {
                    pending.complete(Unit)
                }
            } catch (error: Exception) {
                pending.completeExceptionally(error)
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
    }

    private fun attemptTeardown(steps: MutableList<() -> Unit>): Exception? {
        var failure: Exception? = null
        val remaining = steps.iterator()
        while (remaining.hasNext()) {
            try { remaining.next().invoke(); remaining.remove() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (failure == null) failure = error else if (error !== failure) failure?.addSuppressed(error)
            }
        }
        return failure
    }

    companion object {
        fun supported(): Boolean = listOf(WebViewFeature.WEB_MESSAGE_LISTENER, WebViewFeature.DOCUMENT_START_SCRIPT,
            WebViewFeature.NAVIGATION_LISTENER, WebViewFeature.DELETE_BROWSING_DATA).all(WebViewFeature::isFeatureSupported)

        suspend fun open(context: Context, selection: RealmSelection, sessions: EnterpriseSessionController,
            synchronization: EnterpriseSynchronizationService, scope: CoroutineScope,
            registry: PortalDocumentRegistry, onClosed: (PortalClosure) -> Unit): PortalWebView {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!supported()) throw PortalFailure("source_unavailable")
            val assets = PortalAssets.load(context)
            var host: PortalWebView? = null
            val document = PortalDocument.open(selection, sessions, synchronization, scope, registry,
                closeHost = { host?.destroy() ?: CompletableDeferred(Unit) }, onClosed = onClosed)
            try {
                if (document.isClosed) throw kotlinx.coroutines.CancellationException("Portal document unavailable")
                return PortalWebView(WebView(context), document, assets).also {
                    host = it
                    it.load()
                }
            } catch (failure: Exception) {
                try {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                        document.close()
                        document.awaitClosed()
                    }
                }
                catch (cleanup: Exception) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }
}
