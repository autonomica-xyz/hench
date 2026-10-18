# Android curl_cffi runtime

`build-curl-cffi.sh` builds the arm64 Python package installed from
`src/main/assets/python-packages/curl_cffi_arm64.zip`. It matches the pinned
CPython 3.14.6 runtime in `native-python/`; x86_64 impersonation is not bundled.

The official curl_cffi 0.16.3 Android wheel still embeds curl 8.21.0, including
[CVE-2026-92392](https://curl.se/docs/CVE-2026-92392.html), an HTTP/2 upload
use-after-free. This recipe uses the wheel's Python files and metadata, but
replaces its native extension with a source build.

## Build

Build `../native-python/` first. Then:

```sh
cd hench/native-curl
./build-curl-cffi.sh
cp out/curl_cffi_arm64.zip ../src/main/assets/python-packages/
```

Requirements: Linux, CMake, Ninja, patch, curl, zip/unzip, Python 3.14 with
pycparser, a host C compiler/libffi, and Android NDK r28.2.13676358.
`NDK`, `API` (default 24), and `PYPREFIX` can select existing toolchains.
The default Python prefix is `../native-python/out/aarch64/usr`.

When replacing the asset, bump `BUNDLED_CURL_CFFI_VERSION` in
`PythonPackageManager.kt`. The local revision `0.16.3+hench.1` distinguishes
this payload from an already-installed official 0.16.3 wheel. The Python
package itself truthfully retains its upstream API version, 0.16.3.

## Source inputs

Top-level downloads are SHA-256-pinned in `checksums.txt`. Transitive native
sources are pinned by the upstream CMake `URL_HASH` entries.

| Component | Input |
|---|---|
| curl_cffi | 0.16.3 wheel for Python files; sdist for `ffi/shim.c` and `ffi/cdef.c` |
| curl-impersonate | v2.2.3, based on curl 8.22.0 |
| curl security fixes | 20 backports from 8.23.0; commit IDs in `patches/curl-8_23_0-security-backport.patch` |
| BoringSSL | `156c7b75ae9b8c3b3f847acf264f17594c3859fb` plus upstream impersonation patch |
| nghttp2 / ngtcp2 / nghttp3 | 1.63.0 / 1.20.0 / 1.15.0, with upstream patches |
| Brotli / zlib / Zstandard | 1.2.0 / 1.3.2 / 1.5.7 |
| cffi / certifi | 2.1.1 / 2026.7.22 |

Apply the curl security patch after upstream's impersonation patch.
`patches/android-build.patch` selects static-library-only output, updates
zlib, and preserves Android's threaded system resolver. c-ares is not built:
this Python subprocess has no JVM/ConnectivityManager for its Android setup.

The extension links shared Bionic libc and the runtime's `libc++_shared.so`.
Do not add the NDK sysroot parent to the linker search path: its `libc.a`
shadows the API-specific shared libc and causes an Android loader crash.
The backend import is not suppressed; a missing native backend is an error.

## Checks and limits

The recipe checks input hashes, AArch64 ELF headers, shared-library dependency
closure, dynamic libc linkage, and 16 KiB LOAD alignment. It replaces the output
ZIP rather than updating an old archive, and bundles the upstream licenses.
Pixel smoke checks exercised import, Chrome-impersonated HTTPS, a hash-verified
HTTP/2 upload/download, an early-rejected upload, and connection rotation.

Advisory review dated 2026-10-18:

- The 20 curl backports include the HTTP/2 upload fix and connection-pool
  lifetime follow-ups. CVE-2026-107928 is GnuTLS-only, not applicable to this
  BoringSSL build. CVE-2026-86411 is already fixed in the 8.22.0 baseline.
- nghttp2's CVE-2026-27135 requires extension-receive options that this curl
  integration does not enable. The upstream impersonation fork pins 1.63.0
  because newer nghttp2 removed its priority API.
- ngtcp2's CVE-2026-40170 requires qlog; this curl library path leaves qlog off.
- BoringSSL has no conventional release/CVE feed. These pins and reachability
  findings are not a claim that every possible native vulnerability is absent.
