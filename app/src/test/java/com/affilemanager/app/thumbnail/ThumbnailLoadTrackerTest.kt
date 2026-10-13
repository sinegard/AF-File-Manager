package com.affilemanager.app.thumbnail

import org.junit.Assert.*
import org.junit.Test

class ThumbnailLoadTrackerTest {
    @Test fun unrelatedCopyDoesNotCancelActiveImageAndChangedImageCannotReturn() {
        val tracker = ThumbnailLoadTracker()
        val one = tracker.begin("one")
        val two = tracker.begin("two")
        tracker.invalidate { it == "copy-target" }
        assertTrue(one.isCurrent)
        assertTrue(two.isCurrent)
        tracker.invalidate { it == "one" }
        assertFalse(one.isCurrent)
        assertTrue(two.isCurrent)
    }

    @Test fun aLaterLoadOfTheNewRevisionIsIndependent() {
        val tracker = ThumbnailLoadTracker()
        val old = tracker.begin("image")
        tracker.invalidate { it == "image" }
        tracker.finish(old)
        val fresh = tracker.begin("image")
        assertFalse(old.isCurrent)
        assertTrue(fresh.isCurrent)
    }

    @Test(expected = IllegalStateException::class) fun activeLoadsHaveAHardTwoDecodeLimit() {
        val tracker = ThumbnailLoadTracker()
        tracker.begin("one")
        tracker.begin("two")
        tracker.begin("three")
    }
}
