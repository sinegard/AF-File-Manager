package com.affilemanager.app.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalPtyBackendTest {
    @Test
    fun interactiveShellStartsInRequestedDirectoryAndReturnsOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "pty-test-${System.nanoTime()}").apply { mkdirs() }
        val working = File(root, "working folder").apply { mkdirs() }
        val backend = LocalPtyBackend.open(
            workingDirectory = working,
            homeDirectory = File(root, "home"),
            temporaryDirectory = File(root, "tmp"),
        )
        try {
            val marker = "AF_PTY_OUTPUT_${System.nanoTime()}"
            backend.write("pwd; printf '$marker\\n'; exit\n".toByteArray())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1_024)
            withTimeout(10_000) {
                while (true) {
                    val count = backend.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        val text = output.toString(Charsets.UTF_8.name())
                        if (text.lineSequence().any { it.trimEnd('\r') == marker }) break
                    }
                }
            }
            val text = output.toString(Charsets.UTF_8.name())
            assertTrue(text, text.contains(working.canonicalPath))
            assertTrue(text, text.lineSequence().any { it.trimEnd('\r') == marker })
        } finally {
            backend.close()
            root.deleteRecursively()
        }
    }
}
