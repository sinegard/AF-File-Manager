package com.affilemanager.app.network;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Real AF UI without Kotlin-library linkage, app reflection, or additional release keep rules. */
public final class FtpTlsUiRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        Context context = test.getTargetContext();
        String name = "AF TLS " + UUID.randomUUID().toString().substring(0,8);
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(service);
        boolean saved = false;
        Throwable primaryFailure = null;
        try (FtpTlsFixture.Material material = new FtpTlsFixture.Material();
             FtpTlsFixture.Endpoint fixture = new FtpTlsFixture.Endpoint(material.context, false)) {
            try {
                Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(context, "com.affilemanager.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                test.runOnMainSync(() -> context.startActivity(launch));
                click(test, await(test, n -> id(n,"nav_connections"),15000));
                MediaEditingRuntimeVerifier.clickDescription(test,"Add connection");
                click(test,scrollTo(test,"network_protocol_FTPS","network_protocols"));
                setField(test,"network_name",name);
                setField(test,"network_host","127.0.0.1");
                setField(test,"network_port",Integer.toString(fixture.port));
                setField(test,"network_username","fixture-user");
                setField(test,"network_password",UUID.randomUUID().toString());
                MediaEditingRuntimeVerifier.clickLabel(test,"Save"); saved = true;
                fixture.start();
                click(test,ownAction(test,name,"profile_connect_"));
                await(test,n -> id(n,"network_connection_error"),20000);
                scrollMatch(test,"network_profiles",n -> !n.isEditable() && text(n).contains("FTPS-CONNECT-TLS"));
                if (fixture.loginReceived.get()) throw new AssertionError("AF sent login data to an untrusted FTPS endpoint");
                File record = new File(context.getExternalFilesDir("validation"),"optimized-ftps-trust.json");
                try (Writer out = new OutputStreamWriter(new FileOutputStream(record),StandardCharsets.UTF_8)) {
                    out.write(new JSONObject().put("package",context.getPackageName()).put("api",Build.VERSION.SDK_INT)
                        .put("actualAfProfileUi",true).put("diagnosticCode","FTPS-CONNECT-TLS")
                        .put("loginSentToUntrustedServer",false).put("systemTrustUnchanged",true).toString(2));
                }
                return true;
            } catch (Throwable error) {
                primaryFailure = error;
                try {
                    Bitmap image = test.getUiAutomation().takeScreenshot();
                    if (image != null) try (OutputStream out = new FileOutputStream(new File(context.getExternalFilesDir("validation"),"ftps-ui-failure.png"))) {
                        image.compress(Bitmap.CompressFormat.PNG,100,out);
                    } finally { if (image != null) image.recycle(); }
                } catch (Throwable capture) { error.addSuppressed(capture); }
                throw error;
            } finally {
                if (saved) try {
                    click(test,ownAction(test,name,"profile_remove_"));
                    await(test,n -> !n.isEditable() && text(n).contains(name),15000);
                    MediaEditingRuntimeVerifier.clickLabel(test,"Remove");
                    long end = SystemClock.elapsedRealtime()+10000;
                    while (find(test,n -> text(n).equals(name)) != null && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
                    if (find(test,n -> text(n).equals(name)) != null) throw new AssertionError("Owned TLS profile cleanup failed");
                } catch (Throwable cleanup) { if (primaryFailure != null) primaryFailure.addSuppressed(cleanup); else throw cleanup; }
            }
        }
    }
    static void setField(Instrumentation test,String tag,String value) throws Exception {
        AccessibilityNodeInfo field = null;
        for (AccessibilityNodeInfo node : descendants(scrollTo(test,tag,"network_profile_fields"))) if (node.isEditable()) { field=node; break; }
        if (field == null) throw new AssertionError("AF field is not editable: "+tag);
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) throw new AssertionError("AF field input rejected: "+tag);
        test.getUiAutomation().waitForIdle(100,5000);
    }
    static AccessibilityNodeInfo scrollTo(Instrumentation test,String tag,String container) throws Exception {
        return scrollMatch(test,container,n -> id(n,tag));
    }
    static AccessibilityNodeInfo scrollMatch(Instrumentation test,String container,Match match) throws Exception {
        for (int step=0;step<14;step++) {
            AccessibilityNodeInfo found=find(test,match); if (found != null) return found;
            await(test,n -> id(n,container),15000).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            test.getUiAutomation().waitForIdle(200,5000); SystemClock.sleep(300);
        }
        throw new AssertionError("AF control absent after bounded scrolling in "+container);
    }
    static AccessibilityNodeInfo ownAction(Instrumentation test,String name,String prefix) throws Exception {
        AccessibilityNodeInfo parent=scrollMatch(test,"network_profiles",n -> text(n).equals(name));
        for (int level=0;level<10 && parent != null;level++,parent=parent.getParent()) {
            String card=parent.getViewIdResourceName(); if (card == null) continue;
            int separator=card.lastIndexOf(":id/"); if(separator>=0)card=card.substring(separator+4);
            if (card.startsWith("network_profile_card_")) return scrollTo(test,prefix+card.substring("network_profile_card_".length()),"network_profiles");
        }
        throw new AssertionError("Owned AF connection action unavailable");
    }
    static void click(Instrumentation test,AccessibilityNodeInfo node) throws Exception {
        for (int level=0;level<8 && node != null;level++,node=node.getParent()) if (node.isClickable()) {
            if (!node.isEnabled() || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("AF control rejected the click");
            test.getUiAutomation().waitForIdle(200,5000); return;
        }
        throw new AssertionError("AF control has no click target");
    }
    static boolean id(AccessibilityNodeInfo node,String tag) { String value=node.getViewIdResourceName();return tag.equals(value)||(value!=null&&value.endsWith(":id/"+tag)); }
    static String text(AccessibilityNodeInfo node) {return node.getText()==null?"":node.getText().toString();}
    interface Match {boolean accepts(AccessibilityNodeInfo node);}
    static List<AccessibilityNodeInfo> descendants(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result=new ArrayList<>(); ArrayDeque<AccessibilityNodeInfo> pending=new ArrayDeque<>();pending.add(root);
        while (!pending.isEmpty()&&result.size()<6000) {
            AccessibilityNodeInfo node=pending.removeFirst(); if (!node.refresh()) continue; result.add(node);
            for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)pending.add(child);}
        }
        return result;
    }
    static AccessibilityNodeInfo find(Instrumentation test,Match match) {
        List<AccessibilityWindowInfo> windows=new ArrayList<>(test.getUiAutomation().getWindows());windows.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
        for(AccessibilityWindowInfo window:windows){AccessibilityNodeInfo root=window.getRoot();if(root==null||!test.getTargetContext().getPackageName().contentEquals(root.getPackageName()))continue;
            for(AccessibilityNodeInfo node:descendants(root))if(node.isVisibleToUser()&&match.accepts(node))return node;}
        return null;
    }
    static AccessibilityNodeInfo await(Instrumentation test,Match match,long timeout) throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;
        do{AccessibilityNodeInfo node=find(test,match);if(node!=null)return node;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError("Expected AF control or certificate error was not displayed");
    }
}
