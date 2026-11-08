package de.davscha.denkwerk.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(12)

/** Ein angehängtes Dokument – der ausgelesene Text liegt als .txt in Chats/Dokumente. */
data class DocAttachment(val name: String, val textPath: String, val chars: Int) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("textPath", textPath).put("chars", chars)

    companion object {
        fun fromJson(o: JSONObject) = DocAttachment(o.optString("name"), o.optString("textPath"), o.optInt("chars"))
    }
}

/** Ein Bild aus dem Web (oder lokal gespeichert) für Galerie und Slideshow. */
data class WebImage(
    val url: String,
    /** kleines Vorschaubild, leer = url */
    val thumb: String = "",
    val title: String = "",
    /** Seite, auf der das Bild steht */
    val source: String = "",
    /** true = url ist ein Dateipfad auf dem Gerät */
    val local: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("thumb", thumb).put("title", title).put("source", source).put("local", local)

    companion object {
        fun fromJson(o: JSONObject) = WebImage(
            o.optString("url"), o.optString("thumb"), o.optString("title"), o.optString("source"), o.optBoolean("local"),
        )
    }
}

/** Eine Quelle, auf die sich die Antwort stützt (als Kärtchen unter der Antwort) */
data class SourceLink(val title: String, val url: String) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("url", url)

    companion object {
        fun fromJson(o: JSONObject) = SourceLink(o.optString("title"), o.optString("url"))
    }
}

/** Ein gefundenes Video (Videosuche) */
data class WebVideo(
    val url: String,
    val title: String = "",
    val thumb: String = "",
    /** z. B. "12:34" */
    val duration: String = "",
    /** Kanal / Anbieter */
    val publisher: String = "",
    /** Datum, z. B. "2026-10-03" */
    val published: String = "",
    /** Quelle fürs Schild auf dem Kärtchen, z. B. "ZDF", "Archive", "Vimeo" */
    val source: String = "",
    /** Direkte Videodatei (.mp4) – wird im Video-Player geöffnet statt im Browser */
    val directUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("title", title).put("thumb", thumb)
        .put("duration", duration).put("publisher", publisher).put("published", published)
        .put("source", source).put("directUrl", directUrl)

    companion object {
        fun fromJson(o: JSONObject) = WebVideo(
            o.optString("url"), o.optString("title"), o.optString("thumb"),
            o.optString("duration"), o.optString("publisher"), o.optString("published"),
            o.optString("source"), o.optString("directUrl"),
        )
    }
}

/** Ein Werkzeug-Aufruf der KI (Websuche, Seite lesen …), als Kärtchen über der Antwort. */
data class ToolEvent(val name: String, val detail: String, val summary: String, val ok: Boolean = true, val running: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("detail", detail).put("summary", summary).put("ok", ok)

    companion object {
        fun fromJson(o: JSONObject) = ToolEvent(o.optString("name"), o.optString("detail"), o.optString("summary"), o.optBoolean("ok", true))
    }
}

/** Eine Fassung einer KI-Antwort – bei „Neu erzeugen“ bleiben alle Fassungen erhalten (‹ 1/3 ›). */
data class Variant(val text: String, val modelLabel: String, val tokensPerSec: Float, val isError: Boolean) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text).put("modelLabel", modelLabel).put("tokensPerSec", tokensPerSec.toDouble()).put("isError", isError)

    companion object {
        fun fromJson(o: JSONObject) = Variant(
            o.optString("text"), o.optString("modelLabel"), o.optDouble("tokensPerSec", 0.0).toFloat(), o.optBoolean("isError"),
        )
    }
}

