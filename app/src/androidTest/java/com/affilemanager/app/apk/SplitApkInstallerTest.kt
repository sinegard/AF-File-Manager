package com.affilemanager.app.apk

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SplitApkInstallerTest {
    @Test fun originalSplitSetRequiresAndroidConfirmationAndMismatchedSignaturesNeverInstall() {
        // Synthetic package install/uninstall is restricted to our disposable test emulator.
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun waitForText(timeout: Long, matches: (String) -> Boolean): AccessibilityNodeInfo? {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (matches(node.text?.toString().orEmpty())) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            val deadline = System.nanoTime() + timeout * 1_000_000L
            do {
                find(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
                Thread.sleep(100)
            } while (System.nanoTime() < deadline)
            return null
        }
        val root = File(app.cacheDir, "split-installer-test-${UUID.randomUUID()}").apply { mkdir() }
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
        fun installed() = runCatching { app.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0) }.getOrNull()
        assertNull("The isolated fixture package must not pre-exist this test", installed())
        fun archive(name: String) = File(root, "$name.apks").also { file ->
            instrumentation.context.assets.open("split-apk/$name.apks").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        fun launch(file: File): ActivityScenario<SplitApkInstallActivity> {
            val plan = SplitApkArchive.plan(file)
            return ActivityScenario.launch(Intent(app, SplitApkInstallActivity::class.java)
                .putExtra("archive", file.path).putStringArrayListExtra("parts", ArrayList(plan.parts.map { it.name })))
        }
        try {
            shell("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
            launch(archive("valid")).use {
                val install = waitForText(15_000) { it.equals("Install", ignoreCase = true) }
                assertNotNull("Android must show its installation confirmation", install)
                assertNull(installed())
                assertTrue(requireNotNull(install).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val deadline = System.nanoTime() + 15_000_000_000L
                while (installed() == null && System.nanoTime() < deadline) Thread.sleep(100)
                val info = requireNotNull(installed())
                assertTrue(info.splitNames.orEmpty().contains("config.en"))
                assertTrue(File(app.cacheDir, "split-apk-install").listFiles().orEmpty().isEmpty())
            }
            shell("pm uninstall $FIXTURE_PACKAGE")
            assertNull(installed())
            launch(archive("wrong-signature")).use {
                // Android may reject before or after confirmation, depending on platform version.
                waitForText(3_000) { it.equals("Install", ignoreCase = true) }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val failure = waitForText(15_000) { Regex("signatures|certificates|CERTIFICATE|INVALID_APK", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                assertNotNull("The signer mismatch must remain visible", failure)
                assertNull(installed())
                assertTrue(File(app.cacheDir, "split-apk-install").listFiles().orEmpty().isEmpty())
            }
        } finally {
            if (installed() != null) shell("pm uninstall $FIXTURE_PACKAGE")
            root.deleteRecursively()
        }
    }
    companion object { private const val FIXTURE_PACKAGE = "com.affilemanager.fixture.split" }
}
