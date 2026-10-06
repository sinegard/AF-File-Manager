package com.affilemanager.app.transfer;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Black-box service/index checks. No app keep rules or test hooks in the shipping APK. */
public final class ReleaseShareIndexVerifier {
    private static final String SERVICE = "com.affilemanager.app.transfer.LanTransferService";
    private static final String START = "com.affilemanager.app.action.START_LAN_TRANSFER";
    // These are synthetic, emulator-only credentials, not a saved user connection.
    private static final String PASSWORD = "12345678";

    public static boolean verify(Instrumentation test) throws Exception {
        if (!android.os.Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"))
            throw new AssertionError("Disposable emulator required");
        Context context = test.getTargetContext();
        InetAddress address = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
            .flatMap(iface -> Collections.list(iface.getInetAddresses()).stream())
            .filter(ip -> ip instanceof Inet4Address && ip.isSiteLocalAddress()).findFirst()
            .orElseThrow(() -> new AssertionError("No private emulator interface"));
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AFReleaseIndex-" + UUID.randomUUID());
        if (!root.mkdir()) throw new AssertionError("Owned shared fixture directory unavailable");
        try {
            int port = freePort(address);
            start(test, root, address, port, "WEB", true);
            String first = login(address, port), second = login(address, port);
            interruptedUpload(address, port, first, root);
            String firstPeer = pairing(address, 23001, "NativeFirst");
            String secondPeer = pairing(address, 23002, "NativeSecond");
            expect(request(address, port, "POST", "/nearby/group/join", first, firstPeer.getBytes(StandardCharsets.UTF_8), ""), 200);
            expect(request(address, port, "POST", "/nearby/group/join", second, secondPeer.getBytes(StandardCharsets.UTF_8), ""), 200);
            byte[] bytes = "Owned release fixture".getBytes(StandardCharsets.UTF_8);
            expect(request(address, port, "POST", "/upload?name=received.txt", second, bytes, ""), 201);
            File received = new File(root, "received.txt");
            check(received.length() == bytes.length && "received.txt".equals(indexedName(context, received)), "Received file index");
            expect(request(address, port, "POST", "/nearby/disconnect", first, new byte[0], ""), 200);
            String remaining = request(address, port, "GET", "/nearby/group/members", second, new byte[0], "");
            expect(remaining, 200);
            check(remaining.contains("NativeSecond") && !remaining.contains("NativeFirst"), "Member-only disconnect");
            Intent remove = new Intent().setClassName(context, SERVICE)
                .setAction("com.affilemanager.app.action.REMOVE_GROUP_MEMBER").putExtra("member_pairing", secondPeer);
            test.runOnMainSync(() -> context.startService(remove));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            String revoked;
            do {
                revoked = request(address, port, "GET", "/nearby/group/members", second, new byte[0], "");
                if (revoked.startsWith("HTTP/1.1 403")) break;
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < deadline);
            expect(revoked, 403);
            String denied = request(address, port, "POST", "/upload?name=blocked.txt", second, new byte[]{1}, "");
            expect(denied, 403);
            check(denied.contains("X-AF-Group-State: removed") && !new File(root, "blocked.txt").exists(), "Revoked upload denial");
            stop(test);
            awaitStopped(address, port);

            port = freePort(address);
            start(test, root, address, port, "WEBDAV", false);
            String auth = "Authorization: Basic " + android.util.Base64.encodeToString(
                ("af:" + PASSWORD).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP) + "\r\n";
            expect(request(address, port, "PUT", "/dav.txt", null, bytes, auth), 201);
            check("dav.txt".equals(indexedName(context, new File(root, "dav.txt"))), "WebDAV receive index");
            expect(request(address, port, "COPY", "/dav.txt", null, new byte[0], auth + destination(address, port, "copy.txt")), 201);
            check("copy.txt".equals(indexedName(context, new File(root, "copy.txt"))) && new File(root, "dav.txt").isFile(), "Copy preserves original and index");
            expect(request(address, port, "MOVE", "/copy.txt", null, new byte[0], auth + destination(address, port, "moved.txt")), 201);
            check(!new File(root, "copy.txt").exists() && indexedName(context, new File(root, "copy.txt")) == null &&
                "moved.txt".equals(indexedName(context, new File(root, "moved.txt"))), "Move removes only verified source");
            String overlap = request(address, port, "COPY", "/dav.txt", null, new byte[0], auth + destination(address, port, "dav.txt"));
            check(overlap.startsWith("HTTP/1.1 400") && new File(root, "dav.txt").length() == bytes.length, "Overlapping destination rejection");
            expect(request(address, port, "DELETE", "/moved.txt", null, new byte[0], auth), 204);
            check(!new File(root, "moved.txt").exists() && indexedName(context, new File(root, "moved.txt")) == null, "Deletion index");

            File apk = new File(root, "original.apk");
            File installed = new File(context.getApplicationInfo().sourceDir);
            try (InputStream input = new FileInputStream(installed); OutputStream output = new FileOutputStream(apk)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
            }
            ContentValues row = new ContentValues();
            row.put(MediaStore.MediaColumns.DATA, apk.getAbsolutePath());
            row.put(MediaStore.MediaColumns.DISPLAY_NAME, "stale-index-name.apk");
            row.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
            context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), row);
            expect(request(address, port, "MOVE", "/original.apk", null, new byte[0], auth + destination(address, port, "renamed.apk")), 201);
            File renamed = new File(root, "renamed.apk");
            check(indexedName(context, apk) == null && "renamed.apk".equals(indexedName(context, renamed)), "Real APK name publication");
            check(Arrays.equals(digest(installed), digest(renamed)), "Renaming does not edit APK bytes");
            expect(request(address, port, "PUT", "/unauthorized.txt", null, bytes, ""), 401);
            check(!new File(root, "unauthorized.txt").exists(), "Unauthenticated write denial");
            return true;
        } finally {
            stop(test);
            File[] children = root.listFiles();
            if (children == null || children.length > 32) throw new AssertionError("Unexpected fixture contents");
            for (File file : children) {
                if (!file.isFile() || !file.delete()) throw new AssertionError("Owned fixture cleanup failed");
                context.getContentResolver().delete(MediaStore.Files.getContentUri("external"),
                    MediaStore.MediaColumns.DATA + " = ?", new String[]{file.getAbsolutePath()});
            }
            if (!root.delete()) throw new AssertionError("Owned fixture directory cleanup failed");
        }
    }

    private static void interruptedUpload(InetAddress address, int port, String cookie, File root) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 5000);
            socket.setSoTimeout(10000);
            String head = "POST /upload?name=incomplete.txt HTTP/1.1\r\nHost: localhost\r\nCookie: " + cookie +
                "\r\nContent-Length: 100\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(new byte[]{1, 2, 3, 4});
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            for (int count; (count = socket.getInputStream().read(buffer)) >= 0;) {
                check(bytes.size() + count <= 4096, "Interrupted response bound");
                bytes.write(buffer, 0, count);
            }
            String response = bytes.toString("UTF-8");
            expect(response, 408);
            check(response.contains("X-AF-Error-Code: AF-XFER-CONNECTION") &&
                response.contains("X-AF-Error-Phase: read") && !response.contains(root.getAbsolutePath()),
                "Optimized receiver stable/private failure identifiers");
            File[] residue = root.listFiles();
            check(residue != null && !new File(root, "incomplete.txt").exists(), "Interrupted upload is not committed");
            for (File file : residue) check(!file.getName().endsWith(".partial"), "Interrupted staging cleanup");
        }
    }

    private static void start(Instrumentation test, File root, InetAddress address, int port, String protocol, boolean group) throws Exception {
        Context context = test.getTargetContext();
        Intent intent = new Intent().setClassName(context, SERVICE).setAction(START)
            .putExtra("root", root.getAbsolutePath()).putExtra("port", port).putExtra("protocol", protocol)
            .putExtra("username", "af").putExtra("password", PASSWORD).putExtra("duration_minutes", 15)
            .putExtra("bind_address", address.getHostAddress()).putExtra("group_mode", group)
            .putExtra("receiver_name", "NativeOrganizer").putExtra("group_name", "NativeFixtureGroup");
        test.runOnMainSync(() -> context.startForegroundService(intent));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            try (Socket ignored = new Socket(address, port)) { return; }
            catch (IOException notReady) { SystemClock.sleep(50); }
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Native receiving service did not start");
    }
    private static void stop(Instrumentation test) {
        test.runOnMainSync(() -> test.getTargetContext().stopService(new Intent().setClassName(test.getTargetContext(), SERVICE)));
    }
    private static int freePort(InetAddress address) throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, address)) { return server.getLocalPort(); }
    }
    private static void awaitStopped(InetAddress address, int port) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            try (Socket ignored = new Socket(address, port)) { SystemClock.sleep(50); }
            catch (IOException stopped) { return; }
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Previous native receiving session did not stop");
    }
    private static String pairing(InetAddress address, int port, String name) {
        return "af-file-manager://receive?host=" + address.getHostAddress() + "&port=" + (port <= 65535 ? port : 23001) + "&code=23456789&name=" + name;
    }
    private static String destination(InetAddress address, int port, String name) {
        return "Destination: http://" + address.getHostAddress() + ":" + port + "/" + name + "\r\n";
    }
    private static String login(InetAddress address, int port) throws Exception {
        String response = request(address, port, "POST", "/login", null, ("code=" + PASSWORD).getBytes(StandardCharsets.UTF_8), "");
        expect(response, 200);
        for (String header : response.split("\r\n")) if (header.startsWith("Set-Cookie:")) return header.substring(11).split(";", 2)[0].trim();
        throw new AssertionError("No native session cookie");
    }
    private static String request(InetAddress address, int port, String method, String path, String cookie, byte[] body, String headers) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 5000); socket.setSoTimeout(45000);
            String head = method + " " + path + " HTTP/1.1\r\nHost: " + address.getHostAddress() + ":" + port + "\r\n" +
                "Connection: close\r\nContent-Length: " + body.length + "\r\n" + headers + (cookie == null ? "" : "Cookie: " + cookie + "\r\n") + "\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().write(body); socket.getOutputStream().flush();
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
            for (int count; (count = socket.getInputStream().read(buffer)) >= 0;) {
                if (output.size() + count > 128 * 1024) throw new AssertionError("Fixture response bound exceeded");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
    private static String indexedName(Context context, File file) {
        try (android.database.Cursor cursor = context.getContentResolver().query(MediaStore.Files.getContentUri("external"),
            new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, MediaStore.MediaColumns.DATA + " = ?", new String[]{file.getAbsolutePath()}, null)) {
            if (cursor == null) throw new AssertionError("Provider query unavailable");
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }
    private static byte[] digest(File file) throws Exception {
        java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[64 * 1024]; for (int count; (count = input.read(buffer)) >= 0;) hash.update(buffer, 0, count); }
        return hash.digest();
    }
    private static void expect(String response, int status) { check(response.startsWith("HTTP/1.1 " + status + " "), "Unexpected native HTTP status, expected " + status + ", received " + response.substring(0, Math.min(20, response.length()))); }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
