# Hench

Hench is an Android **ContextVM** worker — it exposes tools (media
download via yt-dlp, with more to come) as MCP tools over Nostr using the
ContextVM transport, uploads finished files to Blossom servers, and settles
payment over Lightning with a native Breez Spark wallet.

Coordination happens through an MCP server exposed over CVM's Nostr transport
rather than NIP-90 DVM request/response events.

## Build Requirements

- JDK 17
- Android SDK with API 36
- A real Breez Spark API key
- Network access for Gradle dependencies

Create a local `breez.txt` file before building (ignored by Git):

```text
BREEZ_API_KEY=your-key-here
```

Then run:

```bash
./gradlew test lintDebug assembleDebug --warning-mode all
```

The debug APK is written under the Gradle build output directory and can be
installed with:

```bash
adb install -r build/outputs/apk/debug/hench-0.1.0-debug.apk
```

## CI Setup

The GitHub Actions workflow expects a repository secret named
`BREEZ_SPARK_API_KEY`, written to `breez.txt` during the job.

## Bundled Runtime Assets

The APK bundles:

- yt-dlp zipapp + curl_cffi Android arm64 wheel contents
- Native libraries for Python, FFmpeg, aria2c, JNA, and Breez Spark

A native library, `libcontextvm_ffi.so` (from the ContextVM rs-sdk), is bundled
for arm64-v8a and x86_64.
