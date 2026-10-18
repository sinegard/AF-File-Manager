# Codec CI experiment

Owner approved the proposed temporary AF GitHub build with "tai sutvarkyk,
nesimato problemos" on 2026-10-18, following the explicit question about a
temporary AF branch, unchanged main, no published release. Risk T2: scoped
CI/build dependency analysis, not runtime app integration or release.

Base main: 6af7eab35fbaebf9be9cd3dc1f930fd53b2a70e2. Preserve all 741 local
uncommitted source/config/test inputs and the real staging index. Use a private
temporary index to construct a commit containing only the standalone workflow,
build script, source downloader and contract. No changes to app code, signing,
settings, phone data, tags, release workflow, main or pre-existing branches.

Scope: reproduce the restricted FFmpeg 9.0.2/LAME 4.0 export payload on Ubuntu
with the exact Android NDK 27.3.13750724, normal speed-oriented -O3 optimization,
no --enable-small. ARM64 is the phone size comparison. Also compile x86_64 for
optional owned headless Android runtime smoke tests. This is not an AF APK.
After the first runtime probe, include raw H.264 demux/mux for testing a bounded
encode-then-remux path: API 26 synchronous MediaCodec/global-header handling
stalled. Preserve that failure and do not label it a working export. Also pin
the FFmpeg version banner to the verified source RELEASE instead of allowing
the nested source archive to inherit the surrounding AF Git commit.
The version is passed through upstream make's REVISION variable and the emitted
header is checked. Include h264_metadata for MediaCodec's mandatory 16-pixel
padding; test a 94 by 62 export to catch missing crop support rather than only
testing already aligned video sizes. These do not add GPL or network features.
Keep existing 256 MiB/1-hour/25-percent runtime contracts unchanged; actual
integration and bounded chunked reverse still require implementation/testing.

Inputs: FFmpeg mirror commit 946fcce07b6dcd0331c8cc609192aeff5e1924f8, signed
n9.0.2 verified in the prior local probe; archive SHA256
0aa2b1de2a5698b20a23e93d539a9a8e82ca0117496c5bdf05d198805f42bb3b.
LAME 4.0 SHA256
3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb.
No GPL/nonfree components or network protocols in the Android payload. Include
matching source archives, build script and LGPL notices in the CI artifact.

Limits: one temporary codex/codec-probe branch, one CI job, two sequential ABI
builds, max four compiler jobs, 40-minute job timeout, 128 MiB per source
download, 1 GiB per source expansion, 12 GiB generated build limit, 25 percent
CI memory reserve guard. CI artifacts expire after three days. No repository
secrets are read. Workflow contents permission is read-only; pinned actions;
checkout does not persist credentials. Runtime diagnostics, if needed, use
one explicitly owned emulator, not a physical phone or main desktop.

Windows Code Integrity block from probe 391 remains recorded; do not weaken,
evade or replace the blocked local executable. Run source compilation in the
owner-approved GitHub environment instead. Preserve earlier failures 375-394.

Validation: local Python/shell/workflow syntax and source hashes before push;
inspect exact commit diff; verify remote branch SHA and successful workflow
head SHA; download exact artifact by ID/digest and validate bounded paths,
manifest/file hashes, ELF ABI/needed imports, required codec/filter/muxer flags,
and APK-payload sizing against the original arm64 APK. Runtime tests must say
what actually ran and never substitute build success for feature acceptance.

Recovery/cleanup: original main and local work remain untouched. The owned
temporary branch may be removed after the artifact and commit are retained and
only if its remote ref still equals the exact owned commit; never remove a
drifted branch. Preserve the local commit, canonical artifact, checksums,
source identities and failed Windows input. Clean only regenerable owned
objects and stopped disposable guest disks, with containment/process/reparse
checks and a recorded recovery source. No broad app/build cleanup.
