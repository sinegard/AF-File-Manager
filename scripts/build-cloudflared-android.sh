#!/usr/bin/env bash
set -euo pipefail

version="2026.9.1"
commit="f11dea9cb7079e90a982c1a2d5548ab40847fdcf"
build_time="2026-09-11T13:42:53Z"
go_archive="go1.26.0.linux-amd64.tar.gz"
go_sha256="aac1b08a0fb0c4e0a7c1555beb7b59180b05dfc5a3d62e40e9de90cd42f88235"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_root="${1:-$repo_root/app/src/main/jniLibs}"
ndk_root="${ANDROID_NDK_ROOT:-${ANDROID_HOME:?ANDROID_HOME is required}/ndk/27.3.13750724}"

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

go_exe="$(command -v go || true)"
if [[ -z "$go_exe" ]] || [[ "$($go_exe version)" != *"go1.26."* ]]; then
    curl --fail --location --silent --show-error \
        "https://go.dev/dl/$go_archive" -o "$work/$go_archive"
    printf '%s  %s\n' "$go_sha256" "$work/$go_archive" | sha256sum --check --status
    mkdir -p "$work/go-toolchain"
    tar -xzf "$work/$go_archive" -C "$work/go-toolchain"
    go_exe="$work/go-toolchain/go/bin/go"
fi

source_root="$work/cloudflared"
git init --quiet "$source_root"
git -C "$source_root" remote add origin https://github.com/cloudflare/cloudflared.git
git -C "$source_root" fetch --quiet --depth 1 origin "$commit"
git -C "$source_root" checkout --quiet --detach FETCH_HEAD
test "$(git -C "$source_root" rev-parse HEAD)" = "$commit"

toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
build_target() {
    local abi="$1" goarch="$2" compiler="$3" goarm="${4:-}"
    local destination="$output_root/$abi/libcloudflared.so"
    test -x "$toolchain/$compiler"
    mkdir -p "$(dirname "$destination")"
    (
        cd "$source_root"
        export GOOS=android GOARCH="$goarch" CGO_ENABLED=1 CC="$toolchain/$compiler"
        if [[ -n "$goarm" ]]; then export GOARM="$goarm"; else unset GOARM || true; fi
        "$go_exe" build -trimpath -buildmode=pie \
            -ldflags "-s -w -buildid= -X main.Version=$version -X main.BuildTime=$build_time" \
            -o "$destination" ./cmd/cloudflared
    )
}

build_target arm64-v8a arm64 aarch64-linux-android26-clang
build_target armeabi-v7a arm armv7a-linux-androideabi26-clang 7
build_target x86_64 amd64 x86_64-linux-android26-clang
build_target x86 386 i686-linux-android26-clang

mkdir -p "$repo_root/app/src/main/assets/licenses"
cp "$source_root/LICENSE" "$repo_root/app/src/main/assets/licenses/cloudflared-LICENSE.txt"
