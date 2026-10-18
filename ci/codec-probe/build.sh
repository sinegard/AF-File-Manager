#!/usr/bin/env bash
set -euo pipefail
umask 022
root="$PWD/codec-probe"
test ! -e "$root"
mkdir -p "$root/dist/sources" "$root/dist/build-source"
python3 ci/codec-probe/fetch-sources.py "$root"
# The source archive is nested in another Git checkout. Do not let upstream's
# version.sh mistake the surrounding AF commit for the FFmpeg source version.
revision="$(cat "$root/sources/FFmpeg-946fcce07b6dcd0331c8cc609192aeff5e1924f8/RELEASE")"
test "$revision" = 9.0.2
export revision
cp "$root/downloads/"*.tar.gz "$root/dist/sources/"
cp ci/codec-probe/build.sh ci/codec-probe/fetch-sources.py ci/codec-probe/CHANGE_CONTRACT.md "$root/dist/build-source/"
cp "$root/source-manifest.json" "$root/dist/"
llvm="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$llvm:$PATH"
grep -q 'Pkg.Revision = 27.3.13750724' "$ANDROID_NDK_ROOT/source.properties"
{
  echo "source=$GITHUB_SHA"
  echo "runner-image=${ImageOS:-unknown}/${ImageVersion:-unknown}"
  clang --version
  gcc --version
  make --version
  python3 --version
  cat "$ANDROID_NDK_ROOT/source.properties"
} > "$root/dist/build-environment.txt"

guard() {
  awk '/MemTotal:/{t=$2}/MemAvailable:/{a=$2}END{exit !(t>0 && a/t>=0.25)}' /proc/meminfo
  test "$(du -sk "$root" | cut -f1)" -lt 12582912
}

for abi in arm64-v8a x86_64; do
  guard
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android; arch=aarch64; host=aarch64-linux-android; asm=();;
    x86_64) triple=x86_64-linux-android; arch=x86_64; host=x86_64-linux-android; asm=(--disable-x86asm);;
  esac
  # x86_64 is an explicit functional-smoke ABI, not a shipping speed claim.
  # ARM64 keeps normal NEON assembly and speed-oriented -O3 optimization.
  build="$root/build-$abi"
  prefix="$root/prefix-$abi"
  out="$root/dist/$abi"
  mkdir -p "$build/lame" "$build/ffmpeg" "$prefix/include/lame" "$prefix/lib" "$out/lib" "$out/config"
  export CC="$llvm/clang --target=${triple}26"
  export CXX="$llvm/clang++ --target=${triple}26"
  export AR="$llvm/llvm-ar" RANLIB="$llvm/llvm-ranlib" NM="$llvm/llvm-nm" STRIP="$llvm/llvm-strip"
  export CFLAGS='-O3 -fPIC -ffunction-sections -fdata-sections -Wno-implicit-function-declaration'
  export LDFLAGS='-Wl,--gc-sections -Wl,-z,max-page-size=16384'
  export ac_cv_prog_cc_c23=no
  export ac_cv_func_malloc_0_nonnull=yes ac_cv_func_realloc_0_nonnull=yes
  (
    cd "$build/lame"
    "$root/sources/lame-4.0/configure" --host="$host" --build=x86_64-pc-linux-gnu \
      --prefix="$prefix" --disable-shared --enable-static --disable-frontend --disable-decoder \
      --disable-nasm --disable-dependency-tracking
    make -j4 -C libmp3lame
    cp libmp3lame/.libs/libmp3lame.a "$prefix/lib/"
    cp "$root/sources/lame-4.0/include/lame.h" "$prefix/include/lame/"
    cd "$build/ffmpeg"
    "$root/sources/FFmpeg-946fcce07b6dcd0331c8cc609192aeff5e1924f8/configure" \
      --prefix="$prefix" --target-os=android --arch="$arch" --enable-cross-compile \
      --cc="$CC" --cxx="$CXX" --as="$CC" --ld="$CC" --ar="$AR" --ranlib="$RANLIB" --nm="$NM -g" --strip="$STRIP" \
      --host-cc=gcc --pkg-config=pkg-config \
      --extra-cflags="-I$prefix/include -ffunction-sections -fdata-sections" \
      --extra-ldflags="-L$prefix/lib -Wl,--gc-sections -Wl,-z,max-page-size=16384" \
      --disable-autodetect --disable-everything --disable-network --disable-doc --disable-debug \
      --disable-gpl --disable-nonfree --disable-static --enable-shared --disable-small --optflags=-O3 \
      --disable-programs --enable-ffmpeg --disable-ffprobe --disable-avdevice \
      --enable-avfilter --enable-swscale --enable-swresample --enable-jni --enable-mediacodec --enable-libmp3lame \
      --enable-protocol=file,pipe \
      --enable-demuxer=mov,matroska,h264,mp3,flac,asf,wav,pcm_s16le \
      --enable-muxer=mp4,mov,matroska,h264,mp3,flac,asf,wav,pcm_s16le \
      --enable-decoder=aac,h264,mp3,mp3float,flac,wmav1,wmav2,pcm_s16le,rawvideo \
      --enable-encoder=aac,h264_mediacodec,libmp3lame,flac,wmav2,pcm_s16le,rawvideo \
      --enable-parser=aac,h264,mpegaudio,flac \
      --enable-bsf=aac_adtstoasc,h264_mp4toannexb,extract_extradata \
      --enable-filter=buffer,abuffer,buffersink,abuffersink,format,aformat,scale,fps,trim,atrim,setpts,asetpts,reverse,areverse,afftdn,volume,afade,aecho,atempo,aresample,crop,pad,transpose,anull,null,split,asplit,concat \
      "${asm[@]}"
    make -j4
    for lib in avutil avcodec avformat avfilter swresample swscale; do
      cp "lib$lib/lib$lib.so" "$out/lib/lib$lib.so"
      "$STRIP" --strip-unneeded "$out/lib/lib$lib.so"
    done
    cp ffmpeg "$out/lib/libffmpeg-cli.so"
    cp config.h config_components.h ffbuild/config.mak "$out/config/"
    for binary in "$out/lib/"*.so; do "$llvm/llvm-readelf" -h -d -l "$binary" > "$out/config/$(basename "$binary").elf.txt"; done
  ) 2>&1 | tee "$out/build.log"
  guard
