package de.davscha.denkwerk.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ein eigener Assistent („GPT“): Name, Symbol, Anweisung an die KI, Temperatur,
 * optional ein Lieblingsmodell und Gesprächs-Starter für den Startbildschirm.
 */
data class Preset(
    val id: String = newId(),
    val name: String = "Neuer Assistent",
    val emoji: String = "✨",
    val description: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    /** "INTERNAL:gemma-4-e4b" oder "OLLAMA:qwen2.5vl:7b" – leer = aktuelles Modell behalten */
    val modelKey: String = "",
    val starters: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("emoji", emoji)
        .put("description", description)
        .put("systemPrompt", systemPrompt)
        .put("temperature", temperature.toDouble())
        .put("modelKey", modelKey)
        .put("starters", JSONArray(starters))

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            id = o.optString("id").ifEmpty { newId() },
            name = o.optString("name").ifEmpty { "Assistent" },
            emoji = o.optString("emoji").ifEmpty { "✨" },
            description = o.optString("description"),
            systemPrompt = o.optString("systemPrompt"),
            temperature = o.optDouble("temperature", 0.7).toFloat(),
            modelKey = o.optString("modelKey"),
            starters = o.optJSONArray("starters")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
        )

        /** Früherer Beispiel-Assistent (Danbooru-Tags) – wird beim Laden entfernt */
        const val OLD_PROMPT_SMITH_ID = "sample-prompt"

        /** Beispiel-Assistenten beim allerersten Start */
        val samples get() = baseSamples

        private val baseSamples = listOf(
            Preset(
                id = "sample-code",
                name = "Code-Helfer",
                emoji = "💻",
                description = "Kotlin, Python, JavaScript – lauffähiger Code zuerst",
                systemPrompt = "Du bist ein erfahrener Entwickler (Kotlin/Jetpack Compose, Python, JavaScript, PHP). " +
                    "Gib zuerst vollständigen, lauffähigen Code in einem Code-Block mit Sprachangabe, danach eine kurze Erklärung in Worten. " +
                    "Antworte auf Deutsch.",
                temperature = 0.3f,
                starters = listOf(
                    "Schreibe eine Compose-Liste mit Suchfeld",
                    "Erkläre diesen Fehler: ",
                    "Mach aus diesem Python-Skript eine Funktion: ",
                ),
            ),
            Preset(
                id = "sample-lehrer",
                name = "Erklärer",
                emoji = "🎓",
                description = "Erklärt alles in Worten – ohne Formeln",
                systemPrompt = "Du erklärst Themen verständlich in einfachen Worten mit Alltagsbeispielen. " +
                    "Verwende keine mathematischen Formeln oder Herleitungen. Wenn es um Programmierung geht, " +
                    "zeige zuerst ein kurzes lauffähiges Beispiel und erkläre es danach. Antworte auf Deutsch.",
                temperature = 0.5f,
                starters = listOf(
                    "Wie lernt ein neuronales Netz?",
                    "Was ist der Unterschied zwischen CPU und GPU?",
                    "Erkläre mir Transformer-Modelle",
                ),
            ),
            Preset(
                id = "sample-uebersetzer",
                name = "Übersetzer",
                emoji = "🌍",
                description = "Deutsch ⇄ Englisch, Spanisch, Französisch",
                systemPrompt = "Du bist ein Übersetzer. Ist der Text deutsch, übersetze ihn ins Englische; sonst ins Deutsche – " +
                    "außer der Nutzer nennt eine andere Zielsprache. Gib nur die Übersetzung aus, ohne Kommentar.",
                temperature = 0.2f,
                starters = listOf("Übersetze ins Englische: ", "Übersetze ins Spanische: "),
            ),
            Preset(
                id = "sample-ideen",
                name = "Ideen-Werkstatt",
                emoji = "💡",
                description = "Brainstorming für Apps, Spiele und Projekte",
                systemPrompt = "Du bist ein kreativer Ideengeber für Apps, Spiele und KI-Projekte. " +
                    "Liefere viele, konkrete und überraschende Ideen als nummerierte Liste mit je einem Satz Begründung. Antworte auf Deutsch.",
                temperature = 1.0f,
                starters = listOf("10 Ideen für eine Android-App mit KI", "Features für ein Toon-Fantasy-Spiel"),
            ),
        )
    }
}
