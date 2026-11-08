package de.davscha.denkwerk.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.davscha.denkwerk.data.WebImage
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Eine Seite, auf der das gesuchte Bild vorkommt */
data class ImageSite(val title: String, val url: String, val domain: String, val imageUrl: String, val size: String)

/** Ergebnis einer Rückwärtssuche */
data class ReverseResult(val sites: List<ImageSite>, val similar: List<WebImage>, val resultPage: String)

/** Ein Treffer bei SauceNAO (Anime, Zeichnungen) */
data class SauceHit(val similarity: Double, val title: String, val creator: String, val urls: List<String>, val thumb: String, val index: String, val extra: String)

/**
 * Bild-Rückwärtssuche:
 * - Yandex (findet Seiten mit dem Bild, andere Größen und ähnliche Bilder; keine offizielle Schnittstelle)
 * - SauceNAO (Anime/Illustrationen, braucht einen kostenlosen Schlüssel)
 */
object ReverseImageSearch {

    private const val UA = Toolbox.USER_AGENT

    // ---------------------------------------------------------------- Yandex

    /**
     * [image] ist entweder eine Datei auf dem Gerät oder eine Web-Adresse.
     * Versucht yandex.com und yandex.ru; der Fehler sagt genau, woran es lag.
     */
    fun yandex(image: String): ReverseResult {
        val errors = mutableListOf<String>()
        for (host in listOf("yandex.com", "yandex.ru")) {
            // Neuer Weg (so lädt die Yandex-Webseite heute hoch), danach der alte
            if (!image.startsWith("http")) {
                try {
                    return yandexResults(host, yandexApphostUpload(host, File(image)))
                } catch (e: Exception) {
                    errors += "$host (neu): ${e.message}"
                }
            }
            try {
                return yandexOn(host, image)
            } catch (e: Exception) {
                errors += "$host: ${e.message}"
            }
        }
        throw IOException(errors.joinToString(" | ").ifBlank { "Yandex nicht erreichbar" })
    }

    /**
     * Hochladen wie die heutige Yandex-Webseite: Bild roh an images-apphost schicken,
     * zurück kommen image_id/image_shard/url → daraus die Ergebnis-Adresse bauen.
     */
    private fun yandexApphostUpload(host: String, file: File): String {
        val bytes = uploadBytes(file)
        val c = URL("https://$host/images-apphost/image-download?cbird=111&images_avatars_size=preview&images_avatars_namespace=images-cbir")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 40_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.setRequestProperty("Accept", "application/json, */*")
        c.setRequestProperty("Referer", "https://$host/images/")
        c.setRequestProperty("Origin", "https://$host")
        val body = try {
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode !in 200..299) throw IOException("Fehler ${c.responseCode}")
            c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
        if (body.contains("captcha", ignoreCase = true) && !body.trimStart().startsWith("{")) throw IOException("verlangt Captcha")
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw IOException("unerwartete Antwort: " + body.take(80).replace(Regex("\\s+"), " "))
        val id = o.optString("image_id")
        val shard = o.opt("image_shard")?.toString().orEmpty()
        val url = o.optString("url")
        if (id.isBlank()) throw IOException("Antwort ohne image_id (Felder: " + o.keys().asSequence().take(8).joinToString(",") + ")")
        return "https://$host/images/search?rpt=imageview&cbir_id=" + URLEncoder.encode("$shard/$id", "UTF-8") +
            (if (url.isNotBlank()) "&url=" + URLEncoder.encode(url, "UTF-8") else "")
    }

    private fun yandexOn(host: String, image: String): ReverseResult {
        val resultUrl = if (image.startsWith("http")) {
            "https://$host/images/search?rpt=imageview&url=" + URLEncoder.encode(image, "UTF-8")
        } else {
            // Bild hochladen – die Antwort enthält die Adresse der Ergebnisseite
            val request = URLEncoder.encode("""{"blocks":[{"block":"b-page_type_search-by-image__link"}]}""", "UTF-8")
            val json = multipart(
                "https://$host/images/search?rpt=imageview&format=json&request=$request",
                field = "upfile",
                fileName = "bild.jpg",
                bytes = uploadBytes(File(image)),
                referer = "https://$host/images/",
            )
            if (json.contains("captcha", ignoreCase = true) && !json.trimStart().startsWith("{")) {
                throw IOException("$host verlangt eine Captcha-Bestätigung (Schutz gegen Apps)")
            }
            val obj = runCatching { JSONObject(json) }.getOrNull()
                ?: throw IOException("$host antwortet nicht im erwarteten Format: " + json.take(80).replace(Regex("\\s+"), " "))
            val params = obj.optJSONArray("blocks")?.optJSONObject(0)?.optJSONObject("params")?.optString("url")
            if (params.isNullOrBlank()) {
                throw IOException("Bild nicht angenommen (Antwort-Felder: " + obj.keys().asSequence().take(8).joinToString(",") + ")")
            }
            "https://$host/images/search?$params"
        }
        return yandexResults(host, resultUrl)
    }