done
cp "$root/sources/FFmpeg-946fcce07b6dcd0331c8cc609192aeff5e1924f8/COPYING.LGPLv2.1" "$root/dist/FFmpeg-LGPL-2.1.txt"
cp "$root/sources/lame-4.0/COPYING" "$root/dist/LAME-LGPL-2.txt"
python3 - "$root/dist" <<'PY'
import hashlib, json, os, pathlib, sys, zipfile
root = pathlib.Path(sys.argv[1])
required = ('LIBMP3LAME_ENCODER','FLAC_ENCODER','WMAV2_ENCODER','H264_MEDIACODEC_ENCODER','AAC_ENCODER','MOV_MUXER','MATROSKA_MUXER','MP3_MUXER','FLAC_MUXER','ASF_MUXER','REVERSE_FILTER','AREVERSE_FILTER','AFFTDN_FILTER')
files = []
for abi in ('arm64-v8a', 'x86_64'):
    components = (root / abi / 'config/config_components.h').read_text()
    config = (root / abi / 'config/config.h').read_text()
    for key in required:
        assert f'#define CONFIG_{key} 1\n' in components, (abi, key)
    for key in ('GPL','NONFREE','NETWORK','SMALL'):
        assert f'#define CONFIG_{key} 0\n' in config, (abi, key)
    with zipfile.ZipFile(root / f'{abi}-payload.zip','x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for file in sorted((root / abi / 'lib').glob('*.so')):
            archive.write(file, f'lib/{abi}/{file.name}')
        archive.write(root / 'FFmpeg-LGPL-2.1.txt', 'assets/licenses/af-media-export/FFmpeg-LGPL-2.1.txt')
        archive.write(root / 'LAME-LGPL-2.txt', 'assets/licenses/af-media-export/LAME-LGPL-2.txt')
for file in sorted(root.rglob('*')):
    if file.is_file():
        data = file.read_bytes()
        files.append({'path':file.relative_to(root).as_posix(),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
(root / 'manifest.json').write_text(json.dumps({'sourceCommit':os.environ['GITHUB_SHA'],'ndk':'27.3.13750724','androidApi':26,'optimization':'-O3; small disabled; ARM64 NEON enabled; x86_64 smoke without external assembler','runtimeTested':False,'integrated':False,'files':files},indent=2)+'\n')
assert (root / 'arm64-v8a-payload.zip').stat().st_size < 10_000_000, 'phone payload exceeds approved size target'
print('ARM64 compressed payload bytes:', (root / 'arm64-v8a-payload.zip').stat().st_size)
PY
