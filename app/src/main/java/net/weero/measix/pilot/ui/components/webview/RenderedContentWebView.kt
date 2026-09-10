package net.weero.measix.pilot.ui.components.webview

import android.webkit.WebResourceResponse
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.*
import org.koin.compose.koinInject

@Composable
internal fun rememberRenderedContentState(
    document: RenderedContent?,
    files: FileManagementApplicationService = koinInject(),
    queries: ConversationQueryService = koinInject(),
): WebViewState? {
    val handler = net.weero.measix.pilot.ui.components.richtext.LocalRichTextActions.current?.uriHandler
        ?: net.weero.measix.pilot.ui.components.richtext.UnavailableContentUriHandler
    val authorizedSource by produceState<RenderedContentSource?>(null, document?.source, files, queries) {
        value = null
        val source = document?.source ?: return@produceState
        try {
            files.requireContentAccess(source)
            when (source) {
                is RenderedContentSource.Conversation -> queries.observeViewAccess(source.view).collect { value = source.takeIf { _ -> it } }
                is RenderedContentSource.RealmConfiguration -> queries.observeViewAccess(source.view).collect { value = source.takeIf { _ -> it } }
                RenderedContentSource.Static, RenderedContentSource.UserConfiguration -> value = source
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
    }
    if (document == null || authorizedSource != document.source) return null
    return remember(document, files, handler) {
        // Independent registrable origins prevent cookies, IndexedDB and browser caches from becoming a cross-source store.
        val origin = android.net.Uri.parse("https://measix-preview-${java.util.UUID.randomUUID()}.invalid")
        WebViewState(
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
                catch (_: Exception) { denied() }
            },
        )
    }
}

@Composable
fun RenderedContentWebView(document: RenderedContent?, modifier: Modifier = Modifier) {
    val state = rememberRenderedContentState(document)
    if (state == null) {
        Text(stringResource(R.string.rendered_content_unavailable), modifier)
    } else key(state) {
        WebView(state, modifier)
    }
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
