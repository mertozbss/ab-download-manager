package ir.amirab.downloader.downloaditem.ytdlp

import java.io.File

/**
 * File name / path safety for yt-dlp output (mertozbss fork).
 *
 * The user can edit the file name in the quality window, so it is untrusted input:
 * - no path separators, no "..", no drive letters, no Windows-reserved characters or names
 * - '%' is escaped because the name becomes part of a yt-dlp output template
 * - the final file reported by yt-dlp must be inside the chosen folder ([isInsideFolder])
 */
object YtDlpFileNames {
    const val MAX_NAME_BYTES = 150
    private const val FALLBACK_NAME = "video"

    private val knownExtensions = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "ogg", "aac", "flac", "wav")
    private val reservedWindowsNames = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }
    private val forbiddenChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * Makes [input] safe to use as a bare file name (without extension).
     */
    fun sanitizeBaseName(input: String, maxBytes: Int = MAX_NAME_BYTES): String {
        val sb = StringBuilder()
        for (ch in input) {
            sb.append(
                when {
                    ch.isISOControl() -> ' '
                    ch in forbiddenChars -> '_'
                    else -> ch
                }
            )
        }
        var s = sb.toString()
        // collapse any ".." so the name can never be a parent-directory reference
        while (s.contains("..")) {
            s = s.replace("..", ".")
        }
        s = s.replace(Regex("\\s+"), " ")
        // Windows strips trailing dots/spaces; leading dots make hidden files / relative tricks
        s = s.trim().trim('.').trim()
        s = truncateUtf8(s, maxBytes).trim().trimEnd('.').trim()
        if (s.isEmpty()) {
            s = FALLBACK_NAME
        }
        if (s.substringBefore('.').uppercase() in reservedWindowsNames) {
            s = "_$s"
        }
        return s
    }

    /** "Title [id]" like yt-dlp's `%(title).150B [%(id)s]` */
    fun suggestedBaseName(title: String?, videoId: String?): String {
        val t = title?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
        val suffix = videoId?.takeIf { YouTubeUrlNormalizer.isValidVideoId(it) }?.let { " [$it]" }.orEmpty()
        return sanitizeBaseName(truncateUtf8(t, MAX_NAME_BYTES).trim() + suffix, MAX_NAME_BYTES + suffix.length)
    }

    fun suggestedFileName(title: String?, videoId: String?, quality: YtDlpQuality): String {
        return suggestedBaseName(title, videoId) + "." + quality.expectedExtension
    }

    /** removes a known media extension (the real one is decided by yt-dlp) */
    fun stripKnownExtension(name: String): String {
        val ext = name.substringAfterLast('.', "")
        return if (ext.lowercase() in knownExtensions && name.length > ext.length + 1) {
            name.dropLast(ext.length + 1)
        } else {
            name
        }
    }

    /** escape for use inside a yt-dlp output template */
    fun escapeForTemplate(name: String): String = name.replace("%", "%%")

    /** the `-o` value for a user supplied file name */
    fun outputTemplateFor(userFileName: String): String {
        val base = sanitizeBaseName(stripKnownExtension(userFileName))
        return escapeForTemplate(base) + ".%(ext)s"
    }

    /** true if [file] (after resolving symlinks / "..") is located inside [folder] */
    fun isInsideFolder(folder: File, file: File): Boolean {
        val root = runCatching { folder.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        var p: File? = target.parentFile
        while (p != null) {
            if (p == root) return true
            p = p.parentFile
        }
        return false
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chars = Character.charCount(cp)
            val b = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + b > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += b
            i += chars
        }
        return sb.toString()
    }
}
