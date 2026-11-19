package com.affilemanager.app.transfer

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.affilemanager.app.MainActivity
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class NearbyReceiverLifetimeTest {
    // Use the actual Activity lifecycle for Home/screen-off, without an ActivityScenario
    // forcing or closing a transition the Android launcher owns in this fixture.
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val payload = "Owned AF idle regression fixture\n".toByteArray()

    @Test fun manualReceiverRemainsAvailableAfterBackgroundIdle() = withManualReceiver { root, before ->
        val cookie = approveFixture(before)
        // A second request may not replace the current notification or foreground type.
        context.startForegroundService(Intent(context, LanTransferService::class.java)
            .setAction(LanTransferService.ACTION_START)
            .putExtra(LanTransferService.EXTRA_ROOT, root.path)
            .putExtra(LanTransferService.EXTRA_DURATION_MINUTES, 5)
            .putExtra(LanTransferService.EXTRA_PROTOCOL, LanTransferProtocol.WEB.name))
        await("Duplicate start did not reach the service") { LanTransferController.state.value.message == "LAN sesija jau veikia" }
        assertSameListener(before)
        assertTrue("Duplicate start replaced the running notification", hasReceiveNotification())
        compose.onNodeWithTag("nearby_receive_close").performClick()
        val power = context.getSystemService(PowerManager::class.java)
        val idleMillis = InstrumentationRegistry.getArguments().getString("afManualIdleMillis")?.toLong()
            ?.also { require(it in 1_000L..360_000L) } ?: 2_000L
        try {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            await("AF did not go to the background") { !activityResumed() }
            shell("input keyevent KEYCODE_SLEEP")
            await("Screen did not sleep") { !power.isInteractive }
            val time = SystemClock.elapsedRealtime()
            Log.i("AFIssue276", "idle-start screenOff=true requestedMs=$idleMillis notification=${hasReceiveNotification()}")
            // No network traffic, pings, or listening-service restarts during this wait.
            SystemClock.sleep(idleMillis)
            check(!power.isInteractive) { "Screen woke during idle" }
            assertSameListener(before)
            assertTrue("Receive notification disappeared in the background", hasReceiveNotification())
            Log.i("AFIssue276", "idle-end elapsedMs=${SystemClock.elapsedRealtime() - time} sameSession=true notification=true")
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            openMainActivity()
        }
        assertSameListener(before)
        compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
        assertEquals("Original approval no longer writes", 201, upload(before, cookie))
        assertTrue(File(root, "owned.txt").readBytes().contentEquals(payload))
        awaitDetails()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
        compose.onNodeWithTag("nearby_receive_stop").performScrollTo().performClick()
        awaitEnded()
        assertTrue(File(root, "owned.txt").readBytes().contentEquals(payload))
        Log.i("AFIssue276", "post-idle writeVerified=true explicitStop=true")
    }

    @Test fun systemServiceDestructionClearsTheActiveSessionAndPreservesCompletedFiles() = withManualReceiver { root, before ->
        val cookie = approveFixture(before)
        assertEquals(201, upload(before, cookie))
        awaitDetails()
        // Request teardown at the Android service boundary, without AF's Stop action.
        assertTrue(context.stopService(Intent(context, LanTransferService::class.java)))
        awaitEnded(LanTransferStatus.ERROR)
        compose.onNodeWithTag("nearby_transfer_details").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
        compose.onNodeWithTag("nearby_receive_dialog").assertIsDisplayed()
        compose.onNodeWithTag("nearby_receive_stop").assertDoesNotExist()
        compose.onNode(hasText("LAN service stopped") and hasAnyAncestor(hasTestTag("nearby_receive_dialog")))
            .performScrollTo().assertIsDisplayed()
        assertTrue(File(root, "owned.txt").readBytes().contentEquals(payload))
        compose.onNodeWithTag("nearby_receive_network").performScrollTo().performClick()
        val replacement = awaitReceiver()
        assertTrue("Receiver still used the ended session", before.receiveSessionId != replacement.receiveSessionId)
        assertEquals(401, upload(replacement, cookie))
        assertTrue(File(root, "owned.txt").readBytes().contentEquals(payload))
    }

    private fun withManualReceiver(block: (File, LanTransferState) -> Unit) {
        check(Build.MODEL.contains("sdk", ignoreCase = true)) { "Owned emulator only" }
        assertEquals(LanTransferStatus.STOPPED, LanTransferController.state.value.status)
        val activity = openMainActivity()
        val vm = compose.runOnUiThread { ViewModelProvider(activity)[MainViewModel::class.java] }
        val original = vm.shareScreenPreferences.value
        @Suppress("DEPRECATION")
        val root = File(Environment.getExternalStorageDirectory(), "AF-issue276-${UUID.randomUUID()}")
        check(root.mkdir()) { "Could not create test fixture" }
        try {
            compose.runOnUiThread {
                vm.updateShareScreenPreferences { it.withNearbyReceivePath(root.path).copy(durationMinutes = 15) }
                vm.setSection(AppSection.SHARE)
            }
            compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
            compose.onNodeWithTag("nearby_receive_duration").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
            compose.onNodeWithTag("nearby_receive_network").performScrollTo().performClick()
            val running = awaitReceiver()
            assertEquals(LanSessionDuration.MANUAL_EXPIRY, running.expiresAtMillis)
            assertTrue("Receiver did not use the owned fixture", running.rootPath == root.path)
            block(root, running)
        } finally {
            if (LanTransferController.state.value.status in setOf(LanTransferStatus.STARTING, LanTransferStatus.RUNNING)) {
                LanTransferController.stop(context)
                await("Receiver teardown did not finish") { !hasReceiveNotification() }
            }
            compose.runOnUiThread {
                vm.updateShareScreenPreferences { original }
                activity.finishAndRemoveTask()
            }
            File(root, "owned.txt").takeIf(File::isFile)?.let { check(it.delete()) }
            check(root.delete()) { "Owned fixture has unexpected leftover files" }
        }
    }

    private fun awaitReceiver(): LanTransferState {
        await("Manual receiver or its notification failed to start") {
            LanTransferController.state.value.let { it.status == LanTransferStatus.RUNNING && it.phoneReceiver } && hasReceiveNotification()
        }
        return LanTransferController.state.value.also { check(it.receiveSessionId != null && it.url != null && it.code != null) }
    }

    private fun awaitEnded(expected: LanTransferStatus = LanTransferStatus.STOPPED) {
        await("Receive notification was not removed") { !hasReceiveNotification() }
        await("Inactive listener was still presented as running") { LanTransferController.state.value.status == expected }
        val ended = LanTransferController.state.value
        assertTrue(ended.url == null && ended.code == null && ended.receiveSessionId == null && ended.pendingSenders.isEmpty())
    }

    private fun assertSameListener(before: LanTransferState) {
        val current = LanTransferController.state.value
        assertEquals(LanTransferStatus.RUNNING, current.status)
        assertTrue("The original listener was replaced", current.receiveSessionId == before.receiveSessionId && current.url == before.url)
        assertEquals(LanSessionDuration.MANUAL_EXPIRY, current.expiresAtMillis)
    }

    private fun hasReceiveNotification(): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == 41 && it.notification.channelId == "lan_transfer" &&
            it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 && !it.notification.actions.isNullOrEmpty() }

    private fun approveFixture(state: LanTransferState): String {
        assertEquals("Receiver accepted bytes before approval", 401, upload(state, null))
        val form = "code=${URLEncoder.encode(requireNotNull(state.code), "UTF-8")}" +
            "&client=${UUID.randomUUID().toString().replace("-", "")}&name=Owned%20idle%20sender"
        val ticket = request(state, "/login", form.toByteArray(), mapOf("X-AF-Sender-Approval" to "1",
            "Content-Type" to "application/x-www-form-urlencoded")) {
            assertEquals(202, responseCode)
            requireNotNull(getHeaderField("X-AF-Approval-Request"))
        }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("nearby_sender_allow").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nearby_sender_allow").performClick()
        var approved: String? = null
        await("Explicit owner approval did not reach sender") {
            approved = request(state, "/nearby/approval", byteArrayOf(), mapOf("X-AF-Approval-Request" to ticket)) {
                if (responseCode == 200) getHeaderField("Set-Cookie")?.substringBefore(';') else null
            }
            approved != null
        }
        return requireNotNull(approved)
    }

    private fun upload(state: LanTransferState, cookie: String?): Int = request(
        state, "/upload?name=owned.txt", payload, if (cookie == null) emptyMap() else mapOf("Cookie" to cookie),
    ) { responseCode }

    private fun <T> request(state: LanTransferState, path: String, data: ByteArray, headers: Map<String, String>,
        result: HttpURLConnection.() -> T): T {
        val connection = URL(requireNotNull(state.url) + path.removePrefix("/")).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 3_000
            connection.requestMethod = "POST"
            headers.forEach(connection::setRequestProperty)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(data.size)
            connection.outputStream.use { it.write(data) }
            return connection.result()
        } finally { connection.disconnect() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        do {
            if (condition()) return
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }

    private fun openMainActivity(): MainActivity {
        shell("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
        var activity: MainActivity? = null
        await("AF did not return to the foreground") { resumedActivity().also { activity = it } != null }
        return requireNotNull(activity)
    }

    private fun activityResumed(): Boolean = resumedActivity() != null

    private fun resumedActivity(): MainActivity? {
        var resumed: MainActivity? = null
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()
        }
        return resumed
    }

    private fun awaitDetails() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nearby_transfer_details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nearby_transfer_details").assertIsDisplayed()
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }
}
