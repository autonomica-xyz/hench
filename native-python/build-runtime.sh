#!/usr/bin/env bash
# Rebuild hench/libs/hench-python-runtime.aar — a drop-in replacement for
# io.github.junkfood02.youtubedl-android:library:0.18.1 with a current,
# non-vulnerable Python + QuickJS runtime.
#
# Python side uses Termux termux-main PREBUILT debs (sha256-pinned in
# debs-<abi>.txt — same provenance as native-ffmpeg), extracted and repacked
# exactly per upstream BUILD_PYTHON.md. The stock AAR already ran with the
# termux RUNPATH (/data/data/com.termux/files/usr/lib); the Java wrapper
# resolves everything via PYTHONHOME + LD_LIBRARY_PATH, so no patchelf is
# needed — the prebuilt graph is ABI-identical to the old layout.
#
# Produces:
#   out/<termux-arch>/usr/          full extracted prefix (headers, libs, bin)
#                                   → consumed by ../native-curl (cp314 wheel +
#                                     cross-compiled _cffi_backend)
#   out/hench-python-runtime.aar    also copied to ../libs/
#
# Requires: bash, curl, sha256sum, ar, tar, unzip, zip, readelf, make, cc and
# an Android NDK (quickjs only). NO docker.
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"

die() { echo "ERROR: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || die "missing required tool: $1"; }
for t in curl sha256sum ar tar unzip zip readelf make cc strings install; do need "$t"; done

WORK="${WORK:-$SCRIPT_DIR/work}"
OUT="${OUT:-$SCRIPT_DIR/out}"
AAR_NAME=hench-python-runtime.aar
LIBS_DIR="$SCRIPT_DIR/../libs"
mkdir -p "$WORK" "$OUT" "$LIBS_DIR"
WORK=$(cd "$WORK" && pwd); OUT=$(cd "$OUT" && pwd); LIBS_DIR=$(cd "$LIBS_DIR" && pwd)

REPO="https://packages.termux.dev/apt/termux-main"
ORIG_URL="https://repo1.maven.org/maven2/io/github/junkfood02/youtubedl-android/library/0.18.1/library-0.18.1.aar"
ORIG_SHA256="579b5fb480892b1abc2b218c2089699d52759cc8d7ba256bf876453f0365faef"
QUICKJS_VERSION=2026-06-04
QUICKJS_SHA256=b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a
PREFIX="data/data/com.termux/files/usr"      # inside every termux deb
PY_MINOR=3.14
PYTHON_VER=3.14.6

# ---- NDK (quickjs only) ------------------------------------------------------
NDK="${NDK:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK" ]; then
    for base in "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}/ndk"; do
        [ -d "$base" ] && NDK=$(ls -d "$base"/*/ 2>/dev/null | sort -V | tail -1)
    done
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || die "no Android NDK found; set NDK=/path/to/ndk"
NDK="${NDK%/}"
CLANG_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$CLANG_BIN/aarch64-linux-android24-clang" ] || die "no android24 clang in $NDK"

cd "$WORK"

# ---- 1. fetch + verify pinned inputs -----------------------------------------
[ -f orig-library.aar ] || curl -sfL -o orig-library.aar "$ORIG_URL"
echo "$ORIG_SHA256  orig-library.aar" | sha256sum -c -

QJS_TGZ="quickjs-${QUICKJS_VERSION}.tar.xz"
[ -f "$QJS_TGZ" ] || curl -sfLO "https://bellard.org/quickjs/$QJS_TGZ"
echo "$QUICKJS_SHA256  $QJS_TGZ" | sha256sum -c -

MUTAGEN_WHEEL=mutagen-1.48.1-py3-none-any.whl
MUTAGEN_URL=https://files.pythonhosted.org/packages/47/d8/a29e4e3991765e7ce4ed1f7e4074fe1ba9da03e0048639734de60f9cadb9/$MUTAGEN_WHEEL
[ -f "$MUTAGEN_WHEEL" ] || curl -sfL -o "$MUTAGEN_WHEEL" "$MUTAGEN_URL"
echo "4f077fe87d3fc7fba259aa63d8c026b18382ca6a42ef37c61e16f1b1b5b82fe7  $MUTAGEN_WHEEL" | sha256sum -c -

TERMUX_ARCHES="aarch64 x86_64"
ABI_OF()    { case "$1" in aarch64) echo arm64-v8a;; x86_64) echo x86_64;; *) die "bad arch $1";; esac; }
MANIFEST_OF() { case "$1" in aarch64) echo debs-arm64.txt;; x86_64) echo debs-x86_64.txt;; esac; }
TRIPLE_OF() { case "$1" in aarch64) echo aarch64-linux-android;; x86_64) echo x86_64-linux-android;; esac; }

# ---- 2. download + extract pinned debs ----------------------------------------
for arch in $TERMUX_ARCHES; do
    tsv="$SCRIPT_DIR/$(MANIFEST_OF "$arch")"
    ddeb="$WORK/debs-$arch" dext="$OUT/$arch"
    rm -rf "$dext"
    mkdir -p "$ddeb" "$dext"
    while IFS=$'\t' read -r pkg ver fn sha; do
        [[ "$pkg" =~ ^# ]] && continue
        deb="$ddeb/$(basename "$fn")"
        [ -f "$deb" ] || curl -fsSL -o "$deb" "$REPO/$fn"
        echo "$sha  $deb" | sha256sum -c - >/dev/null \
            || die "checksum FAILED: $pkg ($fn)"
    done < "$tsv"
    while IFS=$'\t' read -r pkg ver fn sha; do
        [[ "$pkg" =~ ^# ]] && continue
        deb="$ddeb/$(basename "$fn")" dx="$ddeb/x-$(basename "$fn" .deb)"
        mkdir -p "$dx"
        ar x "$deb" --output="$dx"
        tar --force-local -xf "$dx"/data.tar.* -C "$dext"
    done < "$tsv"
    [ -d "$dext/$PREFIX" ] || die "unexpected deb layout for $arch"
    mv "$dext/$PREFIX" "$dext/usr"
    rm -rf "$dext/data"

    # artifact version self-checks (deb filenames + extracted contents)
    ls "$ddeb"/python_${PYTHON_VER}-*_"$arch".deb >/dev/null 2>&1 \
        || ls "$ddeb"/python_3.14* >/dev/null || die "python deb missing for $arch"
    U="$dext/usr"
    [ -f "$U/include/python${PY_MINOR}/Python.h" ]   || die "python headers missing for $arch"
    [ -f "$U/include/python${PY_MINOR}/pyconfig.h" ] || die "pyconfig.h missing for $arch"
    [ -f "$U/lib/libpython${PY_MINOR}.so" ]          || die "libpython missing for $arch"
    [ -f "$U/bin/python${PY_MINOR}" ]                || die "python binary missing for $arch"
    [ -f "$U/lib/libffi.so" ]                        || die "libffi missing for $arch"
    [ -f "$U/include/ffi.h" ]                        || die "ffi.h missing for $arch"
    [ -f "$U/lib/libc++_shared.so" ]                 || die "libc++_shared missing for $arch"
done

# ---- 3. prune + install pinned media metadata package ---------------------------
for arch in $TERMUX_ARCHES; do
    U="$OUT/$arch/usr"
    # match the original zip shape: drop tkinter/idlelib (pulls X11 libs),
    # static libs and pkgconfig files from the runtime payload.
    rm -rf "$U/lib/python${PY_MINOR}/tkinter" "$U/lib/python${PY_MINOR}/idlelib"
    find "$U/lib" -name '_tkinter*.so' -delete
    find "$U/lib" -name '*.a' -delete
    rm -rf "$U/lib/pkgconfig"
    mkdir -p "$U/lib/python${PY_MINOR}/site-packages"
    unzip -qo "$MUTAGEN_WHEEL" -d "$U/lib/python${PY_MINOR}/site-packages"
    [ -d "$U/lib/python${PY_MINOR}/site-packages/Cryptodome" ] || die "pycryptodomex missing for $arch"
done

# ---- 4. quickjs (host build with NDK clang) -------------------------------------
# Upstream ships no qjs build recipe; original binary was committed directly.
# Build from the pinned bellard source so provenance is real.
tar xf "$QJS_TGZ"
QJS_SRC="quickjs-$QUICKJS_VERSION"
for arch in $TERMUX_ARCHES; do
    triple=$(TRIPLE_OF "$arch")
    qdir="$OUT/$arch/qjs-build"
    rm -rf "$qdir" && mkdir -p "$qdir"
    cp -a "$QJS_SRC" "$qdir/src"
    # CROSS_PREFIX is just the upstream toggle that builds host-side tools
    # (unicode_gen, host-qjsc) with HOST_CC; CC/AR/STRIP point at the NDK.
    make -C "$qdir/src" \
        CROSS_PREFIX=x \
        HOST_CC="${HOST_CC:-cc}" \
        CC="$CLANG_BIN/${triple}24-clang" \
        AR="$CLANG_BIN/llvm-ar" \
        STRIP="$CLANG_BIN/llvm-strip" \
        LIBS="-lm -ldl" \
        qjs
    "$CLANG_BIN/llvm-strip" "$qdir/src/qjs"
    install -Dm755 "$qdir/src/qjs" "$OUT/$arch/libqjs.so"
done

# ---- 5. audit + repack per arch --------------------------------------------------
SYS_LIBS="^(libc|libm|libdl|liblog|libandroid|libmediandk|libaaudio|libcamera2ndk|libjnigraphics|libEGL|libGLESv[123]|libOpenMAXAL|libOpenSLES|libvulkan|libneuralnetworks|libwebviewchromium_plat_support|libz)\.so$"
for arch in $TERMUX_ARCHES; do
    U="$OUT/$arch/usr"
    # python executable ships inside the AAR as jni/<abi>/libpython.so
    # (real ELF executable; YoutubeDL exec()s it directly).
    install -Dm755 "$U/bin/python${PY_MINOR}" "$OUT/$arch/libpython.so"

    # Documentation licenses and terminfo are runtime symlink targets too.
    find -L "$U/lib" "$U/etc" "$U/share/terminfo" "$U/share/doc" "$U/share/LICENSES" -type l -print -quit \
        | grep -q . && die "$arch: broken runtime symlink"

    # every NEEDED of every shipped .so/binary must be a platform lib or
    # resolvable inside usr/lib (symlinks count).
    missing=0
    case "$arch" in aarch64) elf_machine=AArch64;; x86_64) elf_machine=X86-64;; esac
    while IFS= read -r so; do
        readelf -h "$so" | grep -F "$elf_machine" >/dev/null || die "$arch: wrong ELF architecture: $so"
        while IFS= read -r n; do
            b="${n#\[}"; b="${b%\]}"
            if ! echo "$b" | grep -qE "$SYS_LIBS" \
               && ! compgen -G "$U/lib/$b" >/dev/null \
               && ! compgen -G "$U/lib/python${PY_MINOR}/lib-dynload/$b" >/dev/null; then
                echo "  MISSING: $so needs $b"; missing=1
            fi
        done < <(readelf -d "$so" | sed -n 's/.*Shared library: //p')
    done < <(find "$U/lib" -name '*.so*' -type f; printf '%s\n' "$OUT/$arch/libpython.so" "$OUT/$arch/libqjs.so")
    [ "$missing" = 0 ] || die "$arch: unresolved NEEDED above — add the owning deb to debs-$arch"

    # version self-checks on emitted artifacts
    strings "$U/lib/libpython${PY_MINOR}.so" | grep -F "$PYTHON_VER" >/dev/null || die "libpython not $PYTHON_VER"
    strings "$OUT/$arch/libqjs.so" | grep -F "QuickJS version $QUICKJS_VERSION" >/dev/null || die "qjs not $QUICKJS_VERSION"
    readelf -d "$OUT/$arch/libpython.so" | grep -F "libpython${PY_MINOR}.so" >/dev/null || die "libpython.so NEEDED drift"

    # Include the terminfo and license trees referenced by bundled symlinks.
    [ -d "$U/etc" ] || die "$arch: usr/etc missing (ca-certificates/openssl)"
    (cd "$OUT/$arch" && zip -qry "$OUT/$arch/libpython.zip.so" \
        usr/lib usr/etc usr/share/terminfo usr/share/doc usr/share/LICENSES)
    python3 - "$OUT/$arch/libpython.zip.so" <<'PY'
import posixpath, stat, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    names = {entry.filename.rstrip("/") for entry in archive.infolist()}
    for entry in archive.infolist():
        if stat.S_ISLNK(entry.external_attr >> 16):
            target = posixpath.normpath(posixpath.join(
                posixpath.dirname(entry.filename), archive.read(entry).decode()))
            assert target in names, f"unbundled symlink target: {entry.filename} -> {target}"
PY
    unzip -Z1 "$OUT/$arch/libpython.zip.so" | grep -Fx "usr/lib/libpython${PY_MINOR}.so" >/dev/null || die "zip missing libpython"
    unzip -Z1 "$OUT/$arch/libpython.zip.so" | grep -Fx "usr/etc/tls/cert.pem" >/dev/null || die "zip missing CA bundle"
    # the wrapper's upgrade path compares stored pythonLibVersion (SharedPrefs)
    # against String.valueOf(libpython.zip.so.length) — the zip MUST differ in
    # size from the stock 0.18.1 payload or existing installs keep the stale
    # 3.12 stdlib. Assert here rather than trusting statistics.
    newlen=$(stat -c%s "$OUT/$arch/libpython.zip.so")
    case "$arch" in
        aarch64) oldlen=14305904;;
        x86_64)  oldlen=14302766;;
    esac
    [ "$newlen" != "$oldlen" ] || die "$arch: new libpython.zip.so size identical to stock — wrapper would skip re-extraction"
done

# ---- 6. assemble AAR ---------------------------------------------------------------
AAR_DIR="$OUT/aar"
rm -rf "$AAR_DIR" && mkdir -p "$AAR_DIR"
# keep the original wrapper (classes.jar, manifest, res/raw/ytdlp) byte-identical
unzip -qo orig-library.aar -x 'jni/*' -d "$AAR_DIR"
for arch in $TERMUX_ARCHES; do
    abi=$(ABI_OF "$arch")
    mkdir -p "$AAR_DIR/jni/$abi"
    for f in libpython.so libpython.zip.so libqjs.so; do
        install -Dm755 "$OUT/$arch/$f" "$AAR_DIR/jni/$abi/$f"
    done
done
(cd "$AAR_DIR" && rm -f "../$AAR_NAME" && zip -qr "../$AAR_NAME" .)
cp "$OUT/$AAR_NAME" "$LIBS_DIR/$AAR_NAME"

echo
echo "done: $LIBS_DIR/$AAR_NAME"
echo "python prefixes: $OUT/{aarch64,x86_64}/usr"
