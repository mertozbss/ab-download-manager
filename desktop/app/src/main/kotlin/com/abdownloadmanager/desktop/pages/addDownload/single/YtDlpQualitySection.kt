package com.abdownloadmanager.desktop.pages.addDownload.single

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.downloaderinui.DownloadSize
import com.abdownloadmanager.shared.downloaderinui.rememberString
import com.abdownloadmanager.shared.downloaderinui.ytdlp.YtDlpNewDownloadInputs
import com.abdownloadmanager.shared.downloaderinui.ytdlp.YtDlpStrings
import com.abdownloadmanager.shared.ui.widget.LoadingIndicator
import com.abdownloadmanager.shared.ui.widget.RadioButton
import com.abdownloadmanager.shared.ui.widget.Text
import com.abdownloadmanager.shared.util.convertDurationToHumanReadable
import com.abdownloadmanager.shared.util.div
import com.abdownloadmanager.shared.util.ui.WithContentAlpha
import com.abdownloadmanager.shared.util.ui.myColors
import com.abdownloadmanager.shared.util.ui.theme.myShapes
import com.abdownloadmanager.shared.util.ui.theme.myTextSizes
import ir.amirab.util.compose.resources.myStringResource

/**
 * YouTube part of the add-download window (mertozbss fork): title / channel / duration and the
 * simple quality choices with estimated sizes. All text is rendered as plain text.
 */
@Composable
fun YtDlpQualitySection(inputs: YtDlpNewDownloadInputs) {
    val isLoading by inputs.isLoading.collectAsState()
    val info by inputs.videoInfo.collectAsState()
    val error by inputs.error.collectAsState()
    val options by inputs.qualityOptions.collectAsState()
    val selected by inputs.quality.collectAsState()

    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(myShapes.defaultRounded)
            .background(myColors.surface / 50)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        when {
            isLoading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(myStringResource(Res.string.ytdlp_loading_video_info), fontSize = myTextSizes.base)
                }
                Spacer(Modifier.height(4.dp))
                WithContentAlpha(0.6f) {
                    Text(myStringResource(Res.string.ytdlp_first_run_hint), fontSize = myTextSizes.sm)
                }
            }

            error != null && info == null -> {
                Text(
                    text = (error?.message ?: error?.let { it::class.simpleName }).orEmpty().take(600),
                    color = myColors.error,
                    fontSize = myTextSizes.sm,
                )
            }

            else -> {
                info?.let { videoInfo ->
                    Text(
                        text = videoInfo.title.orEmpty(),
                        fontWeight = FontWeight.Bold,
                        fontSize = myTextSizes.base,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    WithContentAlpha(0.7f) {
                        val duration = videoInfo.duration?.let { convertDurationToHumanReadable(it).rememberString() }
                        val parts = listOfNotNull(
                            videoInfo.channel?.let { "${myStringResource(Res.string.ytdlp_channel)}: $it" },
                            duration?.let { "${myStringResource(Res.string.ytdlp_duration)}: $it" },
                        )
                        Text(parts.joinToString("  ·  "), fontSize = myTextSizes.sm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                    for (option in options) {
                        val isSelected = option.quality == selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(myShapes.defaultRounded)
                                .clickable { inputs.selectQuality(option.quality) }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            RadioButton(
                                value = isSelected,
                                onValueChange = { inputs.selectQuality(option.quality) },
                                size = 14.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                myStringResource(YtDlpStrings.qualityTitle(option.quality)),
                                modifier = Modifier.weight(1f),
                                fontSize = myTextSizes.base,
                            )
                            option.estimatedSize?.let { size ->
                                WithContentAlpha(0.7f) {
                                    Text("~ " + DownloadSize.Bytes(size).rememberString(), fontSize = myTextSizes.sm)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
