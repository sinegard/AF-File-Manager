package com.affilemanager.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainViewModelLifecycleTest {
    @Test
    fun clearingTheScreenFinishesItsApplicationScopedPersistenceWorkers() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val applicationJob = requireNotNull(application.graph.applicationScope.coroutineContext[Job])
        val store = ViewModelStore()
        var ownedJobs = emptySet<Job>()
        try {
            instrumentation.runOnMainSync {
                val previousJobs = applicationJob.children.toSet()
                val model = ViewModelProvider(
                    store,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(application),
                )[MainViewModel::class.java]
                model.updateShareScreenPreferences { it.copy(receiverName = "Lifecycle test") }
                ownedJobs = applicationJob.children.toSet() - previousJobs
                assertTrue("Persistence workers were not observed", ownedJobs.size >= 3)
                store.clear()
            }
            val finished = withTimeoutOrNull(5_000L) {
                while (ownedJobs.any { !it.isCompleted }) delay(20L)
                true
            } ?: false
            assertTrue("A cleared screen still has an application-scoped worker retaining it", finished)
            assertEquals(
                "Lifecycle test",
                application.graph.uiPreferences.loadShare("/storage/emulated/0", "Android phone").receiverName,
            )
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            ownedJobs.forEach { it.cancel() }
        }
    }
}
