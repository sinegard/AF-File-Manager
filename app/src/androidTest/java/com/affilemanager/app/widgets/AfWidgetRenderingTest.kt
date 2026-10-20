package com.affilemanager.app.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.media.editing.MediaTestFixtures
import com.affilemanager.app.ui.localization.appLanguageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AfWidgetRenderingTest {
    private val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun updatesKeepOnePeriodicOwnerAndStopItsWorkAfterTheLastWidgetIsRemoved() {
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 2026271)
        val provider = ComponentName(app, AfWidgetProvider::class.java)
        val before = manager.getAppWidgetIds(provider).toSet()
        val work = androidx.work.WorkManager.getInstance(app)
        var id = -1
        fun records(name: String) = work.getWorkInfosForUniqueWork(name).get(5, java.util.concurrent.TimeUnit.SECONDS)
        try {
            shell("appwidget grantbind --package ${app.packageName} --user 0")
            id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, provider))
            assertEquals("Only WorkManager may own periodic refreshes", 0, manager.getAppWidgetInfo(id).updatePeriodMillis)
            AfWidgetStore(app).save(id, AfWidgetConfig(AfWidgetKind.STORAGE, folder = app.cacheDir.path))
            AfWidgetProvider.refresh(app)
            val periodic = records("af-home-widgets-periodic")
            assertEquals("Configured widgets need one persistent periodic owner, not a component-change refresh loop", 1, periodic.size)
            val periodicId = periodic.single().id
            assertFalse(periodic.single().state.isFinished)
            assertEquals(30 * 60_000L, periodic.single().periodicityInfo?.repeatIntervalMillis)
            assertEquals(30 * 60_000L, periodic.single().initialDelayMillis)
            val firstManual = records("af-home-widgets").map { it.id }.toSet()
            AfWidgetProvider.refresh(app)
            assertEquals(setOf(periodicId), records("af-home-widgets-periodic").map { it.id }.toSet())
            assertTrue("Explicit refresh must still enqueue fresh work", records("af-home-widgets").any { it.id !in firstManual })
            // PackageManager batches initial component changes for up to ten seconds
            // on Android 8 (and during newer-system startup). Settle beyond that
            // window; a repeating one- or ten-second loop cannot become stable.
            var manualIds = records("af-home-widgets").map { it.id }.toSet()
            var stableSince = android.os.SystemClock.uptimeMillis()
            val settleDeadline = stableSince + 30_000
            do {
                val latest = records("af-home-widgets")
                val latestIds = latest.map { it.id }.toSet()
                if (latestIds != manualIds || latest.any { !it.state.isFinished }) {
                    manualIds = latestIds
                    stableSince = android.os.SystemClock.uptimeMillis()
                }
                android.os.SystemClock.sleep(50)
            } while (android.os.SystemClock.uptimeMillis() - stableSince < 11_000 && android.os.SystemClock.uptimeMillis() < settleDeadline)
            assertTrue("Explicit widget refresh did not settle", android.os.SystemClock.uptimeMillis() - stableSince >= 11_000)
            val quietDeadline = android.os.SystemClock.uptimeMillis() + 3_000
            do {
                assertEquals("An idle widget started another unsolicited refresh", manualIds, records("af-home-widgets").map { it.id }.toSet())
                assertEquals(setOf(periodicId), records("af-home-widgets-periodic").map { it.id }.toSet())
                android.os.SystemClock.sleep(100)
            } while (android.os.SystemClock.uptimeMillis() < quietDeadline)
            AfWidgetStore(app).remove(id)
            host.deleteAppWidgetId(id)
            AfWidgetProvider().onDeleted(app, intArrayOf(id))
            if (before.isEmpty()) {
                assertTrue(records("af-home-widgets-periodic").all { it.state.isFinished })
                assertTrue(records("af-home-widgets").all { it.state.isFinished })
            }
        } finally {
            if (id > 0) { AfWidgetStore(app).remove(id); host.deleteAppWidgetId(id); AfWidgetProvider().onDeleted(app, intArrayOf(id)) }
            shell("appwidget revokebind --package ${app.packageName} --user 0")
        }
    }
    @Test fun noTransitionUsesZeroDurationAnimatorsForEverySlideshowInterval() {
        instrumentation.runOnMainSync {
            for (seconds in listOf(5, 15, 30, 60, 300)) {
                val config = AfWidgetConfig(AfWidgetKind.IMAGES, folder = app.cacheDir.path,
                    slideSeconds = seconds, transition = AfWidgetTransition.NONE)
                val flipper = android.widget.RemoteViews(app.packageName, AfWidgetRenderer.slidesLayout(config))
                    .apply(app, FrameLayout(app)) as android.widget.AdapterViewFlipper
                assertEquals("None must not inherit Android's default incoming fade ($seconds)", 0L, flipper.inAnimation.duration)
                assertEquals("None must not inherit Android's default outgoing fade ($seconds)", 0L, flipper.outAnimation.duration)
            }
        }
    }
    @Test fun frameworkLoadingViewCannotReuseACollectionRowOrItsActions() {
        val loading = SlidesFactory(app, -1).getLoadingView()
        assertNotEquals("The framework loading view must not reuse the interactive image row", R.layout.af_widget_slide, loading.layoutId)
        val view = loading.apply(app, FrameLayout(app))
        assertNull(view.findViewById<android.view.View>(R.id.widget_slide_image))
        assertNull(view.findViewById<android.view.View>(R.id.widget_slide_previous))
        assertNull(view.findViewById<android.view.View>(R.id.widget_slide_next))
        assertFalse(view.hasOnClickListeners())
        assertEquals(AfWidgetRenderer.text(app, "Ruošiama…"), view.findViewById<TextView>(R.id.widget_slide_name).text.toString())
    }
    @Test fun nativeWidgetDirectionFollowsAfInsteadOfTheLauncherLanguage() = runBlocking {
        val host = AppWidgetHost(app, 2026260)
        val manager = AppWidgetManager.getInstance(app)
        val original = if (android.os.Build.VERSION.SDK_INT >= 33) androidx.core.app.LocaleManagerCompat.getApplicationLocales(app)
            else androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        var id = -1
        try {
            shell("appwidget grantbind --package ${app.packageName} --user 0")
            id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, AfWidgetProvider::class.java)))
            val config = AfWidgetConfig(AfWidgetKind.STORAGE, folder = app.cacheDir.path)
            AfWidgetStore(app).save(id, config)
            for (tag in listOf("ar", "en", "lt")) {
                instrumentation.runOnMainSync { com.affilemanager.app.ui.localization.AppLanguageManager.setLanguage(app, tag) }
                assertEquals("Background language change was not saved", tag,
                    app.appLanguageContext().resources.configuration.locales[0].language)
                val views = AfWidgetRenderer.render(app, id, config)
                val expected = if (tag == "ar") android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
                val launcher = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(if (tag == "ar") "en" else "ar"))
                })
                instrumentation.runOnMainSync {
                    val parent = FrameLayout(launcher).apply { layoutDirection = if (tag == "ar") android.view.View.LAYOUT_DIRECTION_LTR else android.view.View.LAYOUT_DIRECTION_RTL }
                    val view = views.apply(launcher, parent)
                    parent.addView(view)
                    parent.measure(android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
                    parent.layout(0, 0, 600, 600)
                    assertEquals("Widget followed the launcher instead of AF's $tag language", expected, view.layoutDirection)
                    if (tag == "en" || tag == "lt") {
                        val labels = listOf(
                            Triple(R.id.widget_settings, "Settings", "Nustatymai"),
                            Triple(R.id.widget_refresh, "Refresh", "Atnaujinti"),
                            Triple(R.id.widget_previous, "Previous", "Ankstesnis"),
                            Triple(R.id.widget_next, "Next", "Kitas"),
                            Triple(R.id.widget_play, "Play", "Leisti"),
                            Triple(R.id.widget_stop, "Stop", "Sustabdyti"))
                        labels.forEach { (id, english, lithuanian) ->
                            assertEquals("Widget accessibility language: $tag / $id", if (tag == "en") english else lithuanian,
                                view.findViewById<android.view.View>(id).contentDescription.toString())
                        }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                if (android.os.Build.VERSION.SDK_INT >= 33) app.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                    android.os.LocaleList.forLanguageTags(original.toLanguageTags())
                else androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(original)
            }
            if (id > 0) { AfWidgetStore(app).remove(id); host.deleteAppWidgetId(id) }
            host.stopListening()
            shell("appwidget revokebind --package ${app.packageName} --user 0")
        }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun actualHomeLauncherChangesAndOpensTheImageWhileAfIsInTheBackground() {
        assertTrue(PinnedWidgetRuntimeVerifier.verify(instrumentation))
    }

    @Test fun storageWidgetVisibleBodyOpensAnalysisInTheSameRunningAfActivity() = runBlocking {
        val host = AppWidgetHost(app, 2026264)
        val manager = AppWidgetManager.getInstance(app)
        val wasLocked = app.graph.appLock.enabled.value
        var id = -1
        var current: MainActivity? = null
        try {
            app.graph.appLock.setEnabled(false)
            shell("appwidget grantbind --package ${app.packageName} --user 0")
            id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, AfWidgetProvider::class.java)))
            val config = AfWidgetConfig(AfWidgetKind.STORAGE, folder = app.cacheDir.path)
            AfWidgetStore(app).save(id, config)
            current = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity = current
            var widget: android.appwidget.AppWidgetHostView? = null
            var model: com.affilemanager.app.ui.MainViewModel? = null
            instrumentation.runOnMainSync {
                model = androidx.lifecycle.ViewModelProvider(activity)[com.affilemanager.app.ui.MainViewModel::class.java]
                model!!.setSection(com.affilemanager.app.ui.AppSection.FILES)
                host.startListening()
                widget = host.createView(activity, id, manager.getAppWidgetInfo(id))
                activity.findViewById<FrameLayout>(android.R.id.content).addView(widget, FrameLayout.LayoutParams(600, 600))
            }
            manager.updateAppWidget(id, AfWidgetRenderer.render(app, id, config))
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { ready = widget?.findViewById<TextView>(R.id.widget_details)?.let { it.isShown && it.text.isNotBlank() } == true }
                if (!ready) android.os.SystemClock.sleep(40)
            }
            assertTrue("Storage widget did not render its visible body", ready)
            instrumentation.runOnMainSync { assertTrue("Visible widget content has no explicit action", widget!!.findViewById<TextView>(R.id.widget_details).performClick()) }
            val openedBy = android.os.SystemClock.elapsedRealtime() + 10_000
            while (model!!.section.value != com.affilemanager.app.ui.AppSection.ANALYZE && android.os.SystemClock.elapsedRealtime() < openedBy) android.os.SystemClock.sleep(40)
            assertEquals(com.affilemanager.app.ui.AppSection.ANALYZE, model!!.section.value)
            assertFalse(activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { current?.finish() }
            host.stopListening()
            if (id > 0) { AfWidgetStore(app).remove(id); host.deleteAppWidgetId(id) }
            app.graph.appLock.setEnabled(wasLocked)
            shell("appwidget revokebind --package ${app.packageName} --user 0")
        }
    }

    @Test fun indexUpdatesDoNotOverwriteEditedOrDeletedWidgetSettingsAcrossStoreInstances() {
        val name = "widget-cas-${UUID.randomUUID()}"
        val first = AfWidgetStore(app, name)
        val second = AfWidgetStore(app, name)
        val initial = AfWidgetConfig(AfWidgetKind.IMAGES, folder = app.cacheDir.path)
        try {
            first.save(1, initial)
            second.save(1, initial.copy(title = "Edited by the user"))
            assertFalse(first.compareAndSet(1, initial, initial.copy(index = 1)))
            assertEquals("Edited by the user", first.load(1)?.title)
            second.remove(1)
            assertFalse(first.compareAndSet(1, initial, initial.copy(index = 1)))
            assertNull(first.load(1))
            first.save(1, initial)
            val start = java.util.concurrent.CountDownLatch(1)
            val results = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
            val workers = (1..2).map { index -> Thread {
                start.await(); results += AfWidgetStore(app, name).compareAndSet(1, initial, initial.copy(index = index))
            }.apply { start() } }
            start.countDown(); workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertEquals(1, results.count { it })
            assertTrue(first.load(1)?.index in setOf(1, 2))
        } finally { first.remove(1) }
    }

    @Test fun aReusedCollectionPlaceholderClearsItsOldBitmapAndControls() {
        val view = android.widget.RemoteViews(app.packageName, R.layout.af_widget_slide).apply {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            setImageViewBitmap(R.id.widget_slide_image, bitmap)
        }.apply(app, FrameLayout(app))
        assertNotNull(view.findViewById<android.widget.ImageView>(R.id.widget_slide_image).drawable)
        // An unavailable collection row reuses the collection layout; the
        // separate framework loading layout is checked above and is not reused.
        SlidesFactory(app, -1).getViewAt(0).reapply(app, view)
        assertNull(view.findViewById<android.widget.ImageView>(R.id.widget_slide_image).drawable)
        assertEquals(android.view.View.INVISIBLE, view.findViewById<android.view.View>(R.id.widget_slide_image).visibility)
        assertEquals(android.view.View.GONE, view.findViewById<android.view.View>(R.id.widget_slide_previous).visibility)
        assertEquals(android.view.View.GONE, view.findViewById<android.view.View>(R.id.widget_slide_next).visibility)
    }

    @Test fun musicControlsPauseResumeSkipAndStopTheExistingPlayer() = runBlocking {
        val root = File(app.cacheDir, "widget-music-${UUID.randomUUID()}").apply { check(mkdir()) }
        val one = root.resolve("one.wav").also { MediaTestFixtures.wav(it) }
        val two = root.resolve("two.wav").also { MediaTestFixtures.wav(it) }
        val host = AppWidgetHost(app, 2026256)
        val manager = AppWidgetManager.getInstance(app)
        var id = -1
        val locked = app.graph.appLock.enabled.value
        val scenario = ActivityScenario.launch<androidx.activity.ComponentActivity>(Intent(app, androidx.activity.ComponentActivity::class.java))
        fun waitFor(predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (predicate()) return
                android.os.SystemClock.sleep(40)
            }
            fail("Music widget did not change the real playback state")
        }
        fun control(action: String) = instrumentation.runOnMainSync {
            AfWidgetProvider().onReceive(app, Intent(app, AfWidgetProvider::class.java)
                .setAction("com.affilemanager.app.CONTROL_HOME_WIDGET").putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AfWidgetProvider.EXTRA_ACTION, action))
        }
        val playback = com.affilemanager.app.media.BackgroundPlaybackService
        try {
            app.graph.appLock.setEnabled(false)
            shell("appwidget grantbind --package ${app.packageName} --user 0")
            id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, AfWidgetProvider::class.java)))
            val config = AfWidgetConfig(AfWidgetKind.MUSIC, folder = root.path, sources = listOf(one.path, two.path))
            AfWidgetStore(app).save(id, config)
            instrumentation.runOnMainSync { playback.play(app, android.net.Uri.fromFile(one), one.name, 0, true, 1f, 1f,
                listOf(com.affilemanager.app.media.BackgroundMediaItem(android.net.Uri.fromFile(one).toString(), one.name),
                    com.affilemanager.app.media.BackgroundMediaItem(android.net.Uri.fromFile(two).toString(), two.name))) }
            waitFor { playback.state.value?.phase == com.affilemanager.app.media.BackgroundPlaybackPhase.PLAYING }
            control("play"); waitFor { playback.state.value?.phase == com.affilemanager.app.media.BackgroundPlaybackPhase.PAUSED }
            control("play"); waitFor { playback.state.value?.phase == com.affilemanager.app.media.BackgroundPlaybackPhase.PLAYING }
            control("next"); waitFor { playback.state.value?.uri == android.net.Uri.fromFile(two).toString() && playback.state.value?.phase == com.affilemanager.app.media.BackgroundPlaybackPhase.PLAYING }
            val views = AfWidgetRenderer.render(app, id, config)
            instrumentation.runOnMainSync {
                val view = views.apply(app, FrameLayout(app))
                assertTrue(view.findViewById<TextView>(R.id.widget_details).text.toString().contains(two.name))
                assertEquals(android.view.View.VISIBLE, view.findViewById<android.view.View>(R.id.widget_stop).visibility)
            }
            control("stop"); waitFor { playback.state.value == null &&
                app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == com.affilemanager.app.media.BackgroundPlaybackService.NOTIFICATION_ID } }
        } finally {
            instrumentation.runOnMainSync { playback.stop(app) }
            if (id > 0) { AfWidgetStore(app).remove(id); host.deleteAppWidgetId(id) }
            host.stopListening(); scenario.close()
            app.graph.appLock.setEnabled(locked)
            shell("appwidget revokebind --package ${app.packageName} --user 0")
            root.deleteRecursively()
        }
    }

    @Test fun imageCollectionBindsInAnActualHostAndItsFrameOpensTheSameSource() = exerciseImageHost(false)
    @Test fun imageCollectionOpensTheVisibleSourceWhenAfIsAlreadyRunning() = exerciseImageHost(true)

    private fun exerciseImageHost(warm: Boolean) = runBlocking {
        val root = File(app.cacheDir, "widget-host-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("widget-blue.png")
        val photo = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        source.outputStream().use { check(photo.compress(Bitmap.CompressFormat.PNG, 100, it)) }; photo.recycle()
        val host = AppWidgetHost(app, 2026255)
        val manager = AppWidgetManager.getInstance(app)
        val wasLocked = app.graph.appLock.enabled.value
        var id = -1
        var hostActivity: androidx.activity.ComponentActivity? = null
        var warmActivity: MainActivity? = null
        var warmTaskId: Int? = null
        var activity: MainActivity? = null
        var primaryFailure: Throwable? = null
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val routeEvents = arrayListOf<String>()
        val trace = androidx.test.runner.lifecycle.ActivityLifecycleCallback { opened, stage ->
            if (opened is MainActivity) {
                val request = AfWidgetProvider.request(app, opened.intent)
                val config = AfWidgetStore(app).load(id)
                val files = config?.sources?.map(::File).orEmpty()
                val pieces = request?.action?.split('-').orEmpty()
                val matching = pieces.getOrNull(1)?.toIntOrNull()?.let(files::getOrNull)?.let { SlidesFactory.fingerprint(it) == pieces.getOrNull(2) }
                val event = "AF_WIDGET_ENTRY warm=$warm stage=$stage instance=${System.identityHashCode(opened)} changing=${opened.isChangingConfigurations} component=${opened.intent.component} action=${opened.intent.action} request=$request files=${files.size} matching=$matching"
                if (routeEvents.size < 64) routeEvents += event
                System.out.println(event)
            }
        }
        try {
            monitor.addLifecycleCallback(trace)
            shell("appwidget grantbind --package ${app.packageName} --user 0")
            app.graph.appLock.setEnabled(false)
            if (warm) {
                warmActivity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                warmTaskId = warmActivity.taskId
            }
            host.deleteHost() // Only this test's fixed host, including a prior interrupted attempt.
            id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, AfWidgetProvider::class.java)))
            val sources = listOf(source) + (1..31).map { index -> root.resolve("z$index.png").also { source.copyTo(it) } }
            // The next frame must have different actual pixels: identical blue
            // copies cannot prove the host has finished drawing the new row.
            val nextBitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
            try { sources[1].outputStream().use { assertTrue(nextBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { nextBitmap.recycle() }
            val config = AfWidgetConfig(AfWidgetKind.IMAGES, folder = root.path, sources = sources.map(File::getPath), slideSeconds = 300, transition = AfWidgetTransition.NONE)
            AfWidgetStore(app).save(id, config)
            // ActivityScenario's default CLEAR_TASK would destroy a warm AF task and turn
            // this into another cold launch. A launcher-like host must leave that task intact.
            hostActivity = instrumentation.startActivitySync(Intent(app, androidx.activity.ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as androidx.activity.ComponentActivity
            var widget: android.appwidget.AppWidgetHostView? = null
            instrumentation.runOnMainSync {
                val current = requireNotNull(hostActivity)
                host.startListening()
                widget = host.createView(current, id, manager.getAppWidgetInfo(id))
                current.findViewById<FrameLayout>(android.R.id.content).addView(widget, FrameLayout.LayoutParams(600, 700))
            }
            manager.updateAppWidget(id, AfWidgetRenderer.render(app, id, config))
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_flipper)
            fun <T : android.view.View> descendant(view: android.view.View?, resource: Int): T? {
                if (view == null) return null
                @Suppress("UNCHECKED_CAST")
                if (view.id == resource) return view as T
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                    descendant<T>(view.getChildAt(index), resource)?.let { return it }
                }
                return null
            }
            fun currentFrame() = widget?.findViewById<android.widget.AdapterViewFlipper>(R.id.widget_flipper)?.currentView
            fun waitFor(predicate: () -> Boolean) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = predicate() }
                    if (ready) return
                    android.os.SystemClock.sleep(40)
                }
                instrumentation.runOnMainSync {
                    val current = widget
                    val flipper = current?.findViewById<android.widget.AdapterViewFlipper>(R.id.widget_flipper)
                    System.out.println("AF_WIDGET_HOST attached=${current?.isAttachedToWindow} children=${current?.childCount} title=${current?.findViewById<TextView>(R.id.widget_title)?.text} adapterCount=${flipper?.adapter?.count} displayed=${flipper?.displayedChild} slide=${descendant<TextView>(currentFrame(), R.id.widget_slide_name)?.text} kind=${AfWidgetStore(app).load(id)?.kind}")
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().forEach { opened ->
                            val model = androidx.lifecycle.ViewModelProvider(opened)[com.affilemanager.app.ui.MainViewModel::class.java]
                            System.out.println("AF_WIDGET_ROUTING action=${opened.intent.action} component=${opened.intent.component} uri=${opened.intent.data} preview=${model.preview.value?.javaClass?.simpleName} locked=${app.graph.appLock.enabled.value}")
                        }
                }
                val failure = instrumentation.uiAutomation.takeScreenshot()
                File(app.getExternalFilesDir("validation"), "home-widget-host-failed.png").outputStream().use { check(failure.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                failure.recycle()
                fail("Widget host did not bind or route the collection")
            }
            var location = IntArray(2)
            var width = 0; var height = 0
            waitFor {
                val image = descendant<android.widget.ImageView>(currentFrame(), R.id.widget_slide_image)
                if (descendant<TextView>(currentFrame(), R.id.widget_slide_name)?.text?.toString() != source.name ||
                    image?.drawable == null || image.width <= 0 || image.height <= 0) return@waitFor false
                val flipper = widget!!.findViewById<android.widget.AdapterViewFlipper>(R.id.widget_flipper)
                assertEquals(32, flipper.adapter.count)
                assertNotNull(image.drawable); assertTrue(image.width > 0 && image.height > 0)
                val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertTrue(bitmap.width <= AfWidgetRenderer.artEdge(app, 32))
                val frame = requireNotNull(descendant<android.view.View>(currentFrame(), R.id.widget_slide_root))
                frame.getLocationOnScreen(location); width = frame.width; height = frame.height
                true
            }
            val expectedSource = sources[1]
            waitFor {
                // A host refresh can replace a ready photo with its loading view
                // between two main-thread turns. Resolve and click one live frame
                // atomically instead of dereferencing the previous frame's controls.
                val frame = currentFrame()
                val next = descendant<android.view.View>(frame, R.id.widget_slide_next)
                if (descendant<TextView>(frame, R.id.widget_slide_name)?.text?.toString() != source.name ||
                    next == null || next.visibility != android.view.View.VISIBLE || !next.isEnabled || !next.hasOnClickListeners()) false
                else next.performClick()
            }
            waitFor {
                val image = descendant<android.widget.ImageView>(currentFrame(), R.id.widget_slide_image)
                if (descendant<TextView>(currentFrame(), R.id.widget_slide_name)?.text?.toString() != expectedSource.name ||
                    image?.drawable == null || image.width <= 0 || image.height <= 0) return@waitFor false
                val frame = requireNotNull(descendant<android.view.View>(currentFrame(), R.id.widget_slide_root))
                frame.getLocationOnScreen(location); width = frame.width; height = frame.height
                true
            }
            // A bound drawable is not proof that the host has drawn it. The standard
            // first-frame animation may still be transparent at this point.
            val visibleDeadline = android.os.SystemClock.elapsedRealtime() + 5000
            var visibleNext = false
            var stableFrame: android.view.View? = null
            val stableBounds = android.graphics.Rect()
            var stableSince = android.os.SystemClock.elapsedRealtime()
            do {
                var liveFrame: android.view.View? = null
                instrumentation.runOnMainSync {
                    val frame = descendant<android.view.View>(currentFrame(), R.id.widget_slide_root)
                    if (frame?.isAttachedToWindow == true && frame.isShown &&
                        descendant<TextView>(currentFrame(), R.id.widget_slide_name)?.text?.toString() == expectedSource.name) {
                        liveFrame = frame
                        frame.getLocationOnScreen(location); width = frame.width; height = frame.height
                    }
                }
                val pixels = instrumentation.uiAutomation.takeScreenshot()
                try {
                    val x = (location[0] + width / 2).coerceIn(0, pixels.width - 1)
                    val y = (location[1] + height / 2).coerceIn(0, pixels.height - 1)
                    val color = pixels.getPixel(x, y)
                    val green = liveFrame != null && android.graphics.Color.green(color) > 180 && android.graphics.Color.red(color) < 70 && android.graphics.Color.blue(color) < 70
                    val bounds = android.graphics.Rect(location[0], location[1], location[0] + width, location[1] + height)
                    if (!green || stableFrame !== liveFrame || stableBounds != bounds) {
                        stableFrame = liveFrame; stableBounds.set(bounds)
                        stableSince = android.os.SystemClock.elapsedRealtime()
                    } else visibleNext = android.os.SystemClock.elapsedRealtime() - stableSince >= 500
                } finally { pixels.recycle() }
                if (!visibleNext) android.os.SystemClock.sleep(50)
            } while (!visibleNext && android.os.SystemClock.elapsedRealtime() < visibleDeadline)
            assertTrue("The host bound the next image but did not display its distinct pixels", visibleNext)
            waitFor { hostActivity.window.decorView.hasWindowFocus() }
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(app.getExternalFilesDir("validation"), "home-widget-real-host.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }; screenshot.recycle()
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                if (action == android.view.MotionEvent.ACTION_UP) android.os.SystemClock.sleep(80)
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(),
                    action, location[0] + width / 2f, location[1] + height / 2f, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue("Native widget input was rejected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            instrumentation.uiAutomation.waitForIdle(200, 5000)
            waitFor {
                activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
                activity?.let { opened ->
                    val model = androidx.lifecycle.ViewModelProvider(opened)[com.affilemanager.app.ui.MainViewModel::class.java]
                    (model.preview.value as? com.affilemanager.app.ui.PreviewTarget.LocalFile)?.entry?.absolutePath == expectedSource.path
                } == true
            }
            System.out.println("AF_WIDGET_TASK warm=$warm original=$warmTaskId opened=${activity?.taskId}")
            if (warm) {
                assertEquals("Widget should reuse AF's existing task", warmTaskId, activity?.taskId)
                assertSame("Widget should preserve the already running AF activity", warmActivity, activity)
            }
            val stale = AfWidgetProvider.launchIntent(app, id).setData(android.net.Uri.parse("af-widget://$id/slideshow-open"))
                .putExtra("slide_index", 32).putExtra("slide_revision", SlidesFactory.fingerprint(source))
            assertNull(AfWidgetProvider.request(app, stale))
            System.out.println("AF_WIDGET_COLLECTION_ACCEPTED 32 frames, bounded bitmap, real tap opened matching source")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                instrumentation.runOnMainSync { activity?.finish() }
                instrumentation.runOnMainSync { hostActivity?.finish() }
                instrumentation.runOnMainSync { warmActivity?.finish() }
            } catch (cleanup: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
            } finally {
                File(app.getExternalFilesDir("validation"), "widget-route-${if (warm) "warm" else "cold"}.txt")
                    .writeText(routeEvents.joinToString("\n"))
                monitor.removeLifecycleCallback(trace)
                host.stopListening()
                if (id > 0) { AfWidgetStore(app).remove(id); host.deleteAppWidgetId(id) }
                app.graph.appLock.setEnabled(wasLocked)
                shell("appwidget revokebind --package ${app.packageName} --user 0")
                root.deleteRecursively()
            }
        }
    }

    @Test fun settingsRoundTripIndependentlyAndDeletingOneInstanceKeepsTheOther() {
        val namespace = "widget-test-${UUID.randomUUID()}"
        val store = AfWidgetStore(app, namespace)
        try {
            val one = AfWidgetConfig(AfWidgetKind.IMAGES, "First", app.cacheDir.path, listOf(app.cacheDir.resolve("fixture.png").path),
                horizontal = true, opacity = 65, density = 115, rotation = 90, slideSeconds = 60)
            val two = AfWidgetConfig(AfWidgetKind.SEARCH, "Second", app.cacheDir.path, query = "report")
            store.save(101, one); store.save(102, two)
            assertEquals(one, store.load(101)); assertEquals(two, store.load(102))
            store.remove(101)
            assertNull(store.load(101)); assertEquals(two, store.load(102))
            for (id in 103..133) store.save(id, two)
            assertThrows(IllegalArgumentException::class.java) { store.save(134, two) }
            assertEquals(two, store.load(102))
        } finally { app.deleteSharedPreferences(namespace) }
    }

    @Test fun actualBoundWidgetsRenderEveryKindAndRejectForeignEntryPoints() = runBlocking {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val root = File(app.cacheDir, "widget-render-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = File(root, "image.png")
        val photo = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        source.outputStream().use { check(photo.compress(Bitmap.CompressFormat.PNG, 100, it)) }; photo.recycle()
        val audio = File(root, "music.wav").also { MediaTestFixtures.wav(it) }
        val video = File(root, "video.mp4").also { MediaTestFixtures.video(it) }
        val original = root.listFiles()!!.associate { it.name to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
        val host = AppWidgetHost(app, 2026254)
        val manager = AppWidgetManager.getInstance(app)
        val ids = mutableListOf<Int>()
        val wasLocked = app.graph.appLock.enabled.value
        try {
            shell("appwidget grantbind --package ${app.packageName} --user 0")
            app.graph.appLock.setEnabled(false)
            repeat(2) {
                val id = host.allocateAppWidgetId(); ids += id
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(app, AfWidgetProvider::class.java)))
            }
            assertNotNull(AfWidgetProvider.request(app, AfWidgetProvider.launchIntent(app, ids[0])))
            assertNull(AfWidgetProvider.request(app, AfWidgetProvider.launchIntent(app, ids[0]).setClass(app, MainActivity::class.java)))
            assertNull(AfWidgetProvider.request(app, AfWidgetProvider.launchIntent(app, Int.MAX_VALUE)))
            for (kind in AfWidgetKind.entries) {
                val config = AfWidgetConfig(kind, folder = root.path, sources = when (kind) {
                    AfWidgetKind.IMAGES -> listOf(source.path)
                    AfWidgetKind.MUSIC -> listOf(audio.path)
                    AfWidgetKind.VIDEO -> listOf(video.path)
                    else -> emptyList()
                })
                AfWidgetStore(app).save(ids[0], config)
                val views = AfWidgetRenderer.render(app, ids[0], config)
                instrumentation.runOnMainSync {
                    val parent = FrameLayout(app)
                    val view = views.apply(app, parent)
                    view.measure(android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, 600, 600)
                    assertTrue(view.findViewById<TextView>(R.id.widget_title).text.isNotBlank())
                    if (kind != AfWidgetKind.IMAGES) assertTrue("No content for $kind", view.findViewById<TextView>(R.id.widget_details).text.isNotBlank())
                    else assertEquals(android.view.View.VISIBLE, view.findViewById<android.view.View>(R.id.widget_slideshow).visibility)
                    assertNotNull(view.findViewById<android.view.View>(R.id.widget_settings).contentDescription)
                }
                manager.updateAppWidget(ids[0], views)
            }
            val music = AfWidgetConfig(AfWidgetKind.MUSIC, folder = root.path, sources = listOf(audio.path))
            AfWidgetStore(app).save(ids[1], music)
            val visibleImage = AfWidgetRenderer.render(app, ids[1], AfWidgetConfig(AfWidgetKind.IMAGES, folder = root.path, sources = listOf(source.path)))
            app.graph.appLock.setEnabled(true)
            assertTrue(AfWidgetProvider.publishRendered(app, ids[1], music, visibleImage))
            assertFalse(AfWidgetProvider.publishRendered(app, ids[1], music.copy(title = "Obsolete configuration"), visibleImage))
            val locked = AfWidgetRenderer.render(app, ids[1], music)
            instrumentation.runOnMainSync {
                val view = visibleImage.apply(app, FrameLayout(app))
                assertEquals(android.view.View.VISIBLE, view.findViewById<android.view.View>(R.id.widget_slideshow).visibility)
                locked.reapply(app, view)
                assertFalse(view.findViewById<TextView>(R.id.widget_details).text.contains("music.wav"))
                assertEquals(android.view.View.GONE, view.findViewById<android.view.View>(R.id.widget_image).visibility)
                assertNull(view.findViewById<android.widget.ImageView>(R.id.widget_image).drawable)
                assertEquals(android.view.View.GONE, view.findViewById<android.view.View>(R.id.widget_slideshow).visibility)
                assertEquals(0, view.findViewById<android.view.ViewGroup>(R.id.widget_slideshow).childCount)
            }
            AfWidgetProvider().onDeleted(app, intArrayOf(ids[0]))
            assertNull(AfWidgetStore(app).load(ids[0]))
            assertEquals(music, AfWidgetStore(app).load(ids[1]))
            original.forEach { (name, sha) -> assertArrayEquals(sha, java.security.MessageDigest.getInstance("SHA-256").digest(root.resolve(name).readBytes())) }
        } finally {
            ids.forEach { AfWidgetStore(app).remove(it); host.deleteAppWidgetId(it) }
            host.stopListening()
            app.graph.appLock.setEnabled(wasLocked)
            shell("appwidget revokebind --package ${app.packageName} --user 0")
            root.deleteRecursively()
        }
    }
}
