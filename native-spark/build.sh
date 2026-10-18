#!/usr/bin/env bash
# Reproduce ../libs/hench-spark-bindings.aar: the official Breez Spark
# bindings-android 0.26.1 AAR with its native lib rebuilt from the audited,
# dependency-remediated source at commit eab30bae. See README.md.
#
# Every external input is pinned and SHA-256-verified (checksums.txt).
# Run:  ./build.sh          (parent step only — nothing else builds this)
set -euo pipefail
cd "$(dirname "$0")"

# ---- pinned inputs (see checksums.txt) --------------------------------------
SDK_COMMIT=eab30baed1122824a33dc03ee81ef3d9454f5580   # breez/spark-sdk 0.26.1
AAR_URL="https://mvn.breez.technology/releases/breez_sdk_spark/bindings-android/0.26.1/bindings-android-0.26.1.aar"
AAR_FILE=bindings-android-0.26.1.aar
ROOT="$(pwd)"; LIBS="$(dirname "$ROOT")/libs"   # hench/libs next to native-spark
SRC_TGZ="spark-sdk-${SDK_COMMIT}.tar.gz"
SRC_URL="https://github.com/breez/spark-sdk/archive/${SDK_COMMIT}.tar.gz"
PROTOC_ZIP=protoc-27.5-linux-x86_64.zip
PROTOC_URL="https://github.com/protocolbuffers/protobuf/releases/download/v27.5/${PROTOC_ZIP}"
RUST_TOOLCHAIN=1.95.0            # rust-toolchain.toml pin in the SDK tree
NDK_VER=28.2.13676358            # NDK r28c; r28+ links 16KiB-aligned ELF
FEATURES="db-storage-api,secp-lowmemory,release-max-level-debug"  # upstream mobile recipe
CRATE_DIR=crates/breez-sdk/bindings
SONAME=libbreez_sdk_spark_bindings.so
SYS30=libsqlite3-sys-0.30.1.crate    # [patch] skeleton: keeps crate version 0.30.1
SYS38=libsqlite3-sys-0.38.2.crate    # payload donor: bundles SQLite 3.53.2
SYS_URL="https://static.crates.io/crates/libsqlite3-sys"

VENDOR="$(pwd)/vendor"; WORK="$(pwd)/work"; OUT="$(pwd)/out"
mkdir -p "$VENDOR" "$WORK" "$OUT"

die() { echo "ERROR: $*" >&2; exit 1; }

# ---- 1. fetch + verify all remote inputs ------------------------------------
fetch() {  # fetch <file> <url>  (idempotent: reuses verified vendor copy)
  if [ ! -f "$VENDOR/$1" ]; then
    echo ">> fetch $1"
    curl -fL --retry 3 -o "$VENDOR/$1.tmp" "$2"
    mv "$VENDOR/$1.tmp" "$VENDOR/$1"
  fi
}
fetch "$AAR_FILE" "$AAR_URL"
fetch "$SRC_TGZ" "$SRC_URL"
fetch "$PROTOC_ZIP" "$PROTOC_URL"
fetch "$SYS30" "$SYS_URL/$SYS30"
fetch "$SYS38" "$SYS_URL/$SYS38"
(cd "$VENDOR" && sha256sum -c ../checksums.txt) \
  || die "checksum mismatch — vendor/ contents do not match checksums.txt"

# ---- 2. private toolchain prerequisites (no pip, no sudo) --------------------
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/$NDK_VER}"
[ -d "$NDK/toolchains/llvm" ] \
  || die "Android NDK $NDK_VER not found at $NDK — set ANDROID_NDK_HOME"
export ANDROID_NDK_HOME="$NDK"   # bindings build.rs needs it for x86_64 workaround
command -v cargo-ndk >/dev/null \
  || die "cargo-ndk missing — install once: cargo install cargo-ndk --version 4.1.2 --locked"

PROTOC="$WORK/tools/protoc/bin/protoc"
if [ ! -x "$PROTOC" ]; then
  mkdir -p "$WORK/tools/protoc"
  unzip -o -q "$VENDOR/$PROTOC_ZIP" -d "$WORK/tools/protoc"
fi
export PROTOC                          # prost-build honours $PROTOC
export PROTOC_INCLUDE="$WORK/tools/protoc/include"   # google/protobuf/* imports
"$PROTOC" --version

