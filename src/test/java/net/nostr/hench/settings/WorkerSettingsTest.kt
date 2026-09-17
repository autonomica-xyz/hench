package net.nostr.hench.settings

import net.nostr.hench.worker.DeliveryMode
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSettingsTest {
    @Test
    fun defaultsAreSafeForMinimalWorker() {
        val settings = WorkerSettings()

        assertFalse(settings.enabled)
        assertTrue(settings.acceptAudio)
        assertFalse(settings.acceptVideo)
        assertEquals(WalletMode.BUILT_IN, settings.walletMode)
        assertTrue(settings.validate().contains(WorkerSettingsIssue.MISSING_SPARK_API_KEY))
        assertEquals(60L, settings.minBillableSeconds)
        assertTrue(settings.cleanupAfterUpload)
        assertTrue(settings.hoursOfOperation.isOpenAt(LocalTime.of(13, 0)))
    }

    @Test
    fun validatesRequiredFieldsWhenWorkerIsEnabled() {
        val issues = WorkerSettings(enabled = true).validate()

        assertTrue(issues.contains(WorkerSettingsIssue.MISSING_RELAY_URL))
        assertTrue(issues.contains(WorkerSettingsIssue.MISSING_BLOSSOM_URL))
        assertTrue(issues.contains(WorkerSettingsIssue.MISSING_WORKER_SECRET_KEY))
        assertTrue(issues.contains(WorkerSettingsIssue.MISSING_AUDIO_PRICE))
    }

    @Test
    fun supportsAudioAndVideoAccordingToUserToggles() {
        val audioOnly = validSettings(acceptVideo = false)
        val videoToo = validSettings(acceptVideo = true)

        assertTrue(audioOnly.supports(DeliveryMode.AUDIO_ONLY))
        assertFalse(audioOnly.supports(DeliveryMode.FULL))
        assertTrue(videoToo.supports(DeliveryMode.FULL))
    }

    @Test
    fun hoursOfOperationHandlesRegularAndOvernightWindows() {
        val day = HoursOfOperation.daily(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val overnight = HoursOfOperation.daily(LocalTime.of(22, 0), LocalTime.of(6, 0))

        assertTrue(day.isOpenAt(LocalTime.of(12, 0)))
        assertFalse(day.isOpenAt(LocalTime.of(18, 0)))
        assertTrue(overnight.isOpenAt(LocalTime.of(23, 0)))
        assertTrue(overnight.isOpenAt(LocalTime.of(5, 59)))
        assertFalse(overnight.isOpenAt(LocalTime.of(12, 0)))
    }

    @Test
    fun nwcModeRequiresConnectionUri() {
        val issues = validSettings(
            walletMode = WalletMode.NWC,
            nwcConnectionUri = null,
        ).validate()

        assertTrue(issues.contains(WorkerSettingsIssue.MISSING_NWC_CONNECTION_URI))
    }

    @Test
    fun nwcModeRejectsInvalidConnectionUri() {
        val issues = validSettings(
            walletMode = WalletMode.NWC,
            nwcConnectionUri = "nostr+walletconnect://not-a-pubkey?relay=wss://relay.example.com&secret=bad",
        ).validate()

        assertTrue(issues.contains(WorkerSettingsIssue.INVALID_NWC_CONNECTION_URI))
    }

    private fun validSettings(
        acceptVideo: Boolean = false,
        walletMode: WalletMode = WalletMode.BUILT_IN,
        nwcConnectionUri: String? = null,
    ) = WorkerSettings(
        enabled = true,
        relayUrls = listOf("wss://relay.example.com"),
        blossomUrls = listOf("https://blossom.example.com"),
        workerSecretKeyHex = "0".repeat(63) + "1",
        acceptAudio = true,
        acceptVideo = acceptVideo,
        basePriceMsat = 1_000,
        audioPriceMsatPerHour = 60_000,
        videoPriceMsatPerHour = if (acceptVideo) 120_000 else 0,
        walletMode = walletMode,
        sparkApiKey = if (walletMode == WalletMode.BUILT_IN) "test-api-key" else null,
        sparkSeedHex = if (walletMode == WalletMode.BUILT_IN) "00".repeat(16) else null,
        nwcConnectionUri = nwcConnectionUri,
    )
}
