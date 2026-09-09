package net.weero.measix.pilot.ui.components.richtext

import android.webkit.JavascriptInterface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.webview.WEB_VIEW_ASSET_URL
import net.weero.measix.pilot.ui.components.webview.WEB_VIEW_BASE_URL
import net.weero.measix.pilot.ui.components.webview.WebView
import net.weero.measix.pilot.ui.components.webview.rememberWebViewState
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import net.weero.measix.pilot.utils.escapeHtml
import net.weero.measix.pilot.utils.toCssHex

@Composable
fun Mermaid(
    code: String,
    exportRequestKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalImageSourceResolver.current
    key(code, resolver, MaterialTheme.colorScheme, LocalDarkMode.current) {
        MermaidContent(code, exportRequestKey, modifier)
    }
}

@Composable
private fun MermaidContent(code: String, exportRequestKey: Int, modifier: Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val darkMode = LocalDarkMode.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = LocalImageSourceResolver.current
    val files: net.weero.measix.pilot.service.FileManagementApplicationService = org.koin.compose.koinInject()
    val exporter: net.weero.measix.pilot.service.MediaExportService = org.koin.compose.koinInject()
    val toaster = LocalToaster.current

    val exportSuccessText = stringResource(R.string.mermaid_export_success)
    val exportFailedText = stringResource(R.string.mermaid_export_failed)
    val permissionRequiredText = stringResource(R.string.image_viewer_save_need_permission)
    val jsInterface = remember(context, scope, resolver, files, exporter, toaster,
        exportSuccessText, exportFailedText, permissionRequiredText) {
        MermaidInterface { base64Image ->
            scope.launch {
                try {
                    val url = "data:image/png;base64,$base64Image"
                    val image = requireNotNull(if (resolver != null) resolver(url) else files.externalImageSource(url)) { "image_unavailable" }
                    exporter.saveImage(context, image)
                    toaster.show(exportSuccessText, type = ToastType.Success)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    toaster.show(if (error.message == net.weero.measix.pilot.service.IMAGE_SAVE_PERMISSION_REQUIRED)
                        permissionRequiredText else exportFailedText, type = ToastType.Error)
                }
            }
        }
    }

    val html = remember(code, colorScheme, darkMode) {
        buildMermaidHtml(
            code = code,
            colorScheme = colorScheme,
        )
    }

    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = WEB_VIEW_BASE_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(
            "AndroidInterface" to jsInterface
        ),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    )

    val initialExportRequest = remember { exportRequestKey }
    LaunchedEffect(exportRequestKey) {
        val view = webViewState.webView
        if (exportRequestKey > initialExportRequest && view != null) {
            jsInterface.requestExport(exportRequestKey)
            view.evaluateJavascript(
                "if (typeof exportSvgToPng === 'function') { exportSvgToPng($exportRequestKey); true; } else { false; }",
            ) { result ->
                if (result != "true" && jsInterface.cancelExport(exportRequestKey)) scope.launch {
                    toaster.show(exportFailedText, type = ToastType.Error)
                }
            }
        }
    }

    WebView(
        state = webViewState,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .height(200.dp),
    )
}

private class MermaidInterface(
    private val onExportImage: (String) -> Unit
) {
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    fun requestExport(id: Int) { pending.set(id) }
    fun cancelExport(id: Int): Boolean = pending.compareAndSet(id, 0)

    @JavascriptInterface
    fun exportImage(requestId: Int, base64Image: String) {
        if (requestId > 0 && pending.compareAndSet(requestId, 0)) onExportImage(base64Image)
    }
}

