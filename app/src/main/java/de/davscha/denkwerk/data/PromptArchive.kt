package de.davscha.denkwerk.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Davids Daz-Prompt-Archiv (archive.json): Figuren, Haare, Kleidung, Posen, Umgebungen, Kameras … als Prompt-Bausteine.
 * Das Archiv hat beim Malen immer Vorrang: Was im Text genannt wird (Name, ID oder Kategorie), kommt aus dem Archiv;
 * Haare und Kleidung, die fehlen, werden passend ergänzt. Das Sprachmodell schreibt nur noch Stimmung und Extras.
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
        val tags get() = json.optJSONArray("tags").strings() + json.optJSONArray("themes").strings()
    }

    /** Was für ein Bild gebaut wird – jeder Platz kann leer sein */
    data class Plan(
        val figure: Entry? = null,
        val hair: Entry? = null,
        val hairVariant: Int = -1,
        val outfit: Entry? = null,
        val pose: Entry? = null,
        val poseIndex: Int = -1,
        val env: Entry? = null,
        val sceneIndex: Int = -1,
        val camera: Entry? = null,
        /** Was vom Archiv selbst ergänzt wurde (für die Karte) */
        val filled: Set<String> = emptySet(),
    ) {
        val isEmpty get() = figure == null && outfit == null && pose == null && env == null && hair == null

        /** Für die 🎨-Karte: „Victoria 7 · Queen of Shadow · Pose ‚…‘ · Mystic Hollow“ */
        fun label(): String = listOfNotNull(
            figure?.short,
            hair?.let { "Haare ${it.short}" + if ("hair" in filled) " (ergänzt)" else "" },
            outfit?.let { it.short + if ("outfit" in filled) " (ergänzt)" else "" },
            pose?.let { p -> poseAt(p, poseIndex)?.optString("name")?.let { "Pose „$it“" } ?: p.short },
            env?.let { e -> sceneAt(e, sceneIndex)?.optString("name")?.let { "${e.short}: $it" } ?: e.short },
            camera?.short,
        ).joinToString(" · ")
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

    /** „Queen of Shadow for Genesis 3 Female(s)“ → „Queen of Shadow“ */
    fun shortName(name: String) = name
        .replace(Regex("(?i)\\s+for\\s+(genesis|g\\d|michael|victoria|daz).*$"), "")
        .replace(Regex("(?i)\\s*\\((s)\\)"), "").trim()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9äöüß]+"), " ").trim()

    private val poseCategories = listOf(
        Regex("(?i)pin.?up") to "pinups", Regex("(?i)kampf|combat|fight") to "combat", Regex("(?i)tanz|dance|sport") to "dance_sport",
        Regex("(?i)alltag|everyday|casual") to "everyday", Regex("(?i)horror|gruselig") to "horror", Regex("(?i)fantasy") to "fantasy",
        Regex("(?i)interagier|interacting|zu zweit|paar") to "interacting",
    )
    private val outdoorCats = setOf("nature", "city street", "old town", "desert", "medieval village")
    private val outdoorWords = Regex("(?i)außen|ausserhalb|außerhalb|draußen|draussen|outdoor|outside|im freien|unter freiem himmel")
    private val indoorWords = Regex("(?i)\\bdrinnen\\b|\\binnen\\b|indoor|inside|im raum|im zimmer|im studio")
    private val placeWords = listOf(
        Regex("(?i)wald|forest|woods") to "forest", Regex("(?i)stadt|city|straße|street|urban") to "city",
        Regex("(?i)wüste|desert") to "desert", Regex("(?i)dorf|village") to "village", Regex("(?i)ruine|ruins") to "ruins",
        Regex("(?i)wiese|tal|valley|meadow") to "valley", Regex("(?i)nacht|night") to "night",
    )

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

    /**
     * Plan aus dem Nutzertext: genannte Namen/IDs zuerst, dann Kategorien („Pin-up-Pose“, „draußen“),
     * dann fehlende Haare/Kleidung ergänzen. [previous] = Plan des letzten Bildes („gleiches Bild, aber …“).
     */
    fun plan(text: String, previous: Plan? = null): Plan {
        if (!loaded) return Plan()
        val t = " " + norm(text) + " "
        // 1) Namen und IDs im Text (längste zuerst, damit „Victoria 7“ vor „Victoria“ greift)
        val named = mutableListOf<Entry>()
        entries.filter { it.active }.sortedByDescending { it.short.length }.forEach { e ->
            val n = norm(e.short)
            val hit = Regex("(?i)\\b${Regex.escape(e.id)}\\b").containsMatchIn(text) ||
                (n.length >= 4 && t.contains(" $n ") && named.none { norm(it.short).contains(n) })
            if (hit) named += e
        }
        var p = previous?.copy(filled = emptySet()) ?: Plan()
        val change = Regex("(?i)\\b(andere[nmrs]?|neue[nmrs]?|anderer|other|different)\\b")
        named.forEach { e ->
            p = when (e.area) {
                "figures", "shaping" -> p.copy(figure = e)
                "hair" -> p.copy(hair = e, hairVariant = -1)
                "wardrobe", "accessories" -> p.copy(outfit = e)
                "poses" -> p.copy(pose = e, poseIndex = -1)
                "environments" -> p.copy(env = e, sceneIndex = -1)
                "cameras" -> p.copy(camera = e)
                else -> p
            }
        }
        // Einzelne Posen und Szenen per Name („Runway Stride Arms Back“, „Foggy Forest Path“)
        var poseByName = named.any { it.area == "poses" }
        var envByName = named.any { it.area == "environments" }
        entries.filter { it.active && it.area == "poses" }.forEach { e ->
            e.json.optJSONArray("poses")?.let { a ->
                for (i in 0 until a.length()) {
                    val n = norm(a.getJSONObject(i).optString("name"))
                    if (n.length >= 6 && t.contains(" $n ")) { p = p.copy(pose = e, poseIndex = i); poseByName = true }
                }
            }
        }
        entries.filter { it.active && it.area == "environments" }.forEach { e ->
            e.json.optJSONArray("scenes")?.let { a ->
                for (i in 0 until a.length()) {
                    val n = norm(a.getJSONObject(i).optString("name"))
                    if (n.length >= 6 && t.contains(" $n ")) { p = p.copy(env = e, sceneIndex = i); envByName = true }
                }
            }
        }
        val fig = p.figure
        val gender = fig?.genders?.firstOrNull()
        val young = fig?.young == true
        fun allowed(e: Entry) = e.active && !(young && e.adultOnly) &&
            (gender == null || e.genders.isEmpty() || gender in e.genders)

        // 2) Kategorien: Pose („Pin-up-Pose“, „andere Pose“)
        val wantsPose = Regex("(?i)pose|posier|posing").containsMatchIn(text)
        val poseCat = poseCategories.firstOrNull { it.first.containsMatchIn(text) }?.second
        val rerollPose = wantsPose && change.containsMatchIn(text)
        if (!poseByName && (poseCat != null || (wantsPose && (p.pose == null || rerollPose)))) {
            val cat = poseCat ?: if (rerollPose) p.pose?.path?.lastOrNull() else null
            val pool = entries.filter { it.area == "poses" && allowed(it) && (cat == null || it.path.lastOrNull() == cat) }
            pickPose(pool)?.let { (e, i) -> p = p.copy(pose = e, poseIndex = i) }
        }
        // Kategorien: Umgebung
        val outdoor = outdoorWords.containsMatchIn(text)
        val indoor = indoorWords.containsMatchIn(text)
        val place = placeWords.filter { it.first.containsMatchIn(text) }.map { it.second }
        val rerollEnv = change.containsMatchIn(text) && Regex("(?i)\\bort\\b|umgebung|hintergrund|location|background|setting").containsMatchIn(text)
        val wantsEnv = outdoor || indoor || rerollEnv || (place.isNotEmpty() && p.env == null)
        if (wantsEnv && !envByName) {
            val pool = entries.filter { it.area == "environments" && it.active }.filter { e ->
                val cat = e.json.optString("category")
                (!outdoor || cat in outdoorCats) && (!indoor || cat !in outdoorCats)
            }
            val themed = pool.filter { e -> place.isEmpty() || place.any { w -> e.tags.any { it.contains(w, true) } || e.json.optString("category").contains(w, true) } }
            val outfitThemes = p.outfit?.json?.optJSONArray("themes").strings().map { it.lowercase() }
            val best = themed.ifEmpty { pool }.let { l -> l.filter { e -> e.tags.any { it.lowercase() in outfitThemes } }.ifEmpty { l } }
            best.randomOrNull()?.let { e -> p = p.copy(env = e, sceneIndex = randomIndex(e.json.optJSONArray("scenes"))) }
        }
        // „andere Frisur/Outfit“
        if (change.containsMatchIn(text) && Regex("(?i)frisur|haare|hair").containsMatchIn(text) && named.none { it.area == "hair" }) p = p.copy(hair = null)
        if (change.containsMatchIn(text) && Regex("(?i)outfit|kleidung|klamotten|kostüm|clothes").containsMatchIn(text) && named.none { it.area == "wardrobe" }) p = p.copy(outfit = null)

        // 3) Fehlendes ergänzen: Haare und Kleidung zur Figur
        val filled = mutableSetOf<String>()
        if (fig != null && p.hair == null) {
            entries.filter { it.area == "hair" && allowed(it) }.randomOrNull()?.let { h ->
                p = p.copy(hair = h, hairVariant = hairVariantFor(h, fig)); filled += "hair"
            }
        }
        if (fig != null && p.outfit == null) {
            val pool = entries.filter { it.area == "wardrobe" && allowed(it) && it.path.lastOrNull() !in setOf("intimates", "footwear", "accessories") }
            val themed = p.env?.tags?.map { it.lowercase() }?.let { th -> pool.filter { o -> o.tags.any { it.lowercase() in th } } }.orEmpty()
            themed.ifEmpty { pool.filter { it.path.lastOrNull() == "everyday" }.ifEmpty { pool } }.randomOrNull()?.let { p = p.copy(outfit = it); filled += "outfit" }
        }
        // Sicherheitsregel: jung wirkende Figur nie mit adult_only
        if (young) {
            if (p.outfit?.adultOnly == true) p = p.copy(outfit = null)
            if (p.pose?.adultOnly == true) p = p.copy(pose = null, poseIndex = -1)
            if (p.hair?.adultOnly == true) p = p.copy(hair = null)
        }
        val cam = named.firstOrNull { it.area == "cameras" } ?: if (previous?.camera != null && named.none { it.area == "cameras" } &&
            !Regex("(?i)ganzkörper|full body|cowboy|oberkörper|porträt|portrait|upper body|close.?up").containsMatchIn(text)) previous.camera
        else cameraFor(text, p.pose != null)
        return p.copy(camera = cam, filled = filled)
    }

    private fun randomIndex(a: JSONArray?): Int {
        if (a == null || a.length() == 0) return -1
        val act = (0 until a.length()).filter { a.getJSONObject(it).optBoolean("active", true) }
        return act.ifEmpty { (0 until a.length()).toList() }.random()
    }

    /** Pose zufällig: 1 Person, ohne Requisiten, „leicht“ bevorzugt */
    private fun pickPose(pool: List<Entry>): Pair<Entry, Int>? {
        val all = pool.flatMap { e ->
            val a = e.json.optJSONArray("poses") ?: return@flatMap emptyList()
            (0 until a.length()).map { e to it }.filter { (_, i) ->
                val o = a.getJSONObject(i)
                o.optBoolean("active", true) && o.optInt("persons", 1) <= 1 && (o.optJSONArray("requires")?.length() ?: 0) == 0
            }
        }
        val easy = all.filter { (e, i) -> e.json.getJSONArray("poses").getJSONObject(i).optString("difficulty") == "leicht" }
        return (if (easy.isNotEmpty() && Math.random() < 0.7) easy else all).randomOrNull()
    }

    /** Haarfarbe passend zu den Tags der Figur (brunette, blonde, redhead …) */
    private fun hairVariantFor(h: Entry, fig: Entry): Int {
        val a = h.json.optJSONArray("variants") ?: return -1
        val tags = fig.tags.joinToString(" ").lowercase()
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

    // ---------- Prompt bauen ----------

    private fun active(a: JSONArray?): JSONObject? {
        if (a == null || a.length() == 0) return null
        for (i in 0 until a.length()) if (a.getJSONObject(i).optBoolean("active", false)) return a.getJSONObject(i)
        return a.getJSONObject(0)
    }

    private fun poseAt(e: Entry, i: Int): JSONObject? = e.json.optJSONArray("poses")?.let { if (i in 0 until it.length()) it.getJSONObject(i) else active(it) }
    private fun sceneAt(e: Entry, i: Int): JSONObject? = e.json.optJSONArray("scenes")?.let { if (i in 0 until it.length()) it.getJSONObject(i) else active(it) }

    fun figurePrompt(e: Entry): String {
        val o = e.json
        val parts = mutableListOf<String>()
        o.optString("age").takeIf { it.isNotBlank() }?.let { parts += it }
        o.optJSONObject("shapes")?.let { s -> s.keys().forEach { k -> s.optString(k).takeIf { it.isNotBlank() }?.let { parts += it } } }
        for (field in listOf("skins", "eye_colors", "makeup")) active(o.optJSONArray(field))?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { parts += it }
        when (val sp = o.opt("special")) {
            is String -> if (sp.isNotBlank()) parts += sp
            is JSONArray -> parts += sp.strings()
        }
        return parts.joinToString(", ")
    }

    private fun variantPrompt(e: Entry, index: Int, zones: List<String>?): String {
        val a = e.json.optJSONArray("variants") ?: return e.json.optString("prompt")
        val v = if (index in 0 until a.length()) a.getJSONObject(index) else active(a) ?: return ""
        val parts = v.optJSONArray("parts") ?: return v.optString("prompt")
        return (0 until parts.length()).map { parts.getJSONObject(it) }
            .filter { it.optBoolean("active", true) && (zones == null || it.optString("zone").isBlank() || it.optString("zone") in zones) }
            .joinToString(", ") { it.optString("prompt") }
    }

    /** Fertiger Bild-Prompt aus dem Plan; [extra] = Stimmung/Ausdruck vom Sprachmodell */
    fun build(p: Plan, extra: String = ""): String {
        val zones = p.camera?.json?.optJSONArray("zones").strings().ifEmpty { null }
        return buildList {
            p.figure?.let { add(figurePrompt(it)) }
            p.hair?.let { add(variantPrompt(it, p.hairVariant, null)) }
            p.outfit?.let { variantPrompt(it, -1, zones).takeIf { s -> s.isNotBlank() }?.let { s -> add("wearing $s") } }
            p.pose?.let { poseAt(it, p.poseIndex)?.optString("prompt")?.let { s -> add(s) } }
            p.env?.let { sceneAt(it, p.sceneIndex)?.let { s -> add(listOf(s.optString("prompt"), s.optString("light")).filter { x -> x.isNotBlank() }.joinToString(", ")) } }
            extra.trim().trim('.').takeIf { it.isNotBlank() }?.let { add(it) }
            p.camera?.json?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.filter { it.isNotBlank() }.joinToString(". ") + "."
    }

    /** Platzhalter {ID} oder {Name} in einem fertigen Prompt durch Archiv-Bausteine ersetzen (z. B. bei „!male“) */
    fun resolvePlaceholders(prompt: String): String = Regex("\\{([^{}]{2,80})\\}").replace(prompt) { m ->
        val key = m.groupValues[1].trim()
        val e = byId(key) ?: entries.firstOrNull { norm(it.short) == norm(key) || norm(it.name) == norm(key) } ?: return@replace m.value
        when (e.area) {
            "figures", "shaping" -> figurePrompt(e)
            "poses" -> poseAt(e, -1)?.optString("prompt").orEmpty()
            "environments" -> sceneAt(e, -1)?.let { "${it.optString("prompt")}, ${it.optString("light")}" }.orEmpty()
            "cameras" -> e.json.optString("prompt")
            else -> variantPrompt(e, -1, null)
        }
    }
}

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
