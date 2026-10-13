package com.affilemanager.app.ui.preview

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

internal fun offlinePageView(context: Context, document: OfflineWebDocument, onRendererFailure: () -> Unit = {}): WebView = WebView(context).apply {
    isLongClickable = true
    settings.apply {
        javaScriptEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        setAllowFileAccessFromFileURLs(false)
        @Suppress("DEPRECATION")
        setAllowUniversalAccessFromFileURLs(false)
        blockNetworkLoads = true
        domStorageEnabled = false
        databaseEnabled = false
        setGeolocationEnabled(false)
        cacheMode = WebSettings.LOAD_NO_CACHE
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
        mediaPlaybackRequiresUserGesture = true
    }
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse = response(request.url.toString(), request.isForMainFrame)
        @Deprecated("Legacy WebView callback")
        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse = response(url.orEmpty(), false)
        private fun response(url: String, mainFrame: Boolean): WebResourceResponse {
            // loadDataWithBaseURL creates a synthetic data: request. Serve only our already-read
            // document, not arbitrary data supplied by a link or a nested frame.
            if (mainFrame && url.startsWith("data:text/html;")) {
                return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(document.html.toByteArray(Charsets.UTF_8)))
            }
            val key = OfflineWebDocument.canonicalUrl(document.baseUrl, url)
            val part = document.parts[key]
            val safeStatic = part?.mime in setOf("text/css", "text/html", "image/png", "image/jpeg", "image/gif", "image/webp")
            return if (part != null && safeStatic) WebResourceResponse(part.mime, part.charset ?: "UTF-8", ByteArrayInputStream(part.bytes))
            else WebResourceResponse("text/plain", "UTF-8", 403, "Offline preview", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        @Deprecated("Legacy WebView callback")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) onRendererFailure()
        }
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            (view?.parent as? ViewGroup)?.removeView(view)
            view?.destroy()
            onRendererFailure()
            return true
        }
    }
    setDownloadListener { _, _, _, _, _ -> /* A preview is not a browser or a downloader. */ }
    loadDataWithBaseURL(document.baseUrl, document.html, "text/html", "UTF-8", null)
}

@Composable
internal fun OfflineWebPreview(source: PreviewSource) {
    val context = LocalContext.current
    var rendererFailed by remember(source.key) { mutableStateOf(false) }
    val result by produceState<Result<OfflineWebDocument>?>(null, source.key) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                val job = coroutineContext
                Result.success(source.openInputStream(context).use {
                    OfflineWebDocument.load(it, source.extension in setOf("mht", "mhtml")) { job.ensureActive() }
                })
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
    }
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        result?.isFailure == true -> PreviewLoadError(requireNotNull(result?.exceptionOrNull()))
        rendererFailed -> PreviewLoadError(IllegalStateException("Failo peržiūros sukurti nepavyko"))
        else -> key(source.key) { AndroidView(
            modifier = Modifier.fillMaxSize().testTag("offline_web_preview"),
            factory = {
                try { offlinePageView(it, requireNotNull(result?.getOrNull())) { rendererFailed = true } }
                catch (_: Exception) { rendererFailed = true; FrameLayout(it) }
            },
            onRelease = { view -> if (view is WebView) { view.stopLoading(); view.webViewClient = WebViewClient(); view.loadUrl("about:blank"); view.clearHistory(); view.removeAllViews(); view.destroy() } },
        ) }
    }
}
