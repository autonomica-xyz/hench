package net.nostr.hench.tools

/**
 * Catalog of tools this build can advertise. The settings screen renders the
 * toggles from here; WorkerForegroundService constructs the enabled subset.
 * Adding a tool = add an entry here + a branch in the service constructor map.
 */
object ToolCatalog {
    data class Entry(val name: String, val displayName: String, val blurb: String)

    val all: List<Entry> = listOf(
        Entry("download_media", "Media download (yt-dlp)", "Download audio/video from YouTube & other platforms"),
        Entry("web_fetch", "Web fetch", "Fetch pages in a real mobile browser (isolated, unauthenticated session)"),
        Entry("page_screenshot", "Page screenshot", "Render pages and return a screenshot PNG"),
    )
}
