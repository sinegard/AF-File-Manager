package com.affilemanager.app.apk

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitApkInstallerTest {
    @Test(timeout = 120_000)
    fun originalSplitSetRequiresAndroidConfirmationAndMismatchedSignaturesNeverInstall() {
        val test = InstrumentationRegistry.getInstrumentation()
        // Use the same strict real-system workflow as normal R8: wait for the
        // terminal install callback before uninstalling, then reject a different
        // signer, exercise Cancel and check owned staging cleanup. No data reset.
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        test.startActivitySync(Intent(Intent.ACTION_MAIN)
            .setClassName(test.targetContext, "com.affilemanager.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(SplitApkRuntimeVerifier.verify(test))
    }
}
