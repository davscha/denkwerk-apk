package de.davscha.denkwerk.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import de.davscha.denkwerk.model.hasSharedStorage
import org.json.JSONObject
import java.io.File

/**
 * Speichert jeden Chat als eigene JSON-Datei im Ordner Chats, angehängte Bilder in Chats/Bilder.
 * Mit der Erlaubnis "Zugriff auf alle Dateien" liegt alles in /Denkwerk und übersteht Neuinstallationen.
 */
class ChatStore(private val context: Context) {

    private val sharedDir get() = File(Environment.getExternalStorageDirectory(), "Denkwerk")
    val dataDir: File get() = (if (hasSharedStorage()) sharedDir else context.filesDir).apply { mkdirs() }
    private val chatsDir get() = File(dataDir, "Chats").apply { mkdirs() }
    private val imagesDir get() = File(chatsDir, "Bilder").apply { mkdirs() }

    private fun file(id: String) = File(chatsDir, "chat-$id.json")

    /** Alle Chats, neueste zuerst. */
    fun loadAll(): List<Chat> =
        chatsDir.listFiles { f -> f.isFile && f.name.startsWith("chat-") && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { f -> runCatching { Chat.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun save(chat: Chat) {
        // Erst in eine Hilfsdatei schreiben, dann umbenennen – so geht bei einem Absturz nichts kaputt
        val tmp = File(chatsDir, "chat-${chat.id}.json.tmp")
        tmp.writeText(chat.toJson().toString(2))
        tmp.renameTo(file(chat.id))
    }

    fun delete(chat: Chat) {
        file(chat.id).delete()
        // Bilder, die nur zu diesem Chat gehörten, mit aufräumen
        chat.messages.flatMap { it.imagePaths }.forEach { path ->
            File(path).takeIf { it.parentFile == imagesDir }?.delete()
        }
    }

    /** Ein Bild als verkleinertes JPEG (max. 1024 px) in Chats/Bilder ablegen. Gibt den Pfad zurück. */
    fun importImage(uri: Uri): String? = runCatching {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val maxSide = 1024
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true,
            )
        } else bitmap
        val f = File(imagesDir, "bild-${System.currentTimeMillis()}.jpg")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        f.absolutePath
    }.getOrNull()

    /** Chat als Markdown-Text (zum Teilen). */
    fun toMarkdown(chat: Chat): String = buildString {
        appendLine("# ${chat.title}")
        appendLine()
        chat.messages.filter { !it.isError }.forEach { m ->
            appendLine(if (m.fromUser) "**Du:**" else "**Denkwerk** (${m.modelLabel}):")
            if (m.imagePaths.isNotEmpty()) appendLine("_[${m.imagePaths.size} Bild(er)]_")
            appendLine(m.text)
            appendLine()
        }
    }
}
