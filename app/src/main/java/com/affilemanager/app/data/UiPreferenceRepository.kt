package com.affilemanager.app.data

import android.content.Context
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.transfer.LanTransferProtocol
import org.json.JSONArray
import org.json.JSONObject

data class ShareScreenPreferences(
    val sharedPath: String,
    val protocol: LanTransferProtocol = LanTransferProtocol.WEB,
    val durationMinutes: Int = 15,
    val portText: String = "",
    val username: String = "",
    val readOnly: Boolean = false,
    val receiverName: String = "Android phone",
)

enum class SearchScopePreference { CURRENT_FOLDER, ALL_STORAGE, SELECTED_STORAGE }

data class SearchDraftPreferences(
    val scope: SearchScopePreference = SearchScopePreference.CURRENT_FOLDER,
    val selectedStoragePaths: Set<String> = emptySet(),
    val includeHidden: Boolean = false,
    val useRegex: Boolean = false,
    val kinds: Set<EntryKind> = emptySet(),
    val minimumMiB: String = "",
    val maximumMiB: String = "",
    val newerThanDays: Int? = null,
    val olderThanDays: Int? = null,
    val tags: Set<String> = emptySet(),
    val advancedExpanded: Boolean = false,
)

/** Persists non-secret screen choices. Temporary passwords and pairing codes never enter this store. */
class UiPreferenceRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadShare(defaultPath: String, defaultReceiverName: String): ShareScreenPreferences = runCatching {
        val json = JSONObject(preferences.getString(KEY_SHARE, null) ?: return@runCatching defaultShare(defaultPath, defaultReceiverName))
        UiPreferenceRules.normalizeShare(
            ShareScreenPreferences(
                sharedPath = json.optString("sharedPath", defaultPath),
                protocol = enumValueOrDefault(json.optString("protocol"), LanTransferProtocol.WEB),
                durationMinutes = json.optInt("durationMinutes", 15),
                portText = json.optString("portText"),
                username = json.optString("username"),
                readOnly = json.optBoolean("readOnly"),
                receiverName = json.optString("receiverName", defaultReceiverName),
            ),
            defaultPath = defaultPath,
            defaultReceiverName = defaultReceiverName,
        )
    }.getOrElse { defaultShare(defaultPath, defaultReceiverName) }

    fun saveShare(value: ShareScreenPreferences, defaultPath: String, defaultReceiverName: String) {
        val normalized = UiPreferenceRules.normalizeShare(value, defaultPath, defaultReceiverName)
        val json = JSONObject()
            .put("sharedPath", normalized.sharedPath)
            .put("protocol", normalized.protocol.name)
            .put("durationMinutes", normalized.durationMinutes)
            .put("portText", normalized.portText)
            .put("username", normalized.username)
            .put("readOnly", normalized.readOnly)
            .put("receiverName", normalized.receiverName)
        check(preferences.edit().putString(KEY_SHARE, json.toString()).commit()) {
            "Nustatymų įrašyti nepavyko"
        }
    }

    fun loadSearchDraft(): SearchDraftPreferences = runCatching {
        val json = JSONObject(preferences.getString(KEY_SEARCH, null) ?: return@runCatching SearchDraftPreferences())
        UiPreferenceRules.normalizeSearch(
            SearchDraftPreferences(
                scope = enumValueOrDefault(json.optString("scope"), SearchScopePreference.CURRENT_FOLDER),
                selectedStoragePaths = json.stringSet("selectedStoragePaths"),
                includeHidden = json.optBoolean("includeHidden"),
                useRegex = json.optBoolean("useRegex"),
                kinds = json.stringSet("kinds").mapNotNullTo(linkedSetOf()) { enumValueOrNull<EntryKind>(it) },
                minimumMiB = json.optString("minimumMiB"),
                maximumMiB = json.optString("maximumMiB"),
                newerThanDays = json.optionalInt("newerThanDays"),
                olderThanDays = json.optionalInt("olderThanDays"),
                tags = json.stringSet("tags"),
                advancedExpanded = json.optBoolean("advancedExpanded"),
            ),
        )
    }.getOrElse { SearchDraftPreferences() }

    fun saveSearchDraft(value: SearchDraftPreferences) {
        val normalized = UiPreferenceRules.normalizeSearch(value)
        val json = JSONObject()
            .put("scope", normalized.scope.name)
            .put("selectedStoragePaths", normalized.selectedStoragePaths.toJsonArray())
            .put("includeHidden", normalized.includeHidden)
            .put("useRegex", normalized.useRegex)
            .put("kinds", normalized.kinds.map(EntryKind::name).toJsonArray())
            .put("minimumMiB", normalized.minimumMiB)
            .put("maximumMiB", normalized.maximumMiB)
            .put("newerThanDays", normalized.newerThanDays ?: JSONObject.NULL)
            .put("olderThanDays", normalized.olderThanDays ?: JSONObject.NULL)
            .put("tags", normalized.tags.toJsonArray())
            .put("advancedExpanded", normalized.advancedExpanded)
        check(preferences.edit().putString(KEY_SEARCH, json.toString()).commit()) {
            "Nustatymų įrašyti nepavyko"
        }
    }

    private fun defaultShare(defaultPath: String, defaultReceiverName: String): ShareScreenPreferences =
        UiPreferenceRules.normalizeShare(
            ShareScreenPreferences(sharedPath = defaultPath, receiverName = defaultReceiverName),
            defaultPath,
            defaultReceiverName,
        )

    private fun JSONObject.stringSet(key: String): Set<String> {
        val array = optJSONArray(key) ?: return emptySet()
        return buildSet {
            repeat(array.length()) { index ->
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun JSONObject.optionalInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun Collection<String>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach(array::put) }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValueOrNull<T>(value) ?: default

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private companion object {
        const val PREFS = "ui_preferences_v1"
        const val KEY_SHARE = "share"
        const val KEY_SEARCH = "search"
    }
}

internal object UiPreferenceRules {
    private const val MAX_PATH_LENGTH = 4_096
    private const val MAX_USERNAME_LENGTH = 128
    private const val MAX_RECEIVER_NAME_LENGTH = 80
    private const val MAX_SELECTED_ROOTS = 32
    private const val MAX_TAGS = 40
    private const val MAX_TAG_LENGTH = 120
    private val supportedDayChoices = setOf(7, 30, 365)

    fun normalizeShare(
        value: ShareScreenPreferences,
        defaultPath: String,
        defaultReceiverName: String,
    ): ShareScreenPreferences {
        val safeDefaultPath = cleanSingleLine(defaultPath, MAX_PATH_LENGTH).ifBlank { "/" }
        val safeDefaultName = cleanSingleLine(defaultReceiverName, MAX_RECEIVER_NAME_LENGTH).ifBlank { "Android phone" }
        return value.copy(
            sharedPath = cleanSingleLine(value.sharedPath, MAX_PATH_LENGTH).ifBlank { safeDefaultPath },
            durationMinutes = value.durationMinutes.coerceIn(5, 60),
            portText = value.portText.filter(Char::isDigit).take(5),
            username = cleanSingleLine(value.username, MAX_USERNAME_LENGTH),
            receiverName = cleanSingleLine(value.receiverName, MAX_RECEIVER_NAME_LENGTH).ifBlank { safeDefaultName },
        )
    }

    fun normalizeSearch(value: SearchDraftPreferences): SearchDraftPreferences = value.copy(
        selectedStoragePaths = value.selectedStoragePaths.asSequence()
            .map { cleanSingleLine(it, MAX_PATH_LENGTH) }
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_SELECTED_ROOTS)
            .toCollection(linkedSetOf()),
        minimumMiB = decimal(value.minimumMiB),
        maximumMiB = decimal(value.maximumMiB),
        newerThanDays = value.newerThanDays?.takeIf(supportedDayChoices::contains),
        olderThanDays = value.olderThanDays?.takeIf(supportedDayChoices::contains),
        tags = value.tags.asSequence()
            .map { cleanSingleLine(it, MAX_TAG_LENGTH) }
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_TAGS)
            .toCollection(linkedSetOf()),
    )

    private fun decimal(value: String): String {
        var decimalSeen = false
        return buildString {
            value.forEach { character ->
                when {
                    character.isDigit() -> append(character)
                    (character == '.' || character == ',') && !decimalSeen -> {
                        append('.')
                        decimalSeen = true
                    }
                }
            }
        }.take(16)
    }

    private fun cleanSingleLine(value: String, maximumLength: Int): String =
        value.filterNot(Char::isISOControl).trim().take(maximumLength)
}
