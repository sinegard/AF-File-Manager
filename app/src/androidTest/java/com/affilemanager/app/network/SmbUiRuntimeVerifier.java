package com.affilemanager.app.network;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

import static com.affilemanager.app.network.FtpTlsUiRuntimeVerifier.*;

/** Actual optimized AF SMB profile/connect/list/disconnect; no shipping hooks or keep exceptions. */
public final class SmbUiRuntimeVerifier {
    public static boolean verify(Instrumentation test, int port, char[] password) throws Exception {
        if (!android.os.Build.MODEL.toLowerCase(java.util.Locale.ROOT).contains("sdk")) throw new AssertionError("Owned guest required");
        Context context = test.getTargetContext();
        String name = "AF SMB " + UUID.randomUUID().toString().substring(0,8);
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(service);
        boolean saved = false, connected = false;
        Throwable primary = null;
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            test.runOnMainSync(() -> context.startActivity(launch));
            click(test, await(test,n -> id(n,"nav_connections"),15000));
            MediaEditingRuntimeVerifier.clickDescription(test,"Add connection");
            click(test,scrollTo(test,"network_protocol_SMB","network_protocols"));
            setField(test,"network_name",name);
            setField(test,"network_host","127.0.0.1");
            setField(test,"network_port",Integer.toString(port));
            setField(test,"network_username","audit");
            setField(test,"network_password",new String(password));
            AccessibilityNodeInfo label = scrollMatch(test,"network_profile_fields", n -> text(n).equals("Share name"));
            Rect labelBounds = new Rect(); label.getBoundsInScreen(labelBounds);
            AccessibilityNodeInfo field = null; int distance = Integer.MAX_VALUE;
            for (AccessibilityNodeInfo candidate : descendants(await(test,n -> id(n,"network_profile_fields"),10000))) {
                if (!candidate.isEditable() || !candidate.isVisibleToUser()) continue;
                Rect bounds = new Rect(); candidate.getBoundsInScreen(bounds);
                int delta = Math.abs(bounds.centerY()-labelBounds.centerY());
                if (delta < distance) { field=candidate; distance=delta; }
            }
            if (field == null || distance > 200) throw new AssertionError("Actual SMB share input not reachable");
            Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"AF_AUDIT");
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value)) throw new AssertionError("Share input rejected text");
            MediaEditingRuntimeVerifier.clickLabel(test,"Save"); saved = true;
            click(test,ownAction(test,name,"profile_connect_"));
            await(test,n -> id(n,"remote_entry_/publish-failure.bin"),20000); connected = true;
            try (Writer out = new OutputStreamWriter(new FileOutputStream(new File(context.getExternalFilesDir("validation"),"optimized-smb-ui.json")),StandardCharsets.UTF_8)) {
                out.write(new JSONObject().put("package",context.getPackageName()).put("api",android.os.Build.VERSION.SDK_INT)
                        .put("actualAfProfileUi",true).put("protocol","SMB2").put("listedOwnedFixture",true).toString(2));
            }
            return true;
        } catch (Throwable failure) { primary=failure; throw failure; }
        finally {
            try {
                if (connected) {
                    MediaEditingRuntimeVerifier.clickDescription(test,"Folder actions");
                    MediaEditingRuntimeVerifier.scrollClickLabel(test,"Disconnect");
                }
                if (saved) {
                    click(test,ownAction(test,name,"profile_remove_"));
                    await(test,n -> !n.isEditable() && text(n).contains(name),15000);
                    MediaEditingRuntimeVerifier.clickLabel(test,"Remove");
                    if (find(test,n -> text(n).equals(name)) != null) throw new AssertionError("Owned SMB profile still present");
                }
            } catch (Throwable cleanup) { if(primary!=null)primary.addSuppressed(cleanup); else throw cleanup; }
            java.util.Arrays.fill(password,'\0');
        }
    }
}
