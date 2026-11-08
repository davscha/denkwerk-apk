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
    /** Abgesicherte Suche: 0 = Streng, 1 = Moderat, 2 = Aus */
    private val safeSearch: () -> Int = { 0 },
    /** Wie viele Zeichen einer Webseite höchstens ans Modell gehen */
    private val pageBudget: () -> Int,
    /** Fragt dich, ob gespeichert werden darf (true = ja) */
    private val confirmDownload: suspend (DownloadRequest) -> Boolean,
    /** Gedächtnis: Fakt merken (gibt null zurück, wenn ausgeschaltet) */
    private val remember: suspend (String) -> String? = { null },
    /** Gedächtnis: passende Fakten löschen, gibt die Anzahl zurück */
    private val forget: suspend (String) -> Int = { 0 },
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
                "videos_suchen",
                "Sucht Videos im Internet (vor allem YouTube). Die Videos werden dem Nutzer automatisch als Kärtchen mit Vorschaubild angezeigt.",
                JSONObject()
                    .put("anfrage", JSONObject().put("type", "string").put("description", "Suchbegriffe"))
                    .put("anzahl", JSONObject().put("type", "integer").put("description", "Wie viele Videos (1 bis 10, Standard 5)"))
                    .put("dauer", JSONObject().put("type", "string").put("enum", JSONArray(listOf("egal", "kurz", "mittel", "lang")))
                        .put("description", "kurz = unter 4 Min., mittel = 4–20 Min., lang = über 20 Min."))
                    .put("neueste", JSONObject().put("type", "boolean").put("description", "true = nur aus dem letzten Monat, neueste zuerst")),
                required = listOf("anfrage"),
            )
        )
        put(
            spec(
                "video_verstehen",
                "Liest Titel, Beschreibung und Untertitel eines YouTube-Videos, damit du es zusammenfassen oder Fragen dazu beantworten kannst.",
                props("url" to "YouTube-Adresse des Videos"),
                required = listOf("url"),
            )
        )
        put(
            spec(
                "auf_seite_suchen",
                "Benutzt die eingebaute Suchfunktion (Suchfeld) einer Webseite und liefert die Treffer; die ersten Treffer werden auf Wunsch " +
                    "gleich gelesen. Benutzen, wenn der Nutzer auf einer bestimmten Webseite etwas suchen möchte (z. B. Shop, Forum, Wiki, Nachrichtenseite).",
                JSONObject()
                    .put("url", JSONObject().put("type", "string").put("description", "Adresse der Webseite mit https://"))
                    .put("suche", JSONObject().put("type", "string").put("description", "Suchbegriff, wie man ihn ins Suchfeld tippen würde"))
                    .put("treffer_lesen", JSONObject().put("type", "integer").put("description", "Wie viele der ersten Treffer gleich gelesen werden (0 bis 5, Standard 3)"))
                    .put("bilder", JSONObject().put("type", "boolean").put("description", "true = Bilder aus den Treffern sammeln")),
                required = listOf("url", "suche"),
            )
        )
        put(
            spec(
                "webseite_durchsuchen",
                "Durchsucht eine GANZE Webseite: folgt den Links zu Unterseiten derselben Seite (bis zu max_seiten) und sammelt " +
                    "Text-Ausschnitte zu einem Suchbegriff und auf Wunsch alle Bilder. Benutzen, wenn der Nutzer eine Adresse nennt und " +
                    "Infos oder Bilder von der ganzen Seite möchte, nicht nur von der Startseite. Bilder erscheinen automatisch als Galerie.",
                JSONObject()
                    .put("url", JSONObject().put("type", "string").put("description", "Startadresse mit https://"))
                    .put("suche", JSONObject().put("type", "string").put("description", "Wonach gesucht wird (leer = alles zusammenfassen)"))
                    .put("bilder", JSONObject().put("type", "boolean").put("description", "true = auch Bilder sammeln"))
                    .put("max_seiten", JSONObject().put("type", "integer").put("description", "Wie viele Unterseiten höchstens (Standard 15, max. 40)")),
                required = listOf("url"),
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
                "merken",
                "Merkt sich dauerhaft einen Fakt über den Nutzer (Name, Wohnort, Vorlieben, Projekte, Haustiere …) für alle künftigen Chats. " +
                    "Benutzen, wenn der Nutzer \"merk dir\" sagt oder etwas Dauerhaftes über sich erzählt. Kurz, in der dritten Person, z. B. \"Hat einen Kater namens Felix\".",
                props("fakt" to "Der Fakt in einem kurzen Satz"),
                required = listOf("fakt"),
            )
        )
        put(
            spec(
                "vergessen",
                "Löscht gemerkte Fakten über den Nutzer, die zu einem Stichwort passen. Benutzen, wenn der Nutzer \"vergiss …\" sagt.",
                props("fakt" to "Stichwort oder Fakt, der gelöscht werden soll"),
                required = listOf("fakt"),
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
        "videos_suchen" -> searchVideos(
            args.optString("anfrage"),
            args.optInt("anzahl", 5).coerceIn(1, 10),
            args.optString("dauer"),
            args.optBoolean("neueste", false) || args.optString("neueste") == "true",
        )
        "video_verstehen" -> videoTranscript(args.optString("url"))
        "auf_seite_suchen" -> siteSearch(
            args.optString("url"),
            args.optString("suche"),
            args.optInt("treffer_lesen", 3).coerceIn(0, 5),
            args.optBoolean("bilder", false) || args.optString("bilder") == "true",
        )
        "webseite_durchsuchen" -> crawlSite(
            args.optString("url"),
            args.optString("suche"),
            args.optBoolean("bilder", false) || args.optString("bilder") == "true",
            args.optInt("max_seiten", 15).coerceIn(2, 40),
        )
        "bilder_suchen" -> searchImages(args.optString("anfrage"), args.optInt("anzahl", 6).coerceIn(1, 12))
        "herunterladen" -> download(args)
        "merken" -> {
            val fakt = args.optString("fakt").trim()
            if (fakt.isEmpty()) ToolOutput("Kein Fakt angegeben.", "leer", ok = false)
            else remember(fakt)?.let { ToolOutput("Gemerkt: $it", it) }
                ?: ToolOutput("Das Gedächtnis ist ausgeschaltet.", "Gedächtnis aus", ok = false)
        }
        "vergessen" -> {
            val n = forget(args.optString("fakt"))
            ToolOutput(if (n > 0) "$n Fakt(en) gelöscht." else "Nichts Passendes gefunden.", if (n > 0) "$n gelöscht" else "nichts gefunden", ok = n > 0)
        }
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
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8") + "&kl=de-de&kp=" + ddgKp())
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
        val conn = URL("$base/search?format=json&language=de&safesearch=${searxSafe()}&q=" + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection
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
        var text = pageLines(doc).joinToString("\n")
        val budget = pageBudget()
        val cut = text.length > budget
        if (cut) text = text.take(budget) + "\n[… gekürzt, ${text.length} Zeichen insgesamt]"
        return ToolOutput("Titel: $title\nAdresse: ${doc.location()}\n\n$text", title + (if (cut) " · gekürzt" else ""))
    }

    /** Lesbarer Text einer Seite als Zeilen (Achtung: entfernt Menüs usw. aus [doc]). */
    private fun pageLines(doc: Document): List<String> {
        doc.select("script, style, noscript, iframe, svg, nav, footer, header, aside, form, button, [role=navigation], .cookie, .ads, .advert").remove()
        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body() ?: return emptyList()
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
        return lines.ifEmpty { listOf(root.text()) }
    }

    /** Bilder einer Seite: Adresse → Beschreibung (ohne Symbole, Logos, Mini-Bilder). */
    private fun pageImages(doc: Document): LinkedHashMap<String, String> {
        val found = linkedMapOf<String, String>()
        // Vorschaubild der Seite zuerst
        doc.selectFirst("meta[property=og:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() }?.let { found[it] = "Vorschaubild: ${doc.title()}".take(120) }
        for (img in doc.select("img")) {
            val src = listOf("abs:src", "abs:data-src", "abs:data-original", "abs:data-lazy-src").map { img.attr(it) }.firstOrNull { it.startsWith("http") }
                ?: img.attr("abs:srcset").split(",").lastOrNull()?.trim()?.substringBefore(" ")?.takeIf { it.startsWith("http") }
                ?: continue
            val lower = src.lowercase()
            if (lower.endsWith(".svg") || lower.contains("logo") || lower.contains("icon") || lower.contains("sprite") || lower.contains("pixel") || lower.contains("avatar")) continue
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            if ((w != null && w < 120) || (h != null && h < 120)) continue
            found.putIfAbsent(src, img.attr("alt").ifBlank { img.attr("title") }.take(120))
        }
        return found
    }

    private fun imagesOnPage(url: String): ToolOutput {
        val doc = fetch(url)
        val found = pageImages(doc)
        if (found.isEmpty()) return ToolOutput("Keine passenden Bilder auf ${doc.location()} gefunden.", "keine Bilder gefunden")
        val list = found.entries.take(25).mapIndexed { i, (src, alt) -> "${i + 1}. $src" + (if (alt.isNotBlank()) " – $alt" else "") }
        val gallery = found.entries.take(12).map { (src, alt) -> de.davscha.denkwerk.data.WebImage(src, title = alt, source = doc.location()) }
        return ToolOutput(
            "Bilder auf ${doc.location()} (die ersten ${gallery.size} werden dem Nutzer bereits als Galerie angezeigt):\n" + list.joinToString("\n"),
            "${list.size} Bilder gefunden",
            webImages = gallery,
        )
    }

    // ---------- Suchfunktion einer Webseite benutzen ----------

    /**
     * Benutzt die eingebaute Suche einer Webseite:
     * 1. OpenSearch-Beschreibung im Seitenkopf, 2. Suchformular im HTML, 3. bekannte Adress-Muster.
     * Liefert die Trefferliste und liest auf Wunsch die ersten Treffer gleich mit.
     */
    private fun siteSearch(url: String, query: String, readTop: Int, wantImages: Boolean): ToolOutput {
        if (query.isBlank()) return ToolOutput("Kein Suchbegriff angegeben.", "kein Suchbegriff", ok = false)
        val home = fetch(url)
        val origin = URL(home.location()).let { "${it.protocol}://${it.host}" }
        val host = URL(origin).host.removePrefix("www.")
        val terms = query.lowercase().split(Regex("[^a-zäöüß0-9]+")).filter { it.length >= 3 }
        fun relevance(t: String): Int { val l = t.lowercase(); return terms.count { l.contains(it) } }

        var results: Document? = null
        var method = ""

        // 1. OpenSearch: <link rel="search" type="application/opensearchdescription+xml" href="…">
        home.selectFirst("link[rel=search][type*=opensearch]")?.attr("abs:href")?.takeIf { it.isNotBlank() }?.let { osUrl ->
            runCatching {
                val xml = Jsoup.connect(osUrl).userAgent(USER_AGENT).timeout(10_000).ignoreContentType(true)
                    .parser(org.jsoup.parser.Parser.xmlParser()).get()
                val template = xml.select("Url, url").firstOrNull { it.attr("type").contains("html") }?.attr("template")
                if (!template.isNullOrBlank()) {
                    val searchUrl = template
                        .replace("{searchTerms}", URLEncoder.encode(query, "UTF-8"))
                        .replace(Regex("\\{[^}]*\\?}"), "") // optionale Platzhalter wie {startPage?}
                        .replace(Regex("\\{[^}]*}"), "")
                    results = fetch(searchUrl)
                    method = "OpenSearch"
                }
            }
        }

        // 2. Suchformular im HTML
        if (results == null) {
            val nameRx = Regex("^(q|s|query|search|suche|suchbegriff|keyword|keywords|term|searchterm|search_query|text|k|w)$", RegexOption.IGNORE_CASE)
            val best = home.select("form").map { f ->
                val field = f.selectFirst("input[type=search]")
                    ?: f.select("input[name]").firstOrNull { nameRx.matches(it.attr("name")) && it.attr("type").lowercase() in setOf("", "text", "search") }
                var score = 0
                if (field != null) score += 3
                if (field?.attr("type") == "search") score += 2
                if (Regex("search|such|find", RegexOption.IGNORE_CASE).containsMatchIn(f.attr("action") + f.attr("id") + f.attr("class") + f.attr("role"))) score += 2
                if (f.select("input[type=password]").isNotEmpty()) score = -10 // Login-Formular
                Triple(f, field, score)
            }.filter { it.second != null && it.third > 0 }.maxByOrNull { it.third }
            if (best != null) {
                val (form, field, _) = best
                runCatching {
                    val action = form.attr("abs:action").ifBlank { home.location() }
                    val data = linkedMapOf<String, String>()
                    form.select("input[type=hidden][name]").forEach { data[it.attr("name")] = it.attr("value") }
                    data[field!!.attr("name").ifBlank { "q" }] = query
                    val conn = Jsoup.connect(action).userAgent(USER_AGENT).timeout(20_000).followRedirects(true).data(data)
                    results = if (form.attr("method").equals("post", ignoreCase = true)) conn.post() else conn.get()
                    method = "Suchformular"
                }
            }
        }

        // 3. Bekannte Muster ausprobieren (WordPress, typische Shops/Foren)
        if (results == null) {
            val q = URLEncoder.encode(query, "UTF-8")
            for (pattern in listOf("/?s=$q", "/search?q=$q", "/suche?q=$q", "/search?query=$q", "/search/?q=$q", "/suche/?s=$q")) {
                val d = runCatching { fetch(origin + pattern) }.getOrNull() ?: continue
                val differs = canonical(d.location()) != canonical(home.location())
                if (differs && terms.any { d.text().lowercase().contains(it) }) {
                    results = d
                    method = "Adresse $pattern".substringBefore("=") + "="
                    break
                }
            }
        }

        val res = results
            ?: return ToolOutput(
                "Auf $host wurde keine nutzbare Suchfunktion gefunden (vielleicht lädt die Suche per JavaScript). " +
                    "Versuche stattdessen webseite_durchsuchen.",
                "keine Suchfunktion gefunden",
                ok = false,
            )

        // Treffer: Links auf derselben Webseite, passende zuerst (Menüs/Kopf/Fuß vorher entfernen)
        val resultImages = if (wantImages) pageImages(res) else linkedMapOf()
        val content = res.clone().apply { select("nav, header, footer, aside, [role=navigation], form").remove() }
        val links = linkedMapOf<String, String>()
        for (a in content.select("a[href]")) {
            val link = a.attr("abs:href").substringBefore('#')
            val text = a.text().trim()
            if (!link.startsWith("http") || text.length < 6) continue
            val linkHost = runCatching { URL(link).host.removePrefix("www.") }.getOrNull() ?: continue
            if (linkHost != host || canonical(link) == canonical(res.location())) continue
            if (Regex("login|anmelden|warenkorb|cart|account|javascript:|mailto:|[?&](page|p|start|offset)=").containsMatchIn(link.lowercase())) continue
            links.putIfAbsent(link, text.take(140))
        }
        val ranked = links.entries.sortedByDescending { (l, t) -> relevance("$t $l") }.take(12)
        if (ranked.isEmpty()) {
            val text = pageLines(res).joinToString("\n").take(pageBudget())
            return ToolOutput("Suchergebnis auf $host ($method) für „$query“:\n$text", "$method · Ergebnisseite gelesen")
        }

        // Die ersten Treffer gleich lesen
        val budget = pageBudget()
        val read = StringBuilder()
        val images = linkedMapOf<String, Pair<String, String>>()
        resultImages.forEach { (src, alt) -> images.putIfAbsent(src, alt to res.location()) }
        ranked.take(readTop).forEach { (link, title) ->
            val d = runCatching { fetch(link) }.getOrNull() ?: return@forEach
            if (wantImages) pageImages(d).forEach { (src, alt) -> images.putIfAbsent(src, alt to d.location()) }
            val lines = pageLines(d)
            val hits = lines.filter { relevance(it) > 0 }.ifEmpty { lines.take(8) }
            read.append("### ").append(title).append('\n').append(link).append('\n')
            read.append(hits.joinToString("\n").take((budget / readTop.coerceAtLeast(1)).coerceIn(500, 4000))).append("\n\n")
        }
        val gallery = images.entries.sortedByDescending { (src, v) -> relevance(v.first + " " + src) }.take(24)
            .map { (src, v) -> de.davscha.denkwerk.data.WebImage(src, title = v.first, source = v.second) }

        val forModel = buildString {
            append("Suche auf $host über $method nach „$query“ – ${ranked.size} Treffer:\n")
            ranked.forEachIndexed { i, (l, t) -> append("${i + 1}. $t – $l\n") }
            if (read.isNotEmpty()) append("\nInhalt der ersten Treffer:\n").append(read.toString().take(budget))
            if (gallery.isNotEmpty()) append("\n(${gallery.size} Bilder werden dem Nutzer bereits als Galerie angezeigt – nicht als Links auflisten.)")
        }
        val summary = "$method · ${ranked.size} Treffer" + (if (readTop > 0) " · ${minOf(readTop, ranked.size)} gelesen" else "") +
            (if (gallery.isNotEmpty()) " · ${gallery.size} Bilder" else "")
        return ToolOutput(forModel, summary, webImages = gallery)
    }

    // ---------- Ganze Webseite durchsuchen ----------

    private class CrawledPage(val title: String, val url: String, val lines: List<String>, val score: Int)

    /**
     * Folgt den Links innerhalb derselben Webseite (Breitensuche, höchstens [maxPages] Seiten)
     * und sammelt Text-Ausschnitte und Bilder, die zum Suchbegriff passen.
     */
    private fun crawlSite(url: String, query: String, wantImages: Boolean, maxPages: Int): ToolOutput {
        val startUrl = normalizeUrl(url)
        val host = URL(startUrl).host.removePrefix("www.")
        val terms = query.lowercase().split(Regex("[^a-zäöüß0-9]+")).filter { it.length >= 3 }
        fun relevance(t: String): Int { val l = t.lowercase(); return terms.sumOf { term -> l.windowed(term.length).count { it == term } } }

        val queue = ArrayDeque<String>().apply { add(startUrl) }
        val seen = mutableSetOf(canonical(startUrl))
        val pages = mutableListOf<CrawledPage>()
        val images = linkedMapOf<String, Pair<String, String>>() // Bild → (Beschreibung, Seite)
        val deadline = System.currentTimeMillis() + 75_000
        var errors = 0

        while (queue.isNotEmpty() && pages.size < maxPages && System.currentTimeMillis() < deadline) {
            val u = queue.removeFirst()
            val doc = runCatching { fetch(u) }.getOrNull()
            if (doc == null) { errors++; continue }
            // 1. Links derselben Webseite einsammeln – passende (Text/Adresse mit Suchbegriff) nach vorn
            for (a in doc.select("a[href]")) {
                val link = a.attr("abs:href").substringBefore('#').trim()
                if (!link.startsWith("http")) continue
                val linkHost = runCatching { URL(link).host.removePrefix("www.") }.getOrNull() ?: continue
                if (linkHost != host) continue
                val lower = link.lowercase()
                if (Regex("\\.(pdf|jpe?g|png|gif|webp|svg|zip|rar|mp[34]|exe|apk|docx?|xlsx?)(\\?|$)").containsMatchIn(lower)) continue
                if (Regex("login|logout|anmelden|warenkorb|cart|checkout|account|wp-admin|javascript:|mailto:|/feed|/tag/|/print").containsMatchIn(lower)) continue
                if (!seen.add(canonical(link)) || seen.size > 600) continue
                if (terms.isNotEmpty() && relevance(a.text() + " " + link) > 0) queue.addFirst(link) else queue.addLast(link)
            }
            // 2. Bilder
            if (wantImages) {
                pageImages(doc).forEach { (src, alt) -> images.putIfAbsent(src, alt to doc.location()) }
            }
            // 3. Text (verändert doc – deshalb zuletzt)
            val title = doc.title().ifBlank { u }
            val lines = pageLines(doc)
            pages += CrawledPage(title, doc.location(), lines, relevance(title) * 3 + lines.sumOf { relevance(it) })
            Thread.sleep(150) // die Webseite nicht überrennen
        }
        if (pages.isEmpty()) throw IOException("Die Webseite konnte nicht geladen werden")

        // Textauszüge: passende Zeilen je Seite, die relevantesten Seiten zuerst
        val budget = pageBudget()
        val ordered = if (terms.isEmpty()) pages else pages.sortedByDescending { it.score }
        val perPage = (budget / ordered.size.coerceAtLeast(1)).coerceIn(300, 2500)
        val text = buildString {
            for (p in ordered) {
                if (length > budget) break
                val excerpt = if (terms.isEmpty()) p.lines.joinToString("\n") else {
                    val hits = p.lines.filter { relevance(it) > 0 }
                    if (hits.isEmpty()) continue
                    hits.joinToString("\n")
                }
                append("### ${p.title}\n${p.url}\n")
                append(excerpt.take(perPage))
                append("\n\n")
            }
        }.ifBlank { "Auf ${pages.size} Seiten nichts zu „$query“ gefunden. Seiten: " + pages.joinToString { it.url } }

        // Bilder: passende Beschreibung/Adresse zuerst
        val imgList = images.entries.sortedByDescending { (src, v) -> relevance(v.first + " " + src) }
        val gallery = if (wantImages) imgList.take(24).map { (src, v) ->
            de.davscha.denkwerk.data.WebImage(src, title = v.first, source = v.second)
        } else emptyList()

        val forModel = buildString {
            append("Durchsucht: ${pages.size} Seiten von $host")
            if (errors > 0) append(" ($errors nicht ladbar)")
            append(".\n\n")
            append(text.take(budget))
            if (gallery.isNotEmpty()) {
                append("\n\nGefundene Bilder (${images.size} insgesamt, die ${gallery.size} passendsten werden dem Nutzer bereits als Galerie angezeigt – nicht als Links auflisten):\n")
                gallery.take(12).forEach { append("- ${it.title.ifBlank { "Bild" }} (${it.source})\n") }
            }
        }
        val summary = "${pages.size} Seiten" + (if (wantImages) " · ${images.size} Bilder" else "")
        return ToolOutput(forModel, summary, webImages = gallery)
    }

    /** Gleiche Seite = gleiche Adresse ohne www, Schrägstrich am Ende und Tracking-Parameter */
    private fun canonical(u: String): String =
        u.lowercase().replace("://www.", "://").substringBefore("?utm_").trimEnd('/')

    // ---------- Videosuche ----------

    private fun searchVideos(query: String, count: Int, duration: String, newest: Boolean): ToolOutput {
        if (query.isBlank()) return ToolOutput("Leere Suchanfrage", "leere Anfrage", ok = false)
        var found = if (searxngUrl().isNotBlank()) searchVideosSearx(query) else searchVideosDdg(query, duration, newest)
        if (newest) found = found.sortedByDescending { it.published }
        found = found.take(count)
        if (found.isEmpty()) return ToolOutput("Keine Videos für „$query“ gefunden.", "„$query“ · keine Videos")
        val list = found.mapIndexed { i, v ->
            "${i + 1}. ${v.title} – ${v.publisher}, ${v.duration}${if (v.published.isNotBlank()) ", ${v.published}" else ""} – ${v.url}"
        }.joinToString("\n")
        return ToolOutput(
            "Videos für „$query“ – sie werden dem Nutzer bereits als Video-Kärtchen angezeigt (antippen spielt ab). " +
                "Beschreibe kurz, was dabei ist, ohne die Links noch einmal aufzulisten:\n$list",
            "„$query“ · ${found.size} Videos",
            webVideos = found,
        )
    }

    /** DuckDuckGo-Videosuche: wie die Bildersuche, nur mit v.js */
    private fun searchVideosDdg(query: String, duration: String, newest: Boolean): List<de.davscha.denkwerk.data.WebVideo> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = httpGet("https://duckduckgo.com/?q=$q&iax=videos&ia=videos&kp=${ddgKp()}", referer = null)
        val vqd = Regex("vqd=[\"']?([\\d-]+)").find(html)?.groupValues?.get(1)
            ?: throw IOException("Die DuckDuckGo-Videosuche antwortet gerade nicht")
        val dur = when (duration) { "kurz" -> "videoDuration:short"; "mittel" -> "videoDuration:medium"; "lang" -> "videoDuration:long"; else -> "" }
        val time = if (newest) "publishedAfter:m" else ""
        val f = URLEncoder.encode("$time,,$dur,", "UTF-8")
        val p = if (safeSearch() == 2) "-1" else "1"
        val json = httpGet("https://duckduckgo.com/v.js?l=de-de&o=json&q=$q&vqd=$vqd&f=$f&p=$p&kp=${ddgKp()}", referer = "https://duckduckgo.com/")
        val arr = JSONObject(json).optJSONArray("results") ?: JSONArray()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val imgs = o.optJSONObject("images")
            de.davscha.denkwerk.data.WebVideo(
                url = o.optString("content"),
                title = o.optString("title"),
                thumb = imgs?.optString("large")?.ifBlank { null } ?: imgs?.optString("medium").orEmpty(),
                duration = o.optString("duration"),
                publisher = o.optString("uploader").ifBlank { o.optString("publisher") },
                published = o.optString("published").take(10),
            )
        }.filter { it.url.startsWith("http") }
    }

    private fun searchVideosSearx(query: String): List<de.davscha.denkwerk.data.WebVideo> {
        val base = searxngUrl().trim().trimEnd('/')
        val json = httpGet("$base/search?format=json&categories=videos&safesearch=${searxSafe()}&q=" + URLEncoder.encode(query, "UTF-8"), referer = null)
        val arr = JSONObject(json).optJSONArray("results") ?: JSONArray()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            de.davscha.denkwerk.data.WebVideo(
                url = o.optString("url"),
                title = o.optString("title"),
                thumb = o.optString("thumbnail"),
                duration = o.optString("length"),
                publisher = o.optString("author"),
                published = o.optString("publishedDate").take(10),
            )
        }.filter { it.url.startsWith("http") }
    }

    // ---------- YouTube-Video über Untertitel verstehen ----------

    /**
     * Holt Titel, Kanal, Beschreibung und – falls vorhanden – die Untertitel eines YouTube-Videos
     * (Deutsch bevorzugt, sonst Englisch, sonst automatisch erzeugte).
     */
    private fun videoTranscript(url: String): ToolOutput {
        val id = youtubeId(url) ?: return ToolOutput("Das ist kein YouTube-Video – nur YouTube-Untertitel werden unterstützt.", "kein YouTube-Link", ok = false)
        val conn = URL("https://www.youtube.com/watch?v=$id&hl=de").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
        // Zustimmungs-Seite überspringen
        conn.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        val html = try {
            if (conn.responseCode !in 200..299) throw IOException("YouTube antwortet mit Fehler ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val player = Regex("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});\\s*(?:var|</script>)", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw IOException("Video-Infos konnten nicht gelesen werden")
        val details = player.optJSONObject("videoDetails")
        val title = details?.optString("title").orEmpty()
        val channel = details?.optString("author").orEmpty()
        val seconds = details?.optString("lengthSeconds")?.toIntOrNull() ?: 0
        val description = details?.optString("shortDescription").orEmpty()

        val tracks = player.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
        val all = if (tracks == null) emptyList() else List(tracks.length()) { tracks.getJSONObject(it) }
        fun pick(lang: String, auto: Boolean) = all.firstOrNull { it.optString("languageCode").startsWith(lang) && (it.optString("kind") == "asr") == auto }
        val track = pick("de", false) ?: pick("de", true) ?: pick("en", false) ?: pick("en", true) ?: all.firstOrNull()

        var transcript = ""
        if (track != null) {
            transcript = runCatching {
                val xml = httpGet(track.optString("baseUrl"), referer = "https://www.youtube.com/")
                Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser()).select("text")
                    .joinToString(" ") { org.jsoup.parser.Parser.unescapeEntities(it.text(), false) }
                    .replace(Regex("\\s+"), " ").trim()
            }.getOrDefault("")
        }
        val budget = pageBudget()
        val cut = transcript.length > budget
        val minutes = "${seconds / 60}:${"%02d".format(seconds % 60)}"
        val forModel = buildString {
            append("Video: $title\nKanal: $channel\nLänge: $minutes\nAdresse: https://www.youtube.com/watch?v=$id\n\n")
            append("Beschreibung:\n${description.take(1500)}\n\n")
            if (transcript.isNotBlank()) {
                append("Untertitel (${track?.optString("languageCode")}${if (track?.optString("kind") == "asr") ", automatisch erzeugt" else ""}):\n")
                append(transcript.take(budget))
                if (cut) append("\n[… gekürzt, ${transcript.length} Zeichen insgesamt]")
            } else {
                append("Für dieses Video gibt es keine abrufbaren Untertitel – nur Titel und Beschreibung sind bekannt.")
            }
        }
        val video = de.davscha.denkwerk.data.WebVideo(
            url = "https://www.youtube.com/watch?v=$id",
            title = title,
            thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            duration = minutes,
            publisher = channel,
        )
        val summary = if (transcript.isNotBlank()) "Untertitel gelesen" + (if (cut) " · gekürzt" else "") else "keine Untertitel – nur Beschreibung"
        return ToolOutput(forModel, summary, ok = true, webVideos = listOf(video))
    }

    private fun youtubeId(url: String): String? =
        Regex("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

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
        val html = httpGet("https://duckduckgo.com/?q=$q&iax=images&ia=images&kp=${ddgKp()}", referer = null)
        val vqd = Regex("vqd=[\"']?([\\d-]+)").find(html)?.groupValues?.get(1)
            ?: throw IOException("Die DuckDuckGo-Bildersuche antwortet gerade nicht")
        // p: 1 = abgesichert, -1 = aus; kp zusätzlich für Streng/Moderat/Aus
        val p = if (safeSearch() == 2) "-1" else "1"
        val json = httpGet("https://duckduckgo.com/i.js?l=de-de&o=json&q=$q&vqd=$vqd&f=,,,,,&p=$p&kp=${ddgKp()}", referer = "https://duckduckgo.com/")
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
        val json = httpGet("$base/search?format=json&categories=images&safesearch=${searxSafe()}&q=" + URLEncoder.encode(query, "UTF-8"), referer = null)
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

    /** DuckDuckGo: kp=1 Streng, kp=-1 Moderat, kp=-2 Aus */
    private fun ddgKp(): String = when (safeSearch()) { 0 -> "1"; 1 -> "-1"; else -> "-2" }

    /** SearXNG: 2 Streng, 1 Moderat, 0 Aus */
    private fun searxSafe(): Int = 2 - safeSearch().coerceIn(0, 2)

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
            "webseite_durchsuchen" -> "🕸️" to "Webseite durchsucht"
            "auf_seite_suchen" -> "🔎" to "Seitensuche"
            "videos_suchen" -> "🎬" to "Videosuche"
            "video_verstehen" -> "📺" to "Video angeschaut"
            "herunterladen" -> "⬇️" to "Herunterladen"
            "merken" -> "🧠" to "Gemerkt"
            "vergessen" -> "🧹" to "Vergessen"
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
