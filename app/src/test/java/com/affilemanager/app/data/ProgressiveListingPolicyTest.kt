package com.affilemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveListingPolicyTest {
    @Test
    fun firstItemAndEarlyBatchesPublishImmediately() {
        assertTrue(ProgressiveListingPolicy.shouldPublish(1))
        assertTrue(ProgressiveListingPolicy.shouldPublish(256))
        assertTrue(ProgressiveListingPolicy.shouldPublish(4_096))
    }

    @Test
    fun hundredThousandEntriesHaveBoundedUpdateCount() {
        val updates = (1..ProgressiveListingPolicy.MAX_VISIBLE_ENTRIES)
            .count(ProgressiveListingPolicy::shouldPublish)

        assertTrue(updates in 30..48)
        assertEquals(100_000, ProgressiveListingPolicy.MAX_VISIBLE_ENTRIES)
        assertEquals(200_000, ProgressiveListingPolicy.MAX_SCANNED_ENTRIES)
    }
}
