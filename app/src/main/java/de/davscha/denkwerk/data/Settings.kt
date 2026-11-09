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

    /** Kontextgröße für Ollama (Tokens). Mehr = längeres Gedächtnis, braucht mehr Grafikspeicher am PC. */
    var ollamaContext: Int
        get() = prefs.getInt("ollamaContext", 16384)
        set(v) = prefs.edit().putInt("ollamaContext", v).apply()

    /** Werkzeuge (Websuche, Seite lesen, Herunterladen …) bei Ollama-Modellen mit Tool-Unterstützung */
    var toolsEnabled: Boolean
        get() = prefs.getBoolean("toolsEnabled", true)
        set(v) = prefs.edit().putBoolean("toolsEnabled", v).apply()

    /** Eigene SearXNG-Suche, z. B. http://192.168.178.41:8888 – leer = DuckDuckGo */
    var searxngUrl: String
        get() = prefs.getString("searxngUrl", "") ?: ""
        set(v) = prefs.edit().putString("searxngUrl", v).apply()

    /** Gedächtnis: gemerkte Fakten gehen in jeden Chat mit */
    var memoryEnabled: Boolean
        get() = prefs.getBoolean("memoryEnabled", true)
        set(v) = prefs.edit().putBoolean("memoryEnabled", v).apply()

    /** Abgesicherte Suche für Web- und Bildersuche: 0 = Streng (Standard), 1 = Moderat, 2 = Aus */
    var safeSearch: Int
        get() = prefs.getInt("safeSearch", 0)
        set(v) = prefs.edit().putInt("safeSearch", v.coerceIn(0, 2)).apply()

    /** Kostenloser Schlüssel von saucenao.com (Anime/Illustrationen-Quellensuche), leer = aus */
    var sauceNaoKey: String
        get() = prefs.getString("sauceNaoKey", "") ?: ""
        set(v) = prefs.edit().putString("sauceNaoKey", v.trim()).apply()

    /** Videosuche: YouTube-Treffer zeigen (Standard an) */
    var videoShowYoutube: Boolean
        get() = prefs.getBoolean("videoShowYoutube", true)
        set(v) = prefs.edit().putBoolean("videoShowYoutube", v).apply()

    /** Videosuche: Treffer von TikTok, Instagram, Facebook, X, Reddit … zeigen (Standard an) */
    var videoShowSocial: Boolean
        get() = prefs.getBoolean("videoShowSocial", true)
        set(v) = prefs.edit().putBoolean("videoShowSocial", v).apply()

    /** Eigene Sperrliste für die Videosuche (Domains, durch Komma oder Zeilen getrennt) */
    var videoBlocked: String
        get() = prefs.getString("videoBlocked", "") ?: ""
        set(v) = prefs.edit().putString("videoBlocked", v).apply()

    /** Websuche vor der Antwort: 0 = Modell entscheidet, 1 = bei Fragen (Standard), 2 = immer */
    var webMode: Int
        get() = prefs.getInt("webMode", 1)
        set(v) = prefs.edit().putInt("webMode", v.coerceIn(0, 2)).apply()

    /** ComfyUI auf dem PC (Bilder malen) */
    var comfyHost: String
        get() = prefs.getString("comfyHost", "192.168.178.41") ?: ""
        set(v) = prefs.edit().putString("comfyHost", v.trim()).apply()

    var comfyPort: Int
        get() = prefs.getInt("comfyPort", 8080)
        set(v) = prefs.edit().putInt("comfyPort", v).apply()

    /** true = KI schmückt den Bildwunsch aus; false = nur wörtlich ins Englische übersetzen */
    var promptEnhance: Boolean
        get() = prefs.getBoolean("promptEnhance", true)
        set(v) = prefs.edit().putBoolean("promptEnhance", v).apply()

    /** Bildrezept als JSON (Checkpoint, LoRAs, Sampler) */
    var imageRecipe: ImageRecipe
        get() = ImageRecipe.fromJson(prefs.getString("imageRecipe", "") ?: "")
        set(v) = prefs.edit().putString("imageRecipe", v.toJson().toString()).apply()

    /** Denk-Modelle (qwen3 …): nachdenken lassen (gründlicher) oder nicht (schneller) */
    var thinking: Boolean
        get() = prefs.getBoolean("thinking", true)
        set(v) = prefs.edit().putBoolean("thinking", v).apply()

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
