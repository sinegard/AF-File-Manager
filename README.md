# AF File Manager

AF File Manager is a privacy-first Android file manager with no ads, tracking SDKs, or mandatory account. It is built with Kotlin and Jetpack Compose for phones and tablets.

## Highlights

- single- and dual-pane browsing with independent tabs, history, and per-folder view settings;
- folders-first sorting, list/grid layouts, real file icons, and optional asynchronous thumbnails;
- local copy, move, rename, batch rename, recoverable trash, operation queue, pause/resume, retry, and SHA-256 verification;
- continuous PDF viewing, image zoom, media/text/APK previews, Android `Open with`, and archive browsing;
- ZIP, 7z, RAR, TAR, TAR.GZ, and GZIP support with extraction safety limits;
- scoped search, saved searches, tags, ratings, folder-size analysis, large files, empty folders, and duplicate detection;
- SMB 2/3, SFTP, HTTPS WebDAV, FTP, and FTPS connections with bidirectional recursive transfers;
- editable network profiles, Android Keystore-protected secrets, SFTP host-key pinning, and safe reconnect after transient transport failures;
- local Wi-Fi file transfer page with a one-time code and an automatic expiry;
- self-update from signed [GitHub Releases](https://github.com/sinegard/AF-File-Manager/releases).

The current user interface is Lithuanian. Additional locales are planned.

## Installation and updates

Download the newest APK from [Releases](https://github.com/sinegard/AF-File-Manager/releases/latest). Android must allow installs from the browser or file manager used to open the APK.

AF File Manager checks the public GitHub repository when the app starts, no more than once every six hours. On an unmetered network, a newer stable APK can be downloaded automatically. Before opening Android's installer, the app verifies all of the following:

- the release belongs to this repository;
- the APK name and semantic version match the release;
- the size and GitHub-provided SHA-256 digest match;
- the package is `com.affilemanager.app`;
- the version code is newer;
- the APK signing certificate is identical to the installed app.

Android always shows its own installation confirmation. A regular third-party app cannot silently replace itself.

## Android storage boundaries

Full local browsing on Android 11+ uses the special **All files access** permission. Android and device-vendor restrictions can still block `Android/data` and `Android/obb`. The app also supports user-selected Storage Access Framework locations without this broad permission.

Root and Shizuku actions are not performed automatically.

## Build

Requirements: JDK 17 and Android SDK 36.

```powershell
$env:JAVA_HOME = 'path-to-jdk-17'
$env:ANDROID_HOME = 'path-to-android-sdk'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest
```

Unsigned release builds can be created locally with `assembleRelease`. A distributable release must use the same private signing key for every version. The key is never stored in this repository. The release workflow expects these GitHub Actions secrets:

- `AF_RELEASE_KEYSTORE_BASE64`
- `AF_KEYSTORE_PASSWORD`
- `AF_KEY_ALIAS`
- `AF_KEY_PASSWORD`

Pushing a stable `vMAJOR.MINOR.PATCH` tag runs the full JVM/lint/release build and publishes the signed APK to GitHub Releases.

## Privacy and security

See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md). File names and file contents are never sent to GitHub by the updater. GitHub receives only an ordinary release metadata request and APK download request.
