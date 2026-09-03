package com.affilemanager.app.terminal

import android.content.Context
import android.os.IBinder
import android.os.IInterface
import android.os.Binder
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.advanced.IPrivilegedFileService
import com.affilemanager.app.advanced.ShizukuFileService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivilegedPtyBackendTest {
    @Test
    fun largeInputIsParceledInBoundedChunks() = runBlocking {
        val received = ByteArrayOutputStream()
        val localService = object : IPrivilegedFileService.Stub() {
            override fun getFileSystemService(): IBinder = Binder()
            override fun getProcessUid(): Int = Process.myUid()
            override fun openTerminal(path: String, rows: Int, columns: Int): Long = 1L
            override fun readTerminal(handle: Long, destination: ByteArray): Int = 0
            override fun resizeTerminal(handle: Long, rows: Int, columns: Int) = Unit
            override fun closeTerminal(handle: Long) = Unit
            override fun destroy() = Unit
            override fun writeTerminal(handle: Long, source: ByteArray, offset: Int, length: Int): Int {
                assertTrue(source.size <= TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES)
                assertEquals(0, offset)
                assertEquals(source.size, length)
                received.write(source, offset, length)
                return length
            }
        }
        val remoteBinder = object : IBinder by localService.asBinder() {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
        }
        val backend = PrivilegedPtyBackend.open(IPrivilegedFileService.Stub.asInterface(remoteBinder), "/")
        try {
            val source = ByteArray(128 * 1_024) { (it % 251).toByte() }
            backend.write(source, 17, source.size - 37)
            assertArrayEquals(source.copyOfRange(17, source.size - 20), received.toByteArray())
        } finally {
            backend.close()
        }
    }

    @Test
    fun rootServiceBinderUsesTheSamePtyContractWithoutClaimingDeviceRoot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(PrivilegedTerminalRuntimeVerifier.verifyRootServiceContract(context))
    }

    @Test
    fun privilegedServiceTransportStartsInRequestedDirectoryAndReturnsOutput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "privileged-pty-${System.nanoTime()}").apply { mkdirs() }
        val working = File(root, "root terminal folder").apply { mkdirs() }
        val localService = ShizukuFileService(context)
        val remoteBinder = object : IBinder by localService.asBinder() {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
        }
        val service = IPrivilegedFileService.Stub.asInterface(remoteBinder)
        val backend = PrivilegedPtyBackend.open(
            service = service,
            workingDirectory = working.absolutePath,
        )
        try {
            val marker = "AF_PRIVILEGED_PTY_${System.nanoTime()}"
            backend.write("pwd; id; printf '$marker\\n'; exit\n".toByteArray())
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
            assertTrue(text, text.contains("uid="))
            assertTrue(text, text.lineSequence().any { it.trimEnd('\r') == marker })
        } finally {
            backend.close()
            root.deleteRecursively()
        }
    }
}
