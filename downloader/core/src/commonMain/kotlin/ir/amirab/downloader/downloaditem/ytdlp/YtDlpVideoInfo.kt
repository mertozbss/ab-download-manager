package ir.amirab.downloader.downloaditem.ytdlp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class YtDlpFormatInfo(
    val formatId: String,
    val ext: String?,
    val height: Int?,
    val vcodec: String?,
    val acodec: String?,
    val filesize: Long?,
    val filesizeApprox: Long?,
    val tbr: Double?,
) {
    val hasVideo get() = vcodec != null && vcodec != "none"
    val hasAudio get() = acodec != null && acodec != "none"
    val size: Long? get() = filesize ?: filesizeApprox
}

data class YtDlpVideoInfo(
    val id: String,
    val title: String?,
    val channel: String?,
    /** seconds */
    val duration: Double?,
    val isLive: Boolean,
    val formats: List<YtDlpFormatInfo>,
)

data class YtDlpQualityOption(
    val quality: YtDlpQuality,
    /** null = unknown */
    val estimatedSize: Long?,
)

/** parses `yt-dlp -J` output and maps it to the simple quality choices */
object YtDlpInfoParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(jsonText: String): YtDlpVideoInfo {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val id = root.str("id")?.takeIf { YouTubeUrlNormalizer.isValidVideoId(it) }
            ?: throw IllegalArgumentException("yt-dlp returned no valid video id")
        val formats = (root["formats"] as? JsonArray).orEmpty().mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            YtDlpFormatInfo(
                formatId = f.str("format_id") ?: return@mapNotNull null,
                ext = f.str("ext"),
                height = f.long("height")?.toInt(),
                vcodec = f.str("vcodec"),
                acodec = f.str("acodec"),
                filesize = f.long("filesize"),
                filesizeApprox = f.long("filesize_approx"),
                tbr = f.double("tbr"),
            )
        }
        return YtDlpVideoInfo(
            id = id,
            title = root.str("title"),
            channel = root.str("channel") ?: root.str("uploader"),
            duration = root.double("duration"),
            isLive = root.bool("is_live") == true,
            formats = formats,
        )
    }

    /**
     * Options to show: Best (<=1080p, default), the higher/lower tiers that actually exist, and audio.
     * The size is estimated the same way yt-dlp would pick formats for that option.
     */
    fun qualityOptions(info: YtDlpVideoInfo): List<YtDlpQualityOption> {
        val videos = info.formats.filter { it.hasVideo && it.height != null }
        val audios = info.formats.filter { it.hasAudio && !it.hasVideo }
        val maxHeight = videos.maxOfOrNull { it.height!! } ?: 0

        // Best1080: "bv*[height<=1080][ext=mp4]" -> mp4 first
        fun bestMp4Video(maxH: Int): YtDlpFormatInfo? {
            val candidates = videos.filter { it.height!! <= maxH }
            return candidates.filter { it.ext == "mp4" }.maxWithOrNull(compareBy({ it.height }, { it.tbr ?: 0.0 }))
                ?: candidates.maxWithOrNull(compareBy({ it.height }, { it.tbr ?: 0.0 }))
        }

        // tiers: "-S res:H,ext:mp4:m4a" -> resolution first, mp4 on ties
        fun bestVideo(maxH: Int): YtDlpFormatInfo? {
            return videos.filter { it.height!! <= maxH }
                .maxWithOrNull(compareBy({ it.height }, { if (it.ext == "mp4") 1 else 0 }, { it.tbr ?: 0.0 }))
        }

        val bestM4a = audios.filter { it.ext == "m4a" }.maxByOrNull { it.tbr ?: 0.0 }
        val bestAudio = audios.maxByOrNull { it.tbr ?: 0.0 }

        fun sum(vararg f: YtDlpFormatInfo?): Long? {
            if (f.any { it == null }) return null
            val sizes = f.map { it!!.size }
            return if (sizes.any { it == null }) null else sizes.sumOf { it!! }
        }

        val result = mutableListOf<YtDlpQualityOption>()
        if (videos.isNotEmpty()) {
            result += YtDlpQualityOption(YtDlpQuality.Best1080, sum(bestMp4Video(1080), bestM4a ?: bestAudio))
            for (q in listOf(YtDlpQuality.P2160, YtDlpQuality.P1440, YtDlpQuality.P720, YtDlpQuality.P480)) {
                val h = q.maxHeight!!
                val exists = videos.any { it.height == h }
                // 720p/480p only make sense when Best would pick something bigger
                val useful = if (h > 1080) maxHeight >= h else maxHeight > h
                if (exists && useful) {
                    result += YtDlpQualityOption(q, sum(bestVideo(h), bestM4a ?: bestAudio))
                }
            }
        }
        if (audios.isNotEmpty() || videos.isEmpty()) {
            result += YtDlpQualityOption(YtDlpQuality.AudioM4a, (bestM4a ?: bestAudio)?.size)
            // MP3 is re-encoded (VBR ~245 kbps for --audio-quality 0): rough estimate from duration
            result += YtDlpQualityOption(
                YtDlpQuality.AudioMp3,
                info.duration?.let { (it * 245_000 / 8).toLong() },
            )
        }
        return result
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.double(key: String): Double? = prim(key)?.doubleOrNull
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
