package com.affilemanager.app.advanced

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy

class PrivilegedWriteOutputStreamTest {
    @Test fun offsetsAndLargeInputsUseBoundedAcknowledgedChunksAndOneFinish() {
        val fake = Fake()
        val source = ByteArray(300_017) { (it % 251).toByte() }
        val output = PrivilegedWriteOutputStream(fake.service,"/owned")
        output.write(source,17,source.size-34)
        output.write(123)
        output.close()
        output.close()
        assertArrayEquals(source.copyOfRange(17,source.size-17)+byteArrayOf(123),fake.bytes.toByteArray())
        assertTrue(fake.maxChunk <= 65_536)
        assertEquals(1,fake.finished)
        assertEquals(0,fake.aborted)
    }

    @Test fun failedWritesAbortInsteadOfReportingACompletedFile() {
        val fake = Fake().apply { failWrite = true }
        val output = PrivilegedWriteOutputStream(fake.service,"/owned")
        assertThrows(IllegalStateException::class.java) { output.write(byteArrayOf(1,2,3)) }
        output.close()
        output.close()
        assertEquals(0,fake.finished)
        assertEquals(1,fake.aborted)
    }

    @Test fun failedSyncIsVisibleAndClosesTheOwnedHandle() {
        val fake = Fake().apply { failFinish = true }
        val output = PrivilegedWriteOutputStream(fake.service,"/owned")
        output.write(1)
        assertThrows(IllegalStateException::class.java) { output.close() }
        output.close()
        assertEquals(1,fake.finished)
        assertEquals(1,fake.aborted)
    }

    @Test fun invalidRangesAndUnsupportedHandlesCannotWrite() {
        val fake = Fake()
        PrivilegedWriteOutputStream(fake.service,"/owned").use { output ->
            assertThrows(IllegalArgumentException::class.java) { output.write(ByteArray(3),-1,1) }
            assertThrows(IllegalArgumentException::class.java) { output.write(ByteArray(3),0,4) }
        }
        assertEquals(0,fake.bytes.size())
        fake.handle = 0L
        assertThrows(IllegalStateException::class.java) { PrivilegedWriteOutputStream(fake.service,"/owned") }
    }

    private class Fake {
        val bytes = ByteArrayOutputStream()
        var handle = 1L
        var maxChunk = 0
        var finished = 0
        var aborted = 0
        var failWrite = false
        var failFinish = false
        val service = Proxy.newProxyInstance(IPrivilegedFileService::class.java.classLoader,
            arrayOf(IPrivilegedFileService::class.java)) { _, method, arguments ->
            when (method.name) {
                "openPrivateWrite" -> handle
                "writePrivateFile" -> {
                    check(!failWrite)
                    val source = arguments[1] as ByteArray
                    val length = arguments[2] as Int
                    maxChunk = maxOf(maxChunk,source.size)
                    bytes.write(source,0,length)
                    length
                }
                "finishPrivateWrite" -> { finished++; check(!failFinish); null }
                "abortPrivateWrite" -> { aborted++; null }
                else -> error(method.name)
            }
        } as IPrivilegedFileService
    }
}