internal fun buildMermaidHtml(
    code: String,
    colorScheme: ColorScheme,
): String {
    val primaryColor = colorScheme.primaryContainer.toCssHex()
    val secondaryColor = colorScheme.secondaryContainer.toCssHex()
    val tertiaryColor = colorScheme.tertiaryContainer.toCssHex()
    val background = colorScheme.background.toCssHex()
    val surface = colorScheme.surface.toCssHex()
    val onPrimary = colorScheme.onPrimaryContainer.toCssHex()
    val onSecondary = colorScheme.onSecondaryContainer.toCssHex()
    val onTertiary = colorScheme.onTertiaryContainer.toCssHex()
    val onBackground = colorScheme.onBackground.toCssHex()
    val errorColor = colorScheme.error.toCssHex()
    val onErrorColor = colorScheme.onError.toCssHex()

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=1024">
            <script src="${WEB_VIEW_ASSET_URL}/html/mermaid.min.js"></script>
            <style>
                body {
                    margin: 0;
                    padding: 0;
                    display: flex;
                    justify-content: center;
                    background-color: ${background};
                }
                .mermaid {
                    padding: 8px;
                    width: fit-content;
                    min-width: 100%;
                }
                .mermaid svg {
                    background: transparent !important;
                }
            </style>
        </head>
        <body>
            <pre class="mermaid">
                ${code.escapeHtml()}
            </pre>
            <script>
              mermaid.initialize({
                    startOnLoad: true,
                    theme: 'base',
                    themeVariables: {
                        primaryColor: '${primaryColor}',
                        primaryTextColor: '${onPrimary}',
                        primaryBorderColor: '${primaryColor}',

                        secondaryColor: '${secondaryColor}',
                        secondaryTextColor: '${onSecondary}',
                        secondaryBorderColor: '${secondaryColor}',

                        tertiaryColor: '${tertiaryColor}',
                        tertiaryTextColor: '${onTertiary}',
                        tertiaryBorderColor: '${tertiaryColor}',

                        background: '${background}',
                        mainBkg: '${primaryColor}',
                        secondBkg: '${secondaryColor}',

                        lineColor: '${onBackground}',
                        textColor: '${onBackground}',

                        nodeBkg: '${surface}',
                        nodeBorder: '${primaryColor}',
                        clusterBkg: '${surface}',
                        clusterBorder: '${primaryColor}',

                        actorBorder: '${primaryColor}',
                        actorBkg: '${surface}',
                        actorTextColor: '${onBackground}',
                        actorLineColor: '${primaryColor}',

                        taskBorderColor: '${primaryColor}',
                        taskBkgColor: '${primaryColor}',
                        taskTextLightColor: '${onPrimary}',
                        taskTextDarkColor: '${onBackground}',

                        labelColor: '${onBackground}',
                        errorBkgColor: '${errorColor}',
                        errorTextColor: '${onErrorColor}'
                    }
              });

              window.exportSvgToPng = function(requestId) {
                try {
                    const svgElement = document.querySelector('.mermaid svg');
                    if (!svgElement) {
                        AndroidInterface.exportImage(requestId, '');
                        return;
                    }

                    const canvas = document.createElement('canvas');
                    const ctx = canvas.getContext('2d');

                    const svgRect = svgElement.getBoundingClientRect();
                    const width = svgRect.width;
                    const height = svgRect.height;

                    const scaleFactor = window.devicePixelRatio * 2;
                    canvas.width = width * scaleFactor;
                    canvas.height = height * scaleFactor;

                    const svgXml = new XMLSerializer().serializeToString(svgElement);
                    const svgBase64 = btoa(unescape(encodeURIComponent(svgXml)));

                    const img = new Image();
                    img.onload = function() {
                        ctx.fillStyle = '${background}';
                        ctx.fillRect(0, 0, canvas.width, canvas.height);
                        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

                        ctx.font = '14px Arial';
                        ctx.fillStyle = '${onBackground}';
                        ctx.fillText('measix-pilot.weero.net', 20, canvas.height - 10);

                        const pngBase64 = canvas.toDataURL('image/png').split(',')[1];
                        AndroidInterface.exportImage(requestId, pngBase64);
                    };
                    img.onerror = function(e) {
                        AndroidInterface.exportImage(requestId, '');
                    }
                    img.src = 'data:image/svg+xml;base64,' + svgBase64;
                } catch (e) {
                    AndroidInterface.exportImage(requestId, '');
                }
              };
            </script>
        </body>
        </html>
    """.trimIndent()
}
