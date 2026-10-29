package com.affilemanager.benchmark

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Captures the actual missing-control screen without relaxing a performance assertion. */
class BenchmarkNavigationDiagnostic {
    @Test fun largeDirectoryKeepsItsScrollableAccessibilityContainer() {
        val test = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(test)
        AfBenchmarkEnvironment.prepareTarget(device)
        test.context.startActivity(AfBenchmarkEnvironment.localIntent("large"))
        val content = device.wait(Until.hasObject(By.res("file_list_content_LEFT")), 30_000)
        SystemClock.sleep(500)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val folder = File(output?.let(::File) ?: test.context.getExternalFilesDir(null), "navigation-diagnostic").apply { check(mkdirs()) }
        fun capture(step: Int) {
            device.dumpWindowHierarchy(File(folder, "large-directory-$step.xml"))
            device.takeScreenshot(File(folder, "large-directory-$step.png"))
        }
        capture(0)
        assertTrue("First directory content was not shown", content)
        repeat(3) { step ->
            val visible = device.wait(Until.hasObject(By.res("file_list_LEFT")), 30_000)
            capture(step + 1)
            assertTrue("The real list disappeared before scroll $step", visible)
            // This is a short-viewport functional check, not the unchanged
            // high-speed Macrobenchmark fling. A very short synthetic fling
            // can become a tap before Android crosses its touch-slop threshold.
            val bounds = device.findObject(By.res("file_list_LEFT")).visibleBounds
            assertTrue("The permission notice must leave usable browsing space", bounds.height() >= 96)
            assertTrue(device.swipe(bounds.centerX(), bounds.bottom - 24, bounds.centerX(), bounds.top + 24, 30))
        }
        capture(4)
        assertTrue("Dragging must scroll the list rather than open its first file", device.hasObject(By.res("file_list_LEFT")))
        assertTrue("Three real drags must change the visible files", !device.hasObject(By.text("item-00000.txt")))
    }
}
