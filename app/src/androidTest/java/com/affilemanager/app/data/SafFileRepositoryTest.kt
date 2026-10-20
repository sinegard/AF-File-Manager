package com.affilemanager.app.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SafFileRepositoryTest {
    @Test fun typedAddressDoesNotGrantAccessToAnUnselectedProviderOrFileUri() = runBlocking {
        listOf("file:///data/data/com.termux/files/home", "content://unselected.provider/tree/home",
            "https://example.com/tree/home", "content://unselected.provider/document/home").forEach { address ->
            assertTrue(address, repository.resolveAddress(address).isFailure)
        }
    }
    private val repository = SafFileRepository(
        ApplicationProvider.getApplicationContext<AFFileManagerApplication>(),
    )

    @Test
    fun treeRootAndChildDocumentUrisAreDistinguished() {
        val root = Uri.parse("content://com.google.android.apps.docs.storage/tree/drive-root")
        val child = Uri.parse(
            "content://com.google.android.apps.docs.storage/tree/drive-root/document/drive-child",
        )

        assertTrue(repository.isTreeRootUri(root))
        assertFalse(repository.isTreeRootUri(child))
        assertTrue(repository.usesPersistedTreeAccess(root))
        assertTrue(repository.usesPersistedTreeAccess(child))
    }
}
