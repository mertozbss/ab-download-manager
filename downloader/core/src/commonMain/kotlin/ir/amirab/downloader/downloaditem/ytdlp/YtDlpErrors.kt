package ir.amirab.downloader.downloaditem.ytdlp

import ir.amirab.downloader.exception.DownloadValidationException

enum class YtDlpErrorKind {
    PrivateVideo,
    AgeRestricted,
    Unavailable,
    LiveNotStarted,
    NeedsUpdate,
    Network,
    InvalidUrl,
    ToolsMissing,
    ToolVerificationFailed,
    UnsafeOutputPath,
    Unknown,
}

/**
 * Error shown on a yt-dlp download item. [message] is human readable (EN + TR) and,
 * for YouTube-side breakages, tells the user to update yt-dlp from settings.
 */
class YtDlpException(
    val kind: YtDlpErrorKind,
    val details: String? = null,
    cause: Throwable? = null,
) : DownloadValidationException(buildMessage(kind, details), cause) {
    val suggestUpdate: Boolean get() = kind == YtDlpErrorKind.NeedsUpdate

    // only network problems are worth an automatic retry
    override fun isCritical(): Boolean = kind != YtDlpErrorKind.Network

    companion object {
        fun buildMessage(kind: YtDlpErrorKind, details: String?): String {
            val base = when (kind) {
                YtDlpErrorKind.PrivateVideo -> "This video is private. / Video gizli."
                YtDlpErrorKind.AgeRestricted -> "This video is age-restricted (sign-in required). / Video yaş kısıtlı (giriş gerekiyor)."
                YtDlpErrorKind.Unavailable -> "This video is unavailable. / Video kullanılamıyor."
                YtDlpErrorKind.LiveNotStarted -> "This live stream has not started yet. / Canlı yayın henüz başlamadı."
                YtDlpErrorKind.NeedsUpdate -> "YouTube rejected the request (403 / bot check). Update yt-dlp: Settings > YouTube > Update components. " +
                        "If it keeps happening, wait a while and try again. / " +
                        "YouTube isteği reddetti (403 / bot doğrulaması). yt-dlp'yi güncelle: Ayarlar > YouTube > Bileşenleri güncelle. " +
                        "Devam ederse bir süre bekleyip tekrar dene."
                YtDlpErrorKind.Network -> "Network error. / Ağ hatası."
                YtDlpErrorKind.InvalidUrl -> "Not a supported YouTube video URL. / Desteklenmeyen YouTube adresi."
                YtDlpErrorKind.ToolsMissing -> "YouTube components (yt-dlp/ffmpeg/deno) could not be installed. / YouTube bileşenleri kurulamadı."
                YtDlpErrorKind.ToolVerificationFailed -> "YouTube component failed SHA-256 verification and was not run. / YouTube bileşeni SHA-256 doğrulamasından geçmedi, çalıştırılmadı."
                YtDlpErrorKind.UnsafeOutputPath -> "yt-dlp reported a file outside the download folder. / yt-dlp indirme klasörü dışında bir dosya bildirdi."
                YtDlpErrorKind.Unknown -> "yt-dlp failed. / yt-dlp hata verdi."
            }
            return if (details.isNullOrBlank()) base else "$base\n$details"
        }
    }
}

object YtDlpErrorClassifier {
    private val needsUpdatePatterns = listOf(
        "http error 403",
        "sign in to confirm you",   // "...you're not a bot"
        "n challenge",
        "nsig extraction failed",
        "signature extraction failed",
        "unable to extract",
        "please report this issue",
        "confirm you are on the latest version",
        "requested format is not available",
        "no supported javascript runtime",
        "only images are available",
        "po token",
    )
    private val networkPatterns = listOf(
        "timed out",
        "connection reset",
        "connection refused",
        "temporary failure in name resolution",
        "getaddrinfo failed",
        "network is unreachable",
        "remote end closed connection",
        "incompleteread",
        "http error 5",
    )

    /**
     * @param outputTail last lines of yt-dlp stderr/stdout
     */
    fun classify(outputTail: List<String>, exitCode: Int?): YtDlpException {
        val errorLines = outputTail.filter { it.contains("ERROR", ignoreCase = false) }
        val relevant = (errorLines.ifEmpty { outputTail }).takeLast(5)
        val text = relevant.joinToString("\n").lowercase()
        val details = relevant.joinToString("\n").take(1000).ifBlank { exitCode?.let { "exit code $it" } }
        val kind = when {
            "private video" in text -> YtDlpErrorKind.PrivateVideo
            "confirm your age" in text || "age-restricted" in text || "age restricted" in text -> YtDlpErrorKind.AgeRestricted
            "live event will begin" in text || "premieres in" in text -> YtDlpErrorKind.LiveNotStarted
            needsUpdatePatterns.any { it in text } -> YtDlpErrorKind.NeedsUpdate
            "video unavailable" in text || "video is unavailable" in text || "has been removed" in text || "not available in your country" in text -> YtDlpErrorKind.Unavailable
            networkPatterns.any { it in text } -> YtDlpErrorKind.Network
            else -> YtDlpErrorKind.Unknown
        }
        return YtDlpException(kind, details)
    }
}
