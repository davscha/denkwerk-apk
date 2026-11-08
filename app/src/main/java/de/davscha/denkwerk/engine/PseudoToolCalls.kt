package de.davscha.denkwerk.engine

import org.json.JSONObject

/**
 * Manche Modelle (z. B. umgebaute Gemma-Varianten) rufen Werkzeuge nicht richtig auf,
 * sondern schreiben den Aufruf als Text in die Antwort, etwa
 * `{"action": "bilder_suchen", "action_input": "{'anfrage': 'Katzen', 'anzahl': 4}"}`.
 * Diese Klasse findet solche Text-Aufrufe, damit Denkwerk sie trotzdem ausführen
 * und aus der sichtbaren Antwort entfernen kann.
 */
object PseudoToolCalls {

    data class Found(
        val name: String,
        val args: JSONObject,
        /** Genau dieser Textabschnitt wird aus der Antwort entfernt */
        val raw: String,
    )

    /** Hauptargument je Werkzeug – falls das Modell nur einen Text statt Objekt schickt */
    private val mainArg = mapOf(
        "web_suche" to "anfrage",
        "bilder_suchen" to "anfrage",
        "seite_lesen" to "url",
        "bilder_auf_seite" to "url",
        "webseite_durchsuchen" to "url",
        "auf_seite_suchen" to "url",
        "videos_suchen" to "anfrage",
        "video_verstehen" to "url",
        "bild_rueckwaertssuche" to "bild",
        "anime_bild_quelle" to "bild",
        "rechnen" to "ausdruck",
        "merken" to "fakt",
        "vergessen" to "fakt",
    )

    fun find(text: String, known: Set<String>): List<Found> {
        val result = mutableListOf<Found>()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start < 0) break
            val end = matchingBrace(text, start)
            if (end < 0) break
            val candidate = text.substring(start, end + 1)
            val parsed = parse(candidate, known)
            if (parsed != null) {
                result += Found(parsed.first, parsed.second, widen(text, start, end))
                i = end + 1
            } else {
                i = start + 1
            }
        }
        return result
    }

    /** Entfernt alle Text-Aufrufe (für den Verlauf, damit das Modell es nicht weiter nachmacht). */
    fun strip(text: String, known: Set<String>): String {
        var t = text
        find(text, known).forEach { t = t.replace(it.raw, "") }
        return t.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun parse(candidate: String, known: Set<String>): Pair<String, JSONObject>? {
        // org.json ist großzügig: einfache Anführungszeichen sind erlaubt
        val o = runCatching { JSONObject(candidate) }.getOrNull() ?: return null
        val name = listOf("action", "name", "tool", "tool_name", "function")
            .map { o.optString(it) }
            .firstOrNull { it in known } ?: return null
        val rawArgs: Any? = listOf("action_input", "arguments", "parameters", "args", "input")
            .firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it != JSONObject.NULL } }
        val args = when (rawArgs) {
            is JSONObject -> rawArgs
            is String -> runCatching { JSONObject(rawArgs) }.getOrNull()
                ?: JSONObject().apply { mainArg[name]?.let { put(it, rawArgs) } }
            null -> JSONObject()
            else -> JSONObject()
        }
        return name to args
    }

    /** Passende schließende Klammer – Klammern in Texten ("…") zählen nicht. */
    private fun matchingBrace(text: String, start: Int): Int {
        var depth = 0
        var quote: Char? = null
        var j = start
        while (j < text.length) {
            val c = text[j]
            if (quote != null) {
                if (c == '\\') j++ else if (c == quote) quote = null
            } else when (c) {
                '"' -> quote = c
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
            j++
        }
        return -1
    }

    /** Code-Zaun ```json … ``` drumherum gehört mit zum Text-Aufruf. */
    private fun widen(text: String, start: Int, end: Int): String {
        var s = start
        var e = end + 1
        val before = text.substring(0, s)
        val fence = Regex("```[a-zA-Z_]*\\s*$").find(before)
        if (fence != null) {
            val after = text.substring(e)
            val close = Regex("^\\s*```").find(after)
            if (close != null) {
                s = fence.range.first
                e += close.range.last + 1
            }
        }
        return text.substring(s, e)
    }
}
