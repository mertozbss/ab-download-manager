package ir.amirab.downloader.downloaditem.ytdlp

/**
 * Parallel DASH-fragment mode (`youtube:formats=dashy` + `--concurrent-fragments`) is ~1.8x faster,
 * but YouTube occasionally rejects those fragment requests with HTTP 403. In that case the job
 * retries once in the plain single-connection mode instead of failing with "update yt-dlp".
 */
object YtDlpFallbackPolicy {
    fun shouldFallBackToSingleConnection(
        error: Throwable,
        usedFragments: Boolean,
        alreadyFellBack: Boolean,
        outputTail: List<String>,
    ): Boolean {
        if (!usedFragments || alreadyFellBack) return false
        if (error !is YtDlpException) return false
        if (error.kind != YtDlpErrorKind.NeedsUpdate && error.kind != YtDlpErrorKind.Unknown) return false
        return outputTail.any {
            val l = it.lowercase()
            "http error 403" in l || "fragment" in l && "not found" in l
        }
    }
}
