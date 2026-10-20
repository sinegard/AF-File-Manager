package com.affilemanager.app.widgets;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the actual home launcher, not a host view running under AF's own visible UID. */
public final class PinnedWidgetRuntimeVerifier {
    private interface Check { boolean ready() throws Exception; }

    public static boolean verify(Instrumentation test) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned guest required");
        Context context = test.getTargetContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (!manager.isRequestPinAppWidgetSupported()) throw new AssertionError("The test launcher does not support widget pinning");
        ComponentName provider = new ComponentName(context.getPackageName(), "com.affilemanager.app.widgets.AfWidgetProvider");
        Set<Integer> before = ids(manager, provider);
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File root = new File(downloads, "AFWidgetPin-" + UUID.randomUUID());
        if (!root.getCanonicalFile().getParentFile().equals(downloads.getCanonicalFile()) || !root.mkdir()) throw new AssertionError("Pin fixture boundary");
        String fixture = UUID.randomUUID().toString().substring(0, 6);
        File blue = new File(root, "pin-" + fixture + "-blue.png"), green = new File(root, "pin-" + fixture + "-green.png");
        image(blue, Color.BLUE); image(green, Color.GREEN);
        byte[] blueHash = hash(blue), greenHash = hash(green);
        String action = context.getPackageName() + ".TEST_WIDGET_PIN_" + UUID.randomUUID();
        AtomicBoolean accepted = new AtomicBoolean();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { if (action.equals(intent.getAction())) accepted.set(true); }
        };
        Activity main = null;
        boolean registered = false;
        Throwable primary = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, new IntentFilter(action));
            registered = true;
            PendingIntent callback = PendingIntent.getBroadcast(context, 2026270,
                    new Intent(action).setPackage(context.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            main = test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            boolean[] requested = {false};
            test.runOnMainSync(() -> requested[0] = manager.requestPinAppWidget(provider, null, callback));
            if (!requested[0]) throw new AssertionError("Home launcher rejected the pin request");
            waitFor(() -> {
                AccessibilityNodeInfo add = confirmation(test.getUiAutomation().getRootInActiveWindow());
                return add != null && add.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }, 10000);
            waitFor(accepted::get, 15000);
            if (!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) throw new AssertionError("Home navigation");
            // Pin requests do not run the launcher's widget-picker configuration flow.
            // Use the same visible Settings action that a user uses after pinning.
            findHomePage(test, "Open widget settings");
            MediaEditingRuntimeVerifier.clickDescription(test, "Settings");
            MediaEditingRuntimeVerifier.awaitLabel(test, "Widget");
            settleConfigurationWindow(test);
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Pictures");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Choose a folder");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Download");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, root.getName());
            MediaEditingRuntimeVerifier.clickLabel(test, "Select");
            MediaEditingRuntimeVerifier.clickLabel(test, "Save");
            if (!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) throw new AssertionError("Home navigation");
            findHomePage(test, blue.getName());
            MediaEditingRuntimeVerifier.awaitLabel(test, blue.getName());
            waitFor(() -> settledImage(test, blue.getName(), Color.BLUE), 10000);
            capture(test, "widget-pinned-home");
            MediaEditingRuntimeVerifier.clickDescription(test, "Next");
            Set<Integer> currentIds = ids(manager, provider); currentIds.removeAll(before);
            if (currentIds.size() != 1) throw new AssertionError("Pinned widget identity changed");
            final int currentId = currentIds.iterator().next();
            waitFor(() -> {
                String raw = context.getSharedPreferences("home_widgets_v1", Context.MODE_PRIVATE).getString(String.valueOf(currentId), null);
                return raw != null && new org.json.JSONObject(raw).getInt("index") == 1;
            }, 5000);
            System.out.println("AF_WIDGET_INDEX_STORED id=" + currentId + " index=1");
            MediaEditingRuntimeVerifier.awaitLabel(test, green.getName());
            waitFor(() -> settledImage(test, green.getName(), Color.GREEN), 10000);
            capture(test, "widget-pinned-next");
            MediaEditingRuntimeVerifier.clickLabel(test, green.getName());
            waitFor(() -> {
                AccessibilityNodeInfo active = test.getUiAutomation().getRootInActiveWindow();
                return active != null && context.getPackageName().contentEquals(active.getPackageName())
                        && contains(active, green.getName()) && hasClose(active);
            }, 15000);
            capture(test, "widget-pinned-opened");
            if (!Arrays.equals(blueHash, hash(blue)) || !Arrays.equals(greenHash, hash(green))) throw new AssertionError("Pinned widget modified a source");
            removeFromHome(test, manager, provider, currentId, green.getName());
            System.out.println("AF_WIDGET_PIN_ACCEPTED actual home launcher; AF background; Next and exact image open");
            return true;
        } catch (Throwable failure) {
            primary = failure;
            try { capture(test, "widget-pinned-failed"); } catch (Throwable capture) { failure.addSuppressed(capture); }
            throw failure;
        } finally {
            try {
                if (registered) context.unregisterReceiver(receiver);
                Set<Integer> created = ids(manager, provider); created.removeAll(before);
                AppWidgetHost deletion = new AppWidgetHost(context, 2026270);
                for (int id : created) deletion.deleteAppWidgetId(id);
                if (!ids(manager, provider).equals(before)) throw new AssertionError("Pinned widget ID cleanup");
                Activity lastMain = main;
                test.runOnMainSync(() -> { if (lastMain != null) lastMain.finish(); });
                if (!blue.delete() || !green.delete() || !root.delete()) throw new AssertionError("Owned pin fixture cleanup");
            } catch (Throwable cleanup) {
                if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    private static void settleConfigurationWindow(Instrumentation test) throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        int previousFlags = service.flags;
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(service);
        try {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            long stableSince = SystemClock.elapsedRealtime();
            Rect previous = null;
            boolean dismissRequested = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                boolean keyboardVisible = false;
                for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
                    AccessibilityNodeInfo root = window.getRoot();
                    keyboardVisible |= window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                            && root != null && root.isVisibleToUser();
                }
                if (keyboardVisible) {
                    // API26 may publish the dialog before the previous input method finishes
                    // opening. Dismiss only that observed keyboard, never blindly close a dialog.
                    if (!dismissRequested) {
                        if (!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                            throw new AssertionError("Observed widget keyboard Back rejected");
                        System.out.println("AF_WIDGET_DISMISSED_OBSERVED_IME");
                        dismissRequested = true;
                    }
                    previous = null;
                    stableSince = SystemClock.elapsedRealtime();
                } else {
                    dismissRequested = false;
                    AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
                    if (root != null && contains(root, "Widget") && hasClose(root)) {
                        Rect current = new Rect(); root.getBoundsInScreen(current);
                        if (current.isEmpty() || previous == null || !current.equals(previous)) {
                            previous = current;
                            stableSince = SystemClock.elapsedRealtime();
                        } else if (SystemClock.elapsedRealtime() - stableSince >= 800) {
                            System.out.println("AF_WIDGET_CONFIGURATION_STABLE " + current);
                            return;
                        }
                    } else {
                        previous = null;
                        stableSince = SystemClock.elapsedRealtime();
                    }
                }
                SystemClock.sleep(100);
            }
            throw new AssertionError("Actual widget configuration or keyboard did not settle within five seconds");
        } finally {
            android.accessibilityservice.AccessibilityServiceInfo restored = test.getUiAutomation().getServiceInfo();
            restored.flags = previousFlags;
            test.getUiAutomation().setServiceInfo(restored);
        }
    }

    private static Set<Integer> ids(AppWidgetManager manager, ComponentName provider) {
        Set<Integer> result = new HashSet<>(); for (int id : manager.getAppWidgetIds(provider)) result.add(id); return result;
    }
    private static AccessibilityNodeInfo confirmation(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = node.getText() == null ? "" : node.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (Arrays.asList("add", "add automatically", "add widget", "add to home screen").contains(text)) {
            AccessibilityNodeInfo click = node;
            while (click != null && !click.isClickable()) click = click.getParent();
            if (click != null && click.isVisibleToUser()) return click;
        }
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo match = confirmation(node.getChild(i)); if (match != null) return match; }
        return null;
    }
    private static boolean contains(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (text.contentEquals(node.getText() == null ? "" : node.getText()) && node.isVisibleToUser()) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (contains(node.getChild(i), text)) return true;
        return false;
    }
    private static boolean hasClose(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("Close".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()) && node.isVisibleToUser()) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasClose(node.getChild(i))) return true;
        return false;
    }
    private static boolean settledImage(Instrumentation test, String label, int expected) {
        AccessibilityNodeInfo widget = imageWidget(test.getUiAutomation().getRootInActiveWindow(), label);
        if (widget == null) return false;
        Rect bounds = new Rect(); widget.getBoundsInScreen(bounds);
        Bitmap screen = test.getUiAutomation().takeScreenshot();
        if (screen == null) return false;
        try {
            if (bounds.isEmpty() || !new Rect(0, 0, screen.getWidth(), screen.getHeight()).contains(bounds.centerX(), bounds.centerY())) return false;
            int pixel = screen.getPixel(bounds.centerX(), bounds.centerY());
            return Math.abs(Color.red(pixel) - Color.red(expected)) < 50
                    && Math.abs(Color.green(pixel) - Color.green(expected)) < 50
                    && Math.abs(Color.blue(pixel) - Color.blue(expected)) < 50;
        } finally { screen.recycle(); }
    }
    private static AccessibilityNodeInfo imageWidget(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (node.getClassName() != null && node.getClassName().toString().endsWith("AppWidgetHostView") && contains(node, label)) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = imageWidget(node.getChild(i), label); if (found != null) return found; }
        return null;
    }
    private static void findHomePage(Instrumentation test, String label) throws Exception {
        // A second Home press returns the old AOSP launcher to page zero, even
        // when Add automatically placed this widget on a different page.
        Rect viewport = new Rect(0, 0, test.getTargetContext().getResources().getDisplayMetrics().widthPixels,
                test.getTargetContext().getResources().getDisplayMetrics().heightPixels);
        waitFor(() -> homePager(test.getUiAutomation().getRootInActiveWindow()) != null ||
                visibleLabel(test.getUiAutomation().getRootInActiveWindow(), label, viewport), 10000);
        for (boolean forward : new boolean[]{true, false}) for (int page = 0; page < 5; page++) {
            if (visibleLabel(test.getUiAutomation().getRootInActiveWindow(), label, viewport)) {
                capture(test, "widget-home-found");
                return;
            }
            AccessibilityNodeInfo pager = homePager(test.getUiAutomation().getRootInActiveWindow());
            if (pager == null) { SystemClock.sleep(200); continue; }
            Rect bounds = new Rect(); pager.getBoundsInScreen(bounds);
            System.out.println("AF_WIDGET_PAGE forward=" + forward + " page=" + page + " viewport=" + viewport + " pager=" + bounds);
            if (bounds.width() < 100 || bounds.height() < 100) throw new AssertionError("Home pager has no touch bounds");
            float start = bounds.left + bounds.width() * (forward ? .8f : .2f);
            float end = bounds.left + bounds.width() * (forward ? .2f : .8f);
            float y = bounds.top + bounds.height() * .45f;
            long down = SystemClock.uptimeMillis();
            touch(test, down, android.view.MotionEvent.ACTION_DOWN, start, y);
            for (int step = 1; step <= 15; step++) {
                SystemClock.sleep(20);
                touch(test, down, android.view.MotionEvent.ACTION_MOVE, start + (end - start) * step / 15, y);
            }
            touch(test, down, android.view.MotionEvent.ACTION_UP, end, y);
            test.getUiAutomation().waitForIdle(200, 3000);
        }
        throw new AssertionError("The pinned widget is absent from the bounded home-page search");
    }
    private static boolean visibleLabel(AccessibilityNodeInfo node, String label, Rect viewport) {
        if (node == null || !node.refresh()) return false;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (label.contentEquals(node.getText() == null ? "" : node.getText()) && node.isVisibleToUser() &&
                !bounds.isEmpty() && viewport.contains(bounds.centerX(), bounds.centerY())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (visibleLabel(node.getChild(i), label, viewport)) return true;
        return false;
    }
    private static boolean hasAfHost(AccessibilityNodeInfo node, Rect viewport) {
        if (node == null) return false;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && node.getClassName() != null && node.getClassName().toString().endsWith("AppWidgetHostView") &&
                "AF File Manager".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()) &&
                viewport.contains(bounds.centerX(), bounds.centerY())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasAfHost(node.getChild(i), viewport)) return true;
        return false;
    }
    private static void removeFromHome(Instrumentation test, AppWidgetManager manager, ComponentName provider, int id, String label) throws Exception {
        if (!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) throw new AssertionError("Home cleanup navigation");
        findHomePage(test, label);
        boolean removed = false;
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!removed && SystemClock.elapsedRealtime() < deadline) {
            if (!ids(manager, provider).contains(id) && !test.getTargetContext()
                    .getSharedPreferences("home_widgets_v1", Context.MODE_PRIVATE).contains(String.valueOf(id))) {
                removed = true;
                break;
            }
            // A modern launcher can expose a nested framework collection host,
            // while its own Remove action belongs to the enclosing widget cell.
            // Home's transition can also publish the label before these actions.
            AccessibilityNodeInfo widget = imageWidget(test.getUiAutomation().getRootInActiveWindow(), label);
            for (int depth = 0; widget != null && depth < 8 && !removed; depth++, widget = widget.getParent()) {
                if (widget.getClassName() != null && widget.getClassName().toString().endsWith("Workspace")) break;
                for (AccessibilityNodeInfo.AccessibilityAction action : widget.getActionList()) {
                    String name = action.getLabel() == null ? "" : action.getLabel().toString().toLowerCase(Locale.ROOT);
                    if (widget.isVisibleToUser() && widget.isEnabled() &&
                            (name.equals("remove") || name.equals("remove widget") || name.equals("remove from home screen"))) {
                        removed = widget.performAction(action.getId()); break;
                    }
                }
            }
            if (!removed) SystemClock.sleep(100);
        }
        if (!removed && !ids(manager, provider).contains(id) && !test.getTargetContext()
                .getSharedPreferences("home_widgets_v1", Context.MODE_PRIVATE).contains(String.valueOf(id))) removed = true;
        if (!removed) {
            // Some launchers expose removal only through their normal drag target,
            // not a custom accessibility action on a nested collection host.
            AccessibilityNodeInfo widget = imageWidget(test.getUiAutomation().getRootInActiveWindow(), label);
            if (widget == null) throw new AssertionError("Owned widget drag-removal target");
            Rect source = new Rect(); widget.getBoundsInScreen(source);
            if (source.isEmpty()) throw new AssertionError("Owned widget drag bounds");
            long down = SystemClock.uptimeMillis();
            boolean held = true;
            try {
                touch(test, down, android.view.MotionEvent.ACTION_DOWN, source.exactCenterX(), source.exactCenterY());
                SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 150);
                touch(test, down, android.view.MotionEvent.ACTION_MOVE, source.exactCenterX() + 32, source.exactCenterY() - 32);
                AccessibilityNodeInfo target = null;
                long targetDeadline = SystemClock.elapsedRealtime() + 5000;
                while (target == null && SystemClock.elapsedRealtime() < targetDeadline) {
                    target = removeDropTarget(test.getUiAutomation().getRootInActiveWindow());
                    if (target == null) SystemClock.sleep(100);
                }
                if (target == null) throw new AssertionError("The real launcher did not expose a visible Remove drop target");
                Rect destination = new Rect(); target.getBoundsInScreen(destination);
                for (int step = 1; step <= 20; step++) {
                    SystemClock.sleep(20);
                    touch(test, down, android.view.MotionEvent.ACTION_MOVE,
                        source.exactCenterX() + (destination.exactCenterX() - source.exactCenterX()) * step / 20,
                        source.exactCenterY() + (destination.exactCenterY() - source.exactCenterY()) * step / 20);
                }
                touch(test, down, android.view.MotionEvent.ACTION_UP, destination.exactCenterX(), destination.exactCenterY());
                held = false;
            } finally {
                if (held) touch(test, down, android.view.MotionEvent.ACTION_CANCEL, source.exactCenterX(), source.exactCenterY());
            }
        }
        waitFor(() -> !ids(manager, provider).contains(id) && !test.getTargetContext()
                .getSharedPreferences("home_widgets_v1", Context.MODE_PRIVATE).contains(String.valueOf(id)), 10000);
    }
    private static AccessibilityNodeInfo removeDropTarget(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = node.getText() == null ? "" : node.getText().toString().trim().toLowerCase(Locale.ROOT);
        String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim().toLowerCase(Locale.ROOT);
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && !bounds.isEmpty() &&
            (text.equals("remove") || description.equals("remove") || text.equals("remove from home screen"))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = removeDropTarget(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }
    private static AccessibilityNodeInfo homePager(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String type = node.getClassName() == null ? "" : node.getClassName().toString();
        if (node.isVisibleToUser() && (type.endsWith("Workspace") ||
                type.equals("android.widget.ScrollView") && "Home".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = homePager(node.getChild(i)); if (found != null) return found; }
        return null;
    }
    private static void touch(Instrumentation test, long down, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!test.getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Home-page touch was rejected"); }
        finally { event.recycle(); }
    }
    private static void image(File file, int color) throws IOException {
        Bitmap image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888); image.eraseColor(color);
        try (OutputStream out = new FileOutputStream(file)) { if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Pin image"); }
        finally { image.recycle(); }
    }
    private static byte[] hash(File file) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; for (int count; (count = in.read(buffer)) >= 0;) out.write(buffer, 0, count);
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        }
    }
    private static void waitFor(Check check, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!check.ready()) { if (SystemClock.elapsedRealtime() >= end) throw new AssertionError("Actual launcher widget timed out"); SystemClock.sleep(50); }
    }
    private static void capture(Instrumentation test, String name) throws IOException {
        File folder = test.getTargetContext().getExternalFilesDir("validation");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Pin evidence folder");
        Bitmap image = test.getUiAutomation().takeScreenshot();
        try (OutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Pin capture");
        } finally { image.recycle(); }
    }
}
