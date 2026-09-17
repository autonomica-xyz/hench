package net.nostr.hench.settings

import net.nostr.hench.identity.NostrIdentity
import net.nostr.hench.identity.hexToBytes
import net.nostr.hench.wallet.generateSparkSeedHex

object OnboardingDefaults {
    const val LOCAL_RELAY_URL = "ws://127.0.0.1:10563"
    const val LOCAL_BLOSSOM_URL = "http://127.0.0.1:18080"
    val DEFAULT_RELAY_URLS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://relay.nostr.net",
    )
    val DEFAULT_BLOSSOM_URLS = listOf(
        "https://blossom.yakihonne.com/",
        "https://24242.io/",
        "https://blossom.primal.net/",
        "https://nostr.download/",
        "https://cdn.sovbit.host/",
        "https://cdn.nostrcheck.me/",
    )

    /**
     * Derive a stable, human-unique worker name ("hench <adjective>-<noun>")
     * from the spark seed — same words always recover the same name, every
     * device gets a different one. No wordlist dep: picks from a small baked-in
     * adjective/noun list indexed by seed-hash bits (adjective*36 + noun ~ 1500
     * combos; plus the short npub suffix in announcements for uniqueness).
     */
    fun generateWorkerName(sparkSeedHex: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("hench/name/v1".toByteArray() + sparkSeedHex.hexToBytes())
        val adj = WORKER_NAME_ADJECTIVES[(digest[0].toInt() and 0xff) % WORKER_NAME_ADJECTIVES.size]
        val noun = WORKER_NAME_NOUNS[(digest[1].toInt() and 0xff) % WORKER_NAME_NOUNS.size]
        return "hench-$adj-$noun"
    }

    private val WORKER_NAME_ADJECTIVES = listOf(
        "swift", "brave", "calm", "eager", "fierce", "gentle", "happy", "jolly",
        "keen", "lively", "merry", "noble", "proud", "quiet", "rapid", "sturdy",
        "tidy", "valiant", "witty", "zealous", "bold", "clever", "dashing", "earnest",
    )
    private val WORKER_NAME_NOUNS = listOf(
        "otter", "falcon", "badger", "raven", "heron", "lynx", "ibex", "marten",
        "puffin", "stoat", "viper", "wolverine", "kiwi", "tapir", "narwhal", "quokka",
        "ermine", "gibbon", "jackal", "lemur",
    )

    fun createFirstRunWorkerSettings(bundledSparkApiKey: String): WorkerSettings {
        // One seed to back up: the Nostr identity derives from the spark seed
        // (deriveNostrSecretKeyHex) — the 12 words recover wallet AND identity.
        val seed = generateSparkSeedHex()
        return WorkerSettings(
            enabled = true,
            relayUrls = DEFAULT_RELAY_URLS,
            blossomUrls = DEFAULT_BLOSSOM_URLS,
            workerSecretKeyHex = net.nostr.hench.wallet.deriveNostrSecretKeyHex(seed),
            workerName = generateWorkerName(seed),
        acceptAudio = true,
        acceptVideo = true,
        basePriceMsat = 9_000,
        audioPriceMsatPerHour = 60_000,
        videoPriceMsatPerHour = 60_000,
        minBillableSeconds = 60,
        cleanupAfterUpload = true,
        hoursOfOperation = HoursOfOperation.always(),
        walletMode = WalletMode.BUILT_IN,
            sparkApiKey = bundledSparkApiKey.trim().takeIf { it.isNotBlank() },
            sparkSeedHex = seed,
        )
    }
}
