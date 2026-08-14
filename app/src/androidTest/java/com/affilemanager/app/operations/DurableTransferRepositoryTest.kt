package com.affilemanager.app.operations

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.model.ConflictPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DurableTransferRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val storeDirectory get() = File(context.filesDir, "durable_operations_v1")
    private val fixtureDirectory get() = File(context.cacheDir, "durable-transfer-test")

    @Before
    fun prepare() {
        storeDirectory.deleteRecursively()
        fixtureDirectory.deleteRecursively()
        assertTrue(fixtureDirectory.mkdirs())
    }

    @After
    fun clean() {
        storeDirectory.deleteRecursively()
        fixtureDirectory.deleteRecursively()
    }

    @Test
    fun stateSurvivesRepositoryRecreation() {
        val plan = fixturePlan()
        val first = DurableTransferRepository(context)
        first.create(plan)
        first.saveState(
            DurableTransferState(
                planId = plan.id,
                status = DurableTransferStatus.INTERRUPTED,
                nextItemIndex = 1,
                attempt = 1,
                lastMessage = "Atkuriama",
            ),
        )

        val restored = DurableTransferRepository(context).load(plan.id)

        assertEquals(plan, restored.plan)
        assertEquals(DurableTransferStatus.INTERRUPTED, restored.state.status)
        assertEquals(1, restored.state.nextItemIndex)
        assertEquals("Atkuriama", restored.state.lastMessage)
    }

    @Test
    fun unsupportedStateIsQuarantinedInsteadOfExecuted() {
        val plan = fixturePlan()
        val repository = DurableTransferRepository(context)
        repository.create(plan)
        val stateFile = File(storeDirectory, "${plan.id}.state.json")
        stateFile.writeText(stateFile.readText().replace("\"schemaVersion\":1", "\"schemaVersion\":999"))

        val records = repository.list()

        assertTrue(records.isEmpty())
        assertFalse(stateFile.exists())
        assertTrue(File(storeDirectory, "corrupt").listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
    }

    private fun fixturePlan(): DurableTransferPlan {
        val source = File(fixtureDirectory, "source.txt").apply { writeText("fixture") }
        val destination = File(fixtureDirectory, "destination").apply { mkdir() }
        return DurableTransferPlanner().create(
            sourcePaths = listOf(source.absolutePath),
            destinationDirectoryPath = destination.absolutePath,
            move = false,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
    }
}
