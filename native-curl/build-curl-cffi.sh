#!/usr/bin/env bash
# Reproduce src/main/assets/python-packages/curl_cffi_arm64.zip for the pinned
# CPython 3.14.6 runtime (native-python AAR). See README.md.
#
# SECURITY REBUILD (2026-10-18): the official curl_cffi 0.16.3 Android wheel
# embeds libcurl-impersonate 2.2.2 = curl 8.21.0 with 33 published CVEs,
# including CVE-2026-92392 (HIGH, HTTP/2 upload use-after-free) fixed only in
# curl 8.23.0. No released wheel ships 8.23.0, so BUILD the v2.2.3
# curl-impersonate baseline (curl 8.22.0) with 20 security backports.
# The remaining 8.22.0 advisory is GnuTLS-only; this build uses BoringSSL.
# Keep Android's system DNS resolver: the Python subprocess has no JVM
# for c-ares initialization. Rebuild _wrapper.abi3.so from the pinned
# curl_cffi sdist; use the wheel only for its Python package files.
#
# Top-level downloads use checksums.txt; dependency pins use CMake URL_HASH.
# Output: $OUT/curl_cffi_arm64.zip -> copy to src/main/assets/python-packages/
set -euo pipefail
cd "$(dirname "$0")"

# ---- knobs -----------------------------------------------------------------
NDK="${NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
API="${API:-24}"                        # upstream wheel floor: android_24
CURL_CFFI_VER=0.16.3                    # package files + version metadata
IMPERSONATE_VER=2.2.3                   # build baseline: embeds curl 8.22.0
CFFI_VER=2.1.1
CERTIFI_VER=2026.7.22
# pinned Python 3.14.6 android prefix (parent-owned):
#   native-python/out/aarch64/usr - aarch64 for arm64-v8a
PYPREFIX="${PYPREFIX:-$(pwd)/../native-python/out/aarch64/usr}"
WORK="$(pwd)/work"; OUT="$(pwd)/out"
mkdir -p "$WORK" "$OUT"; cd "$WORK"

CC_WHL="curl_cffi-${CURL_CFFI_VER}-cp314-cp314-android_24_arm64_v8a.whl"
CC_SDIST="curl_cffi-${CURL_CFFI_VER}.tar.gz"
CFFI_TGZ="cffi-${CFFI_VER}.tar.gz"
CERTIFI_WHL="certifi-${CERTIFI_VER}-py3-none-any.whl"
IMP_SRC="curl-impersonate-${IMPERSONATE_VER}.tar.gz"

curl -fL -o "$CC_WHL" \
  "https://files.pythonhosted.org/packages/fb/f4/3dedff1a31c93a9b18acaa346e23832c29bc18075138e90e9af795188e5e/${CC_WHL}"
curl -fL -o "$CC_SDIST" \
  "https://files.pythonhosted.org/packages/82/e1/730125c43e3e331d98e17af3cb310ba526b3f1101b7635ca23d976ebfcf5/${CC_SDIST}"
curl -fL -o "$CFFI_TGZ" \
  "https://files.pythonhosted.org/packages/9e/ef/008a1939e372c06329a3fce4279c02f328488f3526744906eeec3da7ad5f/${CFFI_TGZ}"
curl -fL -o "$CERTIFI_WHL" \
  "https://files.pythonhosted.org/packages/0b/a7/71ac2cff56fec219ed242bb11b8efb69fcc4bec75db06fb7bfe35de520e6/${CERTIFI_WHL}"
curl -fL -o "$IMP_SRC" \
  "https://github.com/lexiforest/curl-impersonate/archive/refs/tags/v${IMPERSONATE_VER}.tar.gz"
sha256sum -c ../checksums.txt
echo "all inputs verified"

NDKBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$NDKBIN/aarch64-linux-android${API}-clang"
READELF="$NDKBIN/llvm-readelf"
AR="$NDKBIN/llvm-ar"
[ -x "$CLANG" ]   || { echo "NDK clang missing: $CLANG (set \$NDK)"; exit 1; }
[ -x "$AR" ]      || { echo "llvm-ar missing: $AR"; exit 1; }
[ -x "$READELF" ] || READELF=readelf
command -v cmake  >/dev/null || { echo "cmake missing"; exit 1; }
command -v ninja  >/dev/null || { echo "ninja missing"; exit 1; }

