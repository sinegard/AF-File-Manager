package com.affilemanager.app.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.compose.ui.graphics.toArgb
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.R
import com.affilemanager.app.ui.components.FileVisualLoader
import com.affilemanager.app.ui.localization.appLanguageContext
import com.affilemanager.app.ui.theme.resolveAfOpaqueColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/** Bound only by Android's widget host. Holds file references, never 32 decoded originals. */
class AfWidgetSlidesService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = SlidesFactory(application as AFFileManagerApplication,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
        intent.getIntExtra("slide_start", 0).coerceIn(0, AfWidgetConfig.MAX_SOURCES - 1))
}

internal class SlidesFactory(private val app: AFFileManagerApplication, private val id: Int, private val presentationStart: Int? = null) : RemoteViewsService.RemoteViewsFactory {
    private data class Slide(val file: File, val sourceIndex: Int)
    @Volatile private var files = emptyList<Slide>()
    @Volatile private var failed = false
    override fun onCreate() = Unit
    override fun onDataSetChanged() {
        failed = false
        files = try {
            if (!AfWidgetProvider.owns(app, id) || app.graph.appLock.enabled.value) emptyList() else runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(5000) {
                    AfWidgetStore(app).load(id)?.takeIf { it.kind == AfWidgetKind.IMAGES }?.let { config ->
                        val sources = AfWidgetRenderer.sources(config).mapIndexed { index, file -> Slide(file, index) }
                        val start = (presentationStart ?: config.index).coerceIn(0, (sources.size - 1).coerceAtLeast(0))
                        sources.drop(start) + sources.take(start)
                    }
                } ?: run { failed = true; emptyList() }
            }
        } catch (_: Exception) { failed = true; emptyList() }
    }
    override fun onDestroy() { files = emptyList() }
    override fun getCount() = if (app.graph.appLock.enabled.value || !AfWidgetProvider.owns(app, id)) 0 else if (failed) 1 else files.size
    override fun getViewTypeCount() = 1
    override fun hasStableIds() = true
    override fun getItemId(position: Int): Long = files.getOrNull(position)?.let {
        java.lang.Long.parseUnsignedLong(fingerprint(it.file), 16)
    } ?: position.toLong()
    override fun getLoadingView(): RemoteViews = RemoteViews(app.packageName, R.layout.af_widget_loading).apply {
        // Loading views are not collection items. A distinct text-only layout
        // cannot inherit a photo row's bitmap, root tag or fill-in listeners.
        val color = resolveAfOpaqueColors(app, app.graph.appearance.settings.value,
            app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        setTextViewText(R.id.widget_slide_name, AfWidgetRenderer.text(app, "Ruošiama…"))
        setTextColor(R.id.widget_slide_name, color.onSurface.toArgb())
        setInt(R.id.widget_slide_name, "setLayoutDirection", app.appLanguageContext().resources.configuration.layoutDirection)
    }
    override fun getViewAt(position: Int): RemoteViews {
        if (failed) return unavailable()
        val config = AfWidgetStore(app).load(id)
        val slide = files.getOrNull(position)
        val file = slide?.file
        if (config == null || file == null || !AfWidgetProvider.owns(app, id) || app.graph.appLock.enabled.value) return unavailable()
        return try { runBlocking(Dispatchers.IO) {
            val view = RemoteViews(app.packageName, R.layout.af_widget_slide)
            view.setViewVisibility(R.id.widget_slide_image, android.view.View.VISIBLE)
            view.setViewVisibility(R.id.widget_slide_previous, android.view.View.VISIBLE)
            view.setViewVisibility(R.id.widget_slide_next, android.view.View.VISIBLE)
            val color = resolveAfOpaqueColors(app, app.graph.appearance.settings.value,
                app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            val visual = withTimeoutOrNull(5000) {
                val edge = AfWidgetRenderer.artEdge(app, files.size)
                FileVisualLoader.loadLocal(app, app.graph.localFiles.toEntry(file), edge, edge, true)
            }
            if (app.graph.appLock.enabled.value || !AfWidgetProvider.owns(app, id)) return@runBlocking unavailable()
            if (visual == null) return@runBlocking unavailable()
            visual?.let { source ->
                val bitmap = if (config.rotation == 0) source.bitmap else Bitmap.createBitmap(source.bitmap, 0, 0, source.bitmap.width, source.bitmap.height,
                    Matrix().apply { postRotate(config.rotation.toFloat()) }, true)
                view.setImageViewBitmap(R.id.widget_slide_image, bitmap)
            }
            view.setTextColor(R.id.widget_slide_name, color.onSurface.toArgb())
            view.setInt(R.id.widget_slide_caption, "setBackgroundColor", color.surface.toArgb())
            view.setTextViewText(R.id.widget_slide_name, file.name)
            fun command(action: String) = Intent().putExtra("slide_index", slide.sourceIndex)
                .putExtra("slide_revision", fingerprint(file)).putExtra("slide_command", action)
            val open = command("open")
            view.setOnClickFillInIntent(R.id.widget_slide_root, open)
            // The collection root carries a fill-in tag for AdapterView's item click.
            // Bind the visible children too, so a nested host cannot consume their tap
            // as its default "open application" action instead of opening this image.
            view.setOnClickFillInIntent(R.id.widget_slide_image, open)
            view.setOnClickFillInIntent(R.id.widget_slide_name, open)
            listOf(R.id.widget_slide_previous to ("previous" to "Ankstesnis"), R.id.widget_slide_next to ("next" to "Kitas")).forEach { (button, action) ->
                view.setInt(button, "setColorFilter", color.onSurface.toArgb())
                view.setContentDescription(button, AfWidgetRenderer.text(app, action.second))
                view.setOnClickFillInIntent(button, command(action.first))
            }
            view
        } } catch (_: Exception) { unavailable() }
    }
    private fun unavailable() = status("Valdiklio turinys nepasiekiamas. Nustatymuose pasirinkite šaltinį iš naujo.")
    private fun status(message: String) = RemoteViews(app.packageName, R.layout.af_widget_slide).apply {
        setTextViewText(R.id.widget_slide_name, AfWidgetRenderer.text(app, message))
        setImageViewResource(R.id.widget_slide_image, 0)
        setViewVisibility(R.id.widget_slide_image, android.view.View.INVISIBLE)
        setViewVisibility(R.id.widget_slide_previous, android.view.View.GONE)
        setViewVisibility(R.id.widget_slide_next, android.view.View.GONE)
        val rejected = Intent().putExtra("slide_index", -1).putExtra("slide_revision", "").putExtra("slide_command", "unavailable")
        listOf(R.id.widget_slide_root, R.id.widget_slide_image, R.id.widget_slide_name, R.id.widget_slide_previous, R.id.widget_slide_next)
            .forEach { setOnClickFillInIntent(it, rejected) }
    }
    companion object {
        internal fun fingerprint(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest("${file.absolutePath}|${file.length()}|${file.lastModified()}".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }
}
