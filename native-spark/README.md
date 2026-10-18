# Breez Spark Android native rebuild

`build.sh` produces `../libs/hench-spark-bindings.aar`, the local dependency
used by `build.gradle`. It preserves the official 0.26.1 AAR's Java classes,
manifest, resources and consumer rules, replacing only the native libraries
and removing ABIs that the application does not ship.

## Rebuild

```sh
cd hench/native-spark
./build.sh
```

The recipe uses Rust 1.95.0, cargo-ndk 4.x and Android NDK r28 or newer
(default `28.2.13676358`, overridable through `ANDROID_NDK_HOME`). Install
cargo-ndk if necessary with `cargo install cargo-ndk --version 4.1.2 --locked`.
Rust targets and a checksum-pinned protoc 27.5 are prepared by the script.

Five remote inputs are SHA-256-pinned in `checksums.txt`: the official AAR,
SDK source archive, protoc, and two libsqlite3-sys source archives. SDK source
is pinned to `eab30baed1122824a33dc03ee81ef3d9454f5580`, with the local
`dependencies.patch` and `Cargo.lock` applied before compilation. A verified
tarball is used if Git fetch is unavailable; an enclosing checkout is never
used as the SDK repository.

Both Android targets use the upstream mobile feature set:
`db-storage-api,secp-lowmemory,release-max-level-debug`, without defaults.
The script checks ELF architecture, shared-library type, absence of TEXTREL,
16 KiB alignment, debug sections, and equality of UniFFI export names with
the official libraries. Output hashes are written to `out/artifact.sha256`.

## SQLite and selected dependencies

The published libsqlite3-sys 0.30.1 bundled SQLite 3.46.0. The patched crate
retains the Rust API/version required by rusqlite 0.32.1 and
rusqlite_migration 1.3.1, but takes the complete `sqlite3/` payload and bundled
bindings from libsqlite3-sys 0.38.2: SQLite 3.53.2. Both archives are verified.
The Cargo path override selects `work/libsqlite3-sys`; inherited pkg-config
settings cannot substitute a system SQLite. This is a source rebuild, not a
version-string change. SQLite's advisory history is at
<https://sqlite.org/cves.html>.

The full workspace lock retains three vulnerability advisories affecting two
packages: hickory-proto 0.25.2 (RUSTSEC-2026-0118/0119) and tokio-tar 0.3.1
(RUSTSEC-2025-0111). Neither package is in the selected Android normal/build
dependency graph on either ABI; the enabling paths are testcontainers/dev
features. Verify the actual graph rather than suppressing the full-lock audit:

```sh
cd work/spark-sdk
cargo +1.95.0 tree --locked --offline -p breez-sdk-spark-bindings \
  --target aarch64-linux-android --no-default-features \
  --features db-storage-api,secp-lowmemory,release-max-level-debug -e normal,build
# Repeat with --target x86_64-linux-android.
```

Informational maintenance warnings remain for rustls-pemfile, paste and
proc-macro-error2. The selected-graph check is dated 2026-10-18; it is not a
blanket guarantee against future advisories.

## Application integration and licenses

A local AAR does not bring its POM dependencies. `build.gradle` explicitly
provides JNA, Kotlin coroutines/serialization, atomicfu and Android credentials;
OkHttp provides Okio. Do not restore the old POM's dependency pins.

SDK source is MIT; the preserved official license is copied to
`out/LICENSE.breez-spark-sdk`. The SQLite Rust wrapper is MIT and SQLite's
amalgamation is public domain. Generated `vendor/`, `work/`, `out/` and AAR
outputs are reproducible artifacts, not additional source dependencies.
