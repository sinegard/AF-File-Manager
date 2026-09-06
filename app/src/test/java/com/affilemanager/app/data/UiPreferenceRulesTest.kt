package com.affilemanager.app.data

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.transfer.LanTransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UiPreferenceRulesTest {
    @Test
    fun shareDirectoriesChangeOnlyForTheirSelectedDestination() {
        val original = ShareScreenPreferences("/legacy")
        val changed = original.withPathFor(LanTransferProtocol.FTP, "/ftp")
            .withPathFor(LanTransferProtocol.WEBDAV, "/dav")
            .withPathFor(LanTransferProtocol.WEB, "/web")
            .copy(nearbyReceivePath = "/phone")
        assertEquals("/web", changed.pathFor(LanTransferProtocol.WEB))
        assertEquals("/ftp", changed.pathFor(LanTransferProtocol.FTP))
        assertEquals("/dav", changed.pathFor(LanTransferProtocol.WEBDAV))
        assertEquals("/phone", changed.nearbyReceivePath)
        LanTransferProtocol.entries.forEach { assertEquals("/legacy", original.pathFor(it)) }
        assertEquals("/legacy", original.nearbyReceivePath)
    }

    @Test
    fun shareChoicesAreBoundedAndSecretsHaveNoPersistedField() {
        val normalized = UiPreferenceRules.normalizeShare(
            ShareScreenPreferences(
                sharedPath = "\n/storage/emulated/0/Download\r",
                protocol = LanTransferProtocol.WEBDAV,
                durationMinutes = 600,
                portText = "80x80",
                username = " user\nname ",
                receiverName = "\u0000My phone\n",
            ),
            defaultPath = "/storage/emulated/0",
            defaultReceiverName = "Android phone",
        )

        assertEquals("/storage/emulated/0/Download", normalized.sharedPath)
        assertEquals(LanTransferProtocol.WEBDAV, normalized.protocol)
        assertEquals(60, normalized.durationMinutes)
        assertEquals("8080", normalized.portText)
        assertEquals("username", normalized.username)
        assertEquals("My phone", normalized.receiverName)
        assertFalse(ShareScreenPreferences::class.java.declaredFields.any { it.name.contains("password", ignoreCase = true) })
        assertFalse(ShareScreenPreferences::class.java.declaredFields.any { it.name.contains("code", ignoreCase = true) })
    }

    @Test
    fun searchDraftKeepsOnlySupportedBoundedChoices() {
        val normalized = UiPreferenceRules.normalizeSearch(
            SearchDraftPreferences(
                scope = SearchScopePreference.SELECTED_STORAGE,
                selectedStoragePaths = (1..40).mapTo(linkedSetOf()) { "/storage/$it" },
                kinds = setOf(EntryKind.DOCUMENT),
                minimumMiB = " 1,25 MiB ",
                maximumMiB = "200.5",
                newerThanDays = 13,
                olderThanDays = 30,
                tags = (1..50).mapTo(linkedSetOf()) { " tag-$it\n" },
                advancedExpanded = true,
            ),
        )

        assertEquals(SearchScopePreference.SELECTED_STORAGE, normalized.scope)
        assertEquals(32, normalized.selectedStoragePaths.size)
        assertEquals(setOf(EntryKind.DOCUMENT), normalized.kinds)
        assertEquals("1.25", normalized.minimumMiB)
        assertEquals("200.5", normalized.maximumMiB)
        assertNull(normalized.newerThanDays)
        assertEquals(30, normalized.olderThanDays)
        assertEquals(40, normalized.tags.size)
        assertEquals("tag-1", normalized.tags.first())
        assertEquals(true, normalized.advancedExpanded)
    }
}
