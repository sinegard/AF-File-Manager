# Releasing AF File Manager

The Android package is `com.affilemanager.app`. Every public APK must be signed by the same dedicated release key; changing or losing the key breaks the Android update chain.

## Automated GitHub release

1. Update `versionCode` and stable `versionName` in `app/build.gradle.kts`.
2. Regenerate the Baseline/Startup Profiles when critical user journeys changed, then run the full local validation and performance gate on the dedicated emulator.
3. Commit and push the version change to `main`.
4. Create and push the exact matching tag, for example `v0.9.5`.
5. The `Publish signed APK` workflow validates that the tag matches Gradle, compiles the pinned `cloudflared` source for all four supported Android ABIs, runs JVM tests and lint, builds with the repository signing secrets, and prepares a draft release containing the ABI-specific APKs, a universal compatibility APK, and their SHA-256 files.
6. Download the exact draft APK for the test device ABI, verify its package/version, checksum, packaged ABI and signing certificate, and exercise affected optimized runtime paths on the test device. In particular, JNI/Binder and network checks must use the downloadable artifact, not just a local rebuild. Keep failures visible and leave the release as a draft until they are resolved.
7. Add concise release notes from `CHANGELOG.md`, then publish the draft. Only a published stable release is offered by the in-app updater.

Required repository secrets:

- `AF_RELEASE_KEYSTORE_BASE64`
- `AF_KEYSTORE_PASSWORD`
- `AF_KEY_ALIAS`
- `AF_KEY_PASSWORD`

The private key must never be committed, attached to an issue, or included in a release asset.

## Local owner build

The owner machine keeps the key outside the checkout under `%USERPROFILE%\.android\af-file-manager-signing`. Its password is stored in a Windows DPAPI-protected credential file. The release script requires an explicit emulator serial and runs the performance gate before loading signing credentials:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1 -EmulatorSerial emulator-5554
```

To update the checked-in profiles after a startup or navigation change:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-baseline-profile.ps1 -EmulatorSerial emulator-5554
```

`scripts/run-performance-gate.ps1` refuses physical-device serials, builds the minified candidate only for the emulator's ABI, and checks the resulting Macrobenchmark JSON against `performance/budgets.json`. The ABI match keeps the measurement representative of the corresponding release APK instead of the larger universal compatibility package. Emulator results are used only as a repeatable regression guard; release decisions for perceived speed should still include direct physical-device use.

The script exposes signing values only to the child Gradle process and clears the temporary environment variables afterward.

The local release path also runs `scripts/build-cloudflared-android.ps1`. Set `AF_GO_EXE` to a Go 1.26 executable and keep Android NDK `27.3.13750724` installed. Generated native binaries and the copied upstream license are ignored build inputs rather than repository blobs.

## Update invariants

- GitHub Releases must be public and stable, not draft or prerelease.
- The release tag is `vMAJOR.MINOR.PATCH`.
- ABI assets are named `AF-File-Manager-MAJOR.MINOR.PATCH-ABI.apk`; the universal compatibility asset remains `AF-File-Manager-MAJOR.MINOR.PATCH.apk`.
- The updater must select the first published asset matching `Build.SUPPORTED_ABIS`, falling back only to the universal APK.
- A release is never replaced with a differently signed APK.
- Android still requires user confirmation to install an update outside Google Play.