# ---- 3. source tree: shallow git fetch (provenance + built's git2), ---------
# ----    tarball fallback when git protocol is unavailable            --------
SRC="$WORK/spark-sdk"
if [ ! -d "$SRC" ]; then
  ok=
  if git init -q "$SRC" 2>/dev/null \
     && git -C "$SRC" remote add origin https://github.com/breez/spark-sdk \
     && git -C "$SRC" fetch -q --depth 1 origin "$SDK_COMMIT"; then
    git -C "$SRC" checkout -q FETCH_HEAD && ok=1
  fi
  if [ -z "$ok" ]; then
    rm -rf "$SRC"; mkdir -p "$SRC"
    echo ">> using checksum-verified source tarball"
  fi
fi
if [ -e "$SRC/.git" ]; then
  head_sha="$(git -C "$SRC" rev-parse HEAD)"
  [ "$head_sha" = "$SDK_COMMIT" ] || die "source HEAD $head_sha != pinned $SDK_COMMIT"
else
  tar xzf "$VENDOR/$SRC_TGZ" -C "$SRC" --strip-components=1
  head_sha=tarball-extract
fi
echo ">> source: $head_sha"

# ---- 4. remediated dependency patch + lockfile (SparkRustRemediation) -------
PATCH="$(pwd)/dependencies.patch"; LOCK="$(pwd)/Cargo.lock"
[ -f "$PATCH" ] || die "missing $PATCH — SparkRustRemediation output required"
[ -f "$LOCK" ]  || die "missing $LOCK  — SparkRustRemediation output required"
if [ -e "$SRC/.git" ]; then
  git -C "$SRC" checkout -q -- .            # idempotent re-runs
  if git -C "$SRC" apply --check "$PATCH" 2>/dev/null; then
    git -C "$SRC" apply "$PATCH"
  elif git -C "$SRC" apply -R --check "$PATCH" 2>/dev/null; then
    echo ">> dependencies.patch already applied"
  else
    die "dependencies.patch does not apply to $SDK_COMMIT"
  fi
else
  if patch -d "$SRC" -p1 --dry-run < "$PATCH" >/dev/null 2>&1; then
    patch -d "$SRC" -p1 < "$PATCH"
  elif patch -d "$SRC" -p1 -R --dry-run < "$PATCH" >/dev/null 2>&1; then
    echo ">> dependencies.patch already applied"
  else
    die "dependencies.patch does not apply to source tree"
  fi
fi
cp "$LOCK" "$SRC/Cargo.lock"

# ---- 4a. libsqlite3-sys [patch] crate: 0.30.1 skeleton + SQLite 3.53.2 ------
# rusqlite 0.32.1 (kept for rusqlite_migration 1.3.1) requires ^0.30.1 and no
# newer 0.30.x exists, so dependencies.patch points [patch.crates-io] at
# ../libsqlite3-sys: the published 0.30.1 crate whose sqlite3/ payload is
# replaced by 0.38.2's (SQLite 3.53.2; advisory review dated 2026-10-18).
SYS_PATCH="$WORK/libsqlite3-sys"
rm -rf "$SYS_PATCH"
tar xzf "$VENDOR/$SYS30" -C "$WORK"
mv "$WORK/libsqlite3-sys-0.30.1" "$SYS_PATCH"
tar xzf "$VENDOR/$SYS38" -C "$WORK" "libsqlite3-sys-0.38.2/sqlite3"
rm -rf "$SYS_PATCH/sqlite3"
mv "$WORK/libsqlite3-sys-0.38.2/sqlite3" "$SYS_PATCH/sqlite3"
rm -rf "$WORK/libsqlite3-sys-0.38.2"
grep -q 'define SQLITE_VERSION        "3.53.2"' "$SYS_PATCH/sqlite3/sqlite3.h" \
  || die "patched libsqlite3-sys payload is not SQLite 3.53.2"
grep -q '^version = "0.30.1"$' "$SYS_PATCH/Cargo.toml" \
  || die "patched libsqlite3-sys lost crate version 0.30.1"

# ---- 5. toolchain pins -------------------------------------------------------
rustup toolchain list | grep -q "^$RUST_TOOLCHAIN" \
  || rustup toolchain install "$RUST_TOOLCHAIN" --profile minimal
rustup target add --toolchain "$RUST_TOOLCHAIN" \
  aarch64-linux-android x86_64-linux-android

# ---- 6. compile (upstream mobile recipe; cargo-ndk 4.x takes ABI names) ------
export LIBSQLITE3_SYS_USE_PKG_CONFIG=0
cd "$SRC/$CRATE_DIR"
# Upstream .cargo/config.toml already links -z max-page-size=16384 + packed
# relocs for both 64-bit targets; release profile keeps line-tables-only
# debug info, so the .so stays symbol/line-debuggable.
cargo +"$RUST_TOOLCHAIN" ndk -t arm64-v8a -t x86_64 -P 24 -o "$WORK/jniLibs" \
  build --locked --release --no-default-features --features "$FEATURES"

