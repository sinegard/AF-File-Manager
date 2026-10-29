package com.affilemanager.app.advanced;

import android.os.ParcelFileDescriptor;

interface IPrivilegedFileService {
    void destroy() = 16777114;
    IBinder getFileSystemService() = 1;
    int getProcessUid() = 2;
    long openTerminal(String workingDirectory, int rows, int columns) = 3;
    int readTerminal(long handle, inout byte[] destination) = 4;
    int writeTerminal(long handle, in byte[] source, int offset, int length) = 5;
    void resizeTerminal(long handle, int rows, int columns) = 6;
    void closeTerminal(long handle) = 7;
    String[] listRunningAppMemory() = 8;
    boolean forceStopPackage(String packageName) = 9;
    String[] readFileMetadata(String path) = 10;
    void applyFileMetadata(String path, in String[] metadata) = 11;
    void createPrivateEntry(String path, boolean directory) = 12;
    int getFileMetadataVersion() = 13;
    long openPrivateWrite(String path) = 14;
    int writePrivateFile(long handle, in byte[] source, int length) = 15;
    void finishPrivateWrite(long handle) = 16;
    void abortPrivateWrite(long handle) = 17;
    int getFileReadVersion() = 18;
    ParcelFileDescriptor openReadOnlyFile(String path) = 19;
}
