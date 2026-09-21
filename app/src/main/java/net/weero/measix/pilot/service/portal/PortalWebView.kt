package net.weero.measix.pilot.service.portal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.webkit.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.EnterpriseSynchronizationService
import kotlin.coroutines.resume

internal class PortalHostUnavailable(val provider: String, val missing: List<String>) :
    IllegalStateException("Required WebView features unavailable: ${missing.joinToString()}")

internal enum class PortalDestination { HOME, USAGE }

internal class PortalPageSource(
    val grant: net.weero.measix.pilot.data.enterprise.PlatformPortalGrant,
    destination: PortalDestination = PortalDestination.HOME,
) {
    private val requestDetail = Regex("^/api/portal/v1/usage/requests/req_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val updateDetail = Regex("^/api/client/v1/enterprise/updates/eup_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val exchange = try {
        URI(grant.exchangeUrl)
    } catch (_: Exception) {
        throw EnterpriseConfigurationException("invalid_platform_portal_exchange_url")
    }
    val origin = "${exchange.scheme}://${exchange.rawAuthority}"
    val entry = "$origin/portal/"
    val initialLocationScript = when (destination) {
        PortalDestination.HOME -> ""
        PortalDestination.USAGE -> "history.replaceState(null,'','/portal/?view=usage');"
    }

    init {
        if (exchange.scheme !in setOf("http", "https") || exchange.rawAuthority == null ||
            exchange.rawUserInfo != null ||
            exchange.rawQuery != null || exchange.rawFragment != null ||
            exchange.rawPath != "/portal/session/exchange") {
            throw EnterpriseConfigurationException("invalid_platform_portal_exchange_url")
        }
    }

    fun permitsSubresource(method: String, path: String): Boolean =
        (path == "/api/portal/v1/session" && method in setOf("GET", "DELETE")) ||
            (method == "GET" && path in setOf(
                "/api/portal/v1/budgets",
                "/api/portal/v1/usage/summary",
                "/api/portal/v1/usage/trend",
                "/api/portal/v1/usage/distribution",
                "/api/portal/v1/usage/requests",
            )) ||
            (method == "GET" && requestDetail.matches(path)) ||
            (method == "GET" && (path == "/api/client/v1/enterprise/updates" || updateDetail.matches(path)))
}

/** A WebView is never reused for a second authorized document, including same-origin reloads. */
internal class PortalWebView private constructor(
    val view: WebView,
    val document: PortalDocument,
    private val source: PortalPageSource,
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val main = Handler(Looper.getMainLooper())
    private var navigationPhase = 0
    private val binding = PortalPageBinding()

    @SuppressLint("SetJavaScriptEnabled")
    private fun load() {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkLoads = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> document.close(PortalCloseReason.DOCUMENT_REPLACED) }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                val accepted = when {
                    navigationPhase == 0 && url == source.grant.exchangeUrl -> true
                    navigationPhase <= 1 && withoutFragment(url) == source.entry -> true
                    navigationPhase == 2 && withoutFragment(url) == source.entry && android.net.Uri.parse(url).fragment != null -> true
                    else -> false
                }
                if (!accepted) document.close(PortalCloseReason.DOCUMENT_REPLACED)
                else if (withoutFragment(url) == source.entry) navigationPhase = 2 else navigationPhase = 1
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Fragment changes stay in the approved document; no other navigation can replace it.
                val url = request.url
                if (request.isForMainFrame && url.buildUpon().fragment(null).build().toString() == source.entry &&
                    (navigationPhase < 2 || url.fragment != null)) return false
                if (request.isForMainFrame) document.close(PortalCloseReason.DOCUMENT_REPLACED)
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!active.get()) return denied()
                val uri = request.url
                if (uri.encodedPath != uri.path) return denied()
                if (uri.scheme + "://" + uri.encodedAuthority != source.origin) return denied()
                val path = uri.path.orEmpty()
                val allowed = if (request.isForMainFrame) {
                    (request.method == "POST" && path == "/portal/session/exchange" && navigationPhase <= 1) ||
                        (request.method == "GET" && path == "/portal/" && navigationPhase <= 2)
                } else {
                    (request.method == "GET" && path.startsWith("/portal/") && path != "/portal/") ||
                        (source.permitsSubresource(request.method, path) &&
                            (path != "/api/portal/v1/session" || uri.query == null))
                }
                return if (allowed) null else denied()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    android.util.Log.e("EnterprisePortal", "Main-frame load failed: ${request.url} (${error.errorCode}: ${error.description})")
                    document.close(PortalCloseReason.HOST_FAILURE)
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    android.util.Log.e("EnterprisePortal", "Main-frame HTTP failure: ${request.url} (${errorResponse.statusCode} ${errorResponse.reasonPhrase})")
                    document.close(PortalCloseReason.HOST_FAILURE)
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                android.util.Log.e("EnterprisePortal", "Portal renderer exited (crashed=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()})")
                document.close(PortalCloseReason.HOST_FAILURE)
                return true
            }
        }
        val origins = setOf(source.origin)
        WebViewCompat.addWebMessageListener(view, "MeasixPortalTransport", origins) { _, message, sourceOrigin, isMainFrame, originalReply ->
            if (active.get() && isMainFrame && sourceOrigin.toString() == source.origin &&
                message.type == WebMessageCompat.TYPE_STRING) {
                val payload = try { message.data?.let(binding::receive) }
                catch (_: Exception) { document.close(PortalCloseReason.DOCUMENT_REPLACED); null }
                payload?.let { document.receive(it, sourceOrigin.toString(), true, originalReply::postMessage) }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(view,
            """
            if(window===window.top&&location.pathname==='/portal/'){
              (()=>{
                ${source.initialLocationScript}
                const transport=window.MeasixPortalTransport;
                const instance=Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');
                const send=payload=>transport.postMessage(JSON.stringify({instance,payload}));
                send(null);
                const host={postMessage:payload=>send(payload),
                  addEventListener:(...args)=>transport.addEventListener(...args),
                  removeEventListener:(...args)=>transport.removeEventListener(...args)};
                Object.defineProperty(host,'onmessage',{get:()=>transport.onmessage,set:value=>{transport.onmessage=value;}});
                Object.defineProperty(window,'MeasixHost',{value:Object.freeze(host),writable:false,configurable:false});
                Object.defineProperty(window,'MeasixPortalDocument',{value:Object.freeze(${document.bootstrap}),writable:false,configurable:false});
              })();
            }
            """.trimIndent(), origins)
        view.postUrl(source.grant.exchangeUrl,
            ("ticket=" + URLEncoder.encode(source.grant.ticket, Charsets.UTF_8.name())).toByteArray(Charsets.UTF_8))
    }

    private fun withoutFragment(url: String): String = android.net.Uri.parse(url).buildUpon().fragment(null).build().toString()

    override fun close() = document.close()

    private val viewTeardown by lazy {
        mutableListOf<() -> Unit>(
            { view.stopLoading() },
            { WebViewCompat.removeWebMessageListener(view, "MeasixPortalTransport") },
            { view.clearHistory() },
            { (view.parent as? android.view.ViewGroup)?.removeView(view) },
        )
    }
    private var teardown: CompletableDeferred<Unit>? = null
    private var destroyed = false

    private fun destroy(): Deferred<Unit> {
        active.set(false)
        return teardown?.takeUnless { it.isCancelled } ?: CompletableDeferred<Unit>().also { pending ->
            teardown = pending
            // Revoke the document immediately, but unwind Chromium's navigation/message callback before destroying its WebView.
            // The same receipt covers detachment, destruction and site cleanup before a replacement host can open.
            val posted = main.post {
                try {
                    var failure = attemptTeardown(viewTeardown)
                    // WebView methods cannot be retried after destroy, which also requires a detached view.
                    if (viewTeardown.isEmpty() && !destroyed) {
                        try { view.destroy(); destroyed = true }
                        catch (error: Exception) {
                            if (failure == null) failure = error else if (error !== failure) failure.addSuppressed(error)
                        }
                    }
                    failure?.let { throw it }
                    clearBrowsingData(source) { pending.complete(Unit) }
                } catch (error: Exception) {
                    pending.completeExceptionally(error)
                }
            }
            if (!posted) pending.completeExceptionally(IllegalStateException("Portal teardown dispatcher unavailable"))
        }
    }

    private fun attemptTeardown(steps: MutableList<() -> Unit>): Exception? {
        var failure: Exception? = null
        val remaining = steps.iterator()
        while (remaining.hasNext()) {
            try { remaining.next().invoke(); remaining.remove() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                val previous = failure
                if (previous == null) failure = error else if (error !== previous) previous.addSuppressed(error)
            }
        }
        return failure
    }

    companion object {
        fun missingFeatures(): List<String> = listOf(WebViewFeature.WEB_MESSAGE_LISTENER,
            WebViewFeature.DOCUMENT_START_SCRIPT, WebViewFeature.DELETE_BROWSING_DATA)
            .filterNot(WebViewFeature::isFeatureSupported)

        suspend fun open(context: Context, selection: RealmSelection, sessions: EnterpriseSessionController,
            synchronization: EnterpriseSynchronizationService, scope: CoroutineScope,
            registry: PortalDocumentRegistry, source: PortalPageSource,
            createNative: (suspend (PortalDocumentContext) -> PortalNativeActions)? = null,
            onClosed: (PortalClosure) -> Unit): PortalWebView {
            check(Looper.myLooper() == Looper.getMainLooper())
            val missing = missingFeatures()
            if (missing.isNotEmpty()) {
                val provider = WebViewCompat.getCurrentWebViewPackage(context)
                throw PortalHostUnavailable(provider?.let { "${it.packageName} ${it.versionName}" } ?: "unavailable", missing)
            }
            clearBrowsingDataAwait(source)
            var host: PortalWebView? = null
            var delivered = false
            val document = PortalDocument.open(selection, sessions, synchronization, scope, registry,
                sourceOrigin = source.origin,
                closeHost = { host?.destroy() ?: CompletableDeferred(Unit) }, createNative = createNative,
                onClosed = { if (delivered) onClosed(it) })
            try {
                if (document.isClosed) throw kotlinx.coroutines.CancellationException("Portal document unavailable")
                return PortalWebView(WebView(context), document, source).also {
                    host = it
                    it.load()
                    delivered = true
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

        private suspend fun clearBrowsingDataAwait(source: PortalPageSource) =
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                clearBrowsingData(source) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }

        private fun clearBrowsingData(source: PortalPageSource, done: () -> Unit) {
            val clearStorage = {
                WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), source.origin, done)
            }
            val secure = if (source.origin.startsWith("https://")) "; Secure" else ""
            CookieManager.getInstance().setCookie(source.origin,
                "measix_portal_session=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; HttpOnly; SameSite=Strict$secure") {
                CookieManager.getInstance().flush()
                clearStorage()
            }
        }
    }
}
