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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
 * view is as tall as its content, so it can live inside a scrolling column.
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
    // Plain text is already drawn in theme colours; HTML mail is darkened by the WebView, except
    // before Android 10 where it cannot be: it is shown on white "paper" instead.
    val darken = dark && body.isHtml
    val paper = darken && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    val content = WebContent(html = html, blockNetworkLoads = !remoteImagesAllowed, darken = darken)
    var generation by remember { mutableIntStateOf(0) }
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)

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
        key(dark, generation) {
            AndroidView(
                factory = { context ->
                    createMessageWebView(
                        context = context,
                        dark = dark,
                        onLinkClick = { uri -> currentOnLinkClick(uri) },
                        onRendererGone = { generation++ },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                onReset = null,
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.destroy()
                },
                update = { webView -> webView.bind(content, fontScale, paper) },
            )
        }
    }
}

private fun cssColor(color: Color): String = String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF)

private fun createMessageWebView(
    context: Context,
    dark: Boolean,
    onLinkClick: (Uri) -> Unit,
    onRendererGone: () -> Unit,
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
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> settings.isAlgorithmicDarkeningAllowed = darken
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            @Suppress("DEPRECATION")
            settings.forceDark = if (darken) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }
}

/** Never navigates inside the WebView: every link goes to [onLinkClick]. */
private class MessageWebViewClient(
    private val onLinkClick: (Uri) -> Unit,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) onLinkClick(request.url)
        return true
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // The renderer is shared by every WebView of the app; returning true keeps the app alive.
        view.visibility = View.INVISIBLE
        onRendererGone()
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
