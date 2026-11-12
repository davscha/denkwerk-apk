package de.davscha.denkwerk.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Davids Daz-Prompt-Archiv (archive.json): Figuren, Haare, Kleidung, Posen, Umgebungen, Kameras … als Prompt-Bausteine.
 * Beim Malen hat das Archiv immer Vorrang. Ergebnis ist eine Szene im Format von Davids scene.json
 * (style, figures[…], action, background, lights, camera) – MOSS Image versteht JSON direkt.
 *
 * Quelle: Denkwerk/archive.json (wenn vorhanden, zum Pflegen ohne neuen Build), sonst die mitgelieferte Datei.
 */
object PromptArchive {

    /** Ein Archiv-Eintrag (Produkt) mit Bereich (figures, hair, wardrobe, poses, environments, cameras …) und Kategorie-Pfad */
    class Entry(val id: String, val name: String, val area: String, val path: List<String>, val json: JSONObject) {
        val short: String = shortName(name)
        val active: Boolean get() = json.optBoolean("active", true)
        val genders: List<String> get() = json.optJSONArray("gender").strings().ifEmpty { listOfNotNull(path.firstOrNull { it == "female" || it == "male" }) }
        val adultOnly get() = json.optBoolean("adult_only", false)
        val young get() = json.optBoolean("minor", false) || json.optBoolean("youthful_look", false)
        val tags get() = (json.optJSONArray("tags").strings() + json.optJSONArray("themes").strings()).map { it.lowercase() }
    }

    /** Eine Figur in der Szene mit ihren Bausteinen */
    data class Person(
        val figure: Entry,
        val hair: Entry? = null,
        val hairVariant: Int = -1,
        val outfit: Entry? = null,
        val pose: Entry? = null,
        val poseIndex: Int = -1,
        val filled: Set<String> = emptySet(),
    ) {
        val gender get() = figure.genders.firstOrNull()
        /** Rufname im Bild: „Victoria 7“ → „Victoria“ */
        val callName get() = figure.short.replace(Regex("(?i)\\s+(\\d+|hd|pro|bundle|add-on)\\b.*$"), "").trim().ifBlank { figure.short }
    }

    /** Die ganze Szene */
    data class Plan(
        val people: List<Person> = emptyList(),
        val env: Entry? = null,
        val sceneIndex: Int = -1,
        val camera: Entry? = null,
        /** Kamera-Zusätze aus dem Text (dutch angle, low angle …) */
        val cameraExtra: String = "",
        /** true = aus dem letzten Bild weiterentwickelt („gleiches Bild, aber …“) */
        val fromPrevious: Boolean = false,
        /** true = im Text wurde wirklich etwas aus dem Archiv genannt oder geändert */
        val changed: Boolean = false,
    ) {
        val isEmpty get() = people.isEmpty() && env == null

        /** Für die 🎨-Karte: „Victoria 7 (Haare …, Outfit …, Pose „…“) · Mystic Hollow: … · Full Body“ */
        fun label(): String = (people.map { p ->
            val parts = listOfNotNull(
                p.hair?.let { "Haare ${it.short}" + if ("hair" in p.filled) " (ergänzt)" else "" },
                p.outfit?.let { it.short + if ("outfit" in p.filled) " (ergänzt)" else "" },
                p.pose?.let { e -> poseAt(e, p.poseIndex)?.optString("name")?.let { "Pose „$it“" } ?: e.short },
            )
            p.figure.short + if (parts.isNotEmpty()) " (" + parts.joinToString(", ") + ")" else ""
        } + listOfNotNull(
            env?.let { e -> sceneAt(e, sceneIndex)?.optString("name")?.let { "${e.short}: $it" } ?: e.short },
            camera?.short?.let { it + if (cameraExtra.isNotBlank()) ", $cameraExtra" else "" },
        )).joinToString(" · ")

        /** Rufnamen für das Sprachmodell */
        fun names() = people.joinToString(", ") { it.callName }
    }

    @Volatile private var entries: List<Entry> = emptyList()
    val loaded get() = entries.isNotEmpty()
    val size get() = entries.size

