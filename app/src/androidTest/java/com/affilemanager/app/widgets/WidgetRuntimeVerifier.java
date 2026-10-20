package com.affilemanager.app.widgets;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterViewFlipper;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Native framework + real configuration UI. No target model references or optimizer test keeps. */
public final class WidgetRuntimeVerifier {
    private interface Check { boolean accepts() throws Exception; }
    public static boolean verify(Instrumentation test) throws Exception {
        if (!android.os.Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned guest required");
        Context context = test.getTargetContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        AppWidgetHost host = new AppWidgetHost(context, 2026258);
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloads.isDirectory() && !downloads.mkdirs()) throw new AssertionError("Owned shared fixture unavailable");
        File root = new File(downloads, "AFWidgetRelease-" + UUID.randomUUID());
        if (!root.getCanonicalFile().getParentFile().equals(downloads.getCanonicalFile()) || !root.mkdir()) throw new AssertionError("Fixture boundary");
        File source = new File(root, "widget-blue.png");
        Bitmap bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.BLUE);
        try (OutputStream output = new FileOutputStream(source)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("Fixture image"); }
        finally { bitmap.recycle(); }
        byte[] original = MessageDigest.getInstance("SHA-256").digest(read(source));
        File second = new File(root, "widget-green.png");
        Bitmap green = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888); green.eraseColor(Color.GREEN);
        try (OutputStream output = new FileOutputStream(second)) { if (!green.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("Second fixture image"); }
        finally { green.recycle(); }
        byte[] secondOriginal = MessageDigest.getInstance("SHA-256").digest(read(second));
        int id = -1;
        Activity[] main = {null}; Activity configure = null;
        try {
            shell(test, "appwidget grantbind --package " + context.getPackageName() + " --user 0");
            host.deleteHost(); id = host.allocateAppWidgetId();
            if (!manager.bindAppWidgetIdIfAllowed(id, new ComponentName(context.getPackageName(), "com.affilemanager.app.widgets.AfWidgetProvider")))
                throw new AssertionError("System widget binding");
            configure = test.startActivitySync(new Intent().setClassName(context, "com.affilemanager.app.widgets.AfWidgetConfigureActivity")
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MediaEditingRuntimeVerifier.awaitLabel(test, "Widget");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Pictures");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Choose a folder");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Download");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, root.getName());
            MediaEditingRuntimeVerifier.clickLabel(test, "Select");
            MediaEditingRuntimeVerifier.clickLabel(test, "Save");
            main[0] = test.startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final int widgetId = id;
            AppWidgetHostView[] view = {null};
            test.runOnMainSync(() -> {
                host.startListening();
                view[0] = host.createView(main[0], widgetId, manager.getAppWidgetInfo(widgetId));
                main[0].findViewById(android.R.id.content).setVisibility(View.VISIBLE);
                ((ViewGroup) main[0].findViewById(android.R.id.content)).addView(view[0], new FrameLayout.LayoutParams(
                    Math.min(600, context.getResources().getDisplayMetrics().widthPixels), 700));
            });
            waitFor(() -> {
                boolean[] ready = {false};
                test.runOnMainSync(() -> {
                    AdapterViewFlipper flipper = findFlipper(view[0]);
                    View frame = flipper == null ? null : flipper.getCurrentView();
                    ready[0] = frame != null && frame.getWidth() > 0 && frame.getHeight() > 0 && containsText(frame, source.getName());
                });
                return ready[0];
            }, 20000);
            waitFor(() -> {
                int[] point = {-1, -1};
                test.runOnMainSync(() -> {
                    AdapterViewFlipper flipper = findFlipper(view[0]);
                    View frame = flipper == null ? null : flipper.getCurrentView();
                    if (frame != null && frame.getWidth() > 0 && frame.getHeight() > 0) {
                        frame.getLocationOnScreen(point); point[0] += frame.getWidth() / 2; point[1] += frame.getHeight() / 2;
                    }
                });
                Bitmap pixels = test.getUiAutomation().takeScreenshot();
                try {
                    if (pixels == null || point[0] < 0 || point[1] < 0 || point[0] >= pixels.getWidth() || point[1] >= pixels.getHeight()) return false;
                    int color = pixels.getPixel(point[0], point[1]);
                    return Color.blue(color) > 180 && Color.red(color) < 70 && Color.green(color) < 70;
                } finally { if (pixels != null) pixels.recycle(); }
            }, 5000);
            capture(test, "widgets-shipping-configured");
            MediaEditingRuntimeVerifier.clickDescription(test, "Next");
            waitFor(() -> {
                boolean[] ready = {false};
                test.runOnMainSync(() -> {
                    AdapterViewFlipper flipper = findFlipper(view[0]);
                    ready[0] = flipper != null && containsText(flipper.getCurrentView(), second.getName());
                });
                return ready[0];
            }, 15000);
            MediaEditingRuntimeVerifier.clickLabel(test, second.getName());
            MediaEditingRuntimeVerifier.awaitLabel(test, second.getName());
            // The file preview opens a separate dialog; its Close button is not the host's image.
            waitFor(() -> {
                android.view.accessibility.AccessibilityNodeInfo active = test.getUiAutomation().getRootInActiveWindow();
                return hasClose(active);
            }, 15000);
            capture(test, "widgets-shipping-opened-image");
            if (!Arrays.equals(original, MessageDigest.getInstance("SHA-256").digest(read(source)))) throw new AssertionError("Widget modified source");
            if (!Arrays.equals(secondOriginal, MessageDigest.getInstance("SHA-256").digest(read(second)))) throw new AssertionError("Widget modified second source");
            return true;
        } finally {
            Activity lastConfigure = configure;
            test.runOnMainSync(() -> { if (lastConfigure != null) lastConfigure.finish(); if (main[0] != null) main[0].finish(); });
            host.stopListening();
            if (id > 0) host.deleteAppWidgetId(id);
            shell(test, "appwidget revokebind --package " + context.getPackageName() + " --user 0");
            if (source.exists() && !source.delete()) throw new AssertionError("Owned widget source cleanup");
            if (second.exists() && !second.delete()) throw new AssertionError("Owned second widget source cleanup");
            if (!root.delete()) throw new AssertionError("Owned widget folder cleanup");
        }
    }
    private static AdapterViewFlipper findFlipper(View root) {
        if (root instanceof AdapterViewFlipper) return (AdapterViewFlipper) root;
        if (root instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) root).getChildCount(); index++) {
            AdapterViewFlipper found = findFlipper(((ViewGroup) root).getChildAt(index)); if (found != null) return found;
        }
        return null;
    }
    private static boolean containsText(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) root).getChildCount(); index++)
            if (containsText(((ViewGroup) root).getChildAt(index), value)) return true;
        return false;
    }
    private static boolean hasClose(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("Close".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()) && node.isVisibleToUser()) return true;
        for (int index = 0; index < node.getChildCount(); index++) if (hasClose(node.getChild(index))) return true;
        return false;
    }
    private static byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count); return output.toByteArray();
        }
    }
    private static void waitFor(Check check, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!check.accepts()) { if (SystemClock.elapsedRealtime() >= end) throw new AssertionError("Shipping widget timed out"); SystemClock.sleep(50); }
    }
    private static void shell(Instrumentation test, String command) throws IOException {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(test.getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[512]; while (input.read(buffer) >= 0) { }
        }
    }
    private static void capture(Instrumentation test, String name) throws IOException {
        Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        try (OutputStream output = new FileOutputStream(new File(test.getTargetContext().getExternalFilesDir("validation"), name + ".png"))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Widget capture");
        } finally { bitmap.recycle(); }
    }
}
