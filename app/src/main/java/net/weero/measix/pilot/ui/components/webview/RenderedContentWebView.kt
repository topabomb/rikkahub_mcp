package net.weero.measix.pilot.ui.components.webview

import net.weero.measix.pilot.utils.logDiagnosticFailure

import android.webkit.WebResourceResponse
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.files.ArtifactProjectionException
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.components.ui.ErrorCard
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.koin.compose.koinInject

internal sealed interface RenderedContentReadState {
    data object Loading : RenderedContentReadState
    data object Unavailable : RenderedContentReadState
    data class Failed(val error: Throwable) : RenderedContentReadState
    data class Ready(val webView: WebViewState) : RenderedContentReadState
}

private fun renderedContentFailure(error: Exception): RenderedContentReadState {
    val unavailable = error is EnterpriseConfigurationException && error.reason in setOf(
        "enterprise_data_access_unavailable", "enterprise_selection_revoked", "enterprise_session_required",
    ) || error is IllegalStateException && error.message in setOf("conversation_view_closed", "conversation_view_revoked")
    if (unavailable) return RenderedContentReadState.Unavailable
    logDiagnosticFailure("RenderedContent", "Preview read failed", error)
    return RenderedContentReadState.Failed(error)
}

@Composable
internal fun rememberRenderedContentState(
    document: RenderedContent?,
    files: FileManagementApplicationService = koinInject(),
    queries: ConversationQueryService = koinInject(),
    readRevision: Int = 0,
): RenderedContentReadState {
    val handler = net.weero.measix.pilot.ui.components.richtext.LocalRichTextActions.current?.uriHandler
        ?: net.weero.measix.pilot.ui.components.richtext.UnavailableContentUriHandler
    // Every attempt owns its UI state; callbacks from a disposed WebView cannot fail a new document.
    val state = remember(document, files, queries, readRevision, handler) {
        mutableStateOf<RenderedContentReadState>(if (document == null) RenderedContentReadState.Unavailable else RenderedContentReadState.Loading)
    }
    LaunchedEffect(state) {
        val current = document ?: return@LaunchedEffect
        val source = current.source
        try {
            files.requireContentAccess(source)
            val webView = renderedContentWebViewState(current, files, handler) { error ->
                if (state.value is RenderedContentReadState.Ready) state.value = renderedContentFailure(error)
            }
            val view = when (source) {
                is RenderedContentSource.Conversation -> source.view
                is RenderedContentSource.RealmConfiguration -> source.view
                RenderedContentSource.Static, RenderedContentSource.UserConfiguration -> null
            }
            if (view == null) state.value = RenderedContentReadState.Ready(webView)
            else queries.observeViewAccess(view).collect { allowed ->
                if (!allowed) state.value = RenderedContentReadState.Unavailable
                else if (state.value == RenderedContentReadState.Loading) state.value = RenderedContentReadState.Ready(webView)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            state.value = renderedContentFailure(error)
        }
    }
    return state.value
}

private fun renderedContentWebViewState(
    document: RenderedContent,
    files: FileManagementApplicationService,
    handler: androidx.compose.ui.platform.UriHandler,
    onFailure: (Exception) -> Unit,
): WebViewState {
    // Independent registrable origins prevent cookies, IndexedDB and browser caches from becoming a cross-source store.
    val origin = android.net.Uri.parse("https://measix-preview-${java.util.UUID.randomUUID()}.invalid")
    return WebViewState(
        initialContent = WebContent.Data(prepareRenderedHtml(document.html, origin.toString()), origin.toString(), "UTF-8", "text/html"),
        settings = {
            domStorageEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
        onNavigate = { url ->
            val uri = android.net.Uri.parse(url)
            if (uri.host == origin.host) {
                renderedLocalFileUrl(uri)?.let(handler::openUri)
            } else handler.openUri(url)
        },
        interceptRequest = { uri ->
            fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Unavailable", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
            try {
                runBlocking {
                    files.requireContentAccess(document.source)
                    val localUrl = when {
                        uri.scheme.equals("file", ignoreCase = true) -> uri.toString()
                        uri.host == origin.host || uri.host.equals("measix.local", ignoreCase = true) -> renderedLocalFileUrl(uri)
                        else -> null
                    }
                    when {
                        // loadDataWithBaseURL itself enters through a synthetic data URL; inline document resources have no filesystem authority.
                        uri.scheme.equals("data", ignoreCase = true) -> null
                        localUrl != null -> files.readRenderedImage(document.source, localUrl)?.let { (mime, bytes) ->
                            WebResourceResponse(mime, null, java.io.ByteArrayInputStream(bytes))
                        } ?: denied()
                        uri.scheme.equals("content", ignoreCase = true) -> denied()
                        uri.scheme?.lowercase() !in setOf("http", "https") -> denied()
                        (uri.host == origin.host || uri.host.equals("measix.local", ignoreCase = true)) && !uri.path.orEmpty().startsWith("/assets/") -> denied()
                        else -> null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ArtifactProjectionException) { denied() }
            catch (error: Exception) { onFailure(error); denied() }
        },
    )
}

@Composable
fun RenderedContentWebView(document: RenderedContent?, modifier: Modifier = Modifier) {
    var revision by remember(document) { mutableIntStateOf(0) }
    when (val state = rememberRenderedContentState(document, readRevision = revision)) {
        RenderedContentReadState.Loading -> LinearProgressIndicator(modifier.fillMaxWidth())
        RenderedContentReadState.Unavailable -> Text(stringResource(R.string.rendered_content_unavailable), modifier)
        is RenderedContentReadState.Failed -> RenderedContentReadFailure(state.error, { revision++ }, modifier)
        is RenderedContentReadState.Ready -> key(state.webView) { WebView(state.webView, modifier) }
    }
}

@Composable
internal fun RenderedContentReadFailure(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val diagnostic = remember(error) { error.userVisibleDiagnostic() }
    val title = stringResource(R.string.rendered_content_read_failed)
    val displayError = remember(diagnostic, title) {
        ChatError(title = title, detail = diagnostic, retention = ChatErrorRetention.UNTIL_DISMISSED)
    }
    ErrorCard(displayError, modifier = modifier.padding(16.dp), onRetry = onRetry)
}

/** Native file access stays disabled. Dynamic HTML and Markdown-created nodes use the same owned resource path. */
private fun prepareRenderedHtml(html: String, origin: String): String {
    val document = org.jsoup.Jsoup.parse(html)
    val script = """
        (() => {
          const origin = '$origin';
          const path = origin + '$RENDERED_FILE_PATH?url=';
          const local = value => /^file:/i.test(value) ? path + encodeURIComponent(value) : value.startsWith('/upload/') ? origin + value : value;
          const css = value => value.replace(/url\(\s*(["']?)((?:file:|\/upload\/)[^"')]+)\1\s*\)/gi, (_, q, url) => 'url("' + local(url.trim()) + '")');
          const rewrite = element => {
            if (element.nodeType !== 1) return;
            for (const attr of ['src', 'href', 'xlink:href', 'poster']) {
              const old = element.getAttribute(attr);
              if (old) {
                const next = local(old);
                if (next !== old) element.setAttribute(attr, next);
              }
            }
            const srcset = element.getAttribute('srcset');
            if (srcset) {
              const next = srcset.replace(/(^|,\s*)((?:file:|\/upload\/)[^\s,]+)/gi, (_, prefix, url) => prefix + local(url));
              if (next !== srcset) element.setAttribute('srcset', next);
            }
            const style = element.getAttribute('style');
            if (style && css(style) !== style) element.setAttribute('style', css(style));
            if (element.tagName === 'STYLE' && css(element.textContent) !== element.textContent) element.textContent = css(element.textContent);
          };
          const visit = node => {
            rewrite(node);
            if (node.querySelectorAll) node.querySelectorAll('*').forEach(rewrite);
            if (node.nodeType === 3 && node.parentElement) rewrite(node.parentElement);
          };
          new MutationObserver(records => records.forEach(record => {
            if (record.type === 'childList') record.addedNodes.forEach(visit);
            else if (record.type === 'attributes') rewrite(record.target);
            else if (record.target.parentElement) rewrite(record.target.parentElement);
          })).observe(document.documentElement, {subtree:true, childList:true, attributes:true, characterData:true,
            attributeFilter:['src','href','xlink:href','poster','srcset','style']});
          visit(document.documentElement);
        })();
    """.trimIndent()
    document.head().prependElement("script").appendChild(org.jsoup.nodes.DataNode(script))
    return document.outerHtml()
}
