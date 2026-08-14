# Releasing AF File Manager

The Android package is `com.affilemanager.app`. Every public APK must be signed by the same dedicated release key; changing or losing the key breaks the Android update chain.

## Automated GitHub release

1. Update `versionCode` and stable `versionName` in `app/build.gradle.kts`.
2. Run the full local validation.
3. Commit and push the version change to `main`.
4. Create and push the exact matching tag, for example `v0.9.5`.
5. The `Publish signed APK` workflow validates that the tag matches Gradle, runs JVM tests and lint, builds with the repository signing secrets, and publishes the APK plus a SHA-256 file.

Required repository secrets:

- `AF_RELEASE_KEYSTORE_BASE64`
- `AF_KEYSTORE_PASSWORD`
- `AF_KEY_ALIAS`
- `AF_KEY_PASSWORD`

The private key must never be committed, attached to an issue, or included in a release asset.

## Local owner build

The owner machine keeps the key outside the checkout under `%USERPROFILE%\.android\af-file-manager-signing`. Its password is stored in a Windows DPAPI-protected credential file. Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

The script exposes signing values only to the child Gradle process and clears the temporary environment variables afterward.

## Update invariants

- GitHub Releases must be public and stable, not draft or prerelease.
- The release tag is `vMAJOR.MINOR.PATCH`.
- The APK asset is exactly `AF-File-Manager-MAJOR.MINOR.PATCH.apk`.
- A release is never replaced with a differently signed APK.
- Android still requires user confirmation to install an update outside Google Play.
