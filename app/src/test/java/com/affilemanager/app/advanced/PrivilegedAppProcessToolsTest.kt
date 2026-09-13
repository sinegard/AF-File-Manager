package com.affilemanager.app.advanced

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedAppProcessToolsTest {
    @Test
    fun `parser sums child processes and rejects system commands`() {
        val parsed = PrivilegedAppProcessTools.parsePsOutput(
            """
            RSS NAME
            1200 com.example.one
            300 com.example.one:worker
            500 com.example.two
            900 surfaceflinger
            invalid com.example.bad
            """.trimIndent(),
        )

        assertEquals(1_500L * 1_024L, parsed["com.example.one"])
        assertEquals(500L * 1_024L, parsed["com.example.two"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun `package validation cannot become a shell command`() {
        assertEquals("com.example.app", PrivilegedAppProcessTools.requireValidPackageName("com.example.app"))
        listOf("com.example.app;id", "com example", "../data", "android", "").forEach { value ->
            assertTrue(runCatching { PrivilegedAppProcessTools.requireValidPackageName(value) }.isFailure)
        }
    }
}
