package de.davscha.denkwerk.engine

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Eine geplante Datei-Speicherung, die du erst bestätigen musst. */
data class DownloadRequest(val urls: List<String>)

/**
 * Die Werkzeuge, die ein Modell mit Tool-Unterstützung benutzen darf.
 * Alles, was nur liest, läuft sofort; Herunterladen fragt vorher nach ([confirmDownload]).
 */
class Toolbox(
    /** Ordner für Downloads (Denkwerk/Downloads) */
    private val downloadDir: () -> File,
    /** Adresse einer eigenen SearXNG-Suche, leer = DuckDuckGo */
    private val searxngUrl: () -> String,
    /** Wie viele Zeichen einer Webseite höchstens ans Modell gehen */
    private val pageBudget: () -> Int,
    /** Fragt dich, ob gespeichert werden darf (true = ja) */
    private val confirmDownload: suspend (DownloadRequest) -> Boolean,
) {

    /** Die Werkzeugliste im Format, das Ollama erwartet. */
    fun specs(): JSONArray = JSONArray().apply {
        put(spec("uhrzeit", "Gibt das aktuelle Datum, den Wochentag und die Uhrzeit (Zeitzone des Nutzers) zurück.", JSONObject()))
        put(
            spec(
                "rechnen",
                "Rechnet einen mathematischen Ausdruck exakt aus, z. B. \"(17.5*3)^2 / sqrt(2)\". " +
                    "Kann + - * / ^ %, Klammern, sqrt, sin, cos, tan, log, ln, abs, round, pi, e.",
                props("ausdruck" to "Der Ausdruck, z. B. 12*(3+4)"),
                required = listOf("ausdruck"),
            )
        )
        put(
            spec(
                "web_suche",
                "Sucht im Internet und liefert Titel, Adresse und Kurztext der besten Treffer. " +
                    "Benutzen für aktuelle Informationen, Nachrichten, Preise oder alles nach deinem Wissensstand.",
                props("anfrage" to "Suchbegriffe, kurz und genau"),
                required = listOf("anfrage"),
            )
        )
        put(
            spec(
                "seite_lesen",
                "Lädt eine Webseite und gibt ihren lesbaren Text (ohne Menüs und Werbung) zurück. " +
                    "Benutzen, wenn der Nutzer eine Adresse nennt oder ein Suchtreffer genauer gelesen werden soll.",
                props("url" to "Vollständige Adresse mit https://"),
                required = listOf("url"),
            )
        )
        put(
            spec(
                "bilder_suchen",
                "Sucht Bilder im Internet (Bildersuche). Die gefundenen Bilder werden dem Nutzer automatisch als Galerie " +
                    "mit Slideshow angezeigt. Benutzen, wenn der Nutzer Bilder, Fotos oder Beispiele sehen möchte.",
                JSONObject()
                    .put("anfrage", JSONObject().put("type", "string").put("description", "Suchbegriffe, z. B. \"rote Katze Kätzchen\""))
                    .put("anzahl", JSONObject().put("type", "integer").put("description", "Wie viele Bilder (1 bis 12, Standard 6)")),
                required = listOf("anfrage"),
            )
        )
        put(
            spec(
                "bilder_auf_seite",
                "Listet die Bilder einer Webseite mit Adresse und Beschreibung auf (ohne kleine Symbole und Logos).",
                props("url" to "Vollständige Adresse mit https://"),
                required = listOf("url"),
            )
        )
        put(
            spec(
                "herunterladen",
                "Speichert Dateien (Bilder, PDFs, Texte, Audio, Video) auf dem Gerät des Nutzers im Ordner Denkwerk/Downloads. " +
                    "Der Nutzer muss vorher zustimmen. Bilder werden danach im Chat angezeigt.",
                JSONObject().put(
                    "urls",
                    JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                        .put("description", "Liste der Datei-Adressen (höchstens 10)"),
                ),
                required = listOf("urls"),
            )
        )
    }

    /** Führt ein Werkzeug aus (läuft im Hintergrund-Thread). */
    suspend fun run(name: String, args: JSONObject): ToolOutput = when (name) {
        "uhrzeit" -> now()
        "rechnen" -> calc(args.optString("ausdruck"))
        "web_suche" -> search(args.optString("anfrage"))
        "seite_lesen" -> readPage(args.optString("url"))
        "bilder_auf_seite" -> imagesOnPage(args.optString("url"))
        "bilder_suchen" -> searchImages(args.optString("anfrage"), args.optInt("anzahl", 6).coerceIn(1, 12))
        "herunterladen" -> download(args)
        else -> ToolOutput("Unbekanntes Werkzeug: $name", "unbekanntes Werkzeug", ok = false)
    }

    // ---------------------------------------------------------------------

    private fun now(): ToolOutput {
        val f = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm 'Uhr' (zzzz)", Locale.GERMANY)
        f.timeZone = TimeZone.getDefault()
        val s = f.format(Date())
        return ToolOutput(s, s)
    }

    private fun calc(expr: String): ToolOutput {
        val v = Calculator.eval(expr)
        val shown = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.10g".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
        return ToolOutput("$expr = $shown", "$expr = $shown")
    }

    private fun search(query: String): ToolOutput {
        if (query.isBlank()) return ToolOutput("Leere Suchanfrage", "leere Anfrage", ok = false)
        val results = if (searxngUrl().isNotBlank()) searchSearx(query) else searchDuckDuckGo(query)
        if (results.isEmpty()) return ToolOutput("Keine Treffer für „$query“.", "„$query“ · keine Treffer")
        val text = results.mapIndexed { i, r -> "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}" }.joinToString("\n")
        return ToolOutput("Suchergebnisse für „$query“:\n$text", "„$query“ · ${results.size} Treffer")
    }

    private data class Hit(val title: String, val url: String, val snippet: String)

    /** DuckDuckGo-Webseite (ohne Schlüssel). Kann gelegentlich blocken. */
    private fun searchDuckDuckGo(query: String): List<Hit> {
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8") + "&kl=de-de")
            .userAgent(USER_AGENT)
            .timeout(15_000)
            .get()
        return doc.select("div.result").mapNotNull { r ->
            val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
            var href = a.attr("href")
            // Links laufen über eine Weiterleitung: //duckduckgo.com/l/?uddg=<echte Adresse>
            Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            if (href.contains("duckduckgo.com/y.js")) return@mapNotNull null // Werbung
            Hit(a.text(), href, r.selectFirst(".result__snippet")?.text().orEmpty())
        }.take(8)
    }

    /** Eigene SearXNG-Suche (z. B. im Docker auf dem PC; Format json muss erlaubt sein). */
    private fun searchSearx(query: String): List<Hit> {
        val base = searxngUrl().trim().trimEnd('/')
        val conn = URL("$base/search?format=json&language=de&q=" + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode !in 200..299) throw IOException("SearXNG antwortet mit Fehler ${conn.responseCode}")
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("results") ?: JSONArray()
            return List(minOf(arr.length(), 8)) { i ->
                val o = arr.getJSONObject(i)
                Hit(o.optString("title"), o.optString("url"), o.optString("content"))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetch(url: String): Document {
        val clean = normalizeUrl(url)
        return Jsoup.connect(clean)
            .userAgent(USER_AGENT)
            .timeout(20_000)
            .maxBodySize(6 shl 20)
            .followRedirects(true)
            .get()
    }

    private fun readPage(url: String): ToolOutput {
        val doc = fetch(url)
        val title = doc.title().ifBlank { url }
        doc.select("script, style, noscript, iframe, svg, nav, footer, header, aside, form, button, [role=navigation], .cookie, .ads, .advert").remove()
        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        // Absätze, Überschriften, Listen und Code als Zeilen
        val lines = root.select("h1, h2, h3, h4, p, li, pre, blockquote, td").mapNotNull { e ->
            val t = e.text().trim()
            when {
                t.length < 2 -> null
                e.tagName().matches(Regex("h[1-4]")) -> "\n## $t"
                e.tagName() == "li" -> "- $t"
                else -> t
            }
        }.distinct()
        var text = lines.joinToString("\n").ifBlank { root.text() }
        val budget = pageBudget()
        val cut = text.length > budget
        if (cut) text = text.take(budget) + "\n[… gekürzt, ${text.length} Zeichen insgesamt]"
        return ToolOutput("Titel: $title\nAdresse: ${doc.location()}\n\n$text", title + (if (cut) " · gekürzt" else ""))
    }

    private fun imagesOnPage(url: String): ToolOutput {
        val doc = fetch(url)
        val found = linkedMapOf<String, String>()
        // Vorschaubild der Seite zuerst
        doc.selectFirst("meta[property=og:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() }?.let { found[it] = "Vorschaubild der Seite" }
        for (img in doc.select("img")) {
            val src = listOf("abs:src", "abs:data-src", "abs:data-original").map { img.attr(it) }.firstOrNull { it.startsWith("http") }
                ?: img.attr("abs:srcset").split(",").lastOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.startsWith("http") }
                ?: continue
            val lower = src.lowercase()
            if (lower.endsWith(".svg") || lower.contains("logo") || lower.contains("icon") || lower.contains("sprite") || lower.contains("pixel")) continue
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            if ((w != null && w < 120) || (h != null && h < 120)) continue
            found.putIfAbsent(src, img.attr("alt").ifBlank { img.attr("title") }.take(120))
        }
        if (found.isEmpty()) return ToolOutput("Keine passenden Bilder auf ${doc.location()} gefunden.", "keine Bilder gefunden")
        val list = found.entries.take(25).mapIndexed { i, (src, alt) -> "${i + 1}. $src" + (if (alt.isNotBlank()) " – $alt" else "") }
        val gallery = found.entries.take(12).map { (src, alt) -> de.davscha.denkwerk.data.WebImage(src, title = alt, source = doc.location()) }
        return ToolOutput(
            "Bilder auf ${doc.location()} (die ersten ${gallery.size} werden dem Nutzer bereits als Galerie angezeigt):\n" + list.joinToString("\n"),
            "${list.size} Bilder gefunden",
            webImages = gallery,
        )
    }

    // ---------- Bildersuche ----------

    private fun searchImages(query: String, count: Int): ToolOutput {
        if (query.isBlank()) return ToolOutput("Leere Suchanfrage", "leere Anfrage", ok = false)
        val found = (if (searxngUrl().isNotBlank()) searchImagesSearx(query) else searchImagesDdg(query)).take(count)
        if (found.isEmpty()) return ToolOutput("Keine Bilder für „$query“ gefunden.", "„$query“ · keine Bilder")
        val list = found.mapIndexed { i, w -> "${i + 1}. ${w.title} – ${w.url} (Seite: ${w.source})" }.joinToString("\n")
        return ToolOutput(
            "Bilder für „$query“ – sie werden dem Nutzer bereits als Galerie mit Slideshow angezeigt. " +
                "Beschreibe kurz, was zu sehen ist, und liste die Bilder NICHT noch einmal als Links auf:\n$list",
            "„$query“ · ${found.size} Bilder",
            webImages = found,
        )
    }

    /** DuckDuckGo-Bildersuche: erst ein Schlüssel (vqd) von der Suchseite, dann die Ergebnisse als JSON. */
    private fun searchImagesDdg(query: String): List<de.davscha.denkwerk.data.WebImage> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = httpGet("https://duckduckgo.com/?q=$q&iax=images&ia=images", referer = null)
        val vqd = Regex("vqd=[\"']?([\\d-]+)").find(html)?.groupValues?.get(1)
            ?: throw IOException("Die DuckDuckGo-Bildersuche antwortet gerade nicht")
        val json = httpGet("https://duckduckgo.com/i.js?l=de-de&o=json&q=$q&vqd=$vqd&f=,,,,,&p=1", referer = "https://duckduckgo.com/")
        val arr = JSONObject(json).optJSONArray("results") ?: JSONArray()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            de.davscha.denkwerk.data.WebImage(
                url = o.optString("image"),
                thumb = o.optString("thumbnail"),
                title = o.optString("title"),
                source = o.optString("url"),
            )
        }.filter { it.url.startsWith("http") }
    }

    /** SearXNG-Bildersuche (categories=images) */
    private fun searchImagesSearx(query: String): List<de.davscha.denkwerk.data.WebImage> {
        val base = searxngUrl().trim().trimEnd('/')
        val json = httpGet("$base/search?format=json&categories=images&q=" + URLEncoder.encode(query, "UTF-8"), referer = null)
        val arr = JSONObject(json).optJSONArray("results") ?: JSONArray()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            de.davscha.denkwerk.data.WebImage(
                url = o.optString("img_src"),
                thumb = o.optString("thumbnail_src"),
                title = o.optString("title"),
                source = o.optString("url"),
            )
        }.filter { it.url.startsWith("http") }
    }

    private fun httpGet(url: String, referer: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        try {
            if (conn.responseCode !in 200..299) throw IOException("Fehler ${conn.responseCode} von ${URL(url).host}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Ein Bild direkt speichern (z. B. aus der Slideshow – du hast selbst getippt, keine Rückfrage nötig). */
    fun saveUrl(url: String): File = downloadOne(url)

    private suspend fun download(args: JSONObject): ToolOutput {
        val arr = args.optJSONArray("urls")
        val urls = (if (arr != null) List(arr.length()) { arr.optString(it) } else listOf(args.optString("url")))
            .map { it.trim() }.filter { it.startsWith("http") }.distinct().take(10)
        if (urls.isEmpty()) return ToolOutput("Keine gültigen Adressen angegeben.", "keine Adressen", ok = false)
        if (!confirmDownload(DownloadRequest(urls))) {
            return ToolOutput("Der Nutzer hat das Herunterladen abgelehnt.", "abgelehnt", ok = false)
        }
        val saved = mutableListOf<File>()
        val errors = mutableListOf<String>()
        for (u in urls) {
            runCatching { saved += downloadOne(u) }.onFailure { errors += "$u: ${it.message}" }
        }
        val images = saved.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") }.map { it.absolutePath }
        val forModel = buildString {
            if (saved.isNotEmpty()) append("Gespeichert in Denkwerk/Downloads: " + saved.joinToString { it.name })
            if (errors.isNotEmpty()) append("\nFehler: " + errors.joinToString("; "))
        }
        val summary = "${saved.size} von ${urls.size} gespeichert" + (if (errors.isNotEmpty()) " · ${errors.size} Fehler" else "")
        return ToolOutput(forModel, summary, ok = saved.isNotEmpty(), images = images)
    }

    private fun downloadOne(url: String): File {
        val conn = URL(normalizeUrl(url)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode !in 200..299) throw IOException("Fehler ${conn.responseCode}")
            val type = conn.contentType.orEmpty().substringBefore(';').lowercase()
            val ext = extensionFor(type, url) ?: throw IOException("Dateityp „$type“ wird nicht gespeichert")
            val length = conn.contentLengthLong
            if (length > MAX_DOWNLOAD) throw IOException("zu groß (${length shr 20} MB)")
            val base = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "datei" }
            val dir = downloadDir().apply { mkdirs() }
            var f = File(dir, "$base.$ext")
            var n = 2
            while (f.exists()) f = File(dir, "$base-${n++}.$ext")
            conn.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        total += r
                        if (total > MAX_DOWNLOAD) {
                            out.close()
                            f.delete()
                            throw IOException("zu groß (über ${MAX_DOWNLOAD shr 20} MB)")
                        }
                        out.write(buf, 0, r)
                    }
                }
            }
            return f
        } finally {
            conn.disconnect()
        }
    }

    /** Nur harmlose Dateitypen – keine Programme oder APKs. */
    private fun extensionFor(type: String, url: String): String? {
        val byType = mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/bmp" to "bmp",
            "application/pdf" to "pdf", "text/plain" to "txt", "text/markdown" to "md", "text/csv" to "csv",
            "application/json" to "json", "audio/mpeg" to "mp3", "audio/ogg" to "ogg", "audio/wav" to "wav",
            "video/mp4" to "mp4", "video/webm" to "webm",
        )
        byType[type]?.let { return it }
        // Manche Server melden "application/octet-stream" – dann nach der Endung gehen
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in byType.values }
    }

    private fun normalizeUrl(url: String): String {
        val u = url.trim()
        require(u.isNotEmpty()) { "Keine Adresse angegeben" }
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    private fun spec(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()) =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)),
                    ),
            )

    private fun props(vararg p: Pair<String, String>) = JSONObject().apply {
        p.forEach { (k, d) -> put(k, JSONObject().put("type", "string").put("description", d)) }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        const val MAX_DOWNLOAD = 25L shl 20

        /** Hübsche Namen und Symbole fürs Kärtchen im Chat */
        fun label(name: String): Pair<String, String> = when (name) {
            "uhrzeit" -> "🕒" to "Uhrzeit"
            "rechnen" -> "🧮" to "Rechner"
            "web_suche" -> "🔍" to "Websuche"
            "seite_lesen" -> "📄" to "Seite gelesen"
            "bilder_auf_seite" -> "🖼️" to "Bilder auf Seite"
            "bilder_suchen" -> "🖼️" to "Bildersuche"
            "herunterladen" -> "⬇️" to "Herunterladen"
            else -> "🔧" to name
        }
    }
}

