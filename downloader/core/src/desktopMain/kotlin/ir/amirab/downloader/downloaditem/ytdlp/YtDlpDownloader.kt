package ir.amirab.downloader.downloaditem.ytdlp

import ir.amirab.downloader.DownloadManager
import ir.amirab.downloader.Downloader
import ir.amirab.downloader.downloaditem.IDownloadItem
import ir.amirab.downloader.downloaditem.ytdlp.tools.YtDlpToolManager
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/** desktop only: registered in the desktop DI, not on Android */
class YtDlpDownloader(
    val toolManager: YtDlpToolManager,
) : Downloader<YtDlpDownloadItem, YtDlpDownloadJob, YtDlpDownloadCredentials> {

    val infoFetcher = YtDlpInfoFetcher(toolManager)

    override fun createJob(item: YtDlpDownloadItem, downloadManager: DownloadManager): YtDlpDownloadJob {
        return YtDlpDownloadJob(
            downloadItem = item,
            downloadManager = downloadManager,
            toolManager = toolManager,
        )
    }

    override fun accept(item: IDownloadItem): Boolean = item is YtDlpDownloadItem

    override val downloadItemClass: KClass<YtDlpDownloadItem> = YtDlpDownloadItem::class
    override val downloadCredentialsClass: KClass<YtDlpDownloadCredentials> = YtDlpDownloadCredentials::class
    override val downloadJobClass: KClass<YtDlpDownloadJob> = YtDlpDownloadJob::class
    override val downloadItemSerializer: KSerializer<YtDlpDownloadItem> = YtDlpDownloadItem.serializer()
    override val downloadCredentialsSerializer: KSerializer<YtDlpDownloadCredentials> =
        YtDlpDownloadCredentials.serializer()

    override fun convertToCredentials(item: YtDlpDownloadItem): YtDlpDownloadCredentials {
        return YtDlpDownloadCredentials.from(item)
    }
}
