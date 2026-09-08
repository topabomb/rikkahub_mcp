package net.weero.measix.pilot.service.portal

import android.content.Context
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.EnterpriseSynchronizationService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PortalWebViewAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun deliveredPortalLoadsThroughNativeBootstrapAndReloadRevokesOnlyItsDocument() = runBlocking<Unit> {
        val root = File(context.noBackupFilesDir, "portal-webview-test-${Uuid.random()}").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val registry = PortalDocumentRegistry()
        val hosts = mutableListOf<PortalWebView>()
        var host: PortalWebView? = null
        try {
            val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "client")))
            val source = source(root, sessions)
            val enrolled = source.enrollExample()
            val selection = requireNotNull(sessions.observeSelectedRealmSelection().first())
            val sync = EnterpriseSynchronizationService(sessions, source, scope)
            val closed = CompletableDeferred<Pair<PortalClosure, Boolean>>()
            host = withContext(Dispatchers.Main) {
                assertTrue("Installed WebView lacks required v3 features: ${WebViewCompat.getCurrentWebViewPackage(context)}", PortalWebView.supported())
                PortalWebView.open(compose.activity, selection, sessions, sync, scope, registry) {
                    closed.complete(it to (host?.view?.parent == null))
                }.also { hosts += it }
            }
            val original = requireNotNull(host)
            var displayed by mutableStateOf<PortalWebView?>(original)
            compose.setContent { displayed?.let { page -> key(page.document.id) { AndroidView(factory = { page.view }) } } }
            val loaded = awaitPage(original) { page ->
                page["text"]?.jsonPrimitive?.content?.contains(enrolled.manifest.session!!.identity.enterpriseName) == true &&
                    page["bootstrap"] is JsonObject
            }
            assertEquals(3, loaded.getValue("bootstrap").jsonObject.getValue("bridgeVersion").jsonPrimitive.int)
            assertEquals(original.document.id, loaded.getValue("bootstrap").jsonObject.getValue("documentId").jsonPrimitive.content)
            assertEquals(PortalProtocol.LOCAL_ENTRY, loaded.getValue("url").jsonPrimitive.content)
            val feed = sessions.listFeed(selection, EnterpriseFeedQuery())
            awaitPage(original) { it["text"]?.jsonPrimitive?.content?.contains(feed.body.items.single().title) == true }
            assertEquals(enrolled.manifest.applied, (sessions.state.value as EnterpriseState.Available).manifest.applied)
            withContext(Dispatchers.Main) { original.view.evaluateJavascript("location.hash='within-document'", null) }
            awaitPage(original) { it["url"]?.jsonPrimitive?.content?.endsWith("#within-document") == true }
            assertFalse(original.document.isClosed)
            withContext(Dispatchers.Main) { original.view.reload() }
            val (closure, detachedBeforeNotification) = withTimeout(15_000) { closed.await() }
            assertEquals(PortalClosure(original.document.id, PortalCloseReason.DOCUMENT_REPLACED), closure)
            assertTrue("Navigation was notified before the old WebView was detached", detachedBeforeNotification)
            original.document.awaitClosed()
            assertTrue(original.document.isClosed)
            assertEquals(enrolled.manifest.session, (sessions.state.value as EnterpriseState.Available).manifest.session)
            val replacement = withContext(Dispatchers.Main) {
                PortalWebView.open(compose.activity, selection, sessions, sync, scope, registry) {}.also { hosts += it }
            }
            try {
                assertNotEquals(original.document.id, replacement.document.id)
                withContext(Dispatchers.Main) { displayed = replacement }
                awaitPage(replacement) { page ->
                    page["text"]?.jsonPrimitive?.content?.contains(feed.body.items.single().title) == true
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { displayed = null; replacement.close() }
                withContext(NonCancellable) { replacement.document.awaitClosed() }
            }
        } finally {
            closeHosts(hosts, scope)
            check(root.deleteRecursively())
        }
    }

    @Test
    fun realmChangeClosesActualWebViewAndDoesNotReuseDocumentOnReturn() = runBlocking<Unit> {
        val root = File(context.noBackupFilesDir, "portal-webview-test-${Uuid.random()}").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val registry = PortalDocumentRegistry()
        val hosts = mutableListOf<PortalWebView>()
        try {
            val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "client")))
            val source = source(root, sessions)
            source.enrollExample()
            val closed = CompletableDeferred<Unit>()
            val selection = requireNotNull(sessions.observeSelectedRealmSelection().first())
            val original = withContext(Dispatchers.Main) {
                PortalWebView.open(compose.activity, selection, sessions, EnterpriseSynchronizationService(sessions, source, scope), scope, registry) {
                    closed.complete(Unit)
                }.also { hosts += it }
            }
            compose.setContent { AndroidView(factory = { original.view }) }
            awaitPage(original) { it["bootstrap"] is JsonObject }
            sessions.selectPersonalFixture()
            sessions.selectEnterpriseFixture()
            withTimeout(15_000) { closed.await() }
            assertTrue(original.document.isClosed)
            withContext(Dispatchers.Main) {
                assertNull(original.view.parent)
                assertNull(original.document.receive("{}", PortalProtocol.LOCAL_ORIGIN, true) { fail("Closed document replied") })
            }
        } finally {
            closeHosts(hosts, scope)
            check(root.deleteRecursively())
        }
    }

    @Test
    fun lateSynchronizationErrorCannotReachTheReplacementWebView() = runBlocking<Unit> {
        val root = File(context.noBackupFilesDir, "portal-webview-test-${Uuid.random()}").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val registry = PortalDocumentRegistry()
        val hosts = mutableListOf<PortalWebView>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "client")))
            val source = source(root, sessions)
            val enrolled = source.enrollExample()
            val selection = requireNotNull(sessions.observeSelectedRealmSelection().first())
            val delayed = mockk<EnterpriseSynchronizationService>()
            coEvery { delayed.synchronize(selection.access as RealmAccess.Enterprise) } coAnswers {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                throw PortalFailure("configuration_invalid")
            }
            val originalScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
            val original = withContext(Dispatchers.Main) {
                PortalWebView.open(compose.activity, selection, sessions, delayed, originalScope, registry) {}.also { hosts += it }
            }
            var displayed by mutableStateOf<PortalWebView?>(original)
            compose.setContent { displayed?.let { page -> key(page.document.id) { AndroidView(factory = { page.view }) } } }
            awaitPage(original) { it["refreshEnabled"]?.jsonPrimitive?.boolean == true }
            withContext(Dispatchers.Main) {
                original.view.evaluateJavascript("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.includes('同步企业配置')).click()", null)
            }
            try { withTimeout(10_000) { started.await() } }
            catch (failure: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Enabled refresh did not reach native synchronization", failure) }
            withContext(Dispatchers.Main) { original.view.reload() }
            compose.waitUntil(10_000) { compose.runOnUiThread { original.document.isClosed } }
            val replacement = withContext(Dispatchers.Main) {
                PortalWebView.open(compose.activity, selection, sessions, EnterpriseSynchronizationService(sessions, source, scope), scope, registry) {}.also { hosts += it }
            }
            withContext(Dispatchers.Main) { displayed = replacement }
            awaitPage(replacement) { it["text"]?.jsonPrimitive?.content?.contains("同步企业配置") == true }
            withContext(Dispatchers.Main) {
                // Inspection only: production responses still use the original JavaScriptReplyProxy.
                replacement.view.evaluateJavascript("window.portalTestResponses=[];MeasixHost.addEventListener('message',e=>portalTestResponses.push(JSON.parse(e.data)))", null)
            }
            awaitPage(replacement) { it["observing"]?.jsonPrimitive?.boolean == true }
            release.complete(Unit)
            try { withTimeout(10_000) { original.document.awaitClosed() } }
            catch (failure: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Revoked document did not finish its original synchronization", failure) }
            withContext(Dispatchers.Main) {
                replacement.view.evaluateJavascript("MeasixHost.postMessage(JSON.stringify({bridgeVersion:3,documentId:MeasixPortalDocument.documentId,requestId:'replacement-probe',method:'getStatus',params:{}}))", null)
            }
            val page = awaitPage(replacement) { snapshot ->
                (snapshot["responses"] as? JsonArray)?.any { it.jsonObject["requestId"]?.jsonPrimitive?.content == "replacement-probe" } == true
            }
            val replies = page.getValue("responses").jsonArray.map { it.jsonObject }
            assertTrue(replies.all { it.getValue("documentId").jsonPrimitive.content == replacement.document.id && "error" !in it })
            assertNotEquals(original.document.id, replacement.document.id)
            assertEquals(enrolled.manifest.session, (sessions.state.value as EnterpriseState.Available).manifest.session)
        } finally {
            release.complete(Unit)
            closeHosts(hosts, scope)
            check(root.deleteRecursively())
        }
    }

    @Test
    fun closingClearsPortalCookiesAndStorageBeforeReopeningWithoutClearingAnotherSite() = runBlocking<Unit> {
        val root = File(context.noBackupFilesDir, "portal-webview-test-${Uuid.random()}").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val registry = PortalDocumentRegistry()
        val hosts = mutableListOf<PortalWebView>()
        val suffix = Uuid.random().toString().replace("-", "")
        val cookieName = "portal_$suffix"
        val privateCookieName = "portal_private_$suffix"
        val storageKey = "portal-storage-$suffix"
        val unrelatedSite = "https://portal-test-$suffix.example.invalid/"
        val unrelatedCookie = "preserved_$suffix"
        val privatePath = "${PortalProtocol.LOCAL_ORIGIN}/portal/private/"
        try {
            val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "client")))
            val source = source(root, sessions)
            source.enrollExample()
            val selection = requireNotNull(sessions.observeSelectedRealmSelection().first())
            val sync = EnterpriseSynchronizationService(sessions, source, scope)
            val original = withContext(Dispatchers.Main) {
                PortalWebView.open(compose.activity, selection, sessions, sync, scope, registry) {}.also { hosts += it }
            }
            var displayed by mutableStateOf<PortalWebView?>(original)
            compose.setContent { displayed?.let { page -> key(page.document.id) { AndroidView(factory = { page.view }) } } }
            val feedTitle = sessions.listFeed(selection, EnterpriseFeedQuery()).body.items.single().title
            awaitPage(original) { it["text"]?.jsonPrimitive?.content?.contains(feedTitle) == true }
            setCookie(privatePath, "$privateCookieName=private-value; Path=/portal/private/; Secure; HttpOnly; SameSite=Strict")
            setCookie(unrelatedSite, "$unrelatedCookie=keep-value; Path=/; Secure; SameSite=Strict")
            val stored = evaluatePageJson(original, """
                (() => {
                    document.cookie = '$cookieName=page-value; Path=/portal/; Secure; SameSite=Strict';
                    localStorage.setItem('$storageKey', 'enterprise-private');
                    return JSON.stringify({cookie:document.cookie,stored:localStorage.getItem('$storageKey')});
                })()
            """.trimIndent())
            assertTrue(stored.getValue("cookie").jsonPrimitive.content.contains("$cookieName=page-value"))
            assertEquals("enterprise-private", stored.getValue("stored").jsonPrimitive.content)
            assertTrue(cookieHeader(privatePath).contains("$privateCookieName=private-value"))
            assertTrue(cookieHeader(unrelatedSite).contains("$unrelatedCookie=keep-value"))

            withContext(Dispatchers.Main) { displayed = null; original.close() }
            original.document.awaitClosed()
            assertFalse(cookieHeader(PortalProtocol.LOCAL_ENTRY).contains("$cookieName="))
            assertFalse(cookieHeader(privatePath).contains("$privateCookieName="))
            assertTrue("Portal cleanup removed another site's cookie", cookieHeader(unrelatedSite).contains("$unrelatedCookie=keep-value"))

            val replacement = withContext(Dispatchers.Main) {
                PortalWebView.open(compose.activity, selection, sessions, sync, scope, registry) {}.also { hosts += it }
            }
            withContext(Dispatchers.Main) { displayed = replacement }
            awaitPage(replacement) { it["text"]?.jsonPrimitive?.content?.contains(feedTitle) == true }
            val reopened = evaluatePageJson(replacement,
                "JSON.stringify({cookie:document.cookie,stored:localStorage.getItem('$storageKey')})")
            assertEquals(JsonNull, reopened.getValue("stored"))
            assertFalse(reopened.getValue("cookie").jsonPrimitive.content.contains("$cookieName="))
            assertNotEquals(original.document.id, replacement.document.id)
            assertTrue(cookieHeader(unrelatedSite).contains("$unrelatedCookie=keep-value"))
        } finally {
            try { closeHosts(hosts, scope) }
            finally {
                withContext(NonCancellable) { setCookie(unrelatedSite, "$unrelatedCookie=; Max-Age=0; Path=/; Secure; SameSite=Strict") }
                check(root.deleteRecursively())
            }
        }
    }

    private suspend fun closeHosts(hosts: List<PortalWebView>, scope: CoroutineScope) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            try {
                hosts.forEach { it.close() }
                var failure: Exception? = null
                hosts.forEach {
                    try { it.document.awaitClosed() }
                    catch (error: Exception) {
                        if (failure == null) failure = error else if (error !== failure) failure?.addSuppressed(error)
                    }
                }
                failure?.let { throw it }
            } finally { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
        }
    }

    private suspend fun setCookie(url: String, value: String) = withContext(Dispatchers.Main.immediate) {
        val accepted = CompletableDeferred<Boolean>()
        CookieManager.getInstance().setCookie(url, value) { accepted.complete(it) }
        assertTrue("WebView rejected the test cookie for $url", withTimeout(5_000) { accepted.await() })
    }

    private suspend fun cookieHeader(url: String): String = withContext(Dispatchers.Main.immediate) {
        CookieManager.getInstance().getCookie(url).orEmpty()
    }

    private suspend fun evaluatePageJson(host: PortalWebView, script: String): JsonObject = withContext(Dispatchers.Main.immediate) {
        val result = CompletableDeferred<String>()
        host.view.evaluateJavascript(script) { result.complete(it) }
        Json.parseToJsonElement(Json.decodeFromString<String>(withTimeout(5_000) { result.await() })).jsonObject
    }

    private fun awaitPage(host: PortalWebView, predicate: (JsonObject) -> Boolean): JsonObject {
        val snapshot = AtomicReference<JsonObject?>()
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                host.view.post {
                    if (!host.document.isClosed) host.view.evaluateJavascript(
                        "JSON.stringify({url:location.href,bootstrap:window.MeasixPortalDocument||null,text:document.body?document.body.innerText:'',ready:document.readyState,refreshEnabled:Array.from(document.querySelectorAll('button')).some(b=>b.textContent.includes('同步企业配置')&&!b.disabled),observing:Array.isArray(window.portalTestResponses),responses:window.portalTestResponses||[]})",
                    ) { encoded ->
                        if (encoded != "null") snapshot.set(Json.parseToJsonElement(Json.decodeFromString<String>(encoded)).jsonObject)
                    }
                }
                snapshot.get()?.let(predicate) == true
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Portal closed=${host.document.isClosed}; last page=${snapshot.get()}", failure)
        }
        return requireNotNull(snapshot.get())
    }

    private fun source(root: File, sessions: EnterpriseSessionController) = LocalEnterpriseSource(
        { context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET) }, sessions,
        LocalEnrollmentAuthority(File(root, "authority")),
        { context.assets.open(LocalEnterpriseSource.IDENTITY_ASSET) }, LocalEnterpriseConfigurationStore(File(root, "authority")),
    )
}
