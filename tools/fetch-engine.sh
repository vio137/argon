#!/usr/bin/env bash
# Fetch the pinned Servo engine nightly and install the native libs.
#
# Provenance (also in app/src/main/assets/NOTICES.txt):
#   Engine:  Servo, servo/servo@b6c9df76bf2d79d479e4b0162f7ecb383d32d2fd
#   Binary:  servoshell nightly 2026-11-05 (servo-aarch64-android.apk)
#   License: MPL-2.0 (see LICENSE)
set -euo pipefail

URL="https://download.servo.org/nightly/android/servo-aarch64-android.apk"
PINNED_SHA256="22c470fc49207cfef7ff79e9cce370247d30f8cc14a229629b162debae994278"
ENGINE_PIN="b6c9df76bf2d79d479e4b0162f7ecb383d32d2fd"
OUT_DIR="$(dirname "$0")/../app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading Servo nightly (servoshell, arm64)..."
curl -fSL "$URL" -o "$TMP/engine.apk"

echo "$PINNED_SHA256  $TMP/engine.apk" | sha256sum -c - || {
  echo
  echo "ERROR: the nightly at $URL no longer matches the pinned build." >&2
  echo "Argon releases are built against servo/servo@$ENGINE_PIN" >&2
  echo "(nightly 2026-11-05, sha256 $PINNED_SHA256)." >&2
  echo "The byte-exact nightly APK used for each Argon release is attached to" >&2
  echo "that release on GitHub. Download it from the Releases page, place it at" >&2
  echo "$TMP is gone - save it as engine.apk and re-run with:" >&2
  echo "  ENGINE_APK=/path/to/engine.apk $0" >&2
  exit 1
}

APK="${ENGINE_APK:-$TMP/engine.apk}"
if [ -n "${ENGINE_APK:-}" ]; then
  echo "${PINNED_SHA256}  ${ENGINE_APK}" | sha256sum -c -
fi

mkdir -p "$OUT_DIR"
unzip -o -j "$APK" "lib/arm64-v8a/libservoshell.so" "lib/arm64-v8a/libc++_shared.so" -d "$OUT_DIR"
echo "Installed: $(ls -lh "$OUT_DIR" | tail -n +2)"
