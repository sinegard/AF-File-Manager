package com.affilemanager.app.transfer;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
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
                                extra = "Set-Cookie: af_session=fixture-only; HttpOnly; Path=/\r\nX-AF-Session-Expires: " + (System.currentTimeMillis() + 60000) + "\r\n";
                            } else {
                                if (!"af_session=fixture-only".equals(headers.get("cookie"))) throw new AssertionError("Missing session cookie");
                                String batch = headers.get("x-af-batch-id");
                                if (start.startsWith("POST /nearby/manifest ")) {
                                    if (batch == null || !batches.add(batch)) throw new AssertionError("New batch must have its own identity");
                                } else if (start.startsWith("POST /upload?")) {
                                    if (!batches.contains(batch) || !Arrays.equals(expected, body)) throw new AssertionError("Upload bytes or batch mismatch");
                                    uploaded = true;
                                } else throw new AssertionError("Unexpected request route");
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
                    Intent command = new Intent().setClassName(context, "com.affilemanager.app.transfer.NearbyTransferService")
                        .setAction("com.affilemanager.app.action.START_NEARBY_TRANSFER").putExtra("pairing", pairing)
                        .putStringArrayListExtra("paths", new ArrayList<>(Collections.singletonList(source.getPath())))
                        .putStringArrayListExtra("relative_paths", new ArrayList<>(Collections.singletonList(source.getName())))
                        .putStringArrayListExtra("directories", new ArrayList<>());
                    test.runOnMainSync(() -> context.startForegroundService(command));
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
            if (!source.delete() || !root.delete()) throw new AssertionError("Transfer fixture cleanup failed");
        }
    }
    @SuppressWarnings("deprecation") private static boolean running(Context context) {
        for (ActivityManager.RunningServiceInfo service : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (service.service.getClassName().equals("com.affilemanager.app.transfer.NearbyTransferService")) return true;
        return false;
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