    /** Ergebnisseite laden und auswerten */
    private fun yandexResults(host: String, resultUrl: String): ReverseResult {
        val html = get(resultUrl)
        if (html.contains("showcaptcha") || html.contains("SmartCaptcha")) {
            throw IOException("$host verlangt eine Captcha-Bestätigung (Schutz gegen Apps)")
        }
        val doc = Jsoup.parse(html, resultUrl)
        val sites = linkedMapOf<String, ImageSite>()
        val similar = linkedMapOf<String, WebImage>()
        // Die Ergebnisse stecken als JSON in data-state-Attributen – allgemein durchsuchen, damit Umbauten weniger stören
        for (el in doc.select("[data-state]")) {
            val state = runCatching { JSONObject(el.attr("data-state")) }.getOrNull() ?: continue
            walk(state) { o ->
                val url = o.optString("url")
                val domain = o.optString("domain")
                val title = o.optString("title")
                if (url.startsWith("http") && domain.isNotBlank() && title.isNotBlank()) {
                    val orig = o.optJSONObject("originalImage")
                    val img = fixUrl(orig?.optString("url").orEmpty())
                    val size = if (orig != null && orig.optInt("width") > 0) "${orig.optInt("width")}×${orig.optInt("height")}" else ""
                    sites.putIfAbsent(url, ImageSite(title.take(140), url, domain, img, size))
                }
                val imageUrl = fixUrl(o.optString("imageUrl").ifBlank { o.optString("img_href") })
                if (imageUrl.startsWith("http")) {
                    val thumb = fixUrl(o.optString("thumbUrl").ifBlank { o.optJSONObject("thumb")?.optString("url").orEmpty() })
                    val page = fixUrl(o.optString("linkUrl").ifBlank { o.optString("pageUrl") })
                    similar.putIfAbsent(imageUrl, WebImage(imageUrl, thumb = thumb, title = o.optString("title").take(120), source = page))
                }
            }
        }
        return ReverseResult(sites.values.toList(), similar.values.toList(), resultUrl)
    }

    /** Läuft durch alle Objekte eines JSON-Baums */
    private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                node.keys().forEach { k -> walk(node.opt(k), visit) }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }

    private fun fixUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        else -> u
    }

    // ---------------------------------------------------------------- SauceNAO

    fun sauceNao(image: String, apiKey: String): List<SauceHit> {
        val base = "https://saucenao.com/search.php?output_type=2&numres=8&db=999&api_key=" + URLEncoder.encode(apiKey, "UTF-8")
        val json = if (image.startsWith("http")) get(base + "&url=" + URLEncoder.encode(image, "UTF-8"))
        else multipart(base, field = "file", fileName = "bild.jpg", bytes = uploadBytes(File(image)))
        val root = JSONObject(json)
        val header = root.optJSONObject("header")
        if (header != null && header.optInt("status", 0) < 0) throw IOException(header.optString("message").ifBlank { "SauceNAO-Fehler" })
        val arr = root.optJSONArray("results") ?: JSONArray()
        return List(arr.length()) { i ->
            val r = arr.getJSONObject(i)
            val h = r.optJSONObject("header") ?: JSONObject()
            val d = r.optJSONObject("data") ?: JSONObject()
            val urls = d.optJSONArray("ext_urls")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
            val creator = listOf("creator", "member_name", "author_name", "artist").map { k ->
                when (val v = d.opt(k)) {
                    is JSONArray -> List(v.length()) { v.optString(it) }.joinToString(", ")
                    is String -> v
                    else -> ""
                }
            }.firstOrNull { it.isNotBlank() }.orEmpty()
            val extra = listOfNotNull(
                d.optString("material").takeIf { it.isNotBlank() }?.let { "Werk: $it" },
                d.optString("characters").takeIf { it.isNotBlank() }?.let { "Figuren: $it" },
                d.optString("source").takeIf { it.isNotBlank() }?.let { "Quelle: $it" },
            ).joinToString(" · ")
            SauceHit(
                similarity = h.optString("similarity").toDoubleOrNull() ?: 0.0,
                title = d.optString("title").ifBlank { d.optString("eng_name").ifBlank { d.optString("source") } },
                creator = creator,
                urls = urls,
                thumb = h.optString("thumbnail"),
                index = h.optString("index_name").substringAfter(": ").substringBefore(" - "),
                extra = extra,
            )
        }.sortedByDescending { it.similarity }
    }

    // ---------------------------------------------------------------- Hilfen

    /** Große Bilder vor dem Hochladen verkleinern (max. 1600 px, JPEG) */
    private fun uploadBytes(f: File): ByteArray {
        if (!f.exists()) throw IOException("Bild nicht gefunden")
        if (f.length() < 2_500_000 && f.extension.lowercase() in setOf("jpg", "jpeg")) return f.readBytes()
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: throw IOException("Bild konnte nicht gelesen werden")
        val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
        try {
            if (c.responseCode !in 200..299) throw IOException("Fehler ${c.responseCode} von ${URL(url).host}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun multipart(url: String, field: String, fileName: String, bytes: ByteArray, referer: String? = null): String {
        val boundary = "----Denkwerk" + System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 40_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
        c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
        if (referer != null) c.setRequestProperty("Referer", referer)
        try {
            c.outputStream.use { out ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$fileName\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                out.write(bytes)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            if (c.responseCode !in 200..299) throw IOException("Fehler ${c.responseCode} von ${URL(url).host}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}
