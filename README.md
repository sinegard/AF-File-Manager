# AF File Manager

**AF stands for Ad Free.**

I started AF File Manager because I wanted a capable file manager for my own phone without banners, pop-ups, tracking, or an account. Too many otherwise useful file managers turn basic tasks into advertising space. This is simply the file manager I wanted to use myself, shared so other people can use it too.

AF File Manager is built with Kotlin and Jetpack Compose for Android phones and tablets. It has no ads, analytics, telemetry, or tracking SDKs.

## Highlights

- single- and dual-pane browsing with independent tabs, history, and per-folder list/grid settings for icon size, spacing, one to six columns, real file icons, and optional asynchronous thumbnails;
- a home screen with bounded recent files, storage usage percentages, and Quick Locations that switch between list and four-to-six-column grid layouts;
- system, light, and dark themes; Default, Material You, Catppuccin, and Orange palettes; plus an optional AMOLED-black background;
- local copy, move, rename, batch rename, recoverable trash, operation queue, pause/resume, retry, and SHA-256 verification;
- continuous PDF viewing, image zoom, media/text/APK previews, Android `Open with`, and archive browsing;
- a full built-in text and code editor with line numbers, syntax highlighting, find/replace, undo/redo, go-to-line, configurable wrapping and text size, UTF-8/UTF-16/Windows-1252/ISO-8859-1 support, and LF/CRLF/CR preservation;
- transparent local and remote editing through private working copies, explicit Save or Save As to the phone, Android document providers, or the active server, revision conflicts, SHA-256 verification, remote rollback protection, and automatic removal of temporary remote-edit downloads;
- ZIP, 7z, RAR, TAR, TAR.GZ, and GZIP support with extraction safety limits;
- scoped search, saved searches, tags, ratings, folder-size analysis, large files, empty folders, duplicate detection, and MediaStore-indexed Apps, Archives, and typed searches that avoid unnecessary full-storage walks;
- a review-first cleanup workspace for large files, installer/archive leftovers, exact duplicates, empty folders, and locally detected similar photos; nothing is selected or deleted automatically;
- optional protected-folder browsing for `Android/data` and `Android/obb` through Shizuku or a compatible root manager, disabled by default and limited to explicit file operations;
- SMB 2/3, SFTP, HTTPS WebDAV, FTP, and FTPS connections with bidirectional recursive transfers;
- one cross-location clipboard: copy locally and paste into the current server folder, or copy remotely and paste into the current local folder;
- `Copy more` appends missed local, search-result, or same-server items to the current copy set without duplicates or replacing its earlier contents;
- named **AF Plans** can combine local, search-result, archive, and multiple-server sources, then copy them to several required or optional destinations after one explicit conflict and free-space preview;
- **AF Timeline** keeps bounded human-readable and JSON operation receipts, supports path search, and offers undo only while the current destination still matches its recorded SHA-256 proof;
- move-to-many removes a source only after every required destination has passed a final SHA-256 verification, while interrupted plans resume from durable checkpoints;
- scheduled rules are disabled until their exact SHA-256 preview is shown and approved; changed files, oversized work, metered-network rules, and charging constraints stop execution safely;
- a browsable local upload picker with folder navigation, persistent multi-folder selection, and system-back history;
- the same long-press selection, clear/select-all toggle, strong highlight, and grouped copy flow for local and remote files;
- one consistent browser layout across local storage, remote servers, Archives, Apps, Android document providers, protected Root/Shizuku locations, archive contents, and the trash, with back/forward/up navigation, scoped search, list/grid views, folders-first sorting, file-type icons, and matching display controls;
- editable network profiles, Android Keystore-protected secrets, SFTP host-key pinning, and safe reconnect after transient transport failures;
- three-way remote text merging compares the downloaded original, the user's edit, and the current server version; overlapping changes remain visibly marked for a human decision;
- a full-screen, touch-friendly terminal in the current folder: a real local Android PTY on the phone, or an SSH shell that reuses the active SFTP profile and opens at the current server path; an active session stays open when the app is minimized;
- temporary local Web, FTP, and WebDAV sharing with a browsable folder picker, optional custom port and credentials, read-only mode, and automatic expiry; WebDAV sharing currently uses HTTP and says so in the app;
- self-update from signed [GitHub Releases](https://github.com/sinegard/AF-File-Manager/releases).

The interface starts in English and can be switched to Lithuanian in **More → Language**. Labels, metadata, dates, runtime messages, and the local transfer page follow that choice. File names, folder names, paths, server names, and user-entered text are never translated.

## Free for non-commercial use

AF File Manager and its source code are available at no charge for personal and other non-commercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE). You may inspect the code, build it, change it, and share it as the license allows.

Commercial use is not included. If you want to use AF File Manager for a commercial product, service, or business purpose, ask for separate permission first.

The source is public, but the commercial-use restriction means this is **source-available software**, not open-source software in the OSI sense. Earlier releases remain governed by the license included with each of those releases.

The software is provided **as is**, without warranty. The authors and copyright holders accept no liability for claims, damage, data loss, or other consequences arising from the software or its use. The complete legally controlling terms are in [LICENSE](LICENSE).

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

An optional advanced mode under **More → Tools and security → Protected Android folder access** can use an already running Shizuku service or a compatible root manager. It is off by default, asks before connecting, accepts only a privileged service running as Android shell or root, and exposes a bounded browser for `Android/data` and `Android/obb`. AF File Manager does not install, start, or configure Shizuku or root on its own.

The phone terminal still runs `/system/bin/sh` as AF File Manager's ordinary Android app user, even when protected-folder access is enabled. It is not a root shell or a packaged Linux distribution. A remote shell is available only for SFTP/SSH profiles because FTP, FTPS, SMB, and WebDAV do not provide a shell protocol.

## Build

Requirements: JDK 17, Android SDK 36, and Android NDK `27.3.13750724`.

```powershell
$env:JAVA_HOME = 'path-to-jdk-17'
$env:ANDROID_HOME = 'path-to-android-sdk'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.\gradlew.bat connectedDebugAndroidTest
powershell -File .\scripts\generate-baseline-profile.ps1 -EmulatorSerial emulator-5554
powershell -File .\scripts\run-performance-gate.ps1 -EmulatorSerial emulator-5554
```

The performance suite uses a separate non-obfuscated `profile` variant to generate stable Baseline/Startup Profile rules and the minified `benchmark` variant to measure cold start, first useful content, 10,000-item scrolling, thumbnails, remote listing, frame times, and memory. Emulator budgets are regression guards rather than claims about physical-device speed.

Unsigned release builds can be created locally with `assembleRelease`. A distributable release must use the same private signing key for every version. The key is never stored in this repository. The release workflow expects these GitHub Actions secrets:

- `AF_RELEASE_KEYSTORE_BASE64`
- `AF_KEYSTORE_PASSWORD`
- `AF_KEY_ALIAS`
- `AF_KEY_PASSWORD`

Pushing a stable `vMAJOR.MINOR.PATCH` tag runs the full JVM/lint/release build and publishes the signed APK to GitHub Releases.

## Privacy and security

See [PRIVACY.md](PRIVACY.md), [SECURITY.md](SECURITY.md), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). File names and file contents are never sent to GitHub by the updater. GitHub receives only an ordinary release metadata request and APK download request.

Contributions are welcome through GitHub issues and pull requests. Never include passwords, private keys, personal file names, or live server details in a report.
