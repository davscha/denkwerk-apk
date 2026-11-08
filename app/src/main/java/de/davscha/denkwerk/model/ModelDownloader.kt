package de.davscha.denkwerk.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lädt eine Modelldatei herunter. Bricht der Download ab, geht es beim nächsten
 * Mal an derselben Stelle weiter (die halbe Datei liegt als ".part" daneben).
 */
object ModelDownloader {

    suspend fun download(
        context: Context,
        model: ModelInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val part = model.partFile(context)
        val target = model.file(context)
        var url = URL(model.url)
        var redirects = 0

        while (true) {
            val already = if (part.exists()) part.length() else 0L
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Denkwerk-Android")
                if (already > 0) setRequestProperty("Range", "bytes=$already-")
            }
            val code = conn.responseCode
            // Hugging Face leitet auf einen Speicher-Server um; dabei bleibt der Range-Kopf erhalten
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("Umleitung ohne Ziel")
                conn.disconnect()
                if (++redirects > 10) throw IOException("Zu viele Umleitungen")
                url = URL(url, location)
                continue
            }
            // 416 = die halbe Datei ist in Wahrheit schon ganz
            if (code == 416 && already == model.sizeBytes) {
                conn.disconnect()
                if (!part.renameTo(target)) throw IOException("Datei konnte nicht gespeichert werden")
                return@withContext
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !append) {
                conn.disconnect()
                throw IOException("Server antwortet mit Fehler $code")
            }

            // Gesamtgröße laut Server (bei Fortsetzen steht sie hinter dem "/" in Content-Range)
            val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: conn.contentLengthLong.takeIf { it > 0 && !append }
                ?: model.sizeBytes
            var downloaded = if (append) already else 0L
            val buffer = ByteArray(1 shl 16)
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        if (downloaded - lastReport > 4_000_000) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            conn.disconnect()
            onProgress(downloaded, total)

            if (part.length() != total) {
                throw IOException("Datei unvollständig (${part.length()} von $total Bytes)")
            }
            if (!part.renameTo(target)) throw IOException("Datei konnte nicht gespeichert werden")
            return@withContext
        }
    }
}
