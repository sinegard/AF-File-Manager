package com.affilemanager.app.media;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class ProtectedMediaEntryPointTest {
    @Test
    public void systemAudioUsesPrivateCopyAndInvalidAudioLeavesTheAppOpen() throws Exception {
        assertTrue(ProtectedMediaRuntimeVerifier.verify(InstrumentationRegistry.getInstrumentation()));
    }
}
