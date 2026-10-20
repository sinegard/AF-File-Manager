package com.affilemanager.app.widgets

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject

internal enum class AfWidgetKind(val label: String) {
    STORAGE("Saugykla"), CLEANUP("Įrenginio valymas"), PHONE_SHARE("Bendrinti su telefonu"),
    COMPUTER_SHARE("Bendrinti su kompiuteriu"), SHORTCUT("Failas arba aplankas"), SEARCH("Gilioji paieška"),
    IMAGES("Nuotraukos"), VIDEO("Vaizdo įrašai"), MUSIC("Muzika"),
}
internal enum class AfWidgetStorage(val label: String) { INTERNAL("Vidinė atmintis"), SD("SD kortelė"), USB("USB"), ROOT("Root"), RAM("RAM") }
internal enum class AfWidgetTransition(val label: String) { NONE("Nėra"), FADE("Išblukimas"), SLIDE("Slinkimas") }

internal data class AfWidgetConfig(
    val kind: AfWidgetKind = AfWidgetKind.STORAGE,
    val title: String = "",
    val folder: String = Environment.getExternalStorageDirectory().absolutePath,
    val sources: List<String> = emptyList(),
    val horizontal: Boolean = false,
    val opacity: Int = 100,
    val density: Int = 100,
    val rotation: Int = 0,
    val repeat: Boolean = true,
    val speed: Float = 1f,
    val query: String = "",
    val index: Int = 0,
    val storage: AfWidgetStorage = AfWidgetStorage.INTERNAL,
    val slideSeconds: Int = 30,
    val transition: AfWidgetTransition = AfWidgetTransition.FADE,
) {
    fun validated(): AfWidgetConfig = apply {
        require(title.length <= 120 && title.none(Char::isISOControl))
        require(folder.length in 1..4096 && folder.startsWith('/') && !folder.startsWith("//") && folder.none(Char::isISOControl))
        require(sources.size <= MAX_SOURCES && sources.distinct().size == sources.size)
        require(sources.all { value -> value.length in 1..4096 && value.none(Char::isISOControl) &&
            value.startsWith('/') && !value.startsWith("//") })
        require(opacity in 25..100 && density in 75..125 && rotation in setOf(0, 90, 180, 270))
        require(speed.isFinite() && speed in .5f..2f)
        require(query.length <= 512 && index in 0 until MAX_SOURCES)
        require(slideSeconds in setOf(5, 15, 30, 60, 300))
    }

    companion object { const val MAX_SOURCES = 32 }
}

/** Private per-instance settings, not a new document/provider or a cache of user media. */
internal class AfWidgetStore(context: Context, name: String = "home_widgets_v1") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun load(id: Int): AfWidgetConfig? = preferences.getString(id.toString(), null)?.let { raw ->
        runCatching {
            require(raw.length <= 140_000)
            val json = JSONObject(raw)
            val paths = json.optJSONArray("sources") ?: JSONArray()
            require(paths.length() <= AfWidgetConfig.MAX_SOURCES)
            AfWidgetConfig(kind = AfWidgetKind.valueOf(json.getString("kind")), title = json.optString("title"), folder = json.getString("folder"),
                sources = List(paths.length()) { paths.getString(it) }, horizontal = json.optBoolean("horizontal"), opacity = json.optInt("opacity", 100),
                density = json.optInt("density", 100), rotation = json.optInt("rotation"), repeat = json.optBoolean("repeat", true),
                speed = json.optDouble("speed", 1.0).toFloat(), query = json.optString("query"), index = json.optInt("index"),
                storage = AfWidgetStorage.valueOf(json.optString("storage", "INTERNAL")), slideSeconds = json.optInt("slideSeconds", 30),
                transition = AfWidgetTransition.valueOf(json.optString("transition", "FADE"))).validated()
        }.getOrNull()
    }
    fun save(id: Int, config: AfWidgetConfig) = synchronized(writeLock) {
        require(id > 0)
        config.validated()
        require(preferences.contains(id.toString()) || preferences.all.size < MAX_WIDGETS) { "Pasiekta valdiklių skaičiaus riba" }
        val value = JSONObject().put("kind", config.kind.name).put("title", config.title).put("folder", config.folder)
            .put("sources", JSONArray(config.sources)).put("horizontal", config.horizontal).put("opacity", config.opacity)
            .put("density", config.density).put("rotation", config.rotation)
            .put("repeat", config.repeat).put("speed", config.speed).put("query", config.query).put("index", config.index)
            .put("storage", config.storage.name).put("slideSeconds", config.slideSeconds).put("transition", config.transition.name).toString()
        check(value.length <= 140_000 && preferences.edit().putString(id.toString(), value).commit()) { "Nepavyko išsaugoti valdiklio nustatymų" }
    }
    fun compareAndSet(id: Int, expected: AfWidgetConfig, updated: AfWidgetConfig): Boolean = synchronized(writeLock) {
        if (load(id) != expected) false else { save(id, updated); true }
    }
    fun remove(id: Int) = synchronized(writeLock) {
        check(preferences.edit().remove(id.toString()).commit())
    }
    companion object { const val MAX_WIDGETS = 32; private val writeLock = Any() }
}
