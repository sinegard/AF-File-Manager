package com.affilemanager.app;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.os.*;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.media.editing.PlatformMediaTestFixtures;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real foreground players and Activity recreation in the normal R8/resource-shrunk APK. */
public final class MediaPlayerRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        Context context = test.getTargetContext();
        Application app = (Application) context.getApplicationContext();
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        test.getUiAutomation().setServiceInfo(service);
        AtomicReference<Activity> activity = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity value) { if (value.getClass().getName().equals("com.affilemanager.app.MainActivity")) activity.set(value); }
            public void onActivityCreated(Activity value, Bundle state) {}
            public void onActivityStarted(Activity value) {}
            public void onActivityPaused(Activity value) {}
            public void onActivityStopped(Activity value) {}
            public void onActivitySaveInstanceState(Activity value, Bundle state) {}
            public void onActivityDestroyed(Activity value) {}
        };
        app.registerActivityLifecycleCallbacks(lifecycle);
        File root = new File(context.getCacheDir(), "player-runtime-" + UUID.randomUUID());
        if (!root.mkdir()) throw new AssertionError("Owned fixture root unavailable");
        try {
            File wav = new File(root, "player.wav"), video = new File(root, "player.mp4");
            wave(wav); PlatformMediaTestFixtures.video(video);
            for (boolean isVideo : new boolean[]{false, true}) {
                String prefix = isVideo ? "video" : "audio";
                File source = isVideo ? video : wav;
                Uri uri = Uri.parse("content://" + context.getPackageName() + ".files/app_cache/" + root.getName() + "/" + source.getName());
                Intent view = new Intent(Intent.ACTION_VIEW).setClassName(context, "com.affilemanager.app.MainActivity")
                    .setDataAndType(uri, isVideo ? "video/mp4" : "audio/wav")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                test.runOnMainSync(() -> context.startActivity(view));
                await(test, prefix + "_play_pause", true);
                AccessibilityNodeInfo seek = await(test, prefix + "_seek", true);
                AccessibilityNodeInfo.RangeInfo inputRange = seek.getRangeInfo();
                if (inputRange == null || inputRange.getMax() <= inputRange.getMin()) throw new AssertionError("Invalid native seek range");
                float requested = inputRange.getMin() + .35f * (inputRange.getMax() - inputRange.getMin());
                android.util.Log.i("AFPlayerRuntime", prefix + " native seek range=" + inputRange.getMin() + ".." + inputRange.getMax()
                    + " current=" + inputRange.getCurrent() + " requested=" + requested);
                Bundle argument = new Bundle(); argument.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, requested);
                if (!seek.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), argument)) throw new AssertionError("Seek rejected");
                waitFor(() -> progress(test, prefix) > .2f, "Native seek position");
                com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier.awaitLabel(test, isVideo ? "0:01" : "0:10");
                float before = progress(test, prefix);
                if (before < .2f) throw new AssertionError("Native seek position was not reached");
                Activity old = activity.get();
                if (old == null) throw new AssertionError("Real Activity was not observed");
                test.runOnMainSync(old::recreate);
                waitFor(() -> activity.get() != old, "Activity recreation");
                await(test, prefix + "_play_pause", true);
                awaitDescription(test, "Start");
                if (Math.abs(progress(test, prefix) - before) > .12f) throw new AssertionError("Paused position reset on recreation");
                android.util.Log.i("AFPlayerRuntime", prefix + " paused position retained: " + before + " -> " + progress(test, prefix));
                click(test, prefix + "_play_pause");
                awaitDescription(test, "Pause");
                Activity portrait = activity.get();
                test.runOnMainSync(() -> portrait.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                waitFor(() -> activity.get() != portrait, "Landscape Activity recreation");
                await(test, prefix + "_play_pause", true);
                awaitDescription(test, "Pause");
                Activity landscape = activity.get();
                test.runOnMainSync(() -> landscape.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
                waitFor(() -> activity.get() != landscape, "Portrait Activity recreation");
                await(test, prefix + "_play_pause", true);
                awaitDescription(test, "Pause");
                click(test, prefix + "_play_pause");
                awaitDescription(test, "Start");
                com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier.clickDescription(test, "Close");
                if (source.length() <= 0) throw new AssertionError("Source was lost");
            }
            return true;
        } finally {
            app.unregisterActivityLifecycleCallbacks(lifecycle);
            for (File file : Objects.requireNonNull(root.listFiles())) if (!file.delete()) throw new AssertionError("Fixture cleanup failed");
            if (!root.delete()) throw new AssertionError("Fixture root cleanup failed");
        }
    }

    private interface Check { boolean get() throws Exception; }
    private static void waitFor(Check check, String name) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (!check.get()) { if (SystemClock.uptimeMillis() > deadline) throw new AssertionError(name + " timed out"); SystemClock.sleep(50); }
    }
    private static AccessibilityNodeInfo find(Instrumentation test, String tag, String description) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        AccessibilityNodeInfo active = test.getUiAutomation().getRootInActiveWindow();
        if (active != null && active.getChildCount() > 0) queue.add(active);
        else {
            AccessibilityNodeInfo top = null; int layer = Integer.MIN_VALUE;
            for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getLayer() > layer) {
                    top = root; layer = window.getLayer();
                }
            }
            if (top != null) queue.add(top);
        }
        int count = 0;
        AccessibilityNodeInfo rangeFallback = null;
        int rangeTop = Integer.MAX_VALUE;
        while (!queue.isEmpty() && count++ < 5000) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (!node.refresh()) continue;
            if (node.isVisibleToUser() && (tag != null && node.getViewIdResourceName() != null && node.getViewIdResourceName().endsWith(tag)
                || description != null && description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) return node;
            // A Dialog owns a separate semantics root on older Compose versions;
            // use its ordinary accessible controls if it does not inherit view IDs.
            if (tag != null && node.isVisibleToUser()) {
                if (tag.endsWith("_seek") && node.getRangeInfo() != null
                    && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)) {
                    android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty() && bounds.top < rangeTop) { rangeFallback = node; rangeTop = bounds.top; }
                }
                if (tag.endsWith("_play_pause") && ("Start".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())
                    || "Pause".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) {
                    for (AccessibilityNodeInfo control=node;control!=null;control=control.getParent()) if(control.isClickable())return control;
                }
            }
            for (int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo child=node.getChild(i); if(child!=null)queue.add(child); }
        }
        return rangeFallback;
    }
    private static AccessibilityNodeInfo await(Instrumentation test, String tag, boolean enabled) throws Exception {
        final AccessibilityNodeInfo[] result = {null};
        waitFor(() -> { result[0]=find(test,tag,null); return result[0]!=null && (!enabled || result[0].isEnabled()); }, tag);
        return result[0];
    }
    private static void awaitDescription(Instrumentation test, String description) throws Exception {
        waitFor(() -> find(test,null,description)!=null, description);
    }
    private static float progress(Instrumentation test, String prefix) throws Exception {
        AccessibilityNodeInfo.RangeInfo range=await(test,prefix+"_seek",true).getRangeInfo();
        if(range==null || range.getMax()<=range.getMin())throw new AssertionError("Seek has no progress");
        return (range.getCurrent()-range.getMin())/(range.getMax()-range.getMin());
    }
    private static void click(Instrumentation test, String tag) throws Exception {
        if(!await(test,tag,true).performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError(tag+" click rejected");
    }
    private static void wave(File file) throws IOException {
        int bytes=30000*48*2;
        ByteBuffer header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36+bytes).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1)
            .putShort((short)1).putInt(48000).putInt(96000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(bytes);
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))) { out.write(header.array()); byte[] silence=new byte[96000]; for(int i=0;i<30;i++)out.write(silence); }
    }
}
