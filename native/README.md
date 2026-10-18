# hench/native — Android ContextVM FFI build inputs

Self-contained Rust workspace producing `libcontextvm_ffi.so` for the Android
app (`src/main/jniLibs/{arm64-v8a,x86_64}/`). This repo builds standalone — no
neighboring checkouts required.

## Provenance

- `contextvm-sdk/` — byte-identical snapshot of `hench-cli/vendor/contextvm-sdk`
  (upstream `github.com/nostr-net/rs-sdk` @ `2cf7e6c`, MIT, Kosti Rytkönen),
  plus the vendored diffs: `lru` 0.12→0.18 and the nostr 0.44→0.45.5 port
  (`ContextVmSigner` replaces the removed `NostrSigner`). Payment-initiation
  failures return a correlated error instead of leaving clients waiting;
  private processor diagnostics remain server-side.
- `contextvm-ffi/` — `contextvm-ffi` crate from the same upstream revision,
  repathed to the vendored SDK (`path = "../contextvm-sdk"`) and ported to the
  0.45 signer/notification API (`Arc<dyn ContextVmSigner>`,
  `ClientNotification`).
- `LICENSE` — upstream rs-sdk MIT license, preserved verbatim.
- `Cargo.lock` — pinned; no known-vulnerable crates (lru only 0.18.5,
  no `pool`, no `nostr-relay-pool`, negentropy 0.5.2, rustls 0.23.46).

## Rebuild (aarch64 + x86_64)

Requires Android NDK r28 or newer on `ANDROID_NDK_HOME`, cargo-ndk 4.x, and
Rust targets `aarch64-linux-android` and `x86_64-linux-android`.

```sh
cd hench/native
cargo ndk -t arm64-v8a -t x86_64 -P 24 -o . build --release --locked -p contextvm-ffi
# outputs: ./arm64-v8a/libcontextvm_ffi.so, ./x86_64/libcontextvm_ffi.so
cp arm64-v8a/libcontextvm_ffi.so ../src/main/jniLibs/arm64-v8a/
cp x86_64/libcontextvm_ffi.so ../src/main/jniLibs/x86_64/
```

The checked-in Kotlin binding does not need regeneration for this dependency
port. On the Pixel, all 70 exported UniFFI checksum/contract functions matched
the previous library. Both binaries have 16 KiB LOAD alignment. The workspace
regressions and lockfile advisory scan also pass; Tokio test utilities are
enabled only for tests, not the production library.
