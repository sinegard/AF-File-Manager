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
- New `.afvault` files use the versioned AFFMV2 envelope and Tink's AES256_GCM_HKDF_1MB streaming AEAD, with an in-memory PBKDF2-HMAC-SHA256 password key. The complete envelope is authenticated; the original name and size remain visible, as in AFFMV1. Keys are not stored or sent anywhere. Older AF versions cannot open V2 files.
- AFFMV1 copies remain readable without conversion. Their older whole-file AEAD implementation is admitted only when its estimated working set fits with 25% heap headroom. A memory refusal, wrong passphrase, damaged stream or cancellation leaves the encrypted original unchanged and does not publish a partial plaintext file.
- Thumbnails are generated locally. The process-memory LRU is capped at 24 MiB; reusable thumbnails also stay in the app-private `cacheDir/af-thumbnails-v1` cache, bounded to 128 MiB and 2,048 entries. AF invalidates affected entries after its own file changes and checks source revisions before reuse. These caches never leave the device, and Android may remove disposable cache data. Opening a location does not silently index the entire device.
- The home screen reads at most 200 recent MediaStore rows at a time and keeps at most 200 paths for files explicitly touched by AF File Manager. Missing and unreadable files are filtered, and nothing from this list leaves the device.
- Remote previews and editable working copies use bounded app-private staging. Redundant downloads are removed when editing starts, the remaining temporary copy is removed when the preview closes or a remote edit is saved, and stale staging is purged on the next app start.
- SFTP host keys are verified by SHA-256 fingerprint. Trust on first use is available only after an explicit choice; the first key is stored and a later key change is blocked.
- WebDAV requires HTTPS. FTPS validates the certificate chain and endpoint name. Plain FTP is intentionally an insecure legacy protocol and should never be used on an untrusted network.
- Temporary web, FTP and WebDAV sharing reject symbolic links below the explicitly chosen sharing root before resolving paths. This includes broken links and linked parent directories; an unsupported alias is not treated as permission to change its referent.
- The local terminal is a bounded PTY session running `/system/bin/sh` under the app's normal Android UID and SELinux policy. It never grants root, Shizuku, or ADB privileges.
- The server terminal is offered only for SFTP/SSH profiles. It creates a separate SSH shell with the saved encrypted credential and the same pinned host-key policy; credentials and terminal output are not written to app logs.
- Closing a terminal tears down its PTY or SSH channel and process group. Terminal input queues, paste size, dimensions, and renderer scrollback are bounded.
- Protected-folder access is off by default. Shizuku permission or root access is requested only after an explicit user action, and the returned service is accepted only when it runs as Android shell (UID 2000) or root (UID 0).
- The Shizuku shell browser is limited to its allowed `Android/data` and `Android/obb` roots. A genuinely granted UID-0 root or Shizuku-root backend can browse the device root. Both paths validate canonical locations and retain bounded depth, item count, preview size and transfer size. A privileged terminal is a separate explicit action backed by that authorized service; the ordinary local terminal remains under the app UID.

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
- Quick Tunnel is opt-in and exposes only the already selected AF Web share. It keeps the one-time AF access code, runs the pinned bundled `cloudflared` component as the ordinary app UID, and stops with the Web session or the user's Stop action. The temporary address is public, Cloudflare terminates its HTTPS connection, and Cloudflare's service availability and terms remain outside AF's security boundary.

## Security boundaries

- The optional Get content picker returns temporary read access only to files explicitly selected by the user. It respects the AF app lock and Android storage permissions. It does not expose saved servers, privileged roots, app-private storage, directory grants, or write access to the requesting app.
- Android and device-vendor restrictions take precedence over the app. Ordinary access to `Android/data` and `Android/obb` remains restricted; the optional advanced mode works only when a separately installed and configured Shizuku or compatible root service actually grants access.
- Release APKs are signed with a separate distribution key stored as GitHub Actions secrets. The private key and passwords are not present in this repository. Losing that key prevents compatible updates.
- Public releases contain a CPU-specific APK for each supported ABI plus a universal fallback for older AF versions. The updater chooses only an APK matching the device ABI and still applies the package, version, digest, and signing-certificate checks above.
- Every real server type should be validated against the owner's infrastructure and certificate or SSH-fingerprint policy before production use.
- No software can guarantee that a remote server, network, removable drive, or third-party Android provider is trustworthy or available.

## Warranty and liability

AF File Manager is distributed for non-commercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE). It is provided **as is**, without warranty of any kind, and the licensor is not liable for damages arising from the software or its use to the extent allowed by law. See [LICENSE](LICENSE) for the complete terms.
