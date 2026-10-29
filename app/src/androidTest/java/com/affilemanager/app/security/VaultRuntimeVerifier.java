package com.affilemanager.app.security;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.TransferOptionsRuntimeVerifier;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Black-box vault workflow for the normal optimized app; no shipping class names or hooks. */
public final class VaultRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File root = new File(downloads, "AFVault-" + UUID.randomUUID().toString().substring(0, 8));
        if (!root.mkdir()) throw new AssertionError("Vault fixture unavailable");
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(service);
        File source = new File(root, "document.txt");
        File first = new File(root, "document.txt.afvault");
        File second = new File(root, "document.txt (1).afvault");
        boolean accepted = false;
        try {
            byte[] block = new byte[65536]; Arrays.fill(block, (byte)'a'); block[block.length - 1] = '\n';
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(source))) {
                for (int i = 0; i < 34; i++) output.write(block);
            }
            String original = sha(source);
            String firstHash = null;
            for (int round = 0; round < 2; round++) {
                navigate(test, root);
                MediaEditingRuntimeVerifier.clickDescription(test, "File actions: " + source.getName());
                MediaEditingRuntimeVerifier.clickLabel(test, "Select");
                MediaEditingRuntimeVerifier.clickLabel(test, "More");
                MediaEditingRuntimeVerifier.scrollClickLabel(test, "Privacy");
                MediaEditingRuntimeVerifier.scrollClickLabel(test, "Encrypt selected file");
                waitFor(() -> editable(test).size() == 2, 10000);
                MediaEditingRuntimeVerifier.setText(editable(test).get(0), round == 0 ? "af-runtime-one" : "af-runtime-two");
                MediaEditingRuntimeVerifier.setText(editable(test).get(1), round == 0 ? "af-runtime-one" : "af-runtime-two");
                test.getUiAutomation().waitForIdle(250, 5000);
                android.graphics.Bitmap keyboard = Objects.requireNonNull(test.getUiAutomation().takeScreenshot());
                try (OutputStream output = new FileOutputStream(new File(context.getExternalFilesDir("validation"),
                        root.getName() + "-passphrase-" + round + ".png"))) {
                    if (!keyboard.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                        throw new AssertionError("Could not capture the real public-fixture passphrase window");
                } finally { keyboard.recycle(); }
                MediaEditingRuntimeVerifier.clickLabel(test, "Encrypt");
                File expected = round == 0 ? first : second;
                waitFor(() -> expected.isFile() && expected.length() > source.length(), 15000);
                try (DataInputStream header = new DataInputStream(new FileInputStream(expected))) {
                    byte[] magic = new byte[6]; header.readFully(magic);
                    if (!"AFFMV2".equals(new String(magic, StandardCharsets.US_ASCII))) throw new AssertionError("The real UI did not write V2");
                }
                if (round == 0) firstHash = sha(first);
                else if (!firstHash.equals(sha(first))) throw new AssertionError("Repeated encryption changed its first result");
                if (!original.equals(sha(source))) throw new AssertionError("Encryption changed the original");
            }
            decryptThroughUi(test, root, first, "af-runtime-one");
            File restored = new File(root, "document (1).txt");
            waitFor(restored::isFile, 15000);
            if (!original.equals(sha(restored)) || !original.equals(sha(source))) throw new AssertionError("Actual V2 restore or Keep both failed");

            File legacy = new File(root, "legacy.afvault");
            try (InputStream input = test.getContext().getAssets().open("affmv1-legacy-vector.b64")) {
                String encoded = new String(readSmall(input), StandardCharsets.US_ASCII).trim();
                try (OutputStream output = new FileOutputStream(legacy)) { output.write(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)); }
            }
            String legacyHash = sha(legacy);
            decryptThroughUi(test, root, legacy, "public-compatibility-vector");
            File oldRestored = new File(root, "legacy.txt");
            waitFor(oldRestored::isFile, 15000);
            String expectedLegacy = "AF legacy compatibility ąčęėįšųūž\nsecond line\n";
            try (InputStream input = new FileInputStream(oldRestored)) {
                if (!expectedLegacy.equals(new String(readSmall(input), StandardCharsets.UTF_8))) throw new AssertionError("Actual V1 preview restore differs");
            }
            if (!legacyHash.equals(sha(legacy))) throw new AssertionError("Old vault was rewritten");

            Set<String> before = new TreeSet<>(Arrays.asList(Objects.requireNonNull(root.list())));
            decryptThroughUi(test, root, first, "deliberately-wrong-key");
            MediaEditingRuntimeVerifier.awaitLabel(test, "Could not decrypt the file. Check the passphrase or try another copy.");
            if (!before.equals(new TreeSet<>(Arrays.asList(Objects.requireNonNull(root.list()))))) throw new AssertionError("Wrong key left a plaintext output or stage");
            if (!firstHash.equals(sha(first)) || !original.equals(sha(source))) throw new AssertionError("Failed decryption changed an input");
            File record = new File(context.getExternalFilesDir("validation"), "optimized-vault-runtime.json");
            try (Writer output = new OutputStreamWriter(new FileOutputStream(record), StandardCharsets.UTF_8)) {
                output.write(new JSONObject().put("package", context.getPackageName()).put("api", Build.VERSION.SDK_INT)
                    .put("sourceSha256", original).put("restoredSha256", sha(restored)).put("firstVaultSha256", firstHash)
                    .put("legacyVaultSha256", legacyHash).put("repeatKeepsBoth", true).put("legacyUiRestore", true)
                    .put("wrongKeyVisibleAndNoOutput", true).toString(2));
            }
            accepted = true;
            return true;
        } finally {
            if (!root.getCanonicalFile().getParentFile().equals(downloads.getCanonicalFile()) || !root.getName().startsWith("AFVault-"))
                throw new AssertionError("Unsafe vault fixture cleanup target");
            if (accepted) removeOwned(root);
            else android.util.Log.e("AFVaultAudit", "Retained failed synthetic optimized fixture: " + root.getPath());
        }
    }

    private static void decryptThroughUi(Instrumentation test, File root, File vault, String password) throws Exception {
        navigate(test, root);
        MediaEditingRuntimeVerifier.scrollClickLabel(test, vault.getName());
        MediaEditingRuntimeVerifier.clickLabel(test, "Decrypt next to original");
        waitFor(() -> editable(test).size() == 1, 10000);
        MediaEditingRuntimeVerifier.setText(editable(test).get(0), password);
        MediaEditingRuntimeVerifier.clickLabel(test, "Decrypt");
    }
    private static void navigate(Instrumentation test, File root) throws Exception {
        Context context = test.getTargetContext();
        Intent reopen = new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        test.runOnMainSync(() -> context.startActivity(reopen));
        test.getUiAutomation().waitForIdle(250, 5000);
        TransferOptionsRuntimeVerifier.navigateToFixture(test, root);
    }
    private static List<AccessibilityNodeInfo> editable(Instrumentation test) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !test.getTargetContext().getPackageName().contentEquals(root.getPackageName())) continue;
            ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>(); pending.add(root);
            int scanned = 0;
            while (!pending.isEmpty() && scanned++ < 5000) {
                AccessibilityNodeInfo node = pending.removeFirst();
                if (node.isEditable() && node.isVisibleToUser()) result.add(node);
                for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) pending.add(child); }
            }
        }
        result.sort(Comparator.comparingInt(n -> { android.graphics.Rect bounds = new android.graphics.Rect(); n.getBoundsInScreen(bounds); return bounds.top; }));
        return result;
    }
    private static void waitFor(BooleanSupplier condition, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) { if (condition.getAsBoolean()) return; SystemClock.sleep(50); }
        throw new AssertionError("Vault UI condition timed out");
    }
    private static byte[] readSmall(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[1024]; int count;
        while ((count = input.read(bytes)) >= 0) { if (output.size() + count > 4096) throw new IOException("Fixture bound exceeded"); output.write(bytes, 0, count); }
        return output.toByteArray();
    }
    private static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count); }
        StringBuilder value = new StringBuilder(); for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString();
    }
    private static void removeOwned(File file) throws IOException {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles(); if (children == null) throw new IOException("Fixture cleanup listing failed");
            for (File child : children) removeOwned(child);
        }
        if (!file.delete()) throw new IOException("Fixture cleanup failed");
    }
}
