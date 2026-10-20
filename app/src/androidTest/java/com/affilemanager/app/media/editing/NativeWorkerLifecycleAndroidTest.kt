package com.affilemanager.app.media.editing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class NativeWorkerLifecycleAndroidTest {
    @Test fun oneHundredRapidMetadataWorkersFinishWithoutFalseMemoryFailures()=runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"metadata-workers-${UUID.randomUUID()}").apply {check(mkdir())}
        val source=root.resolve("source.wav").also {MediaTestFixtures.wav(it,1000)}
        try {
            repeat(100){
                try {assertEquals(MediaTags(),MediaExportEngine.readTags(context,source))}
                catch(e:NativeMediaFailure){throw AssertionError("Worker $it: ${e.code}/${e.phase}/${e.nativeExit}",e)}
            }
            println("AF_NATIVE_METADATA_100_WORKERS_OK")
        }finally{root.deleteRecursively()}
    }
}
