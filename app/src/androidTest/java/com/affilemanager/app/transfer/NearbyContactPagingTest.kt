package com.affilemanager.app.transfer

import android.database.MatrixCursor
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NearbyContactPagingTest {
    @Test fun boundedPagesReachEveryContactBeyondTheFormerThousandRowCutoff() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        MatrixCursor(arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)).use { cursor ->
            repeat(1_137) { cursor.addRow(arrayOf("lookup-$it", "Contact $it", null)) }
            val read = linkedSetOf<String>()
            var offset: Int? = 0
            var pages = 0
            while (offset != null) {
                val page = app.graph.nearbySources.readContactPage(cursor, offset!!)
                assertFalse(page.truncated)
                assertTrue(page.contacts.size <= NearbySourcePreparer.CONTACT_PAGE_ROWS)
                assertTrue(read.addAll(page.contacts.map { it.lookupKey }))
                offset = page.nextOffset
                pages++
            }
            assertEquals(1_137, read.size)
            assertEquals(5, pages)
            assertTrue("lookup-1136" in read)
        }
    }
}
