# Changelog

This file records the user-visible changes in AF File Manager releases.

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

[0.26.0]: https://github.com/sinegard/AF-File-Manager/compare/v0.25.4...v0.26.0
