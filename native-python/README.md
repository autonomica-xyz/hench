# Python / QuickJS Android runtime AAR rebuild

`hench/libs/hench-python-runtime.aar` replaces the published
`io.github.junkfood02.youtubedl-android:library:0.18.1` AAR, whose bundled
runtime is old and carries known-vulnerable components (CPython 3.12.11,
OpenSSL 3.5.2, sqlite 3.50.4, zlib 1.3.1, xz 5.8.1, quickjs 2025-04-26).
`build-runtime.sh` repacks the same layout with the pinned components below.
The Java wrapper (`YoutubeDL`, classes.jar, `res/raw/ytdlp`, manifest) remains
byte-identical and GPL-3.0; replacing native payloads does not relicense it.

## Run

```bash
cd hench/native-python
./build-runtime.sh        # no docker; downloads verified debs + builds qjs
```

Prereqs: curl, sha256sum, ar, tar, unzip, zip, readelf, make, cc and an
Android NDK (auto-detected; quickjs only).

## How

- **Python graph**: Termux `termux-main` **prebuilt** debs — python 3.14.6-1,
  openssl 3.6.5, sqlite 3.54.1, zlib 1.3.2, xz 5.8.4, expat 2.9.0, bzip2
  1.0.8, libffi 3.8.0, zstd 1.5.7, ncurses, readline, gdbm, libcrypt,
  libandroid-{support,posix-semaphore,execinfo}, libiconv, libc++ 30,
  ca-certificates 2026.09.25 — sha256-pinned in `debs-arm64.txt` /
  `debs-x86_64.txt` (identical provenance to `native-ffmpeg`). No
  cross-compile: these are the same binaries termux ships today. The stock
  runtime also ran under the termux RUNPATH; the wrapper sets PYTHONHOME and
  LD_LIBRARY_PATH, so prefix relocation is unchanged behavior.
- **Python 3.14** matches the current interpreter and the source-built
  curl_cffi extension in `native-curl/`. `PythonPackageManager` installs the
  matching cpython-314 backend and records each package update independently.
- **Bundled site-packages**: Mutagen 1.48.1 from its verified upstream wheel;
  Cryptodomex 3.24.1 from pinned Termux packages for each ABI. The original
  runtime's stale copies are not reused.
- **QuickJS**: bellard `quickjs-2026-06-04` (sha256-pinned), built on the host
  with NDK clang → `libqjs.so` + `usr/lib/quickjs/libquickjs.a`.
- **ZIP layout**: `usr/lib` and `usr/etc`, with Unix symlinks preserved;
  `usr/bin/python3.14` becomes the `jni/<abi>/libpython.so` executable.

## Self-checks

input sha256s, required-file asserts per arch, broken-symlink scan, full ELF
NEEDED closure vs platform-lib allowlist, version strings in emitted
binaries, zip contents (libpython3.14.so + CA bundle), and — because the
wrapper invalidates extraction by `libpython.zip.so` byte length — a guard
that the new zip size differs from stock (re-extraction is guaranteed).

Pixel checks exercised Python/package imports, AES, HTTPS and QuickJS BigInt.
The x86_64 build passes structural checks but has not been exercised on a device.

Expat 2.9.0 still has an upstream CPU-denial-of-service issue
([CVE-2025-66382](https://github.com/libexpat/libexpat/issues/1076)) with no
available fix. Job subprocess timeouts limit its impact; this runtime is not
claimed to be free of every published vulnerability.

## Outputs

- `out/{aarch64,x86_64}/usr` — full prefixes incl. `include/python3.14/`,
  `lib/libpython3.14.so`, `lib/libffi.so` (for `native-curl` cross builds).
- `out/hench-python-runtime.aar` → copied to `hench/libs/` (git-ignored).
  ABIs: `arm64-v8a` + `x86_64` only (matches `abiFilters`; stock 32-bit jni
  entries intentionally dropped).

## Gradle integration

```groovy
implementation files('libs/hench-python-runtime.aar')   // replaces :library
implementation files('libs/hench-ffmpeg-runtime.aar')   // replaces :ffmpeg
implementation "io.github.junkfood02.youtubedl-android:common:0.18.1"
```

The app declares the wrappers' JVM dependencies explicitly: Jackson,
Commons IO and Commons Compress. XZ for Java 1.12 and the Android
Zstd JNI 1.5.7-23 AAR complete Commons Compress's optional ZIP codec paths;
release shrinking retains the names used by Zstd JNI. The wrapper restores
Unix symlinks, so replacing it with a simpler ZIP extractor would break the
native runtime layout.
