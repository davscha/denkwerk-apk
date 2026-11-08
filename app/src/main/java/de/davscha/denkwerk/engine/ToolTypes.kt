package de.davscha.denkwerk.engine

import org.json.JSONObject

/** Ergebnis eines Werkzeugs: Text fürs Modell, Kurzfassung für dich, evtl. gespeicherte Dateien. */
data class ToolOutput(
    /** Was das Modell als Ergebnis bekommt */
    val forModel: String,
    /** Kurze Zeile fürs Kärtchen im Chat, z. B. "6 Treffer" */
    val summary: String,
    val ok: Boolean = true,
    /** Heruntergeladene Bilder (werden im Chat gezeigt) */
    val images: List<String> = emptyList(),
    /** Gefundene Bilder aus dem Web (Galerie/Slideshow im Chat) */
    val webImages: List<de.davscha.denkwerk.data.WebImage> = emptyList(),
)

/** Was beim Antworten mit Werkzeugen passiert – Text oder ein Werkzeug-Aufruf. */
sealed interface ToolStreamEvent {
    data class Text(val text: String) : ToolStreamEvent
    data class ToolStart(val name: String, val args: JSONObject) : ToolStreamEvent
    data class ToolDone(val name: String, val output: ToolOutput) : ToolStreamEvent
}
