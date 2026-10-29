package com.affilemanager.app.advanced

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PrivilegedReadInputStreamTest {
    @After fun closeOwnedReads() = PrivilegedReadInputStream.closeAll()

    @Test fun primingReplaysEveryByteExactlyOnceIncludingSingleAndBulkReads() {
        val bytes = ByteArray(300_017) { (it % 251).toByte() }
        PrivilegedReadInputStream({ ByteArrayInputStream(bytes) }).use { input ->
            assertEquals(bytes[0].toInt() and 255,input.read())
            assertArrayEquals(bytes.copyOfRange(1,bytes.size),input.readBytes())
            assertEquals(-1,input.read())
        }
        PrivilegedReadInputStream({ ByteArrayInputStream(bytes) }).use { input ->
            val first = ByteArray(5) { -1 }
            assertEquals(1,input.read(first,2,2))
            assertEquals(bytes[0],first[2])
            assertEquals((-1).toByte(),first[1])
            assertArrayEquals(bytes.copyOfRange(1,bytes.size),input.readBytes())
        }
    }

    @Test fun emptyAndOneByteFilesReachRealEof() {
        for (bytes in listOf(byteArrayOf(),byteArrayOf(0),byteArrayOf(-1))) {
            PrivilegedReadInputStream({ ByteArrayInputStream(bytes) }).use { input ->
                assertArrayEquals(bytes,input.readBytes())
                assertEquals(-1,input.read(ByteArray(1)))
                assertEquals(0,input.read(ByteArray(0)))
            }
        }
    }

    @Test fun aStaleAvailableEstimateDoesNotTruncateTheNewFile() {
        val bytes = "edited in AF\nsecond line\n".toByteArray()
        val original = object : ByteArrayInputStream(bytes) { override fun available() = 8 }
        PrivilegedReadInputStream({ original }).use { assertArrayEquals(bytes,it.readBytes()) }
    }

    @Test fun skipAndInvalidRangesDoNotLoseOrDuplicateThePrimedByte() {
        PrivilegedReadInputStream({ ByteArrayInputStream(ByteArray(12) { it.toByte() }) }).use { input ->
            assertThrows(IndexOutOfBoundsException::class.java) { input.read(ByteArray(3),-1,1) }
            assertThrows(IndexOutOfBoundsException::class.java) { input.read(ByteArray(3),1,3) }
            assertEquals(0,input.read(ByteArray(0)))
            assertEquals(0L,input.skip(-3))
            assertEquals(6L,input.skip(6))
            assertEquals(6,input.read())
        }
    }

    @Test fun noMoreThanEightReadsAreAdmittedAndExplicitCloseReleasesOneSlot() {
        val streams = MutableList(8) { PrivilegedReadInputStream({ Tracked() }) }
        assertThrows(IOException::class.java) { PrivilegedReadInputStream({ Tracked() }) }
        streams[0].close()
        PrivilegedReadInputStream({ Tracked() }).close()
        streams.forEach { it.close() }
    }

    @Test fun failedOpenAndFailedFirstReadReleaseTheirReservations() {
        repeat(16) {
            assertThrows(IOException::class.java) { PrivilegedReadInputStream({ throw IOException("open") }) }
            val source = Tracked(failPrime=true)
            assertThrows(IOException::class.java) { PrivilegedReadInputStream({ source }) }
            assertEquals(1,source.closes)
        }
        assertCapacityAvailable()
    }

    @Test fun failedLaterReadClosesTheOwnedStreamAndDoesNotReportEof() {
        val source = Tracked(failBulk=true)
        val input = PrivilegedReadInputStream({ source })
        assertEquals(1,input.read(ByteArray(3)))
        assertThrows(IOException::class.java) { input.read(ByteArray(3)) }
        input.close()
        assertEquals(1,source.closes)
        assertCapacityAvailable()
    }

    @Test fun disconnectClosesLiveStreamsAndReadAfterCloseIsAnError() {
        val sources = List(8) { Tracked() }
        val streams = sources.map { source -> PrivilegedReadInputStream({ source }) }
        PrivilegedReadInputStream.closeAll()
        for (index in streams.indices) {
            assertThrows(IOException::class.java) { streams[index].read() }
            streams[index].close()
            assertEquals(1,sources[index].closes)
        }
        assertCapacityAvailable()
    }

    @Test fun aLateReplyCannotReviveAnOpenCancelledDuringDisconnect() {
        val entered = CountDownLatch(1)
        val reply = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val source = Tracked()
        try {
            val result = executor.submit<Boolean> {
                try {
                    PrivilegedReadInputStream({ entered.countDown(); check(reply.await(5,TimeUnit.SECONDS)); source }).close()
                    false
                } catch (_: IOException) { true }
            }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            PrivilegedReadInputStream.closeAll()
            reply.countDown()
            assertTrue(result.get(5,TimeUnit.SECONDS))
            assertEquals(1,source.closes)
            assertCapacityAvailable()
        } finally { reply.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test fun providerDeathOrRevokedOwnershipClosesTheDescriptor() {
        val alive = AtomicBoolean(true)
        val source = Tracked()
        val input = PrivilegedReadInputStream({ source },alive::get)
        alive.set(false)
        assertThrows(IOException::class.java) { input.read() }
        assertEquals(1,source.closes)
        assertCapacityAvailable()
    }

    @Test fun closeFailureIsVisibleAndDoesNotRetainAnAdmissionSlot() {
        val source = Tracked(failClose=true)
        val input = PrivilegedReadInputStream({ source })
        assertThrows(IOException::class.java) { input.close() }
        input.close()
        assertEquals(1,source.closes)
        assertCapacityAvailable()
    }

    private fun assertCapacityAvailable() {
        val reads = List(8) { PrivilegedReadInputStream({ Tracked() }) }
        reads.forEach { it.close() }
    }

    @Test fun revokedBeforeAdmissionDoesNotEvenCallTheProvider() {
        var opens = 0
        repeat(16) {
            assertThrows(IOException::class.java) {
                PrivilegedReadInputStream(openInput = { opens++; Tracked() }, isAlive = { false })
            }
        }
        assertEquals(0,opens)
        val valid = List(8) { PrivilegedReadInputStream({ Tracked() }) }
        valid.forEach { it.close() }
    }

    private class Tracked(val failPrime: Boolean=false,val failBulk: Boolean=false,val failClose: Boolean=false) : InputStream() {
        var closes = 0
        override fun read(): Int { if(failPrime)throw IOException("prime"); return 97 }
        override fun read(bytes: ByteArray,offset: Int,length: Int): Int { if(failBulk)throw IOException("read"); return -1 }
        override fun close() { closes++; if(failClose)throw IOException("close") }
    }
}
