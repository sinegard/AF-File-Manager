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

/** Black-box normal/error checks of the exact optimized APK; no shipping test hooks. */
public final class NearbyRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        if (!android.os.Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Disposable emulator required");
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
        AtomicInteger logins = new AtomicInteger(), uploads = new AtomicInteger(), cancellations = new AtomicInteger();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Set<String> batches = Collections.synchronizedSet(new HashSet<>());
        Map<String, Integer> order = new HashMap<>(), attempts = new HashMap<>();
        AtomicInteger acknowledged = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        long[] busyStartedAt = {0};
        try (ServerSocket server = new ServerSocket(0, 2, address)) {
            server.setSoTimeout(500);
            Thread receiver = new Thread(() -> {
                long deadline = SystemClock.elapsedRealtime() + 120000;
                try {
                    while (!server.isClosed() && !done.get() && SystemClock.elapsedRealtime() < deadline) {
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
                            String extra = "", status = "200 OK", responseBody = "OK";
                            boolean uploaded = false;
                            if (start.startsWith("POST /login ")) {
                                if (logins.incrementAndGet() != 1) throw new AssertionError("Consumed one-time code was used again");
                                extra = "Set-Cookie: af_session=fixture-only; HttpOnly; Path=/\r\nX-AF-Queue-Version: 1\r\nX-AF-Session-Expires: " + (System.currentTimeMillis() + 180000) + "\r\n";
                            } else {
                                if (!"af_session=fixture-only".equals(headers.get("cookie"))) throw new AssertionError("Missing session cookie");
                                String batch = headers.get("x-af-batch-id");
                                if (start.startsWith("POST /nearby/manifest ")) {
                                    if (batch == null || !batches.add(batch)) throw new AssertionError("New batch must have its own identity");
                                    order.put(batch, batches.size());
                                } else if (start.startsWith("POST /upload?")) {
                                    if (!batches.contains(batch) || !Arrays.equals(expected, body)) throw new AssertionError("Upload bytes or batch mismatch");
                                    uploaded = true;
                                    int attempt = attempts.getOrDefault(batch, 0) + 1;
                                    attempts.put(batch, attempt);
                                    int scenario = order.get(batch);
                                    if (scenario == 2 || scenario == 4 || scenario == 5 || (scenario == 3 && attempt == 1)) {
                                        status = "500 Server error";
                                        responseBody = "Server error";
                                        if (scenario == 4) extra = "X-AF-Error-Code: AF-XFER-SPACE\r\nX-AF-Error-Phase: write\r\n";
                                        if (scenario == 5) busyStartedAt[0] = SystemClock.elapsedRealtime();
                                    }
                                } else if (start.startsWith("GET /nearby/file-status")) {
                                    if (!batches.contains(batch)) throw new AssertionError("Status requested for another batch");
                                    int scenario = order.get(batch);
                                    responseBody = scenario == 3 || scenario == 4 ? "FAILED" :
                                        scenario == 5 && SystemClock.elapsedRealtime() - busyStartedAt[0] < 5000 ? "TRANSFERRING" : "COMPLETED";
                                    if (scenario == 4) extra = "X-AF-Error-Code: AF-XFER-SPACE\r\n";
                                    acknowledged.incrementAndGet();
                                } else if (start.startsWith("POST /nearby/cancel")) {
                                    cancellations.incrementAndGet();
                                } else if (!start.startsWith("POST /nearby/peer ")) throw new AssertionError("Unexpected request route");
                            }
                            socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: " +
                                responseBody.length() + "\r\n" + extra + "\r\n" + responseBody).getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                            if (uploaded) uploads.incrementAndGet();
                        } catch (SocketTimeoutException expectedTimeout) { /* Bounded listener lifetime. */ }
                    }
                } catch (Throwable error) { if (!server.isClosed()) failed.set(error); }
            }, "AF-owned-native-transfer-fixture");
            receiver.start();
            try {
                String pairing = "af-file-manager://receive?host=" + address.getHostAddress() + "&port=" + server.getLocalPort() + "&code=12345678&name=NativeFixture";
                int[] expectedUploads = {1, 2, 4, 5, 6};
                for (int batch = 1; batch <= expectedUploads.length; batch++) {
                    // Exercise the same share -> preview -> explicit Start path as a user.
                    // No unshrunk test API or stale service payload contract in the release APK.
                    android.net.Uri uri = cacheUri(context, source);
                    Intent command = new Intent(Intent.ACTION_SEND).setClassName(context, "com.affilemanager.app.MainActivity")
                        .setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    test.runOnMainSync(() -> context.startActivity(command));
                    awaitNode(test, "prepared files", node -> "Ready to send: 1".contentEquals(node.getText() == null ? "" : node.getText()));
                    AccessibilityNodeInfo field = findNode(test, node -> node.isEditable() &&
                        "android.widget.EditText".contentEquals(node.getClassName()));
                    if (field != null) {
                        android.os.Bundle text = new android.os.Bundle();
                        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pairing);
                        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) throw new AssertionError("Pairing field rejected input");
                        awaitNode(test, "entered pairing value", node -> node.isEditable() && textEquals(node, pairing));
                    } else if (batch == 1) throw new AssertionError("Initial pairing input unavailable");
                    AccessibilityNodeInfo label = awaitNode(test, "Start button", node -> "Start transfer".contentEquals(node.getText() == null ? "" : node.getText()) &&
                        clickableAncestor(node) != null && clickableAncestor(node).isEnabled());
                    AccessibilityNodeInfo start = clickableAncestor(label);
                    if (!start.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Explicit Start action unavailable");
                    long deadline = SystemClock.elapsedRealtime() + 20000;
                    int expectedCount = expectedUploads[batch - 1];
                    while (failed.get() == null && (uploads.get() < expectedCount || running(context)) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                    if (failed.get() != null) throw new AssertionError("Native fixture failed", failed.get());
                    if (uploads.get() != expectedCount || running(context)) throw new AssertionError("Native transfer did not finish scenario " + batch);
                    if (batch == 4) awaitNode(test, "stable storage error", node -> node.getText() != null && node.getText().toString().contains("AF-XFER-SPACE"));
                    if (cancellations.get() != (batch < 4 ? 0 : 1)) throw new AssertionError("Only the permanent failed batch may be cancelled");
                }
                if (logins.get() != 1 || batches.size() != 5 || uploads.get() != 6 || acknowledged.get() < 5 ||
                    cancellations.get() != 1 || !source.isFile() || !Arrays.equals(expected, java.nio.file.Files.readAllBytes(source.toPath())))
                    throw new AssertionError("Normal/recovered/permanent-error/busy transfer contract failed");
                return true;
            } finally {
                done.set(true);
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
    public static boolean verifyDiagnostics(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (!android.os.Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Disposable emulator required");
        File journal = new File(context.getFilesDir(), "transfer-diagnostics/events.log");
        if (context.getSharedPreferences("transfer_diagnostics", Context.MODE_PRIVATE).getBoolean("enabled", false) || journal.exists())
            throw new AssertionError("Diagnostics must remain off and unwritten before explicit opt-in");
        clickTag(test, "nav_share");
        click(test, scrolledNode(test, "sharing_list", "diagnostics launcher", node -> tag(node, "nearby_diagnostics")));
        awaitNode(test, "English diagnostics privacy label", node -> textEquals(node, "Data stays on this phone."));
        click(test, awaitNode(test, "diagnostics switch", node -> node.isCheckable() && node.isClickable()));
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!context.getSharedPreferences("transfer_diagnostics", Context.MODE_PRIVATE).getBoolean("enabled", false) &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        if (!context.getSharedPreferences("transfer_diagnostics", Context.MODE_PRIVATE).getBoolean("enabled", false))
            throw new AssertionError("Visible diagnostic opt-in was not saved");
        clickText(test, "Close");
        verify(test); // Real normal/error sending now records explicit stable codes under R8.
        if (!journal.isFile() || journal.length() > 128 * 1024) throw new AssertionError("Private diagnostic journal is missing or unbounded");
        String report = new String(java.nio.file.Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8);
        if (!report.contains("AF-XFER-SPACE") || !report.contains("send\trecover") || report.contains("source.txt") ||
            report.contains("fixture-only") || report.contains("12345678") || report.contains(context.getCacheDir().toString()))
            throw new AssertionError("Private diagnostic stable codes/privacy contract failed");
        // verify() stops its owned receiver in cleanup; finished progress may
        // already disappear. Diagnostics are independent of that progress row.
        click(test, scrolledNode(test, "sharing_list", "diagnostics launcher", node -> tag(node, "nearby_diagnostics")));
        clickText(test, "Export");
        awaitNode(test, "system export chooser", node -> node.getPackageName() != null &&
            !context.getPackageName().contentEquals(node.getPackageName()));
        test.getUiAutomation().waitForIdle(500, 5000);
        File exported = new File(context.getCacheDir(), "transfer-diagnostics/report.txt");
        if (!exported.isFile() || exported.length() > 129 * 1024) throw new AssertionError("Explicit export did not create bounded private report");
        android.net.Uri uri = cacheUri(context, exported);
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null || !new String(readBounded(input, 129 * 1024), StandardCharsets.UTF_8).contains("AF transfer diagnostics v1"))
                throw new AssertionError("Export FileProvider read failed");
        }
        back(test);
        awaitNode(test, "diagnostics after export chooser Back", node -> textEquals(node, "Data stays on this phone."));
        clickText(test, "Clear");
        deadline = SystemClock.elapsedRealtime() + 5000;
        while ((!journal.isFile() || journal.length() != 0 || exported.exists()) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        if (!journal.isFile() || journal.length() != 0 || exported.exists()) throw new AssertionError("Clear did not clear just private diagnostics");
        click(test, awaitNode(test, "diagnostics switch", node -> node.isCheckable() && node.isClickable()));
        deadline = SystemClock.elapsedRealtime() + 5000;
        while (context.getSharedPreferences("transfer_diagnostics", Context.MODE_PRIVATE).getBoolean("enabled", false) &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        if (context.getSharedPreferences("transfer_diagnostics", Context.MODE_PRIVATE).getBoolean("enabled", false))
            throw new AssertionError("Diagnostics could not be disabled");
        back(test);
        awaitNode(test, "diagnostics launcher after Back", node -> tag(node, "nearby_diagnostics"));
        return true;
    }
    public static boolean verifyLocales(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        if (!android.os.Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Disposable emulator required");
        for (String language : new String[]{"en", "lt", "de", "ar", "en"}) {
            clickTag(test, "nav_tools");
            scrollTop(test, "tools_list");
            if (findNode(test, node -> tag(node, "change_language")) == null) clickTag(test, "settings_section_appearance");
            clickTag(test, "change_language");
            // Dialog accessibility does not inherit testTagsAsResourceId from the activity.
            AccessibilityNodeInfo search = awaitNode(test, "language search", AccessibilityNodeInfo::isEditable);
            android.os.Bundle text = new android.os.Bundle();
            text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, language);
            if (!search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) throw new AssertionError("Language search rejected text");
            Locale selected = Locale.forLanguageTag(language);
            String nativeName = selected.getDisplayName(selected);
            click(test, awaitNode(test, "language option " + language, node -> node.getText() != null &&
                nativeName.equalsIgnoreCase(node.getText().toString()) && clickableAncestor(node) != null));
            SystemClock.sleep(400);
            String terminal = translated(context, language, "Terminal", "Terminalas");
            // This is the only Terminal label in Tools. Accessibility may flatten
            // its non-interactive card, so assert its visible translated text.
            scrolledNode(test, "tools_list", "Visible features terminal in " + language, node -> textEquals(node, terminal));
            clickTag(test, "nav_share");
            click(test, scrolledNode(test, "sharing_list", "diagnostics launcher", node -> tag(node, "nearby_diagnostics")));
            String privacy = translated(context, language, "Data stays on this phone.", "Duomenys lieka šiame telefone.");
            awaitNode(test, "diagnostics privacy in " + language, node -> textEquals(node, privacy));
            android.graphics.Bitmap screenshot = test.getUiAutomation().takeScreenshot();
            if (screenshot == null) throw new AssertionError("Exact APK screenshot unavailable");
            File evidence = new File(context.getExternalFilesDir("validation"), "release-diagnostics-" + language + ".png");
            try (FileOutputStream output = new FileOutputStream(evidence)) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); }
            finally { screenshot.recycle(); }
            back(test);
        }
        return true;
    }
    private static String translated(Context context, String language, String english, String lithuanian) throws Exception {
        if (language.equals("en")) return english;
        if (language.equals("lt")) return lithuanian;
        try (InputStream input = context.getAssets().open("i18n/" + language + ".json");
             InputStream index = context.getAssets().open("i18n/index.json")) {
            org.json.JSONArray keys = new org.json.JSONObject(new String(readBounded(index, 2 * 1024 * 1024), StandardCharsets.UTF_8)).getJSONArray("exact");
            org.json.JSONArray values = new org.json.JSONObject(new String(readBounded(input, 2 * 1024 * 1024), StandardCharsets.UTF_8)).getJSONArray("exact");
            if (keys.length() != values.length() || keys.length() > 5000) throw new AssertionError("Bounded translation catalog mismatch");
            for (int i = 0; i < keys.length(); i++) if (keys.getString(i).equals(english)) return values.getString(i);
            throw new AssertionError("Requested UI phrase missing from shipped catalog");
        }
    }
    private static AccessibilityNodeInfo scrolledNode(Instrumentation test, String list, String description, NodeMatch match) throws Exception {
        scrollTop(test, list);
        test.getUiAutomation().waitForIdle(250, 3000);
        for (int scroll = 0; scroll < 64; scroll++) {
            AccessibilityNodeInfo found = findNode(test, match);
            if (found != null) return found;
            AccessibilityNodeInfo container = awaitNode(test, list, node -> tag(node, list));
            scrollPartial(test, container);
            SystemClock.sleep(150);
            test.getUiAutomation().waitForIdle(250, 3000);
        }
        return awaitNode(test, description, match);
    }
    private static void scrollPartial(Instrumentation test, AccessibilityNodeInfo container) {
        // Full-viewport steps can skip labels at a viewport boundary with large fonts.
        android.graphics.Rect bounds = new android.graphics.Rect();
        container.getBoundsInScreen(bounds);
        if (bounds.height() < 100) throw new AssertionError("Scrollable fixture viewport unavailable");
        float x = bounds.exactCenterX(), start = bounds.top + bounds.height() * .70f;
        float end = bounds.top + bounds.height() * .40f;
        long down = SystemClock.uptimeMillis();
        injectTouch(test, down, android.view.MotionEvent.ACTION_DOWN, x, start);
        for (int step = 1; step <= 8; step++) {
            SystemClock.sleep(20);
            injectTouch(test, down, android.view.MotionEvent.ACTION_MOVE, x, start + (end - start) * step / 8);
        }
        SystemClock.sleep(120); // Stop at the chosen position instead of flinging past controls.
        injectTouch(test, down, android.view.MotionEvent.ACTION_MOVE, x, end);
        injectTouch(test, down, android.view.MotionEvent.ACTION_UP, x, end);
    }
    private static void injectTouch(Instrumentation test, long down, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!test.getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Fixture scroll rejected"); }
        finally { event.recycle(); }
    }
    private static void scrollTop(Instrumentation test, String list) throws Exception {
        AccessibilityNodeInfo container = awaitNode(test, list, node -> tag(node, list));
        for (int scroll = 0; scroll < 16; scroll++) {
            if (!container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return;
            SystemClock.sleep(150);
            container = awaitNode(test, list, node -> tag(node, list));
        }
    }
    private static AccessibilityNodeInfo findNode(Instrumentation test, NodeMatch match) {
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        AccessibilityNodeInfo root = activeRoot(test);
        if (root != null) nodes.add(root);
        int count = 0;
        while (!nodes.isEmpty() && count++ < 2000) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            if (node.isVisibleToUser() && match.matches(node) && node.refresh() && node.isVisibleToUser() && match.matches(node)) return node;
            for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) nodes.add(child); }
        }
        return null;
    }
    private static AccessibilityNodeInfo activeRoot(Instrumentation test) {
        android.app.UiAutomation automation = test.getUiAutomation();
        AccessibilityNodeInfo active = automation.getRootInActiveWindow();
        if (active != null && active.isVisibleToUser() && active.getChildCount() > 0) return active;
        android.accessibilityservice.AccessibilityServiceInfo service = automation.getServiceInfo();
        if ((service.flags & android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) return active;
        // On API26 the active-window shortcut can be empty after a system chooser
        // returns to a dialog. Query the actual top application window instead.
        AccessibilityNodeInfo top = null;
        int layer = Integer.MIN_VALUE;
        for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getLayer() > layer) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) { top = root; layer = window.getLayer(); }
            }
        }
        return top != null ? top : active;
    }
    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) >= 0;) {
            if (bytes.size() + count > maximum) throw new IOException("Export exceeds bound");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    private static void clickText(Instrumentation test, String text) throws Exception {
        click(test, awaitNode(test, text, node -> textEquals(node, text) && clickableAncestor(node) != null));
    }
    private static boolean textEquals(AccessibilityNodeInfo node, String expected) {
        CharSequence actual = node.getText();
        return actual != null && expected.contentEquals(actual);
    }
    private static android.net.Uri cacheUri(Context context, File file) throws IOException {
        // Public FileProvider contract, independent of the target APK's method mapping.
        String prefix = context.getCacheDir().getCanonicalPath() + File.separator;
        String canonical = file.getCanonicalPath();
        if (!canonical.startsWith(prefix)) throw new AssertionError("Fixture must remain in private cache");
        android.net.Uri.Builder uri = new android.net.Uri.Builder().scheme("content")
            .authority(context.getPackageName() + ".files").appendPath("app_cache");
        for (String segment : canonical.substring(prefix.length()).split("/")) uri.appendPath(segment);
        return uri.build();
    }
    private static void clickTag(Instrumentation test, String id) throws Exception {
        click(test, awaitNode(test, id, node -> tag(node, id) && clickableAncestor(node) != null));
    }
    private static boolean tag(AccessibilityNodeInfo node, String id) {
        String value = node.getViewIdResourceName();
        return value != null && (value.equals(id) || value.endsWith("/" + id));
    }
    private static void click(Instrumentation test, AccessibilityNodeInfo node) throws IOException {
        String originalId = node.getViewIdResourceName();
        AccessibilityNodeInfo button = clickableAncestor(node);
        if (button == null || !button.refresh() || !button.isVisibleToUser() || !button.isEnabled() ||
            !button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            captureFailure(test);
            throw new AssertionError("Visible action rejected click: " + originalId);
        }
        SystemClock.sleep(150);
    }
    private static void back(Instrumentation test) {
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(service);
        long down = SystemClock.uptimeMillis();
        test.getUiAutomation().injectInputEvent(new android.view.KeyEvent(down, down,
            android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK, 0), true);
        SystemClock.sleep(50);
        test.getUiAutomation().injectInputEvent(new android.view.KeyEvent(down, SystemClock.uptimeMillis(),
            android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK, 0), true);
        SystemClock.sleep(200);
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
        long refreshAfter = SystemClock.elapsedRealtime() + 700;
        boolean refreshed = false;
        List<String> observed = new ArrayList<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            observed.clear();
            ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
            AccessibilityNodeInfo root = activeRoot(test);
            if (root != null) nodes.add(root);
            int count = 0;
            while (!nodes.isEmpty() && count++ < 2000) {
                AccessibilityNodeInfo node = nodes.removeFirst();
                if (node.isVisibleToUser() && match.matches(node) && node.refresh() && node.isVisibleToUser() && match.matches(node)) return node;
                if (node.isVisibleToUser() && observed.size() < 80)
                    observed.add(node.getClassName() + " / " + node.getText() + " / " + node.getViewIdResourceName());
                for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) nodes.add(child); }
            }
            if (!refreshed && observed.isEmpty() && SystemClock.elapsedRealtime() >= refreshAfter) {
                // Reconnect the test's accessibility client once after a window
                // transition; API26 can retain an empty stale active root.
                test.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
                test.getUiAutomation();
                refreshed = true;
            }
            SystemClock.sleep(50);
        }
        captureFailure(test);
        throw new AssertionError("Optimized " + description + " unavailable. Observed: " + observed);
    }
    private static void captureFailure(Instrumentation test) throws IOException {
        android.graphics.Bitmap screenshot = test.getUiAutomation().takeScreenshot();
        if (screenshot != null) {
            File evidence = new File(test.getTargetContext().getExternalFilesDir("validation"), "optimized-nearby-control.png");
            try (FileOutputStream output = new FileOutputStream(evidence)) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); }
            finally { screenshot.recycle(); }
        }
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
