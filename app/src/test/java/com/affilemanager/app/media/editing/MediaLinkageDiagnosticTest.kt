package com.affilemanager.app.media.editing

import org.junit.Assert.*
import org.junit.Test

class MediaLinkageDiagnosticTest {
    @Test fun descriptorsExposeOnlyTheMissingClassIdentifier() {
        assertEquals("AF_MEDIA_CLASS=android.media.MissingType",
            mediaLinkageDiagnostic(NoClassDefFoundError("Failed resolution of: Landroid/media/MissingType;")))
        assertEquals("AF_MEDIA_CLASS=java.SomeType",
            mediaLinkageDiagnostic(ClassNotFoundException("java.SomeType")))
    }
    @Test fun wrappedFailuresAndQuotedAndroidMessagesStayBounded() {
        assertEquals("AF_MEDIA_CLASS=androidx.media3.MissingType",
            mediaLinkageDiagnostic(IllegalStateException("Export", ClassNotFoundException(
                "Didn't find class \"androidx.media3.MissingType\" on path: /private/source.mp4"))))
        assertNull(mediaLinkageDiagnostic(NoClassDefFoundError("a".repeat(200))))
    }
    @Test fun arbitraryNativeMessagesPathsAndUrisAreNeverDisplayed() {
        listOf("/storage/emulated/0/private.mp4", "https://user:secret@example.test/file", "secret text", "L../private;")
            .forEach { assertNull(mediaLinkageDiagnostic(NoClassDefFoundError(it))) }
        assertNull(mediaLinkageDiagnostic(IllegalStateException("/private/file")))
    }
    @Test fun codeLocationsDoNotExposeSourceFileNamesOrMessages() {
        val failure = NoClassDefFoundError("java.Missing")
        failure.stackTrace = Array(10) { StackTraceElement("a.b", "call", "/private/secret.file", 42) }
        val origin = requireNotNull(mediaLinkageOrigin(failure))
        assertEquals(4, origin.lines().size)
        assertFalse(origin.contains("secret"))
        assertNull(mediaLinkageOrigin(IllegalStateException("/private/secret.file")))
    }
}
