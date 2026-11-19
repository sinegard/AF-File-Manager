package com.affilemanager.app.transfer;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** Black-box owner UI and real non-exported service, also against the normally minified APK. */
public final class ReceiverConsentRuntimeVerifier {
    private static final String SERVICE = "com.affilemanager.app.transfer.LanTransferService";
    private static final String SECRET = "owned-consent-fixture";
    private static final class Reply {
        final int code; final String ticket, cookie;
        Reply(int status, String request, String accepted) { code=status; ticket=request; cookie=accepted; }
        public String toString() { return "ConsentHttpResult(" + code + ")"; }
    }
    public static boolean verify(Instrumentation test, String prefix) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator only");
        Context context = test.getTargetContext();
        if (context.getPackageManager().getServiceInfo(new ComponentName(context, SERVICE), 0).exported)
            throw new AssertionError("The local consent service must not be exported");
        File root = new File(context.getCacheDir(), "receiver-consent-" + System.nanoTime());
        if (!root.mkdir()) throw new AssertionError("Owned fixture unavailable");
        File output = new File(context.getExternalFilesDir("validation"), "runs/" + prefix + "/receiver-consent");
        if (output.exists() || !output.mkdirs()) throw new AssertionError("Preserve prior evidence");
        android.accessibilityservice.AccessibilityServiceInfo accessibility = test.getUiAutomation().getServiceInfo();
        accessibility.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        accessibility.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        test.getUiAutomation().setServiceInfo(accessibility);
        InetAddress address = null;
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces()))
            for (InetAddress ip : Collections.list(iface.getInetAddresses()))
                if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) address = ip;
        if (address == null) throw new AssertionError("No private test interface");
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1, address)) { port = reserved.getLocalPort(); }
        String origin = "http://" + address.getHostAddress() + ":" + port;
        Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        test.runOnMainSync(() -> context.startActivity(launch));
        String stage = "start";
        boolean passed = false;
        try {
            start(test, root, port, address.getHostAddress());
            waitHttp(origin);
            stage = "no-consent";
            Reply pending = login(origin, "Owned sender 1");
            need(pending.code == 202 && pending.cookie == null && pending.ticket != null, "Login must only request consent");
            await(test, n -> "Owned sender 1".contentEquals(n.getText() == null ? "" : n.getText()));
            await(test, n -> tag(n, "nearby_sender_allow") || "Confirm".contentEquals(n.getText() == null ? "" : n.getText()));
            for (String fake : new String[]{null, "af_session=fabricated", "af_session=" + pending.ticket}) {
                for (String path : new String[]{"/upload?name=unexpected.txt", "/mkdir?path=unexpected", "/nearby/manifest", "/nearby/peer", "/nearby/message", "/nearby/approve"})
                    need(http(origin, path, "unapproved".getBytes(StandardCharsets.UTF_8), fake, null, false).code == 401, "Unapproved request must fail");
            }
            need(root.list() != null && root.list().length == 0, "No final or temporary file may exist before consent");
            capture(test, new File(output, "pending.png"));
            stage = "back-rejects";
            need(test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), "Back rejected");
            waitStatus(origin, pending.ticket, 403);
            Reply denied = login(origin, "Owned sender 2");
            need(denied.code == 202, "Fresh request unavailable");
            await(test, n -> "Owned sender 2".contentEquals(n.getText() == null ? "" : n.getText()));
            click(test, "nearby_sender_deny");
            waitStatus(origin, denied.ticket, 403);
            need(root.list().length == 0, "Rejected sender wrote a file");
            stage = "owner-approves";
            Reply next = login(origin, "Owned sender 3");
            need(next.code == 202, "Third request unavailable");
            await(test, n -> "Owned sender 3".contentEquals(n.getText() == null ? "" : n.getText()));
            click(test, "nearby_sender_allow");
            Reply accepted = waitStatus(origin, next.ticket, 200);
            need(accepted.cookie != null, "Explicit approval did not grant a session");
            byte[] expected = "exact bytes after explicit owner approval".getBytes(StandardCharsets.UTF_8);
            need(http(origin, "/upload?name=received.txt", expected, accepted.cookie, null, false).code == 201, "Approved upload failed");
            File received = new File(root, "received.txt");
            need(Arrays.equals(expected, java.nio.file.Files.readAllBytes(received.toPath())), "Approved payload differs");
            for (String path : new String[]{"/", "/list", "/download?path=received.txt"})
                need(http(origin, path, null, accepted.cookie, null, false).code == 403, "Incoming consent must not disclose old files");
            stage = "stop-restart";
            stop(context);
            SystemClock.sleep(750);
            // Phone receiving uses a fresh ephemeral port. Rebinding the fixture's
            // fixed old port can fail in TIME_WAIT and does not test consent at all.
            try (ServerSocket reserved = new ServerSocket(0, 1, address)) { port = reserved.getLocalPort(); }
            origin = "http://" + address.getHostAddress() + ":" + port;
            start(test, root, port, address.getHostAddress());
            waitHttp(origin);
            need(http(origin, "/upload?name=stale.txt", expected, accepted.cookie, null, false).code == 401, "Old grant survived restart");
            need(http(origin, "/nearby/approval", new byte[0], null, next.ticket, false).code == 410, "Old pending token survived restart");
            need(root.list().length == 1, "Restart accepted an unapproved file");
            passed = true;
            return true;
        } finally {
            if (!passed) capture(test, new File(output, "failure.png"));
            stop(context);
            try (Writer record = new OutputStreamWriter(new FileOutputStream(new File(output, "result.json")), StandardCharsets.UTF_8)) {
                record.write("{\"passed\":" + passed + ",\"lastStage\":\"" + stage + "\",\"phoneTouched\":false,\"credentialsRecorded\":false}");
            }
            for (File entry : Objects.requireNonNull(root.listFiles())) {
                if (!entry.isFile() || !entry.getName().equals("received.txt") || !entry.delete()) throw new AssertionError("Unexpected fixture result retained");
            }
            need(root.delete(), "Owned fixture cleanup failed");
        }
    }
    private static void start(Instrumentation test, File root, int port, String address) {
        Context context = test.getTargetContext();
        Intent start = new Intent().setClassName(context, SERVICE).setAction("com.affilemanager.app.action.START_LAN_TRANSFER")
            .putExtra("root", root.getPath()).putExtra("duration_minutes", 15).putExtra("protocol", "WEB")
            .putExtra("port", port).putExtra("password", SECRET).putExtra("phone_receiver", true)
            .putExtra("bind_address", address).putStringArrayListExtra("receive_allowed_roots", new ArrayList<>(Collections.singletonList(root.getPath())));
        test.runOnMainSync(() -> context.startForegroundService(start));
    }
    private static void stop(Context context) { context.startService(new Intent().setClassName(context, SERVICE).setAction("com.affilemanager.app.action.STOP_LAN_TRANSFER")); }
    private static Reply login(String origin, String name) throws Exception {
        String form = "code=" + SECRET + "&client=" + UUID.randomUUID().toString().replace("-", "") + "&name=" + URLEncoder.encode(name, "UTF-8");
        return http(origin, "/login", form.getBytes(StandardCharsets.UTF_8), null, null, true);
    }
    private static Reply http(String origin, String path, byte[] body, String cookie, String ticket, boolean modern) throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL(origin + path).openConnection();
        request.setConnectTimeout(2000); request.setReadTimeout(3000); request.setInstanceFollowRedirects(false);
        if (cookie != null) request.setRequestProperty("Cookie", cookie);
        if (ticket != null) request.setRequestProperty("X-AF-Approval-Request", ticket);
        if (modern) request.setRequestProperty("X-AF-Sender-Approval", "1");
        try {
            if (body != null) { request.setRequestMethod("POST"); request.setDoOutput(true); request.setFixedLengthStreamingMode(body.length); request.getOutputStream().write(body); }
            int status = request.getResponseCode();
            String accepted = request.getHeaderField("Set-Cookie");
            return new Reply(status, request.getHeaderField("X-AF-Approval-Request"), accepted == null ? null : accepted.split(";", 2)[0]);
        } finally { request.disconnect(); }
    }
    private static void waitHttp(String origin) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        do { try { if (http(origin, "/", null, null, null, false).code == 401) return; } catch (IOException ignored) {} SystemClock.sleep(100); } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Phone receiver did not start");
    }
    private static Reply waitStatus(String origin, String ticket, int code) throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        do { Reply result = http(origin, "/nearby/approval", new byte[0], null, ticket, false); if (result.code == code) return result; SystemClock.sleep(100); } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Owner decision did not reach requester; expected status " + code);
    }
    private static boolean tag(AccessibilityNodeInfo node, String value) { return value.equals(node.getViewIdResourceName()) || (node.getViewIdResourceName() != null && node.getViewIdResourceName().endsWith(":id/" + value)); }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> condition) {
        if (node == null) return null; if (condition.test(node)) return node;
        for (int i=0; i<node.getChildCount(); i++) { AccessibilityNodeInfo result=find(node.getChild(i), condition); if (result != null) return result; } return null;
    }
    private static AccessibilityNodeInfo await(Instrumentation test, Predicate<AccessibilityNodeInfo> condition) {
        long end=SystemClock.elapsedRealtime()+15000;
        do {
            AccessibilityNodeInfo active=find(test.getUiAutomation().getRootInActiveWindow(), condition);
            if (active != null) return active;
            for (android.view.accessibility.AccessibilityWindowInfo window : test.getUiAutomation().getWindows()) { AccessibilityNodeInfo result=find(window.getRoot(), condition); if (result != null) return result; }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime()<end);
        throw new AssertionError("Owner consent control not shown");
    }
    private static void click(Instrumentation test, String tag) {
        String label = tag.endsWith("_deny") ? "Cancel" : "Confirm";
        AccessibilityNodeInfo node = await(test, n -> tag(n, tag) || label.contentEquals(n.getText() == null ? "" : n.getText()));
        for (int level=0; level<8 && node != null; level++, node=node.getParent())
            if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        throw new AssertionError("Consent click rejected");
    }
    private static void capture(Instrumentation test, File file) throws IOException {
        android.graphics.Bitmap image=test.getUiAutomation().takeScreenshot(); need(image != null, "Screenshot unavailable");
        try (OutputStream out=new FileOutputStream(file)) { need(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"Screenshot failed"); } finally { image.recycle(); }
    }
    private static void need(boolean value, String error) { if (!value) throw new AssertionError(error); }
}
