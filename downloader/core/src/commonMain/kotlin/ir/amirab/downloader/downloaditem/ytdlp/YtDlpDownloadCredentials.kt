package ir.amirab.downloader.downloaditem.ytdlp

import arrow.core.Option
import arrow.core.getOrElse
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A page URL handled by yt-dlp (YouTube only). Deliberately NOT an http-based credential:
 * no headers / cookies / auth are ever forwarded to yt-dlp.
 */
interface IYtDlpCredentials : IDownloadCredentials {
    /** title as reported by the browser extension (informational only) */
    val title: String?
}

@Serializable
@SerialName("ytdlp")
data class YtDlpDownloadCredentials(
    override val link: String,
    override val downloadPage: String? = null,
    override val title: String? = null,
) : IYtDlpCredentials {
    override fun validateCredentials() {
        YtDlpArgsBuilder.validateUrl(link)
    }

    override fun copy(
        link: Option<String>,
        downloadPage: Option<String?>
    ): IDownloadCredentials {
        return copy(
            link = link.getOrElse { this.link },
            downloadPage = downloadPage.getOrElse { this.downloadPage },
        )
    }

    companion object {
        fun from(credentials: IYtDlpCredentials): YtDlpDownloadCredentials {
            return when (credentials) {
                is YtDlpDownloadCredentials -> credentials
                else -> YtDlpDownloadCredentials(
                    link = credentials.link,
                    downloadPage = credentials.downloadPage,
                    title = credentials.title,
                )
            }
        }

        /** normalizes the URL; throws [YtDlpInvalidUrlException] if it is not an allowed YouTube video */
        fun createValidated(link: String, title: String? = null, downloadPage: String? = null): YtDlpDownloadCredentials {
            return YtDlpDownloadCredentials(
                link = YtDlpArgsBuilder.validateUrl(link),
                downloadPage = downloadPage,
                title = title?.take(500),
            )
        }
    }
}
