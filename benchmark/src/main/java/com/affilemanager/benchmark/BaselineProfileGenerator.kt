package com.affilemanager.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.Direction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private lateinit var ftp: LoopbackFtpServer

    @Before
    fun prepare() {
        ftp = LoopbackFtpServer().also { it.start() }
    }

    @After
    fun close() = ftp.close()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = AfBenchmarkEnvironment.PACKAGE_NAME,
        maxIterations = 3,
        stableIterations = 2,
        includeInStartupProfile = true,
        filterPredicate = { rule -> rule.startsWith("Lcom/affilemanager/") },
    ) {
        AfBenchmarkEnvironment.prepareTarget(device)
        pressHome()
        startActivityAndWait(AfBenchmarkEnvironment.mainIntent())
    }

    @Test
    fun criticalUserJourneys() = baselineProfileRule.collect(
        packageName = AfBenchmarkEnvironment.PACKAGE_NAME,
        maxIterations = 3,
        stableIterations = 2,
        includeInStartupProfile = false,
        filterPredicate = { rule -> rule.startsWith("Lcom/affilemanager/") },
    ) {
        AfBenchmarkEnvironment.prepareTarget(device)
        pressHome()
        startActivityAndWait(AfBenchmarkEnvironment.localIntent("large"))
        AfBenchmarkEnvironment.run { awaitObject("file_list_ready_LEFT") }
        AfBenchmarkEnvironment.run { fling("file_list_LEFT", Direction.DOWN, times = 3) }

        startActivityAndWait(AfBenchmarkEnvironment.localIntent("thumbnails"))
        AfBenchmarkEnvironment.run { awaitObject("file_grid_ready_LEFT") }
        AfBenchmarkEnvironment.run { fling("file_grid_LEFT", Direction.DOWN, times = 3) }

        startActivityAndWait(AfBenchmarkEnvironment.remoteIntent())
        AfBenchmarkEnvironment.run { awaitObject("remote_list_ready") }
        AfBenchmarkEnvironment.run { fling("remote_list", Direction.DOWN, times = 3) }
    }
}
