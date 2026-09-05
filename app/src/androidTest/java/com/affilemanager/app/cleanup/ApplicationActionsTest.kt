package com.affilemanager.app.cleanup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationActionsTest {
    @Test fun uninstallOpensAndroidConfirmationAndCancelKeepsThePackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val fixture = instrumentation.context.packageName
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        try {
            // Use only platform APIs so the same regression can run against the preceding APK.
            app.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", fixture, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var cancel: AccessibilityNodeInfo? = null
            while (cancel == null && SystemClock.elapsedRealtime() < deadline) {
                cancel = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button2")?.firstOrNull()
                if (cancel == null) SystemClock.sleep(50)
            }
            assertNotNull("Android uninstall confirmation did not open", cancel)
            assertTrue(cancel!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertNotNull(app.packageManager.getPackageInfo(fixture, 0))
            assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.REQUEST_DELETE_PACKAGES))
        } finally {
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    @Test fun allAppActionsPreservePackageIdentityAndRejectNonPackageInput() {
        val packageName = "com.affilemanager.app.debug"
        assertEquals("package:$packageName", ApplicationActions.settingsIntent(packageName).dataString)
        assertEquals("package:$packageName", ApplicationActions.uninstallIntent(packageName).dataString)
        assertTrue(ApplicationActions.uninstallIntent(packageName).flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ApplicationActions.uninstallIntent("com.app?other") }
    }
}
