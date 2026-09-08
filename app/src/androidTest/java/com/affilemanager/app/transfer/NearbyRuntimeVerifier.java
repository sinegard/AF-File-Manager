package com.affilemanager.app.transfer;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real optimized sending service against a bounded, emulator-private HTTP peer. */
public final class NearbyRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        if (!android.os.Build.MODEL.contains("sdk")) throw new AssertionError("Disposable emulator required");
        Context context = test.getTargetContext();
        InetAddress address = null;
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces()))
            for (InetAddress candidate : Collections.list(iface.getInetAddresses()))
                if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) address = candidate;
        if (address == null) throw new AssertionError("No private emulator interface");
        File root = new File(context.getCacheDir(), "optimized-nearby-" + UUID.randomUUID());
        if (!root.mkdir()) throw new AssertionError("Fixture directory creation failed");
        File source = new File(root, "source.txt");
        byte[] expected = "AF private fixture bytes".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(source)) { output.write(expected); }
        AtomicInteger logins = new AtomicInteger(), uploads = new AtomicInteger();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Set<String> batches = Collections.synchronizedSet(new HashSet<>());
        try (ServerSocket server = new ServerSocket(0, 2, address)) {
            server.setSoTimeout(500);
            Thread receiver = new Thread(() -> {
                long deadline = SystemClock.elapsedRealtime() + 30000;
                try {
                    while (!server.isClosed() && uploads.get() < 2 && SystemClock.elapsedRealtime() < deadline) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(5000);
                            InputStream input = socket.getInputStream();
                            String start = line(input);
                            Map<String, String> headers = new HashMap<>();
                            String header;
                            while (!(header = line(input)).isEmpty()) {
                                if (headers.size() >= 30 || !header.contains(":")) throw new AssertionError("Invalid fixture request");
                                headers.put(header.substring(0, header.indexOf(':')).toLowerCase(Locale.ROOT), header.substring(header.indexOf(':') + 1).trim());
                            }
                            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                            if (length < 0 || length > 16384) throw new AssertionError("Fixture request too large");
                            byte[] body = new byte[length];
                            new DataInputStream(input).readFully(body);
                            String extra = "";
                            boolean uploaded = false;
                            if (start.startsWith("POST /login ")) {
                                if (logins.incrementAndGet() != 1) throw new AssertionError("Consumed one-time code was used again");
                                extra = "Set-Cookie: af_session=fixture-only; HttpOnly; Path=/\r\nX-AF-Queue-Version: 1\r\nX-AF-Session-Expires: " + (System.currentTimeMillis() + 60000) + "\r\n";
                            } else {
                                if (!"af_session=fixture-only".equals(headers.get("cookie"))) throw new AssertionError("Missing session cookie");
                                String batch = headers.get("x-af-batch-id");
                                if (start.startsWith("POST /nearby/manifest ")) {
                                    if (batch == null || !batches.add(batch)) throw new AssertionError("New batch must have its own identity");
                                } else if (start.startsWith("POST /upload?")) {
                                    if (!batches.contains(batch) || !Arrays.equals(expected, body)) throw new AssertionError("Upload bytes or batch mismatch");
                                    uploaded = true;
                                } else if (!start.startsWith("POST /nearby/peer ")) throw new AssertionError("Unexpected request route");
                            }
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 2\r\n" + extra + "\r\nOK").getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                            if (uploaded) uploads.incrementAndGet();
                        } catch (SocketTimeoutException expectedTimeout) { /* Bounded listener lifetime. */ }
                    }
                } catch (Throwable error) { if (!server.isClosed()) failed.set(error); }
            }, "AF-owned-native-transfer-fixture");
            receiver.start();
            try {
                String pairing = "af-file-manager://receive?host=" + address.getHostAddress() + "&port=" + server.getLocalPort() + "&code=12345678&name=NativeFixture";
                for (int batch = 1; batch <= 2; batch++) {
                    // Exercise the same share -> preview -> explicit Start path as a user.
                    // No unshrunk test API or stale service payload contract in the release APK.
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".files", source);
                    Intent command = new Intent(Intent.ACTION_SEND).setClassName(context, "com.affilemanager.app.MainActivity")
                        .setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    test.runOnMainSync(() -> context.startActivity(command));
                    awaitNode(test, "prepared files", node -> "Ready to send: 1".contentEquals(node.getText() == null ? "" : node.getText()));
                    if (batch == 1) {
                        AccessibilityNodeInfo field = awaitNode(test, "pairing input", node -> node.isEditable() &&
                            "android.widget.EditText".contentEquals(node.getClassName()));
                        android.os.Bundle text = new android.os.Bundle();
                        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pairing);
                        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) throw new AssertionError("Pairing field rejected input");
                    }
                    AccessibilityNodeInfo label = awaitNode(test, "Start button", node -> "Start transfer".contentEquals(node.getText() == null ? "" : node.getText()) &&
                        clickableAncestor(node) != null && clickableAncestor(node).isEnabled());
                    AccessibilityNodeInfo start = clickableAncestor(label);
                    if (!start.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Explicit Start action unavailable");
                    long deadline = SystemClock.elapsedRealtime() + 10000;
                    while (failed.get() == null && (uploads.get() < batch || running(context)) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                    if (failed.get() != null) throw new AssertionError("Native fixture failed", failed.get());
                    if (uploads.get() != batch || running(context)) throw new AssertionError("Native transfer did not finish");
                }
                if (logins.get() != 1 || batches.size() != 2 || !source.isFile()) throw new AssertionError("Repeated-send contract failed");
                return true;
            } finally {
                server.close(); receiver.join(2000);
                if (receiver.isAlive()) throw new AssertionError("Fixture worker leaked");
            }
        } finally {
            test.runOnMainSync(() -> context.stopService(new Intent().setClassName(context, "com.affilemanager.app.transfer.NearbyTransferService")));
            test.runOnMainSync(() -> context.startService(new Intent().setClassName(context, "com.affilemanager.app.transfer.LanTransferService")
                .setAction("com.affilemanager.app.action.STOP_LAN_TRANSFER")));
            if (!source.delete() || !root.delete()) throw new AssertionError("Transfer fixture cleanup failed");
        }
    }
    @SuppressWarnings("deprecation") private static boolean running(Context context) {
        for (ActivityManager.RunningServiceInfo service : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (service.service.getClassName().equals("com.affilemanager.app.transfer.NearbyTransferService")) return true;
        return false;
    }
    private interface NodeMatch { boolean matches(AccessibilityNodeInfo node); }
    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 5; depth++, node = node.getParent())
            if (node.isClickable()) return node;
        return null;
    }
    private static AccessibilityNodeInfo awaitNode(Instrumentation test, String description, NodeMatch match) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        List<String> observed = new ArrayList<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            observed.clear();
            ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            if (root != null) nodes.add(root);
            int count = 0;
            while (!nodes.isEmpty() && count++ < 2000) {
                AccessibilityNodeInfo node = nodes.removeFirst();
                if (node.isVisibleToUser() && match.matches(node)) return node;
                if (node.isVisibleToUser() && observed.size() < 80)
                    observed.add(node.getClassName() + " / " + node.getText() + " / " + node.getViewIdResourceName());
                for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) nodes.add(child); }
            }
            SystemClock.sleep(50);
        }
        android.graphics.Bitmap screenshot = test.getUiAutomation().takeScreenshot();
        if (screenshot != null) {
            File evidence = new File(test.getTargetContext().getExternalFilesDir("validation"), "optimized-nearby-control.png");
            try (FileOutputStream output = new FileOutputStream(evidence)) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); }
            finally { screenshot.recycle(); }
        }
        throw new AssertionError("Optimized " + description + " unavailable. Observed: " + observed);
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 8192) {
            int value = input.read();
            if (value < 0) throw new EOFException();
            if (value == '\n') return bytes.toString("US-ASCII").replace("\r", "");
            bytes.write(value);
        }
        throw new IOException("Fixture header too large");
    }
}
