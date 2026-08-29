# Changelog

This file records the user-visible changes in AF File Manager releases.

## [Unreleased]

## [0.27.0] - 2026-08-29

### Added

- SD card and USB location badges for files that are actually stored on removable volumes.
- Real thumbnails and file-type icons in Cleanup Review.
- One action that analyzes internal storage and every currently connected SD card or USB drive together.
- Long-press and drag selection in both phone and server folders, including edge scrolling for longer lists.
- Per-item compression, explicit archive opening, and empty ZIP, 7Z, TAR, or TAR.GZ creation from the New item dialog.
- Calculated file and folder counts, combined size, and partial-scan warnings in the Information dialog for one or many selected items.
- Analyze actions for the current folder and for a selection containing files, folders, or both.
- Optional Aura, Tokyo, and Yin Yang color palettes.

### Changed

- Disconnected SD cards and USB drives are hidden instead of remaining as disabled placeholders, and the storage list refreshes when media is attached, removed, or the app resumes.
- Root storage opens in the normal file browser without requiring privileged mode; entries that Android cannot read are marked with a lock.
- Analysis and Cleanup Review cards use the same surface level as the storage overview.
- The Android status and navigation bars now follow the selected palette and use light or dark icons with the correct contrast.

### Fixed

- The New item dialog now shows the actual selected Folder, File, or Archive option.
- Opening a file from Cleanup Review now shows its normal preview and returns to the same cleanup screen afterward.
- Device UI diagnostics now use private temporary device storage and remove their XML hierarchy dump after capture instead of leaving files in shared phone storage.

## [0.26.0] - 2026-08-29

### Added

- 59 offline interface languages with English as the default, a searchable language picker, and right-to-left layout support for Arabic, Hebrew, Persian, and Urdu.
- A selectable Material Blue color palette for both light and dark themes. Existing palettes remain available and no palette is forced on the user.
- In-place folder browsing in Cleanup Review, including folder contents, calculated folder sizes, selection totals, retry handling, and normal back navigation.

### Changed

- Folder-size work in Cleanup Review now runs with bounded scans and cached results so large or inaccessible trees cannot block the interface indefinitely.
- Cleanup selection avoids overlapping parent and child entries, making the final delete preview clearer and safer.
- Privacy and third-party notices now explain how the bundled offline translations are produced and used. File names, paths, server names, and user-entered text are never translated.

### Fixed

- Fixed an Android-specific regular-expression incompatibility that could close the app during startup after multilingual support was added.

English and Lithuanian are maintained directly. The other bundled translations were generated offline and may still need corrections from native speakers.

[0.27.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.26.0...v0.27.0
[0.26.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.25.4...v0.26.0
