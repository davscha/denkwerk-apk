package de.davscha.denkwerk.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(12)

/** Eine Nachricht im Chat – von dir oder von der KI. */
data class ChatMessage(
    val id: String = newId(),
    val fromUser: Boolean,
    val text: String,
    /** Angehängte Bilder (absolute Pfade), nur bei deinen Nachrichten */
    val imagePaths: List<String> = emptyList(),
    /** Welches Modell geantwortet hat, z. B. "Gemma 4 E4B · Gerät" */
    val modelLabel: String = "",
    /** Geschwindigkeit der Antwort (ungefähr Wortstücke pro Sekunde) */
    val tokensPerSec: Float = 0f,
    /** true = statt einer Antwort kam ein Fehler */
    val isError: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("fromUser", fromUser)
        .put("text", text)
        .put("imagePaths", JSONArray(imagePaths))
        .put("modelLabel", modelLabel)
        .put("tokensPerSec", tokensPerSec.toDouble())
        .put("isError", isError)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = ChatMessage(
            id = o.optString("id").ifEmpty { newId() },
            fromUser = o.optBoolean("fromUser"),
            text = o.optString("text"),
            imagePaths = o.optJSONArray("imagePaths")?.let { a -> List(a.length()) { a.getString(it) } }
                // ältere Chats hatten nur ein Bild
                ?: listOfNotNull(o.optString("imagePath").ifEmpty { null }),
            modelLabel = o.optString("modelLabel"),
            tokensPerSec = o.optDouble("tokensPerSec", 0.0).toFloat(),
            isError = o.optBoolean("isError"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("format", "denkwerk-chat")
        .put("version", 1)
        .put("id", id)
        .put("title", title)
        .put("pinned", pinned)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
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
