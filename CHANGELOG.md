# Changelog

This file records the user-visible changes in AF File Manager releases.

## [Unreleased]

## [0.31.1] - 2026-09-04

### Fixed

- The phone-transfer video category now follows the selected interface language instead of showing a Lithuanian label in English.

## [0.31.0] - 2026-09-04

### Added

- Authenticated phone-to-phone transfers over a trusted local network, with QR or manual pairing and guided selection of files, apps, photos, videos, and music.
- AF File Manager integration with Android's system file picker, plus bookmarks for storage and cloud providers installed on the device.
- A cleanup manager for reviewing unused apps, available app-cache information, and old media without deleting anything automatically.
- Share actions for local files, archive entries, and staged app backups, together with working installed-app export and Android-owned uninstall confirmation.

### Changed

- Main lists now keep their top bars fixed and support pull-to-refresh at the top of the content.
- Quick locations and storage visibility/order are managed from display settings.
- Archive contents use AF's normal list/grid, selection, preview, and action model. Extraction now asks for a destination folder, including when only selected entries are extracted.
- Writable archives can be renamed, moved, or cleaned transactionally so a failed change preserves the original archive.

### Fixed

- Back from Downloads or a favorite opened from Files home now returns to Files home at the correct boundary.
- Network, archive, and creation forms no longer leave an unintended gap at the screen edge.

## [0.30.0] - 2026-09-03

### Changed

- Storage, Quick locations, Favorites, and Tags now remember their own display settings. Quick locations supports up to six columns, with icons aligned across short and long names.
- Small dialogs fit their content; longer forms still scroll and keep their actions above the keyboard. Tapping outside a dialog cancels without applying unfinished changes.

### Fixed

- WebDAV collection addresses keep their trailing slash. Safe redirects within the same server now work, including the `/dav/` redirect used by InfiniCLOUD.
- WebDAV errors show the HTTP status and useful connection or path guidance without exposing credentials. Connection help also uses the selected interface language.
- Opening a terminal from a root folder no longer closes the app. An active Root or Shizuku backend provides the terminal in that folder; unavailable access is explained clearly.
- Shizuku reconnects cleanly when its access mode is switched off and on again.
- Root folder discovery includes more device-specific entries when Android restricts ordinary directory listing.
- Folder-unavailable and unchanged-value labels no longer appear in Lithuanian when English is selected.

## [0.29.0] - 2026-08-31

### Added

- Dedicated Favorites and Tags browsers under a single Tools section on the Files home screen.
- Image zoom controls, continuous PDF page scrolling, and clearer video and audio playback controls.
- Quick access to favorite locations and viewing history from the active folder menu.
- Copy, move, tags, and favorite actions in each local file menu, plus an Add to favorites action for a multi-file selection.

### Changed

- Storage, Quick locations, and Tools now use a clearer three-row home layout with configurable list or grid presentation.
- Large forms, selectors, file information, connection setup, create, rename, transfer, tagging, sharing, and storage-selection windows now use AF's shared full-size dialog layout.
- Tab and file action menus now use consistent, descriptive icons, including a distinct Installed apps shortcut icon.

### Fixed

- Back from a root folder opened directly from Files home now returns to Files home instead of an unrelated storage screen.
- Media preview controls now remain usable without obscuring the viewed content.

## [0.28.0] - 2026-08-30

### Added

- Safe Cleanup can select duplicate copies in one action while always leaving one file from every verified duplicate group unselected.

### Changed

- When Android blocks direct enumeration of the system root, the normal browser now shows a bounded list of root entries that the device confirms exist and marks unreadable entries with a lock.
- The README is shorter and clearer about the project's personal origins, non-commercial license, and user responsibility.

### Fixed

- Back navigation from a directly opened protected `Android/data` subfolder now moves to its allowed parent before closing the protected-files browser.
- The protected-files Up action no longer attempts to navigate outside the active Root or Shizuku access boundary.

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

[0.31.1]: https://github.com/sinegard/AF-File-Manager/compare/v0.31.0...v0.31.1
[0.31.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.30.0...v0.31.0
[0.30.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.29.0...v0.30.0
[0.29.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.28.0...v0.29.0
[0.28.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.27.0...v0.28.0
[0.27.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.26.0...v0.27.0
[0.26.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.25.4...v0.26.0