/** Eine Nachricht im Chat – von dir oder von der KI. */
data class ChatMessage(
    val id: String = newId(),
    val fromUser: Boolean,
    val text: String,
    /** Angehängte Bilder (absolute Pfade), nur bei deinen Nachrichten */
    val imagePaths: List<String> = emptyList(),
    /** Bilder aus dem Web, die die KI gefunden hat (Galerie über der Antwort) */
    val webImages: List<WebImage> = emptyList(),
    /** Gelesene Webseiten, auf die sich die Antwort stützt */
    val sources: List<SourceLink> = emptyList(),
    /** Gefundene Videos (Video-Kärtchen über der Antwort) */
    val webVideos: List<WebVideo> = emptyList(),
    /** Werkzeuge, die die KI für diese Antwort benutzt hat */
    val toolEvents: List<ToolEvent> = emptyList(),
    /** Angehängte Dokumente (nur bei deinen Nachrichten) */
    val documents: List<DocAttachment> = emptyList(),
    /** Welches Modell geantwortet hat, z. B. "Gemma 4 E4B · Gerät" */
    val modelLabel: String = "",
    /** Geschwindigkeit der Antwort (ungefähr Wortstücke pro Sekunde) */
    val tokensPerSec: Float = 0f,
    /** true = statt einer Antwort kam ein Fehler */
    val isError: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** Alle Fassungen dieser Antwort (leer = nur eine); die angezeigte steht zusätzlich in text/modelLabel */
    val variants: List<Variant> = emptyList(),
    val variantIndex: Int = 0,
) {
    /** Die angezeigte Fassung als Variant */
    fun asVariant() = Variant(text, modelLabel, tokensPerSec, isError)

    /** Andere Fassung anzeigen */
    fun showVariant(index: Int): ChatMessage {
        val v = variants.getOrNull(index) ?: return this
        return copy(text = v.text, modelLabel = v.modelLabel, tokensPerSec = v.tokensPerSec, isError = v.isError, variantIndex = index)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("fromUser", fromUser)
        .put("text", text)
        .put("imagePaths", JSONArray(imagePaths))
        .put("documents", JSONArray(documents.map { it.toJson() }))
        .put("toolEvents", JSONArray(toolEvents.map { it.toJson() }))
        .put("webImages", JSONArray(webImages.map { it.toJson() }))
        .put("webVideos", JSONArray(webVideos.map { it.toJson() }))
        .put("sources", JSONArray(sources.map { it.toJson() }))
        .put("modelLabel", modelLabel)
        .put("tokensPerSec", tokensPerSec.toDouble())
        .put("isError", isError)
        .put("createdAt", createdAt)
        .put("variants", JSONArray(variants.map { it.toJson() }))
        .put("variantIndex", variantIndex)

    companion object {
        fun fromJson(o: JSONObject) = ChatMessage(
            id = o.optString("id").ifEmpty { newId() },
            fromUser = o.optBoolean("fromUser"),
            text = o.optString("text"),
            imagePaths = o.optJSONArray("imagePaths")?.let { a -> List(a.length()) { a.getString(it) } }
                // ältere Chats hatten nur ein Bild
                ?: listOfNotNull(o.optString("imagePath").ifEmpty { null }),
            sources = o.optJSONArray("sources")?.let { a -> List(a.length()) { SourceLink.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            webVideos = o.optJSONArray("webVideos")?.let { a -> List(a.length()) { WebVideo.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            webImages = o.optJSONArray("webImages")?.let { a -> List(a.length()) { WebImage.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            toolEvents = o.optJSONArray("toolEvents")?.let { a -> List(a.length()) { ToolEvent.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            documents = o.optJSONArray("documents")?.let { a -> List(a.length()) { DocAttachment.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            modelLabel = o.optString("modelLabel"),
            tokensPerSec = o.optDouble("tokensPerSec", 0.0).toFloat(),
            isError = o.optBoolean("isError"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            variants = o.optJSONArray("variants")?.let { a -> List(a.length()) { Variant.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            variantIndex = o.optInt("variantIndex", 0),
        )
    }
}

/** Ein ganzes Gespräch mit Titel. */
data class Chat(
    val id: String = newId(),
    val title: String = "Neuer Chat",
    val messages: List<ChatMessage> = emptyList(),
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Mit welchem Assistenten (Preset) der Chat läuft – null = Standard */
    val presetId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", "denkwerk-chat")
        .put("version", 1)
        .put("id", id)
        .put("title", title)
        .put("pinned", pinned)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("presetId", presetId ?: "")
        .put("messages", JSONArray(messages.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): Chat {
            val arr = o.optJSONArray("messages") ?: JSONArray()
            return Chat(
                id = o.optString("id").ifEmpty { newId() },
                title = o.optString("title").ifEmpty { "Neuer Chat" },
                messages = List(arr.length()) { ChatMessage.fromJson(arr.getJSONObject(it)) },
                pinned = o.optBoolean("pinned"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                presetId = o.optString("presetId").ifEmpty { null },
            )
        }

        /** Einfacher Titel aus der ersten Nachricht: die ersten Wörter, höchstens ~40 Zeichen. */
        fun titleFrom(text: String): String {
            val oneLine = text.replace(Regex("\\s+"), " ").trim()
            if (oneLine.isEmpty()) return "Bild-Chat"
            if (oneLine.length <= 40) return oneLine
            val cut = oneLine.take(40)
            return cut.substringBeforeLast(' ', cut).trimEnd(',', '.', ':', ';') + " …"
        }
    }
}
