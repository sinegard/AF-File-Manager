# Privacy policy

AF File Manager has no advertising, analytics, telemetry, tracking SDK, or mandatory account.

## Data processed on the device

Thumbnails and file icons are generated on the device. File content is not sent over the network for preview generation, and the thumbnail cache is not written to disk.

The app stores the following locally:

- user tags, history, saved searches, Storage Access Framework locations, and synchronization schedules;
- network-profile metadata;
- passwords and optional private SSH keys encrypted through Android Keystore;
- files placed in the app's recoverable trash and the metadata needed to restore them;
- interface language and other user settings.

Android backup and device-to-device transfer are disabled for app data so credentials and trash contents are not copied accidentally.

## Network activity

Data is sent to SMB, SFTP, WebDAV, FTP, or FTPS servers only when the user configures those servers and starts a transfer, or enables a synchronization schedule. Plain FTP does not encrypt credentials or content and should be used only on a trusted network.

At app launch, no more than once every six hours, AF File Manager checks stable releases in the public `sinegard/AF-File-Manager` GitHub repository. GitHub receives a normal HTTPS request with the app version in the `User-Agent` header. The app never sends file names, file contents, storage listings, or network profiles to GitHub. A newer APK is downloaded automatically only on an unmetered network; on a metered network the app asks first.

The temporary local-network transfer page shares only the folder selected by the user, requires a one-time code, and expires automatically. It does not create a public internet tunnel.

## Deleting local app data

Removing a network profile also removes its encrypted credential entry. Removing a Storage Access Framework location revokes only the app's persistent permission and does not delete files. Clearing AF File Manager's app data in Android settings removes its local settings, saved credentials, metadata, and app-managed trash.

## License and responsibility

AF File Manager is free and open-source software under the [MIT License](LICENSE). It is provided as is, without warranty or liability, as stated in the license.
