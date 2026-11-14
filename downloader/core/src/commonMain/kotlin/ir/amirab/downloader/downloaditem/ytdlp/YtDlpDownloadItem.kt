package ir.amirab.downloader.downloaditem.ytdlp

import arrow.core.Option
import arrow.core.getOrElse
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.downloaditem.IDownloadItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("ytdlp")
data class YtDlpDownloadItem(
    override var id: Long,
    override var folder: String,
    override var name: String,
    override var link: String,
    /** total bytes; an estimate while downloading, exact file size after completion */
    override var contentLength: Long = IDownloadItem.LENGTH_UNKNOWN,
    override var downloadPage: String? = null,
    override var dateAdded: Long,
    override var startTime: Long? = null,
    override var completeTime: Long? = null,
    override var status: DownloadStatus = DownloadStatus.Added,
    override var preferredConnectionCount: Int? = null,
    override var speedLimit: Long = 0,
    override var fileChecksum: String? = null,
    override var title: String? = null,
    var quality: YtDlpQuality = YtDlpQuality.Default,
    var channel: String? = null,
    /** seconds */
    var duration: Double? = null,
    /** size estimate from the quality window, used to merge the video+audio progress */
    var estimatedSize: Long? = null,
    /** persisted progress so the list shows the right value after an app restart */
    var downloadedBytes: Long = 0,
    var progressPercent: Int = 0,
) : IDownloadItem, IYtDlpCredentials {
    override fun copy(
        id: Option<Long>,
        folder: Option<String>,
        name: Option<String>,
        link: Option<String>,
        contentLength: Option<Long>,
        downloadPage: Option<String?>,
        dateAdded: Option<Long>,
        startTime: Option<Long?>,
        completeTime: Option<Long?>,
        status: Option<DownloadStatus>,
        preferredConnectionCount: Option<Int?>,
        speedLimit: Option<Long>,
        fileChecksum: Option<String?>
    ): IDownloadItem {
        return copy(
            id = id.getOrElse { this.id },
            folder = folder.getOrElse { this.folder },
            name = name.getOrElse { this.name },
            link = link.getOrElse { this.link },
            contentLength = contentLength.getOrElse { this.contentLength },
            downloadPage = downloadPage.getOrElse { this.downloadPage },
            dateAdded = dateAdded.getOrElse { this.dateAdded },
            startTime = startTime.getOrElse { this.startTime },
            completeTime = completeTime.getOrElse { this.completeTime },
            status = status.getOrElse { this.status },
            preferredConnectionCount = preferredConnectionCount.getOrElse { this.preferredConnectionCount },
            speedLimit = speedLimit.getOrElse { this.speedLimit },
            fileChecksum = fileChecksum.getOrElse { this.fileChecksum },
        )
    }

    override fun copy(
        link: Option<String>,
        downloadPage: Option<String?>
    ): YtDlpDownloadItem {
        return copy(
            link = link.getOrElse { this.link },
            downloadPage = downloadPage.getOrElse { this.downloadPage },
        )
    }

    override fun validateItem() {
        validateCredentials()
        require(YtDlpFileNames.sanitizeBaseName(YtDlpFileNames.stripKnownExtension(name)).isNotBlank()) {
            "invalid file name"
        }
    }

    override fun withCredentials(credentials: IDownloadCredentials): YtDlpDownloadItem {
        return if (credentials is IYtDlpCredentials) {
            apply {
                link = credentials.link
                downloadPage = credentials.downloadPage
                credentials.title?.let { title = it }
            }
        } else {
            this
        }
    }

    override fun validateCredentials() {
        YtDlpArgsBuilder.validateUrl(link)
    }

    companion object {
        fun createWithCredentials(
            credentials: IYtDlpCredentials,
            id: Long,
            folder: String,
            name: String,
            contentLength: Long = IDownloadItem.LENGTH_UNKNOWN,
            dateAdded: Long = 0,
            preferredConnectionCount: Int? = null,
            speedLimit: Long = 0,
            fileChecksum: String? = null,
            quality: YtDlpQuality = YtDlpQuality.Default,
        ): YtDlpDownloadItem {
            return YtDlpDownloadItem(
                id = id,
                folder = folder,
                name = name,
                link = credentials.link,
                downloadPage = credentials.downloadPage,
                title = credentials.title,
                contentLength = contentLength,
                dateAdded = dateAdded,
                preferredConnectionCount = preferredConnectionCount,
                speedLimit = speedLimit,
                fileChecksum = fileChecksum,
                quality = quality,
            )
        }
    }
}

fun YtDlpDownloadItem.applyFrom(other: YtDlpDownloadItem) {
    id = other.id
    folder = other.folder
    name = other.name
    link = other.link
    contentLength = other.contentLength
    downloadPage = other.downloadPage
    dateAdded = other.dateAdded
    startTime = other.startTime
    completeTime = other.completeTime
    status = other.status
    preferredConnectionCount = other.preferredConnectionCount
    speedLimit = other.speedLimit
    fileChecksum = other.fileChecksum
    title = other.title
    quality = other.quality
    channel = other.channel
    duration = other.duration
    estimatedSize = other.estimatedSize
    downloadedBytes = other.downloadedBytes
    progressPercent = other.progressPercent
}