/** Kleiner Rechner: + - * / ^ %, Klammern, Funktionen und Konstanten. */
object Calculator {
    fun eval(input: String): Double {
        val p = Parser(input.replace(',', '.').replace('×', '*').replace('÷', '/').replace(" ", ""))
        val v = p.expr()
        if (p.pos < p.s.length) error("Unerwartetes Zeichen „${p.s[p.pos]}“")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        private fun peek() = if (pos < s.length) s[pos] else '\u0000'
        private fun eat(c: Char): Boolean = if (peek() == c) { pos++; true } else false

        fun expr(): Double {
            var v = term()
            var going = true
            while (going) {
                when {
                    eat('+') -> v += term()
                    eat('-') -> v -= term()
                    else -> going = false
                }
            }
            return v
        }

        private fun term(): Double {
            var v = power()
            var going = true
            while (going) {
                when {
                    eat('*') -> v *= power()
                    eat('/') -> v /= power()
                    eat('%') -> v %= power()
                    else -> going = false
                }
            }
            return v
        }

        private fun power(): Double {
            val base = unary()
            return if (eat('^')) Math.pow(base, power()) else base
        }

        private fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> atom()
        }

        private fun atom(): Double {
            if (eat('(')) {
                val v = expr()
                if (!eat(')')) error("Klammer fehlt")
                return v
            }
            val start = pos
            if (peek().isDigit() || peek() == '.') {
                while (peek().isDigit() || peek() == '.') pos++
                if (peek() == 'e' || peek() == 'E') {
                    val save = pos
                    pos++
                    if (peek() == '-' || peek() == '+') pos++
                    if (peek().isDigit()) {
                        while (peek().isDigit()) pos++
                    } else {
                        pos = save
                    }
                }
                return s.substring(start, pos).toDouble()
            }
            if (peek().isLetter()) {
                while (peek().isLetter() || peek().isDigit()) pos++
                val name = s.substring(start, pos).lowercase()
                when (name) {
                    "pi" -> return Math.PI
                    "e" -> return Math.E
                }
                if (!eat('(')) error("Klammer nach $name fehlt")
                val a = expr()
                if (!eat(')')) error("Klammer fehlt")
                return when (name) {
                    "sqrt", "wurzel" -> Math.sqrt(a)
                    "sin" -> Math.sin(a)
                    "cos" -> Math.cos(a)
                    "tan" -> Math.tan(a)
                    "log" -> Math.log10(a)
                    "ln" -> Math.log(a)
                    "abs" -> Math.abs(a)
                    "round" -> Math.rint(a)
                    "floor" -> Math.floor(a)
                    "ceil" -> Math.ceil(a)
                    "exp" -> Math.exp(a)
                    else -> error("Unbekannte Funktion $name")
                }
            }
            error(if (pos >= s.length) "Ausdruck unvollständig" else "Unerwartetes Zeichen „${peek()}“")
        }
    }
}
