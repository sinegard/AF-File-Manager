package com.affilemanager.app.media.editing;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Black-box UI/native checks: no shipping classes, reflection hooks or optimizer keep rules. */
public final class MediaEditingRuntimeVerifier {
    /** Shared test-only native input, never added to the shipping application. */
    public static void clickLabel(Instrumentation test, String label) throws Exception {
        click(test, await(test, n -> text(n, label) && n.isVisibleToUser() && hasClickTarget(n), 15000));
    }
    public static void clickDescription(Instrumentation test, String description) throws Exception {
        click(test, await(test, n -> description.contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription())
                && n.isVisibleToUser() && hasClickTarget(n), 15000));
    }
    public static void scrollClickLabel(Instrumentation test, String label) throws Exception {
        click(test, scrollAwait(test, n -> text(n, label) && hasClickTarget(n)));
    }
    public static void awaitLabel(Instrumentation test, String label) throws Exception {
        await(test, n -> text(n, label), 15000);
    }
    public static boolean verify(Instrumentation test) throws Exception {
        if (!android.os.Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        Context context = test.getTargetContext();
        File root = new File(context.getCacheDir(), "media-release-" + UUID.randomUUID());
        if (!root.mkdir()) throw new AssertionError("Private fixture root unavailable");
        File source = new File(root, "video.mp4");
        PlatformMediaTestFixtures.video(source);
        byte[] original = digest(source);
        List<File> outputs = new ArrayList<>();
        android.accessibilityservice.AccessibilityServiceInfo access = test.getUiAutomation().getServiceInfo();
        access.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(access);
        try {
            // Choose a real destination through AF instead of inheriting another
            // suite's last folder, which its own fixture cleanup may have removed.
            com.affilemanager.app.BrowserIssuesRuntimeVerifier.openDownloads(test);
            for (String format : new String[]{"MP4", "MKV", "MOV", "M4A", "MP3", "WAV", "FLAC", "WMA", "GIF"}) {
                Uri uri = Uri.parse("content://" + context.getPackageName() + ".files/app_cache/" + root.getName() + "/video.mp4");
                // AF reuses its singleTask activity through onNewIntent. An activity
                // monitor waiting for a newly created Activity cannot validate that path.
                Intent view = new Intent(Intent.ACTION_VIEW).setClassName(context, "com.affilemanager.app.MainActivity")
                    .setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                test.runOnMainSync(() -> context.startActivity(view));
                click(test, await(test, n -> "Actions".contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()), 15000));
                click(test, await(test, n -> text(n, "Trim"), 15000));
                AccessibilityNodeInfo input = scrollAwait(test, MediaEditingRuntimeVerifier::editable);
                setText(input, "../invalid");
                await(test, n -> text(n, "Prepare and save as") && !clickable(n).isEnabled(), 5000);
                String name = "AFMediaRelease-" + UUID.randomUUID().toString().substring(0, 8);
                setText(await(test, MediaEditingRuntimeVerifier::editable, 5000), name);
                click(test, scrollAwait(test, n -> text(n, format)));
                await(test, n -> text(n, format) && selected(n), 5000);
                if (format.equals("MKV")) {
                    scrollClickLabel(test,"Reverse video");
                    scrollClickLabel(test,"Audio effects");
                    scrollClickLabel(test,"Noise reduction (FFT)");
                } else if (format.equals("MOV")) {
                    scrollClickLabel(test,"Copy tracks without re-encoding");
                } else if (format.equals("FLAC")) {
                    scrollClickLabel(test,"Audio effects");
                    scrollClickLabel(test,"Reverse audio");
                }
                if (format.equals("WAV")) {
                    scrollClickLabel(test, "22050 Hz");
                    scrollClickLabel(test, "Mono");
                    scrollClickLabel(test, "Audio effects");
                    scrollClickLabel(test, "500 ms"); // First visible row: fade in.
                }
                test.getUiAutomation().waitForIdle(250, 5000);
                capture(test, "media-shipping-" + format.toLowerCase(Locale.ROOT));
                click(test, await(test, n -> text(n, "Prepare and save as") && clickable(n).isEnabled(), 5000));
                await(test, n -> text(n, "Save here") && clickable(n).isEnabled(), 60000);
                // The action must already be accessible with an open keyboard.
                // Hide that observed IME only to inspect the folder heading, which
                // may legitimately be above the short, scrollable content viewport.
                for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && window.getRoot() != null) {
                        if (!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) throw new AssertionError("Keyboard back action rejected");
                        test.getUiAutomation().waitForIdle(250, 5000);
                        break;
                    }
                }
                AccessibilityNodeInfo path = await(test, n -> n.getText() != null && n.getText().toString().startsWith("/storage/emulated/0"), 5000);
                File destination = new File(path.getText().toString()).getCanonicalFile();
                File shared = Environment.getExternalStorageDirectory().getCanonicalFile();
                if (!(destination.equals(shared) || destination.getPath().startsWith(shared.getPath() + File.separator))) throw new AssertionError("Fixture destination escaped shared storage");
                File output = new File(destination, name + "." + format.toLowerCase(Locale.ROOT));
                if (output.exists()) throw new AssertionError("Unexpected occupied destination");
                outputs.add(output);
                // This owned Download fixture has files, never child folders.
                // Wait for its completed listing, not the initial loading body's
                // briefly enabled/cached action before the dialog's final height.
                await(test, n -> text(n, "This folder has no subfolders"), 15000);
                click(test, await(test, n -> text(n, "Save here") && clickable(n).isEnabled(), 5000));
                try {
                    waitFor(() -> output.isFile() && output.length() > 0, 15000, "Actual Save As file");
                } catch (AssertionError failure) {
                    System.out.println("AF_MEDIA_EXPECTED_OUTPUT " + output.getCanonicalPath() + " exists=" + output.exists() + " bytes=" + output.length());
                    File[] observed = destination.listFiles();
                    if (observed != null) for (File item : observed) if (item.getName().startsWith("AFMediaRelease-"))
                        System.out.println("AF_MEDIA_OBSERVED_OUTPUT " + item.getName() + " bytes=" + item.length());
                    throw failure;
                }
                if (format.equals("GIF")) {
                    android.graphics.Movie animation = android.graphics.Movie.decodeFile(output.getPath());
                    if (animation == null || animation.duration() < 2500 || animation.width() < 1) throw new AssertionError("Invalid actual GIF");
                } else if (format.equals("WMA")) {
                    verifyWma(context, root, output);
                } else {
                    MediaMetadataRetriever reader = new MediaMetadataRetriever();
                    MediaExtractor extractor = new MediaExtractor();
                    try {
                        reader.setDataSource(output.getPath());
                        long duration = Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                        if (duration < 2600 || duration > 3400) throw new AssertionError("Actual output duration mismatch");
                        extractor.setDataSource(output.getPath());
                        boolean video = false, audio = false;
                        for (int i = 0; i < extractor.getTrackCount(); i++) {
                            android.media.MediaFormat track = extractor.getTrackFormat(i);
                            String mime = track.getString("mime");
                            video |= mime.startsWith("video/"); audio |= mime.startsWith("audio/");
                            if (format.equals("WAV") && mime.startsWith("audio/")) {
                                if (track.getInteger("sample-rate") != 22050 || track.getInteger("channel-count") != 1)
                                    throw new AssertionError("Shipping sample-rate/channel controls did not change PCM");
                            }
                        }
                        if (!audio || ((format.equals("MP4") || format.equals("MKV") || format.equals("MOV")) != video)) throw new AssertionError("Output tracks differ from chosen format");
                    } finally { reader.release(); extractor.release(); }
                    if (format.equals("WAV")) verifyFade(output);
                }
                if (!Arrays.equals(original, digest(source))) throw new AssertionError("Source modified");
                click(test, await(test, n -> "Close".contentEquals(n.getContentDescription() == null ? "" : n.getContentDescription()), 15000));
                waitFor(() -> count(new File(context.getCacheDir(), "edit-sessions")) == 0 && count(new File(context.getCacheDir(), "media-exports")) == 0 && count(new File(context.getCacheDir(), "media-work")) == 0,
                    15000, "Private working/export cleanup");
                System.out.println("AF_MEDIA_SHIPPING_" + format + "_SAVEAS_OK");
            }
            return true;
        } catch (Exception | AssertionError failure) {
            try {
                AccessibilityNodeInfo scroll = await(test, n -> {
                    android.graphics.Rect bounds = new android.graphics.Rect(); n.getBoundsInScreen(bounds);
                    return n.isScrollable() && bounds.height() >= 200;
                }, 1000);
                for (int index = 0; index < 24 && scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); index++) {
                    test.getUiAutomation().waitForIdle(100, 2000);
                    scroll.refresh();
                }
                try { await(test, n -> false, 100); } catch (AssertionError details) { failure.addSuppressed(details); }
            } catch (Exception | AssertionError inspection) { failure.addSuppressed(inspection); }
            capture(test, "media-shipping-failure");
            throw failure;
        } finally {
            for (File output : outputs) if (output.exists() && !output.delete()) throw new AssertionError("Owned output cleanup failed");
            if (!source.delete() || !root.delete()) throw new AssertionError("Owned source cleanup failed");
        }
    }
    private static void verifyWma(Context context, File root, File output) throws Exception {
        File probe=new File(root,"probe.json"), diagnostics=new File(root,"native-check.txt"), decoded=new File(root,"decoded.pcm");
        try {
            nativeCheck(context,root,diagnostics,probe,"libffprobe-cli.so",Arrays.asList("-v","error","-show_entries","format=format_name,duration:stream=codec_type,codec_name","-of","json",output.getPath()));
            if(probe.length()>65536)throw new AssertionError("Unbounded probe response");
            org.json.JSONObject json=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(probe.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            if(!json.getJSONObject("format").getString("format_name").equals("asf"))throw new AssertionError("Not an actual ASF/WMA file");
            double duration=json.getJSONObject("format").getDouble("duration");
            if(duration<2.6 || duration>3.4 || !json.getJSONArray("streams").getJSONObject(0).getString("codec_name").equals("wmav2"))throw new AssertionError("WMA track/duration mismatch");
            nativeCheck(context,root,diagnostics,probe,"libffmpeg-cli.so",Arrays.asList("-hide_banner","-nostdin","-v","error","-y","-i",output.getPath(),"-t","0.25","-vn","-c:a","pcm_s16le","-f","s16le",decoded.getPath()));
            if(decoded.length()<24000 || decoded.length()>200000)throw new AssertionError("WMA did not decode to real PCM");
        } finally {for(File file:new File[]{probe,diagnostics,decoded})if(file.exists()&&!file.delete())throw new AssertionError("Owned native check cleanup failed");}
    }
    private static void nativeCheck(Context context,File root,File diagnostics,File stdout,String tool,List<String> args) throws Exception {
        File directory=new File(context.getApplicationInfo().nativeLibraryDir),pid=new File(root,"check-"+UUID.randomUUID()+".pid");
        List<String> command=new ArrayList<>();command.add(new File(directory,"libafmediaexec.so").getPath());command.add(new File(directory,tool).getPath());command.addAll(args);
        ProcessBuilder builder=new ProcessBuilder(command).redirectError(diagnostics).redirectOutput(stdout);
        builder.environment().put("LD_LIBRARY_PATH",directory.getPath());builder.environment().put("AF_MEDIA_PID_FILE",pid.getPath());
        Process process=builder.start();process.getOutputStream().close();
        try {if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Owned decoder timed out");if(process.exitValue()!=0)throw new AssertionError("Owned decoder failed: "+tool+"/"+process.exitValue());}
        finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS);}if(pid.exists()&&!pid.delete())throw new AssertionError("Owned PID cleanup failed");}
    }
    private static void verifyFade(File file) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(12);
            while (input.getFilePointer() + 8 <= input.length()) {
                byte[] name = new byte[4]; input.readFully(name);
                int bytes = Integer.reverseBytes(input.readInt()); long start = input.getFilePointer();
                if (bytes < 0 || start + bytes > input.length()) throw new AssertionError("Invalid shipping WAV chunk");
                if (new String(name, java.nio.charset.StandardCharsets.US_ASCII).equals("data")) {
                    int count = bytes / 2;
                    double first = 0, middle = 0;
                    for (int index = 0; index < count; index++) {
                        int sample = Short.reverseBytes(input.readShort());
                        if (index < 2205) first += Math.abs(sample);
                        if (index >= 22050 && index < 24255) middle += Math.abs(sample);
                    }
                    if (middle <= 0 || first >= middle * .25) throw new AssertionError("Shipping fade-in did not affect real PCM");
                    return;
                }
                input.seek(start + bytes + bytes % 2);
            }
        }
        throw new AssertionError("No shipping PCM samples");
    }
    private static int count(File root) { String[] values = root.list(); return values == null ? 0 : values.length; }
    private static void setText(AccessibilityNodeInfo input, String value) {
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new AssertionError("Output field rejected edit");
    }
    private interface Match { boolean accepts(AccessibilityNodeInfo node); }
    private static boolean editable(AccessibilityNodeInfo node) {
        return node.isEditable() || "android.widget.EditText".contentEquals(node.getClassName() == null ? "" : node.getClassName()) ||
            node.getActionList().stream().anyMatch(action -> action.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT);
    }
    private static AccessibilityNodeInfo scrollAwait(Instrumentation test, Match match) throws Exception {
        String lastObserved = "";
        for (int step = 0; step < 32; step++) {
            try { return await(test, match, 600); } catch (AssertionError missing) {
                lastObserved = missing.getMessage();
                AccessibilityNodeInfo scroll = await(test, n -> {
                    android.graphics.Rect viewport = new android.graphics.Rect(); n.getBoundsInScreen(viewport);
                    return n.isScrollable() && viewport.height() >= 200;
                }, 3000);
                android.graphics.Rect bounds = new android.graphics.Rect(); scroll.getBoundsInScreen(bounds);
                if (bounds.height() < 100) throw new AssertionError("Missing owned scroll viewport");
                long down = SystemClock.uptimeMillis();
                // The editor has a padded scrolling gutter. Do not start a vertical
                // scroll on a horizontal trim/seek slider and change its value.
                // Overlapping, small steps cannot jump over the output-name field.
                // Use the observed viewport's padding, not the center of its sliders.
                float x = bounds.left + Math.min(12, bounds.width() / 20f), start = bounds.top + bounds.height() * .75f, end = bounds.top + bounds.height() * .5f;
                System.out.println("AF_MEDIA_SCROLL " + step + " " + bounds);
                inject(test, down, android.view.MotionEvent.ACTION_DOWN, x, start);
                for (int index = 1; index <= 15; index++) { SystemClock.sleep(20); inject(test, down, android.view.MotionEvent.ACTION_MOVE, x, start + (end - start) * index / 15); }
                SystemClock.sleep(100);
                inject(test, down, android.view.MotionEvent.ACTION_MOVE, x, end);
                inject(test, down, android.view.MotionEvent.ACTION_UP, x, end);
                SystemClock.sleep(150);
            }
        }
        throw new AssertionError("Control absent after bounded forward scroll: " + lastObserved);
    }
    private static void inject(Instrumentation test, long down, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!test.getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Fixture scroll rejected"); }
        finally { event.recycle(); }
    }
    private interface Check { boolean accepts(); }
    private static void waitFor(Check check, long timeout, String description) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!check.accepts()) { if (SystemClock.elapsedRealtime() > end) throw new AssertionError(description + " timed out"); SystemClock.sleep(50); }
    }
    private static AccessibilityNodeInfo await(Instrumentation test, Match match, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        List<String> seen = new ArrayList<>();
        do {
            seen.clear(); ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            AccessibilityNodeInfo active = test.getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo top = null; int layer = Integer.MIN_VALUE;
            for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getLayer() > layer) {
                    top = root; layer = window.getLayer();
                }
            }
            if (active != null && active.getChildCount() > 0) queue.add(active);
            else if (top != null) queue.add(top);
            int count = 0;
            while (!queue.isEmpty() && count++ < 2500) {
                AccessibilityNodeInfo node = queue.removeFirst();
                if (!node.refresh()) continue;
                if (node.isVisibleToUser() && match.accepts(node) && node.refresh()) return node;
                if (node.isVisibleToUser() && seen.size() < 120) seen.add(node.getClassName() + ": " + node.getText() + " / " + node.getContentDescription() + " editable=" + editable(node));
                for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Shipping media control unavailable: " + seen);
    }
    private static boolean text(AccessibilityNodeInfo node, String value) { return value.contentEquals(node.getText() == null ? "" : node.getText()); }
    private static boolean selected(AccessibilityNodeInfo node) {
        for (int i = 0; node != null && i < 4; i++, node = node.getParent())
            if (node.isChecked() || node.isSelected()) return true;
        return false;
    }
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo node) {
        for (int i = 0; node != null && i < 10; i++, node = node.getParent())
            if (node.isClickable() || "android.widget.Button".contentEquals(node.getClassName() == null ? "" : node.getClassName())) return node;
        throw new AssertionError("Control has no clickable ancestor");
    }
    private static boolean hasClickTarget(AccessibilityNodeInfo node) {
        for (int i = 0; node != null && i < 10; i++, node = node.getParent()) {
            if (!node.isClickable() && !"android.widget.Button".contentEquals(node.getClassName())) continue;
            // Older Compose can publish visible=true before an offscreen row's
            // geometry intersects its real scrolling viewport. Keep scrolling
            // instead of accepting a target that native input cannot reach.
            try { return node.isVisibleToUser() && !visibleBounds(node).isEmpty(); }
            catch (AssertionError outsideViewport) { return false; }
        }
        return false;
    }
    private static void click(Instrumentation test, AccessibilityNodeInfo node) throws Exception {
        AccessibilityNodeInfo control = clickable(node);
        if (!control.refresh() || !control.isEnabled() || !control.isVisibleToUser()) throw new AssertionError("Control unavailable for touch");
        android.graphics.Rect bounds = visibleBounds(control);
        if (bounds.isEmpty()) throw new AssertionError("Control has no rendered touch bounds");
        // Accessibility idle does not mean the keyboard/window resize animation
        // has finished. A cached enabled button can move between lookup and touch.
        // Wait for its observed native bounds to settle, not a guessed coordinate.
        CharSequence label = node.getText();
        CharSequence description = node.getContentDescription();
        long stableSince = SystemClock.elapsedRealtime();
        long deadline = stableSince + 5000;
        while (SystemClock.elapsedRealtime() - stableSince < 800) {
            SystemClock.sleep(100);
            // Android may keep an old virtual-node snapshot while the replaced
            // dialog's native window is still publishing its new geometry. Read
            // a fresh active-window tree, rather than refreshing that old handle.
            control = clickable(await(test, n ->
                Objects.equals(label, n.getText()) && Objects.equals(description, n.getContentDescription()) &&
                    (label != null || description != null) && hasClickTarget(n), 1000));
            if (!control.refresh() || !control.isEnabled() || !control.isVisibleToUser()) throw new AssertionError("Control changed before native touch");
            android.graphics.Rect current = visibleBounds(control);
            if (current.isEmpty()) throw new AssertionError("Control lost its native touch bounds");
            if (!current.equals(bounds)) stableSince = SystemClock.elapsedRealtime();
            bounds.set(current);
            if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("Control bounds never settled for native touch");
        }
        System.out.println("AF_MEDIA_NATIVE_TOUCH " + node.getText() + " " + node.getContentDescription() + " " + bounds);
        // Exercise the same native touch route as a user. ACTION_CLICK may report
        // success for a cached Compose virtual node without invoking its current action.
        long down = SystemClock.uptimeMillis();
        inject(test, down, android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY());
        inject(test, down, android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY());
        test.waitForIdleSync();
        test.getUiAutomation().waitForIdle(250, 5000);
    }
    private static android.graphics.Rect visibleBounds(AccessibilityNodeInfo control) {
        android.graphics.Rect bounds = new android.graphics.Rect(); control.getBoundsInScreen(bounds);
        // Compose can expose a partially visible button's complete virtual bounds.
        // Its raw center may be under the sticky footer; touch only pixels inside
        // the observed scrolling viewport, never the obscured part of that button.
        AccessibilityNodeInfo parent = control.getParent();
        for (int count = 0; parent != null && count < 20; count++, parent = parent.getParent()) {
            if (!parent.isScrollable()) continue;
            android.graphics.Rect viewport = new android.graphics.Rect(); parent.getBoundsInScreen(viewport);
            if (!bounds.intersect(viewport)) throw new AssertionError("Control is outside its visible scroll viewport");
        }
        return bounds;
    }
    private static byte[] digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[65536]; for (int n; (n = input.read(buffer)) >= 0;) hash.update(buffer, 0, n); }
        return hash.digest();
    }
    private static void capture(Instrumentation test, String name) throws IOException {
        Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        try (OutputStream output = new FileOutputStream(new File(test.getTargetContext().getExternalFilesDir("validation"), name + ".png"))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Capture failed");
        } finally { bitmap.recycle(); }
    }
}
