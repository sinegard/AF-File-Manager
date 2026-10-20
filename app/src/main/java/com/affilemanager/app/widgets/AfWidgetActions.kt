package com.affilemanager.app.widgets

import android.content.Context
import android.net.Uri
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.media.BackgroundMediaItem
import com.affilemanager.app.media.BackgroundPlaybackService
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Invoked only by AF's authenticated main composition, never by a receiver/service. */
internal suspend fun openAfWidget(context: Context, viewModel: MainViewModel, request: AfWidgetRequest) {
    require(AfWidgetProvider.owns(context, request.id))
    val app = context.applicationContext as AFFileManagerApplication
    val store = AfWidgetStore(context)
    var config = store.load(request.id) ?: return
    when (config.kind) {
        AfWidgetKind.STORAGE -> viewModel.setSection(AppSection.ANALYZE)
        AfWidgetKind.CLEANUP -> { viewModel.setSection(AppSection.ANALYZE); viewModel.openDeviceCleanup() }
        AfWidgetKind.PHONE_SHARE -> viewModel.openSharingFromWidget(phone = true)
        AfWidgetKind.COMPUTER_SHARE -> viewModel.openSharingFromWidget(phone = false)
        AfWidgetKind.SEARCH -> {
            require(withContext(Dispatchers.IO) { File(config.folder).let { it.isDirectory && it.canRead() } })
            viewModel.activatePanel(PanelId.LEFT)
            viewModel.openQuickPathFromHome(config.folder, PanelId.LEFT)
            viewModel.setSection(AppSection.ANALYZE)
            if (config.query.isNotBlank()) viewModel.search(SearchFilters(query = config.query), listOf(config.folder))
        }
        AfWidgetKind.SHORTCUT -> {
            val file = File(config.sources.firstOrNull() ?: config.folder)
            val entry = withContext(Dispatchers.IO) { require(file.exists() && file.canRead()); app.graph.localFiles.toEntry(file) }
            if (entry.isDirectory) viewModel.openQuickPathFromHome(file.path) else viewModel.open(entry)
        }
        AfWidgetKind.IMAGES, AfWidgetKind.VIDEO, AfWidgetKind.MUSIC -> {
            val files = AfWidgetRenderer.sources(config)
            require(files.isNotEmpty())
            if (request.action.startsWith("slide-")) {
                val parts = request.action.split('-')
                val index = parts.getOrNull(1)?.toIntOrNull() ?: error("Invalid widget image")
                val file = requireNotNull(files.getOrNull(index))
                require(parts.getOrNull(2) == SlidesFactory.fingerprint(file))
                config = config.copy(index = index)
            }
            if (request.action in setOf("previous", "next")) {
                val direction = if (request.action == "previous") -1 else 1
                val next = if (config.repeat) Math.floorMod(config.index + direction, files.size) else (config.index + direction).coerceIn(files.indices)
                config = config.copy(index = next)
                withContext(Dispatchers.IO) { store.save(request.id, config) }
            }
            val file = files[config.index.coerceAtMost(files.lastIndex)]
            if (config.kind == AfWidgetKind.MUSIC && request.action != "open") {
                val uri = Uri.fromFile(file)
                if (request.action == "play" && BackgroundPlaybackService.state.value?.takeIf { it.active }?.uri == uri.toString()) BackgroundPlaybackService.toggle(context)
                else BackgroundPlaybackService.play(context, uri, file.name, 0, config.repeat, config.speed, 1f,
                    playlist = files.map { BackgroundMediaItem(Uri.fromFile(it).toString(), it.name) })
            } else {
                val entry = withContext(Dispatchers.IO) { app.graph.localFiles.toEntry(file) }
                if (config.kind == AfWidgetKind.VIDEO) viewModel.openWidgetMedia(entry,
                    com.affilemanager.app.ui.InitialMediaPlayback(config.speed, config.repeat, request.action != "open"))
                else viewModel.open(entry)
            }
        }
    }
    AfWidgetProvider.refresh(context)
}
