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
Stage a verified result with `scripts/media-export/stage-payload.ps1`.

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
do not infer them from a raw H.264 stream. Direct synchronous MediaCodec MP4
initialization was observed to stall on API26, so chunk encoding avoids that
path. Crop padding requires the enabled `h264_metadata` bitstream filter.

Codec availability and maximum resolution still depend on the device. A mode
that exceeds device, memory or disk capability fails explicitly rather than
silently changing resolution, format or quality. Transcoding is not lossless,
and a smaller output is never guaranteed. Track-copy mode is separate and is
available only when no incompatible transform is selected.
