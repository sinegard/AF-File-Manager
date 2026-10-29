package com.affilemanager.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Real UI/standard SDK only: this verifier cannot preserve shipping model classes from R8. */
public final class ArchiveRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        if (!android.os.Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned guest required");
        Context context = test.getTargetContext();
        android.accessibilityservice.AccessibilityServiceInfo accessibility = test.getUiAutomation().getServiceInfo();
        accessibility.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(accessibility);
        File downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        File root = new File(downloads, "AFArchiveRuntime-" + UUID.randomUUID());
        if (!root.getCanonicalFile().getParentFile().equals(downloads.getCanonicalFile()) || !root.mkdir()) throw new AssertionError("Archive fixture boundary");
        File list = new File(root, "list.zip"), grid = new File(root, "grid.zip");
        boolean accepted = false;
        try {
            writeZip(list); writeZip(grid);
            byte[] listHash = digest(list), gridHash = digest(grid);
            test.startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(context, "com.affilemanager.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (description(test.getUiAutomation().getRootInActiveWindow(), "Close")) MediaEditingRuntimeVerifier.clickDescription(test, "Close");
            // This non-text workflow starts on Files home. API 26 may leave the
            // prior independent instrumentation's IME visible after its app exits.
            // Establish that observed prerequisite without clearing app data or
            // weakening any archive, scroll-position or Back assertion below.
            boolean keyboard = false;
            for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
                if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) keyboard = true;
            }
            if (keyboard) {
                if (!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    throw new AssertionError("Observed pre-existing keyboard could not be dismissed");
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (keyboard) {
                    keyboard = false;
                    for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
                        if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) keyboard = true;
                    }
                    if (keyboard && android.os.SystemClock.elapsedRealtime() >= deadline)
                        throw new AssertionError("Pre-existing keyboard remained above the non-text archive workflow");
                    if (keyboard) android.os.SystemClock.sleep(100);
                }
            }
            // A newly started process already displays Files home. Its navigation
            // label is separate from the icon's touch target on older Compose.
            MediaEditingRuntimeVerifier.awaitLabel(test, "Internal storage");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Internal storage");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Download");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, root.getName());
            int round = 0;
            for (File source : new File[]{list, grid}) {
                MediaEditingRuntimeVerifier.clickLabel(test, source.getName());
                // A second independent archive must begin at its root, not the previous inner path.
                MediaEditingRuntimeVerifier.awaitLabel(test, "one");
                String toggle = round == 0 ? "Show list" : "Show grid";
                if (description(test.getUiAutomation().getRootInActiveWindow(), toggle)) MediaEditingRuntimeVerifier.clickDescription(test, toggle);
                MediaEditingRuntimeVerifier.clickLabel(test, "one");
                MediaEditingRuntimeVerifier.clickLabel(test, "two");
                MediaEditingRuntimeVerifier.clickLabel(test, "three");
                MediaEditingRuntimeVerifier.scrollClickLabel(test, "15-blue.png");
                // The archive and image both have Actions. Only the child has
                // Close here; the parent's top button navigates up the archive.
                // Wait for asynchronous extraction before sending Back.
                waitForDescription(test, "Close");
                MediaEditingRuntimeVerifier.awaitLabel(test, "15-blue.png");
                capture(test, "archive-shipping-" + round + "-child");
                if (round == 0) {
                    if (!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) throw new AssertionError("Archive child Back");
                } else MediaEditingRuntimeVerifier.clickDescription(test, "Close");
                MediaEditingRuntimeVerifier.awaitLabel(test, "three");
                // Do not scroll here: the same near-end position must already be restored.
                MediaEditingRuntimeVerifier.awaitLabel(test, "15-blue.png");
                capture(test, "archive-shipping-" + round + "-returned");
                for (String parent : new String[]{"two", "one", source.getName()}) {
                    MediaEditingRuntimeVerifier.clickDescription(test, "Return to the previous archive folder");
                    MediaEditingRuntimeVerifier.awaitLabel(test, parent);
                }
                MediaEditingRuntimeVerifier.clickDescription(test, "Close");
                round++;
            }
            // Leave navigation on the surviving parent before removing the owned
            // fixture. Later Save As flows intentionally use the current folder;
            // deleting it while still browsing it would poison their starting state.
            MediaEditingRuntimeVerifier.clickDescription(test, "Up");
            long parentDeadline = android.os.SystemClock.elapsedRealtime() + 15000;
            while (visibleText(test.getUiAutomation().getRootInActiveWindow(), "list.zip")
                    || visibleText(test.getUiAutomation().getRootInActiveWindow(), "grid.zip")) {
                if (android.os.SystemClock.elapsedRealtime() >= parentDeadline) throw new AssertionError("Owned archive fixture navigation cleanup");
                android.os.SystemClock.sleep(100);
            }
            MediaEditingRuntimeVerifier.awaitLabel(test, "Download");
            if (!Arrays.equals(listHash, digest(list)) || !Arrays.equals(gridHash, digest(grid))) throw new AssertionError("Archive source changed");
            accepted = true;
            return true;
        } catch (Throwable error) {
            try { capture(test, "archive-shipping-failure"); }
            catch (Throwable captureFailure) { error.addSuppressed(captureFailure); }
            throw error;
        } finally {
            if (accepted) {
                if (list.exists() && !list.delete()) throw new AssertionError("Owned list archive cleanup");
                if (grid.exists() && !grid.delete()) throw new AssertionError("Owned grid archive cleanup");
                if (!root.delete()) throw new AssertionError("Owned archive folder cleanup");
            } else android.util.Log.e("AFArchiveAudit", "Retained failed synthetic archive fixture: " + root.getPath());
        }
    }
    private static boolean description(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;
        if (node.isVisibleToUser() && value.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (description(node.getChild(i), value)) return true;
        return false;
    }
    private static boolean visibleText(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;
        if (node.isVisibleToUser() && value.contentEquals(node.getText() == null ? "" : node.getText())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (visibleText(node.getChild(i), value)) return true;
        return false;
    }
    private static void waitForDescription(Instrumentation test, String value) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (!description(test.getUiAutomation().getRootInActiveWindow(), value)) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("Missing archive child control: " + value);
            android.os.SystemClock.sleep(100);
        }
    }
    private static void writeZip(File file) throws IOException {
        Bitmap image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888); image.eraseColor(Color.BLUE);
        byte[] picture;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("Archive PNG fixture");
            picture = bytes.toByteArray();
        } finally { image.recycle(); }
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (String folder : new String[]{"one/", "one/two/", "one/two/three/"}) { out.putNextEntry(new ZipEntry(folder)); out.closeEntry(); }
            for (int index = 0; index < 15; index++) {
                out.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "one/two/three/%02d.txt", index)));
                out.write(("Unique archive fixture " + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("one/two/three/15-blue.png")); out.write(picture); out.closeEntry();
        }
    }
    private static byte[] digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] buffer = new byte[65536]; for (int read; (read = in.read(buffer)) >= 0;) hash.update(buffer, 0, read); }
        return hash.digest();
    }
    private static void capture(Instrumentation test, String name) throws IOException {
        File folder = test.getTargetContext().getExternalFilesDir("validation");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Archive evidence directory");
        Bitmap image = test.getUiAutomation().takeScreenshot();
        try (OutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Archive capture");
        } finally { image.recycle(); }
    }
}
