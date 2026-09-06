package com.affilemanager.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.transfer.LanTransferProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiPreferenceRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repository: UiPreferenceRepository

    @Before
    fun setUp() {
        context.deleteSharedPreferences(PREFERENCES_NAME)
        repository = UiPreferenceRepository(context)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(PREFERENCES_NAME)
    }

    @Test
    fun oldSharedDirectoryMigratesWithoutChangingAnyDestinationAndNewChoicesStayIndependent() {
        val prefs = context.getSharedPreferences(PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        check(prefs.edit().putString("share", """{"sharedPath":"/legacy","protocol":"FTP"}""").commit())
        val migrated = repository.loadShare("/default", "Phone")
        LanTransferProtocol.entries.forEach { assertEquals("/legacy", migrated.pathFor(it)) }
        assertEquals("/legacy", migrated.nearbyReceivePath)
        val changed = migrated.withPathFor(LanTransferProtocol.FTP, "/ftp")
            .withPathFor(LanTransferProtocol.WEB, "/web")
            .withPathFor(LanTransferProtocol.WEBDAV, "/dav")
            .copy(nearbyReceivePath = "/receive")
        repository.saveShare(changed, "/default", "Phone")
        assertEquals(changed, UiPreferenceRepository(context).loadShare("/default", "Phone"))
        assertEquals(changed, UiPreferenceRepository(context).loadShare("/other-default", "Other phone"))
    }

    @Test
    fun nonSecretShareAndSearchChoicesSurviveRepositoryRecreation() {
        repository.saveShare(
            ShareScreenPreferences(
                sharedPath = "/storage/emulated/0/Documents",
                protocol = LanTransferProtocol.FTP,
                durationMinutes = 35,
                portText = "2121",
                username = "af-user",
                readOnly = true,
                receiverName = "Work phone",
            ),
            defaultPath = "/storage/emulated/0",
            defaultReceiverName = "Android phone",
        )
        repository.saveSearchDraft(
            SearchDraftPreferences(
                scope = SearchScopePreference.SELECTED_STORAGE,
                selectedStoragePaths = setOf("/storage/emulated/0", "/storage/1234-5678"),
                includeHidden = true,
                useRegex = true,
                kinds = setOf(EntryKind.DOCUMENT, EntryKind.IMAGE),
                minimumMiB = "2.5",
                maximumMiB = "50",
                newerThanDays = 7,
                olderThanDays = 365,
                tags = setOf("work"),
                advancedExpanded = true,
            ),
        )

        val recreated = UiPreferenceRepository(context)
        assertEquals(
            ShareScreenPreferences(
                sharedPath = "/storage/emulated/0/Documents",
                protocol = LanTransferProtocol.FTP,
                durationMinutes = 35,
                portText = "2121",
                username = "af-user",
                readOnly = true,
                receiverName = "Work phone",
            ),
            recreated.loadShare("/storage/emulated/0", "Android phone"),
        )
        assertEquals(SearchScopePreference.SELECTED_STORAGE, recreated.loadSearchDraft().scope)
        assertEquals(setOf(EntryKind.DOCUMENT, EntryKind.IMAGE), recreated.loadSearchDraft().kinds)

        val raw = context.getSharedPreferences(PREFERENCES_NAME, android.content.Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("password", ignoreCase = true))
        assertFalse(raw.contains("pairing", ignoreCase = true))
    }

    private companion object {
        const val PREFERENCES_NAME = "ui_preferences_v1"
    }
}