[ -f "$PYPREFIX/include/python3.14/pyconfig.h" ] \
  && [ -f "$PYPREFIX/lib/libpython3.14.so" ] \
  && [ -f "$PYPREFIX/include/ffi.h" ] \
  && [ -f "$PYPREFIX/lib/libffi.so" ] || {
  echo "PYPREFIX $PYPREFIX lacks python3.14 headers/libs + ffi.h/libffi.so"
  echo "          lib/libpython3.14.so, lib/libffi.so (native-python out)"; exit 1; }

tar xzf "$CFFI_TGZ"
tar xzf "$CC_SDIST"
tar xzf "$IMP_SRC"
CC_SRC="curl_cffi-${CURL_CFFI_VER}"
CI_SRC="curl-impersonate-${IMPERSONATE_VER}"

# ---- 1. libcurl-impersonate: v2.2.3 + curl 8.23.0 CVE backport ---------------
patch --batch --fuzz=0 -p1 -d "$CI_SRC" -i "$WORK/../patches/android-build.patch"

# append the 8.23.0 CVE backport to the upstream curl.patch so the
# ExternalProject PATCH_COMMAND applies both in one pass.
cat ../patches/curl-8_23_0-security-backport.patch >> "$CI_SRC/patches/curl.patch"

BUILD_KEY="$(sha256sum ../build-curl-cffi.sh ../patches/*.patch | sha256sum | cut -c1-16)"
CI_BUILD="$WORK/ci-build-arm64-api${API}-${BUILD_KEY}"
# Cross-compilation must never import host OpenSSL/pkg-config link flags.
export PKG_CONFIG_PATH=""
export PKG_CONFIG_LIBDIR="$CI_BUILD/deps/install/lib/pkgconfig"
cmake -G Ninja -B "$CI_BUILD" -S "$CI_SRC" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-${API}" \
  -DCURL_IMPERSONATE_VERSION="${IMPERSONATE_VER}+hench.1" \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$CI_BUILD" --target curl-impersonate --parallel "$(nproc)"

# merge all dep archives into the mega archive, mirroring upstream's release
# step (workflows/build.yml): relocatable link over --whole-archive.
DEPS_LIB="$CI_BUILD/deps/install/lib"
CURL_LIBA="$CI_BUILD/deps/build/curl/lib/libcurl-impersonate.a"
for a in "$CURL_LIBA" "$DEPS_LIB"/libz.a \
         "$DEPS_LIB"/libzstd.a "$DEPS_LIB"/libbrotlidec.a \
         "$DEPS_LIB"/libbrotlicommon.a "$DEPS_LIB"/libbrotlienc.a \
         "$DEPS_LIB"/libnghttp2.a "$DEPS_LIB"/libnghttp3.a \
         "$DEPS_LIB"/libngtcp2.a "$DEPS_LIB"/libngtcp2_crypto_boringssl.a \
         "$DEPS_LIB"/libssl.a "$DEPS_LIB"/libcrypto.a; do
  [ -f "$a" ] || { echo "missing built archive: $a"; exit 1; }
done
"$CLANG" -r -nostdlib -no-pie -o libcurl-impersonate.full.o \
  -Wl,--whole-archive \
  "$CURL_LIBA" \
  "$DEPS_LIB"/libz.a "$DEPS_LIB"/libzstd.a \
  "$DEPS_LIB"/libbrotlidec.a "$DEPS_LIB"/libbrotlicommon.a \
  "$DEPS_LIB"/libbrotlienc.a "$DEPS_LIB"/libnghttp2.a \
  "$DEPS_LIB"/libnghttp3.a "$DEPS_LIB"/libngtcp2.a \
  "$DEPS_LIB"/libngtcp2_crypto_boringssl.a \
  "$DEPS_LIB"/libssl.a "$DEPS_LIB"/libcrypto.a \
  -Wl,--no-whole-archive
"$AR" rcs "$WORK/libcurl-impersonate.a" libcurl-impersonate.full.o
rm -f libcurl-impersonate.full.o
# headers for shim.c: patched in place inside deps/src/curl (PATCH_COMMAND
# ran there); deps/install/include only exists after an install target run.
IMP_INCLUDE="$CI_BUILD/deps/src/curl/include"
[ -f "$IMP_INCLUDE/curl/curl.h" ] || IMP_INCLUDE="$CI_BUILD/deps/install/include"
[ -f "$IMP_INCLUDE/curl/curl.h" ] || { echo "patched curl headers not found"; exit 1; }

# license notices generated from the dependency sources we actually compiled
cmake -P "$CI_BUILD/CollectLicenses.cmake"
LIC_SRC="$CI_BUILD/licenses"
[ -d "$LIC_SRC" ] || { echo "CollectLicenses produced no licenses dir"; exit 1; }

