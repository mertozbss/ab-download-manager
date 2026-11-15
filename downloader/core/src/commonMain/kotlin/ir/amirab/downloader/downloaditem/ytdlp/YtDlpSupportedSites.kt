package ir.amirab.downloader.downloaditem.ytdlp

/**
 * Single source of truth for which sites the yt-dlp downloader accepts (mertozbss fork).
 *
 * Only YouTube is supported for now. To support more yt-dlp sites later, extend [allowedHosts]
 * and teach [YouTubeUrlNormalizer] (or a sibling normalizer) how to canonicalize their URLs.
 */
object YtDlpSupportedSites {
    val allowedHosts: Set<String> = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
    )

    fun isAllowedHost(host: String?): Boolean {
        if (host == null) return false
        return host.lowercase().trimEnd('.') in allowedHosts
    }
}
