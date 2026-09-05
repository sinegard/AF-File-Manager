package com.affilemanager.app.ui.screens

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.transfer.NearbySourcePreparer
import org.junit.Assert.*
import org.junit.Test

class NearbyPickerSelectionTest {
    private fun entry(index: Int) = FileEntry("/page/$index.jpg", "$index.jpg", EntryKind.IMAGE, 12, 1, false, true, true)

    @Test fun selectionRetainsEarlierPagesAndSearchResults() {
        val first = entry(1)
        val later = entry(10_001)
        val selected = NearbyPickerSelection.toggle(NearbyPickerSelection.toggle(emptyMap(), first), later)
        assertEquals(listOf(first, later), selected.values.toList())
        assertEquals(mapOf(later.absolutePath to later), NearbyPickerSelection.toggle(selected, first))
    }

    @Test fun pageToggleOnlyRemovesThatPageAndNeverExceedsTransferAdmissionLimit() {
        val firstPage = (0 until 240).map(::entry)
        var selection = NearbyPickerSelection.togglePage(emptyMap(), firstPage)
        selection = NearbyPickerSelection.toggle(selection, entry(10_001))
        selection = NearbyPickerSelection.togglePage(selection, firstPage)
        assertEquals(listOf(entry(10_001)), selection.values.toList())
        selection = NearbyPickerSelection.togglePage(selection, (0 until 1_200).map(::entry))
        assertEquals(NearbySourcePreparer.MAX_FILES, selection.size)
        assertTrue(entry(10_001).absolutePath in selection)
        assertEquals(selection, NearbyPickerSelection.toggle(selection, entry(50_000)))
        assertEquals(selection.size - 1, NearbyPickerSelection.toggle(selection, entry(10_001)).size)
    }
}
