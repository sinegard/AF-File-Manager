package com.affilemanager.app;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/** Real UI against the unmodified, minified shipping package. No application test hooks. */
public final class BrowserIssuesRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        Context context = test.getTargetContext();
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AFBrowse-" + UUID.randomUUID().toString().substring(0,8));
        if (!root.mkdirs()) throw new AssertionError("Owned fixture directory unavailable");
        File first = new File(root, "a space.txt"), second = new File(root, "b'quote.txt");
        write(first, "unchanged first"); write(second, "unchanged second");
        File nativeOutput = new File(root, "terminal-result.txt");
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(service);
        try {
            openDownloads(test);
            MediaEditingRuntimeVerifier.scrollClickLabel(test, root.getName());
            MediaEditingRuntimeVerifier.clickDescription(test, "File actions: " + first.getName());
            MediaEditingRuntimeVerifier.clickLabel(test, "Copy path (POSIX)");
            clipboardEquals(test, quoted(first));
            AccessibilityNodeInfo firstNode = await(test, n -> text(n, first.getName()), 10000);
            longClick(firstNode);
            await(test, n -> text(n, "Selected: 1"), 5000);
            MediaEditingRuntimeVerifier.clickLabel(test, second.getName());
            await(test, n -> text(n, "Selected: 2"), 5000);
            tapStaticLabel(test, "Selected: 2");
            if (find(test, n -> n.getContentDescription() != null && n.getContentDescription().toString().startsWith("File actions:")) != null)
                throw new AssertionError("Selection still exposes a competing per-item menu");
            MediaEditingRuntimeVerifier.clickDescription(test, "Copy path (POSIX)");
            clipboardEquals(test, quoted(first) + " " + quoted(second));
            if (!"unchanged first".equals(read(first)) || !"unchanged second".equals(read(second))) throw new AssertionError("Copy path mutated a source");
            MediaEditingRuntimeVerifier.clickDescription(test, "Close");
            MediaEditingRuntimeVerifier.clickDescription(test, "Folder actions");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Open terminal in this folder");
            await(test, n -> "Paste".contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()) && n.isEnabled(), 15000);
            if (!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
                throw new AssertionError("Background action rejected");
            test.getUiAutomation().waitForIdle(500,5000);
            launch(test, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
            await(test, n -> "Paste".contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()) && n.isEnabled(), 15000);
            String command = "printf AF_BROWSER_TERMINAL > " + quoted(nativeOutput);
            test.runOnMainSync(() -> context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("AF terminal fixture", command)));
            MediaEditingRuntimeVerifier.clickDescription(test, "Paste");
            if (nativeOutput.exists()) throw new AssertionError("Single-line paste executed without Enter");
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
            // Shell redirection creates an empty file before printf writes it.
            // Accept only the completed payload, not that earlier creation event.
            waitFor(() -> "AF_BROWSER_TERMINAL".equals(readUnchecked(nativeOutput)), 10000);
            MediaEditingRuntimeVerifier.clickDescription(test, "Close terminal");
            MediaEditingRuntimeVerifier.clickLabel(test, "Close");
            for (String name : new String[]{"lyrics.lrc", "subtitles.srt", "captions.vtt", ".bashrc"}) {
                File source = new File(root, name); write(source, "hello\nsecond line\n");
                openFile(test, source, "application/octet-stream");
                AccessibilityNodeInfo editor = await(test, n -> n.isEditable() && n.getText() != null && n.getText().toString().contains("hello"), 15000);
                Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "updated\nsecond line\n");
                if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value)) throw new AssertionError("AF editor rejected text");
                if (!"hello\nsecond line\n".equals(read(source))) throw new AssertionError("Editor changed original before Save");
                await(test, n -> text(n, "Save") && n.isEnabled(), 5000);
                MediaEditingRuntimeVerifier.clickLabel(test, "Save");
                waitFor(() -> "updated\nsecond line\n".equals(readUnchecked(source)), 10000);
                MediaEditingRuntimeVerifier.clickDescription(test, "Close");
            }
            File unsupported = new File(root, "unknown.af-fixture"); write(unsupported, "fixture");
            openFile(test, unsupported, "application/octet-stream");
            await(test, n -> text(n, "Open with another app"), 10000);
            MediaEditingRuntimeVerifier.clickDescription(test, "Close");
            File image = new File(root, "zoom.png");
            Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.BLUE);
            try (OutputStream out = new FileOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); } finally { bitmap.recycle(); }
            openFile(test, image, "image/png");
            doubleTapImage(test, image.getName());
            await(test, n -> text(n, "200 %"), 5000);
            doubleTapImage(test, image.getName());
            await(test, n -> text(n, "100 %"), 5000);
            MediaEditingRuntimeVerifier.clickDescription(test, "Zoom in");
            await(test, n -> text(n, "125 %"), 5000);
            MediaEditingRuntimeVerifier.clickLabel(test, "Reset");
            await(test, n -> text(n, "100 %"), 5000);
            MediaEditingRuntimeVerifier.clickDescription(test, "Close");
            // Leave an existing active folder before removing this owned fixture.
            // The next suite must not inherit a deleted Save As destination.
            MediaEditingRuntimeVerifier.clickDescription(test, "Up");
            System.out.println("AF_BROWSER_COPY_MENU_EDITOR_TERMINAL_IMAGE_OK");
            return true;
        } catch (Exception | AssertionError error) {
            try {
                Bitmap screen = test.getUiAutomation().takeScreenshot();
                if (screen != null) {
                    File capture = new File(context.getExternalFilesDir("validation"), "browser-runtime-failure-" + SystemClock.uptimeMillis() + ".png");
                    try (OutputStream output = new FileOutputStream(capture)) { screen.compress(Bitmap.CompressFormat.PNG,100,output); }
                    finally { screen.recycle(); }
                    System.out.println("AF_BROWSER_FAILURE_SCREEN " + capture.getAbsolutePath());
                }
            } catch (Throwable captureError) { error.addSuppressed(captureError); }
            throw error;
        } finally {
            File[] files = root.listFiles();
            if (files != null) for (File file : files) if (file.isFile() && !file.delete()) throw new IOException("Fixture cleanup failed");
            if (root.isDirectory() && !root.delete()) throw new IOException("Fixture directory cleanup failed");
        }
    }

    private static void launch(Instrumentation test, Intent intent) {
        Context context = test.getTargetContext();
        intent.setClassName(context, "com.affilemanager.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        test.runOnMainSync(() -> context.startActivity(intent));
    }
    public static void openDownloads(Instrumentation test) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        launch(test, new Intent(Intent.ACTION_MAIN));
        waitFor(() -> test.getUiAutomation().getWindows().stream().anyMatch(window -> {
            AccessibilityNodeInfo root = window.getRoot();
            return window.isFocused() && root != null &&
                    test.getTargetContext().getPackageName().contentEquals(root.getPackageName());
        }), 15000);
        test.getUiAutomation().waitForIdle(250,5000);
        // Dismiss only a visible system IME, never an AF dialog or navigation state.
        boolean imeVisible = test.getUiAutomation().getWindows().stream().anyMatch(BrowserIssuesRuntimeVerifier::visibleIme);
        if (imeVisible) {
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            // API26 can retain an inactive TYPE_INPUT_METHOD window after hiding
            // it. Require the actual AF control below, not removal of that record.
            test.getUiAutomation().waitForIdle(250,5000);
        }
        // The current Material tab may not advertise ACTION_CLICK. Use real input.
        tapStaticLabel(test, "Files");
        MediaEditingRuntimeVerifier.clickLabel(test, "Internal storage");
        MediaEditingRuntimeVerifier.scrollClickLabel(test, "Download");
    }
    private static void doubleTapImage(Instrumentation test, String name) throws Exception {
        AccessibilityNodeInfo node = await(test, n -> name.contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()), 10000);
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        AccessibilityNodeInfo viewport = find(test, n -> "image-zoom-viewport".equals(n.getViewIdResourceName()));
        if (viewport != null) {
            Rect visible = new Rect(); viewport.getBoundsInScreen(visible);
            if (!bounds.intersect(visible)) throw new AssertionError("Image is outside its gesture viewport");
        }
        // Android 8 may keep the editor IME visible while an external VIEW intent
        // replaces the dialog. Accessibility image bounds extend behind that IME;
        // injecting there types into the keyboard instead of touching the image.
        for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
            Rect visible = new Rect(); window.getBoundsInScreen(visible);
            if (window.getId() == node.getWindowId() && !bounds.intersect(visible))
                throw new AssertionError("Image is outside its application window");
            if (visibleIme(window) &&
                    Rect.intersects(bounds, visible)) bounds.bottom = Math.min(bounds.bottom, visible.top);
        }
        if (bounds.isEmpty()) throw new AssertionError("Image has no gesture bounds");
        System.out.println("AF_IMAGE_VISIBLE_GESTURE_BOUNDS " + bounds.toShortString());
        float x = bounds.left + bounds.width() * .4f, y = bounds.top + bounds.height() * .6f;
        for (int i = 0; i < 2; i++) {
            long now = SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {
                if (!test.getUiAutomation().injectInputEvent(down,true)) throw new AssertionError("Image touch rejected");
            } finally { down.recycle(); }
            SystemClock.sleep(40);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,y,0);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {
                if (!test.getUiAutomation().injectInputEvent(up,true)) throw new AssertionError("Image touch release rejected");
            } finally { up.recycle(); }
            // Actual elapsed time, not a future UP timestamp. The interval must
            // exceed Android's double-tap minimum as well as fit its maximum.
            SystemClock.sleep(100);
        }
    }
    private static boolean visibleIme(android.view.accessibility.AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window.getRoot();
        Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
        return window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                root != null && root.isVisibleToUser() && !bounds.isEmpty();
    }
    /** A real outside touch dismisses Android's clipboard preview; semantic clicks do not. */
    public static void tapStaticLabel(Instrumentation test, String label) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        AccessibilityNodeInfo node = await(test, n -> text(n,label),10000);
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) throw new AssertionError("Static label has no touch bounds");
        long now = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now,now+60,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!test.getUiAutomation().injectInputEvent(down,true) || !test.getUiAutomation().injectInputEvent(up,true)) throw new AssertionError("Outside touch rejected");
        } finally { down.recycle(); up.recycle(); }
        test.getUiAutomation().waitForIdle(250,5000);
    }
    private static void openFile(Instrumentation test, File source, String mime) throws Exception {
        String path = source.getCanonicalPath(), shared = Environment.getExternalStorageDirectory().getCanonicalPath();
        if (!path.startsWith(shared + "/")) throw new AssertionError("Fixture escaped shared storage");
        Uri uri = new Uri.Builder().scheme("content").authority(test.getTargetContext().getPackageName()+".files")
            .appendPath("shared").appendEncodedPath(Uri.encode(path.substring(shared.length()+1), "/")).build();
        launch(test, new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
    }
    private static void clipboardEquals(Instrumentation test, String expected) {
        String[] actual = {null}; test.runOnMainSync(() -> {
            ClipData clip = test.getTargetContext().getSystemService(ClipboardManager.class).getPrimaryClip();
            actual[0] = clip == null ? null : clip.getItemAt(0).coerceToText(test.getTargetContext()).toString();
        });
        if (!expected.equals(actual[0])) throw new AssertionError("Quoted clipboard arguments differ");
    }
    private static String quoted(File file) { return "'" + file.getAbsolutePath().replace("'", "'\"'\"'") + "'"; }
    private static void write(File file, String value) throws IOException { try (OutputStream out=new FileOutputStream(file)) { out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }
    private static String read(File file) throws IOException { return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
    private static String readUnchecked(File file) { try { return read(file); } catch (IOException error) { return null; } }
    private interface Check { boolean get(); }
    private static void waitFor(Check check,long timeout) throws Exception { long end=SystemClock.uptimeMillis()+timeout; while(!check.get()) { if(SystemClock.uptimeMillis()>end)throw new AssertionError("AF workflow timed out"); SystemClock.sleep(50); } }
    private static boolean text(AccessibilityNodeInfo node,String text) { return node.getText()!=null && text.contentEquals(node.getText()); }
    private static void longClick(AccessibilityNodeInfo node) { for(int i=0;i<6&&node!=null;i++,node=node.getParent()) if(node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))return; throw new AssertionError("Long-click selection unavailable"); }
    private static AccessibilityNodeInfo await(Instrumentation test,Predicate<AccessibilityNodeInfo> condition,long timeout) throws Exception {
        long end=SystemClock.uptimeMillis()+timeout; do { AccessibilityNodeInfo found=find(test,condition); if(found!=null)return found; SystemClock.sleep(100); } while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("AF UI control unavailable");
    }
    private static AccessibilityNodeInfo find(Instrumentation test,Predicate<AccessibilityNodeInfo> condition) {
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();
        for(android.view.accessibility.AccessibilityWindowInfo window:test.getUiAutomation().getWindows()) {
            // A window can disappear between two getRoot() calls during a real
            // dialog/activity transition. Keep the one observed nullable result.
            AccessibilityNodeInfo root = window.getRoot();
            if(root != null) queue.add(root);
        }
        AccessibilityNodeInfo active=test.getUiAutomation().getRootInActiveWindow(); if(active!=null)queue.add(active);
        int visited=0; while(!queue.isEmpty()&&visited++<4096) { AccessibilityNodeInfo node=queue.removeFirst();
            if(node.isVisibleToUser()&&condition.test(node))return node;
            for(int i=0;i<Math.min(node.getChildCount(),128);i++) { AccessibilityNodeInfo child=node.getChild(i);if(child!=null)queue.add(child); }
        } return null;
    }
}
