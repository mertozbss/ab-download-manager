package ir.amirab.downloader.downloaditem.ytdlp

import ir.amirab.downloader.destination.DestWriter
import ir.amirab.downloader.destination.DownloadDestination
import ir.amirab.downloader.part.DownloadPart
import java.io.File

/**
 * yt-dlp writes the file itself. Partial / fragment files (.part, .ytdl, .fNNN.*) live in a
 * per-download temp folder (`-P temp:`) that belongs only to this item, so cleanup never touches
 * other files.
 */
class YtDlpDownloadDestination(
    outputFile: File,
    private val tempDir: File,
    private val tempRoot: File,
    /** the output file is only ours to delete once yt-dlp produced it */
    private val canDeleteOutputFile: () -> Boolean,
) : DownloadDestination(outputFile) {
    override fun getWriterFor(part: DownloadPart): DestWriter {
        error("yt-dlp downloads don't use part writers")
    }

    override fun canGetFileWriter(): Boolean = false
    override suspend fun prepareFile(onProgressUpdate: (Int?) -> Unit) {}
    override suspend fun isDownloadedPartsIsValid(): Boolean = true
    override fun flush() {}

    override fun cleanUpJunkFiles() {
        deleteTempDir()
    }

    override fun deleteOutputFile() {
        if (canDeleteOutputFile()) {
            super.deleteOutputFile()
        }
    }

    fun deleteTempDir() {
        val dir = runCatching { tempDir.canonicalFile }.getOrNull() ?: return
        val root = runCatching { tempRoot.canonicalFile }.getOrNull() ?: return
        // defense in depth: only ever delete inside <downloadData>/ytdlp/
        if (!dir.path.startsWith(root.path + File.separator)) return
        // files may still be locked for a moment after the process tree was killed (Windows)
        repeat(10) {
            if (!dir.exists() || dir.deleteRecursively()) return
            Thread.sleep(200)
        }
    }
}
