package com.affilemanager.app.apk

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkInstallMetadataReaderTest {
    @Test fun installedFixtureExposesTruthfulInstallationMetadata() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.applicationInfo.sourceDir)
        val metadata = ApkInstallMetadataReader.standalone(context, source)

        assertEquals(context.packageName, metadata.packageName)
        assertTrue(metadata.label.isNotBlank())
        assertTrue(metadata.candidateVersion.isNotBlank())
        assertTrue(metadata.fileBytes > 0)
        assertNotNull(metadata.minimumSdk)
        assertNotNull(metadata.targetSdk)
        assertNotNull(metadata.installedVersion)
    }
}
