package com.affilemanager.app.transfer;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.BrowserIssuesRuntimeVerifier;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Actual AF controls against a normal APK. No shipping classes or optimizer exemptions. */
public final class SharingControlsRuntimeVerifier {
    public static boolean verify(Instrumentation test, String attempt) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        if (attempt == null) attempt = "sharing-controls-" + System.nanoTime();
        if (!attempt.matches("[a-z0-9-]{1,80}")) throw new AssertionError("Invalid evidence prefix");
        Context context = test.getTargetContext();
        android.accessibilityservice.AccessibilityServiceInfo accessibility = test.getUiAutomation().getServiceInfo();
        accessibility.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(accessibility);
        File output = new File(context.getExternalFilesDir("validation"), "runs/" + attempt + "/sharing-controls");
        if (output.exists() || !output.mkdirs()) throw new AssertionError("Evidence must not be replaced");
        File root = new File(Environment.getExternalStorageDirectory(), "AFSharingControls-" + System.nanoTime());
        if (!root.mkdir()) throw new AssertionError("Owned receiving fixture unavailable");
        File sentinel = new File(root, "sentinel.txt");
        try (OutputStream stream = new FileOutputStream(sentinel)) { stream.write("unchanged fixture".getBytes(StandardCharsets.UTF_8)); }
        SharedPreferences preferences = context.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE);
        String original = preferences.getString("share", null);
        ClipboardManager[] clipboardService = {null};
        ClipData[] originalClipboard = {null};
        test.runOnMainSync(() -> {
            clipboardService[0] = context.getSystemService(ClipboardManager.class);
            originalClipboard[0] = clipboardService[0].getPrimaryClip();
        });
        ClipboardManager clipboard = clipboardService[0];
        ClipData originalClip = originalClipboard[0];
        Bitmap qr = null;
        boolean success = false;
        boolean groupStarted = false;
        String qrDigest = null;
        String stage = "start";
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            test.runOnMainSync(() -> context.startActivity(launch));
            BrowserIssuesRuntimeVerifier.tapStaticLabel(test, "Share");
            stage = "sending-controls";
            click(test, await(test, n -> tag(n, "nearby_main_send"), 10000));
            click(test, await(test, n -> tag(n, "nearby_category_Nuotraukos"), 10000));
            click(test, await(test, n -> tag(n, "nearby_storage_scope"), 10000));
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Files · Internal storage");
            click(test, await(test, n -> tag(n, "nearby_storage_scope"), 10000));
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Folders · Internal storage");
            AccessibilityNodeInfo open = await(test, n -> tag(n, "nearby_open_storage"), 10000);
            if (!open.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) throw new AssertionError("Storage long press rejected");
            await(test, n -> n.getPackageName() != null && n.getPackageName().toString().endsWith(".documentsui"), 10000);
            back(test);
            await(test, n -> tag(n, "nearby_storage_scope"), 10000);
            click(test, await(test, n -> tag(n, "nearby_open_storage"), 10000));
            await(test, n -> tag(n, "local_upload_dialog"), 10000);
            back(test);
            await(test, n -> tag(n, "nearby_storage_scope"), 10000);
            back(test);

            stage = "receiving-folder";
            click(test, await(test, n -> tag(n, "nearby_main_receive"), 10000));
            click(test, await(test, n -> tag(n, "nearby_receive_path"), 10000));
            MediaEditingRuntimeVerifier.clickLabel(test, "Change save location");
            for (int level = 0; level < 65; level++) {
                AccessibilityNodeInfo own = find(test, n -> text(n, root.getName()));
                if (own != null) { click(test, own); break; }
                if (level == 64) throw new AssertionError("Owned receiving folder was not reachable");
                AccessibilityNodeInfo up = await(test, n -> tag(n, "share_folder_up") && n.isEnabled(), 5000);
                own = find(test, n -> text(n, root.getName()));
                if (own != null) { click(test, own); break; }
                click(test, up);
                SystemClock.sleep(150);
            }
            MediaEditingRuntimeVerifier.clickLabel(test, "Share this folder");
            click(test, await(test, n -> tag(n, "nearby_main_receive"), 10000));
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Create type folders");
            MediaEditingRuntimeVerifier.clickLabel(test, "Cancel");
            if (root.list() == null || root.list().length != 1) throw new AssertionError("Cancelling the preview created folders");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Create type folders");
            MediaEditingRuntimeVerifier.clickLabel(test, "Create");
            String[] kinds = {"IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "ARCHIVE", "APK"};
            String[] names = {"Images", "Videos", "Music", "Documents", "Archives", "Apps"};
            long deadline = SystemClock.uptimeMillis() + 15000;
            boolean persisted = false;
            do {
                JSONObject share = new JSONObject(preferences.getString("share", "{}"));
                JSONObject paths = share.optJSONObject("nearbyTypePaths");
                persisted = paths != null && paths.length() == 6;
                for (int index = 0; index < 6 && persisted; index++) {
                    File directory = new File(root, names[index]);
                    persisted = directory.isDirectory() && directory.getCanonicalPath().equals(paths.optString(kinds[index]));
                }
                if (!persisted) SystemClock.sleep(50);
            } while (!persisted && SystemClock.uptimeMillis() < deadline);
            if (!persisted) throw new AssertionError("The six receiving routes were not persisted by AF");
            if (!"unchanged fixture".equals(new String(java.nio.file.Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8)))
                throw new AssertionError("Folder setup changed an existing file");
            back(test);

            stage = "system-wifi";
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Open Wi-Fi and hotspot settings");
            MediaEditingRuntimeVerifier.clickLabel(test, "Open Wi-Fi settings");
            await(test, n -> "com.android.settings".contentEquals(n.getPackageName() == null ? "" : n.getPackageName()), 10000);
            test.getUiAutomation().waitForIdle(500, 5000);
            capture(test, new File(output, "system-wifi.png"));
            back(test);

            stage = "group-controls";
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Group");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Start receiving");
            groupStarted = true;
            await(test, n -> tag(n, "nearby_group_copy"), 15000);
            if (find(test, n -> tag(n, "nearby_group_qr")) != null) throw new AssertionError("Group QR is forced on");
            click(test, await(test, n -> tag(n, "nearby_group_add_phone"), 10000));
            AccessibilityNodeInfo image = await(test, n -> tag(n, "nearby_group_qr"), 10000);
            image.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            SystemClock.sleep(250);
            image = await(test, n -> tag(n, "nearby_group_qr"), 5000);
            Rect bounds = new Rect(); image.getBoundsInScreen(bounds);
            test.getUiAutomation().waitForIdle(500, 5000);
            Bitmap screen = test.getUiAutomation().takeScreenshot();
            if (screen == null) throw new AssertionError("Actual group pixels unavailable");
            try {
                if (bounds.width() < 120 || bounds.height() < 120 || bounds.left < 0 || bounds.top < 0 ||
                    bounds.right > screen.getWidth() || bounds.bottom > screen.getHeight()) throw new AssertionError("QR is clipped");
                qr = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height());
            } finally { screen.recycle(); }
            // Showing the QR can move its preceding actions above the viewport,
            // especially on API26. Navigate back through the visible group list.
            for (int i = 0; i < 8 && find(test, n -> text(n, "Copy invitation")) == null; i++) {
                AccessibilityNodeInfo list = find(test, n -> n.isScrollable() &&
                    !"android.widget.HorizontalScrollView".contentEquals(n.getClassName()));
                if (list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break;
                test.waitForIdleSync();
                test.getUiAutomation().waitForIdle(250, 5000);
            }
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Copy invitation");
            String[] copiedInvitation = {""};
            test.runOnMainSync(() -> {
                ClipData clip = clipboard.getPrimaryClip();
                copiedInvitation[0] = clip == null ? "" : clip.getItemAt(0).coerceToText(context).toString();
            });
            String payload = copiedInvitation[0];
            if (payload.length() > 4096 || !payload.contains("://group?")) throw new AssertionError("Group invitation copy failed");
            qrDigest = hex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
            payload = null;
            click(test, await(test, n -> tag(n, "nearby_group_add_phone"), 10000));
            long hiddenDeadline = SystemClock.uptimeMillis() + 5000;
            while (find(test, n -> tag(n, "nearby_group_qr")) != null && SystemClock.uptimeMillis() < hiddenDeadline) SystemClock.sleep(100);
            if (find(test, n -> tag(n, "nearby_group_qr")) != null) throw new AssertionError("Group QR did not hide");
            MediaEditingRuntimeVerifier.scrollClickLabel(test, "Close group");
            await(test, AccessibilityNodeInfo::isEditable, 10000);
            long stoppedDeadline = SystemClock.uptimeMillis() + 10000;
            while (runningReceiver(context) && SystemClock.uptimeMillis() < stoppedDeadline) SystemClock.sleep(100);
            if (runningReceiver(context)) throw new AssertionError("Close group left the receiving service running");
            groupStarted = false;
            // The session is now closed. Retain only its pixels and a digest,
            // never a live invitation/credential or raw clipboard content.
            try (OutputStream stream = new FileOutputStream(new File(output, "group-qr.png"))) { qr.compress(Bitmap.CompressFormat.PNG, 100, stream); }
            success = true;
            return true;
        } catch (Exception | AssertionError failure) {
            capture(test, new File(output, "failure-" + stage + ".png"));
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(output, "failure.json")), StandardCharsets.UTF_8)) {
                writer.write(new JSONObject().put("passed", false).put("stage", stage).put("ownedFixture", root.getName()).toString());
            }
            throw failure;
        } finally {
            try {
                if (groupStarted) context.startService(new Intent().setClassName(context,
                    "com.affilemanager.app.transfer.LanTransferService").setAction("com.affilemanager.app.action.STOP_LAN_TRANSFER"));
            } finally {
            if (qr != null) qr.recycle();
            test.runOnMainSync(() -> {
                if (originalClip != null) clipboard.setPrimaryClip(originalClip);
                else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
            });
            SharedPreferences.Editor restore = preferences.edit();
            if (original == null) restore.remove("share"); else restore.putString("share", original);
            if (!restore.commit()) throw new AssertionError("Owned fixture preferences were not restored");
            if (success) {
                for (String name : new String[]{"Images", "Videos", "Music", "Documents", "Archives", "Apps"}) {
                    File directory = new File(root, name);
                    if (!directory.delete()) throw new AssertionError("Owned empty folder cleanup failed");
                }
                if (!sentinel.delete() || !root.delete()) throw new AssertionError("Owned receiving fixture cleanup failed");
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(output, "result.json")), StandardCharsets.UTF_8)) {
                    writer.write(new JSONObject().put("passed", true).put("qrPayloadSha256", qrDigest)
                        .put("sourceRetained", true).put("sessionStopped", true).put("fixtureCleanupComplete", true).toString());
                }
            }
            }
        }
    }

    private static boolean runningReceiver(Context context) {
        for (android.app.ActivityManager.RunningServiceInfo service : context.getSystemService(android.app.ActivityManager.class).getRunningServices(64))
            if ("com.affilemanager.app.transfer.LanTransferService".equals(service.service.getClassName())) return true;
        return false;
    }

    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for(byte value:bytes) out.append(String.format(Locale.ROOT,"%02x",value&255)); return out.toString(); }
    private static void capture(Instrumentation test,File target)throws IOException { Bitmap bitmap=test.getUiAutomation().takeScreenshot(); if(bitmap==null)throw new IOException("Capture unavailable");
        try(OutputStream stream=new FileOutputStream(target)){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{bitmap.recycle();} }
    private static boolean tag(AccessibilityNodeInfo node,String id){String value=node.getViewIdResourceName();return id.equals(value)||(value!=null&&value.endsWith(":id/"+id));}
    private static boolean text(AccessibilityNodeInfo node,String value){return value.contentEquals(node.getText()==null?"":node.getText());}
    private static AccessibilityNodeInfo find(Instrumentation test,Predicate<AccessibilityNodeInfo> predicate){
        AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();if(root==null)return null;
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);int count=0;
        try{while(!queue.isEmpty()){if(++count>2048)throw new AssertionError("UI tree exceeded bound");AccessibilityNodeInfo node=queue.removeFirst();
            if(node.isVisibleToUser()&&predicate.test(node))return node;
            for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)queue.add(child);}node.recycle();}
        }finally{for(AccessibilityNodeInfo remaining:queue)remaining.recycle();}return null;
    }
    private static AccessibilityNodeInfo await(Instrumentation test,Predicate<AccessibilityNodeInfo> predicate,long timeout){long end=SystemClock.uptimeMillis()+timeout;
        do{AccessibilityNodeInfo found=find(test,predicate);if(found!=null)return found;SystemClock.sleep(100);}while(SystemClock.uptimeMillis()<end);throw new AssertionError("Required sharing control unavailable");}
    private static void click(Instrumentation test,AccessibilityNodeInfo node){for(int i=0;i<8&&node!=null;i++,node=node.getParent())if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;throw new AssertionError("Sharing control rejected click");}
    private static void back(Instrumentation test){if(!test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))throw new AssertionError("Native Back rejected");SystemClock.sleep(150);}
}
