# Media export backend

AF keeps Media3 for its shared media editor, playback and platform transforms.
The optional native export worker supplies MKV, MOV, MP3, FLAC, WMA and FFT
denoise. It runs only during an explicit media operation, without native
network protocols, an account or background indexing.

## Reproducible native input

The source recipe is `scripts/media-export/build.sh`, with exact archive hashes
in `fetch-sources.py`. It uses FFmpeg 9.0.2 commit
`946fcce07b6dcd0331c8cc609192aeff5e1924f8`, LAME 4.0 and Android NDK
`27.3.13750724`, API 26. GPL and nonfree components are disabled. ARM builds
retain native assembly, while x86 variants use explicitly configured O3 C
paths. Native binaries are generated build inputs, never committed source.

On the Linux CI builder, install the pinned NDK, set `ANDROID_NDK_ROOT` and run
`bash scripts/media-export/build.sh` from the repository root. The output is
under `app/build/native-media/dist`, including binary/config/source hashes,
corresponding upstream source archives, license texts and this exact recipe.
Stage a verified result with `scripts/media-export/stage-payload.ps1` (Windows)
or `python3 scripts/media-export/stage-payload.py <dist> --source-commit <builder-commit>`
(Linux). Both reject a missing, changed or incomplete four-ABI payload. Gradle
compiles the small API26 launcher from `media_exec.c` with the same pinned NDK,
then checks the staged libraries before packaging. A fresh Windows checkout
needs that verified Linux-built input before its normal APK assembly.

## Licensing

This software uses FFmpeg under LGPL 2.1 or later and LAME under its LGPL
terms. Their licenses are independent of AF's own license and restrictions;
AF's non-commercial restriction must not be applied to these components.
The matching upstream source archives and complete build recipe must accompany
every release containing the worker, in a separate source asset, with checksums.
Do not substitute a different FFmpeg build or omit corresponding sources.

The APK contains the original notices and component/source identities. Users
can rebuild or modify the independent worker from the accompanying sources.
Replacing its native libraries requires rebuilding/signing one's own APK; AF
does not execute binaries downloaded into writable storage.

## Processing and safety

All sources use the existing private edit session and shared Save As/conflict
workflow. An export does not change or implicitly overwrite its original.
Existing 256 MiB per-file, one-hour input and one-export limits remain. A job
owns bounded private intermediate files; close, cancel and failure remove them.
The file browser does not start/load the native backend during normal browsing.

Reverse video is chunked rather than retaining the whole movie in RAM. Its
output timestamps must use the source presentation durations, including VFR;
do not infer the final timestamps from a raw H.264 stream. Encoded chunks use
an intermediary container only to recover packet boundaries; the final muxer
assigns every reversed source-frame duration explicitly. Clip duration is an
input option, before the reverse filter, not an output truncation. Direct synchronous MediaCodec MP4
initialization was observed to stall on API26, so chunk encoding avoids that
path. Crop padding requires the enabled `h264_metadata` bitstream filter.
The final timeline uses the already included Media3 MP4 writer: Android 8's
platform muxer can ignore the explicit last-frame duration and duplicate the
previous VFR duration. The regression decodes every frame, including the last,
instead of depending on the older platform's unreliable final-frame thumbnail.

Codec availability and maximum resolution still depend on the device. A mode
that exceeds device, memory or disk capability fails explicitly rather than
silently changing resolution, format or quality. Transcoding is not lossless,
and a smaller output is never guaranteed. Track-copy mode is separate and is
available only when no incompatible transform is selected.

WAV export and the bounded reverse-audio PCM intermediate use 16-bit samples.
FLAC compresses the supplied PCM without further loss; choosing FLAC does not
restore precision lost during editing or make a lossy source lossless. No
sample-depth conversion control is advertised by this editor.

## Integrated test builds

Before manually installing the debug app and its instrumentation harness,
build both `:app:assembleDebug` and `:app:assembleDebugAndroidTest`.
The latter alone can compile updated production classes for the harness while
leaving an older installable `app-debug.apk` behind. Record the exact installed
APK hashes, then run the real AF UI and shared Save As workflows on both APIs.