    /** Laden: eigene Datei im Denkwerk-Ordner hat Vorrang vor der mitgelieferten */
    fun load(context: Context, dataDir: File) {
        val own = File(dataDir, "archive.json")
        val text = runCatching { if (own.exists()) own.readText() else null }.getOrNull()
            ?: runCatching { context.assets.open("archive.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val list = mutableListOf<Entry>()
        fun walk(node: Any?, area: String, path: List<String>) {
            when (node) {
                is JSONObject -> if (node.has("id") && node.has("name")) {
                    list += Entry(node.optString("id"), node.optString("name"), area, path, node)
                } else node.keys().forEach { k -> walk(node.opt(k), area.ifEmpty { k }, if (area.isEmpty()) path else path + k) }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), area, path)
            }
        }
        walk(root, "", emptyList())
        entries = list
    }

    fun byId(id: String) = entries.firstOrNull { it.id.equals(id, true) }

    // ---------- Text verstehen ----------

    /** „Queen of Shadow for Genesis 3 Female(s)“ → „Queen of Shadow“, „Nocturne Poses for V6“ → „Nocturne Poses“ */
    fun shortName(name: String) = name
        .replace(Regex("(?i)\\s+for\\s+\\S.*$"), "")
        .replace(Regex("(?i)\\s*\\((s)\\)"), "").trim()
        .ifBlank { name }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9äöüß]+"), " ").trim()

    /** Allgemeine Wörter in Produktnamen – für die Teil-Suche („Nocturne Poses“ → „nocturne“) */
    private val generic = setOf(
        "the", "and", "for", "of", "with", "outfit", "outfits", "pose", "poses", "hair", "hd", "add", "on", "addon", "bundle", "pro",
        "dforce", "set", "pack", "collection", "series", "female", "male", "genesis", "expressions", "clothing", "clothes", "xi", "fn", "mb",
    )
    private val areaHint = mapOf(
        "hair" to Regex("(?i)haar|frisur|hair"),
        "wardrobe" to Regex("(?i)outfit|kleid|klamotten|kostüm|anzug|clothes|wear|trägt|anzieh"),
        "poses" to Regex("(?i)pose|posier"),
        "environments" to Regex("(?i)hintergrund|umgebung|\\bort\\b|environment|background|szene|location"),
    )

    private val poseCategories = listOf(
        Regex("(?i)pin.?up") to "pinups", Regex("(?i)kampf|combat|fight") to "combat", Regex("(?i)tanz|dance|sport") to "dance_sport",
        Regex("(?i)alltag|everyday|casual") to "everyday", Regex("(?i)horror|gruselig") to "horror",
        Regex("(?i)interagier|interacting") to "interacting",
    )
    /** Pose-Funktion (Daz „By Function“) aus dem Text */
    private val poseFunctions = listOf(
        Regex("(?i)stehend|steht|stehen|standing|\\bstand\\b") to "Standing", Regex("(?i)sitzend|sitzt|sitzen|sitting") to "Sitting",
        Regex("(?i)kniend|kniet|knien|kneeling") to "Kneeling", Regex("(?i)liegend|liegt|liegen|lying|laying") to "Laying",
        Regex("(?i)hockend|hockt|crouch") to "Crouching", Regex("(?i)lehnend|lehnt|leaning") to "Leaning",
        Regex("(?i)gehend|läuft|laufend|walking") to "Walking", Regex("(?i)rennend|rennt|running") to "Running",
        Regex("(?i)tanzend|tanzt|dancing") to "Dancing", Regex("(?i)springend|springt|jumping") to "Jumping",
        Regex("(?i)fliegend|fliegt|flying") to "Flying",
    )
    /** Themen aus dem Text → Kleidungs-Kategorie und Tags */
    private val themes = listOf(
        Regex("(?i)steampunk") to "steampunk", Regex("(?i)fantasy|märchen|elfe?\\b") to "fantasy", Regex("(?i)horror|grusel|vampir") to "horror",
        Regex("(?i)sci.?fi|science.?fiction|futurist|cyberpunk") to "sci_fi", Regex("(?i)vintage|retro|50er|20er") to "vintage",
        Regex("(?i)wild west|western") to "wild_west", Regex("(?i)strand|beach|bikini|badeanzug|schwimm") to "swimwear",
        Regex("(?i)party|gala|hochzeit|wedding") to "party_events", Regex("(?i)uniform|polizei|militär") to "uniforms",
        Regex("(?i)schule|school") to "school",
    )
    private val outdoorCats = setOf("nature", "city street", "old town", "desert", "medieval village")
    private val outdoorWords = Regex("(?i)außen|ausserhalb|außerhalb|draußen|draussen|outdoor|outside|im freien|unter freiem himmel")
    private val indoorWords = Regex("(?i)\\bdrinnen\\b|\\binnen\\b|indoor|inside|im raum|im zimmer|im studio")
    private val placeWords = listOf(
        Regex("(?i)wald|forest|woods") to "forest", Regex("(?i)stadt|city|straße|street|urban") to "city",
        Regex("(?i)wüste|desert") to "desert", Regex("(?i)dorf|village") to "village", Regex("(?i)ruine|ruins") to "ruins",
        Regex("(?i)wiese|\\btal\\b|valley|meadow") to "valley", Regex("(?i)lagerhalle|warehouse|fabrik") to "warehouse",
    )
    private val cameraExtras = listOf(
        Regex("(?i)dutch angle|schräg|gekippt") to "dutch angle", Regex("(?i)low angle|froschperspektive|von unten") to "low angle",
        Regex("(?i)high angle|vogelperspektive|von oben") to "high angle", Regex("(?i)weitwinkel|wide shot|totale") to "wide shot",
        Regex("(?i)seitlich|side view|von der seite") to "side view", Regex("(?i)von hinten|back view|rückansicht") to "back view",
    )
    private val change = Regex("(?i)\\b(andere[nmrs]?|anderer|neue[nmrs]?|wechsel\\w*|änder\\w*|tausch\\w*|ersetz\\w*|other|different|change)\\b")
    private val camWords = Regex("(?i)ganzkörper|full body|cowboy|oberkörper|porträt|portrait|upper body|close.?up")

    private data class Hit(val e: Entry, val pos: Int, val poseIndex: Int = -1, val sceneIndex: Int = -1)

    /** Alles, was im Text aus dem Archiv genannt wird – mit Position (für die Zuordnung zu Figuren) */
    private fun findHits(text: String): List<Hit> {
        val t = " " + norm(text) + " "
        val hits = mutableListOf<Hit>()
        // 1) Voller Kurzname (längste zuerst, damit „Victoria 7“ nicht in „Vianne for Victoria 7“ landet)
        entries.filter { it.active }.sortedByDescending { it.short.length }.forEach { e ->
            val n = norm(e.short)
            if (n.length < 4) return@forEach
            val i = t.indexOf(" $n ")
            if (i < 0 || hits.any { it.e.id == e.id }) return@forEach
            if (hits.any { h -> i >= h.pos && i < h.pos + norm(h.e.short).length + 1 }) return@forEach
            hits += Hit(e, i)
        }
        // 2) IDs und SKU-Nummern
        Regex("\\b([A-Z]\\d{4})\\b").findAll(text).forEach { m -> byId(m.value)?.let { e -> if (hits.none { it.e.id == e.id }) hits += Hit(e, m.range.first) } }
        Regex("\\b(\\d{4,6})\\b").findAll(text).forEach { m ->
            val sku = m.value.toInt()
            entries.firstOrNull { it.active && it.json.optInt("sku", -1) == sku }?.let { e -> if (hits.none { it.e.id == e.id }) hits += Hit(e, m.range.first) }
        }
        // 3) Teilname mit Hinweiswort („Nocturne Poses“, „die Fashion Haare“)
        entries.filter { it.active && it.area in areaHint && hits.none { h -> h.e.id == it.id } }.forEach { e ->
            val core = norm(e.short).split(" ").filter { it.length >= 3 && it !in generic && !it.all(Char::isDigit) }
            if (core.isEmpty() || core.sumOf { it.length } < 5) return@forEach
            if (core.all { t.contains(" $it ") } && areaHint[e.area]!!.containsMatchIn(text)) hits += Hit(e, t.indexOf(" ${core.first()} "))
        }
        // 4) Einzelne Posen und Szenen per Name
        entries.filter { it.active && (it.area == "poses" || it.area == "environments") }.forEach { e ->
            val a = e.json.optJSONArray(if (e.area == "poses") "poses" else "scenes") ?: return@forEach
            for (i in 0 until a.length()) {
                val n = norm(a.getJSONObject(i).optString("name"))
                val at = t.indexOf(" $n ")
                if (n.length >= 6 && at >= 0) hits += if (e.area == "poses") Hit(e, at, poseIndex = i) else Hit(e, at, sceneIndex = i)
            }
        }
        return hits.sortedBy { it.pos }
    }

    /**
     * Szene aus dem Nutzertext: genannte Namen/IDs/SKUs zuerst, dann Kategorien („Pin-up-Pose“, „stehende Pose“, „draußen“, „Steampunk“),
     * dann fehlende Haare/Kleidung ergänzen. [previous] = Szene des letzten Bildes („gleiches Bild, aber …“).
     */
    fun plan(text: String, previous: Plan? = null): Plan {
        if (!loaded) return Plan()
        val hits = findHits(text)
        var changed = hits.isNotEmpty()
        val figHits = hits.filter { it.e.area == "figures" || it.e.area == "shaping" }

        // Figuren: neu genannte ersetzen die alten; sonst die vom letzten Bild
        val people = mutableListOf<Person>()
        if (figHits.isNotEmpty()) {
            figHits.forEach { h -> if (people.none { it.figure.id == h.e.id }) people += previous?.people?.firstOrNull { it.figure.id == h.e.id } ?: Person(h.e) }
        } else previous?.people?.let { prev -> people += prev.map { p -> p.copy(filled = emptySet()) } }

        val young = people.any { it.figure.young }
        fun allowed(e: Entry, p: Person?) = e.active && !(young && e.adultOnly) &&
            (p?.gender == null || e.genders.isEmpty() || p.gender in e.genders)

        /** Welche Figur ist gemeint? Die zuletzt davor genannte, sonst die erste mit passendem Geschlecht */
        fun target(pos: Int, e: Entry?): Int {
            if (people.isEmpty()) return -1
            val before = figHits.lastOrNull { it.pos <= pos }?.let { h -> people.indexOfFirst { it.figure.id == h.e.id } } ?: -1
            if (before >= 0 && (e == null || allowed(e, people[before]))) return before
            if (e != null) people.indexOfFirst { allowed(e, it) }.takeIf { it >= 0 }?.let { return it }
            return if (before >= 0) before else 0
        }

        // Genannte Haare, Outfits, Posen den Figuren zuordnen
        var env: Entry? = previous?.env
        var sceneIndex = previous?.sceneIndex ?: -1
        var camera: Entry? = null
        val posedNow = mutableSetOf<Int>()
        hits.filter { it !in figHits }.forEach { h ->
            when (h.e.area) {
                "environments" -> { env = h.e; sceneIndex = if (h.sceneIndex >= 0) h.sceneIndex else randomIndex(h.e.json.optJSONArray("scenes")) }
                "cameras" -> camera = h.e
                "hair", "wardrobe", "accessories", "poses" -> {
                    val i = target(h.pos, h.e)
                    if (i >= 0) {
                        val p = people[i]
                        people[i] = when (h.e.area) {
                            "hair" -> p.copy(hair = h.e, hairVariant = hairVariantFor(h.e, p.figure))
                            "poses" -> { posedNow += i; p.copy(pose = h.e, poseIndex = h.poseIndex) }
                            else -> p.copy(outfit = h.e)
                        }
                    }
                }
            }
        }

        // Posen über Produkt, Kategorie oder Funktion („Pin-up“, „stehende Pose aus Nocturne Poses“, „andere Pose“)
        val wantsPose = Regex("(?i)pose|posier|posing").containsMatchIn(text)
        val poseCat = poseCategories.firstOrNull { it.first.containsMatchIn(text) }?.second
        val function = poseFunctions.firstOrNull { it.first.containsMatchIn(text) }?.second
        if (people.isNotEmpty()) people.indices.forEach { i ->
            val p = people[i]
            val namedSingle = i in posedNow && p.poseIndex >= 0
            if (namedSingle) return@forEach
            val fromProduct = i in posedNow && p.pose != null
            val reroll = wantsPose && (p.pose == null || function != null || poseCat != null || change.containsMatchIn(text))
            if (!fromProduct && !reroll && !(poseCat != null || function != null && wantsPose)) return@forEach
            val pool = if (fromProduct) listOf(p.pose!!) else entries.filter { it.area == "poses" && allowed(it, p) && (poseCat == null || it.path.lastOrNull() == poseCat) }
            val pick = pickPose(pool, function, people.size) ?: pickPose(pool, null, people.size)
            pick?.let { (e, idx) -> people[i] = p.copy(pose = e, poseIndex = idx); changed = true }
        }

        // Umgebung über Kategorie
        val outdoor = outdoorWords.containsMatchIn(text)
        val indoor = indoorWords.containsMatchIn(text)
        val place = placeWords.filter { it.first.containsMatchIn(text) }.map { it.second }
        val theme = themes.firstOrNull { it.first.containsMatchIn(text) }?.second
        val rerollEnv = change.containsMatchIn(text) && areaHint["environments"]!!.containsMatchIn(text)
        val envNamed = hits.any { it.e.area == "environments" }
        if (!envNamed && (outdoor || indoor || rerollEnv || place.isNotEmpty() || (theme != null && people.isNotEmpty() && previous == null))) {
            val pool = entries.filter { it.area == "environments" && it.active }.filter { e ->
                val cat = e.json.optString("category")
                (!outdoor || cat in outdoorCats) && (!indoor || cat !in outdoorCats)
            }
            val byPlace = pool.filter { e -> place.isEmpty() || place.any { w -> e.tags.any { it.contains(w) } || e.json.optString("category").contains(w, true) || e.short.contains(w, true) } }
            val base = byPlace.ifEmpty { pool }
            val byTheme = theme?.let { th -> base.filter { e -> e.tags.any { it.replace(' ', '_').contains(th.removePrefix("sci_")) } } }.orEmpty()
            val best = byTheme.ifEmpty { base }
            best.filter { !rerollEnv || it.id != env?.id }.ifEmpty { best }.randomOrNull()?.let { e ->
                env = e; sceneIndex = randomIndex(e.json.optJSONArray("scenes")); changed = true
            }
        }

        // „andere Frisur/Outfit“ ohne Namen → neu würfeln; Thema („Steampunk“) → passendes Outfit
        val hairNamed = hits.any { it.e.area == "hair" }
        val outfitNamed = hits.any { it.e.area == "wardrobe" }
        if (change.containsMatchIn(text) && areaHint["hair"]!!.containsMatchIn(text) && !hairNamed) {
            people.indices.forEach { people[it] = people[it].copy(hair = null) }; changed = true
        }
        if (!outfitNamed) {
            val reOutfit = change.containsMatchIn(text) && areaHint["wardrobe"]!!.containsMatchIn(text)
            people.indices.forEach { i ->
                val o = people[i].outfit
                if (reOutfit || (theme != null && o != null && o.path.lastOrNull() != theme)) { people[i] = people[i].copy(outfit = null); changed = true }
            }
        }

        // Fehlendes ergänzen: Haare und Kleidung (Thema bevorzugt, sonst passend zum Ort, sonst Alltag)
        people.indices.forEach { i ->
            var p = people[i]
            val filled = p.filled.toMutableSet()
            if (p.hair == null) entries.filter { it.area == "hair" && allowed(it, p) }.randomOrNull()?.let { h ->
                p = p.copy(hair = h, hairVariant = hairVariantFor(h, p.figure)); filled += "hair"
            }
            if (p.outfit == null) {
                val pool = entries.filter { it.area == "wardrobe" && allowed(it, p) && it.path.lastOrNull() !in setOf("intimates", "footwear", "accessories") }
                val byTheme = theme?.let { th -> pool.filter { o -> o.path.lastOrNull() == th || o.tags.any { it.replace(' ', '_') == th } } }.orEmpty()
                val envTags = env?.tags.orEmpty()
                val byEnv = pool.filter { o -> o.tags.any { it in envTags } }
                byTheme.ifEmpty { byEnv }.ifEmpty { pool.filter { it.path.lastOrNull() == "everyday" }.ifEmpty { pool } }.randomOrNull()?.let {
                    p = p.copy(outfit = it); filled += "outfit"
                }
            }
            // Sicherheitsregel: jung wirkende Figur in der Szene → nichts mit adult_only
            if (young) {
                if (p.outfit?.adultOnly == true) p = p.copy(outfit = null)
                if (p.pose?.adultOnly == true) p = p.copy(pose = null, poseIndex = -1)
                if (p.hair?.adultOnly == true) p = p.copy(hair = null)
            }
            people[i] = p.copy(filled = filled)
        }

        // Kamera: genannt > aus dem Text > vom letzten Bild > Ganzkörper bei Posen, sonst Cowboy Shot
        val cam = camera ?: if (!camWords.containsMatchIn(text) && previous?.camera != null) previous.camera else cameraFor(text, people.any { it.pose != null })
        val extraNow = cameraExtras.filter { it.first.containsMatchIn(text) }.joinToString(", ") { it.second }
        val extra = extraNow.ifBlank { if (camWords.containsMatchIn(text)) "" else previous?.cameraExtra.orEmpty() }
        if (extraNow.isNotBlank() || camWords.containsMatchIn(text)) changed = true
        return Plan(people, env, sceneIndex, cam, extra, fromPrevious = previous != null && figHits.isEmpty(), changed = changed)
    }

    /** Kamera aus dem Text, sonst Ganzkörper bei Posen, sonst Cowboy Shot */
    private fun cameraFor(text: String, hasPose: Boolean): Entry? {
        val cams = entries.filter { it.area == "cameras" && it.active }
        fun pick(rx: String) = cams.firstOrNull { Regex(rx, RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
        return when {
            Regex("(?i)ganzkörper|ganzer körper|full body|von kopf bis fuß").containsMatchIn(text) -> pick("full")
            Regex("(?i)cowboy|ab (den )?oberschenkel|halbtotale").containsMatchIn(text) -> pick("cowboy")
            Regex("(?i)oberkörper|porträt|portrait|upper body|nahaufnahme|close.?up").containsMatchIn(text) -> pick("upper")
            hasPose -> pick("full")
            else -> pick("cowboy")
        } ?: cams.firstOrNull()
    }

    private fun randomIndex(a: JSONArray?): Int {
        if (a == null || a.length() == 0) return -1
        val act = (0 until a.length()).filter { a.getJSONObject(it).optBoolean("active", true) }
        return act.ifEmpty { (0 until a.length()).toList() }.random()
    }

    /** Pose zufällig: passende Funktion, ohne Requisiten, keine reinen Ausdrücke, „leicht“ bevorzugt */
    private fun pickPose(pool: List<Entry>, function: String?, persons: Int): Pair<Entry, Int>? {
        val all = pool.flatMap { e ->
            val a = e.json.optJSONArray("poses") ?: return@flatMap emptyList()
            (0 until a.length()).map { e to it }.filter { (_, i) ->
                val o = a.getJSONObject(i)
                o.optBoolean("active", true) && o.optInt("persons", 1) <= maxOf(1, persons) && o.optString("function") != "Expression" &&
                    (o.optJSONArray("requires")?.length() ?: 0) == 0 && (function == null || o.optString("function").equals(function, true))
            }
        }
        val easy = all.filter { (e, i) -> e.json.getJSONArray("poses").getJSONObject(i).optString("difficulty") == "leicht" }
        return (if (easy.isNotEmpty() && Math.random() < 0.7) easy else all).randomOrNull()
    }

    /** Haarfarbe passend zu den Tags der Figur (brunette, blonde, redhead …), sonst die aktive Variante */
    private fun hairVariantFor(h: Entry, fig: Entry): Int {
        val a = h.json.optJSONArray("variants") ?: return -1
        val tags = fig.tags.joinToString(" ")
        val color = when {
            "brunette" in tags || "brown hair" in tags -> "brown"
            "blonde" in tags -> "blonde"
            "redhead" in tags || "red hair" in tags || "ginger" in tags -> "red"
            "black hair" in tags -> "black"
            else -> null
        }
        if (color != null) for (i in 0 until a.length()) if (a.getJSONObject(i).optString("prompt").contains(color, true)) return i
        return -1
    }

    // ---------- Szene bauen (Format wie Davids scene.json) ----------

    private fun active(a: JSONArray?): JSONObject? {
        if (a == null || a.length() == 0) return null
        for (i in 0 until a.length()) if (a.getJSONObject(i).optBoolean("active", false)) return a.getJSONObject(i)
        return a.getJSONObject(0)
    }

    private fun poseAt(e: Entry, i: Int): JSONObject? = e.json.optJSONArray("poses")?.let { if (i in 0 until it.length()) it.getJSONObject(i) else active(it) }
    private fun sceneAt(e: Entry, i: Int): JSONObject? = e.json.optJSONArray("scenes")?.let { if (i in 0 until it.length()) it.getJSONObject(i) else active(it) }

    private fun variant(e: Entry, index: Int): JSONObject? {
        val a = e.json.optJSONArray("variants") ?: return null
        return if (index in 0 until a.length()) a.getJSONObject(index) else active(a)
    }

    /** Figur als Text (für Platzhalter) */
    fun figurePrompt(e: Entry): String {
        val o = e.json
        val parts = mutableListOf<String>()
        o.optString("age").takeIf { it.isNotBlank() }?.let { parts += it }
        o.optJSONObject("shapes")?.let { s -> s.keys().forEach { k -> s.optString(k).takeIf { it.isNotBlank() }?.let { parts += it } } }
        for (field in listOf("skins", "eye_colors", "makeup")) active(o.optJSONArray(field))?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(", ")
    }

    private fun figureJson(p: Person, zones: List<String>?): JSONObject {
        val o = p.figure.json
        val j = JSONObject().put("name", p.callName)
        o.optString("age").takeIf { it.isNotBlank() }?.let { j.put("age", it) }
        p.gender?.let { j.put("gender", it) }
        o.optJSONObject("shapes")?.let { j.put("shapes", JSONObject(it.toString())) }
        active(o.optJSONArray("skins"))?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { j.put("skin", it) }
        active(o.optJSONArray("eye_colors"))?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { j.put("eye_color", it) }
        active(o.optJSONArray("makeup"))?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { j.put("makeup", it) }
        when (val sp = o.opt("special")) {
            is String -> if (sp.isNotBlank()) j.put("special", sp)
            is JSONArray -> if (sp.length() > 0) j.put("special", sp.strings().joinToString(", "))
        }
        p.hair?.let { h -> variant(h, p.hairVariant)?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { j.put("hair", it) } }
        p.outfit?.let { w ->
            val v = variant(w, -1)
            val parts = v?.optJSONArray("parts")
            if (parts != null) {
                val c = JSONObject()
                for (k in 0 until parts.length()) {
                    val part = parts.getJSONObject(k)
                    if (!part.optBoolean("active", true)) continue
                    if (zones != null && part.optString("zone").isNotBlank() && part.optString("zone") !in zones) continue
                    var key = part.optString("name").lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "part" }
                    while (c.has(key)) key += "_2"
                    c.put(key, part.optString("prompt"))
                }
                if (c.length() > 0) j.put("clothing", c)
            } else v?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { j.put("clothing", it) }
        }
        p.pose?.let { e -> poseAt(e, p.poseIndex)?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { j.put("pose", it) } }
        return j
    }

    /** Fertige Szene als JSON-Text; [action] = was die Figuren tun / Stimmung (vom Sprachmodell) */
    fun build(p: Plan, action: String = "", style: String = ""): String {
        val zones = p.camera?.json?.optJSONArray("zones").strings().ifEmpty { null }
        val j = JSONObject()
        style.takeIf { it.isNotBlank() }?.let { j.put("style", it) }
        if (p.people.isNotEmpty()) j.put("figures", JSONArray(p.people.map { figureJson(it, zones) }))
        action.trim().takeIf { it.isNotBlank() }?.let { j.put("action", it) }
        p.env?.let { e ->
            sceneAt(e, p.sceneIndex)?.let { s ->
                s.optString("prompt").takeIf { it.isNotBlank() }?.let { j.put("background", it) }
                s.optString("light").takeIf { it.isNotBlank() }?.let { j.put("lights", it) }
            }
        }
        val cam = listOfNotNull(p.camera?.json?.optString("prompt")?.takeIf { it.isNotBlank() }, p.cameraExtra.takeIf { it.isNotBlank() }).joinToString(", ")
        if (cam.isNotBlank()) j.put("camera", cam)
        return j.toString(2)
    }

    /** Platzhalter {ID} oder {Name} in einem fertigen Prompt durch Archiv-Bausteine ersetzen (z. B. bei „!male“) */
    fun resolvePlaceholders(prompt: String): String = Regex("\\{([^{}\"]{2,80})\\}").replace(prompt) { m ->
        val key = m.groupValues[1].trim()
        val e = byId(key) ?: entries.firstOrNull { norm(it.short) == norm(key) || norm(it.name) == norm(key) } ?: return@replace m.value
        when (e.area) {
            "figures", "shaping" -> figurePrompt(e)
            "poses" -> poseAt(e, -1)?.optString("prompt").orEmpty()
            "environments" -> sceneAt(e, -1)?.let { "${it.optString("prompt")}, ${it.optString("light")}" }.orEmpty()
            "cameras" -> e.json.optString("prompt")
            else -> variant(e, -1)?.let { v ->
                v.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("active", true) }.joinToString(", ") { it.optString("prompt") } }
                    ?: v.optString("prompt")
            }.orEmpty()
        }
    }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
