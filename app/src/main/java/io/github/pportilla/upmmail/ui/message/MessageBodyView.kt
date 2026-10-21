package io.github.pportilla.upmmail.ui.message

import android.content.Context
import android.net.Uri
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.mail.HtmlText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** What a message WebView currently shows; reloading happens only when this changes. */
private data class WebContent(
    val html: String,
    val blockNetworkLoads: Boolean,
    val darken: Boolean,
)

private class MessageWebView(context: Context) : WebView(context) {
    var content: WebContent? = null
}

/**
 * Read-only WebView for one message body. JavaScript, file and content access are off, remote
 * loads are blocked unless [remoteImagesAllowed], and links are handed to [onLinkClick]. The
 * view is as tall as its content, so it can live inside a scrolling column. Without a usable
 * WebView (missing, being updated, or its renderer keeps dying on this message) the text is
 * shown without formatting instead.
 */
@Composable
internal fun MessageBodyView(
    body: RenderedBody,
    remoteImagesAllowed: Boolean,
    onLinkClick: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val fontScale = LocalDensity.current.fontScale
    val textColor = colors.onSurface
    val linkColor = colors.primary
    val quoteColor = colors.onSurfaceVariant
    val quoteBorder = colors.outlineVariant
    val html = remember(body.html, body.isHtml, textColor, linkColor, quoteColor, quoteBorder) {
        if (body.isHtml) {
            body.html
        } else {
            plainTextDocument(
                body.html,
                PlainTextColors(
                    text = cssColor(textColor),
                    link = cssColor(linkColor),
                    quote = cssColor(quoteColor),
                    quoteBorder = cssColor(quoteBorder),
                ),
            )
        }
    }
    // Plain text is already drawn in theme colours. HTML mail is darkened by the WebView on
    // Android 13+. Older versions only offer setForceDark, which WebView ignores in apps targeting
    // Android 13+, so there the mail keeps its own light look on white "paper".
    val canDarken = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val darken = dark && body.isHtml && canDarken
    val paper = dark && body.isHtml && !canDarken
    // On paper the WebView gets a light theme too, so the mail does not switch to its own dark
    // styles (prefers-color-scheme) on the white background.
    val webViewDark = dark && !paper
    val content = WebContent(html = html, blockNetworkLoads = !remoteImagesAllowed, darken = darken)
    var generation by remember { mutableIntStateOf(0) }
    // Renderer losses while showing this content, to stop recreating a WebView that it kills.
    var rendererLosses by remember(content) { mutableIntStateOf(0) }
    var webViewMissing by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val currentOnRendererGone by rememberUpdatedState { crashed: Boolean ->
        // A renderer the system reclaimed while the app was in the background is just recreated.
        if (crashed || lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) rendererLosses++
        generation++
    }

    if (webViewMissing || rendererLosses >= MAX_RENDERER_LOSSES) {
        UnformattedBody(
            html = body.html,
            onRetry = {
                webViewMissing = false
                rendererLosses = 0
                generation++
            },
            modifier = modifier,
        )
        return
    }

    val container = if (paper) {
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(8.dp)
    } else {
        modifier
    }
    Box(modifier = container) {
        // A new theme or a crashed renderer needs a fresh WebView.
        key(dark, paper, generation) {
            AndroidView(
                factory = { context ->
                    try {
                        createMessageWebView(
                            context = context,
                            dark = webViewDark,
                            onLinkClick = { uri -> currentOnLinkClick(uri) },
                            onRendererGone = { crashed -> currentOnRendererGone(crashed) },
                        )
                    } catch (e: RuntimeException) {
                        // No WebView provider (being updated or disabled): show the text instead.
                        webViewMissing = true
                        View(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onReset = null,
                onRelease = { view ->
                    if (view is WebView) {
                        view.stopLoading()
                        view.destroy()
                    }
                },
                update = { view -> (view as? MessageWebView)?.bind(content, fontScale, paper) },
            )
        }
    }
}

/** A renderer that dies again on the same content is not recreated any more. */
private const val MAX_RENDERER_LOSSES = 2

/** The body as plain text, for when no WebView can show it; [onRetry] tries the WebView again. */
@Composable
private fun UnformattedBody(html: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val text by produceState<String?>(initialValue = null, html) {
        value = withContext(Dispatchers.Default) {
            try {
                HtmlText.toPlainText(html)
            } catch (e: Exception) {
                ""
            }
        }
    }
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.message_body_unformatted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        text?.let {
            SelectionContainer {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun cssColor(color: Color): String = String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF)

private fun createMessageWebView(
    context: Context,
    dark: Boolean,
    onLinkClick: (Uri) -> Unit,
    onRendererGone: (crashed: Boolean) -> Unit,
): MessageWebView {
    // On Android 13+ algorithmic darkening follows the theme of the WebView's context, which
    // must match the app's own dark mode setting rather than the system's.
    val themed = ContextThemeWrapper(
        context,
        if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar,
    )
    return MessageWebView(themed).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.applyReadOnlyDefaults()
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = MessageWebViewClient(onLinkClick, onRendererGone)
    }
}

/** The locked-down settings shared by the message and print WebViews. */
private fun WebSettings.applyReadOnlyDefaults() {
    javaScriptEnabled = false
    javaScriptCanOpenWindowsAutomatically = false
    allowFileAccess = false
    allowContentAccess = false
    domStorageEnabled = false
    setGeolocationEnabled(false)
    setSupportMultipleWindows(false)
    mediaPlaybackRequiresUserGesture = true
    blockNetworkLoads = true
    loadsImagesAutomatically = true
    defaultTextEncodingName = "utf-8"
}

private fun MessageWebView.bind(content: WebContent, fontScale: Float, paper: Boolean) {
    val zoom = (fontScale * 100).roundToInt().coerceIn(50, 300)
    if (settings.textZoom != zoom) settings.textZoom = zoom
    if (this.content == content) return
    this.content = content
    setBackgroundColor(if (paper) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
    applyDarkening(content.darken)
    settings.blockNetworkLoads = content.blockNetworkLoads
    loadDataWithBaseURL(null, content.html, "text/html", "utf-8", null)
}

private fun WebView.applyDarkening(darken: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) settings.isAlgorithmicDarkeningAllowed = darken
}

/** Never navigates inside the WebView: every link goes to [onLinkClick]. */
private class MessageWebViewClient(
    private val onLinkClick: (Uri) -> Unit,
    private val onRendererGone: (crashed: Boolean) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) onLinkClick(request.url)
        return true
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // The renderer is shared by every WebView of the app; returning true keeps the app alive.
        view.visibility = View.INVISIBLE
        onRendererGone(detail.didCrash())
        return true
    }
}

/**
 * Prints a message through the system print dialog. It renders [PrintRequest.html] in its own
 * off-screen WebView, which must stay alive until printing is done: call [release] when the
 * screen goes away.
 */
internal class MessagePrinter(private val context: Context) {
    private var webView: WebView? = null

    fun print(request: PrintRequest, onFailure: () -> Unit) {
        release()
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            onFailure()
            return
        }
        val view = try {
            WebView(context)
        } catch (e: Exception) {
            // WebView missing or being updated.
            onFailure()
            return
        }
        view.settings.applyReadOnlyDefaults()
        view.settings.blockNetworkLoads = request.blockNetworkLoads
        view.webViewClient = object : WebViewClient() {
            private var started = false

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                if (started || webView !== view) return
                started = true
                try {
                    val adapter = view.createPrintDocumentAdapter(request.jobName)
                    printManager.print(request.jobName, adapter, PrintAttributes.Builder().build())
                } catch (e: Exception) {
                    onFailure()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    webView = null
                    view.destroy()
                }
                if (!started) onFailure()
                return true
            }
        }
        webView = view
        view.loadDataWithBaseURL(null, request.html, "text/html", "utf-8", null)
    }

    fun release() {
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
    }
}
