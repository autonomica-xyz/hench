# Agent Notes for hench

## Project
Android app (`hench`) that exposes a ContextVM server over Nostr and a `download_media` tool backed by yt-dlp.

## Verification Commands
```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
./gradlew assembleRelease lintRelease testReleaseUnitTest
```

## Lint
The generated UniFFI Kotlin bindings in `src/main/java/uniffi/contextvm_ffi/contextvm_ffi.kt` reference `java.lang.ref.Cleaner` (API 33+). This is a generated seam and has a runtime fallback for older API levels. The file is excluded from the `NewApi` lint check via `lint.xml` in the module root. Do not modify the generated file to fix these warnings.

## Payment Gate Architecture (CEP-8)
- FFI `Server` is configured, then `start()` is called; pricing and payment policy must be set before `start()`.
- `PaymentGateHandler` is the Kotlin seam that drives the FFI payment gate: it drains `GateRequest` events, quotes with `QuoteCalculator`, creates invoices via the wallet, writes the `PaymentRecord` to `PaymentSessionStore` before calling `submitInvoice`, and polls for settlement.
- `PaymentRecord` is keyed by `canonicalInvocationId` and written durably (`SharedPreferences.commit()` style) before `submitInvoice` so a crash after invoice creation can re-bind to the same pay_req instead of minting a second invoice.
- `CvmServer` keeps an in-memory `requestEventId -> canonicalInvocationId` map so that the forwarded `tools/call` (post-gate) can write the canonical id into the terminal `JobRecord`.
- Dashboard updates (`onJobUpdate`) are keyed by `canonicalInvocationId` when available, falling back to `idempotencyKey` for non-gated paths.
