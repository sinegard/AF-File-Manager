package com.affilemanager.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Actual local paste UI on the normal R8 APK, without optimizer exceptions or app hooks. */
public final class TransferOptionsRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        Context context = test.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE);
        String original = prefs.getString("transfer_options", null);
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File root = new File(downloads, "AFTransfer-" + UUID.randomUUID().toString().substring(0, 8));
        if (!root.mkdirs()) throw new AssertionError("Owned transfer fixture unavailable");
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(service);
        try {
            if (!prefs.edit().remove("transfer_options").commit()) throw new AssertionError("Fixture settings reset failed");
            for (boolean moving : new boolean[]{false, true}) {
                String prefix = moving ? "move" : "copy";
                File first = new File(root, prefix + "-first.txt"), second = new File(root, prefix + "-second.txt");
                File targetOne = new File(root, prefix + "-target-one"), targetTwo = new File(root, prefix + "-target-two");
                write(first, "first " + prefix); write(second, "second " + prefix);
                if (!targetOne.mkdir() || !targetTwo.mkdir()) throw new AssertionError("Fixture destination unavailable");
                navigateToFixture(test, root);
                preparePaste(test, first, targetOne, moving);
                MediaEditingRuntimeVerifier.awaitLabel(test, moving ? "Reliable move" : "Reliable copy");
                setAsk(test, false);
                MediaEditingRuntimeVerifier.scrollClickLabel(test, "Save plan and start");
                waitFor(() -> contentEquals(new File(targetOne, first.getName()), "first " + prefix) && first.exists() != moving, 15000);
                JSONObject saved = new JSONObject(prefs.getString("transfer_options", "{}"));
                JSONObject chosen = saved.getJSONObject(prefix);
                if (chosen.getBoolean("askBeforeTransfer") || !"KEEP_BOTH".equals(chosen.getString("conflictPolicy")) ||
                    !"STOP".equals(chosen.getString("failurePolicy")) ||
                    !(moving ? "SHA256" : "SIZE").equals(chosen.getString("verification"))) {
                    throw new AssertionError("Optimized saved choices differ from the user's UI choice: " + chosen);
                }
                // A fresh Activity/VM must load the saved choices rather than an old dialog draft.
                Intent reopen = new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                test.runOnMainSync(() -> context.startActivity(reopen));
                test.getUiAutomation().waitForIdle(300, 5000);
                navigateToFixture(test, root);
                preparePaste(test, second, targetTwo, moving);
                waitFor(() -> contentEquals(new File(targetTwo, second.getName()), "second " + prefix) && second.exists() != moving, 15000);
                if (new File(targetTwo, first.getName()).exists()) throw new AssertionError("A previous file plan was reused");
                if (find(test, n -> text(n, moving ? "Reliable move" : "Reliable copy")) != null) {
                    throw new AssertionError("Options still reopened after an explicit opt-out");
                }
                if (!moving && (!contentEquals(first, "first copy") || !contentEquals(second, "second copy"))) {
                    throw new AssertionError("Copy removed or changed its source");
                }
            }
            return true;
        } finally {
            boolean restored = original == null ? prefs.edit().remove("transfer_options").commit() :
                prefs.edit().putString("transfer_options", original).commit();
            if (!restored) throw new AssertionError("Original transfer preferences were not restored");
            if (!root.getCanonicalFile().getParentFile().equals(downloads.getCanonicalFile()) || !root.getName().startsWith("AFTransfer-")) {
                throw new AssertionError("Unsafe fixture cleanup target");
            }
            deleteFixture(root);
        }
    }

    private static void preparePaste(Instrumentation test, File source, File target, boolean moving) throws Exception {
        String action = "File actions: " + source.getName();
        for (int attempt = 0; attempt < 16; attempt++) {
            AccessibilityNodeInfo menu = find(test, n -> action.contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()));
            AccessibilityNodeInfo create = find(test, n -> "create_local_item".equals(n.getViewIdResourceName()));
            boolean clear = menu != null;
            if (menu != null && create != null) {
                Rect targetBounds = new Rect(), floatingBounds = new Rect();
                // The icon can have a tiny clipped native rectangle while its
                // real 48-dp clickable parent still overlaps the floating button.
                AccessibilityNodeInfo touchTarget = menu;
                for (int up = 0; touchTarget != null && !touchTarget.isClickable() && up < 10; up++) touchTarget = touchTarget.getParent();
                if (touchTarget == null) throw new AssertionError("File menu lost its actual touch target");
                touchTarget.getBoundsInScreen(targetBounds); create.getBoundsInScreen(floatingBounds);
                clear = !Rect.intersects(targetBounds, floatingBounds);
                System.out.println("AF_TRANSFER_MENU_LAYOUT " + source.getName() + " menu=" + targetBounds +
                        " create=" + floatingBounds + " unobscured=" + clear);
            }
            if (clear) break;
            boolean scrolled = false;
            if (menu != null) {
                for (AccessibilityNodeInfo owner = menu.getParent(); owner != null; owner = owner.getParent()) {
                    if (owner.isScrollable()) { scrolled = owner.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); break; }
                }
            }
            if (!scrolled && !scrollForward(test)) break;
            test.getUiAutomation().waitForIdle(250, 5000);
        }
        android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
        if (image != null) {
            File evidence = new File(test.getTargetContext().getExternalFilesDir("validation"),
                    "optimized-transfer-menu-" + source.getName() + ".png");
            try (OutputStream output = new FileOutputStream(evidence)) {
                if (!image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("Native menu evidence unavailable");
            } finally { image.recycle(); }
        }
        MediaEditingRuntimeVerifier.clickDescription(test, "File actions: " + source.getName());
        MediaEditingRuntimeVerifier.clickLabel(test, moving ? "Move" : "Copy");
        openAddress(test, target);
        MediaEditingRuntimeVerifier.clickDescription(test, "Folder actions");
        MediaEditingRuntimeVerifier.clickLabel(test, "Paste");
    }

    public static void navigateToFixture(Instrumentation test, File root) throws Exception {
        Context context = test.getTargetContext();
        Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        test.runOnMainSync(() -> context.startActivity(launch));
        BrowserIssuesRuntimeVerifier.tapStaticLabel(test, "Files");
        MediaEditingRuntimeVerifier.clickLabel(test, "Internal storage");
        openAddress(test, root);
    }

    private static void openAddress(Instrumentation test, File root) throws Exception {
        // Folder history intentionally restores its old viewport. Use the real
        // address control rather than assuming Download is below that viewport.
        waitFor(() -> find(test, n -> "directory_address_local_LEFT".equals(n.getViewIdResourceName())) != null, 10000);
        AccessibilityNodeInfo address = find(test, n -> "directory_address_local_LEFT".equals(n.getViewIdResourceName()));
        if (!address.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Address control rejected click");
        waitFor(() -> find(test, AccessibilityNodeInfo::isEditable) != null, 10000);
        AccessibilityNodeInfo field = find(test, AccessibilityNodeInfo::isEditable);
        android.os.Bundle value = new android.os.Bundle();
        value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, root.getAbsolutePath());
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value)) throw new AssertionError("Address input rejected fixture path");
        MediaEditingRuntimeVerifier.clickLabel(test, "Open");
        // A child with this name can still be visible in the previous folder.
        // Wait for the actual non-editable address, not that ambiguous row label.
        waitFor(() -> find(test, n -> !n.isEditable() && text(n, root.getAbsolutePath())) != null, 10000);
        test.getUiAutomation().waitForIdle(250, 5000);
    }

    private static void setAsk(Instrumentation test, boolean checked) throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            AccessibilityNodeInfo label = find(test, n -> text(n, "Ask"));
            if (label != null) {
                Rect labelBounds = new Rect(); label.getBoundsInScreen(labelBounds);
                AccessibilityNodeInfo parent = label.getParent();
                for (int up = 0; parent != null && up < 3; up++, parent = parent.getParent()) {
                    // Unmerged Rows are absent from Android's accessibility tree.
                    // A parent can therefore contain radio buttons and all switches;
                    // target the actual Switch beside this label, not the first one.
                    AccessibilityNodeInfo toggle = findDescendant(parent, n -> {
                        if (!n.isCheckable()) return false;
                        Rect bounds = new Rect(); n.getBoundsInScreen(bounds);
                        return Math.abs(bounds.centerY() - labelBounds.centerY()) <= Math.max(8, labelBounds.height() / 2);
                    });
                    if (toggle != null && toggle.isVisibleToUser()) {
                        if (toggle.isChecked() != checked && !toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            throw new AssertionError("Ask switch rejected the click");
                        }
                        test.getUiAutomation().waitForIdle(250, 5000);
                        return;
                    }
                }
            }
            if (!scrollForward(test)) break;
            test.getUiAutomation().waitForIdle(250, 5000);
        }
        throw new AssertionError("Ask switch was not reachable");
    }

    private static boolean scrollForward(Instrumentation test) {
        Set<AccessibilityNodeInfo> tried = new HashSet<>();
        while (tried.size() < 8) {
            AccessibilityNodeInfo scroll = find(test, n -> n.isScrollable() && !tried.contains(n));
            if (scroll == null) break;
            tried.add(scroll);
            if (scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo find(Instrumentation test, Predicate<AccessibilityNodeInfo> match) {
        for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo found = findDescendant(root, n -> n.isVisibleToUser() && match.test(n));
            if (found != null) return found;
        }
        AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
        return root == null ? null : findDescendant(root, n -> n.isVisibleToUser() && match.test(n));
    }
    private static AccessibilityNodeInfo findDescendant(AccessibilityNodeInfo root, Predicate<AccessibilityNodeInfo> match) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 4096) {
            AccessibilityNodeInfo node = queue.removeFirst(); node.refresh();
            if (match.test(node)) return node;
            for (int i = 0; i < Math.min(node.getChildCount(), 128); i++) {
                AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child);
            }
        }
        return null;
    }
    private static boolean text(AccessibilityNodeInfo node, String value) {
        return node.getText() != null && value.contentEquals(node.getText());
    }
    private static void write(File file, String content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) { out.write(content.getBytes(StandardCharsets.UTF_8)); }
    }
    private static boolean contentEquals(File file, String content) {
        try { return content.equals(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (IOException ignored) { return false; }
    }
    private interface Check { boolean get(); }
    private static void waitFor(Check check, long timeout) throws Exception {
        long end = SystemClock.uptimeMillis() + timeout;
        while (!check.get()) {
            if (SystemClock.uptimeMillis() >= end) throw new AssertionError("Optimized transfer did not finish");
            SystemClock.sleep(50);
        }
    }
    private static void deleteFixture(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixture(child);
        if (!file.delete() && file.exists()) throw new IOException("Owned transfer fixture cleanup failed");
    }
}
