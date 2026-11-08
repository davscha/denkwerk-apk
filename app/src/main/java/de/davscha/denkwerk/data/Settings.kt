package de.davscha.denkwerk.data

import android.content.Context

/** Woher die Antworten kommen. */
enum class Source { INTERNAL, OLLAMA }

/** Alle Einstellungen, gespeichert in den SharedPreferences. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("denkwerk", Context.MODE_PRIVATE)

    var source: Source
        get() = runCatching { Source.valueOf(prefs.getString("source", null) ?: "") }.getOrDefault(Source.INTERNAL)
        set(v) = prefs.edit().putString("source", v.name).apply()

    /** id aus dem ModelCatalog (z. B. "gemma-4-e4b" oder "custom:datei.litertlm") */
    var internalModelId: String
        get() = prefs.getString("internalModelId", "gemma-4-e4b") ?: "gemma-4-e4b"
        set(v) = prefs.edit().putString("internalModelId", v).apply()

    var ollamaHost: String
        get() = prefs.getString("ollamaHost", "192.168.178.41") ?: ""
        set(v) = prefs.edit().putString("ollamaHost", v).apply()

    var ollamaPort: Int
        get() = prefs.getInt("ollamaPort", 11434)
        set(v) = prefs.edit().putInt("ollamaPort", v).apply()

    var ollamaModel: String
        get() = prefs.getString("ollamaModel", "") ?: ""
        set(v) = prefs.edit().putString("ollamaModel", v).apply()

    var systemPrompt: String
        get() = prefs.getString("systemPrompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(v) = prefs.edit().putString("systemPrompt", v).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.7f)
        set(v) = prefs.edit().putFloat("temperature", v).apply()

    var speechRate: Float
        get() = prefs.getFloat("speechRate", 1.0f)
        set(v) = prefs.edit().putFloat("speechRate", v).apply()

    var speechPitch: Float
        get() = prefs.getFloat("speechPitch", 1.0f)
        set(v) = prefs.edit().putFloat("speechPitch", v).apply()

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Du bist Denkwerk, ein hilfsbereiter KI-Assistent. Antworte in der Sprache des Nutzers, " +
                "klar und gut gegliedert. Nutze Markdown (Überschriften, Listen, Code-Blöcke mit Sprachangabe), " +
                "wenn es die Antwort verständlicher macht."
    }
}