# ---- 2. _wrapper.abi3.so from the curl_cffi sdist ---------------------------
# host-side cffi for the emit step: sdist frontend needs a matching
# _cffi_backend (host python dist-packages has 2.0.0 -> version mismatch).
# Compile it once into work/hostcffi/ with the host's native libffi.
python3 -c "import pycparser" 2>/dev/null \
  || { echo "host needs pycparser (pip install pycparser)"; exit 1; }
mkdir -p hostcffi
# host libffi headers: system libffi-dev if present, else reuse the
# native-python x86_64 build prefix (host-arch headers, same libffi ABI).
HOST_FFI_INC=""
for d in /usr/include /usr/include/x86_64-linux-gnu \
         "$PYPREFIX/../../x86_64/usr/include"; do
  [ -f "$d/ffi.h" ] && HOST_FFI_INC="$d" && break
done
[ -n "$HOST_FFI_INC" ] || { echo "host ffi.h missing (install libffi-dev)"; exit 1; }
cc -shared -fPIC -O2 -DFFI_BUILDING=1 -DUSE__THREAD \
  "-I$(python3 -c 'import sysconfig;print(sysconfig.get_paths()["include"])')" \
  -I"$HOST_FFI_INC" \
  "$WORK/cffi-${CFFI_VER}/src/c/_cffi_backend.c" -l:libffi.so.8 \
  -o "hostcffi/_cffi_backend$(python3 -c 'import sysconfig;print(sysconfig.get_config_var("EXT_SUFFIX"))')" \
  && echo "host _cffi_backend built"
HOST_CFFI_PATH="$(pwd)/hostcffi:$(pwd)/cffi-${CFFI_VER}/src"

# emit the cffi C source on the host (cffi 2.1.1 runs pure-python for this),
# replicating scripts/build.py: set_source + cdef from the pinned sdist.
PYTHONPATH="$HOST_CFFI_PATH" python3 - "$CC_SRC" <<'EOF'
import sys
from pathlib import Path
from cffi import FFI
root = Path(sys.argv[1])
ffibuilder = FFI()
ffibuilder.set_source("curl_cffi._wrapper", '#include "shim.h"')
ffibuilder.cdef((root / "ffi" / "cdef.c").read_text())
ffibuilder.emit_c_code(str(root / "_wrapper_emit.c"))
EOF
[ -f "$CC_SRC/_wrapper_emit.c" ] || { echo "cffi emit failed"; exit 1; }

# link exactly like upstream's IMPERSONATE_LINK_TYPE=static + android path:
# --whole-archive over the mega archive + libc++_shared + libpython3.14.
SYSROOT_LIB="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android"
# Do not add the sysroot parent to -L: its libc.a shadows shared libc.so.
"$CLANG" -shared -fPIC -O2 \
  -I"$CC_SRC/ffi" -I"$IMP_INCLUDE" -I"$PYPREFIX/include/python3.14" \
  "$CC_SRC/ffi/shim.c" "$CC_SRC/_wrapper_emit.c" \
  -Wl,--whole-archive "$WORK/libcurl-impersonate.a" -Wl,--no-whole-archive \
  -L"$PYPREFIX/lib" -l:libpython3.14.so \
  "$SYSROOT_LIB/libc++_shared.so" \
  -o "$WORK/_wrapper.abi3.so"

# ---- 3. _cffi_backend (cffi ships no android wheel) -------------------------
"$CLANG" -shared -fPIC -O2 \
  -DFFI_BUILDING=1 -DUSE__THREAD -DHAVE_SYNC_SYNCHRONIZE \
  -I"$PYPREFIX/include/python3.14" -I"$PYPREFIX/include" \
  "$WORK/cffi-${CFFI_VER}/src/c/_cffi_backend.c" \
  -L"$PYPREFIX/lib" -l:libpython3.14.so -l:libffi.so \
  -o "$WORK/_cffi_backend.cpython-314-aarch64-linux-android.so"

# ---- assemble wheel contents ------------------------------------------------
STAGE="$WORK/stage"; rm -rf "$STAGE"; mkdir -p "$STAGE"
python3 - "$WORK/$CC_WHL" "$STAGE" <<'EOF'
import sys, zipfile
whl, stage = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(whl) as z:
    for n in z.namelist():
        if n.startswith("curl_cffi.libs/"):
            continue                                   # vendored libc++_shared, runtime ships it
        if n == "curl_cffi/_wrapper.abi3.so":
            continue                                   # replaced by our rebuilt copy
        z.extract(n, stage)
