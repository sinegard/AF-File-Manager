# Security policy

## Supported versions

Security fixes are provided for the latest stable release. Before reporting a problem, reproduce it with the newest version from [GitHub Releases](https://github.com/sinegard/AF-File-Manager/releases/latest) when it is safe to do so.

## Reporting a vulnerability

Do not publish credentials, private keys, personal file names, server addresses, or working exploit details in a public issue.

If the repository's **Security** tab offers **Report a vulnerability**, use that private form. If no private form is available, open a minimal public issue requesting a private contact channel and include no sensitive technical details. Maintainers should acknowledge a complete report before discussing disclosure timing.

Ordinary bugs that contain no sensitive information may be reported through GitHub Issues.

## Secret handling

- Network passwords and private SSH keys are encrypted before storage with an AES-GCM key held by `AndroidKeyStore`.
- Secret character and byte arrays are cleared after use where JVM and Android APIs permit it.
- Thumbnails are generated locally and kept only in a process-memory LRU cache capped at 24 MiB. The app does not build a persistent thumbnail database or silently index the entire device.
- The home screen reads at most 200 recent MediaStore rows at a time and keeps at most 200 paths for files explicitly touched by AF File Manager. Missing and unreadable files are filtered, and nothing from this list leaves the device.
- Remote previews and editable working copies use bounded app-private staging. Redundant downloads are removed when editing starts, the remaining temporary copy is removed when the preview closes or a remote edit is saved, and stale staging is purged on the next app start.
- SFTP host keys are verified by SHA-256 fingerprint. Trust on first use is available only after an explicit choice; the first key is stored and a later key change is blocked.
- WebDAV requires HTTPS. FTPS validates the certificate chain and endpoint name. Plain FTP is intentionally an insecure legacy protocol and should never be used on an untrusted network.
- The local terminal is a bounded PTY session running `/system/bin/sh` under the app's normal Android UID and SELinux policy. It never grants root, Shizuku, or ADB privileges.
- The server terminal is offered only for SFTP/SSH profiles. It creates a separate SSH shell with the saved encrypted credential and the same pinned host-key policy; credentials and terminal output are not written to app logs.
- Closing a terminal tears down its PTY or SSH channel and process group. Terminal input queues, paste size, dimensions, and renderer scrollback are bounded.
- Protected-folder access is off by default. Shizuku permission or root access is requested only after an explicit user action, and the returned service is accepted only when it runs as Android shell (UID 2000) or root (UID 0).
- The privileged browser accepts only canonical paths under `Android/data` and `Android/obb`, with bounded depth, item count, preview size, and transfer size. It does not turn the local terminal into a privileged shell.

## File integrity

- Copy, download, encryption, text editing, and extraction write to temporary files and publish the final name only after successful completion.
- Written sizes are checked where possible. Duplicate detection uses SHA-256.
- Archive paths are canonicalized and cannot escape the destination. Entry count, nesting depth, per-file size, and total expanded size are bounded; TAR links are rejected.
- Remote sessions are serialized with a `Mutex` so one client is not used concurrently in an unsafe way.

## Destructive actions

- Local deletion goes to the app's recoverable trash by default.
- Permanent local, Storage Access Framework, and remote deletion requires a separate confirmation.
- Synchronization never performs a silent delete. Background conflicts stop execution and record their state.
- Android confirms every APK installation. Root and Shizuku actions are not executed automatically, and changing the protected-folder access mode disconnects the previous privileged service.
- The updater accepts only a stable release from this public repository. It verifies the HTTPS origin, APK name, size, GitHub SHA-256 digest, package ID, higher `versionCode`, and the installed app's signing certificate before opening Android's installer.

## Security boundaries

- Android and device-vendor restrictions take precedence over the app. Ordinary access to `Android/data` and `Android/obb` remains restricted; the optional advanced mode works only when a separately installed and configured Shizuku or compatible root service actually grants access.
- Release APKs are signed with a separate distribution key stored as GitHub Actions secrets. The private key and passwords are not present in this repository. Losing that key prevents compatible updates.
- Every real server type should be validated against the owner's infrastructure and certificate or SSH-fingerprint policy before production use.
- No software can guarantee that a remote server, network, removable drive, or third-party Android provider is trustworthy or available.

## Warranty and liability

AF File Manager is distributed for non-commercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE). It is provided **as is**, without warranty of any kind, and the licensor is not liable for damages arising from the software or its use to the extent allowed by law. See [LICENSE](LICENSE) for the complete terms.
