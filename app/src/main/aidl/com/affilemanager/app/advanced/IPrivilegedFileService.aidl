package com.affilemanager.app.advanced;

interface IPrivilegedFileService {
    void destroy() = 16777114;
    IBinder getFileSystemService() = 1;
    int getProcessUid() = 2;
}