A64="$WORK/jniLibs/arm64-v8a/$SONAME"
X64="$WORK/jniLibs/x86_64/$SONAME"
[ -f "$A64" ] || A64="$SRC/target/aarch64-linux-android/release/$SONAME"
[ -f "$X64" ] || X64="$SRC/target/x86_64-linux-android/release/$SONAME"
[ -s "$A64" ] || die "missing arm64 build output"
[ -s "$X64" ] || die "missing x86_64 build output"

# ---- 7. ELF validation: ABI, ET_DYN, 16KiB LOAD alignment, debug, no TEXTREL -
check_elf() {  # check_elf <so> <machine-regex>
  readelf -h "$1" | grep -q "$2"                || die "$1: wrong machine"
  readelf -h "$1" | grep -q "DYN (Shared object file)" || die "$1: not ET_DYN"
  readelf -d "$1" | grep -q TEXTREL             && die "$1: has TEXTREL"
  readelf -S "$1" | grep -q "\.debug_"          || die "$1: no debug line tables"
  while read -r alignment; do
    [ "$((alignment))" -ge 16384 ] && [ "$((alignment % 16384))" -eq 0 ] \
      || die "$1: LOAD segment not 16KiB-aligned ($alignment)"
  done < <(readelf -lW "$1" | awk '$1=="LOAD"{print $NF}')
  echo ">> ELF ok: $1"
}
check_elf "$A64" "AArch64"
check_elf "$X64" "X86-64"

# UNIFFI contract: exported uniffi_/ffi_ symbol set must equal the official
# .so's — the Kotlin scaffolding calls every checksum fn at load time.
uniffi_syms() {
  nm -D --defined-only "$1" | awk '{print $NF}' \
    | grep -E '^(uniffi_|ffi_breez_sdk_spark)' | LC_ALL=C sort
}
STAGE_SO="$WORK/official-sos"; mkdir -p "$STAGE_SO"
for abi in arm64-v8a x86_64; do
  unzip -o -q "$VENDOR/$AAR_FILE" "jni/$abi/$SONAME" -d "$STAGE_SO"
done
diff <(uniffi_syms "$STAGE_SO/jni/arm64-v8a/$SONAME") <(uniffi_syms "$A64") >"$WORK/symdiff-arm64.txt" \
  && echo ">> arm64 UNIFFI export surface identical" \
  || die "arm64 UNIFFI symbols differ from official (see work/symdiff-arm64.txt) — API drift, stop"
diff <(uniffi_syms "$STAGE_SO/jni/x86_64/$SONAME") <(uniffi_syms "$X64") >"$WORK/symdiff-x86_64.txt" \
  && echo ">> x86_64 UNIFFI export surface identical" \
  || die "x86_64 UNIFFI symbols differ from official (see work/symdiff-x86_64.txt) — API drift, stop"

# ---- 8. repack: official AAR shell + our .so; drop ABIs the app filters away -
REPACK="$WORK/repack"; rm -rf "$REPACK"; mkdir -p "$REPACK/jni/arm64-v8a" "$REPACK/jni/x86_64"
unzip -o -q "$VENDOR/$AAR_FILE" -x "jni/*" -d "$REPACK"
cp "$A64" "$REPACK/jni/arm64-v8a/$SONAME"
cp "$X64" "$REPACK/jni/x86_64/$SONAME"
rm -f "$OUT/hench-spark-bindings.aar"
(cd "$REPACK" && zip -X -qr "$OUT/hench-spark-bindings.aar" .)

sha256sum "$OUT/hench-spark-bindings.aar" "$A64" "$X64" | tee "$OUT/artifact.sha256"
mkdir -p "$LIBS"
cp "$OUT/hench-spark-bindings.aar" "$LIBS/hench-spark-bindings.aar"
tar xzf "$VENDOR/$SRC_TGZ" -O "spark-sdk-${SDK_COMMIT}/LICENSE" > "$OUT/LICENSE.breez-spark-sdk"
echo
echo "DONE: ../libs/hench-spark-bindings.aar"
echo "  source    : $SDK_COMMIT + dependencies.patch (lock: native-spark/Cargo.lock)"
echo "  natives   : arm64-v8a + x86_64, API 24, 16KiB-aligned, line-tables debug"
echo "  provenance: out/artifact.sha256, out/LICENSE.breez-spark-sdk"
echo "  inputs    : vendor/ pinned by checksums.txt"
