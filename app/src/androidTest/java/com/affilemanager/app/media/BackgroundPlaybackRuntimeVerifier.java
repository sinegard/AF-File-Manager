package com.affilemanager.app.media;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Framework-only checks keep the target release APK identical to the phone artifact. */
public final class BackgroundPlaybackRuntimeVerifier {
    public static boolean verify(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        File file = new File(context.getCacheDir(), "af-optimized-background.wav");
        int bytes = 32000;
        ByteBuffer wave = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes("US-ASCII")).putInt(36 + bytes).put("WAVEfmt ".getBytes("US-ASCII"))
            .putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
            .putShort((short) 2).putShort((short) 16).put("data".getBytes("US-ASCII")).putInt(bytes);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wave.array()); }
        try {
            instrumentation.runOnMainSync(() -> context.startForegroundService(command(context, "PLAY_IN_BACKGROUND")
                .putExtra("uri", Uri.fromFile(file).toString()).putExtra("title", "AF playback test")
                .putExtra("position", 0L).putExtra("loop", true).putExtra("speed", 1f).putExtra("volume", 0f)));
            await(() -> notification(context) != null && notification(context).actions != null && notification(context).actions.length == 2);
            Notification initial = notification(context);
            MediaSession.Token token = initial.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
            if (token == null) throw new AssertionError("Media session missing from optimized notification");
            MediaController controller = new MediaController(context, token);
            await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING);
            controller.getTransportControls().pause();
            await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED);
            controller.getTransportControls().play();
            await(() -> controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING);
            Notification current = notification(context);
            if (current.actions[1].getIcon() == null) throw new AssertionError("Stop icon missing");
            File capture = new File(context.getExternalFilesDir("validation"), "optimized-background-controls.png");
            android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
            try (FileOutputStream output = new FileOutputStream(capture)) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            } finally { image.recycle(); }
            current.actions[1].actionIntent.send();
            await(() -> notification(context) == null);
            return true;
        } finally {
            instrumentation.runOnMainSync(() -> context.startService(command(context, "STOP_BACKGROUND_PLAYBACK")));
            await(() -> notification(context) == null);
            if (!file.delete() && file.exists()) throw new AssertionError("Test fixture cleanup failed");
        }
    }

    private static Intent command(Context context, String action) {
        return new Intent().setClassName(context, "com.affilemanager.app.media.BackgroundPlaybackService")
            .setAction("com.affilemanager.app.action." + action);
    }

    private static Notification notification(Context context) {
        for (StatusBarNotification active : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (active.getId() == 44) return active.getNotification();
        }
        return null;
    }

    private interface Check { boolean satisfied(); }
    private static void await(Check check) throws Exception {
        long end = SystemClock.uptimeMillis() + 8000L;
        while (!check.satisfied()) {
            if (SystemClock.uptimeMillis() >= end) throw new AssertionError("Optimized playback transition timed out");
            Thread.sleep(25L);
        }
    }
}
