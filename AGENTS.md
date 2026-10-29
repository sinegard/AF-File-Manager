# AF File Manager working agreements

Follow the applicable global instructions and `docs/RELEASING.md`.

## Generated-file cleanup

The owner requires cleanup after every successfully published and verified
release. Do not let temporary validation files accumulate between releases.

- Keep at most one active, explicitly owned test emulator. Once a test stage
  has finished and its evidence has been collected, stop that guest and remove
  its disposable data/cache disks unless an active investigation needs them.
  Record any temporary retention reason; do not keep a new disk for every retry.
- After release, remove inactive owned guest disks, regenerable compiler
  intermediates, obsolete helper environments and redundant candidate copies.
  First verify the exact targets and their recovery source. Never clean the
  whole workspace or `app/build`: required toolchains and evidence also live there.
- Keep the current and previous release bundles locally, including their APKs,
  checksums, source identity, signing identity and matching validation records.
  An older or duplicate binary may be removed only after a retained canonical
  copy or exact published asset has been verified by checksum and recorded.
- Preserve unresolved failure evidence and any unique inputs needed to reproduce
  it. Cleanup must not erase a failure, relabel it, or alter an accepted record.
- Never remove source, uncommitted work, signing material, required SDK/JDK/AVD
  masters, user files or phone data. Check for active processes and reparse
  points, use contained literal paths, and record what was removed and the
  measured space recovered.
