package de.davscha.denkwerk.data

import org.json.JSONObject

/** Ein gespeicherter Prompt aus der Bibliothek – im Eingabefeld mit „/Titel“ abrufbar. */
data class SavedPrompt(
    val id: String = newId(),
    val title: String = "",
    val text: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = SavedPrompt(
            id = o.optString("id").ifEmpty { newId() },
            title = o.optString("title"),
            text = o.optString("text"),
        )

        /** Kurzer Befehlsname aus einem Text, z. B. "Fasse zusammen …" → "fasse-zusammen" */
        fun slug(text: String): String =
            text.lowercase().replace(Regex("[^a-zäöüß0-9]+"), "-").trim('-').split('-').take(3).joinToString("-").ifEmpty { "prompt" }

        val samples = listOf(
            SavedPrompt("p-zusammenfassen", "zusammenfassen", "Fasse den folgenden Text in 5 kurzen Stichpunkten zusammen:\n\n"),
            SavedPrompt("p-korrigieren", "korrigieren", "Korrigiere Rechtschreibung und Grammatik im folgenden Text. Gib nur den korrigierten Text aus:\n\n"),
            SavedPrompt("p-einfach", "einfach-erklaeren", "Erkläre mir das Folgende in einfachen Worten, ohne Formeln, mit einem Alltagsbeispiel:\n\n"),
            SavedPrompt("p-codereview", "code-review", "Prüfe den folgenden Code: Finde Fehler, schlage Verbesserungen vor und gib danach die verbesserte Fassung als Code-Block aus:\n\n"),
            SavedPrompt("p-englisch", "englisch", "Übersetze ins Englische (natürlich, nicht wörtlich):\n\n"),
            SavedPrompt("p-mail", "mail-freundlich", "Formuliere daraus eine kurze, freundliche E-Mail:\n\n"),
        )
    }
}
