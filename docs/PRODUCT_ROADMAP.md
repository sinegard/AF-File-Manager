# AF File Manager product direction

This roadmap is designed for Android rather than copied blindly from desktop file managers. Its priorities are data safety, a fast everyday workflow, a clear small-screen interface, and useful features without an account, advertising, or telemetry.

## Available foundation

- Single and dual panes, independent tabs, local and Storage Access Framework locations, and Android-visible SD or USB storage.
- A bounded copy and move queue with conflict policies, pause, resume, retry, cancellation, and optional SHA-256 verification.
- Recoverable trash, previews, continuous PDF reading, and archive browsing or creation.
- SMB, SFTP, HTTPS WebDAV, FTP, and FTPS with bidirectional recursive transfers and synchronization-plan previews.
- Unified long-press selection for local and remote lists, a select-all/deselect-all toggle, and grouped transfers in both directions.
- Cross-location copy/paste between the active local folder and the current remote folder, plus a navigable multi-selection upload picker.
- Unified local and remote browser controls, including navigation history, system Back, breadcrumbs, list/grid layouts, hidden-file filtering, folders-first sorting, and consistent item/selection menus.
- SHA-256 duplicate analysis, large or old files, empty folders, folder-size analysis, tags, ratings, and saved searches.
- Review-first cleanup for large files, installer/archive leftovers, exact duplicates, empty folders, and locally detected similar photos, with no automatic deletion.
- Optional, disabled-by-default `Android/data` and `Android/obb` browsing through an explicitly granted Shizuku or compatible root service.
- English as the default interface plus Lithuanian, with no translation of user data.
- No advertising, mandatory account, analytics, or tracking SDK.

## Implemented productivity work

### Safe batch work and search

- Batch rename with a mandatory `old name → new name` preview, conflict validation, two-phase execution, and undo for the last safely reversible batch.
- Search in the current folder or all Android-visible storage by name or regex, type, size, dates, and tags.
- Search results behave as a virtual work folder: users can select, copy, move, trash, batch rename, save filters, and reveal the real location.
- Folder scans are cancellable, and an outdated slower result cannot replace a newer location.

### Performance and durable operations

- Progressive directory listing presents names and types first and fills metadata later.
- Durable copy and move plans survive process restart and retain bounded error reports.
- A failed item can be retried or skipped according to the selected policy.
- Move sources are removed only after the requested verification succeeds.
- Operations expose only undo actions that can still be proved safe from the current file state.

### Workspaces and transfer

- Independent tabs in each pane support locking, duplication, closing, reopening, history, pane swapping, and folder comparison.
- On touch screens, an explicit **Copy to other pane** action avoids unreliable drag-and-drop while preserving the same conflict preview.
- A private-LAN web session shares one selected folder with a one-time code, foreground-service visibility, atomic uploads, a 15–60 minute lifetime, and an explicit stop action.

### Metadata and storage analysis

- Hierarchical colored tags, ratings, JSON import/export, and tag-filtered smart searches.
- Background folder-size and file-type visualization without automatic deletion.
- Duplicate groups require explicit copy selection before anything moves to recoverable trash.

## Deliberately later

- Local AI or semantic search only after durable operations, sessions, and transfer safety remain proven at larger scale.
- Optional cloud-provider OAuth accounts require a separate threat model and credential lifecycle.
- ADB remains an external development tool rather than an in-app access mode. Android restrictions around `Android/data` and `Android/obb` must continue to be represented honestly when neither Shizuku nor compatible root access is active.
- A portable Windows or Linux build would be a separate product and architecture, not a property of the Android APK.
