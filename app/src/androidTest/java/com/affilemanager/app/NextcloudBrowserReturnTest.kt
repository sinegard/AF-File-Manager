package com.affilemanager.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.NextcloudLoginPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NextcloudBrowserReturnTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemBackFromTheLoginBrowserReturnsToTheSameAfDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val browser = context.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://127.0.0.1/")),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        org.junit.Assume.assumeTrue("The fixture needs an installed browser", browser != null)
        if (browser?.activityInfo?.packageName == "org.chromium.webview_shell") {
            org.junit.Assume.assumeTrue("This Android 8 system image has no WebView provider for its browser shell",
                android.webkit.WebView.getCurrentWebViewPackage() != null)
        }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val endpoint = "http://127.0.0.1:${server.localPort}"
        val active = AtomicBoolean(true)
        val loginPageRequested = AtomicBoolean(false)
        val fixtureFailure = AtomicReference<Throwable?>(null)
        val worker = thread(name = "af-nextcloud-return-fixture", isDaemon = true) {
            while (active.get()) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader()
                        val request = input.readLine().orEmpty()
                        var contentLength = 0
                        while (true) {
                            val header = input.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) contentLength = header.substringAfter(':').trim().toInt()
                        }
                        repeat(contentLength.coerceIn(0, 8_192)) { input.read() }
                        val loginStart = request.contains("/index.php/login/v2 ")
                        val polling = request.contains("/poll ")
                        if (request.contains("/login ")) loginPageRequested.set(true)
                        val body = if (loginStart) {
                            """{"login":"$endpoint/login","poll":{"endpoint":"$endpoint/poll","token":"af-test-session"}}"""
                        } else if (polling) "{}" else "<html><body>AF browser return fixture</body></html>"
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 ${if (polling) "404 Not Found" else "200 OK"}\r\nContent-Length: ${bytes.size}\r\nContent-Type: ${if (loginStart || polling) "application/json" else "text/html"}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // A browser may preconnect without sending a request. Close that idle socket
                    // and keep serving the actual login page and AF polling requests.
                } catch (error: Exception) {
                    if (active.get()) { fixtureFailure.set(error); break }
                }
            }
        }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val activity = compose.activity
        try {
            compose.runOnUiThread { vm.openNextcloudSetup(); vm.updateNextcloudServer(endpoint) }
            compose.onNodeWithTag("nextcloud_login_start").performClick()
            compose.waitUntil(15_000) {
                vm.nextcloudLogin.value.phase == NextcloudLoginPhase.WAITING_FOR_BROWSER &&
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
            try {
                if (InstrumentationRegistry.getArguments().getString("afPrepareBrowser") == "true")
                    prepareChromeEmulatorFixture(context, loginPageRequested)
                compose.waitUntil(15_000) {
                    fixtureFailure.get()?.let { throw AssertionError("Login fixture failed", it) }
                    vm.nextcloudLogin.value.phase == NextcloudLoginPhase.WAITING_FOR_BROWSER &&
                        !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && loginPageRequested.get()
                }
            } catch (error: Throwable) {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val artifact = java.io.File(context.getExternalFilesDir("validation"), "nextcloud-browser.png")
                automation.takeScreenshot()?.let { bitmap ->
                    artifact.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                fun visibleText(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> =
                    if (node == null) emptyList() else listOfNotNull(node.text?.toString()) +
                        (0 until node.childCount).flatMap { visibleText(node.getChild(it)) }
                throw AssertionError("Browser=${automation.rootInActiveWindow?.packageName}, lifecycle=${activity.lifecycle.currentState}, login=${vm.nextcloudLogin.value}, pageLoaded=${loginPageRequested.get()}, browserTexts=${visibleText(automation.rootInActiveWindow)}", error)
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK"),
            ).use { it.readBytes() }
            compose.waitUntil(15_000) { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            compose.onNodeWithTag("nextcloud_login_dialog").assertIsDisplayed()
            assertTrue(vm.nextcloudLogin.value.open)
            assertEquals(endpoint, vm.nextcloudLogin.value.server)
            assertEquals(NextcloudLoginPhase.WAITING_FOR_BROWSER, vm.nextcloudLogin.value.phase)
            assertTrue(vm.nextcloudLogin.value.error == null)
        } finally {
            compose.runOnUiThread { vm.cancelNextcloudSetup() }
            active.set(false)
            server.close()
            worker.join(5_000)
        }
    }

    /** Explicit emulator-only setup: no account is added, and usage/crash reporting stays off. */
    private fun prepareChromeEmulatorFixture(context: android.content.Context, pageLoaded: AtomicBoolean) {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true)) { "Browser setup is emulator-only" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun matching(text: String) = automation.rootInActiveWindow
            ?.takeIf { it.packageName == "com.android.chrome" }?.findAccessibilityNodeInfosByText(text).orEmpty()
        compose.waitUntil(10_000) {
            pageLoaded.get() || matching("Use without an account").isNotEmpty() || matching("Make Chrome better").isNotEmpty()
        }
        if (pageLoaded.get()) return
        if (matching("Make Chrome better").isEmpty()) {
            val consent = matching("Manage").first()
            val text = consent.text as? android.text.Spanned
            val manage = text?.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)
                ?.firstOrNull { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() == "Manage" }
            if (manage != null) manage.onClick(android.view.View(context))
            else clickBrowserNode(consent)
        }
        compose.waitUntil(5_000) { matching("Make Chrome better").isNotEmpty() }
        fun switches(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
            if (node == null) emptyList() else (if (node.isCheckable) listOf(node) else emptyList()) +
                (0 until node.childCount).flatMap { switches(node.getChild(it)) }
        val privacySwitch = switches(automation.rootInActiveWindow).single()
        if (privacySwitch.isChecked) clickBrowserNode(privacySwitch)
        compose.waitUntil(5_000) { switches(automation.rootInActiveWindow).singleOrNull()?.isChecked == false }
        clickBrowserNode(matching("Done").first())
        compose.waitUntil(5_000) { matching("Use without an account").isNotEmpty() && matching("Make Chrome better").isEmpty() }
        clickBrowserNode(matching("Use without an account").first())
    }

    private fun clickBrowserNode(node: android.view.accessibility.AccessibilityNodeInfo) {
        val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
        assertTrue("The observed browser control must be clickable",
            clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
