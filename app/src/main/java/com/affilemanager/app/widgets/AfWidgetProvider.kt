package com.affilemanager.app.widgets

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.work.*
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.media.BackgroundPlaybackPhase
import com.affilemanager.app.media.BackgroundPlaybackService
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.ui.components.FileVisualLoader
import com.affilemanager.app.ui.localization.appLanguageContext
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.theme.resolveAfOpaqueColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class AfWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)
    override fun onDeleted(context: Context, ids: IntArray) {
        playbackWidgets = null
        val store = AfWidgetStore(context)
        ids.forEach { id ->
            synchronized(slideActions) { slideActions.remove(id)?.cancel() }
            store.remove(id)
        }
        if (ids(context).isEmpty()) cancelUpdates(context)
    }
    override fun onDisabled(context: Context) {
        if (ids(context).isEmpty()) cancelUpdates(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CONTROL) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (!owns(context, id)) return
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action == "refresh") { refresh(context); return }
        val config = AfWidgetStore(context).load(id) ?: return
        val app = context.applicationContext as AFFileManagerApplication
        if (config.kind == AfWidgetKind.IMAGES && action == "slide") {
            if (intent.data != Uri.parse("af-widget://$id/slides-control")) return
            val index = intent.getIntExtra("slide_index", -1)
            val revision = intent.getStringExtra("slide_revision").orEmpty()
            val command = intent.getStringExtra("slide_command")
            if (index !in 0 until AfWidgetConfig.MAX_SOURCES || !revision.matches(Regex("[0-9a-f]{16}")) ||
                command !in setOf("open", "previous", "next")) return
            if (command == "open" || app.graph.appLock.enabled.value) {
                context.startActivity(launchIntent(context, id).setData(Uri.parse("af-widget://$id/slideshow-open"))
                    .putExtra("slide_index", index).putExtra("slide_revision", revision))
                return
            }
            val pending = goAsync()
            val job = app.graph.applicationScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
                fun unavailable() {
                    val latest = AfWidgetStore(context).load(id) ?: return
                    publishRendered(context, id, latest, AfWidgetRenderer.error(app, id))
                }
                try {
                    val completed = withTimeoutOrNull(5000) { slideActionMutex.withLock {
                        val latest = AfWidgetStore(context).load(id) ?: return@withLock true
                        if (!owns(context, id) || latest.kind != AfWidgetKind.IMAGES || app.graph.appLock.enabled.value) return@withLock true
                        val files = AfWidgetRenderer.sources(latest)
                        val source = files.getOrNull(index)
                        require(source != null && SlidesFactory.fingerprint(source) == revision)
                        val next = Math.floorMod(index + if (command == "previous") -1 else 1, files.size)
                        val saved = withContext(Dispatchers.IO) {
                            AfWidgetStore(context).compareAndSet(id, latest, latest.copy(index = next))
                        }
                        if (saved) refresh(context)
                        true
                    } }
                    if (completed != true) unavailable()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { unavailable() }
            }
            synchronized(slideActions) { slideActions.remove(id)?.cancel(); slideActions[id] = job }
            job.invokeOnCompletion {
                synchronized(slideActions) { if (slideActions[id] === job) slideActions.remove(id) }
                pending?.finish()
            }
            job.start()
            return
        }
        val playback = BackgroundPlaybackService.state.value
        val playingPath = playback?.uri?.let(Uri::parse)?.takeIf { it.scheme == "file" }?.path
        val controlsCurrentMusic = config.kind == AfWidgetKind.MUSIC && playback?.active == true && playingPath != null &&
            (playingPath in config.sources || config.sources.isEmpty() && File(playingPath).parent == config.folder)
        if (!app.graph.appLock.enabled.value && controlsCurrentMusic) {
            when (action) {
                "play" -> BackgroundPlaybackService.toggle(context)
                "previous" -> BackgroundPlaybackService.skip(context, -1)
                "next" -> BackgroundPlaybackService.skip(context, 1)
                "stop" -> BackgroundPlaybackService.stop(context)
                else -> return
            }
            refresh(context)
            return
        }
        // Controls use the same activity unlock boundary as opening a file.
        if (action in setOf("previous", "next", "play", "open")) {
            context.startActivity(launchIntent(context, id, action ?: "open").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        internal const val ACTION_OPEN = "com.affilemanager.app.OPEN_HOME_WIDGET"
        private const val ACTION_CONTROL = "com.affilemanager.app.CONTROL_HOME_WIDGET"
        internal const val EXTRA_ACTION = "widget_action"
        private const val ENTRY_CLASS = "com.affilemanager.app.WidgetEntryActivity"
        private const val WORK = "af-home-widgets"
        private const val PERIODIC_WORK = "af-home-widgets-periodic"
        private val slideActions = mutableMapOf<Int, Job>()
        private val slideActionMutex = Mutex()
        @Volatile private var playbackWidgets: List<Pair<Int, AfWidgetConfig>>? = null
        private var previousPlaybackUri: String? = null
        internal fun ids(context: Context) = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, AfWidgetProvider::class.java)).take(AfWidgetStore.MAX_WIDGETS)
        internal fun owns(context: Context, id: Int) = id > 0 &&
            AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == ComponentName(context, AfWidgetProvider::class.java)
        internal fun refresh(context: Context) {
            playbackWidgets = null
            if (ids(context).isEmpty()) { cancelUpdates(context); return }
            val work = WorkManager.getInstance(context)
            // Keep one scheduled owner while widgets exist. Finishing the last one-time
            // work toggles WorkManager's receiver and can trigger another widget update.
            work.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AfWidgetRefreshWorker>(30, TimeUnit.MINUTES)
                    .setInitialDelay(30, TimeUnit.MINUTES).build())
            work.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AfWidgetRefreshWorker>().build())
        }
        private fun cancelUpdates(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(WORK)
                cancelUniqueWork(PERIODIC_WORK)
            }
        }
        internal fun hideLockedContent(context: Context) {
            WidgetPublicationGate.serialize {
                val manager = AppWidgetManager.getInstance(context)
                ids(context).forEach { manager.updateAppWidget(it, AfWidgetRenderer.locked(context, it)) }
            }
        }
        /** Recheck privacy inside the same gate used by lock and playback updates. */
        internal fun publishRendered(context: Context, id: Int, config: AfWidgetConfig?, views: RemoteViews): Boolean = WidgetPublicationGate.serialize {
            if (!owns(context, id) || AfWidgetStore(context).load(id) != config) return@serialize false
            val app = context.applicationContext as AFFileManagerApplication
            val locked = app.graph.appLock.enabled.value
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(id, if (locked) AfWidgetRenderer.locked(context, id) else views)
            if (!locked && config?.kind == AfWidgetKind.IMAGES) manager.notifyAppWidgetViewDataChanged(id, R.id.widget_flipper)
            true
        }
        /** Reuses the existing player's tick. No new timer, thumbnails, scan or worker per tick. */
        internal fun updatePlayback(context: Context, state: com.affilemanager.app.media.BackgroundPlaybackState?) {
            val app = context.applicationContext as AFFileManagerApplication
            if (app.graph.appLock.enabled.value) return
            val widgets = playbackWidgets ?: ids(context).mapNotNull { id ->
                AfWidgetStore(context).load(id)?.takeIf { it.kind == AfWidgetKind.MUSIC }?.let { id to it }
            }.also { playbackWidgets = it }
            if (widgets.isEmpty()) return
            if (previousPlaybackUri != state?.uri) {
                previousPlaybackUri = state?.uri
                refresh(context) // Track changes only, never a thumbnail refresh for every position tick.
            }
            val path = state?.uri?.let(Uri::parse)?.takeIf { it.scheme == "file" }?.path
            val manager = AppWidgetManager.getInstance(context)
            for ((id, config) in widgets) {
                val matching = path != null && (path in config.sources || config.sources.isEmpty() && File(path).parent == config.folder)
                if (!matching && state != null) continue
                val update = RemoteViews(context.packageName, AfWidgetRenderer.layout(context, id, config))
                AfWidgetRenderer.playback(context, update, state.takeIf { matching })
                WidgetPublicationGate.serialize {
                    if (!app.graph.appLock.enabled.value) manager.partiallyUpdateAppWidget(id, update)
                }
            }
        }
        internal fun launchIntent(context: Context, id: Int, action: String = "open") = Intent().setComponent(ComponentName(context, ENTRY_CLASS))
            .setAction(ACTION_OPEN).setData(Uri.parse("af-widget://$id/$action"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        internal fun request(context: Context, intent: Intent): AfWidgetRequest? {
            if (intent.action != ACTION_OPEN || intent.component?.className != ENTRY_CLASS) return null
            val uri = intent.data?.takeIf { it.scheme == "af-widget" } ?: return null
            val id = uri.host?.toIntOrNull() ?: return null
            if (!owns(context, id) || intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) != id) return null
            if (uri.path == "/slideshow-open") {
                val index = intent.getIntExtra("slide_index", -1)
                val revision = intent.getStringExtra("slide_revision") ?: return null
                return if (index in 0 until AfWidgetConfig.MAX_SOURCES && revision.matches(Regex("[0-9a-f]{16}")) &&
                    AfWidgetStore(context).load(id)?.kind == AfWidgetKind.IMAGES) AfWidgetRequest(id, "slide-$index-$revision") else null
            }
            val action = intent.getStringExtra(EXTRA_ACTION) ?: "open"
            return if (action in setOf("open", "play", "previous", "next") && uri.path == "/$action") AfWidgetRequest(id, action) else null
        }
    }
}

