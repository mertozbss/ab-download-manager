package com.abdownloadmanager.shared.downloaderinui.ytdlp

import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.downloaderinui.CredentialAndItemMapper
import com.abdownloadmanager.shared.downloaderinui.DownloadSize
import com.abdownloadmanager.shared.downloaderinui.LinkCheckerFactory
import com.abdownloadmanager.shared.downloaderinui.edit.CanEditDownloadResult
import com.abdownloadmanager.shared.downloaderinui.edit.DownloadConflictDetector
import com.abdownloadmanager.shared.downloaderinui.edit.EditDownloadChecker
import com.abdownloadmanager.shared.downloaderinui.edit.EditDownloadCheckerFactory
import com.abdownloadmanager.shared.downloaderinui.edit.EditDownloadInputs
import com.abdownloadmanager.shared.ui.configurable.item.ToggleableSpeedLimitConfigurable
import com.abdownloadmanager.shared.util.SizeAndSpeedUnitProvider
import com.abdownloadmanager.shared.util.convertPositiveSpeedToHumanReadable
import ir.amirab.downloader.downloaditem.DownloadJobExtraConfig
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpArgsBuilder
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloadCredentials
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpDownloadItem
import ir.amirab.util.FileNameValidator
import ir.amirab.util.compose.StringSource
import ir.amirab.util.compose.asStringSource
import ir.amirab.util.compose.combineStringSources
import ir.amirab.util.flow.mapTwoWayStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class YtDlpItemToCredentialMapper : CredentialAndItemMapper<YtDlpDownloadCredentials, YtDlpDownloadItem> {
    override fun itemToCredentials(item: YtDlpDownloadItem): YtDlpDownloadCredentials {
        return YtDlpDownloadCredentials(link = item.link, downloadPage = item.downloadPage, title = item.title)
    }

    override fun appliedCredentialsToItem(item: YtDlpDownloadItem, credentials: YtDlpDownloadCredentials): YtDlpDownloadItem {
        return item.copy().withCredentials(credentials)
    }

    override fun itemWithEditedName(item: YtDlpDownloadItem, name: String): YtDlpDownloadItem {
        return item.copy(name = name)
    }

    override fun credentialsWithEditedLink(credentials: YtDlpDownloadCredentials, link: String): YtDlpDownloadCredentials {
        return credentials.copy(link = link)
    }
}

class YtDlpEditDownloadChecker(
    currentDownloadItem: MutableStateFlow<YtDlpDownloadItem>,
    editedDownloadItem: MutableStateFlow<YtDlpDownloadItem>,
    linkChecker: YtDlpLinkChecker,
    conflictDetector: DownloadConflictDetector,
    scope: CoroutineScope,
) : EditDownloadChecker<YtDlpDownloadItem, YtDlpDownloadCredentials, YtDlpResponseInfo, DownloadSize.Bytes, YtDlpLinkChecker>(
    currentDownloadItem = currentDownloadItem,
    editedDownloadItem = editedDownloadItem,
    linkChecker = linkChecker,
    conflictDetector = conflictDetector,
    scope = scope,
) {
    init {
        editedDownloadItem.onEach { _canEditResult.value = CanEditDownloadResult.Waiting }.launchIn(scope)
    }

    override fun check() {
        val current = currentDownloadItem.value
        val edited = editedDownloadItem.value
        _canEditResult.value = when {
            current == edited -> CanEditDownloadResult.NothingChanged
            runCatching { YtDlpArgsBuilder.validateUrl(edited.link) }.isFailure -> CanEditDownloadResult.InvalidURL
            edited.name != current.name && !FileNameValidator.isValidFileName(edited.name) -> CanEditDownloadResult.InvalidFileName
            edited.name != current.name && conflictDetector.checkAlreadyExists(current, edited) -> CanEditDownloadResult.FileNameAlreadyExists
            else -> CanEditDownloadResult.CanEdit(emptyList())
        }
    }
}

class YtDlpEditDownloadInputs(
    currentDownloadItem: MutableStateFlow<YtDlpDownloadItem>,
    editedDownloadItem: MutableStateFlow<YtDlpDownloadItem>,
    mapper: YtDlpItemToCredentialMapper,
    conflictDetector: DownloadConflictDetector,
    linkCheckerFactory: LinkCheckerFactory<YtDlpDownloadCredentials, YtDlpResponseInfo, DownloadSize.Bytes, YtDlpLinkChecker>,
    editDownloadCheckerFactory: EditDownloadCheckerFactory<YtDlpDownloadItem, YtDlpDownloadCredentials, YtDlpResponseInfo, DownloadSize.Bytes, YtDlpLinkChecker>,
    scope: CoroutineScope,
    private val sizeAndSpeedUnitProvider: SizeAndSpeedUnitProvider,
) : EditDownloadInputs<
        YtDlpDownloadItem,
        YtDlpDownloadCredentials,
        YtDlpResponseInfo,
        DownloadSize.Bytes,
        YtDlpLinkChecker,
        YtDlpItemToCredentialMapper,
        >(
    currentDownloadItem,
    editedDownloadItem,
    mapper = mapper,
    scope = scope,
    conflictDetector = conflictDetector,
    linkCheckerFactory = linkCheckerFactory,
    editDownloadCheckerFactory = editDownloadCheckerFactory,
) {
    init {
        // size estimate shown in the edit window should match the item's quality
        linkChecker.selectedQuality.value = currentDownloadItem.value.quality
    }

    override val configurableList = listOf(
        ToggleableSpeedLimitConfigurable(
            Res.string.download_item_settings_speed_limit.asStringSource(),
            Res.string.download_item_settings_speed_limit_description.asStringSource(),
            backedBy = editedDownloadItem.mapTwoWayStateFlow(
                map = { it.speedLimit },
                unMap = { copy(speedLimit = it) }
            ),
            describe = {
                if (it == 0L) Res.string.unlimited.asStringSource()
                else convertPositiveSpeedToHumanReadable(it, sizeAndSpeedUnitProvider.speedUnit.value).asStringSource()
            }
        ),
    )

    override val downloadJobConfig: StateFlow<DownloadJobExtraConfig?> = MutableStateFlow(null)

    override fun applyEditedItemTo(item: YtDlpDownloadItem) {
        val edited = editedDownloadItem.value
        item.link = edited.link
        item.downloadPage = edited.downloadPage
        item.folder = edited.folder
        item.name = edited.name
        item.speedLimit = edited.speedLimit
    }

    override fun downloadSizeToStringSource(downloadSize: DownloadSize.Bytes): StringSource {
        return listOf("~ ".asStringSource(), downloadSize.asStringSource(sizeAndSpeedUnitProvider.sizeUnit.value))
            .combineStringSources()
    }
}
