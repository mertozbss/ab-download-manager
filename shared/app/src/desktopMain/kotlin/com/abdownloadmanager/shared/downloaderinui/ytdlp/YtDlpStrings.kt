package com.abdownloadmanager.shared.downloaderinui.ytdlp

import com.abdownloadmanager.resources.Res
import ir.amirab.downloader.downloaditem.ytdlp.YtDlpQuality
import ir.amirab.util.compose.resources.MyStringResource

object YtDlpStrings {
    val quality: MyStringResource get() = Res.string.ytdlp_quality
    val qualityDescription: MyStringResource get() = Res.string.ytdlp_quality_description

    fun qualityTitle(q: YtDlpQuality): MyStringResource = when (q) {
        YtDlpQuality.Best1080 -> Res.string.ytdlp_quality_best
        YtDlpQuality.P2160 -> Res.string.ytdlp_quality_2160
        YtDlpQuality.P1440 -> Res.string.ytdlp_quality_1440
        YtDlpQuality.P720 -> Res.string.ytdlp_quality_720
        YtDlpQuality.P480 -> Res.string.ytdlp_quality_480
        YtDlpQuality.AudioM4a -> Res.string.ytdlp_quality_audio_m4a
        YtDlpQuality.AudioMp3 -> Res.string.ytdlp_quality_audio_mp3
    }
}