EOF
cp "$WORK/_wrapper.abi3.so" "$STAGE/curl_cffi/_wrapper.abi3.so"
cp "$WORK/_cffi_backend.cpython-314-aarch64-linux-android.so" "$STAGE/"


python3 - "$WORK/$CERTIFI_WHL" "$STAGE" <<'EOF'
import sys,zipfile
whl,stage=sys.argv[1],sys.argv[2]
with zipfile.ZipFile(whl) as z:
    for n in z.namelist():
        if n.startswith("certifi/"):
            z.extract(n,stage)
EOF

# license notices: generated from the exact dep sources we compiled +
# package licenses
LIC="$STAGE/licenses"; mkdir -p "$LIC"
cp "$LIC_SRC"/* "$LIC/"
cp "$STAGE/curl_cffi-${CURL_CFFI_VER}.dist-info/licenses/LICENSE" "$LIC/LICENSE.curl_cffi"
cp "cffi-${CFFI_VER}/LICENSE" "$LIC/LICENSE.cffi"
python3 - "$WORK/$CERTIFI_WHL" "$LIC" <<'EOF'
import sys,zipfile
whl,lic=sys.argv[1],sys.argv[2]
with zipfile.ZipFile(whl) as z:
    tgt=[n for n in z.namelist() if n.startswith('certifi-') and ('LICENSE' in n or 'LICENCE' in n)]
    if tgt:
        open(f"{lic}/LICENSE.certifi","wb").write(z.read(tgt[0]))
EOF

# ---- ELF dependency checks ---------------------------------------------------
python3 - "$STAGE" "$READELF" <<'EOF'
import re, subprocess, sys
stage, readelf = sys.argv[1], sys.argv[2]
allowed = {"libpython3.14.so", "libc++_shared.so", "libdl.so", "libm.so", "libc.so"}
for so in (f"{stage}/curl_cffi/_wrapper.abi3.so",
           f"{stage}/_cffi_backend.cpython-314-aarch64-linux-android.so"):
    out = subprocess.run([readelf, "-d", so], capture_output=True, text=True).stdout
    needed = set(re.findall(r"Shared library: \[(.+?)\]", out))
    assert "libc.so" in needed, f"{so}: missing dynamic Bionic libc dependency"
    # _cffi_backend additionally needs libffi.so
    ok = allowed | ({"libffi.so"} if "_cffi_backend" in so else set())
    bad = needed - ok
    assert not bad, f"{so}: unexpected NEEDED {bad}"
    hdr = subprocess.run([readelf, "-h", so], capture_output=True, text=True).stdout
    assert "AArch64" in hdr, f"{so}: not aarch64"
    phdr = subprocess.run([readelf, "-lW", so], capture_output=True, text=True, check=True).stdout
    loads = [int(line.split()[-1], 16) for line in phdr.splitlines() if line.lstrip().startswith("LOAD ")]
    assert loads and all(align >= 16384 for align in loads), f"{so}: not 16 KB aligned"
    print(f"{so}: OK -> {sorted(needed)}")
EOF

# regenerate dist-info RECORD (rebuilt _wrapper + added backend/certifi/licenses)
python3 - "$STAGE" <<'EOF'
import base64,hashlib,os,sys
stage=sys.argv[1]; di=None
for n in os.listdir(stage):
    if n.endswith(".dist-info"): di=n
assert di, "dist-info missing"
def digest(p):
    h=hashlib.sha256(open(p,'rb').read()).digest()
    return "sha256="+base64.urlsafe_b64encode(h).rstrip(b"=").decode()
rec=[]
for root,_,files in os.walk(stage):
    for f in sorted(files):
        full=os.path.join(root,f)
        rel=os.path.relpath(full,stage)
        if rel==f"{di}/RECORD": continue
        rec.append(f"{rel},{digest(full)},{os.path.getsize(full)}")
rec.append(f"{di}/RECORD,,")
open(os.path.join(stage,di,"RECORD"),"w").write("\n".join(rec)+"\n")
EOF

rm -f "$OUT/curl_cffi_arm64.zip"
(cd "$STAGE" && zip -qr "$OUT/curl_cffi_arm64.zip" .)
echo "built $OUT/curl_cffi_arm64.zip"
echo "NEXT: copy to src/main/assets/python-packages/ and bump BUNDLED_CURL_CFFI_VERSION (e.g. ${CURL_CFFI_VER}+hench.1) in PythonPackageManager.kt so installed 0.16.3 cannot skip the new payload"
