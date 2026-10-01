package com.affilemanager.app.media;

import android.accessibilityservice.AccessibilityService;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** Exercises the unmodified downloadable APK through its public open-file entry point. */
public final class ProtectedMediaRuntimeVerifier {
    public static boolean verify(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        File original = null;
        for (String path : new String[]{"/product/media/audio/alarms", "/system/media/audio/alarms"}) {
            File[] children = new File(path).listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isFile() && child.canRead() && child.getName().endsWith(".ogg")) {
                    original = child;
                    break;
                }
            }
            if (original != null) break;
        }
        if (original == null) throw new AssertionError("Readable system OGG fixture is required");
        boolean outsideProvider = false;
        try { FileProvider.getUriForFile(context, context.getPackageName() + ".files", original); }
        catch (IllegalArgumentException expected) { outsideProvider = true; }
        if (!outsideProvider) throw new AssertionError("Fixture must reproduce the unexposed system path");
        long modified = original.lastModified();
        byte[] originalDigest = digest(original);
        File previewRoot = new File(context.getCacheDir(), "privileged-previews");
        File invalid = new File(context.getCacheDir(), "af-invalid-audio-" + UUID.randomUUID() + ".ogg");
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                open(instrumentation, original);
                await(() -> clickDescription(instrumentation.getUiAutomation().getRootInActiveWindow(), "Start"),
                        "System audio preparation or playback did not become available");
                await(() -> hasText(instrumentation.getUiAutomation().getRootInActiveWindow(), "Pause"),
                        "System audio did not start playing");
                File[] directories = previewRoot.listFiles();
                if (directories == null || directories.length != 1) throw new AssertionError("Private preview copy missing");
                File[] copies = directories[0].listFiles();
                if (copies == null || copies.length != 1 ||
                        !copies[0].getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath() + File.separator) ||
                        !Arrays.equals(originalDigest, digest(copies[0]))) {
                    throw new AssertionError("Private preview does not match its original");
                }
                close(instrumentation);
                await(() -> previewRoot.listFiles() == null || previewRoot.listFiles().length == 0,
                        "Closed preview left its temporary system copy behind");
            }
            try (FileOutputStream output = new FileOutputStream(invalid)) { output.write(new byte[]{1, 2, 3, 4}); }
            open(instrumentation, invalid);
            await(() -> hasText(instrumentation.getUiAutomation().getRootInActiveWindow(), "Could not create the file preview"),
                    "Invalid audio must show a recoverable preview error");
            close(instrumentation);
            await(() -> belongsToApp(instrumentation.getUiAutomation().getRootInActiveWindow(), context.getPackageName()),
                    "The app did not remain available after the media error");
            if (!Arrays.equals(originalDigest, digest(original)) || original.lastModified() != modified || !invalid.isFile()) {
                throw new AssertionError("Playback changed or removed an original");
            }
            return true;
        } finally {
            if (invalid.exists() && !invalid.delete()) throw new AssertionError("Owned audio fixture cleanup failed");
        }
    }

    private static void open(Instrumentation instrumentation, File file) {
        instrumentation.runOnMainSync(() -> instrumentation.getTargetContext().startActivity(new Intent(Intent.ACTION_VIEW)
                .setClassName(instrumentation.getTargetContext(), "com.affilemanager.app.MainActivity")
                .setDataAndType(Uri.fromFile(file), "audio/ogg")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private static void close(Instrumentation instrumentation) {
        if (!instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            throw new AssertionError("Preview Back action failed");
        }
    }

    private static boolean clickDescription(AccessibilityNodeInfo node, String description) {
        if (node == null) return false;
        if (description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()) && node.isEnabled()) {
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null && target.isVisibleToUser() && target.isEnabled()) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickDescription(node.getChild(i), description)) return true;
        }
        if (node.isScrollable()) node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        return false;
    }

    private static boolean hasText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (text.contentEquals(node.getText() == null ? "" : node.getText()) ||
                text.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasText(node.getChild(i), text)) return true;
        return false;
    }

    private static boolean belongsToApp(AccessibilityNodeInfo node, String packageName) {
        return node != null && packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName());
    }

    private static byte[] digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hash.digest();
    }

    private interface Check { boolean satisfied(); }
    private static void await(Check check, String failure) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000L;
        while (!check.satisfied()) {
            if (SystemClock.uptimeMillis() >= deadline) throw new AssertionError(failure);
            Thread.sleep(100L);
        }
    }
}
