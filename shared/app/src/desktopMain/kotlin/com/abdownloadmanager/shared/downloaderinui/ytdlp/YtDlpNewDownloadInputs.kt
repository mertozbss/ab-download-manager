package com.abdownloadmanager.shared.downloaderinui.ytdlp

import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.downloaderinui.DownloadSize
import com.abdownloadmanager.shared.downloaderinui.LinkCheckerFactory
import com.abdownloadmanager.shared.downloaderinui.add.NewDownloadInputs
import com.abdownloadmanager.shared.downloaderinui.add.NewDownloadUiChecker
import com.abdownloadmanager.shared.ui.configurable.item.EnumConfigurable
import com.abdownloadmanager.shared.ui.configurable.item.ToggleableSpeedLimitConfigurable
import com.abdownloadmanager.shared.util.DownloadSystem
import com.abdownloadmanager.shared.util.SizeAndSpeedUnitProvider
import com.abdownloadmanager.shared.util.convertPositiveSpeedToHumanReadable
import com.abdownloadmanager.shared.util.perhostsettings.PerHostSettingsItem
import ir.amirab.downloader.downloaditem.DownloadJobExtraConfig
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.IDownloadItem
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloadCredentials
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloadItem
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpFileNames
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpQuality
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpQualityOption
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpVideoInfo
import ir.amirab.util.compose.StringSource
import ir.amirab.util.compose.asStringSource
import ir.amirab.util.compose.combineStringSources
import ir.amirab.util.flow.combineStateFlows
import ir.amirab.util.flow.mapStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class YtDlpNewDownloadUiChecker(
    initialCredentials: YtDlpDownloadCredentials,
    linkCheckerFactory: LinkCheckerFactory<YtDlpDownloadCredentials, YtDlpResponseInfo, DownloadSize.Bytes, YtDlpLinkChecker>,
    initialFolder: String,
    initialName: String,
    downloadSystem: DownloadSystem,
    scope: CoroutineScope,
) : NewDownloadUiChecker<YtDlpDownloadCredentials, YtDlpResponseInfo, DownloadSize.Bytes, YtDlpLinkChecker>(
    initialCredentials = initialCredentials,
    linkCheckerFactory = linkCheckerFactory,
    initialFolder = initialFolder,
    initialName = initialName,
    downloadSystem = downloadSystem,
    scope = scope,
) {
    val ytDlpLinkChecker: YtDlpLinkChecker get() = linkChecker
}

/**
 * Inputs of the YouTube quality window: simple quality choices with size estimates,
 * title / channel / duration, editable file name and the usual folder/category logic.
 */
class YtDlpNewDownloadInputs(
    downloadUiChecker: YtDlpNewDownloadUiChecker,
    private val sizeAndSpeedUnitProvider: SizeAndSpeedUnitProvider,
    scope: CoroutineScope,
) : NewDownloadInputs<
        YtDlpDownloadItem,
        YtDlpDownloadCredentials,
        YtDlpResponseInfo,
        DownloadSize.Bytes,
        YtDlpLinkChecker,
        >(
    newDownloadUiChecker = downloadUiChecker
) {
    private val linkChecker = downloadUiChecker.ytDlpLinkChecker
    private val speedLimit = MutableStateFlow(0L)

    val quality: StateFlow<YtDlpQuality> = linkChecker.selectedQuality
    val qualityOptions: StateFlow<List<YtDlpQualityOption>> = linkChecker.options
    val videoInfo: StateFlow<YtDlpVideoInfo?> = downloadUiChecker.responseInfo.mapStateFlow { it?.info }
    val isLoading = downloadUiChecker.gettingResponseInfo
    val error = downloadUiChecker.responseResult.mapStateFlow { it?.exceptionOrNull() }

    fun selectQuality(newQuality: YtDlpQuality) {
        val old = linkChecker.selectedQuality.value
        if (old == newQuality) return
        linkChecker.selectedQuality.value = newQuality
        // keep the user's name, only swap the extension
        val currentName = name.value
        if (currentName.isNotBlank()) {
            name.update { YtDlpFileNames.stripKnownExtension(it) + "." + newQuality.expectedExtension }
        }
    }

    override val downloadItem: StateFlow<YtDlpDownloadItem> = combineStateFlows(
        this.credentials,
        this.folder,
        this.name,
        this.speedLimit,
        this.quality,
        downloadUiChecker.responseInfo,
    ) { credentials, folder, name, speedLimit, quality, response ->
        val estimate = response?.optionFor(quality)?.estimatedSize
        YtDlpDownloadItem(
            id = -1,
            folder = folder,
            name = name,
            link = credentials.link,
            downloadPage = credentials.downloadPage,
            title = response?.info?.title ?: credentials.title,
            channel = response?.info?.channel,
            duration = response?.info?.duration,
            dateAdded = openedTime,
            status = DownloadStatus.Added,
            speedLimit = speedLimit,
            quality = quality,
            estimatedSize = estimate,
            contentLength = estimate ?: IDownloadItem.LENGTH_UNKNOWN,
        )
    }

    override val downloadJobConfig: StateFlow<DownloadJobExtraConfig?> = MutableStateFlow(null)

    override fun applyHostSettingsToExtraConfig(extraConfig: PerHostSettingsItem) {
        // only the speed limit applies; no credentials/headers are ever passed to yt-dlp
        extraConfig.speedLimit?.let { speedLimit.value = it }
    }

    override val configurableList = listOf(
        EnumConfigurable(
            title = YtDlpStrings.quality.asStringSource(),
            description = YtDlpStrings.qualityDescription.asStringSource(),
            backedBy = linkChecker.selectedQuality,
            itemTitle = { YtDlpStrings.qualityTitle(it).asStringSource() },
            possibleValues = YtDlpQuality.entries.toList(),
        ),
        ToggleableSpeedLimitConfigurable(
            Res.string.download_item_settings_speed_limit.asStringSource(),
            Res.string.download_item_settings_speed_limit_description.asStringSource(),
            backedBy = speedLimit,
            describe = {
                if (it == 0L) Res.string.unlimited.asStringSource()
                else convertPositiveSpeedToHumanReadable(
                    it, sizeAndSpeedUnitProvider.speedUnit.value
                ).asStringSource()
            }
        ),
    )

    override fun downloadSizeToStringSource(downloadSize: DownloadSize.Bytes): StringSource {
        return listOf("~ ".asStringSource(), downloadSize.asStringSource(sizeAndSpeedUnitProvider.sizeUnit.value))
            .combineStringSources()
    }
}
