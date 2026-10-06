#!/usr/bin/env bash
# Fork: download the libretro cores listed in app/src/appstore/libretro-cores.txt from the libretro
# buildbot into app/src/appstore/jniLibs/<abi>/lib<core>_libretro.so, so the Google Play build
# (bundleAppstoreRelease) ships them inside the app. Play doesn't allow the app to download them.
#
#   scripts/fetch-libretro-cores.sh            # arm64-v8a and armeabi-v7a, the TV ABIs
#   ABIS="arm64-v8a" scripts/fetch-libretro-cores.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
list="$root/app/src/appstore/libretro-cores.txt"
out="$root/app/src/appstore/jniLibs"
abis="${ABIS:-arm64-v8a armeabi-v7a}"
base="https://buildbot.libretro.com/nightly/android/latest"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

count=0
for abi in $abis; do
  mkdir -p "$out/$abi"
  while read -r core buildbot; do
    case "$core" in ''|'#'*) continue ;; esac
    zip="$tmp/$abi-$core.zip"
    curl -fsSL --retry 4 --retry-delay 5 -o "$zip" "$base/$abi/${buildbot}_libretro_android.so.zip"
    # Each zip holds a single core library, whatever it is named inside
    so="$(unzip -Z1 "$zip" | grep -i '\.so$' | head -n1)"
    [ -n "$so" ] || { echo "no .so in $buildbot ($abi)" >&2; exit 1; }
    unzip -p "$zip" "$so" > "$out/$abi/lib${core}_libretro.so"
    count=$((count + 1))
  done < "$list"
  echo "$abi: $(ls "$out/$abi" | wc -l) cores, $(du -sh "$out/$abi" | cut -f1)"
done
echo "Fetched $count core builds into $out"
