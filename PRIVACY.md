# Privacy policy

AF File Manager has no advertising, analytics, telemetry, tracking SDK, or mandatory account.

All interface language packs are static files included in the APK. Changing language does not send interface text, file names, paths, or any other data to a translation service.

## Data processed on the device

Thumbnails and file icons are generated on the device. File content is not sent over the network for preview generation, and the thumbnail cache is not written to disk.

Files opened from a remote server are staged temporarily in the app's private cache. When editing starts, the redundant preview download is removed as soon as the private working copy is ready. Closing the preview or successfully saving a remote edit removes the remaining temporary copy. A separate file remains only when the user deliberately chooses a destination through Save as or a normal transfer. Stale staging files from an interrupted app process are removed the next time the app starts.

The app stores the following locally:

- user tags, navigation history, a bounded recent-file path list, saved searches, Storage Access Framework locations, and synchronization schedules;
- network-profile metadata;
- passwords and optional private SSH keys encrypted through Android Keystore;
- files placed in the app's recoverable trash and the metadata needed to restore them;
- interface language, protected-folder access mode, and other user settings.

Android backup and device-to-device transfer are disabled for app data so credentials and trash contents are not copied accidentally.

## Network activity

Data is sent to SMB, SFTP, WebDAV, FTP, or FTPS servers only when the user configures those servers and starts a transfer, or enables a synchronization schedule. Plain FTP does not encrypt credentials or content and should be used only on a trusted network. A Nextcloud connection contacts only the server entered by the user; browser authorization creates a revocable app password that is stored through Android Keystore until the connection is removed.

At app launch, no more than once every six hours, AF File Manager checks stable releases in the public `sinegard/AF-File-Manager` GitHub repository. GitHub receives a normal HTTPS request with the app version in the `User-Agent` header. The app never sends file names, file contents, storage listings, or network profiles to GitHub. A newer APK is downloaded automatically only on an unmetered network; on a metered network the app asks first.

The temporary local-network transfer page shares only the folder selected by the user, requires a one-time code, and expires automatically. It stays on the local network unless the user explicitly chooses **Quick Tunnel**. Quick Tunnel creates a temporary public HTTPS address through Cloudflare and routes requests and transferred file content through Cloudflare's network. Cloudflare receives the connection metadata and handles the public HTTPS connection; AF has no Cloudflare account or token. The AF one-time code still controls access, but users should not enable Quick Tunnel for content they do not want routed through a third-party service. Stopping either the tunnel or its AF Web session removes the public route.

Phone groups keep the member directory on the organizer phone. File content is transferred directly between the selected sender and recipient on their local connection rather than through the organizer or an AF-operated server.

Protected-folder access is local and optional. When the user enables it, selected paths and file operations are sent only to the local Shizuku or root service on that Android device. AF File Manager does not send those paths or file contents to its own server or any analytics service.

## Deleting local app data

Removing a network profile also removes its encrypted credential entry. Removing a Storage Access Framework location revokes only the app's persistent permission and does not delete files. Clearing AF File Manager's app data in Android settings removes its local settings, saved credentials, metadata, and app-managed trash.

## License and responsibility

AF File Manager is source-available and free of charge for non-commercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE). It is provided as is, without warranty or liability, as stated in the license.
