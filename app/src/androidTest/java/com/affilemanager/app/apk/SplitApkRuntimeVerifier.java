package com.affilemanager.app.apk;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

/** Framework-only acceptance. No target-class references or relaxed target R8 rules. */
public final class SplitApkRuntimeVerifier {
    private static final String FIXTURE = "com.affilemanager.fixture.split";
    public static boolean verify(Instrumentation test) throws Exception {
        if (!android.os.Build.MODEL.contains("sdk")) throw new AssertionError("Disposable emulator required");
        Context context = test.getTargetContext();
        if (installed(context) != null) throw new AssertionError("Fixture already exists; refusing to change it");
        File root = new File(context.getCacheDir(), "optimized-split-" + UUID.randomUUID());
        if (!root.mkdir()) throw new AssertionError("Fixture directory creation failed");
        try {
            shell(test, "appops set " + context.getPackageName() + " REQUEST_INSTALL_PACKAGES allow");
            launch(test, fixture(test, root, "valid"));
            AccessibilityNodeInfo install = waitText(test, value -> value.equalsIgnoreCase("Install"), 15000);
            if (install == null || installed(context) != null) throw new AssertionError("Missing Android confirmation");
            if (!install.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Install control failed");
            await(() -> installed(context) != null);
            if (!Arrays.asList(installed(context).splitNames).contains("config.en")) throw new AssertionError("Original split missing");
            shell(test, "pm uninstall " + FIXTURE);
            if (installed(context) != null) throw new AssertionError("Fixture removal failed");

            launch(test, fixture(test, root, "wrong-signature"));
            install = waitText(test, value -> value.equalsIgnoreCase("Install"), 3000);
            if (install != null) install.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo failure = waitText(test, value -> value.toLowerCase(java.util.Locale.ROOT).matches(".*(signatures|certificates|certificate|invalid_apk).*"), 15000);
            if (failure == null || installed(context) != null) throw new AssertionError("Signer mismatch not rejected visibly");
            AccessibilityNodeInfo close = waitText(test, value -> value.equalsIgnoreCase("Close"), 3000);
            while (close != null && !close.isClickable()) close = close.getParent();
            if (close == null || !close.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Close control failed");
            test.waitForIdleSync();

            launch(test, new File(root, "valid.apks"));
            AccessibilityNodeInfo cancel = waitText(test, value -> value.equalsIgnoreCase("Cancel"), 15000);
            if (cancel == null || !cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Missing cancel confirmation");
            await(() -> context.getPackageManager().getPackageInstaller().getMySessions().isEmpty());
            if (installed(context) != null) throw new AssertionError("Cancelled fixture installed");
            File[] staged = new File(context.getCacheDir(), "split-apk-install").listFiles();
            if (staged != null && staged.length != 0) throw new AssertionError("Temporary APK part remained");
            return true;
        } finally {
            if (installed(context) != null) shell(test, "pm uninstall " + FIXTURE);
            File[] files = root.listFiles();
            if (files != null) for (File file : files) if (!file.delete()) throw new AssertionError("Fixture file cleanup failed");
            if (!root.delete()) throw new AssertionError("Fixture directory cleanup failed");
        }
    }
    private static File fixture(Instrumentation test, File root, String name) throws Exception {
        File file = new File(root, name + ".apks");
        try (InputStream input = test.getContext().getAssets().open("split-apk/" + name + ".apks"); FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
        return file;
    }
    private static void launch(Instrumentation test, File file) throws Exception {
        ArrayList<String> parts = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) { String name = entries.nextElement().getName(); if (name.endsWith(".apk")) parts.add(name); }
        }
        Intent intent = new Intent().setClassName(test.getTargetContext(), "com.affilemanager.app.apk.SplitApkInstallActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("archive", file.getPath()).putStringArrayListExtra("parts", parts);
        // Single-top callbacks may reuse an Activity; startActivitySync would wait
        // indefinitely for an onCreate that must not happen. All outcomes below are bounded.
        test.runOnMainSync(() -> test.getTargetContext().startActivity(intent));
    }
    private static PackageInfo installed(Context context) {
        try { return context.getPackageManager().getPackageInfo(FIXTURE, 0); }
        catch (android.content.pm.PackageManager.NameNotFoundException expected) { return null; }
    }
    private static void shell(Instrumentation test, String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(test.getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[4096]; while (input.read(buffer) >= 0) { /* Drain only; no credential or package inventory logging. */ }
        }
    }
    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        if (!condition.getAsBoolean()) throw new AssertionError("Native install transition timed out");
    }
    private static AccessibilityNodeInfo waitText(Instrumentation test, Predicate<String> matches, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            AccessibilityNodeInfo node = find(test.getUiAutomation().getRootInActiveWindow(), matches);
            if (node != null) return node;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        return null;
    }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, Predicate<String> matches) {
        if (node == null) return null;
        if (matches.test(node.getText() == null ? "" : node.getText().toString())) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo result = find(node.getChild(index), matches);
            if (result != null) return result;
        }
        return null;
    }
}
