package com.affilemanager.app.ui.preview

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.EntryKind
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket

class OfflineWebPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun htmlRendersWithZoomAndSelectionButNoScriptNetworkOrFileAccess() {
        assumeTrue("The factory API26 image has no system WebView", WebView.getCurrentWebViewPackage() != null)
        val server = ServerSocket(0)
        server.soTimeout = 500
        val networkRequests = AtomicInteger()
        val observer = Thread {
            while (!server.isClosed) try { server.accept().use { networkRequests.incrementAndGet() } }
                catch (_: java.net.SocketTimeoutException) { } catch (_: java.io.IOException) { break }
        }.apply { isDaemon = true; start() }
        try {
        val html = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Offline fixture</title></head><body style="font-size:28px"><p>Selectable offline text</p><img src="http://127.0.0.1:${server.localPort}/image"><img src="file:///private/forbidden.png"><script>document.title='Script ran';fetch('http://127.0.0.1:${server.localPort}/script');</script></body></html>"""
        val page = OfflineWebDocument.load(html.byteInputStream(), false)
        val view = AtomicReference<WebView>()
        compose.setContent { MaterialTheme {
            AndroidView(factory = { offlinePageView(it, page).also(view::set) }, modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag("offline_page_for_test"),
                onRelease = { it.stopLoading(); it.destroy() })
        } }
        awaitTitle(view, "Offline fixture")
        compose.runOnUiThread {
            val web = view.get()
            assertFalse(web.settings.javaScriptEnabled)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertTrue(web.settings.blockNetworkLoads)
            assertTrue(web.settings.builtInZoomControls)
            assertTrue(web.isLongClickable)
        }
        screenshot("html")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val location = IntArray(2)
        compose.runOnUiThread { view.get().getLocationOnScreen(location) }
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(downTime, downTime,
            android.view.MotionEvent.ACTION_DOWN, location[0] + 60f, location[1] + 40f, 0)
        try { assertTrue(automation.injectInputEvent(down, true)) } finally { down.recycle() }
        // WebView's native recognizer uses the real looper clock, not Compose's virtual clock.
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 250)
        val up = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
            android.view.MotionEvent.ACTION_UP, location[0] + 60f, location[1] + 40f, 0)
        try { assertTrue(automation.injectInputEvent(up, true)) } finally { up.recycle() }
        screenshot("html-selection")
        var copy: android.view.accessibility.AccessibilityNodeInfo? = null
        compose.waitUntil(10000) {
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            copy = roots.flatMap { it.findAccessibilityNodeInfosByText("Copy") }.firstNotNullOfOrNull { node ->
                var candidate: android.view.accessibility.AccessibilityNodeInfo? = node
                var result: android.view.accessibility.AccessibilityNodeInfo? = null
                repeat(4) { if (candidate?.isClickable == true && result == null) result = candidate; candidate = candidate?.parent }
                result
            }
            copy != null
        }
        assertTrue(requireNotNull(copy).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(5000) {
            var copied = false
            compose.runOnUiThread {
                val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                copied = clipboard.primaryClip?.getItemAt(0)?.text?.contains("Selectable") == true
            }
            copied
        }
        server.close()
        observer.join(1500)
        assertEquals(0, networkRequests.get())
        compose.runOnUiThread { assertEquals("Offline fixture", view.get().title) }
        } finally { server.close(); observer.join(1500) }
    }

    @Test fun mhtDisplaysItsDecodedHtmlInsteadOfMimeSource() {
        assumeTrue("The factory API26 image has no system WebView", WebView.getCurrentWebViewPackage() != null)
        val picture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val encoded = java.io.ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        picture.recycle()
        val mht = "Content-Type: multipart/related; boundary=scope\r\n\r\n--scope\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Location: https://example.invalid/page.html\r\n\r\n<html><head><title>MHT fixture</title></head><body><h1>Saved page</h1><p>Readable offline text</p><img src=\"fixture.png\" width=\"100\" height=\"80\"></body></html>\r\n--scope\r\nContent-Type: image/png\r\nContent-Location: https://example.invalid/fixture.png\r\nContent-Transfer-Encoding: base64\r\n\r\n${java.util.Base64.getEncoder().encodeToString(encoded)}\r\n--scope--\r\n"
        val page = OfflineWebDocument.load(mht.byteInputStream(), true)
        val view = AtomicReference<WebView>()
        compose.setContent { MaterialTheme { AndroidView(factory = { offlinePageView(it, page).also(view::set) },
            modifier = Modifier.fillMaxSize().safeDrawingPadding(), onRelease = { it.stopLoading(); it.destroy() }) } }
        awaitTitle(view, "MHT fixture")
        compose.waitUntil(10000) {
            val rendered = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                var found = false
                outer@ for (y in 0 until rendered.height step 6) for (x in 0 until rendered.width step 6) {
                    if (rendered.getPixel(x, y) == android.graphics.Color.MAGENTA) { found = true; break@outer }
                }
                found
            } finally { rendered.recycle() }
        }
        screenshot("mht")
        compose.runOnUiThread { assertEquals("MHT fixture", view.get().title) }
    }

    @Test fun missingSystemWebViewShowsAnErrorInsteadOfCrashing() {
        assumeTrue("Only the no-WebView platform exercises this path", WebView.getCurrentWebViewPackage() == null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("offline-preview-", ".html", context.cacheDir)
        try {
            file.writeText("<html><body>Original remains unchanged</body></html>")
            val entry = FileEntry(file.path, file.name, EntryKind.DOCUMENT, file.length(), file.lastModified(), false, true, true)
            compose.setContent { MaterialTheme { OfflineWebPreview(PreviewSource.Local(entry)) } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Could not create the file preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Could not create the file preview").fetchSemanticsNodes().forEach { assertNotNull(it) }
            screenshot("webview-unavailable")
            assertEquals("<html><body>Original remains unchanged</body></html>", file.readText())
        } finally { file.delete() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(instrumentation.targetContext.getExternalFilesDir("validation"), "issues-246-252-$name.png")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun awaitTitle(view: AtomicReference<WebView>, expected: String) {
        var title: String? = null
        var scheme: String? = null
        try {
            compose.waitUntil(15000) {
                compose.runOnUiThread { title = view.get()?.title; scheme = view.get()?.url?.substringBefore(':') }
                title == expected
            }
        } catch (failure: Throwable) {
            screenshot("page-load-failure")
            throw AssertionError("Native fixture did not load: title=$title; scheme=$scheme", failure)
        }
    }
}
