package com.abdownloadmanager.shared.downloaderinui.ytdlp

import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.downloaderinui.BasicDownloadItem
import com.abdownloadmanager.shared.downloaderinui.DownloadSize
import com.abdownloadmanager.shared.downloaderinui.DownloaderInUi
import com.abdownloadmanager.shared.downloaderinui.edit.DownloadConflictDetector
import com.abdownloadmanager.shared.downloaderinui.edit.EditDownloadChecker
import com.abdownloadmanager.shared.util.DownloadSystem
import com.abdownloadmanager.shared.util.SizeAndSpeedUnitProvider
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.downloaditem.ytdlp.IYtDlpCredentials
import ir.amirab.downloader.downloaditem.ytdlp.YouTubeUrlNormalizer
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloadCredentials
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloadItem
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloadJob
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloader
import ir.amirab.downloader.monitor.DurationBasedProcessingDownloadItemState
import ir.amirab.downloader.monitor.ProcessingDownloadItemFactoryInputs
import ir.amirab.downloader.monitor.ProcessingDownloadItemState
import ir.amirab.util.compose.StringSource
import ir.amirab.util.compose.asStringSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** desktop only (mertozbss fork): YouTube downloads through yt-dlp */
class YtDlpDownloaderInUi(
    downloader: YtDlpDownloader,
    private val sizeAndSpeedUnitProvider: SizeAndSpeedUnitProvider,
) : DownloaderInUi<
        YtDlpDownloadCredentials,
        YtDlpResponseInfo,
        DownloadSize.Bytes,
        YtDlpLinkChecker,
        YtDlpDownloadItem,
        YtDlpNewDownloadInputs,
        YtDlpEditDownloadInputs,
        YtDlpItemToCredentialMapper,
        YtDlpDownloadJob,
        YtDlpDownloader,
        >(downloader) {

    override fun newDownloadUiChecker(
        initialCredentials: YtDlpDownloadCredentials,
        initialFolder: String,
        initialName: String,
        downloadSystem: DownloadSystem,
        scope: CoroutineScope
    ): YtDlpNewDownloadUiChecker {
        return YtDlpNewDownloadUiChecker(
            initialCredentials = initialCredentials,
            linkCheckerFactory = this,
            initialFolder = initialFolder,
            initialName = initialName,
            downloadSystem = downloadSystem,
            scope = scope,
        )
    }

    override fun acceptDownloadCredentials(item: IDownloadCredentials): Boolean = item is IYtDlpCredentials

    override fun supportsThisLink(link: String): Boolean = YouTubeUrlNormalizer.normalize(link) != null

    override fun createMinimumCredentials(link: String): YtDlpDownloadCredentials {
        return YtDlpDownloadCredentials(link = YouTubeUrlNormalizer.normalize(link) ?: link)
    }

    override fun createBareDownloadItem(
        credentials: YtDlpDownloadCredentials,
        basicDownloadItem: BasicDownloadItem
    ): YtDlpDownloadItem {
        return YtDlpDownloadItem.createWithCredentials(
            credentials = credentials,
            id = -1,
            folder = basicDownloadItem.folder,
            name = basicDownloadItem.name,
            contentLength = basicDownloadItem.contentLength,
            speedLimit = basicDownloadItem.speedLimit,
            fileChecksum = basicDownloadItem.fileChecksum,
        )
    }

    override fun createProcessingDownloadItemState(
        props: ProcessingDownloadItemFactoryInputs<YtDlpDownloadJob>
    ): ProcessingDownloadItemState {
        val job = props.downloadJob
        val item = job.downloadItem
        return DurationBasedProcessingDownloadItemState(
            id = job.id,
            name = item.name,
            folder = item.folder,
            downloadLink = item.link,
            contentLength = item.contentLength,
            saveLocation = item.name,
            dateAdded = item.dateAdded,
            startTime = item.startTime ?: 0,
            completeTime = item.completeTime ?: 0,
            status = job.status.value,
            speed = props.speed,
            parts = emptyList(),
            supportResume = true,
            optimisticLength = item.estimatedSize ?: item.contentLength,
            duration = item.duration,
            progress = item.downloadedBytes,
            percent = item.progressPercent,
            isWaiting = props.isWaiting,
        )
    }

    override val name: StringSource = Res.string.ytdlp_downloader_name.asStringSource()

    override fun createLinkChecker(initialCredentials: YtDlpDownloadCredentials): YtDlpLinkChecker {
        return YtDlpLinkChecker(initialCredentials, downloader.infoFetcher)
    }

    override fun createEditDownloadChecker(
        currentDownloadItem: MutableStateFlow<YtDlpDownloadItem>,
        editedDownloadItem: MutableStateFlow<YtDlpDownloadItem>,
        linkChecker: YtDlpLinkChecker,
        conflictDetector: DownloadConflictDetector,
        scope: CoroutineScope
    ): EditDownloadChecker<YtDlpDownloadItem, YtDlpDownloadCredentials, YtDlpResponseInfo, DownloadSize.Bytes, YtDlpLinkChecker> {
        return YtDlpEditDownloadChecker(currentDownloadItem, editedDownloadItem, linkChecker, conflictDetector, scope)
    }

    override fun createNewDownloadInputs(
        initialCredentials: YtDlpDownloadCredentials,
        initialFolder: String,
        initialName: String,
        downloadSystem: DownloadSystem,
        scope: CoroutineScope
    ): YtDlpNewDownloadInputs {
        return YtDlpNewDownloadInputs(
            newDownloadUiChecker(initialCredentials, initialFolder, initialName, downloadSystem, scope),
            sizeAndSpeedUnitProvider,
            scope,
        )
    }

    override fun createEditDownloadInputs(
        currentDownloadItem: MutableStateFlow<YtDlpDownloadItem>,
        editedDownloadItem: MutableStateFlow<YtDlpDownloadItem>,
        conflictDetector: DownloadConflictDetector,
        scope: CoroutineScope
    ): YtDlpEditDownloadInputs {
        return YtDlpEditDownloadInputs(
            currentDownloadItem = currentDownloadItem,
            editedDownloadItem = editedDownloadItem,
            mapper = YtDlpItemToCredentialMapper(),
            conflictDetector = conflictDetector,
            linkCheckerFactory = this,
            editDownloadCheckerFactory = this,
            scope = scope,
            sizeAndSpeedUnitProvider = sizeAndSpeedUnitProvider,
        )
    }
}
