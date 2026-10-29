package com.affilemanager.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.view.accessibility.AccessibilityNodeInfo;
import com.affilemanager.app.media.editing.MediaEditingRuntimeVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** UI and provider IO against the exact normal optimized APK; no AF implementation/test hooks. */
public final class DocumentWorkflowRuntimeVerifier {
    public static boolean verify(Instrumentation test) throws Exception {
        if (!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk")) throw new AssertionError("Owned emulator required");
        Context context = test.getTargetContext();
        android.accessibilityservice.AccessibilityServiceInfo service = test.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        test.getUiAutomation().setServiceInfo(service);
        List<StorageVolume> volumes = context.getSystemService(StorageManager.class).getStorageVolumes();
        StorageVolume volume = volumes.stream().filter(v -> v.isRemovable() && !v.isPrimary() &&
            v.getDescription(context).toLowerCase(Locale.ROOT).contains("virtual")).findFirst()
            .orElseThrow(() -> new AssertionError("Owned virtual public volume fixture required"));
        String id = Objects.requireNonNull(volume.getUuid());
        String name = "AFDoc-" + UUID.randomUUID().toString().substring(0,8);
        File local = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
        if (!local.mkdirs()) throw new AssertionError("Owned local fixture unavailable");
        String fullName = "long-" + String.join("", Collections.nCopies(140,"a")) + ".txt";
        File original = new File(local,fullName), returned = new File(local,"returned");
        write(original,"AF full provider workflow\nunchanged original\n");
        if (!returned.mkdir()) throw new AssertionError("Owned return folder unavailable");
        Uri providerFolder = null;
        File modernAnchor = Build.VERSION.SDK_INT >= 30 ? new File("/storage/"+id,"AFGrant-"+UUID.randomUUID().toString().substring(0,8)) : null;
        String anchorId = id+":"+(modernAnchor == null ? "" : modernAnchor.getName());
        try {
            if(modernAnchor != null && !modernAnchor.mkdir()) throw new AssertionError("Owned modern provider folder unavailable");
            openLocalDirectory(test,local);
            MediaEditingRuntimeVerifier.clickDescription(test,"File actions: " + fullName);
            MediaEditingRuntimeVerifier.clickLabel(test,"Copy");
            BrowserIssuesRuntimeVerifier.tapStaticLabel(test,"Files");
            if(modernAnchor != null) {
                // Android30+ can forbid selecting a disk root. Exercise AF's
                // ordinary provider-folder route without bypassing that restriction.
                MediaEditingRuntimeVerifier.scrollClickLabel(test,"Cloud");
                MediaEditingRuntimeVerifier.clickLabel(test,"Add provider folder");
                chooseOwnedVolumeInSystemPicker(test,volume.getDescription(context),modernAnchor.getName());
            } else {
                MediaEditingRuntimeVerifier.clickLabel(test,volume.getDescription(context));
                if (awaitOptional(test,n -> label(n,"Grant access"),2000) != null) {
                    MediaEditingRuntimeVerifier.clickLabel(test,"Grant access");
                    chooseOwnedVolumeInSystemPicker(test,volume.getDescription(context));
                }
            }
            Uri grant = null;
            long end = SystemClock.uptimeMillis() + 10000;
            do {
                for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                    if (permission.isReadPermission() && permission.isWritePermission() &&
                        "com.android.externalstorage.documents".equals(permission.getUri().getAuthority()) &&
                        anchorId.equals(DocumentsContract.getTreeDocumentId(permission.getUri()))) grant=permission.getUri();
                }
                if (grant == null) SystemClock.sleep(50);
            } while (grant == null && SystemClock.uptimeMillis() < end);
            if (grant == null) throw new AssertionError("Actual OS root grant was not persisted");
            if(modernAnchor != null) MediaEditingRuntimeVerifier.scrollClickLabel(test,modernAnchor.getName());
            Uri root = DocumentsContract.buildDocumentUriUsingTree(grant,anchorId);
            providerFolder=DocumentsContract.createDocument(context.getContentResolver(),root,DocumentsContract.Document.MIME_TYPE_DIR,name);
            if (providerFolder==null) throw new AssertionError("Owned provider folder unavailable");
            MediaEditingRuntimeVerifier.clickDescription(test,"Folder actions");
            MediaEditingRuntimeVerifier.scrollClickLabel(test,"Refresh");
            MediaEditingRuntimeVerifier.scrollClickLabel(test,name);
            MediaEditingRuntimeVerifier.clickDescription(test,"Folder actions");
            MediaEditingRuntimeVerifier.clickLabel(test,"Paste");
            confirmCopyIfAsked(test);
            final Uri folder = providerFolder;
            Uri copied = waitForChild(context,folder,fullName,20000);
            long copiedDeadline=SystemClock.uptimeMillis()+20000;
            while(!read(original).equals(read(context,copied)) && SystemClock.uptimeMillis()<copiedDeadline) SystemClock.sleep(50);
            if (!read(original).equals(read(context,copied))) throw new AssertionError("Provider copy bytes/name differ");
            if (!original.isFile()) throw new AssertionError("Copy removed the source");
            MediaEditingRuntimeVerifier.clickDescription(test,"File actions: " + fullName);
            MediaEditingRuntimeVerifier.clickLabel(test,"Copy");
            MediaEditingRuntimeVerifier.clickDescription(test,"Back");
            MediaEditingRuntimeVerifier.clickDescription(test,"Back");
            if(modernAnchor != null && awaitOptional(test,n -> label(n,"Cloud and provider locations"),1000) != null)
                MediaEditingRuntimeVerifier.clickDescription(test,"Close");
            openLocalDirectory(test,returned);
            MediaEditingRuntimeVerifier.clickDescription(test,"Folder actions");
            MediaEditingRuntimeVerifier.clickLabel(test,"Paste");
            confirmCopyIfAsked(test);
            long deadline=SystemClock.uptimeMillis()+20000;
            File result=new File(returned,fullName);
            while ((!result.isFile() || !read(original).equals(read(result))) && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(50);
            if (!result.isFile() || !read(original).equals(read(result))) throw new AssertionError("AF UI did not paste the provider source locally");
            if (!read(original).equals(read(context,copied))) throw new AssertionError("Reverse Copy removed or changed its provider source");
            capture(test,"document-optimized-two-way");
            System.out.println("AF_DOCUMENT_OS_GRANT_FULL_NAME_COPY_BOTH_DIRECTIONS_OK");
            return true;
        } catch (Exception | AssertionError failure) {
            capture(test,"document-optimized-failure-" + SystemClock.uptimeMillis());
            throw failure;
        } finally {
            if (providerFolder!=null) DocumentsContract.deleteDocument(context.getContentResolver(),providerFolder);
            if(modernAnchor!=null && modernAnchor.exists() && !modernAnchor.delete()) throw new IOException("Owned provider anchor cleanup failed");
            deleteOwned(local);
        }
    }

    private static boolean isUseFolder(AccessibilityNodeInfo n) { return n.getText()!=null &&
        n.getText().toString().matches("(?i)(select|use this folder|use folder)"); }
    public static void chooseOwnedVolumeInSystemPicker(Instrumentation test,String volumeTitle)throws Exception {
        chooseOwnedVolumeInSystemPicker(test,volumeTitle,null);
    }
    private static void chooseOwnedVolumeInSystemPicker(Instrumentation test,String volumeTitle,String child)throws Exception {
        if(!Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk") || !volumeTitle.toLowerCase(Locale.ROOT).contains("virtual"))
            throw new AssertionError("Only the owned virtual-volume picker fixture is allowed");
        // Older DocumentsUI ignores a suggested tree URI. Select the explicitly owned volume.
        MediaEditingRuntimeVerifier.clickDescription(test,"Show roots");
        MediaEditingRuntimeVerifier.clickLabel(test,volumeTitle);
        if(child!=null) {
            // DocumentsUI uses native views, not AF's Compose virtual nodes.
            // Activate its observed folder row and still require enabled consent
            // plus the exact persisted child identity below; never select the root.
            click(await(test,n -> label(n,child) && n.isVisibleToUser(),10000));
        }
        click(await(test,n -> isUseFolder(n) && n.isEnabled(),10000));
        AccessibilityNodeInfo allow=awaitOptional(test,n -> label(n,"Allow") && n.isEnabled(),1500);
        if(allow!=null)click(allow);
    }
    private static void openLocalDirectory(Instrumentation test,File folder)throws Exception {
        Context context=test.getTargetContext();
        Intent launch=new Intent(Intent.ACTION_MAIN).setClassName(context,"com.affilemanager.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        test.runOnMainSync(()->context.startActivity(launch));
        BrowserIssuesRuntimeVerifier.tapStaticLabel(test,"Files");
        // The home chooser correctly retains its scroll position after Cloud.
        // Navigate back to Storage through UI rather than resetting AF state.
        for(int i=0;i<8 && awaitOptional(test,n -> label(n,"Internal storage") && n.isVisibleToUser(),100)==null;i++) {
            AccessibilityNodeInfo list=awaitOptional(test,n -> n.isScrollable() && n.isVisibleToUser(),1000);
            if(list==null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break;
            test.waitForIdleSync();
            test.getUiAutomation().waitForIdle(250,5000);
        }
        MediaEditingRuntimeVerifier.clickLabel(test,"Internal storage");
        click(await(test,n->n.getViewIdResourceName()!=null&&n.getViewIdResourceName().startsWith("directory_address_local_"),10000));
        AccessibilityNodeInfo input=await(test,AccessibilityNodeInfo::isEditable,5000);
        android.os.Bundle value=new android.os.Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,folder.getAbsolutePath());
        if(!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value))throw new AssertionError("Folder address rejected text");
        MediaEditingRuntimeVerifier.clickLabel(test,"Open");
    }
    private static void confirmCopyIfAsked(Instrumentation test) {
        AccessibilityNodeInfo confirm=awaitOptional(test,n -> "transfer_options_confirm".equals(n.getViewIdResourceName()),1500);
        if(confirm!=null)click(confirm);
    }
    private static boolean label(AccessibilityNodeInfo n,String text) { return n.getText()!=null && text.equalsIgnoreCase(n.getText().toString()); }
    private static AccessibilityNodeInfo awaitOptional(Instrumentation test, Predicate<AccessibilityNodeInfo> match,long timeout) {
        long end=SystemClock.uptimeMillis()+timeout;
        do {
            AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
            if(root!=null) {
                ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);int visited=0;
                while(!queue.isEmpty()&&visited++<8192){AccessibilityNodeInfo n=queue.removeFirst();if(match.test(n))return n;
                    for(int i=0;i<Math.min(n.getChildCount(),128);i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)queue.add(child);}}
            }
            SystemClock.sleep(50);
        } while(SystemClock.uptimeMillis()<end);
        return null;
    }
    private static AccessibilityNodeInfo await(Instrumentation test,Predicate<AccessibilityNodeInfo> match,long timeout) {
        AccessibilityNodeInfo found=awaitOptional(test,match,timeout);if(found==null)throw new AssertionError("Required document UI control unavailable");return found;
    }
    private static void click(AccessibilityNodeInfo node) { for(int i=0;i<6&&node!=null;i++,node=node.getParent())if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;
        throw new AssertionError("Document UI control rejected click"); }
    private static Uri waitForChild(Context context,Uri parent,String name,long timeout) throws Exception {
        long end=SystemClock.uptimeMillis()+timeout;
        do {
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
            try(android.database.Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
                int count=0;if(cursor!=null)while(cursor.moveToNext()){if(++count>50000)throw new AssertionError("Fixture listing exceeded limit");
                    if(name.equals(cursor.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(parent,cursor.getString(0));}}
            SystemClock.sleep(50);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Provider output with the full source name was not created");
    }
    private static void write(File f,String value)throws IOException{try(OutputStream out=new FileOutputStream(f)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static String read(File f)throws IOException{try(InputStream in=new FileInputStream(f)){return readBounded(in);}}
    private static String read(Context context,Uri uri)throws IOException{try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Fixture unavailable");return readBounded(in);}}
    private static String readBounded(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;
        while((n=in.read(b))>=0){if(out.size()+n>65536)throw new IOException("Fixture read exceeded limit");out.write(b,0,n);}return out.toString("UTF-8");}
    private static void deleteOwned(File f)throws IOException{File[] children=f.listFiles();if(children!=null)for(File child:children)deleteOwned(child);if(f.exists()&&!f.delete())throw new IOException("Owned fixture cleanup failed");}
    private static void capture(Instrumentation test,String name)throws IOException{Bitmap b=test.getUiAutomation().takeScreenshot();if(b==null)return;
        File folder=new File(test.getTargetContext().getExternalFilesDir("validation"),"document-runtime");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Evidence unavailable");
        try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
}
