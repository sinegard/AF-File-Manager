# AF File Manager

**AF means Ad Free.**

I made AF File Manager mainly for my own phone. I wanted a fast, capable file manager without banners, pop-ups, tracking, or a required account. I keep sharing it because it may be useful to other people who want the same thing.

AF File Manager is built for Android phones and tablets with Kotlin and Jetpack Compose. It contains no ads, analytics, telemetry, or tracking SDKs.

## What it can do

- Browse internal storage, SD cards, USB drives, Android document providers, archives, installed apps, protected folders, remote servers, and the trash through a consistent list or grid interface.
- Use tabs or dual panes, folder history, real file icons, optional thumbnails, scoped search, saved searches, tags, ratings, and per-folder display settings.
- Copy, move, rename, batch rename, share, compress, extract, recover from trash, verify copies with SHA-256, and resolve file conflicts.
- Add missed items with **Copy more**, then paste one combined clipboard between local storage and connected servers.
- Preview images, PDFs, media, text, code, APKs, metadata, and archive contents.
- Edit text and code locally or on a server, with Save, Save As, encoding and line-ending controls, conflict detection, and temporary-file cleanup.
- Analyze storage for large files, duplicates, similar photos, empty folders, and leftovers without deleting anything automatically.
- Connect through SMB 2/3, SFTP, WebDAV, FTP, and FTPS, with editable profiles and Android Keystore-protected secrets.
- Open a local Android terminal or reuse an active SFTP connection for a remote SSH terminal.
- Share a selected folder temporarily over Web, FTP, or WebDAV on the local network.
- Use optional Root or Shizuku access for protected Android folders on compatible devices.
- Work offline in 59 interface languages. English is the default; English and Lithuanian are reviewed directly.
- Check signed GitHub releases for updates and verify the APK before opening Android's installer.

## Privacy

The main file-management features work without an account and without sending file names or file contents anywhere. Network connections, sharing, and update checks happen only when their matching feature is used. More detail is available in [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## Android limits

Android 11 and newer versions may require **All files access** for full shared-storage browsing. Android or the phone manufacturer can still restrict `Android/data` and `Android/obb`.

AF File Manager can optionally use an already running Shizuku service or a compatible root manager. This mode is off by default. The app does not install, start, or configure Shizuku or root, and its local terminal remains an ordinary app shell rather than a root shell.

## Install and update

Download the newest APK from [GitHub Releases](https://github.com/sinegard/AF-File-Manager/releases/latest). Android will ask for permission to install an APK and will always show its own confirmation before an update.

AF File Manager checks this public repository for a newer stable version at most once every six hours. Before offering an APK, it verifies the repository, version, size, SHA-256 digest, package name, version code, and signing certificate.

## License and responsibility

This is first and foremost a personal project, not a commercial product or service. I share the source and APK so other people can use or improve them for personal and other non-commercial purposes under the [PolyForm Noncommercial License 1.0.0](LICENSE). Commercial use requires separate permission. Because of that restriction, AF File Manager is source-available rather than open-source in the OSI sense.

AF File Manager can read, move, overwrite, delete, archive, upload, and download files. Keep backups of anything important and check paths, servers, and conflict choices before confirming an operation.

If you choose to install or use the app, you do so at your own risk and remain responsible for your device, accounts, credentials, files, servers, backups, and the results of your actions. To the fullest extent allowed by applicable law, the software is provided **as is**, without warranty, and the licensor and contributors accept no liability for damage, data loss, service interruption, security incidents, or any other consequences arising from the software or its use. The complete license terms in [LICENSE](LICENSE) control; this summary does not replace them.

## Build

Requirements: JDK 17, Android SDK 36, and Android NDK `27.3.13750724`.

```powershell
$env:JAVA_HOME = 'path-to-jdk-17'
$env:ANDROID_HOME = 'path-to-android-sdk'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Release signing keys are not stored in the repository. See [docs/RELEASING.md](docs/RELEASING.md) for the release process and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for bundled components.

Issues and pull requests are welcome. Do not include passwords, private keys, personal file names, or real server details in a report.
