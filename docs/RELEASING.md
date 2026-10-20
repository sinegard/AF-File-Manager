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
8. After verifying the published assets and update metadata, perform the local cleanup below. A failed or draft release is not a successful release.

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

The owner approved fixed RSSAnon ceilings of **150 MiB (153,600 KiB)** for cold start and **200 MiB (204,800 KiB)** for large-directory first content on 2026-10-05. These use the unchanged 0.43.0 candidate's seven-iteration peaks of 120,852 and 162,112 KiB, with approximately 25% headroom rounded up to the proposed MiB values. This is a test budget, not reserved Android RAM or a guarantee for low-memory phones. All other limits and the seven-iteration requirement remain unchanged. Do not automatically increase these ceilings from later results; a change needs a separately justified owner decision. Preserve the original failed result when re-evaluating a saved report under this approved budget, and do not describe that re-evaluation as a new measurement or a memory improvement.

The script exposes signing values only to the child Gradle process and clears the temporary environment variables afterward.

The local release path also runs `scripts/build-cloudflared-android.ps1`. Set `AF_GO_EXE` to a Go 1.26 executable and keep Android NDK `27.3.13750724` installed. Generated native binaries and the copied upstream license are ignored build inputs rather than repository blobs.

## Update invariants

- GitHub Releases must be public and stable, not draft or prerelease.
- The release tag is `vMAJOR.MINOR.PATCH`.
- ABI assets are named `AF-File-Manager-MAJOR.MINOR.PATCH-ABI.apk`; the universal compatibility asset remains `AF-File-Manager-MAJOR.MINOR.PATCH.apk`.
- The updater must select the first published asset matching `Build.SUPPORTED_ABIS`, falling back only to the universal APK.
- A release is never replaced with a differently signed APK.
- Android still requires user confirmation to install an update outside Google Play.

## Local cleanup after a successful release

Stop the release's owned test guests after collecting their evidence. Remove
their disposable data/cache disks, regenerable compiler intermediates, obsolete
helper environments and redundant candidate copies. Also dispose of a completed
test stage's temporary disks rather than retaining one disk per retry.

Keep the current and previous release bundles locally: APKs, checksums, source
and signing identity, mappings and matching validation records. Before removing
an older or duplicate binary, verify a retained canonical copy or the exact
published asset by checksum and record that recovery location. Preserve unique
unresolved failure evidence and inputs; never change historical results.

Inventory exact contained paths and check for active processes and reparse
points before deletion. Do not run a blanket cleanup of `app/build`, which may
also contain required toolchains and evidence. Source, uncommitted work, signing
keys, required SDK/JDK/AVD masters, user files and phone data are excluded. Record
the removed paths and actual free-space gain in ignored local validation output.
