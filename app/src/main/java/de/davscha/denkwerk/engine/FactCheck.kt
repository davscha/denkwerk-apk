package de.davscha.denkwerk.engine

/**
 * Einfache Faktenprüfung: Welche Namen und Zahlen aus der Antwort stehen NICHT in den gelesenen Quellen?
 * Das findet keine Fehler sicher, macht aber typische Erfindungen sichtbar (Torschützen, Minuten, Trainer …).
 */
object FactCheck {

    /** Wörter, mit denen Sätze oft beginnen – sonst hielte man „Die Ergebnisse“ für einen Namen */
    private val stopWords = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "einem", "einen", "für", "am", "im", "mit", "laut",
        "auf", "aus", "bei", "nach", "von", "vom", "zum", "zur", "und", "oder", "aber", "weitere", "hinweis", "fazit", "tore",
        "trainer", "formationen", "ergebnis", "ergebnisse", "spiel", "hier", "dazu", "auch", "wie", "was", "wer", "es", "er",
        "sie", "in", "an", "über", "unter", "seit", "bis", "gegen", "beim", "kurz", "quelle", "quellen", "the", "and", "of",
        "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "dezember",
        "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag", "zusammenfassung", "details", "weiteren",
    )

    /** Namen (2+ großgeschriebene Wörter) und Zahlen-Details (Ergebnisse, Minuten, Uhrzeiten, große Zahlen) */
    fun claims(answer: String): List<String> {
        val plain = answer.replace(Regex("[*_`#>\\[\\]()]"), " ")
        val out = LinkedHashSet<String>()
        // Namen: „Matthias Ginter“, „Lucas Höler“, „Sascha Stegemann“
        Regex("\\b([A-ZÄÖÜ][a-zäöüßéè]+(?:[ -][A-ZÄÖÜ][a-zäöüßéè]+)+)\\b").findAll(plain).forEach { m ->
            val words = m.value.split(' ', '-').filter { it.isNotBlank() }.dropWhile { it.lowercase() in stopWords }
            if (words.size >= 2 && words.none { it.lowercase() in stopWords }) out += words.joinToString(" ")
        }
        // Ergebnisse 2:1, Minuten (29.) bzw. 29', Uhrzeiten 17:30 Uhr, große Zahlen 42,9 %
        Regex("\\b\\d{1,2}:\\d{1,2}\\b").findAll(answer).forEach { out += it.value }
        Regex("\\((\\d{1,3})\\.\\)").findAll(answer).forEach { out += "${it.groupValues[1]}." }
        Regex("\\b\\d{1,3}(?:[.,]\\d+)?\\s?%").findAll(answer).forEach { out += it.value.replace(" ", "") }
        Regex("\\b\\d{4,}\\b").findAll(answer).forEach { if (it.value.length != 4 || it.value.toInt() !in 1900..2100) out += it.value }
        return out.toList()
    }

    /** Was davon nicht in [sourceText] oder in der Frage vorkommt */
    fun unverified(answer: String, sourceText: String, question: String): List<String> {
        if (sourceText.isBlank()) return emptyList()
        val src = normalize(sourceText + " " + question)
        return claims(answer).filter { c -> !supported(c, src) }.take(12)
    }

    private fun normalize(t: String) = t.lowercase().replace(Regex("\\s+"), " ")

    private fun supported(claim: String, src: String): Boolean {
        val c = claim.lowercase()
        if (src.contains(c)) return true
        // Ergebnis auch andersherum (2:0 ↔ 0:2) bzw. mit Bindestrich
        Regex("^(\\d{1,2}):(\\d{1,2})$").find(c)?.let { m ->
            val (a, b) = m.destructured
            return listOf("$b:$a", "$a-$b", "$b-$a", "$a – $b", "$a : $b").any { src.contains(it) }
        }
        // Minute „29.“ auch als 29' oder „29. minute“
        Regex("^(\\d{1,3})\\.$").find(c)?.let { m ->
            val n = m.groupValues[1]
            return Regex("\\b$n(['’′]|\\.\\s?min|\\.\\s?minute| min)").containsMatchIn(src)
        }
        // Namen: alle Teile müssen vorkommen (z. B. „Höler“ und „Lucas“ getrennt erwähnt)
        val parts = c.split(' ').filter { it.length > 2 }
        return parts.isNotEmpty() && parts.all { src.contains(it) }
    }
}
