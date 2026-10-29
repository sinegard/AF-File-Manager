package com.affilemanager.app.ui.preview

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class ForegroundPlaybackStateTest {
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    @Test fun restoresPlaybackIntentAndControlsForTheSameSourceOnly() {
        val state = ForegroundPlaybackState("first", 12500L, true, false, 1.5f, .4f)
        val saver = ForegroundPlaybackState.saver("first")
        val saved = with(saver) { scope.save(state) }!!
        val restored = saver.restore(saved)!!
        assertEquals(12500L, restored.position.longValue)
        assertTrue(restored.requested.value)
        assertFalse(restored.loop.value)
        assertEquals(1.5f, restored.speed.floatValue)
        assertEquals(.4f, restored.volume.floatValue)
        assertNull(ForegroundPlaybackState.saver("second").restore(saved))
    }

    @Test fun pausedStaysPausedAndInvalidValuesAreBounded() {
        val state = ForegroundPlaybackState("paused", -1, false, true, Float.NaN, 4f)
        assertFalse(state.requested.value)
        assertEquals(0L, state.position.longValue)
        assertEquals(1f, state.speed.floatValue)
        assertEquals(1f, state.volume.floatValue)
    }
}
