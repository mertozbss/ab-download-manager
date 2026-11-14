package com.abdownloadmanager.shared.downloaderinui.ytdlp

import com.abdownloadmanager.shared.downloaderinui.DownloadSize
import com.abdownloadmanager.shared.downloaderinui.LinkChecker
import ir.amirab.downloader.connection.IResponseInfo
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloadCredentials
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpFileNames
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpInfoFetcher
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpInfoParser
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpQuality
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpQualityOption
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpVideoInfo
import ir.amirab.util.flow.combineStateFlows
import ir.amirab.util.flow.mapStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** result of `yt-dlp -J` shown in the quality window */
data class YtDlpResponseInfo(
    val info: YtDlpVideoInfo,
    val options: List<YtDlpQualityOption>,
) : IResponseInfo {
    override val isSuccessFul: Boolean = true
    override val requiresAuth: Boolean = false
    override val requireBasicAuth: Boolean = false
    override val resumeSupport: Boolean = true
    override val isWebPage: Boolean = false
    override val unsuccessFullException: Throwable? = null

    fun optionFor(quality: YtDlpQuality) = options.firstOrNull { it.quality == quality }
}

class YtDlpLinkChecker(
    credentials: YtDlpDownloadCredentials,
    private val fetcher: YtDlpInfoFetcher,
) : LinkChecker<YtDlpDownloadCredentials, YtDlpResponseInfo, DownloadSize.Bytes>(
    initialCredentials = credentials
) {
    /** chosen quality; lives here because the size estimate depends on it */
    val selectedQuality = MutableStateFlow(YtDlpQuality.Default)

    private val _suggestedName = MutableStateFlow<String?>(null)
    override val suggestedName: StateFlow<String?> = _suggestedName.asStateFlow()

    val options: StateFlow<List<YtDlpQualityOption>> = responseInfo.mapStateFlow { it?.options.orEmpty() }

    override val downloadSize: StateFlow<DownloadSize.Bytes?> = combineStateFlows(
        responseInfo,
        selectedQuality,
    ) { info, quality ->
        info?.optionFor(quality)?.estimatedSize?.let { DownloadSize.Bytes(it) }
    }

    override fun infoUpdated(responseInfo: YtDlpResponseInfo?) {
        if (responseInfo == null) return
        // keep the quality valid for this video
        if (responseInfo.optionFor(selectedQuality.value) == null) {
            responseInfo.options.firstOrNull()?.let { selectedQuality.value = it.quality }
        }
        _suggestedName.value = YtDlpFileNames.suggestedFileName(
            title = responseInfo.info.title ?: credentials.value.title,
            videoId = responseInfo.info.id,
            quality = selectedQuality.value,
        )
    }

    override suspend fun actualCheck(credentials: YtDlpDownloadCredentials): YtDlpResponseInfo {
        val info = fetcher.fetch(credentials.link)
        return YtDlpResponseInfo(info, YtDlpInfoParser.qualityOptions(info))
    }
}
