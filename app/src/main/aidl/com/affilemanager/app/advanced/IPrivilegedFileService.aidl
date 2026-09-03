package com.affilemanager.app.advanced;

interface IPrivilegedFileService {
    void destroy() = 16777114;
    IBinder getFileSystemService() = 1;
    int getProcessUid() = 2;
    long openTerminal(String workingDirectory, int rows, int columns) = 3;
    int readTerminal(long handle, inout byte[] destination) = 4;
    int writeTerminal(long handle, in byte[] source, int offset, int length) = 5;
    void resizeTerminal(long handle, int rows, int columns) = 6;
    void closeTerminal(long handle) = 7;
}
