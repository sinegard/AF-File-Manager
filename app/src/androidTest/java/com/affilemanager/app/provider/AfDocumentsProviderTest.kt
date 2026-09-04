package com.affilemanager.app.provider

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AfDocumentsProviderTest {
    @Test
    fun providerPublishesAtLeastOneWritableStorageRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.documents"
        val projection = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
        )

        context.contentResolver.query(
            DocumentsContract.buildRootsUri(authority),
            projection,
            null,
            null,
            null,
        ).use { cursor ->
            requireNotNull(cursor)
            assertTrue("AF DocumentsProvider exposes no storage roots", cursor.moveToFirst())
            assertTrue(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID)).isNotBlank())
            assertTrue(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID)).isNotBlank())
            assertTrue(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_TITLE)).isNotBlank())
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertEquals(Root.FLAG_SUPPORTS_CREATE, flags and Root.FLAG_SUPPORTS_CREATE)
        }
    }
}