/** Bounded updates from the one periodic owner or explicit OS/user requests. */
class AfWidgetRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { refreshLock.withLock {
        val app = applicationContext as AFFileManagerApplication
        val store = AfWidgetStore(app)
        val completed = withTimeoutOrNull(30_000) { for (id in AfWidgetProvider.ids(app)) {
            if (isStopped) break
            val config = store.load(id)
            val views = try { AfWidgetRenderer.render(app, id, config) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { AfWidgetRenderer.error(app, id) }
            if (!isStopped) AfWidgetProvider.publishRendered(app, id, config, views)
        }; true }
        if (completed == true) Result.success() else Result.failure(workDataOf("error" to "AF_WIDGET_UPDATE_TIMEOUT"))
    } }
    companion object { private val refreshLock = Mutex() }
}

internal object AfWidgetRenderer {
    internal fun slidesLayout(config: AfWidgetConfig): Int {
        val row = when (config.slideSeconds) {
            5 -> intArrayOf(R.layout.af_widget_slides_5_none, R.layout.af_widget_slides_5_fade, R.layout.af_widget_slides_5_slide)
            15 -> intArrayOf(R.layout.af_widget_slides_15_none, R.layout.af_widget_slides_15_fade, R.layout.af_widget_slides_15_slide)
            60 -> intArrayOf(R.layout.af_widget_slides_60_none, R.layout.af_widget_slides_60_fade, R.layout.af_widget_slides_60_slide)
            300 -> intArrayOf(R.layout.af_widget_slides_300_none, R.layout.af_widget_slides_300_fade, R.layout.af_widget_slides_300_slide)
            else -> intArrayOf(R.layout.af_widget_slides, R.layout.af_widget_slides_fade, R.layout.af_widget_slides_slide)
        }
        return row[config.transition.ordinal]
    }
    internal fun artEdge(context: Context, collectionSize: Int = 1): Int {
        val metrics = context.resources.displayMetrics
        // Modern hosts can parcel all collection entries together. Budget the complete
        // collection, not 32 independent full-size bitmaps (host limit is screen-based).
        val pixels = metrics.widthPixels.toLong() * metrics.heightPixels / collectionSize.coerceIn(1, AfWidgetConfig.MAX_SOURCES)
        return kotlin.math.sqrt(minOf(512L * 512L, pixels).toDouble()).toInt().coerceIn(24, 512)
    }
    internal fun layout(context: Context, id: Int, config: AfWidgetConfig): Int {
        val wide = AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= 300
        return if (config.horizontal && wide) R.layout.af_home_widget_horizontal else R.layout.af_home_widget
    }
    internal suspend fun sources(config: AfWidgetConfig): List<File> = withContext(Dispatchers.IO) {
        if (config.sources.isNotEmpty()) return@withContext config.sources.map(::File).filter { accepts(config.kind, it) }.take(AfWidgetConfig.MAX_SOURCES)
        val directory = File(config.folder)
        if (!directory.isDirectory || !directory.canRead()) return@withContext emptyList()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            stream.asSequence().take(512).map { it.toFile() }.filter { accepts(config.kind, it) }
                .take(AfWidgetConfig.MAX_SOURCES).toList().sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        }
    }
    private fun accepts(kind: AfWidgetKind, file: File): Boolean = file.isFile && file.canRead() && when (kind) {
        AfWidgetKind.IMAGES -> FileSystemRules.detectKind(file.name, null) == EntryKind.IMAGE
        AfWidgetKind.VIDEO -> FileSystemRules.detectKind(file.name, null) == EntryKind.VIDEO
        AfWidgetKind.MUSIC -> FileSystemRules.detectKind(file.name, null) == EntryKind.AUDIO
        else -> true
    }
    internal fun error(context: Context, id: Int): RemoteViews = base(context, id, AfWidgetConfig()).apply {
        setTextViewText(R.id.widget_details, text(context, "Valdiklio turinys nepasiekiamas. Nustatymuose pasirinkite šaltinį iš naujo."))
    }
    internal fun locked(context: Context, id: Int): RemoteViews = base(context, id, AfWidgetConfig()).apply {
        setTextViewText(R.id.widget_details, text(context, "Atrakinti AF File Manager"))
    }
    internal suspend fun render(app: AFFileManagerApplication, id: Int, config: AfWidgetConfig?): RemoteViews {
        val current = config ?: AfWidgetConfig()
        val views = base(app, id, current)
        if (app.graph.appLock.enabled.value) {
            views.setTextViewText(R.id.widget_details, text(app, "Atrakinti AF File Manager"))
            return views
        }
        if (config == null) {
            views.setTextViewText(R.id.widget_details, text(app, "Atidaryti valdiklio nustatymus"))
            return views
        }
        val title = config.title.ifBlank { text(app, config.kind.label) }
        views.setTextViewText(R.id.widget_title, title)
        val details = when (config.kind) {
            AfWidgetKind.STORAGE -> {
                val (total, free) = when (config.storage) {
                    AfWidgetStorage.RAM -> ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.let { it.totalMem to it.availMem }
                    AfWidgetStorage.ROOT -> File("/").let { it.totalSpace to it.usableSpace }
                    else -> app.graph.localFiles.roots().firstOrNull { root -> root.kind == when (config.storage) {
                        AfWidgetStorage.SD -> com.affilemanager.app.model.StorageRootKind.SD_CARD
                        AfWidgetStorage.USB -> com.affilemanager.app.model.StorageRootKind.USB_STORAGE
                        else -> com.affilemanager.app.model.StorageRootKind.INTERNAL
                    } }?.let { it.totalBytes to it.freeBytes } ?: (0L to 0L)
                }
                if (total > 0) {
                    val colors = resolveAfOpaqueColors(app, app.graph.appearance.settings.value,
                        app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                    val bitmap = Bitmap.createBitmap(256, 8, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(colors.surfaceVariant.toArgb())
                    val used = (total - free).coerceIn(0, total)
                    canvas.drawRect(0f, 0f, (256.0 * used / total).toFloat(), 8f, android.graphics.Paint().apply { color = colors.primary.toArgb() })
                    views.setImageViewBitmap(R.id.widget_storage_progress, bitmap)
                    views.setViewVisibility(R.id.widget_storage_progress, View.VISIBLE)
                }
                usage(app, text(app, config.storage.label), total, free)
            }
            AfWidgetKind.CLEANUP -> text(app, "Tik peržiūra — AF nieko nešalina be jūsų patvirtinimo")
            AfWidgetKind.PHONE_SHARE, AfWidgetKind.COMPUTER_SHARE -> text(app, config.kind.label)
            AfWidgetKind.SHORTCUT -> config.sources.firstOrNull()?.let { File(it).name } ?: File(config.folder).name.ifEmpty { config.folder }
            AfWidgetKind.SEARCH -> config.query.ifBlank { text(app, "Gilioji paieška") } + "\n" + config.folder
            AfWidgetKind.IMAGES, AfWidgetKind.VIDEO, AfWidgetKind.MUSIC -> {
                val files = sources(config)
                val activePath = BackgroundPlaybackService.state.value?.uri?.let(Uri::parse)?.takeIf { it.scheme == "file" }?.path
                val activeIndex = if (config.kind == AfWidgetKind.MUSIC) files.indexOfFirst { it.path == activePath } else -1
                val selectedIndex = if (activeIndex >= 0) activeIndex else config.index.coerceAtMost((files.size - 1).coerceAtLeast(0))
                val selected = files.getOrNull(selectedIndex)
                if (selected == null) return views.apply { setTextViewText(R.id.widget_details, text(app, "Pasirinktoje vietoje nėra tinkamų failų")) }
                if (config.kind == AfWidgetKind.IMAGES) {
                    views.addView(R.id.widget_slideshow, RemoteViews(app.packageName, slidesLayout(config)))
                    // Adapter actions must use the outer AppWidget host, not a nested FrameLayout.
                    // A changed first slide is a different positional dataset. The
                    // host keys cached collection rows by the intent's filter (not
                    // extras); reusing one URI can leave its old first image visible.
                    // The key has at most MAX_SOURCES variants per live widget.
                    val start = config.index.coerceIn(0, AfWidgetConfig.MAX_SOURCES - 1)
                    views.setRemoteAdapter(R.id.widget_flipper, Intent(app, AfWidgetSlidesService::class.java)
                        .setData(Uri.parse("af-widget://$id/slides/$start"))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("slide_start", start))
                    // Collections require a fill-in template. Keep its destination, action and widget
                    // identity fixed; request() validates the only variable fields before routing.
                    val template = Intent(app, AfWidgetProvider::class.java).setAction("com.affilemanager.app.CONTROL_HOME_WIDGET")
                        .setData(Uri.parse("af-widget://$id/slides-control"))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(AfWidgetProvider.EXTRA_ACTION, "slide")
                    views.setPendingIntentTemplate(R.id.widget_flipper, PendingIntent.getBroadcast(app, id, template,
                        PendingIntent.FLAG_UPDATE_CURRENT or if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                    views.setViewVisibility(R.id.widget_slideshow, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_details, View.GONE)
                    views.setViewVisibility(R.id.widget_media_controls, View.GONE)
                    views.setViewVisibility(R.id.widget_play, View.GONE)
                    return views
                }
                val entry = app.graph.localFiles.toEntry(selected)
                val visual = FileVisualLoader.loadLocal(app, entry, artEdge(app), artEdge(app), true)
                visual?.let { image ->
                    val rotated = if (config.rotation == 0) image.bitmap else Bitmap.createBitmap(image.bitmap, 0, 0,
                        image.bitmap.width, image.bitmap.height, Matrix().apply { postRotate(config.rotation.toFloat()) }, true)
                    views.setImageViewBitmap(R.id.widget_image, rotated)
                    views.setViewVisibility(R.id.widget_image, View.VISIBLE)
                    // RemoteViews retains the bitmap until the host has received its parcel.
                }
                views.setViewVisibility(R.id.widget_media_controls, View.VISIBLE)
                views.setViewVisibility(R.id.widget_play, if (config.kind == AfWidgetKind.IMAGES) View.GONE else View.VISIBLE)
                val playing = BackgroundPlaybackService.state.value?.takeIf { it.uri == Uri.fromFile(selected).toString() }
                views.setImageViewResource(R.id.widget_play, if (playing?.phase == BackgroundPlaybackPhase.PLAYING) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                views.setViewVisibility(R.id.widget_stop, if (config.kind == AfWidgetKind.MUSIC) View.VISIBLE else View.GONE)
                if (playing != null && config.kind == AfWidgetKind.MUSIC) { playback(app, views, playing); return views }
                selected.name + "\n${selectedIndex + 1} / ${files.size}"
            }
        }
        views.setTextViewText(R.id.widget_details, details)
        return views
    }
    internal fun playback(context: Context, views: RemoteViews, state: com.affilemanager.app.media.BackgroundPlaybackState?) {
        val playing = state?.phase == BackgroundPlaybackPhase.PLAYING
        views.setImageViewResource(R.id.widget_play, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        views.setContentDescription(R.id.widget_play, text(context, if (playing) "Pauzė" else "Leisti"))
        views.setViewVisibility(R.id.widget_storage_progress, if (state != null && state.durationMillis > 0) View.VISIBLE else View.GONE)
        if (state == null) {
            views.setTextViewText(R.id.widget_details, text(context, "Sustabdyta"))
            return
        }
        fun time(ms: Long): String = (ms.coerceAtLeast(0) / 1000).let { "%d:%02d".format(java.util.Locale.ROOT, it / 60, it % 60) }
        views.setTextViewText(R.id.widget_details, "${state.title}\n${time(state.positionMillis)} / ${time(state.durationMillis)}")
        if (state.phase == BackgroundPlaybackPhase.ERROR) views.setTextViewText(R.id.widget_details, "${state.title}\n${text(context, "Nepavyko")}")
        val bitmap = Bitmap.createBitmap(256, 8, Bitmap.Config.ARGB_8888)
        val app = context.applicationContext as AFFileManagerApplication
        val colors = resolveAfOpaqueColors(context, app.graph.appearance.settings.value,
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(colors.surfaceVariant.toArgb())
        val fraction = if (state.durationMillis > 0) (state.positionMillis.toDouble() / state.durationMillis).coerceIn(0.0, 1.0) else 0.0
        canvas.drawRect(0f, 0f, (256 * fraction).toFloat(), 8f, android.graphics.Paint().apply { color = colors.primary.toArgb() })
        views.setImageViewBitmap(R.id.widget_storage_progress, bitmap)
    }
    private fun usage(context: Context, label: String, total: Long, free: Long): String {
        if (total <= 0) return "$label · ${text(context, "Nežinoma")}"
        val used = (total - free.coerceAtLeast(0)).coerceIn(0, total)
        return "$label · ${(used.toDouble() * 100 / total).toInt()}% · ${FileSystemRules.humanBytes(used)} / ${FileSystemRules.humanBytes(total)}"
    }
    internal fun text(context: Context, value: String) = UiTranslator.translate(value, context.appLanguageContext().resources.configuration.locales[0].toLanguageTag())
    private fun base(context: Context, id: Int, config: AfWidgetConfig): RemoteViews {
        val app = context.applicationContext as AFFileManagerApplication
        val colors = resolveAfOpaqueColors(context, app.graph.appearance.settings.value,
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        return RemoteViews(context.packageName, layout(context, id, config)).apply {
            setInt(R.id.widget_root, "setLayoutDirection", context.appLanguageContext().resources.configuration.layoutDirection)
            // Hosts normally reapply the same layout instead of reinflating its XML.
            // Explicitly clear the old content when locking or changing widget kinds.
            setViewVisibility(R.id.widget_image, View.GONE)
            setImageViewResource(R.id.widget_image, 0)
            setViewVisibility(R.id.widget_slideshow, View.GONE)
            removeAllViews(R.id.widget_slideshow)
            setViewVisibility(R.id.widget_storage_progress, View.GONE)
            setImageViewResource(R.id.widget_storage_progress, 0)
            setViewVisibility(R.id.widget_media_controls, View.GONE)
            setViewVisibility(R.id.widget_stop, View.GONE)
            setViewVisibility(R.id.widget_details, View.VISIBLE)
            setTextViewText(R.id.widget_details, "")
            setTextViewText(R.id.widget_title, "AF File Manager")
            setTextColor(R.id.widget_title, colors.onSurface.toArgb())
            setTextColor(R.id.widget_details, colors.onSurface.toArgb())
            setInt(R.id.widget_background, "setColorFilter", colors.surface.toArgb())
            setInt(R.id.widget_background, "setImageAlpha", config.opacity * 255 / 100)
            setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 16f * config.density / 100)
            setTextViewTextSize(R.id.widget_details, TypedValue.COMPLEX_UNIT_SP, 14f * config.density / 100)
            setInt(R.id.widget_title, "setMaxLines", 1)
            setInt(R.id.widget_details, "setMaxLines", 2)
            fun open(action: String) = PendingIntent.getActivity(context, id, AfWidgetProvider.launchIntent(context, id, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val openWidget = open("open")
            // Visible content has the same explicit action as its container, including
            // accessibility clicks that do not bubble to the container's listener.
            listOf(R.id.widget_root, R.id.widget_title, R.id.widget_details, R.id.widget_image, R.id.widget_storage_progress)
                .forEach { setOnClickPendingIntent(it, openWidget) }
            fun control(action: String) = PendingIntent.getBroadcast(context, id,
                Intent(context, AfWidgetProvider::class.java).setAction("com.affilemanager.app.CONTROL_HOME_WIDGET")
                    .setData(Uri.parse("af-widget://$id/$action")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AfWidgetProvider.EXTRA_ACTION, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widget_play, control("play"))
            setOnClickPendingIntent(R.id.widget_previous, control("previous"))
            setOnClickPendingIntent(R.id.widget_next, control("next"))
            setOnClickPendingIntent(R.id.widget_stop, control("stop"))
            val configure = Intent(context, AfWidgetConfigureActivity::class.java).setData(Uri.parse("af-widget://$id/configure"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            setOnClickPendingIntent(R.id.widget_settings, PendingIntent.getActivity(context, id, configure, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val refresh = Intent(context, AfWidgetProvider::class.java).setAction("com.affilemanager.app.CONTROL_HOME_WIDGET")
                .setData(Uri.parse("af-widget://$id/refresh")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(AfWidgetProvider.EXTRA_ACTION, "refresh")
            setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            listOf(R.id.widget_settings to "Nustatymai", R.id.widget_refresh to "Atnaujinti", R.id.widget_previous to "Ankstesnis",
                R.id.widget_next to "Kitas", R.id.widget_play to "Leisti", R.id.widget_stop to "Sustabdyti").forEach { (view, label) ->
                setContentDescription(view, text(context, label))
                setInt(view, "setColorFilter", colors.onSurface.toArgb())
            }
        }
    }
}

data class AfWidgetRequest(val id: Int, val action: String)
