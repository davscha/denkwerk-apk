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
    /** Tag-Vorschläge beim Tippen (Danbooru-Liste) */
    val tagHelper: Boolean = false,
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
        .put("tagHelper", tagHelper)

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
            tagHelper = o.optBoolean("tagHelper", false),
        )

        /** Bild-Prompts im Danbooru-Tag-Stil – mit Tag-Liste und Werkzeug tags_pruefen */
        val promptSmith = Preset(
            id = "sample-prompt",
            name = "Prompt-Schmied",
            emoji = "🎨",
            description = "Bild-Prompts im Danbooru-Tag-Stil – aus Text oder Bild",
            systemPrompt = "Du schreibst Bild-Prompts für Stable Diffusion/ComfyUI im Danbooru-Tag-Stil: eine Kommaliste aus kurzen englischen Tags, " +
                "kein Fließtext. Reihenfolge: Personenzahl (1girl, 1boy, solo …), Aussehen (Haare, Augen, Körper), Kleidung, Ausdruck und Pose, " +
                "Gegenstände, Ort und Stimmung, Kamera/Bildaufbau. Hängt der Nutzer ein Bild an, beschreibe es als solche Tag-Liste. " +
                "Wenn das Werkzeug tags_pruefen verfügbar ist, prüfe deine Liste damit und übernimm die korrigierte Fassung; mit tags_suchen findest du passende echte Tags. " +
                "Gib den fertigen Prompt in einem Code-Block (```text) aus, damit er sich kopieren lässt, und erkläre danach in 1–3 Sätzen auf Deutsch, was du gewählt hast. " +
                "Auf Wunsch auch einen Negativ-Prompt als zweiten Code-Block.",
            temperature = 0.6f,
            starters = listOf(
                "Mach einen Prompt für: ",
                "Mach aus dem angehängten Bild eine Tag-Liste",
                "Prüfe und verbessere diesen Prompt: ",
            ),
            tagHelper = true,
        )

        /** Beispiel-Assistenten beim allerersten Start */
        val samples get() = listOf(promptSmith) + baseSamples

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
