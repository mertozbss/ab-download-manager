package ir.amirab.downloader.downloaditem.ytdlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The simple quality choices shown to the user (not the raw yt-dlp format list).
 */
@Serializable
enum class YtDlpQuality(
    /** max video height, null for audio-only */
    val maxHeight: Int?,
    val isAudioOnly: Boolean,
    /** extension we expect the final file to have (used for the suggested name) */
    val expectedExtension: String,
) {
    /** default: best up to 1080p, MP4 */
    @SerialName("best1080")
    Best1080(1080, false, "mp4"),

    @SerialName("p2160")
    P2160(2160, false, "mp4"),

    @SerialName("p1440")
    P1440(1440, false, "mp4"),

    @SerialName("p720")
    P720(720, false, "mp4"),

    @SerialName("p480")
    P480(480, false, "mp4"),

    /** original audio stream, no re-encoding */
    @SerialName("audio_m4a")
    AudioM4a(null, true, "m4a"),

    /** ffmpeg conversion */
    @SerialName("audio_mp3")
    AudioMp3(null, true, "mp3");

    /** yt-dlp arguments that select / post-process formats for this quality */
    fun formatArgs(): List<String> {
        return when (this) {
            Best1080 -> listOf(
                "-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080]",
                "--merge-output-format", "mp4",
            )

            P2160, P1440, P720, P480 -> {
                val h = requireNotNull(maxHeight)
                listOf(
                    "-f", "bv*+ba/b",
                    // highest resolution up to h (smallest dimension, so vertical videos work too),
                    // then prefer mp4/m4a at equal resolution
                    "-S", "res:$h,ext:mp4:m4a",
                    // 1440p/2160p are often VP9/AV1 only; fall back to mkv when mp4 can't hold them
                    "--merge-output-format", "mp4/mkv",
                )
            }

            AudioM4a -> listOf(
                "-f", "ba[ext=m4a]/ba",
            )

            AudioMp3 -> listOf(
                "-f", "ba/b",
                "-x", "--audio-format", "mp3", "--audio-quality", "0",
            )
        }
    }

    /** how many separate downloads (video + audio) yt-dlp will most likely do */
    fun expectedDownloadPhases(): Int = if (isAudioOnly) 1 else 2

    companion object {
        val Default = Best1080
    }
}
