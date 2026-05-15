package app.vitune.android.service

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import app.vitune.android.Database
import app.vitune.android.preferences.DataPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@UnstableApi
object CacheExportManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var monitorJob: Job? = null

    fun start(
        context: Context,
        cache: Cache
    ) {

        if (!DataPreferences.cacheExportEnabled) return

        monitorJob?.cancel()

        monitorJob = scope.launch {

            exportFullyCachedSongs(context, cache)

            while (true) {

                delay(DataPreferences.cacheExportIntervalMs)

                if (DataPreferences.cacheExportEnabled) {
                    exportFullyCachedSongs(context, cache)
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun exportNow(
        context: Context,
        cache: Cache,
        onDone: (Int) -> Unit
    ) {

        scope.launch {

            val count =
                exportFullyCachedSongs(context, cache)

            withContext(Dispatchers.Main) {
                onDone(count)
            }
        }
    }

    private suspend fun exportFullyCachedSongs(
        context: Context,
        cache: Cache
    ): Int {

        val exportDir = getExportDir()

        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        var alreadyExported =
            DataPreferences.exportedVideoIds.toMutableSet()

        var exportCount = 0

        val cacheKeys = cache.keys.toList()

        for (videoId in cacheKeys) {

            if (videoId in alreadyExported) continue

            val metadata =
                cache.getContentMetadata(videoId)

            val contentLength =
                androidx.media3.datasource.cache.ContentMetadata
                    .getContentLength(metadata)

            if (contentLength <= 0) continue

            val cachedBytes =
                cache.getCachedBytes(
                    videoId,
                    0,
                    contentLength
                )

            if (cachedBytes < contentLength) continue

            val songInfo =
                Database.songInfo(videoId)
                    ?: continue

            val safeTitle =
                sanitize(songInfo.title)

            val safeArtist =
                sanitize(
                    songInfo.artistsText
                        ?: "Unknown Artist"
                )

            val ext =
                detectExtension(
                    cache,
                    videoId,
                    contentLength
                )

            val fileName =
                "$safeArtist - $safeTitle [$videoId]$ext"

            val outFile =
                File(exportDir, fileName)

            if (outFile.exists()) {

                alreadyExported =
                    alreadyExported
                        .plus(videoId)
                        .toMutableSet()

                DataPreferences.exportedVideoIds =
                    alreadyExported

                continue
            }

            val success =
                mergeCacheSpans(
                    cache,
                    videoId,
                    contentLength,
                    outFile
                )

            if (success) {

                alreadyExported =
                    alreadyExported
                        .plus(videoId)
                        .toMutableSet()

                DataPreferences.exportedVideoIds =
                    alreadyExported

                exportCount++

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outFile.absolutePath),
                    arrayOf("audio/*"),
                    null
                )
            }
        }

        return exportCount
    }

    private fun mergeCacheSpans(
        cache: Cache,
        videoId: String,
        contentLength: Long,
        outFile: File
    ): Boolean {

        return try {

            val tmpFile =
                File(
                    outFile.parent,
                    "${outFile.name}.tmp"
                )

            FileOutputStream(tmpFile).use { out ->

                var position = 0L

                while (position < contentLength) {

                    val span: CacheSpan =
                        cache.startReadWrite(
                            videoId,
                            position,
                            contentLength
                        )

                    try {

                        if (
                            span.isCached &&
                            span.file != null
                        ) {

                            FileInputStream(span.file!!).use { input ->

                                val fileOffset =
                                    position - span.position

                                if (fileOffset > 0) {
                                    input.skip(fileOffset)
                                }

                                val toRead =
                                    minOf(
                                        span.length - fileOffset,
                                        contentLength - position
                                    )

                                val buffer =
                                    ByteArray(65536)

                                var remaining =
                                    toRead

                                while (remaining > 0) {

                                    val read =
                                        input.read(
                                            buffer,
                                            0,
                                            minOf(
                                                buffer.size.toLong(),
                                                remaining
                                            ).toInt()
                                        )

                                    if (read == -1) break

                                    out.write(
                                        buffer,
                                        0,
                                        read
                                    )

                                    remaining -= read
                                }

                                position += toRead
                            }

                        } else {

                            cache.releaseHoleSpan(span)

                            tmpFile.delete()

                            return false
                        }

                    } finally {

                        cache.releaseHoleSpan(span)
                    }
                }
            }

            tmpFile.renameTo(outFile)

            true

        } catch (_: Exception) {

            outFile.delete()

            false
        }
    }

    private fun detectExtension(
        cache: Cache,
        videoId: String,
        contentLength: Long
    ): String {

        return try {

            val span =
                cache.startReadWrite(
                    videoId,
                    0,
                    minOf(16, contentLength)
                )

            val bytes = ByteArray(16)

            var ext = ".webm"

            if (
                span.isCached &&
                span.file != null
            ) {

                FileInputStream(span.file!!).use {
                    it.read(bytes)
                }

                ext = when {

                    bytes[0] == 0x1A.toByte() &&
                        bytes[1] == 0x45.toByte() &&
                        bytes[2] == 0xDF.toByte() &&
                        bytes[3] == 0xA3.toByte() -> ".webm"

                    bytes[4] == 0x66.toByte() &&
                        bytes[5] == 0x74.toByte() &&
                        bytes[6] == 0x79.toByte() &&
                        bytes[7] == 0x70.toByte() -> ".m4a"

                    bytes[0] == 0x4F.toByte() &&
                        bytes[1] == 0x67.toByte() &&
                        bytes[2] == 0x67.toByte() &&
                        bytes[3] == 0x53.toByte() -> ".ogg"

                    bytes[0] == 0x49.toByte() &&
                        bytes[1] == 0x44.toByte() -> ".mp3"

                    else -> ".webm"
                }
            }

            cache.releaseHoleSpan(span)

            ext

        } catch (_: Exception) {

            ".webm"
        }
    }

    private fun getExportDir(): File {

        val path =
            DataPreferences.cacheExportPath

        return if (path.isNotBlank()) {

            File(path)

        } else {

            File(
                Environment
                    .getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC
                    ),
                "ViTune"
            )
        }
    }

    private fun sanitize(
        name: String
    ): String {

        return name
            .replace(
                Regex("""[\\/*?:"<>|]"""),
                "_"
            )
            .trim()
            .take(100)
    }
}
