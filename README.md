# Hench

**Turn your Android phone into a worker that earns sats.**

Hench is a ContextVM worker that lives on your phone. It offers media
downloads (powered by yt-dlp) as a paid service over Nostr — someone
requests a download, pays a Lightning invoice, and Hench does the work:
it fetches the media, uploads it to a Blossom server, and hands back a
link. The sats land in the wallet on your phone.

<p align="center">
  <img src="docs/screenshots/dashboard.png" width="270" alt="Hench dashboard with the worker ON" />
  <img src="docs/screenshots/wallet.png" width="270" alt="Hench wallet showing earnings and Lightning send/receive" />
  <img src="docs/screenshots/settings.png" width="270" alt="Hench settings for relays, Blossom servers, and worker name" />
</p>

## How it works

1. **Turn the worker on.** One big button. Hench announces itself on Nostr
   relays and starts listening for jobs.
2. **A client sends a job.** Someone (usually an AI agent) asks Hench to
   download a video or audio file. Hench quotes a price in sats.
3. **They pay, you earn.** Hench creates a Lightning invoice through its
   built-in [Breez Spark](https://spark.money) wallet. Payment settles,
   Hench runs the download with yt-dlp, uploads the result to Blossom,
   and delivers the link.

Everything runs on the phone — no server to rent, no account to create.

## What you get

- **A worker, not a server.** Hench runs as a foreground service with a
  simple dashboard: worker on/off, sats earned, jobs executed, and live
  status of the latest job.
- **A real Lightning wallet.** Earnings settle to a built-in Breez Spark
  wallet you control. Send and receive sats right from the app, or export
  the recovery words to any Spark-compatible wallet. Prefer your own
  wallet? Connect over NWC instead.
- **yt-dlp under the hood.** The same engine that powers the web's best
  media downloader, bundled on-device and kept up to date.
- **You set the prices.** Per-hour rates for audio and video, a base
  price per job, and working hours — all configurable in Settings.
- **Open coordination.** Jobs and results flow over Nostr; files are
  stored on Blossom servers. No middleman, no platform fee.

## Getting started

1. Install Hench on an arm64 Android phone (API 24+).
2. Tap **Create wallet & turn worker on**. That's it — a Spark wallet is
   generated on-device and the worker goes live.
3. Open **Settings → Wallet** to back up your 12 recovery words. Do this
   first; the words are the only way to restore your earnings.

## For developers

Hench is open source. Building from source requires JDK 17 and the
Android SDK (API 36); a `breez.txt` file holding your own Breez Spark
API key must exist at the repo root (it is gitignored):

```bash
echo "BREEZ_API_KEY=your-key" > breez.txt
./gradlew assembleRelease
```
