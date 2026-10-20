package com.affilemanager.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalPathTextTest {
    @Test fun asciiPathsAreNotRewritten() {
        for (path in listOf("", "/", "/storage/emulated/0/Pictures", "content://provider/tree/primary%3APictures", "C:\\Users\\Public")) {
            assertEquals(path, canonicalPathForDisplay(path))
        }
    }

    @Test fun arabicComponentsAreIsolatedWithoutChangingTheCharactersOrSeparators() {
        assertEquals("/\u2068وثائق\u2069/\u2068صور\u2069/\u2068ملف\u2069", canonicalPathForDisplay("/وثائق/صور/ملف"))
        assertEquals("\u2068C:\u2069\\\u2068وثائق\u2069\\\u2068صور\u2069\\", canonicalPathForDisplay("C:\\وثائق\\صور\\"))
    }

    @Test fun emptyComponentsAndMixedUrlsRetainTheirExactSyntax() {
        assertEquals("\u2068sftp:\u2069//\u2068host\u2069//\u2068وثائق\u2069/\u20682026\u2069/",
            canonicalPathForDisplay("sftp://host//وثائق/2026/"))
    }

    @Test fun hebrewAndSupplementaryRtlCharactersAreAlsoIsolated() {
        assertEquals("/\u2068מסמכים\u2069/\u2068file.txt\u2069", canonicalPathForDisplay("/מסמכים/file.txt"))
        val adlam = String(Character.toChars(0x1E900))
        assertEquals("/\u2068$adlam\u2069/\u2068file\u2069", canonicalPathForDisplay("/$adlam/file"))
    }
}
