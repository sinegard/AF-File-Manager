package com.affilemanager.app.advanced

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.IInterface
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual Android FD/Parcel path in the app UID; not a claim of device Root. */
@RunWith(AndroidJUnit4::class)
class PrivilegedReadDescriptorAndroidTest {
    private lateinit var root: File
    private lateinit var service: IPrivilegedFileService
    private var accepted = false

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir,"read-descriptor-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val binder = RootFileService().onBind(Intent())
        val proxy = object : IBinder by binder { override fun queryLocalInterface(name: String): IInterface? = null }
        service = IPrivilegedFileService.Stub.asInterface(proxy)
        assertEquals(PrivilegedFileServiceProtocol.READ_VERSION,service.fileReadVersion)
    }

    @After fun cleanUp() {
        PrivilegedReadInputStream.closeAll()
        service.destroy()
        if (accepted) check(root.deleteRecursively())
        else android.util.Log.e("AFRootAudit",root.path)
    }

    @Test fun parcelledReadOnlyDescriptorsReadWholeEmptyTinyAndBoundaryFiles() {
        for (size in listOf(0,1,25,65_535,65_536,65_537,300_017)) {
            val target = File(root,"content-$size.bin")
            val bytes = ByteArray(size) { (it % 251).toByte() }
            target.writeBytes(bytes)
            val before = Os.stat(target.path)
            PrivilegedReadInputStream(service,target.path).use { input -> assertArrayEquals(bytes,input.readBytes()) }
            val after = Os.stat(target.path)
            assertEquals(before.st_ino,after.st_ino)
            assertEquals(before.st_uid,after.st_uid)
            assertEquals(before.st_gid,after.st_gid)
            assertEquals(before.st_mode,after.st_mode)
            assertArrayEquals(bytes,target.readBytes())
        }
        accepted=true
    }

    @Test fun transferredDescriptorDoesNotPermitWritingTheOriginal() {
        val target = File(root,"readonly.txt").apply { writeText("unchanged") }
        service.openReadOnlyFile(target.path).use { descriptor ->
            assertThrows(ErrnoException::class.java) { Os.write(descriptor.fileDescriptor,byteArrayOf(120),0,1) }
            assertEquals('u'.code,ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { it.read() })
        }
        assertEquals("unchanged",target.readText())
        accepted=true
    }

    @Test fun unsupportedCachedServicesCannotOpenOrLeakAReadReservation() {
        val target = File(root,"document.txt").apply { writeText("unchanged") }
        var opened = 0
        val unsupported = object : IPrivilegedFileService by service {
            override fun getFileReadVersion() = 0
            override fun openReadOnlyFile(path: String): ParcelFileDescriptor {
                opened++
                error("The incompatible service must not open any payload")
            }
        }
        repeat(16) { assertThrows(IOException::class.java) { PrivilegedReadInputStream(unsupported,target.path) } }
        assertEquals(0,opened)
        val oldBinder = IPrivilegedFileService.Stub.asInterface(object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
        })
        repeat(16) { assertThrows(IOException::class.java) { PrivilegedReadInputStream(oldBinder,target.path) } }
        PrivilegedReadInputStream(service,target.path).use { assertEquals("unchanged",it.bufferedReader().readText()) }
        accepted=true
    }

    @Test fun directoryFifoMissingAndLinkedPathsAreRejectedBeforePayloadIo() {
        val target = File(root,"safe.txt").apply { writeText("safe") }
        val link = File(root,"link.txt").also { Os.symlink(target.path,it.path) }
        val parentLink = File(root,"linked-parent").also { Os.symlink(root.path,it.path) }
        val fifo = File(root,"fifo").also { Os.mkfifo(it.path,0x180) }
        for (path in listOf(root.path,link.path,File(parentLink,target.name).path,fifo.path,File(root,"missing").path,
            root.path+"/../"+root.name+"/safe.txt")) {
            assertThrows(IllegalStateException::class.java) { service.openReadOnlyFile(path) }
        }
        assertEquals("safe",target.readText())
        assertTrue(link.delete()); assertTrue(parentLink.delete()); assertTrue(fifo.delete())
        accepted=true
    }

    @Test fun openedDescriptorStaysPinnedWhenTheOriginalNameIsReplaced() {
        val target = File(root,"document.txt").apply { writeText("original opened file") }
        service.openReadOnlyFile(target.path).use { descriptor ->
            val original = File(root,"moved-original.txt")
            assertTrue(target.renameTo(original))
            target.writeText("different current file")
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                assertEquals("original opened file",it.bufferedReader().readText())
            }
            assertEquals("different current file",target.readText())
        }
        accepted=true
    }

    @Test fun ownedCloseAndDisconnectActuallyInvalidateClientDescriptors() {
        val target = File(root,"document.txt").apply { writeText("contents") }
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        val inputs = List(8) { PrivilegedReadInputStream({
            val descriptor = service.openReadOnlyFile(target.path).also { descriptors.add(it) }
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }) }
        assertThrows(IOException::class.java) { PrivilegedReadInputStream(service,target.path) }
        PrivilegedReadInputStream.closeAll()
        inputs.forEach { assertThrows(IOException::class.java) { it.read() }; it.close() }
        descriptors.forEach { assertFalse(it.fileDescriptor.valid()) }
        assertEquals("contents",target.readText())
        accepted=true
    }

    @Test fun cancelledPendingOpenClosesTheLateParcelledDescriptor() {
        val target = File(root,"document.txt").apply { writeText("contents") }
        val entered = CountDownLatch(1)
        val reply = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var descriptor: ParcelFileDescriptor? = null
        try {
            val result = executor.submit<Boolean> {
                try {
                    PrivilegedReadInputStream({
                        val opened = service.openReadOnlyFile(target.path)
                        descriptor=opened
                        entered.countDown()
                        check(reply.await(5,TimeUnit.SECONDS))
                        ParcelFileDescriptor.AutoCloseInputStream(opened)
                    }).close()
                    false
                } catch (_: IOException) { true }
            }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            PrivilegedReadInputStream.closeAll()
            reply.countDown()
            assertTrue(result.get(5,TimeUnit.SECONDS))
            assertFalse(checkNotNull(descriptor).fileDescriptor.valid())
        } finally { reply.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)) }
        accepted=true
    }

    @Test fun largeReadUsesBoundedChunksAndMatchesTheOriginalHash() {
        val target = File(root,"large.bin")
        val buffer = ByteArray(65_536) { (it % 251).toByte() }
        target.outputStream().use { output -> repeat(64) { output.write(buffer) }; output.fd.sync() }
        fun hash(input: java.io.InputStream): Pair<Long,ByteArray> = input.use { stream ->
            var total=0L
            val digest=MessageDigest.getInstance("SHA-256")
            while(true) {
                val count=stream.read(buffer)
                if(count<0)break
                assertTrue(count in 1..buffer.size)
                total+=count; digest.update(buffer,0,count)
            }
            total to digest.digest()
        }
        val expected=hash(target.inputStream())
        val actual=hash(PrivilegedReadInputStream(service,target.path))
        assertEquals(4_194_304L,actual.first)
        assertEquals(expected.first,actual.first)
        assertArrayEquals(expected.second,actual.second)
        accepted=true
    }
}
